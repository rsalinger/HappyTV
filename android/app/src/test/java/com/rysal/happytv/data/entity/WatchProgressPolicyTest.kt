package com.rysal.happytv.data.entity

import com.rysal.happytv.data.entity.WatchProgressPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The finished rule (#74): min(5 minutes, 10% of the length) left, or past 95%. */
class WatchProgressPolicyTest {

    private val min = 60_000L

    @Test
    fun `a 22 minute episode is not finished at minute 17`() {
        val length = 22 * min
        assertEquals(Decision.SAVE, WatchProgressPolicy.decide(17 * min, length))
        assertFalse(WatchProgressPolicy.isFinished(17 * min, length))
    }

    @Test
    fun `a 22 minute episode is finished with 10 percent left`() {
        val length = 22 * min
        // 10% of 22 minutes is 2.2 minutes: 2:12 left is the edge.
        val edge = length - (length * 0.10).toLong()
        assertTrue(WatchProgressPolicy.isFinished(edge, length))
        assertFalse(WatchProgressPolicy.isFinished(edge - 1, length))
        assertEquals(Decision.FINISHED, WatchProgressPolicy.decide(edge, length))
    }

    @Test
    fun `a long title still uses the five minute cap`() {
        // 60 minutes: 5 minutes left is 91.7%, so only the cap can make it finished.
        val length = 60 * min
        assertFalse(WatchProgressPolicy.isFinished(length - 5 * min - 1, length))
        assertTrue(WatchProgressPolicy.isFinished(length - 5 * min, length))
    }

    @Test
    fun `a 50 minute show crosses from the fraction to the cap at 5 minutes`() {
        val length = 50 * min
        // 10% is exactly 5 minutes: both bounds agree.
        assertFalse(WatchProgressPolicy.isFinished(length - 5 * min - 1, length))
        assertTrue(WatchProgressPolicy.isFinished(length - 5 * min, length))
    }

    @Test
    fun `past 95 percent is finished and an unknown length never is`() {
        assertTrue(WatchProgressPolicy.isFinished(951, 1000))
        assertFalse(WatchProgressPolicy.isFinished(10 * 60 * min, 0L))
    }

    @Test
    fun `under a minute is still ignored`() {
        assertEquals(Decision.IGNORE, WatchProgressPolicy.decide(59_999L, 22 * min))
        // A 5 minute clip played to the end is finished, not ignored, once past a minute.
        assertEquals(Decision.FINISHED, WatchProgressPolicy.decide(299_000L, 5 * min))
    }
}
