package com.rysal.happytv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
// Kept although PlayerScreen.kt no longer calls focusable() itself: t_7663d865
// (fix/player-first-key-overlay) adds `.focusRequester(canvasFocus).focusable()`
// at PlayerScreen ~211 — if this branch lands second after that one, deleting
// the import (as 9e0357a did) compiles to an unresolved-reference. Merging
// identical unchanged lines is always clean, so not touching it here defuses
// the hazard in EITHER merge order.
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.ui.focus.htvFocusTarget
import com.rysal.happytv.ui.focus.tryRequest
import com.rysal.happytv.ui.theme.HappyTvDarkColors
import com.rysal.happytv.ui.theme.HappyTvDesign
import com.rysal.happytv.ui.theme.LocalHappyTvDesign
import com.rysal.happytv.ui.theme.LocalHappyTvTypography
import kotlinx.coroutines.delay

/**
 * The player screen — design spec §"Screens: player" in full: video underneath
 * (the activity owns the [androidx.media3.exoplayer.ExoPlayer]), this
 * composable draws the chrome over it on the 1920 × 1080 artboard.
 *
 * D-pad rules (the sticky note on the canvas):
 *  - Any button shows the controls; they fade after 5 s without input.
 *  - OK: pause or play. Up/Down: program info, without pausing.
 *  - Left/Right: move between controls while shown; skip 10 s on movies
 *    while hidden.
 *  - Back: closes the controls, then leaves; a movie with more than 5 min
 *    left asks first. Home (system) stops playback.
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    /**
     * Live TV host (#16): when set, Back is sequenced on the key itself - a
     * short press acts on key-up through [onBack], a hold calls this once and
     * always stops playback. Null keeps the key-down behaviour of the movie
     * player.
     */
    onLongBack: (() -> Unit)? = null,
    /** The error cards' "Back to channels"; defaults to [onBack]. */
    onBackToChannels: () -> Unit = onBack,
    /**
     * Live TV host (#16): a focus target that sits under the chrome so key events
     * always have a home inside this screen (the combined screen beneath takes
     * no focus while the player is full screen). Null in the movie player.
     */
    canvasFocus: FocusRequester? = null,
    /** #21: the refused-redirect card's "Open settings"; null opens Settings at the Player section. */
    onOpenSettings: (() -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val openSettings: () -> Unit = onOpenSettings ?: {
        context.startActivity(
            android.content.Intent(context, com.rysal.happytv.ui.settings.SettingsActivity::class.java)
                .putExtra(
                    com.rysal.happytv.ui.settings.EXTRA_SETTINGS_SECTION,
                    com.rysal.happytv.ui.settings.SECTION_PLAYER,
                ),
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val endOfPlayback by viewModel.endOfPlayback.collectAsStateWithLifecycle()
    // #84: the end-of-episode card owns the screen while it shows (not on ReturnToDetails, which leaves).
    val endCardOpen = endOfPlayback is EndOfPlayback.Countdown || endOfPlayback is EndOfPlayback.Stopped
    val nextEpisodeFocus = remember { FocusRequester() }
    val endSecondaryFocus = remember { FocusRequester() }

    var controlsVisible by remember { mutableStateOf(true) }
    var focusedControl by remember { mutableStateOf<PlayerControl?>(null) }
    // #169: true while the root anchor holds focus, i.e. no control, scrubber or panel does.
    var anchorFocused by remember { mutableStateOf(false) }
    var lastInputAtMillis by remember { mutableLongStateOf(0L) }
    var tracksPanelOpen by remember { mutableStateOf(false) }
    var leaveConfirmOpen by remember { mutableStateOf(false) }
    // A focus request queued from an event handler; the effect below lands it
    // once the target control has composed and attached its requester.
    var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(pendingFocus) {
        val focuser = pendingFocus ?: return@LaunchedEffect
        delay(50L)
        // The target composes a frame or more after the state flip. On the Chromecast a single
        // request after 50 ms ran before the control row had attached, failed silently and left
        // nothing focused (#131: the D-pad was dead until the controls hid), so retry once per
        // frame until the requester attaches, bounded by time.
        val deadline = System.nanoTime() + PENDING_FOCUS_TIMEOUT_NANOS
        while (!focuser.tryRequest() && System.nanoTime() < deadline) withFrameNanos { }
        pendingFocus = null
    }

    // 15 s clock tick (top-right clock ages by the minute, instant swaps).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            now = System.currentTimeMillis()
        }
    }

    val ready = state as? PlayerUiState.Ready
    val offline = state as? PlayerUiState.Offline
    val unsupportedFormat = state as? PlayerUiState.UnsupportedFormat
    // F-68: the codec-capability card carries the same top block the offline
    // card draws, so both error states must count as "content" here — the
    // leave-dialog, progress and D-pad branches all read this one value.
    val insecureRedirect = state as? PlayerUiState.InsecureRedirect
    val content = ready?.content ?: offline?.content ?: unsupportedFormat?.content ?: insecureRedirect?.content

    val playPauseFocus = remember { FocusRequester() }
    val scrubberFocus = remember { FocusRequester() }
    // #102: movies and episodes seek from the scrubber.
    val seekMode = content?.mode == PlaybackMode.Movie
    var scrubberFocused by remember { mutableStateOf(false) }
    var lastButton by remember { mutableStateOf<PlayerControl?>(null) }
    val seekSession = remember { SeekSession() }
    var seekTargetMillis by remember { mutableStateOf<Long?>(null) }
    var seekTick by remember { mutableIntStateOf(0) }
    // A seek was applied and the player is rebuffering: keep the controls up and
    // the scrubber focused through it (QA on #103).
    var seekBuffering by remember { mutableStateOf(false) }
    var refocusScrubber by remember { mutableStateOf(false) }
    val anchorFocus = canvasFocus ?: remember { FocusRequester() }
    val goLiveFocus = remember { FocusRequester() }
    // Start over reloads the stream: buffering hides the controls and the focused control
    // leaves composition. When that settles, focus goes to Play/Pause, never Go live (#138:
    // focus must not invite an accidental exit from the replay).
    var focusAfterStartOver by remember { mutableStateOf(false) }
    LaunchedEffect(focusAfterStartOver) {
        if (!focusAfterStartOver) return@LaunchedEffect
        delay(START_OVER_FOCUS_WINDOW_MILLIS)
        focusAfterStartOver = false
    }
    val startOverFocus = remember { FocusRequester() }
    val infoFocus = remember { FocusRequester() }
    val tracksFocus = remember { FocusRequester() }
    val favoriteFocus = remember { FocusRequester() }
    val backToChannelsFocus = remember { FocusRequester() }
    val openSettingsFocus = remember { FocusRequester() }
    val tryAgainFocus = remember { FocusRequester() }
    val keepWatchingFocus = remember { FocusRequester() }
    val stopWatchingFocus = remember { FocusRequester() }
    val requesters = remember {
        mapOf(
            PlayerControl.PlayPause to playPauseFocus,
            PlayerControl.GoLive to goLiveFocus,
            PlayerControl.StartOver to startOverFocus,
            PlayerControl.ProgramInfo to infoFocus,
            PlayerControl.Tracks to tracksFocus,
            PlayerControl.Favorite to favoriteFocus,
        )
    }

    // Read through the view model, not the `content` captured by this composition:
    // the Back path runs from the remembered gate and the BackHandler, and a closure
    // from an older composition once saw no content, so hidden Back skipped the
    // leave dialog and left the movie (#102 QA r3).
    fun liveContent(): PlayerContent? = when (val st = viewModel.state.value) {
        is PlayerUiState.Ready -> st.content
        is PlayerUiState.Offline -> st.content
        is PlayerUiState.UnsupportedFormat -> st.content
        is PlayerUiState.InsecureRedirect -> st.content
        else -> null
    }

    // Revealing the controls focuses Play/Pause in every mode (#138); Go live is one press right.
    fun defaultControl(): PlayerControl = defaultControlFor(liveContent()?.mode)

    fun showControls(requestFocus: Boolean = true) {
        controlsVisible = true
        lastInputAtMillis = System.currentTimeMillis()
        if (requestFocus) {
            focusedControl = defaultControl()
            // The requester attaches on the control row, which composes a
            // frame after this state flip — [pendingFocus] retries for us.
            pendingFocus = requesters[defaultControl()]
        }
    }

    fun commitSeek() {
        val commit = seekSession.commit(System.currentTimeMillis()) ?: return
        viewModel.seekToPosition(commit.targetMillis)
        seekBuffering = true
        refocusScrubber = scrubberFocused
        HtvPerf.log(HtvPerf.PLAYER_SEEK, commit.burstMillis, "presses=${commit.presses}")
    }

    fun seekPress(direction: Int, repeatCount: Int) {
        val progress = content?.progress ?: return
        seekTargetMillis = seekSession.press(
            direction = direction,
            repeatCount = repeatCount,
            nowMillis = System.currentTimeMillis(),
            currentPositionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
        )
        seekTick++
    }

    // A burst of presses is one seek: apply after 400 ms with no press (a held
    // key also applies on key-up), then keep the target drawn until the player's
    // own position catches up.
    LaunchedEffect(seekTick) {
        if (seekTick == 0) return@LaunchedEffect
        // A held key waits for its key-up (a lost one is caught by the safety delay).
        delay(if (seekSession.held) SeekSession.HELD_SAFETY_MILLIS else SeekSession.IDLE_COMMIT_MILLIS)
        commitSeek()
        delay(SeekSession.SETTLE_MILLIS)
        seekTargetMillis = null
    }

    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) scrubberFocused = false
    }

    // Controls fade after 5 s without input (never while the panel is open
    // or the stream is buffering — those states hide the controls anyway).
    LaunchedEffect(controlsVisible, lastInputAtMillis, tracksPanelOpen, content?.isBuffering) {
        if (controlsVisible && !tracksPanelOpen && content?.isBuffering != true) {
            delay(5_000L)
            controlsVisible = false
            focusedControl = null
        }
    }

    // Buffering hides the controls but keeps the top block (other states) —
    // except right after a scrubber seek, where they stay so the next press
    // lands on the scrubber; the hide timer restarts when buffering ends.
    LaunchedEffect(content?.isBuffering) {
        if (content?.isBuffering == true) {
            if (!seekBuffering) controlsVisible = false
        } else {
            if (focusAfterStartOver && content?.mode == PlaybackMode.Replay) {
                focusAfterStartOver = false
                showControls()
            }
            if (seekBuffering) {
                seekBuffering = false
                if (controlsVisible) {
                    lastInputAtMillis = System.currentTimeMillis()
                    if (refocusScrubber) pendingFocus = scrubberFocus
                }
            }
        }
    }

    // The root must hold focus whenever nothing else on screen does, or every
    // D-pad key is dead (the focused control left composition with the hidden
    // controls, a dialog or card closed, or buffering ended).
    val anchorNeeded = needsRootFocus(
        ready = ready != null,
        controlsVisible = controlsVisible,
        tracksPanelOpen = tracksPanelOpen,
        leaveConfirmOpen = leaveConfirmOpen,
        endCardOpen = endCardOpen,
    )
    LaunchedEffect(anchorNeeded, pendingFocus == null) {
        if (anchorNeeded && pendingFocus == null) {
            delay(50L)
            runCatching { anchorFocus.requestFocus() }
        }
    }

    // Entering Ready the first time: controls up with the mode's default focus.
    LaunchedEffect(ready?.content?.url) {
        if (ready != null) showControls()
    }

    // Both error cards open focused on their primary button. design spec (Player
    // · channel offline) specifies "**Back to channels** (primary, focused)",
    // and the card cannot change its own focus — the requester lands a frame
    // later, which is what [pendingFocus]'s delay is for. F-68's codec card
    // inherits this by sharing the same card, so a 4K channel lands the user
    // on "Back to channels" rather than nowhere.
    LaunchedEffect(offline, unsupportedFormat, insecureRedirect) {
        if (offline == null && unsupportedFormat == null && insecureRedirect == null) return@LaunchedEffect
        controlsVisible = false
        focusedControl = null
        // #21: the refused-redirect card leads with Open settings.
        pendingFocus = if (insecureRedirect != null) openSettingsFocus else backToChannelsFocus
    }

    LaunchedEffect(endCardOpen) {
        if (endCardOpen) {
            controlsVisible = false
            focusedControl = null
            tracksPanelOpen = false
            leaveConfirmOpen = false
        }
    }

    fun handleBack() {
        when {
            // #84: Back cancels the countdown (stays on the ended frame); a second Back leaves.
            endOfPlayback is EndOfPlayback.Countdown -> viewModel.cancelCountdown()
            endOfPlayback is EndOfPlayback.Stopped -> onBack()
            tracksPanelOpen -> {
                tracksPanelOpen = false
                showControls()
                focusedControl = PlayerControl.Tracks
                pendingFocus = tracksFocus
            }
            leaveConfirmOpen -> {
                // Back on the dialog = Keep watching (#66): playback never
                // paused, so only the controls need to come back.
                leaveConfirmOpen = false
                showControls()
            }
            controlsVisible -> {
                controlsVisible = false
                focusedControl = null
            }
            liveContent()?.mode == PlaybackMode.Movie &&
                liveContent()!!.progress.remainingMillis() > LEAVE_CONFIRM_THRESHOLD_MILLIS -> {
                leaveConfirmOpen = true
            }
            else -> onBack()
        }
    }

    BackHandler { handleBack() }

    // #16: short Back on key-up, a hold stops (Live TV host only).
    val currentHandleBack by rememberUpdatedState(::handleBack)
    val currentLongBack by rememberUpdatedState(onLongBack)
    val backGate = remember {
        BackKeyGate(
            onShortPress = { currentHandleBack() },
            onLongPress = { currentLongBack?.invoke() },
        )
    }

    // #136 QA: an OK whose KeyDown moved focus (revealing the controls) must have its
    // KeyUp swallowed too, or foundation clicks the button that just took focus.
    val okSequence = remember { OkSequenceGate() }

    PlayerCanvas(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // Neither error card owns a D-pad: their buttons handle their
                // own focus, and Back is the activity's BackHandler.
                val ownsKeys = content != null && offline == null && unsupportedFormat == null &&
                    insecureRedirect == null
                if (onLongBack != null && event.key == Key.Back && ownsKeys) {
                    when (event.type) {
                        KeyEventType.KeyDown -> backGate.onDown(
                            repeatCount = event.nativeKeyEvent.repeatCount,
                            longPressFlag = event.nativeKeyEvent.isLongPress,
                        )
                        KeyEventType.KeyUp -> backGate.onUp()
                    }
                    return@onPreviewKeyEvent true
                }
                if (ownsKeys && okSequence.consumes(event.isOkKey(), event.type == KeyEventType.KeyDown, event.nativeKeyEvent.repeatCount)) {
                    return@onPreviewKeyEvent true
                }
                // Space and media keys are handled on KeyDown; their KeyUp must not click either.
                if (ownsKeys && event.type == KeyEventType.KeyUp && event.key.mediaKeyAction() != null &&
                    mediaKeysAct(tracksPanelOpen) && !leaveConfirmOpen && !endCardOpen
                ) {
                    return@onPreviewKeyEvent true
                }
                // #66: a modal over the player owns every key. This handler sits
                // ABOVE the dialog in the tree and previews first, so without
                // this line RIGHT/LEFT/OK reached the seek and play/pause
                // branches below. Back (above, and in `when` below) still goes
                // to handleBack = Keep watching; the dialog's own key handler
                // routes the rest.
                if ((leaveConfirmOpen || endCardOpen) && ownsKeys && event.key != Key.Back) {
                    return@onPreviewKeyEvent false
                }
                // #102: releasing a held Left/Right applies the seek at once.
                if (event.type == KeyEventType.KeyUp && seekSession.held &&
                    (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
                ) {
                    commitSeek()
                    return@onPreviewKeyEvent false
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (!ownsKeys) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Back -> {
                        handleBack()
                        true
                    }
                    // #136: Play/Pause, Play, Pause and Space act whether the controls are up or not.
                    event.key.mediaKeyAction() != null -> {
                        if (!mediaKeysAct(tracksPanelOpen)) {
                            false
                        } else {
                            showControls(requestFocus = false)
                            when (event.key.mediaKeyAction()) {
                                MediaKeyAction.Toggle -> viewModel.togglePlayPause()
                                MediaKeyAction.Play -> viewModel.play()
                                MediaKeyAction.Pause -> viewModel.pause()
                                null -> Unit
                            }
                            true
                        }
                    }
                    // #136: OK with the controls hidden only brings them up, focus on Play/Pause.
                    event.isOkKey() && okOnlyShowsControls(controlsVisible, tracksPanelOpen) -> {
                        showControls(requestFocus = true)
                        okSequence.begin()
                        true
                    }
                    event.isOkKey() -> {
                        if (!okTogglesPlayback(controlsVisible, tracksPanelOpen, focusedControl, scrubberFocused)) {
                            false // the focused control (or radio row) handles OK itself
                        } else {
                            // On the scrubber, OK keeps focus there (#102).
                            showControls(requestFocus = !scrubberFocused)
                            viewModel.togglePlayPause()
                            okSequence.begin()
                            true
                        }
                    }
                    event.key == Key.DirectionUp || event.key == Key.DirectionDown -> {
                        if (seekMode && controlsVisible && !tracksPanelOpen) {
                            // #102: the scrubber sits below the buttons; Up/Down move between them.
                            showControls(requestFocus = false)
                            if (event.key == Key.DirectionDown) {
                                if (!scrubberFocused) pendingFocus = scrubberFocus
                            } else if (scrubberFocused) {
                                pendingFocus = requesters[lastButton ?: defaultControl()]
                            }
                            true
                        } else if (!tracksPanelOpen) {
                            // Hidden: bring the controls up with focus on the default button.
                            showControls(requestFocus = !controlsVisible)
                            viewModel.toggleProgramInfo()
                            true
                        } else {
                            false // the panel's focus graph moves the selection
                        }
                    }
                    event.key == Key.DirectionLeft || event.key == Key.DirectionRight -> {
                        val direction = if (event.key == Key.DirectionLeft) -1 else 1
                        when {
                            tracksPanelOpen -> false
                            // Hidden controls: show them with the scrubber focused and seek once (#102).
                            seekMode && !controlsVisible -> {
                                showControls(requestFocus = false)
                                pendingFocus = scrubberFocus
                                seekPress(direction, event.nativeKeyEvent.repeatCount)
                                true
                            }
                            // Scrubber focused (or about to be): Left/Right seek, not move focus.
                            seekMode && (scrubberFocused || pendingFocus === scrubberFocus) -> {
                                showControls(requestFocus = false)
                                seekPress(direction, event.nativeKeyEvent.repeatCount)
                                true
                            }
                            // #169: controls up (a media key showed them) but nothing holds focus, so
                            // there is nowhere for focus to move. Land on Play/Pause, as an OK reveal
                            // does; the press itself is spent on that.
                            leftRightTakesFocus(
                                controlsVisible, tracksPanelOpen, anchorFocused, scrubberFocused, pendingFocus != null,
                            ) -> {
                                showControls(requestFocus = true)
                                true
                            }
                            controlsVisible -> {
                                showControls(requestFocus = false)
                                false // let focus move between controls
                            }
                            else -> {
                                showControls(requestFocus = false)
                                viewModel.skipWhileHidden(direction * SKIP_STEP_MILLIS)
                                true
                            }
                        }
                    }
                    else -> false
                }
            },
    ) {
        // The root focus anchor: always present so keys have a target when no
        // control is shown. Requester before the focusable (#50 lesson). Draws nothing.
        // "Video" is its label for TalkBack and scripts/tv-ok.sh.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Video" }
                .htvFocusTarget(anchorFocus)
                .onFocusChanged { anchorFocused = it.isFocused }
                .focusable(),
        )

        // Opaque Ground only while there is no picture to show — it backs the
        // notices so their text stays legible. It must be ABSENT while the
        // video owns the window (QA F-37, then re-opened as F-48): the
        // SurfaceView renders *behind* the app window, so any opaque content
        // painted across its rect stops the window from punching a transparent
        // hole there. The picture is then composited underneath an opaque frame
        // and the user sees Ground — while ExoPlayer happily reports
        // onRenderedFirstFrame and READY, because the frames do land.
        //
        // (F-37 landed on fix/f33-focus-ring but never reached main — that
        // branch was replaced before F-37 was merged, regressing black-screen
        // for every later build. F-48 reopened F-37 against 030224b.)
        if (state !is PlayerUiState.Ready) {
            Box(modifier = Modifier.fillMaxSize().background(HappyTvDarkColors.Ground))
        }

        when (val ui = state) {
            is PlayerUiState.Loading -> CenteredNotice(
                heading = "Loading…",
                body = "Opening the stream.",
            )

            is PlayerUiState.NeedsAuth -> CenteredNotice(
                heading = "Sign in to play",
                body = "HappyTV needs your provider sign-in to open this stream.",
            )

            is PlayerUiState.StreamNotFound -> CenteredNotice(
                heading = "Not in your saved lists",
                body = "Refresh the lists from the dashboard, then try again.",
            )

            is PlayerUiState.ReplayUnsupported -> CenteredNotice(
                heading = "Replay isn't available",
                body = ui.message,
            )

            is PlayerUiState.Ready -> PlayerChrome(
                keepControlsWhileBuffering = seekBuffering,
                content = seekTargetMillis?.let { ui.content.withSeekPreview(it) } ?: ui.content,
                seekTargetLabel = seekTargetMillis?.let { PlayerFormatting.runtimeLabel(it) },
                scrubberFocus = scrubberFocus,
                onScrubberFocusChange = { focused ->
                    scrubberFocused = focused
                    if (focused) focusedControl = null
                },
                now = now,
                controlsVisible = controlsVisible,
                focusedControl = focusedControl,
                requesters = requesters,
                message = message,
                tracksPanelOpen = tracksPanelOpen,
                onFocus = { focusedControl = it; lastButton = it },
                onPlayPause = { showControls(requestFocus = false); viewModel.togglePlayPause() },
                onGoLive = { showControls(requestFocus = false); viewModel.goLive() },
                onStartOver = {
                    showControls(requestFocus = false)
                    viewModel.startOver()
                    // The pill is replaced by Go live in the same slot: move focus with it.
                    focusAfterStartOver = true
                },
                onProgramInfo = { showControls(requestFocus = false); viewModel.toggleProgramInfo() },
                onTracks = {
                    tracksPanelOpen = true
                    controlsVisible = true
                    lastInputAtMillis = System.currentTimeMillis()
                },
                onFavorite = { showControls(requestFocus = false); viewModel.toggleFavorite() },
                onTracksClosed = {
                    tracksPanelOpen = false
                    showControls()
                    focusedControl = PlayerControl.Tracks
                    pendingFocus = tracksFocus
                },
                onDismissMessage = viewModel::clearMessage,
                onSelectAudio = viewModel::selectAudioTrack,
                onSelectSubtitle = viewModel::selectSubtitleTrack,
            )

            is PlayerUiState.Offline -> PlayerOfflineCard(
                content = ui.content,
                onBackToChannels = onBackToChannels,
                onTryAgain = {
                    viewModel.retry()
                    showControls()
                },
                backToChannelsFocus = backToChannelsFocus,
                tryAgainFocus = tryAgainFocus,
            )

            // F-68: the channel is fine, this TV cannot decode its format.
            // Same card geometry, honest copy, and no "Try again" — unlike
            // Offline, retrying a decoder wall cannot succeed.
            is PlayerUiState.UnsupportedFormat -> PlayerUnsupportedFormatCard(
                content = ui.content,
                onBackToChannels = onBackToChannels,
                backToChannelsFocus = backToChannelsFocus,
            )

            // #21: the guard refused an HTTPS to HTTP redirect that would carry
            // the sign-in details. Open settings (focused) and Back; no retry.
            is PlayerUiState.InsecureRedirect -> PlayerErrorCard(
                content = ui.content,
                heading = PlayerViewModel.insecureRedirectHeading(ui.content),
                body = null,
                onBackToChannels = onBackToChannels,
                onTryAgain = null,
                backToChannelsFocus = backToChannelsFocus,
                tryAgainFocus = null,
                backLabel = "Back",
                alwaysShowBack = true,
                onOpenSettings = openSettings,
                openSettingsFocus = openSettingsFocus,
            )
        }

        if (endCardOpen) {
            EndOfEpisodeCard(
                state = endOfPlayback,
                onPrimary = viewModel::playNextEpisode,
                onSecondary = {
                    if (endOfPlayback is EndOfPlayback.Countdown) viewModel.cancelCountdown() else onBack()
                },
                primaryFocus = nextEpisodeFocus,
                secondaryFocus = endSecondaryFocus,
            )
        }

        // Leave-the-movie confirmation (not drawn in the handoff; built from
        // the dialog component — "On a movie with more than 5 min left, it
        // asks first").
        if (leaveConfirmOpen && content != null) {
            LeaveMovieDialog(
                remainingMillis = content.progress.remainingMillis(),
                onKeepWatching = {
                    leaveConfirmOpen = false
                    showControls()
                },
                onStopWatching = onBack,
                keepWatchingFocus = keepWatchingFocus,
                stopWatchingFocus = stopWatchingFocus,
                isEpisode = content.isEpisode,
            )
        }
    }
}

// ------------------------------------------------------------------- chrome

/** The chrome Box children need [BoxScope] for `align`. */
@Composable
private fun BoxScope.PlayerChrome(
    content: PlayerContent,
    keepControlsWhileBuffering: Boolean,
    seekTargetLabel: String?,
    scrubberFocus: FocusRequester,
    onScrubberFocusChange: (Boolean) -> Unit,
    now: Long,
    controlsVisible: Boolean,
    focusedControl: PlayerControl?,
    requesters: Map<PlayerControl, FocusRequester>,
    message: String?,
    tracksPanelOpen: Boolean,
    onFocus: (PlayerControl) -> Unit,
    onPlayPause: () -> Unit,
    onGoLive: () -> Unit,
    onStartOver: () -> Unit,
    onProgramInfo: () -> Unit,
    onTracks: () -> Unit,
    onFavorite: () -> Unit,
    onTracksClosed: () -> Unit,
    onDismissMessage: () -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val buffering = content.isBuffering

    // Gradients + controls: with the controls, except the top block which
    // stays through buffering.
    if (controlsVisible && !buffering) {
        Box(modifier = Modifier.align(Alignment.TopCenter)) { PlayerTopGradient() }
        Box(modifier = Modifier.align(Alignment.BottomCenter)) { PlayerBottomGradient() }
    }

    // Top block + clock (top 54). Stays while buffering; hides with controls.
    if (controlsVisible || buffering) {
        Row(
            modifier = Modifier
                .offset(x = d.px(96), y = d.px(54))
                .width(d.px(1728)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            PlayerTopBlock(
                topLine = content.topLine,
                title = content.title,
                monogram = content.monogram,
                monogramPalette = content.monogramPalette,
                logoUrl = content.logoUrl,
            )
            PlayerClock(utcMillis = now)
        }
    }

    // Now / Next card (channels only, toggled by Program info).
    val infoCard = if (content.showProgramInfo) PlayerInfoCard.from(content, now) else null
    if (controlsVisible && !buffering && infoCard != null) {
        // Bottom edge 24 px above the control row's ring; grows upward with the description.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = d.px(96), bottom = d.px(1080 - 776)),
        ) {
            PlayerInfoCardView(infoCard)
        }
    }

    // Buffering spinner: center of the artboard (top 480, left 900).
    if (buffering) {
        Box(modifier = Modifier.offset(x = d.px(900), y = d.px(480))) {
            PlayerBufferingSpinner()
        }
    }

    // Control row + progress row.
    if (controlsVisible && (!buffering || keepControlsWhileBuffering)) {
        Box(modifier = Modifier.offset(x = d.px(96), y = d.px(800))) {
            PlayerControlRow(
                content = content,
                controlsVisible = true,
                focused = focusedControl,
                requesters = requesters,
                onFocus = onFocus,
                onPlayPause = onPlayPause,
                onGoLive = onGoLive,
                onStartOver = onStartOver,
                onProgramInfo = onProgramInfo,
                onTracks = onTracks,
                onFavorite = onFavorite,
            )
        }
        Box(modifier = Modifier.offset(x = d.px(96), y = d.px(966))) {
            PlayerProgressRow(
                progress = content.progress,
                badge = content.badge,
                seekable = content.mode == PlaybackMode.Movie,
                seekTargetLabel = seekTargetLabel,
                scrubberFocus = scrubberFocus,
                onScrubberFocusChange = onScrubberFocusChange,
            )
        }
    }

    // Snackbar line ("Retrying…") above the progress row, centered.
    if (message != null) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = d.px(876)),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(d.px(22)))
                    .background(HappyTvDarkColors.Raised)
                    .padding(horizontal = d.px(28), vertical = d.px(12)),
            ) {
                Text(
                    text = message,
                    style = typography.captionSemibold,
                    color = HappyTvDarkColors.TextPrimary,
                )
            }
        }
        // The UI owns the dismissal cadence; the VM just publishes the line.
        LaunchedEffect(message) {
            delay(3_000L)
            onDismissMessage()
        }
    }

    // Audio and subtitles panel (right edge, full height).
    if (tracksPanelOpen) {
        val tracks = content.tracks
        if (tracks != null) {
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                PlayerTracksPanel(
                    tracks = tracks,
                    onSelectAudio = onSelectAudio,
                    onSelectSubtitle = onSelectSubtitle,
                    onDone = onTracksClosed,
                )
            }
        }
    }
}

// ------------------------------------------------------------- offline card

/**
 * Channel offline (other states): black video, the top block, then the error
 * card (left 96, top 740, 1100 wide, padding 40/48, radius 24, Raised): a
 * 48 px red alert and "This channel is offline. Try another." (44/52 700),
 * then **Back to channels** (primary, focused) and **Try again** (secondary).
 *
 * F-68 adds one more *other state* — [PlayerUiState.UnsupportedFormat] — on
 * the identical card geometry, with the copy design spec asks for ("one red line
 * with an icon … with the fix beside it") and only the button that actually
 * helps. Both branches share [PlayerErrorCard] so the geometry, the icon and
 * the focus-ring treatment cannot drift apart between them.
 */
@Composable
private fun PlayerOfflineCard(
    content: PlayerContent,
    onBackToChannels: () -> Unit,
    onTryAgain: () -> Unit,
    backToChannelsFocus: FocusRequester,
    tryAgainFocus: FocusRequester,
) {
    val isChannel = content.channelName != null
    PlayerErrorCard(
        content = content,
        heading = if (isChannel) "This channel is offline. Try another." else "This stream is offline.",
        // No second line on the offline card: "Try again" is its own fix.
        body = null,
        onBackToChannels = onBackToChannels,
        onTryAgain = onTryAgain,
        backToChannelsFocus = backToChannelsFocus,
        tryAgainFocus = tryAgainFocus,
    )
}

/**
 * F-68 — the codec-capability card. Same geometry, same red alert icon, same
 * ring-first focus treatment as [PlayerOfflineCard], different truth.
 *
 * Deliberately no **Try again**: a decoder that refused the format will
 * refuse it again, and offering the button teaches the user that retrying is
 * the fix. [PlayerViewModel.UNSUPPORTED_FORMAT_BODY] names the cause in
 * format-generic terms and the one thing that helps (another channel), which
 * is what design spec's error rule asks for — the fix beside the error.
 */
@Composable
private fun PlayerUnsupportedFormatCard(
    content: PlayerContent,
    onBackToChannels: () -> Unit,
    backToChannelsFocus: FocusRequester,
) {
    PlayerErrorCard(
        content = content,
        heading = PlayerViewModel.UNSUPPORTED_FORMAT_HEADING,
        body = PlayerViewModel.UNSUPPORTED_FORMAT_BODY,
        onBackToChannels = onBackToChannels,
        onTryAgain = null,
        backToChannelsFocus = backToChannelsFocus,
        tryAgainFocus = null,
    )
}

/**
 * The one error card both failure states draw: top block, raised card at
 * (96, 740), 1100 wide, alert icon + heading (+ optional fix line), then the
 * buttons that apply.
 *
 * @param tryAgain null hides the button entirely (F-68: a decoder wall has
 *   nothing to retry) — it is not disabled, because a greyed button on TV is
 *   a D-pad trap.
 */
@Composable
private fun PlayerErrorCard(
    content: PlayerContent,
    heading: String,
    body: String?,
    onBackToChannels: () -> Unit,
    onTryAgain: (() -> Unit)?,
    backToChannelsFocus: FocusRequester,
    tryAgainFocus: FocusRequester?,
    backLabel: String = "Back to channels",
    alwaysShowBack: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
    openSettingsFocus: FocusRequester? = null,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val isChannel = content.channelName != null

    Row(
        modifier = Modifier
            .offset(x = d.px(96), y = d.px(54))
            .width(d.px(1728)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        PlayerTopBlock(
            topLine = content.topLine,
            title = content.title,
            monogram = content.monogram,
            monogramPalette = content.monogramPalette,
            logoUrl = content.logoUrl,
        )
    }

    Column(
        modifier = Modifier
            .offset(x = d.px(96), y = d.px(740))
            .width(d.px(1100))
            .clip(RoundedCornerShape(d.px(24)))
            .background(HappyTvDarkColors.Raised)
            .padding(horizontal = d.px(48), vertical = d.px(40)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                imageVector = com.rysal.happytv.ui.theme.HappyTvIcons.Alert,
                contentDescription = null,
                tint = HappyTvDarkColors.StateError,
                modifier = Modifier.size(d.px(48)),
            )
            Spacer(Modifier.width(d.px(32)))
            Text(
                text = heading,
                style = typography.heading,
                color = HappyTvDarkColors.TextPrimary,
            )
        }
        // design spec error rule: the fix beside the error. Body reads under the
        // heading, one level down in weight so the cause stays the loud line.
        if (body != null) {
            Spacer(Modifier.height(d.px(16)))
            Text(
                text = body,
                style = typography.body,
                color = HappyTvDarkColors.TextSecondary,
                modifier = Modifier.padding(start = d.px(80)),
            )
        }
        Spacer(Modifier.height(d.px(40)))
        Row(horizontalArrangement = Arrangement.spacedBy(d.px(24))) {
            if (onOpenSettings != null && openSettingsFocus != null) {
                OfflineButton(
                    label = "Open settings",
                    primary = true,
                    focus = openSettingsFocus,
                    onClick = onOpenSettings,
                )
            }
            if (isChannel || alwaysShowBack) {
                OfflineButton(
                    label = backLabel,
                    primary = onOpenSettings == null,
                    focus = backToChannelsFocus,
                    onClick = onBackToChannels,
                )
            }
            if (onTryAgain != null && tryAgainFocus != null) {
                OfflineButton(
                    label = "Try again",
                    primary = !isChannel,
                    focus = tryAgainFocus,
                    onClick = onTryAgain,
                )
            }
        }
    }
}
@Composable
private fun OfflineButton(
    label: String,
    primary: Boolean,
    focus: FocusRequester,
    onClick: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val fill = if (primary) HappyTvDarkColors.Accent else HappyTvDarkColors.Overlay
    Box(
        modifier = Modifier
            .height(d.px(88))
            // Ring first, colour follows the fill (Accent fill → near-white
            // ring) — the F-33 contract; the old chain clipped the ring away
            // entirely, leaving a D-pad user nothing to steer by.
            .htvPlayerFocusable(
                radius = d.px(44),
                fill = fill,
                focusRequester = focus,
                onClick = onClick,
            )
            .padding(horizontal = d.px(48)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = typography.bodyExtraBold.takeIf { primary } ?: typography.bodyBold,
            color = if (primary) HappyTvDarkColors.OnAccent else HappyTvDarkColors.TextPrimary,
        )
    }
}

// ------------------------------------------------------------- leave dialog

/** "Stop watching?" — the drawn-in-code leave-the-movie confirmation. */
@Composable
private fun LeaveMovieDialog(
    remainingMillis: Long,
    onKeepWatching: () -> Unit,
    onStopWatching: () -> Unit,
    keepWatchingFocus: FocusRequester,
    stopWatchingFocus: FocusRequester,
    isEpisode: Boolean,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val minutesLeft = (remainingMillis / 60_000L).coerceAtLeast(1L)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HappyTvDarkColors.Scrim)
            // #66: the dialog is a focus trap and swallows every key that is
            // not its own. Left/Right pick a button explicitly (no geometry
            // search that could leave the dialog); Up/Down/Tab go nowhere;
            // OK falls through to the focused button; anything else (media
            // keys, digits) is consumed so nothing reaches the player.
            .onPreviewKeyEvent { event -> leaveDialogKey(event, keepWatchingFocus, stopWatchingFocus) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(d.px(1040))
                .clip(RoundedCornerShape(d.px(32)))
                .background(HappyTvDarkColors.Raised)
                .padding(horizontal = d.px(64), vertical = d.px(56)),
        ) {
            Text(
                text = "Stop watching?",
                style = typography.heading,
                color = HappyTvDarkColors.TextPrimary,
            )
            Spacer(Modifier.height(d.px(24)))
            Text(
                text = "The ${if (isEpisode) "episode" else "movie"} has about $minutesLeft min left.",
                style = typography.body,
                color = HappyTvDarkColors.TextSecondary,
            )
            Spacer(Modifier.height(d.px(40)))
            Row(horizontalArrangement = Arrangement.spacedBy(d.px(24))) {
                Box(
                    modifier = Modifier
                        .height(d.px(88))
                        .htvPlayerFocusable(
                            radius = d.px(44),
                            fill = HappyTvDarkColors.Overlay,
                            focusRequester = keepWatchingFocus,
                            onClick = onKeepWatching,
                        )
                        .padding(horizontal = d.px(48)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Keep watching",
                        style = typography.bodyBold,
                        color = HappyTvDarkColors.TextPrimary,
                    )
                }
                Box(
                    modifier = Modifier
                        .height(d.px(88))
                        // Accent fill → the near-white ring, never Accent-on-
                        // Accent (F-33).
                        .htvPlayerFocusable(
                            radius = d.px(44),
                            fill = HappyTvDarkColors.Accent,
                            focusRequester = stopWatchingFocus,
                            onClick = onStopWatching,
                        )
                        .padding(horizontal = d.px(48)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Stop watching",
                        style = typography.bodyExtraBold,
                        color = HappyTvDarkColors.OnAccent,
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { keepWatchingFocus.requestFocus() } }
}

/**
 * Key routing for [LeaveMovieDialog] (#66). Returns true when the dialog
 * consumed the event; false only for the OK keys, which the focused button's
 * click handles.
 */
internal fun leaveDialogKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    keepWatching: FocusRequester,
    stopWatching: FocusRequester,
): Boolean {
    if (event.isOkKey()) return false
    if (event.type == KeyEventType.KeyDown) {
        when (event.key) {
            Key.DirectionLeft -> runCatching { keepWatching.requestFocus() }
            Key.DirectionRight -> runCatching { stopWatching.requestFocus() }
            else -> Unit
        }
    }
    return true
}

// ------------------------------------------------------- end of episode (#84)

/**
 * "Next episode" card at the end of an episode: the next episode's code and title, a plain
 * "Starts in 10 s" line (text only) and two buttons. Next episode has the initial focus. After
 * Cancel it reads Play next episode / Back to details with no count. The card is inside the
 * 54 px safe area and the D-pad is trapped on it, like the leave dialog.
 */
@Composable
private fun EndOfEpisodeCard(
    state: EndOfPlayback,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    primaryFocus: FocusRequester,
    secondaryFocus: FocusRequester,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val label: String
    val countdown: String?
    val primaryText: String
    val secondaryText: String
    when (state) {
        is EndOfPlayback.Countdown -> {
            label = state.label
            countdown = "Starts in ${state.secondsLeft} s"
            primaryText = "Next episode"
            secondaryText = "Cancel"
        }
        is EndOfPlayback.Stopped -> {
            label = state.label
            countdown = null
            primaryText = "Play next episode"
            secondaryText = "Back to details"
        }
        else -> return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HappyTvDarkColors.Scrim)
            .onPreviewKeyEvent { event -> leaveDialogKey(event, primaryFocus, secondaryFocus) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(d.px(1040))
                .clip(RoundedCornerShape(d.px(32)))
                .background(HappyTvDarkColors.Raised)
                .padding(horizontal = d.px(64), vertical = d.px(56)),
        ) {
            Text(text = "Up next", style = typography.body, color = HappyTvDarkColors.TextSecondary)
            Spacer(Modifier.height(d.px(12)))
            Text(text = label, style = typography.heading, color = HappyTvDarkColors.TextPrimary, maxLines = 2)
            Spacer(Modifier.height(d.px(16)))
            // Reserve the line when stopped so the buttons never move.
            Text(
                text = countdown.orEmpty(),
                style = typography.body,
                color = HappyTvDarkColors.TextSecondary,
                modifier = Modifier.height(d.px(40)),
            )
            Spacer(Modifier.height(d.px(24)))
            Row(horizontalArrangement = Arrangement.spacedBy(d.px(24))) {
                Box(
                    modifier = Modifier
                        .height(d.px(88))
                        .htvPlayerFocusable(
                            radius = d.px(44),
                            fill = HappyTvDarkColors.Accent,
                            focusRequester = primaryFocus,
                            onClick = onPrimary,
                        )
                        .padding(horizontal = d.px(48)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = primaryText, style = typography.bodyExtraBold, color = HappyTvDarkColors.OnAccent)
                }
                Box(
                    modifier = Modifier
                        .height(d.px(88))
                        .htvPlayerFocusable(
                            radius = d.px(44),
                            fill = HappyTvDarkColors.Overlay,
                            focusRequester = secondaryFocus,
                            onClick = onSecondary,
                        )
                        .padding(horizontal = d.px(48)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = secondaryText, style = typography.bodyBold, color = HappyTvDarkColors.TextPrimary)
                }
            }
        }
    }

    // Focus lands on the first button when the card shows and again when Cancel swaps the buttons;
    // the countdown ticking does not move it.
    LaunchedEffect(state::class) { runCatching { primaryFocus.requestFocus() } }
}

// ------------------------------------------------------------------ helpers

/** A plain centered notice for the pre-Ready states (loading, auth, missing). */
@Composable
private fun CenteredNotice(heading: String, body: String) {
    val typography = LocalHappyTvTypography.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = heading, style = typography.heading, color = HappyTvDarkColors.TextPrimary)
            Spacer(Modifier.height(androidx.compose.ui.unit.Dp(16f)))
            Text(
                text = body,
                style = typography.body,
                color = HappyTvDarkColors.TextSecondary,
            )
        }
    }
}

/**
 * The player's transparent canvas: same uniform 1920 × 1080 scaling as
 * [com.rysal.happytv.ui.theme.HappyTvCanvas], but without its Ground
 * background — the video shows through, and the black letterbox comes from
 * the activity's window.
 */
@Composable
private fun PlayerCanvas(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(
        // No background at all (QA F-37, re-opened as F-48). This is the
        // *second* of the two edits 06a3b52 made; d34d1c4 re-landed only the
        // conditional Ground Box inside the artboard and left this fill
        // behind, so the whole window stayed opaque over the picture. The
        // PlayerView's SurfaceView composites behind the app window: any
        // opaque content across its rect stops the window punching a
        // transparent hole, the SurfaceView layer is covered, and the user
        // sees a flat Ground screen with audio playing while ExoPlayer
        // happily reports onRenderedFirstFrame + STATE_READY.
        //
        // Evidence: QA's failure screenshot is exactly one colour,
        // rgb(19,16,14) = #13100E = HappyTvDarkColors.Ground, over the whole
        // 1920x1080 (19200/19200 sampled pixels) — not video black.
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val scale = minOf(
            constraints.maxWidth.toFloat() / ARTBOARD_WIDTH_PX,
            constraints.maxHeight.toFloat() / ARTBOARD_HEIGHT_PX,
        )
        val design = remember(density, scale) { HappyTvDesign(density, scale) }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalHappyTvDesign provides design,
        ) {
            Box(
                modifier = Modifier.size(design.px(ARTBOARD_WIDTH_PX), design.px(ARTBOARD_HEIGHT_PX)),
                contentAlignment = Alignment.TopStart,
            ) {
                content()
            }
        }
    }
}

private const val ARTBOARD_WIDTH_PX = 1920f
private const val ARTBOARD_HEIGHT_PX = 1080f

/** Left/Right while controls are hidden: ±10 s on movies. */
private const val SKIP_STEP_MILLIS = 10_000L
private const val PENDING_FOCUS_TIMEOUT_NANOS = 1_000_000_000L

/** Ask before leaving a movie with more than 5 minutes left. */
private const val LEAVE_CONFIRM_THRESHOLD_MILLIS = 5L * 60L * 1000L

private fun androidx.compose.ui.input.key.KeyEvent.isOkKey(): Boolean =
    key == Key.Enter || key == Key.NumPadEnter || key == Key.DirectionCenter

/**
 * #136 QA: the OK press that this screen handled on KeyDown owns the whole sequence: its
 * repeats and its KeyUp are swallowed, so the KeyUp never lands on the control that took
 * focus meanwhile (foundation clicks on enter-key up unconditionally).
 */
internal class OkSequenceGate {
    private var active = false

    fun begin() { active = true }

    /** True when this OK event belongs to a sequence we already handled; the KeyUp ends it. */
    fun consumes(isOk: Boolean, isDown: Boolean, repeatCount: Int = 0): Boolean {
        if (!active || !isOk) return false
        if (isDown && repeatCount == 0) { // a fresh press: the earlier KeyUp was lost
            active = false
            return false
        }
        if (!isDown) active = false
        return true
    }
}

/** What a media key or Space asks of playback (#136). */
internal enum class MediaKeyAction { Toggle, Play, Pause }

internal fun Key.mediaKeyAction(): MediaKeyAction? = when (this) {
    Key.MediaPlayPause, Key.Spacebar -> MediaKeyAction.Toggle
    Key.MediaPlay -> MediaKeyAction.Play
    Key.MediaPause -> MediaKeyAction.Pause
    else -> null
}

/** Media keys and Space act at any time except while the tracks panel owns the keys (modals return earlier). */
internal fun mediaKeysAct(tracksPanelOpen: Boolean): Boolean = !tracksPanelOpen

/** OK with the controls hidden only shows them (#136); it never toggles playback. */
internal fun okOnlyShowsControls(controlsVisible: Boolean, tracksPanelOpen: Boolean): Boolean =
    !controlsVisible && !tracksPanelOpen

/**
 * OK goes to the focused control. Only OK on the scrubber or with nothing focused
 * (controls up) toggles playback here; hidden OK shows the controls instead (#136); the tracks panel and every other button handle their own OK.
 */
internal fun okTogglesPlayback(
    controlsVisible: Boolean,
    tracksPanelOpen: Boolean,
    focusedControl: PlayerControl?,
    scrubberFocused: Boolean,
): Boolean = when {
    tracksPanelOpen -> false
    !controlsVisible -> false // hidden OK only shows the controls (#136)
    scrubberFocused -> true
    focusedControl == null -> true
    else -> false // a control button: its own click runs (Play/Pause included)
}

/**
 * #169: Left/Right with the controls up but only the root anchor focused (a media key reveals them
 * with [showControls] `requestFocus = false`, so no control took focus). Compose finds no
 * neighbour for the anchor, so the press would do nothing; it takes focus to Play/Pause instead.
 */
internal fun leftRightTakesFocus(
    controlsVisible: Boolean,
    tracksPanelOpen: Boolean,
    anchorFocused: Boolean,
    scrubberFocused: Boolean,
    focusPending: Boolean,
): Boolean = controlsVisible && !tracksPanelOpen && anchorFocused && !scrubberFocused && !focusPending

/** True when the root focus anchor must hold focus: Ready, and no overlay or control owns it. */
internal fun needsRootFocus(
    ready: Boolean,
    controlsVisible: Boolean,
    tracksPanelOpen: Boolean,
    leaveConfirmOpen: Boolean,
    endCardOpen: Boolean,
): Boolean = ready && !controlsVisible && !tracksPanelOpen && !leaveConfirmOpen && !endCardOpen

/** The scrubber while a seek is pending (#102): dot, fill and elapsed label at [targetMillis]. */
internal fun PlayerContent.withSeekPreview(targetMillis: Long): PlayerContent {
    val duration = progress.durationMillis
    if (mode != PlaybackMode.Movie || duration <= 0L) return this
    val fraction = (targetMillis.toFloat() / duration).coerceIn(0f, 1f)
    return copy(
        progress = progress.copy(
            fillFraction = fraction,
            bufferedFraction = maxOf(progress.bufferedFraction, fraction),
            startLabel = PlayerFormatting.runtimeLabel(targetMillis),
        ),
    )
}

/** Remaining time for the leave-movie check (movies only track runtime). */
private fun PlayerProgress.remainingMillis(): Long =
    if (durationMillis <= 0L) 0L else (durationMillis - positionMillis).coerceAtLeast(0L)

/** The control that takes focus when the controls are revealed: Play/Pause in every mode (#138). */
internal fun defaultControlFor(mode: PlaybackMode?): PlayerControl = PlayerControl.PlayPause
private const val START_OVER_FOCUS_WINDOW_MILLIS = 15_000L
