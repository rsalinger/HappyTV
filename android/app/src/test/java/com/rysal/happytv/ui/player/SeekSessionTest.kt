package com.rysal.happytv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #102: seek-step acceleration and burst coalescing, with an injected clock. */
class SeekSessionTest {

    private val twoHours = 2 * 3_600_000L

    @Test
    fun stepsAccelerateTenThirtySixtyWithHoldTime() {
        assertEquals(10_000L, SeekSession.stepMillis(0))
        assertEquals(10_000L, SeekSession.stepMillis(1_999))
        assertEquals(30_000L, SeekSession.stepMillis(2_000))
        assertEquals(30_000L, SeekSession.stepMillis(3_999))
        assertEquals(60_000L, SeekSession.stepMillis(4_000))
        assertEquals(60_000L, SeekSession.stepMillis(60_000))
    }

    @Test
    fun aSingleTapIsTenSeconds() {
        assertEquals(70_000L, SeekSession().press(1, 0, 0, 60_000, twoHours))
        assertEquals(10_000L, SeekSession().press(-1, 0, 0, 20_000, twoHours))
    }

    @Test
    fun tapsAccumulateIntoOneCommit() {
        val s = SeekSession()
        var t = 0L
        repeat(5) { s.press(1, 0, t, 100_000, twoHours); t += 120 }
        assertFalse("still inside the idle window", s.isIdle(t))
        assertTrue(s.isIdle(t + 700))
        val c = s.commit(t + 700)!!
        assertEquals(150_000L, c.targetMillis)
        assertEquals(5, c.presses)
        assertNull("a second commit is a no-op, so one seek per burst", s.commit(t + 401))
    }

    @Test
    fun holdingAcceleratesAndStaysInOneBurst() {
        val s = SeekSession()
        var target = 0L
        // First down at 0, key repeats every 50 ms from 500 ms (Android's default).
        s.press(1, 0, 0, 0, twoHours)
        var t = 500L
        var repeat = 1
        while (t < 6_000L) {
            target = s.press(1, repeat++, t, 0, twoHours)
            t += 50
        }
        assertTrue(s.held)
        // First press 10 s; repeats at 500..1950 ms (30) are 10 s, 2000..3950 ms (40) are 30 s,
        // 4000..5950 ms (40) are 60 s.
        assertEquals(10_000L + 30 * 10_000L + 40 * 30_000L + 40 * 60_000L, target)
        val c = s.commit(t)!!
        assertEquals(target, c.targetMillis)
        assertFalse(s.held)
    }

    @Test
    fun aHeldRightKeyCrossesTwoHoursInUnderTenSeconds() {
        val s = SeekSession()
        s.press(1, 0, 0, 0, twoHours)
        var t = 500L
        var repeat = 1
        var target = 0L
        while (target < twoHours - 60_000L && t < 20_000L) {
            target = s.press(1, repeat++, t, 0, twoHours)
            t += 50
        }
        assertTrue("reached the last minute at ${t}ms", t < 10_000L)
    }

    @Test
    fun reversingDirectionRestartsTheRunAtTenSeconds() {
        val s = SeekSession()
        s.press(1, 0, 0, 100_000, twoHours)
        for (i in 1..100) s.press(1, i, 500L + i * 50L, 100_000, twoHours) // deep into the coarse tier
        val before = s.pendingTargetMillis!!
        val after = s.press(-1, 0, 6_000, 100_000, twoHours)
        assertEquals(before - 10_000L, after)
    }

    @Test
    fun targetsClampToTheMovie() {
        assertEquals(0L, SeekSession().press(-1, 0, 0, 4_000, twoHours))
        assertEquals(twoHours, SeekSession().press(1, 0, 0, twoHours - 3_000, twoHours))
    }

    @Test
    fun unknownDurationOnlyClampsAtZero() {
        assertEquals(70_000L, SeekSession().press(1, 0, 0, 60_000, 0))
        assertEquals(0L, SeekSession().press(-1, 0, 0, 5_000, 0))
    }

    @Test
    fun aPressRightAfterACommitContinuesFromItsTargetNotTheStalePosition() {
        val s = SeekSession()
        s.press(1, 0, 0, 100_000, twoHours)
        s.commit(400)
        assertTrue(s.isSettling(500))
        // The player still reports 100 s; the next press builds on the committed 110 s.
        assertEquals(120_000L, s.press(1, 0, 600, 100_000, twoHours))
        s.commit(1_000)
        // Long after, the player's own position wins again.
        assertFalse(s.isSettling(5_000))
        assertEquals(310_000L, s.press(1, 0, 5_000, 300_000, twoHours))
    }

    @Test
    fun theIdleWindowOutlastsAndroidsKeyRepeatDelay() {
        // QA on #103: a 400 ms window committed the first press of a hold before
        // the first repeat (500 ms) arrived.
        assertTrue(SeekSession.IDLE_COMMIT_MILLIS > 500L)
        val s = SeekSession()
        s.press(1, 0, 0, 0, twoHours)
        assertFalse("not idle when the first repeat arrives", s.isIdle(500))
        s.press(1, 1, 500, 0, twoHours)
        assertFalse(s.isIdle(550))
        assertTrue(s.held)
    }

    @Test
    fun nothingIsIdleWithoutAPress() {
        val s = SeekSession()
        assertFalse(s.isIdle(10_000))
        assertNull(s.commit(0))
    }
}
