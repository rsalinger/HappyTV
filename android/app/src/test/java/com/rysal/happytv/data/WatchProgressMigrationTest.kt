package com.rysal.happytv.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.WatchProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * #78: the v4 -> v5 upgrade (watch progress table, `added` / `lastModified` indexes), proven on a
 * realistic v4 database. This is the only proof the schema change gets before it reaches a
 * device (Android cannot downgrade a Room database), so the v4 file is built **from the
 * exported `app/schemas/.../4.json`** - every table, index and setup query exactly as Room wrote
 * them - and filled with the kinds of rows a real install holds: favorites, hidden categories
 * and channels, recents, a guide, live channels, and VOD / series rows with `added` stamps.
 *
 * Room validates the migrated file against the v5 schema when it opens, so a migration whose
 * DDL drifted from `5.json` fails here, not on the TV.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class WatchProgressMigrationTest {

    private lateinit var context: Application
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.cacheDir, "happytv-v4-${counter++}.db")
        deleteFiles()
    }

    @After
    fun tearDown() = deleteFiles()

    private fun deleteFiles() = listOf("", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }

    /** `app/schemas/<db>/<version>.json`; Gradle runs unit tests with `app/` as the working directory. */
    private fun schema(version: Int): JSONObject {
        val file = File("schemas/${HappyTvDatabase::class.java.name}/$version.json")
        assertTrue("missing exported schema ${file.absolutePath}", file.isFile)
        return JSONObject(file.readText()).getJSONObject("database")
    }

    /** Creates the v4 file: Room's own exported DDL and setup queries, then realistic rows. */
    private fun buildV4Database() {
        val v4 = schema(4)
        assertEquals(4, v4.getInt("version"))
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.name)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = v4.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices")
                            for (j in 0 until (indices?.length() ?: 0)) {
                                db.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                            }
                        }
                        val setup = v4.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                        seedV4(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use { it.version = 4 }
        helper.close()
    }

    private fun seedV4(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO categories (id, type, name, parentId, isHidden) VALUES ('10', 'VOD', 'Drama', NULL, 0)")
        db.execSQL("INSERT INTO categories (id, type, name, parentId, isHidden) VALUES ('11', 'VOD', 'Adult', NULL, 1)")
        db.execSQL("INSERT INTO categories (id, type, name, parentId, isHidden) VALUES ('20', 'SERIES', 'Comedy', NULL, 0)")
        db.execSQL("INSERT INTO categories (id, type, name, parentId, isHidden) VALUES ('1', 'LIVE', 'News', NULL, 0)")
        for (i in 1..200) {
            db.execSQL(
                "INSERT INTO vod_streams (id, categoryId, num, name, streamIcon, containerExtension, rating, added) " +
                    "VALUES ('m$i', '10', $i, 'Movie $i', 'https://img.example/m$i.jpg', 'mkv', 7.5, ${1_700_000_000L + i})",
            )
        }
        // A provider row with no stamp at all stays unstamped through the upgrade.
        db.execSQL("INSERT INTO vod_streams (id, categoryId, num, name) VALUES ('undated', '10', 201, 'Undated')")
        for (i in 1..40) {
            db.execSQL(
                "INSERT INTO series_streams (id, categoryId, num, name, cover, releaseDate, lastModified) " +
                    "VALUES ('s$i', '20', $i, 'Show $i', 'https://img.example/s$i.jpg', '2020-01-01', ${1_700_100_000L + i})",
            )
        }
        for (i in 1..30) {
            db.execSQL(
                "INSERT INTO live_streams (id, categoryId, num, name, tvArchive, epgChannelId) " +
                    "VALUES ('c$i', '1', $i, 'Channel $i', ${i % 2}, 'epg.$i')",
            )
        }
        for (i in 0 until 25) {
            db.execSQL(
                "INSERT INTO epg_programs (channelId, title, description, startUtcMillis, endUtcMillis) " +
                    "VALUES ('epg.1', 'Program $i', NULL, ${1_000L + i * 1_000L}, ${2_000L + i * 1_000L})",
            )
        }
        db.execSQL("INSERT INTO favorites (streamId, streamType, addedAt) VALUES ('m5', 'VOD', 1700000000000)")
        db.execSQL("INSERT INTO favorites (streamId, streamType, addedAt) VALUES ('s3', 'SERIES', 1700000001000)")
        db.execSQL("INSERT INTO favorites (streamId, streamType, addedAt) VALUES ('c2', 'LIVE', 1700000002000)")
        db.execSQL("INSERT INTO hidden_categories (categoryId, categoryType) VALUES ('11', 'VOD')")
        db.execSQL("INSERT INTO hidden_channels (streamId, streamType) VALUES ('c7', 'LIVE')")
        db.execSQL("INSERT INTO recent_channels (streamId, lastWatchedUtcMillis) VALUES ('c1', 1700000003000)")
        db.execSQL("INSERT INTO recent_channels (streamId, lastWatchedUtcMillis) VALUES ('c4', 1700000004000)")
    }

    private fun open(): HappyTvDatabase =
        Room.databaseBuilder(context, HappyTvDatabase::class.java, dbFile.name)
            // The production list, exactly: a v4 file must reach v5 through the app's own migrations.
            .addMigrations(
                HappyTvDatabase.MIGRATION_1_2,
                HappyTvDatabase.MIGRATION_2_3,
                HappyTvDatabase.MIGRATION_3_4,
                HappyTvDatabase.MIGRATION_4_5,
            )
            .build()

    private fun HappyTvDatabase.names(sql: String, column: Int = 0): List<String> =
        openHelper.readableDatabase.query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(column)) } }

    private fun HappyTvDatabase.count(table: String): Int =
        openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun `a v4 file built from the exported schema opens as v5 with every row intact`() = runBlocking {
        buildV4Database()
        val db = open()
        try {
            // Opening validates the migrated file against the v5 schema; touching a table runs it.
            assertEquals(5, db.openHelper.readableDatabase.version)
            assertEquals(201, db.vodStreamDao().count())
            assertEquals(40, db.seriesStreamDao().count())
            assertEquals(30, db.liveStreamDao().count())
            assertEquals(25, db.count("epg_programs"))
            assertEquals(4, db.count("categories"))

            // Favorites, hidden items and recents are what a family loses if a migration goes wrong.
            assertEquals(setOf("m5"), db.favoriteDao().observeForType(StreamType.VOD).first().map { it.streamId }.toSet())
            assertEquals(setOf("s3"), db.favoriteDao().observeForType(StreamType.SERIES).first().map { it.streamId }.toSet())
            assertEquals(setOf("c2"), db.favoriteDao().observeForType(StreamType.LIVE).first().map { it.streamId }.toSet())
            assertEquals(listOf("11"), db.names("SELECT categoryId FROM hidden_categories"))
            assertEquals(listOf("c7"), db.names("SELECT streamId FROM hidden_channels"))
            assertEquals(listOf("c4", "c1"), db.recentChannelDao().recent().first().map { it.streamId })
            assertEquals(
                "the hidden flag on a category survives",
                listOf("11"),
                db.names("SELECT id FROM categories WHERE type = 'VOD' AND isHidden = 1"),
            )

            // Catalog rows keep their values, including the `added` stamps the new index covers.
            val movie = db.vodStreamDao().getById("m42")!!
            assertEquals("Movie 42", movie.name)
            assertEquals(1_700_000_042L, movie.added)
            assertEquals("https://img.example/m42.jpg", movie.streamIcon)
            assertEquals(null, db.vodStreamDao().getById("undated")!!.added)
            assertEquals(1_700_100_007L, db.seriesStreamDao().getById("s7")!!.lastModified)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the upgrade adds the watch_progress table and the three new indexes`() = runBlocking {
        buildV4Database()
        val db = open()
        try {
            assertEquals(
                listOf("streamType", "streamId", "seriesId", "seasonNumber", "episodeNumber", "positionMillis", "durationMillis", "updatedUtcMillis", "finishedAt"),
                db.names("SELECT name FROM pragma_table_info('watch_progress') ORDER BY cid"),
            )
            assertEquals(0, db.count("watch_progress"))
            assertEquals(
                listOf("index_watch_progress_seriesId", "index_watch_progress_updatedUtcMillis"),
                db.names("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'watch_progress' AND name LIKE 'index_%' ORDER BY name"),
            )
            assertTrue(
                db.names("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'vod_streams'")
                    .contains("index_vod_streams_added"),
            )
            assertTrue(
                db.names("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'series_streams'")
                    .contains("index_series_streams_lastModified"),
            )
            // The v4 indexes are still there.
            assertTrue(
                db.names("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'vod_streams'")
                    .containsAll(listOf("index_vod_streams_categoryId", "index_vod_streams_name")),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `the migrated database takes watch progress and answers Recently added from the index`() = runBlocking {
        buildV4Database()
        val db = open()
        try {
            val dao = db.watchProgressDao()
            dao.upsert(WatchProgress(StreamType.VOD, "m42", null, null, null, 2_480_000L, 7_200_000L, 5L))
            dao.upsert(WatchProgress(StreamType.VOD, "m43", null, null, null, 7_000_000L, 7_200_000L, 6L, finishedAt = 6L))
            dao.upsert(WatchProgress(StreamType.SERIES, "ep9", "s3", 2, 3, 600_000L, 2_400_000L, 6L))
            assertEquals(listOf("m42"), dao.observeContinueMovies(10).first().map { it.id })
            assertEquals(listOf("s3"), dao.observeContinueShows(10).first().map { it.id })

            val recent = db.vodStreamDao().observeRecentlyAdded(3).first()
            assertEquals(listOf("m200", "m199", "m198"), recent.map { it.id })

            val plan = db.names("EXPLAIN QUERY PLAN SELECT id FROM vod_streams WHERE added IS NOT NULL ORDER BY added DESC, rowid DESC LIMIT 480", column = 3)
            assertTrue("Recently added must use index_vod_streams_added, plan was $plan", plan.any { "index_vod_streams_added" in it })
            assertTrue("no sort step expected, plan was $plan", plan.none { "TEMP B-TREE" in it })
            assertNotNull(db.vodStreamDao().getById("m1"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a v4 file reaches v5 without any destructive fallback`() {
        // Opening with no fallbackToDestructiveMigration() and only the real migrations is the
        // whole claim: a missing step would throw IllegalStateException instead of wiping.
        buildV4Database()
        val db = open()
        try {
            db.openHelper.readableDatabase
        } finally {
            db.close()
        }
    }

    companion object {
        private var counter = 0
    }
}
