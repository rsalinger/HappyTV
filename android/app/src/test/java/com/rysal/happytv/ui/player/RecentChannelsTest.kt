package com.rysal.happytv.ui.player

import com.rysal.happytv.data.entity.LiveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #220: which channels the recent row offers, how its keys route, and how long it stays up. */
class RecentChannelsTest {

    private fun stream(id: String, category: String = "1", name: String = "Channel $id") =
        LiveStream(id = id, categoryId = category, num = id.toIntOrNull(), name = name)

    private fun lineup(vararg ids: String, category: String = "1") =
        ids.associateWith { stream(it, category) }

    private fun select(
        recent: List<String>,
        current: String? = "cur",
        lineup: Map<String, LiveStream> = recent.associateWith { stream(it) },
        hiddenChannels: Set<String> = emptySet(),
        hiddenCategories: Set<String> = emptySet(),
        cap: Int = RECENT_CHANNELS_MAX,
    ) = RecentChannels.select(recent, current, lineup, hiddenChannels, hiddenCategories, cap).map { it.id }

    // ------------------------------------------------------------ selection

    @Test
    fun `keeps newest first order`() {
        assertEquals(listOf("c", "a", "b"), select(listOf("c", "a", "b")))
    }

    @Test
    fun `leaves out the channel playing now and keeps the rest in order`() {
        assertEquals(listOf("b", "c"), select(listOf("cur", "b", "c")))
        assertEquals(listOf("b", "c"), select(listOf("b", "cur", "c")))
    }

    @Test
    fun `leaves out hidden channels`() {
        assertEquals(listOf("a", "c"), select(listOf("a", "b", "c"), hiddenChannels = setOf("b")))
    }

    @Test
    fun `leaves out channels in hidden categories`() {
        val l = mapOf("a" to stream("a", "1"), "b" to stream("b", "9"), "c" to stream("c", "1"))
        assertEquals(listOf("a", "c"), select(listOf("a", "b", "c"), lineup = l, hiddenCategories = setOf("9")))
    }

    @Test
    fun `leaves out channels no longer in the lineup`() {
        assertEquals(listOf("a", "c"), select(listOf("a", "gone", "c"), lineup = lineup("a", "c")))
    }

    @Test
    fun `lists a channel once even when the history repeats it`() {
        assertEquals(listOf("a", "b"), select(listOf("a", "b", "a")))
    }

    @Test
    fun `caps at seven after the exclusions are applied`() {
        val ids = (1..12).map { "ch$it" }
        assertEquals(ids.take(7), select(ids))
        // Excluded rows do not use up seats: ch1 and ch2 are out, so ch3..ch9 fill the row.
        assertEquals(ids.subList(2, 9), select(ids, hiddenChannels = setOf("ch1", "ch2")))
        assertEquals(RECENT_CHANNELS_MAX, 7)
    }

    @Test
    fun `fewer than seven shows only what exists and nothing when only the current channel was watched`() {
        assertEquals(listOf("a", "b"), select(listOf("a", "b")))
        assertTrue(select(listOf("cur")).isEmpty())
        assertTrue(select(emptyList()).isEmpty())
    }

    @Test
    fun `no current channel excludes nothing`() {
        assertEquals(listOf("a", "b"), select(listOf("a", "b"), current = null))
    }

    // ----------------------------------------------------------------- card

    @Test
    fun `card carries the program on now and its progress`() {
        val now = PlayerProgram("Morning Report", startUtcMillis = 1_000L, endUtcMillis = 5_000L)
        val card = RecentChannels.card(stream("104", name = "Harbor News"), PlayerNowNext(now, null))
        assertEquals("Morning Report", card.programTitle)
        assertTrue(card.hasGuide)
        assertEquals(0.5f, card.progressFraction(3_000L)!!, 0.001f)
        assertEquals(0f, card.progressFraction(500L)!!, 0.001f)
        assertEquals(1f, card.progressFraction(9_000L)!!, 0.001f)
    }

    @Test
    fun `card without guide data has no program and no progress line`() {
        val none = RecentChannels.card(stream("1"), null)
        assertFalse(none.hasGuide)
        assertNull(none.progressFraction(1L))
        // Guide rows exist but nothing airs now.
        val nextOnly = RecentChannels.card(stream("1"), PlayerNowNext(null, PlayerProgram("Later", 9L, 10L)))
        assertNull(nextOnly.programTitle)
        assertNull(nextOnly.progressFraction(1L))
    }

    @Test
    fun `a program with a zero length gets no progress line`() {
        val card = RecentChannelCard("1", 1, "A", null, programTitle = "X", startUtcMillis = 5L, endUtcMillis = 5L)
        assertNull(card.progressFraction(5L))
    }

    // -------------------------------------------------------------- timeout

    @Test
    fun `the row hides after ten seconds and the controls alone after five`() {
        assertEquals(10_000L, controlsHideDelayMillis(recentRowOpen = true))
        assertEquals(5_000L, controlsHideDelayMillis(recentRowOpen = false))
    }

    // ---------------------------------------------------------- eligibility

    private fun eligible(
        mode: PlaybackMode? = PlaybackMode.Live,
        handler: Boolean = true,
        count: Int = 3,
        tracks: Boolean = false,
        leave: Boolean = false,
        end: Boolean = false,
        buffering: Boolean = false,
    ) = recentRowEligible(mode, handler, count, tracks, leave, end, buffering)

    @Test
    fun `live and catch-up playback with recent channels are eligible`() {
        assertTrue(eligible(PlaybackMode.Live))
        assertTrue(eligible(PlaybackMode.Replay))
    }

    @Test
    fun `movies and episodes, no recents, no host handler and open panels are not eligible`() {
        assertFalse(eligible(PlaybackMode.Movie))
        assertFalse(eligible(mode = null))
        assertFalse(eligible(count = 0))
        assertFalse(eligible(handler = false))
        assertFalse(eligible(tracks = true))
        assertFalse(eligible(leave = true))
        assertFalse(eligible(end = true))
        assertFalse(eligible(buffering = true))
    }

    // ----------------------------------------------------------- key routing

    private fun route(key: RecentRowKey, open: Boolean, eligible: Boolean = true, index: Int = 0, count: Int = 4) =
        RecentRowKeys.route(key, rowOpen = open, eligible = eligible, focusedIndex = index, cardCount = count)

    @Test
    fun `Down opens the row when eligible`() {
        // The router does not see the controls: live eligibility already requires them (#224, ChannelZapTest).
        assertEquals(RecentRowAction.OpenRow, route(RecentRowKey.Down, open = false))
    }

    @Test
    fun `with no recent channels every key passes through, so Up and Down keep today's behavior`() {
        for (key in RecentRowKey.values()) {
            assertEquals("$key", RecentRowAction.PassThrough, route(key, open = false, eligible = false, count = 0))
        }
    }

    @Test
    fun `with the row closed only Down is the row's key`() {
        for (key in listOf(RecentRowKey.Up, RecentRowKey.Left, RecentRowKey.Right, RecentRowKey.Ok, RecentRowKey.Back)) {
            assertEquals("$key", RecentRowAction.PassThrough, route(key, open = false))
        }
    }

    @Test
    fun `Up and Back close the open row`() {
        assertEquals(RecentRowAction.CloseRow, route(RecentRowKey.Up, open = true))
        assertEquals(RecentRowAction.CloseRow, route(RecentRowKey.Back, open = true))
    }

    @Test
    fun `Down on the open row does nothing`() {
        assertEquals(RecentRowAction.Consume, route(RecentRowKey.Down, open = true))
    }

    @Test
    fun `Left and Right move between cards and stop at the ends without wrapping`() {
        assertEquals(RecentRowAction.FocusCard(2), route(RecentRowKey.Right, open = true, index = 1))
        assertEquals(RecentRowAction.FocusCard(0), route(RecentRowKey.Left, open = true, index = 1))
        assertEquals(RecentRowAction.Consume, route(RecentRowKey.Left, open = true, index = 0))
        assertEquals(RecentRowAction.Consume, route(RecentRowKey.Right, open = true, index = 3, count = 4))
        assertEquals(RecentRowAction.Consume, route(RecentRowKey.Right, open = true, index = 0, count = 1))
    }

    @Test
    fun `OK on the open row picks the focused card`() {
        assertEquals(RecentRowAction.PickFocused, route(RecentRowKey.Ok, open = true, index = 2))
    }

    @Test
    fun `an open row whose list emptied closes on any key`() {
        for (key in RecentRowKey.values()) {
            assertEquals("$key", RecentRowAction.CloseRow, route(key, open = true, count = 0))
        }
    }

    @Test
    fun `catch-up routes like live`() {
        // The router takes eligibility from the mode (see recentRowEligible); a catch-up channel is eligible.
        assertTrue(eligible(PlaybackMode.Replay))
        assertEquals(RecentRowAction.OpenRow, route(RecentRowKey.Down, open = false, eligible = eligible(PlaybackMode.Replay)))
        assertEquals(RecentRowAction.PassThrough, route(RecentRowKey.Down, open = false, eligible = eligible(PlaybackMode.Movie)))
    }

    // ------------------------------------------------------------ quiet zap

    @Test
    fun `a pick then the picked channel ready stays quiet`() {
        var armed = true
        armed = quietZapStep(armed, ZapLanding.Loading).armed
        assertTrue(armed)
        val step = quietZapStep(armed, ZapLanding.Ready)
        assertFalse(step.showControls)
        assertFalse(step.armed)
    }

    @Test
    fun `a pick then a failure then a later ready of another channel shows the controls`() {
        var armed = true
        armed = quietZapStep(armed, ZapLanding.Loading).armed
        armed = quietZapStep(armed, ZapLanding.Failed).armed
        assertFalse(armed)
        // Later zap from the guide: Loading then Ready.
        armed = quietZapStep(armed, ZapLanding.Loading).armed
        assertTrue(quietZapStep(armed, ZapLanding.Ready).showControls)
    }

    @Test
    fun `without a pick every ready shows the controls`() {
        assertTrue(quietZapStep(false, ZapLanding.Ready).showControls)
    }
}
