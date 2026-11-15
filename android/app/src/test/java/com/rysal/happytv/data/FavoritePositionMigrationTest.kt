package com.rysal.happytv.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.StreamType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * #221: the v5 -> v6 upgrade (a stored position for favorites), proven on a realistic v5 database.
 * As for the earlier steps the file is built **from the exported `app/schemas/.../5.json`** and
 * filled with what a real install holds: favorites of every stream type with distinct `addedAt`
 * values (and one tie), plus rows in the other tables. Room validates the migrated file against
 * `6.json` when it opens, so DDL that drifted from the exported schema fails here, not on a TV.
 *
 * The order kept is the one the favorites screens show today: `WHERE id IN (...)` with no sort after it,
 * which SQLite answers through the text primary key, so it is the stream id as text. The fixture makes
 * that differ from the channel number, from the order rows were inserted (rowid) and from the order the
 * favorites were added, so a seed that used any of those would fail.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FavoritePositionMigrationTest {

    private lateinit var context: Application
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.cacheDir, "happytv-v5-${counter++}.db")
        deleteFiles()
    }

    @After
    fun tearDown() = deleteFiles()

    private fun deleteFiles() = listOf("", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }

    private fun schema(version: Int): JSONObject {
        val file = File("schemas/${HappyTvDatabase::class.java.name}/$version.json")
        assertTrue("missing exported schema ${file.absolutePath}", file.isFile)
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private fun buildV5Database() {
        val v5 = schema(5)
        assertEquals(5, v5.getInt("version"))
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.name)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = v5.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices")
                            for (j in 0 until (indices?.length() ?: 0)) {
                                db.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                            }
                        }
                        val setup = v5.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                        seedV5(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use { it.version = 5 }
        helper.close()
    }

    private fun seedV5(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO categories (id, type, name, parentId, isHidden) VALUES ('1', 'LIVE', 'News', NULL, 0)")
        db.execSQL("INSERT INTO categories (id, type, name, parentId, isHidden) VALUES ('10', 'VOD', 'Drama', NULL, 0)")
        // Inserted (rowid) in this order, with these channel numbers: for the favorites below the
        // number order is c9 c4 c1 c7 c6 c3, the rowid order c3 c6 c9 c1 c4 c7, the id order c1 c3 c4 c6 c7 c9.
        val liveNums = linkedMapOf(
            "c3" to 6, "c6" to 5, "c9" to 1, "c1" to 3, "c4" to 2, "c7" to 4,
            "c2" to 7, "c5" to 8, "c8" to 9, "c10" to 10, "c11" to 11, "c12" to 12,
        )
        for ((id, num) in liveNums) {
            db.execSQL(
                "INSERT INTO live_streams (id, categoryId, num, name, tvArchive, epgChannelId) " +
                    "VALUES ('$id', '1', $num, 'Channel $id', ${num % 2}, 'epg.$id')",
            )
        }
        for (i in 1..5) {
            db.execSQL(
                "INSERT INTO vod_streams (id, categoryId, num, name, added) VALUES ('m$i', '10', $i, 'Movie $i', ${1_700_000_000L + i})",
            )
        }
        // Live favorites, deliberately not added in channel order. addedAt (millis): newest first is
        // c9 c4 c6 c1 c7 c3 (c9 was added last).
        // c4 and c6 share a stamp (a double press): the stream id breaks the tie, c4 first.
        for ((id, at) in listOf(
            "c3" to 1_700_000_001_000L, "c9" to 1_700_000_009_000L, "c1" to 1_700_000_004_000L,
            "c7" to 1_700_000_002_000L, "c4" to 1_700_000_006_000L, "c6" to 1_700_000_006_000L,
        )) {
            db.execSQL("INSERT INTO favorites (streamId, streamType, addedAt) VALUES ('$id', 'LIVE', $at)")
        }
        // The same ids in other catalogs are unrelated rows, numbered on their own.
        // Two live favorites whose channel left the catalog: not shown today, so they go last, newest first.
        db.execSQL("INSERT INTO favorites (streamId, streamType, addedAt) VALUES ('gone1', 'LIVE', 1700000010000)")
        db.execSQL("INSERT INTO favorites (streamId, streamType, addedAt) VALUES ('gone2', 'LIVE', 1700000003000)")
        db.execSQL("INSERT INTO favorites (streamId, streamType, addedAt) VALUES ('c3', 'VOD', 1700000003000)")
        db.execSQL("INSERT INTO favorites (streamId, streamType, addedAt) VALUES ('m2', 'VOD', 1700000007000)")
        db.execSQL("INSERT INTO favorites (streamId, streamType, addedAt) VALUES ('m5', 'VOD', 1700000005000)")
        db.execSQL("INSERT INTO favorites (streamId, streamType, addedAt) VALUES ('s8', 'SERIES', 1700000001000)")
        db.execSQL("INSERT INTO hidden_categories (categoryId, categoryType) VALUES ('10', 'VOD')")
        db.execSQL("INSERT INTO hidden_channels (streamId, streamType) VALUES ('c7', 'LIVE')")
        db.execSQL("INSERT INTO recent_channels (streamId, lastWatchedUtcMillis) VALUES ('c1', 1700000003000)")
        db.execSQL(
            "INSERT INTO watch_progress (streamType, streamId, seriesId, seasonNumber, episodeNumber, positionMillis, durationMillis, updatedUtcMillis, finishedAt) " +
                "VALUES ('VOD', 'm2', NULL, NULL, NULL, 1000, 5000, 9, NULL)",
        )
        db.execSQL(
            "INSERT INTO epg_programs (channelId, title, description, startUtcMillis, endUtcMillis) VALUES ('epg.1', 'News', NULL, 1000, 2000)",
        )
    }

    private fun open(): HappyTvDatabase =
        Room.databaseBuilder(context, HappyTvDatabase::class.java, dbFile.name)
            // The production list, exactly (no destructive fallback).
            .addMigrations(*HappyTvDatabase.ALL_MIGRATIONS)
            .build()

    @Test
    fun `every favorite survives a v5 upgrade, in the order the screens show today`() = runBlocking {
        buildV5Database()
        val db = open()
        try {
            assertEquals(6, db.openHelper.readableDatabase.version)
            val shownToday = listOf("c1", "c3", "c4", "c6", "c7", "c9")
            assertEquals(
                "stream id order (what the pane shows), then the ones with no channel, newest added first",
                shownToday + listOf("gone1", "gone2"),
                db.favoriteDao().getOrdered(StreamType.LIVE).map { it.streamId },
            )
            assertEquals((1..8).toList(), db.favoriteDao().getOrdered(StreamType.LIVE).map { it.position })
            // It really is what the screen's query returns today (no sort after it), not a guess.
            assertEquals(shownToday, db.liveStreamDao().observeByIds(shownToday.reversed()).first().map { it.id })
            assertEquals(
                "not the channel number order",
                false,
                db.favoriteDao().getOrdered(StreamType.LIVE).map { it.streamId }.take(6) == listOf("c9", "c4", "c1", "c7", "c6", "c3"),
            )
            // Movies: m2 and m5 are in the catalog (id order), c3 is not (last).
            assertEquals(listOf("m2", "m5", "c3"), db.favoriteDao().getOrdered(StreamType.VOD).map { it.streamId })
            assertEquals(listOf(1, 2, 3), db.favoriteDao().getOrdered(StreamType.VOD).map { it.position })
            assertEquals(listOf("s8"), db.favoriteDao().getOrdered(StreamType.SERIES).map { it.streamId })
            assertEquals(listOf(1), db.favoriteDao().getOrdered(StreamType.SERIES).map { it.position })
            // Nothing lost: the count and every added-at stamp are as before.
            val all = db.favoriteDao().getAll()
            assertEquals(12, all.size)
            assertEquals(1_700_000_009_000L, all.first { it.streamId == "c9" && it.streamType == StreamType.LIVE }.addedAt.toEpochMilli())
            // The readers that still sort by added-at (movies and shows) are unchanged.
            assertEquals(listOf("m2", "m5", "c3"), db.favoriteDao().observeForType(StreamType.VOD).first().map { it.streamId })
        } finally {
            db.close()
        }
    }

    @Test
    fun `the other tables are untouched by the upgrade`() = runBlocking {
        buildV5Database()
        val db = open()
        try {
            fun count(table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`")
                .use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(12, db.liveStreamDao().count())
            assertEquals(5, db.vodStreamDao().count())
            assertEquals(2, count("categories"))
            assertEquals(1, count("hidden_categories"))
            assertEquals(1, count("hidden_channels"))
            assertEquals(1, count("recent_channels"))
            assertEquals(1, count("watch_progress"))
            assertEquals(1, count("epg_programs"))
            assertEquals("Channel c7", db.liveStreamDao().getById("c7")!!.name)
        } finally {
            db.close()
        }
    }

    @Test
    fun `after the upgrade a new favorite goes to the bottom and a place is saved`() = runBlocking {
        buildV5Database()
        val db = open()
        try {
            val dao = db.favoriteDao()
            dao.toggle("c12", StreamType.LIVE)
            assertEquals(
                listOf("c1", "c3", "c4", "c6", "c7", "c9", "gone1", "gone2", "c12"),
                dao.getOrdered(StreamType.LIVE).map { it.streamId },
            )
            dao.reorder(StreamType.LIVE, listOf("c12", "c1", "c3", "c4", "c6", "c7", "c9", "gone1", "gone2"))
            assertEquals("c12", dao.getOrdered(StreamType.LIVE).first().streamId)
            assertEquals((1..9).toList(), dao.getOrdered(StreamType.LIVE).map { it.position })
            assertTrue(db.favoriteDao().isFavorite("c12", StreamType.LIVE))
            // The default for a bare Favorite() is still valid input (position 0 sorts first, then newest).
            dao.insert(Favorite("c11", StreamType.LIVE))
            assertEquals("c11", dao.getOrdered(StreamType.LIVE).first().streamId)
        } finally {
            db.close()
        }
    }

    companion object {
        private var counter = 0
    }
}
