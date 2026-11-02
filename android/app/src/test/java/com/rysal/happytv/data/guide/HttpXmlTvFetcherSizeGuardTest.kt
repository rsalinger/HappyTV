package com.rysal.happytv.data.guide

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-52's payload-size guard: "check Content-Length on the response (39k
 * channels -> potentially very large XML) … before promising".
 *
 * The cap is constructor-injectable so these tests exercise the guard with
 * tiny fixtures instead of moving hundreds of megabytes; production uses
 * [HttpXmlTvFetcher.MAX_XMLTV_BYTES]. Both refusal shapes are pinned:
 * a `Content-Length` over the cap is rejected before the download starts,
 * and a chunked body that lies by omission is rejected while it streams.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class HttpXmlTvFetcherSizeGuardTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Application

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = "http://127.0.0.1:${server.port}/xmltv.php?username=demo&password=secret"

    private fun stagedFile(): File = File(context.cacheDir, "guide_xmltv_stage/pending.xml")

    @Test
    fun `a Content-Length over the cap is refused before the download`() = runBlocking {
        val fetcher = HttpXmlTvFetcher(context, maxBytes = 64)
        server.enqueue(MockResponse().setBody("x".repeat(200)))

        val error = runCatching { fetcher.stageIfModified(url()) }.exceptionOrNull()
        assertTrue("expected an IOException, got $error", error is IOException)
        assertTrue(
            "expected a size message in: ${error?.message}",
            error?.message.orEmpty().contains("too large"),
        )
        assertFalse("nothing may be staged on refusal", stagedFile().exists())
    }

    @Test
    fun `a chunked body over the cap is refused while it streams`() = runBlocking {
        val fetcher = HttpXmlTvFetcher(context, maxBytes = 64)
        // Chunked: no Content-Length to check up front, so the guard has to
        // count bytes on the wire.
        server.enqueue(MockResponse().setChunkedBody("y".repeat(200), 16))

        val error = runCatching { fetcher.stageIfModified(url()) }.exceptionOrNull()
        assertTrue("expected an IOException, got $error", error is IOException)
        assertTrue(
            "expected a size message in: ${error?.message}",
            error?.message.orEmpty().contains("exceeds"),
        )
        assertFalse("nothing may be staged on refusal", stagedFile().exists())
    }

    @Test
    fun `a body within the cap stages in full`() = runBlocking {
        val fetcher = HttpXmlTvFetcher(context, maxBytes = 1024)
        server.enqueue(MockResponse().setBody("<tv></tv>"))

        val staged = fetcher.stageIfModified(url())
        assertTrue("expected a staged file", staged != null)
        assertEquals("<tv></tv>", staged!!.readText())
    }

    @Test
    fun `a 304 answer stages nothing - the stored guide is current`() = runBlocking {
        val fetcher = HttpXmlTvFetcher(context, maxBytes = 1024)
        server.enqueue(
            MockResponse()
                .setBody("<tv></tv>")
                .addHeader("Last-Modified", "Wed, 21 Oct 2026 12:00:00 GMT"),
        )
        fetcher.stageIfModified(url())

        server.enqueue(MockResponse().setResponseCode(304))
        assertNull("304 must stage nothing", fetcher.stageIfModified(url()))
        // The second request carried the validator from the first.
        server.takeRequest() // the first fetch (no validator yet)
        val revalidation = server.takeRequest()
        assertTrue(
            "expected If-Modified-Since on the revalidation request",
            revalidation.getHeader("If-Modified-Since") != null,
        )
    }

    @Test
    fun `the error message never carries the credentials`() = runBlocking {
        val fetcher = HttpXmlTvFetcher(context, maxBytes = 64)
        server.enqueue(MockResponse().setResponseCode(500))

        val error = runCatching { fetcher.stageIfModified(url()) }.exceptionOrNull()
        val message = error?.message.orEmpty()
        assertTrue("expected the failure message, got: $message", message.contains("HTTP 500"))
        assertFalse("password leaked into: $message", message.contains("password=secret"))
    }
}
