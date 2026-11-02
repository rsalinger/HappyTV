package com.rysal.happytv.ui.player

import android.content.Context
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * F-54 — the player/boundary rules for a backgrounded player session, with
 * every platform type behind a lambda so the whole decision table is unit
 * testable without Robolectric, a device, or a real decoder.
 *
 * ## What this fixes
 *
 * Two problems, both caught in review round 1:
 *
 * 1. A session that ends by BACK never reaches the `onStop` release
 *    (`isFinishing` short-circuits it), so the *destroy* path has to tear the
 *    player down too. Review was right that hardening one path and not the
 *    other leaves the filed repro untouched.
 * 2. The rebuild re-prepared with `playWhenReady = true` and no seek (F-56), so
 *    any non-finishing stop → start cycle — display sleep while watching, the
 *    TV screensaver, a system dialog — restarted a movie at 0:00 and started
 *    audio over a player the user had paused.
 *
 * Rather than duplicate "clear surface → release → detach" across call sites
 * with a chance to drift, the whole table lives here:
 *
 * | trigger                     | behaviour                                        |
 * |-----------------------------|--------------------------------------------------|
 * | `onStop` + finishing        | no-op — [releaseForDestroy] owns it               |
 * | `onStop` (Home / sleep)     | remember position + play state, clear surface, release, detach |
 * | `onStart`                   | rebuild from the cached target at the resume position, restore play state |
 * | destroy (any trigger)       | always clear surface, release, detach             |
 * | URL changes (first Ready, Go live) | re-prepare the attached engine            |
 *
 * ## Why the resume state is captured here and not in the ViewModel
 *
 * `PlayerViewModel.onPosition` gets a position every second, but it only
 * records it for [PlaybackMode.Movie] (live/replay progress is computed from
 * the EPG window, not the playhead) and it can be up to a second stale when
 * the stop lands between ticks. Sampling the engine directly inside the stop
 * is both exact and mode-agnostic, so that is what the resume state uses.
 *
 * Live playback deliberately does **not** resume from a position: a live
 * stream moves at 1.0x, so restoring a stale position would leave the viewer
 * permanently behind the live edge instead of where they were watching. Movie
 * and replay are fixed-length streams where the position means what it says.
 */
internal class PlayerLifecycle(
    /** Live read of `Activity.isFinishing`. */
    private val isFinishing: () -> Boolean,
    /** The cached playback target, or null while the ViewModel is still Loading. */
    private val playbackTarget: () -> PlaybackTarget?,
    /** Builds a fresh engine for one URL and wires the listeners/bridge to it. */
    private val engineFactory: (String) -> PlaybackEngine,
    /** Notified whenever the attached engine changes (built or dropped). */
    private val onEngineChanged: (PlaybackEngine?) -> Unit,
) {

    companion object {
        private const val TAG = "PlayerF48"

        /** F-56: below this a position is "the beginning", nothing worth resuming from. */
        const val MIN_RESUME_POSITION_MILLIS = 1_000L
    }

    /** The attached engine, or null between a background stop and the next start. */
    var engine: PlaybackEngine? = null
        private set

    /** Where playback was when the player was dropped (F-56). */
    private var resumePositionMillis = 0L

    /** Whether the user had paused, so the rebuild doesn't force audio on them. */
    private var resumePlayWhenReady = true

    /** The URL currently loaded, so a prepare can tell a resume from a fresh start. */
    private var preparedUrl: String? = null

    /**
     * Set by a backgrounding [onStop], consumed by the next [prepareFor]. While
     * it is set the position and play state captured at stop time are ours to
     * restore; a first-run prepare (no stop behind it) is not a resume.
     */
    private var resumePending = false

    /**
     * `Activity.onStop`. No-op while finishing (the destroy path owns it).
     */
    fun onStop() {
        if (isFinishing()) {
            Log.i(TAG, "onStop: finishing → releaseForDestroy() owns the teardown")
            return
        }
        val attached = engine ?: run {
            Log.i(TAG, "onStop: nothing attached → no-op")
            return
        }
        resumePositionMillis = attached.currentPosition
        resumePlayWhenReady = attached.playWhenReady
        resumePending = true
        Log.i(TAG, "onStop: dropping player — resumeAt=${resumePositionMillis}ms " +
            "playWhenReady=$resumePlayWhenReady")
        releaseAndDetach(attached)
    }

    /**
     * `Activity.onStart`. Rebuilds only when the player was dropped by [onStop].
     *
     * On the very first frame after `onCreate` there is nothing attached and no
     * cached URL yet, so this is a no-op — [prepareFor] runs instead when the
     * ViewModel reaches `Ready`. Both paths funnel through [prepareFor], so a
     * player is never prepared twice for the same URL.
     */
    fun onStart() {
        if (engine != null) {
            Log.i(TAG, "onStart: player already attached → nothing to rebuild")
            return
        }
        val target = playbackTarget() ?: run {
            Log.i(TAG, "onStart: no cached URL yet → skip rebuild (prepareFor will run)")
            return
        }
        prepareFor(target)
    }

    /**
     * The one prepare path. Builds an engine when none is attached, points it
     * at the URL's media source, restores the resume position/play state for a
     * fixed-length stream, and prepares.
     */
    fun prepareFor(target: PlaybackTarget) {
        val attached = engine ?: engineFactory(target.url).also { created ->
            Log.i(TAG, "prepareFor: built a fresh engine for ${PlayerUrlBuilder.redactCredentials(target.url)}")
            engine = created
            onEngineChanged(created)
        }
        // A resume only counts when the *same* stream is being reloaded; a URL
        // change (first Ready, or Replay's Go live) starts over at 0:00.
        val isResume = resumePending && preparedUrl == target.url
        resumePending = false
        preparedUrl = target.url

        // Only fixed-length streams resume: seeking a live stream back would
        // leave the viewer permanently behind the live edge ([PlayerPolicy]).
        val startAt = if (isResume &&
            PlayerPolicy.resumeOnStart(target.mode) &&
            resumePositionMillis >= MIN_RESUME_POSITION_MILLIS
        ) {
            resumePositionMillis
        } else if (target.mode == PlaybackMode.Movie) {
            // A first start can still begin partway in: details' Resume (#78).
            target.startPositionMillis.coerceAtLeast(0L)
        } else {
            0L
        }
        attached.setUrl(target.url, startAt)
        if (startAt > 0L) {
            Log.i(TAG, "prepareFor: startAt=${startAt}ms — resume where the user left off")
        }

        attached.playWhenReady = if (isResume) resumePlayWhenReady else true
        attached.prepare()
        Log.i(TAG, "prepareFor: prepared url=${PlayerUrlBuilder.redactCredentials(target.url)} " +
            "playWhenReady=${attached.playWhenReady} startAt=${startAt}ms")
    }

    /**
     * Composition dispose — i.e. the activity is going away for good, by BACK,
     * by `finish()` from a terminal state, or by the system reclaiming it.
     * Unconditional (no finishing carve-out): the surface has to be
     * torn down before the window leaves SurfaceFlinger.
     */
    fun releaseForDestroy() {
        val attached = engine ?: run {
            Log.i(TAG, "releaseForDestroy: nothing attached → no-op")
            return
        }
        Log.i(TAG, "releaseForDestroy: clearing surface → releasing player")
        releaseAndDetach(attached)
    }

    /** `clearVideoSurface()` before `release()` so the surface is destroyed
     *  while the window is still alive, then drop it from the view layer.
     *
     * [preparedUrl] deliberately survives: it records *which* stream this
     *  session was on, and the rebuild has to know the URL is unchanged to
     *  treat the next prepare as a resume rather than a fresh start. */
    private fun releaseAndDetach(attached: PlaybackEngine) {
        attached.clearVideoSurface()
        attached.release()
        engine = null
        onEngineChanged(null)
    }
}

/**
 * The URL plus the mode it was resolved for — the two facts a rebuild needs.
 * Live matters: only fixed-length streams (movie, replay) seek back.
 */
internal data class PlaybackTarget(val url: String, val mode: PlaybackMode, val startPositionMillis: Long = 0L)

/**
 * The slice of `ExoPlayer` this package's lifecycle touches, so tests can
 * drive [PlayerLifecycle] against a recorder instead of a real decoder. The
 * URL crosses as a `String` rather than a Media3 `MediaSource` precisely so
 * the whole table is testable on a plain JVM — no Robolectric, no decoder.
 */
internal interface PlaybackEngine {
    var playWhenReady: Boolean
    val currentPosition: Long
    val playbackState: Int

    /**
     * Points the engine at a stream, starting at [startPositionMillis].
     *
     * The start position rides along with the media source rather than a
     * separate `seekTo()`: `setMediaSource(mediaSource, startPositionMs)`
     * is Media3's API for exactly this, and it stays correct regardless of
     * whether `prepare()` has run yet. A `seekTo()` issued before prepare has
     * to be honoured as a pending seek, and that ordering is an easy thing to
     * get wrong.
     */
    fun setUrl(url: String, startPositionMillis: Long)
    fun seekTo(positionMillis: Long)
    fun prepare()
    fun clearVideoSurface()
    fun release()
}

/** The production [PlaybackEngine]: Media3, one player per launch. */
internal class ExoPlayerEngine(
    private val context: Context,
    val player: ExoPlayer,
) : PlaybackEngine {

    override var playWhenReady: Boolean
        get() = player.playWhenReady
        set(value) {
            player.playWhenReady = value
        }

    override val currentPosition: Long get() = player.currentPosition

    override val playbackState: Int get() = player.playbackState

    override fun setUrl(url: String, startPositionMillis: Long) {
        val mediaSource = PlayerFactory.mediaSource(context, url)
        if (startPositionMillis > 0L) {
            player.setMediaSource(mediaSource, startPositionMillis)
        } else {
            player.setMediaSource(mediaSource)
        }
    }

    override fun seekTo(positionMillis: Long) = player.seekTo(positionMillis)

    override fun prepare() = player.prepare()

    override fun clearVideoSurface() = player.clearVideoSurface()

    override fun release() = player.release()
}

/** Readable in a test failure: the state name behind [PlaybackEngine.playbackState]. */
internal fun playbackStateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "STATE_IDLE"
    Player.STATE_BUFFERING -> "STATE_BUFFERING"
    Player.STATE_READY -> "STATE_READY"
    Player.STATE_ENDED -> "STATE_ENDED"
    else -> "UNKNOWN($state)"
}