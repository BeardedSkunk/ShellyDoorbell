package de.beardedskunk.shellydoorbell.shelly

import android.net.Network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

/** Max. Sendungen pro Call: 1 regulaer + 2 Auth-Wiederholungen mit frischer Challenge. */
private const val MAX_AUTH_SENDS = 3

sealed class ConnectionState {
    /** Kein WLAN verfuegbar (oder keine IP konfiguriert). */
    data object NoWifi : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val deviceName: String) : ConnectionState()
}

class ShellyRpcException(val code: Int, message: String) : Exception(message) {
    /** true = Transportproblem (nicht verbunden/abgebrochen), false = echte Antwort vom Geraet. */
    val isTransport: Boolean get() = code in -3..-1

    /** true = Geraet verlangt Authentifizierung (Passwort fehlt oder ist falsch). */
    val isAuth: Boolean get() = code == 401
}

/**
 * Haelt dauerhaft eine WebSocket-RPC-Verbindung zu ws://<ip>/rpc.
 *
 * Die Verbindung laeuft ausschliesslich ueber das uebergebene WLAN-[Network]
 * (SocketFactory + DNS daran gebunden), Mobilfunk wird nie benutzt. Bei
 * Verbindungsabbruch wird mit exponentiellem Backoff neu verbunden; wechselt
 * IP oder Netzwerk, wird die laufende Verbindung ersetzt.
 */
class ShellyClient(
    private val scope: CoroutineScope,
    private val ipFlow: StateFlow<String>,
    private val networkFlow: StateFlow<Network?>,
    private val passwordFlow: StateFlow<String>,
) {
    private val src = "dbellapp-" + Random.nextInt(0x100000).toString(16)
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    /** Zuletzt vom Geraet erhaltene Digest-Challenge (null = noch keine gesehen). */
    @Volatile
    private var cachedChallenge: ShellyAuth.Challenge? = null

    /** Nonce-Zaehler: pro authentifiziertem Request hochgezaehlt (siehe [ShellyAuth]). */
    private val ncCounter = AtomicInteger(0)

    /** Serialisiert das Lernen der Challenge, damit parallele erste Calls (z. B.
     *  onConnected + Live-Polling) nicht den nc-Zaehler gegeneinander verwuerfeln. */
    private val authMutex = Mutex()

    /** Haelt nc-Vergabe und Sendereihenfolge zusammen: der Shelly verlangt pro
     *  Nonce aufsteigende nc-Werte. Ohne dieses Lock koennten parallele Calls
     *  ihre Frames vertauscht auf den Draht bringen (nc=2 vor nc=1) — das Geraet
     *  wertet das kleinere nc dann als Replay und antwortet 401. */
    private val sendLock = Any()

    /**
     * true = das aktuelle Passwort wurde vom Geraet bereits als falsch/fehlend
     * abgewiesen. Dann NICHT weiter authentifiziert senden – sonst haemmern wir
     * den Shelly mit 401-Roundtrips, was er mit "429 too many requests" quittiert
     * und wofuer er Verbindungs-Slots verbraucht. Wird erst bei einem
     * Passwortwechsel ([resetAuth]) wieder freigegeben.
     */
    @Volatile
    private var authFailed = false

    /** Letzte Challenge-/401-Message – fuer schnelles Scheitern ohne erneuten Roundtrip. */
    @Volatile
    private var lastAuthMessage: String = "Authentifizierung erforderlich"

    /** Auth-Zustand komplett verwerfen und einen frischen Versuch erlauben. */
    private fun resetAuth() {
        cachedChallenge = null
        ncCounter.set(0)
        authFailed = false
    }

    /** Von aussen aufrufen, wenn IP/Passwort gewechselt wurden (sofortiger Reset). */
    fun credentialsChanged() = resetAuth()

    /** Explizite Neupruefung (z. B. „Verbindung pruefen“): genau einen frischen
     *  Auth-Versuch erlauben — das Passwort koennte ja am Shelly selbst geaendert
     *  worden sein, ohne dass sich in der App etwas tut. */
    fun retryAuth() = resetAuth()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.NoWifi)
    val state: StateFlow<ConnectionState> = _state

    /** NotifyEvent/NotifyStatus-Frames vom Shelly (rohe JSON-RPC-Nachrichten). */
    private val _notifications = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val notifications: SharedFlow<JSONObject> = _notifications

    /** Feuert nach jedem erfolgreichen (Re-)Connect, z. B. zum Settings-Abgleich. */
    private val _connectedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val connectedEvents: SharedFlow<Unit> = _connectedEvents

    @Volatile
    private var currentWs: WebSocket? = null

    @Volatile
    private var backoffSkip: CompletableDeferred<Unit>? = null

    fun start() {
        // Passwortwechsel: gecachte Challenge verwerfen, damit der naechste Call
        // frisch (mit neuem Passwort) authentifiziert. StateFlow dedupliziert schon.
        scope.launch { passwordFlow.collect { resetAuth() } }
        scope.launch {
            combine(ipFlow, networkFlow) { ip, net -> ip to net }
                .distinctUntilChanged()
                .collectLatest { (ip, net) ->
                    if (net == null || ip.isBlank()) {
                        _state.value = ConnectionState.NoWifi
                        return@collectLatest
                    }
                    val http = buildClient(net)
                    try {
                        var backoff = 1_000L
                        while (currentCoroutineContext().isActive) {
                            _state.value = ConnectionState.Connecting
                            val ok = runSession(http, ip)
                            _state.value = ConnectionState.Connecting
                            if (ok) backoff = 1_000L
                            // Warten bis zum naechsten Versuch; reconnectNow() bricht ab
                            val skip = CompletableDeferred<Unit>()
                            backoffSkip = skip
                            withTimeoutOrNull(backoff) { skip.await() }
                            backoff = min(backoff * 2, 30_000L)
                        }
                    } finally {
                        http.dispatcher.executorService.shutdown()
                        http.connectionPool.evictAll()
                    }
                }
        }
    }

    /** Backoff ueberspringen (z. B. wenn das WLAN gerade zurueckkam). */
    fun reconnectNow() {
        backoffSkip?.complete(Unit)
    }

    private fun buildClient(net: Network): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(net.socketFactory)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String) = net.getAllByName(hostname).toList()
        })
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    /** Baut eine Verbindung auf und blockiert, bis sie wieder zerfaellt. */
    private suspend fun runSession(http: OkHttpClient, ip: String): Boolean {
        // Frische Verbindung: Auth komplett neu aushandeln, auch der authFailed-
        // Merker faellt. Pro physischem Reconnect kostet das hoechstens einen
        // kurzen 401-Roundtrip, erkennt dafuer aber von selbst, wenn das Passwort
        // am Shelly geaendert oder abgeschaltet wurde. Den Dauerbeschuss (Ursache
        // fuer 429) verhindert weiterhin der Fast-Fail in call().
        resetAuth()
        val opened = CompletableDeferred<Boolean>()
        val closed = CompletableDeferred<Unit>()
        val ws = http.newWebSocket(
            Request.Builder().url("ws://$ip/rpc").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.complete(false)
                    closed.complete(Unit)
                    // Laufende Calls sofort scheitern lassen statt in ihr Timeout laufen
                    failAllPending()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                    failAllPending()
                }
            }
        )
        var helloOk = false
        try {
            if (opened.await()) {
                currentWs = ws
                // Erst nach erfolgreichem Hello gilt die Verbindung als steht;
                // ausserdem abonniert uns der Shelly damit fuer Notifications.
                // Hello braucht KEINE Auth – sonst wuerde der authFailed-Merker eines
                // falschen Passworts schon den Verbindungsaufbau blockieren.
                val info = runCatching { call("Shelly.GetDeviceInfo", requiresAuth = false) }.getOrNull()
                if (info != null) {
                    helloOk = true
                    val name = (info.opt("name") as? String)?.takeIf { it.isNotBlank() }
                        ?: info.optString("id", "Shelly")
                    _state.value = ConnectionState.Connected(name)
                    _connectedEvents.emit(Unit)
                    closed.await()
                }
            }
        } finally {
            currentWs = null
            // Sauber schliessen (Close-Handshake) statt hart abzubrechen, damit der
            // Shelly den Verbindungs-Slot sofort freigibt und nicht erst nach seinem
            // eigenen Timeout – sonst gehen ihm bei Reconnects die 6 Slots aus.
            if (!ws.close(1000, "bye")) ws.cancel()
            failAllPending()
        }
        return helloOk
    }

    /**
     * Fuehrt einen RPC-Aufruf aus und liefert das result-Objekt. Ist auf dem
     * Shelly Passwortschutz aktiv, wird bei einem 401 die Digest-Challenge aus
     * genau diesem Fehler uebernommen und der Call wiederholt (insgesamt max.
     * [MAX_AUTH_SENDS] Sendungen). Erst wenn auch der Versuch mit der frisch
     * vom Geraet gemeldeten Challenge scheitert, gilt das Passwort als falsch —
     * eine zwischenzeitlich rotierte/abgelaufene Nonce (Shelly-Neustart,
     * Replay-Erkennung) loest so keine falsche Passwort-Meldung mehr aus.
     * @throws ShellyRpcException bei RPC-Fehler oder fehlender Verbindung.
     */
    suspend fun call(
        method: String,
        params: JSONObject? = null,
        timeoutMs: Long = 8_000,
        requiresAuth: Boolean = true,
    ): JSONObject {
        // Passwort bereits als falsch/fehlend erkannt: gar nicht erst senden. So
        // haemmern wir den Shelly nicht mit 401-Roundtrips (sein Brute-Force-
        // Schutz antwortet darauf sonst mit "429 too many requests"). Wieder
        // freigegeben durch Passwortwechsel, Reconnect oder retryAuth().
        if (requiresAuth && authFailed) throw ShellyRpcException(401, lastAuthMessage)
        var attempt = 1
        while (true) {
            try {
                return sendFrame(method, params, timeoutMs)
            } catch (e: ShellyRpcException) {
                if (e.code != 401) throw e
                lastAuthMessage = e.message ?: lastAuthMessage
                val challenge = ShellyAuth.Challenge.parse(e.message)
                authMutex.withLock {
                    if (authFailed) throw ShellyRpcException(401, lastAuthMessage)
                    if (challenge == null || passwordFlow.value.isBlank() || attempt >= MAX_AUTH_SENDS) {
                        authFailed = true
                        throw e
                    }
                    adoptChallenge(challenge)
                }
                attempt++
            }
        }
    }

    /**
     * Uebernimmt die vom Geraet gemeldete Challenge (Aufrufer haelt [authMutex]).
     * Neue Nonce: cachen und den Zaehler auf den vom Geraet erwarteten Stand
     * setzen. Gleiche Nonce: Zaehler hoechstens vorspulen, nie zuruecksetzen —
     * sonst wuerden parallele Calls (oder ein zweites Handy, das dieselbe Nonce
     * benutzt) bereits verbrauchte nc-Werte erneut vergeben.
     */
    private fun adoptChallenge(challenge: ShellyAuth.Challenge) {
        val expected = challenge.nc - 1
        if (cachedChallenge?.nonce != challenge.nonce) {
            cachedChallenge = challenge
            ncCounter.set(expected)
        } else if (ncCounter.get() < expected) {
            ncCounter.set(expected)
        }
    }

    private suspend fun sendFrame(method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
        val ws = currentWs ?: throw ShellyRpcException(-1, "Nicht verbunden")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val frame = JSONObject()
            .put("id", id)
            .put("src", src)
            .put("method", method)
        if (params != null) frame.put("params", params)
        val password = passwordFlow.value
        try {
            // Auth anhaengen und senden in einem Rutsch (siehe [sendLock]) —
            // send() reiht den Frame nur in OkHttps Schreibqueue ein und
            // blockiert nicht, das Lock ist also billig.
            val sent = synchronized(sendLock) {
                val challenge = cachedChallenge
                if (challenge != null && password.isNotBlank()) {
                    val nc = ncCounter.incrementAndGet()
                    val cnonce = Random.nextInt().toLong() and 0xFFFFFFFFL
                    frame.put("auth", ShellyAuth.authObject(challenge, nc, cnonce, password))
                }
                ws.send(frame.toString())
            }
            if (!sent) throw ShellyRpcException(-2, "Senden fehlgeschlagen")
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun handleMessage(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (msg.has("id")) {
            val deferred = pending.remove(msg.optInt("id")) ?: return
            val err = msg.optJSONObject("error")
            if (err != null) {
                deferred.completeExceptionally(
                    ShellyRpcException(err.optInt("code"), err.optString("message", "RPC-Fehler"))
                )
            } else {
                deferred.complete(msg.optJSONObject("result") ?: JSONObject())
            }
        } else {
            _notifications.tryEmit(msg)
        }
    }

    private fun failAllPending() {
        val error = ShellyRpcException(-3, "Verbindung getrennt")
        val ids = pending.keys.toList()
        for (id in ids) pending.remove(id)?.completeExceptionally(error)
    }
}
