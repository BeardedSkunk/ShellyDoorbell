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
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import de.beardedskunk.shellydoorbell.AlarmActivity
import de.beardedskunk.shellydoorbell.Channels
import de.beardedskunk.shellydoorbell.DoorIntents
import de.beardedskunk.shellydoorbell.MainActivity
import de.beardedskunk.shellydoorbell.OpenDoorActivity
import de.beardedskunk.shellydoorbell.R
import de.beardedskunk.shellydoorbell.WireGuard
import de.beardedskunk.shellydoorbell.data.AppDb
import de.beardedskunk.shellydoorbell.data.EventLog
import de.beardedskunk.shellydoorbell.data.Prefs
import de.beardedskunk.shellydoorbell.data.RingDao
import de.beardedskunk.shellydoorbell.data.RingEvent
import de.beardedskunk.shellydoorbell.shelly.ConnectionState
import de.beardedskunk.shellydoorbell.shelly.BellEntry
import de.beardedskunk.shellydoorbell.shelly.BellTimes
import de.beardedskunk.shellydoorbell.shelly.BellWindow
import de.beardedskunk.shellydoorbell.shelly.GateDecision
import de.beardedskunk.shellydoorbell.shelly.Link
import de.beardedskunk.shellydoorbell.shelly.SharedSettings
import de.beardedskunk.shellydoorbell.shelly.ShellyClient
import de.beardedskunk.shellydoorbell.shelly.ShellyRpcException
import de.beardedskunk.shellydoorbell.ui.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private lateinit var homeZone: HomeZone
    private lateinit var events: EventLog

    /** true, sobald die Dauer-Notification mit dem FGS-Typ location laeuft.
     *  false heisst: Berechtigung fehlt ODER das System hat den Typ beim Start
     *  abgelehnt (Dienst kam aus dem Hintergrund) – dann nachholen, sobald die
     *  UI sichtbar wird (siehe setUiVisible). */
    @Volatile private var locationFgsActive = false

    private val ip = MutableStateFlow("")
    private val password = MutableStateFlow("")
    private val wifi = MutableStateFlow<Network?>(null)

    /** Aktuelles VPN-Netz (WireGuard-Tunnel) oder null. Ohne Berechtigung beobachtbar. */
    private val vpn = MutableStateFlow<Network?>(null)

    /** „Auch unterwegs erreichbar" (LocalSettings.awayEnabled). */
    private val awayEnabled = MutableStateFlow(false)

    /** Name des WireGuard-Tunnels nach Hause (LocalSettings.wgTunnel), leer = keiner. */
    private val wgTunnel = MutableStateFlow("")

    /** Letzte Aktion der Tunnel-Automatik, fuer die Einstellungen-Karte (null = noch keine). */
    private val _tunnelAuto = MutableStateFlow<String?>(null)
    val tunnelAuto: StateFlow<String?> = _tunnelAuto

    /** true, wenn die Automatik den stehenden Tunnel selbst eingeschaltet hat. Nur den darf sie
     *  beim Abschalten des Schalters wieder ausmachen — einen vom Nutzer von Hand gestarteten
     *  Tunnel nicht (der kann andere Gruende haben). */
    @Volatile private var appRaisedTunnel = false
    private var awaySinceMs = 0L        // seit wann der WLAN-Pfad ohne Heimnetz ist (0 = nicht)
    private var lastUpMs = 0L           // letzte AN-Sendung (elapsedRealtime)
    private var lastDownMs = 0L         // letzte AUS-Sendung
    private var upPendingSinceMs = 0L   // AN gesendet, VPN-Netz noch nicht da (0 = nichts offen)

    /** true, solange ein WLAN mit bekannt-gutem Namen (Whitelist) anliegt — also das Heim-WLAN.
     *  Gespeist vom [WifiWatcher], sobald der Name da ist. Steht dann der Tunnel, ist das
     *  genau der Fall, in dem er abgeschaltet gehoert (siehe notifView). */
    private val homeWifi = MutableStateFlow(false)

    /**
     * Das Netz, ueber das der Client den Shelly erreichen soll.
     *
     * **Der Tunnel hat Vorrang, sobald er steht** — nicht aus Vorliebe, sondern weil Android es so
     * erzwingt: Ein VPN ist standardmaessig nicht umgehbar und faengt auch Sockets ein, die
     * ausdruecklich ans WLAN gebunden sind (am 23.08.2026 vom Nutzer beobachtet: Tunnel an im
     * Heim-WLAN, Klingel unerreichbar). Ein stehender Tunnel IST also der Weg, ob man will oder
     * nicht; ehrlicher ist, ihn dann auch so zu benutzen und zu benennen. Ohne den Schalter
     * „Auch unterwegs erreichbar" zaehlt ein VPN nicht — dann kann es auch ein fremdes sein
     * (Firmen-VPN, Privatsphaere-Dienst), und die App verhaelt sich exakt wie vor v1.3.
     */
    private val link: StateFlow<Link?> = combine(wifi, vpn, awayEnabled) { w, v, away ->
        when {
            away && v != null -> Link(v, tunnel = true)
            w != null -> Link(w, tunnel = false)
            else -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Fuer die UI: steht gerade ein VPN-Netz? (unabhaengig davon, ob wir es benutzen) */
    val vpnUp: StateFlow<Boolean> = vpn.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Fuer die UI: laeuft die Verbindung ueber den Tunnel? */
    val viaTunnel: StateFlow<Boolean> = link.map { it?.tunnel == true }.stateIn(scope, SharingStarted.Eagerly, false)

    /** true, sobald „Verbinde …" schon [STUCK_AFTER_MS] am Stueck steht — die Versuche scheitern
     *  also, das ist kein normaler Reconnect mehr (der dauert daheim Sekunden). */
    private val stuck = MutableStateFlow(false)

    /**
     * Ein VPN steht, das Heim-WLAN liegt an, und die Verbindung kommt seit einer Weile nicht
     * zustande: Das ist der Fall „Tunnel an im Heim-WLAN" — das VPN faengt den Verkehr ein (siehe
     * [link]). Gilt **unabhaengig vom Schalter** „Auch unterwegs erreichbar": Auch ohne ihn weiss
     * die App das alles und soll es sagen, statt ewig „Verbinde …" zu zeigen. Am Verhalten aendert
     * es nichts, nur an der Anzeige. (Ein VPN, das das Heimnetz nicht routet, wuerde die Klingel
     * gar nicht blockieren — dann kommt die Verbindung zustande und der Hinweis erscheint nie.)
     */
    val vpnBlocking: StateFlow<Boolean> = combine(vpnUp, homeWifi, stuck) { up, hw, st -> up && hw && st }
        .stateIn(scope, SharingStarted.Eagerly, false)

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

    /** "Ruhe bis": Unix-Sekunden, null = keine temporaere Stummschaltung (temp. AUS). */
    private val _muteUntil = MutableStateFlow<Long?>(null)
    val muteUntil: StateFlow<Long?> = _muteUntil

    /** "Einschalten um": Unix-Sekunden, null = keine temporaere Einschaltung (temp. EIN).
     *  Gegenstueck zu [_muteUntil]; es ist immer hoechstens EINES von beiden gesetzt. */
    private val _onAt = MutableStateFlow<Long?>(null)
    val onAt: StateFlow<Long?> = _onAt

    /** null = unbekannt, false = doorbell-Script fehlt/gestoppt auf dem Shelly. */
    private val _scriptOk = MutableStateFlow<Boolean?>(null)
    val scriptOk: StateFlow<Boolean?> = _scriptOk

    /** null = unbekannt; sonst die vom Script per Heartbeat gemeldete Version. */
    private val _scriptVersion = MutableStateFlow<Int?>(null)
    val scriptVersion: StateFlow<Int?> = _scriptVersion

    /** true = Lausch-Betrieb (kein Passwort, keine schreibenden Aufrufe). */
    private val listenOnly = MutableStateFlow(false)

    /**
     * true, sobald [onConnected] fuer die aktuelle Verbindung einmal komplett
     * durchgelaufen ist (Auth etabliert, Einstellungen geladen). Erst dann darf
     * der Live-Watt-Poll seine eigenen Switch.GetStatus feuern – sonst rennt er
     * beim Connect gegen onConnected und beide schicken parallel die erste
     * authentifizierte Anfrage an den schwachen Shelly (429-Ursache). Faellt bei
     * Verbindungsverlust zurueck auf false.
     */
    private val initialLoadDone = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    val connectionState: StateFlow<ConnectionState> get() = client.state
    val alarmActive: StateFlow<Boolean> get() = alarm.active

    private var scriptId: Int? = null
    private var lastAlarmAtMs = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null
    private val refreshMutex = Mutex()

    /** Verhindert, dass onConnected und „Verbindung pruefen" das Script parallel einspielen. */
    private val scriptMutex = Mutex()

    /** Zeitpunkt (elapsedRealtime) des letzten Script-Heartbeats, 0 = keiner. */
    @Volatile
    private var lastHeartbeatMs = 0L

    /** Zeitpunkt (elapsedRealtime), seit dem die aktuelle Verbindung steht; 0 = keine. */
    @Volatile
    private var connectedSinceMs = 0L

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
        events = EventLog(filesDir)
        wifiGate = WifiGate(prefs, scope)
        homeZone = HomeZone(this, prefs, scope)
        runBlocking {
            wifiGate.load()
            homeZone.load()
        }
        // Netzwerk-Tor: entscheidet vor jedem Verbindungsversuch. Zuerst die
        // Ortung — wissen wir SICHER, dass wir ausserhalb der Homezone sind, gar
        // nicht erst versuchen (ausser bei „Verbindung pruefen"/Reconnect = forced).
        // Sonst wie bisher das Subnetz-/SSID-Tor (WifiGate).
        // Reihenfolge ist Absicht: erst die kostenlosen Tore (SSID-Whitelist, Subnetz), die
        // Ortung ganz zuletzt — und nur im mehrdeutigen Fall, also einem FREMDEN WLAN, dessen
        // Subnetz zufaellig passt (192.168.178.x ist FRITZ!Box-Voreinstellung, das kommt bei
        // Nachbarn und Freunden durchaus vor).
        //
        // Frueher wurde die Homezone ZUERST gefragt. Seit nicht mehr dauernd gemessen wird, waere
        // das eine Falle: Ein stehengebliebenes "unterwegs" haette auch im eigenen Heim-WLAN
        // blockiert. Siehe docs/standort-nur-wenn-noetig.md.
        // Ueber den Tunnel wird dieses Tor gar nicht gefragt (siehe Link) — hier geht es nur ums WLAN.
        client = ShellyClient(scope, ip, link, password) { ipStr, forced ->
            val decision = wifiGate.decide(ipStr, forced)
            // Die Ortung kommt erst dran, wenn die Versuche in diesem Netz schon eine Weile
            // scheitern. Sonst fragt sie bei jedem WLAN-Zucken — und Zucken gibt es reichlich
            // (siehe Ereignisprotokoll: "verbinde / kein WLAN / verbinde" binnen Sekunden). Die
            // Whitelist-Abkuerzung allein reicht dafuer NICHT: Beim Beitritt kommt zuerst
            // onAvailable, der WLAN-Name erst danach — in dieser Luecke ist isKnownGood() blind.
            // Steht die Verbindung wieder (daheim eine Sache von Sekunden), kann die Ortung
            // ohnehin nichts beitragen; die laufende Verbindung ist der bessere Beweis.
            if (forced ||
                decision !is GateDecision.Attempt ||
                wifiGate.isKnownGood() ||
                wifiGate.failingForMs() < HOME_ASK_AFTER_MS
            ) {
                decision
            } else if (homeZone.verdict(wifi.value) == HomeStatus.OUTSIDE) {
                GateDecision.Block(
                    ConnectionState.OtherNetwork("Homezone: ausserhalb"),
                    HOME_OUTSIDE_RECHECK_MS,
                )
            } else {
                decision
            }
        }
        // Kein homeZone.start() mehr: Gemessen wird nur noch dann, wenn das Tor es braucht
        // (siehe verdict()). Frueher lief hier ein Dauerabo auf allen Providern.

        // Ohne lokalen Alarm laeuft der Dienst nur fuer die sichtbare UI mit —
        // dann ohne Dauer-Notification (und er beendet sich, wenn die UI zugeht).
        val initial = runBlocking { prefs.settings.first() }
        Log.i(TAG, "onCreate: ip=${initial.ip}, lauschmodus=${initial.listenOnly}, lokalerAlarm=${initial.alarmEnabled}")
        events.log(
            "Dienst gestartet (ip=${initial.ip}, lauschmodus=${initial.listenOnly}, " +
                "lokalerAlarm=${initial.alarmEnabled})",
        )
        ip.value = initial.ip
        password.value = initial.password
        alarmUri = initial.alarmUri
        alarm.prepare(alarmUri?.let { Uri.parse(it) })
        localAlarmEnabled = initial.alarmEnabled
        listenOnly.value = initial.listenOnly
        awayEnabled.value = initial.awayEnabled
        wgTunnel.value = initial.wgTunnel
        if (localAlarmEnabled) startForegroundCompat()
        requestWifi()
        requestVpn()
        client.start()

        scope.launch {
            prefs.settings.collect {
                ip.value = it.ip
                password.value = it.password
                alarmUri = it.alarmUri
                // Tonwechsel: den neuen Ton gleich vorbereiten (siehe AlarmController.prepare).
                alarm.prepare(alarmUri?.let { u -> Uri.parse(u) })
                val listenChanged = listenOnly.value != it.listenOnly
                listenOnly.value = it.listenOnly
                if (awayEnabled.value != it.awayEnabled) {
                    awayEnabled.value = it.awayEnabled
                    events.log("Unterwegs-Modus ${if (it.awayEnabled) "ein" else "aus"}geschaltet")
                    // Schalter aus, und der stehende Tunnel stammt von uns: wieder ausmachen.
                    if (!it.awayEnabled && appRaisedTunnel && vpn.value != null) {
                        if (WireGuard.setTunnel(this@DoorbellService, wgTunnel.value, up = false)) {
                            appRaisedTunnel = false
                            events.log("Tunnel-Automatik: aus (Unterwegs-Modus abgeschaltet)")
                            _tunnelAuto.value = "ausgeschaltet ${Fmt.time(System.currentTimeMillis() / 1000)} (Modus aus)"
                        }
                    }
                }
                wgTunnel.value = it.wgTunnel
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
                val via = if (link.value?.tunnel == true) " ueber Tunnel" else ""
                val label = when (it) {
                    is ConnectionState.Connected -> "verbunden (${it.deviceName})$via"
                    ConnectionState.Connecting -> "verbinde$via"
                    ConnectionState.NoWifi -> if (awayEnabled.value) "kein WLAN, kein Tunnel" else "kein WLAN"
                    is ConnectionState.OtherNetwork -> "anderes Netz (${it.reason})"
                }
                Log.d(TAG, "Verbindungszustand: $label")
                events.log("Verbindung: $label")
            }
        }
        scope.launch {
            // „Verbinde …" mit Stoppuhr: Steht der Zustand laenger als STUCK_AFTER_MS, scheitern
            // die Versuche. collectLatest setzt die Uhr bei jedem Zustandswechsel zurueck.
            client.state.collectLatest { st ->
                stuck.value = false
                if (st == ConnectionState.Connecting) {
                    delay(STUCK_AFTER_MS)
                    stuck.value = true
                }
            }
        }
        scope.launch {
            // Lebenszeichen ins Protokoll: eine Luecke darin heisst, dass der
            // Dienst gar nicht lief (vom System beendet, Handy aus). Ohne diese
            // Zeilen liesse sich "es war ruhig" nicht von "die App war weg"
            // unterscheiden — beides sieht im Protokoll sonst gleich aus.
            while (true) {
                delay(EventLog.ALIVE_MINUTES * 60_000L)
                val hbAgo = if (lastHeartbeatMs == 0L) "nie" else
                    "${(SystemClock.elapsedRealtime() - lastHeartbeatMs) / 1000}s"
                events.log(
                    "laeuft (verbindung=${client.state.value::class.simpleName}, " +
                        "letztesLebenszeichenVomScript=$hbAgo)",
                )
            }
        }
        scope.launch {
            // Dauer-Notification aus mehreren Quellen zusammensetzen: Verbindungs-
            // zustand, Ruhe/Klingelzeiten und Homezone bestimmen Farbe, DND-Badge
            // und Text (siehe notifView).
            // muteUntil/onAt (die beiden temporaeren Schaltpunkte) zu einem Paar
            // buendeln, damit combine bei fuenf Quellen bleibt.
            val tempSwitch = combine(_muteUntil, _onAt) { mute, on -> mute to on }
            // Homezone und Tunnel-Lage zu einem Kontext buendeln (combine bleibt bei fuenf Quellen).
            val vpnLage = combine(viaTunnel, awayEnabled, vpnUp, vpnBlocking) { tunnel, away, up, blocking ->
                listOf(tunnel, away, up, blocking)
            }
            val ctx = combine(homeZone.status, vpnLage) { home, (tunnel, away, up, blocking) ->
                NetCtx(home, tunnel, away, up, blocking)
            }
            combine(
                client.state,
                tempSwitch,
                _bellOn,
                _bellTimes,
                ctx,
            ) { state, (mute, on), bell, times, c ->
                notifView(state, mute, on, bell, times, c)
            }.distinctUntilChanged().collect { updateServiceNotification(it) }
        }
        scope.launch {
            // Die Ruhe-Anzeige ("morgen 9:00" / naechster Fensterbeginn) ist
            // relativ zur aktuellen Zeit. Ohne Anstoss von aussen bliebe sie ueber
            // Mitternacht stehen ("morgen 9:00", obwohl morgen laengst heute ist).
            // Minuetlich (an der Minutengrenze) neu aufbauen — updateService-
            // Notification postet nur bei echter Aenderung neu.
            while (true) {
                delay(msToNextMinute())
                // Vor dem Neubau den Schalterzustand nachziehen, wenn gerade eine
                // Fenstergrenze passiert wurde — sonst baut die Anzeige auf einem
                // veralteten _bellOn auf (siehe catchUpBellState).
                catchUpBellState()
                updateServiceNotification(currentNotifView())
            }
        }
        scope.launch {
            // Jeder Ortswechsel weckt den Reconnect-Loop, damit er das Netzwerk-Tor
            // neu befragt: nach Hause -> nicht bis zur Wiedervorlage im „Unterwegs"-
            // Block haengen bleiben; und umgekehrt beim Wegfahren sofort auf
            // „Unterwegs" umschalten, statt bis zu 30 min weiter „Verbinde …" zu
            // zeigen und die Klingel in einem fremden Netz zu suchen.
            homeZone.status.collect { client.reconnectNow() }
        }
        scope.launch {
            client.connectedEvents.collect {
                if (link.value?.tunnel == true) {
                    // Ueber den Tunnel darf NICHTS gelernt werden: Das Handy steht dabei womoeglich
                    // in einem fremden WLAN an einem fremden Ort (beim Vater) — der WLAN-Name
                    // gehoert nicht auf die Whitelist, der Ort nicht in die Homezone. Gemerkt wird
                    // nur, dass der Tunnel nachweislich zur Klingel fuehrt.
                    events.log("Klingel ueber den Tunnel erreicht")
                    scope.launch { runCatching { prefs.setTunnelReachedAt(System.currentTimeMillis()) } }
                } else {
                    // Erreichbar in diesem WLAN -> SSID whitelisten, Homezone lernen.
                    wifiGate.onConnected()
                    homeZone.recordConnected()
                }
                onConnected()
            }
        }
        scope.launch { client.notifications.collect { handleNotification(it) } }
        scope.launch {
            // Verbindungsverlust setzt das Heartbeat-Fenster zurueck, damit ein
            // frischer Connect erst wieder auf ein Lebenszeichen wartet. Ausserdem
            // das Initial-Load-Flag, damit der Poll nach einem Reconnect erst
            // wieder nach onConnected loslaeuft.
            client.state.collect { st ->
                if (st !is ConnectionState.Connected) {
                    lastHeartbeatMs = 0L
                    connectedSinceMs = 0L
                    initialLoadDone.value = false
                } else if (connectedSinceMs == 0L) {
                    connectedSinceMs = SystemClock.elapsedRealtime()
                }
            }
        }
        scope.launch {
            // Bleibt das 30-s-Lebenszeichen des Scripts aus, obwohl die Verbindung
            // steht, gilt das Script als nicht laufend.
            var staleRounds = 0      // aufeinanderfolgende stumme Pruefrunden (alle 30 s)
            var silentRebuilds = 0   // Neuaufbauten ohne ein einziges Lebenszeichen dazwischen
            while (true) {
                delay(30_000)
                val last = lastHeartbeatMs
                val since = connectedSinceMs
                val now = SystemClock.elapsedRealtime()
                // Auch eine Verbindung, die seit ihrem Aufbau NOCH NIE ein Lebenszeichen bekommen
                // hat, gilt nach STALE_MS als stumm. Frueher zaehlte nur „hatte eins, jetzt keins
                // mehr" — eine von Anfang an taube Verbindung (siehe ShellyClient.src) fiel so
                // nie auf und stand beliebig lange auf „verbunden".
                val stale = client.state.value is ConnectionState.Connected && (
                    (last != 0L && now - last > STALE_MS) ||
                        (last == 0L && since != 0L && now - since > STALE_MS)
                    )
                if (!stale) {
                    staleRounds = 0
                    if (last != 0L) silentRebuilds = 0
                    continue
                }
                _scriptOk.value = false
                staleRounds++
                // Kein Lebenszeichen, aber die Verbindung gilt als offen: entweder steht das
                // Script, oder der Socket ist eine Leiche (Doze), oder der Kanal ist taub
                // (Antworten ja, Broadcasts nein — siehe ShellyClient.src). Erste stumme Runde:
                // EINMAL nachfragen. Das holt den echten Stand (vor allem den Schalterzustand,
                // den ein anderes Geraet umgelegt haben kann) und laesst einen toten Socket
                // auffliegen (Transportfehler -> der Client verbindet neu).
                if (staleRounds == 1) {
                    if (!listenOnly.value && initialLoadDone.value && client.rateLimitedForMs() <= 0) {
                        Log.i(TAG, "Kein Heartbeat seit >${STALE_MS / 1000}s – Zustand einmal aktiv nachfragen")
                        runCatching { pollStatus() }
                    }
                    continue
                }
                // Weiter stumm: Das heilt nur ein Neuaufbau — ein gestopptes Script setzt
                // onConnected dabei wieder in Gang, ein tauber Kanal bekommt eine frische
                // Kennung. Bleibt es auch danach stumm (Script wirklich weg), wird der Abstand
                // jedes Mal eine Minute laenger, Deckel zehn Minuten — kein Dauerfeuer auf den
                // schwachen Shelly, aber auch kein ewiges „verbunden" ohne Klingel.
                if (staleRounds >= 2 + 2 * minOf(silentRebuilds, 9)) {
                    Log.w(TAG, "Weiter kein Heartbeat – Verbindung neu aufbauen (Nr. ${silentRebuilds + 1})")
                    events.log("Verbindung stumm (kein Lebenszeichen) -> Neuaufbau")
                    staleRounds = 0
                    silentRebuilds++
                    client.close()
                }
            }
        }
        scope.launch {
            // Tunnel-Automatik (Schritt 2b, docs/vpn-von-unterwegs.md): alle 10 s nachsehen.
            // Eine Uhr statt Ereignisse, weil die Bedingungen Dauer brauchen („2 min ohne
            // Heimnetz") und das WLAN des Pixel im Minutentakt zuckt.
            while (true) {
                delay(TUNNEL_POLL_MS)
                runCatching { tunnelAutomation() }.onFailure { Log.w(TAG, "Tunnel-Automatik: $it") }
            }
        }
        scope.launch {
            // Live-Watt nur pollen, solange die App sichtbar ist — und nicht im
            // Lauschmodus (Switch.GetStatus wuerde ohne Passwort 401 spammen;
            // Watt/Schalterzustand kommen dort ohnehin per NotifyStatus). Live-
            // Aenderungen (Klingeln, Schalten) pusht der Shelly per NotifyStatus,
            // deshalb reicht ein gemaechlicher Poll fuer die reine Anzeige.
            combine(uiVisible, client.state, listenOnly, initialLoadDone) { visible, st, listen, loaded ->
                // loaded: erst pollen, wenn onConnected durch ist (Auth steht,
                // Nonce gecacht) – sonst Erst-Auth-Rennen mit onConnected.
                visible && !listen && loaded && st is ConnectionState.Connected
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
        scope.launch {
            alarm.active.collect {
                events.log(if (it) "Alarm laeuft" else "Alarm aus")
                if (!it) cancelRingNotification()
            }
        }
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
            startForegroundCompat(currentNotifView())
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!uiVisible.value) stopSelf()
        }
    }

    override fun onDestroy() {
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        vpnCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        events.log("Dienst beendet")
        homeZone.stop()
        alarm.release()
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
        val watcher = WifiWatcher()
        // Der Dauer-Callback laeuft bewusst OHNE FLAG_INCLUDE_LOCATION_INFO. Mit Flagge ist
        // naemlich JEDE Zustellung ein Standortzugriff (der WLAN-Name ist eine Ortsangabe), und
        // zugestellt wird etwa jede Minute — am Geraet gemessen eins zu eins gegen den
        // appop-Zeitstempel. Den Namen holt stattdessen ein kurzlebiger zweiter Callback, einmal
        // je WLAN-Beitritt (siehe WifiWatcher.startSsidProbe).
        val callback: ConnectivityManager.NetworkCallback = PlainWifiCallback(watcher)
        networkCallback = callback
        // requestNetwork (statt registerNetworkCallback) haelt das WLAN aktiv,
        // auch wenn das System sonst auf Mobilfunk wechseln wuerde.
        cm.requestNetwork(request, callback)
    }

    /**
     * Den WireGuard-Tunnel beobachten (nur beobachten — `registerNetworkCallback`, kein
     * `requestNetwork`: Ein VPN fordert man nicht an, es ist da oder nicht).
     *
     * **Falle:** Ein `NetworkRequest` hat `NET_CAPABILITY_NOT_VPN` voreingestellt. Ohne das
     * `removeCapability` unten bekaeme dieser Callback nie ein VPN-Netz zu sehen.
     *
     * Keine Berechtigung, keine Ortung: Ein VPN-Netz traegt keinen WLAN-Namen, es gibt hier nichts
     * zu schwaerzen.
     */
    private fun requestVpn() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (vpn.value != network) {
                    Log.i(TAG, "VPN-Netz da: $network")
                    events.log("VPN-Tunnel steht")
                }
                vpn.value = network
                upPendingSinceMs = 0L
                client.reconnectNow()
            }

            override fun onLost(network: Network) {
                if (vpn.value != network) return
                Log.i(TAG, "VPN-Netz weg: $network")
                events.log("VPN-Tunnel weg")
                vpn.value = null
                client.reconnectNow()
            }
        }
        vpnCallback = cb
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onFailure { Log.w(TAG, "VPN-Callback nicht registrierbar: $it") }
    }

    /**
     * Die eigentliche Auswertung der Netzwerk-Rueckrufe. Steht getrennt von den Callback-Klassen,
     * weil es davon zwei geben muss: Den Konstruktor mit Flagge gibt es erst ab Android 12, und in
     * Kotlin laesst sich der Super-Konstruktor nicht zur Laufzeit waehlen.
     */
    private inner class WifiWatcher {
        /** Aktuelles WLAN. Jeder Beitritt liefert ein neues [Network] — das ist der
         *  berechtigungsfreie "WLAN gewechselt"-Ausloeser fuer die Homezone. */
        private var net: Network? = null
        private var ssid: String? = null
        private var ipv4: ByteArray? = null
        private var prefix = 0

        /** Kurzlebiger Callback mit Standort-Flagge, solange ein Name gesucht wird. */
        private var probe: ConnectivityManager.NetworkCallback? = null
        private var probeJob: Job? = null

        fun available(network: Network) {
            wifi.value = network
            if (net != network) {
                net = network
                // Neues Netz -> altes Ortsurteil gilt nicht mehr. Gemessen wird erst, wenn das
                // Tor es wirklich braucht (siehe HomeZone.verdict), und dann genau einmal.
                homeZone.onNetworkChanged(network)
                // Hier bewusst KEIN wifiGate.onNetwork(): Der WLAN-Name kommt erst mit
                // onCapabilitiesChanged, und ein hier durchgereichtes null wuerde den bekannten
                // Namen fuer ein paar Millisekunden loeschen — genau lange genug, dass die
                // Whitelist-Abkuerzung im Tor danebengreift. Die beiden Rueckrufe unten liefern
                // Netz und Name gemeinsam und kommen unmittelbar hinterher.
                startSsidProbe()
            }
            client.reconnectNow()
        }

        fun capabilities(network: Network, caps: NetworkCapabilities) {
            // Ab Android 12 ist der Name in diesem (ungeflaggten) Callback geschwaerzt. Ein null
            // darf den bereits bekannten Namen NICHT loeschen — sonst waere die
            // Whitelist-Abkuerzung dauerhaft blind. Den Namen liefert [startSsidProbe].
            readSsid(caps)?.let { ssid = it }
            wifiGate.onNetwork(network, ssid, ipv4, prefix)
            homeWifi.value = wifiGate.isKnownGood()
        }

        /**
         * **Einen** WLAN-Namen holen, dann sofort wieder abmelden.
         *
         * Nur ein Callback mit `FLAG_INCLUDE_LOCATION_INFO` bekommt den echten Namen — und jede
         * Zustellung an so einen Callback ist ein Standortzugriff. Am Geraet gemessen (21.08.2026)
         * kam etwa jede Minute eine, eins zu eins gegen den appop-Zeitstempel; das allein liess
         * die Standortanzeige praktisch dauerhaft leuchten. Deshalb ist die Anmeldung so kurz wie
         * moeglich: einmal je WLAN-Beitritt, Abmeldung beim ersten Namen.
         */
        private fun startSsidProbe() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return // dort schwaerzt nichts
            if (probe != null) return
            if (!homeZone.hasPermission()) return
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val cb = SsidProbeCallback(this)
            probe = cb
            val ok = runCatching {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build(),
                    cb,
                )
            }.isSuccess
            if (!ok) {
                probe = null
                return
            }
            // Notbremse: Kommt kein Name (Ortung aus, Berechtigung entzogen), nicht angemeldet
            // bleiben — sonst haetten wir den Dauerzugriff durch die Hintertuer zurueck.
            probeJob = scope.launch {
                delay(SSID_PROBE_MAX_MS)
                stopSsidProbe()
            }
        }

        fun stopSsidProbe() {
            val cb = probe ?: return
            probe = null
            probeJob?.cancel()
            probeJob = null
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        }

        /** Zustellung des kurzlebigen Callbacks — hier kommt der Name wirklich an. */
        fun probed(network: Network, caps: NetworkCapabilities) {
            val name = readSsid(caps) ?: return
            ssid = name
            Log.d(TAG, "WLAN-Name geholt: $name")
            wifiGate.onNetwork(network, name, ipv4, prefix)
            homeWifi.value = wifiGate.isKnownGood()
            stopSsidProbe()
        }

        fun linkProperties(network: Network, lp: LinkProperties) {
            val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
            ipv4 = (la?.address as? Inet4Address)?.address
            prefix = la?.prefixLength ?: 0
            wifiGate.onNetwork(network, ssid, ipv4, prefix)
        }

        fun lost(network: Network) {
            // Beim Wechsel A->B kann onLost(A) NACH onAvailable(B) eintreffen. Dann gehoert der
            // Rueckruf zum alten Netz und darf den neuen Stand nicht ueberschreiben.
            if (net != null && net != network) return
            stopSsidProbe()
            net = null
            ssid = null
            ipv4 = null
            prefix = 0
            if (wifi.value == network) wifi.value = null
            homeWifi.value = false
            // Ohne WLAN wird ohnehin kein Verbindungsversuch unternommen — eine Messung
            // koennte an nichts etwas aendern. Nur das Urteil verfaellt.
            homeZone.onNetworkChanged(null)
            wifiGate.onNetwork(null, null, null, 0)
        }
    }

    /** Vor Android 12: Standort-Infos sind noch nicht geschwaerzt. */
    private class PlainWifiCallback(private val w: WifiWatcher) :
        ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = w.available(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            w.capabilities(network, caps)
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
            w.linkProperties(network, lp)
        override fun onLost(network: Network) = w.lost(network)
    }

    /**
     * Ab Android 12 kommt der WLAN-Name nur mit `FLAG_INCLUDE_LOCATION_INFO` durch — und jede
     * Zustellung an so einen Callback ist ein Standortzugriff. Deshalb ist diese Klasse bewusst
     * **kurzlebig** und hoert nur auf das eine, was sie liefern soll; abgemeldet wird beim ersten
     * Namen (siehe WifiWatcher.startSsidProbe). Auf aelteren Systemen wird sie nie geladen.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private class SsidProbeCallback(private val w: WifiWatcher) :
        ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            w.probed(network, caps)
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
        // Kurz durchatmen lassen: der Hello (Shelly.GetDeviceInfo) lief eben erst.
        // Ein kleiner Moment Abstand vor dem ersten authentifizierten Call gibt dem
        // schwachen Geraet nach dem Socket-Aufbau Luft. Seit die Nonce ueber
        // Neustarts hinweg wiederverwendet wird (kein 401-Roundtrip mehr), genuegt
        // eine kurze Pause – die frueheren 1000 ms waren gegen den Auth-Handshake.
        delay(CONNECT_SETTLE_MS)
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
        // Auth steht und die Nonce ist gecacht -> der Live-Watt-Poll darf jetzt
        // ohne Erst-Auth-Rennen seine eigenen Switch.GetStatus schicken.
        initialLoadDone.value = true
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
            val nowS = System.currentTimeMillis() / 1000
            _muteUntil.value = kv.num("dbell_mute_until")?.toLong()?.takeIf { it > nowS }
            _onAt.value = kv.num("dbell_on_at")?.toLong()?.takeIf { it > nowS }
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
        events.log("Klingel-Ereignis empfangen (lokalerAlarm=$localAlarmEnabled)")
        scope.launch { recordProvisional(ts) }
        // Lokal stummgeschaltet: Ereignis landet trotzdem in der History
        if (!localAlarmEnabled) return
        // „Nicht stoeren" am Handy heisst: nicht stoeren — daheim wie unterwegs, WLAN wie Tunnel
        // (festgelegt am 23.08.2026). Dann gibt es statt Weckerton und Vollbild eine stille
        // Benachrichtigung. AUSSER der Nutzer hat in den Einstellungen „Nicht stoeren durchbrechen"
        // eingeschaltet (Nicht-stoeren-Zugriff erteilt, Kanal darf durchbrechen): Dann klingelt es
        // trotz „Nicht stoeren" wie immer — genau dafuer ist die Einstellung da. Die Klingel im
        // Flur laeutet in jedem Fall; wer auch die abstellen will, nimmt „Ruhe bis".
        if (systemDndActive() && !Channels.canBypassDnd(this)) {
            events.log("Klingeln leise gemeldet (Nicht stoeren ist an, kein Durchbrechen)")
            postQuietRingNotification()
        } else {
            startAlarm()
        }
    }

    /**
     * Schritt 2b: Die App schaltet den WireGuard-Tunnel selbst. Regeln (bewusst traege, siehe
     * docs/vpn-von-unterwegs.md):
     *
     *  - **AN**, wenn der WLAN-Pfad seit [TUNNEL_UP_AFTER_MS] am Stueck ohne Heimnetz ist
     *    (`NoWifi` oder `OtherNetwork`) und kein VPN-Netz steht — egal ob unterwegs oder daheim
     *    mit WLAN aus und Mobilfunk an. Solange in einem WLAN noch versucht wird (`Connecting`),
     *    nie: Das koennte das Heimnetz sein.
     *  - **AUS**, sobald bei stehendem Tunnel das Heim-WLAN anliegt (Name in der Whitelist) —
     *    sofort, denn ein stehender Tunnel faengt den WLAN-Verkehr ein und macht die Klingel
     *    daheim unerreichbar. Zweiter AUS-Grund: WLAN da und der Tunnel liefert seit 45 s keine
     *    Verbindung (`stuck`) — dann lieber das WLAN direkt probieren. Beim Vater (fremdes WLAN,
     *    Tunnel funktioniert) greift keiner von beiden: kein Whitelist-Name, Verbindung steht.
     *  - Wiederholungen fruehestens alle [TUNNEL_RETRY_MS]; ein AN, auf das binnen
     *    [TUNNEL_UP_TIMEOUT_MS] kein VPN-Netz folgt, landet als Hinweis in Protokoll und Karte
     *    (Name falsch oder Fernsteuerung in WireGuard nicht erlaubt — das laesst sich nicht
     *    abfragen, nur beobachten).
     *
     * Laeuft nur mit Schalter „Auch unterwegs erreichbar", eingetragenem Tunnelnamen und erteilter
     * Berechtigung. Alles andere ist die alte App.
     */
    private fun tunnelAutomation() {
        val name = wgTunnel.value
        if (!awayEnabled.value || name.isBlank() || !WireGuard.canControl(this)) {
            awaySinceMs = 0L
            return
        }
        val now = SystemClock.elapsedRealtime()
        val nowS = System.currentTimeMillis() / 1000
        if (vpn.value != null) {
            awaySinceMs = 0L
            val reason = when {
                homeWifi.value -> "Heim-WLAN da"
                wifi.value != null && stuck.value -> "WLAN da, Tunnel liefert nichts"
                else -> null
            }
            if (reason != null && now - lastDownMs >= TUNNEL_RETRY_MS) {
                lastDownMs = now
                if (WireGuard.setTunnel(this, name, up = false)) {
                    appRaisedTunnel = false
                    events.log("Tunnel-Automatik: aus ($reason)")
                    _tunnelAuto.value = "ausgeschaltet ${Fmt.time(nowS)} ($reason)"
                }
            }
            return
        }
        // Kein VPN-Netz. Ein offenes AN, dem nichts gefolgt ist, ist ein Hinweis wert.
        if (upPendingSinceMs != 0L && now - upPendingSinceMs > TUNNEL_UP_TIMEOUT_MS) {
            upPendingSinceMs = 0L
            events.log("Tunnel-Automatik: Tunnel '$name' kam nicht (Name pruefen, Fernsteuerung in WireGuard erlauben)")
            _tunnelAuto.value = "Tunnel '$name' kam nicht – Name prüfen, Fernsteuerung in WireGuard erlauben"
        }
        val st = client.state.value
        val away = st is ConnectionState.NoWifi || st is ConnectionState.OtherNetwork
        if (!away) {
            awaySinceMs = 0L
            return
        }
        if (awaySinceMs == 0L) awaySinceMs = now
        if (now - awaySinceMs < TUNNEL_UP_AFTER_MS) return
        if (now - lastUpMs < TUNNEL_RETRY_MS) return
        lastUpMs = now
        if (WireGuard.setTunnel(this, name, up = true)) {
            upPendingSinceMs = now
            appRaisedTunnel = true
            val why = if (st is ConnectionState.NoWifi) "kein WLAN" else "Fremdnetz"
            events.log("Tunnel-Automatik: an ($why)")
            _tunnelAuto.value = "eingeschaltet ${Fmt.time(nowS)} ($why)"
        }
    }

    /** Ist am Handy „Nicht stoeren" aktiv? Ohne Berechtigung lesbar (im Gegensatz zum Aendern). */
    private fun systemDndActive(): Boolean {
        val f = getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
            ?: return false
        return f != NotificationManager.INTERRUPTION_FILTER_ALL &&
            f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /**
     * Das leise Klingeln bei „Nicht stoeren": eine gewoehnliche Benachrichtigung auf dem Kanal
     * ohne DND-Durchbruch — kein Weckerton, keine Vibration, kein Vollbild, kein Anruf-Banner. Ob
     * sie sichtbar ist oder bis zum Ende von „Nicht stoeren" versteckt bleibt, entscheidet die
     * Nicht-stoeren-Einstellung des Nutzers, nicht die App. Nur „Tuer ansehen", falls die
     * Tuersprecher-App da ist (ueber den Tunnel funktioniert sie, videoapp ab v1.66).
     */
    private fun postQuietRingNotification() {
        val door = DoorIntents.doorIntent(this)
        val builder = NotificationCompat.Builder(this, Channels.RING_QUIET)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(getString(R.string.notif_ring_title))
            .setContentText(getString(R.string.notif_ring_quiet_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 4,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (door != null) {
            builder.addAction(
                0, getString(R.string.notif_ring_door),
                PendingIntent.getActivity(this, 5, door, PendingIntent.FLAG_IMMUTABLE),
            )
        }
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID_RING_QUIET, builder.build()) }
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
        // "Annehmen" = zur Tuersprecher-App springen (dort sieht und hoert man,
        // wer geklingelt hat) — der Alarm wird dabei gestoppt. Ist sie nicht
        // installiert, fuehrt Annehmen auf den Vollbild-Alarm der App selbst.
        val answerPi = if (DoorIntents.doorIntent(this) != null) {
            PendingIntent.getActivity(
                this, 3,
                Intent(this, OpenDoorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            fullScreenPi
        }
        // Als EINGEHENDER ANRUF melden, nicht als gewoehnliche Benachrichtigung:
        // Ein Klingeln an der Tuer ist genau das — man nimmt an und spricht mit
        // dem Besuch. Praktisch entscheidend ist, dass Android Anrufe anders
        // behandelt: sie werden ganz oben einsortiert und bleiben als auffaelliges
        // Banner stehen, AUCH waehrend man das Handy gerade benutzt. Vorher blieb
        // in dem Fall nur eine stille graue Zeile in der Leiste uebrig, weil der
        // Vollbild-Alarm systemseitig nur bei dunklem/gesperrtem Bildschirm
        // startet und der Alarmkanal bewusst lautlos ist (den Ton macht der
        // Dienst selbst, siehe AlarmController).
        //
        // Die Beschriftung der beiden Knoepfe gibt das System vor (Annehmen /
        // Ablehnen) — eigene Texte sind bei CallStyle nicht vorgesehen.
        val caller = Person.Builder()
            .setName(getString(R.string.notif_ring_caller))
            .setImportant(true)
            .build()
        val builder = NotificationCompat.Builder(this, Channels.alarmChannelId(this))
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(getString(R.string.notif_ring_title))
            .setContentText(getString(R.string.notif_ring_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            // Bleibt noetig: unter Android 12 faellt CallStyle auf eine normale
            // Benachrichtigung zurueck, und der Vollbild-Alarm ueber dem
            // Sperrbildschirm haengt ohnehin hieran. Ausserdem ist er die
            // Voraussetzung dafuer, dass CallStyle nicht herabgestuft wird.
            .setFullScreenIntent(fullScreenPi, true)
            .setOngoing(true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, stopPi, answerPi))
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID_RING, builder.build()) }
    }

    private fun cancelRingNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_RING)
    }

    // ---------- Kommandos aus der UI ----------

    fun setUiVisible(visible: Boolean) {
        uiVisible.value = visible
        if (visible) {
            // Wer die App aufmacht, weil die Anzeige nicht stimmt, soll damit etwas erreichen:
            // Das Urteil verfaellt, der naechste Verbindungsversuch misst also neu — aber eben
            // nur einmal und nur, wenn das Tor es ueberhaupt braucht.
            homeZone.invalidate()
            // Die Dauer-Notification ggf. auf den FGS-Typ location heben (die Berechtigung
            // koennte gerade erst erteilt worden sein; ohne den Typ kaeme der Dienst im
            // Hintergrund gar nicht an den Ort). Mit „immer zulassen" wird der Typ bewusst
            // NICHT gesetzt — siehe needsLocationFgsType().
            if (localAlarmEnabled && needsLocationFgsType() && !locationFgsActive) {
                startForegroundCompat(currentNotifView())
            }
        }
        // Ohne lokalen Alarm lief der Dienst nur fuer die UI mit
        if (!visible && !localAlarmEnabled) stopSelf()
    }

    /**
     * „App komplett beenden" aus dem Zurueck-Dialog: den Dauerdienst herunter-
     * fahren und die Dauer-Notification entfernen. Reines Backgrounden (Home /
     * App-Wechsel) laeuft NICHT hierueber — dort bleibt der Dienst absichtlich
     * im Vordergrund. onDestroy schaltet fuer den naechsten Start wieder scharf.
     */
    fun requestFullStop() {
        localAlarmEnabled = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Millisekunden bis zur naechsten vollen Minute (fuer den Anzeige-Ticker). */
    private fun msToNextMinute(): Long =
        (60_000L - System.currentTimeMillis() % 60_000L).coerceIn(1_000L, 60_000L)

    /** Zuletzt vom Zeitplan erwarteter Schalterzustand, null = noch unbekannt. */
    private var lastExpectedOn: Boolean? = null

    /**
     * Der Shelly schaltet an den Fenstergrenzen selbst — die Meldung darueber
     * (NotifyStatus) erreicht uns aber nur, wenn die Verbindung in dem Moment
     * wirklich lebt. Schlief das Handy (Doze), ist [_bellOn] danach veraltet:
     * die Klingel ging um 7:00 an, die Anzeige glaubt weiter "aus" — und der
     * naechste Fensterbeginn ist dann schon der von morgen.
     *
     * Deshalb einmal je Fenstergrenze (genauer: sobald der laut Zeitplan
     * erwartete Zustand kippt und nicht zum gemeldeten passt) den echten
     * Schalterzustand holen. Ein von Hand abgeschalteter Trafo pollt hier
     * NICHT dauerhaft mit — der erwartete Zustand kippt ja nur an der Grenze.
     */
    private suspend fun catchUpBellState() {
        // Ohne geladene Klingelzeiten waere "erwartet" geraten -> abwarten.
        if (_bellTimes.value == null) {
            lastExpectedOn = null
            return
        }
        val expected = expectedBellOn()
        val previous = lastExpectedOn
        lastExpectedOn = expected
        if (previous == null || previous == expected) return
        if (_bellOn.value == expected) return
        if (listenOnly.value || !initialLoadDone.value) return
        if (client.state.value !is ConnectionState.Connected) return
        if (client.rateLimitedForMs() > 0) return
        Log.i(TAG, "Fenstergrenze: erwartet ${if (expected) "EIN" else "AUS"}, gemeldet ${_bellOn.value} – Zustand nachziehen")
        runCatching { pollStatus() }
    }

    // Manueller „Neu verbinden"-Knopf: soll auch aus einem pausierten Zustand
    // (Fremdnetz/Greylist) heraus einen echten Versuch erzwingen.
    fun reconnect() {
        // Der Nutzer erwartet hier etwas: Urteil verwerfen, damit ein stehengebliebenes
        // "unterwegs" den Versuch nicht still abwuergt. forceAttempt() ueberspringt die Tore
        // ohnehin — das hier wirkt fuer die Versuche DANACH.
        homeZone.invalidate()
        client.forceAttempt()
    }

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
                // Manuelles Schalten hebt einen aktiven temporaeren Schaltpunkt auf
                // (wie am Hardware-Taster) — sonst wuerde der Timer die Klingel
                // gleich wieder zurueckstellen. Nur senden, wenn wirklich einer aktiv
                // war, um den schwachen Shelly nicht unnoetig zu belasten.
                val hadTemp = clearTempSwitchLocal()
                client.call("Switch.Set", JSONObject().put("id", 0).put("on", on))
                pollStatus()
                if (hadTemp) notifyScriptCfgChanged()
            }.onFailure { emitFailure(it, "Schalten fehlgeschlagen") }
        }
    }

    /**
     * Loescht einen etwaigen temporaeren Schaltpunkt (Ruhe bis / Einschalten um)
     * im KVS und im lokalen Zustand. Gibt zurueck, ob einer aktiv war.
     */
    private suspend fun clearTempSwitchLocal(): Boolean {
        var had = false
        if (_muteUntil.value != null) {
            kvsDelete("dbell_mute_until")
            _muteUntil.value = null
            had = true
        }
        if (_onAt.value != null) {
            kvsDelete("dbell_on_at")
            _onAt.value = null
            had = true
        }
        return had
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
                // Nur ein temporaerer Schaltpunkt zurzeit: eine etwaige
                // "Einschalten um"-Zeit verwerfen.
                if (_onAt.value != null) {
                    kvsDelete("dbell_on_at")
                    _onAt.value = null
                }
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

    /**
     * "Einschalten um": Gegenstueck zu [setMute]. Die Klingel ist gerade AUS und
     * soll zu [atTs] (vor dem naechsten regulaeren Fenster-Beginn) wieder AN.
     * Der Schalter bleibt einstweilen aus — das Shelly-Script schaltet zum
     * Zeitpunkt selbst ein (auch ohne verbundene App).
     */
    fun setOnAt(atTs: Long) {
        scope.launch {
            runCatching {
                // Nur ein temporaerer Schaltpunkt zurzeit: eine etwaige
                // "Ruhe bis"-Zeit verwerfen.
                if (_muteUntil.value != null) {
                    kvsDelete("dbell_mute_until")
                    _muteUntil.value = null
                }
                kvsSet("dbell_on_at", atTs)
                _onAt.value = atTs
                // Kein alignBell: die Klingel ist aus und soll es bis atTs bleiben.
                notifyScriptCfgChanged()
            }.onFailure {
                emitFailure(it, "Einschaltzeit setzen fehlgeschlagen")
                runCatching { refreshSettings() }
            }
        }
    }

    fun clearOnAt() {
        scope.launch {
            runCatching {
                kvsDelete("dbell_on_at")
                _onAt.value = null
                // Zurueck zu den normalen Klingelzeiten.
                alignBell()
                notifyScriptCfgChanged()
            }.onFailure {
                emitFailure(it, "Einschaltzeit beenden fehlgeschlagen")
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

    /**
     * Schalterzustand, den Klingelzeiten und temporaere Schaltpunkte gerade
     * vorsehen. Ein laufender temporaerer Schaltpunkt haelt die Klingel AUS und
     * hat Vorrang ("Ruhe bis" wie "Einschalten um" bis zum jeweiligen
     * Zeitpunkt); sonst: innerhalb eines Fensters -> an, ausserhalb -> aus;
     * ohne Klingelzeiten gehoert die Klingel an.
     */
    private fun expectedBellOn(): Boolean {
        val nowS = System.currentTimeMillis() / 1000
        val tempOff = (_muteUntil.value ?: 0) > nowS || (_onAt.value ?: 0) > nowS
        val windows = _bellTimes.value.orEmpty().filter { it.enabled }
        return !tempOff && (windows.isEmpty() || windows.any { it.window.isInsideNow() })
    }

    private suspend fun alignBell() {
        // Der Nutzer hat gerade bewusst an Klingelzeiten oder Ruhe gedreht:
        // Schalter sofort auf den erwarteten Zustand bringen.
        val expectedOn = expectedBellOn()
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

    /** Farbe des Zustands-Icons (ARGB). */
    private enum class NotifColor(val argb: Int) {
        /** verbunden */
        BLUE(0xFF1565C0.toInt()),
        /** nicht erreichbar / verbindet / anderes Netz */
        GREY(0xFF9E9E9E.toInt()),
        /** zu Hause, aber kein WLAN */
        RED(0xFFD32F2F.toInt()),
    }

    /** Gebuendelter Anzeigezustand der Dauer-Notification. */
    private data class NotifView(val color: NotifColor, val dnd: Boolean, val text: String)

    /** Netz-Lage fuer die Dauer-Notification: Homezone plus Tunnel (siehe notifView). */
    private data class NetCtx(
        val home: HomeStatus,
        /** Verbindung laeuft ueber den Tunnel. */
        val tunnel: Boolean,
        /** „Auch unterwegs erreichbar" ist an. */
        val away: Boolean,
        /** Ein VPN-Netz steht (ob benutzt oder nicht). */
        val vpnUp: Boolean,
        /** VPN steht, Heim-WLAN liegt an, Verbindung scheitert seit einer Weile (siehe [vpnBlocking]). */
        val vpnBlocking: Boolean,
    )

    /**
     * Braucht der Dauerdienst den FGS-Typ `location` ueberhaupt?
     *
     * **Nur ohne „Standort immer zulassen".** Mit `ACCESS_BACKGROUND_LOCATION` darf der Dienst den
     * Ort jederzeit lesen; der Typ bringt dann nichts — kostet aber die **dauerhafte
     * Standortanzeige in der Statusleiste**. Am Geraet gemessen (21.08.2026): Bei laufendem
     * location-FGS notiert das System FINE_LOCATION im Zustand `fgsvc` alle paar Sekunden weiter,
     * ohne dass die App irgendetwas tut — im Logcat des Prozesses steht in denselben Minuten
     * keine einzige Zeile. Genau das war der blaue Punkt, den auch das Abschalten des
     * Standort-Abos nicht wegbekommen hat.
     *
     * Ist der Standort nur „waehrend der Nutzung" erlaubt, bleibt der Typ noetig: Sonst kaeme der
     * Dienst aus dem Hintergrund gar nicht an den Ort.
     */
    private fun needsLocationFgsType(): Boolean =
        homeZone.hasPermission() && !homeZone.hasBackgroundPermission()

    private fun startForegroundCompat(view: NotifView = currentNotifView()) {
        lastPostedView = view
        val notification = buildServiceNotification(view)
        val hasLoc = needsLocationFgsType()
        if (Build.VERSION.SDK_INT < 34) {
            startForeground(NOTIF_ID_SERVICE, notification)
            locationFgsActive = hasLoc
            return
        }
        // FGS-Typ location nur mit erteilter Standort-Berechtigung ergaenzen –
        // sonst wirft startForeground eine SecurityException. Die erteilte
        // Berechtigung allein genuegt aber NICHT: ist sie „nur waehrend der
        // Nutzung" erteilt (kein ACCESS_BACKGROUND_LOCATION), verlangt Android 14+
        // zusaetzlich, dass die App gerade im Vordergrund ist. Startet der Dienst
        // aus dem Hintergrund (Boot, App-Update via MY_PACKAGE_REPLACED, Neustart
        // des Dienstes durch das System), lehnt es den Typ ab. Das laesst sich
        // nicht sicher vorhersagen – checkSelfPermission sieht nur die Erteilung,
        // nicht den App-Op-Zustand. Also versuchen und notfalls ohne den Typ
        // weiterlaufen; setUiVisible hebt ihn nach, sobald die UI aufgeht.
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (hasLoc) {
            try {
                startForeground(
                    NOTIF_ID_SERVICE,
                    notification,
                    base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
                locationFgsActive = true
                return
            } catch (e: SecurityException) {
                // Der Dienst ist hier noch NICHT im Vordergrund (das System prueft
                // den Typ, bevor es ihn hochstuft) – der zweite Versuch unten ist
                // also noetig, sonst stirbt der Dienst an einer ANR/Timeout.
                Log.i(TAG, "FGS-Typ location abgelehnt (App nicht im Vordergrund?), starte ohne: ${e.message}")
            }
        }
        startForeground(NOTIF_ID_SERVICE, notification, base)
        locationFgsActive = false
    }

    private fun buildServiceNotification(view: NotifView): Notification {
        val contentPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        // Kleines Icon: Glocke, im Ruhemodus das DND-Symbol.
        val smallIcon = if (view.dnd) R.drawable.ic_stat_dnd else R.drawable.ic_stat_bell
        // Kompakt 2-zeilig: Zeile 1 ist die (vom System gezeigte) App-Kopfzeile
        // „Klingelüberwachung", Zeile 2 der Status als Titel. KEIN separater
        // Content-Text und KEIN Titel=App-Name -> sonst stuende der Name doppelt.
        val b = NotificationCompat.Builder(this, Channels.SERVICE)
            .setSmallIcon(smallIcon)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: das App-Icon ist systemseitig fest (nicht pro Zustand
            // einfaerbbar). Deshalb faerben wir die GANZE Benachrichtigung in der
            // Zustandsfarbe. Ruhe/DND hat KEINE eigene Kartenfarbe (blau wie
            // „verbunden"), sondern ein grafisches rotes DND-Zeichen „⛔" vor dem Text.
            val cardColor = if (view.dnd) NotifColor.BLUE.argb else view.color.argb
            val title = if (view.dnd) "⛔ ${view.text}" else view.text
            b.setColorized(true)
                .setColor(cardColor)
                .setContentTitle(title)
        } else {
            // Android <= 11: Standard-Layout, setColor toent das kleine Header-Icon
            // in der Zustandsfarbe (Glocke blau/grau/rot, Ruhe = rotes DND-Symbol).
            val tint = if (view.dnd) NotifColor.RED.argb else view.color.argb
            b.setColor(tint).setContentTitle(view.text)
        }
        return b.build()
    }

    /**
     * Verbindungszustand + Ruhe/Klingelzeiten + Homezone -> Farbe, DND-Flag, Text.
     * Wie Farbe/DND dargestellt werden, entscheidet [buildServiceNotification] je
     * nach Android-Version (getoentes Icon <=11 / eingefaerbte Karte 12+):
     *  - verbunden & aktiv        -> blau, „lausche auf die Klingel"
     *  - verbunden & Klingel ruht -> blau + DND, „Ruhe bis …" / „abgestellt"
     *  - zu Hause, aber kein WLAN -> rot
     *  - sonst (verbindet/anderes Netz/kein WLAN) -> grau
     */
    private fun notifView(
        state: ConnectionState,
        muteUntil: Long?,
        onAt: Long?,
        bellOn: Boolean?,
        bellTimes: List<BellEntry>?,
        ctx: NetCtx,
    ): NotifView = when (state) {
        is ConnectionState.Connected -> {
            // Wahrheit ist der tatsaechliche Schalterzustand — exakt wie die
            // Klingel-Karte (BellCard) rechnet. Schaltet der Nutzer die Klingel
            // waehrend der Ruhe irgendwo (auch am Hardware-Taster) von Hand EIN,
            // ist bellOn==true und die Anzeige zeigt wieder „lausche", statt
            // stur DND zu behaupten. Ausserhalb der Klingelzeiten schaltet der
            // Shelly selbst aus -> bellOn==false deckt das mit ab (kein separates
            // „ausserhalb Fenster", das den manuellen Ein-Zustand ueberstimmt).
            val nowS = System.currentTimeMillis() / 1000
            val off = bellOn == false
            if (!off) {
                val text = if (ctx.tunnel) R.string.notif_listening_vpn else R.string.notif_listening
                NotifView(NotifColor.BLUE, false, getString(text))
            } else {
                val muted = (muteUntil ?: 0L) > nowS
                val onAtPending = (onAt ?: 0L) > nowS
                val windows = bellTimes.orEmpty().filter { it.enabled }.map { it.window }
                // Zeitpunkt, zu dem die Klingel automatisch wieder angeht — relativ
                // zur AKTUELLEN Zeit formatiert (siehe Minuten-Ticker in onCreate,
                // damit „morgen 9:00" nach Mitternacht zu „9:00" wird).
                val reactivateTs: Long? = when {
                    muted -> muteUntil
                    onAtPending -> onAt
                    // Laeuft gerade eine Klingelzeit, gehoert die Klingel laut
                    // Zeitplan AN — dieses Aus hat kein geplantes Ende (von Hand
                    // abgeschaltet) oder unser Schalterzustand ist veraltet. Der
                    // naechste Fensterbeginn liegt dann schon MORGEN; ihn als
                    // „Ruhe bis" zu melden, war der Grund, warum die Notification
                    // um 7:05 „Ruhe bis morgen 07:00" behauptete.
                    BellTimes.insideNow(windows) -> null
                    windows.isNotEmpty() -> BellTimes.nextStart(windows)
                    else -> null
                }
                val text = if (reactivateTs != null) {
                    getString(R.string.notif_listening_quiet_until, Fmt.muteUntil(reactivateTs))
                } else {
                    getString(R.string.notif_listening_quiet_off)
                }
                NotifView(NotifColor.BLUE, true, text)
            }
        }
        ConnectionState.Connecting -> when {
            // Ein VPN steht, das Heim-WLAN liegt auch an, und es klappt seit 45 s nicht: Das VPN
            // faengt den Verkehr ein, die Klingel ist nicht erreichbar (siehe vpnBlocking). Der
            // Nutzer kann das beheben — deshalb rot und eine Handlungsanweisung, nicht grau.
            ctx.vpnBlocking -> NotifView(NotifColor.RED, false, getString(R.string.notif_home_vpn_on))
            ctx.tunnel -> NotifView(NotifColor.GREY, false, getString(R.string.notif_connecting_vpn))
            else -> NotifView(NotifColor.GREY, false, getString(R.string.notif_connecting))
        }
        ConnectionState.NoWifi -> when {
            ctx.home == HomeStatus.INSIDE -> NotifView(NotifColor.RED, false, getString(R.string.notif_home_no_wifi))
            // Unterwegs-Modus an, aber kein Tunnel: Das ist der Hinweis, der hilft.
            ctx.away && !ctx.vpnUp -> NotifView(NotifColor.GREY, false, getString(R.string.notif_away_vpn_off))
            ctx.home == HomeStatus.OUTSIDE -> NotifView(NotifColor.GREY, false, getString(R.string.notif_away))
            else -> NotifView(NotifColor.GREY, false, getString(R.string.notif_no_wifi))
        }
        // Alle Fremdnetz-Faelle heissen gleich — falsches Subnetz, Greylist, Homezone. Woran die
        // App gemerkt hat, dass sie nicht daheim ist, aendert fuer den Nutzer nichts und stand
        // frueher nur als Rauschen in der Leiste (samt Shelly-IP). Der Grund steht im
        // Ereignisprotokoll, siehe ConnectionState.OtherNetwork.reason.
        is ConnectionState.OtherNetwork ->
            if (ctx.away && !ctx.vpnUp) {
                NotifView(NotifColor.GREY, false, getString(R.string.notif_away_vpn_off))
            } else {
                NotifView(NotifColor.GREY, false, getString(R.string.notif_away))
            }
    }

    private fun currentNotifView(): NotifView = notifView(
        client.state.value, _muteUntil.value, _onAt.value, _bellOn.value, _bellTimes.value,
        NetCtx(homeZone.status.value, viaTunnel.value, awayEnabled.value, vpnUp.value, vpnBlocking.value),
    )

    /** Zuletzt gepostete Ansicht — verhindert unnoetiges Neu-Posten (Minuten-Ticker). */
    @Volatile private var lastPostedView: NotifView? = null

    private fun updateServiceNotification(view: NotifView) {
        // Nicht im Vordergrund (Alarm lokal aus) -> notify() wuerde die gerade
        // entfernte Dauer-Notification wieder anheften
        if (!localAlarmEnabled) return
        if (view == lastPostedView) return
        lastPostedView = view
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID_SERVICE, buildServiceNotification(view))
        }
    }

    companion object {
        private const val TAG = "DoorbellSvc"

        const val ACTION_STOP_ALARM = "de.beardedskunk.shellydoorbell.STOP_ALARM"

        private const val NOTIF_ID_SERVICE = 1
        private const val NOTIF_ID_RING = 2
        private const val NOTIF_ID_RING_QUIET = 3

        /** Poll-Abstand fuer die reine Watt-Anzeige (Live-Aenderungen pusht der
         *  Shelly ohnehin per NotifyStatus – gemaechlich genuegt, spart Anfragen). */
        private const val POLL_INTERVAL_MS = 5_000L

        /** Verschnaufpause nach dem Verbinden, bevor der erste authentifizierte
         *  Call rausgeht – gibt dem schwachen Shelly nach dem Socket-Aufbau Luft. */
        private const val CONNECT_SETTLE_MS = 300L

        /** „Sicher unterwegs" (ausserhalb der Homezone): erst spaeter neu bewerten.
         *  Ein Standort-/Link-Wechsel weckt den Loop ohnehin sofort. */
        private const val HOME_OUTSIDE_RECHECK_MS = 10 * 60_000L

        /** So lange muessen die Versuche in einem Netz scheitern, bevor die Ortung ueberhaupt
         *  gefragt wird. Der Backoff hat dann schon vier Versuche hinter sich (5/10/20/40 s), und
         *  ein normaler Reconnect daheim (Sekunden) kommt hier nie an. Kuerzer -> der blaue Punkt
         *  kommt bei jedem WLAN-Zucken zurueck; laenger -> „Unterwegs" erscheint traeger. */
        private const val HOME_ASK_AFTER_MS = 45_000L

        /** So lange darf „Verbinde …" stehen, bevor es als „scheitert" gilt (siehe [stuck]). Gleich
         *  lang wie HOME_ASK_AFTER_MS, aus demselben Grund: Ein normaler Reconnect daheim kommt
         *  hier nie an, und das WLAN-Zucken auch nicht. */
        private const val STUCK_AFTER_MS = 45_000L

        /** Takt der Tunnel-Automatik (siehe tunnelAutomation). */
        private const val TUNNEL_POLL_MS = 10_000L

        /** So lange muss der WLAN-Pfad am Stueck ohne Heimnetz sein, bevor der Tunnel angeht.
         *  Kostet hoechstens ein Klingeln in den ersten zwei Minuten nach dem Verlassen des
         *  Hauses; dafuer reisst kein WLAN-Zucker daheim den Tunnel hoch (der die Klingel
         *  daheim abwuergen wuerde, siehe link). */
        private const val TUNNEL_UP_AFTER_MS = 2 * 60_000L

        /** Mindestabstand zwischen zwei gleichen Schaltbefehlen — WireGuard antwortet nicht, also
         *  nicht haemmern, wenn es nicht wirkt (Fernsteuerung nicht erlaubt, Name falsch). */
        private const val TUNNEL_RETRY_MS = 5 * 60_000L

        /** Kommt nach einem AN so lange kein VPN-Netz, hat WireGuard den Befehl verworfen. */
        private const val TUNNEL_UP_TIMEOUT_MS = 20_000L

        /** So lange bleibt der kurzlebige Callback mit Standort-Flagge hoechstens angemeldet.
         *  Kommt bis dahin kein WLAN-Name, kommt auch keiner mehr — dann lieber abmelden, sonst
         *  haetten wir den Dauerzugriff durch die Hintertuer zurueck. */
        private const val SSID_PROBE_MAX_MS = 20_000L

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
