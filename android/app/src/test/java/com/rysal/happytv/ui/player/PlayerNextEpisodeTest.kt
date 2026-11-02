package com.rysal.happytv.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.entity.WatchProgress
import com.rysal.happytv.data.network.SeriesInfoDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #84: the end-of-episode state machine and the next-episode selection behind it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class PlayerNextEpisodeTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private var now = 1_713_542_400_000L
    private val episodeLength = 2_700_000L

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Repo(val episodes: Map<String, PlayerEpisode>) : PlayerRepository {
        val saved = mutableListOf<WatchProgress>()
        val upNext = mutableListOf<WatchProgress>()
        val asked = mutableListOf<String?>()
        var savedRow: WatchProgress? = null

        override suspend fun connection() = PlayerConnection("panel.example", 8080, false, "u", "p")
        override suspend fun liveStream(id: String): LiveStream? = null
        override suspend fun vodStream(id: String): VodStream? = VodStream(id = id, categoryId = "1", name = "Movie")
        override suspend fun firstEpisode(seriesId: String) = episodes.values.first()
        override suspend fun episode(seriesId: String, episodeId: String?): PlayerEpisode? {
            asked += episodeId
            return episodeId?.let { episodes[it] } ?: firstEpisode(seriesId)
        }
        override suspend fun allowedOutputFormats(): List<String>? = null
        override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? = null
        override suspend fun isFavorite(streamId: String, streamType: StreamType) = false
        override suspend fun toggleFavorite(streamId: String, streamType: StreamType) = Unit
        override suspend fun saveProgress(progress: WatchProgress) { saved += progress }
        override suspend fun saveUpNext(progress: WatchProgress) { upNext += progress }
        override suspend fun progress(streamType: StreamType, streamId: String) = savedRow
    }

    private class Handle : PlayerHandle {
        override fun togglePlayPause() = Unit
        override fun seekBy(deltaMillis: Long) = Unit
        override fun prepare() = Unit
    }

    private val e1 = PlayerEpisode(
        "e1", "mp4", "One", "The Show", seasonNumber = 1, episodeNumber = 1,
        nextId = "e2", nextSeasonNumber = 1, nextEpisodeNumber = 2, nextTitle = "Two",
    )
    private val e2 = PlayerEpisode(
        "e2", "mp4", "Two", "The Show", seasonNumber = 1, episodeNumber = 2,
        nextId = "e3", nextSeasonNumber = 2, nextEpisodeNumber = 1, nextTitle = "Three",
    )
    private val last = PlayerEpisode("e3", "mp4", "Three", "The Show", seasonNumber = 2, episodeNumber = 1)

    private fun repo() = Repo(listOf(e1, e2, last).associateBy { it.id })

    private fun vm(repo: Repo, launch: PlayerLaunch = PlayerLaunch.series("5")) =
        PlayerViewModel(launch, repo, Handle(), clock = { now })

    private fun watch(vm: PlayerViewModel, millis: Long = 61_000L) {
        vm.onMediaRendered()
        vm.onIsPlayingChanged(true)
        now += millis
    }

    private fun PlayerViewModel.ready() = (state.value as PlayerUiState.Ready).content

    @Test
    fun `an ended episode with a next one starts a ten second countdown with its code and title`() = runTest {
        val vm = vm(repo())
        vm.onPlaybackEnded(episodeLength)
        assertEquals(EndOfPlayback.Countdown("S1 E2 · Two", 10), vm.endOfPlayback.value)
        advanceTimeBy(3_001L)
        assertEquals(EndOfPlayback.Countdown("S1 E2 · Two", 7), vm.endOfPlayback.value)
    }

    @Test
    fun `the ended episode is finished and the next one is queued`() = runTest {
        val repo = repo()
        val vm = vm(repo)
        vm.onPlaybackEnded(episodeLength)
        val finished = repo.saved.single()
        assertEquals("e1", finished.streamId)
        assertEquals(now, finished.finishedAt)
        assertEquals(listOf("e2"), repo.upNext.map { it.streamId })
    }

    @Test
    fun `the countdown reaching zero plays the next episode from the start`() = runTest {
        val repo = repo()
        val vm = vm(repo)
        vm.onPlaybackEnded(episodeLength)
        advanceTimeBy(10_500L)
        assertEquals("e2", repo.asked.last())
        assertEquals(EndOfPlayback.None, vm.endOfPlayback.value)
        assertEquals(0L, vm.ready().startPositionMillis)
        assertEquals("Two", vm.ready().title)
    }

    @Test
    fun `Next episode plays now and resumes a saved in-progress position`() = runTest {
        val repo = repo()
        repo.savedRow = WatchProgress(StreamType.SERIES, "e2", "5", 1, 2, 600_000L, episodeLength, now)
        val vm = vm(repo)
        vm.onPlaybackEnded(episodeLength)
        vm.playNextEpisode()
        assertEquals("e2", repo.asked.last())
        assertEquals(600_000L, vm.ready().startPositionMillis)
    }

    @Test
    fun `a watched next episode starts at zero`() = runTest {
        val repo = repo()
        repo.savedRow = WatchProgress(StreamType.SERIES, "e2", "5", 1, 2, episodeLength, episodeLength, now, finishedAt = now)
        val vm = vm(repo)
        vm.onPlaybackEnded(episodeLength)
        vm.playNextEpisode()
        assertEquals(0L, vm.ready().startPositionMillis)
    }

    @Test
    fun `the next episode is picked across a season boundary`() = runTest {
        val vm = vm(repo(), PlayerLaunch.series("5").copy(episodeId = "e2"))
        vm.onPlaybackEnded(episodeLength)
        assertEquals(EndOfPlayback.Countdown("S2 E1 · Three", 10), vm.endOfPlayback.value)
    }

    @Test
    fun `the last episode returns to details with no countdown`() = runTest {
        val repo = repo()
        val vm = vm(repo, PlayerLaunch.series("5").copy(episodeId = "e3"))
        vm.onPlaybackEnded(episodeLength)
        assertEquals(EndOfPlayback.ReturnToDetails, vm.endOfPlayback.value)
        assertEquals("e3", repo.saved.single().streamId)
        assertTrue(repo.upNext.isEmpty())
    }

    @Test
    fun `cancel stops the countdown and nothing starts`() = runTest {
        val repo = repo()
        val vm = vm(repo)
        vm.onPlaybackEnded(episodeLength)
        vm.cancelCountdown()
        assertEquals(EndOfPlayback.Stopped("S1 E2 · Two"), vm.endOfPlayback.value)
        advanceTimeBy(30_000L)
        assertEquals(listOf<String?>(null), repo.asked)
        assertEquals(EndOfPlayback.Stopped("S1 E2 · Two"), vm.endOfPlayback.value)
    }

    @Test
    fun `after cancel Play next episode still starts it`() = runTest {
        val repo = repo()
        val vm = vm(repo)
        vm.onPlaybackEnded(episodeLength)
        vm.cancelCountdown()
        vm.playNextEpisode()
        assertEquals("e2", repo.asked.last())
        assertEquals(EndOfPlayback.None, vm.endOfPlayback.value)
    }

    @Test
    fun `a second end signal does not restart the countdown`() = runTest {
        val vm = vm(repo())
        vm.onPlaybackEnded(episodeLength)
        advanceTimeBy(4_001L)
        vm.onPlaybackEnded(episodeLength)
        assertEquals(EndOfPlayback.Countdown("S1 E2 · Two", 6), vm.endOfPlayback.value)
    }

    @Test
    fun `a movie that ends offers nothing`() = runTest {
        val vm = vm(repo(), PlayerLaunch.movie("9"))
        vm.onPlaybackEnded(episodeLength)
        assertEquals(EndOfPlayback.None, vm.endOfPlayback.value)
    }

    @Test
    fun `stopping the player drops the card`() = runTest {
        val vm = vm(repo())
        vm.onPlaybackEnded(episodeLength)
        vm.stop()
        assertEquals(EndOfPlayback.None, vm.endOfPlayback.value)
        advanceTimeBy(20_000L)
        assertEquals(EndOfPlayback.None, vm.endOfPlayback.value)
    }

    @Test
    fun `saves between the switch and the new item rendering are ignored, later ones kept`() = runTest {
        val repo = repo()
        val vm = vm(repo)
        vm.onPlaybackEnded(episodeLength)
        vm.playNextEpisode()
        val before = repo.saved.size
        // The engine still reports the old item's end: the periodic save, then the stop/Back save.
        vm.persistProgress(episodeLength, episodeLength)
        vm.persistProgress(episodeLength - 1_000L, episodeLength)
        assertEquals(before, repo.saved.size)
        assertTrue(repo.upNext.none { it.streamId == "e3" })

        vm.onMediaRendered()
        watch(vm)
        vm.persistProgress(120_000L, episodeLength)
        val kept = repo.saved.last()
        assertEquals("e2", kept.streamId)
        assertEquals(120_000L, kept.positionMillis)
        assertNull(kept.finishedAt)
    }

    @Test
    fun `the old item's lingering end signal cannot start the next countdown`() = runTest {
        val vm = vm(repo())
        vm.onPlaybackEnded(episodeLength)
        vm.playNextEpisode()
        vm.onPlaybackEnded(episodeLength)
        assertEquals(EndOfPlayback.None, vm.endOfPlayback.value)
        vm.onMediaRendered()
        vm.onPlaybackEnded(episodeLength)
        assertEquals(EndOfPlayback.Countdown("S2 E1 · Three", 10), vm.endOfPlayback.value)
    }

    // ---- selection (GraphPlayerRepository.episodeOf) and label

    private val json = Json { ignoreUnknownKeys = true }

    private val twoSeasons = json.decodeFromString<SeriesInfoDto>(
        """{"info":{"name":"The Show"},"episodes":{
          "2":[{"id":"21","episode_num":"1","title":"The Show - S02E01 - Fresh start"}],
          "1":[{"id":"11","episode_num":"1","title":"One"},{"id":"12","episode_num":"2","title":"The Show S01E02 Two"}],
          "0":[{"id":"1","episode_num":"1","title":"Special"}]}}""",
    )

    @Test
    fun `the next episode of a season finale is the next season's first`() {
        val finale = GraphPlayerRepository.episodeOf("The Show", twoSeasons, "12")!!
        assertEquals("21", finale.nextId)
        assertEquals(2, finale.nextSeasonNumber)
        assertEquals(1, finale.nextEpisodeNumber)
        assertEquals("Fresh start", finale.nextTitle)
        assertEquals("S2 E1 · Fresh start", nextEpisodeLabel(finale.nextSeasonNumber, finale.nextEpisodeNumber, finale.nextTitle))
    }

    @Test
    fun `within a season the next episode has the cleaned title`() {
        val first = GraphPlayerRepository.episodeOf("The Show", twoSeasons, "11")!!
        assertEquals("12", first.nextId)
        assertEquals("Two", first.nextTitle)
    }

    @Test
    fun `specials come after the last season and have no next`() {
        val lastSeason = GraphPlayerRepository.episodeOf("The Show", twoSeasons, "21")!!
        assertEquals("1", lastSeason.nextId)
        val special = GraphPlayerRepository.episodeOf("The Show", twoSeasons, "1")!!
        assertNull(special.nextId)
    }

    @Test
    fun `the label leaves out what is unknown`() {
        assertEquals("S2 E4 · The Return", nextEpisodeLabel(2, 4, "The Return"))
        assertEquals("S2 E4", nextEpisodeLabel(2, 4, null))
        assertEquals("The Return", nextEpisodeLabel(null, null, " The Return "))
        assertEquals("Next episode", nextEpisodeLabel(null, null, ""))
    }
}
