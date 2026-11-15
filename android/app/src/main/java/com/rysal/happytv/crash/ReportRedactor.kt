package com.rysal.happytv.crash

import com.rysal.happytv.security.ChannelLogoGuard
import com.rysal.happytv.ui.player.PlayerUrlBuilder
import java.net.URLEncoder

/**
 * #259: every character that goes into a crash report passes through [redact] first.
 *
 * Layers, all applied to the whole text (a stack trace, an exception message, a logcat line):
 *  1. the signed-in username, password and provider host, when known (literal and URL-encoded),
 *  2. [PlayerUrlBuilder.redactCredentials] (path form and `?username=` / `&password=`),
 *  3. looser patterns for text that is not a clean URL: a credential path without its trailing
 *     slash, `password=` after a space or in a form body, JSON `"password":"..."`, `user:pass@host`,
 *     an `Authorization` header, and the whole query string of any http(s) address.
 *
 * Channel, movie and show names are not touched; they stay on the device.
 * Redaction never throws: if a layer fails the text is withheld, not passed through.
 */
object ReportRedactor {

    /** The provider host as signed in, set by the credential store. Memory only. */
    @Volatile
    var knownHost: String? = null

    private const val MIN_SECRET_LENGTH = 3
    const val MASK = "***"
    private const val WITHHELD = "[text withheld: redaction failed]"

    /** `/live/u/p` with or without a trailing slash; also `timeshift`, which the replay URLs use. */
    private val LOOSE_PATH = Regex("""(/(?:live|movie|series|timeshift|timeShift)/)[^/\s?#"'<>]+/[^/\s?#"'<>]+""")

    /** `username=x`, `password=x`, also after a space or inside a form body. */
    private val KEY_VALUE =
        Regex("""\b(password|passwd|pass|pwd|username|user|token)=[^&\s#"',;)]*""", RegexOption.IGNORE_CASE)

    private val JSON_KEY_VALUE =
        Regex("""("(?:password|passwd|pass|pwd|username|user_name|user|token)"\s*:\s*")[^"]*(")""", RegexOption.IGNORE_CASE)

    private val USER_INFO = Regex("""(\b[a-z][a-z0-9+.\-]*://)[^/\s@]+@""", RegexOption.IGNORE_CASE)

    private val AUTH_HEADER = Regex("""(authorization\s*[:=]\s*)(?:basic|bearer)?\s*[^\s,;]+""", RegexOption.IGNORE_CASE)

    private val URL_QUERY = Regex("""(https?://[^\s?#"'<>]+)\?[^\s"'<>]*""", RegexOption.IGNORE_CASE)

    fun redact(text: String): String = redact(text, ChannelLogoGuard.secrets, knownHost)

    /** The same, with the known values passed in (tests use made-up ones). Never throws. */
    fun redact(text: String, secrets: List<String>, host: String?): String = try {
        redactUnsafe(text, secrets, host)
    } catch (e: Throwable) {
        WITHHELD
    }

    /** After a `\n`, or after a `\r` that is not followed by `\n`. */
    private val LINE_BREAKS = Regex("""(?<=\n)|(?<=\r)(?!\n)""")

    /**
     * Layers run one line at a time, line breaks kept, so no pattern can swallow text past the end
     * of its line (`PlayerUrlBuilder.redactCredentials` has a value class that matches whitespace,
     * so a query-form address at the end of a line would otherwise eat the lines after it).
     * A known secret that spans a line break is not supported.
     */
    private fun redactUnsafe(text: String, secrets: List<String>, host: String?): String {
        val out = StringBuilder(text.length)
        for (piece in text.split(LINE_BREAKS)) {
            val body = piece.trimEnd('\r', '\n')
            out.append(redactLine(body, secrets, host)).append(piece, body.length, piece.length)
        }
        return out.toString()
    }

    private fun redactLine(line: String, secrets: List<String>, host: String?): String {
        var out = line
        for (secret in secrets) {
            if (secret.length < MIN_SECRET_LENGTH) continue
            out = out.replace(secret, MASK)
            val encoded = URLEncoder.encode(secret, "UTF-8")
            if (encoded != secret) out = out.replace(encoded, MASK)
            val pathEncoded = encoded.replace("+", "%20")
            if (pathEncoded != encoded) out = out.replace(pathEncoded, MASK)
        }
        val h = host?.trim().orEmpty()
        if (h.length >= MIN_SECRET_LENGTH) out = out.replace(h, "<server>", ignoreCase = true)
        out = PlayerUrlBuilder.redactCredentials(out)
        out = LOOSE_PATH.replace(out, "$1***/***")
        out = KEY_VALUE.replace(out) { it.groupValues[1] + "=" + MASK }
        out = JSON_KEY_VALUE.replace(out, "$1***$2")
        out = USER_INFO.replace(out, "$1***@")
        out = AUTH_HEADER.replace(out) { it.groupValues[1] + MASK }
        out = URL_QUERY.replace(out, "$1?***")
        return out
    }
}
