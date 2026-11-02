package com.rysal.happytv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rysal.happytv.data.entity.WatchProgress
import kotlinx.coroutines.flow.Flow

/** A Continue watching card: the poster columns plus how far along it is. */
data class ContinueRow(
    /** The movie id, or the **show** id for an episode. */
    val id: String,
    val name: String,
    val year: String?,
    val posterUrl: String?,
    val positionMillis: Long,
    val durationMillis: Long,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val updatedUtcMillis: Long,
)

/** Watch progress reads and writes (#78). Writes are single-row upserts and deletes. */
@Dao
abstract class WatchProgressDao {

    /** #19: every progress row, once, for the household backup file. */
    @Query("SELECT * FROM watch_progress")
    abstract suspend fun getAll(): List<WatchProgress>

    /** #19: restore; an existing row wins. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(rows: List<WatchProgress>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(progress: WatchProgress)

    @Query("DELETE FROM watch_progress WHERE streamType = 'VOD' AND streamId = :movieId")
    abstract suspend fun deleteMovie(movieId: String)

    @Query("DELETE FROM watch_progress WHERE streamType = 'SERIES' AND streamId = :episodeId")
    abstract suspend fun deleteEpisode(episodeId: String)

    /** "Remove from Continue watching" on a show: every episode of it. */
    @Query("DELETE FROM watch_progress WHERE streamType = 'SERIES' AND seriesId = :seriesId")
    abstract suspend fun deleteSeries(seriesId: String)

    @Query("SELECT * FROM watch_progress WHERE streamType = 'SERIES' AND streamId = :episodeId")
    abstract suspend fun getEpisode(episodeId: String): WatchProgress?

    @Query("SELECT * FROM watch_progress WHERE streamType = 'VOD' AND streamId = :movieId AND finishedAt IS NULL")
    abstract fun observeMovie(movieId: String): Flow<WatchProgress?>

    @Query("SELECT * FROM watch_progress WHERE streamType = 'VOD' AND streamId = :movieId")
    abstract suspend fun getMovie(movieId: String): WatchProgress?

    /** The show's most recently watched unfinished episode (or its up-next one), or null. */
    @Query(
        "SELECT * FROM watch_progress WHERE streamType = 'SERIES' AND seriesId = :seriesId " +
            "AND finishedAt IS NULL ORDER BY updatedUtcMillis DESC, streamId LIMIT 1",
    )
    abstract fun observeLatestEpisode(seriesId: String): Flow<WatchProgress?>

    /** Every episode row of a show, finished ones included (their [WatchProgress.finishedAt] marks them watched), for #74. */
    @Query("SELECT * FROM watch_progress WHERE streamType = 'SERIES' AND seriesId = :seriesId")
    abstract fun observeEpisodes(seriesId: String): Flow<List<WatchProgress>>

    /** Movies in progress (not finished), most recent first. A movie gone from the catalog drops out. */
    @Query(
        "SELECT v.id AS id, v.name AS name, NULL AS year, v.streamIcon AS posterUrl, " +
            "w.positionMillis AS positionMillis, w.durationMillis AS durationMillis, " +
            "NULL AS seasonNumber, NULL AS episodeNumber, w.updatedUtcMillis AS updatedUtcMillis " +
            "FROM watch_progress w JOIN vod_streams v ON v.id = w.streamId " +
            "WHERE w.streamType = 'VOD' AND w.finishedAt IS NULL ORDER BY w.updatedUtcMillis DESC LIMIT :limit",
    )
    abstract fun observeContinueMovies(limit: Int): Flow<List<ContinueRow>>

    /**
     * Shows in progress, most recent first, one row per show: its latest episode. The newest
     * episode row per show wins (ties: the lower episode id).
     */
    @Query(
        "SELECT s.id AS id, s.name AS name, " + SERIES_YEAR_SQL_FOR_S + " AS year, s.cover AS posterUrl, " +
            "w.positionMillis AS positionMillis, w.durationMillis AS durationMillis, " +
            "w.seasonNumber AS seasonNumber, w.episodeNumber AS episodeNumber, " +
            "w.updatedUtcMillis AS updatedUtcMillis " +
            "FROM watch_progress w JOIN series_streams s ON s.id = w.seriesId " +
            "WHERE w.streamType = 'SERIES' AND w.finishedAt IS NULL AND w.streamId = (" +
            "SELECT w2.streamId FROM watch_progress w2 WHERE w2.streamType = 'SERIES' " +
            "AND w2.finishedAt IS NULL AND w2.seriesId = w.seriesId ORDER BY w2.updatedUtcMillis DESC, w2.streamId LIMIT 1) " +
            "ORDER BY w.updatedUtcMillis DESC LIMIT :limit",
    )
    abstract fun observeContinueShows(limit: Int): Flow<List<ContinueRow>>
}

/** [SERIES_YEAR_SQL] for the `s.` alias used by the join above. */
private const val SERIES_YEAR_SQL_FOR_S =
    "CASE WHEN length(s.releaseDate) >= 4 AND substr(s.releaseDate, 1, 4) GLOB '[0-9][0-9][0-9][0-9]' " +
        "THEN substr(s.releaseDate, 1, 4) ELSE NULL END"
