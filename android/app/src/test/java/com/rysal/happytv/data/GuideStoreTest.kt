package com.rysal.happytv.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.EpgProgram
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The guide's store path against real SQLite (Robolectric): both sources
 * write the one unchanged `epg_programs` table — XMLTV through
 * `replaceAll` (`@Transaction`, task §6), provider listings through the
 * per-channel upsert the on-demand fetches and the worker's ≤25-channel
 * refresh use — and the grid's range/count queries read them back.
 *
 * Same harness as [HappyTvDatabaseRoundTripTest]: plain framework
 * Application, in-memory database, test-only main-thread queries.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GuideStoreTest {

    private lateinit var db: HappyTvDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HappyTvDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun program(channelId: String, title: String, start: Long, end: Long) =
        EpgProgram(
            channelId = channelId,
            title = title,
            description = null,
            startUtcMillis = start,
            endUtcMillis = end,
        )

    @Test
    fun `XMLTV path - replaceAll swaps the whole table in one transaction`() = runBlocking {
        val dao = db.epgProgramDao()
        val t = 1_000_000L

        dao.replaceAll(listOf(program("northbay.news", "One", t, t + 3600_000)))
        assertEquals(1, dao.count())

        // A second refresh replaces wholesale — never appends duplicates.
        dao.replaceAll(
            listOf(
                program("northbay.news", "Two", t, t + 3600_000),
                program("capital.desk", "Three", t + 3600_000, t + 7200_000),
            ),
        )
        assertEquals(2, dao.count())

        val window = dao.getBetweenRange(t - 1, t + 1)
        assertEquals(listOf("Two"), window.map { it.title })
        val wide = dao.getBetweenRange(t - 1, t + 7200_001)
        assertEquals(2, wide.size)
        // Window miss: nothing overlaps.
        assertEquals(0, dao.getBetweenRange(t + 7200_001, t + 9999_000).size)
    }

    @Test
    fun `provider path - per-channel upsert replaces one channel only`() = runBlocking {
        val dao = db.epgProgramDao()
        val t = 2_000_000L

        dao.insertAll(
            listOf(
                program("101", "Old 101", t, t + 1800_000),
                program("101", "Old 101 b", t + 1800_000, t + 3600_000),
                program("301", "Kept 301", t, t + 3600_000),
            ),
        )
        assertEquals(3, dao.count())

        // The focus-land fetch upserts channel 101…
        dao.deleteForChannel("101")
        dao.insertAll(listOf(program("101", "Fresh 101", t, t + 1800_000)))

        assertEquals(2, dao.count())
        assertEquals(listOf("Kept 301"), dao.getBetweenRange(t - 1, t + 3600_001).filter { it.channelId == "301" }.map { it.title })
        assertEquals(listOf("Fresh 101"), dao.getBetweenRange(t - 1, t + 1800_001).filter { it.channelId == "101" }.map { it.title })

        // Empty upsert (guide vanished upstream) leaves the channel clean.
        dao.deleteForChannel("101")
        assertEquals(0, dao.getBetweenRange(t - 1, t + 1800_001).count { it.channelId == "101" })
    }

    @Test
    fun `channel counts drive No guide info vs No guide data and the empty state`() = runBlocking {
        val dao = db.epgProgramDao()
        val t = 3_000_000L

        // Empty store → the guide's "No guide yet" empty state.
        assertTrue(dao.count() == 0)
        assertEquals(0, dao.observeChannelCounts().first().size)

        dao.insertAll(listOf(program("101", "Headline", t, t + 3600_000)))
        val counts = dao.observeChannelCounts().first().associate { it.channelId to it.count }
        assertEquals(mapOf("101" to 1), counts) // 101 has guide info; every other channel: none
    }

    @Test
    fun `deleteBefore prunes to the epgPastDaysToKeep window`() = runBlocking {
        val dao = db.epgProgramDao()
        val now = 10_000_000_000L
        dao.insertAll(
            listOf(
                program("101", "Ancient", now - 30 * 86400_000, now - 30 * 86400_000 + 1000),
                program("101", "Recent", now - 86400_000, now - 86400_000 + 1000),
            ),
        )
        dao.deleteBefore(now - 7 * 86400_000)
        assertEquals(listOf("Recent"), dao.getBetweenRange(0, now).map { it.title })
    }
}
