package com.rysal.happytv.data.guide

import com.rysal.happytv.security.Credentials
import java.net.URLEncoder

/**
 * `xmltv.php` URL builder — the F-47/F-52 minimum:
 * a fresh sign-in now derives the guide URL from the
 * saved Xtream credentials alone, so the panel's `xmltv.php`
 * is requested without the user having to type a second URL.
 *
 * Shape (Xtream §2.1, verbatim):
 *
 * ```
 * http(s)://{host}[:{port}]/xmltv.php?username={user}&password={pass}
 * ```
 *
 * `password` may contain `@`, `/`, `+`, `&`, `?`, `#`, `%`, space —
 * anything reserved by RFC 3986 — so we percent-encode every value
 * to be safe, even when the user only typed a hex password. The
 * server treats the params as opaque strings, so over-encoding
 * (`%XX` for an unreserved char) is fine.
 *
 * @param credentials the encrypted [Credentials] from
 *   `SecuredCredentialsStore.current()` — the [Credentials.host],
 *   [Credentials.port], [Credentials.useTls] decide the scheme and
 *   authority; [Credentials.username] and [Credentials.password] go
 *   into the query string.
 */
object XmlTvUrlDeriver {

    /** `null` if [credentials] carries no host — a misconfigured panel. */
    fun derive(credentials: Credentials?): String? {
        val c = credentials ?: return null
        val host = c.host.trim().ifBlank { return null }
        val user = c.username.trim()
        val pass = c.password // never trimmed: a password can legitimately have surrounding spaces
        if (user.isEmpty() || pass.isEmpty()) return null
        val scheme = if (c.useTls) "https" else "http"
        val authority = if (c.port == defaultPort(c.useTls)) host else "$host:${c.port}"
        return buildString {
            append(scheme).append("://").append(authority).append("/xmltv.php")
            append("?username=").append(enc(user))
            append("&password=").append(enc(pass))
        }
    }

    /** https default 443; http default 80 — strip them so the URL stays clean. */
    private fun defaultPort(useTls: Boolean): Int = if (useTls) 443 else 80

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
