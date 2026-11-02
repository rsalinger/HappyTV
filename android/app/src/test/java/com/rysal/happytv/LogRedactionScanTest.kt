package com.rysal.happytv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #17: no log line may carry a credential. A pragmatic source scan (same idea as
 * `FocusRequesterOrderTest`) over `src/main`:
 *
 *  1. a `Log.*` call that interpolates a value named like a URL (`url`, `uri`,
 *     `streamUrl`, `target.url`, ...) must route it through `redactCredentials`;
 *  2. a `Log.*` call must not interpolate a username or password;
 *  3. the HTTP logging interceptor never uses `Level.BODY` (API query strings carry
 *     the credentials).
 *
 * Allowed exceptions: none. A new exception must be added to [ALLOWED] with a reason.
 * What the scan cannot see: a URL assigned to an innocently named variable, or a log
 * through another API; reviewers still read the diff.
 */
class LogRedactionScanTest {

    private val root = File("src/main/java/com/rysal/happytv")

    /** `relative/path.kt:line` -> why it is safe. */
    private val ALLOWED = emptyMap<String, String>()

    private val urlInterpolation = Regex("""\$\{?[\w.?!]*[uU](?:rl|ri)\b""")
    private val secretInterpolation = Regex("""\$\{?[\w.?!]*(?:username|password|passwd|userName)\b""")

    @Test
    fun `no Log call interpolates an unredacted url or a credential`() {
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                logStatements(file.readLines()).mapNotNull { (line, text) ->
                    val key = "${file.relativeTo(root)}:$line"
                    if (key in ALLOWED) return@mapNotNull null
                    when {
                        offendingUrl(text) -> "$key (url without redactCredentials)"
                        secretInterpolation.containsMatchIn(text) -> "$key (credential interpolated)"
                        else -> null
                    }
                }
            }
            .toList()
        assertTrue("credentials may reach logcat: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the http logging interceptor never logs bodies`() {
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""Level\.BODY""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).toString() }
            .toList()
        assertTrue("HttpLoggingInterceptor.Level.BODY logs API query strings: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the scan flags unredacted urls and accepts redacted ones`() {
        fun flagged(code: String) = logStatements(code.trimIndent().lines()).any { (_, t) ->
            offendingUrl(t) || secretInterpolation.containsMatchIn(t)
        }
        assertTrue(flagged("""Log.i(TAG, "built URL = ${'$'}url mode=${'$'}mode")"""))
        assertTrue(flagged("""Log.i(TAG, "prepared url=${'$'}{target.url} " +
            "x")"""))
        assertTrue(flagged("""Log.i(TAG, "as ${'$'}username")"""))
        assertTrue(flagged("""Log.i(TAG, "user=${'$'}{conn.username}")"""))
        assertFalse(flagged("""Log.i(TAG, "built URL = ${'$'}{PlayerUrlBuilder.redactCredentials(url)}")"""))
        assertFalse(flagged("""Log.i(TAG, "no poster URL for '${'$'}name'")"""))
        assertEquals(1, logStatements(listOf("val x = 1", "Log.d(T, \"a\")")).size)
    }

    private fun offendingUrl(statement: String): Boolean =
        urlInterpolation.containsMatchIn(statement) && !statement.contains("redactCredentials")

    /** (1-based line, text) of every `Log.x(` / `android.util.Log.x(` statement, continuation lines joined. */
    private fun logStatements(lines: List<String>): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("import")) return@forEachIndexed
            if (!Regex("""\bLog\.[viwed]\(""").containsMatchIn(line)) return@forEachIndexed
            val text = StringBuilder(line)
            var next = index + 1
            // Join continuation lines until the call's parentheses balance (bounded).
            while (next < lines.size && next < index + 8 && depth(text) > 0) {
                text.append(' ').append(lines[next])
                next++
            }
            out += (index + 1) to text.toString()
        }
        return out
    }

    private fun depth(text: CharSequence): Int {
        var d = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && inString) i++
            else if (c == '"') inString = !inString
            else if (!inString && c == '(') d++
            else if (!inString && c == ')') d--
            i++
        }
        return d
    }
}
