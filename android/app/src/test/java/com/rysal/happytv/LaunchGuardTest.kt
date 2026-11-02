package com.rysal.happytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * F-58: the dashboard's [LaunchGuard] drops a duplicate launch that fires
 * within the same debounce window as the previous one — covering a rapid
 * double-OK from the Chromecast remote, where the second `startActivity`
 * would otherwise stack a second MoviesActivity whose `NavHost` could race
 * the first's `handleDeepLink` against an empty graph and throw
 * `NoSuchElementException: Sequence is empty.`
 *
 * Runs under Robolectric so [ShadowSystemClock.advanceBy] deterministically
 * moves `SystemClock.elapsedRealtime()` without needing a wall-clock sleep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class LaunchGuardTest {

    @Test
    fun `first launch always runs`() {
        val guard = LaunchGuard()
        var count = 0
        guard.tryLaunch { count++ }
        assertEquals(1, count)
    }

    @Test
    fun `immediate relaunch is dropped`() {
        val guard = LaunchGuard()
        var count = 0
        guard.tryLaunch { count++ }          // first tap
        guard.tryLaunch { count++ }          // 150 ms double-OK drops the second
        guard.tryLaunch { count++ }          // a third would also drop
        assertEquals(1, count)
    }

    @Test
    fun `launch after window fires again`() {
        val guard = LaunchGuard()
        var count = 0
        guard.tryLaunch { count++ }
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(LaunchGuard.WINDOW_MILLIS + 5))
        guard.tryLaunch { count++ }
        assertEquals(2, count)
    }

    @Test
    fun `launch just inside window is still dropped`() {
        val guard = LaunchGuard()
        var count = 0
        guard.tryLaunch { count++ }
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(LaunchGuard.WINDOW_MILLIS - 20))
        guard.tryLaunch { count++ }
        assertEquals(1, count)
    }

    @Test
    fun `dropped attempt does not extend debounce window`() {
        val guard = LaunchGuard()
        var count = 0
        guard.tryLaunch { count++ }
        // A dropped attempt must NOT reset the timer — otherwise an unlucky
        // stream of duplicates indefinitely extends the debounce.
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(100))
        guard.tryLaunch { count++ }                                          // dropped
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(LaunchGuard.WINDOW_MILLIS + 5))
        guard.tryLaunch { count++ }
        assertEquals(2, count)
    }

    @Test
    fun `rapid sequence of mixed tile taps holds a single launch`() {
        val guard = LaunchGuard()
        val log = mutableListOf<String>()
        guard.tryLaunch { log += "LiveTv" }
        guard.tryLaunch { log += "Movies" }
        guard.tryLaunch { log += "Shows" }
        guard.tryLaunch { log += "Guide" }
        assertTrue(log == listOf("LiveTv"))
    }
}
