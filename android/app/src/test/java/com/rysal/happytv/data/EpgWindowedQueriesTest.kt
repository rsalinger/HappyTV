package com.rysal.happytv.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.dao.EpgProgramDao
import com.rysal.happytv.data.entity.EpgProgram
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #10: the batched, key-scoped guide reads behind Live TV's now lines and the
 * guide grid. They replace whole-table scans, so what matters is that they
 * answer for exactly the keys asked about (and for more keys than one SQLite
 * statement can carry).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class EpgWindowedQueriesTest {

    private lateinit var db: HappyTvDatabase
    private lateinit var dao: EpgProgramDao

    private val now = 10_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.epgProgramDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun program(channel: String, title: String, start: Long, end: Long) = EpgProgram(
        channelId = channel,
        title = title,
        description = "long description for $title",
        startUtcMillis = start,
        endUtcMillis = end,
    )

    @Test
    fun `now lookup returns the running programme of each asked key only`() = runBlocking {
        dao.insertAll(
            listOf(
                program("a", "A earlier", now - 7_200_000, now - 3_600_000),
                program("a", "A now", now - 1_800_000, now + 1_800_000),
                program("a", "A later", now + 1_800_000, now + 5_400_000),
                program("b", "B now", now - 600_000, now + 600_000),
                program("c", "C now", now - 600_000, now + 600_000),
                program("d", "D ended", now - 7_200_000, now - 3_600_000),
            ),
        )

        val found = dao.getNowForChannels(listOf("a", "b", "d", "missing"), now)

        assertEquals(mapOf("a" to "A now", "b" to "B now"), found.associate { it.channelId to it.title })
        assertEquals(now + 1_800_000, found.first { it.channelId == "a" }.endUtcMillis)
    }

    @Test
    fun `a programme that ends exactly now is not on now`() = runBlocking {
        dao.insertAll(listOf(program("a", "Over", now - 3_600_000, now)))
        assertTrue(dao.getNowForChannels(listOf("a"), now).isEmpty())
    }

    @Test
    fun `lookups carry more keys than one statement can bind`() = runBlocking {
        val total = EpgProgramDao.KEYS_PER_QUERY * 2 + 37
        val rows = (0 until total).map { program("ch$it", "T$it", now - 1_000, now + 1_000) }
        rows.chunked(100).forEach { dao.insertAll(it) }

        val keys = (0 until total).map { "ch$it" }
        assertEquals(total, dao.getNowForChannels(keys, now).size)
        assertEquals(total, dao.getBetweenForChannels(keys, now - 10, now + 10).size)
        assertEquals(total, dao.getCountsForChannels(keys).size)
    }

    @Test
    fun `window read returns overlapping programmes of the asked keys in order`() = runBlocking {
        dao.insertAll(
            listOf(
                program("b", "B2", now + 100, now + 200),
                program("a", "A1", now - 500, now + 50),
                program("a", "A2", now + 50, now + 400),
                program("a", "A far", now + 10_000, now + 20_000),
                program("z", "Z other channel", now, now + 100),
            ),
        )

        val rows = dao.getBetweenForChannels(listOf("a", "b"), now, now + 300)

        assertEquals(listOf("A1", "A2", "B2"), rows.map { it.title })
    }

    @Test
    fun `the grid's window read is the same rows without the description column`() = runBlocking {
        dao.insertAll(
            listOf(
                program("a", "A1", now - 500, now + 50),
                program("a", "A2", now + 50, now + 400),
                program("b", "B2", now + 100, now + 200),
                program("z", "Z other channel", now, now + 100),
            ),
        )

        val full = dao.getBetweenForChannels(listOf("a", "b"), now, now + 300)
        val lite = dao.getWindowForChannels(listOf("a", "b"), now, now + 300)

        assertEquals(full.map { it.title }, lite.map { it.title })
        assertEquals(full.map { it.startUtcMillis }, lite.map { it.startUtcMillis })
        assertEquals(full.map { it.endUtcMillis }, lite.map { it.endUtcMillis })
        assertTrue(dao.getWindowForChannels(emptyList(), 0, Long.MAX_VALUE).isEmpty())
        val total = EpgProgramDao.KEYS_PER_QUERY + 5
        (0 until total).map { program("ch$it", "T$it", now - 1_000, now + 1_000) }.chunked(100).forEach { dao.insertAll(it) }
        assertEquals(total, dao.getWindowForChannels((0 until total).map { "ch$it" }, now - 10, now + 10).size)
    }

    @Test
    fun `programAt reads the one programme on at an instant, with its description`() = runBlocking {
        dao.insertAll(
            listOf(
                program("a", "A earlier", now - 7_200_000, now - 3_600_000),
                program("a", "A now", now - 1_800_000, now + 1_800_000),
                program("a", "A later", now + 1_800_000, now + 5_400_000),
                program("b", "B now", now - 600_000, now + 600_000),
            ),
        )

        val found = dao.programAt("a", now)!!
        assertEquals("A now", found.title)
        assertEquals("long description for A now", found.description)
        // A program's own start instant finds that program, not the one before it.
        assertEquals("A later", dao.programAt("a", now + 1_800_000)!!.title)
        // Its end is exclusive.
        assertEquals("A later", dao.programAt("a", now + 1_800_000)!!.title)
        assertEquals(null, dao.programAt("a", now + 5_400_000))
        assertEquals(null, dao.programAt("nobody", now))
    }

    @Test
    fun `counts cover all time for the asked keys`() = runBlocking {
        dao.insertAll(
            listOf(
                program("a", "1", 0, 1),
                program("a", "2", 2, 3),
                program("b", "3", 0, 1),
                program("c", "4", 0, 1),
            ),
        )
        val counts = dao.getCountsForChannels(listOf("a", "b", "nobody")).associate { it.channelId to it.count }
        assertEquals(mapOf("a" to 2, "b" to 1), counts)
    }

    @Test
    fun `no keys means no query and no rows`() = runBlocking {
        assertTrue(dao.getNowForChannels(emptyList(), now).isEmpty())
        assertTrue(dao.getBetweenForChannels(emptyList(), 0, Long.MAX_VALUE).isEmpty())
        assertTrue(dao.getCountsForChannels(emptyList()).isEmpty())
    }

    @Test
    fun `store version is zero when empty and moves when the store is written`() = runBlocking {
        assertEquals(0L, dao.observeStoreVersion().first())
        dao.insertAll(listOf(program("a", "1", 0, 1)))
        val afterFirst = dao.observeStoreVersion().first()
        assertTrue(afterFirst > 0L)
        dao.insertAll(listOf(program("a", "2", 2, 3)))
        assertNotEquals(afterFirst, dao.observeStoreVersion().first())
        dao.deleteAll()
        assertEquals(0L, dao.observeStoreVersion().first())
    }

    @Test
    fun `now lookup is an indexed search, not a table scan`() {
        // The whole point of #10's batched lookup: SEARCH using the (channelId, start) index.
        val cursor = db.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT channelId, title, endUtcMillis FROM epg_programs " +
                "WHERE channelId IN ('a','b') AND startUtcMillis <= 5 AND endUtcMillis > 5",
        )
        val plan = StringBuilder()
        while (cursor.moveToNext()) plan.append(cursor.getString(cursor.getColumnIndexOrThrow("detail"))).append('|')
        cursor.close()
        assertTrue("expected an index search, got: $plan", plan.contains("USING INDEX") && !plan.contains("SCAN TABLE epg_programs|"))
    }
}
