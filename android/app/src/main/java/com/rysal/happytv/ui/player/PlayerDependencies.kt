package com.rysal.happytv.ui.player

import com.rysal.happytv.ui.catalog.cleanEpisodeTitle
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.dao.EpgProgramDao
import com.rysal.happytv.data.dao.FavoriteDao
import com.rysal.happytv.data.dao.LiveStreamDao
import com.rysal.happytv.data.dao.SeriesStreamDao
import com.rysal.happytv.data.dao.VodStreamDao
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.entity.WatchProgress
import com.rysal.happytv.data.dao.WatchProgressDao
import com.rysal.happytv.data.network.SeriesInfoDto
import com.rysal.happytv.data.network.UserInfoDto
import com.rysal.happytv.data.network.XtreamRepository
import com.rysal.happytv.data.network.episodesBySeasonNumber
import com.rysal.happytv.data.network.panelOrigin
import com.rysal.happytv.security.SecuredCredentialsStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Everything the player needs from the rest of the app, behind one seam —
 * the pattern the dashboard established with `RefreshGateway`: the ViewModel
 * is constructed against [PlayerRepository] + [PlayerHandle], tests hand it
 * recording fakes, and [GraphPlayerRepository] is the production wiring.
 */
interface PlayerRepository {

    /** The stored login — null when the user isn't signed in. */
    suspend fun connection(): PlayerConnection?

    /** Channel row by id; null = not in the current catalog. */
    suspend fun liveStream(id: String): LiveStream?

    /** Movie row by id. */
    suspend fun vodStream(id: String): VodStream?

    /**
     * The first playable episode of a series — v1 placeholder for the
     * episode drill-down (show details now pick a season and episode, #74).
     */
    suspend fun firstEpisode(seriesId: String): PlayerEpisode?

    /** [episodeId] of the series, or its first episode when null or not found (#78 resume). */
    suspend fun episode(seriesId: String, episodeId: String?): PlayerEpisode? = firstEpisode(seriesId)

    /** Saves watch progress (#78). Default: nothing is kept (tests that do not care). */
    suspend fun saveProgress(progress: WatchProgress) {}

    /** Records the "up next" episode of a show whose episode just finished, unless it already has a row (#78). */
    suspend fun saveUpNext(progress: WatchProgress) {}

    /** The saved progress row of a movie or episode, or null (the next episode may resume, #84). */
    suspend fun progress(streamType: StreamType, streamId: String): WatchProgress? = null

    /** Deletes a movie or episode's progress row, finished mark included (Start over, #78). */
    suspend fun clearProgress(streamType: StreamType, streamId: String) {}

    /**
     * `user_info.allowed_output_formats` from the login response cached at
     * refresh time (`AppSettings.userInfo`); null when not cached yet.
     */
    suspend fun allowedOutputFormats(): List<String>?

    /** Now/next for a channel's `epg_channel_id`, or null without guide data. */
    suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext?

    suspend fun isFavorite(streamId: String, streamType: StreamType): Boolean

    suspend fun toggleFavorite(streamId: String, streamType: StreamType)
}

/** One resolved login (the panel origin is derived, never stored twice). */
data class PlayerConnection(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val username: String,
    val password: String,
) {
    /** `http://panel.example:8080` — same defensive parsing as login. */
    val origin: String get() = panelOrigin(host, port, useTls)
}

/** A series episode resolved to something the `/series/` URL can play. */
data class PlayerEpisode(
    /** `episodes[season][i].id` — **not** the series id (protocol §3.3). */
    val id: String,
    val containerExtension: String?,
    val title: String?,
    val seriesName: String?,
    /** Season and episode numbers, for "S2 E3" and per-episode progress (#78). */
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** The episode after this one (next in the same or the following season), if there is one: "Up next". */
    val nextId: String? = null,
    val nextSeasonNumber: Int? = null,
    val nextEpisodeNumber: Int? = null,
    /** The next episode's title with the show name and S/E code stripped (#84), if it has one. */
    val nextTitle: String? = null,
)

/** One EPG program, trimmed to what the player shows. */
data class PlayerProgram(
    val title: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    /** The guide's description (#131); null when the guide has none. Same text as the guide's details panel. */
    val description: String? = null,
)

/** Now/next pair for the Now / Next card and the live progress bounds. */
data class PlayerNowNext(val now: PlayerProgram?, val next: PlayerProgram?)

/**
 * The playback surface the ViewModel drives. The activity owns the ExoPlayer
 * (player attachment is lifecycle-sensitive, not recomposition-sensitive) and
 * implements this; tests use a recording fake to assert D-pad behaviour.
 */
interface PlayerHandle {
    /** OK on the control row / any OK keypress while watching. */
    fun togglePlayPause()

    /** Play (true) or Pause (false) keys (#136): sets the intent rather than flipping it. Default no-op for fakes. */
    fun setPlayWhenReady(play: Boolean) {}

    /** ±10 s arrow keys while the controls are hidden (movies only). */
    fun seekBy(deltaMillis: Long)

    /** Scrubber seek (#102): jump to an absolute position. Default no-op for fakes. */
    fun seekTo(positionMillis: Long) = Unit

    /** Re-`prepare()` after a stream error (the retry path). */
    fun prepare()
}

/**
 * Production [PlayerRepository]: Room for catalog rows and favorites, the
 * XMLTV cache for now/next, DataStore for the cached `user_info`, the
 * encrypted store for the login, and a one-shot `get_series_info` for the
 * first-episode placeholder.
 */
class GraphPlayerRepository(
    private val credentialsStore: SecuredCredentialsStore,
    private val liveStreamDao: LiveStreamDao,
    private val vodStreamDao: VodStreamDao,
    private val seriesStreamDao: SeriesStreamDao,
    private val favoriteDao: FavoriteDao,
    private val epgProgramDao: EpgProgramDao,
    private val userInfoJson: suspend () -> ByteArray?,
    private val json: Json,
    private val seriesInfo: suspend (PlayerConnection, String) -> SeriesInfoDto?,
    private val watchProgressDao: WatchProgressDao? = null,
) : PlayerRepository {

    override suspend fun connection(): PlayerConnection? {
        val creds = credentialsStore.current() ?: return null
        return PlayerConnection(
            host = creds.host,
            port = creds.port,
            useTls = creds.useTls,
            username = creds.username,
            password = creds.password,
        )
    }

    override suspend fun liveStream(id: String): LiveStream? = liveStreamDao.getById(id)

    override suspend fun vodStream(id: String): VodStream? = vodStreamDao.getById(id)

    override suspend fun firstEpisode(seriesId: String): PlayerEpisode? = episode(seriesId, null)

    override suspend fun episode(seriesId: String, episodeId: String?): PlayerEpisode? {
        val series = seriesStreamDao.getById(seriesId) ?: return null
        val info = seriesInfo(
            connection() ?: return null,
            seriesId,
        ) ?: return null
        return episodeOf(series.name, info, episodeId)
    }

    override suspend fun saveProgress(progress: WatchProgress) {
        watchProgressDao?.upsert(progress)
    }

    override suspend fun saveUpNext(progress: WatchProgress) {
        val dao = watchProgressDao ?: return
        // An episode that already has a row (in progress, or watched before) keeps it.
        if (dao.getEpisode(progress.streamId) == null) dao.upsert(progress)
    }

    override suspend fun progress(streamType: StreamType, streamId: String): WatchProgress? =
        if (streamType == StreamType.SERIES) watchProgressDao?.getEpisode(streamId) else watchProgressDao?.getMovie(streamId)

    override suspend fun clearProgress(streamType: StreamType, streamId: String) {
        when (streamType) {
            StreamType.VOD -> watchProgressDao?.deleteMovie(streamId)
            StreamType.SERIES -> watchProgressDao?.deleteEpisode(streamId)
            StreamType.LIVE -> Unit
        }
    }

    override suspend fun allowedOutputFormats(): List<String>? {
        val bytes = userInfoJson() ?: return null
        return runCatching {
            json.decodeFromString<UserInfoDto>(bytes.decodeToString()).allowed_output_formats
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
        }.getOrNull()
    }

    override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? {
        // A 36 h window: whatever is airing plus everything scheduled after it.
        val programs = epgProgramDao.getBetween(
            channelId = epgChannelId,
            fromUtcMillis = nowUtcMillis - 6L * 60L * 60L * 1000L,
            toUtcMillis = nowUtcMillis + 30L * 60L * 60L * 1000L,
        )
        if (programs.isEmpty()) return null
        val now = programs.firstOrNull {
            it.startUtcMillis <= nowUtcMillis && it.endUtcMillis > nowUtcMillis
        }
        val nextStartsAfter = now?.endUtcMillis ?: nowUtcMillis
        val next = programs.firstOrNull {
            it.startUtcMillis >= nextStartsAfter && it.endUtcMillis > nowUtcMillis
        }
        if (now == null && next == null) return null
        return PlayerNowNext(
            now = now?.let { PlayerProgram(it.title, it.startUtcMillis, it.endUtcMillis, it.description) },
            next = next?.let { PlayerProgram(it.title, it.startUtcMillis, it.endUtcMillis, it.description) },
        )
    }

    override suspend fun isFavorite(streamId: String, streamType: StreamType): Boolean =
        favoriteDao.isFavorite(streamId, streamType)

    override suspend fun toggleFavorite(streamId: String, streamType: StreamType) {
        favoriteDao.toggle(streamId, streamType)
    }

    companion object {
        /**
         * Season-keyed flattening per protocol 7: seasons ordered numerically, episodes by their
         * `episode_num`. Returns the episode with [episodeId] when asked for and found, else the
         * first (what v1 plays).
         */
        internal fun episodeOf(seriesName: String, info: SeriesInfoDto, episodeId: String? = null): PlayerEpisode? {
            // The same order as the episode list on the details page (#74): seasons by number with
            // Specials (season 0) last, episodes by number (position when the provider sent none).
            val bySeason = info.episodesBySeasonNumber().entries.sortedWith(compareBy({ it.key == 0 }, { it.key }))
            val all = bySeason.flatMap { (season, episodes) ->
                episodes.withIndex()
                    .sortedBy { (i, e) -> com.rysal.happytv.data.network.JsonCoerce.toIntOrNull(e.episode_num) ?: (i + 1) }
                    .map { season to it.value }
            }
            val picked = episodeId?.let { id -> all.firstOrNull { it.second.id == id } }
                ?: all.firstOrNull()
                ?: return null
            val (season, episode) = picked
            val next = all.getOrNull(all.indexOf(picked) + 1)
            if (episode.id.isBlank()) return null
            return PlayerEpisode(
                id = episode.id,
                containerExtension = episode.container_extension,
                title = episode.title,
                seriesName = info.info?.name ?: seriesName,
                seasonNumber = season,
                episodeNumber = com.rysal.happytv.data.network.JsonCoerce.toIntOrNull(episode.episode_num),
                nextId = next?.second?.id?.takeIf { it.isNotBlank() },
                nextSeasonNumber = next?.first,
                nextEpisodeNumber = next?.second?.let { com.rysal.happytv.data.network.JsonCoerce.toIntOrNull(it.episode_num) },
                nextTitle = next?.second?.let { n ->
                    val number = com.rysal.happytv.data.network.JsonCoerce.toIntOrNull(n.episode_num) ?: 0
                    // Cleaned the same way as the episode list on the details page (#74).
                    cleanEpisodeTitle(n.title, listOf(seriesName, info.info?.name), number)
                        .takeIf { number > 0 || !it.startsWith("Episode ") }
                },
            )
        }

        internal fun firstEpisodeOf(seriesName: String, info: SeriesInfoDto): PlayerEpisode? =
            episodeOf(seriesName, info, null)
    }
}

/** One production wiring, assembled from [Graph] like every other screen. */
fun Graph.playerRepository(): PlayerRepository = GraphPlayerRepository(
    credentialsStore = securedCredentials,
    liveStreamDao = database.liveStreamDao(),
    vodStreamDao = database.vodStreamDao(),
    seriesStreamDao = database.seriesStreamDao(),
    favoriteDao = database.favoriteDao(),
    epgProgramDao = database.epgProgramDao(),
    userInfoJson = { settings.settings.first().userInfo },
    json = json,
    seriesInfo = { connection, seriesId ->
        XtreamRepository(
            host = connection.host,
            port = connection.port,
            useTls = connection.useTls,
            username = connection.username,
            password = connection.password,
        ).getSeriesInfo(seriesId)
    },
    watchProgressDao = database.watchProgressDao(),
)
