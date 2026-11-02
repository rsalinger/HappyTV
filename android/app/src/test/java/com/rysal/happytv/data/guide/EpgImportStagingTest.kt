package com.rysal.happytv.data.guide

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.RoomTestTeardown
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.dao.EpgProgramDao
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.security.Credentials
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-65 — "the XMLTV import freezes the app for 4–6 minutes, twice per sign-in".
 *
 * The old import did `deleteAll()` and then inserted all 369,366 rows inside
 * **one** Room transaction. On the Chromecast HD that is a ~120 MB WAL written
 * under a write lock held for minutes: every other query blocks behind it,
 * SQLite stops granting pool connections (`unable to grant a connection to
 * thread … for 270s`), and Live TV shows "0 channels", the dashboard tile
 * says "Updating…" and Search returns nothing until the COMMIT lands.
 *
 * The import is now **stage-then-promote**:
 *
 *  1. `EpgProgramDao.stageAllStreamed` parses into `epg_programs_stage` in
 *     chunked transactions — a table Room does not observe, so **the live
 *     guide stays readable and unchanged throughout**.
 *  2. `EpgProgramDao.promoteStaged` swaps the staged rows into the live table
 *     in one short transaction, rebuilding the index around the copy.
 *
 * These tests pin the behaviour that makes the difference, all against real
 * in-memory Room + a MockWebServer panel:
 *
 *  - **The live guide is never emptied mid-import.** A watcher collecting the
 *    live Flow during a full import sees either the old rows or the new ones —
 *    never an empty table. This is the F-62/F-63 symptom, asserted directly.
 *  - **A failed import leaves the last-good guide intact** and does not leave
 *    a half-populated stage table behind for the next run (F-52 preserved).
 *  - **The staging table is emptied after a promotion**, so the DB doesn't
 *    carry two full copies of a 369k-row guide.
 *  - **Concurrent imports collapse into one** — the sign-in kick and the
 *    worker no longer rewrite the store twice.
 *
 * Robolectric + in-memory Room, same harness as `GuideXmlTvLastGoodTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class EpgImportStagingTest {

    private lateinit var server: MockWebServer
    private lateinit var db: HappyTvDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var context: Application

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "epg-staging-${dataStoreFiles++}.preferences_pb")
        }
        settings = SettingsRepository(dataStore)
    }

    /** Collectors a test started on its own scope; joined before the database closes. */
    private val watchers = mutableListOf<kotlinx.coroutines.Job>()

    @After
    fun tearDown() {
        server.shutdown()
        // A test that fails while its watcher is still collecting must not leave
        // it running into db.close() (or into the next test).
        RoomTestTeardown.shutDown(watchers, db, resetMain = false)
    }

    private fun repository(creds: Credentials?) = GuideRepository(
        epgDao = db.epgProgramDao(),
        liveDao = db.liveStreamDao(),
        categoryDao = db.categoryDao(),
        favoriteDao = db.favoriteDao(),
        recentDao = db.recentChannelDao(),
        settings = settings,
        session = { null },
        xmlTvFetcher = HttpXmlTvFetcher(context),
        credentials = { creds },
    )

    private fun creds() = Credentials(
        host = "127.0.0.1",
        port = server.port,
        useTls = false,
        username = "demo",
        password = "secret",
        savedAtUtcMillis = 0L,
    )

    /** An XMLTV document with [count] programmes spread over [channels] channels. */
    private fun xmltv(count: Int, channels: Int = 8, tag: String = "New"): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("<tv>")
        // Relative to today, never a fixed date: the import trims programmes older
        // than the keep-past-days window, so a hard-coded 2026-10-16 fixture lost
        // its 00:00 row once the wall clock passed that window (a #41 time bomb).
        val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        for (i in 0 until count) {
            val ch = "ch%d.fake".format(i % channels)
            // Overlap deliberately: each channel has several concurrent shows,
            // so the guide grid's per-channel grouping has something to show.
            append(
                """<programme start="${day}%02d%02d00 +0000" """.format(i % 24, i % 60) +
                    """stop="${day}%02d%02d00 +0000" channel="$ch">""".format(i % 24, i % 60) +
                    "<title>$tag $i</title><desc>Description $i</desc></programme>",
            )
        }
        append("</tv>")
    }

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse().setBody(body).addHeader("Content-Type", "application/xml"),
        )
    }

    /** Enqueue a bare response — used for the 304 "not modified" reply. */
    private fun enqueue(response: MockResponse) {
        server.enqueue(response)
    }

    /** The guide the user is watching before any import runs. */
    private suspend fun seedLastGood() {
        db.epgProgramDao().insertAll(
            (0 until 5).map {
                EpgProgram(
                    channelId = "kept.fake",
                    title = "Kept $it",
                    description = null,
                    startUtcMillis = 1_000L + it * 1_000L,
                    endUtcMillis = 2_000L + it * 1_000L,
                )
            },
        )
    }

    private suspend fun storedTitles(): List<String> =
        db.epgProgramDao().observeBetweenRange(0, Long.MAX_VALUE).first().map { it.title }

    @Test
    fun `a successful import replaces the guide and empties the staging table`() = runBlocking {
        enqueue(xmltv(count = 40, tag = "Imported"))

        assertTrue(repository(creds()).fetchAndStoreXmlTv())

        assertEquals(40, db.epgProgramDao().count())
        assertEquals(
            "the stage table must not keep a second copy of the guide",
            0,
            db.epgProgramDao().stagedCount(),
        )
        assertTrue(storedTitles().all { it.startsWith("Imported") })
    }

    @Test
    fun `a promoted import marks the guide fresh for the cold start check`() = runBlocking {
        val repo = repository(creds())
        assertFalse("nothing imported yet", repo.isGuideFresh(RefreshGuideScheduler.STARTUP_GUIDE_REUSE_MS))

        enqueue(xmltv(count = 40, tag = "Imported"))
        assertTrue(repo.fetchAndStoreXmlTv())

        assertTrue(repo.isGuideFresh(RefreshGuideScheduler.STARTUP_GUIDE_REUSE_MS))
        assertFalse(
            "a guide stamped long ago is not fresh",
            repo.isGuideFresh(RefreshGuideScheduler.STARTUP_GUIDE_REUSE_MS, nowMillis = System.currentTimeMillis() + 4 * 3_600_000L),
        )
    }

    @Test
    fun `the live guide is never emptied while the import runs`() = runBlocking {
        // Two full chunks' worth of programmes so the staged import really
        // does span several transactions, not one.
        val rows = EpgProgramDao.STAGE_CHUNK_SIZE * 2 + 17
        seedLastGood()
        enqueue(xmltv(count = rows, channels = 64, tag = "Imported"))

        // Watch the live table the whole time the import runs. The old code
        // ran deleteAll() inside the import transaction and left the table
        // empty (or lock-blocked) until the COMMIT — this is exactly the
        // "0 channels · skeleton" state QA screenshotted.
        // Written on Dispatchers.IO by the watcher, read here: must be thread-safe.
        val observed = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val firstEmission = CompletableDeferred<Int>()
        val promotedEmission = CompletableDeferred<Unit>()
        val watcher = CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            db.epgProgramDao().observeBetweenRange(0, Long.MAX_VALUE)
                // Robolectric's in-memory SQLite can fail a cursor read that races
                // a concurrent write (Couldn't read row N from CursorWindow). The
                // batched promotion (#43) commits many times, so the race is
                // likelier than with one big transaction; a retry re-queries. The
                // assertions below still fail on any empty emission.
                .retry { e -> (e is IllegalStateException && e.message.orEmpty().contains("CursorWindow")).also { if (it) delay(5) } }
                .collect { rowsNow ->
                observed += rowsNow.size
                firstEmission.complete(rowsNow.size)
                if (rowsNow.size == rows) promotedEmission.complete(Unit)
            }
        }

        // Wait for the watcher to be SUBSCRIBED and holding the seeded 5 rows
        // before the import starts. `launch` returns as soon as the coroutine is
        // scheduled; on Dispatchers.IO the import's own download can outrun the
        // first Room query, so the whole watch could start — and finish — after
        // the rows were already swapped. The test then failed its "saw 5"
        // clause purely because it was not watching yet. A fixed `delay(300)`
        // is not a barrier either: the query has to come back from Room first.
        assertEquals(
            "the watcher must see the seeded guide before the import starts",
            5,
            withTimeout(WATCH_SUBSCRIBE_TIMEOUT_MS) { firstEmission.await() },
        )

        assertTrue(repository(creds()).fetchAndStoreXmlTv())
        // Wait for the post-promotion emission itself. Room refreshes its
        // invalidation tracker asynchronously on the query executor, so how long
        // it takes to deliver the swapped rows depends on machine load; a fixed
        // `delay(300)` cancelled the watcher before it arrived (saw=[5]).
        withTimeout(WATCH_SUBSCRIBE_TIMEOUT_MS) { promotedEmission.await() }
        watcher.cancelAndJoin()

        assertEquals(
            "the import must leave the live table populated, never empty",
            0,
            observed.count { it == 0 },
        )
        assertTrue(
            "the watcher should have seen the previous guide and then the new one, " +
                "saw=$observed",
            observed.any { it == 5 } && observed.any { it == rows },
        )
    }

    @Test
    fun `a malformed document keeps the last-good guide and clears the stage`() = runBlocking {
        seedLastGood()
        // Valid programmes well past one staging chunk, then a stray tag.
        val body = xmltv(count = EpgProgramDao.STAGE_CHUNK_SIZE + 5, tag = "Doomed") +
            "</stray>"
        enqueue(body)

        val result = runCatching { repository(creds()).fetchAndStoreXmlTv() }

        assertTrue("expected the malformed document to fail the import", result.isFailure)
        assertEquals((0 until 5).map { "Kept $it" }, storedTitles())
        assertEquals(
            "a failed import must not leave rows behind for the next run",
            0,
            db.epgProgramDao().stagedCount(),
        )
        assertNull(
            "F-52: the derived URL is only persisted once rows actually land",
            settings.settings.first().epgUrl,
        )
    }

    @Test
    fun `two concurrent imports serialize instead of interleaving`() = runBlocking {
        // The sign-in kick and RefreshGuideWorker both drive this within
        // seconds of each other; QA's logcat showed the 369k store rewritten
        // twice in six minutes (15:40:07, then 15:46:01), and two imports
        // racing on one table interleave their `deleteAll()`/insert streams.
        // The repository's mutex makes the second caller wait its turn.
        //
        // Two DIFFERENT documents, so the final state proves which one won
        // and that they didn't blend: exactly 20 rows, not 20 + 30.
        enqueue(xmltv(count = 20, channels = 4, tag = "First"))
        enqueue(xmltv(count = 30, channels = 6, tag = "Second"))

        val repo = repository(creds())
        val first = async { repo.fetchAndStoreXmlTv() }
        val second = async { repo.fetchAndStoreXmlTv() }

        assertTrue(first.await())
        assertTrue(second.await())

        val rows = db.epgProgramDao().getBetweenRange(0, Long.MAX_VALUE)
        assertEquals(
            "the two imports must not interleave — the store is exactly one of " +
                "them, not a blend (saw ${rows.size} rows)",
            30,
            rows.size,
        )
        assertTrue(
            "the surviving rows all come from the last import to run",
            rows.all { it.title.startsWith("Second") },
        )
        assertEquals(
            "no half-written stage table may survive the race",
            0,
            db.epgProgramDao().stagedCount(),
        )
    }

    @Test
    fun `the live index survives the promotion`() = runBlocking {
        // promoteStaged() drops the index around the bulk copy and rebuilds
        // it — if either execSQL landed outside the transaction (or the
        // rebuild silently no-op'd), the guide would be left un-indexed:
        // correct, but every grid query would degrade to a full table scan.
        // Assert the index is present after a real import, and that the
        // planner actually uses it.
        enqueue(xmltv(count = 200, channels = 16, tag = "Indexed"))

        assertTrue(repository(creds()).fetchAndStoreXmlTv())

        val database = db
        val indices = database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?",
            arrayOf<Any>("epg_programs"),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertTrue(
            "the guide must still be indexed after a promotion, found $indices",
            indices.contains(EpgProgramDao.LIVE_INDEX),
        )

        val plan = database.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT * FROM epg_programs " +
                "WHERE channelId = ? AND endUtcMillis >= ? AND startUtcMillis <= ?",
            arrayOf<Any>("ch1.fake", 0L, Long.MAX_VALUE),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
        }
        assertTrue(
            "the guide query should use the (channelId, startUtcMillis) index, " +
                "plan=$plan",
            plan.any { it.contains(EpgProgramDao.LIVE_INDEX) },
        )
    }

    @Test
    fun `a warm store is not re-imported`() = runBlocking {
        // Repeat run against an unchanged panel: the conditional GET answers
        // 304 and the stored guide is kept exactly as it is.
        enqueue(xmltv(count = 12, tag = "First"))
        assertTrue(repository(creds()).fetchAndStoreXmlTv())
        val afterFirst = storedTitles()

        enqueue(MockResponse().setResponseCode(304))

        assertFalse(
            "an unchanged panel must not rewrite the store",
            repository(creds()).fetchAndStoreXmlTv(),
        )
        assertEquals(afterFirst, storedTitles())
    }

    companion object {
        /** DataStore refuses a second instance per file — unique per test. */
        private var dataStoreFiles = 0

        /**
         * How long to wait for the live-guide watcher's first Room emission.
         *
         * Generous: it only bounds a genuinely wedged subscription, and the
         * failure it produces ("the watcher must see the seeded guide before
         * the import starts") says exactly which barrier was missed. A short
         * budget would turn a slow-but-correct build into a flake.
         */
        private const val WATCH_SUBSCRIBE_TIMEOUT_MS = 30_000L
    }
}