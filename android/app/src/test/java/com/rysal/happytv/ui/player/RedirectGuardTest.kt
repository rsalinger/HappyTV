package com.rysal.happytv.ui.player

import okhttp3.Call
import okhttp3.Connection
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * #21 - the redirect policy, hop by hop. Fake values only: the username is
 * `user123` and the password `pa ss&word` (a space and an ampersand, so the
 * encoded forms differ from the raw one).
 *
 * The guard is an OkHttp network interceptor: [chain] below plays the role of
 * one hop (`hop`) of a call that began at `origin`.
 */
class RedirectGuardTest {

    private val username = "user123"
    private val password = "pa ss&word"

    private val log = mutableListOf<Pair<String, String>>()
    private var override = false
    private var credentialReads = 0

    private fun guard() = RedirectGuard(
        credentials = {
            credentialReads++
            listOf(username, password)
        },
        allowOverride = { override },
        log = { event, detail -> log += event to detail },
    )

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun encPath(value: String) = enc(value).replace("+", "%20")

    /** Runs one hop through the guard; returns true when it was let through. */
    private fun passes(origin: String, hop: String): Boolean {
        val chain = FakeChain(origin.toHttpUrl(), hop.toHttpUrl())
        return try {
            guard().intercept(chain)
            chain.proceeded
        } catch (_: InsecureRedirectException) {
            false
        }
    }

    // ---------------------------------------------------------------- policy

    @Test
    fun `https to http with the username in the path is refused`() {
        assertFalse(passes("https://panel.example/movie/a/b/1.mkv", "http://cdn.example/play/$username/1.mkv"))
    }

    @Test
    fun `https to http with the password in the path is refused`() {
        assertFalse(
            passes("https://panel.example/movie/a/b/1.mkv", "http://cdn.example/play/${encPath(password)}/1.mkv"),
        )
    }

    @Test
    fun `https to http with a credential in the query is refused`() {
        assertFalse(passes("https://panel.example/m", "http://cdn.example/play?token=1&u=$username"))
        assertFalse(passes("https://panel.example/m", "http://cdn.example/play?p=${enc(password)}"))
    }

    @Test
    fun `https to http with user info in the target is refused`() {
        assertFalse(passes("https://panel.example/m", "http://$username:x@cdn.example/play/1.mkv"))
    }

    @Test
    fun `url encoded credentials are matched in every encoding`() {
        // %20 (path style) and + (form style) both carry the password.
        for (form in listOf("pa%20ss%26word", "pa+ss%26word")) {
            assertFalse(form, passes("https://panel.example/m", "http://cdn.example/play/$form/1.mkv"))
        }
    }

    @Test
    fun `https to http without credentials keeps playing`() {
        // A provider whose redirect is a plain-HTTP CDN path with no username or password.
        assertTrue(passes("https://panel.example/movie/a/b/1.mkv", "http://203.0.113.9/play/movie/abc123/1.mkv"))
        assertEquals("the guard never logs for an allowed hop", emptyList<Pair<String, String>>(), log)
    }

    @Test
    fun `https to https is unaffected even when it carries credentials`() {
        assertTrue(passes("https://panel.example/m", "https://cdn.example/play/$username/1.mkv"))
        assertEquals("credentials are not even read for an HTTPS hop", 0, credentialReads)
    }

    @Test
    fun `an http origin is unaffected`() {
        // Signed in over HTTP with the cleartext consent: the downgrade check applies only to HTTPS origins.
        assertTrue(passes("http://panel.example/m", "http://cdn.example/play/$username/1.mkv"))
        assertEquals(0, credentialReads)
    }

    @Test
    fun `the override lets the hop through and logs it`() {
        override = true
        assertTrue(passes("https://panel.example/m", "http://cdn.example/play/$username/1.mkv"))
        assertEquals(RedirectGuard.EVENT_ALLOWED, log.single().first)
    }

    @Test
    fun `a refusal logs one line with booleans only`() {
        passes("https://panel.example/m", "http://cdn.example/play/$username/${encPath(password)}/1.mkv")
        val (event, detail) = log.single()
        assertEquals(RedirectGuard.EVENT_REFUSED, event)
        assertEquals("httpsToHttp=true credentialsInTarget=true override=false", detail)
        assertFalse(detail.contains(username))
        assertFalse(detail.contains("cdn.example"))
    }

    @Test
    fun `the refusal carries no url or credential`() {
        val e = assertThrows(InsecureRedirectException::class.java) {
            guard().intercept(FakeChain("https://panel.example/m".toHttpUrl(), "http://c.example/$username".toHttpUrl()))
        }
        assertFalse(e.message.orEmpty().contains(username))
        assertFalse(e.message.orEmpty().contains("example"))
    }

    // ------------------------------------------------------ matching details

    @Test
    fun `short credentials match only a whole segment so ordinary paths are not refused`() {
        val short = listOf("ab")
        val ordinary = "http://cdn.example/play/movie/abc123/1.mkv".toHttpUrl()
        assertFalse(RedirectGuard.carriesCredentials(ordinary, short))
        assertTrue(RedirectGuard.carriesCredentials("http://cdn.example/play/ab/1.mkv".toHttpUrl(), short))
    }

    @Test
    fun `a long credential inside a longer segment is still found`() {
        assertTrue(
            RedirectGuard.carriesCredentials("http://cdn.example/play/x-$username-y/1.mkv".toHttpUrl(), listOf(username)),
        )
    }

    @Test
    fun `blank credentials never match`() {
        assertFalse(RedirectGuard.carriesCredentials("http://cdn.example/a/b".toHttpUrl(), listOf("", "  ")))
    }

    // ------------------------------------------------------------------ wire

    @Test
    fun `an http to http redirect that carries credentials still follows on the wire`() {
        val panel = MockWebServer().also { it.start() }
        val cdn = MockWebServer().also { it.start() }
        try {
            cdn.enqueue(MockResponse().setBody("video"))
            panel.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", cdn.url("/play/$username/1.mkv").toString()),
            )
            val client = OkHttpClient.Builder().addNetworkInterceptor(guard()).build()
            client.newCall(Request.Builder().url(panel.url("/movie/1.mkv")).build()).execute().use {
                assertEquals("video", it.body!!.string())
            }
            assertEquals(1, cdn.requestCount)
        } finally {
            panel.shutdown()
            cdn.shutdown()
        }
    }

    // ------------------------------------------------------------------ fakes

    private class FakeChain(origin: HttpUrl, hop: HttpUrl) : Interceptor.Chain {
        private val originRequest = Request.Builder().url(origin).build()
        private val hopRequest = Request.Builder().url(hop).build()
        var proceeded = false

        override fun request(): Request = hopRequest

        override fun call(): Call = object : Call {
            override fun request(): Request = originRequest
            override fun execute(): Response = error("unused")
            override fun enqueue(responseCallback: okhttp3.Callback) = error("unused")
            override fun cancel() = Unit
            override fun isExecuted() = true
            override fun isCanceled() = false
            override fun timeout(): Timeout = Timeout.NONE
            override fun clone(): Call = this
        }

        override fun proceed(request: Request): Response {
            proceeded = true
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }

        override fun connection(): Connection? = null
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }
}
