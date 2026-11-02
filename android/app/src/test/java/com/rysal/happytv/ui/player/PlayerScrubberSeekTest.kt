package com.rysal.happytv.ui.player

import android.app.Application
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #102: Left/Right seek from the focused scrubber while the controls show. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlayerScrubberSeekTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val now = 1_713_542_400_000L
    private val position = 10 * 60_000L
    private lateinit var handle: SeekRecordingHandle
    private val perfLines = mutableListOf<String>()
    private var backs = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        handle = SeekRecordingHandle()
        backs = 0
        perfLines.clear()
        HtvPerf.sink = { perfLines += it }
    }

    @After
    fun tearDown() {
        HtvPerf.resetForTest()
        Dispatchers.resetMain()
    }

    private val scrubberFocused get() = hasTestTag(SCRUBBER_FOCUS_TAG) and isFocused()

    @Test
    fun downMovesToTheScrubberAndUpReturnsToTheButtons() {
        start()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitScrubberFocus(true)
        press(KeyEvent.KEYCODE_DPAD_UP)
        awaitScrubberFocus(false)
    }

    @Test
    fun eachPressWithTheScrubberFocusedSeeksTenSeconds() {
        start()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitScrubberFocus(true)
        // Each press waits out the idle window, so each is its own seek, and a
        // press right after a commit continues from the committed target.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitSeeks(1)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitSeeks(2)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitSeeks(3)
        assertEquals(listOf(position + 10_000L, position + 20_000L, position + 10_000L), handle.seekTos)
        awaitScrubberFocus(true)
        assertEquals(emptyList<Long>(), handle.seeks)
    }

    @Test
    fun aBurstOfPressesIsOneSeek() {
        start()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitScrubberFocus(true)
        burst(KeyEvent.KEYCODE_DPAD_RIGHT, 6)
        awaitSeeks(1)
        tick(700) // a second seek would have been issued by now
        assertEquals(listOf(position + 60_000L), handle.seekTos)
        assertEquals(1, perfLines.count { it.startsWith("player.seek ") && it.endsWith("presses=6") })
    }

    @Test
    fun holdingRightAppliesOneSeekOnKeyUp() {
        start()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitScrubberFocus(true)
        compose.activityRule.scenario.onActivity { a ->
            val t = 1_000L
            a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            for (i in 1..5) {
                a.dispatchKeyEvent(KeyEvent(t, t + 500L + i * 50L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, i))
            }
            a.dispatchKeyEvent(KeyEvent(t, t + 800L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        }
        // Key-up applies the seek straight away, well inside the idle window.
        assertEquals(1, handle.seekTos.size)
        tick(700)
        assertEquals(1, handle.seekTos.size)
    }

    @Test
    fun aHeldKeyDoesNotCommitUntilKeyUpEvenPastTheRepeatDelay() {
        start()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitScrubberFocus(true)
        val t = 1_000L
        compose.activityRule.scenario.onActivity { a ->
            a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        }
        tick(600) // Android waits 500 ms before the first repeat
        assertEquals("no seek before the first repeat", 0, handle.seekTos.size)
        compose.activityRule.scenario.onActivity { a ->
            for (i in 1..40) {
                a.dispatchKeyEvent(KeyEvent(t, t + 500L + i * 50L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, i))
            }
        }
        tick(300)
        assertEquals("still held: nothing applied yet", 0, handle.seekTos.size)
        compose.activityRule.scenario.onActivity { a ->
            a.dispatchKeyEvent(KeyEvent(t, t + 3_000L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        }
        assertEquals(1, handle.seekTos.size)
        // 10 s first press, repeats climb the 10 s, 30 s and 60 s tiers: well past ten steps of 10 s.
        assertTrue(handle.seekTos[0] > position + 10 * 10_000L)
    }

    @Test
    fun controlsAndScrubberFocusSurvivePostSeekBuffering() {
        start()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitScrubberFocus(true)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitSeeks(1)
        vm.onBuffering(true)
        tick(300)
        assertEquals("controls stay up while the seek rebuffers", 1,
            compose.onAllNodes(hasTestTag(SCRUBBER_FOCUS_TAG)).fetchSemanticsNodes().size)
        vm.onBuffering(false)
        awaitScrubberFocus(true)
        // The next press still lands on the scrubber.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitSeeks(2)
    }

    @Test
    fun keysWorkFromHiddenAfterTheControlsAutoHide() {
        start()
        tick(4_000)
        tick(2_000) // the 5 s fade
        until { compose.onAllNodes(hasTestTag(SCRUBBER_FOCUS_TAG)).fetchSemanticsNodes().isEmpty() }
        tick(300) // the root anchor takes focus
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitSeeks(1)
        assertEquals(listOf(position + 10_000L), handle.seekTos)
        awaitScrubberFocus(true)
    }

    @Test
    fun shortBackFromHiddenControlsAsksBeforeLeaving() {
        start()
        tick(4_000)
        tick(2_000) // auto-hide
        until { compose.onAllNodes(hasTestTag(SCRUBBER_FOCUS_TAG)).fetchSemanticsNodes().isEmpty() }
        tick(300)
        press(KeyEvent.KEYCODE_BACK)
        until { compose.onAllNodes(hasText("Stop watching?")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("no leave before the dialog is answered", 0, backs)
    }

    @Test
    fun theHiddenRootAnchorIsLabelledVideoAndOkOnlyBringsTheControlsUp() {
        start()
        tick(4_000)
        tick(2_000)
        until { compose.onAllNodes(hasTestTag(SCRUBBER_FOCUS_TAG)).fetchSemanticsNodes().isEmpty() }
        until { compose.onAllNodes(hasContentDescription("Video") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_DPAD_CENTER) // #136: shows the controls, no toggle
        assertEquals(0, handle.toggles)
        until { compose.onAllNodes(hasContentDescription("Pause") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theFocusedControlCarriesItsLabelOnTheFocusedNode() {
        start()
        until {
            compose.onAllNodes(hasContentDescription("Pause") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theTargetTimeShowsAboveTheDotWhileSeeking() {
        start()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitScrubberFocus(true)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        until { compose.onAllNodes(hasTestTag(SCRUBBER_TARGET_TAG)).fetchSemanticsNodes().isNotEmpty() }
        until { compose.onAllNodes(hasTestTag(SCRUBBER_TARGET_TAG)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun backWithTheScrubberFocusedHidesTheControlsAndDoesNotLeave() {
        start()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitScrubberFocus(true)
        press(KeyEvent.KEYCODE_BACK)
        until { compose.onAllNodes(hasTestTag(SCRUBBER_FOCUS_TAG)).fetchSemanticsNodes().isEmpty() }
        assertEquals(0, backs)
    }

    @Test
    fun okOnTheScrubberTogglesPlaybackAndKeepsFocusThere() {
        start()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitScrubberFocus(true)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, handle.toggles)
        tick(300)
        compose.onNode(scrubberFocused).assertExists()
    }

    // ------------------------------------------------------------------ setup

    private lateinit var vm: PlayerViewModel

    private fun start() {
        val repo = SeekFakeRepo(
            mapOf("9" to VodStream(id = "9", categoryId = "1", name = "M", containerExtension = "mp4")),
        )
        vm = PlayerViewModel(PlayerLaunch.movie("9"), repo, handle, clock = { now })
        vm.onBuffering(false)
        vm.onIsPlayingChanged(true)
        vm.onPosition(position, 120 * 60_000L, position + 60_000L)
        // Virtual time is stepped by hand: left to auto-advance, the 5 s controls
        // fade elapses inside every waitForIdle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HappyTvTheme { PlayerScreen(viewModel = vm, onBack = { backs++ }) }
        }
        until { true }
        compose.activityRule.scenario.onActivity { activity ->
            val view = findComposeView(activity.window.decorView) ?: error("no compose view")
            view.requestFocus()
        }
        until { true }
        // Robolectric never grants window focus; the first key wakes the focus
        // system and is lost (Menu does nothing in the player).
        press(KeyEvent.KEYCODE_MENU)
    }

    /**
     * Steps virtual time in 100 ms slices until [condition] holds (a slice at
     * least, so queued effects start). Bounded under the 5 s controls fade.
     */
    private fun until(condition: () -> Boolean) {
        repeat(40) {
            tick(100)
            if (condition()) return
        }
        throw AssertionError("condition not met in 4 s of virtual time")
    }

    /**
     * Advance virtual time, then flush the looper: Robolectric's paused looper
     * holds the snapshot-apply notification of a state write made in a key
     * handler until an idle sync, so a bare advanceTimeBy never recomposes it.
     */
    private fun tick(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun awaitScrubberFocus(expected: Boolean) = until {
        compose.onAllNodes(scrubberFocused).fetchSemanticsNodes().isNotEmpty() == expected
    }

    private fun awaitSeeks(count: Int) = until { handle.seekTos.size >= count }

    private fun press(code: Int) {
        compose.activityRule.scenario.onActivity { a ->
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        tick(100)
    }

    private fun burst(code: Int, count: Int) {
        compose.activityRule.scenario.onActivity { a ->
            repeat(count) {
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
        tick(100)
    }

    private fun findComposeView(root: android.view.View?): android.view.View? {
        if (root == null) return null
        if (root is ViewRootForTest) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) findComposeView(root.getChildAt(i))?.let { return it }
        return null
    }
}

private class SeekRecordingHandle : PlayerHandle {
    val seeks = mutableListOf<Long>()
    val seekTos = mutableListOf<Long>()
    var toggles = 0
    override fun togglePlayPause() { toggles++ }
    override fun seekBy(deltaMillis: Long) { seeks += deltaMillis }
    override fun seekTo(positionMillis: Long) { seekTos += positionMillis }
    override fun prepare() = Unit
}

private class SeekFakeRepo(private val vod: Map<String, VodStream>) : PlayerRepository {
    override suspend fun connection() = PlayerConnection("panel.example", 8080, false, "u", "p")
    override suspend fun liveStream(id: String): LiveStream? = null
    override suspend fun vodStream(id: String): VodStream? = vod[id]
    override suspend fun firstEpisode(seriesId: String): PlayerEpisode? = null
    override suspend fun allowedOutputFormats(): List<String> = listOf("m3u8", "ts")
    override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? = null
    override suspend fun isFavorite(streamId: String, streamType: StreamType) = false
    override suspend fun toggleFavorite(streamId: String, streamType: StreamType) = Unit
}
