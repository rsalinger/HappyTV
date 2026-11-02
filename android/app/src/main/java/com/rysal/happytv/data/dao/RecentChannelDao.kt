package com.rysal.happytv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rysal.happytv.data.entity.RecentChannel
import kotlinx.coroutines.flow.Flow

/**
 * Continue-watching rail: one row per stream, upserted on every play/stop with
 * the wall-clock time it was last watched (newest first).
 */
@Dao
abstract class RecentChannelDao {

    /** #19: every recent channel, once, for the household backup file. */
    @Query("SELECT * FROM recent_channels")
    abstract suspend fun getAll(): List<RecentChannel>

    /** #19: restore; an existing row wins. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(rows: List<RecentChannel>)

    /** Newest first — the UI takes the head of this list for its rail. */
    @Query("SELECT * FROM recent_channels ORDER BY lastWatchedUtcMillis DESC")
    abstract fun recent(): Flow<List<RecentChannel>>

    @Query("SELECT * FROM recent_channels WHERE streamId = :streamId")
    abstract suspend fun get(streamId: String): RecentChannel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(channel: RecentChannel)

    /** Record "user watched this at [lastWatchedUtcMillis]" (upsert on streamId). */
    @Transaction
    open suspend fun recordWatch(streamId: String, lastWatchedUtcMillis: Long) {
        insert(RecentChannel(streamId = streamId, lastWatchedUtcMillis = lastWatchedUtcMillis))
    }

    /** Long-press "Remove from recently watched" (F-53): drop one row, for good. */
    @Query("DELETE FROM recent_channels WHERE streamId = :streamId")
    abstract suspend fun remove(streamId: String)

    /** Housekeeping: drop entries older than the keep window. */
    @Query("DELETE FROM recent_channels WHERE lastWatchedUtcMillis < :cutoffUtcMillis")
    abstract suspend fun pruneBefore(cutoffUtcMillis: Long)

    @Query("DELETE FROM recent_channels")
    abstract suspend fun clear()
}
