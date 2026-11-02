package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.RoomTestTeardown
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.SeriesStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.network.RefreshState
import com.rysal.happytv.ui.catalog.CatalogViewModel
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-67, acceptance criterion 1: OK on the **Shows** tile must open a screen
 * titled "Shows" with "All shows" — not the Movies screen.
 *
 * [BrowseCatalogRouteTest] pins the routing (which catalog the start
 * destination resolves to). This one renders the **real** [BrowseNavHost] with
 * the real [CatalogScreen] and asserts the copy a user actually sees, because
 * "the right catalog resolved" and "the screen says Shows" were the two halves
 * of the bug report and only the second is observable from the sofa.
 *
 * Both catalogs are seeded with content, so a wrong catalog cannot hide behind
 * an empty state: a Movies render shows the movie poster, a Shows render the series poster. The refresh is reported
 * settled so the screen leaves its F-60 skeleton and draws real rows.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseNavHostShowsRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: HappyTvDatabase
    private val viewModels = mutableListOf<CatalogViewModel>()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedCatalog()
        advanceUntilIdle()
    }

    @After
    fun tearDown() {
        // This used to `cancel()` without joining and then resetMain() with
        // Room's late emissions still queued, which is the "Dispatchers.Main is
        // used concurrently with setting it" flake (#41). The shared teardown
        // joins, drains Room's executors and idles Main before resetting it.
        val jobs = viewModels.map { it.viewModelScope.coroutineContext.job }
        viewModels.clear()
        RoomTestTeardown.shutDown(jobs, db)
    }

    /** Movies and series both populated, so the two renders cannot be confused. */
    private suspend fun seedCatalog() {
        db.categoryDao().replaceAll(
            StreamType.VOD,
            listOf(Category(id = "vod-drama", type = StreamType.VOD, name = "Movie Drama")),
        )
        db.vodStreamDao().replaceAll(
            listOf(VodStream(id = "m1", categoryId = "vod-drama", num = 1, name = "A Movie", added = 1L)),
        )
        db.categoryDao().replaceAll(
            StreamType.SERIES,
            listOf(Category(id = "ser-drama", type = StreamType.SERIES, name = "Series Drama")),
        )
        db.seriesStreamDao().replaceAll(
            listOf(SeriesStream(id = "s1", categoryId = "ser-drama", num = 1, name = "A Show", lastModified = 1L)),
        )
    }

    /** The real ViewModel over the in-memory db, refresh reported settled. */
    private fun factory(streamType: StreamType) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == CatalogViewModel::class.java) {
                "Factory only knows about CatalogViewModel; got $modelClass"
            }
            val viewModel = CatalogViewModel(
                categoryDao = db.categoryDao(),
                vodStreamDao = db.vodStreamDao(),
                seriesStreamDao = db.seriesStreamDao(),
                favoriteDao = db.favoriteDao(),
                streamType = streamType,
                // F-64's gate: Done settles the skeleton so real rows draw.
                refreshProgress = flowOf(RefreshState.Done(channels = 1, ms = 1L)),
                lastRefresh = flowOf(0L),
                // The one-screen rail restores the last category: none yet.
                lastCategory = flowOf(null),
            )
            viewModels += viewModel
            return viewModel as T
        }
    }

    private fun render(startRoute: BrowseStartRoute) {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    BrowseNavHost(
                        startRoute = startRoute,
                        onExit = {},
                        catalogViewModelFactory = { streamType -> factory(streamType) },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The screen opens on "Recently added" (no favorites yet): its poster is the proof of the catalog. */
    private fun assertShowsScreen() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("A Show")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Shows")).assertExists()
        compose.onNode(hasText("Recently added \u00b7 1 show")).assertExists()
    }

    private fun assertMoviesScreen() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("A Movie")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Movies")).assertExists()
        compose.onNode(hasText("Recently added \u00b7 1 movie")).assertExists()
    }

    // ------------------------------------------------------------- the regression

    @Test
    fun `the Shows tile opens a screen titled Shows`() {
        // Exactly what ShowsActivity passes after the dashboard's OK.
        render(catalogStartRoute(CatalogRoute.SHOWS))
        assertShowsScreen()
    }

    @Test
    fun `the Shows screen does not render any Movies content`() {
        render(catalogStartRoute(CatalogRoute.SHOWS))
        assertShowsScreen()
        // The bug's signature: the movie catalog under a correctly-topped ShowsActivity.
        compose.onNode(hasText("A Movie")).assertDoesNotExist()
        compose.onNode(hasText("Movies")).assertDoesNotExist()
    }

    @Test
    fun `the Shows screen counts shows rather than movies`() {
        render(catalogStartRoute(CatalogRoute.SHOWS))
        assertShowsScreen()
        compose.onNode(hasText("Recently added \u00b7 1 movie")).assertDoesNotExist()
    }

    @Test
    fun `the Movies tile still opens the Movies screen`() {
        render(catalogStartRoute(CatalogRoute.MOVIES))
        assertMoviesScreen()
        compose.onNode(hasText("A Show")).assertDoesNotExist()
    }

    @Test
    fun `Live TV takes the unparameterized route, untouched by the catalog fix`() {
        // Live TV has its own ViewModel (BrowseViewModel over Graph.database),
        // so it is not rendered here — this asserts the wiring the graph uses:
        // a null-catalog start route contributes no {type} default, and its
        // route is the bare live_tv template. BrowseCatalogRouteTest pins the
        // same fact through the real controller.
        val startRoute = BrowseStartRoute.liveTv()
        assertEquals(BrowseRoutes.LIVE_TV, startRoute.route)
        assertEquals(null, startRoute.catalog)
        assertEquals(
            null,
            stringArgument(TYPE_ARG, startRoute).argument.defaultValue,
        )
    }
}