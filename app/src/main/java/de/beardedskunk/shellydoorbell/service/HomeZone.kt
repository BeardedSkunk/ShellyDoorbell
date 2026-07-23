package de.beardedskunk.shellydoorbell.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import de.beardedskunk.shellydoorbell.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Wo befindet sich das Handy relativ zur gelernten Homezone? */
enum class HomeStatus {
    /** Standort spricht dafuer, dass wir zu Hause sind. */
    INSIDE,

    /** Standort spricht klar dagegen (weit weg) — Klingel gar nicht erst suchen. */
    OUTSIDE,

    /** Unbekannt: keine Berechtigung, Ortung aus, kein/veralteter Fix oder
     *  Homezone noch nicht gelernt. Im Zweifel NICHT blockieren. */
    UNKNOWN,
}

/**
 * Lernt und prueft die "Homezone": den Ort, an dem die Klingel zuletzt erreichbar
 * war (grob ~15 m). Arbeitet ergaenzend zum [WifiGate] (Subnetz/SSID): weiss die
 * App sicher, dass sie ausserhalb ist, spart sie sich den Verbindungsversuch; ist
 * sie zu Hause, aber ohne WLAN, kann die Notification darauf hinweisen.
 *
 * Bewusst ueber die Plattform-[LocationManager]-API (keine Play-Services). Der Ort
 * wird nur genutzt, solange der Foreground-Dienst laeuft (while-in-use), es wird
 * KEIN Hintergrund-Standort verlangt. Ohne Berechtigung/Ortung bleibt alles
 * [HomeStatus.UNKNOWN] und die App verhaelt sich wie bisher.
 */
class HomeZone(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val lm: LocationManager? = context.getSystemService(LocationManager::class.java)

    @Volatile private var homeLat: Double? = null
    @Volatile private var homeLon: Double? = null

    /** Zuletzt bekannter Standort (gecacht — das Gate liest ihn synchron). */
    @Volatile private var last: Location? = null

    /** true = beim Verbinden war kein Fix da; der naechste Fix lernt die Homezone. */
    @Volatile private var pendingLearn = false

    private val _status = MutableStateFlow(HomeStatus.UNKNOWN)
    val status: StateFlow<HomeStatus> = _status

    private var listener: LocationListener? = null

    /** Gelernten Mittelpunkt laden (einmal beim Start). */
    suspend fun load() {
        prefs.getHome()?.let { (lat, lon) ->
            homeLat = lat
            homeLon = lon
        }
        recompute()
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun locationEnabled(): Boolean = lm?.isLocationEnabled == true

    private fun providers(): List<String> {
        val m = lm ?: return emptyList()
        return buildList {
            runCatching { if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER) }
            runCatching { if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER) }
        }
    }

    /** Standort-Updates starten (idempotent). Nur mit Berechtigung + aktiver Ortung. */
    fun start() {
        if (listener != null) return
        val m = lm ?: return
        if (!hasPermission() || !locationEnabled()) return
        val l = listenerOf()
        listener = l
        runCatching {
            for (p in providers()) m.getLastKnownLocation(p)?.let { onLocation(it) }
            for (p in providers()) {
                m.requestLocationUpdates(p, UPDATE_MIN_TIME_MS, UPDATE_MIN_DIST_M, l, Looper.getMainLooper())
            }
            Log.i(TAG, "Standort-Updates aktiv (${providers().joinToString()}), letzter=${last != null}")
        }.onFailure { Log.w(TAG, "Standort-Updates nicht gestartet: ${it.message}") }
    }

    fun stop() {
        listener?.let { l -> runCatching { lm?.removeUpdates(l) } }
        listener = null
    }

    private fun onLocation(loc: Location) {
        val prev = last
        if (prev == null || loc.elapsedRealtimeNanos >= prev.elapsedRealtimeNanos) last = loc
        // Auf einen frischen Fix zum Lernen gewartet? Jetzt einloesen.
        if (pendingLearn) learnHome(loc)
        recompute()
    }

    /**
     * Verbindung stand: aktuellen Ort als Homezone merken/nachfuehren. Ist gerade
     * ein frischer Fix da, sofort lernen; sonst auf den naechsten warten
     * ([pendingLearn]) — direkt beim Verbinden ist der Standort-Cache oft noch leer.
     */
    fun recordConnected() {
        if (!hasPermission() || !locationEnabled()) {
            Log.i(TAG, "recordConnected: keine Berechtigung/Ortung -> nichts lernen")
            return
        }
        start() // falls die Berechtigung erst nach onCreate erteilt wurde
        // Bis ein hinreichend genauer Fix gelernt wurde, auf weitere Fixes warten.
        pendingLearn = true
        requestFreshFix() // genauen (GPS-)Fix anstossen
        bestRecent(LEARN_MAX_AGE_MS)?.let { learnHome(it) } // ggf. sofort lernen
    }

    /** Homezone auf [fix] setzen bzw. leicht nachfuehren (wenn genau genug). */
    private fun learnHome(fix: Location) {
        if (fix.hasAccuracy() && fix.accuracy > MAX_LEARN_ACC_M) {
            Log.i(TAG, "learnHome: Fix zu ungenau (${fix.accuracy} m) -> verworfen")
            return
        }
        pendingLearn = false
        val prev = last
        if (prev == null || fix.elapsedRealtimeNanos >= prev.elapsedRealtimeNanos) last = fix
        val cLat = homeLat
        val cLon = homeLon
        val lat: Double
        val lon: Double
        if (cLat == null || cLon == null) {
            lat = fix.latitude
            lon = fix.longitude
        } else {
            // Leichtes Glaetten, damit die Homezone durch einzelne Ausreisser nicht wandert.
            lat = cLat + (fix.latitude - cLat) * LEARN_ALPHA
            lon = cLon + (fix.longitude - cLon) * LEARN_ALPHA
        }
        homeLat = lat
        homeLon = lon
        scope.launch { prefs.setHome(lat, lon) }
        Log.i(TAG, "Homezone gelernt/aktualisiert (acc=${if (fix.hasAccuracy()) fix.accuracy else -1f} m)")
        recompute()
    }

    /**
     * GENAUESTER Fix (kleinste Ungenauigkeit) aus eigenem Cache + den Providern,
     * der nicht aelter als [maxAgeMs] ist. Wichtig: der zeitlich neueste Fix ist
     * oft der grobe Netzwerk-Fix (100 m) — fuer 15 m Homezone unbrauchbar; der
     * genaue GPS-Fix ist minimal aelter, aber der richtige.
     */
    private fun bestRecent(maxAgeMs: Long): Location? {
        val cands = mutableListOf<Location>()
        last?.let { cands += it }
        lm?.let { m ->
            for (p in providers()) {
                runCatching { m.getLastKnownLocation(p) }.getOrNull()?.let { cands += it }
            }
        }
        return cands
            .filter { ageMs(it) <= maxAgeMs }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
    }

    /** Einmalig einen frischen Fix anstossen (z. B. wenn das WLAN wegfaellt und wir
     *  klaeren wollen, ob wir zu Hause sind). Best effort, aktualisiert [last]. */
    fun requestFreshFix() {
        val m = lm ?: return
        if (!hasPermission() || !locationEnabled()) return
        runCatching {
            for (p in providers()) {
                m.requestSingleUpdate(p, listenerOf(), Looper.getMainLooper())
            }
        }
    }

    /** LocationListener mit expliziten Overrides (auf API 29 sind die Methoden
     *  noch nicht als default deklariert -> keine SAM-Konvertierung). */
    private fun listenerOf(): LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun ageMs(loc: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L

    private fun recompute() {
        val s = computeStatus()
        if (_status.value != s) Log.i(TAG, "Homezone-Status: $s")
        _status.value = s
    }

    private fun computeStatus(): HomeStatus {
        val lat = homeLat ?: return HomeStatus.UNKNOWN
        val lon = homeLon ?: return HomeStatus.UNKNOWN
        if (!hasPermission() || !locationEnabled()) return HomeStatus.UNKNOWN
        // Genauesten Fix bis 12 h heranziehen (stehendes Handy liefert kaum neue
        // Updates -> ein alter, aber genauer Fix im Umkreis heisst weiterhin daheim).
        val loc = bestRecent(INSIDE_MAX_AGE_MS) ?: return HomeStatus.UNKNOWN
        val res = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, res)
        val dist = res[0]
        val acc = if (loc.hasAccuracy()) loc.accuracy else DEFAULT_ACC_M
        val age = ageMs(loc)
        return when {
            // Klar zu Hause: der plausible Aufenthalt liegt im Umkreis. Bewusst
            // GROSSZUEGIG mit dem Alter — wer daheim steht, bewegt sich nicht, also
            // kommen keine neuen Fixes; ein alter Fix im Umkreis heisst weiterhin
            // „zu Hause". Nur uralte Fixes (>12 h) verwerfen wir.
            dist <= RADIUS_M + acc && age <= INSIDE_MAX_AGE_MS -> HomeStatus.INSIDE
            // Klar weg: nur mit FRISCHEM Fix blocken (sonst verpassen wir die
            // Klingel). Grosszuegige Marge gegen Fehlblockaden.
            age <= OUTSIDE_FRESH_MS && dist - acc > RADIUS_M + OUTSIDE_MARGIN_M -> HomeStatus.OUTSIDE
            else -> HomeStatus.UNKNOWN
        }
    }

    companion object {
        private const val TAG = "HomeZone"

        /** Umkreis der Homezone. Bewusst grosszuegig (~80 m statt der gewuenschten
         *  15 m): stehende GPS-Fixes driften real 30–50 m, gemeldete Genauigkeit ist
         *  geschoent. Kleiner -> „zu Hause" wird staendig faelschlich verneint.
         *  Unterscheidet „daheim" trotzdem klar von „unterwegs" (Hunderte Meter). */
        private const val RADIUS_M = 80f

        /** Zusatzabstand fuer "sicher ausserhalb" (nur dann wird geblockt). */
        private const val OUTSIDE_MARGIN_M = 120f

        /** Ohne echte Genauigkeit vorsichtig mit dieser rechnen. */
        private const val DEFAULT_ACC_M = 30f

        /** Nur so genaue Fixes fuers Lernen der Homezone akzeptieren. */
        private const val MAX_LEARN_ACC_M = 50f

        /** Fuer „zu Hause" grosszuegig: ein stehendes Handy liefert keine neuen
         *  Fixes, ein alter Fix im Umkreis heisst weiterhin daheim. */
        private const val INSIDE_MAX_AGE_MS = 12 * 3600_000L

        /** Zum LERNEN muss der Fix aktuell sein. */
        private const val LEARN_MAX_AGE_MS = 5 * 60_000L

        /** „Sicher weg" (und damit Blockade) nur mit frischem Fix. */
        private const val OUTSIDE_FRESH_MS = 15 * 60_000L

        /** Standort-Updates: auch im Stand regelmaessig (minDistance 0), damit der
         *  gecachte Fix nicht veraltet und die Homezone nicht „vergessen" wird. */
        private const val UPDATE_MIN_TIME_MS = 120_000L
        private const val UPDATE_MIN_DIST_M = 0f

        /** Nachfuehr-Gewicht beim Lernen (0..1); klein = traege/stabil. */
        private const val LEARN_ALPHA = 0.25
    }
}
