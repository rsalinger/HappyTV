package com.rysal.happytv.data.guide

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window algorithm — spec #7 section 3.4: two hours, starting at the
 * half-hour mark at or before (now - 30 min). At 2:37 the grid runs 2:00 to
 * 4:00. (Ported from the three-hour design spec window: same tests, new numbers.)
 */
class GuideWindowTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    private fun at(hour: Int, minute: Int, second: Int = 0, day: Int = 16): Long =
        LocalDateTime.of(2026, 10, day, hour, minute, second)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    /** The headline case: now at 2 37 PM. */
    @Test
    fun `now at 2 37 PM - the grid starts at 2 00 PM and runs two hours`() {
        val now = at(14, 37)

        assertEquals(at(14, 0), GuideWindow.halfHourMarkAtOrBefore(now - 30 * 60_000L, zone))

        val window = GuideWindow.fromNow(now, zone)
        assertEquals(at(14, 0), window.startUtcMillis)
        assertEquals(at(16, 0), window.endUtcMillis)
    }

    /** Now at 37 min of 120 sits 419.3 px into the 1360 px timeline. */
    @Test
    fun `now at 2 37 PM puts the Now line past the first quarter`() {
        val window = GuideWindow.fromNow(at(14, 37), zone)
        assertEquals(419.3f, window.xOf(at(14, 37)), 0.2f)
    }

    /** Exactly on a half hour: now - 30 min is itself a mark, so Now sits a quarter in. */
    @Test
    fun `exactly on the half-hour starts the window 30 minutes earlier`() {
        val window = GuideWindow.fromNow(at(14, 30), zone)
        assertEquals(at(14, 0), window.startUtcMillis)
        assertEquals(340f, window.xOf(at(14, 30)), 0.01f)
    }

    /** One millisecond before a mark stays on the earlier mark (floor, not round). */
    @Test
    fun `one millisecond before a mark floors to the earlier one`() {
        assertEquals(at(14, 0), GuideWindow.halfHourMarkAtOrBefore(at(14, 30) - 1, zone))
        assertEquals(at(14, 30), GuideWindow.halfHourMarkAtOrBefore(at(14, 30), zone))
    }

    /** Window slide: the start moves a half hour at a time, 30 minutes after each mark. */
    @Test
    fun `window advances with the mark`() {
        // 3:00 sharp: now - 30 min = 2:30, window 2:30 to 4:30.
        assertEquals(at(14, 30), GuideWindow.fromNow(at(15, 0), zone).startUtcMillis)
        // 3:29 still 2:30; 3:30 moves to 3:00.
        assertEquals(at(14, 30), GuideWindow.fromNow(at(15, 29), zone).startUtcMillis)
        assertEquals(at(15, 0), GuideWindow.fromNow(at(15, 30), zone).startUtcMillis)
    }

    /** Ticks: four half-hour marks 340 px apart, starting at the window start. */
    @Test
    fun `ticks are the four half-hour marks of the window`() {
        val window = GuideWindow.fromNow(at(14, 37), zone)
        val ticks = window.tickInstants()
        assertEquals(4, ticks.size)
        assertEquals(at(14, 0), ticks.first())
        assertEquals(at(15, 30), ticks.last())
        assertEquals(GuideWindow.HALF_HOUR_MILLIS, ticks[1] - ticks[0])

        // Geometry: 340 px per half hour (1360 px / 4).
        assertEquals(340f, window.xOf(ticks[1]), 0.001f)
        assertEquals(1360f, window.xOf(window.endUtcMillis), 0.001f)
    }

    /** Day picker: yesterday/tomorrow keep the wall-clock anchor of that day. */
    @Test
    fun `day offsets anchor on the requested day`() {
        val window = GuideWindow.fromNow(at(14, 37), zone, dayOffsetDays = 1)
        assertEquals(at(14, 0, day = 17), window.startUtcMillis)
        assertEquals(at(16, 0, day = 17), window.endUtcMillis)

        val yesterday = GuideWindow.fromNow(at(14, 37), zone, dayOffsetDays = -1)
        assertEquals(at(14, 0, day = 15), yesterday.startUtcMillis)
    }

    @Test
    fun `contains is inclusive of start exclusive of end`() {
        val window = GuideWindow.fromNow(at(14, 37), zone)
        assertTrue(window.contains(at(14, 0)))
        assertTrue(window.contains(at(15, 59)))
        assertTrue(!window.contains(at(16, 0)))
        assertTrue(!window.contains(at(13, 59)))
    }
}
