package com.rysal.happytv.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * F-65: the v2 → v3 upgrade, on a database that already holds a guide.
 *
 * Adding `epg_programs_stage` is meant to be invisible to a signed-in user:
 * the migration only creates the staging table, so the 369k programmes the
 * previous build stored must survive the upgrade untouched. That is the part
 * a fresh install can never exercise — Robolectric's in-memory databases are
 * always built at the current version — so this test builds a **v2-shaped**
 * database by hand, populates it, then opens it through Room with
 * [HappyTvDatabase.MIGRATION_2_3] and checks the rows are still there.
 *
 * It also pins the staging table's shape: same columns as `epg_programs`,
 * and, since v4 (#43), a single `channelId` index so the promotion can drain
 * it a batch of channels at a time.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class EpgStagingMigrationTest {

    private lateinit var context: Application
    private lateinit var dbFile: java.io.File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = java.io.File(context.cacheDir, "happytv-migration-${counter++}.db")
        listOf("", "-wal", "-shm").forEach { java.io.File(dbFile.path + it).delete() }
    }

    @After
    fun tearDown() {
        listOf("", "-wal", "-shm").forEach { java.io.File(dbFile.path + it).delete() }
    }

    /** Build the exact v2 shape, with a populated `epg_programs`. */
    private fun buildV2Database() {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // The v2 schema, verbatim from app/schemas/…/2.json — Room's
                        // TableInfo validation compares every column, so hand-writing
                        // this would drift and fail for unrelated reasons.
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `login_profiles` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `username` TEXT NOT NULL, `password` TEXT NOT NULL, `useTls` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
                        )
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `parentId` TEXT, `isHidden` INTEGER NOT NULL, PRIMARY KEY(`id`, `type`))""",
                        )
                        db.execSQL(
                            """CREATE INDEX IF NOT EXISTS `index_categories_type` ON `categories` (`type`)""",
                        )
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `live_streams` (`id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `num` INTEGER, `name` TEXT NOT NULL, `streamIcon` TEXT, `epgChannelId` TEXT, `tvArchive` INTEGER NOT NULL, `tvArchiveDuration` INTEGER, `containerExtension` TEXT, `added` INTEGER, `customSid` TEXT, `directSource` TEXT, PRIMARY KEY(`id`))""",
                        )
                        db.execSQL(
                            """CREATE INDEX IF NOT EXISTS `index_live_streams_categoryId` ON `live_streams` (`categoryId`)""",
                        )
                        db.execSQL(
                            """CREATE INDEX IF NOT EXISTS `index_live_streams_name` ON `live_streams` (`name`)""",
                        )
                        db.execSQL(
                            """CREATE INDEX IF NOT EXISTS `index_live_streams_epgChannelId` ON `live_streams` (`epgChannelId`)""",
                        )
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `vod_streams` (`id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `num` INTEGER, `name` TEXT NOT NULL, `streamIcon` TEXT, `containerExtension` TEXT, `rating` REAL, `added` INTEGER, `customSid` TEXT, `directSource` TEXT, PRIMARY KEY(`id`))""",
                        )
                        db.execSQL(
                            """CREATE INDEX IF NOT EXISTS `index_vod_streams_categoryId` ON `vod_streams` (`categoryId`)""",
                        )
                        db.execSQL(
                            """CREATE INDEX IF NOT EXISTS `index_vod_streams_name` ON `vod_streams` (`name`)""",
                        )
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `series_streams` (`id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `num` INTEGER, `name` TEXT NOT NULL, `cover` TEXT, `plot` TEXT, `cast` TEXT, `director` TEXT, `genre` TEXT, `releaseDate` TEXT, `lastModified` INTEGER, `rating` REAL, `backdropPath` TEXT, `youtubeTrailer` TEXT, PRIMARY KEY(`id`))""",
                        )
                        db.execSQL(
                            """CREATE INDEX IF NOT EXISTS `index_series_streams_categoryId` ON `series_streams` (`categoryId`)""",
                        )
                        db.execSQL(
                            """CREATE INDEX IF NOT EXISTS `index_series_streams_name` ON `series_streams` (`name`)""",
                        )
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `epg_programs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `channelId` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `startUtcMillis` INTEGER NOT NULL, `endUtcMillis` INTEGER NOT NULL)""",
                        )
                        db.execSQL(
                            """CREATE INDEX IF NOT EXISTS `index_epg_programs_channelId_startUtcMillis` ON `epg_programs` (`channelId`, `startUtcMillis`)""",
                        )
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `favorites` (`streamId` TEXT NOT NULL, `streamType` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`streamId`, `streamType`))""",
                        )
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `hidden_categories` (`categoryId` TEXT NOT NULL, `categoryType` TEXT NOT NULL, PRIMARY KEY(`categoryId`, `categoryType`))""",
                        )
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `hidden_channels` (`streamId` TEXT NOT NULL, `streamType` TEXT NOT NULL, PRIMARY KEY(`streamId`, `streamType`))""",
                        )
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `recent_channels` (`streamId` TEXT NOT NULL, `lastWatchedUtcMillis` INTEGER NOT NULL, PRIMARY KEY(`streamId`))""",
                        )

                        // The guide the user was watching before the upgrade.
                        for (i in 0 until 25) {
                            db.execSQL(
                                "INSERT INTO `epg_programs` " +
                                    "(`channelId`, `title`, `description`, `startUtcMillis`, `endUtcMillis`) " +
                                    "VALUES ('kept.fake', 'Kept $i', NULL, ?, ?)",
                                arrayOf<Any>(1_000L + i * 1_000L, 2_000L + i * 1_000L),
                            )
                        }
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use { it.version = 2 }
        helper.close()
    }

    /** v2 plus F-65's stage table (verbatim v3 DDL) holding rows a killed import left behind. */
    private fun buildV3Database() {
        buildV2Database()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `epg_programs_stage` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `channelId` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `startUtcMillis` INTEGER NOT NULL, `endUtcMillis` INTEGER NOT NULL)""",
                        )
                        for (i in 0 until 3) {
                            db.execSQL(
                                "INSERT INTO `epg_programs_stage` (`channelId`, `title`, `description`, `startUtcMillis`, `endUtcMillis`) " +
                                    "VALUES ('left.over', 'Stale $i', NULL, ?, ?)",
                                arrayOf<Any>(5_000L + i, 6_000L + i),
                            )
                        }
                    }
                })
                .build(),
        )
        helper.writableDatabase.use { it.version = 3 }
        helper.close()
    }

    @Test
    fun `upgrading a populated v3 database to v4 keeps the guide and adds the stage index`() = runBlocking {
        buildV3Database()

        val db = Room.databaseBuilder(context, HappyTvDatabase::class.java, dbFile.name)
            // A v3 file opens with the steps from v3 on (3 -> 4 is under test, 4 -> 5 follows it).
            .addMigrations(HappyTvDatabase.MIGRATION_3_4, HappyTvDatabase.MIGRATION_4_5)
            .build()

        try {
            assertEquals(25, db.epgProgramDao().count())
            assertEquals("rows left in the v3 stage survive untouched", 3, db.epgProgramDao().stagedCount())
            val indices = db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?",
                arrayOf<Any>("epg_programs_stage"),
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertEquals(listOf("index_epg_programs_stage_channelId"), indices)
        } finally {
            db.close()
        }
    }

    @Test
    fun `upgrading a populated v2 database keeps every stored programme`() = runBlocking {
        buildV2Database()

        val db = Room.databaseBuilder(context, HappyTvDatabase::class.java, dbFile.name)
            .addMigrations(
                HappyTvDatabase.MIGRATION_1_2, HappyTvDatabase.MIGRATION_2_3, HappyTvDatabase.MIGRATION_3_4,
                HappyTvDatabase.MIGRATION_4_5,
            )
            .build()

        try {
            // The migration ran — Room opens lazily, so touch a table first.
            assertEquals(25, db.epgProgramDao().count())

            val rows = db.epgProgramDao().observeBetweenRange(0, Long.MAX_VALUE).first()
            assertEquals(25, rows.size)
            assertEquals(
                (0 until 25).map { "Kept $it" },
                rows.map { it.title },
            )
            assertTrue(rows.all { it.channelId == "kept.fake" })
        } finally {
            db.close()
        }
    }

    @Test
    fun `the migrated staging table matches epg_programs column for column`() = runBlocking {
        buildV2Database()

        val db = Room.databaseBuilder(context, HappyTvDatabase::class.java, dbFile.name)
            .addMigrations(
                HappyTvDatabase.MIGRATION_1_2, HappyTvDatabase.MIGRATION_2_3, HappyTvDatabase.MIGRATION_3_4,
                HappyTvDatabase.MIGRATION_4_5,
            )
            .build()

        try {
            val liveColumns = columnNames(db, "epg_programs")
            val stageColumns = columnNames(db, "epg_programs_stage")

            assertEquals(
                "the promotion is a plain INSERT..SELECT, so the two tables " +
                    "must have identical columns",
                liveColumns,
                stageColumns,
            )
            assertEquals(0, db.epgProgramDao().stagedCount())

            // The index must survive the migration on the live table, and
            // (v3 -> v4, #43) the staging table gains exactly one: channelId,
            // which the chunked promotion drains by.
            val indices = db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?",
                arrayOf<Any>("epg_programs_stage"),
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertEquals(
                "the staging table gets exactly the channelId index the chunked promotion needs",
                listOf("index_epg_programs_stage_channelId"),
                indices,
            )

            val liveIndices = db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?",
                arrayOf<Any>("epg_programs"),
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(
                "the live guide's index must still exist after the migration",
                liveIndices.contains(
                    com.rysal.happytv.data.dao.EpgProgramDao.LIVE_INDEX,
                ),
            )
        } finally {
            db.close()
        }
    }

    private fun columnNames(db: HappyTvDatabase, table: String): List<String> =
        db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }

    companion object {
        private var counter = 0
    }
}