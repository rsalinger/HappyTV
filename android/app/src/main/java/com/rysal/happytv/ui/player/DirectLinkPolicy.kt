package com.rysal.happytv.ui.player

import com.rysal.happytv.data.network.RedirectGuard
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which provider-supplied `direct_source` links the player may use (#238).
 *
 * A link is used only when it is an `http` or `https` address. A plain-`http` link is also used
 * when the sign-in itself is plain HTTP (the household consented), when it does not carry the
 * username or password, or when Settings > Player's override is on. The "carries the sign-in
 * details" test is [RedirectGuard.carriesCredentials], the same one the redirect guard applies.
 * Anything else is ignored and the caller builds the standard `/movie/...` URL, so a rejected
 * link never stops playback. HTTPS-only would break plain-HTTP CDNs that play today.
 */
internal object DirectLinkPolicy {

    enum class Verdict { USED, NOT_WEB_ADDRESS, CREDENTIALS_OVER_HTTP }

    class Result(val url: String?, val verdict: Verdict)

    /**
     * [raw] (trimmed) in [Result.url] if it may be played, else null. [override] is read only when
     * the link is plain HTTP, carries the sign-in details and the sign-in is HTTPS, the one case
     * it decides.
     */
    suspend fun check(
        raw: String?,
        signInIsHttps: Boolean,
        username: String,
        password: String,
        override: suspend () -> Boolean,
    ): Result {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Result(null, Verdict.NOT_WEB_ADDRESS)
        // OkHttp accepts only http and https (any letter case); file:, content:, data: and the rest parse to null.
        val url = text.toHttpUrlOrNull() ?: return Result(null, Verdict.NOT_WEB_ADDRESS)
        if (url.isHttps || !signInIsHttps) return Result(text, Verdict.USED)
        if (!RedirectGuard.carriesCredentials(url, listOf(username, password))) return Result(text, Verdict.USED)
        return if (override()) Result(text, Verdict.USED) else Result(null, Verdict.CREDENTIALS_OVER_HTTP)
    }
}
