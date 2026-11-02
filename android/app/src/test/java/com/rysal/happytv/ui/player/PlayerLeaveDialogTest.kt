package com.rysal.happytv.ui.player

import android.app.Application
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.hasContentDescription
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #66: the "Stop watching?" dialog is a modal over the player. It must own
 * every key (the root preview handler used to seek and toggle play/pause
 * behind it), keep focus on its two buttons, and treat Back as Keep watching.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlayerLeaveDialogTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val now = 1_713_542_400_000L
    private var backs = 0
    private var longBacks = 0
    private lateinit var handle: RecordingHandle

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backs = 0
        longBacks = 0
        handle = RecordingHandle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun movieDialogOpensOnKeepWatchingWithMovieCopy() {
        openDialog(episode = false)

        compose.onNode(hasText("Stop watching?")).assertExists()
        compose.onNode(hasText("The movie has about 44 min left.")).assertExists()
        compose.onNode(hasText("Keep watching") and isFocused()).assertExists()
    }

    @Test
    fun episodeDialogSaysEpisode() {
        openDialog(episode = true)

        compose.onNode(hasText("The episode has about 44 min left.")).assertExists()
        compose.onNode(hasText("The movie has about 44 min left.")).assertDoesNotExist()
    }

    @Test
    fun rightReachesStopWatchingAndLeftComesBack() {
        openDialog()

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(hasText("Stop watching") and isFocused()).assertExists()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNode(hasText("Keep watching") and isFocused()).assertExists()
        assertNoPlayerEffect()
    }

    @Test
    fun noKeyReachesThePlayerAndFocusNeverLeavesTheDialog() {
        openDialog()

        for (code in listOf(
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )) {
            press(code)
            val onAButton = compose.onAllNodes(
                (hasText("Keep watching") or hasText("Stop watching")) and isFocused(),
            ).fetchSemanticsNodes().size
            assertEquals("focus left the dialog after key $code", 1, onAButton)
        }
        assertNoPlayerEffect()
        compose.onNode(hasText("Stop watching?")).assertExists()
    }

    @Test
    fun okOnKeepWatchingDismissesWithoutTouchingPlayback() {
        openDialog()

        press(KeyEvent.KEYCODE_DPAD_CENTER)

        compose.onNode(hasText("Stop watching?")).assertDoesNotExist()
        assertEquals(0, backs)
        assertNoPlayerEffect()
    }

    @Test
    fun okOnStopWatchingLeavesThePlayer() {
        openDialog()

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals("Stop watching must hand control back to details", 1, backs)
        assertNoPlayerEffect()
    }

    @Test
    fun backWhileOpenIsKeepWatchingAndBringsTheControlsBack() {
        openDialog()

        press(KeyEvent.KEYCODE_BACK)

        compose.onNode(hasText("Stop watching?")).assertDoesNotExist()
        assertEquals("Back on the dialog must not leave", 0, backs)
        assertTrue(
            "controls visible again",
            compose.onAllNodes(
                hasText("Pause") or hasContentDescription("Pause") or
                    hasText("Play") or hasContentDescription("Play"),
            ).fetchSemanticsNodes().isNotEmpty(),
        )
        assertNoPlayerEffect()
    }

    @Test
    fun holdingBackOnAMovieStopsAndLeavesDirectly() {
        setScreen(movieVm(), withLongBack = true)
        enterFocusSystem()

        val t = 1_000L
        compose.activityRule.scenario.onActivity { a ->
            a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            a.dispatchKeyEvent(KeyEvent(t, t + 600, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 1))
            a.dispatchKeyEvent(KeyEvent(t, t + 700, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 2))
            a.dispatchKeyEvent(KeyEvent(t, t + 800, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        }
        compose.waitForIdle()

        assertEquals(1, longBacks)
        assertEquals("the release of a hold is not a short Back", 0, backs)
        compose.onNode(hasText("Stop watching?")).assertDoesNotExist()
    }

    /** Mirrors PlayerActivity.dispatchKeyEvent: the sequence routes first. */
    private fun activityDispatch(seq: LongBackSequence, e: KeyEvent, a: ComponentActivity, stop: () -> Unit) {
        if (!seq.routeBack(e, HostMode.Full, stop)) a.dispatchKeyEvent(e)
    }

    @Test
    fun activityLevelHoldStopsWithoutOpeningTheDialogAndShortBackStillOpensIt() {
        // Production wiring: no onLongBack on the screen's focus path matters,
        // the activity decides. Controls hidden first so a short Back opens the dialog.
        var stops = 0
        val seq = LongBackSequence()
        // PlayerActivity always passes onLongBack, so the screen's Back gate is live.
        setScreen(movieVm(), withLongBack = true)
        enterFocusSystem()
        press(KeyEvent.KEYCODE_BACK) // hide controls

        val t = 1_000L
        compose.activityRule.scenario.onActivity { a ->
            // `input keyevent --longpress`: DOWN, DOWN(long press), UP
            activityDispatch(seq, KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), a) { stops++ }
            activityDispatch(seq, KeyEvent(t, t + 50, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 1), a) { stops++ }
            activityDispatch(seq, KeyEvent(t, t + 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 2), a) { stops++ }
            activityDispatch(seq, KeyEvent(t, t + 150, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), a) { stops++ }
        }
        compose.waitForIdle()
        assertEquals(1, stops)
        compose.onNode(hasText("Stop watching?")).assertDoesNotExist()

        // A short Back afterwards is a normal Back: it opens the dialog.
        compose.activityRule.scenario.onActivity { a ->
            activityDispatch(seq, KeyEvent(t, t + 500, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), a) { stops++ }
            activityDispatch(seq, KeyEvent(t, t + 560, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), a) { stops++ }
        }
        compose.waitForIdle()
        assertEquals(1, stops)
        compose.onNode(hasText("Stop watching?")).assertExists()
    }

    @Test
    fun longPressFlagAloneAlsoCountsAsAHold() {
        val seq = LongBackSequence()
        var stops = 0
        val t = 1_000L
        val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        val long = KeyEvent(t, t + 500, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0, 0, 0, KeyEvent.FLAG_LONG_PRESS)
        assertEquals(false, seq.routeBack(down, HostMode.Full) { stops++ })
        assertEquals(true, seq.routeBack(long, HostMode.Full) { stops++ })
        assertEquals(1, stops)
    }

    // ------------------------------------------------------------------ setup

    private fun assertNoPlayerEffect() {
        assertEquals("seeks behind the dialog", emptyList<Long>(), handle.seeks)
        assertEquals("play/pause behind the dialog", 0, handle.toggles)
    }

    /** Ready movie/episode with 44 min left; Back twice (controls, then dialog). */
    private fun openDialog(episode: Boolean = false) {
        setScreen(if (episode) episodeVm() else movieVm())
        enterFocusSystem()
        press(KeyEvent.KEYCODE_BACK) // hides the controls
        press(KeyEvent.KEYCODE_BACK) // opens the dialog
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Keep watching") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun press(code: Int) {
        compose.activityRule.scenario.onActivity { a ->
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private fun movieVm(): PlayerViewModel {
        val repo = FakeRepo(
            vod = mapOf("9" to VodStream(id = "9", categoryId = "1", name = "M", containerExtension = "mp4")),
        )
        return PlayerViewModel(PlayerLaunch.movie("9"), repo, handle, clock = { now }).also(::playing)
    }

    private fun episodeVm(): PlayerViewModel {
        val repo = FakeRepo(episode = PlayerEpisode("e1", "mp4", "Pilot", "Show"))
        return PlayerViewModel(PlayerLaunch.series("5"), repo, handle, clock = { now }).also(::playing)
    }

    private fun playing(vm: PlayerViewModel) {
        vm.onBuffering(false)
        vm.onIsPlayingChanged(true)
        vm.onPosition(10 * 60_000L, 54 * 60_000L, 12 * 60_000L)
    }

    private fun setScreen(vm: PlayerViewModel, withLongBack: Boolean = false) {
        compose.setContent {
            HappyTvTheme {
                PlayerScreen(
                    viewModel = vm,
                    onBack = { backs++ },
                    onLongBack = if (withLongBack) ({ longBacks++ }) else null,
                )
            }
        }
        compose.waitForIdle()
    }

    /** Robolectric never grants window focus; see PlayerErrorCardCopyTest. */
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

private class RecordingHandle : PlayerHandle {
    val seeks = mutableListOf<Long>()
    var toggles = 0
    override fun togglePlayPause() { toggles++ }
    override fun seekBy(deltaMillis: Long) { seeks += deltaMillis }
    override fun prepare() = Unit
}

private class FakeRepo(
    private val vod: Map<String, VodStream> = emptyMap(),
    private val episode: PlayerEpisode? = null,
) : PlayerRepository {
    override suspend fun connection() = PlayerConnection("panel.example", 8080, false, "u", "p")
    override suspend fun liveStream(id: String): LiveStream? = null
    override suspend fun vodStream(id: String): VodStream? = vod[id]
    override suspend fun firstEpisode(seriesId: String): PlayerEpisode? = episode
    override suspend fun allowedOutputFormats(): List<String> = listOf("m3u8", "ts")
    override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? = null
    override suspend fun isFavorite(streamId: String, streamType: StreamType) = false
    override suspend fun toggleFavorite(streamId: String, streamType: StreamType) = Unit
}
