package com.rysal.happytv.ui.player

import android.content.Intent
import androidx.media3.common.Player
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.entity.WatchProgress
import com.rysal.happytv.ui.player.PlayerLaunch.Companion.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/** #78: what the player saves, when, and where it starts. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class PlayerWatchProgressTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private var now = 1_713_542_400_000L

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Recorder : PlayerRepository {
        val saved = mutableListOf<WatchProgress>()
        val cleared = mutableListOf<Pair<StreamType, String>>()
        val upNext = mutableListOf<WatchProgress>()
        var episodeAsked: String? = "unset"
        val rows = mutableMapOf<Pair<StreamType, String>, WatchProgress>()

        override suspend fun progress(streamType: StreamType, streamId: String): WatchProgress? = rows[streamType to streamId]

        override suspend fun connection() = PlayerConnection("panel.example", 8080, false, "u", "p")
        override suspend fun liveStream(id: String): LiveStream? = LiveStream(id = id, categoryId = "1", name = "Chan")
        override suspend fun vodStream(id: String): VodStream? = VodStream(id = id, categoryId = "1", name = "The Long Coast")
        override suspend fun firstEpisode(seriesId: String): PlayerEpisode? =
            PlayerEpisode(
                "e1", "mp4", "Pilot", "The Show", seasonNumber = 1, episodeNumber = 1,
                nextId = "e2", nextSeasonNumber = 1, nextEpisodeNumber = 2,
            )

        override suspend fun episode(seriesId: String, episodeId: String?): PlayerEpisode? {
            episodeAsked = episodeId
            return if (episodeId == "e9") {
                PlayerEpisode("e9", "mp4", "Nine", "The Show", seasonNumber = 2, episodeNumber = 3)
            } else {
                firstEpisode(seriesId)
            }
        }

        override suspend fun allowedOutputFormats(): List<String>? = null
        override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? = null
        override suspend fun isFavorite(streamId: String, streamType: StreamType) = false
        override suspend fun toggleFavorite(streamId: String, streamType: StreamType) = Unit
        override suspend fun saveProgress(progress: WatchProgress) {
            saved += progress
        }

        override suspend fun saveUpNext(progress: WatchProgress) {
            upNext += progress
        }

        override suspend fun clearProgress(streamType: StreamType, streamId: String) {
            cleared += streamType to streamId
        }
    }

    private class Handle : PlayerHandle {
        override fun togglePlayPause() = Unit
        override fun seekBy(deltaMillis: Long) = Unit
        override fun prepare() = Unit
    }

    private fun vm(launch: PlayerLaunch, repo: Recorder) = PlayerViewModel(launch, repo, Handle(), clock = { now })

    private val twoHours = 7_200_000L

    /** A session that rendered its first frame and played for [millis] of wall time (#104). */
    private fun watch(vm: PlayerViewModel, millis: Long = 61_000L) {
        vm.onMediaRendered()
        vm.onIsPlayingChanged(true)
        now += millis
    }

    // ---------------------------------------------------- short sessions (#104)

    @Test
    fun `a fresh launch resumed at 41 minutes saves nothing before it has played`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.movie("9").copy(startPositionMillis = 2_480_000L), repo)
        // The engine reports the launch's resume point from the moment it is set up.
        vm.persistProgress(2_480_000L, twoHours)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `a 10 second session resumed far in does not rewrite the row`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.movie("9").copy(startPositionMillis = 2_480_000L), repo)
        watch(vm, 10_000L)
        vm.persistProgress(2_490_000L, twoHours)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `a finished movie played for 10 seconds with a stale position writes nothing`() = runTest {
        val repo = Recorder()
        repo.rows[StreamType.VOD to "9"] = finishedRow(StreamType.VOD, "9")
        val vm = vm(PlayerLaunch.movie("9"), repo)
        // Back before the first frame, with the engine still reporting the old finish.
        vm.persistProgress(twoHours - 60_000L, twoHours)
        watch(vm, 10_000L)
        vm.persistProgress(twoHours - 60_000L, twoHours)
        assertTrue(repo.saved.isEmpty())
        assertTrue(repo.upNext.isEmpty())
    }

    @Test
    fun `a pause stops the watched time from growing`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.movie("9"), repo)
        vm.onMediaRendered()
        vm.onIsPlayingChanged(true)
        now += 30_000L
        vm.onIsPlayingChanged(false)
        now += 600_000L // paused for ten minutes
        vm.persistProgress(30_000L + 120_000L, twoHours)
        assertTrue(repo.saved.isEmpty())
        vm.onIsPlayingChanged(true)
        now += 31_000L
        vm.persistProgress(200_000L, twoHours)
        assertEquals(200_000L, repo.saved.single().positionMillis)
    }

    @Test
    fun `a seek far in then Back after 10 seconds saves that position as the resume point`() = runTest {
        val repo = Recorder()
        repo.rows[StreamType.VOD to "9"] = finishedRow(StreamType.VOD, "9")
        val vm = vm(PlayerLaunch.movie("9"), repo)
        watch(vm, 5_000L)
        vm.seekToPosition(4_800_000L)
        now += 5_000L
        vm.persistProgress(4_810_000L, twoHours)
        val saved = repo.saved.single()
        assertEquals(4_810_000L, saved.positionMillis)
        assertNull(saved.finishedAt)
    }

    @Test
    fun `a seek before the first frame does not count as watching`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.movie("9"), repo)
        vm.start(PlayerLaunch.movie("9"))
        vm.seekToPosition(4_800_000L)
        vm.onMediaRendered()
        vm.persistProgress(4_800_000L, twoHours)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `watched time does not carry over to the next item`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.series("5"), repo)
        watch(vm, 120_000L)
        vm.start(PlayerLaunch.series("5").copy(episodeId = "e9", startPositionMillis = 900_000L))
        vm.onMediaRendered()
        vm.persistProgress(900_000L, 2_700_000L)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `a movie saves its position with the time it was watched`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.movie("9"), repo)

        watch(vm)
        vm.persistProgress(2_480_000L, twoHours)

        assertEquals(
            listOf(WatchProgress(StreamType.VOD, "9", null, null, null, 2_480_000L, twoHours, now)),
            repo.saved,
        )
        assertTrue(repo.cleared.isEmpty())
    }

    @Test
    fun `under a minute in saves nothing`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.movie("9"), repo)
        vm.persistProgress(59_000L, twoHours)
        assertTrue(repo.saved.isEmpty())
        assertTrue(repo.cleared.isEmpty())
    }

    @Test
    fun `five minutes from the end keeps the row and marks it finished`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.movie("9"), repo)
        watch(vm)
        vm.persistProgress(twoHours - 240_000L, twoHours)
        assertEquals(
            listOf(WatchProgress(StreamType.VOD, "9", null, null, null, twoHours - 240_000L, twoHours, now, finishedAt = now)),
            repo.saved,
        )
        assertTrue(repo.cleared.isEmpty())
        assertTrue("a movie has no up next", repo.upNext.isEmpty())
    }

    @Test
    fun `an episode saves under its own id with the show and its season and episode`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.series("5"), repo)

        watch(vm)
        vm.persistProgress(600_000L, 2_700_000L)

        assertEquals(
            listOf(WatchProgress(StreamType.SERIES, "e1", "5", 1, 1, 600_000L, 2_700_000L, now)),
            repo.saved,
        )
    }

    @Test
    fun `a finished episode is marked watched and the next one is queued as up next`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.series("5"), repo)
        watch(vm)
        vm.persistProgress(2_600_000L, 2_700_000L)

        val saved = repo.saved.single()
        assertEquals("e1", saved.streamId)
        assertEquals(now, saved.finishedAt)
        assertEquals(2_600_000L, saved.positionMillis)
        assertEquals(WatchProgress(StreamType.SERIES, "e2", "5", 1, 2, 0L, 0L, now), repo.upNext.single())
        assertTrue(repo.cleared.isEmpty())
    }

    @Test
    fun `Start over deletes the saved row as soon as the movie loads`() = runTest {
        val repo = Recorder()
        vm(PlayerLaunch.movie("9").copy(startOver = true), repo)
        assertEquals(listOf(StreamType.VOD to "9"), repo.cleared)
    }

    @Test
    fun `Start over on a show deletes the episode being restarted`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.series("5").copy(startOver = true), repo)
        assertEquals(listOf(StreamType.SERIES to "e1"), repo.cleared)
        assertEquals(0L, (vm.state.value as PlayerUiState.Ready).content.startPositionMillis)
    }

    @Test
    fun `a plain launch deletes nothing`() = runTest {
        val repo = Recorder()
        vm(PlayerLaunch.movie("9"), repo)
        assertTrue(repo.cleared.isEmpty())
    }

    @Test
    fun `live channels never save progress`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.live("7"), repo)
        vm.persistProgress(3_600_000L, 0L)
        assertTrue(repo.saved.isEmpty())
        assertTrue(repo.cleared.isEmpty())
    }

    @Test
    fun `nothing is saved before a stream has loaded or after it was stopped`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.movie("9"), repo)
        vm.stop()
        vm.persistProgress(2_480_000L, twoHours)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `a resume launch starts the movie at the saved position`() = runTest {
        val vm = vm(PlayerLaunch.movie("9").copy(startPositionMillis = 2_480_000L), Recorder())
        assertEquals(2_480_000L, (vm.state.value as PlayerUiState.Ready).content.startPositionMillis)
    }

    @Test
    fun `a plain launch starts at the beginning (Start over)`() = runTest {
        val vm = vm(PlayerLaunch.movie("9"), Recorder())
        assertEquals(0L, (vm.state.value as PlayerUiState.Ready).content.startPositionMillis)
    }

    @Test
    fun `resuming a show plays the episode that was in progress at its position`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.series("5").copy(startPositionMillis = 900_000L, episodeId = "e9"), repo)

        val content = (vm.state.value as PlayerUiState.Ready).content
        assertEquals("e9", repo.episodeAsked)
        assertEquals("http://panel.example:8080/series/u/p/e9.mp4", content.url)
        assertEquals(900_000L, content.startPositionMillis)

        watch(vm)
        vm.persistProgress(1_000_000L, 2_700_000L)
        assertEquals(WatchProgress(StreamType.SERIES, "e9", "5", 2, 3, 1_000_000L, 2_700_000L, now), repo.saved.single())
    }

    @Test
    fun `a missing episode falls back to the first and does not seek into it`() = runTest {
        val repo = Recorder()
        val vm = vm(PlayerLaunch.series("5").copy(startPositionMillis = 900_000L, episodeId = "gone"), repo)
        val content = (vm.state.value as PlayerUiState.Ready).content
        assertEquals("http://panel.example:8080/series/u/p/e1.mp4", content.url)
        assertEquals(0L, content.startPositionMillis)
    }

    @Test
    fun `the launch intent carries the resume position and episode`() {
        val launch = PlayerLaunch.series("5").copy(startPositionMillis = 900_000L, episodeId = "e9")
        val parsed = PlayerLaunch.from(Intent().put(launch.copy(startOver = true)))!!
        assertTrue(parsed.startOver)
        assertEquals(900_000L, parsed.startPositionMillis)
        assertEquals("e9", parsed.episodeId)

        val plain = PlayerLaunch.from(Intent().put(PlayerLaunch.movie("9")))!!
        assertEquals(0L, plain.startPositionMillis)
        assertNull(plain.episodeId)
    }

    // ----------------------------------------------------------- lifecycle start

    private class StartEngine : PlaybackEngine {
        val starts = mutableListOf<Long>()
        override var playWhenReady = true
        override val currentPosition = 0L
        override val playbackState = Player.STATE_READY
        override fun setUrl(url: String, startPositionMillis: Long) {
            starts += startPositionMillis
        }
        override fun seekTo(positionMillis: Long) = Unit
        override fun prepare() = Unit
        override fun clearVideoSurface() = Unit
        override fun release() = Unit
    }

    private fun lifecycle(engine: StartEngine) = PlayerLifecycle(
        isFinishing = { false },
        playbackTarget = { null },
        engineFactory = { engine },
        onEngineChanged = {},
    )

    @Test
    fun `the first prepare of a movie seeks to the resume position`() {
        val engine = StartEngine()
        lifecycle(engine).prepareFor(PlaybackTarget("http://x/movie.mp4", PlaybackMode.Movie, 2_480_000L))
        assertEquals(listOf(2_480_000L), engine.starts)
    }

    @Test
    fun `live and replay ignore a start position`() {
        val live = StartEngine()
        lifecycle(live).prepareFor(PlaybackTarget("http://x/live.m3u8", PlaybackMode.Live, 2_480_000L))
        assertEquals(listOf(0L), live.starts)
        val replay = StartEngine()
        lifecycle(replay).prepareFor(PlaybackTarget("http://x/replay", PlaybackMode.Replay, 2_480_000L))
        assertEquals(listOf(0L), replay.starts)
    }

    private fun finishedRow(type: StreamType, id: String, seriesId: String? = null) =
        WatchProgress(type, id, seriesId, null, null, twoHours - 60_000L, twoHours, now - 1, finishedAt = now - 1)

    @Test
    fun `Play on a finished movie starts at 0 and leaves the watched row alone`() = runTest {
        val repo = Recorder()
        repo.rows[StreamType.VOD to "9"] = finishedRow(StreamType.VOD, "9")
        val vm = vm(PlayerLaunch.movie("9"), repo)
        assertEquals(0L, (vm.state.value as PlayerUiState.Ready).content.startPositionMillis)
        vm.persistProgress(10_000L, twoHours) // under a minute: ignored
        assertTrue(repo.cleared.isEmpty())
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `past a minute the save replaces a finished movie row with an unfinished one`() = runTest {
        val repo = Recorder()
        repo.rows[StreamType.VOD to "9"] = finishedRow(StreamType.VOD, "9")
        val vm = vm(PlayerLaunch.movie("9"), repo)
        watch(vm)
        vm.persistProgress(90_000L, twoHours)
        assertEquals(
            listOf(WatchProgress(StreamType.VOD, "9", null, null, null, 90_000L, twoHours, now, finishedAt = null)),
            repo.saved,
        )
        vm.persistProgress(twoHours - 60_000L, twoHours) // finishing again re-marks it
        assertEquals(now, repo.saved.last().finishedAt)
    }

    @Test
    fun `a finished episode replayed keeps its mark under a minute and is replaced past it`() = runTest {
        val repo = Recorder()
        repo.rows[StreamType.SERIES to "e1"] = finishedRow(StreamType.SERIES, "e1", "5")
        val vm = vm(PlayerLaunch.series("5"), repo)
        vm.persistProgress(5_000L, 2_700_000L)
        assertTrue(repo.cleared.isEmpty() && repo.saved.isEmpty())
        watch(vm)
        vm.persistProgress(120_000L, 2_700_000L)
        assertNull(repo.saved.single().finishedAt)
        assertEquals("e1", repo.saved.single().streamId)
    }

    @Test
    fun `a resumed movie keeps its unfinished row`() = runTest {
        val repo = Recorder()
        repo.rows[StreamType.VOD to "9"] = finishedRow(StreamType.VOD, "9").copy(finishedAt = null, positionMillis = 600_000L)
        vm(PlayerLaunch.movie("9").copy(startPositionMillis = 600_000L), repo)
        assertTrue(repo.cleared.isEmpty())
    }

    @Test
    fun `an unfinished row is not cleared by a plain Play at 0`() = runTest {
        val repo = Recorder()
        repo.rows[StreamType.VOD to "9"] = finishedRow(StreamType.VOD, "9").copy(finishedAt = null, positionMillis = 600_000L)
        vm(PlayerLaunch.movie("9"), repo)
        assertTrue(repo.cleared.isEmpty())
    }

    @Test
    fun `clearing the player cancels a next episode timer left armed`() = runTest {
        com.rysal.happytv.perf.HtvPerf.resetForTest()
        val vm = vm(PlayerLaunch.series("5"), Recorder())
        com.rysal.happytv.perf.HtvPerf.start(com.rysal.happytv.perf.HtvPerf.PLAYER_NEXT_EPISODE)
        vm.onCleared()
        assertTrue(!com.rysal.happytv.perf.HtvPerf.isRunning(com.rysal.happytv.perf.HtvPerf.PLAYER_NEXT_EPISODE))
        com.rysal.happytv.perf.HtvPerf.resetForTest()
    }
}
