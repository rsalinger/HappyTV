package com.rysal.happytv.ui.player

import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #223: a live channel counts as watched only after 10 s of actual playing; virtual time, no sleeps. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayedChannelTimerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- the timer on its own

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val recorded = mutableListOf<String>()
    private val timer = PlayedChannelTimer(scope, clock = { scope.currentTime }, record = { recorded += it })

    private fun advance(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    private fun advanceVm(millis: Long) {
        dispatcher.scheduler.advanceTimeBy(millis)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `playing for 10 seconds records once`() {
        timer.arm("7")
        timer.onPlaying(true)
        advance(9_999)
        assertTrue(recorded.isEmpty())
        advance(1)
        assertEquals(listOf("7"), recorded)
        advance(60_000)
        timer.onPlaying(false)
        timer.onPlaying(true)
        advance(60_000)
        assertEquals(listOf("7"), recorded)
    }

    @Test
    fun `leaving before 10 seconds records nothing`() {
        timer.arm("7")
        timer.onPlaying(true)
        advance(9_000)
        timer.cancel()
        advance(60_000)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `zapping away resets the count`() {
        timer.arm("7")
        timer.onPlaying(true)
        advance(8_000)
        timer.arm("8")
        timer.onPlaying(true)
        advance(8_000)
        assertTrue(recorded.isEmpty())
        advance(2_000)
        assertEquals(listOf("8"), recorded)
    }

    @Test
    fun `buffering pauses the clock and does not reset it`() {
        timer.arm("7")
        timer.onPlaying(true)
        advance(6_000)
        timer.onPlaying(false) // buffering
        advance(30_000)
        assertTrue(recorded.isEmpty())
        timer.onPlaying(true)
        advance(3_999)
        assertTrue(recorded.isEmpty())
        advance(1)
        assertEquals(listOf("7"), recorded)
    }

    @Test
    fun `a failed stream zeroes the count`() {
        timer.arm("7")
        timer.onPlaying(true)
        advance(9_000)
        timer.onFailure()
        advance(60_000)
        assertTrue(recorded.isEmpty())
        timer.onPlaying(true)
        advance(9_999)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `never playing records nothing`() {
        timer.arm("7")
        advance(120_000)
        assertTrue(recorded.isEmpty())
    }

    // ---- wired into the player view model

    private class Repo : PlayerRepository {
        val recorded = mutableListOf<String>()
        override suspend fun connection() = PlayerConnection("panel.example", 8080, false, "u", "p")
        override suspend fun liveStream(id: String) = LiveStream(id, "1", 101, "Channel $id")
        override suspend fun vodStream(id: String): VodStream? = null
        override suspend fun firstEpisode(seriesId: String): PlayerEpisode? = null
        override suspend fun allowedOutputFormats(): List<String>? = null
        override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? = null
        override suspend fun isFavorite(streamId: String, streamType: StreamType) = false
        override suspend fun toggleFavorite(streamId: String, streamType: StreamType) {}
        override suspend fun recentChannels(currentStreamId: String?, nowUtcMillis: Long) = emptyList<RecentChannelCard>()
        override suspend fun recordWatch(streamId: String) {
            recorded += streamId
        }
    }

    private class Handle : PlayerHandle {
        override fun togglePlayPause() {}
        override fun seekBy(deltaMillis: Long) {}
        override fun prepare() {}
    }

    private val repo = Repo()
    private fun now() = dispatcher.scheduler.currentTime

    /** Search's path: the host starts the launch, then the activity arms the timer. */
    private fun searchLaunch(id: String): PlayerViewModel {
        val vm = PlayerViewModel(null, repo, Handle(), clock = ::now)
        vm.start(PlayerLaunch.live(id))
        vm.recordWatchedAfterPlaying()
        return vm
    }

    private fun PlayerViewModel.startsPlaying() {
        onMediaRendered()
        onIsPlayingChanged(true)
    }

    @Test
    fun `a search launch records after 10 seconds of playing`() {
        val vm = searchLaunch("7")
        vm.startsPlaying()
        advanceVm(9_999)
        assertTrue(repo.recorded.isEmpty())
        advanceVm(1)
        assertEquals(listOf("7"), repo.recorded)
    }

    @Test
    fun `a search launch left before 10 seconds records nothing`() {
        val vm = searchLaunch("7")
        vm.startsPlaying()
        advanceVm(5_000)
        vm.stop()
        advanceVm(60_000)
        assertTrue(repo.recorded.isEmpty())
    }

    @Test
    fun `a zap after a search launch drops the search channel`() {
        val vm = searchLaunch("7")
        vm.startsPlaying()
        advanceVm(9_000)
        vm.start(PlayerLaunch.live("8")) // Live TV's own zap: records on OK, not by the timer
        vm.startsPlaying()
        advanceVm(60_000)
        assertTrue(repo.recorded.isEmpty())
    }

    @Test
    fun `a search stream that fails records nothing`() {
        val vm = searchLaunch("7")
        vm.startsPlaying()
        advanceVm(9_000)
        vm.onStreamError(errorCode = 2001)
        vm.onIsPlayingChanged(false)
        advanceVm(60_000)
        assertTrue(repo.recorded.isEmpty())
    }

    @Test
    fun `a movie or a replay never arms the timer`() {
        val vm = PlayerViewModel(PlayerLaunch.movie("9"), repo, Handle(), clock = ::now)
        vm.recordWatchedAfterPlaying()
        vm.startsPlaying()
        advanceVm(60_000)
        val replay = PlayerViewModel(PlayerLaunch.replay("7", now() - 600_000L, 30), repo, Handle(), clock = ::now)
        replay.recordWatchedAfterPlaying()
        replay.startsPlaying()
        advanceVm(60_000)
        assertTrue(repo.recorded.isEmpty())
    }

    /** #224: the tune after channel up/down - the host starts the launch, then the layer arms the timer. */
    private fun PlayerViewModel.channelStep(id: String) {
        start(PlayerLaunch.live(id))
        recordWatchedAfterPlaying()
    }

    @Test
    fun `a channel reached by channel up or down is recorded only after 10 seconds of playing`() {
        val vm = PlayerViewModel(null, repo, Handle(), clock = ::now)
        vm.channelStep("8")
        vm.startsPlaying()
        advanceVm(9_999)
        assertTrue("nothing is recorded at the tune", repo.recorded.isEmpty())
        advanceVm(1)
        assertEquals(listOf("8"), repo.recorded)
    }

    @Test
    fun `stepping on before 10 seconds records only the channel that stayed`() {
        val vm = PlayerViewModel(null, repo, Handle(), clock = ::now)
        vm.channelStep("8")
        vm.startsPlaying()
        advanceVm(6_000)
        vm.channelStep("9")
        vm.startsPlaying()
        advanceVm(9_999)
        assertTrue(repo.recorded.isEmpty())
        advanceVm(1)
        assertEquals(listOf("9"), repo.recorded)
    }
}
