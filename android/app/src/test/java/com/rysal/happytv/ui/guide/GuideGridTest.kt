package com.rysal.happytv.ui.guide

import com.rysal.happytv.data.guide.GENRE_COLORS
import com.rysal.happytv.data.guide.GuideChannel
import com.rysal.happytv.data.guide.GuideProgram
import com.rysal.happytv.data.guide.GuideProgramState
import com.rysal.happytv.data.guide.GuideWindow
import com.rysal.happytv.data.guide.knownGenre
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid's pure rules (spec #7 section 3.4) - ported from the three-hour guide:
 *
 *  - all five status-chip strings;
 *  - ended blocks focusable only on replay channels;
 *  - genre dots in the four spec'd colors, and never when there's no genre;
 *  - "No guide info" (one wording for every empty span, #172);
 *  - the info band's lines (formerly the strip) and the Now pill's label rule;
 *  - dialog button sets + initial focus per state.
 */
class GuideGridTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(hour: Int, minute: Int, day: Int = 16): Long =
        LocalDateTime.of(2026, 10, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private val window = GuideWindow(at(14, 0)) // 14:00 to 16:00
    private val now = at(14, 37)

    private fun channel(
        streamId: String = "101",
        num: Int? = 101,
        name: String = "Northbay News",
        genre: String? = "News",
        favorite: Boolean = false,
        replay: Boolean = true,
    ) = GuideChannel(
        streamId = streamId,
        num = num,
        name = name,
        genre = genre,
        isFavorite = favorite,
        hasReplay = replay,
        epgChannelId = null,
    )

    private fun program(
        channelId: String = "101",
        title: String = "Headline report",
        description: String? = null,
        start: Long,
        end: Long,
    ) = GuideProgram(channelId, title, description, start, end)

    /* ------------------------------------------------------------- chips */

    @Test
    fun `on now chip - On now dot N min left`() {
        val p = program(start = at(14, 0), end = at(15, 0)) // 23 min left at 14:37
        assertEquals("On now · 23 min left", statusChip(p, hasReplay = true, nowUtcMillis = now, zone = zone))
    }

    @Test
    fun `on now chip never rounds below one minute`() {
        val p = program(start = at(14, 0), end = at(14, 37) + 20_000)
        assertEquals("On now · 1 min left", statusChip(p, hasReplay = false, nowUtcMillis = now, zone = zone))
    }

    @Test
    fun `starts in chip - within the hour`() {
        val p = program(start = at(14, 52), end = at(15, 22))
        assertEquals("Starts in 15 min", statusChip(p, hasReplay = false, nowUtcMillis = now, zone = zone))
    }

    @Test
    fun `starts in chip - exactly an hour still counts as minutes`() {
        val p = program(start = at(15, 37), end = at(16, 7))
        assertEquals("Starts in 60 min", statusChip(p, hasReplay = false, nowUtcMillis = now, zone = zone))
    }

    @Test
    fun `starts at chip - beyond an hour`() {
        val p = program(start = at(16, 0), end = at(17, 0))
        assertEquals("Starts at 4:00", statusChip(p, hasReplay = false, nowUtcMillis = now, zone = zone))
    }

    @Test
    fun `ended chips - replay available vs plain ended`() {
        val ended = program(start = at(13, 0), end = at(14, 0))
        assertEquals("Ended · replay available", statusChip(ended, hasReplay = true, nowUtcMillis = now, zone = zone))
        assertEquals("Ended", statusChip(ended, hasReplay = false, nowUtcMillis = now, zone = zone))
    }

    /* --------------------------------------------------- focusability */

    @Test
    fun `ended block is focusable only when the channel has replay`() {
        // Ended must still overlap the 14:00 window start AND not collide
        // with the live program (overlap dedupe drops colliding rows).
        val ended = program(start = at(13, 0), end = at(14, 5))
        val live = program(start = at(14, 5), end = at(15, 0))
        val future = program(start = at(15, 0), end = at(16, 0))

        val replayRow = buildGuideRow(channel(replay = true), listOf(ended, live, future), 3, window, now, zone)
        val replayEnded = replayRow.programAt(ended.startUtcMillis)!!
        val replayLive = replayRow.programAt(live.startUtcMillis)!!
        val replayFuture = replayRow.programAt(future.startUtcMillis)!!
        assertTrue(replayEnded.focusable)
        assertEquals(GuideProgramState.ENDED, replayEnded.state)
        assertTrue(replayEnded.showReplayIcon)
        assertTrue(replayLive.focusable)
        assertTrue(replayFuture.focusable)

        val plainRow = buildGuideRow(channel(replay = false), listOf(ended, live, future), 3, window, now, zone)
        val plainEnded = plainRow.programAt(ended.startUtcMillis)!!
        assertFalse(plainEnded.focusable) // ended x no replay is NOT focusable
        assertFalse(plainEnded.showReplayIcon)
        assertTrue(plainRow.programAt(live.startUtcMillis)!!.focusable)
        assertTrue(plainRow.programAt(future.startUtcMillis)!!.focusable)

        // Focus lists carry only focusable programs.
        assertEquals(2, plainRow.focusablePrograms.size)
        assertEquals(3, replayRow.focusablePrograms.size)
    }

    @Test
    fun `block fills and opacity follow the state table`() {
        val live = program(start = at(14, 5), end = at(15, 0))
        val future = program(start = at(15, 0), end = at(16, 0))
        val ended = program(start = at(13, 0), end = at(14, 5))
        val row = buildGuideRow(channel(), listOf(ended, live, future), 3, window, now, zone)
        assertEquals(GuideProgramState.LIVE, row.programAt(live.startUtcMillis)!!.state)
        assertEquals(GuideProgramState.FUTURE, row.programAt(future.startUtcMillis)!!.state)
        assertEquals(GuideProgramState.ENDED, row.programAt(ended.startUtcMillis)!!.state)
    }

    /* ------------------------------------------------------------ genre */

    @Test
    fun `genre dot colors are the four spec'd values`() {
        assertEquals(0xFF6CA6F0L, GENRE_COLORS.getValue("News"))
        assertEquals(0xFFF2D04AL, GENRE_COLORS.getValue("Kids"))
        assertEquals(0xFF5CC98FL, GENRE_COLORS.getValue("Sports"))
        assertEquals(0xFF4FC1C9L, GENRE_COLORS.getValue("Documentary"))
    }

    @Test
    fun `knownGenre maps only the four dot-able genres, case-insensitively`() {
        assertEquals("News", knownGenre("news"))
        assertEquals("Sports", knownGenre("Sports"))
        assertEquals("Documentary", knownGenre(" DOCUMENTARY "))
        assertNull(knownGenre("Entertainment"))
        assertNull(knownGenre("Movies 24/7"))
        assertNull(knownGenre(null))
        assertNull(knownGenre("  "))
    }

    @Test
    fun `a program without a genre carries no dot`() {
        val p = program(start = at(14, 0), end = at(15, 0))
        val noGenre = buildGuideRow(channel(genre = null), listOf(p), 1, window, now, zone)
        assertNull(noGenre.programAt(p.startUtcMillis)!!.genre)

        val withGenre = buildGuideRow(channel(genre = "Kids"), listOf(p), 1, window, now, zone)
        assertEquals("Kids", withGenre.programAt(p.startUtcMillis)!!.genre)
    }

    /* --------------------------------------------------------- geometry */

    @Test
    fun `row geometry is the spec's 56 tall rows 4 apart with a 360 channel cell`() {
        assertEquals(56, ROW_HEIGHT_PX)
        assertEquals(4, ROW_GAP_PX)
        assertEquals(360, CHANNEL_CELL_WIDTH_PX)
        assertEquals(340, HALF_HOUR_PX)
    }

    @Test
    fun `blocks are placed by start time minus the 4 px gap`() {
        val first = program(start = at(14, 0), end = at(14, 30))
        val second = program(start = at(15, 0), end = at(15, 30))
        val row = buildGuideRow(channel(), listOf(first, second), 2, window, now, zone)

        val b1 = row.programAt(first.startUtcMillis)!!
        assertEquals(0f, b1.x, 0.01f)
        assertEquals(340f - 4f, b1.w, 0.01f) // 30 min = 340 px, minus the gap
        assertEquals("2:00–2:30", b1.timeLabel) // en dash, no spaces, full times

        val b2 = row.programAt(second.startUtcMillis)!!
        assertEquals(680f, b2.x, 0.01f) // 60 min x 11.33 px
        assertEquals("3:00–3:30", b2.timeLabel)
    }

    @Test
    fun `programs are clipped to the window edges`() {
        val spanning = program(start = at(13, 30), end = at(14, 20))
        val row = buildGuideRow(channel(), listOf(spanning), 1, window, now, zone)
        val block = row.programAt(spanning.startUtcMillis)!!
        assertEquals(0f, block.x, 0.01f) // clipped start
        assertEquals(223f, block.w, 0.01f) // round(20 min -> 226.67 px)=227, minus 4
        assertEquals("1:30–2:20", block.timeLabel) // label keeps the real times
    }

    @Test
    fun `overlapping duplicates are dropped, later start wins not`() {
        val a = program(start = at(14, 0), end = at(15, 0))
        val overlap = program(start = at(14, 30), end = at(15, 30))
        val row = buildGuideRow(channel(), listOf(a, overlap), 2, window, now, zone)
        assertEquals(1, row.allPrograms.size)
        assertEquals(a.startUtcMillis, row.allPrograms.single().key)
    }

    /**
     * Providers pad end times, so an ended program can overlap the start of
     * the live one. The live program must win that collision - "first start
     * wins" alone hid the on-now block behind the padded neighbour (found on
     * TV: 101's live Daybreak News vanished behind Morning Headline).
     */
    @Test
    fun `live program wins the collision with a padded ended neighbour`() {
        val paddedEnded = program(title = "Morning Headline", start = at(13, 30), end = at(14, 35))
        val live = program(title = "Daybreak News", start = at(14, 30), end = at(15, 30))
        val row = buildGuideRow(channel(), listOf(paddedEnded, live), 2, window, now, zone)

        assertEquals(listOf(live.startUtcMillis), row.allPrograms.map { it.key })
        assertEquals(GuideProgramState.LIVE, row.allPrograms.single().state)
        assertEquals("Daybreak News", row.liveProgram?.program?.title)
    }

    /* ------------------------------------------------------------- gaps */

    @Test
    fun `channel with no guide at all - whole window reads No guide info`() {
        val row = buildGuideRow(channel(), programs = emptyList(), guideCount = 0, window, now, zone)
        val gap = row.blocks.single() as GuideBlock.GapBlock
        assertEquals("No guide info", gap.label)
        assertEquals(0f, gap.x, 0.01f)
        assertEquals(1360f - 4f, gap.w, 0.01f)
    }

    @Test
    fun `channel with guide outside the window - No guide info`() {
        val other = program(start = at(18, 0), end = at(19, 0), channelId = "101")
        val row = buildGuideRow(channel(), listOf(other), guideCount = 1, window, now, zone)
        val gap = row.blocks.single() as GuideBlock.GapBlock
        assertEquals("No guide info", gap.label)
    }

    @Test
    fun `now-and-next-only channel stripes the rest of the row as No guide info`() {
        // The acceptance state: provider sent only two programs, the rest of
        // the row (from 15:30 to the window end 16:00) is striped.
        val current = program(start = at(14, 0), end = at(15, 0))
        val next = program(start = at(15, 0), end = at(15, 30))
        val row = buildGuideRow(channel(), listOf(current, next), guideCount = 4, window, now, zone)

        val gaps = row.blocks.filterIsInstance<GuideBlock.GapBlock>()
        assertEquals(1, gaps.size)
        val gap = gaps.single()
        assertEquals("No guide info", gap.label)
        assertEquals(1020f, gap.x, 0.01f)          // x(15:30): 90 min into the window
        assertEquals(336f, gap.w, 0.2f)            // 30 min x 11.33 px - 4
        // Both real programs still render focusable.
        assertEquals(2, row.focusablePrograms.size)
    }

    @Test
    fun `a leading gap before the first program is striped too`() {
        val late = program(start = at(14, 30), end = at(15, 0))
        val row = buildGuideRow(channel(), listOf(late), guideCount = 1, window, now, zone)
        val gaps = row.blocks.filterIsInstance<GuideBlock.GapBlock>()
        assertEquals(2, gaps.size) // leading + trailing
        val leading = gaps.first()
        assertEquals("No guide info", leading.label)
        assertEquals(0f, leading.x, 0.01f)
        assertEquals(340f - 4f, leading.w, 0.01f)
        // The program itself sits between them, in window order.
        assertEquals(1, row.allPrograms.size)
        assertTrue(row.blocks.indexOf(row.allPrograms.single()) in 1..2)
    }

    /* -------------------------------------------------------- info band */

    @Test
    fun `info band for a program carries channel line, title, range and status`() {
        val p = program(start = at(14, 30), end = at(15, 30), title = "College football: pre-game")
        val row = buildGuideRow(
            channel(num = 301, name = "Fieldhouse Sports", genre = "Sports"),
            listOf(p), 1, window, now, zone,
        )
        val band = infoBandForFocus(GuideFocus.Program(0, p.startUtcMillis), listOf(row), now, zone)!!
        assertEquals("301 · Fieldhouse Sports", band.channelLine)
        assertEquals("College football: pre-game", band.title)
        assertTrue(band.hasProgram)
        // 14:30 start at now=14:37 is already on: 15:30 end -> 53 min left.
        assertEquals("2:30 – 3:30 · On now · 53 min left", band.timeLine)
    }

    @Test
    fun `info band drops the number when the panel sent none`() {
        assertEquals("Front Room", channelLineOf(null, "Front Room"))
        assertEquals("404 · Front Room", channelLineOf(404, "Front Room"))
    }

    @Test
    fun `info band for a channel cell shows what is on now`() {
        val live = program(start = at(14, 0), end = at(15, 0), title = "Headline report")
        val row = buildGuideRow(channel(favorite = true), listOf(live), 1, window, now, zone)
        val band = infoBandForFocus(GuideFocus.ChannelCell(0), listOf(row), now, zone)!!
        assertEquals("Headline report", band.title)
        assertEquals("2:00 – 3:00 · On now · 23 min left", band.timeLine)
    }

    @Test
    fun `info band for a channel with nothing in the window says No guide info`() {
        val elsewhere = program(start = at(18, 0), end = at(19, 0))
        val row = buildGuideRow(channel(), listOf(elsewhere), 1, window, now, zone)
        val band = infoBandForFocus(GuideFocus.ChannelCell(0), listOf(row), now, zone)!!
        assertEquals("No guide info", band.title)
        assertFalse(band.hasProgram)
        assertNull(band.timeLine)
    }

    @Test
    fun `channel cell on a window without the program on now describes the first program the row draws (154)`() {
        // The guide moved forward: nothing in the window is on now, but the row has data.
        val moved = GuideWindow(at(18, 0))
        val first = program(title = "Evening news", start = at(18, 0), end = at(19, 0))
        val second = program(title = "Late film", start = at(19, 0), end = at(20, 30))
        val row = buildGuideRow(channel(), listOf(first, second), 2, moved, now, zone)

        val band = infoBandForFocus(GuideFocus.ChannelCell(0), listOf(row), now, zone)!!

        assertTrue(band.hasProgram)
        assertEquals("Evening news", band.title)
        assertEquals("6:00 – 7:00 · Starts at 6:00", band.timeLine)
        assertEquals(first.startUtcMillis, infoBlockFor(GuideFocus.ChannelCell(0), row)!!.program.startUtcMillis)
    }

    @Test
    fun `channel cell still prefers the program on now when the window has it (154)`() {
        val past = program(title = "Earlier", start = at(14, 0), end = at(14, 30))
        val live = program(title = "Headline report", start = at(14, 30), end = at(15, 0))
        val row = buildGuideRow(channel(), listOf(past, live), 2, window, now, zone)
        assertEquals("Headline report", infoBandForFocus(GuideFocus.ChannelCell(0), listOf(row), now, zone)!!.title)
    }

    @Test
    fun `a focused program the row no longer draws describes the row's own program, not the old one (154)`() {
        // Tomorrow's program was focused; Today was chosen in the header, so the row is rebuilt for Today.
        val tomorrowKey = at(9, 0, day = 17)
        val today = program(title = "Headline report", start = at(14, 0), end = at(15, 0))
        val row = buildGuideRow(channel(), listOf(today), 1, window, now, zone)

        val band = infoBandForFocus(GuideFocus.Program(0, tomorrowKey), listOf(row), now, zone)!!
        assertEquals("Headline report", band.title)
        assertEquals(today.startUtcMillis, infoBlockFor(GuideFocus.Program(0, tomorrowKey), row)!!.program.startUtcMillis)
    }

    @Test
    fun `info band for an ended program on a replay channel says replay is available`() {
        val ended = program(start = at(13, 0), end = at(14, 30)) // overlaps the window, already over
        val row = buildGuideRow(channel(replay = true), listOf(ended), 1, window, now, zone)
        val band = infoBandForFocus(GuideFocus.Program(0, ended.startUtcMillis), listOf(row), now, zone)!!
        assertEquals("1:00 – 2:30 · Ended · replay available", band.timeLine)

        val noReplay = buildGuideRow(channel(replay = false), listOf(ended), 1, window, now, zone)
        val plain = infoBandFor("101 · Northbay News", ended, hasReplay = false, nowUtcMillis = now, zone = zone)
        assertEquals("1:00 – 2:30 · Ended", plain.timeLine)
        assertNotNull(noReplay)
    }

    @Test
    fun `info band is null for a row that is gone`() {
        assertNull(infoBandForFocus(GuideFocus.ChannelCell(3), emptyList(), now, zone))
    }

    /* ----------------------------------------------------------- dialog */

    private fun dialogFor(replay: Boolean, start: Long, end: Long): GuideDialogUi {
        val p = program(start = start, end = end)
        val row = buildGuideRow(channel(replay = replay), listOf(p), 1, window, now, zone)
        val block = row.programAt(p.startUtcMillis)!!
        return dialogFor(row, block, now, zone)
    }

    @Test
    fun `dialog for an on-now program on a replay channel`() {
        val dialog = dialogFor(replay = true, start = at(14, 0), end = at(15, 0))
        assertEquals("101 · Northbay News", dialog.channelLine)
        assertEquals("Headline report", dialog.title)
        assertEquals("2:00 – 3:00 · News · On now · 23 min left", dialog.metaLine)
        assertNotNull(dialog.progressFraction)
        assertEquals(listOf(GuideDialogButton.WATCH_NOW, GuideDialogButton.WATCH_FROM_START), dialog.buttons)
        assertEquals(GuideDialogButton.WATCH_NOW, dialog.initialButton)
    }

    @Test
    fun `dialog for an on-now program without replay shows Watch now only`() {
        val dialog = dialogFor(replay = false, start = at(14, 0), end = at(15, 0))
        assertEquals(listOf(GuideDialogButton.WATCH_NOW), dialog.buttons)
        assertEquals(GuideDialogButton.WATCH_NOW, dialog.initialButton)
    }

    @Test
    fun `dialog for an ended program on a replay channel makes Watch from the start primary`() {
        val dialog = dialogFor(replay = true, start = at(13, 0), end = at(14, 30))
        assertEquals(listOf(GuideDialogButton.WATCH_FROM_START), dialog.buttons)
        assertEquals(GuideDialogButton.WATCH_FROM_START, dialog.initialButton)
        assertNull(dialog.progressFraction)
        assertEquals("1:00 – 2:30 · News · Ended · replay available", dialog.metaLine)
    }

    @Test
    fun `dialog for a future program offers Remind me`() {
        val dialog = dialogFor(replay = false, start = at(15, 30), end = at(16, 30))
        assertEquals(listOf(GuideDialogButton.REMIND), dialog.buttons)
        assertEquals(GuideDialogButton.REMIND, dialog.initialButton)
        assertNull(dialog.progressFraction)
    }

    @Test
    fun `progress fraction is how far in the program is`() {
        val p = program(start = at(14, 0), end = at(16, 0)) // 37 min of 120
        val row = buildGuideRow(channel(), listOf(p), 1, window, now, zone)
        val dialog = dialogFor(row, row.programAt(p.startUtcMillis)!!, now, zone)
        assertEquals(37f / 120f, dialog.progressFraction!!, 0.001f)
    }

    /* ------------------------------------------------- whole-state bits */

    private fun state(
        channels: List<GuideChannel> = listOf(channel()),
        programs: Map<String, List<GuideProgram>> = emptyMap(),
        counts: Map<String, Int> = emptyMap(),
        xmltv: Boolean = false,
        day: GuideDay = GuideDay.TODAY,
        loaded: Boolean = true,
        storeHasGuide: Boolean = true,
    ) = buildGuideState(
        channels = channels,
        programsByKey = programs,
        guideCounts = counts,
        xmltvMode = xmltv,
        day = day,
        nowUtcMillis = now,
        zone = zone,
        loaded = loaded,
        storeHasGuide = storeHasGuide,
    )

    @Test
    fun `empty state only when the store holds no guide at all`() {
        val s = state(storeHasGuide = false)
        assertTrue(s.isEmptyState)
        assertTrue(s.showNowLine) // Today keeps the Now line
    }

    @Test
    fun `a selection without guide while the store has data is striped rows, not the empty state`() {
        val s = state(storeHasGuide = true)
        assertFalse(s.isEmptyState)
        assertEquals("No guide info", (s.rows.single().blocks.single() as GuideBlock.GapBlock).label)
    }

    @Test
    fun `loading is never the empty state`() {
        assertFalse(state(loaded = false, storeHasGuide = false).isEmptyState)
    }

    @Test
    fun `grid state when the store has guide rows`() {
        val live = program(start = at(14, 0), end = at(15, 0))
        val s = state(programs = mapOf("101" to listOf(live)), counts = mapOf("101" to 4))
        assertFalse(s.isEmptyState)
        assertEquals(live.startUtcMillis, s.rows.single().liveProgram!!.key)
        assertTrue(s.showNowLine)
    }

    @Test
    fun `yesterday and tomorrow grids hide the Now line`() {
        for (day in listOf(GuideDay.YESTERDAY, GuideDay.TOMORROW)) {
            assertFalse(state(counts = mapOf("101" to 4), day = day).showNowLine)
        }
    }

    @Test
    fun `xmltv mode looks channels up by epg channel id`() {
        val xmltvChannel = channel().copy(epgChannelId = "northbay.news")
        val providerOnly = program(start = at(14, 0), end = at(15, 0), channelId = "101")
        val xmltvProgram = providerOnly.copy(channelId = "northbay.news")

        val xmltvState = state(
            channels = listOf(xmltvChannel),
            programs = mapOf("101" to listOf(providerOnly), "northbay.news" to listOf(xmltvProgram)),
            counts = mapOf("northbay.news" to 1),
            xmltv = true,
        )
        assertFalse(xmltvState.isEmptyState)
        assertEquals(1, xmltvState.rows.single().allPrograms.size)

        // Same inputs in provider mode: the XMLTV-keyed rows are invisible.
        val providerState = state(
            channels = listOf(xmltvChannel),
            programs = mapOf("northbay.news" to listOf(xmltvProgram)),
            counts = mapOf("northbay.news" to 1),
            xmltv = false,
        )
        assertTrue(providerState.rows.single().allPrograms.isEmpty()) // no provider-keyed rows
        assertEquals("No guide info", (providerState.rows.single().blocks.single() as GuideBlock.GapBlock).label)
    }

    @Test
    fun `ticks and labels line up with the half-hour marks of a two-hour window`() {
        val s = state(counts = mapOf("101" to 1))
        assertEquals(4, s.ticks.size)
        assertEquals(listOf(0f, 340f, 680f, 1020f), s.ticks.map { it.first })
        assertEquals(listOf("2:00", "2:30", "3:00", "3:30"), s.ticks.map { it.second })
        assertEquals("2:37", s.clockLabel)
        // Now at 14:37: 37 min of 120 -> 419.3 px.
        assertEquals(419.3f, s.nowX, 0.2f)
    }

    /* --------------------------------------------------- Now pill vs ruler */

    @Test
    fun `a ruler label under the Now pill is hidden`() {
        // Now at 419 px: the pill spans about 357 to 481, covering the 340 px label
        // (340 to 424) but not the one at 0 or at 680.
        assertTrue(labelHiddenByNow(tickX = 340f, nowX = 419f))
        assertFalse(labelHiddenByNow(tickX = 0f, nowX = 419f))
        assertFalse(labelHiddenByNow(tickX = 680f, nowX = 419f))
        // Now right on a tick hides that tick's label, and only that one.
        assertTrue(labelHiddenByNow(tickX = 680f, nowX = 680f))
        assertFalse(labelHiddenByNow(tickX = 340f, nowX = 680f))
    }

    /** #57: "Now 12:59" is nine tabular glyphs at 24 px 800 (~132 px) plus 14 px per side. */
    @Test
    fun `the Now pill is wide enough for the widest label and does not swallow a neighbour tick`() {
        assertTrue(NOW_PILL_WIDTH_PX >= 132f + 2 * 14f)
        // Neighbouring labels (340 px apart, 84 px wide) stay visible next to a pill on a tick.
        assertFalse(labelHiddenByNow(tickX = 340f, nowX = 680f))
        assertFalse(labelHiddenByNow(tickX = 1020f, nowX = 680f))
    }
}
