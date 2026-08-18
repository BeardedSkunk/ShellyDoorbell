package de.beardedskunk.shellydoorbell.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kleines, dauerhaftes Ereignis-Protokoll im App-Verzeichnis.
 *
 * Hintergrund: Bleibt der Alarm einmal aus, ist die Frage immer dieselbe — hing
 * das Handy in dem Moment ueberhaupt am Shelly? Der logcat-Puffer des Geraets
 * beantwortet das nicht: er reichte im Ernstfall nur **zwei Minuten** zurueck,
 * ein Ausfall vom Vormittag war mittags nicht mehr rekonstruierbar.
 *
 * Hier landen deshalb nur die wenigen Zeilen, die eine solche Luecke erklaeren:
 * Verbindungswechsel, empfangene Klingel-Ereignisse, Alarm an/aus, Dienst-Start
 * und -Ende sowie alle [ALIVE_MINUTES] Minuten ein Lebenszeichen — dessen Fehlen
 * zeigt, dass der Dienst gar nicht lief. Kein Ersatz fuer logcat beim Entwickeln,
 * sondern ein Gedaechtnis fuer den Tag danach.
 *
 * Auslesen:
 * `adb exec-out run-as de.beardedskunk.shellydoorbell cat files/log/events.log`
 */
class EventLog(filesDir: File) {

    private val dir = File(filesDir, "log")
    private val current = File(dir, "events.log")
    private val previous = File(dir, "events-alt.log")
    private val lock = Any()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.GERMANY)

    /**
     * Haengt eine Zeile an. Schluckt alle Fehler: ein volles oder gesperrtes
     * Dateisystem darf niemals den Klingel-Alarm aufhalten.
     */
    fun log(line: String) {
        synchronized(lock) {
            runCatching {
                dir.mkdirs()
                // Rollen statt wachsen: eine Generation reicht, um einen Ausfall
                // vom Vortag noch zu sehen.
                if (current.length() > MAX_BYTES) {
                    previous.delete()
                    current.renameTo(previous)
                }
                current.appendText(stamp.format(Date()) + "  " + line + "\n")
            }
        }
    }

    companion object {
        /** Beide Generationen zusammen bleiben damit unter einem halben Megabyte. */
        private const val MAX_BYTES = 192 * 1024L

        /** Takt des Lebenszeichens (siehe Klassenkommentar). */
        const val ALIVE_MINUTES = 15L
    }
}
