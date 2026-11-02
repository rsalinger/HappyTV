package com.rysal.happytv.ui.catalog

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.RoomTestTeardown
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.SeriesStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.network.SeriesInfoDto
import com.rysal.happytv.data.network.SeriesStreamDto
import com.rysal.happytv.data.network.XtreamRepository
import com.rysal.happytv.ui.main.FakeXtreamApi
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #74 (and the #78 QA finding): coming back from the player must not leave focus on Back. A show
 * returns to the episode that was playing, a movie to its primary action. The real
 * [DetailsScreen] runs over an in-memory database; the lifecycle stands in for the player's
 * Stop watching (stop, then resume), and focus is first parked on Back, where the window leaves it.
 */
@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DetailsFocusRestoreTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: HappyTvDatabase
    private val viewModels = mutableListOf<CatalogViewModel>()
    private val played = mutableListOf<ResumePoint?>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
        kotlinx.coroutines.runBlocking {
            db.categoryDao().replaceAll(StreamType.SERIES, listOf(Category("c", StreamType.SERIES, "Drama")))
            db.seriesStreamDao().replaceAll(
                listOf(
                    SeriesStream(id = "s1", categoryId = "c", name = "Show one"),
                    SeriesStream(
                        id = "s2", categoryId = "c", name = "A show with a rather long title",
                        genre = "Drama, Comedy", plot = "A long description of the show that needs more than a few lines to say what it is about, " +
                            "who is in it and why anyone should care, repeated so it runs past the room under the poster. " +
                            "A long description of the show that needs more than a few lines to say what it is about.",
                    ),
                ),
            )
            db.categoryDao().replaceAll(StreamType.VOD, listOf(Category("v", StreamType.VOD, "Drama")))
            db.vodStreamDao().replaceAll(listOf(VodStream(id = "m1", categoryId = "v", name = "Alpha")))
        }
    }

    @After
    fun tearDown() {
        val jobs = viewModels.map { it.viewModelScope.coroutineContext.job }
        viewModels.clear()
        RoomTestTeardown.shutDown(jobs, db)
    }

    private fun factory(type: StreamType, id: String) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val api = FakeXtreamApi().apply {
                seriesInfoResponse = SeriesInfoDto(
                    info = SeriesStreamDto(name = "Show one", series_id = "s1"),
                    episodes = Graph.json.parseToJsonElement(
                        if (id == "s2") {
                            (1..8).joinToString(prefix = """{"1":[""", postfix = "]}") {
                                """{"id":"x$it","episode_num":"$it","title":"Ep $it","info":{"plot":"A plot"}}"""
                            }
                        } else {
                            """{"1":[{"id":"e1","episode_num":"1","title":"First"},{"id":"e2","episode_num":"2","title":"Second"}]}"""
                        },
                    ),
                )
            }
            val vm = CatalogViewModel(
                categoryDao = db.categoryDao(),
                vodStreamDao = db.vodStreamDao(),
                seriesStreamDao = db.seriesStreamDao(),
                favoriteDao = db.favoriteDao(),
                streamType = type,
                streamId = id,
                session = {
                    XtreamRepository(host = "panel.example", port = 8080, useTls = false, username = "u", password = "p", api = api)
                },
                watchProgressDao = db.watchProgressDao(),
            )
            viewModels += vm
            return vm as T
        }
    }

    private fun render(type: StreamType, id: String) {
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                DetailsScreen(
                    streamType = type,
                    streamId = id,
                    viewModelFactory = factory(type, id),
                    onPlay = { _, point -> played += point },
                )
            } }
        }
        compose.waitForIdle()
    }

    private fun key(k: Key) { compose.onRoot().performKeyInput { pressKey(k) }; compose.waitForIdle() }

    private fun leaveAndReturn() {
        // The window leaves focus on Back when the page resumes.
        compose.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithContentDescription("Back").assertIsFocused() }.isFailure
        }
        compose.waitForIdle()
    }

    @Test
    fun `the episode note shows on an episode row and goes back to the plot on the chips`() {
        render(StreamType.SERIES, "s1")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Season 1")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        fun noteShown() = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("details-episode-note"))
            .fetchSemanticsNodes().isNotEmpty()

        key(Key.DirectionDown); key(Key.DirectionDown) // Play -> chips -> first episode
        compose.waitUntil(5_000) { noteShown() }
        key(Key.DirectionUp) // back to the season chips
        compose.waitUntil(5_000) { !noteShown() }
    }

    @Test
    fun `back from an episode lands on that episode, not on Back`() {
        render(StreamType.SERIES, "s1")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Season 1")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        // Play S1 E1 -> chips -> chips' list -> second episode.
        key(Key.DirectionDown); key(Key.DirectionDown); key(Key.DirectionDown)
        compose.onNode(hasText("E2 · Second", substring = true) and hasClickAction()).assertIsFocused()
        key(Key.DirectionCenter)
        assertEquals("e2", played.last()?.episodeId)

        leaveAndReturn()
        compose.onNode(hasText("E2 · Second", substring = true) and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `back from the primary action lands on the primary action`() {
        render(StreamType.SERIES, "s1")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Play S1 E1")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Play S1 E1").assertIsFocused()
        key(Key.DirectionCenter)
        assertEquals("e1", played.last()?.episodeId)

        leaveAndReturn()
        compose.onNodeWithText("Play S1 E1").assertIsFocused()
    }

    @Test
    fun `back from a movie lands on its primary action`() {
        render(StreamType.VOD, "m1")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Play")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Play").assertIsFocused()
        key(Key.DirectionCenter)
        assertEquals(1, played.size)

        leaveAndReturn()
        compose.onNodeWithText("Play").assertIsFocused()
    }

    // ------------------------------------------------------------ open, late progress, layout

    @Test
    fun `a movie details page opens with the primary action focused`() {
        render(StreamType.VOD, "m1")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Play")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Play").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithText("Add to favorites").assertIsFocused()
    }

    @Test
    fun `a show details page opens with the primary action focused and keeps it when episodes load`() {
        render(StreamType.SERIES, "s1")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Play S1 E1")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // The label changed from "Play first episode" while loading; the focus held.
        compose.onNodeWithText("Play S1 E1").assertIsFocused()
    }

    @Test
    fun `progress that arrives after the page opened does not take focus away`() {
        render(StreamType.VOD, "m1")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Play")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Play").assertIsFocused()
        kotlinx.coroutines.runBlocking {
            db.watchProgressDao().upsert(
                com.rysal.happytv.data.entity.WatchProgress(StreamType.VOD, "m1", null, null, null, 86_000L, 7_200_000L, 1L),
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Resume from 1:26")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Resume from 1:26").assertIsFocused()
    }

    @Test
    fun `back from a movie lands on Resume when the progress was saved while the player ran`() {
        render(StreamType.VOD, "m1")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Play")).fetchSemanticsNodes().isNotEmpty() }
        key(Key.DirectionCenter)
        // The player saves on Stop watching, while the page is stopped.
        kotlinx.coroutines.runBlocking {
            db.watchProgressDao().upsert(
                com.rysal.happytv.data.entity.WatchProgress(StreamType.VOD, "m1", null, null, null, 86_000L, 7_200_000L, 1L),
            )
        }
        leaveAndReturn()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Resume from 1:26")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Resume from 1:26").assertIsFocused()
    }

    @Test
    fun `the episode area never overlaps the poster and shows three rows under a two line title`() {
        render(StreamType.SERIES, "s2")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Season 1")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val poster = compose.onNodeWithTag("details-poster").fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithText("Season 1").fetchSemanticsNode().boundsInRoot
        fun apart(a: androidx.compose.ui.geometry.Rect, b: androidx.compose.ui.geometry.Rect) =
            a.right <= b.left || a.left >= b.right || a.bottom <= b.top || a.top >= b.bottom
        assertEquals("chip $chip vs poster $poster", true, apart(chip, poster))

        // Handoff px from the poster: it is 400 x 600 at (96, 150).
        val scale = poster.height / 600f
        val safeBottom = poster.top + (1026 - 150) * scale
        val rows = compose.onAllNodes(hasText("\u00b7 Ep", substring = true)).fetchSemanticsNodes()
            .filter { it.layoutInfo.isPlaced }
        rows.forEach { r -> assertEquals("row ${r.boundsInRoot} vs poster $poster", true, apart(r.boundsInRoot, poster)) }
        val whole = rows.count { it.boundsInRoot.bottom <= safeBottom }
        assertEquals("rows fully above the safe edge: $whole of ${rows.size}", true, whole >= 3)
    }

    @Test
    fun `genres and the plot sit under the poster inside the safe area`() {
        render(StreamType.SERIES, "s2")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Season 1")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val poster = compose.onNodeWithTag("details-poster").fetchSemanticsNode().boundsInRoot
        val box = compose.onNodeWithTag("details-under-poster").fetchSemanticsNode().boundsInRoot
        val plot = compose.onNodeWithTag("details-plot").fetchSemanticsNode().boundsInRoot
        val scale = poster.height / 600f
        val safeBottom = poster.top + (1026 - 150) * scale
        assertEquals("under the poster: $box vs $poster", true, box.top >= poster.bottom)
        assertEquals(true, box.left >= poster.left - 1f && box.right <= poster.right + 1f)
        assertEquals("plot $plot inside the column $box", true, plot.bottom <= box.bottom + 1f && plot.bottom <= safeBottom + 1f)
        assertEquals(true, compose.onAllNodes(hasText("Drama")).fetchSemanticsNodes().any { it.layoutInfo.isPlaced })
    }
}
