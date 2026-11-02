package com.rysal.happytv.ui.player

import android.app.Application
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #131: the D-pad path through the control row. Left/Right walk [playerControlOrder]; the greyed-out
 * Start over and Program info stay focus stops (so the reason under them is readable) but OK does
 * nothing on them; enabled ones act.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlayerControlRowFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L
    private val min = 60_000L

    private fun content(
        mode: PlaybackMode = PlaybackMode.Live,
        archive: Boolean = true,
        guide: Boolean = true,
    ) = PlayerContent(
        url = "u",
        mode = mode,
        title = "Headline report",
        topLine = "101 · Northbay News",
        channelName = "Northbay News",
        monogram = "NN",
        monogramPalette = null,
        nowNext = if (guide) {
            PlayerNowNext(PlayerProgram("Headline report", now - 12 * min, now + 48 * min, "Details."), null)
        } else {
            null
        },
        tvArchive = archive,
        isPlaying = true,
        progress = PlayerProgress(null, null, 0f, 0f, null, ScrubberDot.PulsingRed),
        badge = null,
    )

    private val visited = mutableListOf<PlayerControl>()
    private val clicks = mutableListOf<PlayerControl>()

    private fun show(content: PlayerContent) {
        visited.clear()
        clicks.clear()
        val requesters = PlayerControl.values().associateWith { FocusRequester() }
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    var focused by remember { mutableStateOf<PlayerControl?>(null) }
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(50) // as PlayerScreen does: let the row attach and collect first
                        requesters.getValue(PlayerControl.PlayPause).requestFocus()
                    }
                    PlayerControlRow(
                        content = content,
                        controlsVisible = true,
                        focused = focused,
                        requesters = requesters,
                        onFocus = { focused = it; visited += it },
                        onPlayPause = { clicks += PlayerControl.PlayPause },
                        onGoLive = { clicks += PlayerControl.GoLive },
                        onStartOver = { clicks += PlayerControl.StartOver },
                        onProgramInfo = { clicks += PlayerControl.ProgramInfo },
                        onTracks = { clicks += PlayerControl.Tracks },
                        onFavorite = { clicks += PlayerControl.Favorite },
                    )
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `right walks live controls in order`() {
        show(content())
        repeat(3) { press(Key.DirectionRight) }
        assertEquals(
            listOf(PlayerControl.PlayPause, PlayerControl.StartOver, PlayerControl.ProgramInfo, PlayerControl.Favorite),
            visited.distinct(),
        )
        press(Key.DirectionLeft)
        assertEquals(PlayerControl.ProgramInfo, visited.last())
    }

    @Test
    fun `greyed-out start over and program info are focus stops but do nothing`() {
        show(content(archive = false, guide = false))
        press(Key.DirectionRight)
        assertEquals(PlayerControl.StartOver, visited.last())
        compose.onNode(isFocused() and hasContentDescription("Start over, not available")).assertExists()
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        assertEquals(PlayerControl.ProgramInfo, visited.last())
        press(Key.DirectionCenter)
        assertTrue("greyed-out buttons ignore OK: $clicks", clicks.isEmpty())
    }

    @Test
    fun `an enabled start over acts on OK`() {
        show(content())
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf(PlayerControl.StartOver), clicks)
    }

    @Test
    fun `an enabled program info acts on OK`() {
        show(content())
        repeat(2) { press(Key.DirectionRight) }
        press(Key.DirectionCenter)
        assertEquals(listOf(PlayerControl.ProgramInfo), clicks)
    }

    @Test
    fun `replay shows go live in the start over slot`() {
        show(content(mode = PlaybackMode.Replay))
        press(Key.DirectionRight)
        assertEquals(PlayerControl.GoLive, visited.last())
        press(Key.DirectionCenter)
        assertEquals(listOf(PlayerControl.GoLive), clicks)
    }
}
