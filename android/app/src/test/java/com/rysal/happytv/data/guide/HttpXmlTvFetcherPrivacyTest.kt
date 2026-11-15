package com.rysal.happytv.data.guide

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #237: the guide download keeps no sign-in details on disk, and old caches are removed. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class HttpXmlTvFetcherPrivacyTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Application

    private val user = "fakeuser7731"
    private val pass = "fakepass9946"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = "http://127.0.0.1:${server.port}/xmltv.php?username=$user&password=$pass"

    private fun filesContaining(secret: String): List<File> = context.cacheDir.walkTopDown()
        .filter { it.isFile && it.readBytes().toString(Charsets.ISO_8859_1).contains(secret) }
        .toList()

    @Test
    fun `no file under cacheDir holds the username or password after a fetch`() = runBlocking {
        val fetcher = HttpXmlTvFetcher(context)
        server.enqueue(
            MockResponse().setBody("<tv/>")
                .setHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
                .setHeader("Cache-Control", "max-age=3600"),
        )
        val staged = fetcher.stageIfModified(url())
        assertNotNull(staged)
        staged!!.delete()

        assertEquals(emptyList<File>(), filesContaining(pass))
        assertEquals(emptyList<File>(), filesContaining(user))
        assertFalse(File(context.cacheDir, "xmltv_http").exists())
    }

    @Test
    fun `a 304 still skips the parse and a cleared validator forces a full download`() = runBlocking {
        val fetcher = HttpXmlTvFetcher(context)
        server.enqueue(MockResponse().setBody("<tv/>").setHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT"))
        fetcher.stageIfModified(url())!!.delete()
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(304))
        assertNull(fetcher.stageIfModified(url()))
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", server.takeRequest().getHeader("If-Modified-Since"))

        fetcher.clearValidators()
        server.enqueue(MockResponse().setBody("<tv/>"))
        fetcher.stageIfModified(url())!!.delete()
        assertNull(server.takeRequest().getHeader("If-Modified-Since"))
    }

    @Test
    fun `the old xmltv_http folder is removed`() {
        val old = File(context.cacheDir, "xmltv_http").apply { mkdirs() }
        File(old, "entry.0").writeText("GET http://host/xmltv.php?username=$user&password=$pass")
        HttpXmlTvFetcher.deleteLegacyHttpCache(context)
        assertFalse(old.exists())
        HttpXmlTvFetcher.deleteLegacyHttpCache(context) // nothing there: no error
    }
}
