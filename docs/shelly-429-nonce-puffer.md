# Shelly „429 Too Many Requests" verstehen und vermeiden

> Gilt für Shelly Gen2/Gen3 (hier verifiziert am **Plug M Gen3**, FW `20251209`)
> über die RPC-API (WebSocket **und** HTTP). Diese Notiz ist bewusst allgemein
> gehalten, weil sie für weitere Shelly-Projekte gilt – nicht nur für diese App.

## Kurzfassung (TL;DR)

Der `429` hat **nichts** mit dem reinen Anfrage-Volumen und **nichts** mit dem
oft zitierten „6-Verbindungen-Limit" zu tun. Er entsteht durch das
**Digest-Auth-Nonce-Management** des Geräts:

- Der Shelly hält einen **Ringpuffer mit maximal 32 Nonce-Einträgen**.
- Eine per `401` ausgegebene, **noch nicht benutzte** Nonce ist „**pending**" und
  wird **vor Verdrängung geschützt**.
- Findet das Gerät keinen freien Slot, öffnet es ein **2-Sekunden-Throttle-Fenster,
  in dem Anfragen nach *neuen* Nonces mit `429` beantwortet werden**.
- Eine **einzelne Nonce darf man bis zu 30.000-mal / 1 Stunde wiederverwenden**,
  solange man den Zähler `nc` bei jedem Request hochzählt.

**Die goldene Regel:** *Eine* Nonce besorgen und **wiederverwenden** (mit `nc++`).
**Niemals** in kurzer Folge viele *neue* Nonces anfordern – vor allem keine, die
man nie sauber zu Ende authentifiziert („orphaned pending challenges"). Genau das
füllt den 32er-Puffer und löst die anhaltende Sperre aus.

## Wie die Digest-Auth beim Shelly läuft

Benutzer ist immer `admin`, das Passwort ist das in der Web-UI gesetzte.

1. Ein **nicht** authentifizierter Aufruf einer geschützten Methode wird mit
   `401` beantwortet. Die Fehlermeldung enthält die **Challenge**:
   ```json
   { "auth_type":"digest", "nonce":"<opak, String!>", "nc":1,
     "realm":"shellyXXX-<id>", "algorithm":"SHA-256" }
   ```
   (Über HTTP kommt stattdessen ein `WWW-Authenticate: Digest …`-Header.)
   **Achtung:** Mit dieser Antwort ist bereits eine **pending Nonce** im Puffer
   belegt.

2. Der Client rechnet die Antwort (SHA-256, Shelly-Variante):
   ```
   ha1      = SHA256("admin:" + realm + ":" + password)
   ha2      = SHA256("dummy_method:dummy_uri")     // fester String!
   response = SHA256(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)
   ```
   und schickt den Call erneut, diesmal mit `auth`-Objekt
   (`realm, username, nonce, cnonce, nc, response, algorithm`). Jetzt wird die
   Nonce **aktiv** (mindestens einmal benutzt).

3. **Wiederverwenden statt neu holen:** Für jeden weiteren Request dieselbe
   `nonce` nehmen und `nc` um 1 erhöhen. Dieselbe `nonce` mit **demselben** `nc`
   erneut zu benutzen, wertet das Gerät als Replay → `401`. Eine **neue** Nonce
   nur anfordern, wenn ein `401` mit `stale=true` kommt (Nonce abgelaufen / Limit
   erreicht) oder man noch gar keine hat.

## Nonce-Puffer & Verdrängung (die 429-Mechanik)

Zustände eines Slots:

| Zustand   | Bedeutung                                                        | Verdrängung |
|-----------|------------------------------------------------------------------|-------------|
| pending   | in einem `401` ausgegeben, Client hat noch nicht geantwortet     | **geschützt** |
| active    | Client hat mindestens einmal damit authentifiziert              | verdrängbar |

Slot-Vergabe bei vollem Puffer, in dieser Reihenfolge:

1. Leere Slots zuerst.
2. Sonst: am wenigsten genutzte **active** Session (nur einmal benutzt) verdrängen.
3. **pending** Challenges sind zunächst geschützt.
4. Ist kein passender Slot da → **2-s-Throttle-Fenster** → in dieser Zeit liefern
   Anfragen nach neuen Nonces `429`.
5. Nach Ablauf des Throttles wird der Slot mit der **kleinsten Nutzungszahl**
   zwangsverdrängt (notfalls auch eine pending).

**Konsequenz:** Wer viele **pending** Challenges erzeugt (Challenge anfordern,
aber nie zu Ende authentifizieren – z. B. weil die Verbindung abreißt oder die
Auth scheitert), stapelt geschützte Einträge, bis der Puffer voll ist. Dann liefert
jede weitere Neu-Anfrage `429`. Und weil jede Neu-Anfrage im Throttle das Fenster
neu anstößt, **hält man die Sperre durch fortgesetztes Nachfragen selbst am
Leben** → das, was sich wie eine „Tiefensperre" anfühlt.

## Am Gerät verifiziert (eigene Python-Skripte, ohne Handy)

Nachbau des WS-Frame-Digests, voller Kontrolle über Nonce-Wiederverwendung und `nc`:

| # | Vorgehen | Ergebnis |
|---|----------|----------|
| **A** | 1 Verbindung, 30 Calls, **gleiche Nonce**, `nc++` | **0×429** @ ~29 req/s |
| **B** | 15 **frische** WS-Verbindungen, alle **dieselbe** persistierte Nonce (`nc` läuft weiter) | **0×429** @ ~13 Reconnects/s |
| **C** | 45× **neue** Nonce, jede aber **sofort 1× erfolgreich** benutzt | 0×429 (werden „used once" = verdrängbar) |
| **D** | nur unauth → `401`, Auth **nie** abgeschlossen (orphaned pending) | **429**, Puffer läuft voll |
| **Erholung** | 1 Sonde alle 30 s | erholt nach ~30 s **Stille**; jede Neu-Anfrage im Throttle verlängert |
| **E** | 12 WS **gleichzeitig** offen + Hello (auth-frei) | alle ok; „6er"-Limit betrifft nur HTTP; Close gibt Slot sofort frei |

**A und B** sind der Beweis für die gute Praxis: Nonce wiederverwenden ist selbst
über schnelle Reconnects völlig unkritisch. **D** ist der Beweis für den Auslöser.
**E** zeigt: die Verbindungs­anzahl ist nicht das Problem (eine langlebige WS genügt).

## Do & Don't

**DO**
- Eine Nonce besorgen und **über Reconnects hinweg behalten**; `nc`-Zähler
  fortführen. Den ersten Call einer neuen Session **präemptiv authentifiziert**
  schicken (kein unauth-Probe → keine neue Challenge).
- Eine **neue** Nonce nur bei `401 stale=true`, beim allerersten Connect oder bei
  Passwortwechsel holen – und sie dann **sofort** zu Ende authentifizieren.
- Eine langlebige WebSocket-Verbindung halten; nur bei echtem Abriss neu verbinden.
- Zum Erholen aus einem `429`: **die vorhandene Nonce wiederverwenden** (das ist
  keine Neu-Anfrage und umgeht den Throttle) und ansonsten schlicht **Ruhe geben**.

**DON'T**
- **Nicht** bei jedem Reconnect die Auth zurücksetzen / eine neue Challenge ziehen.
- **Nie** eine Challenge anfordern, die man nicht auf derselben lebenden Verbindung
  abschließt (kein „unauth-Probe, dann Verbindung fallen lassen").
- Im `429`-Zustand **nicht** mit immer neuen Nonces weiterprobieren – das füttert
  die Sperre.
- Sich nicht auf das „6-Verbindungen-Limit" als Erklärung verlassen – es gilt nur
  für nicht-persistente HTTP-Kanäle.

## Wie diese App es macht (Fix)

Ursprünglicher Bug: `ShellyClient.runSession()` rief bei **jedem** Reconnect
`resetAuth()` → jede Verbindung forderte eine neue Challenge an. Unter
Verbindungs-Churn (WLAN-Zucken, App-Neustarts) blieben diese als pending liegen →
Puffer voll → `429`, und die Recovery-Versuche (wieder mit `resetAuth`) hielten die
Sperre am Leben (beobachtete Eskalation 30 → 60 → 120 s).

Behoben durch:

1. **Nonce über Reconnects behalten** – `resetAuth()` aus `runSession()` entfernt.
   Der erste Call einer neuen Session geht präemptiv authentifiziert raus (`nc++`).
2. **Neue Challenge nur bei echtem Bedarf** – `doCall` holt eine frische Nonce
   ausschließlich über den regulären `401`-Pfad (abgelaufene Nonce). Voller
   `resetAuth()` nur noch bei Passwortwechsel (`credentialsChanged()`).
3. **429-Erholung ohne Nachfüttern** – der Auth-Wiederanlauf löst nur die Sperre
   (`authFailed = false`) und **behält die Nonce**; die `rateLimited`-Erholung
   nutzt die vorhandene Nonce weiter.

Ergänzend (aus einer früheren Runde, weiterhin sinnvoll): alle RPCs strikt
seriell (`callMutex`) mit ~250 ms Mindestabstand; der Live-Watt-Poll startet erst,
nachdem der Verbindungsaufbau (`onConnected`) durch ist, plus eine kurze
Settle-Pause nach dem Connect.

Relevante Dateien: `ShellyClient.kt` (Auth-Lebensdauer, `doCall`, `runSession`),
`ShellyAuth.kt` (Digest-Formel), `DoorbellService.kt` (Poll-Gating, Erholung).

## Nachtrag (2026-07-19): lange Verbindungs-Latenz nach App-Aus/Ein

**Symptom:** Nach „Alarm auf diesem Handy" aus + Zurück (Dienst wird zerstört) und
erneutem Start stand die App zwar sofort auf „Verbunden" (der auth-freie Hello
klappt in <100 ms), aber Watt/Klingel-Status/Klingelzeiten blieben teils **~30 s
(eskalierend 30→60→120 s)** leer. Am Gerät (Pixel 8 Pro, Shelly Plug M Gen3)
per Logcat reproduziert.

**Zwei Ursachen, beide am selben Gerät verifiziert:**

1. **Nonces sind an die WS-Verbindung gebunden.** Anders als die frühere Annahme
   (und die zweideutige Messung in Szenario B, die nur *429* zählte) akzeptiert
   diese FW eine Nonce **nicht** auf einer neuen WS-Verbindung: nach jedem
   Reconnect/Neustart wird die alte Nonce mit **401** (nicht 429) abgewiesen, das
   Gerät gibt eine frische Challenge aus. → Eine Nonce über den *Prozess-Neustart*
   hinweg zu **persistieren bringt nichts** (der erste Call wird ohnehin mit 401
   beantwortet). Ein solcher Versuch wurde gebaut, gemessen und wieder **verworfen**.
   Das In-RAM-Behalten über Reconnects (`runSession` ohne `resetAuth`) ist
   ebenfalls wirkungslos, aber **harmlos** (kostet keinen Extra-Roundtrip, weil der
   unauth-Probe genauso einen 401 zieht) – daher unverändert gelassen.

2. **Das Gerät gibt beim Handshake gelegentlich 2–3 frische Challenges hintereinander
   aus**, bevor eine „greift" (vermutlich rotiert die eben ausgegebene Nonce, ehe
   unsere Antwort – nach dem 250 ms `MIN_CALL_GAP` – eintrifft). Bei
   `MAX_AUTH_SENDS = 3` reichten die Versuche dann **nicht**: „Auth endgültig
   gescheitert" → **30 s Auth-Sperre** (`scheduleAuthRetry`), obwohl das Passwort
   korrekt ist. Genau das war der wiederkehrende Lange-Warten-Fall.

Der 429-Sturm aus dem Rest dieses Dokuments trat zusätzlich auf, wenn das Gerät
durch **schnelles Neustart-Churn** (Test/WLAN-Zucken) übersättigt war – dann half
nur Stille bzw. ein **Shelly-Reboot** (leert den 32er-Puffer sofort).

**Fix (klein, gemessen):**

- `MAX_AUTH_SENDS` 3 → **4** (1 Probe + 3 frische Challenges). Deckt das beobachtete
  Maximum (3 frische Challenges) ab, damit ein transienter 401 nicht als falsches
  Passwort gilt und die 30-s-Sperre auslöst.
- `CONNECT_SETTLE_MS` 1000 → **300 ms**: der frühere 1 s war Vorsorge gegen den
  Auth-Handshake; da der ohnehin nötig ist und der Poll separat gegated wird, genügt
  eine kurze Pause. Spart 700 ms pro Verbindung.

**Ergebnis am Gerät (3 schnelle Aus/Ein-Zyklen hintereinander):** vollständige
Anmeldung (bis „refreshSettings ok") in **~2,0 / 2,0 / 2,5 s**, **kein 429**,
**keine Auth-Sperre** – vorher ~33 s je Zyklus. Ein Zyklus brauchte 3 frische
Challenges und wäre mit `MAX_AUTH_SENDS = 3` in die 30-s-Sperre gelaufen.

**Zweiter Optimierungs-Schritt (gleicher Tag):** Der Anmelde-Schwanz besteht aus
~6 seriellen RPCs (Script.List, Script.GetCode, KVS, Schedule.List, Switch.GetStatus),
je mit `MIN_CALL_GAP`. Den globalen Abstand zu verkleinern brachte **Rückschritt**:
der Auth-Handshake (der gelegentlich 2–4 frische Challenges braucht) wurde damit
*unzuverlässiger* – enger getaktet „griff" keine Challenge mehr → Auth-Sperre. Also
**zweigeteilter Abstand** (`authEstablished`-Flag):

- Solange die Nonce **noch nicht bestätigt** ist (Handshake): `MIN_CALL_GAP = 250 ms`
  – konservativ, damit jede Challenge Zeit hat zu greifen.
- Sobald **ein** authentifizierter Call durchkam: `POST_AUTH_GAP = 120 ms` für alle
  weiteren Calls mit derselben Nonce (nc++). Das ist laut Szenario A (30 Calls,
  gleiche Nonce, ~34 ms Abstand → 0×429) völlig unkritisch. Bei neuer Nonce/Reconnect
  fällt das Flag zurück.

Am Gerät geladen (bis „refreshSettings ok") in **~1,4–1,8 s** statt ~2,0–2,5 s, ohne
den Handshake zu riskieren.

**Grenze (Hardware):** Weil die Nonce an die WS-Verbindung gebunden ist, erzwingt
**jeder** Neustart eine frische Challenge. **Sehr** schnelle Neustarts in Folge
(im Test ~9–24 s Abstand, dutzendfach) sättigen den 32er-Puffer → wieder 429,
egal wie die Abstände getunt sind. Ein frisches (rebootetes) Gerät schafft normale
Aus/Ein-Zyklen problemlos in ~1,5–2 s; nur Dauerbeschuss (Test) kippt es. Reale
Bedienung (paar Neustarts, nicht dutzende pro Minute) bleibt im schnellen Bereich.

## Quellen

- Shelly Technical Documentation – Authentication:
  <https://shelly-api-docs.shelly.cloud/gen2/General/Authentication/>
- Shelly Technical Documentation – RPC Channels:
  <https://shelly-api-docs.shelly.cloud/gen2/General/RPCChannels/>
- Shelly Technical Documentation – Common Errors:
  <https://shelly-api-docs.shelly.cloud/gen2/General/CommonErrors/>
