package com.rysal.happytv.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * #223: "this channel counts as watched once it has really played for [thresholdMillis]".
 * Used for live channels started outside Live TV's own lists (Search, the player screen), and
 * reusable for channel up/down.
 *
 * The clock runs only while the picture is playing ([onPlaying] true): it starts when the stream
 * is Ready and rendering, pauses while buffering or paused, and a stream error zeroes it
 * ([onFailure]), so a stream that never plays records nothing. [arm] for another channel (a
 * zap) and [cancel] (stop, leaving) drop whatever was counted. Records at most once per arm.
 */
class PlayedChannelTimer(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val record: (String) -> Unit,
    private val thresholdMillis: Long = DEFAULT_THRESHOLD_MILLIS,
) {
    private var streamId: String? = null
    private var playedMillis = 0L
    private var playingSince: Long? = null
    private var job: Job? = null

    /** Starts counting for [streamId]; discards any earlier channel's progress. */
    fun arm(streamId: String) {
        cancel()
        this.streamId = streamId
    }

    /** The picture started or stopped playing (buffering, pause, end). No-op when not armed. */
    fun onPlaying(isPlaying: Boolean) {
        val id = streamId ?: return
        if (isPlaying) {
            if (playingSince != null) return
            playingSince = clock()
            job = scope.launch {
                delay((thresholdMillis - playedMillis).coerceAtLeast(0L))
                streamId = null
                playingSince = null
                job = null
                record(id)
            }
        } else {
            pause()
        }
    }

    /** A stream error: whatever played before does not count toward this attempt. */
    fun onFailure() {
        if (streamId == null) return
        pause()
        playedMillis = 0L
    }

    /** Disarms: the channel was left, stopped, or replaced. */
    fun cancel() {
        job?.cancel()
        job = null
        streamId = null
        playedMillis = 0L
        playingSince = null
    }

    private fun pause() {
        job?.cancel()
        job = null
        playingSince?.let { playedMillis += (clock() - it).coerceAtLeast(0L) }
        playingSince = null
    }

    companion object {
        const val DEFAULT_THRESHOLD_MILLIS = 10_000L
    }
}
