package com.rysal.happytv.data.entity

import androidx.room.Entity
import java.time.Instant

/**
 * A user favorite, keyed by `(streamId, streamType)` — ids are only unique per
 * catalog type, so the type is part of the primary key.
 *
 * [addedAt] is a [Instant] so it exercises the database's registered
 * `java.time.Instant` type converter (millisecond columns elsewhere stay Long).
 */
@Entity(tableName = "favorites", primaryKeys = ["streamId", "streamType"])
data class Favorite(
    /** Target id, stringified — a [LiveStream]/[VodStream]/[SeriesStream] id. */
    val streamId: String,
    val streamType: StreamType,
    /** When the user added it; defaults to "now" for one-liner inserts. */
    val addedAt: Instant = Instant.now(),
)
