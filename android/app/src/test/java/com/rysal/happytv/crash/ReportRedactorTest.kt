package com.rysal.happytv.crash

import com.rysal.happytv.security.ChannelLogoGuard
import java.net.UnknownHostException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #259: nothing that identifies the sign-in may reach a report. All hosts and credentials here are made up. */
class ReportRedactorTest {

    private val user = "alice_demo"
    private val pass = "s3cr3tPass"
    private val host = "iptv.provider.example"

    private val env = ReportEnvironment("1.1.0", 2, "release", "abc1234", "Acme TV", "14", 34, 4242)

    @Before
    fun setSecrets() {
        ChannelLogoGuard.update(user, pass)
        ReportRedactor.knownHost = host
    }

    @After
    fun clearSecrets() {
        ChannelLogoGuard.update(null, null)
        ReportRedactor.knownHost = null
    }

    private fun assertClean(text: String) {
        for (secret in listOf(user, pass, host)) {
            assertFalse("report still holds a credential: $text", text.contains(secret, ignoreCase = true))
        }
    }

    // ------------------------------------------------------------ path form

    @Test
    fun `path form is masked for live, movie, series and timeshift`() {
        val lines = listOf(
            "GET http://other.example:8080/live/$user/$pass/101.m3u8 200",
            "https://other.example/movie/$user/$pass/555.mkv",
            "https://other.example/series/$user/$pass/9001.mp4",
            "https://other.example/timeshift/$user/$pass/60/2026-11-12:20-00/101.ts",
        )
        for (line in lines) {
            val out = ReportRedactor.redact(line, listOf(), null)
            assertClean(out)
            assertTrue("route and id stay: $out", out.contains("/***/***/") || out.contains("/***/***"))
        }
        assertEquals(
            "GET http://other.example:8080/live/***/***/101.m3u8 200",
            ReportRedactor.redact(lines[0], listOf(), null),
        )
    }

    @Test
    fun `a credential path cut before its trailing slash is still masked`() {
        val out = ReportRedactor.redact("Unable to connect to http://other.example/live/$user/$pass", listOf(), null)
        assertClean(out)
    }

    // ----------------------------------------------------------- query form

    @Test
    fun `query form is masked on player_api, xmltv and get php`() {
        val lines = listOf(
            "http://other.example:8080/player_api.php?username=$user&password=$pass&action=get_live_streams",
            "https://other.example/xmltv.php?username=$user&password=$pass",
            "https://other.example/get.php?username=$user&password=$pass&type=m3u_plus&output=ts",
        )
        for (line in lines) {
            val out = ReportRedactor.redact(line, listOf(), null)
            assertClean(out)
            assertFalse("no query string survives on an address: $out", out.contains("password"))
        }
    }

    @Test
    fun `key=value outside an address and in a form body or JSON is masked`() {
        val form = ReportRedactor.redact("body username=$user&password=$pass done", listOf(), null)
        assertClean(form)
        val spaced = ReportRedactor.redact("login failed for password=$pass user=$user", listOf(), null)
        assertClean(spaced)
        val json = ReportRedactor.redact("""{"username":"$user","password":"$pass","x":1}""", listOf(), null)
        assertClean(json)
        assertTrue(json.contains("\"x\":1"))
    }

    @Test
    fun `user info in an address and an authorization header are masked`() {
        assertClean(ReportRedactor.redact("http://$user:$pass@other.example/path", listOf(), null))
        assertClean(ReportRedactor.redact("Authorization: Basic YWxpY2U6cGFzcw==", listOf(), null).also {
            assertFalse(it.contains("YWxpY2U6"))
        })
    }

    // ------------------------------------------------------- known values

    @Test
    fun `the signed-in host, username and password are masked wherever they appear`() {
        val out = ReportRedactor.redact(
            "Unable to resolve host \"$host\": No address associated with hostname; user $user pass $pass; " +
                "encoded ${java.net.URLEncoder.encode("p@ss word", "UTF-8")}",
        )
        assertClean(out)
        assertTrue(out.contains("<server>"))
    }

    @Test
    fun `a known password with characters that get encoded is masked in its encoded forms`() {
        val secret = "p@ss word&1"
        val out = ReportRedactor.redact(
            "path /live/u/${java.net.URLEncoder.encode(secret, "UTF-8")}/1.ts and /live/u/p%40ss%20word%261/1.ts",
            listOf(secret),
            null,
        )
        assertFalse(out.contains("p%40ss"))
        assertFalse(out.contains("p@ss"))
    }

    @Test
    fun `very short known values are not used as literals so ordinary text survives`() {
        val out = ReportRedactor.redact("a is a letter", listOf("a", "is"), "ab")
        assertEquals("a is a letter", out)
    }

    @Test
    fun `channel, movie and show names stay readable`() {
        val line = "Playing channel \"Cartoon Network HD\" from category Kids at /live/***/***/12.ts"
        assertEquals(line, ReportRedactor.redact(line, listOf(), null))
    }

    // ------------------------------------------- exception messages, causes

    private fun reportFor(error: Throwable): String =
        CrashReportText.crash(1_700_000_000_000, env, Thread.currentThread(), error)

    @Test
    fun `an OkHttp style message carrying the full address is masked in the report`() {
        val error = java.io.IOException(
            "Failed to connect to $host/203.0.113.9:8080 for http://$host:8080/player_api.php?username=$user&password=$pass&action=x",
        )
        assertClean(reportFor(error))
    }

    @Test
    fun `an UnknownHostException with the provider host is masked`() {
        val error = UnknownHostException("Unable to resolve host \"$host\": No address associated with hostname")
        val report = reportFor(error)
        assertClean(report)
        assertTrue(report.contains("UnknownHostException"))
    }

    @Test
    fun `a Media3 style HttpDataSourceException message and its causes are masked`() {
        val root = java.net.SocketTimeoutException("timeout reading http://$host:8080/live/$user/$pass/7.m3u8")
        val middle = IllegalStateException("Source error for http://$host:8080/live/$user/$pass/7.m3u8", root)
        val top = RuntimeException("Playback failed: ${middle.message}", middle)
        top.addSuppressed(java.io.IOException("also /movie/$user/$pass/9.mkv"))
        val report = reportFor(top)
        assertClean(report)
        assertTrue("causes are listed", report.contains("Caused by:"))
        assertTrue("the route is still readable", report.contains("/live/***/***/7.m3u8"))
    }

    @Test
    fun `an exception whose message throws does not break the report`() {
        val error = object : RuntimeException() {
            override val message: String get() = throw IllegalStateException("broken message")
        }
        val report = reportFor(error)
        assertTrue(report.contains("Stack trace"))
    }

    // ------------------------------------------------------------- log lines

    @Test
    fun `log lines are masked when added to a report`() {
        val section = CrashReportText.logSection(
            listOf(
                "11-12 20:00:00.000  4242  4242 I PlayerFactory: mediaSource(url=http://$host/live/$user/$pass/1.ts)",
                "11-12 20:00:01.000  4242  4242 W Net: GET /player_api.php?username=$user&password=$pass",
            ),
        )
        assertClean(section)
        assertTrue(section.contains("Recent log lines (2,"))
        assertTrue(section.contains("PlayerFactory"))
    }

    // ------------------------------------------------- one line at a time

    private val queryEnd = "failed: http://other.example/player_api.php?username=fakeuser&password=fakepass"

    @Test
    fun `a query-form address at the end of a line does not eat the lines after it`() {
        val text = "Stack trace\njava.io.IOException: $queryEnd\n" +
            "\tat com.rysal.Foo.bar(Foo.kt:1)\nCaused by: java.net.SocketException: reset\n\tat Baz.qux(Baz.kt:2)\n" +
            "Recent log lines (1, x)\n11-12 20:00 I Tag: after"
        val out = ReportRedactor.redact(text, listOf(), null)
        assertFalse(out.contains("fakeuser"))
        assertFalse(out.contains("fakepass"))
        listOf("at com.rysal.Foo.bar(Foo.kt:1)", "Caused by: java.net.SocketException: reset", "at Baz.qux(Baz.kt:2)",
            "Recent log lines (1, x)", "I Tag: after").forEach { assertTrue("lost: $it in\n$out", out.contains(it)) }
        assertEquals(text.count { it == '\n' }, out.count { it == '\n' })
    }

    @Test
    fun `the same address in the middle of the log section keeps both neighbours`() {
        val text = "Recent log lines (3, x)\nline before\nW Net: GET /get.php?username=fakeuser&password=fakepass\nline after\n"
        val out = ReportRedactor.redact(text, listOf(), null)
        assertFalse(out.contains("fakepass"))
        assertTrue(out.contains("line before\n"))
        assertTrue(out.contains("\nline after\n"))
    }

    @Test
    fun `CRLF and lone CR line breaks are kept and respected`() {
        val text = "first $queryEnd\r\nsecond line\r\nthird?password=fakepass\rfourth line"
        val out = ReportRedactor.redact(text, listOf(), null)
        assertFalse(out.contains("fakepass"))
        assertFalse(out.contains("fakeuser"))
        assertTrue(out.contains("\r\nsecond line\r\n"))
        assertTrue(out.endsWith("\rfourth line"))
    }

    @Test
    fun `a multi-line report is unchanged by a second pass`() {
        val text = "a $queryEnd\n{\"password\":\"fakepass\"}\nAuthorization: Basic\nnext line\n"
        val once = ReportRedactor.redact(text, listOf(), null)
        assertTrue(once.contains("next line"))
        assertEquals(once, ReportRedactor.redact(once, listOf(), null))
    }

    @Test
    fun `a report carries the fields the issue lists`() {
        val report = reportFor(RuntimeException("boom"))
        listOf(
            "Time: 2023-11-14", "App version: 1.1.0 (2)", "Build type: release", "Commit: abc1234",
            "Device: Acme TV", "Android: 14 (API 34)", "Thread:", "Stack trace", "RuntimeException: boom",
            CrashReportText.LOG_PENDING_MARKER,
        ).forEach { assertTrue("missing '$it' in\n$report", report.contains(it)) }
        assertFalse("API 34 has exit reasons", report.contains(CrashReportText.NO_EXIT_REASONS))
    }

    @Test
    fun `a report from below API 30 says exit reasons are absent`() {
        val report = CrashReportText.crash(0, env.copy(apiLevel = 29), Thread.currentThread(), RuntimeException("x"))
        assertTrue(report.contains(CrashReportText.NO_EXIT_REASONS))
    }
}
