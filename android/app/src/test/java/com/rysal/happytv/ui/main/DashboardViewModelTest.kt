package com.rysal.happytv.ui.main

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.SyncMark
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.network.LoginResult
import com.rysal.happytv.data.network.RefreshCoordinator
import com.rysal.happytv.data.network.RefreshGateway
import com.rysal.happytv.data.network.RefreshProgress
import com.rysal.happytv.data.network.RefreshState
import com.rysal.happytv.data.network.SessionState
import com.rysal.happytv.security.SecuredCredentialsStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

/**
 * Drives [DashboardViewModel] through every row of the design spec tile-state
 * table, two ways:
 *
 *  1. Integration: a *real* [RefreshCoordinator] over a [FakeXtreamApi] panel
 *     (the 6-call fan-out, the Room writes, the DataStore stamps) — proves
 *     counts land with Done and per-tile refresh leaves the other two alone.
 *  2. [RecordingGateway]: a fake that records the call list (the acceptance's
 *     "fake coordinator that records the call list") and lets a test script
 *     each tile's state by hand, with [MotionGates] holding the refresh
 *     motion open so every phase — Updating, Complete, Collapsing, Fresh —
 *     is observable.
 *
 * The "never both, never neither" invariant is asserted at every step: a
 * tile shows the bar XOR the stamp, and counts are lines XOR placeholders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DashboardViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var db: HappyTvDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var credentials: SecuredCredentialsStore
    private lateinit var coordinator: RefreshCoordinator
    private lateinit var api: FakeXtreamApi

    /** Every VM built in a test — cleared in tearDown so their tickers and
     *  motion coroutines can't touch Dispatchers.Main after resetMain(). */
    private val createdViewModels = mutableListOf<DashboardViewModel>()

    /** [closeDatabaseEventually] runs at most once per test instance. */
    private var closedDatabase = false

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "dash-settings-${dataStoreFiles++}.preferences_pb")
        }
        settings = SettingsRepository(dataStore)
        credentials = SecuredCredentialsStore(
            prefsFactory = { context.getSharedPreferences("test_secure_prefs", Context.MODE_PRIVATE) },
            // Same frozen clock as the coordinator and the ViewModel, so the
            // sign-in stamp login() persists is exactly FIXED_CLOCK.
            clockUtcMillis = { FIXED_CLOCK },
        )
        api = FakeXtreamApi()
        coordinator = RefreshCoordinator(
            database = db,
            settings = settings,
            credentials = credentials,
            api = api,
            io = Dispatchers.Unconfined,
            clockUtcMillis = { FIXED_CLOCK },
        )
    }

    @After
    fun tearDown() {
        // Cancel ViewModel scopes before resetting Main: the stamp ticker and
        // motion beats hop to Dispatchers.Default and would otherwise resume
        // onto Main mid-reset of the next test ("Dispatchers.Main is used
        // concurrently with setting it"). advanceUntilIdle then drains the
        // cancellation continuations those cancellations queued on Main.
        val store = androidx.lifecycle.ViewModelStore()
        createdViewModels.forEachIndexed { index, vm -> store.put("$index", vm) }
        store.clear()
        mainDispatcher.scheduler.advanceUntilIdle()

        // …and JOIN each scope. Cancelling is not enough: `motionDelayMs` runs
        // its beat on Dispatchers.Default, so a coroutine cancelled mid-beat
        // resumes its continuation *on Main* — and if that resume lands while
        // the NEXT test's setUp (or this test's resetMain) is installing a new
        // Main dispatcher, kotlinx's guard throws
        // "Dispatchers.Main is used concurrently with setting it". Joining
        // before resetMain makes the window impossible.
        runBlocking {
            createdViewModels.forEach { vm ->
                vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
        mainDispatcher.scheduler.advanceUntilIdle()

        drainRoomExecutors()
        closeDatabaseEventually()
        Dispatchers.resetMain()
    }

    /**
     * Barrier on Room's own executors: both are single-threaded, so a task
     * submitted here runs *after* every query the cancelled coroutines had
     * already queued — which is exactly the set that must finish before
     * [HappyTvDatabase.close] is safe.
     *
     * Cancellation alone is not a barrier: Room dispatches a query to the
     * query executor, and cancelling the collecting coroutine does not
     * interrupt a statement already running on that thread.
     */
    private fun drainRoomExecutors() {
        listOf(db.queryExecutor, db.transactionExecutor).forEach { executor ->
            runCatching {
                val drained = java.util.concurrent.CountDownLatch(1)
                executor.execute { drained.countDown() }
                // Bounded: a wedged executor must not hang the build. The close
                // below is bounded too, so the worst case is a logged warning.
                if (!drained.await(DB_CLOSE_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    android.util.Log.w(
                        "DashboardViewModelTest",
                        "Room executor did not drain within ${DB_CLOSE_GRACE_MS}ms",
                    )
                }
            }
        }
    }

    /**
     * Close [db] without deadlocking Robolectric's main thread.
     *
     * `RoomDatabase.close()` is synchronous and takes `androidx.sqlite`'s
     * `ProcessLock`; a query still in flight holds the OpenHelper monitor and
     * waits on the invalidation read lock that close() already took. The JVM's
     * deadlock detector names it ("Found 1 deadlock.") and the whole run then
     * hangs forever — which is how this surfaced as an unrelated-looking flake
     * in whichever test happened to be running when the two collided.
     *
     * With the executor barrier above this normally returns instantly. The
     * bounded wait is the backstop: abandoning the close leaks an in-memory
     * database that dies with the test JVM, while hanging wedges every
     * subsequent run. Leaking is strictly the cheaper failure.
     */
    private fun closeDatabaseEventually() {
        if (closedDatabase) return
        closedDatabase = true
        val closeDone = java.util.concurrent.CountDownLatch(1)
        val closer = Thread({
            runCatching { db.close() }
            closeDone.countDown()
        }, "dash-test-db-close")
        closer.isDaemon = true
        closer.start()
        if (!closeDone.await(DB_CLOSE_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            android.util.Log.w(
                "DashboardViewModelTest",
                "db.close() did not finish within ${DB_CLOSE_GRACE_MS}ms; abandoning the " +
                    "close thread (the in-memory DB dies with the JVM anyway)",
            )
        }
    }

    /** Dashboard over the real coordinator, frozen clock (real-time motion). */
    private fun realDashboard() = track(
        DashboardViewModel(
            coordinator = coordinator,
            settings = settings,
            database = db,
            clockUtcMillis = { FIXED_CLOCK },
            // The test Main dispatcher turns delay() into virtual time, which
            // would park the refresh motion forever; run the beats on Default.
            motionDelayMs = { ms -> withContext(Dispatchers.Default) { delay(ms) } },
        ),
    )

    /** Dashboard over a recording fake with hand-scripted states + motion gates. */
    private fun fakeDashboard(
        gateway: RecordingGateway,
        motion: MotionGates? = null,
        startupDelayMs: Long? = null,
    ) = track(
        DashboardViewModel(
            coordinator = gateway,
            settings = settings,
            database = db,
            clockUtcMillis = { FIXED_CLOCK },
            motionDelayMs = motion?.gate
                ?: { ms -> withContext(Dispatchers.Default) { delay(ms) } },
            startupRefreshDelayMs = startupDelayMs,
        ),
    )

    private fun track(vm: DashboardViewModel): DashboardViewModel {
        createdViewModels += vm
        return vm
    }

    /** The panel accepts — `RefreshCoordinator.login` persists + publishes the session. */
    private suspend fun login() {
        val result = coordinator.login("panel.example", 8080, false, "u", "p")
        assertTrue(result is LoginResult.Success)
    }

    /* ------------------------------------------------------------------ */
    /*  Signed out / first load — the welcome strip and the placeholders  */
    /* ------------------------------------------------------------------ */

    /* ------------------------------------------------------------------ */
    /*  Issue #30: unresolved session on a cold start                      */
    /* ------------------------------------------------------------------ */

    @Test
    fun initialState_isNeutral_noSignedOutAffordances() {
        val state = initialUiState()

        assertFalse(state.sessionResolved)
        assertFalse(state.isSignedIn)
        assertEquals(3, state.tiles.size)
        state.tiles.forEach { tile ->
            // First-load look: placeholder bars, never a sign-in prompt or "0".
            assertEquals(TileCounts.Placeholders, tile.counts)
            assertEquals(TileStatus.Hidden, tile.status)
        }
    }

    @Test
    fun unresolvedSession_mapsToNeutralTiles_thenResolvesSignedIn() = runBlocking {
        val gateway = RecordingGateway().apply { lastSignedInAt = FIXED_CLOCK }
        gateway.resolveLater()
        val dashboard = fakeDashboard(gateway)

        val resolving = waitFor(dashboard) { it.tiles.size == 3 }
        assertFalse(resolving.sessionResolved)
        resolving.tiles.forEach { assertEquals(TileCounts.Placeholders, it.counts) }

        gateway.signedIn = true
        val resolved = waitFor(dashboard) { it.sessionResolved }
        assertTrue(resolved.isSignedIn)
        assertEquals(FIXED_CLOCK, resolved.lastSignedInAtUtcMillis)
    }

    @Test
    fun resolvedSignedOut_stillShowsSignedOutDashboard() = runBlocking {
        val gateway = RecordingGateway()
        gateway.resolveLater()
        val dashboard = fakeDashboard(gateway)
        waitFor(dashboard) { it.tiles.size == 3 }

        gateway.signedIn = false // resolves as signed out
        val state = waitFor(dashboard) { it.sessionResolved }
        assertFalse(state.isSignedIn)
        state.tiles.forEach { assertTrue(it.counts is TileCounts.SignedOut) }
    }

    @Test
    fun signedOut_tilesShowSignInPrompts_notZeroChannels() = runBlocking {
        val state = waitFor(realDashboard()) { it.sessionResolved }

        assertEquals(3, state.tiles.size)
        assertFalse(state.isSignedIn)
        state.tiles.forEach { tile ->
            assertEquals(TileStatus.Hidden, tile.status)
            assertTrue(tile.counts is TileCounts.SignedOut)
            assertFalse(tile.isFirstLoad)
            // The welcome strip's invariant: no pills, no focusable bodies.
            assertNull(tile.errorMessage)
        }
        // The three prompts name their unit: channels / movies / shows.
        assertEquals(
            "Sign in to load channels",
            (state.tiles.first { it.type == StreamType.LIVE }.counts as TileCounts.SignedOut).prompt,
        )
        assertEquals(
            "Sign in to load movies",
            (state.tiles.first { it.type == StreamType.VOD }.counts as TileCounts.SignedOut).prompt,
        )
        assertEquals(
            "Sign in to load shows",
            (state.tiles.first { it.type == StreamType.SERIES }.counts as TileCounts.SignedOut).prompt,
        )
        // Titles read Live TV / Movies / Shows (not "Series").
        assertEquals(listOf("Live TV", "Movies", "Shows"), state.tiles.map { it.title })
    }

    @Test
    fun firstLoad_signedInIdle_showsPlaceholderBars_notZeroChannels() = runBlocking {
        login() // signed in, but no refresh yet — first load, idle
        val dashboard = realDashboard()

        val state = waitFor(dashboard) { it.isSignedIn }
        state.tiles.forEach { tile ->
            assertTrue(tile.isFirstLoad)
            assertTrue(tile.counts is TileCounts.Placeholders)
            // Idle with no data: the stamp says so; the bar stays hidden.
            assertTrue(tile.status is TileStatus.Stamp)
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Integration over the real coordinator                             */
    /* ------------------------------------------------------------------ */

    @Test
    fun refreshAll_landsCountsAndFreshStampTogether() = runBlocking {
        login()
        val dashboard = realDashboard()

        coordinator.refreshAll()

        val state = waitFor(dashboard) { s ->
            s.isSignedIn && s.allFreshSettled()
        }
        val live = state.tiles.first { it.type == StreamType.LIVE }
        val vod = state.tiles.first { it.type == StreamType.VOD }
        val series = state.tiles.first { it.type == StreamType.SERIES }

        // Counts landed with the fresh stamp — no intermediate "Done with the
        // old counts" state was ever published (the onEach ordering fix).
        assertEquals(TileCounts.Lines("3 channels", "1 category"), live.counts)
        assertEquals(TileCounts.Lines("2 movies", "1 category"), vod.counts)
        assertEquals(TileCounts.Lines("1 show", "1 category"), series.counts)
        assertEquals("Updated just now", (live.status as TileStatus.Stamp).text)
        assertTrue(live.showStaleBadge.not())
    }

    @Test
    fun perTileRefresh_leavesOtherTilesUntouched() = runBlocking {
        login()
        val dashboard = realDashboard()
        coordinator.refreshAll()
        // Wait for the whole first refresh to settle (a bare Stamp matches
        // the initial "Not yet refreshed" stamp too — require FRESH), and for
        // the motion run to finish, or "left its stamp" below would catch the
        // other two tiles mid-Completing.
        waitFor(dashboard) { it.allFreshSettled() }

        // Wipe the live catalog so only live can restore it.
        db.liveStreamDao().deleteAll()
        api.liveStreamsFailure = null

        dashboard.refreshTile(StreamType.LIVE).join()

        val after = waitFor(dashboard) { s ->
            // Counts alone settle before the motion coroutine clears, so also
            // require the animation to be finished — otherwise the
            // "never left its stamp" check below reads a mid-Completing tile
            // from the *live* refresh that is still running.
            s.tiles.first { it.type == StreamType.LIVE }.channels == 3 && s.settled()
        }
        assertEquals(3, after.tiles.first { it.type == StreamType.LIVE }.channels)
        assertEquals(2, after.tiles.first { it.type == StreamType.VOD }.channels)
        assertEquals(1, after.tiles.first { it.type == StreamType.SERIES }.channels)
        // The other two never left their stamp — no bar flicker on them.
        after.tiles.filter { it.type != StreamType.LIVE }.forEach { tile ->
            assertTrue(
                "${tile.title} left its stamp: status=${tile.status} " +
                    "refreshState=${tile.refreshState} ch=${tile.channels}",
                tile.status is TileStatus.Stamp,
            )
        }
    }

    @Test
    fun allTilesErrored_showsConnectionBanner() = runBlocking {
        login()
        val dashboard = realDashboard()
        api.liveStreamsFailure = java.io.IOException("panel flaked")
        api.vodStreamsFailure = java.io.IOException("panel flaked")
        api.seriesStreamsFailure = java.io.IOException("panel flaked")

        coordinator.refreshAll()

        val state = waitFor(dashboard) { s ->
            s.isSignedIn && s.tiles.all { it.refreshState is RefreshState.Error }
        }
        assertTrue(state.showConnectionBanner)
        assertTrue(state.allTilesErrored)
        assertTrue(state.connectionMessage.startsWith("Nothing refreshed."))
        state.tiles.forEach { tile ->
            assertTrue(tile.status is TileStatus.Failed)
            // #195: a first sync that failed never completed, so the categories that
            // persisted are not a catalog; the Error state owns the tile and the
            // numbers stay hidden.
            assertTrue(tile.counts is TileCounts.Placeholders)
        }
    }

    @Test
    fun partialFailure_onlyShowsBannerWhenAllThreeFail() = runBlocking {
        login()
        val dashboard = realDashboard()
        api.liveStreamsFailure = java.io.IOException("panel flaked")

        coordinator.refreshAll()

        val state = waitFor(dashboard) { s ->
            s.isSignedIn &&
                s.tiles.firstOrNull { it.type == StreamType.LIVE }?.refreshState is RefreshState.Error &&
                s.tiles.firstOrNull { it.type == StreamType.VOD }?.status is TileStatus.Stamp
        }
        assertFalse(state.showConnectionBanner)
        assertFalse(state.allTilesErrored)
        // The failed tile keeps its stamp-free error; the others went fresh.
        assertTrue(state.tiles.first { it.type == StreamType.VOD }.status is TileStatus.Stamp)
    }

    @Test
    fun returnFromChild_afterPartialSigninLoad_firesNoRefreshAll() = runBlocking {
        // F-41 end-to-end: the series CATEGORIES half flakes at sign-in while
        // its streams half lands (catalog rows persisted, tile reports Error).
        // Pre-fix, that type kept lastRefreshedAt=0 forever and every BACK
        // from Live TV fired a full refreshAll(). Now the landed rows stamp
        // the type, and the return check sees fresh data on all three tiles.
        login()
        val dashboard = realDashboard()
        api.seriesCategoriesFailure = java.io.IOException("panel flaked")

        coordinator.refreshAll()

        val loaded = waitFor(dashboard) { s ->
            s.isSignedIn &&
                s.tiles.all { it.refreshState is RefreshState.Done || it.refreshState is RefreshState.Error } &&
                s.tiles.first { it.type == StreamType.SERIES }.refreshState is RefreshState.Error
        }
        // Rows landed, so the partial load was stamped (the coordinator fix)…
        val series = loaded.tiles.first { it.type == StreamType.SERIES }
        assertTrue(series.lastRefreshedAtUtcMillis > 0L)
        // …and the other two fully succeeded and are stamped too.
        assertTrue(loaded.tiles.all { it.lastRefreshedAtUtcMillis > 0L })

        // The exact staleness check onReturnedFromChild runs on return from
        // Live TV: no tile qualifies → no silent refreshAll fires (F-41).
        val anyStale = loaded.tiles.any { t ->
            staleForReturnRefresh(t.lastRefreshedAtUtcMillis, t.refreshState, FIXED_CLOCK)
        }
        assertFalse("partial-load tiles must not read as stale", anyStale)
    }

    @Test
    fun tileCarriesErrorMessageFromCoordinator() = runBlocking {
        login()
        val dashboard = realDashboard()
        api.liveStreamsFailure = java.io.IOException("panel flaked")

        coordinator.refreshLiveOnly()

        val state = waitFor(dashboard) { s ->
            s.tiles.firstOrNull { it.type == StreamType.LIVE }?.refreshState is RefreshState.Error
        }
        val live = state.tiles.first { it.type == StreamType.LIVE }
        val error = live.refreshState as RefreshState.Error
        assertNotNull(error.message)
        assertTrue(error.message.contains("panel flaked"))
        assertEquals(error.message, live.errorMessage)
        assertEquals(error.message, (live.status as TileStatus.Failed).message)
    }

    /**
     * F-45 regression: a sign-in that lands *while the dashboard is alive*
     * (LoginActivity in front, MainActivity stopped — the composition is not
     * re-created) must flip the header state immediately. Pre-fix the header
     * read a one-shot provider + `{ 0L }` stub and needed a cold start; now it
     * observes the coordinator's live [com.rysal.happytv.data.network.SessionState].
     */
    @Test
    fun signIn_flipsHeaderStateLive_withoutReprimeOrRestart() = runBlocking {
        val dashboard = realDashboard()
        val before = waitFor(dashboard) { !it.isSignedIn }
        assertEquals(
            "Not signed in",
            lastSignedInLabel(before.lastSignedInAtUtcMillis, FIXED_CLOCK, before.isSignedIn),
        )

        login() // panel accepts mid-session — no primeAuthState() call exists anymore

        val after = waitFor(dashboard) { it.isSignedIn }
        assertTrue(after.isSignedIn)
        assertEquals(FIXED_CLOCK, after.lastSignedInAtUtcMillis)
        // The exact copy the header draws the moment the login screen fades out.
        assertEquals(
            "Signed in just now",
            lastSignedInLabel(after.lastSignedInAtUtcMillis, FIXED_CLOCK, after.isSignedIn),
        )
    }

    /* ------------------------------------------------------------------ */
    /*  F-45 — SessionState → header: the state mapping                  */
    /* ------------------------------------------------------------------ */

    /**
     * The unit test for the state mapping (F-45 acceptance): every shape of
     * [SessionState] renders the header copy it must — the dashboard draws
     * exactly `lastSignedInLabel(stamp, now, signedIn)` from the live flow.
     */
    @Test
    fun sessionState_mapsToHeaderCopy() {
        val now = FIXED_CLOCK
        // Signed out, whatever the stamp says.
        assertEquals("Not signed in", lastSignedInLabel(0L, now, isSignedIn = false))
        assertEquals("Not signed in", lastSignedInLabel(now, now, isSignedIn = false))
        // Signed in — the copy ladder by stamp age.
        assertEquals("Signed in just now", lastSignedInLabel(now, now, isSignedIn = true))
        assertEquals("Last signed in 1 minute ago", lastSignedInLabel(now - 60_000L, now, true))
        assertEquals("Last signed in 12 minutes ago", lastSignedInLabel(now - 12 * 60_000L, now, true))
        assertEquals("Last signed in 59 minutes ago", lastSignedInLabel(now - 59 * 60_000L, now, true))
        assertEquals("Last signed in 1 hour ago", lastSignedInLabel(now - 60 * 60_000L, now, true))
        assertEquals("Last signed in 3 hours ago", lastSignedInLabel(now - 180 * 60_000L, now, true))
        // Defensive: signed in but no stamp to show (never written) — don't
        // claim "just now" over a value that doesn't exist.
        assertEquals("Not signed in", lastSignedInLabel(0L, now, isSignedIn = true))
    }

    /**
     * Cold start with remembered credentials (process death between sign-in
     * and now): the session flow is derived from the persisted store, so the
     * header shows the *age of the real sign-in* — not "Signed in just now",
     * and never "Not signed in" (the F-45/F-34 bug).
     */
    @Test
    fun coldStart_showsAgeOfPersistedSignIn_notSignedOut() = runBlocking {
        login() // stamp = FIXED_CLOCK, persisted to the credential store

        val dashboard = realDashboard() // fresh VM = app start
        val state = waitFor(dashboard) { it.isSignedIn }

        assertEquals(FIXED_CLOCK, state.lastSignedInAtUtcMillis)
        assertEquals(
            "Signed in just now",
            lastSignedInLabel(state.lastSignedInAtUtcMillis, FIXED_CLOCK, state.isSignedIn),
        )
        // The stamp is frozen; the label ages off the clock.
        assertEquals(
            "Last signed in 12 minutes ago",
            lastSignedInLabel(state.lastSignedInAtUtcMillis, FIXED_CLOCK + 12 * 60_000L, true),
        )
        assertEquals(
            "Last signed in 3 hours ago",
            lastSignedInLabel(state.lastSignedInAtUtcMillis, FIXED_CLOCK + 180 * 60_000L, true),
        )
    }

    /** Sign out wipes the credentials — and with them the stamp (F-45). */
    @Test
    fun signOut_clearsStamp_headerSaysNotSignedInAgain() = runBlocking {
        login()
        val dashboard = realDashboard()
        waitFor(dashboard) { it.isSignedIn }

        dashboard.signOut().join()

        val state = waitFor(dashboard) { !it.isSignedIn }
        assertEquals(0L, state.lastSignedInAtUtcMillis)
        assertEquals(
            "Not signed in",
            lastSignedInLabel(state.lastSignedInAtUtcMillis, FIXED_CLOCK, state.isSignedIn),
        )
    }

    /* ------------------------------------------------------------------ */
    /*  Recording fake — the call list + the full state table             */
    /* ------------------------------------------------------------------ */

    @Test
    fun perTileRefresh_recordsExactCallList() = runBlocking {
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)
        waitFor(dashboard) { it.tiles.all { tile -> tile.status is TileStatus.Stamp } }

        dashboard.refreshTile(StreamType.LIVE).join()
        dashboard.refreshTile(StreamType.SERIES).join()
        dashboard.retryAll().join()

        assertEquals(
            listOf("refreshLiveOnly", "refreshSeriesOnly", "refreshAll"),
            gateway.calls.toList(),
        )
    }

    @Test
    fun tileStateMachine_idleLoadingCompleteFresh() = runBlocking {
        val gateway = RecordingGateway().apply { signedIn = true }
        val motion = MotionGates()
        val dashboard = fakeDashboard(gateway, motion)
        settings.markRefreshed(StreamType.LIVE, FIXED_CLOCK - 5 * 60_000L)

        // Idle: cached stamp, no bar.
        val idle = waitFor(dashboard) { s ->
            s.tiles.first { it.type == StreamType.LIVE }.status is TileStatus.Stamp
        }
        assertEquals(
            "Updated 5 minutes ago",
            (idle.tiles.first { it.type == StreamType.LIVE }.status as TileStatus.Stamp).text,
        )

        // Loading → Updating: bar, no stamp, placeholders (never "0").
        gateway.emit(StreamType.LIVE, RefreshState.Loading(step = 1, ofSteps = 3))
        val updating = waitFor(dashboard) {
            it.tiles.first { it.type == StreamType.LIVE }.status is TileStatus.Updating
        }
        updating.tiles.first { it.type == StreamType.LIVE }.assertNeverBoth()

        // Done → Complete: bar filled to 100%, still "Updating…" text.
        settings.markRefreshed(StreamType.LIVE, FIXED_CLOCK)
        gateway.emit(StreamType.LIVE, RefreshState.Done(channels = 3, ms = 12L))
        val completing = waitFor(dashboard) {
            it.tiles.first { it.type == StreamType.LIVE }.status is TileStatus.Completing
        }
        completing.tiles.first { it.type == StreamType.LIVE }.assertNeverBoth()

        // Release the fill beat → Collapsing.
        motion.releaseNext()
        val collapsing = waitFor(dashboard) {
            it.tiles.first { it.type == StreamType.LIVE }.status is TileStatus.Collapsing
        }
        collapsing.tiles.first { it.type == StreamType.LIVE }.assertNeverBoth()

        // Release the collapse beat → Fresh.
        motion.releaseNext()
        val fresh = waitFor(dashboard) { s ->
            s.tiles.first { it.type == StreamType.LIVE }.status.let {
                it is TileStatus.Stamp && it.age == TileStatus.AgeTier.FRESH
            }
        }
        assertEquals(
            "Updated just now",
            (fresh.tiles.first { it.type == StreamType.LIVE }.status as TileStatus.Stamp).text,
        )
        fresh.tiles.first { it.type == StreamType.LIVE }.assertNeverBoth()
    }

    @Test
    fun loadingErrorRetryLoadingFresh() = runBlocking {
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)

        gateway.emit(StreamType.VOD, RefreshState.Loading(step = 1, ofSteps = 3))
        waitFor(dashboard) { it.tiles.first { it.type == StreamType.VOD }.status is TileStatus.Updating }

        gateway.emit(StreamType.VOD, RefreshState.Error("Couldn't reach panel"))
        val failed = waitFor(dashboard) {
            it.tiles.first { it.type == StreamType.VOD }.status is TileStatus.Failed
        }
        val vodFailed = failed.tiles.first { it.type == StreamType.VOD }
        assertEquals("Couldn't reach panel", (vodFailed.status as TileStatus.Failed).message)
        // With only one tile failed there is no connection banner.
        assertFalse(failed.showConnectionBanner)

        // Retry after a failure: OK on the pill → only that tile.
        dashboard.refreshTile(StreamType.VOD).join()
        assertEquals(listOf("refreshVodOnly"), gateway.calls.toList())

        gateway.emit(StreamType.VOD, RefreshState.Loading(step = 2, ofSteps = 3))
        waitFor(dashboard) { it.tiles.first { it.type == StreamType.VOD }.status is TileStatus.Updating }

        settings.markRefreshed(StreamType.VOD, FIXED_CLOCK)
        gateway.emit(StreamType.VOD, RefreshState.Done(channels = 2, ms = 8L))
        val fresh = waitFor(dashboard) { s ->
            s.tiles.first { it.type == StreamType.VOD }.status.let {
                it is TileStatus.Stamp && it.age == TileStatus.AgeTier.FRESH
            }
        }
        assertTrue(fresh.tiles.first { it.type == StreamType.VOD }.status is TileStatus.Stamp)
    }

    @Test
    fun liveTile_showsStamp_whileTheOtherTwoTilesAreStillRefreshing() = runBlocking {
        // F-63 regression, at the layer the TV actually shows. The reported
        // symptom: the Live TV pill kept reading "Updating…" (a11y label
        // "Live TV: Updating.") long after its own refresh had finished,
        // while the other two tiles showed stamps.
        //
        // This is the one state that would produce exactly that: LIVE is
        // Done while VOD and SERIES are still Loading. A tile that only
        // flipped when the whole run settled — or that read the session-wide
        // aggregate instead of its own per-tile state — would sit on
        // "Updating…" here and never recover until the last sibling landed.
        val gateway = RecordingGateway().apply { signedIn = true }
        // Real-time motion beats for three tiles at once add scheduling jitter
        // to this shared class (the known DashboardViewModelTest ordering-flake
        // family). Gate them: this test is about WHICH state the pill lands in,
        // not how long the collapse takes.
        val motion = MotionGates()
        val dashboard = fakeDashboard(gateway, motion)
        // All three stamped: the refresh is a re-run, not a first load, so
        // the pre-existing data is on screen behind the bars.
        StreamType.entries.forEach { settings.markRefreshed(it, FIXED_CLOCK - 20 * 60_000L) }
        waitFor(dashboard) { s -> s.tiles.all { it.status is TileStatus.Stamp } }

        // All three in flight — bars up, no stamps.
        StreamType.entries.forEach { gateway.emit(it, RefreshState.Loading(step = 1, ofSteps = 3)) }
        val inFlight = waitFor(dashboard) { s ->
            s.tiles.all { it.status is TileStatus.Updating }
        }
        assertEquals("Live TV: Updating.", inFlight.tiles.first { it.type == StreamType.LIVE }.pillDescription)

        // LIVE lands first and lands alone.
        settings.markRefreshed(StreamType.LIVE, FIXED_CLOCK)
        gateway.emit(StreamType.LIVE, RefreshState.Done(channels = 3, ms = 12L))

        // Drain Live's two motion beats (fill+hold, then collapse) so it reaches
        // the stamp. The siblings' beats stay parked — they are still Loading.
        repeat(2) { motion.releaseNextWhenParked() }
        val settled = waitFor(dashboard) { s ->
            s.tiles.first { tile -> tile.type == StreamType.LIVE }.status.let {
                it is TileStatus.Stamp && it.age == TileStatus.AgeTier.FRESH
            }
        }
        val live = settled.tiles.first { it.type == StreamType.LIVE }
        // The acceptance criterion: a real "Updated …" stamp, and an a11y
        // label that no longer says Updating — while its siblings still run.
        assertEquals("Updated just now", (live.status as TileStatus.Stamp).text)
        assertEquals("Live TV: Updated just now. Refresh", live.pillDescription)
        assertTrue(
            "pill label must not mention Updating, was '${live.pillDescription}'",
            !live.pillDescription.contains("Updating"),
        )
        assertEquals(RefreshState.Done(channels = 3, ms = 12L), live.refreshState)
        live.assertNeverBoth()
        // …and the siblings are genuinely still mid-refresh, so this is the
        // partial-completion case and not a fully-settled dashboard.
        assertTrue(settled.tiles.first { it.type == StreamType.VOD }.status is TileStatus.Updating)
        assertTrue(settled.tiles.first { it.type == StreamType.SERIES }.status is TileStatus.Updating)

        // The siblings finishing later must not disturb the Live stamp.
        settings.markRefreshed(StreamType.VOD, FIXED_CLOCK)
        settings.markRefreshed(StreamType.SERIES, FIXED_CLOCK)
        gateway.emit(StreamType.VOD, RefreshState.Done(channels = 2, ms = 8L))
        gateway.emit(StreamType.SERIES, RefreshState.Done(channels = 1, ms = 9L))
        // Four beats: two per sibling.
        repeat(4) { motion.releaseNextWhenParked() }
        val allDone = waitFor(dashboard) { s ->
            s.tiles.all { it.status.let { st -> st is TileStatus.Stamp && st.age == TileStatus.AgeTier.FRESH } }
        }
        assertEquals(
            "Live TV: Updated just now. Refresh",
            allDone.tiles.first { it.type == StreamType.LIVE }.pillDescription,
        )
    }

    @Test
    fun allThreeFailed_bannerMessageAndRetryAll() = runBlocking {
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)
        settings.markRefreshed(StreamType.LIVE, FIXED_CLOCK - 3 * 3_600_000L)
        settings.markRefreshed(StreamType.VOD, FIXED_CLOCK - 3 * 3_600_000L)
        settings.markRefreshed(StreamType.SERIES, FIXED_CLOCK - 3 * 3_600_000L)

        StreamType.entries.forEach { gateway.emit(it, RefreshState.Error("boom")) }

        val state = waitFor(dashboard) { it.showConnectionBanner }
        assertEquals(
            "Nothing refreshed. Your last successful update was 3 hours ago.",
            state.connectionMessage,
        )
        assertEquals("provider.example:8080", state.serverLabel)

        dashboard.retryAll().join()
        assertEquals(listOf("refreshAll"), gateway.calls.toList())
    }

    @Test
    fun staleBadge_requiresRefreshOnStart() = runBlocking {
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)
        settings.setRefreshOnStart(true)
        settings.markRefreshed(StreamType.SERIES, FIXED_CLOCK - 3 * 3_600_000L)

        val stale = waitFor(dashboard) { s ->
            s.tiles.first { it.type == StreamType.SERIES }.status.let {
                it is TileStatus.Stamp && it.age == TileStatus.AgeTier.STALE
            }
        }
        val series = stale.tiles.first { it.type == StreamType.SERIES }
        assertEquals("Updated 3 hours ago", (series.status as TileStatus.Stamp).text)
        assertTrue(series.showStaleBadge)

        // design spec open question 6: with "Refresh on start" off, no badge —
        // but the stamp still reads stale.
        settings.setRefreshOnStart(false)
        val noBadge = waitFor(dashboard) {
            it.tiles.first { it.type == StreamType.SERIES }.showStaleBadge.not()
        }
        assertTrue(
            noBadge.tiles.first { it.type == StreamType.SERIES }.status is TileStatus.Stamp,
        )
    }

    @Test
    fun silentRefresh_keepsStampsUp() = runBlocking {
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)
        val halfHourOld = FIXED_CLOCK - 31 * 60_000L
        settings.markRefreshed(StreamType.LIVE, halfHourOld)
        settings.markRefreshed(StreamType.VOD, halfHourOld)
        settings.markRefreshed(StreamType.SERIES, halfHourOld)
        waitFor(dashboard) { s ->
            s.tiles.all { it.lastRefreshedAtUtcMillis == halfHourOld }
        }

        // Park the fake's refreshAll so the silent window stays open while we
        // script the Loading states (production refreshAll blocks until the
        // tiles settle, which is exactly what the flag spans).
        val refreshGate = CompletableDeferred<Unit>()
        gateway.refreshGate = refreshGate
        val silentRun = dashboard.onReturnedFromChild()
        waitFor(dashboard) { gateway.calls.contains("refreshAll") }

        // Mid-refresh: stamps stay up — no bars on any tile.
        StreamType.entries.forEach { gateway.emit(it, RefreshState.Loading(step = 2, ofSteps = 3)) }
        kotlinx.coroutines.delay(100L)
        dashboard.ui.value.tiles.forEach { tile ->
            assertTrue(
                "silent refresh must not raise a bar on ${tile.title}",
                tile.status is TileStatus.Stamp,
            )
        }

        // Done: stamps swap in place.
        StreamType.entries.forEach {
            settings.markRefreshed(it, FIXED_CLOCK)
            gateway.emit(it, RefreshState.Done(channels = 1, ms = 5L))
        }
        val done = waitFor(dashboard) { s ->
            s.tiles.all { t ->
                t.status.let { it is TileStatus.Stamp && it.age == TileStatus.AgeTier.FRESH }
            }
        }
        assertEquals("Updated just now", (done.tiles.first().status as TileStatus.Stamp).text)

        refreshGate.complete(Unit)
        silentRun.join()
    }

    @Test
    fun returnFromChild_afterFreshSignIn_firesNoRefreshAll() = runBlocking {
        // F-41 regression: every tile finished loading but none were ever
        // marked (markRefreshed only runs on full success — a type that
        // errored/partialled at sign-in keeps 0). Loaded + unmarked must NOT
        // read as "infinitely stale".
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)
        StreamType.entries.forEach {
            gateway.emit(it, RefreshState.Done(channels = 3, ms = 12L))
        }
        val loaded = waitFor(dashboard) { s ->
            s.tiles.all { it.refreshState is RefreshState.Done } &&
                s.tiles.all { it.lastRefreshedAtUtcMillis == 0L }
        }
        assertTrue(loaded.tiles.all { it.lastRefreshedAtUtcMillis == 0L })

        dashboard.onReturnedFromChild().join()
        assertEquals(emptyList<String>(), gateway.calls)
    }

    @Test
    fun returnFromChild_neverLoadedTile_stillRefreshes() = runBlocking {
        // The other side of the same rule: 0 + not-Done means there is no
        // data to serve (fresh install, or the type still failed), so the
        // silent refresh must still fire for it.
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)
        gateway.emit(StreamType.LIVE, RefreshState.Done(channels = 3, ms = 12L))
        gateway.emit(StreamType.VOD, RefreshState.Done(channels = 2, ms = 9L))
        gateway.emit(StreamType.SERIES, RefreshState.Error("panel flaked"))
        waitFor(dashboard) { s ->
            s.tiles.first { it.type == StreamType.SERIES }.refreshState is RefreshState.Error
        }

        dashboard.onReturnedFromChild().join()
        assertEquals(listOf("refreshAll"), gateway.calls.toList())
    }

    @Test
    fun longPressMenu_aboutThisData() = runBlocking {
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)
        settings.markRefreshed(StreamType.VOD, FIXED_CLOCK - 12 * 60_000L)
        waitFor(dashboard) { it.tiles.first { t -> t.type == StreamType.VOD }.lastRefreshedAtUtcMillis > 0L }

        dashboard.openLongPressMenu(StreamType.VOD)
        val menu = waitFor(dashboard) { it.menuTile == StreamType.VOD }
        assertEquals(StreamType.VOD, menu.menuTile)
        assertEquals("Movies", menu.menuTitle)
        assertFalse(menu.menuShowingAbout)
        assertTrue(
            "About copy was: ${menu.aboutLastUpdated}",
            menu.aboutLastUpdated.endsWith("(12 minutes ago)"),
        )

        dashboard.menuShowAbout()
        waitFor(dashboard) { it.menuShowingAbout }

        dashboard.closeLongPressMenu()
        val closed = waitFor(dashboard) { it.menuTile == null }
        assertNull(closed.menuTile)
    }

    @Test
    fun signOut_wipesTilesBackToHidden() = runBlocking {
        val gateway = RecordingGateway().apply {
            lastSignedInAt = FIXED_CLOCK
            signedIn = true
        }
        val dashboard = fakeDashboard(gateway)
        waitFor(dashboard) { it.isSignedIn }

        dashboard.signOut().join()
        val state = waitFor(dashboard) { !it.isSignedIn }
        assertFalse(state.isSignedIn)
        // The stamp goes with the credentials: the header falls back to
        // "Not signed in" instead of a stale "Last signed in …" (F-45).
        assertEquals(0L, state.lastSignedInAtUtcMillis)
        state.tiles.forEach { tile ->
            assertEquals(TileStatus.Hidden, tile.status)
            assertTrue(tile.counts is TileCounts.SignedOut)
        }
        assertEquals(listOf("signOut"), gateway.calls.toList())
    }

    /* ------------------------------------------------------------------ */
    /*  Pure helpers                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    fun stamps_ageByTheMinute() {
        val now = FIXED_CLOCK
        assertEquals("Updated just now", stampFor(now - 59_000L, now).text)
        assertEquals("Updated 1 minute ago", stampFor(now - 60_000L, now).text)
        assertEquals("Updated 12 minutes ago", stampFor(now - 12 * 60_000L, now).text)
        assertEquals("Updated 1 hour ago", stampFor(now - 61 * 60_000L, now).text)
        assertEquals("Updated 3 hours ago", stampFor(now - 3 * 3_600_000L, now).text)
        assertEquals(TileStatus.AgeTier.FRESH, stampFor(now - 30_000L, now).age)
        assertEquals(TileStatus.AgeTier.RECENT, stampFor(now - 12 * 60_000L, now).age)
        assertEquals(TileStatus.AgeTier.STALE, stampFor(now - 61 * 60_000L, now).age)
    }

    @Test
    fun staleForReturnRefresh_isStaleOnlyWhenDataIsActuallyOld() {
        val now = FIXED_CLOCK
        val done = RefreshState.Done(channels = 3, ms = 12L)

        // Stamped tiles: stale only past the 30-minute line (boundary included),
        // fresh just under it — regardless of what the state machine says.
        assertFalse(staleForReturnRefresh(now - 5 * 60_000L, done, now))
        assertFalse(staleForReturnRefresh(now - 29 * 60_000L, done, now))
        assertTrue(staleForReturnRefresh(now - 30 * 60_000L, done, now))
        assertTrue(staleForReturnRefresh(now - 3 * 3_600_000L, done, now))

        // F-41: unstamped (0) but DONE — the load finished and was never
        // marked — must count as fresh, or every BACK fires refreshAll.
        assertFalse(staleForReturnRefresh(0L, done, now))
        assertFalse(staleForReturnRefresh(-1L, done, now))

        // Unstamped and not done: no data to serve, still stale.
        assertTrue(staleForReturnRefresh(0L, RefreshState.Idle, now))
        assertTrue(staleForReturnRefresh(0L, RefreshState.Loading(step = 1, ofSteps = 3), now))
        assertTrue(staleForReturnRefresh(0L, RefreshState.Error("panel flaked"), now))
    }

    @Test
    fun longFormTimes_carryAmPm() {
        // 12-hour clock with AM/PM — the one place it appears.
        val afternoon = FIXED_CLOCK // frozen at 2023-11-14 22:13:20 UTC
        assertTrue(longFormTime(afternoon, afternoon).contains(":"))
        val today = longFormTime(afternoon, afternoon)
        assertTrue(today.startsWith("Today at "))
        assertTrue(today.endsWith("AM") || today.endsWith("PM"))
    }

    /* ------------------------------------------------------------------ */
    /*  Harness                                                           */
    /* ------------------------------------------------------------------ */

    /** A tile must never show the bar and the stamp at the same time. */
    private fun DashboardTile.assertNeverBoth() {
        val bar = status is TileStatus.Updating ||
            status is TileStatus.Completing ||
            status is TileStatus.Collapsing
        val stamp = status is TileStatus.Stamp
        assertFalse("bar XOR stamp violated for $title ($status)", bar && stamp)
        // Counts: lines XOR placeholders (or the signed-out single line).
        val lines = counts is TileCounts.Lines
        val placeholders = counts is TileCounts.Placeholders
        assertFalse(lines && placeholders)
    }

    /**
     * Polls the ViewModel's StateFlow until [predicate] holds. The initial
     * state always carries three tiles (seeded), so predicates can assume it.
     *
     * **Predicates must include [settled]**, or they match the transient
     * mid-motion state. `RefreshCoordinator` publishes `Done` and stamps the
     * tile the instant the rows land, but `DashboardViewModel` keeps the tile
     * in `Completing` → `Collapsing` for another 260 ms while its motion
     * coroutine runs, and the combine keeps `Completing` as the status while
     * that map holds an entry. A predicate that only asks "is it `Done`?" or
     * "is the stamp fresh?" therefore returns *during the animation*, and
     * whatever the test asserts next is read off a half-finished frame.
     *
     * That was the flake: `refreshAll_landsCountsAndFreshStampTogether` and
     * `perTileRefresh_leavesOtherTilesUntouched` each passed on most runs and
     * failed whenever the motion coroutine had not yet cleared — the assertion
     * sampled `Completing`, or caught the counts a beat before they landed.
     */
    private suspend fun waitFor(
        dashboard: DashboardViewModel,
        timeoutMs: Long = 5_000L,
        predicate: (DashboardUiState) -> Boolean,
    ): DashboardUiState {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val state = dashboard.ui.value
            if (state.tiles.size == 3 && predicate(state)) return state
            kotlinx.coroutines.delay(25L)
        }
        val last = dashboard.ui.value
        val dump = last.tiles.joinToString("; ") { "${it.title}=${it.status}/${it.counts}" }
        throw AssertionError("waitFor timed out. Last state: signedIn=${last.isSignedIn} $dump")
    }

    /**
     * True when no tile is mid-animation — every refresh has finished its
     * Completing → Collapsing run and dropped back to a plain stamp.
     *
     * This is the *settled* condition the assertions above actually mean, and
     * the one the motion design makes last, so it is the only safe thing to
     * gate a status/counts assertion on.
     */
    private fun DashboardUiState.settled(): Boolean = tiles.all { tile ->
        tile.status !is TileStatus.Updating &&
            tile.status !is TileStatus.Completing &&
            tile.status !is TileStatus.Collapsing
    }

    /**
     * The settled end state of a successful `refreshAll`: every tile `Done`,
     * every stamp fresh, and nothing still animating.
     *
     * All three are needed, and each one alone is satisfiable too early:
     *
     *  - **fresh stamp alone** — `RefreshCoordinator.refreshTile` calls
     *    `settings.markRefreshed(type)` *before* `setState(type, Done)`. So
     *    there is a real frame where the tile already reads "Updated just now"
     *    while `catalogCounts` still holds the pre-refresh row counts and
     *    `refreshState` is still `Idle`. `waitFor` polling that frame returned
     *    `Lines("0 channels", …)` and the counts assertion failed.
     *  - **`Done` alone** — see [waitFor]'s note: `progress` and `catalogCounts`
     *    reach the combine on independent paths, so `Done` can be published one
     *    emission before the re-queried counts do.
     *  - **settled alone** — the stamp-and-counts frame above is not animating.
     */
    private fun DashboardUiState.allFreshSettled(): Boolean = settled() && tiles.all { tile ->
        tile.refreshState is RefreshState.Done &&
            tile.status.let { it is TileStatus.Stamp && it.age == TileStatus.AgeTier.FRESH }
    }

    /** Fake panel + call recorder for the dashboard's gateway seam. */
    private class RecordingGateway : RefreshGateway {
        val calls = mutableListOf<String>()
        private val _progress = MutableStateFlow(RefreshProgress())
        override val progress: StateFlow<RefreshProgress> = _progress.asStateFlow()

        private val _session = MutableStateFlow(SessionState())
        override val sessionState: StateFlow<SessionState> = _session.asStateFlow()

        /** Puts the session back to "cold start, read still in flight". */
        fun resolveLater() {
            _session.value = SessionState(resolved = false)
        }

        /** Scripts the session the way login()/signOut() publish it (F-45). */
        var signedIn: Boolean
            get() = _session.value.signedIn
            set(value) {
                _session.value = SessionState(
                    signedIn = value,
                    lastSignedInAtUtcMillis = if (value) lastSignedInAt else 0L,
                )
            }

        /** The fake's sign-in stamp — published alongside [signedIn] = true. */
        var lastSignedInAt: Long = 0L

        /** When set, [refreshAll] parks on it (production refreshAll blocks
         *  until the tiles settle — the silent window spans exactly that). */
        var refreshGate: CompletableDeferred<Unit>? = null

        override suspend fun refreshAll() {
            calls += "refreshAll"
            refreshGate?.await()
        }

        override suspend fun refreshLiveOnly() {
            calls += "refreshLiveOnly"
        }

        override suspend fun refreshVodOnly() {
            calls += "refreshVodOnly"
        }

        override suspend fun refreshSeriesOnly() {
            calls += "refreshSeriesOnly"
        }

        override suspend fun isSignedIn(): Boolean = signedIn

        override suspend fun signOut() {
            calls += "signOut"
            signedIn = false
        }

        override suspend fun serverLabel(): String = "provider.example:8080"

        fun emit(type: StreamType, state: RefreshState) {
            _progress.update { it.with(type, state) }
        }
    }

    /**
     * Hands the refresh motion its beats one at a time: each motion delay
     * parks on a fresh gate the test releases with [releaseNext], so
     * Updating / Complete / Collapsing are individually observable.
     */
    private class MotionGates {
        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        val gate: suspend (Long) -> Unit = {
            val gate = CompletableDeferred<Unit>()
            gates += gate
            gate.await()
        }

        fun releaseNext() {
            check(gates.isNotEmpty()) { "no motion beat parked" }
            gates.removeAt(0).complete(Unit)
        }

        /**
         * Releases the next [count] beats, waiting for each one to park first.
         *
         * Beats park asynchronously (the Done state only starts the motion
         * coroutine once the ViewModel's collector sees it), so releasing N
         * beats back-to-back races against their arrival — `releaseNext()`
         * would throw "no motion beat parked" on a fast machine. Poll here
         * instead: deterministic and still fully synchronous per beat.
         */
        suspend fun releaseNextWhenParked() {
            withTimeout(5_000L) {
                while (gates.isEmpty()) kotlinx.coroutines.delay(5L)
            }
            releaseNext()
        }
    }

    // ---- #163 / #167: dashboard counts --------------------------------------


    @Test
    fun settleCounts_loadingTypeKeepsPreviousNumbers() {
        val previous = CatalogCounts(liveChannels = 21_000, liveCategories = 108, vodChannels = 37_000, vodCategories = 60)
        val partial = CatalogCounts(liveChannels = 19_000, liveCategories = 0, vodChannels = 22_000, vodCategories = 0)
        val progress = RefreshProgress(live = RefreshState.Loading(step = 1, ofSteps = 3), vod = RefreshState.Done(channels = 1, ms = 1L))

        val settled = settleCounts(previous, partial, progress)

        // Live is mid-refresh: the partial read is ignored. Movies is done: the new read lands.
        assertEquals(21_000, settled.liveChannels)
        assertEquals(108, settled.liveCategories)
        assertEquals(22_000, settled.vodChannels)
    }

    @Test
    fun tileCounts_neverRefreshedAndLoading_isPlaceholdersEvenWithPartialRows() {
        val loading = tileCounts(true, true, RefreshState.Loading(step = 1, ofSteps = 3), 19_000, 0, "channels", lastRefreshedAtUtcMillis = 0L)
        assertEquals(TileCounts.Placeholders, loading)
        val idle = tileCounts(true, true, RefreshState.Idle, 19_000, 0, "channels", lastRefreshedAtUtcMillis = 0L)
        assertEquals(TileCounts.Placeholders, idle)
        // A tile with a good stamp keeps showing its numbers while it loads.
        val stamped = tileCounts(true, true, RefreshState.Loading(step = 1, ofSteps = 3), 21_000, 108, "channels", lastRefreshedAtUtcMillis = 5L)
        assertEquals(TileCounts.Lines("21,000 channels", "108 categories"), stamped)
    }

    @Test
    fun liveTile_countsMatchLiveTvHeader_hiddenChannelsAndCategoriesLeftOut() = runBlocking {
        login()
        val live = com.rysal.happytv.data.entity.StreamType.LIVE
        db.categoryDao().insertAll(
            listOf(
                com.rysal.happytv.data.entity.Category("c1", live, "News"),
                com.rysal.happytv.data.entity.Category("c2", live, "Sports"),
                com.rysal.happytv.data.entity.Category("c3", live, "Adult", isHidden = true),
            ),
        )
        db.liveStreamDao().insertAll(
            listOf(
                com.rysal.happytv.data.entity.LiveStream("1", "c1", name = "A"),
                com.rysal.happytv.data.entity.LiveStream("2", "c1", name = "B"),
                com.rysal.happytv.data.entity.LiveStream("3", "c2", name = "C"),
                com.rysal.happytv.data.entity.LiveStream("4", "c3", name = "D"),
            ),
        )
        db.hiddenChannelDao().insertAll(listOf(com.rysal.happytv.data.entity.HiddenChannel("2", live)))
        settings.markRefreshed(StreamType.LIVE, FIXED_CLOCK)
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)

        val state = waitFor(dashboard) { s ->
            s.tiles.first { it.type == StreamType.LIVE }.counts is TileCounts.Lines
        }

        // Channel 2 is hidden and category c3 is hidden: 2 channels, 2 categories.
        assertEquals(TileCounts.Lines("2 channels", "2 categories"), state.tiles.first { it.type == StreamType.LIVE }.counts)
    }

    @Test
    fun movieTile_withAnInterruptedSync_hidesItsPartialNumbersAndSaysItNeedsUpdating_195() = runBlocking {
        login()
        val vod = StreamType.VOD
        db.vodStreamDao().insertAll(
            (1..5).map { com.rysal.happytv.data.entity.VodStream("$it", "c1", name = "Movie $it") },
        )
        settings.markRefreshed(vod, FIXED_CLOCK - 60_000L)
        settings.setSyncMark(vod, SyncMark.RUNNING)
        val dashboard = fakeDashboard(RecordingGateway().apply { signedIn = true })

        val state = waitFor(dashboard) { s -> s.tiles.first { it.type == vod }.incomplete }

        val tile = state.tiles.first { it.type == vod }
        assertEquals(TileCounts.Placeholders, tile.counts)
        assertEquals(NEEDS_UPDATING_STAMP, tile.status)
        // Refresh on start is on by default and the stamp is a minute old: no Stale badge on top.
        assertTrue(state.refreshOnStart)
        assertFalse(tile.showStaleBadge)
    }

    // ---- #157 / #158: the start-up refresh -----------------------------------

    @Test
    fun startup_neverRefreshedTiles_refreshOneAtATime_cheapestFirst() = runBlocking {
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)

        dashboard.onEnteredForeground().join()

        assertEquals(listOf("refreshLiveOnly", "refreshSeriesOnly", "refreshVodOnly"), gateway.calls.toList())
    }

    @Test
    fun startup_recentlyRefreshedTiles_areSkipped() = runBlocking {
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)
        settings.markRefreshed(StreamType.LIVE, FIXED_CLOCK - 5 * 60_000L)
        settings.markRefreshed(StreamType.VOD, FIXED_CLOCK - 5 * 60_000L)
        settings.markRefreshed(StreamType.SERIES, FIXED_CLOCK - 5 * 60_000L)

        dashboard.onEnteredForeground().join()

        assertEquals(emptyList<String>(), gateway.calls.toList())
    }

    @Test
    fun startup_onlyTheStaleTileRefreshes() = runBlocking {
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)
        settings.markRefreshed(StreamType.LIVE, FIXED_CLOCK - 5 * 60_000L)
        settings.markRefreshed(StreamType.SERIES, FIXED_CLOCK - 5 * 60_000L)
        settings.markRefreshed(StreamType.VOD, FIXED_CLOCK - 2 * 3_600_000L)

        dashboard.onEnteredForeground().join()

        assertEquals(listOf("refreshVodOnly"), gateway.calls.toList())
    }

    @Test
    fun startup_respectsRefreshOnStartOff_andSignedOut() = runBlocking {
        val off = RecordingGateway().apply { signedIn = true }
        settings.setRefreshOnStart(false)
        fakeDashboard(off).onEnteredForeground().join()
        assertEquals(emptyList<String>(), off.calls.toList())

        settings.setRefreshOnStart(true)
        val signedOut = RecordingGateway()
        fakeDashboard(signedOut).onEnteredForeground().join()
        assertEquals(emptyList<String>(), signedOut.calls.toList())
    }

    @Test
    fun startup_waitsBeforeTouchingTheNetwork() = runBlocking {
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway, startupDelayMs = 600L)

        val job = dashboard.onEnteredForeground()
        delay(200L)
        assertEquals("nothing may run during the settle delay", emptyList<String>(), gateway.calls.toList())
        job.join()
        assertEquals(3, gateway.calls.size)
    }

    @Test
    fun startup_skipsTilesTheSignInRefreshIsAlreadyLoading() = runBlocking {
        val gateway = RecordingGateway().apply { signedIn = true }
        val dashboard = fakeDashboard(gateway)
        gateway.emit(StreamType.LIVE, RefreshState.Done(channels = 3, ms = 12L))
        gateway.emit(StreamType.VOD, RefreshState.Loading(step = 1, ofSteps = 3))

        dashboard.onEnteredForeground().join()

        assertEquals(listOf("refreshSeriesOnly"), gateway.calls.toList())
    }

    @Test
    fun interruptedSync_neverShowsPartialNumbersWithAStamp_195() {
        val done = RefreshState.Done(channels = 55_000, ms = 1)
        // The reported case: a good stamp, 55,000 movies and 0 categories.
        assertEquals(
            TileCounts.Placeholders,
            tileCounts(true, true, RefreshState.Idle, 55_000, 0, "movies", lastRefreshedAtUtcMillis = 5L, incomplete = true),
        )
        assertEquals(
            TileCounts.Placeholders,
            tileCounts(true, true, RefreshState.Error("x"), 2_000, 0, "channels", lastRefreshedAtUtcMillis = 5L, incomplete = true),
        )
        assertEquals(
            TileCounts.Lines("55,000 movies", "0 categories"),
            tileCounts(true, true, done, 55_000, 0, "movies", lastRefreshedAtUtcMillis = 5L),
        )
    }

    @Test
    fun syncIncomplete_runningCountsAsCompleteOnlyWhileThisProcessIsLoading_195() {
        val loading = RefreshState.Loading(step = 1, ofSteps = 3)
        assertFalse(syncIncomplete(SyncMark.NONE, RefreshState.Idle))
        assertTrue(syncIncomplete(SyncMark.PARTIAL, loading))
        assertTrue(syncIncomplete(SyncMark.PARTIAL, RefreshState.Idle))
        assertFalse("#190: a healthy refresh keeps its numbers", syncIncomplete(SyncMark.RUNNING, loading))
        assertTrue("left by a dead process", syncIncomplete(SyncMark.RUNNING, RefreshState.Idle))
    }

    @Test
    fun startupRefresh_includesAnIncompleteTileEvenWhenYoungerThanThirtyMinutes_195() {
        val now = FIXED_CLOCK
        val settings = com.rysal.happytv.data.AppSettings(
            lastLiveRefreshUtcMillis = now - 60_000L,
            lastVodRefreshUtcMillis = now - 60_000L,
            lastSeriesRefreshUtcMillis = now - 60_000L,
            vodSyncMark = SyncMark.RUNNING,
            seriesSyncMark = SyncMark.PARTIAL,
        )
        assertEquals(
            listOf(StreamType.SERIES, StreamType.VOD),
            startupTilesToRefresh(settings, RefreshProgress(), now),
        )
        // A running sync in this process is not started a second time.
        val progress = RefreshProgress(vod = RefreshState.Loading(step = 1, ofSteps = 3))
        assertEquals(listOf(StreamType.SERIES), startupTilesToRefresh(settings, progress, now))
    }

    @Test
    fun returnRefresh_treatsAnIncompleteTileAsStale_195() {
        val now = FIXED_CLOCK
        assertTrue(staleForReturnRefresh(now - 1_000L, RefreshState.Idle, now, incomplete = true))
        assertFalse(staleForReturnRefresh(now - 1_000L, RefreshState.Idle, now))
    }

    @Test
    fun startupTilesToRefresh_ageBoundaryIsThirtyMinutes() {
        val now = FIXED_CLOCK
        fun stale(ageMs: Long) = startupTilesToRefresh(
            com.rysal.happytv.data.AppSettings(
                lastLiveRefreshUtcMillis = now - ageMs,
                lastVodRefreshUtcMillis = now,
                lastSeriesRefreshUtcMillis = now,
            ),
            RefreshProgress(),
            now,
        )
        assertEquals(emptyList<StreamType>(), stale(STARTUP_REFRESH_AGE_MS - 1))
        assertEquals(listOf(StreamType.LIVE), stale(STARTUP_REFRESH_AGE_MS))
    }

    companion object {
        const val FIXED_CLOCK = 1_700_000_000_000L

        /**
         * Grace period for Room teardown. Long enough that a busy executor
         * always drains in practice, short enough that a genuine regression
         * costs one test's time rather than the whole run's.
         */
        private const val DB_CLOSE_GRACE_MS = 5_000L

        @JvmStatic
        private var dataStoreFiles = 0
    }
}
