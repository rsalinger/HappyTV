package com.rysal.happytv.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * "Resume where I was watching" — the last stream touched and when, for the
 * Continue-watching rail. Upsert on [streamId], most recent first.
 *
 * Deliberately minimal (id + timestamp) per v1 scope: the UI resolves the row
 * against the live catalog by id. If a future screen needs the stream type too,
 * add it in a v2 schema migration rather than inlining it here.
 */
@Entity(tableName = "recent_channels")
data class RecentChannel(
    /** Stream id, stringified (v1 tracks live channels). */
    @PrimaryKey val streamId: String,
    val lastWatchedUtcMillis: Long,
)
