package com.rysal.happytv.ui.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.RoomTestTeardown
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.network.RefreshState
import com.rysal.happytv.ui.browse.BrowseNavHost
import com.rysal.happytv.ui.browse.CatalogRoute
import com.rysal.happytv.ui.browse.catalogStartRoute
import com.rysal.happytv.ui.catalog.CatalogViewModel
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #239: the player's 1 s position tick must not recompose the Live TV content under the
 * playback layer, nor the player screen outside the progress UI.
 *
 * Live TV itself needs the app graph, so the layer's pattern is rendered over the real
 * [BrowseNavHost] on a catalog route; the catalog destination's ViewModel factory is called
 * once per composition of the destination, which is what the counter reads.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerTickRecompositionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: HappyTvDatabase
    private val viewModels = mutableListOf<CatalogViewModel>()
    private var destinationCompositions = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        destinationCompositions = 0
    }

    @After
    fun tearDown() {
        val jobs = viewModels.map { it.viewModelScope.coroutineContext.job }
        viewModels.clear()
        RoomTestTeardown.shutDown(jobs, db)
    }

    private fun catalogFactory(streamType: StreamType): ViewModelProvider.Factory {
        destinationCompositions++
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val viewModel = CatalogViewModel(
                    categoryDao = db.categoryDao(),
                    vodStreamDao = db.vodStreamDao(),
                    seriesStreamDao = db.seriesStreamDao(),
                    favoriteDao = db.favoriteDao(),
                    streamType = streamType,
                    refreshProgress = flowOf(RefreshState.Done(channels = 0, ms = 1L)),
                    lastRefresh = flowOf(0L),
                    lastCategory = flowOf(null),
                )
                viewModels += viewModel
                return viewModel as T
            }
        }
    }

    private fun playingMovie(): PlayerViewModel {
        val repo = TickFakeRepo()
        return PlayerViewModel(PlayerLaunch.movie("9"), repo, TickFakeHandle(), clock = { NOW }).also {
            it.onBuffering(false)
            it.onIsPlayingChanged(true)
            it.onPosition(10 * 60_000L, 54 * 60_000L, 12 * 60_000L)
        }
    }

    private fun render(layer: @Composable (PlayerViewModel) -> Unit, vm: PlayerViewModel) {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas { layer(vm) }
            }
        }
        compose.waitForIdle()
    }

    private fun tick(vm: PlayerViewModel, times: Int = 5) {
        repeat(times) { i ->
            compose.runOnIdle { vm.onPosition((11 + i) * 60_000L, 54 * 60_000L, (13 + i) * 60_000L) }
            compose.waitForIdle()
        }
    }

    @Test
    fun `a position tick does not recompose the Live TV content`() {
        val vm = playingMovie()
        render({ player ->
            val buffering by player.isBuffering.collectAsStateWithLifecycle()
            @Suppress("UNUSED_VARIABLE") val read = buffering
            val route = remember { catalogStartRoute(CatalogRoute.MOVIES) }
            BrowseNavHost(startRoute = route, onExit = {}, catalogViewModelFactory = ::catalogFactory)
        }, vm)
        val baseline = destinationCompositions

        tick(vm)

        assertEquals("destination recomposed by the position tick", baseline, destinationCompositions)
    }

    @Test
    fun `the main-branch pattern, whole state and a fresh start route, does recompose on every tick`() {
        // Negative control: proves the counter sees the problem this issue fixes.
        val vm = playingMovie()
        render({ player ->
            val playerState by player.state.collectAsStateWithLifecycle()
            @Suppress("UNUSED_VARIABLE") val read = (playerState as? PlayerUiState.Ready)?.content?.isBuffering == true
            BrowseNavHost(startRoute = catalogStartRoute(CatalogRoute.MOVIES), onExit = {}, catalogViewModelFactory = ::catalogFactory)
        }, vm)
        val baseline = destinationCompositions

        tick(vm, times = 5)

        assertEquals(baseline + 5, destinationCompositions)
    }

    @Test
    fun `a position tick leaves uiState and isBuffering silent and changes tick`() {
        val vm = playingMovie()
        var uiEmissions = 0
        var bufferingEmissions = 0
        var tickEmissions = 0
        val scope = kotlinx.coroutines.CoroutineScope(mainDispatcher)
        scope.launch { vm.uiState.collect { uiEmissions++ } }
        scope.launch { vm.isBuffering.collect { bufferingEmissions++ } }
        scope.launch { vm.tick.collect { tickEmissions++ } }
        val ui = uiEmissions
        val buf = bufferingEmissions
        val ticks = tickEmissions

        repeat(5) { i -> vm.onPosition((11 + i) * 60_000L, 54 * 60_000L, (13 + i) * 60_000L) }

        assertEquals(ui, uiEmissions)
        assertEquals(buf, bufferingEmissions)
        assertEquals(ticks + 5, tickEmissions)
        assertEquals(15 * 60_000L, vm.tick.value?.progress?.positionMillis)
        assertEquals(15 * 60_000L, (vm.state.value as PlayerUiState.Ready).content.progress.positionMillis)
        scope.cancel()
    }

    @Test
    fun `the player screen still draws each tick in its progress row`() {
        val vm = playingMovie()
        compose.setContent { HappyTvTheme { PlayerScreen(viewModel = vm, onBack = {}, onLongBack = null) } }
        compose.waitForIdle()

        compose.runOnIdle { vm.onPosition(3_734_000L, 7_440_000L, 3_900_000L) }
        compose.waitForIdle()
        compose.onNode(hasText("1:02:14")).assertExists()

        compose.runOnIdle { vm.onPosition(3_795_000L, 7_440_000L, 3_900_000L) }
        compose.waitForIdle()
        compose.onNode(hasText("1:03:15")).assertExists()
        compose.onNode(hasText("1:02:14")).assertDoesNotExist()
    }
}

private const val NOW = 1_713_542_400_000L

private class TickFakeHandle : PlayerHandle {
    override fun togglePlayPause() = Unit
    override fun seekBy(deltaMillis: Long) = Unit
    override fun prepare() = Unit
}

private class TickFakeRepo : PlayerRepository {
    override suspend fun connection() = PlayerConnection("panel.example", 8080, false, "u", "p")
    override suspend fun liveStream(id: String): LiveStream? = null
    override suspend fun vodStream(id: String): VodStream? =
        VodStream(id = id, categoryId = "1", name = "M", containerExtension = "mp4")
    override suspend fun firstEpisode(seriesId: String): PlayerEpisode? = null
    override suspend fun allowedOutputFormats(): List<String> = listOf("m3u8", "ts")
    override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? = null
    override suspend fun isFavorite(streamId: String, streamType: StreamType) = false
    override suspend fun toggleFavorite(streamId: String, streamType: StreamType) = Unit
}
