package com.rysal.happytv.backup

import androidx.room.withTransaction
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.HiddenCategory
import com.rysal.happytv.data.entity.HiddenChannel
import com.rysal.happytv.data.entity.RecentChannel
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.WatchProgress
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * #19: the one small file the Android backup carries.
 *
 * Favorites, hidden categories and channels, recently watched, watch progress
 * and non-secret settings, keyed by the provider's stream and category ids.
 * It has no host, username, password or URL, and nothing in it identifies the
 * account. The sign-in details and the Room database are never backed up (see
 * `res/xml/backup_rules.xml`), so a restored install asks the user to sign in
 * and then calls [HouseholdBackup.importIfPending] once.
 */
@Serializable
data class HouseholdBackupFile(
    val version: Int = VERSION,
    val favorites: List<FavoriteRow> = emptyList(),
    val hiddenCategories: List<HiddenRow> = emptyList(),
    val hiddenChannels: List<HiddenRow> = emptyList(),
    val recents: List<RecentRow> = emptyList(),
    val progress: List<ProgressRow> = emptyList(),
    val settings: HouseholdSettings = HouseholdSettings(),
    /** #209: set on files a person can hand over; [FORMAT] marks a file as a HappyTV backup. The Android file has none. */
    val format: String? = null,
    val appVersion: String? = null,
    val createdAtMillis: Long? = null,
) {
    /** [position] (#221) is the user's order within [type], 1 = top; files from before it have none and restore in the order the screens showed before (stream id). */
    @Serializable
    data class FavoriteRow(val id: String, val type: String, val addedAtMillis: Long, val position: Int? = null)

    @Serializable data class HiddenRow(val id: String, val type: String)

    @Serializable data class RecentRow(val id: String, val watchedAtMillis: Long)

    @Serializable
    data class ProgressRow(
        val id: String,
        val type: String,
        val seriesId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val positionMillis: Long,
        val durationMillis: Long,
        val updatedAtMillis: Long,
        val finishedAtMillis: Long? = null,
    )

    companion object {
        const val VERSION = 1
        const val FORMAT = "happytv-backup"
    }
}

/**
 * The non-secret settings that travel. Null = the user never set it. Deliberately
 * absent: the guide link, the unencrypted sign-in override, refresh stamps, the
 * cached account info and the backup switch itself.
 */
@Serializable
data class HouseholdSettings(
    val refreshOnStart: Boolean? = null,
    val epgRefreshHours: Int? = null,
    val epgPastDaysToKeep: Int? = null,
    val preferredStreamFormat: String? = null,
    val replayFromStart: Boolean? = null,
    val aspectRatio: String? = null,
    val liveTvOpenOn: String? = null,
    val keepCategoriesOpen: Boolean? = null,
    val backWhileWatching: String? = null,
    val lightMode: Boolean? = null,
)

/** Why a local backup was not written. Nothing partial is left behind in any case. */
enum class BackupWriteFailure { NO_SPACE, CANNOT_WRITE, DATABASE }

sealed interface BackupWriteResult {
    /** [summary] counts what went into the file, for the "Backed up" confirmation. */
    data class Written(val summary: BackupPreview) : BackupWriteResult

    data class Failed(val reason: BackupWriteFailure) : BackupWriteResult
}

/** Where a restore stopped. Only [SAVING_UNDO_COPY] and [DATABASE] leave the TV exactly as it was. */
enum class RestoreFailure {
    /** The "before restore" copy could not be saved, so nothing was changed. */
    SAVING_UNDO_COPY,

    /** Favorites and hidden items could not be replaced; the transaction rolled back and settings were not touched. */
    DATABASE,

    /** Favorites and hidden items were replaced but settings could not be written. Undo is available. */
    SETTINGS,
}

sealed interface RestoreResult {
    data class Restored(val undoAvailable: Boolean) : RestoreResult

    /** [BackupCheck.Ok] is never here: the saved undo copy failed the checker, so nothing was changed. */
    data class Rejected(val check: BackupCheck) : RestoreResult

    data class Failed(val stage: RestoreFailure) : RestoreResult
}

/**
 * The household backup engine: the Android-backup file (#19) and the local backup, check, restore
 * and undo (#209). No UI. Every restore goes through [BackupChecker].
 *
 * Local backup, for the Settings screen:
 * - [writeLocalBackup] writes favorites, hidden items and settings (never credentials, recents or progress).
 * - [check] a file or stream, show [CheckedBackup.preview] in the confirmation, then [restore] it.
 * - [restore] replaces, after saving a "before restore" copy; [hasUndoCopy], [checkUndoCopy] and
 *   [restoreFromUndoCopy] bring that copy back.
 *
 * Everything here is safe to call from the main thread: file and Room work runs on [io].
 */
class HouseholdBackup(
    private val database: HappyTvDatabase,
    private val settings: SettingsRepository,
    /** Where the file lives; it exists only for the length of a backup, or after a restore. */
    private val dir: File,
    private val appVersion: String = "",
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Serializes restore, undo and the one-time import so two never interleave. */
    private val mutex = Mutex()

    val file: File get() = File(dir, FILE_NAME)

    /** The single "before restore" slot, overwritten by each restore. App-private, and not in the Android backup. */
    val undoFile: File get() = File(dir, UNDO_FILE_NAME)

    /** The household data as a backup file, read from the database and settings (the Android backup carries recents and progress too). */
    suspend fun export(): HouseholdBackupFile = snapshot(includeHistory = true)

    private suspend fun snapshot(includeHistory: Boolean): HouseholdBackupFile = HouseholdBackupFile(
        favorites = database.favoriteDao().getAll()
            .sortedWith(compareBy<Favorite> { it.streamType.ordinal }.thenBy { it.position }.thenByDescending { it.addedAt })
            .take(BackupLimits.MAX_FAVORITES)
            .map { HouseholdBackupFile.FavoriteRow(it.streamId, it.streamType.name, it.addedAt.toEpochMilli(), it.position) },
        hiddenCategories = database.hiddenCategoryDao().getAll()
            .take(BackupLimits.MAX_HIDDEN_CATEGORIES)
            .map { HouseholdBackupFile.HiddenRow(it.categoryId, it.categoryType.name) },
        hiddenChannels = database.hiddenChannelDao().getAll()
            .take(BackupLimits.MAX_HIDDEN_CHANNELS)
            .map { HouseholdBackupFile.HiddenRow(it.streamId, it.streamType.name) },
        recents = if (!includeHistory) emptyList() else database.recentChannelDao().getAll()
            .take(BackupLimits.MAX_RECENTS)
            .map { HouseholdBackupFile.RecentRow(it.streamId, it.lastWatchedUtcMillis) },
        progress = if (!includeHistory) emptyList() else database.watchProgressDao().getAll()
            .take(BackupLimits.MAX_PROGRESS)
            .map {
                HouseholdBackupFile.ProgressRow(
                    id = it.streamId,
                    type = it.streamType.name,
                    seriesId = it.seriesId,
                    season = it.seasonNumber,
                    episode = it.episodeNumber,
                    positionMillis = it.positionMillis,
                    durationMillis = it.durationMillis,
                    updatedAtMillis = it.updatedUtcMillis,
                    finishedAtMillis = it.finishedAt,
                )
            },
        // #216: a local backup carries every setting's effective value; the Android file only what was set.
        settings = if (includeHistory) settings.householdSettingsSnapshot() else settings.effectiveHouseholdSettings(),
        format = if (includeHistory) null else HouseholdBackupFile.FORMAT,
        appVersion = if (includeHistory) null else appVersion.takeIf { it.isNotBlank() },
        createdAtMillis = if (includeHistory) null else clockMillis(),
    )

    /**
     * Writes [file] for the backup that is about to run. With the switch off it
     * writes nothing and removes any old file, so the backup is empty. Returns
     * whether a file was written.
     */
    suspend fun writeForBackup(): Boolean {
        if (!settings.settings.first().backupHousehold) {
            clear()
            return false
        }
        dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(HouseholdBackupFile.serializer(), export()))
        file.delete()
        check(tmp.renameTo(file)) { "household backup file could not be written" }
        return true
    }

    /** Removes the file (after a backup, so it never sits on disk or is mistaken for a restore). */
    fun clear() {
        file.delete()
        File(dir, "$FILE_NAME.tmp").delete()
    }

    // ------------------------------------------------------------------ local backup (#209)

    /**
     * Writes a local backup to [target]: favorites, hidden items and settings, with the format
     * version, app version and creation time. Atomic: the bytes go to `<name>.tmp` beside it, are
     * flushed to disk, then renamed, so a full disk or an unplugged drive never leaves a half
     * file under the real name. Never throws.
     */
    suspend fun writeLocalBackup(target: File): BackupWriteResult = withContext(io) {
        val data = try {
            snapshot(includeHistory = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "local backup not read: ${e::class.java.simpleName}")
            return@withContext BackupWriteResult.Failed(BackupWriteFailure.DATABASE)
        }
        try {
            writeAtomically(target, json.encodeToString(HouseholdBackupFile.serializer(), data).toByteArray(Charsets.UTF_8))
            BackupWriteResult.Written(BackupChecker.previewOf(data, current = null))
        } catch (e: IOException) {
            Log.w(TAG, "local backup not written: ${e::class.java.simpleName}")
            BackupWriteResult.Failed(if (isNoSpace(e)) BackupWriteFailure.NO_SPACE else BackupWriteFailure.CANNOT_WRITE)
        } catch (e: SecurityException) {
            BackupWriteResult.Failed(BackupWriteFailure.CANNOT_WRITE)
        }
    }

    /** Like the [File] overload for a stream the caller opened (not atomic: the caller owns the destination). The stream is not closed. */
    suspend fun writeLocalBackup(out: OutputStream): BackupWriteResult = withContext(io) {
        val data = try {
            snapshot(includeHistory = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext BackupWriteResult.Failed(BackupWriteFailure.DATABASE)
        }
        try {
            out.write(json.encodeToString(HouseholdBackupFile.serializer(), data).toByteArray(Charsets.UTF_8))
            out.flush()
            BackupWriteResult.Written(BackupChecker.previewOf(data, current = null))
        } catch (e: IOException) {
            BackupWriteResult.Failed(if (isNoSpace(e)) BackupWriteFailure.NO_SPACE else BackupWriteFailure.CANNOT_WRITE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupWriteResult.Failed(BackupWriteFailure.CANNOT_WRITE)
        }
    }

    /** `HappyTV-backup-YYYY-MM-DD-HHMM.json` for [millis] in [zone]; the name Part 2 gives a new backup. */
    fun suggestedFileName(millis: Long = clockMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
        "HappyTV-backup-" + FILE_NAME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone)) + ".json"

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val parent = target.absoluteFile.parentFile ?: throw IOException("no folder")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) throw IOException("folder missing")
        val tmp = File(parent, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (target.exists() && !target.delete()) throw IOException("could not replace the file")
            if (!tmp.renameTo(target)) throw IOException("could not finish the file")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun isNoSpace(e: IOException): Boolean {
        val m = e.message.orEmpty()
        return m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)
    }

    // ------------------------------------------------------------------ check, restore, undo (#209)

    /** Checks a backup file a person handed over. Never throws, never changes anything. */
    suspend fun check(source: File): BackupCheck = withContext(io) { withCurrentSettings(BackupChecker.check(source, requireMarker = true)) }

    /** Checks a backup read from [input] (the caller closes it). Never throws, never changes anything. */
    suspend fun check(input: InputStream): BackupCheck = withContext(io) { withCurrentSettings(BackupChecker.check(input, requireMarker = true)) }

    private suspend fun withCurrentSettings(result: BackupCheck): BackupCheck {
        if (result !is BackupCheck.Ok) return result
        val current = try {
            settings.householdSettingsSnapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return result
        }
        val file = result.backup.file
        return BackupCheck.Ok(CheckedBackup(file, BackupChecker.previewOf(file, current)))
    }

    /**
     * Replaces the favorites, hidden items and settings on this TV with [checked]'s. Recents and
     * watch progress are not touched. First the TV's current state is saved to [undoFile]; if that
     * fails nothing changes. Then favorites and hidden items are replaced in one short Room
     * transaction (hidden-category flags in the catalog included, so screens update at once); only
     * if that commits are the settings overwritten. Never throws.
     */
    suspend fun restore(checked: CheckedBackup): RestoreResult = withContext(io) {
        mutex.withLock { applyLocked(checked.file, saveUndoCopy = true) }
    }

    /** True when a "before restore" copy exists, so the screen can offer Undo. */
    fun hasUndoCopy(): Boolean = undoFile.isFile

    /** The preview of the "before restore" copy; [BackupCheck.NotABackup] when there is none. */
    suspend fun checkUndoCopy(): BackupCheck = withContext(io) {
        if (!undoFile.exists()) BackupCheck.NotABackup else withCurrentSettings(BackupChecker.check(undoFile, requireMarker = true))
    }

    /** Brings back the state saved by the last [restore], then removes the copy (one slot, one undo). */
    suspend fun restoreFromUndoCopy(): RestoreResult = withContext(io) {
        mutex.withLock {
            if (!undoFile.exists()) return@withLock RestoreResult.Rejected(BackupCheck.NotABackup)
            when (val result = BackupChecker.check(undoFile, requireMarker = true)) {
                is BackupCheck.Ok -> {
                    val applied = applyLocked(result.backup.file, saveUndoCopy = false)
                    if (applied is RestoreResult.Restored) undoFile.delete()
                    if (applied is RestoreResult.Restored) RestoreResult.Restored(undoAvailable = false) else applied
                }
                else -> RestoreResult.Rejected(result)
            }
        }
    }

    private suspend fun applyLocked(data: HouseholdBackupFile, saveUndoCopy: Boolean): RestoreResult {
        if (saveUndoCopy) {
            try {
                val current = snapshot(includeHistory = false)
                writeAtomically(undoFile, json.encodeToString(HouseholdBackupFile.serializer(), current).toByteArray(Charsets.UTF_8))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "undo copy not saved: ${e::class.java.simpleName}")
                return RestoreResult.Failed(RestoreFailure.SAVING_UNDO_COPY)
            }
        }
        try {
            // Small rows, one short transaction, nothing slow inside it (F-65).
            database.withTransaction {
                database.favoriteDao().clear()
                database.favoriteDao().insertAll(favoriteEntities(data.favorites))
                database.hiddenCategoryDao().clear()
                database.hiddenCategoryDao().insertAll(
                    data.hiddenCategories.mapNotNull { r -> type(r.type)?.let { HiddenCategory(r.id, it) } },
                )
                // The catalog's own copy of the flag: clear every one, then set from the new rows.
                database.categoryDao().clearHiddenFlags()
                StreamType.entries.forEach { database.categoryDao().reapplyHidden(it) }
                database.hiddenChannelDao().clear()
                database.hiddenChannelDao().insertAll(
                    data.hiddenChannels.mapNotNull { r -> type(r.type)?.let { HiddenChannel(r.id, it) } },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "restore rolled back: ${e::class.java.simpleName}")
            return RestoreResult.Failed(RestoreFailure.DATABASE)
        }
        try {
            settings.replaceHouseholdSettings(data.settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "restore settings not written: ${e::class.java.simpleName}")
            return RestoreResult.Failed(RestoreFailure.SETTINGS)
        }
        return RestoreResult.Restored(undoAvailable = saveUndoCopy)
    }

    // ------------------------------------------------------------------ Android restore (#19)

    /**
     * Restore: imports a restored [file] once, then deletes it. Runs after the
     * first successful sign-in. It never overwrites: if any household data
     * already exists the file is discarded unread, and a setting the user has
     * already set stays as it is. Rows are keyed by provider ids and do not
     * depend on the catalog, so the order against the catalog refresh does not
     * matter (the refresh re-applies hidden-category flags from the rows).
     * The file goes through [BackupChecker] first (#209). Returns true when
     * something was imported; never throws.
     */
    suspend fun importIfPending(): Boolean = withContext(io) {
        if (!file.exists()) return@withContext false
        mutex.withLock {
            try {
                val backup = (BackupChecker.check(file, requireMarker = false) as? BackupCheck.Ok)?.backup?.file
                    ?: return@withLock false
                if (hasHouseholdData()) return@withLock false
                database.withTransaction {
                    database.favoriteDao().insertAll(favoriteEntities(backup.favorites))
                    database.hiddenCategoryDao().insertAll(
                        backup.hiddenCategories.mapNotNull { r -> type(r.type)?.let { HiddenCategory(r.id, it) } },
                    )
                    database.hiddenChannelDao().insertAll(
                        backup.hiddenChannels.mapNotNull { r -> type(r.type)?.let { HiddenChannel(r.id, it) } },
                    )
                    database.recentChannelDao().insertAll(backup.recents.map { RecentChannel(it.id, it.watchedAtMillis) })
                    database.watchProgressDao().insertAll(
                        backup.progress.mapNotNull { r ->
                            type(r.type)?.let {
                                WatchProgress(
                                    streamType = it,
                                    streamId = r.id,
                                    seriesId = r.seriesId,
                                    seasonNumber = r.season,
                                    episodeNumber = r.episode,
                                    positionMillis = r.positionMillis,
                                    durationMillis = r.durationMillis,
                                    updatedUtcMillis = r.updatedAtMillis,
                                    finishedAt = r.finishedAtMillis,
                                )
                            }
                        },
                    )
                }
                settings.importHouseholdSettings(backup.settings)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "household import failed: ${e::class.java.simpleName}")
                false
            } finally {
                // Once means once: imported, discarded or unreadable, the file goes.
                clear()
            }
        }
    }

    private suspend fun hasHouseholdData(): Boolean =
        database.favoriteDao().getAll().isNotEmpty() ||
            database.hiddenCategoryDao().getAll().isNotEmpty() ||
            database.hiddenChannelDao().getAll().isNotEmpty() ||
            database.recentChannelDao().getAll().isNotEmpty() ||
            database.watchProgressDao().getAll().isNotEmpty()

    private fun type(name: String): StreamType? = StreamType.entries.firstOrNull { it.name == name }

    companion object {
        const val FILE_NAME = "household_backup.json"
        const val UNDO_FILE_NAME = "before_restore_backup.json"
        private const val TAG = "HouseholdBackup"
        private val FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.US)
    }
}

/**
 * #221: the favorites rows a backup restores. Within each stream type the order is the file's
 * `position` when every row of that type has one, else the order the screens showed favorites
 * before positions existed (the stream id as text, see `MIGRATION_5_6`), and positions are
 * renumbered 1..N so they are always dense.
 */
internal fun favoriteEntities(rows: List<HouseholdBackupFile.FavoriteRow>): List<Favorite> {
    val known = rows.mapNotNull { r -> StreamType.entries.firstOrNull { it.name == r.type }?.let { it to r } }
    return known.groupBy({ it.first }, { it.second }).flatMap { (type, group) ->
        val ordered = if (group.all { it.position != null }) {
            group.sortedWith(compareBy<HouseholdBackupFile.FavoriteRow> { it.position }.thenByDescending { it.addedAtMillis })
        } else {
            group.sortedBy { it.id }
        }
        ordered.mapIndexed { i, r -> Favorite(r.id, type, Instant.ofEpochMilli(r.addedAtMillis), i + 1) }
    }
}
