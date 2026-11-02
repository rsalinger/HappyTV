package com.rysal.happytv.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One XMLTV `<programme>` row for a channel.
 *
 * Times are stored as epoch **millis** (converted from XMLTV's
 * `20240101123000 +0000` stamps at parse time) so range queries are plain
 * integer comparisons; [com.rysal.happytv.data.Converters] still registers an
 * `Instant` converter for code that prefers `java.time`.
 */
@Entity(
    tableName = "epg_programs",
    indices = [Index(value = ["channelId", "startUtcMillis"])],
)
data class EpgProgram(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `channelId` = XMLTV programme `channel` = [LiveStream.epgChannelId]. */
    val channelId: String,
    val title: String,
    val description: String? = null,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
)
