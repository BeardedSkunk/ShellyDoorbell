package de.beardedskunk.shellydoorbell.ui

import de.beardedskunk.shellydoorbell.shelly.BellWindow
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

    val DAY_LABELS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

    /** Kompakte Tagesliste (0=Montag): "Mo–Fr", "Sa, So", "Mo, Mi–Fr", "Täglich". */
    fun dayRange(days: Set<Int>): String {
        if (days.size == 7) return "Täglich"
        val sorted = days.sorted()
        val parts = mutableListOf<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            parts += when (j) {
                i -> DAY_LABELS[sorted[i]]
                i + 1 -> "${DAY_LABELS[sorted[i]]}, ${DAY_LABELS[sorted[j]]}"
                else -> "${DAY_LABELS[sorted[i]]}–${DAY_LABELS[sorted[j]]}"
            }
            i = j + 1
        }
        return parts.joinToString(", ")
    }

    /** Zielzeit der "Ruhe bis"-Anzeige: "17:30" bzw. "morgen 08:00". */
    fun muteUntil(ts: Long): String {
        val d = localDate(ts)
        val t = time(ts)
        return when (d) {
            LocalDate.now() -> t
            LocalDate.now().plusDays(1) -> "morgen $t"
            else -> "${date(d)}, $t"
        }
    }

    /** "Mo–Fr 08:00–20:00" — Anzeige einer Klingelzeit. */
    fun window(w: BellWindow): String =
        "${dayRange(w.days)} ${minutes(w.startMin)}–${minutes(w.endMin)}"
}
