package com.rysal.happytv.data.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.entity.SeriesStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.entity.WatchProgress
import com.rysal.happytv.data.entity.WatchProgressPolicy
import com.rysal.happytv.data.entity.WatchProgressPolicy.Decision
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #78: the watch-progress queries behind Continue watching and Resume, and the write rules. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class WatchProgressDaoTest {

    private lateinit var db: HappyTvDatabase

    @Before
    fun setUp() {
        db = HappyTvDatabase.build(ApplicationProvider.getApplicationContext<Application>(), inMemory = true)
    }

    @After
    fun tearDown() = db.close()

    private val dao get() = db.watchProgressDao()

    private fun movie(id: String, position: Long, at: Long, duration: Long = 7_200_000L) =
        WatchProgress(StreamType.VOD, id, null, null, null, position, duration, at)

    private fun episode(id: String, series: String, season: Int, number: Int, at: Long, position: Long = 600_000L) =
        WatchProgress(StreamType.SERIES, id, series, season, number, position, 2_400_000L, at)

    private suspend fun seedCatalog() {
        db.vodStreamDao().insertAll(
            listOf(
                VodStream(id = "m1", categoryId = "c", name = "First", streamIcon = "https://img.example/1.jpg"),
                VodStream(id = "m2", categoryId = "c", name = "Second"),
                VodStream(id = "m3", categoryId = "c", name = "Third"),
            ),
        )
        db.seriesStreamDao().insertAll(
            listOf(
                SeriesStream(id = "s1", categoryId = "c", name = "Show one", cover = "https://img.example/s1.jpg", releaseDate = "2021-05-01"),
                SeriesStream(id = "s2", categoryId = "c", name = "Show two"),
            ),
        )
    }

    @Test
    fun `continue movies list newest first with their position and poster`() = runBlocking {
        seedCatalog()
        dao.upsert(movie("m1", 1_000_000L, at = 10))
        dao.upsert(movie("m3", 2_000_000L, at = 30))
        dao.upsert(movie("m2", 3_000_000L, at = 20))

        val rows = dao.observeContinueMovies(10).first()
        assertEquals(listOf("m3", "m2", "m1"), rows.map { it.id })
        assertEquals(1_000_000L, rows.last().positionMillis)
        assertEquals(7_200_000L, rows.last().durationMillis)
        assertEquals("https://img.example/1.jpg", rows.last().posterUrl)
        assertEquals(2, dao.observeContinueMovies(2).first().size)
    }

    @Test
    fun `a movie that left the catalog drops out of continue movies`() = runBlocking {
        seedCatalog()
        dao.upsert(movie("gone", 1_000_000L, at = 10))
        dao.upsert(movie("m1", 1_000_000L, at = 5))
        assertEquals(listOf("m1"), dao.observeContinueMovies(10).first().map { it.id })
    }

    @Test
    fun `saving again replaces the row instead of adding one`() = runBlocking {
        seedCatalog()
        dao.upsert(movie("m1", 1_000_000L, at = 10))
        dao.upsert(movie("m1", 3_000_000L, at = 20))
        assertEquals(1, dao.observeContinueMovies(10).first().size)
        assertEquals(3_000_000L, dao.getMovie("m1")!!.positionMillis)
    }

    @Test
    fun `a show is one entry at its latest episode`() = runBlocking {
        seedCatalog()
        dao.upsert(episode("e1", "s1", 1, 1, at = 10))
        dao.upsert(episode("e2", "s1", 1, 2, at = 40, position = 900_000L))
        dao.upsert(episode("e3", "s1", 1, 3, at = 20))
        dao.upsert(episode("x1", "s2", 3, 4, at = 30))

        val rows = dao.observeContinueShows(10).first()
        assertEquals(listOf("s1", "s2"), rows.map { it.id })
        val first = rows.first()
        assertEquals(1, first.seasonNumber)
        assertEquals(2, first.episodeNumber)
        assertEquals(900_000L, first.positionMillis)
        assertEquals("2021", first.year)
        assertEquals(3, rows.last().seasonNumber)
        assertEquals(4, rows.last().episodeNumber)
    }

    @Test
    fun `movies and shows keep separate rows even when ids collide`() = runBlocking {
        seedCatalog()
        dao.upsert(movie("m1", 1_000_000L, at = 10))
        dao.upsert(WatchProgress(StreamType.SERIES, "m1", "s1", 1, 1, 500_000L, 2_400_000L, 11))
        assertEquals(1_000_000L, dao.getMovie("m1")!!.positionMillis)
        assertEquals(listOf("m1"), dao.observeContinueMovies(10).first().map { it.id })
        assertEquals(listOf("s1"), dao.observeContinueShows(10).first().map { it.id })
    }

    @Test
    fun `details read the latest episode and every episode in progress`() = runBlocking {
        dao.upsert(episode("e1", "s1", 1, 1, at = 10))
        dao.upsert(episode("e2", "s1", 2, 3, at = 50))
        val latest = dao.observeLatestEpisode("s1").first()!!
        assertEquals("e2", latest.streamId)
        assertEquals(2, latest.seasonNumber)
        assertEquals(3, latest.episodeNumber)
        assertEquals(setOf("e1", "e2"), dao.observeEpisodes("s1").first().map { it.streamId }.toSet())
        assertNull(dao.observeLatestEpisode("other").first())
    }

    @Test
    fun `deleting a movie, an episode or a whole show removes only those rows`() = runBlocking {
        seedCatalog()
        dao.upsert(movie("m1", 1_000_000L, at = 10))
        dao.upsert(movie("m2", 1_000_000L, at = 11))
        dao.upsert(episode("e1", "s1", 1, 1, at = 12))
        dao.upsert(episode("e2", "s1", 1, 2, at = 13))
        dao.upsert(episode("x1", "s2", 1, 1, at = 14))

        dao.deleteMovie("m1")
        assertEquals(listOf("m2"), dao.observeContinueMovies(10).first().map { it.id })
        dao.deleteEpisode("e2")
        assertEquals(setOf("e1"), dao.observeEpisodes("s1").first().map { it.streamId }.toSet())
        dao.deleteSeries("s1")
        assertEquals(listOf("s2"), dao.observeContinueShows(10).first().map { it.id })
        assertEquals("a movie row is untouched by deleting a show", listOf("m2"), dao.observeContinueMovies(10).first().map { it.id })
    }

    @Test
    fun `finished movies leave Continue watching and Resume but keep their row`() = runBlocking {
        seedCatalog()
        dao.upsert(movie("m1", 1_000_000L, at = 10))
        dao.upsert(movie("m2", 7_000_000L, at = 20).copy(finishedAt = 20))
        assertEquals(listOf("m1"), dao.observeContinueMovies(10).first().map { it.id })
        assertNull("Resume ignores a finished movie", dao.observeMovie("m2").first())
        assertEquals(7_000_000L, dao.getMovie("m2")!!.positionMillis)
        assertEquals(20L, dao.getMovie("m2")!!.finishedAt)
    }

    @Test
    fun `a finished episode drops out of Continue watching unless a later one is in progress`() = runBlocking {
        seedCatalog()
        dao.upsert(episode("e1", "s1", 1, 1, at = 10).copy(finishedAt = 10))
        assertTrue(dao.observeContinueShows(10).first().isEmpty())
        assertNull(dao.observeLatestEpisode("s1").first())

        // The next episode, up next at position 0, brings the show back, newer than the finished one.
        dao.upsert(WatchProgress(StreamType.SERIES, "e2", "s1", 1, 2, 0L, 0L, 11L))
        val row = dao.observeContinueShows(10).first().single()
        assertEquals("s1", row.id)
        assertEquals(2, row.episodeNumber)
        assertEquals(0L, row.positionMillis)
        assertEquals("e2", dao.observeLatestEpisode("s1").first()!!.streamId)
    }

    @Test
    fun `a finished older episode never hides the unfinished one`() = runBlocking {
        seedCatalog()
        dao.upsert(episode("e1", "s1", 1, 1, at = 50).copy(finishedAt = 50))
        dao.upsert(episode("e2", "s1", 1, 2, at = 10))
        assertEquals("e2", dao.observeContinueShows(10).first().single().let { dao.observeLatestEpisode(it.id).first()!!.streamId })
    }

    @Test
    fun `the episode list still sees finished rows so they can be marked watched`() = runBlocking {
        dao.upsert(episode("e1", "s1", 1, 1, at = 10).copy(finishedAt = 10))
        dao.upsert(episode("e2", "s1", 1, 2, at = 11))
        val rows = dao.observeEpisodes("s1").first().associateBy { it.streamId }
        assertEquals(10L, rows.getValue("e1").finishedAt)
        assertNull(rows.getValue("e2").finishedAt)
    }

    @Test
    fun `recently added movies come off the index newest first and skip unstamped rows`() = runBlocking {
        db.vodStreamDao().insertAll(
            listOf(
                VodStream(id = "old", categoryId = "c", name = "Old", added = 100L),
                VodStream(id = "undated", categoryId = "c", name = "Undated"),
                VodStream(id = "new", categoryId = "c", name = "New", added = 900L),
                VodStream(id = "tie", categoryId = "c", name = "Tie", added = 900L),
            ),
        )
        // A tie goes to the row stored later (the higher rowid), deterministically.
        assertEquals(listOf("tie", "new", "old"), db.vodStreamDao().observeRecentlyAdded(10).first().map { it.id })
        assertEquals(listOf("tie"), db.vodStreamDao().observeRecentlyAdded(1).first().map { it.id })
        assertTrue(db.vodStreamDao().hasAddedStamps())
    }

    @Test
    fun `recently added shows order by last modified`() = runBlocking {
        db.seriesStreamDao().insertAll(
            listOf(
                SeriesStream(id = "a", categoryId = "c", name = "A", lastModified = 5L),
                SeriesStream(id = "b", categoryId = "c", name = "B"),
                SeriesStream(id = "c", categoryId = "c", name = "C", lastModified = 50L, releaseDate = "2019"),
            ),
        )
        val rows = db.seriesStreamDao().observeRecentlyAdded(10).first()
        assertEquals(listOf("c", "a"), rows.map { it.id })
        assertEquals("2019", rows.first().year)
    }

    // ------------------------------------------------------------- the write rules

    @Test
    fun `under a minute in nothing is saved`() {
        assertEquals(Decision.IGNORE, WatchProgressPolicy.decide(0L, 7_200_000L))
        assertEquals(Decision.IGNORE, WatchProgressPolicy.decide(59_999L, 7_200_000L))
        assertEquals(Decision.SAVE, WatchProgressPolicy.decide(60_000L, 7_200_000L))
    }

    @Test
    fun `five minutes or less left is finished`() {
        val hour = 3_600_000L
        assertEquals(Decision.SAVE, WatchProgressPolicy.decide(hour - 300_001L, hour))
        assertEquals(Decision.FINISHED, WatchProgressPolicy.decide(hour - 300_000L, hour))
        assertEquals(Decision.FINISHED, WatchProgressPolicy.decide(hour, hour))
    }

    @Test
    fun `past 95 percent is finished even with more than five minutes left`() {
        // 95% of 2 hours leaves 6 minutes: the fraction is what ends it.
        val two = 7_200_000L
        assertEquals(Decision.SAVE, WatchProgressPolicy.decide(6_840_000L, two))
        assertEquals(Decision.FINISHED, WatchProgressPolicy.decide(6_840_001L, two))
    }

    @Test
    fun `an unknown length saves once a minute in and never finishes`() {
        assertEquals(Decision.SAVE, WatchProgressPolicy.decide(3_600_000L, 0L))
        assertEquals(Decision.IGNORE, WatchProgressPolicy.decide(10_000L, 0L))
    }

    @Test
    fun `positions read as clock times`() {
        assertEquals("41:20", WatchProgressPolicy.clock(2_480_000L))
        assertEquals("1:05:09", WatchProgressPolicy.clock(3_909_000L))
        assertEquals("0:00", WatchProgressPolicy.clock(-5L))
    }

    @Test
    fun `fraction is the share watched, and unknown without a length`() {
        assertEquals(0.5f, movie("m", 3_600_000L, 1).fraction!!, 0.0001f)
        assertNull(movie("m", 3_600_000L, 1, duration = 0L).fraction)
    }
}
