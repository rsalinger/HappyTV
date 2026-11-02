package com.rysal.happytv.ui.browse

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How the combined Live TV screen talks to the activity's player host (#16).
 *
 * The screen never touches the player: it asks to play a channel, asks to stop,
 * reads which channel is playing (to leave the preview slot undrawn, F-48, and
 * to land focus), and watches [restoreTick] for "Back brought you here, focus
 * the channel that was playing".
 *
 * @param playingStreamId read through a lambda so the caller's snapshot state
 *   is observed by whoever reads [playingStreamId] in composition.
 */
@Stable
class LivePlayback(
    private val playingStreamIdProvider: () -> String?,
    /** OK on a channel or "Watch now": the host decides between enlarging the preview and a zap. */
    val onPlayChannel: (streamId: String) -> Unit,
    private val fullActiveProvider: () -> Boolean = { false },
    /** Where a live channel sits in its provider category, to find it again when it is not in the current list. */
    val locate: suspend (streamId: String) -> ChannelLocation? = { null },
) {
    /**
     * True while the player is full screen. The combined screen must not act on
     * Back then (the host routes it), whatever has focus (#16 QA).
     */
    val fullActive: Boolean get() = fullActiveProvider()

    /**
     * Set by the screen while a modal (program details, a popup menu) is open. The
     * host hides the preview video meanwhile so the overlay is not clipped by the
     * SurfaceView hole.
     */
    var overlayOpen: Boolean by mutableStateOf(false)

    /** The live channel playing in the preview (or full screen), null when nothing plays. */
    val playingStreamId: String? get() = playingStreamIdProvider()

    /** Bumped by the host when Back returns to the screen; the screen focuses [restoreStreamId]. */
    var restoreTick: Int by mutableIntStateOf(0)
        private set

    /** The channel Back came from; stays set after a stop so focus still lands on it. */
    var restoreStreamId: String? by mutableStateOf(null)
        private set

    /**
     * Set when a 2 s Back hold brought the preview to full screen (#138): the next Back out
     * jumps the list to the playing channel, in its own category if need be, instead of
     * leaving the D-pad where the viewer was browsing. Cleared by that Back.
     */
    var jumpToPlayingOnBack: Boolean = false

    /** The pending restore should find the channel in its own category when it is not listed. */
    var restoreJump: Boolean = false
        private set

    /** Ask the screen to put the D-pad back on [streamId]'s row or cell. */
    fun requestRestore(streamId: String?) {
        restoreJump = jumpToPlayingOnBack
        jumpToPlayingOnBack = false
        restoreStreamId = streamId
        restoreTick++
    }
}

/** A channel's provider [categoryId] and the number of channels that sort before it there (#138). */
data class ChannelLocation(val categoryId: String, val rowsBefore: Int)
