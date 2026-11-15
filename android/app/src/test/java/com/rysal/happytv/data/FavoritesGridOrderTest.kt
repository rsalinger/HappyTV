package com.rysal.happytv.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.SeriesStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #232: the Movies and Shows Favorites grids list items in `favorites.position` order, new ones last. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FavoritesGridOrderTest {

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

    // Ids whose text order (10, 2, 9) differs from the add order (9, 10, 2) and from insert order.
    private val ids = listOf("9", "10", "2")

    private suspend fun seed() {
        db.vodStreamDao().insertAll(listOf("2", "9", "10", "77").map { VodStream(id = it, categoryId = "c", name = "m$it") })
        db.seriesStreamDao().insertAll(listOf("10", "2", "77", "9").map { SeriesStream(id = it, categoryId = "c", name = "s$it") })
    }

    @Test
    fun `movies favorites list in the order they were added`() = runBlocking {
        seed()
        ids.forEach { db.favoriteDao().toggle(it, StreamType.VOD) }
        assertEquals(ids, db.vodStreamDao().observeFavorites().first().map { it.id })
        db.favoriteDao().toggle("77", StreamType.VOD)
        assertEquals(ids + "77", db.vodStreamDao().observeFavorites().first().map { it.id })
    }

    @Test
    fun `shows favorites list in the order they were added`() = runBlocking {
        seed()
        ids.forEach { db.favoriteDao().toggle(it, StreamType.SERIES) }
        assertEquals(ids, db.seriesStreamDao().observeFavorites().first().map { it.id })
        db.favoriteDao().toggle("77", StreamType.SERIES)
        assertEquals(ids + "77", db.seriesStreamDao().observeFavorites().first().map { it.id })
    }

    @Test
    fun `a reorder and a removal carry through, and other catalogs do not leak in`() = runBlocking {
        seed()
        val dao = db.favoriteDao()
        ids.forEach { dao.toggle(it, StreamType.VOD) }
        dao.toggle("2", StreamType.SERIES) // same id, other catalog
        dao.reorder(StreamType.VOD, listOf("2", "9", "10"))
        assertEquals(listOf("2", "9", "10"), db.vodStreamDao().observeFavorites().first().map { it.id })
        dao.remove("9", StreamType.VOD)
        assertEquals(listOf("2", "10"), db.vodStreamDao().observeFavorites().first().map { it.id })
        assertEquals(listOf("2"), db.seriesStreamDao().observeFavorites().first().map { it.id })
    }

    @Test
    fun `ties at position 0 list newest first, with no duplicates and unknown ids skipped`() = runBlocking {
        seed()
        val dao = db.favoriteDao()
        fun fav(id: String, type: StreamType, at: Long) = Favorite(id, type, Instant.ofEpochMilli(at), position = 0)
        dao.insertAll(
            listOf(
                fav("9", StreamType.VOD, 1000), fav("2", StreamType.VOD, 3000), fav("10", StreamType.VOD, 2000),
                fav("gone", StreamType.VOD, 4000),
                fav("9", StreamType.SERIES, 1000), fav("2", StreamType.SERIES, 3000), fav("10", StreamType.SERIES, 2000),
            ),
        )
        assertEquals(listOf("2", "10", "9"), db.vodStreamDao().observeFavorites().first().map { it.id })
        assertEquals(listOf("2", "10", "9"), db.seriesStreamDao().observeFavorites().first().map { it.id })
    }
}
