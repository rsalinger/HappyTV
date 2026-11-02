package com.rysal.happytv.data.dao

/**
 * One poster-grid row: the columns the 6-across grid actually draws
 * (name, year, poster art) and nothing else.
 *
 * The poster grid pages this projection instead of whole catalog entities
 * (F-55): a real panel holds 31 598 VOD rows, and `VodStream`/`SeriesStream`
 * carry plots, cast lists, ratings, direct sources — everything the grid
 * never shows. Field names are the SQL aliases, same as [SearchPosterRow].
 */
data class PosterGridRow(
    val id: String,
    val name: String,
    /** Show year ("2023"), or null when the catalog doesn't carry one. */
    val year: String?,
    /**
     * Poster art URL — `stream_icon` for movies, `cover` for shows. Rides
     * along so the poster renderer can bind it (F-49) without a second pass
     * over the table.
     */
    val posterUrl: String?,
)
