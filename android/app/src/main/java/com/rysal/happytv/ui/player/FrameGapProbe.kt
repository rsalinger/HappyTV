@file:OptIn(androidx.media3.common.util.UnstableApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.rysal.happytv.ui.player

import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.rysal.happytv.perf.HtvPerf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only `player.back.gap` (#16): the longest gap between rendered video
 * frames in the [WINDOW_MILLIS] after Back, which is how a frozen picture or a
 * rebuffer shows up when the surface changes size. Fed from the playback
 * thread by Media3's frame-metadata hook, so main-thread jank cannot inflate
 * it. The gap that straddles the Back press counts (the frame before it is
 * remembered even while disarmed).
 *
 * Logs `HtvPerf: player.back.gap <n>ms` once per arm. No URLs or titles.
 */
internal class FrameGapProbe(
    private val nowNanos: () -> Long = System::nanoTime,
    private val sink: (gapMillis: Long) -> Unit = { HtvPerf.log(HtvPerf.PLAYER_BACK_GAP, it) },
) : VideoFrameMetadataListener {

    private val lock = Any()
    private var lastFrameNanos = 0L
    private var armedAtNanos = 0L
    private var armed = false
    private var maxGapNanos = 0L

    /** Starts a [WINDOW_MILLIS] window now; [scope] closes it if frames stop. */
    fun arm(scope: CoroutineScope) {
        val armedAt: Long
        synchronized(lock) {
            armed = true
            armedAtNanos = nowNanos()
            armedAt = armedAtNanos
            maxGapNanos = 0L
        }
        scope.launch {
            delay(WINDOW_MILLIS + CLOSE_SLACK_MILLIS)
            finish(armedAt)
        }
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) = onFrame()

    /** One rendered frame. Split out so tests drive it without Media3 types. */
    fun onFrame() {
        var report: Long? = null
        synchronized(lock) {
            val now = nowNanos()
            if (armed) {
                if (lastFrameNanos != 0L) maxGapNanos = maxOf(maxGapNanos, now - lastFrameNanos)
                if (now - armedAtNanos >= WINDOW_MILLIS * NANOS_PER_MILLI) {
                    armed = false
                    report = maxGapNanos / NANOS_PER_MILLI
                }
            }
            lastFrameNanos = now
        }
        report?.let(sink)
    }

    /** Closes the window when no frame arrived to do it (a stall is the worst gap). */
    internal fun finish(armedAt: Long) {
        var report: Long? = null
        synchronized(lock) {
            if (!armed || armedAtNanos != armedAt) return
            armed = false
            val stall = if (lastFrameNanos == 0L) 0L else nowNanos() - lastFrameNanos
            report = maxOf(maxGapNanos, stall) / NANOS_PER_MILLI
        }
        report?.let(sink)
    }

    /** Test seam: arm without a coroutine scope. Returns the arm token for [finish]. */
    internal fun armForTest(): Long = synchronized(lock) {
        armed = true
        armedAtNanos = nowNanos()
        maxGapNanos = 0L
        armedAtNanos
    }

    companion object {
        const val WINDOW_MILLIS = 3_000L
        private const val CLOSE_SLACK_MILLIS = 100L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
