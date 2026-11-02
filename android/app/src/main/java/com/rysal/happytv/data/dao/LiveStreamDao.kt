package com.rysal.happytv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rysal.happytv.data.entity.LiveStream
import kotlinx.coroutines.flow.Flow

/** Live-channel catalog reads + refresh writes. */
@Dao
abstract class LiveStreamDao {

    /** Channel list for one sidebar category, in `num` display order. */
    @Query("SELECT * FROM live_streams WHERE categoryId = :categoryId ORDER BY num, name COLLATE NOCASE")
    abstract fun observeByCategory(categoryId: String): Flow<List<LiveStream>>

    /** Substring match on channel name, case-insensitive (SQLite LIKE semantics). */
    @Query("SELECT * FROM live_streams WHERE name LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE")
    abstract fun search(query: String): Flow<List<LiveStream>>

    /**
     * The Search overlay's channel index (design spec §"Screens: search").
     *
     * Three columns per row — Search matches on names, so it must not pull
     * whole entities into memory on every keystroke.
     */
    @Query("SELECT id, name, num FROM live_streams ORDER BY num, name COLLATE NOCASE")
    abstract fun observeSearchRows(): Flow<List<SearchChannelRow>>

    /** Logo URLs (`stream_icon`) for the few channel ids a search draws (#139). */
    @Query("SELECT id, streamIcon AS logoUrl FROM live_streams WHERE id IN (:ids)")
    abstract suspend fun searchLogoUrls(ids: List<String>): List<SearchChannelLogoRow>

    /** Resolve a stored id back to its row (Continue-watching, deep links). */
    @Query("SELECT * FROM live_streams WHERE id = :id")
    abstract suspend fun getById(id: String): LiveStream?

    /** Stream id → count of channels per categoryId; used for the sidebar counts. */
    @Query("SELECT categoryId, COUNT(*) AS c FROM live_streams GROUP BY categoryId")
    abstract fun observeCounts(): Flow<List<CategoryCount>>

    /**
     * Stream rows in the order given by [ids] — used by Favorites and Recently
     * Watched rails, which carry only ids + a sort key. The caller keeps the
     * order: WHERE … IN (…) doesn't preserve "by index" without a CASE, but
     * outer `combine` keeps the favorites/recent list (most-recent first).
     */
    @Query("SELECT * FROM live_streams WHERE id IN (:ids)")
    abstract fun observeByIds(ids: List<String>): Flow<List<LiveStream>>

    /** Whole catalog in num+name order. */
    @Query("SELECT * FROM live_streams ORDER BY num, name COLLATE NOCASE")
    abstract fun observeAll(): Flow<List<LiveStream>>

    /**
     * The channel pane's paged window (F-46): the whole catalog in display
     * order, capped at [limit] rows. A real panel holds 39 361 live channels
     * and hydrating every one of them to fill a six-row viewport is what made
     * first paint take seconds; the ViewModel widens [limit] a page at a time
     * via `loadMoreChannels()` as the pane is browsed.
     */
    @Query("SELECT * FROM live_streams ORDER BY num, name COLLATE NOCASE LIMIT :limit")
    abstract fun observeAllWindow(limit: Int): Flow<List<LiveStream>>

    /** [observeAllWindow] narrowed to one category — the pane's window for a rail row. */
    @Query(
        "SELECT * FROM live_streams WHERE categoryId = :categoryId " +
            "ORDER BY num, name COLLATE NOCASE LIMIT :limit",
    )
    abstract fun observeByCategoryWindow(categoryId: String, limit: Int): Flow<List<LiveStream>>

    /**
     * How many channels of [categoryId] sort before one with [num] and [name] in the
     * pane's order (num, then name; a missing num sorts first). That channel's zero-based
     * row in its category, for sizing the window that has to contain it (#138).
     */
    @Query(
        "SELECT COUNT(*) FROM live_streams WHERE categoryId = :categoryId AND (" +
            "COALESCE(num, -2147483648) < COALESCE(:num, -2147483648) OR " +
            "(COALESCE(num, -2147483648) = COALESCE(:num, -2147483648) AND name COLLATE NOCASE < :name COLLATE NOCASE))",
    )
    abstract suspend fun rowsBefore(categoryId: String, num: Int?, name: String): Int

    @Query("SELECT COUNT(*) FROM live_streams")
    abstract suspend fun count(): Int

    /**
     * #167: channels Live TV lists by default: not in a hidden category, not hidden themselves.
     * The dashboard tile uses this so it agrees with the Live TV header.
     */
    @Query(
        "SELECT COUNT(*) FROM live_streams WHERE " +
            "categoryId NOT IN (SELECT id FROM categories WHERE type = 'LIVE' AND isHidden = 1) " +
            "AND id NOT IN (SELECT streamId FROM hidden_channels WHERE streamType = 'LIVE')",
    )
    abstract suspend fun countVisible(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(streams: List<LiveStream>)

    @Query("DELETE FROM live_streams")
    abstract suspend fun deleteAll()

    /**
     * Refresh write: the full wire list replaces the whole table in one
     * transaction (Xtream has no incremental/delta endpoint, so anything not in
     * the response no longer exists upstream either).
     */
    @Transaction
    open suspend fun replaceAll(streams: List<LiveStream>) {
        deleteAll()
        insertAll(streams)
    }

    /** The whole table; tests only. The refresh reads by id (see [getByIds]). */
    @Query("SELECT * FROM live_streams")
    abstract suspend fun getAll(): List<LiveStream>

    @Query("SELECT * FROM live_streams WHERE id IN (:ids)")
    abstract suspend fun getByIds(ids: List<String>): List<LiveStream>

    /** Stored ids after [after] in id order: one page of the sync's removal scan (#130). */
    @Query("SELECT id FROM live_streams WHERE id > :after ORDER BY id LIMIT :limit")
    abstract suspend fun idsAfter(after: String, limit: Int): List<String>

    @Query("DELETE FROM live_streams WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<String>)

    /**
     * Refresh write (#43): upsert only the rows that differ from what is
     * stored and delete the ones the provider dropped. Unchanged rows are not
     * touched, so a steady-state refresh writes nothing and wakes no observer.
     * See [syncCatalogStream].
     */
    suspend fun syncAll(streams: List<LiveStream>): CatalogWrite = syncStream(streams.asSequence())
}

/**
 * A row in `SELECT categoryId, COUNT(*)`. Room maps the columns directly to
 * these two properties.
 */
data class CategoryCount(
    val categoryId: String,
    @androidx.room.ColumnInfo(name = "c") val count: Int,
)
