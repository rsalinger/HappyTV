package com.rysal.happytv.ui.player

import android.app.Application
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.getOrNull
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #131: the live control row through the whole screen. D-pad Left/Right walk Pause, Start over,
 * Program info, (tracks), Favorite; Start over replays the program and hands focus to Play/Pause;
 * greyed-out buttons are focus stops that do nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlayerLiveControlsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val now = 1_713_542_400_000L
    private val min = 60_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun focusedDescription(): String? =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes().firstNotNullOfOrNull { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull()
        }

    private fun assertFocused(description: String) {
        assertEquals(description, focusedDescription())
    }

    @Test
    fun `controls open on pause and right walks start over, program info, favorite`() {
        val vm = liveVm(archive = true, guide = true)
        setScreen(vm)
        enterFocusSystem()

        assertFocused("Pause")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused("Start over")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused("Program info")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused("Favorite")
    }

    @Test
    fun `start over replays the program and focus ends on play pause, not go live`() {
        val vm = liveVm(archive = true, guide = true)
        setScreen(vm)
        enterFocusSystem()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused("Start over")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val content = (vm.state.value as PlayerUiState.Ready).content
        assertEquals(PlaybackMode.Replay, content.mode)
        compose.waitUntil(5_000) { focusedDescription() == "Pause" }
        // Go live is one press right of Play/Pause, never the landing spot.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused("Go live")
        assertEquals(true, content.url.contains("/timeshift/"))
    }

    @Test
    fun `a channel without catch-up keeps start over as a greyed-out focus stop`() {
        val vm = liveVm(archive = false, guide = true)
        setScreen(vm)
        enterFocusSystem()
        val before = (vm.state.value as PlayerUiState.Ready).content.url

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused("Start over, not available")
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(before, (vm.state.value as PlayerUiState.Ready).content.url)
        assertFocused("Start over, not available")
        compose.onNode(hasText("No replay on this channel")).assertExists()
    }

    @Test
    fun `without guide data program info is a greyed-out focus stop that says so`() {
        val vm = liveVm(archive = true, guide = false)
        setScreen(vm)
        enterFocusSystem()

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused("Start over, not available")
        compose.onNode(hasText("No guide info for this show")).assertExists()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused("No guide info")
        compose.onNode(hasText("No guide info")).assertExists()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(false, (vm.state.value as PlayerUiState.Ready).content.showProgramInfo)
    }

    @Test
    fun `program info opens a card with the description and what is next`() {
        val vm = liveVm(archive = true, guide = true)
        setScreen(vm)
        enterFocusSystem()
        // enterFocusSystem's Down already toggled the card on; make sure it is on exactly once.
        if (!(vm.state.value as PlayerUiState.Ready).content.showProgramInfo) vm.toggleProgramInfo()
        compose.waitForIdle()

        compose.onNode(hasText("A long story about the program.")).assertExists()
        compose.onNode(hasText("Market wrap", substring = true)).assertExists()
    }

    // ------------------------------------------------------------------ setup

    private fun liveVm(archive: Boolean, guide: Boolean): PlayerViewModel {
        val repo = LiveRepo(archive, if (guide) {
            PlayerNowNext(
                PlayerProgram("Headline report", now - 12 * min, now + 48 * min, "A long story about the program."),
                PlayerProgram("Market wrap", now + 48 * min, now + 78 * min),
            )
        } else {
            null
        })
        return PlayerViewModel(PlayerLaunch.live("7"), repo, RecHandle(), clock = { now }).also {
            it.onBuffering(false)
            it.onIsPlayingChanged(true)
        }
    }

    private fun press(code: Int) {
        compose.activityRule.scenario.onActivity { a ->
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private fun setScreen(vm: PlayerViewModel) {
        compose.setContent { HappyTvTheme { PlayerScreen(viewModel = vm, onBack = {}) } }
        compose.waitForIdle()
    }

    /** Robolectric never grants window focus; see PlayerErrorCardCopyTest. */
    private fun enterFocusSystem() {
        compose.activityRule.scenario.onActivity { activity ->
            val composeView = findComposeView(activity.window.decorView) ?: error("no compose view")
            composeView.requestFocus()
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { focusedDescription() == "Pause" }
    }

    private fun findComposeView(root: android.view.View?): android.view.View? {
        if (root == null) return null
        if (root is ViewRootForTest) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) findComposeView(root.getChildAt(i))?.let { return it }
        return null
    }
}

private class RecHandle : PlayerHandle {
    override fun togglePlayPause() = Unit
    override fun seekBy(deltaMillis: Long) = Unit
    override fun prepare() = Unit
}

private class LiveRepo(private val archive: Boolean, private val nowNext: PlayerNowNext?) : PlayerRepository {
    override suspend fun connection() = PlayerConnection("panel.example", 8080, false, "u", "p")
    override suspend fun liveStream(id: String): LiveStream =
        LiveStream(id = id, categoryId = "1", num = 101, name = "Northbay News", tvArchive = archive, epgChannelId = "ch7")
    override suspend fun vodStream(id: String): VodStream? = null
    override suspend fun firstEpisode(seriesId: String): PlayerEpisode? = null
    override suspend fun allowedOutputFormats(): List<String> = listOf("m3u8", "ts")
    override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? = nowNext
    override suspend fun isFavorite(streamId: String, streamType: StreamType) = false
    override suspend fun toggleFavorite(streamId: String, streamType: StreamType) = Unit
}
