package de.beardedskunk.shellydoorbell.service

import android.os.SystemClock
import android.util.Log
import de.beardedskunk.shellydoorbell.data.Prefs
import de.beardedskunk.shellydoorbell.shelly.ConnectionState
import de.beardedskunk.shellydoorbell.shelly.GateDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Entscheidet, ob/ wie oft die App in einem WLAN versucht, den Shelly zu erreichen —
 * damit sie in Fremdnetzen (Supermarkt, Buero) nicht sinnlos dauernd verbindet.
 *
 * Drei Stufen, von billig nach schlau:
 *  1. **Subnetz-Tor** (ohne Berechtigung): liegt die Shelly-IP nicht im selben
 *     Subnetz wie die eigene WLAN-IP, ist er hier unerreichbar -> gar nicht versuchen.
 *  2. **Whitelist** (SSIDs, in denen der Shelly schon erreichbar war): dort wird
 *     gemuetlich (max. alle 60 s) weiterversucht, falls er mal nicht antwortet.
 *  3. **Greylist** (SSIDs, in denen 10 min lang nichts ging): dort nur EINE Probe je
 *     WLAN-Beitritt, danach nur alle 30 min. Unbekannte SSIDs eskalieren 5 s -> 30 min
 *     und landen nach 10 min erfolglos automatisch auf der Greylist.
 *
 * Eine frische Verbindung streicht die SSID aus der Greylist und nimmt sie in die
 * Whitelist ([onConnected]). "Verbindung pruefen"/Reconnect ueberspringt das Tor
 * (forced=true). Ohne SSID-Berechtigung bleibt nur Stufe 1 + eskalierender Backoff.
 */
class WifiGate(private val prefs: Prefs, private val scope: CoroutineScope) {

    @Volatile private var ssid: String? = null
    @Volatile private var myIpv4: ByteArray? = null
    @Volatile private var prefixLen: Int = 0

    private val lock = Any()
    private var whitelist = mutableSetOf<String>()
    private var greylist = mutableMapOf<String, Long>() // ssid -> letzter Versuch (epoch ms), 0 = noch keiner

    /** Seit wann (elapsedRealtime) versuchen wir auf der aktuellen SSID erfolglos? */
    @Volatile private var failSinceMs = 0L
    @Volatile private var failSsid: String? = null

    /** Listen aus dem DataStore laden (einmal beim Start). */
    suspend fun load() {
        val w = prefs.getWhitelist()
        val g = prefs.getGreylist()
        synchronized(lock) {
            whitelist = w.toMutableSet()
            greylist = g.toMutableMap()
        }
    }

    /** Aus den Netzwerk-Callbacks: aktuelle SSID (null = unbekannt/keine Berechtigung),
     *  eigene IPv4 (4 Bytes) und Prefix-Laenge. */
    fun onNetwork(ssid: String?, ipv4: ByteArray?, prefix: Int) {
        val joined = ssid != this.ssid
        this.ssid = ssid
        this.myIpv4 = ipv4
        this.prefixLen = prefix
        if (joined) {
            // Neues WLAN betreten -> Fehlversuchs-Timer neu (erlaubt u. a. die eine
            // Greylist-Probe je Beitritt).
            failSsid = ssid
            failSinceMs = SystemClock.elapsedRealtime()
            Log.d(TAG, "WLAN gewechselt: ssid=${ssid ?: "?"}, prefix=$prefix")
        }
    }

    /** Verbindung steht: aktuelle SSID whitelisten (und aus der Greylist nehmen). */
    fun onConnected() {
        val s = ssid ?: return
        var changed = false
        synchronized(lock) {
            if (greylist.remove(s) != null) changed = true
            if (whitelist.add(s)) changed = true
        }
        failSinceMs = SystemClock.elapsedRealtime()
        if (changed) {
            Log.i(TAG, "SSID '$s' als erreichbar gemerkt (Whitelist)")
            persist()
        }
    }

    /** Vor jedem Verbindungsversuch aufgerufen (siehe ShellyClient). */
    fun decide(shellyIp: String, forced: Boolean): GateDecision {
        if (forced) return GateDecision.Attempt(WHITELIST_MAX_MS)

        // Stufe 1: Subnetz-Tor (braucht keine Berechtigung).
        if (!subnetPlausible(shellyIp)) {
            return GateDecision.Block(
                ConnectionState.OtherNetwork("Anderes WLAN – Klingel ($shellyIp) hier nicht erreichbar."),
                RECHECK_WRONG_NET_MS,
            )
        }

        // Ohne SSID (keine Berechtigung / noch unbekannt): nur Subnetz-Tor +
        // eskalierender Backoff, keine Listen.
        val s = ssid ?: return GateDecision.Attempt(UNKNOWN_MAX_MS)

        synchronized(lock) {
            if (whitelist.contains(s)) return GateDecision.Attempt(WHITELIST_MAX_MS)

            if (!greylist.containsKey(s)) {
                // Unbekannt: eskalierend versuchen; nach 10 min erfolglos -> Greylist.
                val failingLongEnough =
                    failSsid == s && SystemClock.elapsedRealtime() - failSinceMs > GREYLIST_AFTER_MS
                if (!failingLongEnough) return GateDecision.Attempt(UNKNOWN_MAX_MS)
                greylist[s] = 0L
                Log.i(TAG, "SSID '$s' auf Greylist (10 min ohne Klingel)")
                persist()
                // faellt in die Greylist-Behandlung unten
            }

            // Greylist: eine Probe je Beitritt (last==0), danach alle 30 min.
            val last = greylist[s] ?: 0L
            val nowMs = System.currentTimeMillis()
            if (last == 0L || nowMs - last >= GREYLIST_RECHECK_MS) {
                greylist[s] = nowMs
                persist()
                return GateDecision.Attempt(GREYLIST_MAX_MS)
            }
            val wait = (GREYLIST_RECHECK_MS - (nowMs - last)).coerceAtLeast(60_000L)
            return GateDecision.Block(
                ConnectionState.OtherNetwork("Klingel in diesem WLAN bisher nicht gefunden – seltene Prüfung."),
                wait,
            )
        }
    }

    private fun subnetPlausible(shellyIp: String): Boolean {
        val mine = myIpv4 ?: return true          // eigene IP unbekannt -> nicht blockieren
        if (prefixLen !in 1..32) return true
        val shelly = parseIpv4(shellyIp) ?: return true // Shelly nicht als IPv4 -> nicht blockieren
        return sameSubnet(mine, shelly, prefixLen)
    }

    private fun persist() {
        val w: Set<String>
        val g: Map<String, Long>
        synchronized(lock) {
            w = whitelist.toSet()
            g = greylist.toMap()
        }
        scope.launch {
            prefs.setWhitelist(w)
            prefs.setGreylist(g)
        }
    }

    companion object {
        private const val TAG = "WifiGate"

        /** Whitelist: hoechstens alle 60 s neu versuchen, wenn der Shelly schweigt. */
        private const val WHITELIST_MAX_MS = 60_000L

        /** Unbekannte SSID: Backoff eskaliert bis hoechstens alle 30 min. */
        private const val UNKNOWN_MAX_MS = 30 * 60_000L

        /** Greylist: nur alle 30 min ein Versuch. */
        private const val GREYLIST_MAX_MS = 30 * 60_000L

        /** So lange erfolglos auf einer unbekannten SSID -> Greylist. */
        private const val GREYLIST_AFTER_MS = 10 * 60_000L

        /** Greylist-Wiedervorlage nach einer Probe. */
        private const val GREYLIST_RECHECK_MS = 30 * 60_000L

        /** Falsches Subnetz: nur selten neu bewerten (Link-Wechsel weckt sofort). */
        private const val RECHECK_WRONG_NET_MS = 30 * 60_000L

        /** "1.2.3.4" -> 4 Bytes; null bei ungueltigem IPv4-Literal. */
        fun parseIpv4(s: String): ByteArray? {
            val parts = s.trim().split(".")
            if (parts.size != 4) return null
            val b = ByteArray(4)
            for (i in 0..3) {
                val n = parts[i].toIntOrNull() ?: return null
                if (n !in 0..255) return null
                b[i] = n.toByte()
            }
            return b
        }

        /** Liegen a und b unter den oberen [prefix] Bits im selben Subnetz? */
        fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
            var bits = prefix
            for (i in 0..3) {
                if (bits <= 0) break
                val take = if (bits >= 8) 8 else bits
                val mask = (0xFF shl (8 - take)) and 0xFF
                if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
                bits -= 8
            }
            return true
        }
    }
}
