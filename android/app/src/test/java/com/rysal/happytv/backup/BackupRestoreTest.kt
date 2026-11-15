package com.rysal.happytv.backup

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.AppSettings
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.HiddenChannel
import com.rysal.happytv.data.entity.RecentChannel
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.WatchProgress
import com.rysal.happytv.security.InMemoryEpgOverrideStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
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

/** #209: the local backup file, the one checker, replace-restore and undo. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BackupRestoreTest {

    private lateinit var db: HappyTvDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var backup: HouseholdBackup
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "restore-settings-${System.nanoTime()}.preferences_pb")
        }
        settings = SettingsRepository(dataStore, InMemoryEpgOverrideStore())
        dir = File(context.cacheDir, "restore-dir-${System.nanoTime()}")
        backup = HouseholdBackup(
            db, settings, dir,
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

    // ------------------------------------------------------------------ helpers

    private suspend fun seedA() {
        db.favoriteDao().insert(Favorite("101", StreamType.LIVE, Instant.ofEpochMilli(5_000)))
        db.favoriteDao().insert(Favorite("202", StreamType.VOD, Instant.ofEpochMilli(6_000)))
        db.favoriteDao().insert(Favorite("303", StreamType.SERIES, Instant.ofEpochMilli(7_000)))
        db.categoryDao().insertAll(
            listOf(Category("7", StreamType.LIVE, "Seven"), Category("8", StreamType.LIVE, "Eight"), Category("9", StreamType.VOD, "Nine")),
        )
        db.hiddenCategoryDao().toggle("7", StreamType.LIVE)
        db.hiddenChannelDao().insert(HiddenChannel("404", StreamType.LIVE))
        settings.setLightMode(true)
        settings.setPreferredStreamFormat(SettingsRepository.FORMAT_TS)
        settings.setRefreshOnStart(false)
    }

    private suspend fun changeToB() {
        db.favoriteDao().clear()
        db.favoriteDao().insert(Favorite("999", StreamType.LIVE, Instant.ofEpochMilli(1)))
        db.hiddenCategoryDao().toggle("7", StreamType.LIVE) // unhide 7
        db.hiddenCategoryDao().toggle("8", StreamType.LIVE) // hide 8
        db.hiddenChannelDao().clear()
        db.hiddenChannelDao().insert(HiddenChannel("888", StreamType.LIVE))
        settings.setLightMode(false)
        settings.setPreferredStreamFormat(SettingsRepository.FORMAT_M3U8)
        settings.setAspectRatio("4:3")
    }

    private suspend fun seedHistory() {
        db.recentChannelDao().insert(RecentChannel("55", 9_000))
        db.watchProgressDao().upsert(
            WatchProgress(
                streamType = StreamType.VOD, streamId = "66", positionMillis = 1_000,
                durationMillis = 5_000, updatedUtcMillis = 10_000,
            ),
        )
    }

    private data class State(
        val favorites: Set<Triple<String, StreamType, Long>>,
        val hiddenCategories: Set<Pair<String, StreamType>>,
        val flags: Set<Pair<String, Boolean>>,
        val hiddenChannels: Set<Pair<String, StreamType>>,
        val settings: HouseholdSettings,
    )

    private suspend fun state() = State(
        favorites = db.favoriteDao().getAll().map { Triple(it.streamId, it.streamType, it.addedAt.toEpochMilli()) }.toSet(),
        hiddenCategories = db.hiddenCategoryDao().getAll().map { it.categoryId to it.categoryType }.toSet(),
        flags = StreamType.entries.flatMap { t -> db.categoryDao().getByType(t) }.map { it.id to it.isHidden }.toSet(),
        hiddenChannels = db.hiddenChannelDao().getAll().map { it.streamId to it.streamType }.toSet(),
        settings = settings.effectiveHouseholdSettings(),
    )

    private suspend fun writeBackup(name: String = "b.json"): File {
        val f = File(dir, name)
        assertTrue(backup.writeLocalBackup(f) is BackupWriteResult.Written)
        return f
    }

    private suspend fun okOf(file: File): CheckedBackup = (backup.check(file) as BackupCheck.Ok).backup

    private fun bytes(s: String) = ByteArrayInputStream(s.toByteArray())

    private fun favoritesJson(n: Int, idLength: Int = 5) = (0 until n).joinToString(",") {
        "{\"id\":\"${it.toString().padStart(idLength, '0')}\",\"type\":\"LIVE\",\"addedAtMillis\":1}"
    }

    private fun backupJson(body: String = "", version: Int = 1) =
        "{\"format\":\"happytv-backup\",\"version\":$version$body}"

    // ------------------------------------------------------------------ round trip and replace

    @Test
    fun `a round trip returns the state of the export`() = runBlocking {
        seedA()
        val exported = state()
        val file = writeBackup()
        changeToB()
        assertFalse(state() == exported)

        val result = backup.restore(okOf(file))

        assertEquals(RestoreResult.Restored(undoAvailable = true), result)
        assertEquals(exported, state())
    }

    @Test
    fun `restore replaces rather than merges, and unset settings go back to their defaults`() = runBlocking {
        // A file that carries one favorite and light mode only.
        val file = File(dir, "small.json").also {
            dir.mkdirs()
            it.writeText(backupJson(",\"favorites\":[{\"id\":\"1\",\"type\":\"LIVE\",\"addedAtMillis\":5}],\"settings\":{\"lightMode\":true}"))
        }
        seedA()
        settings.setAspectRatio("16:9")

        assertTrue(backup.restore(okOf(file)) is RestoreResult.Restored)

        val s = state()
        assertEquals(setOf(Triple("1", StreamType.LIVE, 5L)), s.favorites)
        assertTrue(s.hiddenCategories.isEmpty())
        assertTrue(s.hiddenChannels.isEmpty())
        assertTrue("every hidden flag in the catalog is cleared", s.flags.none { it.second })
        assertEquals(SettingsRepository.withDefaults(HouseholdSettings(lightMode = true)), s.settings)
        assertEquals(SettingsRepository.ASPECT_AUTO, settings.settings.first().aspectRatio)
    }

    @Test
    fun `restore updates the hidden flags in the catalog at once`() = runBlocking {
        seedA()
        val file = writeBackup()
        changeToB()
        assertEquals(setOf("8" to true, "7" to false, "9" to false), state().flags)

        backup.restore(okOf(file))

        assertEquals(setOf("7" to true, "8" to false, "9" to false), state().flags)
        assertEquals(listOf("8", "9").sorted(), db.categoryDao().observeVisible().first().map { it.id }.sorted())
    }

    @Test
    fun `recents and progress are not in the file and survive a restore untouched`() = runBlocking {
        seedA()
        seedHistory()
        val file = writeBackup()
        val text = file.readText()
        assertFalse(text.contains("\"55\""))
        assertFalse(text.contains("\"66\""))
        changeToB()

        backup.restore(okOf(file))

        assertEquals(listOf("55"), db.recentChannelDao().getAll().map { it.streamId })
        assertEquals(listOf("66"), db.watchProgressDao().getAll().map { it.streamId })
    }

    @Test
    fun `a local file carries the format, app version and creation time and never credentials`() = runBlocking {
        seedA()
        settings.setEpgUrl("http://fake-host.invalid/xmltv.php?username=fakeuser123&password=fakepass456")
        settings.setAllowSignInOverUnencrypted(true)
        val text = writeBackup().readText()

        assertTrue(text.contains("\"format\":\"happytv-backup\""))
        assertTrue(text.contains("\"appVersion\":\"1.1.0-test\""))
        assertTrue(text.contains("\"createdAtMillis\":1700000000000"))
        listOf("fake-host", "fakeuser123", "fakepass456", "http", "xmltv", "epgUrl", "allow").forEach {
            assertFalse("must not contain $it", text.contains(it, ignoreCase = true))
        }
        assertFalse(File(dir, "b.json.tmp").exists())
    }

    @Test
    fun `the preview counts rows by type and names the settings that would change`() = runBlocking {
        seedA()
        val file = writeBackup()
        settings.setLightMode(false)
        settings.setRefreshOnStart(false) // same as the file

        val preview = okOf(file).preview

        assertEquals(1700000000000, preview.createdAtMillis)
        assertEquals("1.1.0-test", preview.appVersion)
        assertEquals(mapOf(StreamType.LIVE to 1, StreamType.VOD to 1, StreamType.SERIES to 1), preview.favoritesByType)
        assertEquals(3, preview.favoritesTotal)
        assertEquals(1, preview.hiddenCategories)
        assertEquals(1, preview.hiddenChannels)
        assertEquals(10, preview.settingsInFile)
        assertEquals(listOf(BackupSetting.LIGHT_MODE), preview.settingsChanged)
    }

    @Test
    fun `a failing database step changes nothing, settings included`() = runBlocking {
        seedA()
        val file = writeBackup()
        changeToB()
        val before = state()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_hidden BEFORE INSERT ON hidden_channels BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )

        val result = backup.restore(okOf(file))

        assertEquals(RestoreResult.Failed(RestoreFailure.DATABASE), result)
        assertEquals(before, state())
    }

    @Test
    fun `when the before-restore copy cannot be saved nothing changes`() = runBlocking {
        seedA()
        val file = writeBackup()
        changeToB()
        val before = state()
        backup.undoFile.mkdirs() // a folder, not empty, where the copy should go
        File(backup.undoFile, "keep").writeText("x")

        val result = backup.restore(okOf(file))

        assertEquals(RestoreResult.Failed(RestoreFailure.SAVING_UNDO_COPY), result)
        assertEquals(before, state())
    }

    // ------------------------------------------------------------------ undo

    @Test
    fun `undo brings back the state from just before the restore`() = runBlocking {
        seedA()
        val file = writeBackup()
        changeToB()
        val b = state()
        assertFalse(backup.hasUndoCopy())

        backup.restore(okOf(file))
        assertTrue(backup.hasUndoCopy())
        assertTrue(backup.checkUndoCopy() is BackupCheck.Ok)

        assertEquals(RestoreResult.Restored(undoAvailable = false), backup.restoreFromUndoCopy())

        assertEquals(b, state())
        assertFalse("one undo", backup.hasUndoCopy())
        assertTrue(backup.restoreFromUndoCopy() is RestoreResult.Rejected)
    }

    @Test
    fun `a second restore overwrites the one undo slot`() = runBlocking {
        seedA()
        val file = writeBackup()
        changeToB()
        backup.restore(okOf(file))
        val afterFirst = state()
        db.favoriteDao().insert(Favorite("777", StreamType.LIVE, Instant.ofEpochMilli(3)))
        val beforeSecond = state()

        backup.restore(okOf(file))
        backup.restoreFromUndoCopy()

        assertEquals(beforeSecond, state())
        assertFalse(afterFirst == beforeSecond)
    }

    @Test
    fun `a damaged undo copy is refused and changes nothing`() = runBlocking {
        seedA()
        dir.mkdirs()
        backup.undoFile.writeText("{\"format\":\"happytv-backup\",\"version\":1,\"favorites\":")
        val before = state()

        assertEquals(RestoreResult.Rejected(BackupCheck.Damaged), backup.restoreFromUndoCopy())
        assertEquals(before, state())
    }

    // ------------------------------------------------------------------ the corpus of bad files

    private fun checkOf(input: InputStream) = runBlocking { backup.check(input) }

    private suspend fun assertUntouched(block: suspend () -> Unit) {
        seedA()
        seedHistory()
        val before = state()
        val history = db.recentChannelDao().getAll() to db.watchProgressDao().getAll()
        block()
        assertEquals(before, state())
        assertEquals(history, db.recentChannelDao().getAll() to db.watchProgressDao().getAll())
    }

    @Test
    fun `bad files get the right result, throw nothing and change nothing`() = runBlocking {
        val random = ByteArray(5_000).also { java.util.Random(7).nextBytes(it) }
        val cases: List<Pair<String, Pair<ByteArray, Class<out BackupCheck>>>> = listOf(
            "empty" to (ByteArray(0) to BackupCheck.Damaged::class.java),
            "blank" to ("  \n ".toByteArray() to BackupCheck.Damaged::class.java),
            "truncated" to (backupJson(",\"favorites\":[{\"id\":\"1\",\"ty").toByteArray() to BackupCheck.Damaged::class.java),
            "random bytes" to (random to BackupCheck.NotABackup::class.java),
            "a json array" to ("[1,2,3]".toByteArray() to BackupCheck.NotABackup::class.java),
            "other json" to ("{\"hello\":\"world\"}".toByteArray() to BackupCheck.NotABackup::class.java),
            "another format" to ("{\"format\":\"other\",\"version\":1}".toByteArray() to BackupCheck.NotABackup::class.java),
            "favorites is a number" to (backupJson(",\"favorites\":5").toByteArray() to BackupCheck.Damaged::class.java),
            "favorites is null" to (backupJson(",\"favorites\":null").toByteArray() to BackupCheck.Damaged::class.java),
            "row is a string" to (backupJson(",\"favorites\":[\"x\"]").toByteArray() to BackupCheck.Damaged::class.java),
            "row id is null" to (backupJson(",\"favorites\":[{\"id\":null,\"type\":\"LIVE\",\"addedAtMillis\":1}]").toByteArray() to BackupCheck.Damaged::class.java),
            "settings is a list" to (backupJson(",\"settings\":[]").toByteArray() to BackupCheck.Damaged::class.java),
            "setting has the wrong type" to (backupJson(",\"settings\":{\"lightMode\":\"yes\"}").toByteArray() to BackupCheck.Damaged::class.java),
            "version is text" to ("{\"format\":\"happytv-backup\",\"version\":\"one\"}".toByteArray() to BackupCheck.Damaged::class.java),
            "no version" to ("{\"format\":\"happytv-backup\"}".toByteArray() to BackupCheck.Damaged::class.java),
            "a future version" to (backupJson(",\"favorites\":5", version = 99).toByteArray() to BackupCheck.FromNewerVersion::class.java),
            "too many favorites" to (backupJson(",\"favorites\":[${favoritesJson(BackupLimits.MAX_FAVORITES + 1, 5)}]").toByteArray() to BackupCheck.TooLarge::class.java),
            "a huge file" to (ByteArray(BackupLimits.MAX_BYTES + 1) { ' '.code.toByte() } to BackupCheck.TooLarge::class.java),
        )
        assertUntouched {
            cases.forEach { (name, case) ->
                val (data, expected) = case
                val result = checkOf(ByteArrayInputStream(data))
                assertEquals("case: $name", expected, result::class.java)
            }
        }
    }

    @Test
    fun `an oversized stream is refused after reading only the cap, not the whole thing`() {
        var read = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                read++
                return ' '.code
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                read += len
                return len
            }
        }

        assertEquals(BackupCheck.TooLarge, checkOf(endless))
        assertTrue("read $read bytes", read <= BackupLimits.MAX_BYTES + 16 * 1024)
    }

    @Test
    fun `an oversized file is refused by its length without being read`() = runBlocking {
        dir.mkdirs()
        val f = File(dir, "big.json")
        java.io.RandomAccessFile(f, "rw").use { it.setLength(BackupLimits.MAX_BYTES + 1L) }

        assertEquals(BackupCheck.TooLarge, backup.check(f))
    }

    @Test
    fun `a missing or unreadable file is damaged, not an exception`() = runBlocking {
        assertEquals(BackupCheck.Damaged, backup.check(File(dir, "nope.json")))
        dir.mkdirs()
        assertEquals(BackupCheck.Damaged, backup.check(dir)) // a folder
        val boom = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("unplugged")
        }
        assertEquals(BackupCheck.Damaged, backup.check(boom))
    }

    @Test
    fun `any unexpected exception while checking is damaged, and a failing stream write is a clean failure`() = runBlocking {
        val boom = object : InputStream() {
            override fun read(): Int = throw IllegalStateException("odd")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IndexOutOfBoundsException("odd")
        }
        assertEquals(BackupCheck.Damaged, backup.check(boom))

        val badOut = object : java.io.OutputStream() {
            override fun write(b: Int) = throw IllegalStateException("odd")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IllegalStateException("odd")
        }
        assertEquals(BackupWriteResult.Failed(BackupWriteFailure.CANNOT_WRITE), backup.writeLocalBackup(badOut))
    }

    @Test
    fun `a file at every row cap passes and is under the size cap`() = runBlocking {
        val body = StringBuilder()
        body.append(",\"favorites\":[").append(favoritesJson(BackupLimits.MAX_FAVORITES, 6)).append("]")
        body.append(",\"hiddenChannels\":[").append((0 until BackupLimits.MAX_HIDDEN_CHANNELS).joinToString(",") { "{\"id\":\"${it.toString().padStart(6, '0')}\",\"type\":\"LIVE\"}" }).append("]")
        body.append(",\"hiddenCategories\":[").append((0 until BackupLimits.MAX_HIDDEN_CATEGORIES).joinToString(",") { "{\"id\":\"$it\",\"type\":\"LIVE\"}" }).append("]")
        val data = backupJson(body.toString()).toByteArray()
        assertTrue("${data.size} bytes", data.size < BackupLimits.MAX_BYTES)

        val result = checkOf(ByteArrayInputStream(data))

        val preview = (result as BackupCheck.Ok).backup.preview
        assertEquals(BackupLimits.MAX_FAVORITES, preview.favoritesTotal)
        assertEquals(BackupLimits.MAX_HIDDEN_CHANNELS, preview.hiddenChannels)
    }

    @Test
    fun `rows with a bad id or an unknown type are dropped one by one and the rest loads`() = runBlocking {
        val longId = "9".repeat(BackupLimits.MAX_ID_LENGTH + 1)
        val okId = "9".repeat(BackupLimits.MAX_ID_LENGTH)
        val json = backupJson(
            ",\"favorites\":[" +
                "{\"id\":\"1\",\"type\":\"LIVE\",\"addedAtMillis\":1}," +
                "{\"id\":\"2\",\"type\":\"RADIO\",\"addedAtMillis\":1}," +
                "{\"id\":\"$longId\",\"type\":\"LIVE\",\"addedAtMillis\":1}," +
                "{\"id\":\"$okId\",\"type\":\"VOD\",\"addedAtMillis\":-5}," +
                "{\"id\":\"\",\"type\":\"LIVE\",\"addedAtMillis\":1}," +
                "{\"id\":\"3\",\"type\":\"live\",\"addedAtMillis\":1}]," +
                "\"hiddenChannels\":[{\"id\":\"5\",\"type\":\"NOPE\"},{\"id\":\"6\",\"type\":\"LIVE\"}]," +
                "\"unknownField\":{\"anything\":[1,2,3]}",
        )
        val checked = (checkOf(ByteArrayInputStream(json.toByteArray())) as BackupCheck.Ok).backup
        db.favoriteDao().insert(Favorite("old", StreamType.LIVE))

        backup.restore(checked)

        assertEquals(setOf("1", okId), db.favoriteDao().getAll().map { it.streamId }.toSet())
        assertEquals(0L, db.favoriteDao().getAll().first { it.streamId == okId }.addedAt.toEpochMilli())
        assertEquals(listOf("6"), db.hiddenChannelDao().getAll().map { it.streamId })
    }

    @Test
    fun `garbage setting values fall back to the default and the valid ones load`() = runBlocking {
        val json = backupJson(
            ",\"settings\":{\"lightMode\":true,\"epgRefreshHours\":-3,\"epgPastDaysToKeep\":99999," +
                "\"preferredStreamFormat\":\"mkv\",\"aspectRatio\":\"21:9\",\"liveTvOpenOn\":\"nonsense\"," +
                "\"backWhileWatching\":\"\",\"refreshOnStart\":false}",
        )
        settings.setAspectRatio("4:3")
        settings.setEpgRefreshHours(6)

        val checked = (checkOf(ByteArrayInputStream(json.toByteArray())) as BackupCheck.Ok).backup
        assertEquals(2, checked.preview.settingsInFile)
        backup.restore(checked)

        val now = settings.settings.first()
        assertTrue(now.lightMode)
        assertFalse(now.refreshOnStart)
        assertEquals(SettingsRepository.ASPECT_AUTO, now.aspectRatio)
        assertEquals(SettingsRepository.DEFAULT_EPG_REFRESH_HOURS, now.epgRefreshHours)
        assertEquals(SettingsRepository.FORMAT_M3U8, now.preferredStreamFormat)
    }

    @Test
    fun `a file with extra fields still loads, and a recents list in a local file is ignored`() = runBlocking {
        seedHistory()
        val json = backupJson(",\"someNewThing\":1,\"recents\":[{\"id\":\"zzz\",\"watchedAtMillis\":1}],\"favorites\":[]")
        val checked = (checkOf(ByteArrayInputStream(json.toByteArray())) as BackupCheck.Ok).backup

        backup.restore(checked)

        assertEquals(listOf("55"), db.recentChannelDao().getAll().map { it.streamId })
    }

    // ------------------------------------------------------------------ writing

    @Test
    fun `writing to a place that cannot be written fails cleanly and leaves nothing behind`() = runBlocking {
        seedA()
        dir.mkdirs()
        val blocker = File(dir, "blocker").also { it.writeText("x") }

        val result = backup.writeLocalBackup(File(blocker, "HappyTV/b.json"))

        assertEquals(BackupWriteResult.Failed(BackupWriteFailure.CANNOT_WRITE), result)
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `writing creates the folder, replaces a same-named file and goes through a stream too`() = runBlocking {
        seedA()
        val target = File(dir, "HappyTV/b.json")
        assertTrue(backup.writeLocalBackup(target) is BackupWriteResult.Written)
        assertTrue(backup.writeLocalBackup(target) is BackupWriteResult.Written)
        assertEquals(listOf("b.json"), target.parentFile!!.list()!!.toList())

        val out = java.io.ByteArrayOutputStream()
        val written = backup.writeLocalBackup(out) as BackupWriteResult.Written
        assertEquals(3, written.summary.favoritesTotal)
        assertTrue(backup.check(ByteArrayInputStream(out.toByteArray())) is BackupCheck.Ok)
    }

    @Test
    fun `the file name is dated to the minute`() {
        val millis = Instant.parse("2026-11-03T21:05:59Z").toEpochMilli()
        assertEquals("HappyTV-backup-2026-11-03-2105.json", backup.suggestedFileName(millis, ZoneOffset.UTC))
    }

    @Test
    fun `export caps each list at its row cap so the writer cannot make a file the checker refuses`() = runBlocking {
        db.favoriteDao().insertAll((0 until BackupLimits.MAX_FAVORITES + 5).map { Favorite("$it", StreamType.LIVE, Instant.ofEpochMilli(it.toLong())) })

        val exported = backup.export()

        assertEquals(BackupLimits.MAX_FAVORITES, exported.favorites.size)
        assertEquals("newest kept", "${BackupLimits.MAX_FAVORITES + 4}", exported.favorites.first().id)
    }

    // ------------------------------------------------------------------ the Android restore uses the checker

    @Test
    fun `the Android file goes through the checker, a bad one is deleted and imports nothing`() = runBlocking {
        dir.mkdirs()
        val cases = listOf(
            ByteArray(BackupLimits.MAX_BYTES + 1) { ' '.code.toByte() },
            backupJson(",\"favorites\":[${favoritesJson(BackupLimits.MAX_FAVORITES + 1)}]").toByteArray(),
            "{\"version\":1,\"favorites\":{}}".toByteArray(),
            "{\"hello\":1}".toByteArray(),
            ByteArray(100) { 0x7f },
        )
        cases.forEach {
            backup.file.writeBytes(it)
            assertFalse(backup.importIfPending())
            assertFalse(backup.file.exists())
            assertTrue(db.favoriteDao().getAll().isEmpty())
        }
    }

    @Test
    fun `the Android file with bad rows imports the good ones and caps the ids`() = runBlocking {
        dir.mkdirs()
        backup.file.writeText(
            "{\"version\":1,\"favorites\":[{\"id\":\"1\",\"type\":\"LIVE\",\"addedAtMillis\":1}," +
                "{\"id\":\"${"7".repeat(500)}\",\"type\":\"LIVE\",\"addedAtMillis\":1},{\"id\":\"2\",\"type\":\"X\",\"addedAtMillis\":1}]}",
        )

        assertTrue(backup.importIfPending())

        assertEquals(listOf("1"), db.favoriteDao().getAll().map { it.streamId })
        assertFalse(backup.file.exists())
    }

    @Test
    fun `a failing import is reported as false and the file is still removed`() = runBlocking {
        dir.mkdirs()
        backup.file.writeText("{\"version\":1,\"hiddenChannels\":[{\"id\":\"1\",\"type\":\"LIVE\"}]}")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_hidden BEFORE INSERT ON hidden_channels BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )

        assertFalse(backup.importIfPending())
        assertFalse(backup.file.exists())
    }

    @Test
    fun `the Android backup rules still carry only the one household file`() {
        // The undo copy and local backups live outside the include rules, so they never reach the cloud backup.
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText() + File("src/main/res/xml/backup_rules.xml").readText()
        assertFalse(rules.contains(HouseholdBackup.UNDO_FILE_NAME))
        assertTrue(rules.contains(HouseholdBackup.FILE_NAME))
    }

    // ------------------------------------------------------------------ #216: every setting's effective value

    private fun defaults() = AppSettings()

    @Test
    fun `a local backup at defaults carries all ten settings with their default values`() = runBlocking {
        val file = writeBackup()
        val checked = okOf(file)
        val d = defaults()
        val s = checked.file.settings

        assertEquals(10, checked.preview.settingsInFile)
        assertEquals(d.refreshOnStart, s.refreshOnStart)
        assertEquals(d.epgRefreshHours, s.epgRefreshHours)
        assertEquals(d.epgPastDaysToKeep, s.epgPastDaysToKeep)
        assertEquals(d.preferredStreamFormat, s.preferredStreamFormat)
        assertEquals(d.replayFromStart, s.replayFromStart)
        assertEquals(d.aspectRatio, s.aspectRatio)
        assertEquals(d.liveTvOpenOn.stored, s.liveTvOpenOn)
        assertEquals(d.keepCategoriesOpen, s.keepCategoriesOpen)
        assertEquals(d.backWhileWatching.stored, s.backWhileWatching)
        assertEquals(d.lightMode, s.lightMode)
        // Writing the backup stored nothing on this TV.
        assertEquals(HouseholdSettings(), settings.householdSettingsSnapshot())
    }

    @Test
    fun `the undo copy carries every setting`() = runBlocking {
        seedA()
        val file = writeBackup()
        backup.restore(okOf(file))

        val undo = (backup.checkUndoCopy() as BackupCheck.Ok).backup
        assertEquals(10, undo.preview.settingsInFile)
    }

    @Test
    fun `a round trip restores each value and a stored default is not a change`() = runBlocking {
        seedA()
        settings.setAspectRatio("4:3")
        val file = writeBackup()
        changeToB()
        settings.setReplayFromStart(defaults().replayFromStart) // stored, equal to the default

        val checked = okOf(file)
        assertEquals(
            setOf(BackupSetting.STREAM_FORMAT, BackupSetting.LIGHT_MODE),
            checked.preview.settingsChanged.toSet(),
        )
        backup.restore(checked)

        val now = settings.settings.first()
        assertTrue(now.lightMode)
        assertFalse(now.refreshOnStart)
        assertEquals(SettingsRepository.FORMAT_TS, now.preferredStreamFormat)
        assertEquals("4:3", now.aspectRatio)
        assertEquals(checked.file.settings, settings.effectiveHouseholdSettings())
    }

    @Test
    fun `an unset setting and one stored at its default do not show as changed`() = runBlocking {
        val file = writeBackup() // all defaults
        settings.setLightMode(false)
        settings.setEpgRefreshHours(defaults().epgRefreshHours)

        assertEquals(emptyList<BackupSetting>(), okOf(file).preview.settingsChanged)
    }

    @Test
    fun `a file from before this change, with nulls, restores those settings to defaults`() = runBlocking {
        settings.setLightMode(true)
        settings.setAspectRatio("16:9")
        settings.setEpgRefreshHours(6)
        val json = backupJson(",\"settings\":{\"refreshOnStart\":false}")

        val checked = (checkOf(ByteArrayInputStream(json.toByteArray())) as BackupCheck.Ok).backup
        assertEquals(1, checked.preview.settingsInFile)
        assertTrue(BackupSetting.LIGHT_MODE in checked.preview.settingsChanged)
        backup.restore(checked)

        val now = settings.settings.first()
        assertFalse(now.refreshOnStart)
        assertFalse(now.lightMode)
        assertEquals(SettingsRepository.ASPECT_AUTO, now.aspectRatio)
        assertEquals(SettingsRepository.DEFAULT_EPG_REFRESH_HOURS, now.epgRefreshHours)
    }

    @Test
    fun `the Android backup file still carries only the settings that were set`() = runBlocking {
        settings.setLightMode(true)
        val exported = backup.export().settings
        assertEquals(true, exported.lightMode)
        assertEquals(null, exported.epgRefreshHours)
        assertEquals(null, exported.aspectRatio)
    }

    // ------------------------------------------------------------------ #245: the removed catch-up setting

    /**
     * The file 1.0.0 wrote for the Android backup (the writer is `HouseholdBackup.writeForBackup` at
     * tag v1.0.0): no `format`, no app version, favorites without a position, and every setting key
     * written, null when never set (`encodeDefaults = true`), including `catchupEnabled`.
     */
    private val file100 =
        "{\"version\":1," +
            "\"favorites\":[{\"id\":\"101\",\"type\":\"LIVE\",\"addedAtMillis\":5000}]," +
            "\"hiddenCategories\":[{\"id\":\"7\",\"type\":\"LIVE\"}]," +
            "\"hiddenChannels\":[{\"id\":\"404\",\"type\":\"LIVE\"}]," +
            "\"recents\":[{\"id\":\"55\",\"watchedAtMillis\":9000}]," +
            "\"progress\":[{\"id\":\"66\",\"type\":\"VOD\",\"seriesId\":null,\"season\":null,\"episode\":null," +
            "\"positionMillis\":1000,\"durationMillis\":5000,\"updatedAtMillis\":10000,\"finishedAtMillis\":null}]," +
            "\"settings\":{\"refreshOnStart\":false,\"epgRefreshHours\":null,\"epgPastDaysToKeep\":null," +
            "\"preferredStreamFormat\":\"ts\",\"catchupEnabled\":false,\"replayFromStart\":true,\"aspectRatio\":null," +
            "\"liveTvOpenOn\":null,\"keepCategoriesOpen\":null,\"backWhileWatching\":null,\"lightMode\":true}}"

    @Test
    fun `a 1_0_0 Android backup file with the catch-up field passes the checker and the field is ignored`() {
        val result = BackupChecker.check(ByteArrayInputStream(file100.toByteArray()), requireMarker = false)

        val ok = result as BackupCheck.Ok
        // refreshOnStart, preferredStreamFormat, replayFromStart and lightMode are known; catchupEnabled is not counted.
        assertEquals(4, ok.backup.preview.settingsInFile)
        assertEquals(1, ok.backup.preview.favoritesTotal)
    }

    @Test
    fun `a 1_0_0 Android backup file with the catch-up field restores everything else after sign-in`() = runBlocking {
        dir.mkdirs()
        backup.file.writeText(file100)

        assertTrue(backup.importIfPending())

        assertEquals(listOf("101"), db.favoriteDao().getAll().map { it.streamId })
        assertEquals(listOf("404"), db.hiddenChannelDao().getAll().map { it.streamId })
        val now = settings.settings.first()
        assertFalse(now.refreshOnStart)
        assertEquals(SettingsRepository.FORMAT_TS, now.preferredStreamFormat)
        assertTrue(now.replayFromStart)
        assertTrue(now.lightMode)
        assertFalse(backup.file.exists())
    }

    @Test
    fun `a file with the catch-up field in its settings restores as a local backup without error and names no replay setting`() = runBlocking {
        val json = backupJson(
            ",\"settings\":{\"catchupEnabled\":false,\"lightMode\":true,\"replayFromStart\":true}",
        )
        val checked = (checkOf(ByteArrayInputStream(json.toByteArray())) as BackupCheck.Ok).backup
        assertEquals(2, checked.preview.settingsInFile)
        assertEquals(
            setOf(BackupSetting.LIGHT_MODE, BackupSetting.REPLAY_FROM_START),
            checked.preview.settingsChanged.toSet(),
        )

        val result = backup.restore(checked)

        assertTrue(result is RestoreResult.Restored)
        val now = settings.settings.first()
        assertTrue(now.lightMode)
        assertTrue(now.replayFromStart)
    }

    @Test
    fun `a new backup does not write the catch-up field`() = runBlocking {
        val file = writeBackup()
        assertFalse(file.readText().contains("catchup", ignoreCase = true))
    }
}
