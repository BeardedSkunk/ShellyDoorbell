package de.beardedskunk.shellydoorbell.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

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

    private val ip = MutableStateFlow("")
    private val wifi = MutableStateFlow<Network?>(null)
    private val uiVisible = MutableStateFlow(false)
    private var alarmUri: String? = null

    private val _watts = MutableStateFlow<Double?>(null)
    val watts: StateFlow<Double?> = _watts

    private val _bellOn = MutableStateFlow<Boolean?>(null)
    val bellOn: StateFlow<Boolean?> = _bellOn

    private val _shared = MutableStateFlow<SharedSettings?>(null)
    val shared: StateFlow<SharedSettings?> = _shared

    /** null = noch nicht geladen; sonst die Liste der Klingelzeiten auf dem Shelly. */
    private val _bellTimes = MutableStateFlow<List<BellEntry>?>(null)
    val bellTimes: StateFlow<List<BellEntry>?> = _bellTimes

    /** null = unbekannt, false = doorbell-Script fehlt/gestoppt auf dem Shelly. */
    private val _scriptOk = MutableStateFlow<Boolean?>(null)
    val scriptOk: StateFlow<Boolean?> = _scriptOk

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    val connectionState: StateFlow<ConnectionState> get() = client.state
    val alarmActive: StateFlow<Boolean> get() = alarm.active

    private var scriptId: Int? = null
    private var lastAlarmAtMs = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val refreshMutex = Mutex()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        dao = AppDb.get(this).ringDao()
        alarm = AlarmController(this, scope)
        client = ShellyClient(scope, ip, wifi)

        startForegroundCompat()
        requestWifi()
        client.start()

        scope.launch {
            prefs.settings.collect {
                ip.value = it.ip
                alarmUri = it.alarmUri
            }
        }
        scope.launch { client.state.collect { updateServiceNotification(it) } }
        scope.launch { client.connectedEvents.collect { onConnected() } }
        scope.launch { client.notifications.collect { handleNotification(it) } }
        scope.launch {
            // Live-Watt nur pollen, solange die App sichtbar ist
            combine(uiVisible, client.state) { visible, st -> visible && st is ConnectionState.Connected }
                .distinctUntilChanged()
                .collectLatest { pollingActive ->
                    if (!pollingActive) return@collectLatest
                    while (true) {
                        runCatching { pollStatus() }
                        delay(2_000)
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
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        alarm.stop()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Netzwerk (nur WLAN, nie Mobilfunk) ----------

    private fun requestWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifi.value = network
                client.reconnectNow()
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

    // ---------- Verbindungsaufbau / Settings-Abgleich ----------

    private suspend fun onConnected() {
        runCatching { findScript() }
        runCatching { refreshSettings() }
            .onFailure { _messages.tryEmit("Einstellungen konnten nicht geladen werden: ${it.message}") }
    }

    private suspend fun findScript() {
        scriptId = null
        val scripts = client.call("Script.List").optJSONArray("scripts")
        if (scripts != null) {
            for (i in 0 until scripts.length()) {
                val s = scripts.optJSONObject(i) ?: continue
                if (s.optString("name") == "doorbell") {
                    if (s.optBoolean("running")) scriptId = s.optInt("id")
                    break
                }
            }
        }
        _scriptOk.value = scriptId != null
    }

    private suspend fun refreshSettings() {
        refreshMutex.withLock {
            val kv = kvsGetMany("dbell_*")
            _shared.value = SharedSettings(
                thresholdW = kv.num("dbell_cfg_threshold_w") ?: DEFAULT_THRESHOLD_W,
                debounceS = kv.num("dbell_cfg_debounce_s")?.toInt() ?: DEFAULT_DEBOUNCE_S,
            )
            refreshBellTimes(kv)
            runCatching { pollStatus() }
            mergeKvsLog(kv)
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
                        "doorbell_cfg" -> scope.launch { runCatching { refreshSettings() } }
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
        val power = data?.optDouble("power", Double.NaN)?.takeIf { !it.isNaN() && it >= 0 }
        scope.launch { dao.insertAll(listOf(RingEvent(ts, power))) }
        startAlarm()
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
    }

    fun reconnect() = client.reconnectNow()

    fun setBell(on: Boolean) {
        scope.launch {
            runCatching {
                client.call("Switch.Set", JSONObject().put("id", 0).put("on", on))
                pollStatus()
            }.onFailure { _messages.tryEmit("Schalten fehlgeschlagen: ${it.message}") }
        }
    }

    fun saveShared(thresholdW: Double, debounceS: Int) {
        scope.launch {
            runCatching {
                kvsSet("dbell_cfg_threshold_w", thresholdW)
                kvsSet("dbell_cfg_debounce_s", debounceS)
                _shared.value = SharedSettings(thresholdW, debounceS)
                notifyScriptCfgChanged()
            }.onFailure { _messages.tryEmit("Speichern fehlgeschlagen: ${it.message}") }
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
                _messages.tryEmit("Klingelzeit anlegen fehlgeschlagen: ${it.message}")
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
                _messages.tryEmit("Klingelzeit löschen fehlgeschlagen: ${it.message}")
                runCatching { refreshSettings() }
            }
        }
    }

    private fun currentIds(): List<Pair<Int, Int>> = _bellTimes.value.orEmpty().map { it.onId to it.offId }

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
        // Der Nutzer hat gerade bewusst an den Klingelzeiten gedreht: Schalter
        // sofort auf den erwarteten Zustand bringen (innerhalb eines Fensters ->
        // an, ausserhalb -> aus; ohne Klingelzeiten gehoert die Klingel an).
        val windows = _bellTimes.value.orEmpty().filter { it.enabled }
        val expectedOn = windows.isEmpty() || windows.any { it.window.isInsideNow() }
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
                val ts = arr.optLong(i)
                if (ts > MIN_VALID_TS) events.add(RingEvent(ts))
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

    private fun startForegroundCompat() {
        val notification = buildServiceNotification(getString(R.string.notif_connecting))
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

    private fun updateServiceNotification(state: ConnectionState) {
        val text = when (state) {
            is ConnectionState.Connected -> getString(R.string.notif_listening)
            ConnectionState.Connecting -> getString(R.string.notif_connecting)
            ConnectionState.NoWifi -> getString(R.string.notif_no_wifi)
        }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID_SERVICE, buildServiceNotification(text))
        }
    }

    companion object {
        const val ACTION_STOP_ALARM = "de.beardedskunk.shellydoorbell.STOP_ALARM"

        private const val NOTIF_ID_SERVICE = 1
        private const val NOTIF_ID_RING = 2

        /** Aeltere Timestamps gelten als "keine echte Uhrzeit" (Shelly ohne NTP). */
        private const val MIN_VALID_TS = 1_000_000_000L
        private const val PRUNE_AFTER_S = 400L * 24 * 3600

        const val DEFAULT_THRESHOLD_W = 2.0
        const val DEFAULT_DEBOUNCE_S = 30

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DoorbellService::class.java))
        }
    }
}
