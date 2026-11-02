package com.rysal.happytv.data.entity

import androidx.room.Entity

/**
 * A category from `get_live_categories` / `get_vod_categories` / `get_series_categories`.
 *
 * Composite primary key `(id, type)`: Xtream category ids are only unique per
 * catalog type — live category `7` and VOD category `7` are different rows.
 *
 * [isHidden] is a denormalized copy of [HiddenCategory] kept so
 * `CategoryDao.observeVisible()` can filter with a plain WHERE clause;
 * [HiddenCategory] is the durable record that survives bulk catalog replaces
 * (CategoryDao.replaceAll re-applies it after every replace).
 */
@Entity(
    tableName = "categories",
    primaryKeys = ["id", "type"],
    indices = [androidx.room.Index("type")],
)
data class Category(
    /** `category_id` from the API, stringified (panels send both int and string). */
    val id: String,
    val type: StreamType,
    val name: String,
    /** Parent category id, stringified like [id]. Most panels never set it. */
    val parentId: String? = null,
    val isHidden: Boolean = false,
)
