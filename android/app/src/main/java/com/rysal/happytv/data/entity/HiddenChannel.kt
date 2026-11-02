package com.rysal.happytv.data.entity

import androidx.room.Entity

/**
 * Durable record of a user-hidden channel, keyed by `(streamId, streamType)`
 * (stream ids are only unique per catalog, exactly like [Favorite]).
 *
 * Why a separate table rather than a flag on [LiveStream]: `LiveStreamDao.replaceAll`
 * wipes and re-inserts the whole channel table on every refresh, which would
 * silently un-hide everything. This row is never touched by a refresh, so
 * "hidden" means hidden until the user says otherwise.
 *
 * (v1 only ever hides live channels, but the type column keeps the table honest
 * if Movies/Shows grow their own hide action later.)
 */
@Entity(tableName = "hidden_channels", primaryKeys = ["streamId", "streamType"])
data class HiddenChannel(
    /** [LiveStream.id], stringified. */
    val streamId: String,
    /** Which catalog this channel belongs to. */
    val streamType: StreamType = StreamType.LIVE,
)
