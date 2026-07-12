package de.beardedskunk.shellydoorbell.shelly

import java.time.LocalDateTime

/**
 * Eine Ruhezeit ("Klingel-DND"). Jede Ruhezeit lebt als Paar normaler
 * Shelly-Schedules (aus/ein) auf dem Geraet und ist damit auch in der
 * Shelly-App sichtbar/aenderbar. Es koennen mehrere Ruhezeiten parallel
 * existieren (z. B. Werktage und Wochenende getrennt).
 *
 * [days] sind die Tage, an denen die Ruhezeit BEGINNT — App-intern
 * 0=Montag .. 6=Sonntag (Montag zuerst, anders als die Shelly-Oberflaeche).
 */
data class DndWindow(
    val startMin: Int,
    val endMin: Int,
    val days: Set<Int>,
) {
    /** Liegt der Zeitpunkt gerade innerhalb der Ruhezeit? */
    fun isInsideNow(now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (days.isEmpty()) return false
        val nowMin = now.hour * 60 + now.minute
        val today = now.dayOfWeek.value - 1 // ISO: Montag=1 -> 0
        return if (endMin > startMin) {
            today in days && nowMin >= startMin && nowMin < endMin
        } else {
            // Fenster ueber Mitternacht: heute begonnen ODER gestern begonnen
            (today in days && nowMin >= startMin) || ((today + 6) % 7 in days && nowMin < endMin)
        }
    }

    companion object {
        /** Vorbelegung des Editors: 22:00–06:00, taeglich. */
        val DEFAULT = DndWindow(startMin = 22 * 60, endMin = 6 * 60, days = (0..6).toSet())
    }
}

/** Eine auf dem Shelly angelegte Ruhezeit samt der IDs ihrer Schedule-Jobs. */
data class DndEntry(
    val offId: Int,
    val onId: Int,
    val window: DndWindow,
    /** false, wenn die Jobs in der Shelly-App deaktiviert wurden. */
    val enabled: Boolean,
)

/** Gemeinsame Erkennungs-Einstellungen (liegen im Shelly-KVS). */
data class SharedSettings(
    val thresholdW: Double,
    val debounceS: Int,
)

/**
 * Umrechnung zwischen App-Wochentagen (0=Montag) und Cron-Timespecs der
 * Shelly-Schedules ("ss mm hh dom mon dow", dow 0=Sonntag) sowie
 * Ueberschneidungs-Pruefung zwischen Ruhezeiten.
 */
object Dnd {
    /** Der Shelly erlaubt max. 20 Schedule-Jobs -> 10 Aus/Ein-Paare. */
    const val MAX_WINDOWS = 10

    private const val WEEK_MIN = 7 * 1440

    private fun isoToCron(day: Int) = (day + 1) % 7
    private fun cronToIso(day: Int) = (day + 6) % 7

    private val DAY_NAMES = mapOf(
        "sun" to 0, "mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6,
    )

    private fun timespec(minuteOfDay: Int, cronDays: Collection<Int>): String =
        "0 ${minuteOfDay % 60} ${minuteOfDay / 60} * * ${cronDays.sorted().joinToString(",")}"

    /** Timespec des Aus-Jobs (Ruhezeit-Beginn). */
    fun offTimespec(w: DndWindow): String = timespec(w.startMin, w.days.map { isoToCron(it) })

    /**
     * Timespec des Ein-Jobs (Ruhezeit-Ende). Geht das Fenster ueber Mitternacht,
     * faellt das Ende auf den Folgetag -> Tage um 1 verschieben.
     */
    fun onTimespec(w: DndWindow): String {
        val cronDays = w.days.map { isoToCron(it) }
        val shifted = if (w.endMin > w.startMin) cronDays else cronDays.map { (it + 1) % 7 }
        return timespec(w.endMin, shifted)
    }

    /**
     * Liest ein [DndWindow] aus den beiden Schedule-Jobs zurueck.
     * Liefert null, wenn die Timespecs nicht interpretierbar sind.
     */
    fun parse(offTimespec: String, onTimespec: String): DndWindow? {
        val off = parseSpec(offTimespec) ?: return null
        val on = parseSpec(onTimespec) ?: return null
        return DndWindow(
            startMin = off.first,
            endMin = on.first,
            days = off.second.map { cronToIso(it) }.toSet(),
        )
    }

    /**
     * Ueberschneiden oder beruehren sich zwei Ruhezeiten? Beruehrung (Ende A ==
     * Beginn B) zaehlt mit: Aus- und Ein-Job wuerden zur selben Sekunde feuern,
     * die Reihenfolge ist undefiniert und die Klingel bliebe evtl. faelschlich an.
     */
    fun overlaps(a: DndWindow, b: DndWindow): Boolean {
        val ib = intervals(b)
        return intervals(a).any { x -> ib.any { y -> touches(x, y) } }
    }

    /** Minuten-Intervalle [Beginn, Ende] auf der Wochen-Zeitachse (Ende ggf. > Woche). */
    private fun intervals(w: DndWindow): List<Pair<Int, Int>> = w.days.map { d ->
        val start = d * 1440 + w.startMin
        val end = if (w.endMin > w.startMin) d * 1440 + w.endMin else d * 1440 + 1440 + w.endMin
        start to end
    }

    private fun touches(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean =
        intArrayOf(-WEEK_MIN, 0, WEEK_MIN).any { shift ->
            a.first <= b.second + shift && b.first + shift <= a.second
        }

    /** Timespec -> (Minuten seit Mitternacht, Cron-Wochentage). */
    private fun parseSpec(spec: String): Pair<Int, Set<Int>>? {
        val f = spec.trim().split(Regex("\\s+"))
        val (mm, hh, dow) = when (f.size) {
            5 -> Triple(f[0], f[1], f[4])
            6, 7 -> Triple(f[1], f[2], f[5])
            else -> return null
        }
        val m = mm.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        val h = hh.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val days = parseDays(dow) ?: return null
        return (h * 60 + m) to days
    }

    private fun parseDays(dow: String): Set<Int>? {
        if (dow == "*") return (0..6).toSet()
        val days = mutableSetOf<Int>()
        for (part in dow.split(",")) {
            val range = part.split("-")
            when (range.size) {
                1 -> days += dayNum(range[0]) ?: return null
                2 -> {
                    val a = dayNum(range[0]) ?: return null
                    val b = dayNum(range[1]) ?: return null
                    var d = a
                    while (true) {
                        days += d
                        if (d == b) break
                        d = (d + 1) % 7
                    }
                }
                else -> return null
            }
        }
        return days
    }

    private fun dayNum(token: String): Int? {
        token.toIntOrNull()?.let { return if (it in 0..7) it % 7 else null }
        return DAY_NAMES[token.trim().lowercase().take(3)]
    }
}
