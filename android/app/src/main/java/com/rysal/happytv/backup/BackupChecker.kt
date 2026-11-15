package com.rysal.happytv.backup

import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.entity.StreamType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

/**
 * #209: the limits every backup file is held to, local or the Android restore.
 *
 * The file is a few hundred KB for a heavy household (20k favorites is about 1.3 MB, 30k hidden
 * channels about 1 MB), so 4 MiB leaves room for the largest legitimate file while keeping the
 * worst case a small, bounded allocation on a TV with a 128 MB heap. The row caps are chosen so a
 * file at every cap still fits under [MAX_BYTES]; the writer applies the same caps, so the app can
 * never write a file its own checker refuses.
 */
object BackupLimits {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_FAVORITES = 20_000
    const val MAX_HIDDEN_CATEGORIES = 2_000

    /** The live catalog is 20k+ channels; this allows hiding most of it. */
    const val MAX_HIDDEN_CHANNELS = 30_000
    const val MAX_RECENTS = 2_000
    const val MAX_PROGRESS = 5_000

    /** Provider ids are short numbers; 64 characters is generous and keeps a hostile id out of Room. */
    const val MAX_ID_LENGTH = 64
}

/** What the checker made of a file. It is never an exception. */
sealed interface BackupCheck {
    /** The file passed. Hand [backup] to [HouseholdBackup.restore]; show [CheckedBackup.preview] first. */
    data class Ok(val backup: CheckedBackup) : BackupCheck

    /** Not a HappyTV backup: some other file, other JSON, or not JSON at all. */
    data object NotABackup : BackupCheck

    /** Over [BackupLimits.MAX_BYTES], or a list longer than its row cap. */
    data object TooLarge : BackupCheck

    /** Made by a newer HappyTV than this one understands. */
    data class FromNewerVersion(val version: Int) : BackupCheck

    /** A HappyTV backup that is cut off, garbled or has fields of the wrong type, or a file that could not be read. */
    data object Damaged : BackupCheck
}

/** A file that passed the checker, already cleaned. Only the engine can apply it. */
class CheckedBackup internal constructor(
    internal val file: HouseholdBackupFile,
    val preview: BackupPreview,
)

/** What a restore would do, for the confirmation. All counts are of rows that passed the checker. */
data class BackupPreview(
    /** When the file was made, or null for a file that does not say. */
    val createdAtMillis: Long?,
    /** The HappyTV version that made it, or null. */
    val appVersion: String?,
    val favoritesByType: Map<StreamType, Int>,
    val hiddenCategories: Int,
    val hiddenChannels: Int,
    /** How many settings the file carries. */
    val settingsInFile: Int,
    /** The settings whose value on this TV would change (a setting the file lacks goes back to its default). */
    val settingsChanged: List<BackupSetting>,
) {
    val favoritesTotal: Int get() = favoritesByType.values.sum()
}

/** The settings a backup can carry; Part 2 maps these to the labels shown in Settings. */
enum class BackupSetting(internal val read: (HouseholdSettings) -> Any?) {
    REFRESH_ON_START({ it.refreshOnStart }),
    GUIDE_REFRESH_HOURS({ it.epgRefreshHours }),
    GUIDE_PAST_DAYS_TO_KEEP({ it.epgPastDaysToKeep }),
    STREAM_FORMAT({ it.preferredStreamFormat }),
    REPLAY_FROM_START({ it.replayFromStart }),
    ASPECT_RATIO({ it.aspectRatio }),
    LIVE_TV_OPENS_ON({ it.liveTvOpenOn }),
    KEEP_CATEGORIES_OPEN({ it.keepCategoriesOpen }),
    BACK_WHILE_WATCHING({ it.backWhileWatching }),
    LIGHT_MODE({ it.lightMode }),
}

/**
 * #209: the one checker. Nothing reaches Room or DataStore unless it passes here. Pure: no
 * database, no settings, no Android. It never throws. It has no suspension points, so a
 * coroutine cancellation is the caller's to honour.
 */
object BackupChecker {

    private val json = Json { ignoreUnknownKeys = true }

    /** Only the two fields needed to recognise a file, so a newer file is named as such even if its rows changed shape. */
    @Serializable
    private data class Header(val format: String? = null, val version: Int? = null)

    /**
     * @param requireMarker true for files a person can hand over (they must say they are a HappyTV
     * backup); false for the private Android-restore file, which older builds wrote without a marker.
     */
    fun check(file: File, requireMarker: Boolean): BackupCheck {
        return try {
            if (!file.isFile) return BackupCheck.Damaged
            if (file.length() > BackupLimits.MAX_BYTES) return BackupCheck.TooLarge
            file.inputStream().use { check(it, requireMarker) }
        } catch (e: Exception) {
            BackupCheck.Damaged
        } catch (e: StackOverflowError) {
            BackupCheck.Damaged
        }
    }

    fun check(input: InputStream, requireMarker: Boolean): BackupCheck {
        val bytes = try {
            readBounded(input) ?: return BackupCheck.TooLarge
        } catch (e: Exception) {
            return BackupCheck.Damaged
        }
        return try {
            checkBytes(bytes, requireMarker)
        } catch (e: Exception) {
            BackupCheck.Damaged
        } catch (e: StackOverflowError) {
            BackupCheck.Damaged
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun checkBytes(bytes: ByteArray, requireMarker: Boolean): BackupCheck {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        while (start < bytes.size && bytes[start].toInt().toChar().isWhitespace()) start++
        if (start >= bytes.size) return BackupCheck.Damaged // empty
        if (bytes[start] != '{'.code.toByte()) return BackupCheck.NotABackup

        val header = json.decodeFromStream(Header.serializer(), ByteArrayInputStream(bytes))
        if (header.format != null && header.format != HouseholdBackupFile.FORMAT) return BackupCheck.NotABackup
        if (requireMarker && header.format == null) return BackupCheck.NotABackup
        val version = header.version ?: return if (requireMarker) BackupCheck.Damaged else BackupCheck.NotABackup
        if (version > HouseholdBackupFile.VERSION) return BackupCheck.FromNewerVersion(version)
        if (version < 1) return BackupCheck.Damaged

        val raw = json.decodeFromStream(HouseholdBackupFile.serializer(), ByteArrayInputStream(bytes))
        if (raw.favorites.size > BackupLimits.MAX_FAVORITES ||
            raw.hiddenCategories.size > BackupLimits.MAX_HIDDEN_CATEGORIES ||
            raw.hiddenChannels.size > BackupLimits.MAX_HIDDEN_CHANNELS ||
            raw.recents.size > BackupLimits.MAX_RECENTS ||
            raw.progress.size > BackupLimits.MAX_PROGRESS
        ) {
            return BackupCheck.TooLarge
        }
        val clean = sanitize(raw)
        return BackupCheck.Ok(CheckedBackup(clean, previewOf(clean, current = null)))
    }

    /** Reads at most [BackupLimits.MAX_BYTES]; null when there is more. */
    private fun readBounded(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > BackupLimits.MAX_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Drops rows with an unknown type or a bad id, and puts every setting in range. */
    internal fun sanitize(raw: HouseholdBackupFile): HouseholdBackupFile {
        fun idOk(id: String) = id.isNotEmpty() && id.length <= BackupLimits.MAX_ID_LENGTH && id.none { it.isISOControl() }
        fun typeOk(t: String) = StreamType.entries.any { it.name == t }
        fun rowOk(id: String, type: String) = idOk(id) && typeOk(type)
        return raw.copy(
            favorites = raw.favorites.filter { rowOk(it.id, it.type) }
                .map { it.copy(addedAtMillis = it.addedAtMillis.coerceAtLeast(0)) },
            hiddenCategories = raw.hiddenCategories.filter { rowOk(it.id, it.type) },
            hiddenChannels = raw.hiddenChannels.filter { rowOk(it.id, it.type) },
            recents = raw.recents.filter { idOk(it.id) }.map { it.copy(watchedAtMillis = it.watchedAtMillis.coerceAtLeast(0)) },
            progress = raw.progress.filter { rowOk(it.id, it.type) && (it.seriesId == null || idOk(it.seriesId)) }
                .map {
                    it.copy(
                        positionMillis = it.positionMillis.coerceAtLeast(0),
                        durationMillis = it.durationMillis.coerceAtLeast(0),
                        updatedAtMillis = it.updatedAtMillis.coerceAtLeast(0),
                    )
                },
            settings = SettingsRepository.sanitizeHouseholdSettings(raw.settings),
            appVersion = raw.appVersion?.filter { !it.isISOControl() }?.take(40),
            createdAtMillis = raw.createdAtMillis?.takeIf { it > 0 },
        )
    }

    /** The preview of an already clean [file]; [current] (this TV's settings) fills in what would change. */
    internal fun previewOf(file: HouseholdBackupFile, current: HouseholdSettings?): BackupPreview {
        val byType = StreamType.entries.associateWith { type -> file.favorites.count { it.type == type.name } }
        return BackupPreview(
            createdAtMillis = file.createdAtMillis,
            appVersion = file.appVersion,
            favoritesByType = byType,
            hiddenCategories = file.hiddenCategories.size,
            hiddenChannels = file.hiddenChannels.size,
            settingsInFile = BackupSetting.entries.count { it.read(file.settings) != null },
            settingsChanged = if (current == null) emptyList() else BackupSetting.entries.filter {
                // #216: effective values on both sides, so unset and "set to the default" are the same.
                val fileEffective = SettingsRepository.withDefaults(file.settings)
                it.read(fileEffective) != it.read(SettingsRepository.withDefaults(current))
            },
        )
    }
}
