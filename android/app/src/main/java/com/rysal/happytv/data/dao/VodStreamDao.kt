package com.rysal.happytv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rysal.happytv.data.entity.VodStream
import kotlinx.coroutines.flow.Flow

/** VOD catalog reads + refresh writes. See [LiveStreamDao] for the shared pattern. */
@Dao
abstract class VodStreamDao {

    @Query("SELECT * FROM vod_streams WHERE categoryId = :categoryId ORDER BY num, name COLLATE NOCASE")
    abstract fun observeByCategory(categoryId: String): Flow<List<VodStream>>

    /**
     * The poster grid's paged window (F-55): the whole catalog in display
     * order, capped at [limit] rows. Lightweight columns only — see
     * [PosterGridRow]. The ViewModel widens [limit] a page at a time as the
     * grid is browsed, so nothing beyond the first page is held in memory.
     */
    @Query(
        "SELECT id, name, NULL AS year, streamIcon AS posterUrl " +
            "FROM vod_streams ORDER BY num, name COLLATE NOCASE LIMIT :limit",
    )
    abstract fun observeGridWindow(limit: Int): Flow<List<PosterGridRow>>

    /** [observeGridWindow] narrowed to one category — a category poster grid. */
    @Query(
        "SELECT id, name, NULL AS year, streamIcon AS posterUrl " +
            "FROM vod_streams WHERE categoryId = :categoryId " +
            "ORDER BY num, name COLLATE NOCASE LIMIT :limit",
    )
    abstract fun observeGridWindowByCategory(
        categoryId: String,
        limit: Int,
    ): Flow<List<PosterGridRow>>

    /**
     * "Recently added": the [limit] newest movies, newest first. `index_vod_streams_added` (v5)
     * serves the `ORDER BY` straight off the index, so only [limit] rows are ever read. Before
     * the index this was an in-memory top-N, because the sort over 31 000 rows cost about half a
     * second on the TV. Ties go to the higher rowid, which is what the index yields for free.
     */
    @Query(
        "SELECT id, name, NULL AS year, streamIcon AS posterUrl FROM vod_streams " +
            "WHERE added IS NOT NULL ORDER BY added DESC, rowid DESC LIMIT :limit",
    )
    abstract fun observeRecentlyAdded(limit: Int): Flow<List<PosterGridRow>>

    /** True when at least one movie carries an `added` stamp, so "Recently added" has something to list. */
    @Query("SELECT EXISTS(SELECT 1 FROM vod_streams WHERE added IS NOT NULL)")
    abstract suspend fun hasAddedStamps(): Boolean

    /** Rows in the order given by [ids] — the Favorites poster grid. */
    @Query("SELECT * FROM vod_streams WHERE id IN (:ids)")
    abstract fun observeByIds(ids: List<String>): Flow<List<VodStream>>

    @Query("SELECT * FROM vod_streams WHERE name LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE")
    abstract fun search(query: String): Flow<List<VodStream>>

    /**
     * The Search overlay's movie index. No year: the Xtream list endpoint for
     * VOD carries none (the same gap that leaves movie details on
     * "Plot not available."), so the poster's year line stays empty.
     */
    @Query("SELECT id, name, NULL AS year FROM vod_streams ORDER BY num, name COLLATE NOCASE")
    abstract fun observeSearchRows(): Flow<List<SearchPosterRow>>

    /** Poster URLs (`stream_icon`) for the few movie ids a search draws (#67). */
    @Query("SELECT id, streamIcon AS posterUrl FROM vod_streams WHERE id IN (:ids)")
    abstract suspend fun searchPosterUrls(ids: List<String>): List<SearchPosterUrlRow>

    @Query("SELECT * FROM vod_streams WHERE id = :id")
    abstract suspend fun getById(id: String): VodStream?

    @Query("SELECT COUNT(*) FROM vod_streams")
    abstract suspend fun count(): Int

    /** Reactive total for the poster-grid header ("8,214 movies"). */
    @Query("SELECT COUNT(*) FROM vod_streams")
    abstract fun observeCount(): Flow<Int>

    /** `{categoryId → movie count}` for the category cells' "1,204 movies". */
    @Query("SELECT categoryId, COUNT(*) AS c FROM vod_streams GROUP BY categoryId")
    abstract fun observeCounts(): Flow<List<CategoryCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(streams: List<VodStream>)

    @Query("DELETE FROM vod_streams")
    abstract suspend fun deleteAll()

    /** Full-list replace, one transaction — see [LiveStreamDao.replaceAll]. */
    @Transaction
    open suspend fun replaceAll(streams: List<VodStream>) {
        deleteAll()
        insertAll(streams)
    }

    /** The whole table; tests only. The refresh reads by id (see [getByIds]). */
    @Query("SELECT * FROM vod_streams")
    abstract suspend fun getAll(): List<VodStream>

    @Query("SELECT * FROM vod_streams WHERE id IN (:ids)")
    abstract suspend fun getByIds(ids: List<String>): List<VodStream>

    /** Stored ids after [after] in id order: one page of the sync's removal scan (#130). */
    @Query("SELECT id FROM vod_streams WHERE id > :after ORDER BY id LIMIT :limit")
    abstract suspend fun idsAfter(after: String, limit: Int): List<String>

    @Query("DELETE FROM vod_streams WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<String>)

    /**
     * Refresh write (#43): upsert only the rows that differ from what is
     * stored and delete the ones the provider dropped. Unchanged rows are not
     * touched, so a steady-state refresh writes nothing and wakes no observer.
     * See [syncCatalogStream].
     */
    suspend fun syncAll(streams: List<VodStream>): CatalogWrite = syncStream(streams.asSequence())
}
