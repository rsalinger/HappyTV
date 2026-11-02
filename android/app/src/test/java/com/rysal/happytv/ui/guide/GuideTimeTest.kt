package com.rysal.happytv.ui.guide

import com.rysal.happytv.data.guide.GuideProgram
import com.rysal.happytv.data.guide.GuideWindow
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #146: moving the guide through time, as pure functions. Times are minutes from a base that sits on
 * a half hour, in UTC, so every expectation reads as plain arithmetic.
 */
class GuideTimeTest {

    private val zone = ZoneId.of("UTC")
    private val min = 60_000L
    private val base = ZonedDateTime.of(2026, 10, 29, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val now = base + 45 * min // 20:45, window 20:00 - 22:00
    private val window = GuideWindow.WINDOW_MILLIS
    private val step = GuideTime.STEP_MILLIS
    private val lastStart = GuideTime.lastWindowStart(now, zone)

    private fun at(minutes: Int) = base + minutes * min

    private fun prog(fromMin: Int, toMin: Int) = GuideProgram("c", "P$fromMin", null, at(fromMin), at(toMin))

    private fun focus(fromMin: Int, row: Int = 0) = GuideFocus.Program(row, at(fromMin))

    /** Hourly programmes 19:00 to 01:00. */
    private val hourly = (-60..300 step 60).map { prog(it, it + 60) }

    private fun forward(
        on: Int,
        programs: List<GuideProgram> = hourly,
        windowStart: Long = base,
        loadedTo: Long = at(600),
        hasReplay: Boolean = false,
    ) = GuideTime.forward(focus(on), programs, hasReplay, windowStart, loadedTo, now, lastStart)

    private fun backward(
        on: Int,
        windowStart: Long,
        programs: List<GuideProgram> = hourly,
        hasReplay: Boolean = false,
    ) = GuideTime.backward(focus(on), programs, hasReplay, windowStart, base, now)

    // ------------------------------------------------------------ window start

    @Test
    fun `yesterday never moves, the others never go before their starting window`() {
        assertEquals(base, GuideTime.windowStart(base, GuideDay.YESTERDAY, base + 3 * step))
        assertEquals(base, GuideTime.windowStart(base, GuideDay.TODAY, null))
        assertEquals(base, GuideTime.windowStart(base, GuideDay.TODAY, base - step))
        assertEquals(base + 3 * step, GuideTime.windowStart(base, GuideDay.TODAY, base + 3 * step))
    }

    @Test
    fun `the loaded block covers every window that starts inside it`() {
        var start = base
        while (start <= base + 12 * 60 * min) {
            val range = GuideTime.loadRange(base, start)
            assertTrue("window at +${(start - base) / min} min", range.covers(start))
            // ... with an hour to look ahead for the next program.
            assertTrue(range.to >= start + window + GuideTime.LOOKAHEAD_MILLIS)
            assertTrue(range.from >= base)
            start += step
        }
    }

    @Test
    fun `the loaded block is the same for steps inside it and changes when the window leaves it`() {
        val start = GuideTime.loadRange(base, base)
        // Opening reads the window and an hour past it only.
        assertEquals(base, start.from)
        assertEquals(base + window + GuideTime.LOOKAHEAD_MILLIS, start.to)
        // The first step reads the wider block; steps inside it read nothing.
        val first = GuideTime.loadRange(base, base + step)
        assertEquals(first, GuideTime.loadRange(base, base + 2 * step))
        assertEquals(first, GuideTime.loadRange(base, base + 3 * step))
        assertFalse(first == GuideTime.loadRange(base, base + 4 * step))
        assertEquals(base, first.from)
    }

    // ---------------------------------------------------------------- the day

    @Test
    fun `the day follows the window across midnight - today becomes tomorrow`() {
        val evening = ZonedDateTime.of(2026, 10, 29, 22, 45, 0, 0, zone).toInstant().toEpochMilli()
        val eveningBase = GuideWindow.fromNow(evening, zone).startUtcMillis // 22:00
        fun day(offsetMin: Int) = GuideTime.dayFor(
            GuideDay.TODAY, eveningBase + offsetMin * min, eveningBase, evening, zone,
        )
        assertEquals(GuideDay.TODAY, day(0))
        assertEquals(GuideDay.TODAY, day(60)) // 23:00 - 01:00 starts on the 29th
        assertEquals(GuideDay.TODAY, day(90)) // 23:30
        assertEquals(GuideDay.TOMORROW, day(120)) // 00:00 on the 30th
        assertEquals(GuideDay.TOMORROW, day(300))
    }

    @Test
    fun `just after midnight the starting window is still today and the first step stays today`() {
        val late = ZonedDateTime.of(2026, 10, 30, 0, 10, 0, 0, zone).toInstant().toEpochMilli()
        val lateBase = GuideWindow.fromNow(late, zone).startUtcMillis // 23:30 on the 29th
        assertEquals(GuideDay.TODAY, GuideTime.dayFor(GuideDay.TODAY, lateBase, lateBase, late, zone))
        assertEquals(GuideDay.TODAY, GuideTime.dayFor(GuideDay.TODAY, lateBase + step, lateBase, late, zone))
        assertEquals(GuideDay.TOMORROW, GuideTime.dayFor(GuideDay.TODAY, lateBase + 49 * step, lateBase, late, zone))
    }

    @Test
    fun `the grid stops at the end of tomorrow, where the picker ends`() {
        val end = GuideTime.endOfPicker(now, zone)
        assertEquals(ZonedDateTime.of(2026, 10, 31, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), end)
        assertEquals(end - window, GuideTime.lastWindowStart(now, zone))
    }

    // ------------------------------------------------------------------ Right

    @Test
    fun `Right on the last program of the window moves one step to show the next program`() {
        // 21:00 - 22:00 is the last program of the 20:00 window; 22:00 - 23:00 starts at its edge.
        val move = forward(on = 60)!!
        assertEquals(base + step, move.windowStart)
        assertEquals(focus(120), move.focus)
    }

    @Test
    fun `a next program already in the window is a move without a shift`() {
        val move = forward(on = 0)!!
        assertEquals(base, move.windowStart)
        assertEquals(focus(60), move.focus)
    }

    @Test
    fun `Right keeps stepping forward one program at a time`() {
        var windowStart = base
        var current = focus(60)
        val starts = ArrayList<Long>()
        repeat(4) {
            val move = GuideTime.forward(current, hourly, false, windowStart, at(600), now, lastStart)!!
            windowStart = move.windowStart
            current = move.focus
            starts += windowStart
        }
        assertEquals(at(300), current.startUtcMillis)
        // The window never moved backwards and always had the focused program's start on screen.
        assertTrue(starts.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(current.startUtcMillis < windowStart + window)
    }

    @Test
    fun `a gap before the next program moves the window far enough to show it`() {
        val programs = listOf(prog(0, 60), prog(60, 120), prog(300, 330))
        val move = forward(on = 60, programs = programs)!!
        // 120 minutes of gap: the next one starts at +300, so the window must reach at least +330.
        assertTrue(move.windowStart + window >= at(330))
        assertEquals((move.windowStart - base) % step, 0L)
        assertEquals(focus(300), move.focus)
    }

    @Test
    fun `Right stops where the row's data ends`() {
        assertNull(forward(on = 300))
        // A last program that ends inside the window is the end of the row, too.
        assertNull(forward(on = 60, programs = listOf(prog(0, 60), prog(60, 110))))
    }

    @Test
    fun `a program that outlasts the loaded range steps the window and keeps focus`() {
        val movie = listOf(prog(60, 60 + 8 * 60))
        val move = forward(on = 60, programs = movie, loadedTo = at(300))!!
        assertEquals(base + step, move.windowStart)
        assertEquals(focus(60), move.focus)
        // ... but not when the movie ends inside what is loaded: nothing is behind it.
        assertNull(forward(on = 60, programs = listOf(prog(60, 280)), loadedTo = at(300)))
    }

    @Test
    fun `Right does not go past the end of tomorrow`() {
        val last = lastStart
        val edgeProgramStart = last + window - 30 * min
        val programs = listOf(
            GuideProgram("c", "Edge", null, edgeProgramStart, edgeProgramStart + 30 * min),
            GuideProgram("c", "After", null, edgeProgramStart + 30 * min, edgeProgramStart + 90 * min),
        )
        val move = GuideTime.forward(
            GuideFocus.Program(0, edgeProgramStart), programs, false, last, last + 10 * window, now, lastStart,
        )
        assertNull("the program after the picker's last day is not reachable", move)
    }

    @Test
    fun `Right skips nothing it can show - replay channels reach ended programs, others do not need to`() {
        // Ended programs are never "next" for a Right press (they are earlier), so replay changes nothing here.
        assertEquals(forward(on = 60), forward(on = 60, hasReplay = true))
    }

    // ------------------------------------------------------------------- Left

    @Test
    fun `Left at the starting window goes nowhere in time - the channel cell takes it`() {
        assertNull(backward(on = 0, windowStart = base))
    }

    @Test
    fun `Left from the first program of a moved window brings the previous program into view`() {
        val moved = base + 3 * step // 21:30 - 23:30, first program shown: 22:00 (clipped list starts 21:00 - 22:00)
        val move = backward(on = 120, windowStart = moved)!!
        // 21:00 - 22:00 ends after the window start (21:30), so it is already on screen: no shift.
        assertEquals(moved, move.windowStart)
        assertEquals(focus(60), move.focus)
    }

    @Test
    fun `Left shifts back to put the previous program's start on the left edge`() {
        val moved = base + 6 * step // 23:00
        val move = backward(on = 180, windowStart = moved)!! // 23:00 program; previous is 22:00 - 23:00
        assertEquals(at(120), move.windowStart)
        assertEquals(focus(120), move.focus)
    }

    @Test
    fun `Left never goes before the starting window`() {
        val moved = base + step
        // The previous program is 20:00 - 21:00 ... but with the window one step in, start 20:00 is the clamp.
        val move = backward(on = 60, windowStart = moved + 60 * min)!!
        assertTrue(move.windowStart >= base)
        // A long previous program that began before the starting window stops at the starting window.
        val programs = listOf(prog(-200, 40), prog(40, 100))
        val clamped = backward(on = 40, windowStart = base + 2 * step, programs = programs, hasReplay = true)!!
        assertEquals(base, clamped.windowStart)
        assertEquals(focus(-200), clamped.focus)
    }

    @Test
    fun `Left skips previous programs that cannot be opened and stops when none is left`() {
        // 20:45 now. A program that ended before then has no replay on this channel: not a place to go.
        val programs = listOf(prog(0, 30), prog(30, 60), prog(60, 120))
        val onNow = backward(on = 60, windowStart = base + 2 * step, programs = programs, hasReplay = false)
        assertEquals("the on-now program is still a place to go", focus(30), onNow?.focus)
        // Nothing earlier than the on-now program: the channel cell.
        assertNull(backward(on = 30, windowStart = base + 2 * step, programs = programs, hasReplay = false))
    }

    @Test
    fun `Right then Left on a row returns to the same program`() {
        val forward = forward(on = 60)!!
        val back = GuideTime.backward(forward.focus, hourly, false, forward.windowStart, base, now)!!
        assertEquals(focus(60), back.focus)
    }

    // ------------------------------------------------------------- the state

    private fun stateAt(windowStart: Long?, day: GuideDay = GuideDay.TODAY) = buildGuideState(
        channels = emptyList(),
        programsByKey = emptyMap(),
        guideCounts = emptyMap(),
        xmltvMode = false,
        day = day,
        nowUtcMillis = now,
        zone = zone,
        windowStartUtcMillis = windowStart,
    )

    @Test
    fun `a moved window has its own ruler, and the Now line leaves when Now does`() {
        val start = stateAt(null)
        assertFalse(start.shifted)
        assertTrue(start.showNowLine)

        val moved = stateAt(base + 2 * step)
        assertTrue(moved.shifted)
        assertEquals(base + 2 * step, moved.window.startUtcMillis)
        assertEquals(listOf("8:00", "8:30", "9:00", "9:30"), start.ticks.map { it.second })
        assertEquals(listOf("9:00", "9:30", "10:00", "10:30"), moved.ticks.map { it.second })
        // 20:45 is in the 20:00 window, not in the 21:00 one.
        assertFalse(moved.showNowLine)
    }
}
