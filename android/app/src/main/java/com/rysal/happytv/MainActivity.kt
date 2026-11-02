package com.rysal.happytv

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.ui.browse.LiveTvActivity
import com.rysal.happytv.ui.browse.MoviesActivity
import com.rysal.happytv.ui.browse.ShowsActivity
import com.rysal.happytv.ui.login.LoginActivity
import com.rysal.happytv.ui.main.DashboardScreen
import com.rysal.happytv.ui.search.SearchActivity
import com.rysal.happytv.ui.search.SearchScope
import com.rysal.happytv.ui.settings.SettingsActivity

/**
 * Main host: the dashboard. Launched after a successful sign-in.
 *
 * Tile taps dispatch into the matching browse activity
 * (see [com.rysal.happytv.ui.browse]). The welcome strip's "Sign in" returns
 * to [LoginActivity] when signed out. TV guide, Settings and the Search
 * overlay each open their own screen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // F-58: each tile click fires straight `startActivity`. A rapid
        // double-press (or the IME-evicted first key + the late second key)
        // produced a duplicate MoviesActivity where the second instance's
        // `NavHost` ran `findStartDestination` against an empty graph and
        // threw `NoSuchElementException: Sequence is empty.` The catalog
        // activities are also `singleTop` in the manifest; this guard is the
        // even-earlier defense — any launcher routed through this activity
        // that fired in the last [LaunchGuard.WINDOW_MILLIS] is swallowed.
        val guard = LaunchGuard()
        setContent {
            DashboardScreen(
                onOpenLiveTv = {
                    guard.tryLaunch {
                        HtvPerf.start(HtvPerf.LIVETV_OPEN)
                        startActivity(LiveTvActivity.liveTvIntent(this))
                    }
                },
                onOpenMovies = {
                    guard.tryLaunch {
                        HtvPerf.start(HtvPerf.CATALOG_OPEN)
                        startActivity(Intent(this, MoviesActivity::class.java))
                    }
                },
                onOpenShows = {
                    guard.tryLaunch {
                        HtvPerf.start(HtvPerf.CATALOG_OPEN)
                        startActivity(Intent(this, ShowsActivity::class.java))
                    }
                },
                onOpenTvGuide = {
                    // The TV guide tile and the Live TV tile open the same screen; this
                    // one always asks for the guide view (spec #7 section 3.6).
                    guard.tryLaunch {
                        HtvPerf.start(HtvPerf.GUIDE_OPEN)
                        startActivity(LiveTvActivity.guideIntent(this))
                    }
                },
                onOpenSettings = {
                    guard.tryLaunch { startActivity(Intent(this, SettingsActivity::class.java)) }
                },
                onOpenSearch = {
                    guard.tryLaunch {
                        startActivity(
                            Intent(this, SearchActivity::class.java).apply {
                                // From the dashboard, Search covers everything.
                                putExtra(SearchActivity.EXTRA_SCOPE, SearchScope.ALL.name)
                            },
                        )
                    }
                },
                onOpenSignIn = {
                    guard.tryLaunch { startActivity(LoginActivity.signInIntent(this)) }
                },
            )
        }
    }
}

/**
 * Drops duplicate launches that land within [WINDOW_MILLIS] of the previous
 * one. A double-press on a TV tile fires `onClick` twice in <100 ms; without
 * this the system sees two `START` events in a row and (depending on the
 * destination's launch mode) either stacks the activity or delivers a fast
 * second Intent the host activity's `onCreate` cannot finish composing before
 * the next one lands.
 *
 * Kept tiny on purpose: no coroutines, no state machines, just an elapsed-
 * realtime comparison. Lives in MainActivity so it scopes to the dashboard's
 * lifetime — exiting the dashboard resets the debounce naturally.
 *
 * `lastLaunchAt` is initialised to a value deep in the past so the very first
 * tap is treated as "never launched" even when [SystemClock.elapsedRealtime]
 * starts at 0 on Robolectric / fresh-device boots. A dropped attempt must
 * not update the timestamp — otherwise an unlucky stream of duplicates
 * indefinitely extends the debounce window.
 */
internal class LaunchGuard {
    private var lastLaunchAt: Long = NEVER_LAUNCHED
    private var launchedAtLeastOnce = false

    /** Runs [block] only when the previous launch is older than [WINDOW_MILLIS]. */
    fun tryLaunch(block: () -> Unit) {
        if (launchedAtLeastOnce && SystemClock.elapsedRealtime() - lastLaunchAt < WINDOW_MILLIS) return
        lastLaunchAt = SystemClock.elapsedRealtime()
        launchedAtLeastOnce = true
        block()
    }

    companion object {
        /**
         * TV remotes fire a normal click in roughly the same window a double-press
         * travels; 400 ms catches the second of two presses without throttling
         * any deliberate second click (back, retry, etc.). One short press
         * within this window's far edge (~300 ms apart) still goes through.
         */
        const val WINDOW_MILLIS = 400L

        /** Sentinel "never launched" — see [tryLaunch] for why a magic value is safest. */
        private const val NEVER_LAUNCHED = Long.MIN_VALUE / 2
    }
}
