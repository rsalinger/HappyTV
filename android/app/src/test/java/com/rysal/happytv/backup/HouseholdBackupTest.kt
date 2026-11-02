package com.rysal.happytv.backup

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.HiddenCategory
import com.rysal.happytv.data.entity.HiddenChannel
import com.rysal.happytv.data.entity.LoginProfile
import com.rysal.happytv.data.entity.RecentChannel
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.WatchProgress
import com.rysal.happytv.security.InMemoryEpgOverrideStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #19: what the household backup file holds, what it never holds, and the once-only restore import. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class HouseholdBackupTest {

    private lateinit var db: HappyTvDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var backup: HouseholdBackup
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "backup-settings-${System.nanoTime()}.preferences_pb")
        }
        settings = SettingsRepository(dataStore, InMemoryEpgOverrideStore())
        dir = File(context.cacheDir, "backup-dir-${System.nanoTime()}")
        backup = HouseholdBackup(db, settings, dir)
    }

    @After
    fun tearDown() {
        val drained = java.util.concurrent.CountDownLatch(1)
        runCatching { db.queryExecutor.execute { drained.countDown() } }
        drained.await(5, java.util.concurrent.TimeUnit.SECONDS)
        db.close()
        dir.deleteRecursively()
    }

    private suspend fun seedHousehold() {
        db.favoriteDao().insert(Favorite("101", StreamType.LIVE, Instant.ofEpochMilli(5_000)))
        db.favoriteDao().insert(Favorite("202", StreamType.VOD, Instant.ofEpochMilli(6_000)))
        db.hiddenCategoryDao().insert(HiddenCategory("7", StreamType.LIVE))
        db.hiddenChannelDao().insert(HiddenChannel("303", StreamType.LIVE))
        db.recentChannelDao().insert(RecentChannel("404", 7_000))
        db.watchProgressDao().upsert(
            WatchProgress(
                streamType = StreamType.SERIES,
                streamId = "505",
                seriesId = "55",
                seasonNumber = 2,
                episodeNumber = 3,
                positionMillis = 90_000,
                durationMillis = 3_000_000,
                updatedUtcMillis = 8_000,
            ),
        )
    }

    /** Everything sign-in related, with recognisable fake values. */
    private suspend fun seedSecrets() {
        db.loginProfileDao().upsert(
            LoginProfile(
                name = "fake-host.invalid",
                host = "fake-host.invalid",
                port = 8443,
                username = FAKE_USER,
                password = "sha256:fakepassfingerprint",
                useTls = true,
            ),
        )
        settings.setEpgUrl("http://fake-host.invalid/xmltv.php?username=$FAKE_USER&password=$FAKE_PASS")
        settings.setAllowSignInOverUnencrypted(true)
        settings.setUserInfo("{\"username\":\"$FAKE_USER\"}".toByteArray())
        settings.setRefreshOnStart(false)
        settings.setReplayFromStart(true)
    }

    @Test
    fun `the file holds favorites, hidden items, recents, progress and settings`() = runBlocking {
        seedHousehold()
        seedSecrets()

        val file = backup.export()

        assertEquals(setOf("101" to "LIVE", "202" to "VOD"), file.favorites.map { it.id to it.type }.toSet())
        assertEquals(listOf("7" to "LIVE"), file.hiddenCategories.map { it.id to it.type })
        assertEquals(listOf("303" to "LIVE"), file.hiddenChannels.map { it.id to it.type })
        assertEquals(listOf("404"), file.recents.map { it.id })
        val progress = file.progress.single()
        assertEquals("505", progress.id)
        assertEquals("55", progress.seriesId)
        assertEquals(90_000L, progress.positionMillis)
        assertEquals(false, file.settings.refreshOnStart)
        assertEquals(true, file.settings.replayFromStart)
    }

    @Test
    fun `the written file never contains the host, username, password or any URL`() = runBlocking {
        seedHousehold()
        seedSecrets()

        assertTrue(backup.writeForBackup())
        val text = backup.file.readText()

        assertTrue("household data is there", text.contains("\"101\""))
        listOf("fake-host.invalid", FAKE_USER, FAKE_PASS, "fakepassfingerprint", "http", "xmltv", "8443", "user_info", "epgUrl")
            .forEach { assertFalse("the file must not contain \"$it\"", text.contains(it, ignoreCase = true)) }
        // The guide link, the sign-in override and the backup switch are not settings that travel.
        val settingsJson = text.substringAfter("\"settings\"")
        assertFalse(settingsJson.contains("allow", ignoreCase = true))
        assertFalse(settingsJson.contains("backup", ignoreCase = true))
    }

    @Test
    fun `with the switch off nothing is written and an old file is removed`() = runBlocking {
        seedHousehold()
        assertTrue(backup.writeForBackup())
        assertTrue(backup.file.exists())

        settings.setBackupHousehold(false)

        assertFalse(backup.writeForBackup())
        assertFalse("an old file is removed", backup.file.exists())
        assertFalse(File(dir, HouseholdBackup.FILE_NAME + ".tmp").exists())
    }

    @Test
    fun `the backup switch is on by default, survives a user_info wipe and reset to defaults turns it back on`() = runBlocking {
        assertTrue(settings.settings.first().backupHousehold)
        settings.setBackupHousehold(false)
        // What sign-out does to settings: it drops the cached account info only.
        settings.setUserInfo(null)
        assertFalse(settings.settings.first().backupHousehold)
        settings.resetUserSettings()
        assertTrue(settings.settings.first().backupHousehold)
    }

    @Test
    fun `clear removes the file after a backup`() = runBlocking {
        seedHousehold()
        backup.writeForBackup()
        backup.clear()
        assertFalse(backup.file.exists())
    }

    @Test
    fun `a restored file is imported once into an empty install and then deleted`() = runBlocking {
        seedHousehold()
        settings.setRefreshOnStart(false)
        settings.setAspectRatio("16:9")
        backup.writeForBackup()
        val saved = backup.file.readText()
        wipe()
        // The file as a restore leaves it.
        dir.mkdirs()
        backup.file.writeText(saved)

        assertTrue(backup.importIfPending())

        assertEquals(setOf("101", "202"), db.favoriteDao().getAll().map { it.streamId }.toSet())
        assertEquals(Instant.ofEpochMilli(5_000), db.favoriteDao().getAll().first { it.streamId == "101" }.addedAt)
        assertEquals(listOf("7"), db.hiddenCategoryDao().getAll().map { it.categoryId })
        assertEquals(listOf("303"), db.hiddenChannelDao().getAll().map { it.streamId })
        assertEquals(listOf("404"), db.recentChannelDao().getAll().map { it.streamId })
        val progress = db.watchProgressDao().getAll().single()
        assertEquals(2, progress.seasonNumber)
        assertEquals(3, progress.episodeNumber)
        assertFalse(settings.settings.first().refreshOnStart)
        assertEquals("16:9", settings.settings.first().aspectRatio)
        assertFalse("the file is deleted", backup.file.exists())

        // Once: a second call has nothing to do, even if the user then clears a favorite.
        db.favoriteDao().clear()
        assertFalse(backup.importIfPending())
        assertTrue(db.favoriteDao().getAll().isEmpty())
    }

    @Test
    fun `an import never overwrites existing household data and still deletes the file`() = runBlocking {
        seedHousehold()
        backup.writeForBackup()
        val saved = backup.file.readText()
        backup.clear()
        wipe()
        db.favoriteDao().insert(Favorite("999", StreamType.LIVE, Instant.ofEpochMilli(1)))
        dir.mkdirs()
        backup.file.writeText(saved)

        assertFalse(backup.importIfPending())

        assertEquals(listOf("999"), db.favoriteDao().getAll().map { it.streamId })
        assertTrue(db.hiddenCategoryDao().getAll().isEmpty())
        assertFalse(backup.file.exists())
    }

    @Test
    fun `an import keeps settings the user already set and ignores invalid values`() = runBlocking {
        settings.setAspectRatio("4:3")
        val restored = HouseholdSettings(
            aspectRatio = "16:9",
            refreshOnStart = false,
            epgRefreshHours = -3,
            preferredStreamFormat = "mkv",
            liveTvOpenOn = "nonsense",
        )
        dir.mkdirs()
        backup.file.writeText(
            kotlinx.serialization.json.Json.encodeToString(
                HouseholdBackupFile.serializer(),
                HouseholdBackupFile(settings = restored),
            ),
        )

        assertTrue(backup.importIfPending())

        val now = settings.settings.first()
        assertEquals("kept", "4:3", now.aspectRatio)
        assertFalse("filled", now.refreshOnStart)
        assertEquals(SettingsRepository.DEFAULT_EPG_REFRESH_HOURS, now.epgRefreshHours)
        assertEquals(SettingsRepository.FORMAT_M3U8, now.preferredStreamFormat)
    }

    @Test
    fun `an unreadable or newer file imports nothing and is deleted`() = runBlocking {
        dir.mkdirs()
        backup.file.writeText("not json {")
        assertFalse(backup.importIfPending())
        assertFalse(backup.file.exists())

        backup.file.writeText("{\"version\":99,\"favorites\":[{\"id\":\"1\",\"type\":\"LIVE\",\"addedAtMillis\":1}]}")
        assertFalse(backup.importIfPending())
        assertTrue(db.favoriteDao().getAll().isEmpty())
        assertFalse(backup.file.exists())
    }

    @Test
    fun `no file means no import`() = runBlocking {
        assertFalse(backup.importIfPending())
    }

    private suspend fun wipe() {
        db.favoriteDao().clear()
        db.hiddenCategoryDao().clear()
        db.hiddenChannelDao().clear()
        db.recentChannelDao().clear()
        db.watchProgressDao().getAll().forEach {
            if (it.streamType == StreamType.SERIES) db.watchProgressDao().deleteEpisode(it.streamId) else db.watchProgressDao().deleteMovie(it.streamId)
        }
        backup.clear()
    }

    private companion object {
        const val FAKE_USER = "fakeuser123"
        const val FAKE_PASS = "fakepass456"
    }
}
