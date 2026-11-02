package com.rysal.happytv.data.network

import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.entity.StreamType
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Canned-wire tests: a MockWebServer panel answers with the JSON shapes real
 * Xtream panels send, and we verify (a) the request Retrofit actually dials
 * (path, query params, per-request @Url) and (b) the DTO → entity mapping,
 * including the coercion rules the DTOs exist for.
 *
 * The fixture deliberately mixes types — `stream_id` as number *and* string,
 * `tv_archive` as `1` and `"0"`, `""` for absent, plus fields we do not model
 * — because that is what protocol.md §9 and data-model.md §11 document.
 */
class XtreamApiMockWebServerTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: XtreamRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = buildXtreamApi(OkHttpClient(), Graph.json)
        repository = XtreamRepository(
            host = "localhost",
            port = server.port,
            useTls = false,
            username = "user",
            password = "pass",
            api = api,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------- streamed lists

    /** #128: list endpoints decode from the byte stream, never via ResponseBody.string(). */
    @Test
    fun vodStreams_largeSyntheticList_decodesFromStream() = runBlocking {
        val rows = 60_000
        val buffer = okio.Buffer()
        buffer.writeUtf8("[")
        for (i in 0 until rows) {
            if (i > 0) buffer.writeUtf8(",")
            buffer.writeUtf8(
                """{"num":$i,"name":"Movie $i","stream_type":"movie","stream_id":$i,""" +
                    """"stream_icon":"http://cdn.example/p/$i.jpg","added":"1609459200",""" +
                    """"category_id":"${i % 50}","container_extension":"mkv","rating":"7.5",""" +
                    """"new_panel_field":{"x":[1,2,3]}}""",
            )
        }
        buffer.writeUtf8("]")
        server.enqueue(MockResponse().setBody(buffer))

        val movies = repository.getVodStreams()

        assertEquals(rows, movies.size)
        assertEquals("Movie 59999", movies.last().name)
        assertEquals("59999", movies.last().id)
    }

    @Test
    fun streams_errorObjectInsteadOfList_failsWithoutHanging() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))
        val failure = runCatching { repository.getSeries() }.exceptionOrNull()
        assertTrue(failure != null)
    }

    // ------------------------------------------------------------ live rows

    @Test
    fun liveStreams_mixedWireShapes_mapToEntitiesAndDialTheRightUrl() = runBlocking {
        server.enqueue(MockResponse().setBody(LIVE_STREAMS_JSON))

        val streams = repository.getLiveStreams()

        // The wire request: same origin, /player_api.php, creds + action as query.
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/player_api.php", request.requestUrl!!.encodedPath)
        assertEquals("localhost", request.requestUrl!!.host)
        assertEquals(server.port, request.requestUrl!!.port)
        assertEquals("user", request.requestUrl!!.queryParameter("username"))
        assertEquals("pass", request.requestUrl!!.queryParameter("password"))
        assertEquals("get_live_streams", request.requestUrl!!.queryParameter("action"))
        // Unset optional filter must not appear at all.
        assertNull(request.requestUrl!!.queryParameter("category_id"))

        // Three wire rows: the id-less one is dropped, the rest are coerced.
        assertEquals(2, streams.size)
        val bbc = streams[0]
        assertEquals("123", bbc.id) // numeric 123 → "123"
        assertEquals("1", bbc.categoryId)
        assertEquals(1, bbc.num) // numeric 1 → Int?
        assertEquals("BBC One HD", bbc.name)
        assertEquals("http://cdn.example/icons/bbc1.png", bbc.streamIcon)
        assertEquals("bbc.one.uk", bbc.epgChannelId)
        assertTrue(bbc.tvArchive) // numeric 1 → true
        assertEquals(7, bbc.tvArchiveDuration)
        assertEquals(1609459200L, bbc.added) // quoted unix seconds
        assertNull(bbc.customSid) // "empty is normal" on the wire, means absent to us

        val cbc = streams[1]
        assertEquals("456", cbc.id) // quoted id
        assertEquals(2, cbc.num) // quoted "2" → Int?
        assertEquals("", cbc.categoryId) // kept; an orphan row, never rendered
        assertNull(cbc.streamIcon) // "" → null
        assertNull(cbc.added) // "" → null
        assertFalse(cbc.tvArchive) // "0" → false
        assertNull(cbc.tvArchiveDuration) // absent → null
    }

    @Test
    fun liveStreams_categoryFilter_isSentAsAQueryParameter() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        repository.getLiveStreams(categoryId = "42")

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("42", request.requestUrl!!.queryParameter("category_id"))
    }

    // ---------------------------------------------------------------- auth

    @Test
    fun login_successResponse_isAuthenticated() = runBlocking {
        server.enqueue(MockResponse().setBody(LOGIN_OK_JSON))

        val response = repository.login()

        assertTrue(response.isAuthenticated)
        assertEquals("demo", response.user_info?.username)
        assertEquals(listOf("ts", "m3u8"), response.user_info?.allowed_output_formats)
        assertEquals(1735689600L, JsonCoerce.toLongOrNull(response.user_info?.exp_date))
        assertEquals(2, JsonCoerce.toIntOrNull(response.user_info?.max_connections))
        assertEquals("provider.example", response.server_info?.url)
        assertEquals("8080", response.server_info?.port)

        // Login is the one call with no action parameter.
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/player_api.php", request.requestUrl!!.encodedPath)
        assertEquals("user", request.requestUrl!!.queryParameter("username"))
        assertEquals("pass", request.requestUrl!!.queryParameter("password"))
        assertNull(request.requestUrl!!.queryParameter("action"))
    }

    @Test
    fun login_refusedResponse_isNotAuthenticated() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"user_info":{"username":"demo","auth":0,"status":"Expired","message":"Your subscription has expired"}}""",
            ),
        )

        val response = repository.login()

        assertFalse(response.isAuthenticated)
        assertEquals("Expired", response.user_info?.status)
        assertEquals("Your subscription has expired", response.user_info?.message)
    }

    // ---------------------------------------------------------- categories

    @Test
    fun categories_numberIdsAndParentZero_mapToEntities() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {"category_id":"1","category_name":"Sports","parent_id":0},
                  {"category_id":7,"category_name":"News","some_new_panel_field":"ignored"}
                ]
                """.trimIndent(),
            ),
        )

        val categories = repository.getLiveCategories()

        assertEquals(2, categories.size)
        assertEquals("1", categories[0].id)
        assertEquals("Sports", categories[0].name)
        assertNull(categories[0].parentId) // parent_id 0 = root, stored as null
        assertEquals("7", categories[1].id) // numeric id → "7"
        assertEquals("News", categories[1].name)
        assertNull(categories[1].parentId) // absent parent_id stays null
        assertTrue(categories.all { it.type == StreamType.LIVE })
    }

    // ----------------------------------------------------------------- EPG

    @Test
    fun shortEpg_base64TitlesAndUnixTimestamps_mapToEpgProgram() = runBlocking {
        val encodedTitle = Base64.getEncoder().encodeToString("Morning News".toByteArray())
        server.enqueue(
            MockResponse().setBody(
                """
                {"epg_listings":[
                  {"id":"12345","epg_id":"bbc.one.uk","title":"$encodedTitle","lang":"en",
                   "start":"2024-12-31 08:00:00","end":"2024-12-31 09:00:00",
                   "start_timestamp":1735641600,"stop_timestamp":1735645200,
                   "now_playing":1,"has_archive":0,"description":"Daily news roundup"}
                ]}
                """.trimIndent(),
            ),
        )

        val listings = repository.getShortEpg("123")
        val program = listings.single().toEntity()!!

        assertEquals("bbc.one.uk", program.channelId)
        assertEquals("Morning News", program.title) // base64 decoded
        assertEquals("Daily news roundup", program.description)
        assertEquals(1735641600_000L, program.startUtcMillis) // unix seconds → millis
        assertEquals(1735645200_000L, program.endUtcMillis)

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("123", request.requestUrl!!.queryParameter("stream_id"))
        assertEquals("4", request.requestUrl!!.queryParameter("limit"))
        assertEquals("get_short_epg", request.requestUrl!!.queryParameter("action"))
    }

    @Test
    fun shortEpg_emptyObject_isAnEmptyListNotACrash() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(repository.getShortEpg("123").isEmpty())
    }

    // -------------------------------------------------------- series detail

    @Test
    fun seriesInfo_episodesAreKeyedByStringSeason() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "seasons":[{"id":1,"season_number":1,"name":"Season 1","episode_count":7}],
                  "info":{"name":"Breaking Bad","series_id":"12","category_id":"10",
                          "rating":9.5,"backdrop_path":["https://cdn.example/bg1.jpg"],
                          "tmdb_id":"1396"},
                  "episodes":{
                    "1":[{"id":"11","episode_num":1,"title":"Pilot","container_extension":"mp4",
                          "season":1,"info":{"movie_image":"/pilot.jpg","duration_secs":3480,"rating":8.5}}],
                    "2":[]
                  }
                }
                """.trimIndent(),
            ),
        )

        val detail = repository.getSeriesInfo("12")

        assertEquals("Breaking Bad", detail.info?.name)
        assertEquals(9.5, JsonCoerce.toDoubleOrNull(detail.info?.rating)!!, 0.0)
        assertEquals(listOf("https://cdn.example/bg1.jpg"), detail.info?.backdropPaths())

        val episodes = detail.episodesBySeason()
        assertEquals(setOf("1"), episodes.keys) // keyed by "1", and empty seasons drop out
        val pilot = episodes.getValue("1").single()
        assertEquals("11", pilot.id) // episode id, NOT series_id/episode_num
        assertEquals("Pilot", pilot.title)
        assertEquals(3480, JsonCoerce.toIntOrNull(pilot.info?.duration_secs))
        // TMDB-relative thumbnail resolved against the common TMDB base.
        assertEquals(
            "https://image.tmdb.org/t/p/w500/pilot.jpg",
            pilot.info?.movie_image?.resolvePosterUrl(),
        )

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("12", request.requestUrl!!.queryParameter("series_id"))
        assertEquals("get_series_info", request.requestUrl!!.queryParameter("action"))
    }

    @Test
    fun seriesInfo_junkEpisodesShape_degradesToEmptyMap() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"info":{"name":"X"},"episodes":[]}"""))
        val detail = repository.getSeriesInfo("99")
        assertTrue(detail.episodesBySeason().isEmpty())
    }

    // --------------------------------------------------------------- XMLTV

    @Test
    fun xmlTv_isStreamedAsRawBytesFromItsOwnEndpoint() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """<?xml version="1.0" encoding="utf-8"?><tv><channel id="bbc.one.uk">""" +
                    "<display-name>BBC One</display-name></channel></tv>",
            ),
        )

        val body = repository.getXmlTv().use { it.string() }

        assertTrue(body.contains("<channel id=\"bbc.one.uk\">"))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/xmltv.php", request.requestUrl!!.encodedPath)
        assertEquals("user", request.requestUrl!!.queryParameter("username"))
        assertNull(request.requestUrl!!.queryParameter("action"))
    }

    private companion object {
        /**
         * Fixture: three live rows across both wire shapes, plus a row with no
         * `stream_id` (dropped) and fields our entities do not model
         * (`type_name`, `is_adult`, `custom_sid` semantics, unknown futures).
         */
        const val LIVE_STREAMS_JSON = """
            [
              {"num":1,"name":"BBC One HD","stream_type":"live","stream_id":123,
               "stream_icon":"http://cdn.example/icons/bbc1.png","epg_channel_id":"bbc.one.uk",
               "added":"1609459200","category_id":"1","custom_sid":"","tv_archive":1,
               "direct_source":"","tv_archive_duration":7,"type_name":"Sports","is_adult":0},
              {"num":"2","name":"CBC News","stream_id":"456","stream_icon":"","added":"",
               "category_id":"","tv_archive":"0","some_new_panel_field":{"nested":true}},
              {"name":"No id channel","category_id":"1"}
            ]
        """

        const val LOGIN_OK_JSON = """
            {
              "user_info": {"username":"demo","password":"demo123","message":"Welcome!",
                            "auth":1,"status":"Active","exp_date":"1735689600","is_trial":"0",
                            "active_cons":"1","created_at":"1609459200","max_connections":"2",
                            "allowed_output_formats":["ts","m3u8"]},
              "server_info": {"url":"provider.example","port":"8080","https_port":"8443",
                              "server_protocol":"http","timezone":"UTC","timestamp_now":1670000000,
                              "time_now":"2022-12-03 10:00:00"}
            }
        """
    }
}
