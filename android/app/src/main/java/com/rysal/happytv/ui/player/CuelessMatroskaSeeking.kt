@file:OptIn(androidx.media3.common.util.UnstableApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.rysal.happytv.ui.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor

/**
 * Resume for `.mkv` files that have no Cues (#106).
 *
 * Some provider movies are Matroska files with a known duration but no seek
 * index. Media3's [MatroskaExtractor] reports those as an unseekable stream, and
 * the player answers every seek, including the start position of a resume, by
 * restarting at 0:00. The player log showed `startAt=92742ms` and the scrubber
 * at 0:00:08 eight seconds after the first frame.
 *
 * This wrapper gives such a file an estimated seek map (a position in bytes
 * proportional to the time requested) and, after a seek, scans forward to the
 * next Cluster header so the extractor restarts on a boundary it understands.
 * The landing point is approximate on a variable-bitrate file: the player skips
 * frames when it lands early and starts a little later when it lands late. A
 * file that has Cues keeps Media3's exact seek map; this only replaces maps
 * that are [SeekMap.isSeekable] false with a known duration and length.
 */
internal class CuelessMatroskaExtractor(private val inner: Extractor) : Extractor {

    private companion object {
        const val TAG = "CuelessMkv"
    }

    private var inputLength = C.LENGTH_UNSET.toLong()
    private var durationUs = C.TIME_UNSET
    private var estimated = false
    private var resyncPending = false
    private var search: ClusterSeekSearch? = null

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)

            override fun endTracks() = output.endTracks()

            override fun seekMap(seekMap: SeekMap) {
                val length = inputLength
                val replacement = if (!seekMap.isSeekable && length != C.LENGTH_UNSET.toLong()) {
                    EstimatedSeekMap.orNull(seekMap.durationUs, length)
                } else {
                    null
                }
                estimated = replacement != null
                durationUs = seekMap.durationUs
                output.seekMap(replacement ?: seekMap)
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        inputLength = input.length
        if (resyncPending) {
            resyncPending = false
            if (!ClusterResync.skipToCluster(input)) return Extractor.RESULT_END_OF_INPUT
            val active = search
            if (active != null) {
                val landedUs = ClusterResync.clusterTimeUs(input)
                val step = if (landedUs == null) {
                    ClusterSeekSearch.Step.Accept
                } else {
                    active.next(input.position, landedUs, inputLength, durationUs)
                }
                Log.i(TAG, "landed pos=${input.position} landedUs=$landedUs step=$step")
                when (step) {
                    ClusterSeekSearch.Step.Accept -> search = null
                    is ClusterSeekSearch.Step.SeekTo -> {
                        seekPosition.position = step.position
                        resyncPending = true
                        return Extractor.RESULT_SEEK
                    }
                }
            }
        }
        return inner.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        // Position 0 is a restart from the top (header first): nothing to resync.
        resyncPending = estimated && position > 0L
        Log.i(TAG, "seek position=$position timeUs=$timeUs estimated=$estimated")
        search = if (resyncPending) ClusterSeekSearch(timeUs) else null
        inner.seek(position, timeUs)
    }

    override fun release() = inner.release()

    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}

/** A seek map that maps time to a byte position linearly over the whole file. */
internal class EstimatedSeekMap private constructor(
    private val durationUs: Long,
    private val lengthBytes: Long,
) : SeekMap {

    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = durationUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val clamped = timeUs.coerceIn(0L, durationUs)
        if (clamped == 0L) return SeekMap.SeekPoints(SeekPoint.START)
        return SeekMap.SeekPoints(SeekPoint(clamped, positionFor(clamped)))
    }

    /** Stops short of the end so a seek near it still finds a Cluster to land on. */
    fun positionFor(timeUs: Long): Long {
        val fraction = timeUs.toDouble() / durationUs.toDouble()
        return (fraction * lengthBytes).toLong().coerceIn(0L, (lengthBytes - TAIL_MARGIN_BYTES).coerceAtLeast(0L))
    }

    companion object {
        private const val TAIL_MARGIN_BYTES = 256L * 1024L

        /** Null unless both the duration and the length are known and positive. */
        fun orNull(durationUs: Long, lengthBytes: Long): EstimatedSeekMap? =
            if (durationUs > 0L && durationUs != C.TIME_UNSET && lengthBytes > 0L) {
                EstimatedSeekMap(durationUs, lengthBytes)
            } else {
                null
            }
    }
}

/** Finds the next Matroska Cluster element ID (0x1F43B675) in a stream. */
internal object ClusterResync {
    private val CLUSTER_ID = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
    private const val CHUNK = 64 * 1024
    private const val HEADER_PEEK = 40
    private const val MAX_LEADING_ELEMENTS = 2
    private const val CRC32_ID = 0xBF.toByte()
    private const val VOID_ID = 0xEC.toByte()
    private const val TIMECODE_ID = 0xE7.toByte()

    /** Index of the first Cluster ID in [buffer] (up to [length]), or -1. */
    fun indexOfCluster(buffer: ByteArray, length: Int): Int {
        var i = 0
        val last = length - CLUSTER_ID.size
        while (i <= last) {
            if (buffer[i] == CLUSTER_ID[0] && buffer[i + 1] == CLUSTER_ID[1] &&
                buffer[i + 2] == CLUSTER_ID[2] && buffer[i + 3] == CLUSTER_ID[3]
            ) {
                return i
            }
            i++
        }
        return -1
    }

    /**
     * The Cluster's timecode in microseconds, from the header [input] is
     * positioned at, or null when it cannot be read. Assumes the default
     * TimecodeScale (1 ms), which is what muxers write in practice; a file with
     * another scale only makes the search settle on its last try.
     */
    fun clusterTimeUs(input: ExtractorInput): Long? {
        val bytes = ByteArray(HEADER_PEEK)
        input.resetPeekPosition()
        if (!input.peekFully(bytes, 0, HEADER_PEEK, true)) return null
        return clusterTimeUs(bytes)
    }

    /**
     * [header] starts with the Cluster ID. Pure, for tests. Muxers may put a CRC-32
     * or a Void element before the Timecode (mkvmerge does), so those are skipped.
     */
    fun clusterTimeUs(header: ByteArray): Long? {
        var i = CLUSTER_ID.size
        val sizeLength = vintLength(header[i])
        if (sizeLength == 0) return null
        i += sizeLength
        repeat(MAX_LEADING_ELEMENTS + 1) {
            if (i + 2 > header.size) return null
            val id = header[i]
            val lengthByte = header[i + 1]
            val valueLength = vintLength(lengthByte)
            // Every element met here has a 1-byte size (0x80..0xFE): CRC-32 is 4, Timecode 1..8.
            if (valueLength != 1) return null
            val n = lengthByte.toInt() and 0x7F
            val valueAt = i + 2
            if (id == TIMECODE_ID) {
                if (n !in 1..8 || valueAt + n > header.size) return null
                var value = 0L
                repeat(n) { value = (value shl 8) or (header[valueAt + it].toLong() and 0xFF) }
                return value * 1000L
            }
            if (id != CRC32_ID && id != VOID_ID) return null
            i = valueAt + n
        }
        return null
    }

    /** Bytes in an EBML variable-length integer, from its first byte; 0 when invalid. */
    private fun vintLength(first: Byte): Int {
        val v = first.toInt() and 0xFF
        if (v == 0) return 0
        return Integer.numberOfLeadingZeros(v) - 24 + 1
    }

    /**
     * Advances [input] to the first byte of the next Cluster header. False when
     * the stream ends first.
     */
    fun skipToCluster(input: ExtractorInput): Boolean {
        val buffer = ByteArray(CHUNK)
        while (true) {
            input.resetPeekPosition()
            var filled = 0
            // Peek as much as is left (up to a chunk); a short tail still counts.
            while (filled < CHUNK) {
                val n = input.peek(buffer, filled, CHUNK - filled)
                if (n == C.RESULT_END_OF_INPUT || n <= 0) break
                filled += n
            }
            if (filled < CLUSTER_ID.size) return false
            val at = indexOfCluster(buffer, filled)
            if (at >= 0) {
                input.skipFully(at)
                return true
            }
            // Keep the last three bytes: an ID can straddle two chunks.
            val advance = filled - (CLUSTER_ID.size - 1)
            input.skipFully(advance)
        }
    }
}

/**
 * Refines the estimated landing point (#106). A variable-bitrate file puts the
 * byte offset for 2:26 anywhere from a few seconds to a minute away, so after
 * each landing the extractor reads the Cluster's own timecode and, until it
 * is at or just before the target, asks the loader to try a better offset.
 * The player skips frames between the landing and the target, so an early
 * landing is exact; a late one would start the movie after the resume point.
 * Bounded: [MAX_TRIES] reopenings, then the nearest landing at or before the
 * target, else wherever it is.
 */
internal class ClusterSeekSearch(private val targetUs: Long) {

    sealed interface Step {
        data object Accept : Step
        data class SeekTo(val position: Long) : Step
    }

    private var tries = 0
    private var lowPos = -1L
    private var lowUs = 0L
    private var highPos = -1L
    private var highUs = 0L
    private var finalizing = false

    /** [position] is where the Cluster starts and [landedUs] its time; [length]/[durationUs] size the file. */
    fun next(position: Long, landedUs: Long, length: Long, durationUs: Long): Step {
        if (finalizing) return Step.Accept
        tries++
        if (landedUs <= targetUs) {
            if (lowPos < 0 || landedUs > lowUs) { lowPos = position; lowUs = landedUs }
            if (targetUs - landedUs <= ACCEPT_BEFORE_US) return Step.Accept
        } else {
            if (highPos < 0 || landedUs < highUs) { highPos = position; highUs = landedUs }
        }
        val aimUs = (targetUs - AIM_BEFORE_US).coerceAtLeast(0L)
        val next: Long = when {
            lowPos >= 0 && highPos >= 0 -> {
                if (highPos - lowPos < MIN_SPAN_BYTES || highUs <= lowUs) return finish(position)
                val fraction = (aimUs - lowUs).toDouble() / (highUs - lowUs).toDouble()
                lowPos + (fraction.coerceIn(0.0, 1.0) * (highPos - lowPos)).toLong()
            }
            durationUs > 0L && length > 0L -> {
                val bytesPerUs = length.toDouble() / durationUs.toDouble()
                position + ((aimUs - landedUs) * bytesPerUs).toLong()
            }
            else -> return Step.Accept
        }
        if (tries >= MAX_TRIES) return finish(position)
        val bounded = if (lowPos >= 0 && highPos >= 0) next.coerceIn(lowPos + 1, highPos - 1) else next
        val clamped = bounded.coerceIn(1L, (length - TAIL_MARGIN_BYTES).coerceAtLeast(1L))
        if (kotlin.math.abs(clamped - position) < MIN_SPAN_BYTES) return finish(position)
        return Step.SeekTo(clamped)
    }

    /** Out of tries or progress: go back to the best landing at or before the target, if the last one was later. */
    private fun finish(position: Long): Step {
        finalizing = true
        return if (lowPos >= 0 && position != lowPos) Step.SeekTo(lowPos) else Step.Accept
    }

    companion object {
        const val MAX_TRIES = 6
        const val ACCEPT_BEFORE_US = 5_000_000L
        const val AIM_BEFORE_US = 2_500_000L
        const val MIN_SPAN_BYTES = 64L * 1024L
        const val TAIL_MARGIN_BYTES = 256L * 1024L
    }
}

/** Wraps Media3's Matroska extractor in [CuelessMatroskaExtractor]; everything else is untouched. */
internal class CuelessSeekingExtractorsFactory(
    private val delegate: ExtractorsFactory,
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { i ->
            val extractor = extractors[i]
            if (extractor is MatroskaExtractor) CuelessMatroskaExtractor(extractor) else extractor
        }
}
