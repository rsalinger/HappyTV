package com.rysal.happytv.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A place a backup can go: this TV's Download folder, or the `HappyTV` folder on a mounted drive. */
data class BackupLocation(
    /** Shown on the row: "This TV" or the drive's name. */
    val label: String,
    /** The `HappyTV` folder itself (it may not exist yet). */
    val folder: File,
    val isDrive: Boolean,
    /** Shown under [label], e.g. "Download/HappyTV". */
    val pathLabel: String,
)

/** Where backups live and whether the app may touch them (#210). The app only uses its own `HappyTV` folders. */
interface BackupStorage {
    /** True when the file permission the Android version needs is granted. */
    fun hasAccess(): Boolean

    /** This TV first, then each mounted removable drive. Main-safe. */
    suspend fun locations(): List<BackupLocation>

    /** The `*.json` files directly in [folder], newest first, at most [MAX_LISTED]. Empty when the folder is missing. Main-safe. */
    suspend fun listBackupFiles(folder: File): List<File>

    companion object {
        const val FOLDER_NAME = "HappyTV"
        const val MAX_LISTED = 30
    }
}

/** The real thing. [sdkInt] is injectable only so the version rules can be tested. */
class AndroidBackupStorage(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BackupStorage {

    override fun hasAccess(): Boolean = try {
        if (sdkInt >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    } catch (e: RuntimeException) {
        false
    }

    override suspend fun locations(): List<BackupLocation> = withContext(io) {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val tv = BackupLocation(
            label = "This TV",
            folder = File(downloads, BackupStorage.FOLDER_NAME),
            isDrive = false,
            pathLabel = "Download/${BackupStorage.FOLDER_NAME}",
        )
        listOf(tv) + drives()
    }

    private fun drives(): List<BackupLocation> {
        if (sdkInt < Build.VERSION_CODES.N) return emptyList()
        return try {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyList()
            manager.storageVolumes
                .filter { it.isRemovable && !it.isPrimary && it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { volume ->
                    val root = rootOf(volume) ?: return@mapNotNull null
                    BackupLocation(
                        label = volume.getDescription(context)?.takeIf { it.isNotBlank() } ?: "USB drive",
                        folder = File(root, BackupStorage.FOLDER_NAME),
                        isDrive = true,
                        pathLabel = "${BackupStorage.FOLDER_NAME} folder on the drive",
                    )
                }
        } catch (e: RuntimeException) {
            emptyList()
        }
    }

    private fun rootOf(volume: StorageVolume): File? =
        if (sdkInt >= Build.VERSION_CODES.R) {
            volume.directory
        } else {
            // Before API 30 the public API has no path; the hidden getter has worked on every release.
            try {
                (StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String)?.let(::File)
            } catch (e: ReflectiveOperationException) {
                null
            } catch (e: RuntimeException) {
                null
            }
        }

    override suspend fun listBackupFiles(folder: File): List<File> = withContext(io) { listJsonFiles(folder) }
}

/** The shared listing rule: only `*.json`, only plain files, newest first, capped. Never throws. */
internal fun listJsonFiles(folder: File, max: Int = BackupStorage.MAX_LISTED): List<File> = try {
    folder.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        ?.take(max)
        .orEmpty()
} catch (e: SecurityException) {
    emptyList()
}
