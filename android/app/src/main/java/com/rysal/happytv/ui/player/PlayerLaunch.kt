package com.rysal.happytv.ui.player

import android.content.Intent
import com.rysal.happytv.data.entity.StreamType

/**
 * The intent contract for [PlayerActivity] — every lane that can start playback
 * (Live TV channel rows, movie details' **Play**, the guide's **Watch from the
 * start**) builds one of these.
 *
 * Connection fields ([host]…[password]) are optional here: the guide and the
 * browse screens pass them from the credentials they already hold, but a
 * caller that only knows the stream may omit them and the player falls back to
 * the stored login ([com.rysal.happytv.ui.player.PlayerDependencies.PlayerRepository.connection]).
 *
 * Replay (design spec §"Player" per-mode table): when the guide's **Watch from the
 * start** fires, [isReplay] is true and [startUtcMillis] + [durationMinutes]
 * describe the program; the player builds the `/timeshift/…` URL and opens in
 * Replay mode — but only for channels with `tvArchive == 1`, else the user
 * gets "Replay isn't supported for this channel." and stays put.
 */
data class PlayerLaunch(
    val streamType: StreamType,
    val streamId: String,
    /** `mp4`/`ts`/`m3u8` from the launching row, when it knows one. */
    val extension: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val useTls: Boolean? = null,
    val username: String? = null,
    val password: String? = null,
    /** True = "Watch from the start": open [startUtcMillis]… via timeshift. */
    val isReplay: Boolean = false,
    /** Program start, epoch millis **UTC** (EPG `start_timestamp`). */
    val startUtcMillis: Long = 0L,
    /** Program length in whole minutes — the timeshift window. */
    val durationMinutes: Int = 0,
    /** Movie extras for the top block ("2023 · PG-13"); optional. */
    val year: String? = null,
    val mpaa: String? = null,
    /** Where to start a movie or episode (Resume, #78); 0 = the beginning. */
    val startPositionMillis: Long = 0L,
    /** A show's episode to play (the one in progress); null = the first episode. */
    val episodeId: String? = null,
    /** Start over (#78): the saved row for this title is deleted as soon as playback starts. */
    val startOver: Boolean = false,
) {
    /** True when every connection field is present (no credentials fallback needed). */
    val hasExplicitConnection: Boolean
        get() = !host.isNullOrBlank() && port in 1..65535 && useTls != null &&
            !username.isNullOrBlank() && password != null

    companion object {
        const val EXTRA_STREAM_TYPE = "stream_type"
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_EXTENSION = "extension"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USE_TLS = "use_tls"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_IS_REPLAY = "is_replay"
        const val EXTRA_START_UTC_MILLIS = "start_utc_millis"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val EXTRA_YEAR = "year"
        const val EXTRA_MPAA = "mpaa"
        const val EXTRA_START_POSITION_MILLIS = "start_position_millis"
        const val EXTRA_EPISODE_ID = "episode_id"
        const val EXTRA_START_OVER = "start_over"

        /**
         * Parses an incoming intent. Returns null when the launch is unusable
         * (no stream id, or a stream type the enum doesn't know) — the caller
         * should finish instead of showing a broken player.
         */
        fun from(intent: Intent): PlayerLaunch? {
            val streamId = intent.getStringExtra(EXTRA_STREAM_ID)?.trim().orEmpty()
            if (streamId.isEmpty()) return null
            val rawType = intent.getStringExtra(EXTRA_STREAM_TYPE).orEmpty()
            val streamType = StreamType.entries.firstOrNull { it.name.equals(rawType, ignoreCase = true) }
                ?: return null
            return PlayerLaunch(
                streamType = streamType,
                streamId = streamId,
                extension = intent.getStringExtra(EXTRA_EXTENSION),
                host = intent.getStringExtra(EXTRA_HOST),
                port = intent.getIntExtra(EXTRA_PORT, -1).takeIf { it > 0 },
                useTls = if (intent.hasExtra(EXTRA_USE_TLS)) intent.getBooleanExtra(EXTRA_USE_TLS, false) else null,
                username = intent.getStringExtra(EXTRA_USERNAME),
                password = intent.getStringExtra(EXTRA_PASSWORD),
                isReplay = intent.getBooleanExtra(EXTRA_IS_REPLAY, false),
                startUtcMillis = intent.getLongExtra(EXTRA_START_UTC_MILLIS, 0L),
                durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 0),
                year = intent.getStringExtra(EXTRA_YEAR),
                mpaa = intent.getStringExtra(EXTRA_MPAA),
                startPositionMillis = intent.getLongExtra(EXTRA_START_POSITION_MILLIS, 0L).coerceAtLeast(0L),
                episodeId = intent.getStringExtra(EXTRA_EPISODE_ID)?.takeIf { it.isNotBlank() },
                startOver = intent.getBooleanExtra(EXTRA_START_OVER, false),
            )
        }

        /** The complement of [from] — used by every launching screen. */
        fun Intent.put(launch: PlayerLaunch): Intent = apply {
            putExtra(EXTRA_STREAM_TYPE, launch.streamType.name)
            putExtra(EXTRA_STREAM_ID, launch.streamId)
            launch.extension?.let { putExtra(EXTRA_EXTENSION, it) }
            launch.host?.let { putExtra(EXTRA_HOST, it) }
            launch.port?.let { putExtra(EXTRA_PORT, it) }
            launch.useTls?.let { putExtra(EXTRA_USE_TLS, it) }
            launch.username?.let { putExtra(EXTRA_USERNAME, it) }
            launch.password?.let { putExtra(EXTRA_PASSWORD, it) }
            if (launch.isReplay) {
                putExtra(EXTRA_IS_REPLAY, true)
                putExtra(EXTRA_START_UTC_MILLIS, launch.startUtcMillis)
                putExtra(EXTRA_DURATION_MINUTES, launch.durationMinutes)
            }
            launch.year?.let { putExtra(EXTRA_YEAR, it) }
            launch.mpaa?.let { putExtra(EXTRA_MPAA, it) }
            if (launch.startPositionMillis > 0L) putExtra(EXTRA_START_POSITION_MILLIS, launch.startPositionMillis)
            launch.episodeId?.let { putExtra(EXTRA_EPISODE_ID, it) }
            if (launch.startOver) putExtra(EXTRA_START_OVER, true)
        }

        /** A live channel, plain (or with the connection the caller already has). */
        fun live(
            streamId: String,
            extension: String? = null,
            host: String? = null,
            port: Int? = null,
            useTls: Boolean? = null,
            username: String? = null,
            password: String? = null,
        ): PlayerLaunch = PlayerLaunch(
            streamType = StreamType.LIVE,
            streamId = streamId,
            extension = extension,
            host = host,
            port = port,
            useTls = useTls,
            username = username,
            password = password,
        )

        /** A movie, with the two top-block extras when the details screen has them. */
        fun movie(
            streamId: String,
            extension: String? = null,
            year: String? = null,
            mpaa: String? = null,
            host: String? = null,
            port: Int? = null,
            useTls: Boolean? = null,
            username: String? = null,
            password: String? = null,
        ): PlayerLaunch = PlayerLaunch(
            streamType = StreamType.VOD,
            streamId = streamId,
            extension = extension,
            host = host,
            port = port,
            useTls = useTls,
            username = username,
            password = password,
            year = year,
            mpaa = mpaa,
        )

        /** A show — v1 plays the first episode (series drill-down is v1.1). */
        fun series(streamId: String): PlayerLaunch =
            PlayerLaunch(streamType = StreamType.SERIES, streamId = streamId)

        /**
         * "Watch from the start" — a replay launch for one EPG program on one
         * channel. [startUtcMillis] is the program start, [durationMinutes] its
         * length.
         */
        fun replay(
            streamId: String,
            startUtcMillis: Long,
            durationMinutes: Int,
            host: String? = null,
            port: Int? = null,
            useTls: Boolean? = null,
            username: String? = null,
            password: String? = null,
        ): PlayerLaunch = PlayerLaunch(
            streamType = StreamType.LIVE,
            streamId = streamId,
            host = host,
            port = port,
            useTls = useTls,
            username = username,
            password = password,
            isReplay = true,
            startUtcMillis = startUtcMillis,
            durationMinutes = durationMinutes,
        )
    }
}
