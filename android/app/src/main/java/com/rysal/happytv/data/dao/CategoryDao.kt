package com.rysal.happytv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.HiddenCategory
import com.rysal.happytv.data.entity.StreamType
import kotlinx.coroutines.flow.Flow

/**
 * Catalog categories.
 *
 * [Category.isHidden] is a denormalized copy of the durable
 * [HiddenCategory] row, so `observeVisible()` is a cheap `WHERE isHidden = 0`
 * with no join. Both [toggleHidden] and
 * [com.rysal.happytv.data.dao.HiddenCategoryDao.toggle] keep the two tables in
 * sync, and [replaceAll] re-applies hidden flags after every bulk replace
 * (bulk replace deletes rows, which would otherwise wipe the flag).
 */
@Dao
abstract class CategoryDao {

    /** Browsable list for one catalog — the sidebar source of truth. */
    @Query("SELECT * FROM categories WHERE type = :type AND isHidden = 0 ORDER BY name COLLATE NOCASE")
    abstract fun observeVisible(type: StreamType): Flow<List<Category>>

    /** Visible categories across all three catalogs. */
    @Query("SELECT * FROM categories WHERE isHidden = 0 ORDER BY type, name COLLATE NOCASE")
    abstract fun observeVisible(): Flow<List<Category>>

    /** Includes hidden rows — settings/management screens only. */
    @Query("SELECT * FROM categories WHERE type = :type ORDER BY name COLLATE NOCASE")
    abstract fun observeAll(type: StreamType): Flow<List<Category>>

    /**
     * Every hidden category across all three catalogs — the Settings
     * "Hidden categories" management list (F-44). Each row is one the user
     * can bring back with an unhide action.
     */
    @Query("SELECT * FROM categories WHERE isHidden = 1 ORDER BY type, name COLLATE NOCASE")
    abstract fun observeHidden(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :id AND type = :type")
    abstract suspend fun get(id: String, type: StreamType): Category?

    @Query("SELECT isHidden FROM categories WHERE id = :id AND type = :type")
    abstract suspend fun isHidden(id: String, type: StreamType): Boolean?

    @Query("UPDATE categories SET isHidden = :hidden WHERE id = :id AND type = :type")
    abstract suspend fun setHidden(id: String, type: StreamType, hidden: Boolean)

    /**
     * Flips [Category.isHidden] and mirrors the durable [HiddenCategory] row.
     * Unknown `(id, type)` is a no-op rather than an error.
     */
    @Transaction
    open suspend fun toggleHidden(id: String, type: StreamType) {
        val nowHidden = !(isHidden(id, type) ?: return)
        setHidden(id, type, nowHidden)
        if (nowHidden) {
            insertHidden(HiddenCategory(categoryId = id, categoryType = type))
        } else {
            deleteHidden(id, type)
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertHidden(row: HiddenCategory)

    @Query("DELETE FROM hidden_categories WHERE categoryId = :categoryId AND categoryType = :categoryType")
    abstract suspend fun deleteHidden(categoryId: String, categoryType: StreamType)

    /**
     * Bulk replace for one catalog: wipe that catalog's rows, insert the fresh
     * wire list, then re-apply hidden flags from [HiddenCategory] so a refresh
     * never un-hides a category the user hid.
     *
     * Runs in one transaction — readers see either the old list or the new one.
     */
    @Transaction
    open suspend fun replaceAll(type: StreamType, categories: List<Category>) {
        deleteByType(type)
        insertAll(categories)
        reapplyHidden(type)
    }

    @Query("SELECT * FROM categories WHERE type = :type")
    abstract suspend fun getByType(type: StreamType): List<Category>

    /**
     * Refresh write (#43): [replaceAll], but a list identical to the stored
     * one (ignoring the user's hidden flags) is not written, so a steady-state
     * refresh does not wake the category observers.
     *
     * @return true when the table was rewritten.
     */
    @Transaction
    open suspend fun syncAll(type: StreamType, categories: List<Category>): Boolean {
        val stored = getByType(type).map { it.copy(isHidden = false) }.toSet()
        val incoming = categories.map { it.copy(isHidden = false) }
        if (stored.size == incoming.size && stored == incoming.toSet()) return false
        replaceAll(type, categories)
        return true
    }

    @Query(
        "UPDATE categories SET isHidden = 1 WHERE type = :type " +
            "AND id IN (SELECT categoryId FROM hidden_categories WHERE categoryType = :type)",
    )
    abstract suspend fun reapplyHidden(type: StreamType)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(categories: List<Category>)

    @Query("DELETE FROM categories WHERE type = :type")
    abstract suspend fun deleteByType(type: StreamType)

    @Query("SELECT COUNT(*) FROM categories WHERE type = :type")
    abstract suspend fun count(type: StreamType): Int

    /** #167: categories not hidden, the number every screen header shows. */
    @Query("SELECT COUNT(*) FROM categories WHERE type = :type AND isHidden = 0")
    abstract suspend fun countVisible(type: StreamType): Int
}
