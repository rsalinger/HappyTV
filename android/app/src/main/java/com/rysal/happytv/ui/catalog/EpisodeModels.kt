package com.rysal.happytv.ui.catalog

import com.rysal.happytv.data.entity.WatchProgress
import com.rysal.happytv.data.entity.WatchProgressPolicy
import com.rysal.happytv.data.network.EpisodeDto
import com.rysal.happytv.data.network.JsonCoerce
import com.rysal.happytv.data.network.SeriesInfoDto
import com.rysal.happytv.data.network.episodesBySeasonNumber
import com.rysal.happytv.data.network.resolvePosterUrl

/*
 * The show details page's episode area (#74): what `get_series_info` becomes, and how watch
 * progress marks it. Pure functions and plain data, so the wording and the ordering are pinned by
 * unit tests and not by looking at a screen.
 */

/** One row of the episode list. */
data class EpisodeItem(
    /** The episode id the stream URL and the watch progress use (not the show's id). */
    val id: String,
    val season: Int,
    /** The number shown ("E3") and used for ordering; the position in the season when the provider sent none. */
    val number: Int,
    val title: String,
    /** "42 min", or null when the provider sent no usable length. */
    val runtimeLabel: String?,
    val plot: String?,
    /** The episode's own still, when the provider supplies one. */
    val thumbnailUrl: String?,
) {
    /** "S2 E3". */
    val label: String get() = "S$season E$number"

    /** "E3 · Title". */
    val heading: String get() = "E$number · $title"
}

/** One season chip and the episodes behind it. */
data class SeasonItem(val number: Int, val episodes: List<EpisodeItem>) {
    /** "Season 2", or "Specials" for season 0. */
    val label: String get() = seasonLabel(number)
}

fun seasonLabel(number: Int): String = if (number == 0) "Specials" else "Season $number"

/** Where the episode list stands. */
sealed interface EpisodesState {
    /** Not asked for yet (a movie, or no show open). */
    data object Idle : EpisodesState

    /** The skeleton shows. */
    data object Loading : EpisodesState

    /** The call failed or the show could not be read; the error state offers a retry. */
    data object Failed : EpisodesState

    /** [seasons] in numeric order, season 0 ("Specials") last; empty when the show has no episodes. */
    data class Loaded(val seasons: List<SeasonItem>) : EpisodesState
}

/** The seasons of a show, from the keys of `episodes` (season metadata is optional and not needed). */
fun SeriesInfoDto.toSeasons(showNames: List<String?> = listOf(info?.name)): List<SeasonItem> =
    episodesBySeasonNumber().entries
        .sortedWith(compareBy({ it.key == 0 }, { it.key }))
        .mapNotNull { (season, dtos) ->
            val items = dtos.mapIndexedNotNull { index, dto -> dto.toItem(season, index + 1, showNames) }
                .sortedBy { it.number }
            if (items.isEmpty()) null else SeasonItem(season, items)
        }

private fun EpisodeDto.toItem(season: Int, positionInSeason: Int, showNames: List<String?>): EpisodeItem? {
    if (id.isBlank()) return null
    val number = JsonCoerce.toIntOrNull(episode_num) ?: positionInSeason
    val seconds = JsonCoerce.toIntOrNull(info?.duration_secs)
    val runtime = formatRuntime(info?.duration)
        ?: seconds?.takeIf { it > 0 }?.let { formatRuntime("${it}s") }
    return EpisodeItem(
        id = id,
        season = season,
        number = number,
        title = cleanEpisodeTitle(title, showNames, number),
        runtimeLabel = runtime,
        plot = info?.plot?.trim()?.takeIf { it.isNotEmpty() },
        thumbnailUrl = info?.movie_image.resolvePosterUrl("w300"),
    )
}

/** The season a details page opens on: where the resume episode is, else the first season that has episodes. */
fun initialSeason(seasons: List<SeasonItem>, resumeEpisodeId: String?): Int? =
    seasons.firstOrNull { s -> resumeEpisodeId != null && s.episodes.any { it.id == resumeEpisodeId } }?.number
        ?: seasons.firstOrNull()?.number

/** The first episode in play order: season 1 before Specials, matching the order of the chips. */
fun firstEpisode(seasons: List<SeasonItem>): EpisodeItem? = seasons.firstOrNull()?.episodes?.firstOrNull()

/** How an episode row marks the household's progress. */
data class EpisodeMark(
    /** Finished: shown with a "Watched" mark. */
    val watched: Boolean = false,
    /** 0..1 of an unfinished episode, or null (no progress, finished, or the length was unknown). */
    val fraction: Float? = null,
    /** Where to resume an unfinished episode; 0 otherwise. */
    val positionMillis: Long = 0L,
)

/** Row to mark. An up-next row (position 0, no length) marks nothing. */
fun WatchProgress.toMark(): EpisodeMark = when {
    finishedAt != null -> EpisodeMark(watched = true)
    positionMillis < WatchProgressPolicy.MIN_SAVE_MILLIS -> EpisodeMark()
    else -> EpisodeMark(fraction = fraction?.takeIf { it > 0f }, positionMillis = positionMillis)
}

/** Progress rows of one show to `{episode id to mark}`. */
fun marksOf(rows: List<WatchProgress>): Map<String, EpisodeMark> =
    rows.associate { it.streamId to it.toMark() }

// Only the seam left by removing a token or show name; a title's own trailing "." stays.
private val LEADING_SEPARATORS = Regex("""^[\s\-\u2013\u2014:\u00b7|.]+""")
private val EPISODE_TOKEN = Regex(
    """(?<![\p{L}\d])(S\d{1,2}\s?E\d{1,3}|\d{1,2}x\d{1,3})(?![\p{L}\d])""",
    RegexOption.IGNORE_CASE,
)
private val TRAILING_YEAR_TAG = Regex("""\s*[(\[]\s*(?:19|20)\d{2}\s*[)\]]\s*$""")

/**
 * Providers repeat the show and the code in an episode title ("Big Brother - S01E02 - Day 2",
 * "Gogglebox S01E01"); the row already says "E2". Rules:
 *  1. A season/episode token anywhere (`S01E02`, `S1 E2`, `1x05`): keep only what follows the last one.
 *  2. Otherwise drop a leading show name (as sent, without a trailing "(2023)" / "[2023]", and the
 *     grid's display name), case-insensitively, when a separator or non-letter follows it.
 *  3. Never empty: nothing left, only digits, or just the show name again gives "Episode [number]".
 */
fun cleanEpisodeTitle(raw: String?, showNames: List<String?>, number: Int): String {
    val fallback = "Episode $number"
    val names = showNames.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .flatMap { listOf(it, it.replace(TRAILING_YEAR_TAG, "").trim()) }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
    var t = raw?.trim().orEmpty()
    val token = EPISODE_TOKEN.findAll(t).lastOrNull()
    if (token != null) {
        t = t.substring(token.range.last + 1).replace(LEADING_SEPARATORS, "")
    } else {
        for (name in names) {
            if (t.startsWith(name, ignoreCase = true) &&
                t.getOrNull(name.length)?.let { !it.isLetterOrDigit() } != false
            ) {
                t = t.substring(name.length)
                break
            }
        }
        t = t.replace(LEADING_SEPARATORS, "")
    }
    t = t.trim()
    if (t.isEmpty() || t.all { it.isDigit() } || names.any { it.equals(t, ignoreCase = true) }) return fallback
    return t
}
