package com.rysal.happytv.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.dao.CATALOG_UPSERT_CHUNK
import com.rysal.happytv.data.dao.SeenIds
import com.rysal.happytv.data.dao.syncStream
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.network.XtreamRepository
import com.rysal.happytv.data.network.buildXtreamApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #130: the catalog sync is chunked. It never holds the list or the stored table in memory,
 * compares chunk by chunk, and removes only after the whole list decoded.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CatalogChunkedSyncTest {

    private lateinit var db: HappyTvDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: XtreamRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HappyTvDatabase::class.java,
        ).allowMainThreadQueries().build()
        server = MockWebServer()
        server.start()
        repository = XtreamRepository(
            host = "localhost",
            port = server.port,
            useTls = false,
            username = "user",
            password = "pass",
            api = buildXtreamApi(OkHttpClient(), Graph.json),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun movie(i: Int, name: String = "Movie $i", category: String = "c${i % 7}") =
        VodStream(id = "$i", categoryId = category, num = i, name = name)

    private fun wire(i: Int, name: String = "Movie $i") =
        """{"num":$i,"name":"$name","stream_id":$i,"category_id":"c${i % 7}"}"""

    // ------------------------------------------------------ chunk boundaries

    @Test
    fun `new changed unchanged and removed rows are counted across chunk boundaries`() = runBlocking {
        val dao = db.vodStreamDao()
        val stored = 2_500
        dao.insertAll((1..stored).map { movie(it) })
        assertTrue(stored > 2 * CATALOG_UPSERT_CHUNK)

        // Changed: straddle the chunk edges. Removed: the last 100. New: 2501..2600.
        val edges = setOf(1, CATALOG_UPSERT_CHUNK - 1, CATALOG_UPSERT_CHUNK, CATALOG_UPSERT_CHUNK + 1, 2_000, 2_300)
        val incoming = ((1..2_400) + (2_501..2_600)).map { i ->
            if (i in edges) movie(i, name = "Renamed $i") else movie(i)
        }

        val write = dao.syncStream(incoming.asSequence())

        assertEquals(edges.size + 100, write.upserted)
        assertEquals(100, write.removed)
        assertEquals(2_400 - edges.size, write.unchanged)
        assertEquals(incoming.size, write.total)
        assertEquals(2_500, dao.count())
        edges.forEach { assertEquals("Renamed $it", dao.getById("$it")!!.name) }
        assertNull(dao.getById("2450"))
        assertNotNull(dao.getById("2600"))
        assertEquals("Movie 2400", dao.getById("2400")!!.name)
    }

    @Test
    fun `an identical list writes nothing`() = runBlocking {
        val dao = db.vodStreamDao()
        val list = (1..2_300).map { movie(it) }
        dao.insertAll(list)

        val write = dao.syncStream(list.map { it.copy() }.asSequence())

        assertEquals(0, write.upserted)
        assertEquals(0, write.removed)
        assertEquals(2_300, write.unchanged)
        assertFalse(write.touched)
    }

    @Test
    fun `a later duplicate wins whether or not it shares a chunk`() = runBlocking {
        val dao = db.vodStreamDao()
        val incoming = sequence {
            yield(movie(1, name = "first"))
            yield(movie(1, name = "second")) // same chunk
            for (i in 2..1_500) yield(movie(i))
            yield(movie(2, name = "later chunk")) // another chunk
        }

        val write = dao.syncStream(incoming)

        assertEquals("second", dao.getById("1")!!.name)
        assertEquals("later chunk", dao.getById("2")!!.name)
        assertEquals(1_500, dao.count())
        assertEquals(1_501, write.upserted) // the cross-chunk duplicate is written twice, as REPLACE would
    }

    @Test
    fun `non-numeric ids are matched and removed too`() = runBlocking {
        val dao = db.vodStreamDao()
        dao.insertAll(listOf(movie(1).copy(id = "abc"), movie(2).copy(id = "007"), movie(3).copy(id = "7"), movie(4)))

        val write = dao.syncStream(sequenceOf(movie(1).copy(id = "abc"), movie(3).copy(id = "7")))

        assertEquals(2, write.removed)
        assertEquals(listOf("7", "abc"), dao.getAll().map { it.id }.sorted())
    }

    // --------------------------------------------------------------- failure

    @Test
    fun `a failure midway removes nothing and keeps every stored row`() = runBlocking {
        val dao = db.vodStreamDao()
        dao.insertAll((1..3_000).map { movie(it) })
        val incoming = sequence {
            for (i in 1..1_500) yield(movie(i, name = "Fresh $i"))
            throw java.io.IOException("connection reset")
        }

        val failure = runCatching { dao.syncStream(incoming) }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertEquals(3_000, dao.count())
        // Chunks that arrived are fresh provider data; the rest is untouched.
        assertEquals("Fresh 1000", dao.getById("1000")!!.name)
        assertEquals("Movie 2999", dao.getById("2999")!!.name)
    }

    @Test
    fun `a body cut off midway fails the sync and removes nothing`() = runBlocking {
        val dao = db.vodStreamDao()
        dao.insertAll((1..2_000).map { movie(it) })
        val body = (1..1_200).joinToString(",", prefix = "[", postfix = ",{\"stream_id\":") { wire(it) }
        server.enqueue(MockResponse().setBody(body))

        val failure = runCatching { repository.withVodStreams { dao.syncStream(it) } }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(2_000, dao.count())
    }

    @Test
    fun `an error object instead of an array fails the sync and removes nothing`() = runBlocking {
        val dao = db.vodStreamDao()
        dao.insertAll((1..50).map { movie(it) })
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))

        val failure = runCatching { repository.withVodStreams { dao.syncStream(it) } }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(50, dao.count())
    }

    @Test
    fun `an empty array is a real empty catalog and clears stored rows`() = runBlocking {
        val dao = db.vodStreamDao()
        dao.insertAll((1..50).map { movie(it) })
        server.enqueue(MockResponse().setBody("[]"))

        val write = repository.withVodStreams { dao.syncStream(it) }

        assertEquals(50, write.removed)
        assertEquals(0, dao.count())
    }

    @Test
    fun `wire ids as numbers or strings and blank ids still map as before`() = runBlocking {
        val dao = db.vodStreamDao()
        server.enqueue(
            MockResponse().setBody(
                """[{"stream_id":1,"name":"A","category_id":"1"},{"stream_id":"2","name":"B","category_id":"1"},""" +
                    """{"stream_id":"","name":"dropped","category_id":"1"},{"name":"also dropped"}]""",
            ),
        )

        val write = repository.withVodStreams { dao.syncStream(it) }

        assertEquals(2, write.upserted)
        assertEquals(listOf("1", "2"), dao.getAll().map { it.id }.sorted())
    }

    // -------------------------------------------------------- first-run path

    @Test
    fun `a first load of 115k movies into an empty database works`() = runBlocking {
        val dao = db.vodStreamDao()
        val rows = 115_000

        val write = dao.syncStream((1..rows).asSequence().map { movie(it) })

        assertEquals(rows, write.upserted)
        assertEquals(0, write.removed)
        assertEquals(rows, dao.count())
        assertEquals("Movie 115000", dao.getById("115000")!!.name)
    }

    @Test
    fun `a 115k load into a stale database upserts, keeps and removes the right rows`() = runBlocking {
        val dao = db.vodStreamDao()
        // Stale: ids 1..30000 from an older catalog, 5,000 of them with old names.
        dao.insertAll((1..30_000).map { movie(it, name = if (it % 6 == 0) "Old $it" else "Movie $it") })

        // Fresh: ids 10001..125000 (the first 10k stored ids are gone).
        val write = dao.syncStream((10_001..125_000).asSequence().map { movie(it) })

        assertEquals(10_000, write.removed)
        assertEquals(115_000, write.total)
        assertEquals(115_000, dao.count())
        assertNull(dao.getById("10000"))
        assertEquals("Movie 12000", dao.getById("12000")!!.name)
        assertEquals("Movie 125000", dao.getById("125000")!!.name)
        assertEquals(0, dao.getAll().count { it.name.startsWith("Old") })
    }

    // ----------------------------------------------------------------- SeenIds

    @Test
    fun `SeenIds keeps non-canonical numeric ids distinct`() {
        val seen = SeenIds()
        listOf("7", "007", "+7", "abc", "7", "9223372036854775807", "99999999999999999999").forEach { seen.add(it) }

        assertTrue("7" in seen)
        assertTrue("007" in seen)
        assertTrue("+7" in seen)
        assertTrue("abc" in seen)
        assertTrue("99999999999999999999" in seen)
        assertFalse("8" in seen)
        assertFalse("07" in seen)
        assertEquals(6, seen.distinctCount)
    }

    @Test
    fun `SeenIds grows past its first allocation`() {
        val seen = SeenIds()
        for (i in 200_000 downTo 1) seen.add("$i")
        assertTrue("1" in seen && "200000" in seen && "100000" in seen)
        assertFalse("200001" in seen)
        assertEquals(200_000, seen.distinctCount)
    }
}
