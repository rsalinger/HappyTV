package com.rysal.happytv.ui.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #224: key routing for channel up/down, and a held key becoming one tune. Virtual time, no sleeps. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelZapTest {

    // ---------------------------------------------------------------- key routing

    private fun action(
        down: Boolean,
        mode: PlaybackMode? = PlaybackMode.Live,
        controls: Boolean = false,
        panel: Boolean = false,
        list: Boolean = true,
    ) = verticalKeyAction(down, mode, controls, panel, list)

    @Test
    fun `live with the bar hidden, Up is channel up and Down is channel down`() {
        assertEquals(VerticalKeyAction.ChannelUp, action(down = false))
        assertEquals(VerticalKeyAction.ChannelDown, action(down = true))
    }

    @Test
    fun `live with the bar open, Up does nothing and Down is left to the recent row`() {
        assertEquals(VerticalKeyAction.Ignore, action(down = false, controls = true))
        // The recent row routes Down first; when it declines (no recents), Down is swallowed, not a channel step.
        assertEquals(VerticalKeyAction.Ignore, action(down = true, controls = true))
    }

    @Test
    fun `Up no longer opens Now and Next in live`() {
        for (controls in listOf(true, false)) {
            assertTrue(action(down = false, controls = controls) != VerticalKeyAction.ToggleProgramInfo)
        }
    }

    @Test
    fun `replay keeps today's Up and Down`() {
        for (controls in listOf(true, false)) for (down in listOf(true, false)) {
            assertEquals(VerticalKeyAction.ToggleProgramInfo, action(down, PlaybackMode.Replay, controls))
        }
    }

    @Test
    fun `movies keep the scrubber navigation with the controls open and the Now and Next toggle without`() {
        assertEquals(VerticalKeyAction.MovieScrubber, action(true, PlaybackMode.Movie, controls = true))
        assertEquals(VerticalKeyAction.MovieScrubber, action(false, PlaybackMode.Movie, controls = true))
        assertEquals(VerticalKeyAction.ToggleProgramInfo, action(true, PlaybackMode.Movie, controls = false))
    }

    @Test
    fun `an open tracks panel keeps its keys`() {
        assertEquals(VerticalKeyAction.PassToPanel, action(true, controls = true, panel = true))
        assertEquals(VerticalKeyAction.PassToPanel, action(false, controls = false, panel = true))
    }

    @Test
    fun `live without a channel list (the movie player) keeps the Now and Next toggle`() {
        assertEquals(VerticalKeyAction.ToggleProgramInfo, action(false, list = false))
    }

    @Test
    fun `the recent row opens in live only when the bar is open, in catch-up either way`() {
        fun eligible(mode: PlaybackMode, controls: Boolean) =
            recentRowEligible(mode, true, 3, false, false, false, controlsVisible = controls)
        assertTrue(eligible(PlaybackMode.Live, controls = true))
        assertFalse(eligible(PlaybackMode.Live, controls = false))
        assertTrue(eligible(PlaybackMode.Replay, controls = true))
        assertTrue(eligible(PlaybackMode.Replay, controls = false))
    }

    @Test
    fun `Down with the bar hidden steps a channel, with it open it opens the recent row`() {
        fun downWith(controls: Boolean): Any {
            val eligible = recentRowEligible(PlaybackMode.Live, true, 3, false, false, false, controlsVisible = controls)
            val route = RecentRowKeys.route(RecentRowKey.Down, rowOpen = false, eligible = eligible, focusedIndex = 0, cardCount = 3)
            return if (route == RecentRowAction.OpenRow) route else action(down = true, controls = controls)
        }
        assertEquals(VerticalKeyAction.ChannelDown, downWith(controls = false))
        assertEquals(RecentRowAction.OpenRow, downWith(controls = true))
    }

    @Test
    fun `stepping goes on through the load and over a live channel's error card`() {
        assertTrue(zapKeysOutsideReady(loading = true, zapActive = true, errorCardMode = null))
        assertFalse(zapKeysOutsideReady(loading = true, zapActive = false, errorCardMode = null))
        assertTrue(zapKeysOutsideReady(loading = false, zapActive = false, errorCardMode = PlaybackMode.Live))
        assertFalse(zapKeysOutsideReady(loading = false, zapActive = false, errorCardMode = PlaybackMode.Movie))
    }

    // ---------------------------------------------------------------- walking a list

    @Test
    fun `walkList wraps and skips entries that do not resolve`() = runBlocking {
        val ids = listOf("a", "b", "c", "d")
        val usable = setOf("a", "d")
        assertEquals("d", walkList(ids, "a", +1) { if (it in usable) it else null }) // b, c skipped
        assertEquals("a", walkList(ids, "d", +1) { if (it in usable) it else null }) // wraps
        assertEquals("d", walkList(ids, "a", -1) { if (it in usable) it else null }) // wraps backwards
        assertNull(walkList(ids, "a", +1) { null })
        assertNull(walkList(ids, "zzz", +1) { it })
    }

    // ---------------------------------------------------------------- hold coalescing

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val tunes = mutableListOf<String>()
    private var quiet = 0
    private val lineup = (0..9).map { "c$it" }

    private val stepper = object : ChannelStepper {
        override var playingStreamId: String? = "c0"

        override suspend fun neighbour(fromStreamId: String, direction: Int): ZapTarget {
            val to = lineup[Math.floorMod(lineup.indexOf(fromStreamId) + direction, lineup.size)]
            return ZapTarget(to, 100 + lineup.indexOf(to), "Channel $to", null)
        }

        override fun tune(streamId: String) {
            tunes += streamId
            playingStreamId = streamId
        }
    }

    private val zapper = ChannelZapper(scope, stepper, clock = { scope.currentTime }, beforeTune = { quiet++ })

    private fun advance(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    @Test
    fun `a tap tunes straight away`() {
        zapper.step(+1)
        assertEquals(listOf("c1"), tunes)
        assertEquals(1, quiet)
        advance(ZAP_SETTLE_MILLIS * 2)
        assertEquals("nothing more follows", listOf("c1"), tunes)
    }

    @Test
    fun `the panel shows the number and name of the channel being stepped to at once`() {
        zapper.step(+1)
        assertEquals("101 · Channel c1", zapper.preview.value!!.card.channelLine)
        assertEquals("c1", zapper.preview.value!!.streamId)
        assertEquals(PROGRAM_INFO_UNAVAILABLE, zapper.preview.value!!.card.title)
    }

    @Test
    fun `a hold tunes on the first press, then once more on the channel it ended on`() {
        zapper.step(+1, repeatCount = 0)
        // Auto-repeat every 50 ms for a second: five more steps get through at 200 ms.
        for (t in 1..20) {
            advance(50)
            zapper.step(+1, repeatCount = t)
        }
        assertEquals("only the first press opened a stream while held", listOf("c1"), tunes)
        assertEquals("c6", zapper.preview.value!!.streamId)
        advance(ZAP_SETTLE_MILLIS)
        assertEquals(listOf("c1", "c6"), tunes)
    }

    @Test
    fun `key-up tunes a held key without waiting for the settle time`() {
        zapper.step(+1, repeatCount = 1)
        advance(200)
        zapper.step(+1, repeatCount = 2)
        assertTrue(tunes.isEmpty())
        zapper.release()
        assertEquals(listOf("c2"), tunes)
        advance(ZAP_SETTLE_MILLIS * 2)
        assertEquals(listOf("c2"), tunes)
    }

    @Test
    fun `without a key-up the settle time tunes a held key`() {
        zapper.step(+1, repeatCount = 1)
        advance(ZAP_SETTLE_MILLIS - 1)
        assertTrue(tunes.isEmpty())
        advance(1)
        assertEquals(listOf("c1"), tunes)
    }

    @Test
    fun `a tap during a pending hold settle tunes immediately and cancels the settle`() {
        zapper.step(+1, repeatCount = 1)
        advance(100)
        zapper.step(+1, repeatCount = 0)
        assertEquals("the tap steps on from the pending target", listOf("c2"), tunes)
        advance(ZAP_SETTLE_MILLIS * 2)
        assertEquals("the old settle did not fire", listOf("c2"), tunes)
    }

    @Test
    fun `quick separate taps each tune at once`() {
        zapper.step(+1)
        advance(30)
        zapper.step(+1)
        advance(30)
        zapper.step(+1)
        assertEquals(listOf("c1", "c2", "c3"), tunes)
    }

    @Test
    fun `stepping back to the channel that is playing tunes nothing`() {
        zapper.step(+1, repeatCount = 1)
        advance(200)
        zapper.step(-1, repeatCount = 2)
        advance(ZAP_SETTLE_MILLIS * 2)
        assertTrue(tunes.isEmpty())
        assertEquals(0, quiet)
    }

    @Test
    fun `up walks backwards and wraps at the top of the list`() {
        zapper.step(-1)
        assertEquals(listOf("c9"), tunes)
    }

    @Test
    fun `the next press starts from the channel that was tuned`() {
        zapper.step(+1)
        zapper.step(+1)
        assertEquals(listOf("c1", "c2"), tunes)
    }

    @Test
    fun `the panel stays up until dismissed, and dismissing does not cancel a pending tune`() {
        zapper.step(+1, repeatCount = 1)
        zapper.dismiss()
        assertTrue(zapper.isActive)
        advance(ZAP_SETTLE_MILLIS)
        assertEquals(listOf("c1"), tunes)
        zapper.dismiss()
        assertFalse(zapper.isActive)
    }

    @Test
    fun `cancel drops a pending tune`() {
        zapper.step(+1, repeatCount = 1)
        zapper.cancel()
        advance(ZAP_SETTLE_MILLIS * 2)
        assertTrue(tunes.isEmpty())
        assertFalse(zapper.isActive)
    }

    @Test
    fun `nothing happens when no channel is playing`() {
        stepper.playingStreamId = null
        zapper.step(+1)
        advance(ZAP_SETTLE_MILLIS * 2)
        assertTrue(tunes.isEmpty())
        assertFalse(zapper.isActive)
    }
}
