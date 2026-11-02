package com.rysal.happytv.ui.login

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** Where a launcher start should land. */
internal enum class LaunchRoute {
    /** A complete credential set is stored: open the dashboard, skip sign-in. */
    Dashboard,

    /** No usable stored credentials (or sign-in was requested explicitly). */
    SignIn,
}

/**
 * Launch routing (issue #30; design spec §"Splash": sign-in is "skipped entirely
 * once the user has signed in before (the app opens on the dashboard)").
 *
 * The decision uses **stored credentials only**; it never talks to the
 * network. The credential read (Keystore-backed EncryptedSharedPreferences,
 * slow on a cold Chromecast) always runs on [io], never on the caller's
 * thread. The caller (the launcher activity) holds the system splash on
 * screen while [decide] runs.
 *
 * Fail-safe: a missing set, a read error or a read that exceeds [timeoutMs]
 * (design spec: the splash "falls through to sign-in after 10 seconds at most")
 * all route to [LaunchRoute.SignIn].
 */
internal class LaunchRouter(
    private val credentialsPresent: suspend () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = SPLASH_CAP_MS,
) {
    /**
     * @param forceSignIn the launching intent explicitly asked for the sign-in
     * screen ([LoginActivity.EXTRA_SHOW_SIGN_IN]); no credential check runs.
     */
    suspend fun decide(forceSignIn: Boolean): LaunchRoute {
        if (forceSignIn) return LaunchRoute.SignIn
        // A detached scope on purpose: a blocking read that overruns the
        // timeout must not hold this function (structured concurrency would
        // wait for the child), it is simply abandoned.
        val check = CoroutineScope(SupervisorJob() + io).async { credentialsPresent() }
        return try {
            if (withTimeoutOrNull(timeoutMs) { check.await() } == true) {
                LaunchRoute.Dashboard
            } else {
                LaunchRoute.SignIn
            }
        } catch (e: CancellationException) {
            check.cancel()
            throw e
        } catch (t: Throwable) {
            // Never log the exception message: it can echo preference keys.
            Log.w(TAG, "credential check failed (${t.javaClass.simpleName}); showing sign-in")
            LaunchRoute.SignIn
        }
    }

    companion object {
        private const val TAG = "LaunchRouter"

        /** design spec §"Splash": at most 10 seconds before falling through to sign-in. */
        const val SPLASH_CAP_MS = 10_000L
    }
}
