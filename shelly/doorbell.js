// doorbell.js — Klingelerkennung fuer Shelly Plug M Gen3 (und kompatible Gen2+/Gen3-Geraete).
//
// Das Script laeuft dauerhaft auf dem Shelly (Name muss "doorbell" sein, Autostart an):
//  - Erkennt Klingeln ueber die Wirkleistung des Klingeltrafos (switch:0 / apower).
//  - Broadcastet bei Klingeln Shelly.emitEvent("doorbell", {ts, power}) an alle
//    verbundenen WebSocket-Clients (die Android-Apps).
//  - Fuehrt einen Ringpuffer der letzten Klingel-Zeitstempel im KVS (dbell_log_*),
//    damit Apps nach einem Reconnect verpasste Ereignisse nachladen koennen.
//  - Gleicht nach dem Boot den Schalterzustand mit den DND-Schedules ab
//    (Ruhezeiten schalten den Trafo stromlos; die Schedules verwaltet die App,
//    sie sind aber auch in der normalen Shelly-App sichtbar/aenderbar).
//
// KVS-Schema (alles fuer die Apps les-/schreibbar, kein Passwort):
//   dbell_cfg_threshold_w  Watt-Schwelle fuer "es klingelt"          (Default 2.0)
//   dbell_cfg_debounce_s   Sperrzeit nach einem Klingeln in Sekunden (Default 30)
//   dbell_dnd_ids          Ruhezeiten als JSON-Liste von Schedule-Job-Paaren
//                          "[[ausId,einId],...]" (aus = DND-Beginn, ein = DND-Ende)
//   dbell_log_head         Index des aktuellen Log-Chunks (0..9)
//   dbell_log_0 .. _9      Log-Chunks: JSON-Array von Unix-Timestamps als String
//
// Nach Konfig-Aenderungen rufen die Apps per RPC  Script.Eval {code:"cfgChanged()"}
// auf; das Script laedt die Konfig neu und broadcastet "doorbell_cfg", damit alle
// anderen Apps ihre Anzeige aktualisieren.
//
// Hinweis mJS: bewusst ohne try/catch, split(), parseInt() etc. — die Shelly-
// Script-Engine unterstuetzt nur eine JS-Teilmenge.

let DEF_THRESHOLD_W = 2.0;
let DEF_DEBOUNCE_S = 30;
let LOG_CHUNKS = 10;    // dbell_log_0 .. dbell_log_9 (KVS: max. 50 Keys gesamt!)
let LOG_PER_CHUNK = 20; // 20 Timestamps ~ 221 Zeichen JSON, KVS-Limit ist 253

let cfg = {
  thr: DEF_THRESHOLD_W,
  deb: DEF_DEBOUNCE_S,
  dnd: [] // Ruhezeiten: Array von Schedule-Job-Paaren [ausId, einId]
};

let lastRingMs = 0;
let logHead = 0;   // aktueller Chunk-Index
let logChunk = []; // Inhalt des aktuellen Chunks (Unix-Sekunden)

// ---------- kleine Helfer (mJS hat kein String.split/parseInt) ----------

function splitStr(s, sep) {
  let parts = [];
  let i = s.indexOf(sep);
  while (i >= 0) {
    parts.push(s.slice(0, i));
    s = s.slice(i + sep.length);
    i = s.indexOf(sep);
  }
  parts.push(s);
  return parts;
}

// Nur Dezimalziffern -> Zahl, sonst null (JSON.parse scheitert an "08").
function parseDec(s) {
  if (typeof s !== "string" || s.length === 0) return null;
  let n = 0;
  for (let i = 0; i < s.length; i++) {
    let c = s.charCodeAt(i);
    if (c < 48 || c > 57) return null;
    n = n * 10 + (c - 48);
  }
  return n;
}

// KVS-Werte koennen als Zahl oder String ankommen ("2.5", "30", 30, ...).
function toNum(v, dflt) {
  if (typeof v === "number") return v;
  if (typeof v !== "string") return dflt;
  let s = v;
  let neg = false;
  if (s.length > 0 && s.slice(0, 1) === "-") {
    neg = true;
    s = s.slice(1);
  }
  let p = splitStr(s, ".");
  if (p.length > 2) return dflt;
  let whole = parseDec(p[0]);
  if (whole === null) return dflt;
  let n = whole;
  if (p.length === 2 && p[1].length > 0) {
    let frac = parseDec(p[1]);
    if (frac === null) return dflt;
    let scale = 1;
    for (let i = 0; i < p[1].length; i++) scale = scale * 10;
    n = n + frac / scale;
  }
  return neg ? -n : n;
}

// ---------- Konfiguration aus dem KVS ----------

function loadCfg(cb) {
  Shelly.call("KVS.GetMany", { match: "dbell_cfg_*,dbell_dnd_*" }, function (res) {
    let items = {};
    if (res && res.items) {
      if (res.items.length !== undefined) {
        // Array-Form (neuere Firmware): [{key, etag, value}, ...]
        for (let i = 0; i < res.items.length; i++) items[res.items[i].key] = res.items[i].value;
      } else {
        // Objekt-Form: { key: {etag, value}, ... }
        for (let k in res.items) items[k] = res.items[k].value;
      }
    }
    cfg.thr = toNum(items.dbell_cfg_threshold_w, DEF_THRESHOLD_W);
    cfg.deb = toNum(items.dbell_cfg_debounce_s, DEF_DEBOUNCE_S);
    cfg.dnd = [];
    if (typeof items.dbell_dnd_ids === "string") {
      // Shellys JSON.parse wirft nicht, sondern liefert undefined bei Muell
      let a = JSON.parse(items.dbell_dnd_ids);
      if (a && a.length !== undefined) cfg.dnd = a;
    }
    // Defaults einmalig ablegen, damit die Apps sie vorfinden — aber nur, wenn
    // die Abfrage wirklich geklappt hat (sonst wuerden echte Werte ueberschrieben).
    if (res) {
      if (items.dbell_cfg_threshold_w === undefined)
        Shelly.call("KVS.Set", { key: "dbell_cfg_threshold_w", value: cfg.thr });
      if (items.dbell_cfg_debounce_s === undefined)
        Shelly.call("KVS.Set", { key: "dbell_cfg_debounce_s", value: cfg.deb });
    }
    if (cb) cb();
  });
}

// Wird von den Apps via Script.Eval aufgerufen, nachdem sie KVS/Schedules
// geaendert haben: Konfig neu laden und alle Apps informieren.
function cfgChanged() {
  loadCfg(function () {
    Shelly.emitEvent("doorbell_cfg", null);
  });
  return true;
}

// ---------- Klingel-Log (Ringpuffer im KVS) ----------

let logReady = false;   // erst schreiben, wenn der aktuelle Chunk geladen ist
let logPending = [];    // Klingeln waehrend des Ladens -> nachtragen

function initLog() {
  Shelly.call("KVS.Get", { key: "dbell_log_head" }, function (res, ec) {
    if (ec === 0 && res) logHead = toNum(res.value, 0);
    if (logHead < 0 || logHead >= LOG_CHUNKS) logHead = 0;
    Shelly.call("KVS.Get", { key: "dbell_log_" + JSON.stringify(logHead) }, function (r2, e2) {
      if (e2 === 0 && r2 && typeof r2.value === "string") {
        let arr = JSON.parse(r2.value);
        if (arr && arr.length !== undefined) logChunk = arr;
      }
      logReady = true;
      for (let i = 0; i < logPending.length; i++) writeLog(logPending[i]);
      logPending = [];
    });
  });
}

function appendLog(ts) {
  if (!logReady) {
    logPending.push(ts);
    return;
  }
  writeLog(ts);
}

function writeLog(ts) {
  if (logChunk.length >= LOG_PER_CHUNK) {
    logHead = (logHead + 1) % LOG_CHUNKS;
    logChunk = [];
    Shelly.call("KVS.Set", { key: "dbell_log_head", value: logHead });
  }
  logChunk.push(ts);
  Shelly.call("KVS.Set", {
    key: "dbell_log_" + JSON.stringify(logHead),
    value: JSON.stringify(logChunk)
  });
}

// ---------- Klingelerkennung ----------

function onRing(power) {
  let now = Date.now();
  let ts = Math.floor(now / 1000);
  print("doorbell: Klingeln erkannt (", power, "W )");
  // ts < ~2001 bedeutet: keine NTP-Zeit; die App ersetzt das durch die Handy-Zeit.
  Shelly.emitEvent("doorbell", { ts: ts, power: power });
  appendLog(ts);
}

// Zum Testen ohne Klingelknopf: in der Script-Konsole "testRing()" aufrufen
// (oder per RPC: Script.Eval {id:<id>, code:"testRing()"}).
function testRing() {
  lastRingMs = Date.now();
  onRing(0);
  return true;
}

Shelly.addStatusHandler(function (st) {
  if (st.component !== "switch:0" || !st.delta) return;
  let p = st.delta.apower;
  if (typeof p !== "number" || p < cfg.thr) return;
  let now = Date.now();
  if (now - lastRingMs < cfg.deb * 1000) return;
  lastRingMs = now;
  onRing(p);
});

// ---------- DND-Abgleich nach dem Boot ----------
//
// Jede Ruhezeit liegt in zwei normalen Shelly-Schedules (aus/ein). Faellt der
// Strom waehrend einer Schaltflanke aus, stellt dieser Abgleich nach dem Boot
// den erwarteten Zustand her: innerhalb irgendeiner Ruhezeit aus, sonst an.

// Timespec "ss mm hh dom mon dow" -> {min, days[0..6]} (Tage wie cron: 0=Sonntag).
// Es werden nur numerische Tageslisten/-bereiche unterstuetzt (die App und die
// Shelly-App schreiben Zahlen); sonst null -> Abgleich unterbleibt.
function parseTimespec(spec) {
  if (typeof spec !== "string") return null;
  let f = [];
  let raw = splitStr(spec, " ");
  for (let i = 0; i < raw.length; i++) if (raw[i].length > 0) f.push(raw[i]);
  if (f.length < 5) return null;
  let mm = f.length === 5 ? f[0] : f[1];
  let hh = f.length === 5 ? f[1] : f[2];
  let dow = f.length === 5 ? f[4] : f[5];
  let m = toNum(mm, -1);
  let h = toNum(hh, -1);
  if (m < 0 || m > 59 || h < 0 || h > 23) return null;
  let days = [false, false, false, false, false, false, false];
  if (dow === "*") {
    for (let d = 0; d < 7; d++) days[d] = true;
  } else {
    let parts = splitStr(dow, ",");
    for (let i = 0; i < parts.length; i++) {
      let r = splitStr(parts[i], "-");
      if (r.length === 2) {
        let a = parseDec(r[0]);
        let b = parseDec(r[1]);
        if (a === null || b === null || a > 7 || b > 7) return null;
        a = a % 7;
        b = b % 7;
        let d = a;
        while (true) {
          days[d] = true;
          if (d === b) break;
          d = (d + 1) % 7;
        }
      } else {
        let a = parseDec(parts[i]);
        if (a === null || a > 7) return null;
        days[a % 7] = true;
      }
    }
  }
  return { min: h * 60 + m, days: days };
}

// Lokale Uhrzeit + Wochentag aus dem Sys-Status ableiten (unixtime ist UTC,
// sys.time ist lokal "HH:MM"; daraus ergibt sich der Zeitzonen-Offset).
function computeNowLocal() {
  let sys = Shelly.getComponentStatus("sys");
  if (!sys || !sys.unixtime || typeof sys.time !== "string") return null;
  let hm = splitStr(sys.time, ":");
  if (hm.length < 2) return null;
  let h = parseDec(hm[0]);
  let m = parseDec(hm[1]);
  if (h === null || m === null) return null;
  let lm = h * 60 + m;
  let um = Math.floor(sys.unixtime / 60) % 1440;
  let diff = lm - um;
  // Offset-Fenster (-11h, +13h]: deckt alle gaengigen Zeitzonen ab
  // (nur UTC+14/-12 waeren nicht eindeutig aufloesbar).
  if (diff > 780) diff = diff - 1440;
  if (diff <= -660) diff = diff + 1440;
  let ldays = Math.floor((sys.unixtime + diff * 60) / 86400);
  return { min: lm, dow: (ldays + 4) % 7 }; // 1.1.1970 war ein Donnerstag; 0=Sonntag
}

function setBell(on) {
  let sw = Shelly.getComponentStatus("switch:0");
  if (sw && sw.output === on) return;
  print("doorbell: DND-Abgleich, setze Klingel ", on ? "EIN" : "AUS");
  Shelly.call("Switch.Set", { id: 0, on: on });
}

function reconcileDnd() {
  // Ohne Ruhezeiten gilt: Klingel gehoert an.
  if (cfg.dnd.length === 0) {
    setBell(true);
    return;
  }
  Shelly.call("Schedule.List", {}, function (res) {
    let jobs = (res && res.jobs) ? res.jobs : [];
    let now = computeNowLocal();
    if (!now) {
      print("doorbell: keine Uhrzeit verfuegbar, lasse Zustand unveraendert");
      return;
    }
    let inside = false;  // mindestens eine Ruhezeit laeuft gerade
    let unknown = false; // mindestens eine Ruhezeit war nicht auswertbar
    for (let i = 0; i < cfg.dnd.length; i++) {
      let pair = cfg.dnd[i];
      if (!pair || pair.length !== 2) continue;
      let off = null;
      let on = null;
      for (let j = 0; j < jobs.length; j++) {
        if (jobs[j].id === pair[0]) off = jobs[j];
        if (jobs[j].id === pair[1]) on = jobs[j];
      }
      // Fehlende/deaktivierte Jobs schalten nichts -> zaehlen nicht als Ruhezeit
      if (!off || !on || !off.enable || !on.enable) continue;
      let o = parseTimespec(off.timespec);
      let e = parseTimespec(on.timespec);
      if (!o || !e) {
        unknown = true;
        continue;
      }
      let ins = false;
      if (e.min > o.min) {
        // Fenster am selben Tag
        ins = o.days[now.dow] && now.min >= o.min && now.min < e.min;
      } else {
        // Fenster ueber Mitternacht: heute begonnen ODER gestern begonnen
        let prev = (now.dow + 6) % 7;
        ins = (o.days[now.dow] && now.min >= o.min) || (o.days[prev] && now.min < e.min);
      }
      if (ins) inside = true;
    }
    if (inside) setBell(false);
    else if (!unknown) setBell(true);
    else print("doorbell: DND-Zeitplan nicht auswertbar, lasse Zustand unveraendert");
  });
}

// ---------- Start ----------

loadCfg(function () {
  reconcileDnd();
});
initLog();
print("doorbell: Script gestartet (Schwelle ", cfg.thr, "W, Sperrzeit ", cfg.deb, "s )");
