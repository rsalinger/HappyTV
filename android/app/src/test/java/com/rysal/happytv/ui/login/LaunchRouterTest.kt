package com.rysal.happytv.ui.login

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #30: the launch decision, with no Android framework involved. */
class LaunchRouterTest {

    @Test
    fun `complete stored credentials route to the dashboard`() = runBlocking {
        val router = LaunchRouter(credentialsPresent = { true })
        assertEquals(LaunchRoute.Dashboard, router.decide(forceSignIn = false))
    }

    @Test
    fun `no stored credentials route to sign in`() = runBlocking {
        val router = LaunchRouter(credentialsPresent = { false })
        assertEquals(LaunchRoute.SignIn, router.decide(forceSignIn = false))
    }

    @Test
    fun `explicit show-sign-in wins and never reads credentials`() = runBlocking {
        var reads = 0
        val router = LaunchRouter(credentialsPresent = { reads++; true })
        assertEquals(LaunchRoute.SignIn, router.decide(forceSignIn = true))
        assertEquals(0, reads)
    }

    @Test
    fun `credential read runs off the calling thread`() {
        val caller = Thread.currentThread()
        var readThread: Thread? = null
        val router = LaunchRouter(credentialsPresent = {
            readThread = Thread.currentThread()
            true
        })
        runBlocking { router.decide(forceSignIn = false) }
        assertTrue("the credential check never ran", readThread != null)
        assertNotEquals("credential check ran on the calling thread", caller, readThread)
    }

    @Test
    fun `a failing credential read falls back to sign in`() = runBlocking {
        val router = LaunchRouter(credentialsPresent = { error("keystore unavailable") })
        assertEquals(LaunchRoute.SignIn, router.decide(forceSignIn = false))
    }

    @Test
    fun `a read that overruns the splash cap falls back to sign in`() = runBlocking {
        val never = CompletableDeferred<Boolean>()
        val router = LaunchRouter(
            credentialsPresent = { never.await() },
            io = Dispatchers.IO,
            timeoutMs = 50,
        )
        val start = System.nanoTime()
        assertEquals(LaunchRoute.SignIn, router.decide(forceSignIn = false))
        val tookMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("decide() should return near the cap, took ${tookMs}ms", tookMs < 5_000)
        never.cancel()
    }

    @Test
    fun `the splash cap is ten seconds`() {
        assertEquals(10_000L, LaunchRouter.SPLASH_CAP_MS)
    }
}
