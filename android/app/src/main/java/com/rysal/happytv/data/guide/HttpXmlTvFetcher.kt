package com.rysal.happytv.data.guide

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the XMLTV file with the guards the tasks ask for
 * (§6 + F-52): an explicit **last-modified check**, an **OkHttp
 * `Cache(directory, maxSize)`** in front of the network, a **payload-size
 * guard** on the response, and a **staged copy on disk** so the database
 * replace can be atomic (see below).
 *
 * Conditional GET: the validator we last saw (`Last-Modified`, and `ETag`
 * when the server sends one) goes back as `If-Modified-Since` /
 * `If-None-Match`. A 304 means "your stored copy is current" — the caller
 * skips the multi-megabyte parse entirely ([stageIfModified] returns null).
 * The OkHttp cache additionally short-circuits to a locally fresh response
 * when the server sends cache directives (OkHttp performs its own
 * conditional revalidation on stale-but-validated entries).
 *
 * **Size guard (F-52)**: a panel with 39k channels can serve XMLTV in the
 * hundreds of megabytes. A `Content-Length` over [maxBytes] is refused
 * before a single byte of the body is read, and the staged copy counts
 * bytes as it goes, so a chunked/lying response cannot blow past the cap
 * either. The cap is deliberately generous versus the ~86 MB guide
 * file QA measured; a genuinely larger feed is refused loudly (and the
 * last-good guide stays intact) instead of filling the device's cache
 * partition.
 *
 * **Staged copy (F-52 "cache the last-good guide")**: the body is written
 * to a temp file first and only then handed to the parser. A dropped
 * connection mid-download deletes the temp file and leaves the stored
 * guide untouched — the old stream-straight-to-Room path had already run
 * `deleteAll()` by then, so one flaky refresh wiped the guide the user was
 * watching. The caller parses the staged file inside a Room transaction
 * (`EpgProgramDao.replaceAllStreamed`), which covers the other failure
 * mode (bad XML mid-parse).
 *
 * State lives in a private `SharedPreferences` rather than `AppSettings`:
 * this is fetcher plumbing, not a user setting, and keeping it out of the
 * shared settings file keeps this task off the settings lane's hotspot.
 *
 * @param maxBytes hard cap on the XMLTV payload, counted on the wire.
 *   Injectable so tests can exercise the guard without moving real
 *   hundreds-of-megabytes fixtures.
 */
class HttpXmlTvFetcher(
    context: Context,
    private val maxBytes: Long = MAX_XMLTV_BYTES,
) {

    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences("guide_xmltv_cache", Context.MODE_PRIVATE)

    /**
     * Own client (not `Graph.okHttpClient`): the response cache is specific
     * to this one multi-MB file, and putting a `Cache` on the shared client
     * would start caching panel API responses too.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(appContext.cacheDir, CACHE_DIR), CACHE_MAX_BYTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Downloads the guide file at [url] into a staged temp file unless the
     * server confirms it is unchanged.
     *
     * @return the staged file — the caller must `delete()` it after use —
     *   or **null when the server answered 304** (nothing to parse this
     *   round).
     * @throws IOException on transport failure, a non-2xx/304 status, or a
     *   payload larger than [maxBytes].
     * @throws IllegalArgumentException when [url] is not a valid HTTP(S) URL.
     */
    suspend fun stageIfModified(url: String): File? = withContext(Dispatchers.IO) {
        val input = openIfModified(url) ?: return@withContext null
        val dir = File(appContext.cacheDir, STAGE_DIR)
        dir.mkdirs()
        val staged = File(dir, STAGE_FILE)
        if (staged.exists()) staged.delete()
        try {
            var written = 0L
            input.use { src ->
                staged.outputStream().use { dst ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = src.read(buffer)
                        if (read < 0) break
                        written += read
                        // Belt and braces for chunked responses: the header
                        // guard in [openIfModified] already checked
                        // Content-Length when the server sent one.
                        if (written > maxBytes) {
                            throw IOException(
                                "XMLTV payload exceeds the $maxBytes byte limit — refusing the guide file",
                            )
                        }
                        dst.write(buffer, 0, read)
                    }
                }
            }
            staged
        } catch (t: Throwable) {
            staged.delete()
            throw t
        }
    }

    /**
     * Opens the guide file at [url] unless the server confirms it is
     * unchanged. Internal plumbing for [stageIfModified] — the stream can
     * only be read once, and only inside the staged copy.
     *
     * @return the response body stream (caller closes it), or **null when
     * the server answered 304** — nothing to parse this round.
     * @throws IOException on transport failure, a non-2xx/304 status, or a
     *   `Content-Length` over [maxBytes].
     * @throws IllegalArgumentException when [url] is not a valid HTTP(S) URL.
     */
    private suspend fun openIfModified(url: String): InputStream? = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        prefs.getString(KEY_LAST_MODIFIED, null)?.let { builder.header(IF_MODIFIED_SINCE, it) }
        prefs.getString(KEY_ETAG, null)?.let { builder.header(IF_NONE_MATCH, it) }

        val response = client.newCall(builder.build()).execute()
        when {
            response.code == 304 -> {
                response.close()
                Log.i(TAG, "XMLTV not modified (304) — keeping the stored copy")
                null
            }
            !response.isSuccessful -> {
                response.close()
                throw IOException("XMLTV fetch failed: HTTP ${response.code} for ${redact(url)}")
            }
            else -> {
                val body = response.body
                if (body == null) {
                    response.close()
                    throw IOException("XMLTV fetch returned an empty body for ${redact(url)}")
                }
                // F-52: check Content-Length before promising anything — a
                // misconfigured panel serving multi-GB XMLTV is refused
                // before it can fill the cache partition. (A body of
                // unknown length is bounded while it streams instead.)
                val contentLength = body.contentLength()
                if (contentLength > maxBytes) {
                    response.close()
                    throw IOException(
                        "XMLTV payload too large: $contentLength bytes (limit $maxBytes) " +
                            "for ${redact(url)}",
                    )
                }
                // Keep the validators for the next run before handing the
                // stream over (closing the stream closes the response).
                response.header("Last-Modified")?.let { prefs.edit().putString(KEY_LAST_MODIFIED, it).apply() }
                response.header("ETag")?.let { prefs.edit().putString(KEY_ETAG, it).apply() }
                body.byteStream()
            }
        }
    }

    /**
     * Drops the stored validators so the next [stageIfModified] performs a
     * full download (used when the store is empty but a 304 would
     * otherwise claim the stored copy — of nothing — is current).
     */
    fun clearValidators() {
        prefs.edit().remove(KEY_LAST_MODIFIED).remove(KEY_ETAG).apply()
    }

    /**
     * When (epoch millis) a guide file was last promoted into the store, or 0
     * if never. Panels that ignore conditional GET answer 200 every time, so
     * the validators alone cannot tell "nothing changed" from "never
     * imported"; this stamp lets a cold start skip a pointless re-import (#35).
     */
    fun lastImportedAtMillis(): Long = prefs.getLong(KEY_LAST_IMPORTED_AT, 0L)

    /** Record that a guide import was promoted at [nowMillis]. */
    fun markImported(nowMillis: Long) {
        prefs.edit().putLong(KEY_LAST_IMPORTED_AT, nowMillis).apply()
    }

    companion object {
        private const val TAG = "HttpXmlTvFetcher"
        private const val CACHE_DIR = "xmltv_http"
        private const val CACHE_MAX_BYTES = 25L * 1024 * 1024
        private const val KEY_LAST_MODIFIED = "last_modified"
        private const val KEY_ETAG = "etag"
        private const val KEY_LAST_IMPORTED_AT = "last_imported_at"
        private const val IF_MODIFIED_SINCE = "If-Modified-Since"
        private const val IF_NONE_MATCH = "If-None-Match"

        /** Staging area under `cacheDir` — a stale file is rewritten next run. */
        private const val STAGE_DIR = "guide_xmltv_stage"
        private const val STAGE_FILE = "pending.xml"
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /**
         * Hard payload cap (256 MB). QA measured the real guide file at
         * ~86 MB; 3× headroom keeps a growing panel working while a
         * misconfigured one (multi-GB) is refused instead of filling the
         * Chromecast's cache partition. Streaming parse means memory stays
         * bounded either way — this cap is about disk and time, not heap.
         */
        const val MAX_XMLTV_BYTES = 256L * 1024 * 1024

        /** Log/exception-safe URL: the query string carries `password=…`. */
        private fun redact(url: String): String = url.substringBefore('?') + "?…"
    }
}
