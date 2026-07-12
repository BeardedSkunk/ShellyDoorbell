package de.beardedskunk.shellydoorbell.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Deutsche Anzeige-Formatierung fuer Zeiten und Messwerte. */
object Fmt {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
    private val dateFmt = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)

    fun localDate(ts: Long): LocalDate =
        Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate()

    fun time(ts: Long): String =
        Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).format(timeFmt)

    fun date(d: LocalDate): String = when (d) {
        LocalDate.now() -> "Heute"
        LocalDate.now().minusDays(1) -> "Gestern"
        else -> d.format(dateFmt)
    }

    /** "Heute, 14:32 Uhr" — fuer die Kurzliste auf dem Hauptbildschirm. */
    fun dayTime(ts: Long): String = "${date(localDate(ts))}, ${time(ts)} Uhr"

    fun watts(w: Double?): String =
        if (w == null) "– W" else String.format(Locale.GERMANY, "%.1f W", w)

    /** Minuten seit Mitternacht -> "HH:MM". */
    fun minutes(min: Int): String = String.format(Locale.GERMAN, "%02d:%02d", min / 60, min % 60)
}
