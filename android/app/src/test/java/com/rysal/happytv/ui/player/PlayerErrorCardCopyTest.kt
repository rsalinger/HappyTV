package com.rysal.happytv.ui.player

import android.app.Application
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
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
 * F-68 at the acceptance level: **what the user reads on the TV.**
 *
 * The card's whole job is the sentence. QA's repro pressed OK on a healthy 4K
 * channel and was told "This channel is offline. Try another." — a diagnosis
 * the app had no basis for, pointing at a fix that does not exist. The
 * ViewModel tests prove the state machine reaches the right *state*; this file
 * proves that state renders the right *words*, with the right buttons, and
 * that the D-pad lands somewhere the user can act from.
 *
 * Each test drives the real [PlayerScreen] over a real [PlayerViewModel] with
 * recording fakes (the pattern LiveTvChannelLongPressTest established), so
 * the copy under test is the copy that ships.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlayerErrorCardCopyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()

    /** 2024-04-19T16:00:00Z — the protocol example moment. */
    private val now = 1_713_542_400_000L

    private var backPressed = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backPressed = 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun aFourKChannelSaysTheFormatIsUnsupportedAndNotThatTheChannelIsOffline() {
        val vm = readyLiveViewModel()

        vm.onStreamError(decoderInitFailure(), ERROR_DECODER_INIT_FAILED)
        setScreen(vm)

        compose.onNodeWithText(PlayerViewModel.UNSUPPORTED_FORMAT_HEADING).assertExists()
        compose.onNodeWithText("This channel is offline. Try another.").assertDoesNotExist()
        compose.onNodeWithText("This stream is offline.").assertDoesNotExist()
    }

    @Test
    fun theCodecCardNamesTheFixBesideTheError() {
        // design spec: "Errors: one red line with an icon … with the fix beside it.
        // Errors never dismiss themselves." The fix line is the second half of
        // that rule — without it the card only relabels the problem.
        val vm = readyLiveViewModel()

        vm.onStreamError(decoderInitFailure(), ERROR_DECODER_INIT_FAILED)
        setScreen(vm)

        compose.onNodeWithText(PlayerViewModel.UNSUPPORTED_FORMAT_BODY).assertExists()
    }

    @Test
    fun theCodecCardOffersNoTryAgainBecauseRetryingCannotWork() {
        val vm = readyLiveViewModel()

        vm.onStreamError(decoderInitFailure(), ERROR_DECODER_INIT_FAILED)
        setScreen(vm)

        compose.onNodeWithText("Try again").assertDoesNotExist()
        // The one button that does something: back to the channel list.
        compose.onNodeWithText("Back to channels").assertExists()
    }

    @Test
    fun theCodecCardOpensWithFocusOnItsOnlyButton() {
        // design spec (Player · channel offline): "**Back to channels** (primary,
        // focused)". A D-pad user who lands on an error card with nothing focused
        // has no way forward except Back.
        val vm = readyLiveViewModel()

        vm.onStreamError(decoderInitFailure(), ERROR_DECODER_INIT_FAILED)
        setScreen(vm)
        enterFocusSystem()

        compose.waitUntil(10_000) {
            nodeExists(hasText("Back to channels") and isFocused())
        }
        compose.onNode(hasText("Back to channels") and isFocused()).assertExists()
    }

    @Test
    fun okOnTheCodecCardReturnsToTheChannelList() {
        val vm = readyLiveViewModel()

        vm.onStreamError(decoderInitFailure(), ERROR_DECODER_INIT_FAILED)
        setScreen(vm)

        compose.onNode(hasText("Back to channels")).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        ) { it?.invoke() }
        compose.waitForIdle()

        assertEquals("OK on the card's only button must leave the player", 1, backPressed)
    }

    @Test
    fun aGenuinelyOfflineChannelStillSaysOfflineAndKeepsTryAgain() {
        // The regression guard: F-68 must not swallow the real offline path.
        // Three IO errors are still three IO errors.
        val vm = readyLiveViewModel()

        repeat(3) { vm.onStreamError(null, ERROR_IO_BAD_HTTP_STATUS) }
        setScreen(vm)

        compose.onNodeWithText("This channel is offline. Try another.").assertExists()
        compose.onNodeWithText("Try again").assertExists()
        compose.onNodeWithText("Back to channels").assertExists()
    }

    @Test
    fun aMovieWithAnUnsupportedCodecDoesNotBorrowTheChannelWording() {
        val repo = F68FakePlayerRepository(
            vod = mapOf(
                "9" to VodStream(id = "9", categoryId = "1", name = "M", containerExtension = "mkv"),
            ),
        )
        val vm = PlayerViewModel(PlayerLaunch.movie("9"), repo, F68FakeHandle(), clock = { now })

        vm.onStreamError(decoderInitFailure(), ERROR_DECODER_INIT_FAILED)
        setScreen(vm)

        compose.onNodeWithText("This stream is offline.").assertDoesNotExist()
        compose.onNodeWithText(PlayerViewModel.UNSUPPORTED_FORMAT_HEADING).assertExists()
        // No channel list behind a movie — the card must not offer a door there.
        compose.onNodeWithText("Back to channels").assertDoesNotExist()
    }

    // -------------------------------------------- #21 refused-redirect card

    private var settingsOpened = 0

    private fun refusedMovieViewModel(): PlayerViewModel {
        val repo = F68FakePlayerRepository(
            vod = mapOf("9" to VodStream(id = "9", categoryId = "1", name = "M", containerExtension = "mkv")),
        )
        val vm = PlayerViewModel(PlayerLaunch.movie("9"), repo, F68FakeHandle(), clock = { now })
        vm.onStreamError(java.io.IOException("Unable to connect", InsecureRedirectException()), 2001)
        return vm
    }

    private fun setRefusedScreen(vm: PlayerViewModel) {
        settingsOpened = 0
        compose.setContent {
            HappyTvTheme {
                PlayerScreen(
                    viewModel = vm,
                    onBack = { backPressed++ },
                    onOpenSettings = { settingsOpened++ },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aRefusedRedirectSaysSoAndOffersOpenSettingsAndBackWithoutTryAgain() {
        setRefusedScreen(refusedMovieViewModel())

        compose.onNodeWithText(
            "This movie would send your sign-in details without encryption, so HappyTV stopped it.",
        ).assertExists()
        compose.onNodeWithText("Open settings").assertExists()
        compose.onNodeWithText("Back").assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("This stream is offline.").assertDoesNotExist()
        compose.onNodeWithText(PlayerViewModel.UNSUPPORTED_FORMAT_HEADING).assertDoesNotExist()
    }

    @Test
    fun theRefusedRedirectCardOpensWithFocusOnOpenSettings() {
        setRefusedScreen(refusedMovieViewModel())
        enterFocusSystem()

        compose.waitUntil(10_000) { nodeExists(hasText("Open settings") and isFocused()) }
        compose.onNode(hasText("Open settings") and isFocused()).assertExists()
    }

    @Test
    fun okOnOpenSettingsOpensSettingsAndOkOnBackLeaves() {
        setRefusedScreen(refusedMovieViewModel())

        compose.onNode(hasText("Open settings")).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        ) { it?.invoke() }
        compose.waitForIdle()
        assertEquals(1, settingsOpened)
        assertEquals(0, backPressed)

        compose.onNode(hasText("Back") and androidx.compose.ui.test.hasClickAction()).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        ) { it?.invoke() }
        compose.waitForIdle()
        assertEquals(1, backPressed)
    }

    @Test
    fun aRefusedChannelSaysChannel() {
        val vm = readyLiveViewModel()
        vm.onStreamError(java.io.IOException("Unable to connect", InsecureRedirectException()), 2001)
        setRefusedScreen(vm)

        compose.onNodeWithText(
            "This channel would send your sign-in details without encryption, so HappyTV stopped it.",
        ).assertExists()
    }

    // ------------------------------------------------------------------ setup

    private fun setScreen(vm: PlayerViewModel) {
        compose.setContent {
            HappyTvTheme {
                PlayerScreen(
                    viewModel = vm,
                    onBack = { backPressed++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun nodeExists(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean = try {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    } catch (_: Throwable) {
        false
    }

    /**
     * Boots the focus system the way the device does.
     *
     * Robolectric never grants the compose view window focus, so
     * `AndroidComposeView.onFocusChanged` → `takeFocus` never runs and every
     * `FocusRequester.requestFocus()` is a silent no-op — including the card's
     * "open focused on Back to channels". `LiveTvBackHandlerTest` documents
     * the same trap for Live TV; without this the assertion would be testing
     * Robolectric, not the app.
     */
    private fun enterFocusSystem() {
        compose.activityRule.scenario.onActivity { activity ->
            val composeView = findComposeView(activity.window.decorView)
                ?: error("AndroidComposeView not found in the activity hierarchy")
            composeView.requestFocus()
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        }
        compose.waitForIdle()
    }

    /** Depth-first search for the `AndroidComposeView` under the window. */
    private fun findComposeView(root: android.view.View?): android.view.View? {
        if (root == null) return null
        if (root is ViewRootForTest) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            findComposeView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** A live channel matching QA's repro row: num 2, 4K, tv_archive 0. */
    private fun readyLiveViewModel(): PlayerViewModel {
        val repo = F68FakePlayerRepository(
            live = mapOf(
                "375494" to LiveStream(
                    id = "375494",
                    categoryId = "1",
                    num = 2,
                    name = "Sports Main Event UHD",
                ),
            ),
        )
        return PlayerViewModel(
            PlayerLaunch.live("375494"),
            repo,
            F68FakeHandle(),
            clock = { now },
        )
    }

    /** The 3840×2160 HEVC decoder failure from QA's F-68 repro. */
    private fun decoderInitFailure(): Throwable =
        MediaCodecRenderer.DecoderInitializationException(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(3840)
                .setHeight(2160)
                .build(),
            IllegalStateException("Decoder init failed: c2.amlogic.hevc.decoder"),
            /* secureDecoderRequired = */ false,
            /* errorCode = */ ERROR_DECODER_INIT_FAILED,
        )
}

/** F-68's error codes, spelled as QA filed them in logcat. */
private const val ERROR_DECODER_INIT_FAILED = 4001
private const val ERROR_IO_BAD_HTTP_STATUS = 2004

/**
 * A minimal [PlayerRepository] for the two rows these tests need. The player's
 * own fakes live privately inside PlayerViewModelTest, and this file's subject
 * is the rendered copy, not the repository seam.
 */
private class F68FakePlayerRepository(
    private val live: Map<String, LiveStream> = emptyMap(),
    private val vod: Map<String, VodStream> = emptyMap(),
) : PlayerRepository {
    override suspend fun connection(): PlayerConnection =
        PlayerConnection("panel.example", 8080, false, "u", "p")

    override suspend fun liveStream(id: String): LiveStream? = live[id]

    override suspend fun vodStream(id: String): VodStream? = vod[id]

    override suspend fun firstEpisode(seriesId: String): PlayerEpisode? = null

    override suspend fun allowedOutputFormats(): List<String> = listOf("m3u8", "ts")

    override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? = null

    override suspend fun isFavorite(streamId: String, streamType: StreamType): Boolean = false

    override suspend fun toggleFavorite(streamId: String, streamType: StreamType) = Unit
}

private class F68FakeHandle : PlayerHandle {
    override fun togglePlayPause() = Unit

    override fun seekBy(deltaMillis: Long) = Unit

    override fun prepare() = Unit
}