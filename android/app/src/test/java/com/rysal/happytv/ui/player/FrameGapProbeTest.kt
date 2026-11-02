package com.rysal.happytv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGapProbeTest {

    private var nowMs = 0L
    private val reports = mutableListOf<Long>()
    private val probe = FrameGapProbe(nowNanos = { nowMs * 1_000_000L }, sink = { reports += it })

    private fun frameAt(ms: Long) {
        nowMs = ms
        probe.onFrame()
    }

    @Test
    fun `reports the longest gap in the window once`() {
        frameAt(1_000)
        probe.armForTest() // armed at 1000
        frameAt(1_040)
        frameAt(1_300) // 260 ms gap
        frameAt(1_340)
        frameAt(4_100) // past the window: closes it, and this gap counts
        frameAt(4_140)
        assertEquals(listOf(2_760L), reports)
    }

    @Test
    fun `the gap that straddles the press counts`() {
        frameAt(1_000)
        nowMs = 1_010
        probe.armForTest()
        frameAt(1_200)
        frameAt(4_050)
        assertTrue(reports.single() >= 2_850)
    }

    @Test
    fun `a stall with no further frames is closed by finish`() {
        frameAt(1_000)
        nowMs = 1_010
        val token = probe.armForTest()
        nowMs = 4_100
        probe.finish(token)
        assertEquals(3_100L, reports.single())
    }

    @Test
    fun `frames before arming report nothing`() {
        frameAt(1_000)
        frameAt(1_040)
        assertTrue(reports.isEmpty())
    }
}
