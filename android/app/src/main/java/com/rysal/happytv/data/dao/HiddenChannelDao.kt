package com.rysal.happytv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rysal.happytv.data.entity.HiddenChannel
import com.rysal.happytv.data.entity.StreamType
import kotlinx.coroutines.flow.Flow

/**
 * Channels the user hid with the long-press menu (design spec §"Live TV > Menus").
 *
 * Reads are a [Flow] so the Live TV panes drop a hidden channel from every list
 * the instant it is hidden, without a manual refresh. The row is never touched
 * by a catalog refresh, unlike a flag on the channel row itself.
 */
@Dao
abstract class HiddenChannelDao {

    /**
     * #123: Settings > Hidden items. Every hidden channel with its name and category name. A hidden
     * id whose channel is gone from the catalog keeps its row (name and category null), so the user
     * can still remove it. Sorted by name, gone rows last. Room runs this off the main thread.
     */
    @Query(
        "SELECT h.streamId AS streamId, s.name AS name, c.name AS categoryName " +
            "FROM hidden_channels h " +
            "LEFT JOIN live_streams s ON s.id = h.streamId " +
            "LEFT JOIN categories c ON c.id = s.categoryId AND c.type = :type " +
            "WHERE h.streamType = :type " +
            "ORDER BY (s.name IS NULL), s.name COLLATE NOCASE, h.streamId",
    )
    abstract fun observeListing(type: StreamType = StreamType.LIVE): Flow<List<HiddenChannelListing>>

    /** #19: every hidden channel, once, for the household backup file. */
    @Query("SELECT * FROM hidden_channels")
    abstract suspend fun getAll(): List<HiddenChannel>

    /** #19: restore; an existing row wins. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(rows: List<HiddenChannel>)

    /** Hidden ids for one catalog — the Live TV pane filter. */
    @Query("SELECT streamId FROM hidden_channels WHERE streamType = :type")
    abstract fun observeIds(type: StreamType): Flow<List<String>>

    /** Hidden rows for one catalog — "Show hidden channels" bookkeeping. */
    @Query("SELECT * FROM hidden_channels WHERE streamType = :type")
    abstract fun observe(type: StreamType): Flow<List<HiddenChannel>>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM hidden_channels " +
            "WHERE streamId = :streamId AND streamType = :type)",
    )
    abstract suspend fun isHidden(streamId: String, type: StreamType): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(row: HiddenChannel)

    @Query("DELETE FROM hidden_channels WHERE streamId = :streamId AND streamType = :type")
    abstract suspend fun delete(streamId: String, type: StreamType)

    /** Bulk reveal for one catalog ("Show hidden channels"). */
    @Query("DELETE FROM hidden_channels WHERE streamType = :type")
    abstract suspend fun revealAll(type: StreamType)

    @Query("DELETE FROM hidden_channels")
    abstract suspend fun clear()

    /** Hide if visible, reveal if hidden — the eye-slash toggle. */
    open suspend fun toggle(streamId: String, type: StreamType) {
        if (isHidden(streamId, type)) {
            delete(streamId, type)
        } else {
            insert(HiddenChannel(streamId = streamId, streamType = type))
        }
    }
}

/** One row of the Hidden items channels group; [name] is null when the channel left the catalog. */
data class HiddenChannelListing(
    val streamId: String,
    val name: String?,
    val categoryName: String?,
)
