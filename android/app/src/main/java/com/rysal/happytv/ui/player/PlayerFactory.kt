@file:OptIn(androidx.media3.common.util.UnstableApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.rysal.happytv.ui.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * The Media3 pipeline — design spec task §4. Three production decisions live
 * here, all matching common IPTV players:
 *
 *  1. **Extension renderers ON** — `EXTENSION_RENDERER_MODE_ON` picks up the
 *     FFmpeg audio decoder when its native libs are side-loaded; without it,
 *     odd provider audio codecs (AC3/EAC3/DTS on many channels) fail where
 *     other players cope.
 *  2. **IPTV-tuned [DefaultLoadControl]** — the defaults (2.5 s/50 s/2.5 s)
 *     rebuffer constantly on flaky panel links; 15 s minimum buffer with a
 *     2 s playback start gives the stream room to breathe.
 *  3. **HLS gets an explicit [HlsMediaSource]** (master-playlist variant
 *     selection), everything else goes through [DefaultMediaSourceFactory] so
 *     ExoPlayer sniffs raw TS/MP4/MKV (protocol §3.4: `.m3u8` is the only
 *     ABR route; `.ts` is single-bitrate).
 */
object PlayerFactory {
    private const val TAG = "PlayerF48"

    /** ExoPlayer with extension renderers and the IPTV load control. */
    fun build(context: Context): ExoPlayer {
        Log.i(TAG, "PlayerFactory.build() called")
        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
            )
            .setLoadControl(iptvLoadControl())
            .build()
        Log.i(TAG, "PlayerFactory.build() → ExoPlayer created: playbackState=${player.playbackState}")
        return player
    }

    /** min 15 s / max 30 s / start after 2 s / 4 s after a rebuffer. */
    fun iptvLoadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 15_000,
                /* maxBufferMs */ 30_000,
                /* bufferForPlaybackMs */ 2_000,
                /* bufferForPlaybackAfterRebufferMs */ 4_000,
            )
            .build()

    /**
     * The media source for one playback URL: HLS playlists get
     * [HlsMediaSource]; raw TS and file formats go through the default factory
     * (ExoPlayer sniffs containers).
     *
     * Both paths share [redirectTolerantDataSourceFactory] — one factory, one
     * redirect behaviour (F-51). [PlayerNetworkLog] rides it so every HTTP
     * request the playback makes is provable from logcat (F-51).
     */
    fun mediaSource(context: Context, url: String): MediaSource {
        Log.i(TAG, "PlayerFactory.mediaSource(url=${PlayerUrlBuilder.redactCredentials(url)}) isHls=${isHlsUrl(url)}")
        val mediaItem = MediaItem.fromUri(url)
        val dataSourceFactory = redirectTolerantDataSourceFactory(context)
        return if (isHlsUrl(url)) {
            Log.i(TAG, "PlayerFactory.mediaSource → HlsMediaSource")
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            Log.i(TAG, "PlayerFactory.mediaSource → DefaultMediaSourceFactory")
            DefaultMediaSourceFactory(dataSourceFactory, CuelessSeekingExtractorsFactory(DefaultExtractorsFactory()))
                .createMediaSource(mediaItem)
        }
    }

    /**
     * The one playback [DataSource.Factory] — used by both the HLS and the
     * raw-container paths so redirect behaviour can't drift apart again.
     *
     * **HTTPS to HTTP redirects are followed, with one veto (#21).** Xtream
     * panels commonly front an `https` panel with a plain `http` CDN: the panel
     * answers `/movie/...` with `302 Location: http://cdn.example/play/movie/...`.
     * Media3's default data source refuses that downgrade, so every VOD play
     * died on the wire even though `curl -L` gets the video (F-51); other
     * IPTV players follow these, so we do too. But a redirect whose target
     * carries the username or password would send them without encryption, so
     * [RedirectGuard] (an OkHttp network interceptor, run before each hop is
     * sent) refuses that case unless the Settings override is on. HTTPS to
     * HTTPS, an HTTP origin, and an HTTPS to HTTP hop without credentials are
     * unaffected.
     *
     * [PlayerNetworkLog] rides the shared data source so every HTTP request
     * the playback makes is provable from logcat (F-51).
     */
    internal fun redirectTolerantDataSourceFactory(
        context: Context,
        guard: RedirectGuard = defaultGuard,
    ): DataSource.Factory {
        val httpFactory = OkHttpDataSource.Factory(okHttpClient(guard))
            .setUserAgent("ExoPlayerLib/${MediaLibraryInfo.VERSION}")
        return DefaultDataSource.Factory(context, httpFactory)
            .setTransferListener(PlayerNetworkLog())
    }

    private val defaultGuard: RedirectGuard by lazy { RedirectGuard.production() }

    private val defaultClient: OkHttpClient by lazy { buildClient(defaultGuard) }

    private fun okHttpClient(guard: RedirectGuard): OkHttpClient =
        if (guard === defaultGuard) defaultClient else buildClient(guard)

    private fun buildClient(guard: RedirectGuard): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS) // DefaultHttpDataSource parity
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .followSslRedirects(true) // the guard, not OkHttp, decides which downgrades are safe
            .addNetworkInterceptor(guard)
            .build()

    /** `.m3u8` (query strings stripped) is the HLS marker; everything else isn't. */
    fun isHlsUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#')
            .endsWith(".m3u8", ignoreCase = true)
}
