package com.rysal.happytv

import android.os.Looper
import androidx.room.RoomDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import org.robolectric.Shadows.shadowOf

/**
 * The one teardown order for tests that run a ViewModel (or any collector) over
 * an in-memory Room database with `Dispatchers.setMain` installed (#41).
 *
 * Every flake in that family had the same shape: something cancelled but not
 * *finished* was still alive when the next step ran.
 *
 *  1. cancelling a scope only *requests* cancellation, so [Job.cancelAndJoin];
 *  2. a Room query already handed to its executor runs to completion regardless
 *     of cancellation, so both single-threaded executors are drained, which is a
 *     barrier on everything queued before it;
 *  3. Room's late emissions land as looper runnables that resume continuations on
 *     Main, so the main looper is idled before Main goes away; otherwise one
 *     racing `resetMain()` trips "Dispatchers.Main is used concurrently with
 *     setting it";
 *  4. only then reset Main and close the database.
 *
 * No sleeps and no retries: each step waits on the real condition. A barrier that
 * does not come back in [DRAIN_TIMEOUT_MS] fails the test loudly instead of
 * letting the close race a running statement.
 */
object RoomTestTeardown {

    private const val DRAIN_TIMEOUT_MS = 30_000L

    /** Cancel and join [jobs], drain [db]'s executors, idle Main, reset Main, close [db]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun shutDown(jobs: Collection<Job?>, db: RoomDatabase?, resetMain: Boolean = true) {
        runBlocking { jobs.forEach { it?.cancelAndJoin() } }
        if (db != null) drainExecutors(db)
        shadowOf(Looper.getMainLooper()).idle()
        if (resetMain) Dispatchers.resetMain()
        db?.close()
    }

    /**
     * Blocks until every task queued on [db]'s query and transaction executors
     * before this call has finished. Safe to call between a cancel and a close.
     */
    fun drainExecutors(db: RoomDatabase) {
        listOf(db.queryExecutor, db.transactionExecutor).forEach { executor ->
            val drained = CountDownLatch(1)
            executor.execute { drained.countDown() }
            check(drained.await(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "a Room executor did not drain within ${DRAIN_TIMEOUT_MS}ms"
            }
        }
        // Draining the query executor can post follow-up emissions to Main.
        shadowOf(Looper.getMainLooper()).idle()
    }
}
