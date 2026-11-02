package com.rysal.happytv.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-54 + F-56 — the player lifecycle decision table, driven against a
 * recording engine instead of a real decoder.
 *
 * These are the behaviours review round 1 asked for and the old
 * `PlayerHandleBridgeTest` could not reach (it only exercised a null bridge, so
 * it stayed green with the whole `onStop`/`onStart` block deleted):
 *
 *  - a non-finishing `onStop` clears the surface, releases and drops the engine
 *  - `onStart` rebuilds from the cached target and resumes position + play state
 *  - a finishing `onStop` defers to the destroy path, which also clears the surface
 *  - F-56: a rebuild does not restart at 0:00 and does not force-play a paused player
 */
class PlayerLifecycleTest {

    private val finishing = MutableFlag(false)
    private val target = MutableFlag<PlaybackTarget?>(null)

    /** One entry per engine the lifecycle built, in build order. */
    private val built = mutableListOf<FakeEngine>()
    private val engineChanges = mutableListOf<PlaybackEngine?>()

    private fun newLifecycle(): PlayerLifecycle = PlayerLifecycle(
        isFinishing = { finishing.value },
        playbackTarget = { target.value },
        engineFactory = { url -> FakeEngine(url).also { built += it } },
        onEngineChanged = { engineChanges += it },
    )

    private fun start(
        mode: PlaybackMode,
        url: String = "http://panel/movie/u/p/42.mkv",
    ): PlayerLifecycle {
        target.value = PlaybackTarget(url, mode)
        return newLifecycle().also { it.onStart() }
    }

    // ------------------------------------------------- F-54: the stop paths

    @Test
    fun `background stop clears the surface before releasing`() {
        val lifecycle = start(PlaybackMode.Movie)
        val engine = built.single()

        lifecycle.onStop()

        // Order matters: the surface has to be destroyed while the window is
        // still alive, so this is an ordered list, not three booleans.
        assertEquals(
            listOf("setUrl", "prepare", "clearVideoSurface", "release"),
            engine.calls,
        )
        assertNull(lifecycle.engine)
    }

    @Test
    fun `background stop reports the dropped engine to the view layer`() {
        val lifecycle = start(PlaybackMode.Movie)

        lifecycle.onStop()

        // The Compose PlayerView and the bridge both key off this callback.
        assertEquals(listOf(built.single(), null), engineChanges)
    }

    @Test
    fun `finishing stop defers the teardown to the destroy path`() {
        finishing.value = true
        val lifecycle = start(PlaybackMode.Movie)
        val engine = built.single()

        lifecycle.onStop()
        assertEquals(listOf("setUrl", "prepare"), engine.calls)
        assertEquals(engine, lifecycle.engine)

        // The BACK-out repro: the composition disposes next, and that is where
        // the surface finally gets cleared.
        lifecycle.releaseForDestroy()
        assertEquals(
            listOf("setUrl", "prepare", "clearVideoSurface", "release"),
            engine.calls,
        )
        assertNull(lifecycle.engine)
    }

    @Test
    fun `destroy releases whether or not the activity is finishing`() {
        finishing.value = false
        val lifecycle = start(PlaybackMode.Movie)
        val engine = built.single()

        lifecycle.releaseForDestroy()

        // A carve-out on destroy would leak the surface, which is the one
        // thing F-54 is about.
        assertEquals(
            listOf("setUrl", "prepare", "clearVideoSurface", "release"),
            engine.calls,
        )
    }

    @Test
    fun `destroy with nothing attached stays quiet`() {
        val lifecycle = newLifecycle()

        lifecycle.releaseForDestroy()

        assertTrue(built.isEmpty())
        assertNull(lifecycle.engine)
    }

    // ----------------------------------------------- F-54: the start paths

    @Test
    fun `onStart with no cached url does not build an engine`() {
        target.value = null
        val lifecycle = newLifecycle()

        lifecycle.onStart()

        assertTrue(built.isEmpty())
        assertNull(lifecycle.engine)
    }

    @Test
    fun `onStart with a player already attached does not build a second one`() {
        val lifecycle = start(PlaybackMode.Movie)

        lifecycle.onStart()

        assertEquals(1, built.size)
    }

    @Test
    fun `onStart rebuilds from the cached target after a stop`() {
        val lifecycle = start(PlaybackMode.Movie)
        lifecycle.onStop()

        lifecycle.onStart()

        assertEquals(2, built.size)
        val rebuilt = built[1]
        assertEquals("http://panel/movie/u/p/42.mkv", rebuilt.url)
        assertEquals(playbackStateName(Player.STATE_READY), playbackStateName(rebuilt.playbackState))
    }

    // ----------------------------------------------------------- F-56: resume

    @Test
    fun `rebuild resumes the movie where the user left off instead of restarting`() {
        val lifecycle = start(PlaybackMode.Movie)
        built[0].currentPosition = 913_000L

        lifecycle.onStop()
        lifecycle.onStart()

        // The position rides along with the load, not a seek after it: Media3
        // only honours a pending seek before prepare, and that ordering is
        // exactly what a future edit could break.
        assertEquals(listOf(913_000L), built[1].startPositions)
        assertTrue("no post-load seek should be needed", built[1].seekedTo.isEmpty())
    }

    @Test
    fun `rebuild does not force audio onto a paused player`() {
        val lifecycle = start(PlaybackMode.Movie)
        built[0].playWhenReady = false

        lifecycle.onStop()
        lifecycle.onStart()

        assertFalse("rebuild must not resume a paused player", built[1].playWhenReady)
    }

    @Test
    fun `rebuild keeps playing a player that was playing`() {
        val lifecycle = start(PlaybackMode.Movie)
        built[0].playWhenReady = true

        lifecycle.onStop()
        lifecycle.onStart()

        assertTrue(built[1].playWhenReady)
    }

    @Test
    fun `live rebuild does not seek back behind the live edge`() {
        val lifecycle = start(PlaybackMode.Live, url = "http://panel/live/u/p/7.ts")
        built[0].currentPosition = 913_000L

        lifecycle.onStop()
        lifecycle.onStart()

        // Seeking a live stream back would strand the viewer behind the edge.
        assertEquals(0L, built[1].startPositions.single())
    }

    @Test
    fun `a position at the very start is not worth resuming from`() {
        val lifecycle = start(PlaybackMode.Movie)
        built[0].currentPosition = PlayerLifecycle.MIN_RESUME_POSITION_MILLIS - 1

        lifecycle.onStop()
        lifecycle.onStart()

        assertEquals(0L, built[1].startPositions.single())
    }

    @Test
    fun `replay resumes like a movie`() {
        val lifecycle = start(PlaybackMode.Replay, url = "http://panel/timeshift/u/p/7/2024-01-01")
        built[0].currentPosition = 600_000L

        lifecycle.onStop()
        lifecycle.onStart()

        assertEquals(listOf(600_000L), built[1].startPositions)
    }

    @Test
    fun `a first prepare after a fresh start is not a resume`() {
        start(PlaybackMode.Movie)

        // Nothing was ever stopped, so there is no captured position to restore.
        assertEquals(0L, built[0].startPositions.single())
        assertTrue("a fresh start plays", built[0].playWhenReady)
    }

    @Test
    fun `a movie or episode that starts partway loads at that position and never seeks`() {
        // #106: both play as PlaybackMode.Movie, so this is the episode path too.
        target.value = PlaybackTarget("http://panel/movie/u/p/42.mkv", PlaybackMode.Movie, startPositionMillis = 92_742L)
        newLifecycle().onStart()

        assertEquals(listOf(92_742L), built[0].startPositions)
        assertEquals(listOf("setUrl", "prepare"), built[0].calls)
        assertTrue("the start position rides the load, not a seek", built[0].seekedTo.isEmpty())
    }

    @Test
    fun `a live channel ignores a start position`() {
        target.value = PlaybackTarget("http://panel/live/u/p/7.ts", PlaybackMode.Live, startPositionMillis = 92_742L)
        newLifecycle().onStart()

        assertEquals(listOf(0L), built[0].startPositions)
    }

    @Test
    fun `a url change starts over rather than resuming`() {
        val lifecycle = start(PlaybackMode.Movie)
        built[0].currentPosition = 913_000L
        lifecycle.onStop()

        // Replay's "Go live" swaps the URL; the old position is meaningless.
        val live = PlaybackTarget("http://panel/live/u/p/7.ts", PlaybackMode.Live)
        lifecycle.prepareFor(live)

        assertEquals(0L, built[1].startPositions.single())
    }

    @Test
    fun `stop start cycles keep the most recent position`() {
        val lifecycle = start(PlaybackMode.Movie)

        built[0].currentPosition = 100_000L
        lifecycle.onStop()
        lifecycle.onStart()
        built[1].currentPosition = 500_000L
        lifecycle.onStop()
        lifecycle.onStart()

        assertEquals(listOf(100_000L), built[1].startPositions)
        assertEquals(listOf(500_000L), built[2].startPositions)
        assertNotNull(lifecycle.engine)
    }
}

// ------------------------------------------------------------------- fakes

/** Records every call in order, and the start position of each load. */
private class FakeEngine(val url: String) : PlaybackEngine {
    val calls = mutableListOf<String>()

    /** The start position each `setUrl` was asked to load at, in call order. */
    val startPositions = mutableListOf<Long>()
    val seekedTo = mutableListOf<Long>()

    override var playWhenReady: Boolean = true
    override var currentPosition: Long = 0L
    override val playbackState: Int = Player.STATE_READY

    override fun setUrl(url: String, startPositionMillis: Long) {
        calls += "setUrl"
        startPositions += startPositionMillis
    }

    override fun seekTo(positionMillis: Long) {
        seekedTo += positionMillis
    }

    override fun prepare() {
        calls += "prepare"
    }

    override fun clearVideoSurface() {
        calls += "clearVideoSurface"
    }

    override fun release() {
        calls += "release"
    }
}

/** A mutable box, named to read like Compose's `mutableStateOf` at call sites. */
private class MutableFlag<T>(var value: T)