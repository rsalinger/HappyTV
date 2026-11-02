package com.rysal.happytv.data.guide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #35: a cold start reuses a recently imported guide instead of re-importing it. */
class GuideStartupReuseTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_000_000_000_000L
    private val window = RefreshGuideScheduler.STARTUP_GUIDE_REUSE_MS

    @Test
    fun `a guide imported an hour ago with rows is reused`() {
        assertTrue(isGuideReusable(now - hour, rowCount = 369_000, maxAgeMs = window, nowMillis = now))
    }

    @Test
    fun `a guide older than the window is imported again`() {
        assertFalse(isGuideReusable(now - window - 1, rowCount = 369_000, maxAgeMs = window, nowMillis = now))
    }

    @Test
    fun `a guide never imported is imported`() {
        assertFalse(isGuideReusable(0L, rowCount = 369_000, maxAgeMs = window, nowMillis = now))
    }

    @Test
    fun `an emptied store is imported even when the stamp is recent`() {
        assertFalse(isGuideReusable(now - hour, rowCount = 0, maxAgeMs = window, nowMillis = now))
    }

    @Test
    fun `a stamp in the future is treated as stale`() {
        assertFalse(isGuideReusable(now + hour, rowCount = 10, maxAgeMs = window, nowMillis = now))
    }

    @Test
    fun `the cold start window is three hours`() {
        assertTrue(window == 3 * hour)
    }
}
