package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.RoomTestTeardown
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.LiveTvOpenOn
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [BrowseViewModel] — Live TV's state machine.
 *
 * `UnconfinedTestDispatcher` is installed as `Main`, so `viewModelScope`'s
 * coroutines (and Room's Flow emissions) run eagerly; every mutation is
 * followed by `advanceUntilIdle()` before an assertion.
 *
 * **A `ui.first { … }` predicate that asserts on a *count* must wait for the
 * count.** Each DAO flow in the ViewModel is a separate `stateIn(…, Eagerly,
 * <seed>)`, and `combine` emits as soon as every source has produced *one*
 * value — including its seed. So `it.categories.isNotEmpty()` is satisfied by
 * the very first emission, which is built from the **empty** `channelCounts`
 * seed: the rail is present but every row's count reads 0. Whether the real
 * counts have landed by then is a race against Room's query executor, which is
 * exactly why `assertEquals(3, …AllChannels.count)` failed on roughly one run
 * in six and never on the others. Gate on `totalChannels` (or the specific
 * count being asserted) so the predicate cannot match the pre-load frame.
 *
 * The headline case is the one the D-pad note in the handoff describes:
 * changing the focused category updates the right-hand pane **without OK**.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BrowseViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var db: HappyTvDatabase
    private lateinit var viewModel: BrowseViewModel

    /** 9:00 pm local on the seeded day — the guide line's stable end time. */
    private val programEndUtcMillis = java.time.LocalDate.of(2026, 10, 16)
        .atTime(21, 0)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    /** Half an hour before the programme ends, so "now" is inside it. */
    private val fixedClock = programEndUtcMillis - 1_800_000L

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedFixtures()
        viewModel = newViewModel()
        advanceUntilIdle()
    }

    /** Every ViewModel [newViewModel] built, so teardown can join them all. */
    private val extraViewModels = mutableListOf<BrowseViewModel>()

    @After
    fun tearDown() {
        // Cancel and JOIN before the Main dispatcher goes away or the db
        // closes. A Room flow query still in flight at db.close() throws
        // "Cannot perform this operation because the connection pool has been
        // closed" on its own thread — an uncaught exception that poisons the
        // NEXT test with UncaughtExceptionsBeforeTest (the flake this suite
        // used to chase). Joining the scope's job drains every collector while
        // the db is still open, so the race cannot happen.
        // Tests also build their own view models (and `.cancel()` them without
        // joining); join those too so none outlives resetMain() or the close.
        val jobs = (extraViewModels + viewModel).map { it.viewModelScope.coroutineContext.job }
        extraViewModels.clear()
        RoomTestTeardown.shutDown(jobs, db)
    }

    /**
     * #10: a fresh [BrowseViewModel] opens category-first (Favorites, or nothing
     * picked), so most tests, which were written against the old "opens on All
     * channels" default, pick All channels explicitly. [startOnAll] = false keeps
     * the real entry selection.
     */
    private fun newViewModel(
        xmltvMode: Flow<Boolean> = flowOf(false),
        startOnAll: Boolean = true,
        dao: HappyTvDatabase = db,
        // The list view: the now lines (what most of these tests read) are only looked up
        // while it is the one on screen; the guide brings its own programmes (#15).
        prefs: Flow<LiveTvPrefs> = flowOf(LiveTvPrefs(view = com.rysal.happytv.data.LiveTvView.LIST)),
        saveLastCategory: suspend (String) -> Unit = {},
        saveView: suspend (com.rysal.happytv.data.LiveTvView) -> Unit = {},
    ) = BrowseViewModel(
        categoryDao = dao.categoryDao(),
        liveStreamDao = dao.liveStreamDao(),
        favoriteDao = dao.favoriteDao(),
        recentChannelDao = dao.recentChannelDao(),
        hiddenChannelDao = dao.hiddenChannelDao(),
        epgProgramDao = dao.epgProgramDao(),
        xmltvMode = xmltvMode,
        clock = { fixedClock },
        liveTvPrefs = prefs,
        saveLastCategory = saveLastCategory,
        saveView = saveView,
    ).also {
        extraViewModels += it
        if (startOnAll) it.selectCategory(LiveCategorySelection.AllChannels)
    }

    @Test
    fun `switching category updates the right pane without pressing OK`() = runTest {
        // Starts on All channels: every channel in the catalog.
        val all = viewModel.ui.first { it.categories.isNotEmpty() && it.channels.size == 3 }
        assertEquals(3, all.channels.size)

        // The D-pad note: moving focus in the left list is enough — no OK.
        viewModel.selectCategory(LiveCategorySelection.Category("news", "News"))
        val news = viewModel.ui.first {
            (it.selectedSelection as? LiveCategorySelection.Category)?.categoryId == "news" &&
                it.channels.isNotEmpty() &&
                it.channels.all { row -> row.stream.categoryId == "news" }
        }
        assertEquals(
            listOf("Northbay News", "Capital Desk"),
            news.channels.map { it.stream.name },
        )

        viewModel.selectCategory(LiveCategorySelection.Category("sports", "Sports"))
        val sports = viewModel.ui.first {
            (it.selectedSelection as? LiveCategorySelection.Category)?.categoryId == "sports" &&
                it.channels.isNotEmpty() &&
                it.channels.all { row -> row.stream.categoryId == "sports" }
        }
        assertEquals(
            listOf("Fieldhouse Sports"),
            sports.channels.map { it.stream.name },
        )
    }

    @Test
    fun `selecting a rail row with OK loads its channels`() = runTest {
        // Spec #7 3.2: OK or Right selects (focus alone no longer does, which
        // the screen tests cover); selecting lands the category's channels.
        viewModel.selectCategory(LiveCategorySelection.Category("sports", "Sports"))
        val sports = viewModel.ui.first {
            (it.selectedSelection as? LiveCategorySelection.Category)?.categoryId == "sports" &&
                it.channels.isNotEmpty() &&
                it.channels.all { row -> row.stream.categoryId == "sports" }
        }
        assertEquals(listOf("Fieldhouse Sports"), sports.channels.map { it.stream.name })
    }

    @Test
    fun `selecting the pinned Favorites row selects favorites`() = runTest {
        viewModel.selectCategory(LiveCategorySelection.Favorites)
        val state = viewModel.ui.first { it.selectedSelection is LiveCategorySelection.Favorites }
        assertTrue(state.selectedSelection is LiveCategorySelection.Favorites)
    }

    @Test
    fun `rail pins Favorites and Recently watched above the provider categories, All channels last`() = runTest {
        val state = viewModel.ui.first { it.categories.isNotEmpty() && it.totalChannels == 3 }
        val first = state.categories[0]
        val second = state.categories[1]
        assertTrue(first is LiveCategoryItem.Pinned && first.label == "Favorites")
        assertTrue(second is LiveCategoryItem.Pinned && second.label == "Recently watched")
        assertTrue(state.categories[2] is LiveCategoryItem.Divider)
        // Spec #7 3.2: provider categories in provider order, then All channels last.
        val providers = state.categories.subList(3, state.categories.size - 1)
        assertTrue(providers.isNotEmpty() && providers.all { it is LiveCategoryItem.ProviderCategory })
        val allChannels = state.categories.last()
        assertTrue(allChannels is LiveCategoryItem.AllChannels)
        assertEquals(3, (allChannels as LiveCategoryItem.AllChannels).count)
    }

    @Test
    fun `provider categories carry their counts`() = runTest {
        // The counts arrive on their own source, so `categories.isNotEmpty()`
        // can be satisfied by the seeded rail before the per-category counts
        // land — every row then reads 0. Wait for the counts themselves.
        val state = viewModel.ui.first {
            it.categories.isNotEmpty() &&
                it.categories.filterIsInstance<LiveCategoryItem.ProviderCategory>().all { c -> c.count > 0 }
        }
        val byName = state.categories
            .filterIsInstance<LiveCategoryItem.ProviderCategory>()
            .associate { it.label to it.count }
        assertEquals(2, byName["News"])
        assertEquals(1, byName["Sports"])
    }

    @Test
    fun `toggling a favorite updates the star state on the row`() = runTest {
        viewModel.toggleFavorite("101")
        val state = viewModel.ui.first { "101" in it.favoriteIds }
        val row = state.channels.first { it.stream.id == "101" }
        assertTrue(row.isFavorite)
        val other = state.channels.first { it.stream.id == "201" }
        assertFalse(other.isFavorite)
    }

    @Test
    fun `long-press menu opens on the channel and favorite toggles from it`() = runTest {
        val stream = viewModel.ui.first { it.channels.isNotEmpty() }.channels.first().stream

        viewModel.openChannelMenu(stream)
        assertEquals(stream, viewModel.channelMenu.value)

        // The menu's first item is Favorite/Unfavorite — the same write path
        // as the row's star, so the star flips while the menu is open (F-43).
        viewModel.toggleFavorite(stream.id)
        advanceUntilIdle()
        val starred = viewModel.ui.first { stream.id in it.favoriteIds }
        assertTrue(starred.channels.first { it.stream.id == stream.id }.isFavorite)

        // Closing only closes: the star state outlives the menu.
        viewModel.closeChannelMenu()
        assertNull(viewModel.channelMenu.value)
        assertTrue(
            viewModel.ui.first().channels.first { it.stream.id == stream.id }.isFavorite,
        )
    }

    @Test
    fun `hide channel from the menu closes the menu and drops the row`() = runTest {
        val stream = viewModel.ui.first { it.channels.isNotEmpty() }.channels.first().stream
        viewModel.openChannelMenu(stream)
        viewModel.hideChannel(stream.id)
        advanceUntilIdle()
        assertNull(viewModel.channelMenu.value)
        val state = viewModel.ui.first { stream.id in it.hiddenChannelIds }
        assertFalse(state.channels.any { it.stream.id == stream.id })
    }

    @Test
    fun `favorites rail lists only starred channels`() = runTest {
        viewModel.toggleFavorite("101")
        viewModel.ui.first { "101" in it.favoriteIds }
        viewModel.selectCategory(LiveCategorySelection.Favorites)
        val state = viewModel.ui.first {
            it.selectedSelection is LiveCategorySelection.Favorites &&
                it.channels.map { row -> row.stream.id } == listOf("101")
        }
        assertEquals(listOf("101"), state.channels.map { it.stream.id })
    }

    @Test
    fun `recently watched lists channels newest-first, not in channel order`() = runTest {
        // F-56: the pane showed these in channel-number order. Watch order is
        // the reverse of channel order here (101 watched first, 201 last), so
        // "newest first" and "channel order" are different answers and this can
        // only pass if the DESC order from RecentChannelDao.recent() survives
        // the `WHERE id IN (:ids)` join that used to drop it. This mirrors QA's
        // on-device repro: watched 102 then 105, and 105 was the newest row but
        // 102 was listed above it.
        db.recentChannelDao().recordWatch("101", fixedClock - 60_000L) // oldest
        db.recentChannelDao().recordWatch("102", fixedClock - 30_000L) // middle
        db.recentChannelDao().recordWatch("201", fixedClock) // newest
        advanceUntilIdle()

        viewModel.selectCategory(LiveCategorySelection.RecentlyWatched)
        val state = viewModel.ui.first {
            it.selectedSelection is LiveCategorySelection.RecentlyWatched &&
                it.channels.size == 3
        }
        assertEquals(listOf("201", "102", "101"), state.channels.map { it.stream.id })
        // The guard that makes this test worth having: newest-first must NOT be
        // channel-number order here, so the assertion above can't pass on the
        // old buggy behaviour.
        assertNotEquals(
            "fixture is only discriminating while newest-first != channel order",
            listOf("101", "102", "201"),
            state.channels.map { it.stream.id },
        )
    }

    @Test
    fun `favorites keep channel-number order, which has no ordering contract`() = runTest {
        // F-56's fix is deliberately scoped to RecentlyWatched. Favorites
        // carries no order (its ids are a Set, and no design text promises one),
        // so the pane keeps listing it in channel-number order — pin that here
        // so the recents fix can't quietly spread into a behaviour change.
        viewModel.toggleFavorite("201")
        viewModel.toggleFavorite("101")
        viewModel.toggleFavorite("102")
        viewModel.ui.first { it.favoriteIds.size == 3 }
        viewModel.selectCategory(LiveCategorySelection.Favorites)
        val state = viewModel.ui.first {
            it.selectedSelection is LiveCategorySelection.Favorites &&
                it.channels.size == 3
        }
        assertEquals(listOf("101", "102", "201"), state.channels.map { it.stream.id })
    }

    @Test
    fun `remove from recently watched drops the row and the rail count for good`() = runTest {
        viewModel.recordWatched("101")
        viewModel.recordWatched("102")
        viewModel.ui.first { recentRailCount(it) == 2 }
        viewModel.selectCategory(LiveCategorySelection.RecentlyWatched)
        viewModel.ui.first {
            it.selectedSelection is LiveCategorySelection.RecentlyWatched &&
                it.channels.map { row -> row.stream.id } == listOf("101", "102")
        }

        // The menu action (F-53): the menu closes and the row leaves history.
        viewModel.openChannelMenu(viewModel.ui.value.channels.first().stream)
        viewModel.removeRecentlyWatched("101")
        advanceUntilIdle()
        assertNull(viewModel.channelMenu.value)

        val after = viewModel.ui.first {
            it.channels.map { row -> row.stream.id } == listOf("102") &&
                recentRailCount(it) == 1
        }
        assertEquals(listOf("102"), after.channels.map { it.stream.id })
        assertEquals(1, recentRailCount(after))
        // Persisted: gone from the `recent_channels` table, not just the screen.
        assertEquals(
            listOf("102"),
            db.recentChannelDao().recent().first().map { it.streamId },
        )
    }

    @Test
    fun `remove from recently watched leaves the other lists alone`() = runTest {
        viewModel.recordWatched("101")
        viewModel.recordWatched("201")
        viewModel.ui.first { recentRailCount(it) == 2 }

        viewModel.removeRecentlyWatched("101")
        val after = viewModel.ui.first { recentRailCount(it) == 1 }

        // History is not a hide: the channel still plays from every list.
        assertEquals(3, after.totalChannels)
        assertTrue(after.channels.any { it.stream.id == "101" })
        assertFalse(after.hiddenChannelIds.contains("101"))
    }

    @Test
    fun `hiding a channel drops it from every list and the header count`() = runTest {
        val before = viewModel.ui.first {
            it.categories.isNotEmpty() && it.totalChannels == 3
        }
        assertEquals(3, before.totalChannels)

        viewModel.hideChannel("201")
        val after = viewModel.ui.first {
            "201" in it.hiddenChannelIds &&
                it.totalChannels == 2 &&
                it.categories
                    .filterIsInstance<LiveCategoryItem.ProviderCategory>()
                    .firstOrNull { row -> row.label == "Sports" }
                    ?.count == 0
        }

        // Gone from the pane…
        assertFalse(after.channels.any { it.stream.id == "201" })
        // …and both header numbers drop.
        assertEquals(2, after.totalChannels)
        assertEquals(
            0,
            after.categories
                .filterIsInstance<LiveCategoryItem.ProviderCategory>()
                .first { it.label == "Sports" }
                .count,
        )
        // "Show hidden channels" brings it back.
        viewModel.setShowHiddenChannels(true)
        val shown = viewModel.ui.first {
            it.showHiddenChannels && it.channels.any { row -> row.stream.id == "201" }
        }
        assertTrue(shown.channels.any { it.stream.id == "201" })
    }

    @Test
    fun `hiding every channel in a category reports how many are hidden`() = runTest {
        viewModel.selectCategory(LiveCategorySelection.Category("news", "News"))
        viewModel.hideChannel("101")
        viewModel.ui.first { it.hiddenChannelIds == setOf("101") }
        viewModel.hideChannel("102")
        val state = viewModel.ui.first {
            it.selectedSelection is LiveCategorySelection.Category &&
                it.channels.isEmpty() &&
                it.hiddenChannelCount == 2
        }
        assertEquals(2, state.hiddenChannelCount)
    }

    @Test
    fun `hiding a category drops it from the rail and the category count`() = runTest {
        val before = viewModel.ui.first { it.categories.isNotEmpty() && it.visibleCategoryCount == 2 }
        assertEquals(2, before.visibleCategoryCount)

        viewModel.hideCategory("news")
        val after = viewModel.ui.first { it.visibleCategoryCount == 1 }
        assertFalse(
            after.categories.any {
                it is LiveCategoryItem.ProviderCategory && it.label == "News"
            },
        )
    }

    private fun allChannelsRailCount(state: LiveTvUiState): Int? =
        state.categories.filterIsInstance<LiveCategoryItem.AllChannels>().firstOrNull()?.count

    private fun favoritesRailCount(state: LiveTvUiState): Int? =
        state.categories.filterIsInstance<LiveCategoryItem.Pinned>()
            .firstOrNull { it.label == "Favorites" }?.count

    @Test
    fun `hiding a category drops its channels from the All channels row and the info band count`() = runTest {
        viewModel.ui.first { allChannelsRailCount(it) == 3 }

        viewModel.hideCategory("news")
        val after = viewModel.ui.first { it.visibleCategoryCount == 1 && allChannelsRailCount(it) == 1 }

        assertEquals(1, allChannelsRailCount(after))
        // The info band reads the pane's selection count while All channels is picked.
        assertEquals(1, after.selectionChannelCount)
        assertEquals(1, after.totalChannels)
    }

    @Test
    fun `a hidden channel inside a hidden category is not subtracted twice`() = runTest {
        viewModel.hideChannel("101")
        viewModel.ui.first { allChannelsRailCount(it) == 2 }

        viewModel.hideCategory("news")
        val after = viewModel.ui.first { it.visibleCategoryCount == 1 && allChannelsRailCount(it) == 1 }

        assertEquals(1, allChannelsRailCount(after))
        assertEquals(1, after.totalChannels)
        assertEquals(1, after.selectionChannelCount)
    }

    @Test
    fun `hiding a favorite channel drops it from the Favorites row count`() = runTest {
        viewModel.toggleFavorite("201")
        viewModel.ui.first { favoritesRailCount(it) == 1 }

        viewModel.hideChannel("201")
        val after = viewModel.ui.first { favoritesRailCount(it) == 0 }
        assertEquals(2, allChannelsRailCount(after))
    }

    @Test
    fun `with show hidden channels on every count includes hidden channels`() = runTest {
        viewModel.toggleFavorite("101")
        // The favorite write lands asynchronously: wait for it before the assertions read it.
        viewModel.ui.first { favoritesRailCount(it) == 1 }
        viewModel.hideChannel("101")
        viewModel.ui.first { allChannelsRailCount(it) == 2 && favoritesRailCount(it) == 0 }

        viewModel.setShowHiddenChannels(true)
        viewModel.selectCategory(LiveCategorySelection.Category("news", "News"))
        val state = viewModel.ui.first {
            it.showHiddenChannels &&
                it.selectedSelection is LiveCategorySelection.Category &&
                allChannelsRailCount(it) == 3 &&
                favoritesRailCount(it) == 1
        }

        val newsRow = state.categories.filterIsInstance<LiveCategoryItem.ProviderCategory>()
            .first { it.label == "News" }
        assertEquals(2, newsRow.count)
        assertEquals(2, state.channels.size)
        assertEquals(2, state.selectionChannelCount)
        assertEquals(3, state.totalChannels)
        assertEquals(1, favoritesRailCount(state))
    }

    @Test
    fun `the now line shows the guide title and an end time that never nulls`() = runTest {
        // Default source is the provider side (settings.epgUrl blank), and its
        // rows are stored under the *stream id* — F-36: browse used to ask
        // with `epg_channel_id` and read "No guide info" for every channel.
        val state = viewModel.ui.first {
            it.channels.firstOrNull { row -> row.stream.id == "101" }?.nowTitle != null
        }
        val row = state.channels.first { it.stream.id == "101" }
        assertEquals("Provider bulletin", row.nowTitle)
        assertEquals("until 9:00", row.nowUntilLabel)
        // A channel with no guide entry keeps the designed fallback.
        val noGuide = state.channels.first { it.stream.id == "201" }
        assertNull(noGuide.nowTitle)
        // 102 has no stored rows under either key space.
        assertNull(state.channels.first { it.stream.id == "102" }.nowTitle)
    }

    @Test
    fun `xmltv mode reads the same store by epg channel id`() = runTest {
        // Same table, other source: XMLTV rows are keyed by `channel`
        // (= epg_channel_id), which is what the guide grid looks up. The two
        // keys must not be interchangeable, or a stale row would leak across
        // sources — so this asserts the *XMLTV* title, not the provider one.
        val xmltvViewModel = newViewModel(xmltvMode = flowOf(true))
        try {
            val state = xmltvViewModel.ui.first {
                it.channels.firstOrNull { row -> row.stream.id == "101" }?.nowTitle != null
            }
            val row = state.channels.first { it.stream.id == "101" }
            assertEquals("Headline report", row.nowTitle)
            assertEquals("until 9:00", row.nowUntilLabel)
        } finally {
            xmltvViewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `hide empty categories filters the rail but not the catalog`() = runTest {
        viewModel.setHideEmptyCategories(true)
        val state = viewModel.ui.first {
            it.hideEmptyCategories &&
                it.categories.filterIsInstance<LiveCategoryItem.ProviderCategory>().size == 2
        }
        // Both seeded categories have channels, so nothing disappears.
        assertEquals(2, state.categories.filterIsInstance<LiveCategoryItem.ProviderCategory>().size)
        viewModel.setHideEmptyCategories(false)
    }

    // ------------------------------------------------------------------ #10

    @Test
    fun `with no favorites Live TV opens on nothing picked and never queries the channel list`() = runTest {
        val statements = java.util.Collections.synchronizedList(mutableListOf<String>())
        val context = ApplicationProvider.getApplicationContext<Application>()
        val watched = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> statements += sql }, java.util.concurrent.Executor { it.run() })
            .build()
        val previous = db
        db = watched
        seedFixtures()
        val entry = newViewModel(startOnAll = false)
        try {
            val state = entry.ui.first { !it.isLoading }
            advanceUntilIdle()

            assertEquals(LiveCategorySelection.NothingPicked, state.selectedSelection)
            assertTrue(state.channels.isEmpty())
            assertEquals(ChannelPaneContent.PickCategory, channelPaneContent(state))
            // The rail still shows every category with its count, from the cheap GROUP BY.
            assertEquals(
                setOf("News", "Sports"),
                state.categories.filterIsInstance<LiveCategoryItem.ProviderCategory>().map { it.label }.toSet(),
            )
            // Acceptance #4: no statement read channel rows. Counts (GROUP BY) are the
            // only thing allowed to touch live_streams on entry.
            // Room's invalidation tracker appends statements from its own thread.
            // `synchronizedList` guards single calls, not iteration, so filtering the
            // live list threw ConcurrentModificationException (#41). Snapshot it under
            // the list's own lock first.
            val seen = synchronized(statements) { statements.toList() }
            val channelReads = seen.filter { sql ->
                sql.trimStart().startsWith("SELECT", ignoreCase = true) &&
                    sql.contains("FROM live_streams", ignoreCase = true) &&
                    !sql.contains("GROUP BY", ignoreCase = true)
            }
            assertTrue("entry must not read channel rows, saw: $channelReads", channelReads.isEmpty())
        } finally {
            // Join before closing: the watched database must not close under a
            // collector or under Room's own invalidation query. `previous` stays
            // owned by tearDown.
            entry.viewModelScope.coroutineContext.job.cancelAndJoin()
            db = previous
            RoomTestTeardown.drainExecutors(watched)
            watched.close()
        }
    }

    @Test
    fun `with favorites Live TV opens on them`() = runTest {
        db.favoriteDao().toggle("101", StreamType.LIVE)
        val entry = newViewModel(startOnAll = false)
        try {
            val state = entry.ui.first { !it.isLoading && it.selectedSelection is LiveCategorySelection.Favorites }
            assertEquals(listOf("101"), state.channels.map { it.stream.id })
        } finally {
            entry.viewModelScope.cancel()
        }
    }

    @Test
    fun `selecting a category after an empty entry loads it`() = runTest {
        val entry = newViewModel(startOnAll = false)
        try {
            entry.ui.first { !it.isLoading && it.selectedSelection is LiveCategorySelection.NothingPicked }
            entry.selectCategory(LiveCategorySelection.Category("sports", "Sports"))
            val state = entry.ui.first {
                (it.selectedSelection as? LiveCategorySelection.Category)?.categoryId == "sports" &&
                    it.channels.isNotEmpty()
            }
            assertEquals(listOf("201"), state.channels.map { it.stream.id })
        } finally {
            entry.viewModelScope.cancel()
        }
    }

    // ------------------------------------------------- "Open Live TV on" (spec #7 3.3)

    @Test
    fun `the guide is the default view and the stored choice wins`() = runTest {
        val default = newViewModel(startOnAll = false, prefs = flowOf(LiveTvPrefs()))
        val list = newViewModel(
            startOnAll = false,
            prefs = flowOf(LiveTvPrefs(view = com.rysal.happytv.data.LiveTvView.LIST)),
        )
        try {
            assertEquals(com.rysal.happytv.data.LiveTvView.GUIDE, default.ui.first { !it.isLoading }.view)
            assertEquals(com.rysal.happytv.data.LiveTvView.LIST, list.ui.first { !it.isLoading }.view)
        } finally {
            default.viewModelScope.cancel()
            list.viewModelScope.cancel()
        }
    }

    @Test
    fun `the toggle switches the view at once and remembers it`() = runTest {
        val saved = mutableListOf<com.rysal.happytv.data.LiveTvView>()
        val vm = newViewModel(startOnAll = false, prefs = flowOf(LiveTvPrefs()), saveView = { saved += it })
        try {
            vm.ui.first { !it.isLoading }

            vm.setView(com.rysal.happytv.data.LiveTvView.LIST)

            assertEquals(com.rysal.happytv.data.LiveTvView.LIST, vm.ui.first { it.view == com.rysal.happytv.data.LiveTvView.LIST }.view)
            assertEquals(listOf(com.rysal.happytv.data.LiveTvView.LIST), saved)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `the TV guide tile shows the guide for this visit without remembering it`() = runTest {
        val saved = mutableListOf<com.rysal.happytv.data.LiveTvView>()
        val vm = newViewModel(
            startOnAll = false,
            prefs = flowOf(LiveTvPrefs(view = com.rysal.happytv.data.LiveTvView.LIST)),
            saveView = { saved += it },
        )
        try {
            vm.showViewForThisVisit(com.rysal.happytv.data.LiveTvView.GUIDE)

            // `ui` is a StateFlow fed by Room on other threads: it can still hold the stored view's
            // first state when this line runs, so wait for the state that has the override.
            assertEquals(
                com.rysal.happytv.data.LiveTvView.GUIDE,
                vm.ui.first { !it.isLoading && it.view == com.rysal.happytv.data.LiveTvView.GUIDE }.view,
            )
            assertTrue("only the toggle changes what the Live TV tile reopens", saved.isEmpty())
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `switching to the list view brings the now lines in`() = runTest {
        val vm = newViewModel(startOnAll = true, prefs = flowOf(LiveTvPrefs()))
        try {
            // The guide brings its own programmes, so the list's now lines are only looked
            // up once the list is the view on screen (#15: it was a 300 ms read at start).
            vm.ui.first { !it.isLoading && it.channels.isNotEmpty() }
            vm.setView(com.rysal.happytv.data.LiveTvView.LIST)

            val state = vm.ui.first { it.view == com.rysal.happytv.data.LiveTvView.LIST && it.nowResolved }
            assertTrue(state.channels.isNotEmpty())
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `programFor reads one programme with its description in either guide mode`() = runTest {
        db.epgProgramDao().insertAll(
            listOf(
                EpgProgram(channelId = "101", title = "Provider show", description = "from the provider",
                    startUtcMillis = fixedClock - 1_000, endUtcMillis = fixedClock + 1_000),
                EpgProgram(channelId = "xmltv.101", title = "XMLTV show", description = "from xmltv",
                    startUtcMillis = fixedClock - 1_000, endUtcMillis = fixedClock + 1_000),
            ),
        )
        val provider = newViewModel(startOnAll = false, xmltvMode = flowOf(false))
        val xmltv = newViewModel(startOnAll = false, xmltvMode = flowOf(true))
        try {
            assertEquals("from the provider", provider.programFor("101", "xmltv.101", fixedClock)?.description)
            assertEquals("from xmltv", xmltv.programFor("101", "xmltv.101", fixedClock)?.description)
            // XMLTV mode with no epg channel id has no key: nothing to read.
            assertNull(xmltv.programFor("101", null, fixedClock))
            assertNull(provider.programFor("101", null, fixedClock + 1_000L * 3_600_000L))
        } finally {
            provider.viewModelScope.cancel()
            xmltv.viewModelScope.cancel()
        }
    }

    @Test
    fun `Choose a category opens on nothing picked even with favorites, and queries no channels`() = runTest {
        db.favoriteDao().toggle("101", StreamType.LIVE)
        val entry = newViewModel(
            startOnAll = false,
            prefs = flowOf(LiveTvPrefs(openOn = LiveTvOpenOn.CHOOSE)),
        )
        try {
            val state = entry.ui.first { !it.isLoading }
            assertTrue(state.selectedSelection is LiveCategorySelection.NothingPicked)
            assertTrue("no channel rows on entry", state.channels.isEmpty())
            assertTrue(channelPaneContent(state) is ChannelPaneContent.PickCategory)
        } finally {
            entry.viewModelScope.cancel()
        }
    }

    @Test
    fun `Favorites with none opens on nothing picked, the rail hint state`() = runTest {
        val entry = newViewModel(
            startOnAll = false,
            prefs = flowOf(LiveTvPrefs(openOn = LiveTvOpenOn.FAVORITES)),
        )
        try {
            val state = entry.ui.first { !it.isLoading }
            assertTrue(state.selectedSelection is LiveCategorySelection.NothingPicked)
            assertTrue(channelPaneContent(state) is ChannelPaneContent.PickCategory)
        } finally {
            entry.viewModelScope.cancel()
        }
    }

    @Test
    fun `Last category reopens the remembered provider category`() = runTest {
        val entry = newViewModel(
            startOnAll = false,
            prefs = flowOf(LiveTvPrefs(openOn = LiveTvOpenOn.LAST, lastCategory = "sports")),
        )
        try {
            val state = entry.ui.first { !it.isLoading && it.channels.isNotEmpty() }
            assertEquals(
                LiveCategorySelection.Category("sports", "Sports"),
                state.selectedSelection,
            )
            assertEquals(listOf("201"), state.channels.map { it.stream.id })
        } finally {
            entry.viewModelScope.cancel()
        }
    }

    @Test
    fun `Last category restores the pinned rows too`() = runTest {
        val recent = newViewModel(
            startOnAll = false,
            prefs = flowOf(LiveTvPrefs(openOn = LiveTvOpenOn.LAST, lastCategory = BrowseViewModel.RAIL_RECENT)),
        )
        try {
            val state = recent.ui.first { !it.isLoading }
            assertTrue(state.selectedSelection is LiveCategorySelection.RecentlyWatched)
        } finally {
            recent.viewModelScope.cancel()
        }
        val all = newViewModel(
            startOnAll = false,
            prefs = flowOf(LiveTvPrefs(openOn = LiveTvOpenOn.LAST, lastCategory = BrowseViewModel.RAIL_ALL)),
        )
        try {
            val state = all.ui.first { !it.isLoading }
            assertTrue(state.selectedSelection is LiveCategorySelection.AllChannels)
        } finally {
            all.viewModelScope.cancel()
        }
    }

    @Test
    fun `Last category with nothing remembered, or a category that is gone, falls back to the favorites rule`() = runTest {
        for (remembered in listOf(null, "no-such-category")) {
            val entry = newViewModel(
                startOnAll = false,
                prefs = flowOf(LiveTvPrefs(openOn = LiveTvOpenOn.LAST, lastCategory = remembered)),
            )
            try {
                val state = entry.ui.first { !it.isLoading }
                assertTrue(
                    "remembered=$remembered, no favorites -> nothing picked",
                    state.selectedSelection is LiveCategorySelection.NothingPicked,
                )
            } finally {
                entry.viewModelScope.cancel()
            }
        }
    }

    @Test
    fun `selecting a category remembers it, and nothing picked is never remembered`() = runTest {
        val saved = mutableListOf<String>()
        val entry = newViewModel(startOnAll = false, saveLastCategory = { saved += it })
        try {
            entry.ui.first { !it.isLoading }
            entry.selectCategory(LiveCategorySelection.Category("sports", "Sports"))
            entry.selectCategory(LiveCategorySelection.Favorites)
            entry.selectCategory(LiveCategorySelection.AllChannels)
            entry.selectCategory(LiveCategorySelection.NothingPicked)
            advanceUntilIdle()
            assertEquals(
                listOf("sports", BrowseViewModel.RAIL_FAVORITES, BrowseViewModel.RAIL_ALL),
                saved,
            )
        } finally {
            entry.viewModelScope.cancel()
        }
    }

    @Test
    fun `the entry selection is not remembered until the user picks`() = runTest {
        db.favoriteDao().toggle("101", StreamType.LIVE)
        val saved = mutableListOf<String>()
        val entry = newViewModel(startOnAll = false, saveLastCategory = { saved += it })
        try {
            entry.ui.first { !it.isLoading && it.selectedSelection is LiveCategorySelection.Favorites }
            advanceUntilIdle()
            assertTrue("opening on Favorites must not overwrite Last category", saved.isEmpty())
        } finally {
            entry.viewModelScope.cancel()
        }
    }

    @Test
    fun `Keep categories open reaches the screen state`() = runTest {
        val entry = newViewModel(prefs = flowOf(LiveTvPrefs(keepCategoriesOpen = true)))
        try {
            assertTrue(entry.ui.first { !it.isLoading }.keepCategoriesOpen)
        } finally {
            entry.viewModelScope.cancel()
        }
        assertFalse(viewModel.ui.first { !it.isLoading }.keepCategoriesOpen)
    }

    @Test
    fun `hiding the selected category drops the selection back to nothing picked`() = runTest {
        viewModel.selectCategory(LiveCategorySelection.Category("sports", "Sports"))
        viewModel.ui.first { (it.selectedSelection as? LiveCategorySelection.Category)?.categoryId == "sports" }
        viewModel.hideCategory("sports")
        val state = viewModel.ui.first { it.selectedSelection is LiveCategorySelection.NothingPicked }
        assertTrue(state.channels.isEmpty())
    }

    @Test
    fun `rail entry lands on the selected category, else the first row`() = runTest {
        // `selectCategory(AllChannels)` lands a beat after the first loaded frame, so
        // wait for the selection the assertion below is about (a #41 flake: the
        // first matching frame could still be NothingPicked).
        val state = viewModel.ui.first {
            it.categories.isNotEmpty() && it.totalChannels == 3 &&
                it.selectedSelection is LiveCategorySelection.AllChannels
        }
        // Started on All channels -> that row is the entry.
        assertEquals(BrowseViewModel.RAIL_ALL, railEntryKey(state.categories))
        // Nothing selected -> the first selectable row (Favorites), never the divider.
        val none = state.categories.map { item ->
            when (item) {
                is LiveCategoryItem.AllChannels -> item.copy(selected = false)
                else -> item
            }
        }
        assertEquals(BrowseViewModel.RAIL_FAVORITES, railEntryKey(none))
        // An override (a closed category menu) wins when the row exists.
        assertEquals("sports", railEntryKey(state.categories, override = "sports"))
        assertEquals(BrowseViewModel.RAIL_ALL, railEntryKey(state.categories, override = "gone"))
    }

    @Test
    fun `now lines are looked up for the loaded rows only, and only once per key`() = runTest {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        com.rysal.happytv.perf.HtvPerf.sink = { lines += it }
        try {
            val count = BrowseViewModel.PAGE_SIZE + 60
            seedManyChannels(count)
            db.epgProgramDao().replaceAll(
                List(count) { index ->
                    EpgProgram(
                        channelId = "ch%04d".format(index + 1),
                        title = "Show ${index + 1}",
                        startUtcMillis = fixedClock - 600_000L,
                        endUtcMillis = fixedClock + 600_000L,
                    )
                },
            )
            val paged = newViewModel()
            try {
                val first = paged.ui.first { it.channels.size == BrowseViewModel.PAGE_SIZE && it.nowResolved }
                assertEquals("Show 1", first.channels.first().nowTitle)
                assertTrue(first.channels.all { it.nowTitle != null })

                paged.loadMoreChannels()
                val all = paged.ui.first { it.channels.size == count && it.nowResolved }
                assertEquals("Show $count", all.channels.last().nowTitle)

                // (The fixture ViewModel from setUp logs its own 3-key lookups into the same sink.)
                val lookups = lines.filter {
                    it.startsWith("livetv.now.lookup") && !it.contains("keys=3 ")
                }
                // Widening asks only for the 60 keys it has not seen, never for all 300
                // again (a guide write in between may legitimately refresh the first 240).
                assertTrue("widening must look up only the new keys: $lines", lookups.any { it.contains("keys=60 ") })
                assertTrue(
                    "no lookup may cover the whole widened window: $lines",
                    lookups.none { it.contains("keys=$count ") },
                )
                assertTrue(lookups.none { it.contains("keys=0 ") })
            } finally {
                paged.viewModelScope.cancel()
            }
        } finally {
            com.rysal.happytv.perf.HtvPerf.resetForTest()
        }
    }

    @Test
    fun `rows draw without waiting for their now lines`() = runTest {
        // The now lookup is a separate step after the rows: a state with rows and
        // nowResolved = false is a legitimate first paint (the screen shows the
        // rows; the lines fill in), and it must be representable.
        val state = LiveTvUiState(
            channels = listOf(LiveChannelRow(stream = LiveStream(id = "1", categoryId = "c", name = "One"))),
            selectedSelection = LiveCategorySelection.AllChannels,
            nowResolved = false,
        )
        assertTrue(channelPaneContent(state) is ChannelPaneContent.Rows)
    }

    @Test
    fun `a new category starts on a fresh first page`() = runTest {
        val count = BrowseViewModel.PAGE_SIZE + 60
        seedManyChannels(count)
        val paged = newViewModel()
        try {
            paged.ui.first { it.channels.size == BrowseViewModel.PAGE_SIZE }
            paged.loadMoreChannels()
            paged.ui.first { it.channels.size == count }

            paged.selectCategory(LiveCategorySelection.Category("bulk", "Bulk"))
            val state = paged.ui.first {
                it.selectedSelection is LiveCategorySelection.Category && it.channels.isNotEmpty()
            }
            assertEquals(
                "a window widened on All channels must not carry over",
                BrowseViewModel.PAGE_SIZE,
                state.channels.size,
            )
        } finally {
            paged.viewModelScope.cancel()
        }
    }

    /**
     * The pinned "Recently watched" rail row's count.
     *
     * `LiveTvUiState` starts with `categories = emptyList()`, and these tests
     * wait on `ui.first { … }` — so the first emission the predicate sees can
     * predate the rail. `firstOrNull` rather than `first`: a missing row must
     * answer "not yet" (-1), not throw out of the `first` predicate.
     */
    private fun recentRailCount(state: LiveTvUiState): Int = state.categories
        .filterIsInstance<LiveCategoryItem.Pinned>()
        .firstOrNull { it.id == BrowseViewModel.RAIL_RECENT }
        ?.count
        ?: -1

    // ------------------------------------------------------------------ F-46

    @Test
    fun `before the catalog answers the pane is a skeleton, not an empty state`() = runTest {
        // xmltvMode never emits, so `combine` never builds a state — exactly
        // the screen-entry moment F-46 shipped wrong ("No channels to show in
        // All channels" over a catalog that was still loading).
        val pending = newViewModel(xmltvMode = emptyFlow())
        try {
            val state = pending.ui.value
            assertTrue("first paint is loading", state.isLoading)
            assertEquals(ChannelPaneContent.Loading, channelPaneContent(state))
        } finally {
            pending.viewModelScope.cancel()
        }
    }

    @Test
    fun `an empty catalog resolves to the empty state, not an endless skeleton`() = runTest {
        db.liveStreamDao().replaceAll(emptyList())
        val empty = newViewModel()
        try {
            // The view model starts on NothingPicked and `selectCategory(AllChannels)`
            // lands a beat later; a frame that is loaded but still NothingPicked is a
            // valid state (it maps to "pick a category"), so wait for the selection
            // this test is about as well as for the load.
            val state = empty.ui.first {
                !it.isLoading && it.selectedSelection is LiveCategorySelection.AllChannels
            }
            assertTrue(state.channels.isEmpty())
            assertEquals(ChannelPaneContent.Empty.NothingHere, channelPaneContent(state))
        } finally {
            empty.viewModelScope.cancel()
        }
    }

    @Test
    fun `the pane pages one window at a time and the heading keeps the real total`() = runTest {
        // More than one page of rows, so the window boundary is real.
        val count = BrowseViewModel.PAGE_SIZE + 60
        seedManyChannels(count)
        val paged = newViewModel()
        try {
            advanceUntilIdle()
            val firstPage = paged.ui.first { it.channels.isNotEmpty() }
            assertEquals(
                "exactly one page is paged in at first",
                BrowseViewModel.PAGE_SIZE,
                firstPage.channels.size,
            )
            // The heading counts the selection, never the paged-in window.
            assertEquals(count, firstPage.selectionChannelCount)
            assertEquals(count, firstPage.totalChannels)

            // F-46's browse-deeper half: the pane pages on past the first page.
            paged.loadMoreChannels()
            val more = paged.ui.first { it.channels.size == count }
            assertEquals("ch%04d".format(count), more.channels.last().stream.id)
            assertEquals(count, more.selectionChannelCount)
        } finally {
            paged.viewModelScope.cancel()
        }
    }

    @Test
    fun `paging stops at the last channel instead of growing forever`() = runTest {
        val count = BrowseViewModel.PAGE_SIZE + 10
        seedManyChannels(count)
        val paged = newViewModel()
        try {
            advanceUntilIdle()
            assertEquals(
                BrowseViewModel.PAGE_SIZE,
                paged.ui.first { it.channels.isNotEmpty() }.channels.size,
            )
            paged.loadMoreChannels()
            assertEquals(count, paged.ui.first { it.channels.size == count }.channels.size)

            // End of data: the window came back short, so nothing more is asked
            // for and the rows stay put.
            paged.loadMoreChannels()
            advanceUntilIdle()
            assertEquals(count, paged.ui.value.channels.size)
        } finally {
            paged.viewModelScope.cancel()
        }
    }

    @Test
    fun `loadMoreChannels is a no-op on the favorites list`() = runTest {
        viewModel.toggleFavorite("101")
        viewModel.ui.first { "101" in it.favoriteIds }
        viewModel.selectCategory(LiveCategorySelection.Favorites)
        val before = viewModel.ui.first {
            it.selectedSelection is LiveCategorySelection.Favorites && it.channels.isNotEmpty()
        }
        viewModel.loadMoreChannels()
        advanceUntilIdle()
        assertEquals(before.channels.size, viewModel.ui.value.channels.size)
    }

    /** [count] live channels in one category — "ch0001"… per `num`, for the paging tests. */
    private suspend fun seedManyChannels(count: Int, categoryId: String = "bulk") {
        db.categoryDao().replaceAll(
            StreamType.LIVE,
            listOf(Category(id = categoryId, type = StreamType.LIVE, name = "Bulk")),
        )
        db.liveStreamDao().replaceAll(
            List(count) { index ->
                LiveStream(
                    id = "ch%04d".format(index + 1),
                    categoryId = categoryId,
                    num = index + 1,
                    name = "Channel %04d".format(index + 1),
                )
            },
        )
    }

    private suspend fun seedFixtures() {
        val categoryDao = db.categoryDao()
        val liveStreamDao = db.liveStreamDao()
        categoryDao.replaceAll(
            StreamType.LIVE,
            listOf(
                Category(id = "news", type = StreamType.LIVE, name = "News"),
                Category(id = "sports", type = StreamType.LIVE, name = "Sports"),
            ),
        )
        liveStreamDao.replaceAll(
            listOf(
                LiveStream(
                    id = "101",
                    categoryId = "news",
                    num = 101,
                    name = "Northbay News",
                    tvArchive = true,
                    epgChannelId = "northbay.example",
                ),
                LiveStream(
                    id = "102",
                    categoryId = "news",
                    num = 102,
                    name = "Capital Desk",
                    tvArchive = true,
                    epgChannelId = "capital.example",
                ),
                LiveStream(
                    id = "201",
                    categoryId = "sports",
                    num = 201,
                    name = "Fieldhouse Sports",
                ),
            ),
        )
        // Both key spaces of `epg_programs` side by side for channel 101
        // (GuideModels.programStoreKey): provider rows live under the stream
        // id, XMLTV rows under epg_channel_id. 20:00 -> 21:00 local, so the
        // fixed clock sits inside both programmes.
        db.epgProgramDao().replaceAll(
            listOf(
                EpgProgram(
                    channelId = "101",
                    title = "Provider bulletin",
                    startUtcMillis = fixedClock - 1_800_000L,
                    endUtcMillis = programEndUtcMillis,
                ),
                EpgProgram(
                    channelId = "northbay.example",
                    title = "Headline report",
                    startUtcMillis = fixedClock - 1_800_000L,
                    endUtcMillis = programEndUtcMillis,
                ),
            ),
        )
    }
}
