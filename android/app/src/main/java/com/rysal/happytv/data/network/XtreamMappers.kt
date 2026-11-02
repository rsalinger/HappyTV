package com.rysal.happytv.data.network

import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.SeriesStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * DTO → entity mappers. The only place wire values are interpreted.
 *
 * Coercion rules (data-model §11):
 *  - ids must be non-blank to become a row (a stream without `stream_id`
 *    cannot exist as a Room primary key; a category without `category_id`
 *    cannot be addressed) — those rows are dropped, everything else maps
 *    best-effort;
 *  - blank strings mean "absent" for nullable entity fields, so `""` (the
 *    most common way a panel says nothing) becomes `null`;
 *  - numerics go through [JsonCoerce], so `"42"`, `42`, `""` and `null` all
 *    land somewhere sensible.
 */

// ------------------------------------------------------------------ live

fun LiveStreamDto.toEntity(): LiveStream? {
    if (stream_id.isBlank()) return null
    return LiveStream(
        id = stream_id,
        categoryId = category_id,
        num = JsonCoerce.toIntOrNull(num),
        name = name,
        streamIcon = stream_icon.blankToNull(),
        epgChannelId = epg_channel_id.blankToNull(),
        tvArchive = JsonCoerce.toBooleanOrNull(tv_archive),
        tvArchiveDuration = JsonCoerce.toIntOrNull(tv_archive_duration),
        containerExtension = container_extension.blankToNull(),
        added = JsonCoerce.toLongOrNull(added),
        customSid = custom_sid.blankToNull(),
        directSource = direct_source.blankToNull(),
    )
}

fun List<LiveStreamDto>.toLiveEntities(): List<LiveStream> = mapNotNull { it.toEntity() }

// ------------------------------------------------------------------- VOD

fun VodStreamDto.toEntity(): VodStream? {
    if (stream_id.isBlank()) return null
    return VodStream(
        id = stream_id,
        categoryId = category_id,
        num = JsonCoerce.toIntOrNull(num),
        name = name,
        streamIcon = stream_icon.blankToNull(),
        containerExtension = container_extension.blankToNull(),
        rating = JsonCoerce.toDoubleOrNull(rating),
        added = JsonCoerce.toLongOrNull(added),
        customSid = custom_sid.blankToNull(),
        directSource = direct_source.blankToNull(),
    )
}

fun List<VodStreamDto>.toVodEntities(): List<VodStream> = mapNotNull { it.toEntity() }

// ---------------------------------------------------------------- series

fun SeriesStreamDto.toEntity(): SeriesStream? {
    if (series_id.isBlank()) return null
    return SeriesStream(
        id = series_id,
        categoryId = category_id,
        num = JsonCoerce.toIntOrNull(num),
        name = name,
        cover = cover.blankToNull(),
        plot = plot.blankToNull(),
        cast = cast.blankToNull(),
        director = director.blankToNull(),
        genre = genre.blankToNull(),
        releaseDate = releaseDate.blankToNull(),
        lastModified = JsonCoerce.toLongOrNull(last_modified),
        rating = JsonCoerce.toDoubleOrNull(rating),
        // Stored raw (see SeriesStream.backdropPath) — the UI decodes on render.
        backdropPath = backdrop_path?.toString(),
        youtubeTrailer = youtube_trailer.blankToNull(),
    )
}

fun List<SeriesStreamDto>.toSeriesEntities(): List<SeriesStream> = mapNotNull { it.toEntity() }

/**
 * Typed view over the shape-shifting `backdrop_path` (data-model §11 note 5):
 * a JSON array, a single URL string, or a JSON-encoded array inside a string.
 */
fun SeriesStreamDto.backdropPaths(): List<String> = backdrop_path.toPathList()

/**
 * Decode a stored `SeriesStream.backdropPath` (the raw `toString()` of the
 * wire element: `["url"]`, `"url"`, `[]`, or a quoted JSON-encoded array) into
 * its URLs. Malformed or blank input yields an empty list, never a throw.
 */
fun decodeStoredBackdrops(raw: String?): List<String> {
    val text = raw.blankToNull() ?: return emptyList()
    val element = runCatching { Graph.json.parseToJsonElement(text) }.getOrNull()
        ?: return listOf(text).filter { it.startsWith("http") }
    return element.toPathList()
}

/** Same, for the VOD detail `info` block. */
fun VodInfoDetailDto.backdropPaths(): List<String> = backdrop_path.toPathList()

// ------------------------------------------------------- VOD detail (F-50)
//
// `get_vod_info` returns `{info, movie_data}` — but the two blocks are NOT
// symmetric. `movie_data` only repeats the stream-id / `container_extension`
// subset of `get_vod_streams`; every field the movie detail page renders
// (`movie_image`, `plot`, `genre`, `cast`, `director`, `duration`,
// `releasedate`, `youtube_trailer`, `tmdb_id`) lives in `info`. F-50's
// "clicking a movie shows no detail" is what a parser looks like when it
// reads `movie_data` for those fields: every one of them is null.

/**
 * The `info` block of `get_vod_info`, parsed into what the movie detail page
 * renders. Field names here are the *domain* names (`poster`, `overview`,
 * `genres`, `duration`, `releasedate`) so the UI layer never has to know the
 * wire's `movie_image` / `plot` / `duration_secs` quirks.
 */
data class VodDetail(
    /** Poster URL — [VodInfoDetailDto.movie_image] resolved through [resolvePosterUrl]. */
    val poster: String?,
    /** Plot text — `plot`, falling back to `description` when the panel splits them. */
    val overview: String?,
    /** Comma-separated `genre` split into tags, trimmed, blanks dropped. */
    val genres: List<String>,
    /** Wire `duration` (`HH:MM:SS`) or `duration_secs` rendered as `"Ns"`. */
    val duration: String?,
    /** `releasedate` `YYYY-MM-DD`, kept raw; the UI slices the year. */
    val releasedate: String?,
    /** Comma-separated `cast` split into names, trimmed, blanks dropped. */
    val cast: List<String>,
    val director: String?,
    /** YouTube video id (not a full URL) — wire `youtube_trailer`. */
    val youtubeTrailer: String?,
    /** `age` (`18+`) or `rating_mpaa` (`PG-13`). */
    val age: String?,
    /** IMDB-style 0..10; wire may be `8.7` or `"8.7"`. */
    val rating: String?,
    /** Backdrop URLs — array, single string, or JSON-encoded array in a string. */
    val backdrops: List<String>,
)

/**
 * Parse the full `get_vod_info` envelope into a [VodDetail]. Returns null
 * when the panel sent no `info` block at all (only `movie_data` — which
 * carries no detail fields), so callers fall back to the designed
 * "missing info" state rather than faking one.
 */
fun VodInfoDto.toDetail(): VodDetail? = info?.toDetail()

/**
 * Parse just the `info` block. This is where the actual work happens:
 * `movie_data` (the other half of the envelope) carries only stream ids and
 * `container_extension`, so reading it for plot/cover is exactly F-50.
 */
fun VodInfoDetailDto.toDetail(): VodDetail = VodDetail(
    poster = movie_image.resolvePosterUrl(),
    overview = plot.blankToNull() ?: description.blankToNull(),
    genres = genre.splitList(),
    duration = duration.blankToNull() ?: duration_secs.blankToNull()?.let { "${it}s" },
    releasedate = releasedate.blankToNull(),
    cast = cast.splitList(),
    director = director.blankToNull(),
    youtubeTrailer = youtube_trailer.blankToNull(),
    age = age.blankToNull() ?: rating_mpaa.blankToNull(),
    rating = rating.blankToNull(),
    backdrops = backdropPaths(),
)

private fun String?.splitList(): List<String> =
    orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * TMDB-relative poster paths (`/w500/xyz.jpg`) resolved to a real URL, per
 * the common `https://image.tmdb.org/t/p/w500/` base (data-model §5.3).
 * Absolute URLs and blanks pass through untouched.
 */
fun String?.resolvePosterUrl(size: String = "w500"): String? {
    val raw = this.blankToNull() ?: return null
    return if (raw.startsWith("/")) "https://image.tmdb.org/t/p/$size$raw" else raw
}

// -------------------------------------------------------------- categories

/**
 * [type] is not on the wire — it comes from *which* endpoint returned the row
 * (live / VOD / series category ids collide across catalogs, hence the
 * composite primary key). `parent_id` `0`/blank means "root" and stores as
 * null rather than a fake parent id.
 */
fun CategoryDto.toEntity(type: StreamType): Category? {
    if (category_id.isBlank()) return null
    return Category(
        id = category_id,
        type = type,
        name = category_name,
        parentId = parent_id.blankToNull()?.takeIf { it != "0" },
    )
}

fun List<CategoryDto>.toEntities(type: StreamType): List<Category> = mapNotNull { it.toEntity(type) }

// -------------------------------------------------------------------- EPG

/** Null-safe unwrap of the `{epg_listings}` wrapper (§2.9/§2.10). */
fun EpgListingsResponseDto.toEpgListings(): List<EpgListingDto> = epg_listings.orEmpty()

/**
 * One short-EPG entry → an [EpgProgram] row. `epg_id` is the channel key that
 * matches `LiveStream.epgChannelId` (data-model §8.1); timestamps are the
 * unix-second fields, never the panel-local `start`/`end` strings.
 */
fun EpgListingDto.toEntity(): EpgProgram? {
    val channelId = epg_id.blankToNull() ?: return null
    val start = JsonCoerce.toLongOrNull(start_timestamp)?.let { it * 1000 } ?: return null
    val end = JsonCoerce.toLongOrNull(stop_timestamp)?.let { it * 1000 } ?: return null
    if (end < start) return null
    return EpgProgram(
        channelId = channelId,
        title = title.decodeBase64IfEncoded().orEmpty(),
        description = description.decodeBase64IfEncoded(),
        startUtcMillis = start,
        endUtcMillis = end,
    )
}

// --------------------------------------------------------- series detail

/**
 * The `episodes` map typed out — keyed by season number **as a string**,
 * exactly as panels send it. Anything that is not `{season: [episodes]}` (some
 * panels send an array or an empty object) becomes an empty map instead of a
 * crash; a single unreadable season drops only that season.
 */
fun SeriesInfoDto.episodesBySeason(): Map<String, List<EpisodeDto>> {
    val root = episodes as? JsonObject ?: return emptyMap()
    val json = Graph.json
    return buildMap {
        for ((seasonKey, value) in root) {
            val list = value as? JsonArray ?: continue
            val parsed = runCatching {
                json.decodeFromJsonElement<List<EpisodeDto>>(list)
            }.getOrNull() ?: continue
            if (parsed.isNotEmpty()) put(seasonKey, parsed)
        }
    }
}

/** Season number → its episodes, numeric order, for `seasons[]`-driven UIs. */
fun SeriesInfoDto.episodesBySeasonNumber(): Map<Int, List<EpisodeDto>> =
    episodesBySeason().mapNotNull { (key, episodes) ->
        JsonCoerce.toIntOrNull(key)?.let { it to episodes }
    }.toMap()

// ---------------------------------------------------------------- helpers

/** `""` and whitespace are how panels say "no value" — store null instead. */
internal fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

/**
 * Some panels Base64-encode EPG `title`/`description` (data-model §11 note
 * 10). Heuristic: only try values that *look* like base64 and long enough for
 * a false positive to be unlikely, only accept results that decode as clean
 * UTF-8 text, and keep the original otherwise — a real title that happens to
 * be base64-shaped survives untouched unless it also decodes to readable text.
 */
internal fun String?.decodeBase64IfEncoded(): String? {
    val raw = this.blankToNull() ?: return this
    val candidate = raw.trim()
    if (candidate.length < 8 || !BASE64_LIKE.matches(candidate)) return raw
    val decoded = try {
        String(Base64.getDecoder().decode(candidate), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        return raw
    }
    val readable = decoded.isNotBlank() &&
        !decoded.contains('\uFFFD') &&
        decoded.none { it.isISOControl() && it != '\n' && it != '\t' }
    return if (readable) decoded else raw
}

private val BASE64_LIKE = Regex("^[A-Za-z0-9+/]+={0,2}$")

private fun JsonElement?.toPathList(): List<String> = when (this) {
    null, is kotlinx.serialization.json.JsonNull -> emptyList()
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.content?.blankToNull() }
    is JsonPrimitive -> {
        // A blank string means "no backdrop", not a list holding an empty url.
        val content = content.blankToNull()
        if (content == null) {
            emptyList()
        } else if (content.trimStart().startsWith("[")) {
            // JSON-encoded array inside a string — decode it, fall back to the raw value.
            runCatching { Graph.json.decodeFromString<List<String>>(content) }
                .getOrDefault(listOf(content))
        } else {
            listOf(content)
        }
    }
    else -> emptyList()
}
