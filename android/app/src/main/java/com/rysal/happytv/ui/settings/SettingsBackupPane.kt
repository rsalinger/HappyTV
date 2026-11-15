@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.rysal.happytv.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.focusable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.rysal.happytv.ui.browse.HtPrimaryButton
import com.rysal.happytv.ui.browse.HtSecondaryButton
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.browseType
import com.rysal.happytv.ui.focus.requestWhenAttached
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.backup.BackupCheck

internal const val FOCUS_KEY_BACKUP_NOW = "backup:now"
internal const val FOCUS_KEY_RESTORE = "backup:restore"
internal const val FOCUS_KEY_UNDO = "backup:undo"
internal const val FOCUS_KEY_BACKUP = "backup:automatic"

/** Copy for the Backup pane's rows. */
object BackupPaneCopy {
    const val BACKUP_NOW = "Back up now"
    const val BACKUP_NOW_CAPTION = "Save your favorites, hidden items and settings to this TV or a USB drive."
    const val RESTORE = "Restore"
    const val RESTORE_CAPTION = "Replace your favorites, hidden items and settings with a saved backup."
    const val UNDO = "Undo last restore"
    const val UNDO_CAPTION = "Go back to what was on this TV before the last restore."
    const val NEEDS_ACCESS = "Needs All files access. Press OK to turn it on."
    /** #216: the system list opens without HappyTV selected on Google TV, so say what to do there. */
    const val EXPLAIN_ACCESS =
        "HappyTV needs All files access to save and find backups after HappyTV is reinstalled. " +
            "On the next screen, find HappyTV in the list and turn it on."
    /** #259: the same permission, asked for to save crash reports. */
    const val EXPLAIN_ACCESS_CRASH_REPORTS =
        "HappyTV needs access to files to save crash reports to the HappyTV folder. " +
            "If the next screen opens, find HappyTV in the list and turn it on."
    const val NEEDS_ACCESS_LEGACY = "Needs permission to use files. Press OK to allow it."
}

/** #259: copy for the About rows. Proposed wording, to be confirmed in the PR review. */
object CrashReportsCopy {
    const val LAST_CRASH = "Last crash"
    const val NO_CRASHES = "No crashes recorded"
    const val SAVE = "Save crash reports"
    const val SAVE_CAPTION = "Copy them to this TV or a USB drive. HappyTV never sends them anywhere."
    const val SAVE_CAPTION_NONE = "There are no crash reports to save."
}

/** Backup — design: the same rows as every pane; the work happens in the overlays below. */
@Composable
internal fun BackupPane(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    backup: BackupUiState?,
    onStart: (BackupAction) -> Unit,
    onUndo: () -> Unit,
    focusOf: (String) -> FocusRequester,
    onRowFocused: (String) -> Unit,
) {
    if (backup != null) {
        val needs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BackupPaneCopy.NEEDS_ACCESS else BackupPaneCopy.NEEDS_ACCESS_LEGACY
        SettingsActionRow(
            label = BackupPaneCopy.BACKUP_NOW,
            caption = if (backup.accessGranted) BackupPaneCopy.BACKUP_NOW_CAPTION else needs,
            onClick = { onStart(BackupAction.BACKUP) },
            onFocused = { onRowFocused(FOCUS_KEY_BACKUP_NOW) },
            focusRequester = focusOf(FOCUS_KEY_BACKUP_NOW),
        )
        SettingsActionRow(
            label = BackupPaneCopy.RESTORE,
            caption = if (backup.accessGranted) BackupPaneCopy.RESTORE_CAPTION else needs,
            onClick = { onStart(BackupAction.RESTORE) },
            onFocused = { onRowFocused(FOCUS_KEY_RESTORE) },
            focusRequester = focusOf(FOCUS_KEY_RESTORE),
        )
        if (backup.undoAvailable) {
            SettingsActionRow(
                label = BackupPaneCopy.UNDO,
                caption = BackupPaneCopy.UNDO_CAPTION,
                onClick = onUndo,
                onFocused = { onRowFocused(FOCUS_KEY_UNDO) },
                focusRequester = focusOf(FOCUS_KEY_UNDO),
            )
        }
    }
    val on = state.settings.backupHousehold
    SettingsSwitchRow(
        label = BackupCopy.LABEL,
        caption = if (on) BackupCopy.CAPTION_ON else BackupCopy.CAPTION_OFF,
        on = on,
        onToggle = { viewModel.setBackupHousehold(!on) },
        onFocused = { onRowFocused(FOCUS_KEY_BACKUP) },
        focusRequester = focusOf(FOCUS_KEY_BACKUP),
    )
}

/** The focus stops of the Backup pane, in draw order. */
internal fun backupFocusKeys(backup: BackupUiState?): List<String> = buildList {
    if (backup != null) {
        add(FOCUS_KEY_BACKUP_NOW)
        add(FOCUS_KEY_RESTORE)
        if (backup.undoAvailable) add(FOCUS_KEY_UNDO)
    }
    add(FOCUS_KEY_BACKUP)
}

// -------------------------------------------------------------- system access

/** Opens the screen where the person grants file access. Returns false when this TV has none. */
internal fun openAccessScreen(context: Context, requestLegacy: () -> Unit): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        requestLegacy()
        return true
    }
    val appUri = Uri.parse("package:${context.packageName}")
    val attempts = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // try the next screen
        } catch (_: SecurityException) {
            // try the next screen
        }
    }
    return false
}

/** Re-checks access whenever Settings comes back to the front (the person may have just granted it). */
@Composable
internal fun RefreshAccessOnResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) onResume() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

// ------------------------------------------------------------------- overlays

/** The Backup flow's dialogs. Drawn over the whole screen like the Settings confirmations. */
@Composable
internal fun BackupOverlay(
    step: BackupStep,
    viewModel: BackupViewModel,
    onContinueToAccess: () -> Unit,
) {
    when (step) {
        is BackupStep.ExplainAccess -> MessageDialog(
            key = step,
            title = "Allow access to files?",
            body = if (step.action == BackupAction.SAVE_CRASH_REPORTS) {
                BackupPaneCopy.EXPLAIN_ACCESS_CRASH_REPORTS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BackupPaneCopy.EXPLAIN_ACCESS
            } else {
                "HappyTV needs permission to save and find backups on this TV and USB drives."
            },
            primaryLabel = "Continue",
            onPrimary = onContinueToAccess,
            secondaryLabel = "Not now",
            onSecondary = viewModel::dismiss,
            cancelFirst = false,
        )
        is BackupStep.ChooseLocation -> ListDialog(
            key = step,
            title = when (step.action) {
                BackupAction.BACKUP -> "Back up to"
                BackupAction.RESTORE -> "Restore from"
                BackupAction.SAVE_CRASH_REPORTS -> "Save crash reports to"
            },
            body = if (step.locations.none { it.isDrive }) "Plug in a USB drive to save to it." else "Choose where HappyTV keeps backups.",
            rows = step.locations.map { Triple(it.label, it.pathLabel, { viewModel.chooseLocation(step.action, it) }) },
            onCancel = viewModel::dismiss,
        )
        is BackupStep.ChooseFile -> ListDialog(
            key = step,
            title = "Restore from ${step.location.label}",
            body = "Newest first. Choose a backup to see what it would change.",
            rows = step.files.map { row ->
                val ok = row.check as? BackupCheck.Ok
                Triple(
                    backupWhenLabel(row.whenMillis),
                    if (ok != null) "${row.name} · ${countsLine(ok.backup.preview)}" else "${row.name} · ${checkFailureMessage(row.check)}",
                    { viewModel.chooseFile(row) },
                )
            },
            onCancel = viewModel::dismiss,
        )
        is BackupStep.Working -> WorkingCard(step.title, step.body)
        is BackupStep.Notice -> MessageDialog(
            key = step,
            title = step.title,
            body = step.body,
            primaryLabel = "OK",
            onPrimary = viewModel::dismiss,
        )
        is BackupStep.ConfirmRestore -> MessageDialog(
            key = step,
            title = if (step.undo) "Undo last restore?" else "Restore this backup?",
            body = buildString {
                append(
                    if (step.undo) {
                        "This puts back the favorites, hidden items and settings you had before the last restore."
                    } else {
                        "This replaces your favorites, hidden items and settings on this TV."
                    },
                )
                append("\n")
                append(restorePreviewLines(step.checked.preview, if (step.undo) "The saved copy" else "The backup").joinToString(" "))
                if (!step.undo) append(" Made ${step.sourceLabel}.")
            },
            primaryLabel = if (step.undo) "Undo restore" else "Restore",
            onPrimary = viewModel::confirmRestore,
            secondaryLabel = "Cancel",
            onSecondary = viewModel::dismiss,
            cancelFirst = true,
        )
    }
}

@Composable
private fun DialogScrim(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HappyTvColors.Scrim)
            .testTag("backup-dialog")
            // Trap the D-pad, as every Settings confirmation does.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.TopCenter,
    ) { content() }
}

@Composable
private fun Modifier.dialogCard(top: Int): Modifier {
    val d = browseDesign()
    return this
        .offset(y = d.px(top))
        .width(d.px(DIALOG_WIDTH_PX))
        .shadow(
            elevation = d.px(DIALOG_SHADOW_PX),
            shape = RoundedCornerShape(d.px(DIALOG_RADIUS_PX)),
            ambientColor = HappyTvColors.shadow(DIALOG_SHADOW_ALPHA),
            spotColor = HappyTvColors.shadow(DIALOG_SHADOW_ALPHA),
        )
        .background(HappyTvColors.Raised, RoundedCornerShape(d.px(DIALOG_RADIUS_PX)))
        .padding(start = d.px(64), top = d.px(56), end = d.px(64), bottom = d.px(64))
}

/** Title, body and one or two buttons. [cancelFirst]: the secondary button takes focus (restores). */
@Composable
private fun MessageDialog(
    key: Any,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    cancelFirst: Boolean = false,
) {
    val d = browseDesign()
    val type = browseType()
    val primary = remember(key) { FocusRequester() }
    val secondary = remember(key) { FocusRequester() }
    DialogScrim {
        Column(
            modifier = Modifier.dialogCard(DIALOG_TOP_PX),
            verticalArrangement = Arrangement.spacedBy(d.px(40)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(d.px(16))) {
                Text(text = title, style = type.compactHeading, color = HappyTvColors.TextPrimary)
                Text(text = body, style = type.compactBody, color = HappyTvColors.TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(d.px(24))) {
                HtPrimaryButton(label = primaryLabel, onClick = onPrimary, focusRequester = primary)
                if (secondaryLabel != null) {
                    HtSecondaryButton(label = secondaryLabel, onClick = onSecondary, focusRequester = secondary)
                }
            }
        }
    }
    LaunchedEffect(key) {
        val target = if (cancelFirst && secondaryLabel != null) secondary else primary
        target.requestWhenAttached()
    }
}

/** A short list of choices with Cancel below it. The first choice takes focus. */
@Composable
private fun ListDialog(
    key: Any,
    title: String,
    body: String,
    rows: List<Triple<String, String, () -> Unit>>,
    onCancel: () -> Unit,
) {
    val d = browseDesign()
    val type = browseType()
    val first = remember(key) { FocusRequester() }
    val cancel = remember(key) { FocusRequester() }
    var firstFocused by remember(key) { mutableStateOf(false) }
    DialogScrim {
        Column(
            modifier = Modifier.dialogCard(LIST_DIALOG_TOP_PX),
            verticalArrangement = Arrangement.spacedBy(d.px(32)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(d.px(12))) {
                Text(text = title, style = type.compactHeading, color = HappyTvColors.TextPrimary)
                Text(text = body, style = type.compactBody, color = HappyTvColors.TextSecondary, maxLines = 2)
            }
            LazyColumn(
                modifier = Modifier.heightIn(max = d.px(LIST_MAX_HEIGHT_PX)),
                contentPadding = PaddingValues(vertical = d.px(8), horizontal = d.px(8)),
                verticalArrangement = Arrangement.spacedBy(d.px(12)),
            ) {
                itemsIndexed(rows) { index, (label, caption, onClick) ->
                    SettingsActionRow(
                        label = label,
                        caption = caption,
                        onClick = onClick,
                        onFocused = { if (index == 0) firstFocused = true },
                        focusRequester = if (index == 0) first else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            HtSecondaryButton(label = "Cancel", onClick = onCancel, focusRequester = cancel)
        }
    }
    LaunchedEffect(key) {
        if (rows.isEmpty()) cancel.requestWhenAttached() else first.requestWhenAttached(landed = { firstFocused })
    }
}

/** Shown while a backup or restore runs: one focus stop that takes the keys, so nothing behind it can be reached. */
@Composable
private fun WorkingCard(title: String, body: String) {
    val d = browseDesign()
    val type = browseType()
    val holder = remember { FocusRequester() }
    DialogScrim {
        Column(
            modifier = Modifier
                .dialogCard(DIALOG_TOP_PX)
                .focusRequester(holder)
                .focusable()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(d.px(16)),
        ) {
            Text(text = title, style = type.compactHeading, color = HappyTvColors.TextPrimary)
            Text(text = body, style = type.compactBody, color = HappyTvColors.TextSecondary)
        }
    }
    LaunchedEffect(Unit) { holder.requestWhenAttached() }
}

private const val LIST_DIALOG_TOP_PX = 96
private const val LIST_MAX_HEIGHT_PX = 480
