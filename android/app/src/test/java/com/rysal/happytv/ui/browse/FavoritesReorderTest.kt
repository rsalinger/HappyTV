package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.RoomTestTeardown
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.LiveTvView
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #221: the pure rules of reorder mode. */
class FavoritesReorderRulesTest {

    @Test
    fun `movedTo takes the id out and puts it at the index, clamped to the ends`() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "c", "b", "d"), order.movedTo("b", 2))
        assertEquals(listOf("b", "a", "c", "d"), order.movedTo("b", -5))
        assertEquals(listOf("a", "c", "d", "b"), order.movedTo("b", 99))
        assertEquals(listOf("d", "a", "b", "c"), order.movedTo("d", 0))
    }

    @Test
    fun `movedTo at an end, or for an unknown id, returns the same list so nothing is written`() {
        val order = listOf("a", "b", "c")
        assertTrue(order === order.movedTo("a", -1))
        assertTrue(order === order.movedTo("c", 3))
        assertTrue(order === order.movedTo("b", 1))
        assertTrue(order === order.movedTo("zz", 0))
    }

    @Test
    fun `holding a key moves further per press the longer it is held`() {
        val steps = (0..60).map { reorderStepFor(it) }
        assertEquals(1, reorderStepFor(0))
        assertEquals(1, reorderStepFor(5))
        assertTrue(reorderStepFor(10) > reorderStepFor(5))
        assertTrue(reorderStepFor(20) > reorderStepFor(10))
        assertTrue(reorderStepFor(40) > reorderStepFor(20))
        assertEquals("never slows down while held", steps, steps.sorted())
    }

    @Test
    fun `rows follow the working order and a row the order does not name goes last`() {
        fun row(id: String) = LiveChannelRow(LiveStream(id = id, categoryId = "x", name = id))
        val rows = listOf(row("a"), row("b"), row("c"), row("new"))
        assertEquals(listOf("c", "a", "b", "new"), rows.inOrder(listOf("c", "a", "gone", "b")).map { it.stream.id })
    }
}

/** #221: the ViewModel's reorder mode, against a real in-memory database. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BrowseViewModelReorderTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: HappyTvDatabase
    private lateinit var viewModel: BrowseViewModel

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
        db.categoryDao().replaceAll(StreamType.LIVE, listOf(Category(id = "news", type = StreamType.LIVE, name = "News")))
        db.liveStreamDao().replaceAll(
            (1..6).map { LiveStream(id = "c$it", categoryId = "news", num = 100 + it, name = "Channel $it") },
        )
        viewModel = BrowseViewModel(
            categoryDao = db.categoryDao(),
            liveStreamDao = db.liveStreamDao(),
            favoriteDao = db.favoriteDao(),
            recentChannelDao = db.recentChannelDao(),
            hiddenChannelDao = db.hiddenChannelDao(),
            epgProgramDao = db.epgProgramDao(),
            xmltvMode = flowOf(false),
            clock = { 1_000L },
            liveTvPrefs = flowOf(LiveTvPrefs(view = LiveTvView.LIST)),
            writeDispatcher = Dispatchers.Unconfined,
        )
        advanceUntilIdle()
    }

    @After
    fun tearDown() {
        RoomTestTeardown.shutDown(listOf(viewModel.viewModelScope.coroutineContext.job), db)
    }

    private suspend fun starAll(vararg ids: String) {
        ids.forEach { viewModel.toggleFavorite(it) }
        viewModel.ui.first { it.favoriteIds.size == ids.size }
    }

    private suspend fun openFavorites(count: Int): LiveTvUiState {
        viewModel.selectCategory(LiveCategorySelection.Favorites)
        return viewModel.ui.first { it.selectedSelection is LiveCategorySelection.Favorites && it.channels.size == count }
    }

    /** The UI state is built off the main thread, so wait for the order instead of reading it at once. */
    private suspend fun assertIds(vararg expected: String) {
        viewModel.ui.first { state -> state.channels.map { it.stream.id } == expected.toList() }
    }

    private suspend fun mode(predicate: (ReorderUi) -> Boolean = { true }): ReorderUi =
        viewModel.ui.first { it.reorder?.let(predicate) == true }.reorder!!

    private suspend fun savedOrder(expected: List<String>) {
        db.favoriteDao().observeOrdered(StreamType.LIVE).first { rows -> rows.map { it.streamId } == expected }
    }

    @Test
    fun `Favorites lists in starred order and numbers its rows 1 to N, not the provider's numbers`() = runTest {
        starAll("c5", "c2", "c6")
        val state = openFavorites(3)
        assertEquals(listOf("c5", "c2", "c6"), state.channels.map { it.stream.id })
        assertEquals(listOf(1, 2, 3), state.channels.map { it.position })
        assertEquals("the provider's number is still on the stream", listOf(105, 102, 106), state.channels.map { it.stream.num })
    }

    @Test
    fun `other categories keep the provider's number and no position`() = runTest {
        starAll("c1")
        viewModel.selectCategory(LiveCategorySelection.AllChannels)
        val state = viewModel.ui.first { it.selectedSelection is LiveCategorySelection.AllChannels && it.channels.size == 6 }
        assertTrue(state.channels.all { it.position == null })
    }

    @Test
    fun `removing a favorite closes the gap in the numbering`() = runTest {
        starAll("c1", "c2", "c3")
        openFavorites(3)
        viewModel.toggleFavorite("c2")
        val state = viewModel.ui.first { it.channels.size == 2 }
        assertEquals(listOf("c1", "c3"), state.channels.map { it.stream.id })
        assertEquals(listOf(1, 2), state.channels.map { it.position })
    }

    @Test
    fun `reorder starts only in Favorites`() = runTest {
        starAll("c1", "c2")
        viewModel.selectCategory(LiveCategorySelection.AllChannels)
        viewModel.ui.first { it.selectedSelection is LiveCategorySelection.AllChannels && it.channels.size == 6 }
        viewModel.startReorder()
        advanceUntilIdle()
        assertNull(viewModel.ui.value.reorder)

        openFavorites(2)
        viewModel.startReorder()
        val m = mode()
        assertNull("nothing is selected yet", m.selectedId)
        assertEquals(2, m.total)
    }

    @Test
    fun `select, move and place saves the new order and survives a restart of the screen`() = runTest {
        starAll("c1", "c2", "c3", "c4")
        openFavorites(4)
        viewModel.startReorder()
        viewModel.reorderOk("c3")
        viewModel.reorderMove(-1)
        viewModel.reorderMove(-1)
        val moving = viewModel.ui.first { it.channels.map { r -> r.stream.id } == listOf("c3", "c1", "c2", "c4") }
        assertEquals(1, moving.reorder!!.position)
        assertEquals(3, moving.reorder!!.startPosition)
        assertEquals("c3", moving.reorder!!.selectedId)
        assertEquals("nothing saved until it is placed", listOf("c1", "c2", "c3", "c4"), db.favoriteDao().getOrdered(StreamType.LIVE).map { it.streamId })

        viewModel.reorderOk("c3")
        savedOrder(listOf("c3", "c1", "c2", "c4"))
        val placed = viewModel.ui.first { it.reorder?.notice != null }.reorder!!
        assertNull(placed.selectedId)
        assertEquals(ReorderNotice(ReorderNotice.Kind.PLACED, "c3", 1), placed.notice)

        // A fresh ViewModel (the app restarted) reads the same order from Room.
        val again = BrowseViewModel(
            categoryDao = db.categoryDao(), liveStreamDao = db.liveStreamDao(), favoriteDao = db.favoriteDao(),
            recentChannelDao = db.recentChannelDao(), hiddenChannelDao = db.hiddenChannelDao(),
            epgProgramDao = db.epgProgramDao(), xmltvMode = flowOf(false), clock = { 1_000L },
            liveTvPrefs = flowOf(LiveTvPrefs(view = LiveTvView.LIST)),
        )
        again.selectCategory(LiveCategorySelection.Favorites)
        val reopened = again.ui.first { it.selectedSelection is LiveCategorySelection.Favorites && it.channels.size == 4 }
        assertEquals(listOf("c3", "c1", "c2", "c4"), reopened.channels.map { it.stream.id })
        again.viewModelScope.coroutineContext.job.cancel()
    }

    @Test
    fun `the ends stop and do nothing`() = runTest {
        starAll("c1", "c2", "c3")
        openFavorites(3)
        viewModel.startReorder()
        viewModel.reorderOk("c1")
        viewModel.reorderMove(-1)
        assertIds("c1", "c2", "c3")
        viewModel.reorderMove(50)
        assertIds("c2", "c3", "c1")
        viewModel.reorderMove(1)
        assertIds("c2", "c3", "c1")
    }

    @Test
    fun `Back with a moved channel selected puts it back and stays in the mode, then Back exits`() = runTest {
        starAll("c1", "c2", "c3", "c4")
        openFavorites(4)
        viewModel.startReorder()
        viewModel.reorderOk("c2")
        viewModel.reorderMove(2)
        assertIds("c1", "c3", "c4", "c2")

        assertTrue(viewModel.reorderBack())
        val undone = viewModel.ui.first { it.reorder?.notice != null }
        assertEquals(listOf("c1", "c2", "c3", "c4"), undone.channels.map { it.stream.id })
        assertNotNull("the mode stays", undone.reorder)
        assertNull(undone.reorder!!.selectedId)
        assertEquals(ReorderNotice(ReorderNotice.Kind.RESTORED, "c2", 2), undone.reorder!!.notice)
        assertEquals("an undo writes nothing", listOf("c1", "c2", "c3", "c4"), db.favoriteDao().getOrdered(StreamType.LIVE).map { it.streamId })

        assertTrue(viewModel.reorderBack())
        viewModel.ui.first { it.reorder == null }
        assertFalse("no mode left to act on", viewModel.reorderBack())
    }

    @Test
    fun `Back after selecting without moving just puts it down, with no notice`() = runTest {
        starAll("c1", "c2")
        openFavorites(2)
        viewModel.startReorder()
        viewModel.reorderOk("c1")
        viewModel.reorderBack()
        val m = mode { it.selectedId == null }
        assertNull(m.notice)
    }

    @Test
    fun `jumps send the selected channel to the top or bottom and it stays selected`() = runTest {
        starAll("c1", "c2", "c3", "c4", "c5")
        openFavorites(5)
        viewModel.startReorder()
        viewModel.reorderOk("c3")
        viewModel.reorderJump(toTop = true)
        assertIds("c3", "c1", "c2", "c4", "c5")
        assertEquals("c3", mode().selectedId)
        viewModel.reorderJump(toTop = false)
        assertIds("c1", "c2", "c4", "c5", "c3")
        val atBottom = mode { it.position == 5 }
        assertEquals(3, atBottom.startPosition)

        // Placing keeps the jump; Back would have undone it.
        viewModel.reorderOk("c3")
        savedOrder(listOf("c1", "c2", "c4", "c5", "c3"))
    }

    @Test
    fun `jumps and moves with nothing selected do nothing`() = runTest {
        starAll("c1", "c2", "c3")
        openFavorites(3)
        viewModel.startReorder()
        viewModel.reorderJump(toTop = false)
        viewModel.reorderMove(1)
        assertIds("c1", "c2", "c3")
    }

    @Test
    fun `Home or screen-off while a channel is selected acts like Back, and a placed one is already saved`() = runTest {
        starAll("c1", "c2", "c3")
        openFavorites(3)
        viewModel.startReorder()
        viewModel.reorderOk("c1")
        viewModel.reorderMove(1)
        viewModel.reorderOk("c1") // placed: saved at once
        savedOrder(listOf("c2", "c1", "c3"))

        viewModel.reorderOk("c3")
        viewModel.reorderMove(-2)
        assertIds("c3", "c2", "c1")
        viewModel.reorderRelease()
        assertIds("c2", "c1", "c3")
        mode { it.selectedId == null } // and the mode is still there for when the user comes back
        assertEquals(listOf("c2", "c1", "c3"), db.favoriteDao().getOrdered(StreamType.LIVE).map { it.streamId })
    }

    @Test
    fun `a hidden favorite keeps its place when the shown ones are reordered`() = runTest {
        starAll("c1", "c2", "c3", "c4")
        db.hiddenChannelDao().toggle("c2", StreamType.LIVE)
        viewModel.selectCategory(LiveCategorySelection.Favorites)
        viewModel.ui.first { it.selectedSelection is LiveCategorySelection.Favorites && it.channels.map { r -> r.stream.id } == listOf("c1", "c3", "c4") }
        viewModel.startReorder()
        viewModel.reorderOk("c4")
        viewModel.reorderJump(toTop = true)
        viewModel.reorderOk("c4")
        savedOrder(listOf("c4", "c2", "c1", "c3"))
    }

    @Test
    fun `choosing another category ends the mode`() = runTest {
        starAll("c1", "c2")
        openFavorites(2)
        viewModel.startReorder()
        viewModel.selectCategory(LiveCategorySelection.AllChannels)
        viewModel.ui.first { it.selectedSelection is LiveCategorySelection.AllChannels && it.reorder == null }
    }
}
