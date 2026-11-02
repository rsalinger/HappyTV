package com.rysal.happytv.ui.player

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The player's text formatting — clocks, runtimes and the three per-mode
 * badges (design spec §"Screens: player" per-mode table). Every function is pure
 * (epoch millis in, string out) so the ViewModel tests assert exact copy.
 *
 * All clocks are the device's local 12-hour time without AM/PM, matching the
 * dashboard ("2:37", "4:12") — except [endsAtLabel], which appends the
 * meridiem because it names a future wall-clock moment.
 */
object PlayerFormatting {

    /** "9:41" — 12-hour clock, no meridiem (top-right clock, program bounds). */
    fun clockLabel(utcMillis: Long): String =
        SimpleDateFormat("h:mm", Locale.US).format(Date(utcMillis))

    /** "4:12 PM" — movie badge, "Ends at …". */
    fun endsAtLabel(endsAtUtcMillis: Long): String =
        "Ends at " + SimpleDateFormat("h:mm a", Locale.US).format(Date(endsAtUtcMillis))

    /** "1:02:14" — movie position / duration (always h:mm:ss). */
    fun runtimeLabel(positionMillis: Long): String {
        val totalSeconds = (positionMillis.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    }

    /** "2:00–2:30" — one program's time range on the Now / Next card. */
    fun rangeLabel(startUtcMillis: Long, endUtcMillis: Long): String =
        "${clockLabel(startUtcMillis)}–${clockLabel(endUtcMillis)}"

    /**
     * "Replay · 8 min behind" — how far behind the live edge the replay starts
     * ([nowUtcMillis] - program start). Under an hour stays in minutes; longer
     * adds whole hours so the badge never runs past the scrubber.
     */
    fun replayBadgeLabel(startUtcMillis: Long, nowUtcMillis: Long): String {
        val behindMinutes = ((nowUtcMillis - startUtcMillis) / 60_000L).coerceAtLeast(0L)
        val text = when {
            behindMinutes < 60L -> "$behindMinutes min"
            else -> {
                val hours = behindMinutes / 60L
                val minutes = behindMinutes % 60L
                if (minutes == 0L) "$hours h" else "$hours h $minutes min"
            }
        }
        return "Replay · $text behind"
    }

    /** "101 · Northbay News" — channel number over name (top block). */
    fun channelLine(number: Int?, name: String): String =
        if (number != null) "$number · $name" else name

    /** "2023 · PG-13" — movie meta line; drops whichever half is unknown. */
    fun movieLine(year: String?, mpaa: String?): String? {
        val parts = listOfNotNull(
            year?.trim()?.takeIf { it.isNotEmpty() },
            mpaa?.trim()?.takeIf { it.isNotEmpty() },
        )
        return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
    }
}
