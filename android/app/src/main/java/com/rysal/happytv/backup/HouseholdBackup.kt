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
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
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
) {
    @Serializable data class FavoriteRow(val id: String, val type: String, val addedAtMillis: Long)

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
    val catchupEnabled: Boolean? = null,
    val replayFromStart: Boolean? = null,
    val aspectRatio: String? = null,
    val liveTvOpenOn: String? = null,
    val keepCategoriesOpen: Boolean? = null,
    val backWhileWatching: String? = null,
    val lightMode: Boolean? = null,
)

class HouseholdBackup(
    private val database: HappyTvDatabase,
    private val settings: SettingsRepository,
    /** Where the file lives; it exists only for the length of a backup, or after a restore. */
    private val dir: File,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val file: File get() = File(dir, FILE_NAME)

    /** The household data as a backup file, read from the database and settings. */
    suspend fun export(): HouseholdBackupFile = HouseholdBackupFile(
        favorites = database.favoriteDao().getAll()
            .map { HouseholdBackupFile.FavoriteRow(it.streamId, it.streamType.name, it.addedAt.toEpochMilli()) },
        hiddenCategories = database.hiddenCategoryDao().getAll()
            .map { HouseholdBackupFile.HiddenRow(it.categoryId, it.categoryType.name) },
        hiddenChannels = database.hiddenChannelDao().getAll()
            .map { HouseholdBackupFile.HiddenRow(it.streamId, it.streamType.name) },
        recents = database.recentChannelDao().getAll()
            .map { HouseholdBackupFile.RecentRow(it.streamId, it.lastWatchedUtcMillis) },
        progress = database.watchProgressDao().getAll().map {
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
        settings = settings.householdSettingsSnapshot(),
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

    /**
     * Restore: imports a restored [file] once, then deletes it. Runs after the
     * first successful sign-in. It never overwrites: if any household data
     * already exists the file is discarded unread, and a setting the user has
     * already set stays as it is. Rows are keyed by provider ids and do not
     * depend on the catalog, so the order against the catalog refresh does not
     * matter (the refresh re-applies hidden-category flags from the rows).
     * Returns true when something was imported.
     */
    suspend fun importIfPending(): Boolean {
        if (!file.exists()) return false
        try {
            val backup = runCatching { json.decodeFromString(HouseholdBackupFile.serializer(), file.readText()) }
                .getOrNull() ?: return false
            if (backup.version > HouseholdBackupFile.VERSION || hasHouseholdData()) return false
            database.withTransaction {
                database.favoriteDao().insertAll(
                    backup.favorites.mapNotNull { r ->
                        type(r.type)?.let { Favorite(r.id, it, Instant.ofEpochMilli(r.addedAtMillis)) }
                    },
                )
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
            return true
        } finally {
            // Once means once: imported, discarded or unreadable, the file goes.
            clear()
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
    }
}
