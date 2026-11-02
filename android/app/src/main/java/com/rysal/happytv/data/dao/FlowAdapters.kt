package com.rysal.happytv.data.dao

import com.rysal.happytv.data.entity.Favorite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Project a [LiveStreamDao.observeCounts] stream to a `{categoryId: count}`
 * map. One allocation per upstream emission; safe to collect inside
 * `viewModelScope.combine` blocks.
 */
fun Flow<List<CategoryCount>>.toCountMap(): Flow<Map<String, Int>> =
    map { rows -> rows.associate { it.categoryId to it.count } }

/** Project a single-Favorite stream to its `streamId`. */
fun Flow<Favorite>.favoriteId(): Flow<String> =
    map { fav -> fav.streamId }
