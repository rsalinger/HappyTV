package com.rysal.happytv.ui.catalog

import java.util.Locale

/*
 * How the details page words what the provider sends (spec #72, "Details polish"). Pure
 * functions, so the wording is pinned by unit tests rather than by looking at a screen.
 */

/** The provider's rating scale: Xtream's `rating` is IMDB-style, 0 to 10 (`rating_5based` is the 0 to 5 twin). */
internal const val RATING_SCALE = 10

/** "Rating 7.4/10". */
internal fun ratingText(value: Double): String =
    "Rating " + String.format(Locale.US, "%.1f", value) + "/" + RATING_SCALE

/**
 * "1 h 30 min" from what the panel sends for a runtime: `HH:MM:SS` (`01:30:00`), `MM:SS`, plain
 * minutes (`90`) or `Ns` seconds (the mapper's rendering of `duration_secs`). Null when there is
 * nothing usable (blank, zero, or text this does not recognise), so the page omits the runtime
 * rather than showing the raw string.
 */
internal fun formatRuntime(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val minutes: Int = when {
        Regex("""\d+:\d{2}:\d{2}""").matches(text) -> {
            val (h, m, s) = text.split(':').map { it.toInt() }
            h * 60 + m + if (s >= 30) 1 else 0
        }
        Regex("""\d+:\d{2}""").matches(text) -> {
            val (m, s) = text.split(':').map { it.toInt() }
            m + if (s >= 30) 1 else 0
        }
        Regex("""\d+s""").matches(text) -> (text.dropLast(1).toLong() + 30L).div(60L).toInt()
        Regex("""\d+""").matches(text) -> text.toInt()
        else -> return null
    }
    if (minutes <= 0) return null
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours h"
        else -> "$hours h $rest min"
    }
}

private val TRAILING_YEAR = Regex("""^(.*\S)\s*[(\[]\s*((?:19|20)\d{2})\s*[)\]]\s*$""")

/**
 * Provider titles often end in their year ("Dark Matter (2024)"). The page shows the year once, in
 * its details line, so the title drops it. Returns the title without the year and that year (null
 * when the title carried none).
 */
internal fun splitTitleYear(name: String): Pair<String, String?> {
    val match = TRAILING_YEAR.matchEntire(name.trim()) ?: return name to null
    return match.groupValues[1] to match.groupValues[2]
}

/** A title as a poster shows it: quality tag, the title without tag or year, and the year, once. */
internal data class DisplayTitle(val quality: String?, val title: String, val year: String?)

/**
 * Splits a provider title for display. The year comes from [year] when the provider sent one, else
 * from a bracketed year in the title. It is dropped from the title when the title ends in it as
 * "(2022)", "[2022]", "- 2022" or a bare " 2022" (the bare forms only when it matches [year], so
 * "Blade Runner 2049" and "1984" keep their numbers).
 */
internal fun displayTitle(rawName: String, year: String?): DisplayTitle {
    val (quality, unprefixed) = splitQuality(rawName)
    val (bracketTitle, bracketYear) = splitTitleYear(unprefixed)
    val known = year?.trim()?.takeIf { it.isNotEmpty() }
    if (bracketYear != null) {
        // "Name (2022)": the year moves to its slot. A different year in the title stays in the title.
        return if (known == null || known == bracketYear) {
            DisplayTitle(quality, bracketTitle, bracketYear)
        } else {
            DisplayTitle(quality, unprefixed, known)
        }
    }
    if (known == null) return DisplayTitle(quality, unprefixed, null)
    val trailing = Regex("""^(.*?\S)\s*(?:[-\u2013\u2014]\s*|\s)${Regex.escape(known)}$""").matchEntire(unprefixed)
    return DisplayTitle(quality, trailing?.groupValues?.get(1) ?: unprefixed, known)
}

/** The quality tags providers put in front of a title. */
private const val QUALITY_TAGS = "4K|UHD|FHD|HD|SD|HEVC"

/**
 * `[4K] Title`, `(4K) Title`, `4K: Title`, `4K | Title`, `4K - Title` or `4K Title` (any case). A
 * bare tag must be followed by a space, `:` or `|`, so "4Kids" and "HDTV Classics" stay whole, and
 * something must follow it, so a title that is just "HD" keeps its name.
 */
private val LEADING_QUALITY = Regex(
    """^\s*(?:\[\s*($QUALITY_TAGS)\s*]|\(\s*($QUALITY_TAGS)\s*\)|($QUALITY_TAGS)(?=[\s:|]))""" +
        """\s*(?:[:|]|[-–—](?=\s))?\s*(\S.*)$""",
    RegexOption.IGNORE_CASE,
)

/**
 * Splits a leading quality tag off a provider title: the tag (upper case, "4K") and the title
 * without it. The tag is null and the name untouched when there is none. Display only: search
 * matching and favorites keep the raw name.
 */
internal fun splitQuality(name: String): Pair<String?, String> {
    val match = LEADING_QUALITY.matchEntire(name) ?: return null to name
    val tag = (1..3).map { match.groupValues[it] }.first { it.isNotEmpty() }.uppercase(Locale.US)
    val rest = match.groupValues[4].trim()
    // "HD -" or "4K:" is a tag with nothing behind it: keep the name.
    if (rest.none { it.isLetterOrDigit() }) return null to name
    return tag to rest
}
