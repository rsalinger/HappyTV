package com.rysal.happytv.data.guide

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The guide's two-hour window (spec #7 section 3.4):
 *
 * "Window: 2 hours, starting at the half-hour mark at or before (now - 30 min)."
 * At 2:37 now - 30 min is 2:07, so the grid runs 2:00 to 4:00 and Now sits a
 * little past the first quarter. At exactly 2:30 the start is 2:00, so Now sits
 * a quarter of the way in and never at the left edge.
 *
 * The mark is a **floor** to the half-hour of local time:
 *
 * ```
 * mark = dayStart + (msSinceDayStart / HALF_HOUR_MS) * HALF_HOUR_MS
 * ```
 *
 * Geometry is expressed against a reference timeline of [TIMELINE_WIDTH_PX]
 * design px for 120 minutes; the screen scales it when "Keep categories open"
 * narrows the grid.
 */
class GuideWindow(
    /** Window start, inclusive, epoch millis (local half-hour aligned). */
    val startUtcMillis: Long,
) {
    /** Window end, exclusive = start + 2 hours. */
    val endUtcMillis: Long = startUtcMillis + WINDOW_MILLIS

    /** Minutes from the window start to [utcMillis] (may be negative/out of range). */
    fun minutesFromStart(utcMillis: Long): Long =
        TimeUnit.MILLISECONDS.toMinutes(utcMillis - startUtcMillis)

    /** Timeline x for [utcMillis], design px relative to the timeline's left edge. */
    fun xOf(utcMillis: Long): Float = (utcMillis - startUtcMillis) / 60_000.0f * PX_PER_MINUTE

    /** The four ruler/gridline tick instants (half-hour marks, window start first). */
    fun tickInstants(count: Int = 4): List<Long> =
        (0 until count).map { startUtcMillis + it * HALF_HOUR_MILLIS }

    /** True when [utcMillis] falls inside [startUtcMillis, endUtcMillis). */
    fun contains(utcMillis: Long): Boolean = utcMillis in startUtcMillis until endUtcMillis

    override fun equals(other: Any?): Boolean = other is GuideWindow && other.startUtcMillis == startUtcMillis
    override fun hashCode(): Int = startUtcMillis.hashCode()
    override fun toString(): String = "GuideWindow(start=$startUtcMillis, end=$endUtcMillis)"

    companion object {
        const val HALF_HOUR_MILLIS: Long = 30 * 60 * 1000L

        /** Two hours = four half-hours. */
        const val WINDOW_MILLIS: Long = 4 * HALF_HOUR_MILLIS

        /** Timeline geometry, design px at 1920x1080: 1360 px for 120 minutes. */
        const val TIMELINE_WIDTH_PX: Float = 1360f
        const val PX_PER_MINUTE: Float = TIMELINE_WIDTH_PX / 120f

        /**
         * The half-hour mark at or before [utcMillis], as epoch millis,
         * computed against [zone]'s local day (the grid is aligned to the
         * viewer's wall clock, not UTC).
         */
        fun halfHourMarkAtOrBefore(utcMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
            val dayStart = Instant.ofEpochMilli(utcMillis)
                .atZone(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            val sinceDayStart = utcMillis - dayStart
            return dayStart + (sinceDayStart / HALF_HOUR_MILLIS) * HALF_HOUR_MILLIS
        }

        /**
         * The window for "now": [dayOffsetDays] zero-based days around now
         * (-1 yesterday's grid, 0 today, +1 tomorrow), starting at the
         * half-hour mark at or before (that day's wall-clock time - 30 min).
         */
        fun fromNow(
            nowUtcMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            dayOffsetDays: Int = 0,
        ): GuideWindow {
            val zoned = Instant.ofEpochMilli(nowUtcMillis).atZone(zone)
            val anchor = if (dayOffsetDays == 0) {
                nowUtcMillis
            } else {
                zoned.toLocalDate()
                    .plusDays(dayOffsetDays.toLong())
                    .atTime(zoned.toLocalTime())
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
            }
            return GuideWindow(halfHourMarkAtOrBefore(anchor - HALF_HOUR_MILLIS, zone))
        }
    }
}
