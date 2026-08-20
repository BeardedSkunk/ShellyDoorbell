package de.beardedskunk.shellydoorbell.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Network
import android.os.Build
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

    /** Letzte Einzelmessung (elapsedRealtime) — bremst dichte Anfragen. */
    @Volatile private var lastFixRequestMs = 0L

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

    /**
     * Standort „immer zulassen"? Nur dann darf der Dauerdienst den FGS-Typ
     * location auch setzen, wenn er aus dem Hintergrund startet (Boot,
     * App-Update). Ohne diese Berechtigung ist der Standort nur „waehrend der
     * Nutzung" erlaubt — hasPermission() liefert dann trotzdem true, sagt aber
     * nichts darueber, ob der Zugriff JETZT erlaubt ist.
     */
    fun hasBackgroundPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun locationEnabled(): Boolean = lm?.isLocationEnabled == true

    private fun providers(): List<String> {
        val m = lm ?: return emptyList()
        return buildList {
            runCatching { if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER) }
            runCatching { if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER) }
        }
    }

    /**
     * Netz, für das das aktuelle Urteil gilt — zugleich die Merkmarke „hier wurde schon gemessen".
     *
     * Bewusst das [Network]-Objekt und **nicht der WLAN-Name**: Seit Android 12 ist der Name in
     * `NetworkCapabilities.transportInfo` geschwärzt, solange der Callback nicht mit
     * `FLAG_INCLUDE_LOCATION_INFO` registriert wurde — auf dem Pixel war er dauerhaft null, und ein
     * Urteil, das an null hängt, wird nie ungültig. Genau daran ist der 20.08. gescheitert (siehe
     * docs/standort-nur-wenn-noetig.md, „Der Rückfall vom 20.08."). Ein neues [Network] gibt es
     * dagegen bei **jedem** WLAN-Beitritt, ganz ohne Berechtigung — das ist derselbe Auslöser, nur
     * auf einem Signal, das es wirklich gibt.
     */
    @Volatile
    private var judgedNet: Network? = null

    /**
     * Wurde überhaupt schon gemessen? Getrennt von [judgedNet] geführt, weil null dort ein
     * gültiger Zustand ist („kein WLAN") und nicht „noch nichts gemessen" heißen darf — sonst
     * entsteht wieder die Falle von oben.
     */
    @Volatile
    private var judged = false

    /**
     * Urteil für dieses Netz — und **die einzige Stelle, die eine Messung auslöst**.
     *
     * Gemessen wird höchstens **einmal je WLAN-Beitritt**, nicht periodisch. Der Netzwechsel ist
     * der bessere Auslöser als jede Uhr: Solange dasselbe [Network] steht, hat das Gerät das Netz
     * nicht gewechselt — und nach Hause zu kommen heißt zwangsläufig, dass es wechselt. Ein Timer
     * könnte dagegen nur zu früh feuern (unnötige Ortung) oder zu spät (verpasste Klingel).
     *
     * Liefert [HomeStatus.UNKNOWN], solange nichts entschieden ist. Der Aufrufer blockiert dann
     * **nicht**: Ein Verbindungsversuch ist billig, eine verpasste Klingel nicht.
     */
    fun verdict(net: Network?): HomeStatus {
        if (!hasPermission() || !locationEnabled()) return HomeStatus.UNKNOWN
        // Ohne gelernte Homezone gibt es nichts zu vergleichen — dann gar nicht erst messen.
        if (homeLat == null || homeLon == null) return HomeStatus.UNKNOWN
        if (!judged || judgedNet != net) {
            // Marke sofort setzen, nicht erst im Rückruf: Sonst liefe bei jedem
            // Verbindungsversuch eine neue Messung an, wenn die Ortung gerade nichts liefert.
            judged = true
            judgedNet = net
            measureOnce()
        }
        // Immer neu bewerten, auch ohne neuen Fix. [_status] ist nur ein Zwischenspeicher; die
        // Altersfenster in [computeStatus] wirken erst, wenn jemand rechnet. Ohne diese Zeile
        // bliebe ein „unterwegs" stehen, dessen Fix längst zu alt zum Blockieren ist — die Klingel
        // wäre unerreichbar, bis zufällig etwas anderes misst. So verfällt jede Fehlentscheidung
        // von selbst: Das Schlimmste ist eine Verzögerung, kein Dauerzustand.
        recompute()
        return _status.value
    }

    /** Netzwechsel: Das Urteil galt fürs alte Netz und ist damit hinfällig. */
    fun onNetworkChanged(net: Network?) {
        if (judged && judgedNet == net) return
        judged = false
        judgedNet = null
        if (_status.value != HomeStatus.UNKNOWN) {
            _status.value = HomeStatus.UNKNOWN
            Log.i(TAG, "WLAN gewechselt -> Ortsurteil verworfen")
        }
    }

    /**
     * Urteil verwerfen, ohne dass sich das Netz geändert hat — für die Momente, in denen der
     * Nutzer selbst etwas erwartet: App geöffnet, „Neu verbinden" gedrückt.
     */
    fun invalidate() {
        judged = false
        judgedNet = null
    }

    /**
     * **Eine** Messung, kein Abo.
     *
     * Vorher lief hier `requestLocationUpdates` auf allen Providern mit zwei Minuten Takt,
     * dauerhaft — am Gerät gemessen sechseinhalb Tage am Stück und 6861 Ortungen, davon die
     * Hälfte über GPS mit `HIGH_ACCURACY`. Das war die Ursache des dauerhaften blauen Punktes in
     * der Statusleiste (siehe docs/standort-nur-wenn-noetig.md).
     *
     * Für die Frage „bin ich Kilometer weg?" genügt der **Netz**-Provider. GPS kostet Strom und
     * ist drinnen ohnehin schwach; es wird nur angefragt, wenn es das Netz gar nicht gibt.
     */
    private fun measureOnce() {
        val m = lm ?: return
        val ps = providers()
        // Erst der billige Weg: ein schon vorhandener Fix reicht oft und kostet gar nichts.
        for (p in ps) runCatching { m.getLastKnownLocation(p) }.getOrNull()?.let { onLocation(it) }
        val provider = ps.firstOrNull { it == LocationManager.NETWORK_PROVIDER } ?: ps.firstOrNull()
        if (provider == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                m.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                    loc?.let { onLocation(it) }
                }
            } else {
                @Suppress("DEPRECATION")
                m.requestSingleUpdate(provider, listenerOf(), Looper.getMainLooper())
            }
            Log.i(TAG, "Einzelmessung angefordert ($provider)")
        }.onFailure { Log.w(TAG, "Einzelmessung nicht moeglich: ${it.message}") }
    }

    /** Nur noch fürs Aufräumen beim Dienstende — ein laufendes Abo gibt es nicht mehr. */
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
        // Die Verbindung IST der Beweis, dass wir daheim sind — genauer als jedes GPS. Ein
        // stehendes „unterwegs" kann damit nur falsch sein; dann muss die Zone neu gelernt werden.
        val contradicts = _status.value == HomeStatus.OUTSIDE
        _status.value = HomeStatus.INSIDE
        judged = false
        judgedNet = null
        // Ein Haus bewegt sich nicht: Ist die Zone schon gelernt, wird hier NICHT gemessen.
        // Genau das lief bisher bei jeder Verbindung und hielt die Ortung dauerhaft wach.
        if (homeLat != null && homeLon != null && !contradicts) return
        Log.i(TAG, if (contradicts) "verbunden trotz \"unterwegs\" -> Homezone neu lernen" else "Homezone noch nicht gelernt")
        pendingLearn = true
        requestFreshFix() // hier lohnt der genaue (GPS-)Fix, er passiert nur einmal
        bestRecent(LEARN_MAX_AGE_MS)?.let { learnHome(it) }
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

    /**
     * Einmalig einen frischen Fix anstossen (WLAN-Wechsel, WLAN-Verlust, App wird
     * sichtbar): genau dann steht die Frage „bin ich noch daheim?" neu. Best
     * effort, aktualisiert [last] und damit den Status.
     *
     * Auf API 30+ ueber getCurrentLocation — das erzwingt wirklich eine neue
     * Messung; das alte requestSingleUpdate liefert auch gern nur den Cache.
     */
    fun requestFreshFix() {
        val m = lm ?: return
        if (!hasPermission() || !locationEnabled()) return
        // Die Aufrufer koennen dicht aufeinander folgen (WLAN-Wechsel + Connect).
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastFixRequestMs < FRESH_FIX_MIN_GAP_MS) return
        lastFixRequestMs = nowMs
        val ps = providers()
        runCatching {
            for (p in ps) {
                if (Build.VERSION.SDK_INT >= 30) {
                    m.getCurrentLocation(p, null, context.mainExecutor) { loc ->
                        loc?.let { onLocation(it) }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    m.requestSingleUpdate(p, listenerOf(), Looper.getMainLooper())
                }
            }
            Log.i(TAG, "frischen Standort angefordert (${ps.joinToString()})")
        }.onFailure { Log.w(TAG, "frischer Standort nicht angefordert: ${it.message}") }
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
        // Drei Stufen, jede mit ihrem eigenen Altersfenster — sie beantworten
        // verschiedene Fragen und brauchen deshalb verschiedene Schwellen.
        //
        // 1. Wo bin ich JETZT? Der genaueste Fix der letzten Minuten entscheidet
        //    allein. Aeltere duerfen nicht mitbieten, sonst gewinnt der punktgenaue
        //    GPS-Fix von heute morgen zu Hause (5 m) gegen den groben, aber
        //    richtigen Netz-Fix aus dem Fremd-WLAN (50 m).
        bestRecent(DECIDE_FRESH_MS)?.let { return statusOf(it, lat, lon, allowOutside = true) }
        // 2. Nichts Frisches (Handy liegt still, Doze, Ortung zickt): ein etwas
        //    aelterer Fix darf weiter blockieren. Sonst kippte eine erkannte
        //    Auswaertsfahrt schon nach wenigen ruhigen Minuten zurueck und die App
        //    suchte die Klingel wieder im Fremdnetz.
        bestRecent(OUTSIDE_MAX_AGE_MS)?.let { return statusOf(it, lat, lon, allowOutside = true) }
        // 3. Gar nichts Aktuelles mehr: auf den letzten genauen Fix zurueckfallen —
        //    aber nur, um „zu Hause" zu bestaetigen. So alt darf nichts mehr
        //    blockieren, sonst verpassen wir die Klingel.
        bestRecent(INSIDE_MAX_AGE_MS)?.let { return statusOf(it, lat, lon, allowOutside = false) }
        return HomeStatus.UNKNOWN
    }

    /** Bewertung EINES Fixes gegen die Homezone. */
    private fun statusOf(loc: Location, lat: Double, lon: Double, allowOutside: Boolean): HomeStatus {
        val res = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, res)
        val dist = res[0]
        val acc = if (loc.hasAccuracy()) loc.accuracy else DEFAULT_ACC_M
        val status = when {
            // Klar zu Hause: der plausible Aufenthalt liegt im Umkreis.
            dist <= RADIUS_M + acc -> HomeStatus.INSIDE
            // Klar weg: nur mit frischem UND brauchbar genauem Fix blocken (sonst
            // verpassen wir wegen eines Funkmast-Schaetzers die Klingel).
            // Grosszuegige Marge gegen Fehlblockaden.
            allowOutside && acc <= MAX_OUTSIDE_ACC_M && dist - acc > RADIUS_M + OUTSIDE_MARGIN_M ->
                HomeStatus.OUTSIDE
            else -> HomeStatus.UNKNOWN
        }
        Log.d(TAG, "Ortsbewertung: $status (Abstand ${dist.toInt()} m, Genauigkeit ${acc.toInt()} m, Alter ${ageMs(loc) / 1000}s)")
        return status
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

        /** So ungenaue Fixes duerfen „sicher unterwegs" nicht behaupten (Funkmast-
         *  Schaetzer koennen um Kilometer daneben liegen, und eine Fehlblockade
         *  kostet die Klingel). Fuer „zu Hause" sind sie weiter willkommen. */
        private const val MAX_OUTSIDE_ACC_M = 200f

        /** Nur so genaue Fixes fuers Lernen der Homezone akzeptieren. */
        private const val MAX_LEARN_ACC_M = 50f

        /** Fuer „zu Hause" grosszuegig: ein stehendes Handy liefert keine neuen
         *  Fixes, ein alter Fix im Umkreis heisst weiterhin daheim. */
        private const val INSIDE_MAX_AGE_MS = 12 * 3600_000L

        /** Zum LERNEN muss der Fix aktuell sein. */
        private const val LEARN_MAX_AGE_MS = 5 * 60_000L

        /** Fenster, in dem ein Fix ALLEIN ueber den Aufenthalt entscheidet. Kurz
         *  gehalten, damit der genaue Fix von daheim nach dem Losfahren nicht noch
         *  lange gegen den aktuellen (groberen) vor Ort gewinnt. */
        private const val DECIDE_FRESH_MS = 5 * 60_000L

        /** So alt darf ein Fix hoechstens sein, um „sicher unterwegs" zu behaupten
         *  (und damit Verbindungsversuche zu blockieren). Laenger als
         *  [DECIDE_FRESH_MS], damit eine erkannte Auswaertsfahrt ruhige Minuten
         *  ohne neue Fixes uebersteht. */
        private const val OUTSIDE_MAX_AGE_MS = 15 * 60_000L

        /** Nachfuehr-Gewicht beim Lernen (0..1); klein = traege/stabil. */
        private const val LEARN_ALPHA = 0.25

        /** Mindestabstand zwischen zwei angeforderten Einzelmessungen. */
        private const val FRESH_FIX_MIN_GAP_MS = 20_000L
    }
}
