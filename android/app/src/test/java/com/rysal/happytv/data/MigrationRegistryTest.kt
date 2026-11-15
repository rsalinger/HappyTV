package com.rysal.happytv.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The migrations the app registers ([HappyTvDatabase.ALL_MIGRATIONS], the same list `build()` uses)
 * against the exported schemas in `app/schemas/`.
 *
 * A migration that is written but not registered, or registered but never exercised, fails here:
 * every exported version is built from its own JSON (tables, indexes, identity hash), filled with
 * rows, and opened through Room with only the registered list and no destructive fallback.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class MigrationRegistryTest {

    private lateinit var context: Application
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.cacheDir, "happytv-registry-${counter++}.db")
        deleteDb()
    }

    @After
    fun tearDown() = deleteDb()

    private fun deleteDb() { context.deleteDatabase(dbFile.name) }

    private fun schemaDir(): File =
        listOf("schemas", "app/schemas").map { File(it, HappyTvDatabase::class.java.name) }.first { it.isDirectory }

    private fun schemaVersions(): List<Int> =
        schemaDir().listFiles { f -> f.name.matches(Regex("\\d+\\.json")) }!!
            .map { it.nameWithoutExtension.toInt() }.sorted()

    private fun currentVersion(): Int {
        val db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
        return try { db.openHelper.readableDatabase.version } finally { db.close() }
    }

    @Test
    fun `every exported schema version has exactly one registered migration to the next`() {
        val current = currentVersion()
        assertEquals("exported schemas must be 1..$current", (1..current).toList(), schemaVersions())
        val registered = HappyTvDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        val expected = (1 until current).map { it to it + 1 }
        assertEquals("ALL_MIGRATIONS must be one step per version, in order", expected, registered)
    }

    /** Builds the file the app would have had at [version], from `app/schemas/.../<version>.json`, with rows in it. */
    private fun buildFromSchema(version: Int) {
        val database = Json.parseToJsonElement(File(schemaDir(), "$version.json").readText())
            .jsonObject["database"]!!.jsonObject
        assertEquals(version, database["version"]!!.jsonPrimitive.content.toInt())
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        database["entities"]!!.jsonArray.map { it.jsonObject }.forEach { e ->
                            val table = e["tableName"]!!.jsonPrimitive.content
                            db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                            e["indices"]?.jsonArray?.forEach { i -> db.execSQL(i.jsonObject.sql(table)) }
                        }
                        database["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                        seedUserRows(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun JsonObject.sql(table: String): String =
        this["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    /** Tables every version has; the rows are what a household would lose in a bad migration. */
    private fun seedUserRows(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO favorites (streamId, streamType, addedAt) VALUES ('c2', 'LIVE', 1700000002000)")
        db.execSQL("INSERT INTO favorites (streamId, streamType, addedAt) VALUES ('m5', 'VOD', 1700000000000)")
        db.execSQL("INSERT INTO hidden_categories (categoryId, categoryType) VALUES ('11', 'VOD')")
        db.execSQL("INSERT INTO recent_channels (streamId, lastWatchedUtcMillis) VALUES ('c1', 1700000003000)")
        db.execSQL(
            "INSERT INTO epg_programs (channelId, title, description, startUtcMillis, endUtcMillis) " +
                "VALUES ('epg.1', 'News', NULL, 1000, 2000)",
        )
    }

    private fun HappyTvDatabase.count(table: String, where: String = "1"): Int =
        openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table` WHERE $where").use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun `a populated file at every exported version opens as the current version with its rows`() {
        val current = currentVersion()
        for (version in 1 until current) {
            deleteDb()
            buildFromSchema(version)
            // The app's own path: the production list, no destructive fallback.
            val db = Room.databaseBuilder(context, HappyTvDatabase::class.java, dbFile.name)
                .addMigrations(*HappyTvDatabase.ALL_MIGRATIONS)
                .allowMainThreadQueries()
                .build()
            try {
                assertEquals("v$version file", current, db.openHelper.readableDatabase.version)
                assertEquals("v$version favorites", 2, db.count("favorites"))
                assertEquals("v$version favorites numbered", 2, db.count("favorites", "position > 0"))
                assertEquals("v$version hidden categories", 1, db.count("hidden_categories"))
                assertEquals("v$version recents", 1, db.count("recent_channels"))
                assertEquals("v$version programmes", 1, db.count("epg_programs"))
            } finally {
                db.close()
            }
        }
    }

    companion object {
        private var counter = 0
    }
}
