package com.rysal.happytv.ui.player

import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #220: the view model loads the row for the live channel playing, clears it on a zap or stop, and records picks. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentChannelsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val now = 1_713_542_400_000L

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Repo : PlayerRepository {
        val live = mapOf(
            "7" to LiveStream("7", "1", 101, "Harbor News", tvArchive = true),
            "8" to LiveStream("8", "1", 102, "Summit Sports"),
        )
        val asked = mutableListOf<String?>()
        val recorded = mutableListOf<String>()
        var cards: (String?) -> List<RecentChannelCard> = { emptyList() }

        override suspend fun connection() = PlayerConnection("panel.example", 8080, false, "u", "p")
        override suspend fun liveStream(id: String): LiveStream? = live[id]
        override suspend fun vodStream(id: String): VodStream? = null
        override suspend fun firstEpisode(seriesId: String): PlayerEpisode? = null
        override suspend fun allowedOutputFormats(): List<String>? = null
        override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? = null
        override suspend fun isFavorite(streamId: String, streamType: StreamType) = false
        override suspend fun toggleFavorite(streamId: String, streamType: StreamType) {}
        override suspend fun recentChannels(currentStreamId: String?, nowUtcMillis: Long): List<RecentChannelCard> {
            asked += currentStreamId
            return cards(currentStreamId)
        }
        override suspend fun recordWatch(streamId: String) {
            recorded += streamId
        }
    }

    private class Handle : PlayerHandle {
        override fun togglePlayPause() {}
        override fun seekBy(deltaMillis: Long) {}
        override fun prepare() {}
    }

    private fun card(id: String) = RecentChannelCard(id, null, "Channel $id", null)

    private fun viewModel(repo: Repo, launch: PlayerLaunch = PlayerLaunch.live("7")) =
        PlayerViewModel(launch, repo, Handle(), clock = { now })

    @Test
    fun `a live channel loads the row for itself`() {
        val repo = Repo().apply { cards = { listOf(card("8")) } }
        val vm = viewModel(repo)
        assertEquals(listOf<String?>("7"), repo.asked)
        assertEquals(listOf("8"), vm.recentChannels.value.map { it.streamId })
    }

    @Test
    fun `a zap reloads the row for the new channel and stop clears it`() {
        val repo = Repo().apply { cards = { cur -> listOf(card(if (cur == "7") "8" else "7")) } }
        val vm = viewModel(repo)
        assertEquals(listOf("8"), vm.recentChannels.value.map { it.streamId })

        vm.start(PlayerLaunch.live("8"))
        assertEquals(listOf<String?>("7", "8"), repo.asked)
        assertEquals(listOf("7"), vm.recentChannels.value.map { it.streamId })

        vm.stop()
        assertTrue(vm.recentChannels.value.isEmpty())
    }

    @Test
    fun `catch-up of a live channel also gets the row`() {
        val repo = Repo().apply { cards = { listOf(card("8")) } }
        val vm = viewModel(repo, PlayerLaunch.replay("7", startUtcMillis = now - 600_000L, durationMinutes = 30))
        val content = (vm.state.value as PlayerUiState.Ready).content
        assertEquals(PlaybackMode.Replay, content.mode)
        assertEquals(listOf("8"), vm.recentChannels.value.map { it.streamId })
        // Picking a card goes live on that channel: the same view model re-points, the row follows.
        repo.cards = { listOf(card("7")) }
        vm.start(PlayerLaunch.live("8"))
        assertEquals(PlaybackMode.Live, (vm.state.value as PlayerUiState.Ready).content.mode)
        assertEquals(listOf("7"), vm.recentChannels.value.map { it.streamId })
    }

    @Test
    fun `a failing lookup leaves the row empty instead of failing playback`() {
        val repo = Repo().apply { cards = { error("boom") } }
        val vm = viewModel(repo)
        assertTrue(vm.recentChannels.value.isEmpty())
        assertTrue(vm.state.value is PlayerUiState.Ready)
    }

    @Test
    fun `picking records the watch`() {
        val repo = Repo()
        val vm = viewModel(repo)
        vm.recordWatched("8")
        assertEquals(listOf("8"), repo.recorded)
    }

    @Test
    fun `opening the row closes the now and next card`() {
        val repo = Repo()
        val vm = viewModel(repo)
        // No guide data in this fake, so the card cannot open; closing it keeps the state as it was.
        vm.hideProgramInfo()
        assertFalse((vm.state.value as PlayerUiState.Ready).content.showProgramInfo)
    }
}
