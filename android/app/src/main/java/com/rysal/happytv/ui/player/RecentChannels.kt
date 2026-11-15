package com.rysal.happytv.ui.player

import com.rysal.happytv.data.entity.LiveStream

/** #220: the recent channels row shows at most this many cards. */
internal const val RECENT_CHANNELS_MAX = 7

/** The row hides after this long without a key while it is open; the controls alone use [CONTROLS_HIDE_MILLIS]. */
internal const val RECENT_ROW_HIDE_MILLIS = 10_000L
internal const val CONTROLS_HIDE_MILLIS = 5_000L

/** How long the controls (and the open row) stay up without input. */
internal fun controlsHideDelayMillis(recentRowOpen: Boolean): Long =
    if (recentRowOpen) RECENT_ROW_HIDE_MILLIS else CONTROLS_HIDE_MILLIS

/** One card of the recent channels row, with the program on now when the guide has one. */
data class RecentChannelCard(
    val streamId: String,
    val number: Int?,
    val name: String,
    val logoUrl: String?,
    val programTitle: String? = null,
    val startUtcMillis: Long = 0L,
    val endUtcMillis: Long = 0L,
) {
    val hasGuide: Boolean get() = programTitle != null

    /** How far into the program [nowUtcMillis] is, 0..1; null without guide data. */
    fun progressFraction(nowUtcMillis: Long): Float? {
        if (!hasGuide || endUtcMillis <= startUtcMillis) return null
        return ((nowUtcMillis - startUtcMillis).toFloat() / (endUtcMillis - startUtcMillis)).coerceIn(0f, 1f)
    }
}

internal object RecentChannels {

    /**
     * Which recently watched channels the row offers: [recentIdsNewestFirst] in order, without the
     * channel playing now, repeats, channels that are no longer in the lineup ([lineup]), hidden
     * channels and channels in hidden categories, capped at [cap].
     */
    fun select(
        recentIdsNewestFirst: List<String>,
        currentStreamId: String?,
        lineup: Map<String, LiveStream>,
        hiddenChannelIds: Set<String>,
        hiddenCategoryIds: Set<String>,
        cap: Int = RECENT_CHANNELS_MAX,
    ): List<LiveStream> {
        val seen = HashSet<String>()
        val picked = ArrayList<LiveStream>(cap)
        for (id in recentIdsNewestFirst) {
            if (picked.size >= cap) break
            if (id == currentStreamId || !seen.add(id)) continue
            val stream = lineup[id] ?: continue
            if (id in hiddenChannelIds || stream.categoryId in hiddenCategoryIds) continue
            picked += stream
        }
        return picked
    }

    fun card(stream: LiveStream, nowNext: PlayerNowNext?): RecentChannelCard {
        val now = nowNext?.now
        return RecentChannelCard(
            streamId = stream.id,
            number = stream.num,
            name = stream.name,
            logoUrl = stream.streamIcon,
            programTitle = now?.title?.takeIf { it.isNotBlank() },
            startUtcMillis = now?.startUtcMillis ?: 0L,
            endUtcMillis = now?.endUtcMillis ?: 0L,
        )
    }
}

/** The keys the recent channels row routes (#220). */
internal enum class RecentRowKey { Up, Down, Left, Right, Ok, Back }

internal sealed interface RecentRowAction {
    /** Not the row's key: the player's other handlers run, exactly as before the row existed. */
    data object PassThrough : RecentRowAction

    /** Swallow the key; nothing happens (an edge of the row, or Down on the open row). */
    data object Consume : RecentRowAction

    data object OpenRow : RecentRowAction

    /** Close the row and return to the controls. */
    data object CloseRow : RecentRowAction

    data class FocusCard(val index: Int) : RecentRowAction

    /** Play the focused card's channel full screen. */
    data object PickFocused : RecentRowAction
}

/**
 * Whether Down may open the row: a live or catch-up channel with something to show, and no other panel over it.
 * Live opens it only with the controls open (#224: with them hidden Down is channel down); catch-up keeps
 * opening it either way.
 */
internal fun recentRowEligible(
    mode: PlaybackMode?,
    hasPickHandler: Boolean,
    recentCount: Int,
    tracksPanelOpen: Boolean,
    leaveConfirmOpen: Boolean,
    endCardOpen: Boolean,
    /** While the stream buffers the controls are hidden and cannot show a row. */
    buffering: Boolean = false,
    controlsVisible: Boolean = true,
): Boolean = hasPickHandler && recentCount > 0 && (mode == PlaybackMode.Live || mode == PlaybackMode.Replay) &&
    (mode != PlaybackMode.Live || controlsVisible) &&
    !tracksPanelOpen && !leaveConfirmOpen && !endCardOpen && !buffering

internal object RecentRowKeys {

    fun route(
        key: RecentRowKey,
        rowOpen: Boolean,
        eligible: Boolean,
        focusedIndex: Int,
        cardCount: Int,
    ): RecentRowAction {
        if (!rowOpen) {
            // Closed: only Down, and only with something to show (live: only with the controls open,
            // see [recentRowEligible]). Up, OK, Left/Right and Back keep their behavior.
            return if (key == RecentRowKey.Down && eligible) RecentRowAction.OpenRow else RecentRowAction.PassThrough
        }
        // The list emptied under an open row: leave it.
        if (cardCount <= 0) return RecentRowAction.CloseRow
        return when (key) {
            RecentRowKey.Up, RecentRowKey.Back -> RecentRowAction.CloseRow
            RecentRowKey.Down -> RecentRowAction.Consume
            RecentRowKey.Left ->
                if (focusedIndex > 0) RecentRowAction.FocusCard(focusedIndex - 1) else RecentRowAction.Consume
            RecentRowKey.Right ->
                if (focusedIndex < cardCount - 1) RecentRowAction.FocusCard(focusedIndex + 1) else RecentRowAction.Consume
            RecentRowKey.Ok -> RecentRowAction.PickFocused
        }
    }
}

/** Where a started stream has landed, for [quietZapStep]. */
internal enum class ZapLanding { Loading, Ready, Failed }

internal data class QuietZapStep(val armed: Boolean, val showControls: Boolean)

/**
 * A pick from the recent row plays with the controls closed (#220). [armed] is set by the pick and holds
 * through Loading; the first Ready consumes it (controls stay closed); any failure state (offline, not found,
 * needs sign-in, unsupported format, refused redirect, replay unsupported) disarms it, so a later zap from
 * anywhere else brings the controls up as usual.
 */
internal fun quietZapStep(armed: Boolean, landing: ZapLanding): QuietZapStep = when (landing) {
    ZapLanding.Loading -> QuietZapStep(armed, showControls = false)
    ZapLanding.Ready -> QuietZapStep(armed = false, showControls = !armed)
    ZapLanding.Failed -> QuietZapStep(armed = false, showControls = false)
}
