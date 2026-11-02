package com.rysal.happytv.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.rysal.happytv.ui.player.BackKeyGate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.LiveTvView
import com.rysal.happytv.ui.focus.awaitFocus
import com.rysal.happytv.ui.focus.tryRequest
import com.rysal.happytv.ui.player.HostMode
import com.rysal.happytv.ui.player.PlaybackHost
import com.rysal.happytv.ui.player.PlaybackMode
import com.rysal.happytv.ui.player.PlayerLaunch
import com.rysal.happytv.ui.player.PlayerPolicy
import com.rysal.happytv.ui.player.PlayerScreen
import com.rysal.happytv.ui.player.PlayerSurface
import com.rysal.happytv.ui.player.PlayerUiState
import com.rysal.happytv.ui.player.PlayerViewModel
import com.rysal.happytv.ui.player.ChannelOkAction

/**
 * The Live TV window: the combined screen and the one player surface (#16,
 * design note on #11, option a).
 *
 * Draw order is the F-48 rule: the combined screen first, then the single
 * [PlayerSurface] (never conditionally composed, never re-parented), then the
 * player chrome. Full and Preview are only the surface's bounds: the whole
 * artboard, or the 448 x 252 slot at (1376, 142).
 *
 * Focus: while [HostMode.Full] the combined screen stays composed (re-composing
 * a 20k-channel list on Back would eat the Back budget) but takes no D-pad
 * focus. Going back, the mode flips first and the screen then asks for the
 * playing channel's row ([LivePlayback.requestRestore]) once the focus gate is
 * open.
 */
@Composable
internal fun LiveTvPlaybackLayer(
    host: PlaybackHost,
    playerViewModel: PlayerViewModel,
    startView: LiveTvView?,
    onExit: () -> Unit,
    onRefreshLive: () -> Unit,
    registerLongBackStop: ((() -> Unit)?) -> Unit = {},
    registerHoldReturn: ((() -> Unit)?) -> Unit = {},
) {
    val d = browseDesign()
    val mode by host.modeFlow.collectAsStateWithLifecycle()
    val playingId by host.playingStreamIdFlow.collectAsStateWithLifecycle()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val buffering = (playerState as? PlayerUiState.Ready)?.content?.isBuffering == true

    val playingIdState = rememberUpdatedState(playingId)
    val modeState = rememberUpdatedState(mode)
    val livePlayback = remember(host) {
        lateinit var self: LivePlayback
        self = LivePlayback(
            playingStreamIdProvider = { playingIdState.value },
            onPlayChannel = { streamId ->
                when (PlayerPolicy.channelOk(host.mode, host.playingStreamId, streamId)) {
                    ChannelOkAction.EnterFull -> host.enterFull()
                    ChannelOkAction.PlayFull -> {
                        self.jumpToPlayingOnBack = false
                        host.play(PlayerLaunch.live(streamId = streamId))
                    }
                }
            },
            locate = { streamId ->
                // Room suspend queries run on its own executor, never the main thread.
                val dao = Graph.database.liveStreamDao()
                dao.getById(streamId)?.let { ChannelLocation(it.categoryId, dao.rowsBefore(it.categoryId, it.num, it.name)) }
            },
            fullActiveProvider = { modeState.value == HostMode.Full },
        )
        self
    }

    // Back in Full, routed by mode first (#16 QA): whatever has focus, or nothing, the
    // key never reaches the combined screen's rail handling.
    val routeBack: (longPress: Boolean) -> Unit = { longPress ->
        val id = host.playingStreamId
        val playbackMode = (playerViewModel.state.value as? PlayerUiState.Ready)?.content?.mode
            ?: PlaybackMode.Live
        host.onPlayerBack(playbackMode, longPress)
        livePlayback.requestRestore(id)
    }
    BackHandler(enabled = mode == HostMode.Full) { routeBack(false) }
    // #138: a 2 s hold on Back in the preview returns to the playing channel, full screen,
    // wherever the D-pad is. Backing out of it then jumps the list to that channel.
    val currentReturn by rememberUpdatedState {
        if (host.playingStreamId != null) {
            livePlayback.jumpToPlayingOnBack = true
            host.enterFull()
        }
    }
    DisposableEffect(Unit) {
        registerHoldReturn { currentReturn() }
        onDispose { registerHoldReturn(null) }
    }
    // The activity's long-press fallback stops through the same path (focus restore included).
    val currentRoute by rememberUpdatedState(routeBack)
    DisposableEffect(Unit) {
        registerLongBackStop { currentRoute(true) }
        onDispose { registerLongBackStop(null) }
    }

    Box(modifier = Modifier.fillMaxSize().fullScreenBackKeys(active = mode == HostMode.Full, onBack = routeBack)) {
        // 1. The combined screen. Composed in every mode; focusable except in Full.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusProperties { canFocus = mode != HostMode.Full },
        ) {
            BrowseNavHost(
                startRoute = BrowseStartRoute.liveTv(),
                onExit = onExit,
                onRefreshLive = onRefreshLive,
                liveTvStartView = startView,
                livePlayback = livePlayback,
            )
        }

        // 2. The one surface: hidden while Idle, the preview slot, or the whole screen.
        PlayerSurface(
            player = host.exoPlayer,
            dimmed = buffering,
            visible = mode != HostMode.Idle,
            overlayHidden = mode == HostMode.Preview && livePlayback.overlayOpen,
            modifier = if (mode == HostMode.Full) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .offsetPx(PREVIEW_SLOT_X_PX, PREVIEW_SLOT_Y_PX)
                    .size(width = d.px(PREVIEW_SLOT_WIDTH_PX), height = d.px(PREVIEW_SLOT_HEIGHT_PX))
            },
        )

        // 3. Player chrome, full screen only. Nothing opaque over the picture (F-48):
        //    the screen itself draws a Ground backing only while there is no video.
        if (mode == HostMode.Full) {
            FullScreenPlayer(
                host = host,
                playerViewModel = playerViewModel,
                onBackPressed = routeBack,
                onStopFromCard = {
                    val id = host.playingStreamId
                    host.stopPlayback()
                    livePlayback.requestRestore(id)
                },
            )
        }
    }
}

@Composable
private fun FullScreenPlayer(
    host: PlaybackHost,
    playerViewModel: PlayerViewModel,
    onBackPressed: (longPress: Boolean) -> Unit,
    onStopFromCard: () -> Unit,
) {
    val canvasFocus = remember { FocusRequester() }
    var playerHasFocus by remember { mutableStateOf(false) }
    // Entering Full, and whenever the focused control goes (the controls fade), the
    // canvas target takes focus, so key events stay inside the player. Time-bounded
    // and attach-based (#58 helpers), not a frame count.
    LaunchedEffect(playerHasFocus) {
        if (playerHasFocus) return@LaunchedEffect
        awaitFocus(landed = { playerHasFocus }, request = { canvasFocus.tryRequest() })
    }
    Box(modifier = Modifier.fillMaxSize().onFocusChanged { playerHasFocus = it.hasFocus }) {
        PlayerScreen(
            viewModel = playerViewModel,
            onBack = { onBackPressed(false) },
            onLongBack = { onBackPressed(true) },
            onBackToChannels = onStopFromCard,
            canvasFocus = canvasFocus,
        )
    }
}

private const val PREVIEW_SLOT_X_PX = 1376
private const val PREVIEW_SLOT_Y_PX = 142
private const val PREVIEW_SLOT_WIDTH_PX = 448
private const val PREVIEW_SLOT_HEIGHT_PX = 252

/**
 * Back in full screen is the host's, whatever has focus or whether anything does
 * (#16 QA). Sits on the outermost box and uses the bubbling phase: when the player
 * has focus, its own handler (controls close first) has already consumed the key and
 * this never sees it; when focus is in the combined screen or nowhere, this takes
 * the key before the system's Back reaches the rail. A short press acts on key-up, a
 * hold fires once ([BackKeyGate]).
 */
internal fun Modifier.fullScreenBackKeys(active: Boolean, onBack: (longPress: Boolean) -> Unit): Modifier =
    composed {
        val currentOnBack by rememberUpdatedState(onBack)
        val gate = remember {
            BackKeyGate(onShortPress = { currentOnBack(false) }, onLongPress = { currentOnBack(true) })
        }
        onKeyEvent { event ->
            if (!active || event.key != Key.Back) {
                false
            } else {
                when (event.type) {
                    KeyEventType.KeyDown -> gate.onDown(
                        repeatCount = event.nativeKeyEvent.repeatCount,
                        longPressFlag = event.nativeKeyEvent.isLongPress,
                    )
                    KeyEventType.KeyUp -> gate.onUp()
                }
                true
            }
        }
    }
