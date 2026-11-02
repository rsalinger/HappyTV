package com.rysal.happytv.data.network

import android.util.Log
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.SeriesStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import okhttp3.ResponseBody

/**
 * One authenticated session against one Xtream panel.
 *
 * Construction is pure: it resolves the panel origin from the (host, port,
 * useTls) triple the user typed, logs the cleartext warning once, and does no
 * I/O. Every fetch method is a suspend fun that returns either Room entities
 * (the six catalog lists — already coerced/mapped, ready for the refresh
 * coordinator to persist) or their raw DTO (detail/EPG endpoints, where the
 * caller needs fields the entities don't store, e.g. `has_archive`).
 *
 * [refreshState] is this session's refresh progress — `RefreshCoordinator`
 * drives it with the session-wide aggregate (how many of the 3 tiles have
 * finished) while it drives the per-tile states in [RefreshCoordinator.progress].
 * Either flow can be collected; they are two zoom levels of the same run.
 *
 * No retry, no caching, no deduplication: IPTV panels flake, and retry policy
 * belongs to the caller (RefreshCoordinator per tile, the player per stream).
 */
class XtreamRepository(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val username: String,
    val password: String,
    private val api: XtreamApi = Graph.xtreamApi(XtreamApi::class.java),
    private val json: Json = Graph.json,
) {

    /** e.g. `http://panel.example:8080` — panel API and streams share it. */
    val origin: String = panelOrigin(host, port, useTls)

    /** `GET {origin}/player_api.php` target, query params added per call. */
    val playerApiUrl: String = "$origin/player_api.php"

    /** `GET {origin}/xmltv.php` target — the full XMLTV guide. */
    val xmlTvUrl: String = "$origin/xmltv.php"

    private val _refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()

    init {
        warnIfCleartext()
    }

    // ------------------------------------------------------------------ auth

    /**
     * `player_api.php?username&password` with no action. Returns the raw
     * response — inspect [LoginResponse.isAuthenticated] (and on failure
     * `user_info.status`/`message`) rather than treating HTTP 200 as success:
     * panels answer failed logins with 200 + `auth: 0` (protocol §5.2).
     */
    suspend fun login(): LoginResponse = api.login(playerApiUrl, username, password)

    // -------------------------------------------------------------- catalogs

    suspend fun getLiveCategories(): List<Category> =
        api.getLiveCategories(playerApiUrl, username, password).toEntities(StreamType.LIVE)

    /** @param categoryId pass-through `category_id` filter; null = full list. */
    suspend fun getLiveStreams(categoryId: String? = null): List<LiveStream> =
        withLiveStreams(categoryId) { it.toList() }

    /**
     * Runs [block] over the live channels decoded one at a time straight from the byte stream
     * and mapped to entities as they go (#130): no DTO list and no entity list ever exists. The
     * sequence is single-use and valid only inside [block]; the body is closed afterwards.
     * A body that is not a JSON array (a provider error object) throws on first use.
     */
    suspend fun <R> withLiveStreams(
        categoryId: String? = null,
        block: suspend (Sequence<LiveStream>) -> R,
    ): R = api.getLiveStreams(playerApiUrl, username, password, categoryId).use { body ->
        block(decodeRows<LiveStreamDto>(body).mapNotNull { it.toEntity() })
    }

    suspend fun getVodCategories(): List<Category> =
        api.getVodCategories(playerApiUrl, username, password).toEntities(StreamType.VOD)

    suspend fun getVodStreams(categoryId: String? = null): List<VodStream> =
        withVodStreams(categoryId) { it.toList() }

    /** See [withLiveStreams]. */
    suspend fun <R> withVodStreams(
        categoryId: String? = null,
        block: suspend (Sequence<VodStream>) -> R,
    ): R = api.getVodStreams(playerApiUrl, username, password, categoryId).use { body ->
        block(decodeRows<VodStreamDto>(body).mapNotNull { it.toEntity() })
    }

    suspend fun getSeriesCategories(): List<Category> =
        api.getSeriesCategories(playerApiUrl, username, password).toEntities(StreamType.SERIES)

    suspend fun getSeries(categoryId: String? = null): List<SeriesStream> =
        withSeries(categoryId) { it.toList() }

    /** See [withLiveStreams]. */
    suspend fun <R> withSeries(
        categoryId: String? = null,
        block: suspend (Sequence<SeriesStream>) -> R,
    ): R = api.getSeries(playerApiUrl, username, password, categoryId).use { body ->
        block(decodeRows<SeriesStreamDto>(body).mapNotNull { it.toEntity() })
    }

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified D> decodeRows(body: ResponseBody): Sequence<D> =
        json.decodeToSequence<D>(body.byteStream(), DecodeSequenceMode.ARRAY_WRAPPED)

    /** Convenience for the refresh coordinator: categories for [type]. */
    suspend fun getCategories(type: StreamType): List<Category> = when (type) {
        StreamType.LIVE -> getLiveCategories()
        StreamType.VOD -> getVodCategories()
        StreamType.SERIES -> getSeriesCategories()
    }

    // ---------------------------------------------------------------- detail

    /** `{info, movie_data}` for one movie (protocol §2.5). */
    suspend fun getVodInfo(vodId: String): VodInfoDto =
        api.getVodInfo(playerApiUrl, username, password, vodId)

    /** `{seasons, info, episodes}` for one series — use [episodesBySeason] (protocol §2.8). */
    suspend fun getSeriesInfo(seriesId: String): SeriesInfoDto =
        api.getSeriesInfo(playerApiUrl, username, password, seriesId)

    // ------------------------------------------------------------------- EPG

    /** Now/next entries for one live stream ([limit] defaults to 4). */
    suspend fun getShortEpg(streamId: String, limit: Int = 4): List<EpgListingDto> =
        api.getShortEpg(playerApiUrl, username, password, streamId, limit).toEpgListings()

    /** The legacy full-EPG table for one stream; same entry shape as [getShortEpg]. */
    suspend fun getSimpleDataTable(streamId: String): List<EpgListingDto> =
        api.getSimpleDataTable(playerApiUrl, username, password, streamId).toEpgListings()

    /**
     * The full XMLTV guide as a streaming [ResponseBody] — hand it to an
     * `XmlPullParser` over `byteStream()`; the caller owns closing it. XMLTV
     * files run to tens of MB, which is why this never becomes a String.
     */
    suspend fun getXmlTv(): ResponseBody = api.getXmlTv(xmlTvUrl, username, password)

    // -------------------------------------------------------------- plumbing

    /**
     * Written by [RefreshCoordinator] while this session refreshes (3 = all
     * tiles done). Collected by the dashboard's aggregate view; per-tile UIs
     * should collect [RefreshCoordinator.progress] instead.
     */
    fun reportRefresh(state: RefreshState) {
        _refreshState.value = state
    }

    override fun toString(): String =
        "XtreamRepository(origin=$origin, username=$username)" // never the password

    private fun warnIfCleartext() {
        if (useTls) return
        if (cleartextWarned.compareAndSet(false, true)) {
            Log.w(
                TAG,
                "Talking to $origin over cleartext HTTP: both the Xtream API " +
                    "(username/password travel in the query string) and every " +
                    "stream are readable by anyone on the network. If your panel " +
                    "serves https, add the account again with TLS enabled.",
            )
        }
    }

    companion object {
        private const val TAG = "XtreamRepository"

        /** One warning per process, not one per repository instance. */
        private val cleartextWarned = AtomicBoolean(false)
    }
}

/**
 * `panel.example` + `8080` + no TLS → `http://panel.example:8080`.
 *
 * Defensive against how people actually type panel addresses (protocol §1.1,
 * §9 note 7):
 *  - a scheme in [host] wins over [useTls] — that is the user typing what
 *    their browser shows, and `https://` implies TLS;
 *  - a port inside [host] (`panel.example:25461`) wins over [port], because
 *    it is the more specific thing the user typed;
 *  - any path/query after the host is dropped — panels are origin-rooted;
 *  - an out-of-range or missing port falls back to the scheme default (URL
 *    without an explicit port).
 *
 * IPv6 literals (`[::1]`) are not supported — no panel runs on one, and the
 * user-facing input is a hostname field.
 */
fun panelOrigin(host: String, port: Int, useTls: Boolean): String {
    var hostname = host.trim()
    var tls = useTls
    var explicitPort = port
    when {
        hostname.startsWith("https://", ignoreCase = true) -> {
            tls = true
            hostname = hostname.substring("https://".length)
        }
        hostname.startsWith("http://", ignoreCase = true) -> {
            tls = false
            hostname = hostname.substring("http://".length)
        }
    }
    hostname = hostname.substringBefore('/').substringBefore('?').trim()
    val lastColon = hostname.lastIndexOf(':')
    if (lastColon >= 0) {
        val embeddedPort = hostname.substring(lastColon + 1)
        if (embeddedPort.isNotEmpty() && embeddedPort.all { it.isDigit() }) {
            explicitPort = embeddedPort.toIntOrNull() ?: explicitPort
            hostname = hostname.substring(0, lastColon)
        }
    }
    val scheme = if (tls) "https" else "http"
    val portSuffix = if (explicitPort in 1..65535) ":$explicitPort" else ""
    return "$scheme://$hostname$portSuffix"
}
