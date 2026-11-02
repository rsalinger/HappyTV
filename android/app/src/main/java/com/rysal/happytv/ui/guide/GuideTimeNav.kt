package com.rysal.happytv.ui.guide

import com.rysal.happytv.data.guide.GuideProgram
import com.rysal.happytv.data.guide.GuideProgramState
import com.rysal.happytv.data.guide.GuideWindow
import com.rysal.happytv.data.guide.stateAt
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * Moving the guide through time (#146), as pure functions.
 *
 * The grid opens on the day's **starting window** (the one with Now in it, or Yesterday's /
 * Tomorrow's). Right past its edge moves the window forward in half-hour steps, enough to show
 * the row's next program; Left moves back, never before the starting window. Nothing here
 * touches Room or Compose: the ViewModel feeds in the loaded programmes of one row.
 *
 * Rules, in one place:
 *  - Only Today and Tomorrow move; Yesterday stays as it is.
 *  - A window starts on a half-hour mark counted from the starting window, so ruler ticks stay
 *    on the half hours.
 *  - Right stops where the row's data ends, and at the end of tomorrow (the header's picker
 *    has no day beyond it, so the grid never contradicts it).
 *  - The programmes held in memory cover a [loadRange] around the window, so a step usually
 *    needs no query; a new range is read when the window leaves it.
 */
object GuideTime {
    const val STEP_MILLIS: Long = GuideWindow.HALF_HOUR_MILLIS

    /** The loaded range moves in blocks of this length. */
    const val CHUNK_MILLIS: Long = 2 * 60 * 60 * 1000L

    /** Programmes this far before the block start are kept, so Left can find the previous one. */
    const val LOOKBACK_MILLIS: Long = 2 * 60 * 60 * 1000L

    /** Programmes this far past the window's end are kept, so Right can find the next one. */
    const val LOOKAHEAD_MILLIS: Long = 60 * 60 * 1000L

    /** The time range of programmes held for a window: `[from, to]`. */
    data class LoadRange(val from: Long, val to: Long) {
        /** True when a window starting at [windowStart] is fully inside the range. */
        fun covers(windowStart: Long): Boolean =
            windowStart >= from && windowStart + GuideWindow.WINDOW_MILLIS <= to
    }

    /**
     * The window start for [day]: [base] (the starting window) unless the grid was [shifted]
     * forward. Yesterday never moves.
     */
    fun windowStart(base: Long, day: GuideDay, shifted: Long?): Long =
        if (day == GuideDay.YESTERDAY || shifted == null) base else max(base, shifted)

    /** The programmes to hold for a window starting at [windowStart] (>= [base]). */
    fun loadRange(base: Long, windowStart: Long): LoadRange {
        // Opening the guide reads the window and an hour past it, nothing more: the first step
        // forward is what reads the wider block (measured: the wider read cost the cold open
        // 45 ms on a Chromecast HD).
        if (windowStart <= base) {
            return LoadRange(from = base, to = base + GuideWindow.WINDOW_MILLIS + LOOKAHEAD_MILLIS)
        }
        val block = base + Math.floorDiv(max(0L, windowStart - base), CHUNK_MILLIS) * CHUNK_MILLIS
        return LoadRange(
            from = max(base, block - LOOKBACK_MILLIS),
            to = block + CHUNK_MILLIS + GuideWindow.WINDOW_MILLIS + LOOKAHEAD_MILLIS,
        )
    }

    /**
     * The first instant the grid may not show: local midnight after tomorrow. The header's
     * picker has Yesterday, Today and Tomorrow, so a window stays on those days.
     */
    fun endOfPicker(nowUtcMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowUtcMillis).atZone(zone).toLocalDate().plusDays(2)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** The latest window start that keeps the window inside the picker's days. */
    fun lastWindowStart(nowUtcMillis: Long, zone: ZoneId): Long =
        endOfPicker(nowUtcMillis, zone) - GuideWindow.WINDOW_MILLIS

    /**
     * The day the header's picker shows for a grid starting at [windowStart]: the selected
     * [day] while the grid is on its starting window, else the calendar day of the window's
     * start (Today becomes Tomorrow past midnight).
     */
    fun dayFor(
        day: GuideDay,
        windowStart: Long,
        base: Long,
        nowUtcMillis: Long,
        zone: ZoneId,
    ): GuideDay {
        if (windowStart <= base || day == GuideDay.YESTERDAY) return day
        val today = Instant.ofEpochMilli(nowUtcMillis).atZone(zone).toLocalDate()
        val onDay = Instant.ofEpochMilli(windowStart).atZone(zone).toLocalDate()
        val offset = java.time.temporal.ChronoUnit.DAYS.between(today, onDay).toInt()
        return GuideDay.entries.first { it.dayOffset == offset.coerceIn(0, 1) }
    }

    /** A time move: the window to show and the program to focus in it. */
    data class Move(val windowStart: Long, val focus: GuideFocus.Program)

    /**
     * Right past the last program the window shows. [programs] are the row's loaded
     * programmes; [focus] is the program the key was pressed on. Null when the row has nothing
     * later (or nothing later before the end of tomorrow).
     *
     * With a next program the window moves by whole steps until that program's start is on
     * screen (at least one step); a next program already in the window is a move without a
     * shift. Without one, a program that runs past the loaded range moves the window one step
     * and keeps focus, so a long movie never stops the grid early.
     */
    fun forward(
        focus: GuideFocus.Program,
        programs: List<GuideProgram>,
        hasReplay: Boolean,
        windowStart: Long,
        loadedTo: Long,
        nowUtcMillis: Long,
        lastWindowStart: Long,
    ): Move? {
        val schedule = dedupeOverlaps(programs.sortedBy { it.startUtcMillis }, nowUtcMillis)
        val index = schedule.indexOfFirst { it.startUtcMillis == focus.startUtcMillis }
        if (index < 0) return null
        val windowEnd = windowStart + GuideWindow.WINDOW_MILLIS
        val current = schedule[index]
        val next = (index + 1 until schedule.size).map { schedule[it] }
            .firstOrNull { isFocusable(it, hasReplay, nowUtcMillis) }

        if (next == null) {
            // Nothing after it in what is loaded. Only a program that outlasts the loaded range
            // may have something behind it: step on and keep focus (it is still on screen).
            if (current.endUtcMillis <= loadedTo || current.endUtcMillis <= windowEnd) return null
            val moved = min(windowStart + STEP_MILLIS, lastWindowStart)
            return if (moved > windowStart) Move(moved, focus) else null
        }
        if (next.startUtcMillis < windowEnd) return Move(windowStart, GuideFocus.Program(focus.row, next.startUtcMillis))
        if (next.startUtcMillis >= lastWindowStart + GuideWindow.WINDOW_MILLIS) return null

        // Show at least the first half hour of the next program.
        val visibleUntil = next.startUtcMillis + min(next.endUtcMillis - next.startUtcMillis, STEP_MILLIS)
        val steps = max(1L, Math.floorDiv(visibleUntil - windowEnd + STEP_MILLIS - 1, STEP_MILLIS))
        val moved = min(windowStart + steps * STEP_MILLIS, lastWindowStart)
        if (moved <= windowStart) return null
        return Move(moved, GuideFocus.Program(focus.row, next.startUtcMillis))
    }

    /**
     * Left from the first program the window shows, while the grid is past its starting window
     * ([base]). The previous program the row can show moves into view with its start on the
     * left edge; the window never goes before [base]. Null when there is none, and the grid's
     * own rule applies (the channel cell).
     */
    fun backward(
        focus: GuideFocus.Program,
        programs: List<GuideProgram>,
        hasReplay: Boolean,
        windowStart: Long,
        base: Long,
        nowUtcMillis: Long,
    ): Move? {
        if (windowStart <= base) return null
        val schedule = dedupeOverlaps(programs.sortedBy { it.startUtcMillis }, nowUtcMillis)
        val index = schedule.indexOfFirst { it.startUtcMillis == focus.startUtcMillis }
        if (index <= 0) return null
        val previous = (index - 1 downTo 0).map { schedule[it] }
            .firstOrNull { isFocusable(it, hasReplay, nowUtcMillis) && it.endUtcMillis > base }
            ?: return null
        val target = GuideFocus.Program(focus.row, previous.startUtcMillis)
        // Already on screen (the grid had not caught up with an earlier step): no shift.
        if (previous.endUtcMillis > windowStart) return Move(windowStart, target)
        val aligned = base + Math.floorDiv(max(0L, previous.startUtcMillis - base), STEP_MILLIS) * STEP_MILLIS
        return Move(max(base, min(aligned, windowStart - STEP_MILLIS)), target)
    }

    private fun isFocusable(program: GuideProgram, hasReplay: Boolean, nowUtcMillis: Long): Boolean =
        program.stateAt(nowUtcMillis) != GuideProgramState.ENDED || hasReplay
}

/** A channel going full screen from a guide that has moved forward returns the guide to Now (#146). */
fun guideReturnsToNow(fullScreen: Boolean, shifted: Boolean): Boolean = fullScreen && shifted
