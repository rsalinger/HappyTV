package com.rysal.happytv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.StreamType
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * User favorites, keyed by `(streamId, streamType)`.
 *
 * [isFavorite] takes the type as well as the id because stream ids are only
 * unique per catalog — live `101` and VOD `101` are unrelated rows.
 */
@Dao
abstract class FavoriteDao {

    /** #19: every favorite, once, for the household backup file. */
    @Query("SELECT * FROM favorites")
    abstract suspend fun getAll(): List<Favorite>

    /** #19: restore; an existing row wins. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(rows: List<Favorite>)

    /** One catalog's favorites, most recently added first (the "Favorites" tab source). */
    @Query("SELECT * FROM favorites WHERE streamType = :streamType ORDER BY addedAt DESC")
    abstract fun observeForType(streamType: StreamType): Flow<List<Favorite>>

    /** Everything the user favorited, across catalogs. */
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    abstract fun observeAll(): Flow<List<Favorite>>

    /** Emits `null` until the row exists, then its value — for star-icon state. */
    @Query("SELECT * FROM favorites WHERE streamId = :streamId AND streamType = :streamType")
    abstract fun observe(streamId: String, streamType: StreamType): Flow<Favorite?>

    /** `EXISTS` — cheap boolean for list rows that render a star per item. */
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE streamId = :streamId AND streamType = :streamType)")
    abstract suspend fun isFavorite(streamId: String, streamType: StreamType): Boolean

    @Query("DELETE FROM favorites WHERE streamId = :streamId AND streamType = :streamType")
    abstract suspend fun remove(streamId: String, streamType: StreamType)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(favorite: Favorite)

    /** Adds it if absent, removes it if present — the star-toggle one-liner. */
    @Transaction
    open suspend fun toggle(streamId: String, streamType: StreamType) {
        if (isFavorite(streamId, streamType)) {
            remove(streamId, streamType)
        } else {
            insert(Favorite(streamId = streamId, streamType = streamType, addedAt = Instant.now()))
        }
    }

    @Query("DELETE FROM favorites")
    abstract suspend fun clear()
}
