package com.rysal.happytv.ui.browse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import com.rysal.happytv.security.ChannelLogoGuard
import com.rysal.happytv.ui.theme.LocalHappyTvDesign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Channel logos (#139). A logo replaces the monogram's initials where the provider sends a usable
 * `stream_icon`; the monogram stays underneath, so a missing, slow or broken logo never leaves an
 * empty box and nothing moves when one arrives.
 *
 * Logo hosts are third parties. A URL is requested only when [ChannelLogoUrl.safe] passes it
 * (HTTPS, no credentials), the loader refuses any non-HTTPS hop, and no URL is ever logged.
 */
object ChannelLogoUrl {
    /** Query keys that mean "this URL carries a secret" -> never sent to a third party. */
    private val CREDENTIAL_KEYS = setOf(
        "username", "user", "password", "pass", "pwd", "token", "auth", "key", "apikey", "api_key", "secret",
    )

    /** Xtream stream paths (`/live/<user>/<pass>/<id>`) are never a logo. */
    private val STREAM_PATH_ROOTS = setOf("live", "movie", "series", "timeshift", "streaming")

    private const val MAX_LENGTH = 2048
    private const val MIN_SECRET_LENGTH = 4

    /**
     * [raw] as a URL that is safe to request, or null (the monogram shows).
     *
     * Rejected: blank or over-long input, anything but `https`, user-info in the URL, credential-named
     * query keys, a stream-shaped path, and any URL that contains the signed-in [secrets]
     * (username or password, plain or percent-decoded).
     */
    fun safe(raw: String?, secrets: Collection<String> = emptyList()): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        val url = text.toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.queryParameterNames.any { it.lowercase(Locale.US) in CREDENTIAL_KEYS }) return null
        val segments = url.pathSegments
        if (segments.size >= 3 && segments.first().lowercase(Locale.US) in STREAM_PATH_ROOTS) return null
        val normalized = url.toString()
        if (secrets.isNotEmpty()) {
            val haystacks = listOf(normalized, decode(normalized)).map { it.lowercase(Locale.US) }
            for (secret in secrets) {
                if (secret.length < MIN_SECRET_LENGTH) continue
                val needle = secret.lowercase(Locale.US)
                if (haystacks.any { it.contains(needle) }) return null
            }
        }
        return normalized
    }

    private fun decode(text: String): String = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (_: IllegalArgumentException) {
        text
    }
}

/** The tile a logo sits on, chosen from the logo's own pixels so a white mark or a black one both read. */
object ChannelLogoTile {
    /** Under dark marks and mid colours. */
    const val LIGHT: Int = 0xFFEDEEF1.toInt()

    /** Under light marks (white, yellow). */
    const val DARK: Int = 0xFF23272F.toInt()

    private const val MIN_ALPHA = 40
    private const val OPAQUE_ALPHA = 250
    private const val OPAQUE_SHARE = 0.9f
    private const val NEAR_WHITE_MIN = 200
    private const val NEAR_WHITE_SPREAD = 40
    private const val NEAR_BLACK_MAX = 60
    private const val NO_WHITE = 0.01f
    private const val BLACK_DOMINATES = 2f
    private const val MIN_BLACK = 0.05f
    private const val MOSTLY_DARK = 0.5f

    /**
     * The tile colour (ARGB) for a logo's [pixels] ([width] x [height], ARGB, any size).
     * A logo that fills its whole frame (a JPEG with its own background) sits on its corner colour.
     * Otherwise the choice follows the logo's extremes, not its mean: over the visible pixels, the share
     * of near-white and of near-black ones. [DARK] unless near-black clearly dominates near-white, or
     * there is no near-white and the logo is mostly dark. Saturated mid-tones (red, blue, orange) read
     * on [DARK], so they never push toward [LIGHT].
     */
    fun pick(pixels: IntArray, width: Int, height: Int): Int {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return LIGHT
        var visible = 0
        var opaque = 0
        var white = 0
        var black = 0
        for (p in pixels) {
            val a = p ushr 24
            if (a >= OPAQUE_ALPHA) opaque++
            if (a < MIN_ALPHA) continue
            visible++
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val hi = maxOf(r, g, b)
            val lo = minOf(r, g, b)
            if (lo >= NEAR_WHITE_MIN && hi - lo <= NEAR_WHITE_SPREAD) white++
            if (hi <= NEAR_BLACK_MAX) black++
        }
        if (visible == 0) return LIGHT
        if (opaque.toFloat() / pixels.size >= OPAQUE_SHARE) {
            return corners(pixels, width, height)
        }
        val whiteShare = white.toFloat() / visible
        val blackShare = black.toFloat() / visible
        val light = if (whiteShare < NO_WHITE) {
            blackShare >= MOSTLY_DARK
        } else {
            blackShare >= MIN_BLACK && blackShare > BLACK_DOMINATES * whiteShare
        }
        return if (light) LIGHT else DARK
    }

    /** The average of a small block at each corner (a block, not one pixel, so it holds at any size). */
    private fun corners(pixels: IntArray, width: Int, height: Int): Int {
        val block = maxOf(1, minOf(width, height) / 16)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (cy in intArrayOf(0, height - block)) {
            for (cx in intArrayOf(0, width - block)) {
                for (y in cy until cy + block) {
                    for (x in cx until cx + block) {
                        val p = pixels[y * width + x]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        n++
                    }
                }
            }
        }
        if (n == 0) return LIGHT
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /**
     * The tile for [bitmap]: a full pass over every pixel (no resampling, which skips most of a large
     * image), so the answer is the same at any decode size. Runs once per logo, off the UI thread.
     */
    internal fun of(bitmap: Bitmap): Int {
        if (bitmap.isRecycled) return LIGHT
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return LIGHT
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return pick(pixels, w, h)
    }
}

/**
 * Decoded logos, ready to draw: the bitmap at the size its box needs and its tile colour, computed
 * once at decode time (#139). A cell reads this on composition and draws it with one draw call; no
 * image composable, painter or listener per cell.
 */
internal class LogoEntry(val bitmap: Bitmap, val image: ImageBitmap, val tile: Int)

internal object ChannelLogoCache {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val cache = object : LruCache<String, LogoEntry>(MAX_BYTES) {
        override fun sizeOf(key: String, value: LogoEntry): Int = value.bitmap.allocationByteCount
    }

    /** Tile colour by logo URL (not by size), so the preview slot, list and player agree with the guide. */
    private val tiles = LruCache<String, Int>(4096)

    fun get(key: String): LogoEntry? = synchronized(cache) { cache.get(key) }

    /** Fetches (disk cache first) and decodes [url] at [w] x [h]; null when it fails. Never on Main. */
    suspend fun load(context: Context, url: String, w: Int, h: Int, key: String): LogoEntry? {
        get(key)?.let { return it }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(w, h)
            .scale(Scale.FIT)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = ChannelLogoLoader.get(context).execute(request)
        val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        if (bitmap == null) {
            ChannelLogoLoader.recordFailure(url)
            return null
        }
        val entry = withContext(Dispatchers.Default) {
            // One tile per logo, whatever size it is drawn at: the first decode decides, every size reuses it.
            val tile = synchronized(tiles) { tiles.get(url) } ?: ChannelLogoTile.of(bitmap)
                .also { synchronized(tiles) { tiles.put(url, it) } }
            LogoEntry(bitmap, bitmap.asImageBitmap(), tile)
        }
        ChannelLogoLoader.recordSuccess()
        synchronized(cache) { cache.put(key, entry) }
        return entry
    }
}

/**
 * The logo ImageLoader: its own small memory cache and a disk cache, HTTPS only (a redirect to
 * HTTP, or any non-HTTPS hop, fails), no cookies, no credentials, no URL in any log.
 */
object ChannelLogoLoader {
    private const val DISK_BYTES = 48L * 1024 * 1024
    private const val FAILED_REMEMBERED = 4096
    private const val STATS_EVERY = 100

    @Volatile
    private var loader: ImageLoader? = null
    private val failed = LruCache<String, Boolean>(FAILED_REMEMBERED)
    private val ok = AtomicInteger()
    private val err = AtomicInteger()

    fun get(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: build(context.applicationContext).also { loader = it }
    }

    /** A URL that failed once this session is not asked for again until the app restarts. */
    internal fun hasFailed(url: String): Boolean = synchronized(failed) { failed.get(url) != null }

    internal fun recordFailure(url: String) {
        synchronized(failed) { failed.put(url, true) }
        count(ok = false)
    }

    internal fun recordSuccess() = count(ok = true)

    /** Counts only, every [STATS_EVERY] results: how many logos load, with no URL or host. */
    private fun count(ok: Boolean) {
        val okNow = if (ok) this.ok.incrementAndGet() else this.ok.get()
        val errNow = if (ok) this.err.get() else this.err.incrementAndGet()
        val total = okNow + errNow
        if (total % STATS_EVERY == 0) Log.i("HtvPerf", "logo.stats ok=$okNow failed=$errNow")
    }

    private fun build(context: Context): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            // https -> http (and back) is never followed.
            .followSslRedirects(false)
            .dispatcher(Dispatcher().apply { maxRequests = 8; maxRequestsPerHost = 4 })
            // Sees every hop, redirects included.
            .addNetworkInterceptor { chain ->
                if (!chain.request().url.isHttps) throw IOException("logo request is not https")
                chain.proceed(chain.request())
            }
            .build()
        return ImageLoader.Builder(context)
            .okHttpClient(client)
            .memoryCache(null)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "channel_logos"))
                    .maxSizeBytes(DISK_BYTES)
                    .build()
            }
            // Logo hosts rarely send useful cache headers; keep what we fetched.
            .respectCacheHeaders(false)
            // Needed to sample the pixels for the tile; logos are small.
            .allowHardware(false)
            .crossfade(false)
            .build()
    }
}

/**
 * The channel's logo on a tile, over [fallback] (the monogram). Same size and corner radius as the
 * box it replaces ([widthPx] x [heightPx] x [radiusPx] in design px). The fallback is always
 * drawn first and the logo only appears once it has loaded, so there is no empty box and no shift.
 */
@Composable
fun HtChannelLogo(
    logoUrl: String?,
    widthPx: Int,
    heightPx: Int,
    radiusPx: Int,
    modifier: Modifier = Modifier,
    fallback: @Composable BoxScope.() -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val url = remember(logoUrl) {
        ChannelLogoUrl.safe(logoUrl, ChannelLogoGuard.secrets)?.takeUnless { ChannelLogoLoader.hasFailed(it) }
    }
    Box(modifier = modifier.size(d.px(widthPx), d.px(heightPx))) {
        fallback()
        if (url != null) {
            ChannelLogoLayer(url, widthPx, heightPx, radiusPx)
        }
    }
}

/** A logo is requested only after its cell has been on screen this long, so a fast scroll asks for nothing. */
private const val SETTLE_MS = 150L

@Composable
private fun ChannelLogoLayer(url: String, widthPx: Int, heightPx: Int, radiusPx: Int) {
    val context = LocalContext.current
    val d = LocalHappyTvDesign.current
    val density = LocalDensity.current
    val w = with(density) { d.px(widthPx).roundToPx() }
    val h = with(density) { d.px(heightPx).roundToPx() }
    val key = remember(url, w, h) { "$url@${w}x$h" }
    // One state per cell, set once: a cached logo is there on the first frame, the rest arrive
    // after the settle delay.
    var entry by remember(key) { mutableStateOf(ChannelLogoCache.get(key)) }
    if (entry == null) {
        LaunchedEffect(key) {
            delay(SETTLE_MS)
            entry = ChannelLogoCache.load(context, url, w, h, key)
        }
    }
    val e = entry ?: return
    val radius = with(density) { d.px(radiusPx).toPx() }
    val inset = with(density) { d.px(maxOf(4, heightPx / 10)).toPx() }
    Box(
        modifier = Modifier
            .size(d.px(widthPx), d.px(heightPx))
            .drawBehind {
                drawRoundRect(Color(e.tile), cornerRadius = CornerRadius(radius, radius))
                val bw = e.bitmap.width
                val bh = e.bitmap.height
                val scale = minOf((size.width - 2 * inset) / bw, (size.height - 2 * inset) / bh)
                val dw = (bw * scale).roundToInt().coerceAtLeast(1)
                val dh = (bh * scale).roundToInt().coerceAtLeast(1)
                drawImage(
                    image = e.image,
                    srcSize = IntSize(bw, bh),
                    dstOffset = IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt()),
                    dstSize = IntSize(dw, dh),
                    filterQuality = FilterQuality.Medium,
                )
            },
    )
}

/** The channel's logo, or its [HtMonogram] until (or unless) one loads. The one call every list uses. */
@Composable
fun HtChannelMark(
    name: String,
    logoUrl: String?,
    widthPx: Int,
    heightPx: Int,
    radiusPx: Int,
    modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle? = null,
) {
    HtChannelLogo(logoUrl, widthPx, heightPx, radiusPx, modifier) {
        HtMonogram(
            name = name,
            widthPx = widthPx,
            heightPx = heightPx,
            radiusPx = radiusPx,
            textStyle = textStyle,
        )
    }
}
