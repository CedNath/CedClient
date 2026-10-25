package ced.cedclient.utils

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Countdown text for any timer: "3d 2h 59m 22s" (leading zero units dropped), and the real local date/time it hits
 * ("14:35", "Sat 14:35", "Sat 25 Oct 14:35" once it is a week away).
 */
object Countdown {
    private val timeOnly = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val dayTime = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
    private val fullDate = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.getDefault())

    /** [ms] as "3d 2h 59m 22s"; at most [units] units, "0s" at or below zero. */
    fun format(ms: Long, units: Int = 4): String {
        val total = (ms.coerceAtLeast(0) + 999) / 1000
        val parts = listOf(total / 86400 to "d", total % 86400 / 3600 to "h", total % 3600 / 60 to "m", total % 60 to "s")
        val shown = parts.dropWhile { it.first == 0L }.take(units).ifEmpty { listOf(0L to "s") }
        return shown.joinToString(" ") { "${it.first}${it.second}" }
    }

    /** The local wall-clock time at epoch millis [at]. */
    fun clock(at: Long, now: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val days = ChronoUnit.DAYS.between(LocalDate.ofInstant(Instant.ofEpochMilli(now), zone), t.toLocalDate())
        return when {
            days <= 0 -> t.format(timeOnly)
            days < 7 -> t.format(dayTime)
            else -> t.format(fullDate)
        }
    }

    /** Always the full weekday, day, month and time of epoch millis [at]: "Sat 25 Oct 14:35". */
    fun full(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(fullDate)

    /** "in 7h 3m 2s (14:35)": the countdown to [at] and the local time it lands. */
    fun until(at: Long, prefix: String = "in", now: Long = System.currentTimeMillis()): String =
        "$prefix ${format(at - now)} (${clock(at, now)})"
}
