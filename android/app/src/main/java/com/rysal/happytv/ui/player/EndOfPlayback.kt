package com.rysal.happytv.ui.player

/**
 * What the player shows when an episode reaches its end (#84).
 *
 * `None` -> playing. `Countdown` -> "Next episode" card with a live count, the next episode
 * starts at zero. `Stopped` -> the countdown was cancelled (Cancel or Back): the card stays on
 * the ended frame and offers Play next episode / Back to details; a further Back leaves.
 * `ReturnToDetails` -> last episode of the show, the activity finishes.
 */
sealed interface EndOfPlayback {
    data object None : EndOfPlayback
    data class Countdown(val label: String, val secondsLeft: Int) : EndOfPlayback
    data class Stopped(val label: String) : EndOfPlayback
    data object ReturnToDetails : EndOfPlayback
}

/** The episode that follows the one playing, resolved when the episode loads. */
internal data class NextEpisode(val id: String, val label: String)

/** "S2 E4 · The Return": unknown parts are left out ("E4 · …", just the title, or "Next episode"). */
internal fun nextEpisodeLabel(season: Int?, episode: Int?, title: String?): String {
    val code = listOfNotNull(season?.let { "S$it" }, episode?.let { "E$it" }).joinToString(" ")
    val name = title?.trim().orEmpty()
    return when {
        code.isNotEmpty() && name.isNotEmpty() -> "$code · $name"
        code.isNotEmpty() -> code
        name.isNotEmpty() -> name
        else -> "Next episode"
    }
}

internal const val NEXT_EPISODE_COUNTDOWN_SECONDS = 10
