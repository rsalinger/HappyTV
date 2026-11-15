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

    /** #221: one catalog's favorites in the user's order, top first (ties newest first). */
    @Query("SELECT * FROM favorites WHERE streamType = :streamType ORDER BY position ASC, addedAt DESC, streamId ASC")
    abstract fun observeOrdered(streamType: StreamType): Flow<List<Favorite>>

    @Query("SELECT * FROM favorites WHERE streamType = :streamType ORDER BY position ASC, addedAt DESC, streamId ASC")
    abstract suspend fun getOrdered(streamType: StreamType): List<Favorite>

    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM favorites WHERE streamType = :streamType")
    abstract suspend fun nextPosition(streamType: StreamType): Int

    @Query("UPDATE favorites SET position = :position WHERE streamId = :streamId AND streamType = :streamType")
    abstract suspend fun setPosition(streamId: String, streamType: StreamType, position: Int)

    /**
     * #221: puts [shownIds], the favorites a screen shows, in the order given. They keep the
     * places they already hold in the list (a favorite whose channel is hidden stays where it
     * was), and only rows whose position changes are written, in one small transaction.
     * Positions are renumbered 1..N, so they stay dense.
     */
    @Transaction
    open suspend fun reorder(streamType: StreamType, shownIds: List<String>) {
        val current = getOrdered(streamType)
        val currentIds = current.mapTo(HashSet()) { it.streamId }
        val wanted = shownIds.filter { it in currentIds }
        val shown = wanted.toHashSet()
        val slots = current.indices.filter { current[it].streamId in shown }
        // A stale list (a favorite was removed meanwhile): leave the order alone.
        if (wanted.size != slots.size) return
        val result = current.map { it.streamId }.toMutableList()
        slots.forEachIndexed { i, slot -> result[slot] = wanted[i] }
        val byId = current.associateBy { it.streamId }
        result.forEachIndexed { i, id ->
            if (byId.getValue(id).position != i + 1) setPosition(id, streamType, i + 1)
        }
    }

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
            insert(
                Favorite(
                    streamId = streamId,
                    streamType = streamType,
                    addedAt = Instant.now(),
                    position = nextPosition(streamType),
                ),
            )
        }
    }

    @Query("DELETE FROM favorites")
    abstract suspend fun clear()
}
