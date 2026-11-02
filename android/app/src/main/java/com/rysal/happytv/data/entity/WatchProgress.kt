package com.rysal.happytv.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * How far into a movie or an episode someone has watched (#78): the one row behind "Resume from
 * 41:20", "Start over", and the Continue watching row in Movies and Shows.
 *
 * One row per movie ([streamType] [StreamType.VOD], [streamId] the movie id) or per episode
 * ([StreamType.SERIES], [streamId] the **episode** id, which is not the show's id).
 * Episode rows also carry [seriesId], [seasonNumber] and [episodeNumber], so a show's details
 * can mark each episode's progress and say "Resume S2 E3" without another schema change (#74).
 * Movie rows leave the three null.
 *
 * A finished movie or episode keeps its row with [finishedAt] set (see [WatchProgressPolicy]), so
 * #74 can mark episodes watched. An "up next" episode is a row at position 0 and no length.
 */
@Entity(
    tableName = "watch_progress",
    primaryKeys = ["streamType", "streamId"],
    indices = [Index("updatedUtcMillis"), Index("seriesId")],
)
data class WatchProgress(
    val streamType: StreamType,
    val streamId: String,
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val positionMillis: Long,
    /** 0 when the player did not know the length. */
    val durationMillis: Long,
    val updatedUtcMillis: Long,
    /**
     * When it was finished (min(5 min, 10%) or less left, or past 95%), else null. A finished row keeps its
     * position; Continue watching and Resume ignore it, and #74 marks the episode watched.
     */
    val finishedAt: Long? = null,
) {
    /** 0..1 of the way through, or null when the length is unknown. */
    val fraction: Float?
        get() = if (durationMillis > 0L) (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else null
}

/** The write rules for [WatchProgress] (#78), as pure functions so they are unit tested directly. */
object WatchProgressPolicy {
    /** Under this far in, nothing is saved: a channel-surf glance is not "watching". */
    const val MIN_SAVE_MILLIS = 60_000L

    /** With this much or less left, a long title counts as finished (a short one uses [FINISHED_REMAINING_FRACTION]). */
    const val FINISHED_REMAINING_MILLIS = 5L * 60_000L

    /** With this share of the length or less left, the title counts as finished: the credits of a 22-minute episode, not minute 17. */
    const val FINISHED_REMAINING_FRACTION = 0.10

    /** Past this share of the way through, the title counts as finished. */
    const val FINISHED_FRACTION = 0.95

    /** Save every this often while playing. */
    const val SAVE_INTERVAL_MILLIS = 30_000L

    enum class Decision { IGNORE, SAVE, FINISHED }

    fun decide(positionMillis: Long, durationMillis: Long): Decision = when {
        positionMillis < MIN_SAVE_MILLIS -> Decision.IGNORE
        isFinished(positionMillis, durationMillis) -> Decision.FINISHED
        else -> Decision.SAVE
    }

    /**
     * Finished when no more than min(5 minutes, 10% of the length) is left, or past 95%. The
     * 10% bound keeps a 22-minute episode from being marked watched with 5 minutes to go.
     */
    fun isFinished(positionMillis: Long, durationMillis: Long): Boolean {
        if (durationMillis <= 0L) return false
        val allowedRemaining = minOf(FINISHED_REMAINING_MILLIS, (durationMillis * FINISHED_REMAINING_FRACTION).toLong())
        return durationMillis - positionMillis <= allowedRemaining ||
            positionMillis > durationMillis * FINISHED_FRACTION
    }

    /** "41:20", or "1:05:09" past the hour. */
    fun clock(millis: Long): String {
        val total = (millis / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
