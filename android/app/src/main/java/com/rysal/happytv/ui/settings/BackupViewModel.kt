package com.rysal.happytv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rysal.happytv.backup.AndroidBackupStorage
import com.rysal.happytv.backup.BackupCheck
import com.rysal.happytv.backup.BackupLocation
import com.rysal.happytv.backup.BackupPreview
import com.rysal.happytv.backup.BackupSetting
import com.rysal.happytv.backup.BackupStorage
import com.rysal.happytv.backup.BackupWriteFailure
import com.rysal.happytv.backup.BackupWriteResult
import com.rysal.happytv.backup.CheckedBackup
import com.rysal.happytv.backup.HouseholdBackup
import com.rysal.happytv.backup.RestoreFailure
import com.rysal.happytv.backup.RestoreResult
import com.rysal.happytv.crash.CrashRecorder
import com.rysal.happytv.crash.CrashReportStore
import com.rysal.happytv.data.Graph
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The engine calls the Backup pane uses; [HouseholdBackup] in production, a fake in tests. */
interface BackupEngine {
    fun fileName(): String
    suspend fun write(target: File): BackupWriteResult
    suspend fun check(file: File): BackupCheck
    suspend fun restore(checked: CheckedBackup): RestoreResult
    fun hasUndoCopy(): Boolean
    suspend fun checkUndoCopy(): BackupCheck
    suspend fun restoreUndoCopy(): RestoreResult
}

class HouseholdBackupEngine(private val backup: HouseholdBackup) : BackupEngine {
    override fun fileName(): String = backup.suggestedFileName()
    override suspend fun write(target: File) = backup.writeLocalBackup(target)
    override suspend fun check(file: File) = backup.check(file)
    override suspend fun restore(checked: CheckedBackup) = backup.restore(checked)
    override fun hasUndoCopy() = backup.hasUndoCopy()
    override suspend fun checkUndoCopy() = backup.checkUndoCopy()
    override suspend fun restoreUndoCopy() = backup.restoreFromUndoCopy()
}

/** [SAVE_CRASH_REPORTS] (#259) shares the access explanation and the location choice with the backup actions. */
enum class BackupAction { BACKUP, RESTORE, SAVE_CRASH_REPORTS }

/** One backup file in the Restore list. [check] is its result; only [BackupCheck.Ok] can be restored. */
data class BackupFileRow(
    val file: File,
    val whenMillis: Long,
    val check: BackupCheck,
) {
    val name: String get() = file.name
}

/** What the Backup flow is showing over the pane. Null means nothing: the pane itself. */
sealed interface BackupStep {
    /** One sentence on why All files access is needed, before the system screen opens. */
    data class ExplainAccess(val action: BackupAction) : BackupStep

    /** The choice between This TV and each mounted drive. */
    data class ChooseLocation(val action: BackupAction, val locations: List<BackupLocation>) : BackupStep

    /** Writing, reading or restoring. Takes no input. */
    data class Working(val title: String, val body: String) : BackupStep

    /** The backups found in one location, newest first. */
    data class ChooseFile(val location: BackupLocation, val files: List<BackupFileRow>) : BackupStep

    /** The confirmation. [undo] means it is the Undo last restore one. */
    data class ConfirmRestore(val checked: CheckedBackup, val sourceLabel: String, val undo: Boolean) : BackupStep

    /** A result or an error, with one OK button. */
    data class Notice(val title: String, val body: String) : BackupStep
}

data class BackupUiState(
    /** The file permission this Android version needs is granted. */
    val accessGranted: Boolean = false,
    /** A "before restore" copy exists, so Undo last restore is shown. */
    val undoAvailable: Boolean = false,
    val step: BackupStep? = null,
    /** #259: when the newest crash report was made, for Settings > About. Null: none. */
    val lastCrashMillis: Long? = null,
    /** #259: how many crash reports are kept; "Save crash reports" does nothing at 0. */
    val crashReportCount: Int = 0,
)

/**
 * The Settings > Backup flow (#210): choose a location, write or list, confirm, restore, undo.
 * All file work runs in the engine and storage (both main-safe) on [scope], which is the app scope
 * in production so a restore that has started is never cut off by leaving the screen.
 */
class BackupViewModel(
    private val engine: BackupEngine,
    private val storage: BackupStorage,
    private val scope: CoroutineScope,
    /** #259: the local crash reports; null means none are recorded. */
    private val crashReports: CrashReportStore? = null,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        BackupUiState(accessGranted = storage.hasAccess(), undoAvailable = engine.hasUndoCopy())
            .withCrashReports(),
    )
    val ui: StateFlow<BackupUiState> = _ui.asStateFlow()

    /** Set when the person said yes to the explanation and went to the system screen. */
    private var waitingForAccess: BackupAction? = null

    private fun setStep(step: BackupStep?) = _ui.update { it.copy(step = step) }

    /** Called when Settings comes back to the front and after a permission prompt. */
    fun refreshAccess() {
        val granted = storage.hasAccess()
        val waiting = waitingForAccess
        waitingForAccess = null
        _ui.update { it.copy(accessGranted = granted, undoAvailable = engine.hasUndoCopy()).withCrashReports() }
        if (waiting == null) return
        if (granted) {
            chooseLocation(waiting)
        } else {
            setStep(if (waiting == BackupAction.SAVE_CRASH_REPORTS) Msg.noAccessForCrashReports() else Msg.noAccess())
        }
    }

    fun start(action: BackupAction) {
        refreshOnly()
        if (!_ui.value.accessGranted) setStep(BackupStep.ExplainAccess(action)) else chooseLocation(action)
    }

    private fun refreshOnly() {
        _ui.update { it.copy(accessGranted = storage.hasAccess(), undoAvailable = engine.hasUndoCopy()).withCrashReports() }
    }

    /** Reads file names only: the folder holds at most five small files. */
    private fun BackupUiState.withCrashReports(): BackupUiState {
        val store = crashReports ?: return this
        val reports = store.list()
        return copy(lastCrashMillis = reports.firstOrNull()?.timeMillis, crashReportCount = reports.size)
    }

    /** Continue on the explanation: the screen opens the system access screen next. */
    fun accessExplained(): BackupAction? {
        val step = _ui.value.step as? BackupStep.ExplainAccess ?: return null
        waitingForAccess = step.action
        setStep(null)
        return step.action
    }

    /** The system screen for access could not be opened on this TV. */
    fun accessScreenUnavailable() {
        waitingForAccess = null
        setStep(
            Msg.of(
                "Can't open the access screen",
                "This TV has no screen for All files access, so Backup and Restore are unavailable. Everything else in HappyTV works as before.",
            ),
        )
    }

    private fun chooseLocation(action: BackupAction) {
        setStep(BackupStep.Working("Looking for places to save", "One moment."))
        scope.launch {
            val locations = try {
                storage.locations()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (locations.isEmpty()) {
                setStep(Msg.of("Nowhere to save", "HappyTV couldn't find a place to keep backups on this TV."))
            } else {
                setStep(BackupStep.ChooseLocation(action, locations))
            }
        }
    }

    fun chooseLocation(action: BackupAction, location: BackupLocation) {
        when (action) {
            BackupAction.BACKUP -> backUpTo(location)
            BackupAction.RESTORE -> listBackups(location)
            BackupAction.SAVE_CRASH_REPORTS -> saveCrashReportsTo(location)
        }
    }

    /** #259: copies the kept reports into the location's HappyTV folder, then says where. */
    private fun saveCrashReportsTo(location: BackupLocation) {
        setStep(BackupStep.Working("Saving crash reports", "One moment."))
        scope.launch {
            val saved = try {
                withContext(Dispatchers.IO) { crashReports?.copyAllTo(location.folder) ?: 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            refreshOnly()
            setStep(
                when {
                    saved == null -> Msg.of(
                        "Crash reports not saved",
                        writeFailureMessage(BackupWriteFailure.CANNOT_WRITE, location.label),
                    )
                    saved == 0 -> Msg.of("No crash reports", "There are no crash reports to save.")
                    else -> Msg.of(
                        "Crash reports saved",
                        "Saved ${plural(saved, "crash report")} to the ${BackupStorage.FOLDER_NAME} folder on ${location.label}.",
                    )
                },
            )
        }
    }

    private fun backUpTo(location: BackupLocation) {
        setStep(BackupStep.Working("Backing up", "Saving your favorites, hidden items and settings."))
        scope.launch {
            val target = File(location.folder, engine.fileName())
            val result = try {
                engine.write(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupWriteResult.Failed(BackupWriteFailure.CANNOT_WRITE)
            }
            setStep(
                when (result) {
                    is BackupWriteResult.Written -> Msg.of(
                        "Backup saved",
                        "Saved ${target.name} to ${location.label}. ${countsLine(result.summary)}.",
                    )
                    is BackupWriteResult.Failed -> Msg.of("Backup not saved", writeFailureMessage(result.reason, location.label))
                },
            )
        }
    }

    private fun listBackups(location: BackupLocation) {
        setStep(BackupStep.Working("Looking for backups", "Checking ${location.label}."))
        scope.launch {
            val rows = try {
                storage.listBackupFiles(location.folder).map { file ->
                    val check = engine.check(file)
                    val whenMillis = (check as? BackupCheck.Ok)?.backup?.preview?.createdAtMillis ?: file.lastModified()
                    BackupFileRow(file, whenMillis, check)
                }.sortedWith(compareByDescending<BackupFileRow> { it.check is BackupCheck.Ok }.thenByDescending { it.whenMillis }.thenBy { it.name })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (rows.isEmpty()) {
                setStep(
                    Msg.of(
                        "No backups found",
                        "There are no backups in ${location.pathLabel} on ${location.label}. Choose Back up now to make one.",
                    ),
                )
            } else {
                setStep(BackupStep.ChooseFile(location, rows))
            }
        }
    }

    /** OK on a file row: a good backup goes to the confirmation, anything else gets its message. */
    fun chooseFile(row: BackupFileRow) {
        when (val check = row.check) {
            is BackupCheck.Ok -> setStep(BackupStep.ConfirmRestore(check.backup, backupWhenLabel(row.whenMillis), undo = false))
            else -> setStep(Msg.of("Can't restore this file", checkFailureMessage(check)))
        }
    }

    /** Undo last restore: checks the saved copy, then asks. */
    fun startUndo() {
        setStep(BackupStep.Working("Checking the saved copy", "One moment."))
        scope.launch {
            val check = try {
                engine.checkUndoCopy()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupCheck.Damaged
            }
            refreshOnly()
            setStep(
                when (check) {
                    is BackupCheck.Ok -> BackupStep.ConfirmRestore(check.backup, "before your last restore", undo = true)
                    else -> Msg.of("Can't undo", "The saved copy from before your last restore ${undoFailureTail(check)}")
                },
            )
        }
    }

    fun confirmRestore() {
        val step = _ui.value.step as? BackupStep.ConfirmRestore ?: return
        setStep(
            BackupStep.Working(
                if (step.undo) "Undoing the last restore" else "Restoring",
                "Replacing your favorites, hidden items and settings.",
            ),
        )
        scope.launch {
            val result = try {
                if (step.undo) engine.restoreUndoCopy() else engine.restore(step.checked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RestoreResult.Failed(RestoreFailure.DATABASE)
            }
            refreshOnly()
            setStep(restoreNotice(result, step.undo))
        }
    }

    /** Cancel, Back or OK on a notice. A running step ([BackupStep.Working]) cannot be dismissed. */
    fun dismiss() {
        if (_ui.value.step is BackupStep.Working) return
        setStep(null)
    }

    private fun restoreNotice(result: RestoreResult, undo: Boolean): BackupStep.Notice = when (result) {
        is RestoreResult.Restored -> if (undo) {
            Msg.of("Restore undone", "Your favorites, hidden items and settings are back as they were before the last restore.")
        } else {
            Msg.of("Backup restored", "Your favorites, hidden items and settings now match the backup. Choose Undo last restore to go back.")
        }
        is RestoreResult.Rejected -> Msg.of("Can't restore this file", checkFailureMessage(result.check))
        is RestoreResult.Failed -> when (result.stage) {
            RestoreFailure.SAVING_UNDO_COPY ->
                Msg.of("Nothing changed", "HappyTV couldn't save a copy of what's on this TV first, so it didn't restore. Free some space and try again.")
            RestoreFailure.DATABASE ->
                Msg.of("Nothing changed", "HappyTV couldn't restore your favorites and hidden items. Try again.")
            RestoreFailure.SETTINGS ->
                Msg.of("Settings not restored", "Your favorites and hidden items were restored, but your settings couldn't be saved. Choose Undo last restore to go back.")
        }
    }

    private object Msg {
        fun of(title: String, body: String) = BackupStep.Notice(title, body)
        fun noAccessForCrashReports() = of(
            "All files access is off",
            "Crash reports can't be saved until you turn it on. Everything else in HappyTV works as before.",
        )
        fun noAccess() = of(
            "All files access is off",
            "Backup and Restore stay unavailable until you turn it on. Everything else in HappyTV works as before.",
        )
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BackupViewModel(
                engine = HouseholdBackupEngine(Graph.householdBackup),
                storage = AndroidBackupStorage(Graph.appContext),
                scope = Graph.appScope,
                crashReports = CrashRecorder.current?.store,
            ) as T
        }
    }
}

// ----------------------------------------------------------------------- copy

/** Why a backup could not be saved, in words. */
fun writeFailureMessage(reason: BackupWriteFailure, locationLabel: String): String = when (reason) {
    BackupWriteFailure.NO_SPACE -> "There isn't enough space on $locationLabel. Free some space or choose another location."
    BackupWriteFailure.CANNOT_WRITE ->
        "HappyTV couldn't write to $locationLabel. Check that it is plugged in and not read-only, and that All files access is on."
    BackupWriteFailure.DATABASE -> "HappyTV couldn't read your favorites and settings. Try again."
}

/** Why a file cannot be restored. [BackupCheck.Ok] has no message. */
fun checkFailureMessage(check: BackupCheck): String = when (check) {
    is BackupCheck.NotABackup -> "This file isn't a HappyTV backup."
    is BackupCheck.TooLarge -> "This file is too large to be a HappyTV backup."
    is BackupCheck.FromNewerVersion -> "This backup was made by a newer version of HappyTV. Update HappyTV to use it."
    is BackupCheck.Damaged -> "This backup is damaged and can't be restored."
    is BackupCheck.Ok -> ""
}

private fun undoFailureTail(check: BackupCheck): String = when (check) {
    is BackupCheck.FromNewerVersion -> "was made by a newer version of HappyTV."
    is BackupCheck.NotABackup, is BackupCheck.TooLarge, is BackupCheck.Damaged -> "is missing or damaged."
    is BackupCheck.Ok -> ""
}

/** "12 favorites, 3 hidden items, 5 settings". */
fun countsLine(preview: BackupPreview): String {
    val hidden = preview.hiddenCategories + preview.hiddenChannels
    return "${plural(preview.favoritesTotal, "favorite")}, ${plural(hidden, "hidden item")}, ${plural(preview.settingsInFile, "setting")}"
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

/** What the confirmation says would change, in words. */
fun restorePreviewLines(preview: BackupPreview, subject: String = "The backup"): List<String> {
    val lines = mutableListOf("$subject has ${countsLine(preview)}.")
    val changed = preview.settingsChanged
    if (changed.isEmpty()) {
        lines += "No settings would change."
    } else {
        val names = changed.take(PREVIEW_SETTING_NAMES).map(::settingLabel)
        val more = changed.size - names.size
        lines += "Settings that change: ${names.joinToString(", ")}" + if (more > 0) " and $more more." else "."
    }
    return lines
}

private const val PREVIEW_SETTING_NAMES = 4

fun settingLabel(setting: BackupSetting): String = when (setting) {
    BackupSetting.REFRESH_ON_START -> "Refresh on start"
    BackupSetting.GUIDE_REFRESH_HOURS -> "Update the guide every"
    BackupSetting.GUIDE_PAST_DAYS_TO_KEEP -> "Guide days to keep"
    BackupSetting.STREAM_FORMAT -> "Stream format"
    BackupSetting.REPLAY_FROM_START -> "Start replays from the beginning"
    BackupSetting.ASPECT_RATIO -> "Aspect ratio"
    BackupSetting.LIVE_TV_OPENS_ON -> "Open Live TV on"
    BackupSetting.KEEP_CATEGORIES_OPEN -> "Keep categories open"
    BackupSetting.BACK_WHILE_WATCHING -> "Back while watching"
    BackupSetting.LIGHT_MODE -> "Light mode"
}

/** "Nov 2, 2026, 3:45 PM" in the TV's language and time zone. */
fun backupWhenLabel(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
