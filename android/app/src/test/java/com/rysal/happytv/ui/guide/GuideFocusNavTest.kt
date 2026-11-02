package com.rysal.happytv.ui.guide

import com.rysal.happytv.data.guide.GuideChannel
import com.rysal.happytv.data.guide.GuideProgram
import com.rysal.happytv.data.guide.GuideProgramState
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The guide's D-pad rules (spec #7 section 3.4), ported from the old
 * ViewModel-owned focus model:
 *
 *  - Left / Right moves between programs (past ones skipped unless replay);
 *  - Up / Down changes channel and keeps the time;
 *  - Left from the channel cell is the rail's key, Up from the first row the header's;
 *  - revalidation keeps focus alive across rebuilds.
 *
 * The header (day picker, toggle) and the category rail are ordinary Compose
 * focus targets now, so their old moves live in the Compose screen tests.
 */
class GuideFocusNavTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, 16, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun channel(id: String, replay: Boolean = true) = GuideChannel(
        streamId = id, num = id.toInt(), name = "Channel $id",
        genre = null, isFavorite = false, hasReplay = replay, epgChannelId = null,
    )

    private fun prog(start: Long, end: Long, state: GuideProgramState, focusable: Boolean): GuideBlock.ProgramBlock =
        GuideBlock.ProgramBlock(
            program = GuideProgram("c", "P $start", null, start, end),
            x = 0f,
            w = 100f,
            state = state,
            focusable = focusable,
            genre = null,
            showReplayIcon = state == GuideProgramState.ENDED,
            timeLabel = "",
        )

    /** Row helper: blocks in the order given (nav treats them positionally - x is 0 for all). */
    private fun row(id: String, replay: Boolean = true, blocks: List<GuideBlock.ProgramBlock>): GuideRow =
        GuideRow(channel(id, replay), blocks)

    // Three rows, each with live + future; row1 additionally carries an
    // ended x no-replay program that must be skipped by every move.
    private val live = at(14, 0) to at(14, 30)
    private val future = at(14, 30) to at(15, 0)
    private val future2 = at(15, 0) to at(15, 30)

    private val rows = listOf(
        row(
            "101",
            blocks = listOf(
                prog(live.first, live.second, GuideProgramState.LIVE, focusable = true),
                prog(future.first, future.second, GuideProgramState.FUTURE, focusable = true),
                prog(future2.first, future2.second, GuideProgramState.FUTURE, focusable = true),
            ),
        ),
        row(
            "301",
            replay = false,
            blocks = listOf(
                prog(at(13, 30), at(14, 0), GuideProgramState.ENDED, focusable = false), // skipped!
                prog(live.first, live.second, GuideProgramState.LIVE, focusable = true),
                prog(future.first, future.second, GuideProgramState.FUTURE, focusable = true),
            ),
        ),
        row(
            "205",
            blocks = listOf(
                prog(live.first, live.second, GuideProgramState.LIVE, focusable = true),
            ),
        ),
    )

    private val liveStart = live.first
    private val futureStart = future.first

    /* ------------------------------------------------------- left/right */

    @Test
    fun `left on the first program walks to the channel cell`() {
        val focus = GuideFocus.Program(0, liveStart) // index 0 in row 0
        assertEquals(GuideFocus.ChannelCell(0), moveFocus(focus, GuideKey.LEFT, rows))
    }

    @Test
    fun `left and right step between programs and clamp at the row edges`() {
        val focus = GuideFocus.Program(0, liveStart)
        val next = moveFocus(focus, GuideKey.RIGHT, rows)!!
        assertEquals(GuideFocus.Program(0, futureStart), next)

        val last = moveFocus(GuideFocus.Program(0, future2.first), GuideKey.RIGHT, rows)
        assertNull(last) // clamped at the last program
    }

    @Test
    fun `right from the channel cell enters the program on now`() {
        val focus = moveFocus(GuideFocus.ChannelCell(1), GuideKey.RIGHT, rows)!!
        assertEquals(GuideFocus.Program(1, liveStart), focus)
        // The ended x no-replay block at index 0 was skipped.
    }

    @Test
    fun `past programs are skipped - ended without replay is not a focus stop`() {
        val row1 = rows[1]
        assertEquals(listOf(liveStart, futureStart), row1.focusablePrograms.map { it.key })
        // Right from the live program goes to the next *focusable* one.
        val right = moveFocus(GuideFocus.Program(1, liveStart), GuideKey.RIGHT, rows)!!
        assertEquals(GuideFocus.Program(1, futureStart), right)
    }

    /* --------------------------------------------------------- up/down */

    @Test
    fun `down changes channel and keeps the time`() {
        // Row 0's live program (14:00) -> row 1 has an ended block before it
        // and a live one starting at the same time: the containing program wins.
        val down = moveFocus(GuideFocus.Program(0, liveStart), GuideKey.DOWN, rows)!!
        assertEquals(GuideFocus.Program(1, liveStart), down)

        // From row 1's future program back up to row 0's future program.
        val up = moveFocus(GuideFocus.Program(1, futureStart), GuideKey.UP, rows)!!
        assertEquals(GuideFocus.Program(0, futureStart), up)
    }

    @Test
    fun `down lands on the nearest program when the column has a gap`() {
        val offset = listOf(
            row("501", blocks = listOf(prog(at(14, 45), at(15, 15), GuideProgramState.FUTURE, focusable = true))),
        )
        // Single row: down clamps (row 0 is also the last).
        assertNull(moveFocus(GuideFocus.Program(0, liveStart), GuideKey.DOWN, offset))

        // With two rows the vertical pick happens: keepTime 14:30 is before the
        // only program in row 1 -> nearest = its start.
        val two = rows.take(1) + offset
        val down = moveFocus(GuideFocus.Program(0, futureStart), GuideKey.DOWN, two)!!
        assertEquals(GuideFocus.Program(1, at(14, 45)), down)
    }

    @Test
    fun `down into a row with nothing focusable lands on its cell`() {
        val emptyRow = row("777", replay = false, blocks = emptyList()) // no blocks at all
        val two = listOf(rows[0], emptyRow)
        val down = moveFocus(GuideFocus.Program(0, liveStart), GuideKey.DOWN, two)!!
        assertEquals(GuideFocus.ChannelCell(1), down)
    }

    @Test
    fun `up and down from a channel cell go to the neighbouring row's program on now`() {
        assertEquals(GuideFocus.Program(1, liveStart), moveFocus(GuideFocus.ChannelCell(0), GuideKey.DOWN, rows))
        assertEquals(GuideFocus.Program(0, liveStart), moveFocus(GuideFocus.ChannelCell(1), GuideKey.UP, rows))
    }

    /* ---------------------------------------------------------- edges */

    @Test
    fun `left from the channel cell is the rail's key - it fans the category rail out`() {
        // No move inside the grid ...
        assertNull(moveFocus(GuideFocus.ChannelCell(0), GuideKey.LEFT, rows))
        // ... and the edge rule says where that key goes instead (issue #15, comment 1).
        assertEquals(GuideEdge.RAIL, edgeFor(GuideFocus.ChannelCell(0), GuideKey.LEFT))
        assertEquals(GuideEdge.RAIL, edgeFor(GuideFocus.ChannelCell(2), GuideKey.LEFT))
    }

    @Test
    fun `left from a program still stops at the channel cell before the rail`() {
        assertEquals(GuideFocus.ChannelCell(1), moveFocus(GuideFocus.Program(1, liveStart), GuideKey.LEFT, rows))
        // Only a channel cell fans the rail out.
        assertEquals(GuideEdge.STOP, edgeFor(GuideFocus.Program(1, liveStart), GuideKey.LEFT))
    }

    @Test
    fun `up from the top row hands over to the header`() {
        assertNull(moveFocus(GuideFocus.Program(0, liveStart), GuideKey.UP, rows))
        assertEquals(GuideEdge.HEADER, edgeFor(GuideFocus.Program(0, liveStart), GuideKey.UP))
        assertNull(moveFocus(GuideFocus.ChannelCell(0), GuideKey.UP, rows))
        assertEquals(GuideEdge.HEADER, edgeFor(GuideFocus.ChannelCell(0), GuideKey.UP))
    }

    @Test
    fun `down from the last row and right from the last program stop at the edge`() {
        assertNull(moveFocus(GuideFocus.Program(2, liveStart), GuideKey.DOWN, rows))
        assertEquals(GuideEdge.STOP, edgeFor(GuideFocus.Program(2, liveStart), GuideKey.DOWN))
        assertEquals(GuideEdge.STOP, edgeFor(GuideFocus.Program(0, future2.first), GuideKey.RIGHT))
    }

    @Test
    fun `no rows - nothing moves`() {
        for (key in GuideKey.entries) {
            assertNull(moveFocus(GuideFocus.ChannelCell(0), key, emptyList()))
            assertNull(moveFocus(GuideFocus.Program(0, liveStart), key, emptyList()))
        }
    }

    /* ---------------------------------------------------- revalidation */

    @Test
    fun `revalidation keeps a valid program focus untouched`() {
        val focus = GuideFocus.Program(0, futureStart)
        assertEquals(focus, revalidateFocus(focus, rows))
    }

    @Test
    fun `revalidation re-picks inside the row when the program is gone`() {
        val gone = GuideFocus.Program(0, at(23, 0)) // not in any block list
        val revalidated = revalidateFocus(gone, rows)
        // Falls to the row's program on now (never jumps rows).
        assertEquals(GuideFocus.Program(0, liveStart), revalidated)
    }

    @Test
    fun `revalidation re-picks when a focused program aged into ended without replay`() {
        val aged = listOf(
            row(
                "301",
                replay = false,
                blocks = listOf(
                    prog(live.first, live.second, GuideProgramState.ENDED, focusable = false),
                    prog(future.first, future.second, GuideProgramState.LIVE, focusable = true),
                ),
            ),
        )
        assertEquals(
            GuideFocus.Program(0, futureStart),
            revalidateFocus(GuideFocus.Program(0, liveStart), aged),
        )
    }

    @Test
    fun `revalidation fixes an out-of-range row`() {
        val fix = revalidateFocus(GuideFocus.ChannelCell(99), rows)
        // The row is gone -> back to the guide's default (first program on now).
        assertEquals(GuideFocus.Program(0, liveStart), fix)
    }

    @Test
    fun `revalidation has nothing to focus without rows`() {
        assertNull(revalidateFocus(GuideFocus.ChannelCell(0), emptyList()))
    }

    @Test
    fun `initial focus is the first program on now`() {
        assertEquals(GuideFocus.Program(0, liveStart), initialFocus(rows))
        assertNull(initialFocus(emptyList()))
    }

    @Test
    fun `the entry spot of the first row is its program on now`() {
        assertEquals(GuideFocus.Program(0, liveStart), firstRowFocus(rows))
        // A first row with nothing focusable lands on its channel cell.
        val noFocusable = listOf(row("777", replay = false, blocks = emptyList()))
        assertEquals(GuideFocus.ChannelCell(0), firstRowFocus(noFocusable))
    }

    /* ----------------------------------------------------- dialog moves */

    @Test
    fun `dialog focus walks between the shown buttons only`() {
        val dialog = GuideDialogUi(
            channel = channel("101"),
            program = GuideProgram("101", "Now playing", null, live.first, live.second),
            state = GuideProgramState.LIVE,
            channelLine = "101 · Channel 101",
            title = "Now playing",
            metaLine = "",
            progressFraction = 0.5f,
            buttons = listOf(GuideDialogButton.WATCH_NOW, GuideDialogButton.WATCH_FROM_START),
            initialButton = GuideDialogButton.WATCH_NOW,
        )
        assertEquals(
            GuideDialogButton.WATCH_FROM_START,
            moveDialogFocus(GuideDialogButton.WATCH_NOW, GuideKey.RIGHT, dialog),
        )
        assertNull(moveDialogFocus(GuideDialogButton.WATCH_NOW, GuideKey.LEFT, dialog))
        assertNull(moveDialogFocus(GuideDialogButton.WATCH_FROM_START, GuideKey.RIGHT, dialog))
        assertNull(moveDialogFocus(GuideDialogButton.WATCH_NOW, GuideKey.UP, dialog))
    }
}
