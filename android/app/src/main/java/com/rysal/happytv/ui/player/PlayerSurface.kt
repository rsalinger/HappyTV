package com.rysal.happytv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

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
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view ->
            view.player = player
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
