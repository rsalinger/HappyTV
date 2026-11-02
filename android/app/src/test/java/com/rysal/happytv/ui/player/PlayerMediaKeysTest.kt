package com.rysal.happytv.ui.player

import android.app.Application
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #136: OK with the controls hidden only shows them; Play/Pause and Space toggle,
 * Play only plays, Pause only pauses, hidden or shown; the leave dialog ignores them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlayerMediaKeysTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val now = 1_713_542_400_000L
    private lateinit var handle: MediaKeysHandle

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        handle = MediaKeysHandle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun controlsShown(): Boolean =
        compose.onAllNodes(hasText("Pause") or hasContentDescription("Pause") or hasText("Play") or hasContentDescription("Play"))
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun okWhileHiddenOnlyShowsControls() {
        start(hidden = true)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(0, handle.toggles)
        assertEquals(emptyList<Boolean>(), handle.sets)
        assertEquals(true, controlsShown())
    }

    @Test
    fun okWhileHiddenFullPressDoesNotClickTheNewlyFocusedPlayPause() {
        start(hidden = true)
        down(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle() // focus lands on Play/Pause between the down and the up
        up(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(0, handle.toggles)
        assertEquals(true, controlsShown())
    }

    @Test
    fun okWhileHiddenHeldWithRepeatsDoesNotToggle() {
        start(hidden = true)
        down(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        down(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1)
        down(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 2)
        up(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(0, handle.toggles)
    }

    @Test
    fun theNextOkAfterRevealingActsOnPlayPause() {
        start(hidden = true)
        down(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        up(KeyEvent.KEYCODE_DPAD_CENTER)
        down(KeyEvent.KEYCODE_DPAD_CENTER)
        up(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, handle.toggles)
    }

    @Test
    fun spaceFullPressWithPlayPauseFocusedTogglesOnce() {
        start(hidden = false)
        down(KeyEvent.KEYCODE_SPACE)
        up(KeyEvent.KEYCODE_SPACE)
        assertEquals(1, handle.toggles)
    }

    @Test
    fun playPauseAndSpaceToggleWhileHidden() {
        start(hidden = true)
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        press(KeyEvent.KEYCODE_SPACE)
        assertEquals(2, handle.toggles)
        assertEquals(true, controlsShown())
    }

    @Test
    fun playAndPauseSetIntentWhileHidden() {
        start(hidden = true)
        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        assertEquals(listOf(true, false), handle.sets)
        assertEquals(0, handle.toggles)
    }

    @Test
    fun mediaKeysWorkWhileControlsAreShown() {
        start(hidden = false)
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        press(KeyEvent.KEYCODE_SPACE)
        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        assertEquals(2, handle.toggles)
        assertEquals(listOf(true, false), handle.sets)
    }

    private fun playPauseFocused(): Boolean =
        compose.onAllNodes(
            isFocused() and (hasText("Pause") or hasContentDescription("Pause") or hasText("Play") or hasContentDescription("Play")),
        ).fetchSemanticsNodes().isNotEmpty()

    /** A reveal queues its focus request (50 ms, then once per frame); let it land. */
    private fun settleFocus() {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    /**
     * The entry reveal's Play/Pause focus request retries each frame for up to one second of wall
     * time (PENDING_FOCUS_TIMEOUT_NANOS) and would otherwise grab the control when it returns.
     */
    private fun outlastQueuedFocusRequest() {
        Thread.sleep(1_100)
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun anyControlFocused(): Boolean =
        compose.onAllNodes(isFocused() and hasClickAction()).fetchSemanticsNodes().isNotEmpty()

    /** Hides the controls, lets any queued focus request run out, then reveals them with a media key (nothing focused). */
    private fun revealWithMediaKey(code: Int = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
        start(hidden = true)
        outlastQueuedFocusRequest()
        press(code)
        assertEquals(true, controlsShown())
        assertEquals(false, anyControlFocused())
    }

    /**
     * #169: a media key shows the controls but takes no focus, so the root anchor still holds it and
     * Right had no neighbour. Now it lands on Play/Pause, like an OK reveal, and neither toggles nor seeks.
     */
    @Test
    fun rightAfterAMediaKeyReveal_focusesPlayPause_withoutTogglingAgainOrSeeking() {
        revealWithMediaKey()
        assertEquals(1, handle.toggles)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settleFocus()
        assertEquals(true, playPauseFocused())
        assertEquals(1, handle.toggles)
        assertEquals(emptyList<Long>(), handle.seeks)
    }

    @Test
    fun leftAfterAMediaKeyReveal_focusesPlayPause_andTheNextRightMovesOn() {
        revealWithMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        settleFocus()
        assertEquals(true, playPauseFocused())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(true, anyControlFocused())
        assertEquals(false, playPauseFocused())
    }

    /** The OK reveal is the model: Play/Pause focused, and Right moves on from there (#102 focus rules intact). */
    @Test
    fun rightWithAControlAlreadyFocused_stillMovesFocusOn() {
        start(hidden = true)
        outlastQueuedFocusRequest()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        settleFocus()
        assertEquals(true, playPauseFocused())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(true, anyControlFocused())
        assertEquals(false, playPauseFocused())
    }

    @Test
    fun leftRightGate_onlyWhenControlsUpAndNothingIsFocused() {
        assertEquals(true, leftRightTakesFocus(true, false, true, false, false))
        assertEquals(false, leftRightTakesFocus(false, false, true, false, false)) // hidden: seeks or skips
        assertEquals(false, leftRightTakesFocus(true, true, true, false, false)) // tracks panel
        assertEquals(false, leftRightTakesFocus(true, false, false, false, false))
        assertEquals(false, leftRightTakesFocus(true, false, true, true, false)) // scrubber seeks
        assertEquals(false, leftRightTakesFocus(true, false, true, false, true)) // focus already on its way
    }

    @Test
    fun leaveDialogIgnoresMediaKeysAndSpace() {
        start(hidden = true)
        press(KeyEvent.KEYCODE_BACK) // opens the dialog (44 min left)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Stop watching?")).fetchSemanticsNodes().isNotEmpty()
        }
        for (code in listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        )) press(code)
        assertEquals(0, handle.toggles)
        assertEquals(emptyList<Boolean>(), handle.sets)
    }

    // ------------------------------------------------------------------ setup

    private fun start(hidden: Boolean) {
        val repo = MediaKeysRepo()
        val vm = PlayerViewModel(PlayerLaunch.movie("9"), repo, handle, clock = { now })
        vm.onBuffering(false)
        vm.onIsPlayingChanged(true)
        vm.onPosition(10 * 60_000L, 54 * 60_000L, 12 * 60_000L)
        compose.setContent { HappyTvTheme { PlayerScreen(viewModel = vm, onBack = {}) } }
        compose.waitForIdle()
        enterFocusSystem()
        if (hidden) press(KeyEvent.KEYCODE_BACK) // first Back hides the controls
    }

    private fun press(code: Int) {
        compose.activityRule.scenario.onActivity { a ->
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private fun down(code: Int, repeat: Int = 0) {
        compose.activityRule.scenario.onActivity { a ->
            a.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, repeat))
        }
        if (repeat == 0) compose.waitForIdle()
    }

    private fun up(code: Int) {
        compose.activityRule.scenario.onActivity { a ->
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private fun enterFocusSystem() {
        compose.activityRule.scenario.onActivity { activity ->
            val composeView = findComposeView(activity.window.decorView) ?: error("no compose view")
            composeView.requestFocus()
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        }
        compose.waitForIdle()
    }

    private fun findComposeView(root: android.view.View?): android.view.View? {
        if (root == null) return null
        if (root is ViewRootForTest) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) findComposeView(root.getChildAt(i))?.let { return it }
        return null
    }
}

private class MediaKeysHandle : PlayerHandle {
    var toggles = 0
    val sets = mutableListOf<Boolean>()
    override fun togglePlayPause() { toggles++ }
    override fun setPlayWhenReady(play: Boolean) { sets += play }
    val seeks = mutableListOf<Long>()
    override fun seekBy(deltaMillis: Long) { seeks += deltaMillis }
    override fun prepare() = Unit
}

private class MediaKeysRepo : PlayerRepository {
    override suspend fun connection() = PlayerConnection("panel.example", 8080, false, "u", "p")
    override suspend fun liveStream(id: String): LiveStream? = null
    override suspend fun vodStream(id: String): VodStream? =
        VodStream(id = "9", categoryId = "1", name = "M", containerExtension = "mp4")
    override suspend fun firstEpisode(seriesId: String): PlayerEpisode? = null
    override suspend fun allowedOutputFormats(): List<String> = listOf("m3u8", "ts")
    override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? = null
    override suspend fun isFavorite(streamId: String, streamType: StreamType) = false
    override suspend fun toggleFavorite(streamId: String, streamType: StreamType) = Unit
}
