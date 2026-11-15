package com.rysal.happytv.data.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.guide.HttpXmlTvFetcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #237: the Xtream API client and the guide download both refuse an HTTPS to
 * HTTP redirect that carries the sign-in details (unless the override is on),
 * and still follow every other redirect. Each client is the production shape
 * (`Graph.buildXtreamClient`, `HttpXmlTvFetcher`) against MockWebServer, with a
 * test certificate for the HTTPS side. Fake values only.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ClientRedirectPolicyTest {

    private val user = "fakeuser7731"
    private val pass = "fakepass9946"

    private lateinit var secureA: MockWebServer
    private lateinit var secureB: MockWebServer
    private lateinit var plain: MockWebServer
    private lateinit var certs: HandshakeCertificates
    private var override = false
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server = HandshakeCertificates.Builder().heldCertificate(held).build()
        certs = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        fun tls() = MockWebServer().also {
            it.useHttps(server.sslSocketFactory(), false)
            it.start()
        }
        secureA = tls()
        secureB = tls()
        plain = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        secureA.shutdown()
        secureB.shutdown()
        plain.shutdown()
    }

    private fun guard(prefix: String) = RedirectGuard(
        credentials = { emptyList() },
        allowOverride = { override },
        log = { event, detail -> events += "$event $detail" },
        eventPrefix = prefix,
    )

    private fun secureUrl(server: MockWebServer, path: String) = "https://localhost:${server.port}$path"

    private fun plainUrl(path: String) = "http://localhost:${plain.port}$path"

    private val withCreds = "/player_api.php?username=$user&password=$pass"

    private fun redirect(to: String) = MockResponse().setResponseCode(302).setHeader("Location", to)

    /** One client under test: fetches [url] and returns the body text. */
    private class Subject(val name: String, val fetch: (String) -> String)

    private fun subjects(): List<Subject> {
        val api = Graph.buildXtreamClient(guard(RedirectGuard.API)) {
            sslSocketFactory(certs.sslSocketFactory(), certs.trustManager)
        }
        val fetcher = HttpXmlTvFetcher(
            ApplicationProvider.getApplicationContext(),
            redirectGuard = guard(RedirectGuard.GUIDE),
            configureClient = { sslSocketFactory(certs.sslSocketFactory(), certs.trustManager) },
        )
        return listOf(
            Subject("api") { url -> fetchWith(api, url) },
            Subject("guide") { url ->
                runBlocking { fetcher.stageIfModified(url) }!!.let { f -> f.readText().also { f.delete() } }
            },
        )
    }

    private fun fetchWith(client: OkHttpClient, url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }

    private fun expectRefused(subject: Subject, url: String) {
        try {
            subject.fetch(url)
            fail("${subject.name}: expected the redirect to be refused")
        } catch (e: InsecureRedirectException) {
            assertTrue(e is java.io.IOException)
            assertTrue("no URL in the message", !e.message.orEmpty().contains("http"))
        }
    }

    @Test
    fun httpsToHttpWithCredentials_isRefused_whenOverrideOff_andNeverReachesTheWire() {
        for (s in subjects()) {
            events.clear()
            val before = plain.requestCount
            secureA.enqueue(redirect(plainUrl("/x$withCreds")))
            expectRefused(s, secureUrl(secureA, withCreds))
            assertEquals("${s.name}: plain server saw no request", before, plain.requestCount)
            assertEquals(1, events.size)
            assertTrue(events.single().startsWith("${s.name}.redirect.refused "))
            assertTrue("log has booleans only", !events.single().contains(pass) && !events.single().contains(user))
        }
    }

    @Test
    fun httpsToHttpWithCredentials_isFollowed_whenOverrideOn() {
        override = true
        for (s in subjects()) {
            events.clear()
            secureA.enqueue(redirect(plainUrl("/x$withCreds")))
            plain.enqueue(MockResponse().setBody("ok-${s.name}"))
            assertEquals("ok-${s.name}", s.fetch(secureUrl(secureA, withCreds)))
            assertTrue(events.single().startsWith("${s.name}.redirect.allowed "))
        }
    }

    @Test
    fun httpsToHttpWithoutCredentials_isFollowed() {
        for (s in subjects()) {
            secureA.enqueue(redirect(plainUrl("/cdn/guide.xml")))
            plain.enqueue(MockResponse().setBody("cdn-${s.name}"))
            assertEquals("cdn-${s.name}", s.fetch(secureUrl(secureA, withCreds)))
        }
    }

    @Test
    fun httpsToHttps_isFollowed() {
        for (s in subjects()) {
            secureA.enqueue(redirect(secureUrl(secureB, withCreds)))
            secureB.enqueue(MockResponse().setBody("tls-${s.name}"))
            assertEquals("tls-${s.name}", s.fetch(secureUrl(secureA, withCreds)))
        }
    }

    @Test
    fun httpToHttps_isFollowed() {
        for (s in subjects()) {
            plain.enqueue(redirect(secureUrl(secureB, withCreds)))
            secureB.enqueue(MockResponse().setBody("up-${s.name}"))
            assertEquals("up-${s.name}", s.fetch(plainUrl(withCreds)))
        }
    }
}
