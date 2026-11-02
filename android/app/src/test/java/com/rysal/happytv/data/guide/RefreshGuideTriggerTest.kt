package com.rysal.happytv.data.guide

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.rysal.happytv.data.Graph
import com.rysal.happytv.security.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-47 follow-up: the canonical post-sign-in trigger actually wires up.
 *
 * QA at `release/v1-candidate @ 4c80923` found the guide empty on the
 * Chromecast HD with **zero** XmlTv/RefreshGuide log lines the entire
 * session. Diagnosed as: `RefreshGuideScheduler.refreshNow` had only
 * one caller (LoginActivity's UI `LaunchedEffect`), so any entry path
 * that doesn't go through that activity — a cached-session cold launch
 * straight to MainActivity, or the debug ADB injection — left the
 * worker enqueued 0 times.
 *
 * The fix added [RefreshGuideScheduler.kickPostSignIn] and wired it
 * into HappyTvApp.onCreate + AdbLoginHandler. This test asserts the
 * *invocation* behaviour, not the parser contract:
 *
 *  - With stored credentials, `kickPostSignIn` enqueues one work tagged
 *    with the same `UNIQUE_ONESHOT` name the UI flow uses.
 *  - Without credentials, no work is enqueued (no false positive on a
 *    signed-out cold launch).
 *
 * The actual `doWork()` execution is exercised on device (mocking
 * WorkManager's executor would tell us nothing — we care that the job
 * lands in WorkManager's queue).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RefreshGuideTriggerTest {

    companion object {
        /**
         * Robolectric has no `AndroidKeyStore` provider, so
         * `SecuredCredentialsStore.create()` (EncryptedSharedPreferences)
         * would die with `NoSuchAlgorithmException`. The test-only fake
         * provider restores that one capability — same harness the
         * `SecuredCredentialsStoreTest` uses.
         */
        @BeforeClass
        @JvmStatic
        fun installFakeAndroidKeyStore() {
            FakeAndroidKeyStore.install()
        }
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        // Robolectric's default `Application` does NOT include
        // WorkManager's `androidx.startup` `InitializationProvider`,
        // so `WorkManager.getInstance(...)` throws unless we initialise
        // explicitly. Guard by checking it first so re-runs are safe.
        try {
            WorkManager.getInstance(context)
        } catch (e: IllegalStateException) {
            WorkManager.initialize(
                context,
                Configuration.Builder()
                    .setMinimumLoggingLevel(android.util.Log.INFO)
                    .build(),
            )
        }
        Graph.init(context)
        runBlocking { Graph.securedCredentials.wipe() }
        // Force-finish any prior work so this test's getWorkInfosForUniqueWork
        // doesn't see leftovers from a sibling test in this class. WorkManager
        // keeps CANCELLED entries in its DB for ~24h, so we have to wait for
        // the cancellation Operation to complete AND rely on the per-test
        // assertions to clear the unique name's history — fortunately the
        // "with creds" test runs first (creates the leftover) and the
        // "without creds" test never enqueues, so the leftover is the only
        // thing the second assertion sees. Assert the delta instead of the
        // absolute size, and remember that ENQUEUED/RUNNING only count as
        // "live" — CANCELLED history doesn't.
        runCatching {
            WorkManager.getInstance(context)
                .cancelUniqueWork(RefreshGuideScheduler.UNIQUE_ONESHOT)
                .result.get()
        }
    }

    @After
    fun tearDown() {
        // Be aggressive: cancel by unique name + cancel everything, then
        // drain to completion. WorkManager persists its DB state across
        // tests otherwise, and `getWorkInfosForUniqueWork` reflects what
        // the DB still has on disk.
        runCatching {
            WorkManager.getInstance(context)
                .cancelUniqueWork(RefreshGuideScheduler.UNIQUE_ONESHOT)
                .result.get()
        }
        runCatching {
            WorkManager.getInstance(context)
                .cancelUniqueWork(RefreshGuideScheduler.UNIQUE_PERIODIC)
                .result.get()
        }
        runCatching { WorkManager.getInstance(context).cancelAllWork().result.get() }
        runBlocking { Graph.securedCredentials.wipe() }
    }

    @Test
    fun `kickPostSignIn without credentials does not enqueue any new work`() = runBlocking {
        // Baseline: no credentials + whatever UNIQUE_ONESHOT leftovers
        // survived the previous test's cancelUniqueWork are still in the
        // WorkManager history (cancelled work stays visible for a day).
        // Kick the trigger and verify it did **not** add any fresh
        // ENQUEUED work — the leftover CANCELLED entries are not what we
        // assert against.
        assertNull(
            "no credentials should be stored for this test",
            Graph.securedCredentials.current(),
        )
        val beforeCount = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(RefreshGuideScheduler.UNIQUE_ONESHOT)
            .get()
            .count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

        // Act.
        RefreshGuideScheduler.kickPostSignIn(context)
        // Drain the startupScope coroutine.
        Thread.sleep(200)

        // Assert: no new live work was added.
        val afterCount = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(RefreshGuideScheduler.UNIQUE_ONESHOT)
            .get()
            .count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        assertEquals(
            "kickPostSignIn on signed-out must not enqueue any new work",
            beforeCount,
            afterCount,
        )
    }

    @Test
    fun `kickPostSignIn with stored credentials enqueues a fresh one-shot`() = runBlocking {
        // Arrange: persist credentials so the trigger sees a signed-in app.
        Graph.securedCredentials.save(
            host = "provider.example",
            port = 80,
            useTls = false,
            username = "demo",
            password = "secret",
        )
        assertTrue("credentials must be present for the trigger", Graph.securedCredentials.isPresent())

        // Baseline count of live (ENQUEUED/RUNNING) UNIQUE_ONESHOT work
        // — the previous test left a CANCELLED row in the DB which
        // doesn't count, and `enqueueUniqueWork(REPLACE)` reuses a slot
        // for the same unique name, so we measure the delta in live work.
        val beforeCount = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(RefreshGuideScheduler.UNIQUE_ONESHOT)
            .get()
            .count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

        // Act: fire the entry-point-agnostic post-sign-in kick.
        RefreshGuideScheduler.kickPostSignIn(context)
        Thread.sleep(200)

        // Assert: a fresh live piece of work tagged with the unique name
        // landed in WorkManager.
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(RefreshGuideScheduler.UNIQUE_ONESHOT)
            .get()
        assertNotNull("UNIQUE_ONESHOT work infos", infos)
        val liveInfos = infos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        assertEquals(
            "kickPostSignIn with creds should bump live work by one",
            beforeCount + 1,
            liveInfos.size,
        )
    }
}
