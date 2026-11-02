package com.rysal.happytv.ui.player

import com.rysal.happytv.data.Graph
import com.rysal.happytv.perf.HtvPerf
import java.net.ProtocolException
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Thrown by [RedirectGuard] when a stream request made over HTTPS is redirected
 * to plain HTTP and the redirect target carries the account's sign-in details
 * (#21). Extends [ProtocolException] on purpose: OkHttp treats that as
 * non-recoverable, so it does not retry the request against the provider.
 *
 * The message never contains a URL.
 */
internal class InsecureRedirectException :
    ProtocolException("Refused an HTTPS to HTTP redirect that carries sign-in details")

/**
 * The redirect policy behind playback (#21, decided 2026-10-23).
 *
 * Xtream panels commonly answer an HTTPS `/movie/...` request with a 302 to a
 * plain-HTTP CDN. That is fine while the target is only a CDN path (the
 * provider today: no username or password in it), so those keep playing. What
 * must never happen is the sign-in details travelling over an unencrypted hop.
 * So, per hop:
 *
 *  - the call began on HTTPS, this hop is plain HTTP, and the hop's URL
 *    (path, query or user-info) contains the username or password
 *    (raw or URL-encoded) -> refuse, unless [allowOverride] is on;
 *  - everything else (HTTPS to HTTPS, HTTP origin, HTTPS to HTTP with no
 *    credentials) -> follow.
 *
 * It runs as an OkHttp *network* interceptor, which sees every redirect hop
 * before it is sent, so a refused hop never reaches the wire.
 *
 * [credentials] and [allowOverride] are read only when a hop is an HTTPS to
 * HTTP downgrade, so the common path does no storage reads.
 */
internal class RedirectGuard(
    private val credentials: () -> List<String>,
    private val allowOverride: () -> Boolean,
    private val log: (event: String, detail: String) -> Unit = { event, detail ->
        HtvPerf.log(event, 0L, detail)
    },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val hop = chain.request().url
        val origin = chain.call().request().url
        if (origin.isHttps && !hop.isHttps) {
            val carries = carriesCredentials(hop, credentials())
            if (carries) {
                val overridden = allowOverride()
                log(
                    if (overridden) EVENT_ALLOWED else EVENT_REFUSED,
                    "httpsToHttp=true credentialsInTarget=true override=$overridden",
                )
                if (!overridden) throw InsecureRedirectException()
            }
        }
        return chain.proceed(chain.request())
    }

    companion object {
        const val EVENT_REFUSED = "player.redirect.refused"
        const val EVENT_ALLOWED = "player.redirect.allowed"

        /** Shortest secret matched inside a longer path segment or value. */
        private const val MIN_SUBSTRING_LENGTH = 6

        /** Production wiring: the saved login and the device-level Settings override. */
        fun production(): RedirectGuard = RedirectGuard(
            // Runs on Media3's loader thread, only on an HTTPS to HTTP hop.
            credentials = {
                runBlocking { Graph.securedCredentials.current() }
                    ?.let { listOf(it.username, it.password) }
                    .orEmpty()
            },
            allowOverride = {
                runBlocking { Graph.settings.settings.first().allowSignInOverUnencrypted }
            },
        )

        /** True when [url] contains any of [secrets] in a path, query or user-info position. */
        fun carriesCredentials(url: HttpUrl, secrets: List<String>): Boolean =
            secrets.any { it.isNotBlank() && contains(url, it) }

        private fun contains(url: HttpUrl, secret: String): Boolean {
            // Raw (as sent on the wire) pieces, plus their decoded forms.
            val raw = buildList {
                addAll(url.encodedPathSegments)
                url.encodedQuery?.split('&')?.forEach { pair -> addAll(pair.split('=')) }
                add(url.encodedUsername)
                add(url.encodedPassword)
            }.filter { it.isNotEmpty() }
            val decoded = raw.flatMap { piece ->
                listOf(percentDecode(piece, plusIsSpace = false), percentDecode(piece, plusIsSpace = true))
            }
            val encodedForms = listOf(
                URLEncoder.encode(secret, Charsets.UTF_8.name()), // form style: space is +
                URLEncoder.encode(secret, Charsets.UTF_8.name()).replace("+", "%20"),
            )
            val longEnough = secret.length >= MIN_SUBSTRING_LENGTH
            return decoded.any { it == secret || (longEnough && it.contains(secret)) } ||
                raw.any { piece ->
                    encodedForms.any { form ->
                        piece.equals(form, ignoreCase = true) ||
                            (longEnough && piece.contains(form, ignoreCase = true))
                    }
                }
        }

        private fun percentDecode(piece: String, plusIsSpace: Boolean): String = try {
            URLDecoder.decode(if (plusIsSpace) piece else piece.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            piece // malformed escape: compare as written
        }
    }
}
