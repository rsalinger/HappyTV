package com.rysal.happytv.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.StreamType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #221: favorites keep a position; a new one goes to the bottom, a place writes only what changed. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FavoriteOrderDaoTest {

    private lateinit var db: HappyTvDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        val drained = java.util.concurrent.CountDownLatch(1)
        runCatching { db.queryExecutor.execute { drained.countDown() } }
        drained.await(5, java.util.concurrent.TimeUnit.SECONDS)
        db.close()
    }

    private suspend fun ids(type: StreamType = StreamType.LIVE) = db.favoriteDao().getOrdered(type).map { it.streamId }

    @Test
    fun `new favorites go to the bottom, in the order they were starred`() = runBlocking {
        val dao = db.favoriteDao()
        listOf("c5", "c2", "c9").forEach { dao.toggle(it, StreamType.LIVE) }
        assertEquals(listOf("c5", "c2", "c9"), ids())
        assertEquals(listOf("c5", "c2", "c9"), dao.observeOrdered(StreamType.LIVE).first().map { it.streamId })
    }

    @Test
    fun `each catalog is ordered on its own`() = runBlocking {
        val dao = db.favoriteDao()
        dao.toggle("a", StreamType.LIVE)
        dao.toggle("m1", StreamType.VOD)
        dao.toggle("b", StreamType.LIVE)
        assertEquals(listOf("a", "b"), ids())
        assertEquals(listOf(1, 2), dao.getOrdered(StreamType.LIVE).map { it.position })
        assertEquals(listOf(1), dao.getOrdered(StreamType.VOD).map { it.position })
    }

    @Test
    fun `removing a favorite leaves no gap in the order, and a later one still goes last`() = runBlocking {
        val dao = db.favoriteDao()
        listOf("a", "b", "c", "d").forEach { dao.toggle(it, StreamType.LIVE) }
        dao.toggle("b", StreamType.LIVE) // removed
        assertEquals(listOf("a", "c", "d"), ids())
        dao.toggle("e", StreamType.LIVE)
        assertEquals(listOf("a", "c", "d", "e"), ids())
        // Numbering is the list index, so the screens show 1..N whatever positions are stored.
        assertEquals(listOf(1, 2, 3, 4), ids().indices.map { it + 1 })
    }

    @Test
    fun `reorder puts the favorites in the given order and renumbers them 1 to N`() = runBlocking {
        val dao = db.favoriteDao()
        listOf("a", "b", "c", "d").forEach { dao.toggle(it, StreamType.LIVE) }
        dao.reorder(StreamType.LIVE, listOf("d", "a", "c", "b"))
        assertEquals(listOf("d", "a", "c", "b"), ids())
        assertEquals(listOf(1, 2, 3, 4), dao.getOrdered(StreamType.LIVE).map { it.position })
    }

    @Test
    fun `reorder of the favorites a screen shows keeps the hidden ones where they were`() = runBlocking {
        val dao = db.favoriteDao()
        listOf("a", "b", "c", "d", "e").forEach { dao.toggle(it, StreamType.LIVE) }
        // b and d are hidden channels, so the screen shows a, c, e and the user moves e to the top.
        dao.reorder(StreamType.LIVE, listOf("e", "a", "c"))
        assertEquals(listOf("e", "b", "a", "d", "c"), ids())
    }

    @Test
    fun `reorder leaves other catalogs alone and ignores an id that is not a favorite`() = runBlocking {
        val dao = db.favoriteDao()
        listOf("a", "b", "c").forEach { dao.toggle(it, StreamType.LIVE) }
        dao.toggle("m1", StreamType.VOD)
        dao.reorder(StreamType.LIVE, listOf("c", "b", "a"))
        assertEquals(listOf("m1"), ids(StreamType.VOD))
        // "zz" is not a favorite (removed meanwhile): it is skipped.
        dao.reorder(StreamType.LIVE, listOf("zz", "a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), ids())
    }
}
