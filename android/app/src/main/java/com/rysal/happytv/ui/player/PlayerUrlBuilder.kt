package com.rysal.happytv.ui.player

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Xtream stream URL builder for the player — the Xtream Codes player API
 * §3.1-3.4 (live/movie/series) and §4.2 (timeshift replay).
 *
 * The API never returns playable URLs; every playback URL is constructed here:
 *
 *   live:     /live/{user}/{pass}/{stream_id}.{ext}                    (§3.1)
 *   movie:    /movie/{user}/{pass}/{stream_id}.{container_extension}   (§3.2)
 *   episode:  /series/{user}/{pass}/{episode_id}.{ext}                 (§3.3)
 *   replay:   /timeshift/{user}/{pass}/{min}/{YYYY-MM-DD:HH-MM}/{id}.ts (§4.2)
 *
 * House rules:
 *  - Credentials go through [URLEncoder] — the protocol doesn't demand it, but
 *    a panel that chokes on a `+` or space in the password is the most common
 *    first-login playback failure.
 *  - The replay start stamp is formatted **in UTC** from the EPG start
 *    timestamp (§4.3), as `YYYY-MM-DD:HH-MM`.
 *  - `.m3u8` is preferred for live when the account allows it (§3.4: ABR only
 *    exists on HLS); otherwise `.ts`.
 */
object PlayerUrlBuilder {

    /** HLS — adaptive; only when `user_info.allowed_output_formats` allows it. */
    const val HLS_EXTENSION = "m3u8"

    /** MPEG-TS — the universal fallback for live and the only replay extension. */
    const val TS_EXTENSION = "ts"

    /** `live/{user}/{pass}/{id}.{ext}` (protocol §3.1). */
    fun live(
        origin: String,
        username: String,
        password: String,
        streamId: String,
        extension: String = HLS_EXTENSION,
    ): String {
        val ext = normalizeExt(extension, TS_EXTENSION)
        return "${trimOrigin(origin)}/live/${enc(username)}/${enc(password)}/$streamId.$ext"
    }

    /** `movie/{user}/{pass}/{id}.{container_extension}` (protocol §3.2). */
    fun movie(
        origin: String,
        username: String,
        password: String,
        streamId: String,
        containerExtension: String,
    ): String {
        val ext = normalizeExt(containerExtension, "")
        require(ext.isNotEmpty()) {
            "VOD URL requires a non-empty container extension (mp4/mkv/...); " +
                "pass VodStream.containerExtension verbatim."
        }
        return "${trimOrigin(origin)}/movie/${enc(username)}/${enc(password)}/$streamId.$ext"
    }

    /** `series/{user}/{pass}/{episode_id}.{ext}` (protocol §3.3). */
    fun episode(
        origin: String,
        username: String,
        password: String,
        episodeId: String,
        containerExtension: String,
    ): String {
        val ext = normalizeExt(containerExtension, "mp4")
        return "${trimOrigin(origin)}/series/${enc(username)}/${enc(password)}/$episodeId.$ext"
    }

    /**
     * `timeshift/{user}/{pass}/{duration_minutes}/{YYYY-MM-DD:HH-MM}/{id}.ts`
     * (protocol §4.2) — the **Replay** URL behind "Watch from the start".
     *
     * @param startUtcMillis the program's start (EPG `start_timestamp`, epoch
     *   millis); formatted in UTC, per §4.3.
     * @param durationMinutes catch-up window length in whole minutes, at least 1.
     */
    fun replay(
        origin: String,
        username: String,
        password: String,
        streamId: String,
        startUtcMillis: Long,
        durationMinutes: Int,
    ): String {
        val minutes = durationMinutes.coerceAtLeast(1)
        val start = TIMEShift_FORMAT.format(Instant.ofEpochMilli(startUtcMillis))
        return "${trimOrigin(origin)}/timeshift/${enc(username)}/${enc(password)}/" +
            "$minutes/$start/$streamId.$TS_EXTENSION"
    }

    /**
     * The extension to request for a live stream (protocol §3.1 + §3.4):
     * `.m3u8` when `user_info.allowed_output_formats` lists it (that is the
     * only route to adaptive bitrate), `.ts` otherwise.
     *
     * An absent/empty list means "we don't know what the account allows" —
     * that happens before the first refresh caches `user_info`; HLS is the
     * provider default then, matching the Settings "Stream format" default.
     */
    fun liveExtension(
        allowedOutputFormats: List<String>?,
        preferred: String = HLS_EXTENSION,
    ): String {
        val allowed = allowedOutputFormats?.map { it.trim().lowercase(Locale.US) }.orEmpty()
        if (allowed.isEmpty()) return normalizeExt(preferred, HLS_EXTENSION)
        return when {
            HLS_EXTENSION in allowed -> HLS_EXTENSION
            TS_EXTENSION in allowed -> TS_EXTENSION
            // Unknown-only lists (e.g. ["mp4"]): stay on the preferred format.
            else -> normalizeExt(preferred, HLS_EXTENSION)
        }
    }

    /** `YYYY-MM-DD:HH-MM` in UTC — the one time format the panel accepts. */
    internal val TIMEShift_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm", Locale.US).withZone(ZoneOffset.UTC)

    /**
     * The extension [movie] gets, resolved from the catalog row first and the
     * launch second (F-51): always a value that survives normalization, so a
     * junk `container_extension` on the row (`"."`, `" "`) falls back to `mp4`
     * instead of tripping [movie]'s `require` and killing the load coroutine.
     */
    fun movieExtension(rowExtension: String?, launchExtension: String?): String =
        listOfNotNull(rowExtension, launchExtension)
            .firstOrNull { normalizeExt(it, "").isNotEmpty() }
            ?.let { normalizeExt(it, "mp4") }
            ?: "mp4"

    /**
     * A play URL safe to log: the credential path segments of
     * `/live|movie|series|timeshift/{user}/{pass}/…` and any `password=` /
     * `username=` query parameter become `***` (netsec F-10 — never log
     * credentials). Route, stream id and extension stay visible, which is
     * exactly what "did the right URL reach the network?" needs to see.
     */
    fun redactCredentials(url: String): String = QUERY_CREDENTIALS.replace(
        PATH_CREDENTIALS.replace(url, "$1***/***/"),
        "$1***",
    )

    /** `/movie/u/p/…` → `/movie/<masked>/<masked>/…` (also live/series/timeshift). */
    private val PATH_CREDENTIALS =
        Regex("(/(?:live|movie|series|timeshift)/)[^/?#]+/[^/?#]+/")

    /** `?password=…` / `&username=…` (direct_source can carry these). */
    private val QUERY_CREDENTIALS =
        Regex("([?&](?:password|pass|pwd|username|user)=)[^&#]*", RegexOption.IGNORE_CASE)

    private fun normalizeExt(value: String, fallback: String): String {
        val trimmed = value.trim().trimStart('.').trim()
        return if (trimmed.isEmpty()) fallback else trimmed.lowercase(Locale.US)
    }

    /** Origins arrive as `scheme://host[:port]`; tolerate a stray trailing slash. */
    private fun trimOrigin(origin: String): String = origin.trim().trimEnd('/')

    private fun enc(credential: String): String =
        // URLEncoder is form-encoding: it maps a space to '+', which is a
        // literal plus inside a URL *path*. Rewrite to %20 so passwords with
        // spaces survive the panel's nginx path rewrite.
        URLEncoder.encode(credential, Charsets.UTF_8.name()).replace("+", "%20")
}
