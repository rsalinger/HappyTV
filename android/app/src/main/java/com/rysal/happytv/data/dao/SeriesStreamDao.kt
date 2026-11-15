package com.rysal.happytv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rysal.happytv.data.entity.SeriesStream
import kotlinx.coroutines.flow.Flow

/**
 * The show year the grids and Search read: the first four digits of the
 * panel's raw `releaseDate` ("2023-10-01" and "2023" both read "2023"),
 * null when that is not a year at all. Shared by [SeriesStreamDao.observeSearchRows]
 * and the poster-grid window queries.
 */
private const val SERIES_YEAR_SQL =
    "CASE WHEN length(releaseDate) >= 4 AND substr(releaseDate, 1, 4) GLOB '[0-9][0-9][0-9][0-9]' " +
        "THEN substr(releaseDate, 1, 4) ELSE NULL END"

/** Series catalog reads + refresh writes. See [LiveStreamDao] for the shared pattern. */
@Dao
abstract class SeriesStreamDao {

    @Query("SELECT * FROM series_streams WHERE categoryId = :categoryId ORDER BY num, name COLLATE NOCASE")
    abstract fun observeByCategory(categoryId: String): Flow<List<SeriesStream>>

    /**
     * The poster grid's paged window (F-55): the whole catalog in display
     * order, capped at [limit] rows. Lightweight columns only — see
     * [PosterGridRow]. The ViewModel widens [limit] a page at a time as the
     * grid is browsed, so nothing beyond the first page is held in memory.
     */
    @Query(
        "SELECT id, name, " + SERIES_YEAR_SQL + " AS year, cover AS posterUrl " +
            "FROM series_streams ORDER BY num, name COLLATE NOCASE LIMIT :limit",
    )
    abstract fun observeGridWindow(limit: Int): Flow<List<PosterGridRow>>

    /** [observeGridWindow] narrowed to one category — a category poster grid. */
    @Query(
        "SELECT id, name, " + SERIES_YEAR_SQL + " AS year, cover AS posterUrl " +
            "FROM series_streams WHERE categoryId = :categoryId " +
            "ORDER BY num, name COLLATE NOCASE LIMIT :limit",
    )
    abstract fun observeGridWindowByCategory(
        categoryId: String,
        limit: Int,
    ): Flow<List<PosterGridRow>>

    /** "Recently added" for shows: the [limit] most recently modified, via `index_series_streams_lastModified`. */
    @Query(
        "SELECT id, name, " + SERIES_YEAR_SQL + " AS year, cover AS posterUrl FROM series_streams " +
            "WHERE lastModified IS NOT NULL ORDER BY lastModified DESC, rowid DESC LIMIT :limit",
    )
    abstract fun observeRecentlyAdded(limit: Int): Flow<List<PosterGridRow>>

    /** True when at least one show carries a `lastModified` stamp. */
    @Query("SELECT EXISTS(SELECT 1 FROM series_streams WHERE lastModified IS NOT NULL)")
    abstract suspend fun hasModifiedStamps(): Boolean

    /**
     * #232: the Favorites poster grid, in the user's order: `favorites.position` (1 = top, new ones
     * last), ties (position 0 on older rows) newest `addedAt` first, then id. Favorites whose title
     * is no longer in the catalog are skipped; the join cannot repeat a row (both keys are unique).
     */
    @Query(
        "SELECT s.* FROM series_streams s JOIN favorites f ON f.streamId = s.id AND f.streamType = 'SERIES' " +
            "ORDER BY f.position ASC, f.addedAt DESC, s.id ASC",
    )
    abstract fun observeFavorites(): Flow<List<SeriesStream>>

    @Query("SELECT * FROM series_streams WHERE name LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE")
    abstract fun search(query: String): Flow<List<SeriesStream>>

    /**
     * The Search overlay's show index: [SERIES_YEAR_SQL] for the year line. Unordered on purpose (#241):
     * Search sorts only its matches, see `SearchOrder`.
     */
    @Query(
        "SELECT id, name, " + SERIES_YEAR_SQL + " AS year, num " +
            "FROM series_streams",
    )
    abstract fun observeSearchRows(): Flow<List<SearchPosterRow>>

    /** Poster URLs (`cover`) for the few show ids a search draws (#67). */
    @Query("SELECT id, cover AS posterUrl FROM series_streams WHERE id IN (:ids)")
    abstract suspend fun searchPosterUrls(ids: List<String>): List<SearchPosterUrlRow>

    @Query("SELECT * FROM series_streams WHERE id = :id")
    abstract suspend fun getById(id: String): SeriesStream?

    @Query("SELECT COUNT(*) FROM series_streams")
    abstract suspend fun count(): Int

    /** Reactive total for the poster-grid header ("1,186 shows"). */
    @Query("SELECT COUNT(*) FROM series_streams")
    abstract fun observeCount(): Flow<Int>

    /** `{categoryId → show count}` for the category cells' "214 shows". */
    @Query("SELECT categoryId, COUNT(*) AS c FROM series_streams GROUP BY categoryId")
    abstract fun observeCounts(): Flow<List<CategoryCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(streams: List<SeriesStream>)

    @Query("DELETE FROM series_streams")
    abstract suspend fun deleteAll()

    /** Full-list replace, one transaction — see [LiveStreamDao.replaceAll]. */
    @Transaction
    open suspend fun replaceAll(streams: List<SeriesStream>) {
        deleteAll()
        insertAll(streams)
    }

    /** The whole table; tests only. The refresh reads by id (see [getByIds]). */
    @Query("SELECT * FROM series_streams")
    abstract suspend fun getAll(): List<SeriesStream>

    @Query("SELECT * FROM series_streams WHERE id IN (:ids)")
    abstract suspend fun getByIds(ids: List<String>): List<SeriesStream>

    /** Stored ids after [after] in id order: one page of the sync's removal scan (#130). */
    @Query("SELECT id FROM series_streams WHERE id > :after ORDER BY id LIMIT :limit")
    abstract suspend fun idsAfter(after: String, limit: Int): List<String>

    @Query("DELETE FROM series_streams WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<String>)

    /**
     * Refresh write (#43): upsert only the rows that differ from what is
     * stored and delete the ones the provider dropped. Unchanged rows are not
     * touched, so a steady-state refresh writes nothing and wakes no observer.
     * See [syncCatalogStream].
     */
    suspend fun syncAll(streams: List<SeriesStream>): CatalogWrite = syncStream(streams.asSequence())
}
