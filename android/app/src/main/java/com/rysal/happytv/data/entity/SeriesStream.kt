package com.rysal.happytv.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A series from `get_series` (the list endpoint; episode detail comes from
 * `get_series_info` and is stored by the network layer's own cache if needed).
 *
 * [backdropPath] is stored as the **raw** wire value: panels return
 * `backdrop_path` as a JSON-encoded array of URLs inside a string
 * (`"[\"https://…\"]"`). Keeping it raw avoids parse-on-read; the UI layer
 * decodes it with the shared Json when rendering.
 */
@Entity(
    tableName = "series_streams",
    indices = [Index("categoryId"), Index("name"), Index("lastModified")],
)
data class SeriesStream(
    /** `series_id`, stringified. */
    @PrimaryKey val id: String,
    /** `category_id`, stringified → [Category.id] of a `SERIES` row. */
    val categoryId: String,
    /** `num` — display sort order. */
    val num: Int? = null,
    val name: String,
    /**
     * `cover` — poster URL (F-49 for the Shows rail; kept symmetrical with
     * [VodStream.posterUrl]). Panel rows carry `cover` set + `backdrop_path`
     * as a JSON-encoded array of URLs in a string, both raw.
     */
    val cover: String? = null,
    val plot: String? = null,
    /** `cast` — comma-separated names, kept verbatim. */
    val cast: String? = null,
    val director: String? = null,
    /** `genre` — comma-separated, kept verbatim. */
    val genre: String? = null,
    /** `releaseDate` — panel format varies (`"2023-10-01"`, `"2023"`); raw string. */
    val releaseDate: String? = null,
    /** `last_modified` — unix seconds. */
    val lastModified: Long? = null,
    /** `rating` — coerced to a number by the DTO mapper. */
    val rating: Double? = null,
    /** `backdrop_path` — raw wire value, see class doc. */
    val backdropPath: String? = null,
    /** `youtubeTrailer` — YouTube URL/id when the panel provides one. */
    val youtubeTrailer: String? = null,
) {
    /**
     * The poster field the Shows rails/grid bind to (F-49 mirror of
     * [VodStream.posterUrl]). Blank URLs collapse to `null`.
     */
    val posterUrl: String?
        get() = cover?.takeIf { it.isNotBlank() }

    /** First usable URL in the stored [backdropPath] (array, string or empty). */
    val backdropUrl: String?
        get() = com.rysal.happytv.data.network.decodeStoredBackdrops(backdropPath).firstOrNull()
}
