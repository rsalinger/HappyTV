@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.rysal.happytv.ui.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.ExtractorsFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #106: a Matroska file without Cues is unseekable to Media3, so a resume restarted at 0:00.
 * These pin the substitute seek map, the Cluster resync and the landing search.
 */
class CuelessMatroskaSeekingTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** A Cluster header as the provider's files write it: ID, size, CRC-32, Timecode (3 bytes). */
    private val realHeader = hex("1f43b6751029b917bf8476e43124e783041aab" + "00".repeat(20))

    @Test
    fun `reads the timecode after a CRC-32 element`() {
        // 0x041aab = 268971 ms
        assertEquals(268_971_000L, ClusterResync.clusterTimeUs(realHeader))
    }

    @Test
    fun `reads a timecode that comes first`() {
        val header = hex("1f43b675" + "8f" + "e7" + "82" + "1388" + "00".repeat(20))
        assertEquals(5_000_000L, ClusterResync.clusterTimeUs(header))
    }

    @Test
    fun `an unreadable header gives no time instead of a wrong one`() {
        // The first child is a SimpleBlock, not a Timecode.
        assertNull(ClusterResync.clusterTimeUs(hex("1f43b675" + "8f" + "a3" + "82" + "0000" + "00".repeat(20))))
        // A zero size byte is not a valid EBML integer.
        assertNull(ClusterResync.clusterTimeUs(hex("1f43b675" + "00" + "00".repeat(30))))
    }

    @Test
    fun `finds the cluster id and none in plain data`() {
        val data = ByteArray(100) { 0x11 }
        assertEquals(-1, ClusterResync.indexOfCluster(data, data.size))
        hex("1f43b675").copyInto(data, 40)
        assertEquals(40, ClusterResync.indexOfCluster(data, data.size))
        // Only counts bytes up to the length given.
        assertEquals(-1, ClusterResync.indexOfCluster(data, 43))
    }

    @Test
    fun `skipToCluster advances the input to the next cluster header`() {
        val bytes = ByteArray(200_000) { 0x22 }
        realHeader.copyInto(bytes, 150_001) // beyond the first 64 KB chunk
        val input = input(bytes, 1_000L)
        assertTrue(ClusterResync.skipToCluster(input))
        assertEquals(1_000L + 150_001L, input.position)
    }

    @Test
    fun `skipToCluster finds an id that straddles two chunks`() {
        val bytes = ByteArray(200_000) { 0x22 }
        hex("1f43b675").copyInto(bytes, 65_534) // 0..65535 is the first chunk
        val input = input(bytes, 0L)
        assertTrue(ClusterResync.skipToCluster(input))
        assertEquals(65_534L, input.position) // the input started at 0
    }

    @Test
    fun `skipToCluster reports the end of the stream`() {
        val input = input(ByteArray(10_000) { 0x22 }, 0L)
        assertFalse(ClusterResync.skipToCluster(input))
    }

    // ------------------------------------------------------------ seek map

    @Test
    fun `the estimated seek map is linear and clamped`() {
        val map = EstimatedSeekMap.orNull(durationUs = 400_000_000L, lengthBytes = 400_000_000L)!!
        assertTrue(map.isSeekable)
        assertEquals(400_000_000L, map.durationUs)
        val half = map.getSeekPoints(200_000_000L).first
        assertEquals(200_000_000L, half.timeUs)
        assertEquals(200_000_000L, half.position)
        assertEquals(0L, map.getSeekPoints(0L).first.position)
        // Past the end stays short of the last bytes.
        assertTrue(map.getSeekPoints(999_000_000L).first.position < 400_000_000L)
    }

    @Test
    fun `no estimate without a duration or a length`() {
        assertNull(EstimatedSeekMap.orNull(C.TIME_UNSET, 1_000L))
        assertNull(EstimatedSeekMap.orNull(1_000_000L, C.LENGTH_UNSET.toLong()))
    }

    // ----------------------------------------------------------- the search

    /** A file whose bytes per second vary 5x, so a linear guess is well off. */
    private class VbrFile(val length: Long, val durationUs: Long) {
        // time grows faster early in the file than late (a quiet intro, a busy end).
        fun timeAt(pos: Long): Long {
            val f = pos.toDouble() / length
            return (durationUs * Math.pow(f, 1.6)).toLong()
        }
    }

    private fun runSearch(file: VbrFile, targetUs: Long): Pair<Long, Int> {
        val search = ClusterSeekSearch(targetUs)
        var pos = EstimatedSeekMap.orNull(file.durationUs, file.length)!!.getSeekPoints(targetUs).first.position
        var requests = 1
        while (true) {
            val landed = file.timeAt(pos)
            when (val step = search.next(pos, landed, file.length, file.durationUs)) {
                ClusterSeekSearch.Step.Accept -> return landed to requests
                is ClusterSeekSearch.Step.SeekTo -> { pos = step.position; requests++ }
            }
            check(requests < 20) { "search did not settle" }
        }
    }

    @Test
    fun `the search lands at or just before the target on a variable bitrate file`() {
        val file = VbrFile(length = 273_000_000L, durationUs = 451_000_000L)
        for (targetS in listOf(30L, 106L, 254L, 400L)) {
            val target = targetS * 1_000_000L
            val (landed, requests) = runSearch(file, target)
            assertTrue("landed $landed after target $target", landed <= target)
            assertTrue("landed ${target - landed} us early", target - landed <= ClusterSeekSearch.ACCEPT_BEFORE_US)
            assertTrue("took $requests requests", requests <= ClusterSeekSearch.MAX_TRIES + 1)
        }
    }

    @Test
    fun `the search gives up in bounded tries when the clock never lands near the target`() {
        // A file whose clusters always claim a time far past the target (a timecode scale we misread).
        val search = ClusterSeekSearch(60_000_000L)
        var pos = 100_000_000L
        var requests = 1
        var last: ClusterSeekSearch.Step
        while (true) {
            last = search.next(pos, 1_000_000_000L, 500_000_000L, 500_000_000L)
            if (last is ClusterSeekSearch.Step.SeekTo && requests < 30) { pos = last.position; requests++ } else break
        }
        assertTrue("stopped after $requests requests", requests <= ClusterSeekSearch.MAX_TRIES + 1)
        assertEquals(ClusterSeekSearch.Step.Accept, last)
    }

    // ----------------------------------------------------------- extractor

    /** An inner extractor that, once read, reports an unseekable map with a duration. */
    private class FakeInner(private val durationUs: Long) : Extractor {
        var output: ExtractorOutput? = null
        val seeks = mutableListOf<Pair<Long, Long>>()
        val readsAt = mutableListOf<Long>()
        override fun sniff(input: ExtractorInput) = true
        override fun init(output: ExtractorOutput) { this.output = output }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            readsAt += input.position
            if (readsAt.size == 1) output!!.seekMap(SeekMap.Unseekable(durationUs))
            return Extractor.RESULT_CONTINUE
        }
        override fun seek(position: Long, timeUs: Long) { seeks += position to timeUs }
        override fun release() {}
    }

    private class CapturingOutput : ExtractorOutput {
        var map: SeekMap? = null
        override fun track(id: Int, type: Int) = throw UnsupportedOperationException()
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }

    @Test
    fun `an unseekable map with a known length becomes seekable`() {
        val inner = FakeInner(451_000_000L)
        val extractor = CuelessMatroskaExtractor(inner)
        val out = CapturingOutput()
        extractor.init(out)

        extractor.read(input(ByteArray(1_000), 0L), PositionHolder())

        assertTrue(out.map!!.isSeekable)
        assertEquals(451_000_000L, out.map!!.durationUs)
    }

    @Test
    fun `an unseekable map stays unseekable when the length is unknown`() {
        val inner = FakeInner(451_000_000L)
        val extractor = CuelessMatroskaExtractor(inner)
        val out = CapturingOutput()
        extractor.init(out)

        extractor.read(input(ByteArray(1_000), 0L, length = C.LENGTH_UNSET.toLong()), PositionHolder())

        assertFalse(out.map!!.isSeekable)
    }

    @Test
    fun `a seek past the start resyncs to a cluster and reads from there`() {
        val inner = FakeInner(451_000_000L)
        val extractor = CuelessMatroskaExtractor(inner)
        val out = CapturingOutput()
        extractor.init(out)
        extractor.read(input(ByteArray(1_000), 0L), PositionHolder()) // learns the map

        // Landing at 268.971 s for a 250 s target is late: it asks the loader to try an earlier offset.
        val bytes = ByteArray(300_000) { 0x33 }
        realHeader.copyInto(bytes, 5_000)
        extractor.seek(150_000_000L, 250_000_000L)
        val holder = PositionHolder()
        val result = extractor.read(input(bytes, 150_000_000L, length = 273_000_000L), holder)

        assertEquals(Extractor.RESULT_SEEK, result)
        assertTrue("moved earlier than the landing", holder.position in 1L until 150_005_000L)
        assertEquals("the inner extractor is not fed the bad landing", 1, inner.readsAt.size)
    }

    @Test
    fun `a seek that lands before the target hands the cluster to the inner extractor`() {
        val inner = FakeInner(451_000_000L)
        val extractor = CuelessMatroskaExtractor(inner)
        extractor.init(CapturingOutput())
        extractor.read(input(ByteArray(1_000), 0L), PositionHolder())

        val bytes = ByteArray(300_000) { 0x33 }
        realHeader.copyInto(bytes, 5_000) // 268.971 s
        extractor.seek(150_000_000L, 270_000_000L)
        val result = extractor.read(input(bytes, 150_000_000L, length = 273_000_000L), PositionHolder())

        assertEquals(Extractor.RESULT_CONTINUE, result)
        assertEquals(listOf(0L, 150_005_000L), inner.readsAt)
        assertEquals(listOf(150_000_000L to 270_000_000L), inner.seeks)
    }

    @Test
    fun `a restart at position zero is not resynced`() {
        val inner = FakeInner(451_000_000L)
        val extractor = CuelessMatroskaExtractor(inner)
        extractor.init(CapturingOutput())
        extractor.read(input(ByteArray(1_000), 0L), PositionHolder())

        extractor.seek(0L, 0L)
        extractor.read(input(ByteArray(1_000) { 0x44 }, 0L), PositionHolder())

        assertEquals(listOf(0L, 0L), inner.readsAt)
    }

    @Test
    fun `only the matroska extractor is wrapped`() {
        val other = FakeInner(0L)
        val matroska = MatroskaExtractor()
        val delegate = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> = arrayOf(other, matroska)
        }

        val wrapped = CuelessSeekingExtractorsFactory(delegate).createExtractors()

        assertEquals(2, wrapped.size)
        assertSame(other, wrapped[0])
        assertTrue(wrapped[1] is CuelessMatroskaExtractor)
        assertSame(matroska, wrapped[1].underlyingImplementation)
    }

    // -------------------------------------------------------------- helpers

    /** An input over [bytes] whose first readable byte is at absolute offset [position]. */
    private fun input(bytes: ByteArray, position: Long, length: Long = bytes.size.toLong()): ExtractorInput {
        var cursor = 0
        val reader = DataReader { buffer, offset, len ->
            if (cursor >= bytes.size) {
                C.RESULT_END_OF_INPUT
            } else {
                val n = minOf(len, bytes.size - cursor)
                System.arraycopy(bytes, cursor, buffer, offset, n)
                cursor += n
                n
            }
        }
        return DefaultExtractorInput(reader, position, length)
    }
}
