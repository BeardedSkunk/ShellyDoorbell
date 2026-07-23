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
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)
            // Auf minSdk 29 sind diese Methoden noch nicht als default deklariert.
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = l
        runCatching {
            for (p in providers()) m.getLastKnownLocation(p)?.let { onLocation(it) }
            for (p in providers()) {
                m.requestLocationUpdates(p, UPDATE_MIN_TIME_MS, UPDATE_MIN_DIST_M, l, Looper.getMainLooper())
            }
            Log.d(TAG, "Standort-Updates aktiv (${providers().joinToString()})")
        }.onFailure { Log.w(TAG, "Standort-Updates nicht gestartet: ${it.message}") }
    }

    fun stop() {
        listener?.let { l -> runCatching { lm?.removeUpdates(l) } }
        listener = null
    }

    private fun onLocation(loc: Location) {
        val prev = last
        if (prev == null || loc.elapsedRealtimeNanos >= prev.elapsedRealtimeNanos) last = loc
        recompute()
    }

    /**
     * Verbindung stand: aktuellen Ort als Homezone merken/nachfuehren, sofern
     * Berechtigung, Ortung und ein hinreichend genauer Fix vorliegen.
     */
    fun recordConnected() {
        if (!hasPermission() || !locationEnabled()) return
        start() // falls die Berechtigung erst nach onCreate erteilt wurde
        val fix = freshFix() ?: return
        if (fix.hasAccuracy() && fix.accuracy > MAX_LEARN_ACC_M) return // zu ungenau zum Lernen
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
        Log.d(TAG, "Homezone aktualisiert")
        recompute()
    }

    /** Frischester brauchbarer Fix: gecacht, sonst letzter bekannter der Provider. */
    private fun freshFix(): Location? {
        last?.let { if (ageMs(it) <= MAX_AGE_MS) return it }
        val m = lm ?: return null
        var best: Location? = null
        for (p in providers()) {
            val loc = runCatching { m.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || loc.elapsedRealtimeNanos > best!!.elapsedRealtimeNanos) best = loc
        }
        return best?.takeIf { ageMs(it) <= MAX_AGE_MS }
    }

    private fun ageMs(loc: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L

    private fun recompute() {
        _status.value = computeStatus()
    }

    private fun computeStatus(): HomeStatus {
        val lat = homeLat ?: return HomeStatus.UNKNOWN
        val lon = homeLon ?: return HomeStatus.UNKNOWN
        if (!hasPermission() || !locationEnabled()) return HomeStatus.UNKNOWN
        val loc = last ?: return HomeStatus.UNKNOWN
        if (ageMs(loc) > MAX_AGE_MS) return HomeStatus.UNKNOWN
        val res = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, res)
        val dist = res[0]
        val acc = if (loc.hasAccuracy()) loc.accuracy else DEFAULT_ACC_M
        return when {
            // Klar zu Hause: der plausible Aufenthalt liegt im Umkreis.
            dist <= RADIUS_M + acc -> HomeStatus.INSIDE
            // Klar weg: selbst der naechstmoegliche Punkt liegt weit ausserhalb.
            // Bewusst grosszuegig, damit wir NIE faelschlich blockieren und die
            // Klingel verpassen.
            dist - acc > RADIUS_M + OUTSIDE_MARGIN_M -> HomeStatus.OUTSIDE
            else -> HomeStatus.UNKNOWN
        }
    }

    companion object {
        private const val TAG = "HomeZone"

        /** Umkreis der Homezone (grob). */
        private const val RADIUS_M = 15f

        /** Zusatzabstand fuer "sicher ausserhalb" (nur dann wird geblockt). */
        private const val OUTSIDE_MARGIN_M = 100f

        /** Ohne echte Genauigkeit vorsichtig mit dieser rechnen. */
        private const val DEFAULT_ACC_M = 30f

        /** Nur so genaue Fixes fuers Lernen der Homezone akzeptieren. */
        private const val MAX_LEARN_ACC_M = 50f

        /** Aeltere Fixes gelten als unzuverlaessig -> UNKNOWN. */
        private const val MAX_AGE_MS = 15 * 60_000L

        /** Gemaechliche Standort-Updates (Homezone-Erkennung, akkuschonend). */
        private const val UPDATE_MIN_TIME_MS = 60_000L
        private const val UPDATE_MIN_DIST_M = 20f

        /** Nachfuehr-Gewicht beim Lernen (0..1); klein = traege/stabil. */
        private const val LEARN_ALPHA = 0.25
    }
}
