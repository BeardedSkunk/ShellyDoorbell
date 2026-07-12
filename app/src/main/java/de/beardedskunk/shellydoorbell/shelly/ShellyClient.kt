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

sealed class ConnectionState {
    /** Kein WLAN verfuegbar (oder keine IP konfiguriert). */
    data object NoWifi : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val deviceName: String) : ConnectionState()
}

class ShellyRpcException(val code: Int, message: String) : Exception(message) {
    /** true = Transportproblem (nicht verbunden/abgebrochen), false = echte Antwort vom Geraet. */
    val isTransport: Boolean get() = code in -3..-1
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
) {
    private val src = "dbellapp-" + Random.nextInt(0x100000).toString(16)
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

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
                val info = runCatching { call("Shelly.GetDeviceInfo") }.getOrNull()
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
            ws.cancel()
            failAllPending()
        }
        return helloOk
    }

    /**
     * Fuehrt einen RPC-Aufruf aus und liefert das result-Objekt.
     * @throws ShellyRpcException bei RPC-Fehler oder fehlender Verbindung.
     */
    suspend fun call(method: String, params: JSONObject? = null, timeoutMs: Long = 8_000): JSONObject {
        val ws = currentWs ?: throw ShellyRpcException(-1, "Nicht verbunden")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val frame = JSONObject()
            .put("id", id)
            .put("src", src)
            .put("method", method)
        if (params != null) frame.put("params", params)
        try {
            if (!ws.send(frame.toString())) throw ShellyRpcException(-2, "Senden fehlgeschlagen")
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
