package com.rysal.happytv.ui.player

/**
 * Pure rules behind the player's Start over button and Program info card (#131), kept out of
 * Compose so they can be unit tested without a UI.
 */
object PlayerStartOver {

    /**
     * The program Start over would replay, or null when the button must stay greyed out:
     * live playback only, the channel keeps catch-up (`tv_archive`), and the guide knows the
     * current program's start (and it has a positive length).
     */
    fun program(content: PlayerContent): PlayerProgram? {
        if (content.mode != PlaybackMode.Live || !content.tvArchive) return null
        val now = content.nowNext?.now ?: return null
        if (now.startUtcMillis <= 0L || now.endUtcMillis <= now.startUtcMillis) return null
        return now
    }

    fun isEnabled(content: PlayerContent): Boolean = program(content) != null

    /** Timeshift window in whole minutes, rounded up so the program's tail is included. */
    fun durationMinutes(program: PlayerProgram): Int =
        (((program.endUtcMillis - program.startUtcMillis) + 59_999L) / 60_000L).toInt().coerceAtLeast(1)

    /** The line shown under the focused button when it is greyed out; null when enabled. */
    fun unavailableReason(content: PlayerContent): String? = when {
        isEnabled(content) -> null
        !content.tvArchive -> "No replay on this channel"
        else -> "No guide info for this show"
    }
}

/** What the Program info card shows, resolved from the guide data already loaded with the channel. */
data class PlayerInfoCard(
    /** "101 · Northbay News". */
    val channelLine: String?,
    val title: String,
    /** "2:00–2:30", null when the guide has no program on now. */
    val timeRange: String?,
    /** "28 min left", null when there is no program on now or it has ended. */
    val timeLeft: String?,
    val description: String?,
    val nextTitle: String?,
    /** "2:30–3:00". */
    val nextRange: String?,
) {
    companion object {
        /** Null when the guide has nothing for this channel: the Program info button is greyed out. */
        fun from(content: PlayerContent, nowUtcMillis: Long): PlayerInfoCard? {
            val nowNext = content.nowNext ?: return null
            val now = nowNext.now
            val next = nowNext.next
            if (now == null && next == null) return null
            return PlayerInfoCard(
                channelLine = content.topLine,
                title = now?.title ?: content.title,
                timeRange = now?.let { PlayerFormatting.rangeLabel(it.startUtcMillis, it.endUtcMillis) },
                timeLeft = now?.let { timeLeftLabel(it.endUtcMillis, nowUtcMillis) },
                description = now?.description?.trim()?.takeIf { it.isNotEmpty() },
                nextTitle = next?.title,
                nextRange = next?.let { PlayerFormatting.rangeLabel(it.startUtcMillis, it.endUtcMillis) },
            )
        }

        /** "28 min left" / "1 h 5 min left"; null once the program has ended. */
        fun timeLeftLabel(endUtcMillis: Long, nowUtcMillis: Long): String? {
            val minutes = (endUtcMillis - nowUtcMillis + 59_999L) / 60_000L
            return when {
                minutes <= 0L -> null
                minutes < 60L -> "$minutes min left"
                minutes % 60L == 0L -> "${minutes / 60L} h left"
                else -> "${minutes / 60L} h ${minutes % 60L} min left"
            }
        }
    }
}

/** Reason line under a greyed-out Program info button. */
const val PROGRAM_INFO_UNAVAILABLE = "No guide info"

/**
 * The buttons in the control row, in D-pad order (left to right). Live shows **Start over** and
 * replay **Go live** in the same slot; Program info is for channels (greyed out without guide
 * data, still a focus stop so the reason is readable); Tracks only with choices.
 */
fun playerControlOrder(content: PlayerContent): List<PlayerControl> = buildList {
    add(PlayerControl.PlayPause)
    when (content.mode) {
        PlaybackMode.Live -> add(PlayerControl.StartOver)
        PlaybackMode.Replay -> add(PlayerControl.GoLive)
        PlaybackMode.Movie -> Unit
    }
    if (content.mode != PlaybackMode.Movie) add(PlayerControl.ProgramInfo)
    if (content.tracks?.hasChoices == true) add(PlayerControl.Tracks)
    add(PlayerControl.Favorite)
}

/**
 * The player scrims (#131). The bottom one used to be 560 of 1080 px; now it backs the control
 * row and scrubber (top 800 to the bottom edge) with a soft fade above. Alpha at a point is
 * linear between stops, so the contrast of text over the brightest possible video is a
 * computation ([bottomAlphaAt]) rather than a judgement.
 */
object PlayerScrim {
    const val BOTTOM_HEIGHT_PX = 352

    /** Soft fade above the controls (they start at 800 px; the ring and glow reach about 780). */
    const val BOTTOM_FADE_PX = 72
    const val BOTTOM_ALPHA_AT_FADE_END = 0.82f
    const val BOTTOM_ALPHA_AT_EDGE = 0.90f

    const val TOP_HEIGHT_PX = 240
    const val TOP_ALPHA_AT_EDGE = 0.80f
    const val TOP_HOLD_FRACTION = 0.70f
    const val TOP_ALPHA_AT_HOLD = 0.75f

    /** Black alpha [yPx] below the bottom scrim's top edge. */
    fun bottomAlphaAt(yPx: Float): Float {
        val y = yPx.coerceIn(0f, BOTTOM_HEIGHT_PX.toFloat())
        return if (y <= BOTTOM_FADE_PX) {
            BOTTOM_ALPHA_AT_FADE_END * y / BOTTOM_FADE_PX
        } else {
            BOTTOM_ALPHA_AT_FADE_END +
                (BOTTOM_ALPHA_AT_EDGE - BOTTOM_ALPHA_AT_FADE_END) * (y - BOTTOM_FADE_PX) /
                (BOTTOM_HEIGHT_PX - BOTTOM_FADE_PX)
        }
    }

    /** Black alpha [yPx] below the top of the screen. */
    fun topAlphaAt(yPx: Float): Float {
        val y = yPx.coerceIn(0f, TOP_HEIGHT_PX.toFloat())
        val hold = TOP_HEIGHT_PX * TOP_HOLD_FRACTION
        return if (y <= hold) {
            TOP_ALPHA_AT_EDGE + (TOP_ALPHA_AT_HOLD - TOP_ALPHA_AT_EDGE) * y / hold
        } else {
            TOP_ALPHA_AT_HOLD * (TOP_HEIGHT_PX - y) / (TOP_HEIGHT_PX - hold)
        }
    }
}
