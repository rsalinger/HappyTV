@file:OptIn(androidx.media3.common.util.UnstableApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.rysal.happytv.ui.player

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.rysal.happytv.BuildConfig
import com.rysal.happytv.data.BackWhileWatching
import com.rysal.happytv.data.entity.WatchProgressPolicy
import com.rysal.happytv.perf.HtvPerf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Everything about *playing* that is not drawing: the F-54/F-56 lifecycle
 * ([PlayerLifecycle]), engine construction, the Media3 listeners that feed the
 * ViewModel, the 1 s position tick, the [PlayerEffect] collector, the
 * snapshot-backed [exoPlayer] the [PlayerSurface] reads, and the
 * [PlayerHandleBridge] the ViewModel drives.
 *
 * Plain Kotlin, scoped to one activity. The activity forwards `onStart`,
 * `onStop` and composition dispose to it. [PlayerActivity] (movies, replay) and
 * the combined Live TV screen (#16) are the two hosts, so the lifecycle rules
 * live in exactly one place.
 *
 * The Live TV host also tracks [mode] (Idle / Full / Preview) and drives the
 * live-only transitions: [play] (Full), [toPreview], [enterFull], [stopPlayback].
 * Those never touch the engine except stop: Full and Preview are the same
 * player, only the surface bounds and the D-pad owner differ.
 *
 * [engineFactory] is a seam for tests; production builds a Media3 engine.
 */
internal class PlaybackHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val viewModel: PlayerViewModel,
    val handle: PlayerHandleBridge,
    isFinishing: () -> Boolean,
    engineFactory: ((String) -> PlaybackEngine)? = null,
    /** Debug-only frame-gap probe (#16); a seam so unit tests do not arm real timers. */
    private val frameGapProbe: Boolean = BuildConfig.DEBUG,
) {

    /**
     * The Compose-backed player reference: snapshot state, so lifecycle
     * handlers can flip it to null (stop) or a fresh [ExoPlayer] (start) from
     * outside composition and the [PlayerSurface] `update` lambda picks it up.
     * Non-Compose readers use the same property (a snapshot read).
     */
    var exoPlayer: ExoPlayer? by mutableStateOf(null)
        private set

    private val lifecycle = PlayerLifecycle(
        isFinishing = isFinishing,
        playbackTarget = {
            val ready = viewModel.state.value as? PlayerUiState.Ready ?: return@PlayerLifecycle null
            PlaybackTarget(ready.content.url, ready.content.mode, ready.content.startPositionMillis)
        },
        engineFactory = engineFactory ?: ::buildEngine,
        // Everything outside PlayerLifecycle that has to follow the engine:
        // the Compose PlayerView (via `exoPlayer`) and the bridge the
        // ViewModel drives.
        onEngineChanged = { engine ->
            val player = (engine as? ExoPlayerEngine)?.player
            exoPlayer = player
            if (player == null) handle.detach() else handle.attach(player)
        },
    )

    /** The engine currently attached, or null between a stop and the next start. */
    val engine: PlaybackEngine? get() = lifecycle.engine

    private val _mode = MutableStateFlow(HostMode.Idle)
    private val _playingStreamId = MutableStateFlow<String?>(null)

    /** Live TV host only (#16): what the one surface is showing. Flows, not snapshot state: plain JVM tests drive it. */
    val modeFlow: StateFlow<HostMode> = _mode.asStateFlow()

    /** The live channel that is playing (Full or Preview), or null when Idle. */
    val playingStreamIdFlow: StateFlow<String?> = _playingStreamId.asStateFlow()

    val mode: HostMode get() = _mode.value
    val playingStreamId: String? get() = _playingStreamId.value

    /** Live read of the "Back while watching" setting; the activity keeps it current. */
    @Volatile
    var backWhileWatching: BackWhileWatching = BackWhileWatching.SHOW_CHANNELS

    /** Debug-only (#16): longest rendered-frame gap after Back. */
    private val backGap = FrameGapProbe()

    /** Starts the collectors. Call once, from `onCreate`. */
    fun attach() {
        // Rebuild the pipeline when the URL changes (first Ready, Replay's
        // Go live, a zap). The null between (Loading/Offline) is kept so a
        // retry that lands on the same URL re-prepares.
        scope.launch {
            viewModel.state
                .map { state ->
                    (state as? PlayerUiState.Ready)?.content?.let { PlaybackTarget(it.url, it.mode, it.startPositionMillis) }
                }
                .distinctUntilChanged()
                .collect { target ->
                    Log.i(TAG, "target changed: mode=${target?.mode}")
                    if (target != null) lifecycle.prepareFor(target)
                }
        }

        // Effects toward the player: retry-prepare + track selection.
        scope.launch {
            viewModel.effects.collect { effect ->
                when (effect) {
                    PlayerEffect.RetryPrepare -> exoPlayer?.prepare()
                    is PlayerEffect.SelectAudioTrack ->
                        exoPlayer?.selectTrack(effect.groupIndex, effect.trackIndex)
                    is PlayerEffect.SelectSubtitleTrack ->
                        exoPlayer?.selectTrack(effect.groupIndex, effect.trackIndex)
                }
            }
        }

        // 1 s position tick: feeds progress, badges and the live-edge marker.
        scope.launch {
            var sinceSave = 0L
            while (true) {
                delay(1_000L)
                val player = exoPlayer ?: continue
                viewModel.onPosition(
                    positionMillis = player.currentPosition,
                    durationMillis = player.duration.takeIf { it > 0L } ?: 0L,
                    bufferedMillis = player.bufferedPosition,
                )
                // Watch progress (#78): every 30 s while playing.
                sinceSave += 1_000L
                if (sinceSave >= WatchProgressPolicy.SAVE_INTERVAL_MILLIS) {
                    sinceSave = 0L
                    if (player.isPlaying) saveProgress()
                }
            }
        }
    }

    /**
     * Plays a live [launch] full screen (Live TV host): the first channel, a
     * zap from the preview, or one from Search / the guide. Re-resolves and
     * re-points the same engine; nothing is rebuilt when one is attached.
     */
    fun play(launch: PlayerLaunch) {
        if (engine != null) HtvPerf.start(HtvPerf.PLAYER_ZAP)
        _playingStreamId.value = launch.streamId
        _mode.value = HostMode.Full
        viewModel.start(launch)
    }

    /** OK on the channel that is already the preview: enlarge it, no reload. */
    fun enterFull() {
        if (mode == HostMode.Preview) _mode.value = HostMode.Full
    }

    /**
     * Back from full-screen live playback with Show channels: the surface snaps
     * to the preview slot, the player is untouched. Starts the
     * `player.back.preview` mark (ended by the screen once it is interactive).
     */
    fun toPreview() {
        if (mode != HostMode.Full) return
        HtvPerf.start(HtvPerf.PLAYER_BACK_PREVIEW)
        if (frameGapProbe) backGap.arm(scope)
        _mode.value = HostMode.Preview
    }

    /**
     * A Back press in the player. [longPress] always stops (live); a short
     * press follows [backWhileWatching]. Returns what it did (for the caller's
     * focus handling).
     */
    fun onPlayerBack(playbackMode: PlaybackMode, longPress: Boolean): BackAction {
        val action = PlayerPolicy.backAction(playbackMode, backWhileWatching, longPress)
        when (action) {
            BackAction.ToPreview -> toPreview()
            BackAction.StopPlayback, BackAction.Leave -> stopPlayback()
        }
        return action
    }

    /**
     * Stops playback and returns to Idle: the ViewModel drops its stream, the
     * surface is cleared before the engine is released (F-54) and nothing can
     * rebuild it. Used by Back with Stop playback, long-press Back, an error
     * card's Back, leaving Live TV's window (Home, Settings, screen off).
     */
    fun stopPlayback() {
        saveProgress()
        HtvPerf.cancel(HtvPerf.PLAYER_ZAP)
        HtvPerf.cancel(HtvPerf.PLAYER_BACK_PREVIEW)
        _mode.value = HostMode.Idle
        _playingStreamId.value = null
        viewModel.stop()
        lifecycle.releaseForDestroy()
    }

    /**
     * Live TV host `Activity.onStop`: a non-finishing stop (Home, screen off,
     * an opaque screen on top) stops live playback for good; there is no
     * auto-resume (design note on #11). A finishing stop is the destroy path's.
     */
    fun onActivityStopped(finishing: Boolean) {
        if (PlayerPolicy.stopHostOnActivityStop(mode, finishing)) stopPlayback()
    }

    /** `Activity.onStart`. No-op until a target exists. */
    fun onStart() = lifecycle.onStart()

    /** `Activity.onStop`. */
    fun onStop() {
        saveProgress()
        lifecycle.onStop()
    }

    /** Composition dispose: the destroy path (Back, finish, system reclaim). */
    fun release() {
        saveProgress()
        HtvPerf.cancel(HtvPerf.PLAYER_NEXT_EPISODE)
        lifecycle.releaseForDestroy()
    }

    /**
     * Hands the engine's exact position to the ViewModel to save as watch progress (#78). The
     * ViewModel decides: movies and episodes only, nothing under a minute, finished clears it.
     * Safe to call at any time; with no engine attached there is nothing to save.
     */
    private fun saveProgress() {
        val attached = engine ?: return
        val duration = (attached as? ExoPlayerEngine)?.player?.duration?.takeIf { it > 0L } ?: 0L
        viewModel.persistProgress(attached.currentPosition, duration)
    }

    /** Builds a Media3 engine and binds the listeners to it. */
    private fun buildEngine(url: String): PlaybackEngine {
        // The URL carries credentials, so it is deliberately not logged.
        Log.i(TAG, "buildEngine: PlayerFactory.build()")
        val created = PlayerFactory.build(context)
        attachPlayerListeners(created, viewModel)
        if (frameGapProbe) created.setVideoFrameMetadataListener(backGap)
        return ExoPlayerEngine(context, created)
    }

    private fun attachPlayerListeners(player: ExoPlayer, viewModel: PlayerViewModel) {
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "onPlayerError: errorCode=${error.errorCode} " +
                        "errorCodeName=${error.errorCodeName} message=${error.message} " +
                        "cause=${error.cause}", error)
                    // F-68: the code is the whole diagnosis. 4001 (and its
                    // neighbours) means this TV cannot decode the format — a
                    // capability wall, not a dead channel — and the ViewModel
                    // must not spend the retry budget on it or call it offline.
                    viewModel.onStreamError(error, error.errorCode)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    Log.i(TAG, "onTracksChanged: groups=${tracks.groups.size}")
                    viewModel.onTracks(tracks.toPlayerTracks())
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.i(TAG, "onIsPlayingChanged: $isPlaying")
                    viewModel.onIsPlayingChanged(isPlaying)
                    // Paused (or stopped): save where it stopped (#78).
                    if (!isPlaying) saveProgress()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.i(TAG, "onPlaybackStateChanged: ${playbackStateName(playbackState)} ($playbackState)")
                    viewModel.onBuffering(playbackState == Player.STATE_BUFFERING)
                    if (playbackState == Player.STATE_READY) viewModel.onMediaRendered()
                    if (playbackState == Player.STATE_ENDED) viewModel.onPlaybackEnded(player.duration.takeIf { it > 0L } ?: 0L)
                }

                // The two signals that separate "frames decoded and queued to
                // the output surface" from "frames actually painted", which is
                // the whole question behind F-48: READY alone proves nothing,
                // a SurfaceView layer holding a buffer proves nothing, only
                // onRenderedFirstFrame means the compositor took a frame.
                override fun onRenderedFirstFrame() {
                    HtvPerf.end(HtvPerf.PLAYER_ZAP)
                    HtvPerf.end(HtvPerf.PLAYER_NEXT_EPISODE)
                    viewModel.onMediaRendered()
                    Log.i(TAG, "onRenderedFirstFrame: first video frame rendered to the output surface")
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    Log.i(TAG, "onVideoSizeChanged: ${videoSize.width}x${videoSize.height} " +
                        "pixelWidthHeightRatio=${videoSize.pixelWidthHeightRatio} " +
                        "unappliedRotationDegrees=${videoSize.unappliedRotationDegrees}")
                }
            },
        )
    }

    private companion object {
        const val TAG = "PlayerF48"
    }
}

// ------------------------------------------------------------------ bridging

/**
 * The [PlayerHandle] the ViewModel drives, backed by the host's player.
 * Created before Compose so both sides share one instance.
 */
class PlayerHandleBridge : PlayerHandle {

    @Volatile
    private var player: ExoPlayer? = null

    fun attach(player: ExoPlayer) {
        this.player = player
    }

    /** Drops the player reference; subsequent [togglePlayPause]/[seekBy]/[prepare]
     *  are no-ops until a fresh [attach]. Used when the host drops the player
     *  across a backgrounding transition (see F-54: SurfaceView BLAST layer that
     *  survives a Sleep/Wake has to be released before the activity's window
     *  is allowed to vanish from SurfaceFlinger). */
    fun detach() {
        this.player = null
    }

    override fun togglePlayPause() {
        val player = player ?: return
        player.playWhenReady = !player.playWhenReady
    }

    override fun setPlayWhenReady(play: Boolean) {
        val player = player ?: return
        player.playWhenReady = play
    }

    override fun seekBy(deltaMillis: Long) {
        val player = player ?: return
        player.seekTo((player.currentPosition + deltaMillis).coerceAtLeast(0L))
    }

    override fun seekTo(positionMillis: Long) {
        val player = player ?: return
        player.seekTo(positionMillis.coerceAtLeast(0L))
    }

    override fun prepare() {
        player?.prepare()
    }
}

/**
 * Audio + subtitle options from `Player.tracks` — the ViewModel hides the
 * panel's button when both lists are empty (no track information at all).
 */
internal fun Tracks.toPlayerTracks(): PlayerTracks? {
    val audio = mutableListOf<PlayerTrackOption>()
    val subtitles = mutableListOf<PlayerTrackOption>()
    var selectedAudio = 0
    var selectedSubtitle = 0
    groups.forEachIndexed { groupIndex, group ->
        val kind = when (group.type) {
            C.TRACK_TYPE_AUDIO -> PlayerTrackLabels.Kind.Audio
            else -> PlayerTrackLabels.Kind.Subtitles
        }
        // Unnamed tracks are numbered by their place among all tracks of that kind (#170).
        val already = if (kind == PlayerTrackLabels.Kind.Audio) audio.size else subtitles.count { it.trackIndex != null }
        val labels = (0 until group.length).map { index ->
            val format = group.getTrackFormat(index)
            PlayerTrackLabels.name(format.language, format.label, kind, already + index + 1)
        }
        when (group.type) {
            C.TRACK_TYPE_AUDIO -> {
                labels.forEachIndexed { index, label ->
                    audio += PlayerTrackOption(label, groupIndex, index)
                    if (group.isTrackSelected(index)) selectedAudio = index
                }
            }
            C.TRACK_TYPE_TEXT -> {
                subtitles += PlayerTrackOption("Off", groupIndex, null)
                labels.forEachIndexed { index, label ->
                    subtitles += PlayerTrackOption(label, groupIndex, index)
                    if (group.isTrackSelected(index)) selectedSubtitle = index + 1
                }
            }
            else -> Unit
        }
    }
    if (audio.isEmpty() && subtitles.size <= 1) return null
    // Two English tracks must not read the same.
    val audioNames = PlayerTrackLabels.distinct(audio.map { it.label })
    val subtitleNames = PlayerTrackLabels.distinct(subtitles.filter { it.trackIndex != null }.map { it.label }).iterator()
    return PlayerTracks(
        audio = audio.mapIndexed { i, o -> o.copy(label = audioNames[i]) },
        subtitles = subtitles.map { if (it.trackIndex == null) it else it.copy(label = subtitleNames.next()) }.ifEmpty { listOf(PlayerTrackOption("Off", -1, null)) },
        selectedAudio = selectedAudio,
        selectedSubtitle = selectedSubtitle,
    )
}

/** Applies an audio/subtitle override, or clears it (the "Off" entry). */
private fun ExoPlayer.selectTrack(groupIndex: Int, trackIndex: Int?) {
    if (groupIndex < 0 || groupIndex >= currentTracks.groups.size) return
    val group = currentTracks.groups[groupIndex]
    val parameters = trackSelectionParameters.buildUpon()
    if (trackIndex == null) {
        parameters.clearOverridesOfType(group.type)
    } else {
        parameters.setOverrideForType(
            TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)),
        )
    }
    trackSelectionParameters = parameters.build()
}
