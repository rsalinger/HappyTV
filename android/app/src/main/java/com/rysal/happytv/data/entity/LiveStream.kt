package com.rysal.happytv.data.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A live (TV channel) stream from `get_live_streams`.
 *
 * Field list mirrors the canonical wire map in
 * Xtream Codes field conventions minus common client-side
 * denormalizations (`type_name`, `category_name`,
 * `series_no`, `live`, `user_id_referred`, rating_from_*).
 *
 * All ids are stringified: the wire sends `stream_id`/`category_id` as either
 * int or string depending on the panel. Optional wire values are nullable here —
 * never default them to `""`/0, callers must be able to tell "absent" from "set".
 */
@Entity(
    tableName = "live_streams",
    indices = [Index("categoryId"), Index("name"), Index("epgChannelId")],
)
@Immutable
data class LiveStream(
    /** `stream_id`, stringified — primary key and the input to the stream URL. */
    @PrimaryKey val id: String,
    /** `category_id`, stringified → [Category.id] of a `LIVE` row. */
    val categoryId: String,
    /** `num` — display sort order. Wire-optional, so nullable. */
    val num: Int? = null,
    /** Channel display name (required on the wire). */
    val name: String,
    /** `stream_icon` — channel logo URL, may be empty/absent. */
    val streamIcon: String? = null,
    /** `epg_channel_id` — cross-reference key into XMLTV `display-name`/`id`. */
    val epgChannelId: String? = null,
    /** `tv_archive` (wire 0|1) — true = catchup/timeshift available. */
    val tvArchive: Boolean = false,
    /** `tv_archive_duration` — days of catchup, only meaningful when [tvArchive]. */
    val tvArchiveDuration: Int? = null,
    /** `container_extension` — usually empty for live; file format for direct files. */
    val containerExtension: String? = null,
    /** `added` — unix seconds the channel was added to the panel (wire is a string). */
    val added: Long? = null,
    /** `custom_sid` — STB integration id, usually empty. */
    val customSid: String? = null,
    /** `direct_source` — when set, the player uses this URL instead of building one. */
    val directSource: String? = null,
)
