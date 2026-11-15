package com.rysal.happytv.ui.settings

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.backup.BackupCheck
import com.rysal.happytv.backup.BackupChecker
import com.rysal.happytv.backup.BackupLocation
import com.rysal.happytv.backup.BackupStorage
import com.rysal.happytv.backup.BackupWriteResult
import com.rysal.happytv.backup.CheckedBackup
import com.rysal.happytv.backup.RestoreResult
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #210: the Backup pane and its dialogs on the real [SettingsScreen], driven with D-pad keys. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BackupPaneScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var undo = true
    private lateinit var backupVm: BackupViewModel
    private val location = BackupLocation("This TV", File("/nowhere/HappyTV"), isDrive = false, pathLabel = "Download/HappyTV")
    private val goodFile = File("/nowhere/HappyTV/HappyTV-backup-2026-11-02-1500.json")

    private fun checked(): CheckedBackup = (
        BackupChecker.check(
            ByteArrayInputStream("{\"format\":\"happytv-backup\",\"version\":1,\"settings\":{\"lightMode\":true}}".toByteArray()),
            requireMarker = true,
        ) as BackupCheck.Ok
        ).backup

    private val engine = object : BackupEngine {
        override fun fileName() = "x.json"
        override suspend fun write(target: File): BackupWriteResult = error("not used")
        override suspend fun check(file: File): BackupCheck = BackupCheck.Ok(checked())
        override suspend fun restore(checked: CheckedBackup): RestoreResult {
            calls += "restore"
            return RestoreResult.Restored(true)
        }
        override fun hasUndoCopy() = undo
        override suspend fun checkUndoCopy(): BackupCheck = BackupCheck.Ok(checked())
        override suspend fun restoreUndoCopy(): RestoreResult {
            calls += "undo"
            return RestoreResult.Restored(false)
        }
    }

    private val storage = object : BackupStorage {
        override fun hasAccess() = true
        override suspend fun locations() = listOf(location)
        override suspend fun listBackupFiles(folder: File) = listOf(goodFile)
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) {
            File(context.cacheDir, "backup-pane-${System.nanoTime()}.preferences_pb")
        }
        val viewModel = SettingsViewModel(
            settings = SettingsRepository(dataStore),
            loadAccount = { SettingsAccount("Home", "provider.example", 8080, "demo-user", 0L) },
            onRefreshEverything = {},
            onSignOut = {},
            onGuideScheduleChanged = {},
            appScope = CoroutineScope(Dispatchers.Unconfined),
            startSection = SettingsSection.BACKUP,
        )
        backupVm = BackupViewModel(engine, storage, CoroutineScope(Dispatchers.Unconfined))
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    SettingsScreen(
                        onBack = {},
                        onGoToDashboard = {},
                        onGoToSignIn = {},
                        viewModel = viewModel,
                        backupViewModel = backupVm,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun node(label: String) = hasText(label) and isFocusable() and !isRoot()

    private fun press(label: String, key: Key) {
        compose.onNode(node(label)).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focusedCount() = compose.onAllNodes(isFocusable() and isFocused()).fetchSemanticsNodes().size

    private fun openConfirmation() {
        compose.runOnIdle {
            backupVm.start(BackupAction.RESTORE)
            backupVm.chooseLocation(BackupAction.RESTORE, location)
            backupVm.chooseFile((backupVm.ui.value.step as BackupStep.ChooseFile).files[0])
        }
        compose.waitForIdle()
    }

    @Test
    fun the_pane_lists_back_up_restore_undo_and_the_automatic_switch_in_order() {
        press("Backup", Key.DirectionRight)
        compose.onNode(node("Back up now")).assertIsFocused()
        press("Back up now", Key.DirectionDown)
        compose.onNode(node("Restore")).assertIsFocused()
        press("Restore", Key.DirectionDown)
        compose.onNode(node("Undo last restore")).assertIsFocused()
        press("Undo last restore", Key.DirectionDown)
        compose.onNode(node("Automatic Google backup")).assertIsFocused()
        press("Automatic Google backup", Key.DirectionDown)
        compose.onNode(node("Automatic Google backup")).assertIsFocused()
        press("Automatic Google backup", Key.DirectionLeft)
        compose.onNode(node("Backup")).assertIsFocused()
    }

    @Test
    fun the_location_list_takes_focus_and_the_found_backups_follow() {
        compose.runOnIdle { backupVm.start(BackupAction.RESTORE) }
        compose.waitForIdle()
        compose.onNode(node("This TV")).assertIsFocused()
        press("This TV", Key.DirectionCenter)
        // The one backup found is the focused first row; Cancel is one Down away.
        assertEquals(1, focusedCount())
        press("Cancel", Key.DirectionUp)
        assertEquals(1, focusedCount())
    }

    @Test
    fun restore_confirmation_opens_with_Cancel_focused_and_Cancel_changes_nothing() {
        openConfirmation()
        compose.onNode(node("Cancel")).assertIsFocused()
        compose.onNodeWithText("This replaces your favorites, hidden items and settings on this TV.", substring = true).assertExists()

        press("Cancel", Key.DirectionCenter)
        assertEquals(emptyList<String>(), calls)
        assertEquals(null, backupVm.ui.value.step)
        // Focus is back on a row in the pane, not lost.
        assertEquals(1, focusedCount())
    }

    @Test
    fun confirming_the_restore_calls_the_engine_once_and_shows_the_result() {
        openConfirmation()
        press("Cancel", Key.DirectionLeft)
        // "Restore" is also the pane row behind the dialog; the focused one is the button.
        compose.onNode(node("Restore") and isFocused()).assertExists()
        compose.onNode(node("Restore") and isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(listOf("restore"), calls)
        compose.onNodeWithText("Backup restored").assertExists()
        assertEquals(1, focusedCount())
    }

    @Test
    fun undo_asks_first_with_Cancel_focused() {
        compose.runOnIdle { backupVm.startUndo() }
        compose.waitForIdle()
        compose.onNode(node("Cancel")).assertIsFocused()
        compose.onNodeWithText("Undo last restore?").assertExists()
        press("Cancel", Key.DirectionLeft)
        press("Undo restore", Key.DirectionCenter)
        assertEquals(listOf("undo"), calls)
    }

    @Test
    fun undo_is_hidden_when_there_is_no_saved_copy() {
        undo = false
        compose.runOnIdle { backupVm.refreshAccess() }
        compose.waitForIdle()
        compose.onNodeWithText("Undo last restore").assertDoesNotExist()
    }
}
