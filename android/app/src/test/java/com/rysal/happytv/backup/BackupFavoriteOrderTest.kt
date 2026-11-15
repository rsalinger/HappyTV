package com.rysal.happytv.backup

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.security.InMemoryEpgOverrideStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #221: the favorites order travels in the backup; a file from before it restores in the order the screens showed. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BackupFavoriteOrderTest {

    private lateinit var db: HappyTvDatabase
    private lateinit var backup: HouseholdBackup
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "order-settings-${System.nanoTime()}.preferences_pb")
        }
        dir = File(context.cacheDir, "order-dir-${System.nanoTime()}")
        backup = HouseholdBackup(
            db, SettingsRepository(dataStore, InMemoryEpgOverrideStore()), dir,
            appVersion = "1.1.0-test", io = Dispatchers.Unconfined, clockMillis = { 1_700_000_000_000 },
        )
    }

    @After
    fun tearDown() {
        val drained = java.util.concurrent.CountDownLatch(1)
        runCatching { db.queryExecutor.execute { drained.countDown() } }
        drained.await(5, java.util.concurrent.TimeUnit.SECONDS)
        db.close()
        dir.deleteRecursively()
    }

    private suspend fun ids(type: StreamType = StreamType.LIVE) = db.favoriteDao().getOrdered(type).map { it.streamId }

    private suspend fun okOf(file: File): CheckedBackup = (backup.check(file) as BackupCheck.Ok).backup

    @Test
    fun `a round trip carries the order the user chose`() = runBlocking {
        val dao = db.favoriteDao()
        listOf("c1", "c2", "c3", "c4").forEach { dao.toggle(it, StreamType.LIVE) }
        dao.toggle("m1", StreamType.VOD)
        dao.toggle("m2", StreamType.VOD)
        dao.reorder(StreamType.LIVE, listOf("c3", "c1", "c4", "c2"))
        dao.reorder(StreamType.VOD, listOf("m2", "m1"))

        val file = File(dir, "b.json")
        assertTrue(backup.writeLocalBackup(file) is BackupWriteResult.Written)
        dao.clear()
        dao.toggle("zz", StreamType.LIVE)

        assertTrue(backup.restore(okOf(file)) is RestoreResult.Restored)

        assertEquals(listOf("c3", "c1", "c4", "c2"), ids())
        assertEquals(listOf("m2", "m1"), ids(StreamType.VOD))
        assertEquals((1..4).toList(), dao.getOrdered(StreamType.LIVE).map { it.position })
    }

    @Test
    fun `the file records each favorite's position`() = runBlocking {
        val dao = db.favoriteDao()
        listOf("c1", "c2").forEach { dao.toggle(it, StreamType.LIVE) }
        dao.reorder(StreamType.LIVE, listOf("c2", "c1"))
        val exported = backup.export().favorites
        assertEquals(listOf("c2" to 1, "c1" to 2), exported.map { it.id to it.position })
    }

    @Test
    fun `an older backup with no positions restores in the order the screens showed, and every favorite survives`() = runBlocking {
        dir.mkdirs()
        val file = File(dir, "old.json").also {
            it.writeText(
                "{\"format\":\"happytv-backup\",\"version\":1,\"favorites\":[" +
                    "{\"id\":\"c1\",\"type\":\"LIVE\",\"addedAtMillis\":1000}," +
                    "{\"id\":\"c3\",\"type\":\"LIVE\",\"addedAtMillis\":3000}," +
                    "{\"id\":\"c2\",\"type\":\"LIVE\",\"addedAtMillis\":2000}," +
                    "{\"id\":\"m1\",\"type\":\"VOD\",\"addedAtMillis\":500}]}",
            )
        }
        assertTrue(backup.restore(okOf(file)) is RestoreResult.Restored)
        // Added newest first would be c3 c2 c1; the screens showed stream id order, so that is kept.
        assertEquals(listOf("c1", "c2", "c3"), ids())
        assertEquals(listOf(1, 2, 3), db.favoriteDao().getOrdered(StreamType.LIVE).map { it.position })
        assertEquals(listOf("m1"), ids(StreamType.VOD))
        // The next favorite still goes to the bottom.
        db.favoriteDao().toggle("c9", StreamType.LIVE)
        assertEquals(listOf("c1", "c2", "c3", "c9"), ids())
    }

    @Test
    fun `a file with positions for only some rows falls back to the order the screens showed`() {
        val rows = listOf(
            HouseholdBackupFile.FavoriteRow("a", "LIVE", 1_000, position = 2),
            HouseholdBackupFile.FavoriteRow("b", "LIVE", 2_000, position = null),
            HouseholdBackupFile.FavoriteRow("c", "LIVE", 3_000, position = 1),
        )
        assertEquals(listOf("a", "b", "c"), favoriteEntities(rows).map { it.streamId })
    }

    @Test
    fun `positions in a file are renumbered dense and an unknown type is skipped`() {
        val rows = listOf(
            HouseholdBackupFile.FavoriteRow("a", "LIVE", 1_000, position = 40),
            HouseholdBackupFile.FavoriteRow("b", "LIVE", 2_000, position = 7),
            HouseholdBackupFile.FavoriteRow("x", "NOPE", 2_000, position = 1),
        )
        val entities = favoriteEntities(rows)
        assertEquals(listOf("b" to 1, "a" to 2), entities.map { it.streamId to it.position })
        assertNull(entities.firstOrNull { it.streamId == "x" })
    }

    @Test
    fun `the one-time import after a reinstall keeps the order too`() = runBlocking {
        dir.mkdirs()
        File(dir, HouseholdBackup.FILE_NAME).writeText(
            "{\"version\":1,\"favorites\":[" +
                "{\"id\":\"c1\",\"type\":\"LIVE\",\"addedAtMillis\":9000,\"position\":2}," +
                "{\"id\":\"c2\",\"type\":\"LIVE\",\"addedAtMillis\":1000,\"position\":1}]}",
        )
        assertTrue(backup.importIfPending())
        assertEquals(listOf("c2", "c1"), ids())
    }

    @Test
    fun `an old backup restores in stream id text order, the same as the migration, with numeric-looking ids`() = runBlocking {
        dir.mkdirs()
        // Text order is 10, 100, 2, 9 (not the numeric order, not the order added, which is 9 2 100 10).
        val rows = listOf("9" to 4000, "2" to 3000, "100" to 2000, "10" to 1000).joinToString(",") {
            "{\"id\":\"${it.first}\",\"type\":\"LIVE\",\"addedAtMillis\":${it.second}}"
        }
        val file = File(dir, "old-numeric.json").also {
            it.writeText("{\"format\":\"happytv-backup\",\"version\":1,\"favorites\":[$rows]}")
        }
        assertTrue(backup.restore(okOf(file)) is RestoreResult.Restored)
        assertEquals(listOf("10", "100", "2", "9"), ids())

        // The one-time import after a reinstall (same old file shape, no format marker) agrees.
        db.favoriteDao().clear()
        File(dir, HouseholdBackup.FILE_NAME).writeText("{\"version\":1,\"favorites\":[$rows]}")
        assertTrue(backup.importIfPending())
        assertEquals(listOf("10", "100", "2", "9"), ids())
    }
}
