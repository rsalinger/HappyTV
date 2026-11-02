package com.rysal.happytv.data

import android.app.Application
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.LoginProfile
import com.rysal.happytv.data.entity.SeriesStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Builds the v1 schema in memory (Robolectric supplies real SQLite) and
 * round-trips one row of every entity through every DAO.
 *
 * `@Config(application = Application::class)` pins the plain framework
 * Application so app-startup code (Graph wiring, cert pinning) stays out of
 * scope — this test is about the schema, converters and DAO SQL.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class HappyTvDatabaseRoundTripTest {

    private lateinit var db: HappyTvDatabase

    @Before
    fun createDb() {
        // allowMainThreadQueries is TEST-ONLY: Robolectric runs the whole test on
        // the main looper thread. Graph/HappyTvDatabase.build() never set it, so
        // production callers keep Room's main-thread guard.
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HappyTvDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun createsEveryEntityTable() {
        val expected = setOf(
            "login_profiles",
            "categories",
            "live_streams",
            "vod_streams",
            "series_streams",
            "epg_programs",
            "favorites",
            "hidden_categories",
            "hidden_channels",
            "recent_channels",
        )
        val actual = mutableSetOf<String>()
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .use { cursor: Cursor ->
                while (cursor.moveToNext()) {
                    actual.add(cursor.getString(0))
                }
            }
        val missing = expected - actual
        assertTrue("tables not created: $missing (created: $actual)", missing.isEmpty())
    }

    @Test
    fun roundTripsOneRowOfEachEntity() = runBlocking<Unit> {
        // --- LoginProfile: upsert + observe + single-row semantics -------------
        val profile = LoginProfile(
            name = "My Provider",
            host = "panel.example.com",
            port = 8080,
            username = "user42",
            password = "AES:deadbeef==", // ciphertext — never plaintext in this table
            useTls = false,
        )
        db.loginProfileDao().upsert(profile)
        val loaded = db.loginProfileDao().get()
        assertNotNull(loaded)
        assertEquals("panel.example.com", loaded!!.host)
        assertTrue(loaded.useTls == false)
        assertEquals("AES:deadbeef==", db.loginProfileDao().observe().first()?.password)
        db.loginProfileDao().upsert(profile.copy(host = "panel2.example.com"))
        assertEquals(1, countRows("login_profiles"))
        assertEquals("panel2.example.com", db.loginProfileDao().get()?.host)

        // --- Category: bulk replace, visibility filter, hidden sync ------------
        val news = Category(id = "7", type = StreamType.LIVE, name = "News")
        val sports = Category(id = "8", type = StreamType.LIVE, name = "Sports")
        db.categoryDao().replaceAll(StreamType.LIVE, listOf(news, sports))
        assertEquals(
            listOf("News", "Sports"),
            db.categoryDao().observeVisible(StreamType.LIVE).first().map { it.name },
        )

        // Hiding flips the denormalized flag AND writes the durable row.
        db.categoryDao().toggleHidden("7", StreamType.LIVE)
        assertEquals(
            listOf("Sports"),
            db.categoryDao().observeVisible(StreamType.LIVE).first().map { it.name },
        )
        assertTrue(db.hiddenCategoryDao().isHidden("7", StreamType.LIVE))
        assertTrue(db.categoryDao().isHidden("7", StreamType.LIVE) == true)

        // A refresh (bulk replace) must NOT un-hide the category.
        db.categoryDao().replaceAll(StreamType.LIVE, listOf(news, sports))
        assertEquals(
            listOf("Sports"),
            db.categoryDao().observeVisible(StreamType.LIVE).first().map { it.name },
        )

        // Un-hiding clears both tables again.
        db.categoryDao().toggleHidden("7", StreamType.LIVE)
        assertFalse(db.hiddenCategoryDao().isHidden("7", StreamType.LIVE))
        assertEquals(2, db.categoryDao().observeVisible(StreamType.LIVE).first().size)

        // HiddenCategoryDao.toggle works even when no category row exists yet.
        db.hiddenCategoryDao().toggle("99", StreamType.VOD)
        assertTrue(db.hiddenCategoryDao().isHidden("99", StreamType.VOD))
        assertEquals(listOf("99"), db.hiddenCategoryDao().observe(StreamType.VOD).first().map { it.categoryId })
        db.hiddenCategoryDao().toggle("99", StreamType.VOD)
        assertFalse(db.hiddenCategoryDao().isHidden("99", StreamType.VOD))

        // --- LiveStream: replace, category query, search, id lookup ------------
        val cnn = LiveStream(
            id = "101", categoryId = "7", num = 1, name = "CNN HD",
            streamIcon = "https://cdn.example.com/cnn.png", epgChannelId = "cnn.us",
            tvArchive = true, tvArchiveDuration = 7, containerExtension = "ts",
            added = 1_700_000_000L, customSid = "", directSource = null,
        )
        val bbc = cnn.copy(id = "102", num = 2, name = "BBC One", epgChannelId = "bbc.uk", tvArchive = false, tvArchiveDuration = null)
        db.liveStreamDao().replaceAll(listOf(cnn, bbc))
        assertEquals(2, db.liveStreamDao().count())
        assertEquals(
            listOf("CNN HD", "BBC One"),
            db.liveStreamDao().observeByCategory("7").first().map { it.name },
        )
        assertEquals(listOf("CNN HD"), db.liveStreamDao().search("cnn").first().map { it.name })
        val liveRow = db.liveStreamDao().getById("101")
        assertNotNull(liveRow)
        assertTrue(liveRow!!.tvArchive)
        assertTrue(liveRow.tvArchiveDuration == 7)
        assertTrue(liveRow.added == 1_700_000_000L)
        assertEquals("cnn.us", liveRow.epgChannelId)

        // --- VodStream ---------------------------------------------------------
        val movie = VodStream(
            id = "501", categoryId = "21", num = 3, name = "Inception",
            streamIcon = null, containerExtension = "mp4", rating = 8.8,
            added = 1_600_000_000L, customSid = null, directSource = null,
        )
        db.vodStreamDao().replaceAll(listOf(movie))
        assertEquals(listOf("Inception"), db.vodStreamDao().observeByCategory("21").first().map { it.name })
        assertEquals(listOf("Inception"), db.vodStreamDao().search("cept").first().map { it.name })
        assertTrue(db.vodStreamDao().getById("501")?.rating == 8.8)
        assertEquals(1, db.vodStreamDao().count())

        // --- SeriesStream ------------------------------------------------------
        val show = SeriesStream(
            id = "9001", categoryId = "31", num = 1, name = "Breaking Bad",
            cover = "https://cdn.example.com/bb.jpg", plot = "A chemistry teacher turns to crime.",
            cast = "Bryan Cranston", director = "Vince Gilligan", genre = "Crime",
            releaseDate = "2008-01-20", lastModified = 1_650_000_000L, rating = 9.5,
            backdropPath = "[\"https://cdn.example.com/bb-bd.jpg\"]",
            youtubeTrailer = "https://www.youtube.com/watch?v=example",
        )
        db.seriesStreamDao().replaceAll(listOf(show))
        assertEquals(listOf("Breaking Bad"), db.seriesStreamDao().observeByCategory("31").first().map { it.name })
        assertEquals(listOf("Breaking Bad"), db.seriesStreamDao().search("breaking").first().map { it.name })
        val seriesRow = db.seriesStreamDao().getById("9001")
        assertTrue(seriesRow?.rating == 9.5)
        assertEquals("2008-01-20", seriesRow?.releaseDate)

        // --- EpgProgram: overlap query + keep-window pruning --------------------
        val programme = EpgProgram(
            channelId = "cnn.us", title = "Newsroom", description = "Rolling news",
            startUtcMillis = 1_000L, endUtcMillis = 2_000L,
        )
        db.epgProgramDao().replaceAll(listOf(programme))
        val inWindow = db.epgProgramDao().getBetween("cnn.us", 1_500L, 3_000L)
        assertEquals(1, inWindow.size)
        assertEquals("Newsroom", inWindow.first().title)
        assertTrue(db.epgProgramDao().getBetween("cnn.us", 2_001L, 3_000L).isEmpty())
        assertEquals(1, db.epgProgramDao().observeBetween("cnn.us", 1_500L, 3_000L).first().size)
        db.epgProgramDao().deleteBefore(1_500L) // programme ends at 2000 → still in window
        assertEquals(1, db.epgProgramDao().count())
        db.epgProgramDao().deleteBefore(2_001L) // now it is pruned
        assertEquals(0, db.epgProgramDao().count())

        // --- Favorite: toggle + Instant converter round-trip --------------------
        val beforeToggleUtcMillis = System.currentTimeMillis()
        db.favoriteDao().toggle("101", StreamType.LIVE)
        assertTrue(db.favoriteDao().isFavorite("101", StreamType.LIVE))
        val favorite = db.favoriteDao().observeForType(StreamType.LIVE).first().single()
        assertTrue(favorite.addedAt.toEpochMilli() in (beforeToggleUtcMillis - 1_000)..(System.currentTimeMillis() + 1_000))
        db.favoriteDao().toggle("101", StreamType.LIVE)
        assertFalse(db.favoriteDao().isFavorite("101", StreamType.LIVE))
        assertTrue(db.favoriteDao().observeForType(StreamType.LIVE).first().isEmpty())

        // --- RecentChannel: recordWatch + newest-first ordering -----------------
        db.recentChannelDao().recordWatch("501", 1_700_000_000_000L)
        db.recentChannelDao().recordWatch("101", 1_700_000_100_000L)
        assertEquals(listOf("101", "501"), db.recentChannelDao().recent().first().map { it.streamId })
        db.recentChannelDao().recordWatch("501", 1_700_000_200_000L) // upsert, not append
        assertEquals(listOf("501", "101"), db.recentChannelDao().recent().first().map { it.streamId })
        assertEquals(2, countRows("recent_channels"))
        // F-53's DAO: remove drops exactly the one row it names.
        db.recentChannelDao().remove("501")
        assertEquals(listOf("101"), db.recentChannelDao().recent().first().map { it.streamId })
        assertEquals(1, countRows("recent_channels"))
    }

    /** Raw row count — proves single-row/upsert semantics the DAOs don't expose. */
    private fun countRows(table: String): Int {
        var count = -1
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table")
            .use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    count = cursor.getInt(0)
                }
            }
        return count
    }
}
