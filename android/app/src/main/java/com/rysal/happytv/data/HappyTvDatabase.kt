package com.rysal.happytv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rysal.happytv.data.dao.CategoryDao
import com.rysal.happytv.data.dao.EpgProgramDao
import com.rysal.happytv.data.dao.FavoriteDao
import com.rysal.happytv.data.dao.HiddenCategoryDao
import com.rysal.happytv.data.dao.HiddenChannelDao
import com.rysal.happytv.data.dao.LiveStreamDao
import com.rysal.happytv.data.dao.LoginProfileDao
import com.rysal.happytv.data.dao.RecentChannelDao
import com.rysal.happytv.data.dao.SeriesStreamDao
import com.rysal.happytv.data.dao.VodStreamDao
import com.rysal.happytv.data.dao.WatchProgressDao
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.EpgProgramStage
import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.HiddenCategory
import com.rysal.happytv.data.entity.HiddenChannel
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.LoginProfile
import com.rysal.happytv.data.entity.RecentChannel
import com.rysal.happytv.data.entity.SeriesStream
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.entity.WatchProgress

/**
 * HappyTV's local cache + user-state database.
 *
 * Everything is written by the refresh coordinator inside a transaction and
 * read by the UI through [kotlinx.coroutines.flow.Flow]-returning DAO methods.
 *
 * Migration surface: v1 is the first schema (`app/schemas/<fqcn>/1.json`);
 * **v2 adds [HiddenChannel]**, the Live TV "Hide channel" action's durable row
 * (see [MIGRATION_1_2]); **v3 adds [EpgProgramStage]**, the XMLTV import's
 * staging table (see [MIGRATION_2_3] and F-65); **v4 indexes that staging table by channel**
 * (see [MIGRATION_3_4] and #43); **v5 adds [WatchProgress]** and indexes the movie and show
 * "added" stamps (see [MIGRATION_4_5] and #78). `exportSchema = true` writes
 * `5.json` on every KSP run, so later migrations have a golden file to diff
 * against.
 *
 * Threads: every suspend/Flow DAO call already hops off the caller thread; do
 * NOT add `allowMainThreadQueries()` to production builds — if a query needs
 * it, the call site is wrong.
 */
@Database(
    entities = [
        LoginProfile::class,
        Category::class,
        LiveStream::class,
        VodStream::class,
        SeriesStream::class,
        EpgProgram::class,
        EpgProgramStage::class,
        Favorite::class,
        HiddenCategory::class,
        HiddenChannel::class,
        RecentChannel::class,
        WatchProgress::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HappyTvDatabase : RoomDatabase() {

    abstract fun loginProfileDao(): LoginProfileDao
    abstract fun categoryDao(): CategoryDao
    abstract fun liveStreamDao(): LiveStreamDao
    abstract fun vodStreamDao(): VodStreamDao
    abstract fun seriesStreamDao(): SeriesStreamDao
    abstract fun epgProgramDao(): EpgProgramDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun hiddenCategoryDao(): HiddenCategoryDao
    abstract fun hiddenChannelDao(): HiddenChannelDao
    abstract fun recentChannelDao(): RecentChannelDao
    abstract fun watchProgressDao(): WatchProgressDao

    companion object {
        const val NAME = "happytv.db"

        /**
         * v1 → v2: add `hidden_channels` (the Live TV long-press "Hide channel"
         * action). Purely additive — no column changed, no row rewritten.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hidden_channels` (" +
                        "`streamId` TEXT NOT NULL, `streamType` TEXT NOT NULL, " +
                        "PRIMARY KEY(`streamId`, `streamType`))",
                )
            }
        }

        /**
         * v2 → v3: add `epg_programs_stage` (F-65's XMLTV staging table).
         *
         * Purely additive and **index-free on purpose** — the staging table
         * is only ever written in bulk and drained by one unindexed
         * `INSERT … SELECT`, so an index here would cost 369k index
         * insertions and buy nothing. The live `epg_programs` index is
         * rebuilt by `EpgProgramDao.promoteStaged`, not here.
         *
         * Existing installs keep every stored programme: `epg_programs`
         * itself is untouched, so upgrading mid-session leaves the user's
         * guide exactly as it was.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `epg_programs_stage` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`channelId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`description` TEXT, " +
                        "`startUtcMillis` INTEGER NOT NULL, `endUtcMillis` INTEGER NOT NULL)",
                )
            }
        }

        /** Tables whose Room invalidation triggers [pinInvalidationTriggers] keeps installed. */
        val OBSERVED_TABLES = arrayOf(
            "epg_programs", "live_streams", "vod_streams", "series_streams", "categories",
            "favorites", "hidden_categories", "hidden_channels", "recent_channels", "watch_progress",
        )

        /**
         * Keeps Room's invalidation triggers installed for the process lifetime (#43).
         *
         * Room installs a table's trigger when its first Flow observer starts and
         * drops it when the last one stops, and both are **writes**. During a
         * guide import's staging transactions that write waits for the lock, so
         * opening the guide (or Live TV) queued behind the import for 12 to 19 s
         * before its first query could even run. With a permanent observer the
         * triggers already exist, and screens attach and detach without writing.
         * Registers on a background thread (the first registration is itself a write).
         */
        fun pinInvalidationTriggers(db: HappyTvDatabase) {
            Thread({
                runCatching {
                    db.invalidationTracker.addObserver(
                        object : androidx.room.InvalidationTracker.Observer(OBSERVED_TABLES[0], *OBSERVED_TABLES.copyOfRange(1, OBSERVED_TABLES.size)) {
                            override fun onInvalidated(tables: Set<String>) = Unit
                        },
                    )
                }
            }, "htv-pin-triggers").apply { isDaemon = true }.start()
        }

        /** The only supported construction path — [com.rysal.happytv.data.Graph]
         * calls it with the Application context. [inMemory] is for tests:
         * `Room.inMemoryDatabaseBuilder` gives an empty DB that dies with the
         * process, no file to clean up.
         */
        fun build(context: Context, inMemory: Boolean = false): HappyTvDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            } else {
                Room.databaseBuilder(context, HappyTvDatabase::class.java, NAME)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            }
            return builder.build()
        }

        /**
         * v3 → v4 (#43): index `epg_programs_stage` by `channelId`.
         *
         * The guide promotion now moves the staged rows a batch of channels at
         * a time, in short transactions, instead of one drop-index / copy /
         * rebuild-index transaction that held the database for tens of seconds.
         * Each batch looks its channels up in the stage, so the stage needs the
         * index. Any rows a killed import left behind are harmless: every
         * import starts by clearing the stage.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_epg_programs_stage_channelId` " +
                        "ON `epg_programs_stage` (`channelId`)",
                )
            }
        }

        /**
         * v4 -> v5 (#78): watch progress, and the indexes "Recently added" sorts by.
         *
         * Purely additive: one new table, three new indexes, no row rewritten. Building the two
         * catalog indexes reads the VOD and series tables once (a 31k-row catalog is a fraction
         * of a second); nothing the user saved (favorites, hidden items, recents, the guide) is
         * touched. The DDL is verbatim from `app/schemas/.../5.json`.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_progress` (" +
                        "`streamType` TEXT NOT NULL, `streamId` TEXT NOT NULL, `seriesId` TEXT, " +
                        "`seasonNumber` INTEGER, `episodeNumber` INTEGER, " +
                        "`positionMillis` INTEGER NOT NULL, `durationMillis` INTEGER NOT NULL, " +
                        "`updatedUtcMillis` INTEGER NOT NULL, `finishedAt` INTEGER, PRIMARY KEY(`streamType`, `streamId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_progress_updatedUtcMillis` " +
                        "ON `watch_progress` (`updatedUtcMillis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_progress_seriesId` " +
                        "ON `watch_progress` (`seriesId`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vod_streams_added` ON `vod_streams` (`added`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_series_streams_lastModified` " +
                        "ON `series_streams` (`lastModified`)",
                )
            }
        }
    }
}
