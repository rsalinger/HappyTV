package com.rysal.happytv.data.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire DTOs — `@Serializable` mirrors of what Xtream panels actually return
 * (the Xtream Codes player API responses are the field-by-field source).
 *
 * Three house rules, all driven by how loose the wire is:
 *
 *  1. **Ids are [String].** Panels send `stream_id`/`category_id`/`series_id`
 *     as either JSON numbers or strings; our shared `Graph.json` coerces both
 *     into String (see `WireCoercionProbeTest`), and string ids are what the
 *     Room schema stores.
 *  2. **Every numeric-ish field is [String] too, defaulted to null.** `num`,
 *     `added`, `rating`, `tv_archive`, timestamps… all appear as `42`, `"42"`,
 *     `""` or absent depending on the panel. Declaring them String keeps the
 *     parse from ever throwing; [JsonCoerce] converts in the mappers, where a
 *     missing value is an acceptable outcome instead of a crash.
 *     The explicit `= null` defaults are load-bearing: they give
 *     `coerceInputValues` somewhere to land when a panel sends an object where
 *     a primitive belongs.
 *  3. **Shape-variant values are [JsonElement].** `backdrop_path` is a JSON
 *     array on some panels and a single URL string on others; `episodes` is an
 *     object keyed by season number (and occasionally junk). Parsing those
 *     defensively belongs in the mapper, not the deserializer.
 *
 * Fields some clients store but we deliberately do not declare (`type_name`,
 * `category_name`, `series_no`, `live`, `user_id_referred`, `rating_from_ten`,
 * `rating_from_five`, `is_adult`) are dropped by `ignoreUnknownKeys` — our
 * entities do not model those denormalizations.
 */

// ------------------------------------------------------------------ auth

/** `player_api.php` with no `action` — the login probe (protocol §1.3). */
@Serializable
data class LoginResponse(
    val user_info: UserInfoDto? = null,
    /** Absent or empty when `user_info.auth == 0` — never crash on that. */
    val server_info: ServerInfoDto? = null,
) {
    /** `auth == 1` is the protocol's only success signal (protocol §5.2). */
    val isAuthenticated: Boolean
        get() = JsonCoerce.toIntOrNull(user_info?.auth) == 1
}

@Serializable
data class UserInfoDto(
    val username: String? = null,
    /** Echoed or redacted by panels — never log or display it. */
    val password: String? = null,
    /** `1` = success, `0` = failure. Sent as a number by most panels. */
    val auth: String? = null,
    /** Welcome message, or the human-readable failure reason. */
    val message: String? = null,
    /** `Active` | `Expired` | `Banned` | `Disabled`; usually only on failure. */
    val status: String? = null,
    /** Unix seconds; `null`/`""` = never expires. Check it yourself even when [status] says Active. */
    val exp_date: String? = null,
    val is_trial: String? = null,
    val active_cons: String? = null,
    val created_at: String? = null,
    val max_connections: String? = null,
    /** e.g. `["ts","m3u8"]` — drives the extension used in stream URLs. */
    val allowed_output_formats: List<String>? = null,
)

@Serializable
data class ServerInfoDto(
    /** Panel hostname — sometimes differs from the host the user typed. */
    val url: String? = null,
    val port: String? = null,
    /** Present on panels that serve HTTPS on a second port. */
    val https_port: String? = null,
    /** `http` | `https`. */
    val server_protocol: String? = null,
    val timezone: String? = null,
    /** Server unix seconds — usable for clock-skew detection. */
    val timestamp_now: String? = null,
    val time_now: String? = null,
)

// -------------------------------------------------------------- categories

/**
 * Shared shape for live / VOD / series categories (protocol §2.1, §2.3, §2.6).
 * `parent_id` is `0`/absent for root categories; series-category responses on
 * some panels omit it entirely.
 */
@Serializable
data class CategoryDto(
    val category_id: String = "",
    val category_name: String = "",
    val parent_id: String? = null,
)

// -------------------------------------------------------------- live rows

/** `action=get_live_streams` row (data-model §3.1). */
@Serializable
data class LiveStreamDto(
    /** Display sort order. */
    val num: String? = null,
    val name: String = "",
    /** Always `"live"` here; some panels omit it. */
    val stream_type: String? = null,
    /** Primary key — feeds the `/live/{user}/{pass}/{id}.{ext}` URL. */
    val stream_id: String = "",
    /** Channel logo URL. */
    val stream_icon: String? = null,
    /** Cross-reference into XMLTV `<channel id>` / EPG `epg_id`. */
    val epg_channel_id: String? = null,
    /** Unix seconds the channel was added (wire is a string). */
    val added: String? = null,
    val category_id: String = "",
    /** STB integration id — empty string is normal. */
    val custom_sid: String? = null,
    /** `1` = catchup supported. Sent as `0|1`, `"0"|"1"` or boolean. */
    val tv_archive: String? = null,
    /** When non-blank the player should use this URL instead of building one. */
    val direct_source: String? = null,
    /** Days of catchup; only meaningful when [tv_archive] == 1. */
    val tv_archive_duration: String? = null,
    /** Usually empty for live; file format when the stream is a direct file. */
    val container_extension: String? = null,
)

// ---------------------------------------------------------------- VOD rows

/** `action=get_vod_streams` row (data-model §4.1). */
@Serializable
data class VodStreamDto(
    val num: String? = null,
    val name: String = "",
    /** Always `"movie"` here. */
    val stream_type: String? = null,
    /** Primary key — feeds `/movie/{user}/{pass}/{id}.{ext}` and `get_vod_info`. */
    val stream_id: String = "",
    val stream_icon: String? = null,
    val added: String? = null,
    val category_id: String = "",
    /** `mp4`/`mkv`/`avi`… — the URL extension. */
    val container_extension: String? = null,
    val custom_sid: String? = null,
    val direct_source: String? = null,
    /** IMDB-style 0..10; wire may be `8.7` or `"8.7"`. */
    val rating: String? = null,
    /** 0..5 variant — wire-only, we persist [rating]. */
    val rating_5based: String? = null,
)

/** `action=get_vod_info` → `{ info, movie_data }` (data-model §5). */
@Serializable
data class VodInfoDto(
    val info: VodInfoDetailDto? = null,
    val movie_data: MovieDataDto? = null,
)

@Serializable
data class VodInfoDetailDto(
    val tmdb_id: String? = null,
    val imdb_id: String? = null,
    /** Poster URL, or a TMDB-relative path (`/w500/…`) to resolve client-side. */
    val movie_image: String? = null,
    val cover_big: String? = null,
    /** Array of backdrop URLs — panels also send a single string. See mappers. */
    val backdrop_path: JsonElement? = null,
    /** YouTube video id (not a full URL). */
    val youtube_trailer: String? = null,
    /** Comma-separated genres. */
    val genre: String? = null,
    val plot: String? = null,
    /** Comma-separated cast. */
    val cast: String? = null,
    val director: String? = null,
    val rating: String? = null,
    /** `YYYY-MM-DD`. */
    val releasedate: String? = null,
    val duration_secs: String? = null,
    /** `HH:MM:SS`. */
    val duration: String? = null,
    val bitrate: String? = null,
    /** `{codec_name, width, height}` — kept raw for the detail screen. */
    val video: JsonObject? = null,
    /** `{codec_name, channels[, bitrate]}` — kept raw for the detail screen. */
    val audio: JsonObject? = null,
    val country: String? = null,
    /** Sometimes duplicates [cast]. */
    val actors: String? = null,
    val name: String? = null,
    val name_o: String? = null,
    /** Sometimes duplicates [plot]. */
    val description: String? = null,
    /** Age rating, e.g. `"18+"`. */
    val age: String? = null,
    /** MPAA rating, e.g. `"PG-13"`. */
    val rating_mpaa: String? = null,
    /** Runtime in minutes (series-style VOD). */
    val episode_run_time: String? = null,
)

/** `movie_data` — the `VodStream` subset repeated inside `get_vod_info`. */
@Serializable
data class MovieDataDto(
    val stream_id: String? = null,
    val name: String? = null,
    val added: String? = null,
    val category_id: String? = null,
    val container_extension: String? = null,
    val custom_sid: String? = null,
    val direct_source: String? = null,
)

// ------------------------------------------------------------- series rows

/**
 * `action=get_series` row (data-model §6). The same shape comes back as the
 * `info` block of `get_series_info` (data-model §7.2 — the duplication is
 * intentional), which is why [SeriesInfoDto] reuses this class.
 */
@Serializable
data class SeriesStreamDto(
    val num: String? = null,
    val name: String = "",
    /** Primary key — feeds `get_series_info`. Episodes have their own ids. */
    val series_id: String = "",
    val cover: String? = null,
    val plot: String? = null,
    /** Comma-separated names, kept verbatim. */
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    /** `YYYY-MM-DD` (or year-only on some panels). */
    val releaseDate: String? = null,
    val last_modified: String? = null,
    val rating: String? = null,
    val rating_5based: String? = null,
    /**
     * Array of backdrop URLs, OR a single URL string, OR a JSON-encoded array
     * inside a string — all three occur (data-model §11 note 5). Raw on
     * purpose; [SeriesStreamDto.backdropPaths] is the typed view.
     */
    val backdrop_path: JsonElement? = null,
    /** YouTube video id. */
    val youtube_trailer: String? = null,
    val category_id: String = "",
    /** Missing from the top-level list on most panels; present in `info`. */
    val tmdb_id: String? = null,
)

/**
 * `action=get_series_info` response (data-model §7):
 * `{ seasons: [...], info: {...}, episodes: { "1": [...], "2": [...] } }`.
 */
@Serializable
data class SeriesInfoDto(
    val seasons: List<SeasonDto>? = null,
    /** Same shape as the `get_series` rows — see [SeriesStreamDto]. */
    val info: SeriesStreamDto? = null,
    /**
     * Keyed by season number **as a string** (`"1"`, `"2"`) — the single most
     * common implementation pitfall of this protocol — and occasionally sent
     * as junk (an array, an object of objects). Kept as [JsonElement] so the
     * parse never throws; use [episodesBySeason] for the typed view.
     */
    val episodes: JsonElement? = null,
)

@Serializable
data class SeasonDto(
    /** Panel-internal id; usually equal to [season_number]. */
    val id: String? = null,
    val season_number: String? = null,
    /** e.g. `"Season 1"`. */
    val name: String? = null,
    val cover: String? = null,
    val air_date: String? = null,
    val episode_count: String? = null,
    val rating: String? = null,
    val tmdb_id: String? = null,
)

/**
 * One episode. [id] — **not** `series_id`, **not** `episode_num` — is what
 * goes into the stream URL `/series/{user}/{pass}/{id}.{ext}`.
 */
@Serializable
data class EpisodeDto(
    val id: String = "",
    /** Display number within the season. */
    val episode_num: String? = null,
    val title: String? = null,
    /** The URL extension, e.g. `mp4`. */
    val container_extension: String? = null,
    /** Redundant with the `episodes` key — handy when flattening. */
    val season: String? = null,
    val added: String? = null,
    val custom_sid: String? = null,
    val direct_source: String? = null,
    val info: EpisodeInfoDto? = null,
)

@Serializable
data class EpisodeInfoDto(
    /** Per-episode thumbnail; may be TMDB-relative. */
    val movie_image: String? = null,
    val plot: String? = null,
    val duration_secs: String? = null,
    /** `HH:MM:SS`. */
    val duration: String? = null,
    val rating: String? = null,
    val tmdb_id: String? = null,
)

// -------------------------------------------------------------------- EPG

/**
 * Wrapper for `get_short_epg` and `get_simple_data_table` (protocol §2.9,
 * §2.10). Old panels have been seen to return `{}`, `[]` or an error body for
 * these; `{}` and a missing key degrade to an empty list via [epg_listings],
 * and a non-object body surfaces as a parse error the caller reports per tile.
 */
@Serializable
data class EpgListingsResponseDto(
    val epg_listings: List<EpgListingDto>? = null,
)

/** One EPG entry (data-model §8). */
@Serializable
data class EpgListingDto(
    /** Panel-internal program id. */
    val id: String? = null,
    /** EPG channel key — matches `LiveStream.epg_channel_id`. */
    val epg_id: String? = null,
    /** Base64-encoded on some panels; see `maybeBase64` in the mappers. */
    val title: String? = null,
    val lang: String? = null,
    /** `YYYY-MM-DD HH:MM:SS`, panel-local. */
    val start: String? = null,
    val end: String? = null,
    /** Unix seconds — use these, not the local-time strings, for ranges. */
    val start_timestamp: String? = null,
    val stop_timestamp: String? = null,
    /** Exactly one entry per channel carries `1`. */
    val now_playing: String? = null,
    /** Catchup available for this program. */
    val has_archive: String? = null,
    /** Also Base64-encoded on some panels. */
    val description: String? = null,
)
