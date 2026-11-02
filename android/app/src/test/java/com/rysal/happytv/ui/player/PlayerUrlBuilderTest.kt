package com.rysal.happytv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL-builder coverage for protocol §3 (live/movie/series) and §4
 * (timeshift replay) — the exact strings the panels rewrite server-side.
 */
class PlayerUrlBuilderTest {

    private val origin = "http://panel.example:8080"

    // ------------------------------------------------------------------ live

    @Test
    fun `live url uses m3u8 when the account allows it`() {
        val url = PlayerUrlBuilder.live(origin, "user", "pass", "123", "m3u8")
        assertEquals("http://panel.example:8080/live/user/pass/123.m3u8", url)
    }

    @Test
    fun `live url falls back to ts`() {
        val url = PlayerUrlBuilder.live(origin, "user", "pass", "123", "ts")
        assertEquals("http://panel.example:8080/live/user/pass/123.ts", url)
    }

    @Test
    fun `credentials are url-encoded`() {
        val url = PlayerUrlBuilder.live(origin, "user name", "p+a/ss", "123", "ts")
        assertEquals("http://panel.example:8080/live/user%20name/p%2Ba%2Fss/123.ts", url)
    }

    @Test
    fun `origin tolerates a trailing slash`() {
        val url = PlayerUrlBuilder.live("$origin/", "u", "p", "1", "ts")
        assertEquals("http://panel.example:8080/live/u/p/1.ts", url)
    }

    @Test
    fun `liveExtension prefers m3u8 when allowed`() {
        assertEquals(
            "m3u8",
            PlayerUrlBuilder.liveExtension(listOf("ts", "m3u8")),
        )
    }

    @Test
    fun `liveExtension falls back to ts when m3u8 is not allowed`() {
        assertEquals("ts", PlayerUrlBuilder.liveExtension(listOf("ts")))
    }

    @Test
    fun `liveExtension matches case-insensitively and trims`() {
        assertEquals("m3u8", PlayerUrlBuilder.liveExtension(listOf(" M3U8 ")))
    }

    @Test
    fun `liveExtension with unknown formats keeps the preferred default`() {
        // No cached user_info yet — HLS is the provider default.
        assertEquals("m3u8", PlayerUrlBuilder.liveExtension(null))
        assertEquals("m3u8", PlayerUrlBuilder.liveExtension(emptyList()))
    }

    // ------------------------------------------------------------------ movie

    @Test
    fun `movie url uses the container extension verbatim`() {
        val url = PlayerUrlBuilder.movie(origin, "user", "pass", "5001", "mp4")
        assertEquals("http://panel.example:8080/movie/user/pass/5001.mp4", url)
    }

    @Test
    fun `movie url rejects an empty container extension`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlayerUrlBuilder.movie(origin, "user", "pass", "5001", "  ")
        }
    }

    @Test
    fun `movieExtension prefers the row's extension over the launch's`() {
        assertEquals("mkv", PlayerUrlBuilder.movieExtension("mkv", "mp4"))
        assertEquals("mp4", PlayerUrlBuilder.movieExtension(null, "mp4"))
    }

    @Test
    fun `movieExtension falls back to mp4 on junk instead of throwing`() {
        // F-51 hardening: a row with a degenerate container_extension (".", a
        // lone dot) must not crash the load coroutine — the URL builder's
        // require stays as the caller's guard, this resolves before it.
        assertEquals("mp4", PlayerUrlBuilder.movieExtension(".", null))
        assertEquals("mp4", PlayerUrlBuilder.movieExtension(" ", null))
        assertEquals("mp4", PlayerUrlBuilder.movieExtension(null, null))
        assertEquals("mp4", PlayerUrlBuilder.movieExtension(".", " "))
    }

    @Test
    fun `movieExtension normalizes the kept extension`() {
        assertEquals("mkv", PlayerUrlBuilder.movieExtension(" .MKV ", null))
    }

    // ----------------------------------------------------------- redaction

    @Test
    fun `redacted url masks both credential path segments`() {
        assertEquals(
            "http://panel.example:8080/movie/***/***/5001.mkv",
            PlayerUrlBuilder.redactCredentials("http://panel.example:8080/movie/user/pass/5001.mkv"),
        )
    }

    @Test
    fun `redacted url masks credential query parameters`() {
        assertEquals(
            "http://cdn.example/movie.mkv?username=***&password=***&token=x",
            PlayerUrlBuilder.redactCredentials(
                "http://cdn.example/movie.mkv?username=alice&password=hunter2&token=x",
            ),
        )
    }

    // ---------------------------------------------------------------- series

    @Test
    fun `episode url uses the episode id not the series id`() {
        val url = PlayerUrlBuilder.episode(origin, "user", "pass", "11", "mp4")
        assertEquals("http://panel.example:8080/series/user/pass/11.mp4", url)
    }

    // ---------------------------------------------------------------- replay

    @Test
    fun `replay url matches the timeshift route with a UTC start stamp`() {
        // 2024-04-19T16:00:00Z — the protocol's own example (§4.2).
        val url = PlayerUrlBuilder.replay(
            origin = origin,
            username = "user",
            password = "pass",
            streamId = "123",
            startUtcMillis = 1_713_542_400_000L,
            durationMinutes = 120,
        )
        assertEquals(
            "http://panel.example:8080/timeshift/user/pass/120/2024-04-19:16-00/123.ts",
            url,
        )
    }

    @Test
    fun `replay start is formatted in UTC not device time`() {
        // 2024-04-19T00:30:00Z → date rolls *back* for UTC-negative zones, but
        // the stamp must stay on the 19th either way.
        val url = PlayerUrlBuilder.replay(
            origin = origin,
            username = "u",
            password = "p",
            streamId = "7",
            startUtcMillis = 1_713_486_600_000L,
            durationMinutes = 30,
        )
        assertTrue(url.endsWith("/2024-04-19:00-30/7.ts"))
    }

    @Test
    fun `replay clamps a zero-minute window to one minute`() {
        val url = PlayerUrlBuilder.replay(
            origin = origin,
            username = "u",
            password = "p",
            streamId = "7",
            startUtcMillis = 1_713_542_400_000L,
            durationMinutes = 0,
        )
        assertTrue(url.contains("/1/2024-04-19:16-00/"))
    }

    @Test
    fun `replay is always plain ts`() {
        val url = PlayerUrlBuilder.replay(
            origin = origin,
            username = "u",
            password = "p",
            streamId = "7",
            startUtcMillis = 1_713_542_400_000L,
            durationMinutes = 60,
        )
        assertTrue(url.endsWith(".ts"))
    }
}
