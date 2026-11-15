package com.rysal.happytv.ui.settings

import com.rysal.happytv.backup.BackupCheck
import com.rysal.happytv.backup.BackupLocation
import com.rysal.happytv.backup.BackupStorage
import com.rysal.happytv.backup.BackupWriteResult
import com.rysal.happytv.backup.CheckedBackup
import com.rysal.happytv.backup.RestoreResult
import com.rysal.happytv.backup.listJsonFiles
import com.rysal.happytv.crash.CrashReportStore
import com.rysal.happytv.crash.ReportKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #259: Settings > About "Last crash" and "Save crash reports", through the Backup flow's storage steps. */
class CrashReportsFlowTest {

    private class NoEngine : BackupEngine {
        override fun fileName() = "unused.json"
        override suspend fun write(target: File): BackupWriteResult = error("not used")
        override suspend fun check(file: File): BackupCheck = error("not used")
        override suspend fun restore(checked: CheckedBackup): RestoreResult = error("not used")
        override fun hasUndoCopy() = false
        override suspend fun checkUndoCopy(): BackupCheck = error("not used")
        override suspend fun restoreUndoCopy(): RestoreResult = error("not used")
    }

    private class FakeStorage(var access: Boolean, val tv: BackupLocation) : BackupStorage {
        override fun hasAccess() = access
        override suspend fun locations() = listOf(tv)
        override suspend fun listBackupFiles(folder: File) = listJsonFiles(folder)
    }

    private lateinit var root: File
    private lateinit var store: CrashReportStore
    private lateinit var storage: FakeStorage
    private lateinit var vm: BackupViewModel

    @Before
    fun setUp() {
        root = Files.createTempDirectory("crash-flow").toFile()
        store = CrashReportStore(File(root, "crash-reports"))
        val tv = BackupLocation("This TV", File(root, "Download/HappyTV"), isDrive = false, pathLabel = "Download/HappyTV")
        storage = FakeStorage(access = true, tv = tv)
        vm = BackupViewModel(NoEngine(), storage, CoroutineScope(Dispatchers.Unconfined), store)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun settle(): BackupStep? {
        val end = System.currentTimeMillis() + 10_000
        while (vm.ui.value.step is BackupStep.Working && System.currentTimeMillis() < end) Thread.sleep(10)
        return vm.ui.value.step
    }

    @Test
    fun `with no reports the state shows none and nothing to save`() {
        assertNull(vm.ui.value.lastCrashMillis)
        assertEquals(0, vm.ui.value.crashReportCount)
    }

    @Test
    fun `the last crash time is the newest report and updates on refresh`() {
        store.write(ReportKind.CRASH, 1_700_000_000_000, "a")
        vm.refreshAccess()
        assertEquals(1_700_000_000_000, vm.ui.value.lastCrashMillis)
        store.write(ReportKind.EXIT, 1_700_000_900_000, "b")
        vm.refreshAccess()
        assertEquals(1_700_000_900_000, vm.ui.value.lastCrashMillis)
        assertEquals(2, vm.ui.value.crashReportCount)
    }

    @Test
    fun `saving offers the locations and then copies the reports and says where`() {
        store.write(ReportKind.CRASH, 1_700_000_000_000, "report one")
        store.write(ReportKind.CRASH, 1_700_000_100_000, "report two")
        vm.refreshAccess()

        vm.start(BackupAction.SAVE_CRASH_REPORTS)
        val choose = settle() as BackupStep.ChooseLocation
        assertEquals(BackupAction.SAVE_CRASH_REPORTS, choose.action)

        vm.chooseLocation(BackupAction.SAVE_CRASH_REPORTS, storage.tv)
        val notice = settle() as BackupStep.Notice
        assertEquals("Crash reports saved", notice.title)
        assertEquals("Saved 2 crash reports to the HappyTV folder on This TV.", notice.body)
        assertEquals(2, storage.tv.folder.listFiles()!!.size)
        // The app still keeps its own copies.
        assertEquals(2, store.count())
    }

    @Test
    fun `one report is worded in the singular`() {
        store.write(ReportKind.CRASH, 1_700_000_000_000, "only")
        vm.chooseLocation(BackupAction.SAVE_CRASH_REPORTS, storage.tv)
        assertEquals("Saved 1 crash report to the HappyTV folder on This TV.", (settle() as BackupStep.Notice).body)
    }

    @Test
    fun `without file access saving explains why first, and a refusal says reports stay unsaved`() {
        storage.access = false
        store.write(ReportKind.CRASH, 1_700_000_000_000, "x")
        vm.refreshAccess()
        vm.start(BackupAction.SAVE_CRASH_REPORTS)
        val explain = vm.ui.value.step as BackupStep.ExplainAccess
        assertEquals(BackupAction.SAVE_CRASH_REPORTS, explain.action)

        assertEquals(BackupAction.SAVE_CRASH_REPORTS, vm.accessExplained())
        vm.refreshAccess()
        val notice = vm.ui.value.step as BackupStep.Notice
        assertTrue(notice.body.startsWith("Crash reports can't be saved"))
        assertFalse(storage.tv.folder.exists())
    }

    @Test
    fun `a destination that cannot be written gives an error message and no exception`() {
        store.write(ReportKind.CRASH, 1_700_000_000_000, "x")
        val blocker = File(root, "blocker").apply { writeText("file") }
        val bad = BackupLocation("USB drive", File(blocker, "HappyTV"), isDrive = true, pathLabel = "x")
        vm.chooseLocation(BackupAction.SAVE_CRASH_REPORTS, bad)
        val notice = settle() as BackupStep.Notice
        assertEquals("Crash reports not saved", notice.title)
        assertTrue(notice.body.contains("USB drive"))
    }

    @Test
    fun `the saved copies do not show up as backups to restore`() {
        store.write(ReportKind.CRASH, 1_700_000_000_000, "x")
        vm.chooseLocation(BackupAction.SAVE_CRASH_REPORTS, storage.tv)
        settle()
        assertTrue(listJsonFiles(storage.tv.folder).isEmpty())
    }
}
