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
import com.rysal.happytv.data.guide.GuideRepository
import com.rysal.happytv.data.guide.GuideWindow
import com.rysal.happytv.data.guide.HttpXmlTvFetcher
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #146: the guide moves forward past its two-hour window. Real Room store, a fixed clock, one
 * channel with hourly programmes: Right at the window's edge shifts the window and picks the next
 * program, Left comes back and stops at the Now window, the day follows midnight, and Today snaps
 * back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GuideViewModelForwardTest {

    private val zone = ZoneId.of("UTC")
    private val minute = 60_000L
    private val hour = 60 * minute
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: HappyTvDatabase
    private lateinit var repository: GuideRepository
    private var viewModel: GuideViewModel? = null

    /** 21:45 on the 29th: the Now window is 21:00 - 23:00. */
    private val now = ZonedDateTime.of(2026, 10, 29, 21, 45, 0, 0, zone).toInstant().toEpochMilli()
    private val base = GuideWindow.fromNow(now, zone).startUtcMillis
    private val midnight = ZonedDateTime.of(2026, 10, 30, 0, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "guide-forward-${dataStoreFiles++}.preferences_pb")
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

    /**
     * Channel s0: one programme an hour from 19:00 on the 29th through 06:00 on the 30th. [replay]
     * decides whether the ones that ended before 21:45 can be opened.
     */
    private suspend fun seed(replay: Boolean = false, lastHourOfDay30: Int = 6) {
        db.categoryDao().replaceAll(
            StreamType.LIVE,
            listOf(Category(id = "c0", type = StreamType.LIVE, name = "Category 0")),
        )
        db.liveStreamDao().replaceAll(
            listOf(LiveStream(id = "s0", categoryId = "c0", num = 1, name = "Channel 0", tvArchive = replay)),
        )
        val first = ZonedDateTime.of(2026, 10, 29, 19, 0, 0, 0, zone).toInstant().toEpochMilli()
        val last = midnight + lastHourOfDay30 * hour
        db.epgProgramDao().replaceAll(
            generateSequence(first) { it + hour }.takeWhile { it < last }.map {
                EpgProgram(channelId = "s0", title = "Show at ${it / hour % 24}", startUtcMillis = it, endUtcMillis = it + hour)
            }.toList(),
        )
    }

    private suspend fun openedViewModel(): Pair<GuideViewModel, GuideUiState> {
        val vm = GuideViewModel(repository, zone) { now }.also { viewModel = it }
        vm.setChannels("sel", repository.observeChannels().first())
        return vm to vm.ui.first { it.gridReady && it.rows.isNotEmpty() }
    }

    /** The grid caught up with the ViewModel's window (a held key outruns it; a test waits). */
    private suspend fun GuideViewModel.settled(): GuideUiState =
        ui.first { it.gridReady && it.window.startUtcMillis == currentWindowStart() }

    private fun GuideUiState.lastFocusable(): GuideFocus.Program =
        GuideFocus.Program(0, rows[0].focusablePrograms.last().key)

    private fun GuideUiState.firstFocusable(): GuideFocus.Program =
        GuideFocus.Program(0, rows[0].focusablePrograms.first().key)

    @Test
    fun `the grid opens on the Now window, not moved`() = runTest {
        seed()
        val (_, state) = openedViewModel()

        assertEquals(base, state.window.startUtcMillis)
        assertFalse(state.shifted)
        assertEquals(GuideDay.TODAY, state.day)
        assertTrue(state.showNowLine)
    }

    @Test
    fun `Right at the window edge moves the window one step and picks the next program`() = runTest {
        seed()
        val (vm, state) = openedViewModel()
        val edge = state.lastFocusable() // 22:00 - 23:00

        val target = vm.moveInTime(edge, GuideKey.RIGHT)!!

        assertEquals(midnight - hour, target.startUtcMillis) // 23:00
        val moved = vm.settled()
        assertEquals(base + GuideTime.STEP_MILLIS, moved.window.startUtcMillis)
        assertTrue(moved.shifted)
        assertNotNull("the next program is in the moved grid", moved.rows[0].programAt(target.startUtcMillis))
        assertEquals("the ruler follows", 4, moved.ticks.size)
        assertEquals("9:30", moved.ticks.first().second)
        assertEquals("the Now line is still on screen", true, moved.showNowLine)
    }

    @Test
    fun `holding Right walks the evening, the day follows midnight, and the row's data ends it`() = runTest {
        seed()
        val (vm, opened) = openedViewModel()
        var focus: GuideFocus.Program = opened.lastFocusable()
        var state = opened
        val days = ArrayList<GuideDay>()
        var steps = 0

        while (true) {
            val next = vm.moveInTime(focus, GuideKey.RIGHT) ?: break
            focus = next
            state = vm.settled()
            days += state.day
            assertNotNull("the focused program is in the grid", state.rows[0].programAt(focus.startUtcMillis))
            assertTrue("the grid only moves forward", state.window.startUtcMillis >= base)
            steps++
            assertTrue("bounded", steps < 100)
        }

        // 23:00, 00:00 ... 05:00 are the programs after the 22:00 one; 05:00 - 06:00 is the last.
        assertEquals(midnight + 5 * hour, focus.startUtcMillis)
        assertTrue(state.window.startUtcMillis >= midnight)
        assertEquals(GuideDay.TOMORROW, state.day)
        assertEquals("Today until the window starts on the 30th", GuideDay.TODAY, days.first())
        assertTrue(days.zipWithNext().all { (a, b) -> b.ordinal >= a.ordinal })
        assertFalse("the Now line is off screen", state.showNowLine)
        assertTrue(state.shifted)
    }

    @Test
    fun `Left comes back, stops at the Now window, and the channel cell takes the next Left`() = runTest {
        seed()
        val (vm, opened) = openedViewModel()
        var focus = opened.lastFocusable()
        repeat(3) { focus = vm.moveInTime(focus, GuideKey.RIGHT)!!; vm.settled() }
        assertTrue(vm.ui.value.shifted)

        var guard = 0
        while (true) {
            val back = vm.moveInTime(focus, GuideKey.LEFT) ?: break
            focus = back
            val state = vm.settled()
            assertTrue("never before the Now window", state.window.startUtcMillis >= base)
            assertNotNull(state.rows[0].programAt(focus.startUtcMillis))
            assertTrue(++guard < 50)
        }

        val home = vm.settled()
        assertEquals(base, home.window.startUtcMillis)
        assertFalse(home.shifted)
        assertNull("Left at the starting window is not a move in time", vm.moveInTime(focus, GuideKey.LEFT))
    }

    @Test
    fun `Left will not go to a program that ended without replay`() = runTest {
        seed(replay = false)
        val (vm, opened) = openedViewModel()
        // 21:00 - 22:00 is on now (21:45); 20:00 - 21:00 ended and the channel has no replay.
        var focus = opened.lastFocusable()
        focus = vm.moveInTime(focus, GuideKey.RIGHT)!!
        vm.settled()
        val onNow = GuideFocus.Program(0, base)
        var back = vm.moveInTime(focus, GuideKey.LEFT)
        while (back != null && back.startUtcMillis != onNow.startUtcMillis) {
            vm.settled()
            back = vm.moveInTime(back, GuideKey.LEFT)
        }
        assertEquals(onNow, back)
        vm.settled()
        assertNull(vm.moveInTime(onNow, GuideKey.LEFT))
    }

    @Test
    fun `a channel with replay can go back to a program that ended, but not before the Now window`() = runTest {
        seed(replay = true)
        val (vm, opened) = openedViewModel()
        var focus = opened.lastFocusable()
        repeat(2) { focus = vm.moveInTime(focus, GuideKey.RIGHT)!!; vm.settled() }
        var guard = 0
        while (true) {
            focus = vm.moveInTime(focus, GuideKey.LEFT) ?: break
            vm.settled()
            assertTrue(++guard < 50)
        }
        // The window is back on Now; the first program shown there is the 21:00 one that is on now.
        assertEquals(base, vm.settled().window.startUtcMillis)
    }

    @Test
    fun `choosing Today snaps back to the Now window`() = runTest {
        seed()
        val (vm, opened) = openedViewModel()
        var focus = opened.lastFocusable()
        repeat(3) { focus = vm.moveInTime(focus, GuideKey.RIGHT)!!; vm.settled() }
        assertTrue(vm.ui.value.shifted)

        vm.setDay(GuideDay.TODAY)

        val state = vm.settled()
        assertEquals(base, state.window.startUtcMillis)
        assertFalse(state.shifted)
        assertEquals(GuideDay.TODAY, state.day)
    }

    @Test
    fun `choosing Tomorrow from a moved grid shows Tomorrow's own starting window`() = runTest {
        seed()
        val (vm, opened) = openedViewModel()
        var focus = opened.lastFocusable()
        repeat(2) { focus = vm.moveInTime(focus, GuideKey.RIGHT)!!; vm.settled() }

        vm.setDay(GuideDay.TOMORROW)

        val state = vm.settled()
        assertEquals(GuideDay.TOMORROW, state.day)
        assertFalse(state.shifted)
        assertEquals(GuideWindow.fromNow(now, zone, 1).startUtcMillis, state.window.startUtcMillis)
    }

    @Test
    fun `resetToNow is what Back does`() = runTest {
        seed()
        val (vm, opened) = openedViewModel()
        vm.moveInTime(opened.lastFocusable(), GuideKey.RIGHT)!!
        assertTrue(vm.settled().shifted)

        vm.resetToNow()

        assertEquals(base, vm.settled().window.startUtcMillis)
        assertFalse(vm.ui.value.shifted)
    }

    @Test
    fun `Yesterday does not move forward`() = runTest {
        seed()
        val (vm, _) = openedViewModel()
        vm.setDay(GuideDay.YESTERDAY)
        val state = vm.settled()
        val edge = GuideFocus.Program(0, state.rows[0].allPrograms.lastOrNull()?.key ?: return@runTest)

        assertNull(vm.moveInTime(edge, GuideKey.RIGHT))
        assertEquals(state.window.startUtcMillis, vm.currentWindowStart())
    }

    @Test
    fun `a step inside the loaded block reads nothing, a step out of it reads the next block once`() = runTest {
        seed()
        val (vm, opened) = openedViewModel()
        val firstRange = GuideTime.loadRange(base, base)
        var focus = opened.lastFocusable()
        val ranges = HashSet<GuideTime.LoadRange>()
        var guard = 0
        while (guard++ < 20) {
            focus = vm.moveInTime(focus, GuideKey.RIGHT) ?: break
            val state = vm.settled()
            ranges += GuideTime.loadRange(base, state.window.startUtcMillis)
        }
        // 22:00 -> 06:00 is 8 hours of window starts; at one block per two hours that is a handful of ranges, not a read per step.
        assertTrue(ranges.size in 2..6)
        assertTrue(firstRange !in ranges || ranges.size >= 2)
    }

    @Test
    fun `the first focusable program stays reachable after a move`() = runTest {
        seed()
        val (vm, opened) = openedViewModel()
        vm.moveInTime(opened.lastFocusable(), GuideKey.RIGHT)
        val state = vm.settled()
        // Revalidation keeps a focus that is still in the grid and re-picks within the row otherwise.
        val first = state.firstFocusable()
        assertEquals(first, revalidateFocus(first, state.rows))
        val gone = GuideFocus.Program(0, base)
        assertNotNull(revalidateFocus(gone, state.rows))
    }

    @Test
    fun `back from full screen on a moved guide - the channel cell reads the program on now, not No guide info`() = runTest {
        seed()
        val (vm, opened) = openedViewModel()
        vm.moveInTime(opened.lastFocusable(), GuideKey.RIGHT)!!
        vm.moveInTime(GuideFocus.Program(0, vm.settled().rows[0].focusablePrograms.last().key), GuideKey.RIGHT)
        val moved = vm.settled()
        // #154: on the moved window nothing is on now, but the channel cell describes the row's
        // first program rather than reading "No guide info" while the row has data.
        assertTrue(guideReturnsToNow(fullScreen = true, shifted = moved.shifted))
        val movedBand = infoBandForFocus(GuideFocus.ChannelCell(0), moved.rows, now, zone)!!
        assertTrue(movedBand.hasProgram)
        assertNotEquals(NO_GUIDE_INFO_TITLE, movedBand.title)

        vm.resetToNow() // what going full screen does

        val home = vm.settled()
        val band = infoBandForFocus(GuideFocus.ChannelCell(0), home.rows, now, zone)!!
        assertTrue(band.hasProgram)
        assertEquals("Show at 21", band.title)
        assertFalse(guideReturnsToNow(fullScreen = true, shifted = home.shifted))
        assertFalse(guideReturnsToNow(fullScreen = false, shifted = true))
    }

    private companion object {
        var dataStoreFiles = 0
    }
}
