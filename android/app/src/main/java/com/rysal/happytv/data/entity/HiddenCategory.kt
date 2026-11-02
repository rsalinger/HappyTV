package com.rysal.happytv.data.entity

import androidx.room.Entity

/**
 * Durable record of a user-hidden category, keyed by `(categoryId, categoryType)`
 * (category ids are only unique per catalog type).
 *
 * Why a separate table from [Category.isHidden]: catalog bulk replaces delete
 * and re-insert every category row, which would silently wipe the flag.
 * CategoryDao.replaceAll re-applies these rows after each replace, and both
 * toggle helpers keep the two tables in sync.
 */
@Entity(tableName = "hidden_categories", primaryKeys = ["categoryId", "categoryType"])
data class HiddenCategory(
    /** [Category.id], stringified. */
    val categoryId: String,
    /** Which catalog this category belongs to. */
    val categoryType: StreamType,
)
