package com.rysal.happytv.ui.player

import android.app.Application
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.BackWhileWatching
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [PlaybackHost] wiring (#28): the ViewModel's Ready state reaches the engine,
 * zapping re-points the same engine, and the F-54/F-56 stop/start rules hold
 * when driven through the host rather than [PlayerLifecycle] directly.
 * A recording engine stands in for Media3; no decoder is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlaybackHostTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val built = mutableListOf<HostFakeEngine>()
    private var finishing = false

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newHost(launch: PlayerLaunch): PlaybackHost {
        val vm = PlayerViewModel(launch, HostFakeRepository(), HostFakeHandle())
        val host = PlaybackHost(
            context = ApplicationProvider.getApplicationContext(),
            scope = backgroundScope,
            viewModel = vm,
            handle = PlayerHandleBridge(),
            isFinishing = { finishing },
            engineFactory = { url -> HostFakeEngine(url).also { built += it } },
        )
        host.attach()
        testScheduler.runCurrent()
        return host
    }

    @Test
    fun `a ready live channel builds one engine and prepares it`() = runTest {
        val host = newHost(PlayerLaunch.live("7"))

        val engine = built.single()
        assertEquals(listOf("setUrl", "prepare"), engine.calls)
        assertTrue(engine.url.contains("/live/u/p/7."))
        assertSame(engine, host.engine)
    }

    @Test
    fun `zapping re-points the same engine instead of building another`() = runTest {
        val host = newHost(PlayerLaunch.live("7"))
        val engine = built.single()

        host.play(PlayerLaunch.live("8"))
        testScheduler.runCurrent()

        assertEquals(1, built.size)
        assertEquals(listOf("setUrl", "prepare", "setUrl", "prepare"), engine.calls)
        assertTrue(engine.urls.last().contains("/live/u/p/8."))
        assertEquals(0L, engine.startPositions.last())
    }

    @Test
    fun `home stops a live channel and the return restarts at the live edge`() = runTest {
        val host = newHost(PlayerLaunch.live("7"))
        built[0].currentPosition = 913_000L

        host.onStop()
        assertEquals(listOf("setUrl", "prepare", "clearVideoSurface", "release"), built[0].calls)
        assertNull(host.engine)

        host.onStart()
        testScheduler.runCurrent()
        assertEquals(2, built.size)
        assertEquals(listOf(0L), built[1].startPositions)
    }

    @Test
    fun `a movie resumes where it stopped after the stop start cycle`() = runTest {
        val host = newHost(PlayerLaunch.movie("9"))
        built[0].currentPosition = 913_000L

        host.onStop()
        host.onStart()
        testScheduler.runCurrent()

        assertEquals(listOf(913_000L), built[1].startPositions)
    }

    @Test
    fun `a resume position reaches the engine with the load and nothing later resets it`() = runTest {
        // #106: Resume logged startAt=92742 and still began at 0:00. The handoff is one setUrl with the
        // start position, then nothing: no seek, no second load when buffering ends, the first frame
        // renders or the position tick arrives.
        val vm = PlayerViewModel(
            PlayerLaunch(StreamType.VOD, "9", extension = "mkv", startPositionMillis = 92_742L),
            HostFakeRepository(),
            HostFakeHandle(),
        )
        PlaybackHost(
            context = ApplicationProvider.getApplicationContext(),
            scope = backgroundScope,
            viewModel = vm,
            handle = PlayerHandleBridge(),
            isFinishing = { finishing },
            engineFactory = { url -> HostFakeEngine(url).also { built += it } },
        ).attach()
        testScheduler.runCurrent()

        vm.onBuffering(true)
        vm.onBuffering(false)
        vm.onIsPlayingChanged(true)
        vm.onMediaRendered()
        vm.onPosition(positionMillis = 93_000L, durationMillis = 1_200_000L, bufferedMillis = 100_000L)
        testScheduler.runCurrent()

        val engine = built.single()
        assertEquals(listOf("setUrl", "prepare"), engine.calls)
        assertEquals(listOf(92_742L), engine.startPositions)
        assertTrue("no seek after the load", engine.seekedTo.isEmpty())
    }

    private fun TestScope.idleHost(): PlaybackHost {
        val vm = PlayerViewModel(null, HostFakeRepository(), HostFakeHandle())
        val host = PlaybackHost(
            context = ApplicationProvider.getApplicationContext(),
            scope = backgroundScope,
            viewModel = vm,
            handle = PlayerHandleBridge(),
            isFinishing = { finishing },
            engineFactory = { url -> HostFakeEngine(url).also { built += it } },
            frameGapProbe = false,
        )
        host.attach()
        testScheduler.runCurrent()
        return host
    }

    @Test
    fun `an idle host builds no engine until it is asked to play`() = runTest {
        val host = idleHost()
        assertEquals(HostMode.Idle, host.mode)
        assertTrue(built.isEmpty())

        host.play(PlayerLaunch.live("7"))
        testScheduler.runCurrent()
        assertEquals(HostMode.Full, host.mode)
        assertEquals("7", host.playingStreamId)
        assertEquals(1, built.size)
    }

    @Test
    fun `back with show channels shrinks to the preview and leaves the engine alone`() = runTest {
        val host = idleHost()
        host.backWhileWatching = BackWhileWatching.SHOW_CHANNELS
        host.play(PlayerLaunch.live("7"))
        testScheduler.runCurrent()

        val action = host.onPlayerBack(PlaybackMode.Live, longPress = false)

        assertEquals(BackAction.ToPreview, action)
        assertEquals(HostMode.Preview, host.mode)
        assertEquals("7", host.playingStreamId)
        assertEquals(listOf("setUrl", "prepare"), built.single().calls)
    }

    @Test
    fun `back with stop playback releases the engine and goes idle`() = runTest {
        val host = idleHost()
        host.backWhileWatching = BackWhileWatching.STOP_PLAYBACK
        host.play(PlayerLaunch.live("7"))
        testScheduler.runCurrent()

        host.onPlayerBack(PlaybackMode.Live, longPress = false)

        assertEquals(HostMode.Idle, host.mode)
        assertNull(host.playingStreamId)
        assertNull(host.engine)
        assertEquals(listOf("setUrl", "prepare", "clearVideoSurface", "release"), built.single().calls)
    }

    @Test
    fun `long back stops even with show channels`() = runTest {
        val host = idleHost()
        host.play(PlayerLaunch.live("7"))
        testScheduler.runCurrent()

        host.onPlayerBack(PlaybackMode.Live, longPress = true)

        assertEquals(HostMode.Idle, host.mode)
        assertNull(host.engine)
    }

    @Test
    fun `enter full from the preview reloads nothing`() = runTest {
        val host = idleHost()
        host.play(PlayerLaunch.live("7"))
        testScheduler.runCurrent()
        host.toPreview()

        host.enterFull()
        testScheduler.runCurrent()

        assertEquals(HostMode.Full, host.mode)
        assertEquals(listOf("setUrl", "prepare"), built.single().calls)
    }

    @Test
    fun `zapping from the preview re-points the same engine and goes full`() = runTest {
        val host = idleHost()
        host.play(PlayerLaunch.live("7"))
        testScheduler.runCurrent()
        host.toPreview()

        host.play(PlayerLaunch.live("8"))
        testScheduler.runCurrent()

        assertEquals(HostMode.Full, host.mode)
        assertEquals("8", host.playingStreamId)
        assertEquals(1, built.size)
        assertEquals(4, built.single().calls.size)
    }

    @Test
    fun `home stops a preview and the return does not restart it`() = runTest {
        val host = idleHost()
        host.play(PlayerLaunch.live("7"))
        testScheduler.runCurrent()
        host.toPreview()

        host.onActivityStopped(finishing = false)
        host.onStart()
        testScheduler.runCurrent()

        assertEquals(HostMode.Idle, host.mode)
        assertNull(host.engine)
        assertEquals(1, built.size)
    }

    @Test
    fun `a finishing stop leaves teardown to the destroy path`() = runTest {
        val host = idleHost()
        host.play(PlayerLaunch.live("7"))
        testScheduler.runCurrent()

        host.onActivityStopped(finishing = true)
        assertEquals(HostMode.Full, host.mode)

        host.release()
        assertNull(host.engine)
    }

    @Test
    fun `playing again after a stop builds a fresh engine at the live edge`() = runTest {
        val host = idleHost()
        host.play(PlayerLaunch.live("7"))
        testScheduler.runCurrent()
        built[0].currentPosition = 500_000L
        host.stopPlayback()
        testScheduler.runCurrent() // a stop and the next play are separate main-loop turns

        host.play(PlayerLaunch.live("7"))
        testScheduler.runCurrent()

        assertEquals(2, built.size)
        assertEquals(listOf(0L), built[1].startPositions)
    }

    @Test
    fun `release on the destroy path clears the surface then releases`() = runTest {
        val host = newHost(PlayerLaunch.live("7"))
        finishing = true

        host.onStop() // finishing: defers to the destroy path
        assertEquals(listOf("setUrl", "prepare"), built[0].calls)

        host.release()
        assertEquals(listOf("setUrl", "prepare", "clearVideoSurface", "release"), built[0].calls)
        assertNull(host.engine)
    }
}

internal class HostFakeEngine(val url: String) : PlaybackEngine {
    val calls = mutableListOf<String>()
    val urls = mutableListOf<String>()
    val startPositions = mutableListOf<Long>()
    val seekedTo = mutableListOf<Long>()

    override var playWhenReady: Boolean = true
    override var currentPosition: Long = 0L
    override val playbackState: Int = Player.STATE_READY

    override fun setUrl(url: String, startPositionMillis: Long) {
        calls += "setUrl"
        urls += url
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

private class HostFakeRepository : PlayerRepository {
    override suspend fun connection() = PlayerConnection("panel.example", 8080, false, "u", "p")

    override suspend fun liveStream(id: String) = LiveStream(id = id, categoryId = "1", name = "Channel $id")

    override suspend fun vodStream(id: String) =
        VodStream(id = id, categoryId = "1", name = "Movie $id", containerExtension = "mkv")

    override suspend fun firstEpisode(seriesId: String): PlayerEpisode? = null

    override suspend fun allowedOutputFormats(): List<String> = listOf("m3u8", "ts")

    override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? = null

    override suspend fun isFavorite(streamId: String, streamType: StreamType) = false

    override suspend fun toggleFavorite(streamId: String, streamType: StreamType) = Unit
}

private class HostFakeHandle : PlayerHandle {
    override fun togglePlayPause() = Unit

    override fun seekBy(deltaMillis: Long) = Unit

    override fun prepare() = Unit
}
