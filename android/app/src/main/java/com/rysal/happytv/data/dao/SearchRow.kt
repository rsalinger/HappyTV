package com.rysal.happytv.data.dao

/**
 * The lightweight projections Search reads.
 *
 * Search matches on names only, so it must not drag whole catalog entities
 * (plots, icons, ratings — thousands of rows) into memory on every keystroke:
 * these three-column rows are what the search index flows return.
 *
 * Field names are the SQL aliases, which is what Room maps by.
 */

/** One channel for a search result card: number feeds "Channel 101". */
data class SearchChannelRow(
    val id: String,
    val name: String,
    val num: Int?,
)

/** One movie or show for a search result poster. */
data class SearchPosterRow(
    val id: String,
    val name: String,
    /** Show year ("2023"), or null when the catalog doesn't carry one. */
    val year: String?,
)

/**
 * A result's poster URL, looked up for the handful of ids a search actually
 * draws (#67) so the in-memory name index never carries 40k image URLs.
 */
data class SearchPosterUrlRow(
    val id: String,
    val posterUrl: String?,
)

/** The `stream_icon` for the few channel ids a search draws (#139), looked up so the index stays light. */
data class SearchChannelLogoRow(
    val id: String,
    val logoUrl: String?,
)
