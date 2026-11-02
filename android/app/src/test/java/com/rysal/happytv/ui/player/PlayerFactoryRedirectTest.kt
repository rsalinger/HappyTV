package com.rysal.happytv.ui.player

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Proves F-51's fix at the wire layer: the panel returns 302 with a `Location`
 * the player must follow (common IPTV players do). Media3's default HTTP source
 * follows redirects but, by default, refuses protocol downgrades — so a
 * `302 https → http` surfaces as `InvalidResponseCodeException` (302) and the
 * VOD never plays.
 *
 * We test the contract directly against the [DataSource] chain that
 * [PlayerFactory.redirectTolerantDataSourceFactory] wires:
 *
 *  1. The production factory follows a 302 (panel → CDN chain) and returns
 *     the body — proof the redirect is honoured end-to-end.
 *  2. #21: the factory's OkHttp client carries the [RedirectGuard] as a network
 *     interceptor (it vetoes an HTTPS to HTTP hop that carries sign-in details;
 *     the policy itself is covered by RedirectGuardTest), and still follows
 *     redirects otherwise.
 *  3. [PlayerFactory.mediaSource] builds for both an `.m3u8` URL and a raw
 *     `.mkv` URL, exercising one shared factory — the inconsistency between
 *     the HLS and raw-container paths the task called out as the root cause
 *     is structurally prevented now.
 *
 * Robolectric is required because [DataSpec] and [Uri] are android.* classes.
 * The plain [Application] sidesteps [com.rysal.happytv.HappyTvApp]'s
 * signature pinning the same way [PlayerLaunchTest] does.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlayerFactoryRedirectTest {

    private lateinit var panel: MockWebServer
    private lateinit var cdn: MockWebServer

    private val payload = "fantastic-four-video-bytes".toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        panel = MockWebServer().also { it.start() }
        cdn = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        panel.shutdown()
        cdn.shutdown()
    }

    @Test
    fun redirectTolerantFactory_followsHttpToHttpRedirect_andReturnsBody() {
        // Two-server redirect mimics the panel/CDN split without needing TLS:
        // the *panel* answers with a 302 to the *CDN*; the CDN answers 200.
        cdn.enqueue(MockResponse().setResponseCode(200).setBody(payload.toString(Charsets.UTF_8)))
        panel.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", cdn.url("/play/movie/abc/35801.mkv").toString()),
        )

        val source = PlayerFactory.redirectTolerantDataSourceFactory(ApplicationProvider.getApplicationContext())
            .createDataSource()
        try {
            source.open(DataSpec(Uri.parse(panel.url("/movie/u/p/35801.mkv").toString())))
            val received = ByteArray(payload.size).also { source.read(it, 0, it.size) }
            assertArrayEquals(payload, received)
        } finally {
            source.close()
        }
    }

    @Test
    fun productionFactory_putsTheRedirectGuardOnTheOkHttpSource() {
        // #21: the guard is a network interceptor on the client the factory builds, so it sees
        // every redirect hop before it is sent. Deleting it from buildClient fails this test.
        val guard = RedirectGuard(credentials = { emptyList() }, allowOverride = { false })
        val factory = PlayerFactory.redirectTolerantDataSourceFactory(
            ApplicationProvider.getApplicationContext(),
            guard,
        )
        val client = unwrapOkHttpClient(factory)
        assertTrue(
            "the redirect guard must be a network interceptor",
            client.networkInterceptors.any { it === guard },
        )
        assertTrue("redirects are still followed (the guard vetoes, OkHttp follows)", client.followRedirects)
        assertTrue("HTTPS to HTTP is followed when the guard allows it", client.followSslRedirects)
    }

    @Test
    fun mediaSource_buildsForBothHlsAndRawContainer_usingOneSharedFactory() {
        // The QA card's structural concern: HLS and raw-container used to
        // take different redirect paths. With the shared
        // redirectTolerantDataSourceFactory, both branches of mediaSource(...)
        // instantiate one factory — one redirect behaviour, by construction.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val hls = PlayerFactory.mediaSource(context, "http://${panel.hostName}:${panel.port}/live.m3u8")
        val raw = PlayerFactory.mediaSource(context, "http://${panel.hostName}:${panel.port}/movie.mkv")

        assertNotNull(hls)
        assertNotNull(raw)
        // The HTTP-classification guard still pins HLS to its own source type.
        assertTrue(
            "raw-container URL must not be classified as HLS",
            !PlayerFactory.isHlsUrl("http://x/movie.mkv"),
        )
        assertTrue(
            ".m3u8 URLs are still routed through the HLS branch",
            PlayerFactory.isHlsUrl("http://x/live.m3u8"),
        )
    }

    /** Reach through [DefaultDataSource] to the OkHttp client its HTTP source was built with. */
    private fun unwrapOkHttpClient(factory: DataSource.Factory): okhttp3.OkHttpClient {
        val wrapper = factory.createDataSource() as DefaultDataSource
        val base = DefaultDataSource::class.java.getDeclaredField("baseDataSource")
            .apply { isAccessible = true }.get(wrapper) as DataSource
        check(base is androidx.media3.datasource.okhttp.OkHttpDataSource) { "expected an OkHttp data source" }
        val callFactory = androidx.media3.datasource.okhttp.OkHttpDataSource::class.java
            .getDeclaredField("callFactory").apply { isAccessible = true }.get(base)
        return callFactory as okhttp3.OkHttpClient
    }
}
