package com.rysal.happytv.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import com.rysal.happytv.data.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * #19: full-backup agent. Everything the app stores is excluded by
 * `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` except
 * [HouseholdBackup.FILE_NAME]. This agent writes that file from the database
 * just before the default file backup runs, and removes it afterwards, so it
 * exists on disk only for the length of a backup (or after a restore, until
 * the next sign-in imports it). Nothing is restored over existing data here.
 */
class HappyTvBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        // The include rule names domain="file", which is filesDir.
        val backup = HouseholdBackup(Graph.database, Graph.settings, filesDir)
        try {
            runBlocking { withContext(Dispatchers.IO) { backup.writeForBackup() } }
        } catch (e: Exception) {
            // Better an empty backup than a failed one or a partial file.
            Log.w(TAG, "household backup not written: ${e::class.java.simpleName}")
            backup.clear()
        }
        try {
            super.onFullBackup(data)
        } finally {
            backup.clear()
        }
    }

    // Key/value backup is never used (fullBackupOnly="true").
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    private companion object {
        const val TAG = "HappyTvBackup"
    }
}
