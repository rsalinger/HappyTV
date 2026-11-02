package com.rysal.happytv.data.guide

import com.rysal.happytv.data.entity.EpgProgram

/**
 * Guide vocabulary — the model the ViewModel and the screen share.
 *
 * Two sources feed these models (task body §1):
 *  - the **provider-side guide**: Xtream `get_short_epg&stream_id=…`,
 *    keyed in the store by [GuideChannel.streamId];
 *  - the optional **XMLTV guide** (`settings.epgUrl`), keyed by
 *    [GuideChannel.epgChannelId] (= `LiveStream.epgChannelId`).
 *
 * The store is the pre-existing `epg_programs` table (unchanged schema —
 * no migration). The two key spaces are disjoint in practice (stream ids
 * are numeric, XMLTV ids are dotted strings) and the sources are
 * user-exclusive (Settings → Guide source), so one table serves both.
 */

/**
 * The key a channel's programmes are stored under in the current source
 * mode — the **single source of truth** every reader asks with (the guide
 * grid, and Live TV's now-line: F-36 was browse asking with the XMLTV key
 * while the store held provider rows).
 *
 * Provider rows are written under [streamId] ([GuideRepository.storeProviderPrograms]);
 * XMLTV rows under the programme's `channel`, i.e. [epgChannelId]
 * ([XmlTvParser]). Null = this channel has no key for the mode, so it has
 * no guide ("No guide info").
 */
fun programStoreKey(streamId: String, epgChannelId: String?, xmltvMode: Boolean): String? =
    if (xmltvMode) epgChannelId else streamId

/**
 * Is XMLTV the chosen guide source? (Default: provider-side only.) The
 * stored `settings.epgUrl` is a three-state contract (F-52): **absent**
 * means "auto — derive `xmltv.php` from the saved credentials" (the
 * derivation path sees that as XMLTV once a fetch succeeds and persists
 * the URL), **empty** means an explicit "From provider" choice, and a
 * **URL** is a typed override. Only the last two decide the store's key
 * space immediately, so this stays a plain "is a URL stored" check.
 * Shared so the worker, the repository and the browse screen can never
 * disagree about which source wrote the store.
 */
fun isXmltvGuideEnabled(epgUrl: String?): Boolean = !epgUrl?.trim().isNullOrEmpty()

/** One channel row in the guide grid. */
@androidx.compose.runtime.Immutable
data class GuideChannel(
    /** `LiveStream.id` — provider-side key + stream id for playback. */
    val streamId: String,
    /** `LiveStream.num` — shown under the name; null when the panel omits it. */
    val num: Int?,
    /** Display name. */
    val name: String,
    /**
     * The channel's genre when the guide sends one of the four genres the
     * design draws a dot for ([GENRE_COLORS]), else null ("No genre, no
     * dot"). Resolved from the provider's live-category name — the same
     * source the prototype's `ch.genre` models, and the only genre source
     * that exists in provider mode (the `epg_programs` schema has no
     * category column, by design: "table name unchanged to avoid migration").
     */
    val genre: String?,
    /** Favorite star in the channel cell. */
    val isFavorite: Boolean,
    /** `tv_archive` — replay available: ended blocks become focusable. */
    val hasReplay: Boolean,
    /** `epg_channel_id` — XMLTV lookup key; null = this channel has none. */
    val epgChannelId: String?,
    /**
     * `LiveStream.categoryId` — the guide rail's filter key (F-44). Null when
     * the panel omitted it, which puts the channel only under "All channels".
     */
    val categoryId: String? = null,
    /** The provider category's display name — the rail row's label source. */
    val categoryName: String? = null,
    /** `LiveStream.streamIcon` — the raw logo URL; [com.rysal.happytv.ui.browse.ChannelLogoUrl] vets it before any request (#139). */
    val logoUrl: String? = null,
) {
    /** Store key for programs in the current source mode. */
    fun programKey(xmltvMode: Boolean): String? =
        programStoreKey(streamId = streamId, epgChannelId = epgChannelId, xmltvMode = xmltvMode)
}

/** One guide listing (store row of `epg_programs`, whichever source wrote it). */
@androidx.compose.runtime.Immutable
data class GuideProgram(
    val channelId: String,
    val title: String,
    val description: String?,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
) {
    fun isLive(nowUtcMillis: Long): Boolean = startUtcMillis <= nowUtcMillis && nowUtcMillis < endUtcMillis
    fun hasEnded(nowUtcMillis: Long): Boolean = endUtcMillis <= nowUtcMillis
    fun startsAt(nowUtcMillis: Long): Boolean = startUtcMillis > nowUtcMillis

    /** Whole minutes of runtime, rounded up, never below 1 — the replay window. */
    val durationMinutes: Int
        get() = ((endUtcMillis - startUtcMillis).coerceAtLeast(0L) / 60_000L)
            .let { minutes -> if (minutes < 1L) 1 else minutes.toInt() }
}

fun EpgProgram.toGuideProgram(): GuideProgram = GuideProgram(
    channelId = channelId,
    title = title,
    description = description,
    startUtcMillis = startUtcMillis,
    endUtcMillis = endUtcMillis,
)

fun GuideProgram.toEntity(): EpgProgram = EpgProgram(
    channelId = channelId,
    title = title,
    description = description,
    startUtcMillis = startUtcMillis,
    endUtcMillis = endUtcMillis,
)

/** Lifecycle of a program relative to "now" — design spec "Rows" opacity rules. */
enum class GuideProgramState { LIVE, ENDED, FUTURE }

fun GuideProgram.stateAt(nowUtcMillis: Long): GuideProgramState = when {
    isLive(nowUtcMillis) -> GuideProgramState.LIVE
    hasEnded(nowUtcMillis) -> GuideProgramState.ENDED
    else -> GuideProgramState.FUTURE
}

/**
 * Genre → dot color (design spec §"Placeholder colors" "Guide genre dots").
 * Values are `0xAARRGGBB` longs so this file stays Compose-free; the UI
 * converts with `Color(GENRE_COLORS.getValue(genre))`.
 *
 * A genre that isn't in this map gets **no dot** — there is no default dot.
 */
val GENRE_COLORS: Map<String, Long> = mapOf(
    "News" to 0xFF6CA6F0,
    "Kids" to 0xFFF2D04A,
    "Sports" to 0xFF5CC98F,
    "Documentary" to 0xFF4FC1C9,
)

/**
 * Normalizes a provider category / guide genre name to the canonical
 * one of the four dot-able genres, or null when it isn't one.
 * Case-insensitive so "news"/"NEWS" from a panel still dot correctly.
 */
fun knownGenre(name: String?): String? {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return GENRE_COLORS.keys.firstOrNull { it.equals(trimmed, ignoreCase = true) }
}
