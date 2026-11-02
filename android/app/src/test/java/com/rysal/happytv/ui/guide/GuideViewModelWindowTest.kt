package com.rysal.happytv.ui.guide

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.RoomTestTeardown
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.guide.GuideChannel
import com.rysal.happytv.data.guide.GuideRepository
import com.rysal.happytv.data.guide.HttpXmlTvFetcher
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #10 / #15: the guide reads one window of channels (not the whole selection),
 * reads programmes for those channels only - titles and times, never
 * descriptions - and does not rebuild its rows when nothing they depend on
 * changed. Live TV hands it the selection's channels; focus is not its business.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GuideViewModelWindowTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: HappyTvDatabase
    private lateinit var repository: GuideRepository
    private var viewModel: GuideViewModel? = null

    private val now = 1_800_000_000_000L
    private val channelCount = 300

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "guide-window-${dataStoreFiles++}.preferences_pb")
        }
        repository = GuideRepository(
            epgDao = db.epgProgramDao(),
            liveDao = db.liveStreamDao(),
            categoryDao = db.categoryDao(),
            favoriteDao = db.favoriteDao(),
            recentDao = db.recentChannelDao(),
            settings = SettingsRepository(dataStore),
            session = { null },
            xmlTvFetcher = HttpXmlTvFetcher(context),
        )
    }

    @After
    fun tearDown() {
        RoomTestTeardown.shutDown(listOf(viewModel?.viewModelScope?.coroutineContext?.job), db)
    }

    /** 300 channels over categories c0..c2; [withGuide] picks which get a programme on now. */
    private suspend fun seed(withGuide: (Int) -> Boolean = { true }) {
        db.categoryDao().replaceAll(
            StreamType.LIVE,
            (0 until 3).map { Category(id = "c$it", type = StreamType.LIVE, name = "Category $it") },
        )
        db.liveStreamDao().replaceAll(
            (0 until channelCount).map {
                LiveStream(id = "s$it", categoryId = "c${it % 3}", num = it + 1, name = "Channel $it", tvArchive = true)
            },
        )
        db.epgProgramDao().replaceAll(
            (0 until channelCount).filter(withGuide).map {
                EpgProgram(
                    channelId = "s$it",
                    title = "Show $it",
                    description = "About show $it",
                    startUtcMillis = now - 1_800_000L,
                    endUtcMillis = now + 1_800_000L,
                )
            },
        )
    }

    private fun newViewModel() = GuideViewModel(repository, ZoneId.of("UTC")) { now }.also { viewModel = it }

    private suspend fun allChannels(): List<GuideChannel> = repository.observeChannels().first()

    @Test
    fun `before the screen hands over channels the grid is loading, never the empty state`() = runTest {
        seed(withGuide = { false })
        val vm = newViewModel()

        val state = vm.ui.value
        assertFalse(state.gridReady)
        assertFalse("loading is not 'No guide yet' (#43)", state.isEmptyState)
    }

    @Test
    fun `the grid opens on one window of the selection, with its programmes`() = runTest {
        seed()
        val vm = newViewModel()

        vm.setChannels("sel", allChannels())
        val state = vm.ui.first { it.gridReady && it.rows.isNotEmpty() }

        assertEquals(GuideViewModel.ROW_PAGE, state.rows.size)
        assertEquals("Channel 0", state.rows.first().channel.name)
        assertEquals("sel", state.tag)
        // The window's programmes arrived with its channels.
        assertTrue(state.rows.all { row -> row.blocks.any { it is GuideBlock.ProgramBlock } })
        assertFalse(state.isEmptyState)
    }

    @Test
    fun `the window reads titles and times only - the description is a single-row read`() = runTest {
        seed()
        val vm = newViewModel()
        vm.setChannels("sel", allChannels())

        val state = vm.ui.first { it.gridReady && it.rows.isNotEmpty() }
        val block = state.rows.first().allPrograms.first()
        assertNull("the batched window must not carry descriptions", block.program.description)

        val single = repository.programAt("s0", block.program.startUtcMillis)
        assertEquals("About show 0", single?.description)
        assertEquals("Show 0", single?.title)
    }

    @Test
    fun `focus near the end of the window widens it by one page`() = runTest {
        seed()
        val vm = newViewModel()
        vm.setChannels("sel", allChannels())
        vm.ui.first { it.gridReady && it.rows.size == GuideViewModel.ROW_PAGE }

        // Far from the end: nothing happens.
        vm.widenWindowNear(5)
        assertEquals(GuideViewModel.ROW_PAGE, vm.ui.value.rows.size)

        vm.widenWindowNear(GuideViewModel.ROW_PAGE - GuideViewModel.ROW_LOOKAHEAD + 1)

        val widened = vm.ui.first { it.rows.size == GuideViewModel.ROW_PAGE * 2 }
        assertEquals(GuideViewModel.ROW_PAGE * 2, widened.rows.size)
        // Rows keep their order and their identity-by-index, so focus is still valid.
        assertEquals("Channel 0", widened.rows.first().channel.name)
        assertEquals("Channel ${GuideViewModel.ROW_PAGE * 2 - 1}", widened.rows.last().channel.name)
    }

    @Test
    fun `a new selection starts on a fresh first page`() = runTest {
        seed()
        val vm = newViewModel()
        val everything = allChannels()
        vm.setChannels("all", everything)
        vm.ui.first { it.gridReady && it.rows.size == GuideViewModel.ROW_PAGE }
        vm.widenWindowNear(GuideViewModel.ROW_PAGE - 1)
        vm.ui.first { it.rows.size == GuideViewModel.ROW_PAGE * 2 }

        val news = everything.filter { it.categoryId == "c1" }
        vm.setChannels("c1", news)

        val state = vm.ui.first { it.tag == "c1" && it.gridReady }
        assertEquals(channelCount / 3, state.rows.size)
        assertTrue(state.rows.all { it.channel.categoryId == "c1" })
        assertTrue(state.rows.all { row -> row.blocks.any { it is GuideBlock.ProgramBlock } })
    }

    @Test
    fun `showRows widens the window to reach a deep channel`() = runTest {
        seed()
        val vm = newViewModel()
        vm.setChannels("sel", allChannels())
        vm.ui.first { it.gridReady && it.rows.size == GuideViewModel.ROW_PAGE }

        vm.showRows("sel", GuideViewModel.ROW_PAGE + 5)

        val state = vm.ui.first { it.rows.size == GuideViewModel.ROW_PAGE * 2 }
        assertEquals("Channel ${GuideViewModel.ROW_PAGE + 4}", state.rows[GuideViewModel.ROW_PAGE + 4].channel.name)
    }

    @Test
    fun `a floor asked for before the new selection arrives survives its fresh first page`() = runTest {
        seed()
        val vm = newViewModel()
        val everything = allChannels()
        vm.setChannels("all", everything)
        vm.ui.first { it.gridReady && it.rows.size == GuideViewModel.ROW_PAGE }

        // The hold-return jump asks for rows of a selection that has not been handed over yet.
        vm.showRows("deep", GuideViewModel.ROW_PAGE + 5)
        vm.setChannels("deep", everything)

        val state = vm.ui.first { it.tag == "deep" && it.gridReady && it.rows.size == GuideViewModel.ROW_PAGE * 2 }
        assertEquals(GuideViewModel.ROW_PAGE * 2, state.rows.size)
    }

    @Test
    fun `handing over the same channels again does not rebuild the rows`() = runTest {
        seed()
        val vm = newViewModel()
        val everything = allChannels()
        vm.setChannels("sel", everything)
        val before = vm.ui.first { it.gridReady && it.rows.size == GuideViewModel.ROW_PAGE }

        vm.setChannels("sel", everything.toList())
        vm.setDay(GuideDay.TODAY) // already today: a no-op
        val after = vm.ui.value

        assertSame("same selection, same day: same rows", before.rows, after.rows)
    }

    @Test
    fun `another day rebuilds the window and hides the Now line`() = runTest {
        seed()
        val vm = newViewModel()
        vm.setChannels("sel", allChannels())
        vm.ui.first { it.gridReady && it.rows.size == GuideViewModel.ROW_PAGE }

        vm.setDay(GuideDay.TOMORROW)

        val state = vm.ui.first { it.day == GuideDay.TOMORROW && it.rows.isNotEmpty() }
        assertFalse(state.showNowLine)
    }

    @Test
    fun `a first window with no guide data is not the empty state while the store has data`() = runTest {
        // Only the last channels have guide rows: the first page shows striped
        // "No guide info" rows, not the "No guide yet" empty state.
        seed(withGuide = { it >= 250 })
        val vm = newViewModel()
        vm.setChannels("sel", allChannels())

        val state = vm.ui.first { it.gridReady && it.rows.size == GuideViewModel.ROW_PAGE }

        assertFalse("the store has guide rows, just not in this window", state.isEmptyState)
        assertTrue(state.rows.first().blocks.all { it is GuideBlock.GapBlock })
    }

    @Test
    fun `an empty store is still the empty state`() = runTest {
        seed(withGuide = { false })
        val vm = newViewModel()
        vm.setChannels("sel", allChannels())

        val state = vm.ui.first { it.gridReady && it.rows.size == GuideViewModel.ROW_PAGE }

        assertTrue(state.isEmptyState)
    }

    private companion object {
        var dataStoreFiles = 0
    }
}
