package com.rysal.happytv.data.guide

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rysal.happytv.data.Graph
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The guide's background refresh (task body §6 — the headline rename of
 * this task: was `RefreshEpgWorker`).
 *
 * Two paths, tried in order (F-47 rework — the old gating on
 * `isXmltvGuideEnabled(settings.epgUrl)` never reached the derivation
 * path on a fresh sign-in, because `epgUrl` is blank until the first
 * successful XMLTV store):
 *
 *  1. **XMLTV** (`fetchAndStoreXmlTv`): handles both an explicit
 *     `settings.epgUrl` AND the F-47/F-52 auto-derivation from saved
 *     Xtream credentials. Returns `true` when rows were written, `false`
 *     when no URL is available or the server said "not modified".
 *  2. **Provider-side** (`refreshProviderGuides`): fallback when XMLTV
 *     is unavailable — per-channel `get_short_epg` for favorites + recents
 *     + catalog head, bounded to [PROVIDER_MAX_CHANNELS].
 *
 * Scheduled as `PeriodicWorkRequestBuilder<RefreshGuideWorker>(
 * intervalHours, TimeUnit.HOURS)` by [RefreshGuideScheduler]; the
 * interval comes from `settings.epgRefreshHours` (default 6).
 */
class RefreshGuideWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /**
     * Below API 31 WorkManager runs expedited work as a foreground service
     * and calls this; the [CoroutineWorker] default throws "Not implemented"
     * and crash-loops the process (#200). Requests already persisted as
     * expedited by older builds hit this too, so the worker must answer it
     * no matter how it was enqueued. The notification is quiet (low
     * importance, no sound, not shown on the lock screen).
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(applicationContext)

    override suspend fun doWork(): Result {
        Log.i(TAG, "doWork started (attempt $runAttemptCount)")
        try {
            Graph.init(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Graph.init failed", e)
            return Result.failure()
        }

        return try {
            val repository = Graph.guideRepository

            // Always try XMLTV first — it handles both explicit URLs and
            // the F-47/F-52 auto-derivation. Returns false when there is
            // nothing to fetch (no URL configured or derivable) or the
            // server said "not modified".
            val stored = repository.fetchAndStoreXmlTv()
            if (stored) {
                Log.i(TAG, "XMLTV refresh done (stored=true)")
                Result.success()
            } else {
                // No XMLTV available — fall back to the provider-side
                // per-channel refresh so the Live TV rail still gets
                // now-titles even without a panel XMLTV endpoint.
                val refreshed = repository.refreshProviderGuides(PROVIDER_MAX_CHANNELS)
                Log.i(TAG, "provider guide refresh done ($refreshed channels, cap $PROVIDER_MAX_CHANNELS)")
                Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "guide refresh failed on attempt $runAttemptCount", e)
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val TAG = "RefreshGuideWorker"
        const val NOTIFICATION_CHANNEL_ID = "guide_refresh"
        const val NOTIFICATION_ID = 4101

        internal fun foregroundInfo(context: Context): ForegroundInfo {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 &&
                manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null
            ) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Guide updates",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = Notification.VISIBILITY_SECRET
                    },
                )
            }
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Updating the guide")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .build()
            return if (Build.VERSION.SDK_INT >= 29) {
                ForegroundInfo(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        }
        const val MAX_ATTEMPTS = 3

        /** Task §6: provider-side path refreshes at most this many channels. */
        const val PROVIDER_MAX_CHANNELS = 25
    }
}

/**
 * Deprecated alias for [RefreshGuideWorker], kept for one release in
 * case an old test still references the pre-rename class. It is a
 * **compile-time** alias only: WorkManager has only ever registered
 * `RefreshGuideWorker`, so no stored work can name this type.
 */
@Deprecated(
    "Renamed to RefreshGuideWorker (UI label is Guide; the wire endpoint stays get_short_epg).",
    ReplaceWith("RefreshGuideWorker"),
)
typealias RefreshEpgWorker = RefreshGuideWorker

/**
 * WorkManager wiring for [RefreshGuideWorker].
 *
 *  - [ensureScheduled] — called once per process from
 *    `HappyTvApp.onCreate`: enqueues the periodic run with
 *    `ExistingPeriodicWorkPolicy.KEEP` so opening the app never resets
 *    the refresh timer (a REPLACE on every start would starve the
 *    refresh if the user opens the app more often than the interval).
 *  - [reschedule] — for Settings when "Update the guide every" changes:
 *    REPLACE applies the new interval immediately.
 *  - [refreshNow] — the manual trigger (Settings' "Refresh everything
 *    now" confirmation and any in-app manual refresh): a one-shot run
 *    alongside the periodic schedule.
 */
object RefreshGuideScheduler {

    /** Unique name of the periodic guide refresh. */
    const val UNIQUE_PERIODIC = "refresh_guide"

    /** Unique name of the manual one-shot refresh. */
    const val UNIQUE_ONESHOT = "refresh_guide_now"

    private const val TAG = "RefreshGuideScheduler"

    /** A guide imported within this window is not re-imported on a cold start (#35). */
    const val STARTUP_GUIDE_REUSE_MS = 3L * 60 * 60 * 1000

    /** Startup scope — DataStore reads off the main thread in onCreate. */
    private val startupScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    /** Enqueue the periodic run if it isn't already scheduled (KEEP preserves the timer). */
    fun ensureScheduled(context: Context, intervalHours: Int) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest(intervalHours),
        )
    }

    /** Apply a new interval now (Settings changed "Update the guide every"). */
    fun reschedule(context: Context, intervalHours: Int) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.REPLACE,
            periodicRequest(intervalHours),
        )
    }

    /** Manual refresh trigger — runs [RefreshGuideWorker] once, now. */
    fun refreshNow(context: Context) {
        Log.i(TAG, "refreshNow: enqueuing one-shot guide refresh")
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONESHOT,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RefreshGuideWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setExpedited(
                        androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
                    )
                    .build(),
            )
            Log.i(TAG, "refreshNow: one-shot enqueued OK")
        } catch (e: Exception) {
            Log.e(TAG, "refreshNow: failed to enqueue one-shot", e)
        }
    }

    /**
     * Schedules from the stored `settings.epgRefreshHours` — startup path.
     * DataStore needs a coroutine (never block Application.onCreate);
     * failures are logged, not fatal (the next app start retries, and
     * the guide still refreshes on demand).
     */
    fun ensureScheduledFromSettings(context: Context) {
        val appContext = context.applicationContext
        startupScope.launch {
            try {
                val hours = Graph.settings.settings.first().epgRefreshHours
                ensureScheduled(appContext, hours)
            } catch (e: Exception) {
                Log.w(TAG, "could not schedule the guide refresh: ${e.message}")
            }
        }
    }

    /**
     * The canonical "post-sign-in" trigger — fires the one-shot guide
     * refresh iff there is a stored credential set (i.e. the user is signed
     * in, no matter which entry path got them here: the Login UI, the
         * debug `adb shell am broadcast INJECT_CREDENTIALS` shortcut, or a
     * cold launch straight to MainActivity with a cached session).
     *
     * **F-47 follow-up.** `LoginActivity.LaunchedEffect(isAuthenticated)`
     * still fires `refreshNow` directly on the UI flow; this method is
     * the **entry-point-agnostic** path so the cached-session cold launch
     * also gets a one-shot kick. Idempotent: WorkManager's
     * `enqueueUniqueWork(UNIQUE_ONESHOT, REPLACE)` means the call from
     * LoginActivity and this one merge into one job, never two.
     *
     * Runs on [startupScope] (Dispatchers.Default — DataStore is off the
     * main thread; the subsequent `refreshNow` is a thin WorkManager
     * enqueue that itself bounces to IO inside WM).
     */
    fun kickPostSignIn(context: Context) {
        val appContext = context.applicationContext
        startupScope.launch {
            val signedIn = try {
                Graph.securedCredentials.isPresent()
            } catch (e: Exception) {
                Log.w(TAG, "kickPostSignIn: credential check failed: ${e.message}")
                false
            }
            if (signedIn) {
                // #35: the one-shot used to run a full guide import on every
                // process start. The import's promotion blocks the database
                // for 30 s+ on the Chromecast HD, so a guide imported in the
                // last few hours is reused; the periodic worker, the sign-in
                // path and Settings' manual refresh still refresh it.
                val fresh = try {
                    Graph.guideRepository.isGuideFresh(STARTUP_GUIDE_REUSE_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "kickPostSignIn: freshness check failed: ${e.message}")
                    false
                }
                if (fresh) {
                    Log.i(TAG, "kickPostSignIn: guide imported recently → skipping one-shot")
                } else {
                    Log.i(TAG, "kickPostSignIn: credentials present → enqueueing one-shot")
                    refreshNow(appContext)
                }
            } else {
                Log.i(TAG, "kickPostSignIn: no stored credentials → skipping one-shot")
            }
        }
    }

    /**
     * The request itself, extracted for tests:
     * `PeriodicWorkRequestBuilder<RefreshGuideWorker>(intervalHours, HOURS)`.
     * Network is required — an offline run would only burn a retry.
     */
    fun periodicRequest(intervalHours: Int) =
        PeriodicWorkRequestBuilder<RefreshGuideWorker>(
            intervalHours.coerceAtLeast(1).toLong(),
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
}
