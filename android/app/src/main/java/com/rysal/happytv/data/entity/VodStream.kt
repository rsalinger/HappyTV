package com.rysal.happytv.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A VOD (movie) stream from `get_vod_streams`.
 *
 * Same id/coercion rules as [LiveStream]: ids are stringified, optional wire
 * values stay nullable. Numeric-looking strings are coerced to their numeric
 * type by DTO mappers (Xtream Codes field conventions).
 */
@Entity(
    tableName = "vod_streams",
    indices = [Index("categoryId"), Index("name"), Index("added")],
)
data class VodStream(
    /** `stream_id`, stringified. */
    @PrimaryKey val id: String,
    /** `category_id`, stringified → [Category.id] of a `VOD` row. */
    val categoryId: String,
    /** `num` — display sort order. */
    val num: Int? = null,
    val name: String,
    /**
     * `stream_icon` — poster/art URL (F-49).
     *
     * Panels vary on which field carries the poster:
     *  - `stream_icon` (TMDB `https://image.tmdb.org/t/p/...` URLs) — the field
     *    real panels put on `get_vod_streams` rows and the one we read here;
     *  - `cover` / `movie_image` — legacy aliases on `get_vod_info`'s
     *    `info` object only, not on the list rows we persist.
     *
     * The panel-shaped fixture `XtreamApiMockWebServerTest.VOD_STREAMS_JSON`
     * pins the behaviour: `stream_icon` set + `cover`/`movie_image` null
     * still produces a non-null [posterUrl].
     */
    val streamIcon: String? = null,
    /** `container_extension` — `mp4`/`mkv`/… (used to build the play URL). */
    val containerExtension: String? = null,
    /** `rating` — coerced to a number; wire may be `"7.5"` or `7.5`. */
    val rating: Double? = null,
    /** `added` — unix seconds. */
    val added: Long? = null,
    val customSid: String? = null,
    /** `direct_source` — override URL when present. */
    val directSource: String? = null,
) {
    /**
     * The poster field the rails/grid/details bind to (F-49).
     *
     * Decoupled from the wire field name on purpose: the UI never reads
     * `streamIcon` directly, so if a future panel shifts where the URL lives
     * the change stays here. Blank URLs collapse to `null` so callers can
     * paint a fallback instead of an empty `AsyncImage`.
     */
    val posterUrl: String?
        get() = streamIcon?.takeIf { it.isNotBlank() }
}
