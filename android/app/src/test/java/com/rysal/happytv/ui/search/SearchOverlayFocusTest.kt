package com.rysal.happytv.ui.search

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.dao.SearchChannelRow
import com.rysal.happytv.data.dao.SearchPosterRow
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #59: in the Search overlay, D-pad Down from the field must reach the first
 * result (QA on the TV: it never did; Tab did). Up from the first result goes
 * to the selected scope chip, never the field (#174: the field reopens the keyboard), Left/Right move along a row, and the scope control next to
 * the field must not swallow the Down.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SearchOverlayFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun channel(name: String) = hasText(name) and hasClickAction()
    private fun poster(name: String) = hasContentDescription(name) and hasClickAction()
    private val field = SemanticsMatcher("search field") { node ->
        node.config.contains(SemanticsActions.SetText)
    }

    private val allChip = hasText("All") and hasClickAction()

    private fun startWithResults(scope: SearchScope = SearchScope.ALL) = startWith(
        channels = listOf(
            SearchChannelRow(id = "101", name = "Courtside", num = 101),
            SearchChannelRow(id = "102", name = "County Weather", num = 102),
            SearchChannelRow(id = "103", name = "Little Comets", num = 103),
        ),
        movies = listOf(SearchPosterRow(id = "m1", name = "The Long Coast", year = "2023")),
        shows = listOf(SearchPosterRow(id = "s1", name = "Comet Crew", year = "2022")),
        query = "co",
        scope = scope,
        anyResult = hasText("Courtside").or(hasContentDescription("The Long Coast"))
            .or(hasContentDescription("Comet Crew")),
    )

    private fun startWith(
        channels: List<SearchChannelRow>,
        movies: List<SearchPosterRow>,
        shows: List<SearchPosterRow>,
        query: String,
        scope: SearchScope = SearchScope.ALL,
        anyResult: SemanticsMatcher,
    ) {
        val vm = SearchViewModel(
            channels = flowOf(channels),
            movies = flowOf(movies),
            shows = flowOf(shows),
            startScope = scope,
            matchDispatcher = UnconfinedTestDispatcher(scheduler),
        )
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    SearchOverlay(
                        onBack = {},
                        onPlayChannel = {},
                        onOpenDetails = { _, _ -> },
                        viewModel = vm,
                    )
                }
            }
        }
        compose.waitForIdle()
        vm.onQueryChange(query)
        // The view model waits out the 300 ms pause on the (virtual) main dispatcher.
        scheduler.advanceTimeBy(SearchViewModel.SEARCH_PAUSE_MS + 50)
        scheduler.runCurrent()
        // The scope control also says "Channels" and "Movies": wait on a result card.
        compose.waitUntil(10_000) { exists(anyResult) }
        compose.waitUntil(10_000) { focused(field) }
    }

    private fun exists(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    private fun focused(m: SemanticsMatcher) = exists(m.and(isFocused()))

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun assertFocused(m: SemanticsMatcher, what: String) {
        compose.waitUntil(5_000) { focused(m) }
        assertTrue("$what should hold focus", focused(m))
    }

    @Test
    fun downFromTheField_reachesTheFirstResult_andUpReturns() {
        startWithResults()

        press(Key.DirectionDown)
        assertFocused(channel("Courtside"), "the first channel card")

        press(Key.DirectionUp)
        assertFocused(allChip, "the selected scope chip, not the field (#174)")
    }

    /** #174: Up from the first result reaches the chips in one press, Left goes on to the field, Down returns. */
    @Test
    fun upFromTheFirstResult_landsOnTheChips_leftReachesTheField_downComesBack() {
        startWithResults()
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        assertFocused(allChip, "the selected scope chip")
        // The chips are one press away: move along them.
        press(Key.DirectionRight)
        assertFocused(hasText("Channels") and hasClickAction(), "the Channels chip")
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertFocused(field, "the search field, entered on purpose")
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        assertFocused(channel("Courtside"), "the first channel card")
    }

    @Test
    fun resultRows_moveAcrossAndDownThroughTheGroups() {
        startWithResults()
        press(Key.DirectionDown)

        press(Key.DirectionRight)
        assertFocused(channel("County Weather"), "the second channel card")
        press(Key.DirectionLeft)
        assertFocused(channel("Courtside"), "the first channel card")

        val anyMovie = poster("The Long Coast")
        press(Key.DirectionDown)
        assertFocused(anyMovie, "a movie")
        press(Key.DirectionUp)
        assertFocused(channel("Courtside"), "the first channel card")
        // Every card of the first row sends Up to the field.
        press(Key.DirectionRight)
        press(Key.DirectionUp)
        assertFocused(allChip, "the selected scope chip")
    }

    @Test
    fun showsOnly_downFromTheFieldReachesTheFirstShow() {
        startWithResults(scope = SearchScope.SHOWS)

        press(Key.DirectionDown)
        assertFocused(poster("Comet Crew"), "the first show")
    }

    @Test
    fun withTheScopeControl_downFromTheFieldStillReachesResults() {
        startWithResults(scope = SearchScope.MOVIES)

        press(Key.DirectionDown)
        assertFocused(poster("The Long Coast"), "the first movie")
        press(Key.DirectionUp)
        assertFocused(hasText("Movies") and hasClickAction(), "the selected Movies chip")
    }

    private fun love(): Triple<List<SearchChannelRow>, List<SearchPosterRow>, List<SearchPosterRow>> = Triple(
        (1..96).map { SearchChannelRow(id = "c$it", name = "Love Channel $it", num = it) },
        (1..30).map { SearchPosterRow(id = "m$it", name = "Love Movie $it", year = null) },
        (1..30).map { SearchPosterRow(id = "s$it", name = "Love Show $it", year = "2020") },
    )

    private fun startLove(scope: SearchScope = SearchScope.ALL) {
        val (c, m, s) = love()
        startWith(c, m, s, "love", scope, anyResult = hasText("Love Channel 1").or(hasContentDescription("Love Show 1")))
    }

    /** #64 / #67: with 96 + 30 + 30 matches, Down reaches the movies, the shows and the last line. */
    @Test
    fun downThroughEveryGroup_scrollsToTheLastLine() {
        startLove()
        press(Key.DirectionDown)
        assertFocused(channel("Love Channel 1"), "the first channel card")

        val seeAllShows = hasText("See all 30 shows") and hasClickAction()
        var sawMovie = false
        var sawShow = false
        var steps = 0
        while (!focused(seeAllShows) && steps++ < 40) {
            sawMovie = sawMovie || focused(hasContentDescription("Love Movie", substring = true))
            sawShow = sawShow || focused(hasContentDescription("Love Show", substring = true))
            press(Key.DirectionDown)
        }
        assertTrue("a movie should have held focus on the way down", sawMovie)
        assertTrue("a show should have held focus on the way down", sawShow)
        assertFocused(seeAllShows, "the last line, See all shows")
        compose.onNode(seeAllShows).assertIsDisplayed()
    }

    /** #67: every type chip is on screen and reachable from the field by D-pad. */
    @Test
    fun typeChips_areFullyVisible_reachableByRight_andFilter() {
        startLove()
        listOf("All", "Channels", "Movies", "Shows").forEach { label ->
            compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
        }

        press(Key.DirectionRight)
        assertFocused(hasText("All") and hasClickAction(), "the selected chip")
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertFocused(hasText("Shows") and hasClickAction(), "the last chip")

        press(Key.DirectionCenter)
        compose.waitUntil(5_000) { !exists(hasText("Love Channel 1")) && exists(hasContentDescription("Love Show 1")) }
        // Scoped to shows: the whole group is available, so no See all line is left.
        assertTrue(!exists(hasText("See all 30 shows")))
        assertTrue(!exists(hasContentDescription("Love Movie 1")))
    }

    /** The group cut short at 24 offers See all, and OK on it switches to that type. */
    @Test
    fun seeAll_switchesTheScopeToThatType() {
        startLove()
        val seeAllChannels = hasText("See all 96 channels") and hasClickAction()
        // OK on the line is the only way to the other 72 channels: it must be reachable
        // by D-pad and must switch the scope.
        var steps = 0
        press(Key.DirectionDown)
        while (!focused(seeAllChannels) && steps++ < 10) press(Key.DirectionDown)
        assertFocused(seeAllChannels, "See all channels")
        press(Key.DirectionCenter)
        compose.waitUntil(5_000) { !exists(hasContentDescription("Love Movie 1")) && !exists(seeAllChannels) }
        // The line that held focus is gone: focus must land on the first filtered result,
        // not nowhere (the next Down used to reopen the keyboard).
        assertFocused(channel("Love Channel 1"), "the first result of the filtered list")
    }

    /**
     * The focused node's bounds plus its ring and glow margin (24 px) end inside the 54 px
     * bottom safe area (y <= 1026 of the 1080 artboard).
     */
    private fun assertFocusedWithRingInsideSafeArea(what: String) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val scale = minOf(root.width / 1920f, root.height / 1080f)
        val safeBottom = root.top + (root.height - 1080f * scale) / 2f + 1026f * scale
        val bottom = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "$what: bottom $bottom + ring ${24 * scale} should be <= $safeBottom",
            bottom + 24f * scale <= safeBottom + 1f,
        )
    }

    @Test
    fun lastPosterRow_whenFocused_keepsItsRingInsideTheBottomSafeArea() {
        startLove(scope = SearchScope.SHOWS)
        press(Key.DirectionDown)
        // Scoped to shows, 30 posters: four rows, the last holding two.
        repeat(6) { press(Key.DirectionDown) }
        assertFocused(poster("Love Show 25"), "the first poster of the last row")
        assertFocusedWithRingInsideSafeArea("last poster")
    }

    @Test
    fun lastSeeAllLine_whenFocused_keepsItsRingInsideTheBottomSafeArea() {
        startLove()
        val seeAllShows = hasText("See all 30 shows") and hasClickAction()
        press(Key.DirectionDown)
        var steps = 0
        while (!focused(seeAllShows) && steps++ < 40) press(Key.DirectionDown)
        assertFocused(seeAllShows, "the last line")
        assertFocusedWithRingInsideSafeArea("See all line")
    }

    @Test
    fun lastChannelRow_whenFocused_keepsItsRingInsideTheBottomSafeArea() {
        startLove(scope = SearchScope.CHANNELS)
        press(Key.DirectionDown)
        // 96 channels, four per row: the last row starts at "Love Channel 93".
        repeat(40) { press(Key.DirectionDown) }
        assertFocused(channel("Love Channel 93"), "the first card of the last row")
        assertFocusedWithRingInsideSafeArea("last channel row")
    }
}
