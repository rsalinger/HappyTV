package com.rysal.happytv.ui.settings

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.backup.BackupLocation
import com.rysal.happytv.backup.BackupStorage
import com.rysal.happytv.backup.HouseholdBackup
import com.rysal.happytv.backup.listJsonFiles
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.security.InMemoryEpgOverrideStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #210: the Settings > Backup flow driven through [BackupViewModel] with the real engine, a real
 * folder and a fake storage (permission and drives are the only fakes).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BackupFlowTest {

    private lateinit var db: HappyTvDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var engine: HouseholdBackup
    private lateinit var root: File
    private lateinit var storage: FakeStorage
    private lateinit var vm: BackupViewModel

    private class FakeStorage(var access: Boolean, val tv: BackupLocation, var drives: List<BackupLocation> = emptyList()) : BackupStorage {
        override fun hasAccess() = access
        override suspend fun locations() = listOf(tv) + drives
        override suspend fun listBackupFiles(folder: File) = listJsonFiles(folder)
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "flow-settings-${System.nanoTime()}.preferences_pb")
        }
        settings = SettingsRepository(dataStore, InMemoryEpgOverrideStore())
        root = File(context.cacheDir, "flow-${System.nanoTime()}").also { it.mkdirs() }
        engine = HouseholdBackup(
            db, settings, File(root, "private"),
            appVersion = "1.1.0-test", io = Dispatchers.Unconfined, clockMillis = { 1_700_000_000_000 },
        )
        val tv = BackupLocation("This TV", File(root, "Download/HappyTV"), isDrive = false, pathLabel = "Download/HappyTV")
        storage = FakeStorage(access = true, tv = tv)
        vm = BackupViewModel(HouseholdBackupEngine(engine), storage, CoroutineScope(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() {
        val drained = java.util.concurrent.CountDownLatch(1)
        runCatching { db.queryExecutor.execute { drained.countDown() } }
        drained.await(5, java.util.concurrent.TimeUnit.SECONDS)
        db.close()
        root.deleteRecursively()
    }

    private val step get() = vm.ui.value.step

    /** The engine reads Room on its own thread, so wait for the running step to finish. */
    private fun settle() {
        val end = System.currentTimeMillis() + 10_000
        while (step is BackupStep.Working && System.currentTimeMillis() < end) Thread.sleep(10)
    }
    private val tv get() = storage.tv

    private suspend fun favorites() = db.favoriteDao().getAll().map { it.streamId }.toSet()

    private suspend fun addFavorite(id: String) =
        db.favoriteDao().insert(Favorite(id, StreamType.LIVE, Instant.ofEpochMilli(1_000)))

    @Test
    fun `the access explanation tells people to find HappyTV in the list`() {
        assertEquals(
            "HappyTV needs All files access to save and find backups after HappyTV is reinstalled. " +
                "On the next screen, find HappyTV in the list and turn it on.",
            BackupPaneCopy.EXPLAIN_ACCESS,
        )
    }

    @Test
    fun `without access Back up now explains why, and a refusal leaves a message and no file`() {
        storage.access = false
        vm.refreshAccess()
        assertFalse(vm.ui.value.accessGranted)

        vm.start(BackupAction.BACKUP)
        assertTrue(step is BackupStep.ExplainAccess)

        assertEquals(BackupAction.BACKUP, vm.accessExplained())
        assertNull(step)
        // The person comes back from the system screen without granting.
        vm.refreshAccess()
        val notice = step as BackupStep.Notice
        assertEquals("All files access is off", notice.title)
        assertFalse(tv.folder.exists())
    }

    @Test
    fun `granting access continues to the location choice`() {
        storage.access = false
        vm.start(BackupAction.RESTORE)
        vm.accessExplained()
        storage.access = true
        vm.refreshAccess()
        val choose = step as BackupStep.ChooseLocation
        assertEquals(BackupAction.RESTORE, choose.action)
        assertEquals(listOf("This TV"), choose.locations.map { it.label })
    }

    @Test
    fun `a mounted drive is offered by name`() {
        storage.drives = listOf(BackupLocation("Kingston", File(root, "usb/HappyTV"), isDrive = true, pathLabel = "HappyTV folder on the drive"))
        vm.start(BackupAction.BACKUP)
        val choose = step as BackupStep.ChooseLocation
        assertEquals(listOf("This TV", "Kingston"), choose.locations.map { it.label })
    }

    @Test
    fun `back up, change, restore, then undo returns each earlier state`() = runBlocking {
        addFavorite("1")
        settings.setLightMode(true)

        vm.start(BackupAction.BACKUP)
        vm.chooseLocation(BackupAction.BACKUP, tv)
        settle()
        val saved = step as BackupStep.Notice
        assertEquals("Backup saved", saved.title)
        assertTrue(saved.body, saved.body.contains("1 favorite"))
        val files = tv.folder.listFiles().orEmpty().map { it.name }
        assertEquals(1, files.size)
        assertTrue(files[0].startsWith("HappyTV-backup-") && files[0].endsWith(".json"))
        vm.dismiss()

        // Change things.
        addFavorite("2")
        settings.setLightMode(false)

        vm.start(BackupAction.RESTORE)
        vm.chooseLocation(BackupAction.RESTORE, tv)
        settle()
        val list = step as BackupStep.ChooseFile
        assertEquals(1, list.files.size)
        vm.chooseFile(list.files[0])
        val confirm = step as BackupStep.ConfirmRestore
        assertFalse(confirm.undo)
        assertTrue(restorePreviewLines(confirm.checked.preview).joinToString().contains("Light mode"))

        // Cancel changes nothing.
        vm.dismiss()
        assertNull(step)
        assertEquals(setOf("1", "2"), favorites())
        assertFalse(settings.settings.first().lightMode)

        vm.chooseFile(list.files[0])
        vm.confirmRestore()
        settle()
        assertEquals("Backup restored", (step as BackupStep.Notice).title)
        assertEquals(setOf("1"), favorites())
        assertTrue(settings.settings.first().lightMode)
        assertTrue(vm.ui.value.undoAvailable)
        vm.dismiss()

        vm.startUndo()
        settle()
        val undo = step as BackupStep.ConfirmRestore
        assertTrue(undo.undo)
        vm.confirmRestore()
        settle()
        assertEquals("Restore undone", (step as BackupStep.Notice).title)
        assertEquals(setOf("1", "2"), favorites())
        assertFalse(settings.settings.first().lightMode)
    }

    @Test
    fun `a damaged file and a non-backup json each give a message and change nothing`() = runBlocking {
        addFavorite("1")
        tv.folder.mkdirs()
        File(tv.folder, "damaged.json").writeText("{\"format\":\"happytv-backup\",\"version\":1,\"favorites\":[{\"id\":")
        File(tv.folder, "other.json").writeText("{\"hello\":\"world\"}")
        File(tv.folder, "notes.txt").writeText("not listed")

        vm.start(BackupAction.RESTORE)
        vm.chooseLocation(BackupAction.RESTORE, tv)
        settle()
        val list = step as BackupStep.ChooseFile
        assertEquals(setOf("damaged.json", "other.json"), list.files.map { it.name }.toSet())

        val byName = list.files.associateBy { it.name }
        vm.chooseFile(byName.getValue("damaged.json"))
        assertEquals("This backup is damaged and can't be restored.", (step as BackupStep.Notice).body)
        vm.dismiss()
        vm.chooseFile(byName.getValue("other.json"))
        assertEquals("This file isn't a HappyTV backup.", (step as BackupStep.Notice).body)
        vm.dismiss()

        assertEquals(setOf("1"), favorites())
    }

    @Test
    fun `a backup from a newer version and an oversized file are named`() {
        tv.folder.mkdirs()
        File(tv.folder, "newer.json").writeText("{\"format\":\"happytv-backup\",\"version\":99}")
        File(tv.folder, "big.json").writeBytes(ByteArray(5 * 1024 * 1024) { ' '.code.toByte() })

        vm.start(BackupAction.RESTORE)
        vm.chooseLocation(BackupAction.RESTORE, tv)
        settle()
        val messages = (step as BackupStep.ChooseFile).files.associate { it.name to checkFailureMessage(it.check) }
        assertEquals("This backup was made by a newer version of HappyTV. Update HappyTV to use it.", messages["newer.json"])
        assertEquals("This file is too large to be a HappyTV backup.", messages["big.json"])
    }

    @Test
    fun `an empty or missing folder says no backups were found`() {
        vm.start(BackupAction.RESTORE)
        vm.chooseLocation(BackupAction.RESTORE, tv)
        settle()
        assertEquals("No backups found", (step as BackupStep.Notice).title)
    }

    @Test
    fun `a folder that cannot be written gives a message, not a crash`() {
        // A plain file sits where the folder should go, as a read-only or unplugged drive would fail.
        val blocked = BackupLocation("Drive", File(root, "blocker"), isDrive = true, pathLabel = "HappyTV folder on the drive")
        File(root, "blocker").writeText("x")

        vm.start(BackupAction.BACKUP)
        vm.chooseLocation(BackupAction.BACKUP, blocked)
        settle()

        val notice = step as BackupStep.Notice
        assertEquals("Backup not saved", notice.title)
        assertTrue(notice.body, notice.body.contains("Drive"))
    }

    @Test
    fun `undo is offered only once a restore has saved a copy`() = runBlocking {
        assertFalse(vm.ui.value.undoAvailable)
        addFavorite("1")
        vm.start(BackupAction.BACKUP)
        vm.chooseLocation(BackupAction.BACKUP, tv)
        settle()
        vm.dismiss()
        assertFalse(vm.ui.value.undoAvailable)

        vm.start(BackupAction.RESTORE)
        vm.chooseLocation(BackupAction.RESTORE, tv)
        settle()
        vm.chooseFile((step as BackupStep.ChooseFile).files[0])
        vm.confirmRestore()
        settle()
        assertTrue(vm.ui.value.undoAvailable)
    }

    @Test
    fun `the file written holds no credentials and only the favorites, hidden items and settings`() = runBlocking {
        addFavorite("1")
        vm.start(BackupAction.BACKUP)
        vm.chooseLocation(BackupAction.BACKUP, tv)
        settle()
        val text = tv.folder.listFiles()!!.single().readText()
        for (word in listOf("password", "username", "host", "token", "http")) {
            assertFalse("$word in backup", text.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun `counts and dates read plainly`() {
        val one = com.rysal.happytv.backup.BackupPreview(
            createdAtMillis = null, appVersion = null,
            favoritesByType = mapOf(StreamType.LIVE to 1), hiddenCategories = 0, hiddenChannels = 1,
            settingsInFile = 2, settingsChanged = emptyList(),
        )
        assertEquals("1 favorite, 1 hidden item, 2 settings", countsLine(one))
        assertEquals(listOf("The backup has 1 favorite, 1 hidden item, 2 settings.", "No settings would change."), restorePreviewLines(one))
    }
}
