package com.rysal.happytv.data.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import kotlinx.serialization.serializer
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.lang.reflect.Type
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Xtream Codes v2 Player API as a Retrofit interface.
 *
 * **Why every method takes an `@Url`.** Retrofit's base URL is fixed for the
 * lifetime of the client (`http://localhost/` — see [buildXtreamApi]), and
 * the panel's real origin (`http://host:port`) is passed per call — which is
 * also why the annotations are bare `@GET` (Retrofit rejects a non-empty
 * path next to `@Url`); the endpoint path (`player_api.php` / `xmltv.php`)
 * is part of the URL the repository passes. One Retrofit instance therefore
 * serves every panel, and logging in with a different provider never rebuilds
 * the client ("no per-user base-URL juggling"). `Graph.json` + the official
 * kotlinx.serialization converter do the DTO work; `ignoreUnknownKeys`/
 * `coerceInputValues` tolerate panels that add, drop or mistype fields.
 *
 * **Why `action` is a defaulted parameter.** Every endpoint is the *same*
 * URL (`GET /player_api.php`) distinguished only by its `action` query value
 * (protocol §2, §7). Kotlin default arguments let each method carry its own
 * action as a self-documenting constant while keeping call sites at the
 * spec's shape; pass it explicitly only in tests.
 *
 * **Why credentials are `@Query` parameters.** That *is* the protocol: Xtream
 * has no header auth, no session cookie and no token exchange — every request
 * carries `username`/`password` in the query string (protocol.md §1.2). There
 * is deliberately no `AuthInterceptor`.
 *
 * **Return types.** Lists of `@Serializable` DTOs; detail endpoints return
 * their DTO; `xmltv.php` returns a raw [ResponseBody] because the XMLTV file
 * is megabytes of XML we stream through an `XmlPullParser` instead of
 * deserializing (`@Streaming` keeps it off the heap).
 *
 * Every method is `suspend` — OkHttp executes on Retrofit's own dispatcher.
 */
interface XtreamApi {

    /** `GET /player_api.php?username=U&password=P` — the login/account probe (no `action`). */
    @GET
    suspend fun login(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
    ): LoginResponse

    @GET
    suspend fun getLiveCategories(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = ACTION_GET_LIVE_CATEGORIES,
    ): List<CategoryDto>

    @Streaming
    @GET
    suspend fun getLiveStreams(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("category_id") categoryId: String? = null,
        @Query("action") action: String = ACTION_GET_LIVE_STREAMS,
    ): ResponseBody

    @GET
    suspend fun getVodCategories(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = ACTION_GET_VOD_CATEGORIES,
    ): List<CategoryDto>

    @Streaming
    @GET
    suspend fun getVodStreams(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("category_id") categoryId: String? = null,
        @Query("action") action: String = ACTION_GET_VOD_STREAMS,
    ): ResponseBody

    @GET
    suspend fun getSeriesCategories(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = ACTION_GET_SERIES_CATEGORIES,
    ): List<CategoryDto>

    @Streaming
    @GET
    suspend fun getSeries(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("category_id") categoryId: String? = null,
        @Query("action") action: String = ACTION_GET_SERIES,
    ): ResponseBody

    /** Single-movie metadata: `{ info, movie_data }` (protocol §2.5). */
    @GET
    suspend fun getVodInfo(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("vod_id") vodId: String,
        @Query("action") action: String = ACTION_GET_VOD_INFO,
    ): VodInfoDto

    /**
     * Full series detail: `{ seasons, info, episodes{season:[…]} }` (protocol
     * §2.8). `episodes` is keyed by season number **as a string** — see
     * [SeriesInfoDto.episodesBySeason].
     */
    @GET
    suspend fun getSeriesInfo(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("series_id") seriesId: String,
        @Query("action") action: String = ACTION_GET_SERIES_INFO,
    ): SeriesInfoDto

    /** Now/next listings for one live stream; `limit` defaults to 4 (protocol §2.9). */
    @GET
    suspend fun getShortEpg(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("stream_id") streamId: String,
        @Query("limit") limit: Int = 4,
        @Query("action") action: String = ACTION_GET_SHORT_EPG,
    ): EpgListingsResponseDto

    /**
     * Legacy full-EPG table for one stream (protocol §2.10). Same response
     * shape as [getShortEpg] on modern panels; on old ones it degrades to an
     * empty list rather than a crash — see [EpgListingsResponseDto].
     */
    @GET
    suspend fun getSimpleDataTable(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("stream_id") streamId: String,
        @Query("action") action: String = ACTION_GET_SIMPLE_DATA_TABLE,
    ): EpgListingsResponseDto

    /**
     * `GET /xmltv.php` — the full XMLTV guide as raw bytes. Not JSON, never
     * deserialized here: the caller streams it through an `XmlPullParser`.
     * OkHttp negotiates gzip transparently (protocol.md §9 note 9).
     */
    @Streaming
    @GET
    suspend fun getXmlTv(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
    ): ResponseBody

    /**
     * The `action=` values that turn `player_api.php` into eleven endpoints
     * (protocol §7). `login` deliberately has none — no action *is* the login
     * probe.
     */
    companion object {
        const val ACTION_GET_LIVE_CATEGORIES = "get_live_categories"
        const val ACTION_GET_LIVE_STREAMS = "get_live_streams"
        const val ACTION_GET_VOD_CATEGORIES = "get_vod_categories"
        const val ACTION_GET_VOD_STREAMS = "get_vod_streams"
        const val ACTION_GET_SERIES_CATEGORIES = "get_series_categories"
        const val ACTION_GET_SERIES = "get_series"
        const val ACTION_GET_VOD_INFO = "get_vod_info"
        const val ACTION_GET_SERIES_INFO = "get_series_info"
        const val ACTION_GET_SHORT_EPG = "get_short_epg"
        const val ACTION_GET_SIMPLE_DATA_TABLE = "get_simple_data_table"
    }
}

/**
 * Base URL Retrofit is built with. It is never dialed: every method above
 * carries an absolute `@Url`, so this only has to be syntactically valid and
 * end in `/`. (`localhost` is as good a placeholder as any — the real origin
 * arrives per call.)
 */
const val XTREAM_PLACEHOLDER_BASE_URL = "http://localhost/"

/**
 * The one Retrofit instance for the whole app, built from the shared OkHttp
 * client (15s connect / 30s read+write, no global retry — see
 * [com.rysal.happytv.data.Graph.okHttpClient]) and the shared
 * [com.rysal.happytv.data.Graph.json]. Registered once by `Graph` at startup
 * through `installXtreamApi { … }`.
 */
@OptIn(ExperimentalSerializationApi::class)
fun buildXtreamApi(client: OkHttpClient, json: Json): XtreamApi = Retrofit.Builder()
    .client(client)
    .addConverterFactory(StreamingListConverterFactory(json))
    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
    .baseUrl(XTREAM_PLACEHOLDER_BASE_URL)
    .build()
    .create(XtreamApi::class.java)

/**
 * Decodes `List<…>` responses straight from the byte stream (#128).
 *
 * The official kotlinx converter calls `ResponseBody.string()` first. The
 * movie catalog is ~40M characters, and that String alone is ~81 MB against a
 * 128 MB heap growth limit on a Chromecast HD, so the refresh died with an
 * OutOfMemoryError. Only list endpoints are claimed here; everything else
 * falls through to the official converter. Pair with `@Streaming` so Retrofit
 * does not buffer the body first either.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class StreamingListConverterFactory(private val json: Json) : Converter.Factory() {
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *>? {
        val raw = (type as? java.lang.reflect.ParameterizedType)?.rawType as? Class<*>
        if (raw == null || !List::class.java.isAssignableFrom(raw)) return null
        val serializer = json.serializersModule.serializer(type)
        return Converter<ResponseBody, Any?> { body ->
            body.use { json.decodeFromStream(serializer, it.byteStream()) }
        }
    }
}
