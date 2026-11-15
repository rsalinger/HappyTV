package com.rysal.happytv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * #263: the screen stays on only while the player wants to play and is not
 * idle or ended: `playWhenReady` with BUFFERING or READY. Paused, ended, idle
 * (stopped or failed: ExoPlayer enters IDLE on error), no player, or a hidden
 * surface hold nothing.
 */
internal fun shouldKeepScreenOn(
    hasPlayer: Boolean,
    visible: Boolean,
    playWhenReady: Boolean,
    playbackState: Int,
): Boolean = hasPlayer && visible && playWhenReady &&
    (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY)

/**
 * Drives `View.keepScreenOn` from player state (a listener, not the 1 s tick, so the
 * surface host is not recomposed, #249). No wake lock, no permission.
 */
internal class KeepScreenOnBinder(private val view: PlayerView) : Player.Listener {
    private var player: Player? = null
    private var visible = true

    fun bind(newPlayer: Player?, newVisible: Boolean) {
        if (player !== newPlayer) {
            player?.removeListener(this)
            player = newPlayer
            newPlayer?.addListener(this)
        }
        visible = newVisible
        apply()
    }

    fun release() {
        player?.removeListener(this)
        player = null
        view.keepScreenOn = false
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = apply()
    override fun onPlaybackStateChanged(playbackState: Int) = apply()
    override fun onPlayerError(error: PlaybackException) = apply()

    private fun apply() {
        val p = player
        view.keepScreenOn = shouldKeepScreenOn(
            hasPlayer = p != null,
            visible = visible,
            playWhenReady = p?.playWhenReady ?: false,
            playbackState = p?.playbackState ?: Player.STATE_IDLE,
        )
    }
}

/**
 * The one Media3 surface: a single `AndroidView`/[PlayerView], created once and
 * never re-parented (re-parenting cycles the SurfaceView and risks the F-48
 * dead-surface state). Letterboxed (RESIZE_MODE_FIT, never crop). The caller
 * sizes it through [modifier]; drawing order is its caller's job — nothing
 * opaque may paint across it while video plays (F-48).
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerSurface(
    player: ExoPlayer?,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    /** False hides the view (Live TV host while Idle) without removing it. */
    visible: Boolean = true,
    /**
     * A modal overlay is open over the preview: draw no video so the overlay is not
     * clipped by the SurfaceView hole. The view stays, attached, with its surface;
     * playback and audio continue.
     */
    overlayHidden: Boolean = false,
) {
    AndroidView(
        modifier = modifier,
        onRelease = { view -> (view.tag as? KeepScreenOnBinder)?.release() },
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view ->
            view.player = player
            val binder = (view.tag as? KeepScreenOnBinder)
                ?: KeepScreenOnBinder(view).also { view.tag = it }
            binder.bind(player, visible)
            view.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
            // Buffering dims the video to 35% (design spec other states).
            view.alpha = when {
                overlayHidden -> 0f
                dimmed -> 0.35f
                else -> 1f
            }
        },
    )
}
