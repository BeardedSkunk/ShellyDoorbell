package de.beardedskunk.shellydoorbell.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.beardedskunk.shellydoorbell.AlarmActivity
import de.beardedskunk.shellydoorbell.Channels
import de.beardedskunk.shellydoorbell.MainActivity
import de.beardedskunk.shellydoorbell.R
import de.beardedskunk.shellydoorbell.data.AppDb
import de.beardedskunk.shellydoorbell.data.Prefs
import de.beardedskunk.shellydoorbell.data.RingDao
import de.beardedskunk.shellydoorbell.data.RingEvent
import de.beardedskunk.shellydoorbell.shelly.ConnectionState
import de.beardedskunk.shellydoorbell.shelly.BellEntry
import de.beardedskunk.shellydoorbell.shelly.BellTimes
import de.beardedskunk.shellydoorbell.shelly.BellWindow
import de.beardedskunk.shellydoorbell.shelly.SharedSettings
import de.beardedskunk.shellydoorbell.shelly.ShellyClient
import de.beardedskunk.shellydoorbell.shelly.ShellyRpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address

/**
 * Ergebnis der aktiven Verbindungsprüfung (siehe [DoorbellService.checkConnection]):
 * eine Gesamtaussage — Shelly erreichbar, Passwort ok UND doorbell-Script läuft.
 * [detail] erklärt im Fehlerfall, woran es hängt.
 */
data class ConnCheck(val ok: Boolean, val detail: String?)

/**
 * Der "Lausch-Service": haelt als Foreground-Service (Typ specialUse) dauerhaft
 * die WebSocket-Verbindung zum Shelly, loest bei "doorbell"-Events den Alarm aus
 * und stellt der UI alle Zustaende als Flows bereit.
 */
class DoorbellService : Service() {

    inner class LocalBinder : Binder() {
        val service: DoorbellService get() = this@DoorbellService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var prefs: Prefs
    private lateinit var dao: RingDao
    private lateinit var alarm: AlarmController
    private lateinit var client: ShellyClient
    private lateinit var wifiGate: WifiGate

    private val ip = MutableStateFlow("")
    private val password = MutableStateFlow("")
    private val wifi = MutableStateFlow<Network?>(null)
    private val uiVisible = MutableStateFlow(false)
    private var alarmUri: String? = null
    private var localAlarmEnabled = true

    private val _watts = MutableStateFlow<Double?>(null)
    val watts: StateFlow<Double?> = _watts

    private val _bellOn = MutableStateFlow<Boolean?>(null)
    val bellOn: StateFlow<Boolean?> = _bellOn

    private val _shared = MutableStateFlow<SharedSettings?>(null)
    val shared: StateFlow<SharedSettings?> = _shared

    /** null = noch nicht geladen; sonst die Liste der Klingelzeiten auf dem Shelly. */
    private val _bellTimes = MutableStateFlow<List<BellEntry>?>(null)
    val bellTimes: StateFlow<List<BellEntry>?> = _bellTimes

    /** "Ruhe bis": Unix-Sekunden, null = keine temporaere Stummschaltung. */
    private val _muteUntil = MutableStateFlow<Long?>(null)
    val muteUntil: StateFlow<Long?> = _muteUntil

    /** null = unbekannt, false = doorbell-Script fehlt/gestoppt auf dem Shelly. */
    private val _scriptOk = MutableStateFlow<Boolean?>(null)
    val scriptOk: StateFlow<Boolean?> = _scriptOk

    /** null = unbekannt; sonst die vom Script per Heartbeat gemeldete Version. */
    private val _scriptVersion = MutableStateFlow<Int?>(null)
    val scriptVersion: StateFlow<Int?> = _scriptVersion

    /** true = Lausch-Betrieb (kein Passwort, keine schreibenden Aufrufe). */
    private val listenOnly = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    val connectionState: StateFlow<ConnectionState> get() = client.state
    val alarmActive: StateFlow<Boolean> get() = alarm.active

    private var scriptId: Int? = null
    private var lastAlarmAtMs = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val refreshMutex = Mutex()

    /** Verhindert, dass onConnected und „Verbindung pruefen" das Script parallel einspielen. */
    private val scriptMutex = Mutex()

    /** Zeitpunkt (elapsedRealtime) des letzten Script-Heartbeats, 0 = keiner. */
    @Volatile
    private var lastHeartbeatMs = 0L

    /** Schuetzt den Aufbau der vorlaeufigen Klingel-Gruppe (recordProvisional). */
    private val logMutex = Mutex()
    private var provStart = 0L   // Start des offenen vorlaeufigen Ereignisses, 0 = keins
    private var provCount = 0
    private var provLastTs = 0L

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        dao = AppDb.get(this).ringDao()
        alarm = AlarmController(this, scope)
        wifiGate = WifiGate(prefs, scope)
        runBlocking { wifiGate.load() }
        // Netzwerk-Tor: entscheidet vor jedem Verbindungsversuch (Subnetz / SSID-Listen).
        client = ShellyClient(scope, ip, wifi, password) { ipStr, forced -> wifiGate.decide(ipStr, forced) }

        // Ohne lokalen Alarm laeuft der Dienst nur fuer die sichtbare UI mit —
        // dann ohne Dauer-Notification (und er beendet sich, wenn die UI zugeht).
        val initial = runBlocking { prefs.settings.first() }
        Log.i(TAG, "onCreate: ip=${initial.ip}, lauschmodus=${initial.listenOnly}, lokalerAlarm=${initial.alarmEnabled}")
        ip.value = initial.ip
        password.value = initial.password
        alarmUri = initial.alarmUri
        localAlarmEnabled = initial.alarmEnabled
        listenOnly.value = initial.listenOnly
        if (localAlarmEnabled) startForegroundCompat()
        requestWifi()
        client.start()

        scope.launch {
            prefs.settings.collect {
                ip.value = it.ip
                password.value = it.password
                alarmUri = it.alarmUri
                val listenChanged = listenOnly.value != it.listenOnly
                listenOnly.value = it.listenOnly
                setLocalAlarmEnabled(it.alarmEnabled)
                // Beim Umschalten des Lauschmodus die Verbindung neu bewerten:
                // eingeschaltet -> keine Auth-Aufrufe mehr; ausgeschaltet ->
                // Einstellungen/Script wieder abgleichen.
                if (listenChanged && client.state.value is ConnectionState.Connected) {
                    onConnected()
                }
            }
        }
        scope.launch {
            client.state.collect {
                val label = when (it) {
                    is ConnectionState.Connected -> "verbunden (${it.deviceName})"
                    ConnectionState.Connecting -> "verbinde"
                    ConnectionState.NoWifi -> "kein WLAN"
                    is ConnectionState.OtherNetwork -> "anderes Netz (${it.detail})"
                }
                Log.d(TAG, "Verbindungszustand: $label")
                updateServiceNotification(it)
            }
        }
        scope.launch {
            client.connectedEvents.collect {
                // Erreichbar in diesem WLAN -> SSID whitelisten, dann Daten laden.
                wifiGate.onConnected()
                onConnected()
            }
        }
        scope.launch { client.notifications.collect { handleNotification(it) } }
        scope.launch {
            // Verbindungsverlust setzt das Heartbeat-Fenster zurueck, damit ein
            // frischer Connect erst wieder auf ein Lebenszeichen wartet.
            client.state.collect { st -> if (st !is ConnectionState.Connected) lastHeartbeatMs = 0L }
        }
        scope.launch {
            // Bleibt das 30-s-Lebenszeichen des Scripts aus, obwohl die Verbindung
            // steht, gilt das Script als nicht laufend.
            while (true) {
                delay(30_000)
                val last = lastHeartbeatMs
                if (last != 0L &&
                    client.state.value is ConnectionState.Connected &&
                    SystemClock.elapsedRealtime() - last > STALE_MS
                ) {
                    _scriptOk.value = false
                }
            }
        }
        scope.launch {
            // Live-Watt nur pollen, solange die App sichtbar ist — und nicht im
            // Lauschmodus (Switch.GetStatus wuerde ohne Passwort 401 spammen;
            // Watt/Schalterzustand kommen dort ohnehin per NotifyStatus). Live-
            // Aenderungen (Klingeln, Schalten) pusht der Shelly per NotifyStatus,
            // deshalb reicht ein gemaechlicher Poll fuer die reine Anzeige.
            combine(uiVisible, client.state, listenOnly) { visible, st, listen ->
                visible && !listen && st is ConnectionState.Connected
            }
                .distinctUntilChanged()
                .collectLatest { pollingActive ->
                    if (!pollingActive) return@collectLatest
                    while (true) {
                        // Waehrend einer 429-Sperre NICHT pollen – sonst haelt der
                        // Poll die Shelly-Sperre am Leben und sie laeuft nie ab.
                        val cooldown = client.rateLimitedForMs()
                        if (cooldown > 0) {
                            delay(cooldown.coerceAtMost(5_000))
                            continue
                        }
                        runCatching { pollStatus() }.onFailure {
                            if (it is ShellyRpcException && it.code == 429) {
                                Log.w(TAG, "pollStatus: 429 – Poll pausiert bis Sperre ablaeuft")
                            }
                        }
                        // abgelaufene "Ruhe bis" aus der Anzeige nehmen (das
                        // Script auf dem Shelly schaltet den Trafo selbst zurueck)
                        _muteUntil.value = _muteUntil.value
                            ?.takeIf { it > System.currentTimeMillis() / 1000 }
                        delay(POLL_INTERVAL_MS)
                    }
                }
        }
        scope.launch {
            // Nach Ablauf einer 429-Sperre einmal frisch laden: onConnected kann
            // waehrend der Sperre gescheitert sein, dann blieben Klingelzeiten und
            // Script-Status leer, obwohl die Verbindung steht.
            client.rateLimited.collect { limited ->
                if (!limited && !listenOnly.value &&
                    client.state.value is ConnectionState.Connected
                ) {
                    Log.i(TAG, "429-Sperre vorbei – Einstellungen erneut laden")
                    runCatching { onConnected() }
                }
            }
        }
        scope.launch {
            // Auth hat sich nach einem Fehlschlag automatisch zurueckgesetzt
            // (statt Passwort-Popup) -> Daten erneut laden.
            client.needsReload.collect {
                if (!listenOnly.value && client.state.value is ConnectionState.Connected) {
                    Log.i(TAG, "Auth-Auto-Reset – Einstellungen erneut laden")
                    runCatching { onConnected() }
                }
            }
        }
        scope.launch { alarm.active.collect { if (!it) cancelRingNotification() } }
        scope.launch {
            // lokale History auf ~1 Jahr begrenzen
            dao.prune(System.currentTimeMillis() / 1000 - PRUNE_AFTER_S)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALARM) stopAlarm()
        // Nur der Dauer-Lauscher soll nach einem System-Kill wiederbelebt werden
        return if (localAlarmEnabled) START_STICKY else START_NOT_STICKY
    }

    private fun setLocalAlarmEnabled(enabled: Boolean) {
        if (enabled == localAlarmEnabled) return
        localAlarmEnabled = enabled
        if (enabled) {
            // Sicherstellen, dass der Dienst "gestartet" ist (nicht nur gebunden),
            // sonst endet er beim naechsten Unbind trotz Foreground.
            // Die Foreground-Notification direkt mit dem aktuellen Verbindungs-
            // status posten – sonst bliebe sie auf "verbinde" haengen, wenn der
            // Zustand danach nicht mehr wechselt (Verbindung stand schon).
            ContextCompat.startForegroundService(this, Intent(this, DoorbellService::class.java))
            startForegroundCompat(serviceText(client.state.value))
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!uiVisible.value) stopSelf()
        }
    }

    override fun onDestroy() {
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        alarm.stop()
        // Anstaendig vom Shelly abmelden (Verbindungs-Slot sofort frei), dann alle
        // Coroutinen beenden.
        runCatching { client.close() }
        scope.cancel()
        // Alarm-Aus gilt nur fuer die laufende Sitzung: wurde der Dienst im
        // Alarm-Aus-Zustand beendet (Nutzer hat den Alarm ausgeschaltet und die App
        // verlassen), fuer den naechsten Start wieder scharf schalten. NACH
        // scope.cancel(), damit der (nun tote) Settings-Collector nicht mehr auf die
        // Aenderung reagiert und den Dienst faelschlich wieder in den Vordergrund holt.
        if (!localAlarmEnabled) {
            Log.i(TAG, "Herunterfahren mit Alarm-Aus – fuer naechsten Start wieder aktivieren")
            runCatching { runBlocking { prefs.setAlarmEnabled(true) } }
        }
        super.onDestroy()
    }

    // ---------- Netzwerk (nur WLAN, nie Mobilfunk) ----------

    private fun requestWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Letzter bekannter Stand des aktuellen WLANs -> ans WifiGate.
            private var ssid: String? = null
            private var ipv4: ByteArray? = null
            private var prefix = 0

            override fun onAvailable(network: Network) {
                wifi.value = network
                client.reconnectNow()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                ssid = readSsid(caps)
                wifiGate.onNetwork(ssid, ipv4, prefix)
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
                ipv4 = (la?.address as? Inet4Address)?.address
                prefix = la?.prefixLength ?: 0
                wifiGate.onNetwork(ssid, ipv4, prefix)
            }

            override fun onLost(network: Network) {
                if (wifi.value == network) wifi.value = null
            }
        }
        networkCallback = callback
        // requestNetwork (statt registerNetworkCallback) haelt das WLAN aktiv,
        // auch wenn das System sonst auf Mobilfunk wechseln wuerde.
        cm.requestNetwork(request, callback)
    }

    /** SSID aus den Netzwerk-Capabilities; null ohne Berechtigung/Info. Der Name
     *  kommt in Anfuehrungszeichen bzw. als "<unknown ssid>". */
    private fun readSsid(caps: NetworkCapabilities): String? {
        val info = caps.transportInfo as? WifiInfo ?: return null
        val raw = info.ssid ?: return null
        if (raw == WifiManager.UNKNOWN_SSID) return null
        return raw.trim('"').takeIf { it.isNotBlank() }
    }

    // ---------- Verbindungsaufbau / Settings-Abgleich ----------

    private suspend fun onConnected() {
        // Lauschmodus: keine authentifizierten Aufrufe (wuerden 401 liefern).
        // Klingel-Events, Live-Status und der Script-Heartbeat kommen als
        // Broadcast ganz ohne Passwort an.
        if (listenOnly.value) {
            Log.d(TAG, "onConnected: Lauschmodus – keine Auth-Aufrufe")
            return
        }
        // Laeuft gerade eine 429-Sperre, jetzt nichts senden – der rateLimited-
        // Watcher ruft onConnected nach Ablauf der Sperre erneut auf.
        val cooldown = client.rateLimitedForMs()
        if (cooldown > 0) {
            Log.w(TAG, "onConnected uebersprungen: 429-Sperre noch ${cooldown / 1000}s")
            return
        }
        Log.d(TAG, "onConnected: Script pruefen + Einstellungen laden")
        // Erster authentifizierter Zugriff. Scheitert er am Passwort, NICHT mit
        // refreshSettings weitermachen – das wären nur weitere 401 in Folge.
        val scriptResult = runCatching { ensureScript() }
        scriptResult.exceptionOrNull()?.let { e ->
            if (e is ShellyRpcException && e.isAuth) {
                // Ohne lokal eingetragenes Passwort ist das ein reines Lausch-
                // Geraet: Klingel-Events kommen auch ohne Auth an, also nicht
                // bei jedem Verbinden den Passwort-Dialog aufdraengen. Loest
                // der Nutzer selbst ein Kommando aus, fragt emitFailure weiter.
                if (password.value.isBlank()) {
                    _messages.tryEmit(
                        "Shelly ist passwortgeschützt. Der Klingel-Alarm läuft auch ohne " +
                            "Passwort – für Schalten/Einstellungen das Passwort eintragen oder " +
                            "in den Einstellungen den Lauschmodus aktivieren."
                    )
                } else {
                    // Kein Popup mehr: ein Auth-Fehler beim Verbinden ist meist transient
                    // (Busy-/429-Phase). ShellyClient setzt die Auth automatisch zurueck
                    // und meldet sich per needsReload wieder – dann laden wir erneut.
                    Log.w(TAG, "Auth beim Verbinden gescheitert – automatischer Neuversuch folgt")
                }
                return
            }
            // Verbindung weg ODER 429-Sperre: still abbrechen. Bei 429 wuerde ein
            // weiteres refreshSettings nur fast-failen (zweite Fehler-Snackbar);
            // der Cooldown-Watcher ruft onConnected nach Ablauf ohnehin erneut auf
            // – so bleibt jeder Erholungs-Versuch effektiv EINE Probe.
            if (e is ShellyRpcException && (e.isTransport || e.code == 429)) return
            _messages.tryEmit("doorbell-Script konnte nicht eingerichtet werden: ${e.message}")
        }
        runCatching { refreshSettings() }
            .onFailure { emitFailure(it, "Einstellungen konnten nicht geladen werden") }
    }

    /**
     * Kommandofehler melden: Ein Auth-Fehler (fehlendes/falsches Passwort) loest
     * den Passwort-Dialog aus, alles andere landet als Snackbar-Meldung.
     */
    private fun emitFailure(e: Throwable, fallback: String) {
        if (e is ShellyRpcException && e.isAuth) {
            // Kein Popup: dezenter Hinweis, der auf die Einstellungen verweist.
            _messages.tryEmit("$fallback: Passwort stimmt nicht? In den Einstellungen prüfen.")
        } else {
            _messages.tryEmit("$fallback: ${e.message}")
        }
    }

    // ---------- doorbell-Script: pruefen, installieren, aktualisieren ----------

    /** Inhalt der gebuendelten shelly/doorbell.js (per Gradle als Asset kopiert). */
    private val bundledScript: String by lazy {
        assets.open("doorbell.js").readBytes().decodeToString()
    }

    /** Version der gebuendelten doorbell.js ("let VERSION = n" in der ersten Zeile). */
    private val bundledVersion: Int by lazy { parseScriptVersion(bundledScript) ?: 0 }

    private fun parseScriptVersion(code: String?): Int? =
        code?.take(VERSION_PROBE_LEN)?.let { Regex("""VERSION\s*=\s*(\d+)""").find(it) }
            ?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Haelt das doorbell-Script auf dem Shelly ohne Nutzer-Interaktion in Schuss:
     * fehlt es -> installieren; aeltere/unbekannte Version -> aktualisieren;
     * gestoppt/abgestuerzt -> einfach neu starten. Laeuft bei jedem (Re-)Connect
     * und bei „Verbindung pruefen".
     */
    private suspend fun ensureScript() {
        scriptMutex.withLock {
            scriptId = null
            var found: JSONObject? = null
            val scripts = client.call("Script.List").optJSONArray("scripts")
            if (scripts != null) {
                for (i in 0 until scripts.length()) {
                    val s = scripts.optJSONObject(i) ?: continue
                    if (s.optString("name") == "doorbell") {
                        found = s
                        break
                    }
                }
            }
            if (found == null) {
                Log.i(TAG, "ensureScript: kein doorbell-Script gefunden – installiere v$bundledVersion")
                provisionScript(existingId = null)
                return
            }
            val id = found.optInt("id")
            val deployed = parseScriptVersion(
                client.call(
                    "Script.GetCode",
                    JSONObject().put("id", id).put("offset", 0).put("len", VERSION_PROBE_LEN)
                ).optString("data")
            )
            Log.d(TAG, "ensureScript: doorbell (id=$id) v=$deployed, gebuendelt v$bundledVersion")
            when {
                deployed == null || deployed < bundledVersion -> provisionScript(existingId = id)
                deployed > bundledVersion -> {
                    // Nicht anfassen: Downgrade koennte Features eines neueren Schemas zerlegen
                    _messages.tryEmit("doorbell-Script v$deployed ist neuer als diese App – bitte die App aktualisieren")
                    startScript(found)
                }
                else -> startScript(found)
            }
        }
    }

    /** Script (falls gestoppt/abgestuerzt) kommentarlos wieder starten und als aktiv uebernehmen. */
    private suspend fun startScript(entry: JSONObject) {
        val id = entry.optInt("id")
        Log.d(TAG, "startScript: id=$id, running=${entry.optBoolean("running")}")
        if (!entry.optBoolean("running")) {
            // Autostart gleich mit sichern, falls ihn jemand abgeschaltet hat
            runCatching {
                client.call(
                    "Script.SetConfig",
                    JSONObject().put("id", id).put("config", JSONObject().put("enable", true))
                )
            }
            try {
                client.call("Script.Start", JSONObject().put("id", id))
            } catch (e: ShellyRpcException) {
                // Geraet lehnt den Start ab -> Code vermutlich kaputt, frisch einspielen.
                // Transport-/Auth-Fehler dagegen weiterreichen (da hilft kein Upload).
                if (e.isTransport || e.isAuth) throw e
                provisionScript(existingId = id)
                return
            }
        }
        scriptId = id
        _scriptOk.value = true
    }

    /** Die gebuendelte doorbell.js einspielen — Port der shelly/upload.ps1-Sequenz,
     *  aber ueber die bestehende (authentifizierte) WebSocket-Verbindung. */
    private suspend fun provisionScript(existingId: Int?) {
        val id = if (existingId == null) {
            client.call("Script.Create", JSONObject().put("name", "doorbell")).optInt("id", -1)
                .also { if (it < 0) throw ShellyRpcException(-4, "Script.Create lieferte keine id") }
        } else {
            // Laeuft es noch mit altem Code, erst stoppen (Fehler = war schon gestoppt)
            runCatching { client.call("Script.Stop", JSONObject().put("id", existingId)) }
            existingId
        }
        val code = bundledScript
        val chunks = (code.length + SCRIPT_CHUNK - 1) / SCRIPT_CHUNK
        Log.i(TAG, "provisionScript: lade v$bundledVersion hoch (id=$id, ${code.length} Zeichen, $chunks Bloecke)")
        var pos = 0
        while (pos < code.length) {
            val chunk = code.substring(pos, minOf(pos + SCRIPT_CHUNK, code.length))
            client.call(
                "Script.PutCode",
                JSONObject().put("id", id).put("code", chunk).put("append", pos > 0)
            )
            pos += chunk.length
        }
        client.call("Script.SetConfig", JSONObject().put("id", id).put("config", JSONObject().put("enable", true)))
        client.call("Script.Start", JSONObject().put("id", id))
        scriptId = id
        _scriptOk.value = true
        _messages.tryEmit(
            if (existingId == null) "doorbell-Script v$bundledVersion wurde auf dem Shelly installiert"
            else "doorbell-Script wurde auf v$bundledVersion aktualisiert"
        )
    }

    private suspend fun refreshSettings() {
        refreshMutex.withLock {
            val kv = kvsGetMany("dbell_*")
            _shared.value = SharedSettings(
                thresholdW = kv.num("dbell_cfg_threshold_w") ?: DEFAULT_THRESHOLD_W,
                debounceS = kv.num("dbell_cfg_debounce_s")?.toInt() ?: DEFAULT_DEBOUNCE_S,
            )
            refreshBellTimes(kv)
            _muteUntil.value = kv.num("dbell_mute_until")?.toLong()
                ?.takeIf { it > System.currentTimeMillis() / 1000 }
            runCatching { pollStatus() }
            mergeKvsLog(kv)
            Log.d(TAG, "refreshSettings ok: ${_bellTimes.value?.size ?: 0} Klingelzeiten, Schwelle ${_shared.value?.thresholdW}W")
        }
    }

    private suspend fun refreshBellTimes(kv: Map<String, Any?>) {
        cleanupLegacyDnd(kv)
        val ids = parseIdPairs(kv["dbell_ring_ids"])
        if (ids.isEmpty()) {
            _bellTimes.value = emptyList()
            return
        }
        val jobs = client.call("Schedule.List").optJSONArray("jobs")
        val byId = mutableMapOf<Int, JSONObject>()
        if (jobs != null) {
            for (i in 0 until jobs.length()) {
                val j = jobs.optJSONObject(i) ?: continue
                byId[j.optInt("id", -1)] = j
            }
        }
        val entries = ids.mapNotNull { (onId, offId) ->
            val on = byId[onId] ?: return@mapNotNull null
            val off = byId[offId] ?: return@mapNotNull null
            BellTimes.parse(on.optString("timespec"), off.optString("timespec"))?.let { w ->
                BellEntry(onId, offId, w, on.optBoolean("enable") && off.optBoolean("enable"))
            }
        }
        if (entries.size != ids.size) {
            // Jobs wurden extern geloescht/umgebaut -> Id-Liste im KVS aufraeumen
            kvsSet("dbell_ring_ids", idsJson(entries.map { it.onId to it.offId }))
        }
        _bellTimes.value = entries
    }

    /**
     * Reste der frueheren Ruhezeiten-Logik (invertierte Fenster) entsorgen: die
     * alten Schedules wuerden sonst weiter zur falschen Zeit schalten.
     */
    private suspend fun cleanupLegacyDnd(kv: Map<String, Any?>) {
        val keys = listOf("dbell_dnd_ids", "dbell_dnd_off_id", "dbell_dnd_on_id")
        if (keys.none { kv.containsKey(it) }) return
        val jobIds = parseIdPairs(kv["dbell_dnd_ids"]).flatMap { listOf(it.first, it.second) } +
            listOfNotNull(kv.num("dbell_dnd_off_id")?.toInt(), kv.num("dbell_dnd_on_id")?.toInt())
        for (id in jobIds) runCatching { deleteSchedule(id) }
        for (key in keys) kvsDelete(key)
        _messages.tryEmit("Alte Ruhezeiten wurden entfernt – Klingelzeiten bitte neu anlegen")
    }

    private suspend fun pollStatus() {
        val st = client.call("Switch.GetStatus", JSONObject().put("id", 0))
        if (st.has("apower")) _watts.value = st.optDouble("apower", 0.0)
        if (st.has("output")) _bellOn.value = st.optBoolean("output")
    }

    // ---------- Notifications vom Shelly ----------

    private fun handleNotification(msg: JSONObject) {
        when (msg.optString("method")) {
            "NotifyEvent" -> {
                val events = msg.optJSONObject("params")?.optJSONArray("events") ?: return
                for (i in 0 until events.length()) {
                    val ev = events.optJSONObject(i) ?: continue
                    when (ev.optString("event")) {
                        "doorbell" -> onDoorbell(ev.optJSONObject("data"))
                        "doorbell_cfg" ->
                            if (!listenOnly.value) scope.launch { runCatching { refreshSettings() } }
                        "doorbell_hb" -> onHeartbeat(ev.optJSONObject("data"))
                        "doorbell_log" -> scope.launch { onRingLog(ev.optJSONObject("data")) }
                    }
                }
            }
            "NotifyStatus", "NotifyFullStatus" -> {
                val sw = msg.optJSONObject("params")?.optJSONObject("switch:0") ?: return
                if (sw.has("apower")) _watts.value = sw.optDouble("apower", 0.0)
                if (sw.has("output")) _bellOn.value = sw.optBoolean("output")
            }
        }
    }

    private fun onDoorbell(data: JSONObject?) {
        val nowS = System.currentTimeMillis() / 1000
        // Ohne NTP-Zeit schickt das Script einen Mini-Timestamp -> Handy-Zeit nehmen
        val ts = data?.optLong("ts", 0L)?.takeIf { it > MIN_VALID_TS } ?: nowS
        Log.i(TAG, "Klingel-Event (ts=$ts, lokalerAlarm=$localAlarmEnabled)")
        scope.launch { recordProvisional(ts) }
        // Lokal stummgeschaltet: Ereignis landet trotzdem in der History
        if (localAlarmEnabled) startAlarm()
    }

    private fun onHeartbeat(data: JSONObject?) {
        lastHeartbeatMs = SystemClock.elapsedRealtime()
        _scriptOk.value = true
        val v = data?.optInt("v", -1)?.takeIf { it > 0 }
        if (v != null) _scriptVersion.value = v
        Log.d(TAG, "Heartbeat vom Script (v${v ?: "?"})")
    }

    /**
     * Aus den (per Sperrzeit gedrosselten) Alarm-Events eine vorlaeufige
     * History-Zeile bauen: Druecke innerhalb von [GROUP_GAP_S] zaehlen. Der
     * spaeter eintreffende, exakte Datensatz des Scripts ([onRingLog]) ersetzt
     * diesen Vorlaeufer. Die lokale Zaehlung unterschaetzt naturgemaess (nur ein
     * Event je Sperrzeit), zeigt aber sofort etwas an.
     */
    private suspend fun recordProvisional(ts: Long) {
        logMutex.withLock {
            if (provStart != 0L && ts - provLastTs <= GROUP_GAP_S) {
                provCount++
                provLastTs = ts
            } else {
                provStart = ts
                provCount = 1
                provLastTs = ts
            }
            val dur = (provLastTs - provStart).toInt().coerceAtLeast(1)
            dao.upsert(RingEvent(ts = provStart, count = provCount, durationS = dur, authoritative = false))
        }
    }

    /** Exakter Ereignis-Datensatz vom Script: ersetzt einen etwaigen Vorlaeufer. */
    private suspend fun onRingLog(data: JSONObject?) {
        val t = data?.optLong("t", 0L)?.takeIf { it > MIN_VALID_TS } ?: return
        val n = data.optInt("n", 1).coerceAtLeast(1)
        val d = data.optInt("d", 1).coerceAtLeast(1)
        logMutex.withLock {
            dao.clearProvisional(t - LOG_TOL_S, t + d + LOG_TOL_S)
            dao.upsert(RingEvent(ts = t, count = n, durationS = d, authoritative = true))
            if (provStart != 0L && provStart in (t - LOG_TOL_S)..(t + d + LOG_TOL_S)) {
                provStart = 0L
                provCount = 0
                provLastTs = 0L
            }
        }
    }

    // ---------- Alarm ----------

    private fun startAlarm() {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastAlarmAtMs < 3_000 && lastAlarmAtMs != 0L) return // Doppel-Events abfangen
        lastAlarmAtMs = nowMs
        alarm.start(alarmUri?.let { Uri.parse(it) })
        postRingNotification()
    }

    fun stopAlarm() = alarm.stop()

    fun testAlarm() {
        lastAlarmAtMs = 0
        startAlarm()
    }

    private fun postRingNotification() {
        val fullScreenPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, DoorbellService::class.java).setAction(ACTION_STOP_ALARM),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, Channels.alarmChannelId(this))
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(getString(R.string.notif_ring_title))
            .setContentText(getString(R.string.notif_ring_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPi, true)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_ring_stop), stopPi)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID_RING, notification) }
    }

    private fun cancelRingNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_RING)
    }

    // ---------- Kommandos aus der UI ----------

    fun setUiVisible(visible: Boolean) {
        uiVisible.value = visible
        // Ohne lokalen Alarm lief der Dienst nur fuer die UI mit
        if (!visible && !localAlarmEnabled) stopSelf()
    }

    // Manueller „Neu verbinden"-Knopf: soll auch aus einem pausierten Zustand
    // (Fremdnetz/Greylist) heraus einen echten Versuch erzwingen.
    fun reconnect() = client.forceAttempt()

    /**
     * Übernimmt IP/Passwort sofort in die laufende Verbindung, ohne auf den
     * DataStore-Umweg zu warten. Ohne das würde eine direkt danach ausgelöste
     * Prüfung noch mit den alten Zugangsdaten laufen (erst zweiter Versuch grün).
     */
    fun applyCredentials(ip: String, password: String) {
        this.password.value = password
        this.ip.value = ip
        client.credentialsChanged()
    }

    /** Passwort aus dem Fehlerdialog übernehmen (IP bleibt) und Daten neu abgleichen. */
    fun applyPassword(password: String) {
        this.password.value = password
        client.credentialsChanged()
        // Die Verbindung steht bei Auth-Fehlern weiter -> direkt neu abgleichen,
        // sonst blieben Klingelzeiten/Einstellungen bis zum nächsten Reconnect leer.
        if (client.state.value is ConnectionState.Connected) {
            scope.launch { onConnected() }
        }
    }

    /** Hauptdaten (Script-Status, Einstellungen) neu laden – nach erfolgreicher Prüfung. */
    suspend fun reloadSettings() = onConnected()

    /**
     * Aktive Verbindungsprüfung für den „Verbindung prüfen“-Button. Testet immer
     * frisch (retryAuth: auch eine Passwortänderung am Shelly selbst wird so
     * erkannt) und liefert eine Gesamtaussage: Shelly erreichbar + Passwort ok +
     * doorbell-Script läuft. Fehlt oder hakt das Script, repariert [ensureScript]
     * es dabei gleich selbst.
     */
    suspend fun checkConnection(force: Boolean = false): ConnCheck {
        if (listenOnly.value) return checkListenOnly()
        // Steckt der Shelly in seiner 429-Sperre, NICHT erneut anfragen – jede
        // weitere Anfrage verlaengert die Sperre nur. Die App wiederholt selbst.
        val cooldown = client.rateLimitedForMs()
        if (cooldown > 0) {
            val s = cooldown / 1000 + 1
            Log.w(TAG, "checkConnection: 429-Sperre aktiv, noch ${s}s")
            return ConnCheck(
                ok = false,
                detail = "Shelly hat wegen zu vieler Anfragen kurz dichtgemacht. " +
                    "Die App verbindet sich in ~${s}s von selbst wieder – bitte jetzt nicht erneut prüfen.",
            )
        }
        // Laeuft schon alles (verbunden, Script funkt, Einstellungen geladen) und
        // wurden keine neuen Zugangsdaten eingegeben? Dann NICHTS senden – nur
        // den bekannten, passiv gepflegten Zustand melden. Der schwache Shelly
        // wird sonst bei jedem Druck mit einem Anfragen-Schwall belastet.
        if (!force &&
            client.state.value is ConnectionState.Connected &&
            _scriptOk.value == true &&
            _shared.value != null
        ) {
            val v = _scriptVersion.value ?: bundledVersion
            return ConnCheck(
                ok = true,
                detail = "Verbunden, doorbell-Script v$v läuft, Einstellungen geladen – alles ok.",
            )
        }
        // Nicht verbunden? Verbindungsaufbau anstoßen und kurz darauf warten,
        // statt sofort mit einem Transportfehler zu scheitern. forceAttempt
        // überspringt dabei das Netzwerk-Tor (der Nutzer will jetzt prüfen).
        if (client.state.value !is ConnectionState.Connected) {
            client.forceAttempt()
            val deadline = SystemClock.elapsedRealtime() + CONNECT_WAIT_MS
            while (client.state.value !is ConnectionState.Connected &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                delay(300)
            }
            if (client.state.value !is ConnectionState.Connected) {
                return ConnCheck(ok = false, detail = "Nicht verbunden – WLAN und IP prüfen.")
            }
        }
        client.retryAuth()
        val api = runCatching { client.call("Switch.GetStatus", JSONObject().put("id", 0)) }
        api.exceptionOrNull()?.let { e ->
            val detail = when {
                e is ShellyRpcException && e.isAuth && password.value.isBlank() ->
                    "Shelly ist passwortgeschützt, hier ist keins eingetragen – reiner Lausch-Betrieb: " +
                        "der Klingel-Alarm funktioniert, Schalten/Einstellungen nicht."
                e is ShellyRpcException && e.isAuth ->
                    "Passwort ist falsch (Benutzer ist „admin“). Der Klingel-Alarm " +
                        "funktioniert trotzdem – nur Schalten und Einstellungen brauchen das Passwort."
                e is ShellyRpcException && e.code == 429 ->
                    "Shelly meldet „zu viele Anfragen“ – kurz warten und erneut prüfen."
                e is ShellyRpcException && e.isTransport -> "Shelly nicht erreichbar – IP und WLAN prüfen."
                else -> e.message
            }
            return ConnCheck(ok = false, detail = detail)
        }
        runCatching { ensureScript() }.exceptionOrNull()?.let { e ->
            return ConnCheck(
                ok = false,
                detail = "doorbell-Script läuft nicht und konnte nicht eingerichtet werden: ${e.message}",
            )
        }
        // Version mit ausgeben: das Selbst-Update beim Verbinden laeuft sonst
        // unsichtbar ab, wenn die App-Oberflaeche dabei nicht offen ist.
        return ConnCheck(ok = true, detail = "doorbell-Script v$bundledVersion ist installiert und läuft.")
    }

    /**
     * Prüfung im Lauschmodus: ohne Auth. Bestätigt nur, dass die Verbindung
     * steht und das doorbell-Script sein Lebenszeichen sendet (der einzige
     * Nachweis ohne Passwort, dass es läuft).
     */
    private suspend fun checkListenOnly(): ConnCheck {
        if (client.state.value !is ConnectionState.Connected) {
            return ConnCheck(ok = false, detail = "Nicht verbunden – IP und WLAN prüfen.")
        }
        // Auf das (alle 30 s gesendete) Lebenszeichen warten.
        val deadline = SystemClock.elapsedRealtime() + HEARTBEAT_WAIT_MS
        while (lastHeartbeatMs == 0L && SystemClock.elapsedRealtime() < deadline) {
            delay(500)
        }
        if (lastHeartbeatMs == 0L) {
            return ConnCheck(
                ok = false,
                detail = "Verbunden, aber kein Lebenszeichen vom doorbell-Script – " +
                    "läuft es auf dem Shelly?",
            )
        }
        val v = _scriptVersion.value
        return ConnCheck(
            ok = true,
            detail = "Lausch-Betrieb: Klingel-Alarm läuft" +
                (v?.let { ", doorbell-Script v$it aktiv." } ?: ", doorbell-Script aktiv."),
        )
    }

    fun setBell(on: Boolean) {
        scope.launch {
            runCatching {
                client.call("Switch.Set", JSONObject().put("id", 0).put("on", on))
                pollStatus()
            }.onFailure { emitFailure(it, "Schalten fehlgeschlagen") }
        }
    }

    fun saveShared(thresholdW: Double, debounceS: Int) {
        scope.launch {
            runCatching {
                kvsSet("dbell_cfg_threshold_w", thresholdW)
                kvsSet("dbell_cfg_debounce_s", debounceS)
                _shared.value = SharedSettings(thresholdW, debounceS)
                notifyScriptCfgChanged()
            }.onFailure { emitFailure(it, "Speichern fehlgeschlagen") }
        }
    }

    fun addBellTime(w: BellWindow) {
        scope.launch {
            runCatching {
                val onId = createSchedule(BellTimes.onTimespec(w), switchCall(true))
                val offId = try {
                    createSchedule(BellTimes.offTimespec(w), switchCall(false))
                } catch (e: Exception) {
                    // kein halbes Paar stehen lassen
                    runCatching { deleteSchedule(onId) }
                    throw e
                }
                kvsSet("dbell_ring_ids", idsJson(currentIds() + (onId to offId)))
                _bellTimes.value = _bellTimes.value.orEmpty() + BellEntry(onId, offId, w, enabled = true)
                alignBell()
                notifyScriptCfgChanged()
            }.onFailure {
                emitFailure(it, "Klingelzeit anlegen fehlgeschlagen")
                runCatching { refreshSettings() }
            }
        }
    }

    fun removeBellTime(entry: BellEntry) {
        scope.launch {
            runCatching {
                deleteSchedule(entry.onId)
                deleteSchedule(entry.offId)
                kvsSet("dbell_ring_ids", idsJson(currentIds() - (entry.onId to entry.offId)))
                _bellTimes.value = _bellTimes.value.orEmpty()
                    .filterNot { it.onId == entry.onId && it.offId == entry.offId }
                alignBell()
                notifyScriptCfgChanged()
            }.onFailure {
                emitFailure(it, "Klingelzeit löschen fehlgeschlagen")
                runCatching { refreshSettings() }
            }
        }
    }

    private fun currentIds(): List<Pair<Int, Int>> = _bellTimes.value.orEmpty().map { it.onId to it.offId }

    /** "Ruhe bis": Klingel sofort stumm, das Shelly-Script schaltet bei Ablauf zurueck. */
    fun setMute(untilTs: Long) {
        scope.launch {
            runCatching {
                kvsSet("dbell_mute_until", untilTs)
                _muteUntil.value = untilTs
                alignBell()
                notifyScriptCfgChanged()
            }.onFailure {
                emitFailure(it, "Ruhe setzen fehlgeschlagen")
                runCatching { refreshSettings() }
            }
        }
    }

    fun clearMute() {
        scope.launch {
            runCatching {
                kvsDelete("dbell_mute_until")
                _muteUntil.value = null
                alignBell()
                notifyScriptCfgChanged()
            }.onFailure {
                emitFailure(it, "Ruhe beenden fehlgeschlagen")
                runCatching { refreshSettings() }
            }
        }
    }

    private fun switchCall(on: Boolean): JSONArray = JSONArray().put(
        JSONObject()
            .put("method", "Switch.Set")
            .put("params", JSONObject().put("id", 0).put("on", on))
    )

    private suspend fun createSchedule(timespec: String, calls: JSONArray): Int {
        val params = JSONObject()
            .put("enable", true)
            .put("timespec", timespec)
            .put("calls", calls)
        val id = client.call("Schedule.Create", params).optInt("id", -1)
        if (id < 0) throw ShellyRpcException(-4, "Schedule.Create lieferte keine id")
        return id
    }

    private suspend fun deleteSchedule(id: Int) {
        try {
            client.call("Schedule.Delete", JSONObject().put("id", id))
        } catch (e: ShellyRpcException) {
            // Geraetefehler = Job existiert nicht mehr -> Ziel erreicht.
            // Transportfehler weiterreichen, der Job koennte noch da sein.
            if (e.isTransport) throw e
        }
    }

    private suspend fun alignBell() {
        // Der Nutzer hat gerade bewusst an Klingelzeiten oder Ruhe gedreht:
        // Schalter sofort auf den erwarteten Zustand bringen. Laufende "Ruhe
        // bis" hat Vorrang; sonst: innerhalb eines Fensters -> an, ausserhalb
        // -> aus; ohne Klingelzeiten gehoert die Klingel an.
        val muted = (_muteUntil.value ?: 0) > System.currentTimeMillis() / 1000
        val windows = _bellTimes.value.orEmpty().filter { it.enabled }
        val expectedOn = !muted && (windows.isEmpty() || windows.any { it.window.isInsideNow() })
        if (_bellOn.value != expectedOn) {
            client.call("Switch.Set", JSONObject().put("id", 0).put("on", expectedOn))
        }
        runCatching { pollStatus() }
    }

    // ---------- KVS-Helfer ----------

    private suspend fun kvsSet(key: String, value: Any) {
        client.call("KVS.Set", JSONObject().put("key", key).put("value", value))
    }

    private suspend fun kvsDelete(key: String) {
        // Fehler ignorieren — der Key kann schon weg sein (z. B. anderes Geraet schneller)
        runCatching { client.call("KVS.Delete", JSONObject().put("key", key)) }
    }

    /** KVS-Wert "[[id,id],...]" -> Paarliste; alles Unlesbare wird ignoriert. */
    private fun parseIdPairs(v: Any?): List<Pair<Int, Int>> {
        val s = v as? String ?: return emptyList()
        val arr = runCatching { JSONArray(s) }.getOrNull() ?: return emptyList()
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONArray(i) ?: continue
            if (p.length() == 2) pairs += p.optInt(0) to p.optInt(1)
        }
        return pairs
    }

    private fun idsJson(pairs: List<Pair<Int, Int>>): String {
        val arr = JSONArray()
        for ((off, on) in pairs) arr.put(JSONArray().put(off).put(on))
        return arr.toString()
    }

    private suspend fun kvsGetMany(match: String): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        var offset = 0
        repeat(5) {
            val res = client.call("KVS.GetMany", JSONObject().put("match", match).put("offset", offset))
            val before = out.size
            when (val items = res.opt("items")) {
                // Objekt-Form: { key: {etag, value}, ... }
                is JSONObject -> for (key in items.keys()) out[key] = items.getJSONObject(key).opt("value")
                // Array-Form (neuere Firmware): [{key, etag, value}, ...]
                is JSONArray -> for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    out[o.optString("key")] = o.opt("value")
                }
            }
            val total = res.optInt("total", out.size)
            if (out.size >= total || out.size == before) return out
            offset = out.size
        }
        return out
    }

    private fun Map<String, Any?>.num(key: String): Double? = when (val v = this[key]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    /** Ringpuffer-Chunks (dbell_log_*) in die lokale Room-History mergen. */
    private suspend fun mergeKvsLog(kv: Map<String, Any?>) {
        val events = mutableListOf<RingEvent>()
        for ((key, value) in kv) {
            if (!key.startsWith("dbell_log_") || key == "dbell_log_head") continue
            val arr = (value as? String)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: continue
            for (i in 0 until arr.length()) {
                // Neues Format: Ereignis-Objekte {t,n,d}; Nicht-Objekte ignorieren.
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optLong("t", 0L)
                if (t <= MIN_VALID_TS) continue
                events.add(
                    RingEvent(
                        ts = t,
                        count = o.optInt("n", 1).coerceAtLeast(1),
                        durationS = o.optInt("d", 1).coerceAtLeast(1),
                        authoritative = true,
                    )
                )
            }
        }
        if (events.isNotEmpty()) dao.insertAll(events)
    }

    private suspend fun notifyScriptCfgChanged() {
        val id = scriptId
        if (id == null) {
            _messages.tryEmit("Hinweis: doorbell-Script nicht aktiv – andere Geräte sehen die Änderung erst nach ihrem nächsten Verbinden")
            return
        }
        runCatching { client.call("Script.Eval", JSONObject().put("id", id).put("code", "cfgChanged()")) }
    }

    // ---------- Foreground-Notification ----------

    private fun startForegroundCompat(text: String = getString(R.string.notif_connecting)) {
        val notification = buildServiceNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID_SERVICE, notification)
        }
    }

    private fun buildServiceNotification(text: String): Notification {
        val contentPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Channels.SERVICE)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun serviceText(state: ConnectionState): String = when (state) {
        is ConnectionState.Connected -> getString(R.string.notif_listening)
        ConnectionState.Connecting -> getString(R.string.notif_connecting)
        ConnectionState.NoWifi -> getString(R.string.notif_no_wifi)
        is ConnectionState.OtherNetwork -> state.detail
    }

    private fun updateServiceNotification(state: ConnectionState) {
        // Nicht im Vordergrund (Alarm lokal aus) -> notify() wuerde die gerade
        // entfernte Dauer-Notification wieder anheften
        if (!localAlarmEnabled) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID_SERVICE, buildServiceNotification(serviceText(state)))
        }
    }

    companion object {
        private const val TAG = "DoorbellSvc"

        const val ACTION_STOP_ALARM = "de.beardedskunk.shellydoorbell.STOP_ALARM"

        private const val NOTIF_ID_SERVICE = 1
        private const val NOTIF_ID_RING = 2

        /** Poll-Abstand fuer die reine Watt-Anzeige (Live-Aenderungen pusht der
         *  Shelly ohnehin per NotifyStatus – gemaechlich genuegt, spart Anfragen). */
        private const val POLL_INTERVAL_MS = 5_000L

        /** Aeltere Timestamps gelten als "keine echte Uhrzeit" (Shelly ohne NTP). */
        private const val MIN_VALID_TS = 1_000_000_000L
        private const val PRUNE_AFTER_S = 400L * 24 * 3600

        /** 3 min: Druecke innerhalb dieses Fensters gehoeren zu einem Ereignis. */
        private const val GROUP_GAP_S = 180L

        /** Toleranz beim Zuordnen eines Script-Datensatzes zu einem Vorlaeufer. */
        private const val LOG_TOL_S = 90L

        /** Ohne Heartbeat laenger als das gilt das Script als nicht laufend. */
        private const val STALE_MS = 95_000L

        /** So lange wartet die Lauschmodus-Pruefung auf das erste Lebenszeichen. */
        private const val HEARTBEAT_WAIT_MS = 35_000L

        /** So lange wartet „Verbindung pruefen" bei getrennter Verbindung auf den Connect. */
        private const val CONNECT_WAIT_MS = 10_000L

        const val DEFAULT_THRESHOLD_W = 2.0
        const val DEFAULT_DEBOUNCE_S = 30

        /** So viele erste Zeichen des Script-Codes reichen fuer die VERSION-Zeile. */
        private const val VERSION_PROBE_LEN = 200

        /** Blockgroesse fuer Script.PutCode (wie shelly/upload.ps1). */
        private const val SCRIPT_CHUNK = 1024

        fun start(context: Context) {
            val intent = Intent(context, DoorbellService::class.java)
            val alarmEnabled = runBlocking { Prefs(context).settings.first().alarmEnabled }
            if (alarmEnabled) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                // Nur-UI-Betrieb: normaler Service ohne Dauer-Notification
                runCatching { context.startService(intent) }
            }
        }
    }
}
