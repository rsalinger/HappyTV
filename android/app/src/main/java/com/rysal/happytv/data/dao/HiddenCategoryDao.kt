package com.rysal.happytv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rysal.happytv.data.entity.HiddenCategory
import com.rysal.happytv.data.entity.StreamType
import kotlinx.coroutines.flow.Flow

/**
 * Durable hidden-category record — see [HiddenCategory] for why it is a table
 * of its own instead of only the [com.rysal.happytv.data.entity.Category.isHidden]
 * flag.
 *
 * [toggle] mirrors the flag onto the `categories` row so the denormalized copy
 * stays true; if the category row is absent (hidden before first refresh, or
 * the category disappeared upstream) the durable row still records the wish and
 * CategoryDao.replaceAll re-applies it on the next refresh.
 */
@Dao
abstract class HiddenCategoryDao {

    /** #19: every hidden category, once, for the household backup file. */
    @Query("SELECT * FROM hidden_categories")
    abstract suspend fun getAll(): List<HiddenCategory>

    /** #19: restore; the catalog refresh re-applies the hidden flags from these rows. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(rows: List<HiddenCategory>)

    /** Hidden ids for one catalog — feeds the "show hidden categories" management list. */
    @Query("SELECT * FROM hidden_categories WHERE categoryType = :categoryType ORDER BY categoryId")
    abstract fun observe(categoryType: StreamType): Flow<List<HiddenCategory>>

    @Query("SELECT * FROM hidden_categories ORDER BY categoryType, categoryId")
    abstract fun observeAll(): Flow<List<HiddenCategory>>

    @Query("SELECT EXISTS(SELECT 1 FROM hidden_categories WHERE categoryId = :categoryId AND categoryType = :categoryType)")
    abstract suspend fun isHidden(categoryId: String, categoryType: StreamType): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(row: HiddenCategory)

    @Query("DELETE FROM hidden_categories WHERE categoryId = :categoryId AND categoryType = :categoryType")
    abstract suspend fun remove(categoryId: String, categoryType: StreamType)

    /** Hide ↔ unhide, keeping the `categories.isHidden` copy in sync. */
    @Transaction
    open suspend fun toggle(categoryId: String, categoryType: StreamType) {
        if (isHidden(categoryId, categoryType)) {
            remove(categoryId, categoryType)
            setCategoryFlag(categoryId, categoryType, hidden = false)
        } else {
            insert(HiddenCategory(categoryId = categoryId, categoryType = categoryType))
            setCategoryFlag(categoryId, categoryType, hidden = true)
        }
    }

    @Query("UPDATE categories SET isHidden = :hidden WHERE id = :categoryId AND type = :categoryType")
    abstract suspend fun setCategoryFlag(categoryId: String, categoryType: StreamType, hidden: Boolean)

    @Query("DELETE FROM hidden_categories")
    abstract suspend fun clear()
}
