package com.rysal.happytv.ui.catalog

import android.app.Application
import androidx.activity.ComponentActivity
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
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.network.RefreshState
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #235: a fresh process has not settled a refresh (the progress flow stays `Idle`). An empty
 * Favorites grid must still show its empty state, in Movies and Shows; a provider category must
 * keep its skeleton while it really loads (F-60).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
class CatalogColdStartEmptyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: HappyTvDatabase
    private val viewModels = mutableListOf<CatalogViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        kotlinx.coroutines.runBlocking {
            db.categoryDao().replaceAll(
                StreamType.VOD,
                listOf(Category(id = "drama", type = StreamType.VOD, name = "Drama")),
            )
            // Drama's rows have not landed yet: the category exists, its grid is empty.
            db.vodStreamDao().replaceAll(
                listOf(VodStream(id = "m1", categoryId = "other", num = 1, name = "Movie 01", added = 1L)),
            )
        }
    }

    @After
    fun tearDown() {
        val jobs = viewModels.map { it.viewModelScope.coroutineContext.job }
        viewModels.clear()
        RoomTestTeardown.shutDown(jobs, db)
    }

    private fun start(type: StreamType, last: String) {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CatalogViewModel(
                categoryDao = db.categoryDao(),
                vodStreamDao = db.vodStreamDao(),
                seriesStreamDao = db.seriesStreamDao(),
                favoriteDao = db.favoriteDao(),
                streamType = type,
                refreshProgress = flowOf(RefreshState.Idle),
                lastCategory = flowOf(null),
                watchProgressDao = db.watchProgressDao(),
            ).also { viewModels += it } as T
        }
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas { CatalogScreen(streamType = type, viewModelFactory = factory) }
            }
        }
        compose.waitForIdle()
        // As on the TV: the screen opens on its default row, then the user picks the row in the rail.
        compose.runOnIdle { viewModels.last().selectCategory(last) }
        compose.waitForIdle()
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `Movies Favorites shows the empty state before any refresh settles`() {
        start(StreamType.VOD, CatalogViewModel.FAVORITES_CATEGORY_ID)
        compose.waitUntil(5_000) { exists("No favorite movies yet") }
    }

    @Test
    fun `Shows Favorites shows the empty state before any refresh settles`() {
        start(StreamType.SERIES, CatalogViewModel.FAVORITES_CATEGORY_ID)
        compose.waitUntil(5_000) { exists("No favorite shows yet") }
    }

    @Test
    fun `a provider category stays on skeletons while no refresh has settled`() {
        start(StreamType.VOD, "drama")
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertFalse("no empty copy for a provider category still loading", exists("No movies in Drama"))
    }

    @Test
    fun `Favorites that wait on a catalog that has not landed stay on skeletons, then show posters`() {
        // A restore: the favorite exists, its movie is not in the catalog yet, no refresh settled.
        kotlinx.coroutines.runBlocking {
            db.favoriteDao().insertAll(listOf(com.rysal.happytv.data.entity.Favorite(streamId = "fav1", streamType = StreamType.VOD)))
        }
        start(StreamType.VOD, CatalogViewModel.FAVORITES_CATEGORY_ID)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertFalse("no empty copy while the catalog is still loading", exists("No favorite movies yet"))

        kotlinx.coroutines.runBlocking {
            db.vodStreamDao().replaceAll(
                listOf(VodStream(id = "fav1", categoryId = "drama", num = 1, name = "Restored Movie", added = 1L)),
            )
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Restored Movie")).fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(exists("No favorite movies yet"))
    }
}
