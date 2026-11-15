package com.rysal.happytv.ui.player

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #238: which `direct_source` links the player may use. Fake hosts and credentials only. */
class DirectLinkPolicyTest {

    private var overrideReads = 0

    private suspend fun check(
        raw: String?,
        signInIsHttps: Boolean = true,
        override: Boolean = false,
    ): DirectLinkPolicy.Result = DirectLinkPolicy.check(raw, signInIsHttps, "alice", "s3cret-pw") {
        overrideReads++
        override
    }

    @Test
    fun `an https link is used`() = runTest {
        val r = check("https://cdn.provider.example/movies/1.mkv")
        assertEquals("https://cdn.provider.example/movies/1.mkv", r.url)
        assertEquals(0, overrideReads)
    }

    @Test
    fun `a plain http link without credentials is used on an https sign-in`() = runTest {
        assertEquals("http://cdn.provider.example/movies/1.mkv", check("http://cdn.provider.example/movies/1.mkv").url)
        assertEquals(0, overrideReads)
    }

    @Test
    fun `a plain http link with credentials is ignored on an https sign-in`() = runTest {
        val path = check("http://cdn.provider.example/movie/alice/s3cret-pw/1.mkv")
        assertNull(path.url)
        assertEquals(DirectLinkPolicy.Verdict.CREDENTIALS_OVER_HTTP, path.verdict)
        assertNull(check("http://cdn.provider.example/1.mkv?username=alice&password=s3cret-pw").url)
        assertNull(check("http://cdn.provider.example/1.mkv?token=s3cret-pw").url)
    }

    @Test
    fun `the same link is used when the override is on`() = runTest {
        val link = "http://cdn.provider.example/movie/alice/s3cret-pw/1.mkv"
        assertEquals(link, check(link, override = true).url)
        assertEquals(1, overrideReads)
    }

    @Test
    fun `the same link is used on a consented plain http sign-in`() = runTest {
        val link = "http://cdn.provider.example/movie/alice/s3cret-pw/1.mkv"
        assertEquals(link, check(link, signInIsHttps = false).url)
        assertEquals("override is not needed", 0, overrideReads)
    }

    @Test
    fun `an https link carrying credentials is still used`() = runTest {
        val link = "https://cdn.provider.example/1.mkv?username=alice&password=s3cret-pw"
        assertEquals(link, check(link).url)
    }

    @Test
    fun `a url-encoded credential is found`() = runTest {
        // Password "p@ss word!" travels as p%40ss%20word%21.
        val path = DirectLinkPolicy.check(
            "http://cdn.provider.example/movie/alice/p%40ss%20word%21/1.mkv", true, "alice", "p@ss word!",
        ) { false }
        assertNull(path.url)
        val query = DirectLinkPolicy.check(
            "http://cdn.provider.example/1.mkv?password=p%40ss+word%21", true, "alice", "p@ss word!",
        ) { false }
        assertNull(query.url)
    }

    @Test
    fun `non web schemes are ignored whatever the sign-in or override`() = runTest {
        for (link in listOf(
            "file:///sdcard/movie.mkv",
            "content://media/external/video/1",
            "data:video/mp4;base64,AAAA",
            "rtmp://cdn.provider.example/live/1",
            "ftp://cdn.provider.example/1.mkv",
            "//cdn.provider.example/1.mkv",
            "cdn.provider.example/1.mkv",
        )) {
            val r = check(link, override = true)
            assertNull(link, r.url)
            assertEquals(link, DirectLinkPolicy.Verdict.NOT_WEB_ADDRESS, r.verdict)
            assertNull(link, check(link, signInIsHttps = false).url)
        }
    }

    @Test
    fun `blank and missing links are ignored`() = runTest {
        assertNull(check(null).url)
        assertNull(check("").url)
        assertNull(check("   ").url)
    }

    @Test
    fun `scheme letter case does not matter`() = runTest {
        assertEquals("HTTPS://cdn.provider.example/1.mkv", check("HTTPS://cdn.provider.example/1.mkv").url)
        assertEquals("HtTp://cdn.provider.example/1.mkv", check("HtTp://cdn.provider.example/1.mkv").url)
        assertNull(check("HTTP://cdn.provider.example/movie/alice/s3cret-pw/1.mkv").url)
        assertNull(check("FILE:///sdcard/1.mkv").url)
    }

    @Test
    fun `a link is trimmed`() = runTest {
        assertEquals("https://cdn.provider.example/1.mkv", check("  https://cdn.provider.example/1.mkv\n").url)
    }
}
