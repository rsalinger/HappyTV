package com.rysal.happytv.data.network

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.SyncMark
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.HiddenCategory
import com.rysal.happytv.data.entity.HiddenChannel
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.security.SecuredCredentialsStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
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
 * Drives [RefreshCoordinator] end-to-end against a fake panel: no sockets, no
 * Robolectric shadows for HTTP — just the fake [XtreamApi] returning DTOs,
 * a real in-memory Room, a real DataStore and a real (plaintext-backed)
 * [SecuredCredentialsStore] (the encryption layer itself is the security
 * task's test's job).
 *
 * What this proves that the MockWebServer test cannot: the 6-call fan-out,
 * the per-tile states, the "a failing tile never deletes what it had" rule,
 * the login persistence order, and that whatever the tiles report is what
 * actually landed in Room.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RefreshCoordinatorTest {

    private lateinit var db: HappyTvDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var credentials: SecuredCredentialsStore
    private lateinit var api: FakeXtreamApi
    private lateinit var coordinator: RefreshCoordinator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // allowMainThreadQueries is TEST-ONLY (Robolectric runs on the main looper).
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = PreferenceDataStoreFactory.create {
            // Unique per test: DataStore refuses a second instance per file.
            File(context.cacheDir, "settings-${dataStoreFiles++}.preferences_pb")
        }
        settings = SettingsRepository(dataStore)
        // Real store over plain prefs: same code path, no Keystore under Robolectric.
        credentials = SecuredCredentialsStore(
            prefsFactory = { context.getSharedPreferences("test_secure_prefs", Context.MODE_PRIVATE) },
            // Frozen like the coordinator, so the sign-in stamp is exact.
            clockUtcMillis = { FIXED_CLOCK },
        )
        api = FakeXtreamApi()
        coordinator = RefreshCoordinator(
            database = db,
            settings = settings,
            credentials = credentials,
            api = api,
            io = Dispatchers.Unconfined, // deterministic single-threaded-ish test runs
            clockUtcMillis = { FIXED_CLOCK },
        )
    }

    @After
    fun tearDown() {
        // Barrier on Room's query executor before close: an in-flight query holds
        // the OpenHelper monitor and close() would wait on it (ProcessLock deadlock).
        val drained = java.util.concurrent.CountDownLatch(1)
        runCatching { db.queryExecutor.execute { drained.countDown() } }
        drained.await(5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        db.close()
    }

    @Test
    fun login_persistsEncryptedStoreAndProfileRow() = runBlocking {
        val result = coordinator.login(
            host = "panel.example",
            port = 8080,
            useTls = false,
            username = "user42",
            password = "s3cret",
        )
        assertTrue(result is LoginResult.Success)

        // 1) The plaintext credential set lives in the secured store.
        val stored = credentials.current()!!
        assertEquals("panel.example", stored.host)
        assertEquals(8080, stored.port)
        assertEquals("user42", stored.username)
        assertEquals("s3cret", stored.password)
        assertTrue(coordinator.isSignedIn())

        // 2) The Room row carries display metadata + an opaque fingerprint,
        //    never the password itself.
        val profile = db.loginProfileDao().get()!!
        assertEquals("user42", profile.username)
        assertEquals("panel.example", profile.host)
        assertTrue(profile.password.startsWith("sha256:"))
        assertFalse(profile.password.contains("s3cret"))

        // 3) user_info is cached for fast next-start hydration.
        val cachedUserInfo = settings.settings.first().userInfo
        assertNotNull(cachedUserInfo)
    }

    /** #19: the restore import hook runs after a successful sign-in only, and cannot fail it. */
    @Test
    fun login_runsTheAfterSignInStepOnlyOnSuccess_andItsFailureDoesNotFailTheLogin() = runBlocking {
        var calls = 0
        fun coordinatorWith(step: suspend () -> Unit) = RefreshCoordinator(
            database = db,
            settings = settings,
            credentials = credentials,
            api = api,
            io = Dispatchers.Unconfined,
            clockUtcMillis = { FIXED_CLOCK },
            afterSignIn = step,
        )

        api.loginResponse = refusedLogin()
        val refused = coordinatorWith { calls++ }.login("panel.example", 8080, false, "user42", "wrong")
        assertTrue(refused is LoginResult.Failure)
        assertEquals("not after a refused sign-in", 0, calls)

        api.loginResponse = FakeXtreamApi().loginResponse
        val ok = coordinatorWith { calls++ }.login("panel.example", 8080, false, "user42", "s3cret")
        assertTrue(ok is LoginResult.Success)
        assertEquals(1, calls)

        val failing = coordinatorWith { error("boom") }.login("panel.example", 8080, false, "user42", "s3cret")
        assertTrue("a failing import never fails sign-in", failing is LoginResult.Success)
    }

    @Test
    fun login_refused_storesNothingAndReportsTheReason() = runBlocking {
        api.loginResponse = refusedLogin()

        val result = coordinator.login("panel.example", 8080, false, "user42", "wrong")

        val failure = result as LoginResult.Failure
        assertEquals("Your subscription has expired.", failure.message)
        assertFalse(coordinator.isSignedIn())
        // No sign-in happened: the published session must still be empty (F-45).
        assertEquals(SessionState(), coordinator.sessionState.first { it.resolved })
        assertNull(db.loginProfileDao().get())
        assertNull(coordinator.currentRepository())
    }

    /**
     * F-45: the dashboard header observes [RefreshCoordinator.sessionState],
     * derived from the credential store and published on every transition —
     * signed-in + the persisted stamp the moment login lands, empty again on
     * sign-out. This is the flow the login screen's update reaches, so the
     * header can flip without a re-prime or app restart.
     */
    @Test
    fun sessionState_publishedOnLoginAndSignOut() = runBlocking {
        assertEquals(SessionState(), coordinator.sessionState.first { it.resolved })

        assertTrue(
            coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success,
        )
        // The stamp is exactly what the store persisted at the login call site.
        assertEquals(
            SessionState(signedIn = true, lastSignedInAtUtcMillis = FIXED_CLOCK),
            coordinator.sessionState.value,
        )
        assertEquals(credentials.current()!!.savedAtUtcMillis, coordinator.sessionState.value.lastSignedInAtUtcMillis)

        coordinator.signOut()
        assertEquals(SessionState(), coordinator.sessionState.first { it.resolved })
    }

    /**
     * Cold start with remembered credentials: a *fresh* coordinator (new
     * process) derives the session from the persisted store at construction —
     * the header shows the age of the real sign-in, never "Not signed in".
     */
    @Test
    fun sessionState_coldStartDerivesPersistedSession() = runBlocking {
        assertTrue(
            coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success,
        )

        val freshProcess = RefreshCoordinator(
            database = db,
            settings = settings,
            credentials = credentials,
            api = api,
            io = Dispatchers.Unconfined,
            clockUtcMillis = { FIXED_CLOCK },
        )

        // The construction-time prime reads the store on `io` — wait for it to
        // land (StateFlow re-emits the derived session) before asserting.
        freshProcess.sessionState.first { it.signedIn }
        assertEquals(
            SessionState(signedIn = true, lastSignedInAtUtcMillis = FIXED_CLOCK),
            freshProcess.sessionState.value,
        )
    }

    @Test
    fun refreshAll_writesAllSixCatalogs_andReportsDonePerTile() = runBlocking {
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)

        coordinator.refreshAll()

        // All six halves landed in Room: one category per catalog, then
        // 3 live channels, 2 movies, 1 show.
        assertEquals(
            3,
            db.categoryDao().count(StreamType.LIVE) +
                db.categoryDao().count(StreamType.VOD) +
                db.categoryDao().count(StreamType.SERIES),
        )
        assertEquals(3, db.liveStreamDao().count())
        assertEquals(2, db.vodStreamDao().count())
        assertEquals(1, db.seriesStreamDao().count())

        // Per-tile states: channels = rows written, ms = deterministic clock.
        val progress = coordinator.progress.value
        assertEquals(RefreshState.Done(channels = 3, ms = 0), progress.live)
        assertEquals(RefreshState.Done(channels = 2, ms = 0), progress.vod)
        assertEquals(RefreshState.Done(channels = 1, ms = 0), progress.series)

        // Session aggregate on the repository flow: 6 rows total.
        assertEquals(RefreshState.Done(channels = 6, ms = 0), coordinator.currentRepository()!!.refreshState.value)

        // "Last updated" timestamps for the dashboard's labels.
        assertEquals(FIXED_CLOCK, settings.settings.first().lastLiveRefreshUtcMillis)
        assertEquals(FIXED_CLOCK, settings.settings.first().lastVodRefreshUtcMillis)
        assertEquals(FIXED_CLOCK, settings.settings.first().lastSeriesRefreshUtcMillis)
    }

    @Test
    fun refreshAll_failingLiveTile_reportsErrorAndKeepsTheOtherTiles() = runBlocking {
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)
        api.liveStreamsFailure = IOException("panel flaked")

        coordinator.refreshAll()

        val progress = coordinator.progress.value
        val liveError = progress.live as RefreshState.Error
        assertTrue(liveError.message.contains("Live TV"))
        assertTrue(liveError.message.contains("panel flaked"))
        assertEquals(RefreshState.Done(channels = 2, ms = 0), progress.vod)
        assertEquals(RefreshState.Done(channels = 1, ms = 0), progress.series)

        // The failed half wrote nothing; its categories half DID land, and the
        // old catalog was not deleted (there was none — a pre-existing one survives).
        assertEquals(0, db.liveStreamDao().count())
        assertEquals(1, db.categoryDao().count(StreamType.LIVE))
        assertTrue(coordinator.currentRepository()!!.refreshState.value is RefreshState.Error)
    }

    @Test
    fun refreshAll_partialFailure_rowsLanded_stampsTheType() = runBlocking {
        // F-41: the streams half landed, so this type's data really did
        // refresh even though the tile reports Error. It must still be
        // stamped, or the dashboard treats it as never-refreshed (0) and
        // fires a full refreshAll on every return from a child screen.
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)
        api.liveCategoriesFailure = IOException("categories flaked")

        coordinator.refreshAll()

        assertTrue(coordinator.progress.value.live is RefreshState.Error)
        assertEquals(3, db.liveStreamDao().count())
        assertEquals(FIXED_CLOCK, settings.settings.first().lastLiveRefreshUtcMillis)
    }

    @Test
    fun refreshAll_streamsHalfFails_leavesTheTypeUnmarked() = runBlocking {
        // Mirror case: rows did NOT land (streams half failed), so there is
        // nothing fresh — the stamp must stay 0 so the next return/refresh
        // can still tell this tile has no data.
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)
        api.liveStreamsFailure = IOException("panel flaked")

        coordinator.refreshAll()

        assertTrue(coordinator.progress.value.live is RefreshState.Error)
        assertEquals(0, db.liveStreamDao().count())
        assertEquals(0L, settings.settings.first().lastLiveRefreshUtcMillis)
        // The other two types were unaffected.
        assertEquals(FIXED_CLOCK, settings.settings.first().lastVodRefreshUtcMillis)
        assertEquals(FIXED_CLOCK, settings.settings.first().lastSeriesRefreshUtcMillis)
    }

    @Test
    fun refreshAll_withoutLogin_reportsNotLoggedInOnEveryTile() = runBlocking {
        coordinator.refreshAll()

        val progress = coordinator.progress.value
        assertTrue(progress.live is RefreshState.Error)
        assertTrue(progress.vod is RefreshState.Error)
        assertTrue(progress.series is RefreshState.Error)
        assertTrue((progress.live as RefreshState.Error).message.contains("Not logged in"))
    }

    @Test
    fun refreshLiveOnly_touchesOnlyTheLiveTile() = runBlocking {
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)

        coordinator.refreshLiveOnly()

        assertEquals(RefreshState.Done(channels = 3, ms = 0), coordinator.progress.value.live)
        assertEquals(RefreshState.Idle, coordinator.progress.value.vod)
        assertEquals(RefreshState.Idle, coordinator.progress.value.series)
        assertEquals(3, db.liveStreamDao().count())
    }

    @Test
    fun refreshLiveOnly_liveReachesDoneWhileAggregateStaysIncomplete() = runBlocking {
        // F-63 regression: the dashboard's Live TV tile reads the PER-TILE
        // state (RefreshProgress.live), not the session aggregate. When only
        // the live tile's refresh completes, that per-tile state must reach
        // Done — the aggregate is still Loading(1/3) because the other two
        // tiles have not run, and that must not hold the tile back.
        //
        // The on-TV symptom this pins: the pill kept reading "Updating…"
        // after the refresh was finished. Either a tile that never reaches
        // Done, or a tile whose Done is only visible through the aggregate,
        // produces exactly that.
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)

        coordinator.refreshLiveOnly()

        // Per-tile: Done with the rows this tile actually wrote.
        assertEquals(RefreshState.Done(channels = 3, ms = 0), coordinator.progress.value.live)

        // Aggregate: one of three tiles done, so still a progress state —
        // and explicitly NOT Done, which is the whole point of the split.
        val aggregate = coordinator.currentRepository()!!.refreshState.value
        assertTrue(
            "aggregate must stay in-flight until all three tiles finish, was $aggregate",
            aggregate is RefreshState.Loading,
        )
        assertEquals(1, (aggregate as RefreshState.Loading).step)
        assertEquals(3, aggregate.ofSteps)

        // And the tile's rows are really in Room, so "Done" is not a lie.
        assertEquals(3, db.liveStreamDao().count())
    }

    @Test
    fun refreshLiveOnly_thenRefreshAll_aggregateReachesDoneWhenAllThreeFinish() = runBlocking {
        // The mirror of the test above: once the last tile lands, the
        // aggregate follows. Between the two states the UI must never see
        // the Live tile stuck in Loading.
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)

        coordinator.refreshLiveOnly()
        assertTrue(coordinator.currentRepository()!!.refreshState.value is RefreshState.Loading)

        coordinator.refreshAll()

        assertEquals(RefreshState.Done(channels = 6, ms = 0), coordinator.currentRepository()!!.refreshState.value)
        StreamType.entries.forEach { type ->
            assertTrue(
                "$type must be Done after refreshAll, was ${coordinator.progress.value[type]}",
                coordinator.progress.value[type] is RefreshState.Done,
            )
        }
    }

    @Test
    fun signOut_wipesCredentialsAndProfileButKeepsCatalogs() = runBlocking {
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)
        coordinator.refreshAll()

        coordinator.signOut()

        assertFalse(coordinator.isSignedIn())
        assertNull(db.loginProfileDao().get())
        assertNull(settings.settings.first().userInfo)
        assertEquals(RefreshState.Idle, coordinator.progress.value.live)
        // Catalogs stay: the next account's refresh replaces them wholesale.
        assertEquals(3, db.liveStreamDao().count())
    }

    /** The household's choices plus a stale catalog row and a guide row. */
    private suspend fun seedChoicesAndStaleCatalog() {
        db.liveStreamDao().insertAll(listOf(
            LiveStream(id = "1", categoryId = "1", name = "Old channel 1"),
            LiveStream(id = "99", categoryId = "1", name = "Gone channel"),
        ))
        db.categoryDao().replaceAll(StreamType.LIVE, listOf(
            Category(id = "1", type = StreamType.LIVE, name = "Sports"),
            Category(id = "77", type = StreamType.LIVE, name = "Gone category"),
        ))
        db.favoriteDao().insert(Favorite(streamId = "1", streamType = StreamType.LIVE))
        db.favoriteDao().insert(Favorite(streamId = "99", streamType = StreamType.LIVE))
        db.recentChannelDao().recordWatch("2", 1_000L)
        db.hiddenChannelDao().insert(HiddenChannel(streamId = "3", streamType = StreamType.LIVE))
        db.hiddenCategoryDao().insert(HiddenCategory(categoryId = "1", categoryType = StreamType.LIVE))
        db.epgProgramDao().insertAll(listOf(
            EpgProgram(channelId = "old", title = "Old show", startUtcMillis = 1L, endUtcMillis = 2L),
        ))
    }

    @Test
    fun refreshEverything_keepsChoices_replacesCatalog_andKeepsGuideUntilItIsReplaced() = runBlocking {
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)
        seedChoicesAndStaleCatalog()

        coordinator.refreshEverything()

        // Catalog replaced from the provider: the stale ids are gone, fresh ones landed.
        assertEquals(listOf("1", "2", "3"), db.liveStreamDao().getAll().map { it.id }.sorted())
        assertEquals(setOf("1"), db.categoryDao().getByType(StreamType.LIVE).map { it.id }.toSet())
        assertEquals(2, db.vodStreamDao().count())
        // Choices survive, including ids the provider no longer lists (ignored, not deleted).
        assertTrue(db.favoriteDao().isFavorite("1", StreamType.LIVE))
        assertTrue(db.favoriteDao().isFavorite("99", StreamType.LIVE))
        assertNotNull(db.recentChannelDao().get("2"))
        assertTrue(db.hiddenChannelDao().isHidden("3", StreamType.LIVE))
        assertTrue(db.hiddenCategoryDao().isHidden("1", StreamType.LIVE))
        // The re-downloaded category carries its hidden flag again.
        assertEquals(true, db.categoryDao().isHidden("1", StreamType.LIVE))
        // The guide is only replaced by the staged import; it is not emptied meanwhile.
        assertEquals(1, db.epgProgramDao().count())
        assertEquals(RefreshState.Done::class, coordinator.progress.value.live::class)
    }

    @Test
    fun clearCatalogs_stillWipesEverythingForSwitchAccount() = runBlocking {
        seedChoicesAndStaleCatalog()

        coordinator.clearCatalogs()

        assertEquals(0, db.liveStreamDao().count())
        assertEquals(0, db.categoryDao().getByType(StreamType.LIVE).size)
        assertFalse(db.favoriteDao().isFavorite("1", StreamType.LIVE))
        assertNull(db.recentChannelDao().get("2"))
        assertFalse(db.hiddenChannelDao().isHidden("3", StreamType.LIVE))
        assertFalse(db.hiddenCategoryDao().isHidden("1", StreamType.LIVE))
        assertEquals(0, db.epgProgramDao().count())
    }

    // ---- #195: an interrupted sync must not look complete

    /** Runs a live refresh held open mid-sync, reads the mark a dying process would leave, then lets it finish. */
    private suspend fun markWhileLiveSyncIsRunning(): SyncMark {
        val gate = CompletableDeferred<Unit>()
        api.liveStreamsGate = gate
        val job = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) { coordinator.refreshLiveOnly() }
        val mark = settings.settings.first { it.liveSyncMark != SyncMark.NONE }.liveSyncMark
        gate.complete(Unit)
        job.join()
        api.liveStreamsGate = null
        return mark
    }

    @Test
    fun firstSync_isMarkedPartialBeforeTheFirstRowLands_andClearedWithTheStamp() = runBlocking {
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)

        assertEquals(SyncMark.PARTIAL, markWhileLiveSyncIsRunning())

        assertEquals(SyncMark.NONE, settings.settings.first().liveSyncMark)
        assertEquals(FIXED_CLOCK, settings.settings.first().lastLiveRefreshUtcMillis)
    }

    @Test
    fun resync_overACompleteCatalog_isMarkedRunning_andClearedAtTheEnd() = runBlocking {
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)
        coordinator.refreshLiveOnly()
        assertEquals(SyncMark.NONE, settings.settings.first().liveSyncMark)

        assertEquals(SyncMark.RUNNING, markWhileLiveSyncIsRunning())

        assertEquals(SyncMark.NONE, settings.settings.first().liveSyncMark)
    }

    @Test
    fun aMarkLeftByADeadProcess_staysPartialThroughTheNextRun_untilItCompletes() = runBlocking {
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)
        coordinator.refreshLiveOnly()
        // The process died mid-sync: the RUNNING mark survived with a stamp from the earlier complete sync.
        settings.setSyncMark(StreamType.LIVE, SyncMark.RUNNING)

        api.liveStreamsFailure = IOException("panel flaked")
        coordinator.refreshLiveOnly()
        assertEquals("a failed retry does not make the catalog complete", SyncMark.PARTIAL, settings.settings.first().liveSyncMark)

        api.liveStreamsFailure = null
        coordinator.refreshLiveOnly()
        assertEquals(SyncMark.NONE, settings.settings.first().liveSyncMark)
    }

    @Test
    fun failedFirstSync_staysPartial_butAFailedResyncOverACompleteCatalogDoesNot() = runBlocking {
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)
        api.liveStreamsFailure = IOException("panel flaked")
        coordinator.refreshLiveOnly()
        assertEquals(SyncMark.PARTIAL, settings.settings.first().liveSyncMark)

        api.liveStreamsFailure = null
        coordinator.refreshLiveOnly()
        api.liveStreamsFailure = IOException("panel flaked")
        coordinator.refreshLiveOnly()
        assertEquals(SyncMark.NONE, settings.settings.first().liveSyncMark)
    }

    @Test
    fun clearingTheRows_marksEveryCatalogPartial_andRefreshEverythingEndsComplete() = runBlocking {
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)
        coordinator.refreshAll()

        // The stamps outlive the rows: a restart after the delete must not read them as complete.
        coordinator.clearCatalogs()
        val cleared = settings.settings.first()
        assertEquals(SyncMark.PARTIAL, cleared.liveSyncMark)
        assertEquals(SyncMark.PARTIAL, cleared.vodSyncMark)
        assertEquals(SyncMark.PARTIAL, cleared.seriesSyncMark)

        coordinator.refreshEverything()
        val done = settings.settings.first()
        assertEquals(SyncMark.NONE, done.liveSyncMark)
        assertEquals(SyncMark.NONE, done.vodSyncMark)
        assertEquals(SyncMark.NONE, done.seriesSyncMark)
    }

    @Test
    fun cancelledResyncOverACompleteCatalog_restoresTheMark() = runBlocking {
        assertTrue(coordinator.login("panel.example", 8080, false, "user42", "s3cret") is LoginResult.Success)
        coordinator.refreshLiveOnly()
        api.liveStreamsGate = CompletableDeferred()
        val job = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) { coordinator.refreshLiveOnly() }
        settings.settings.first { it.liveSyncMark == SyncMark.RUNNING }

        job.cancel()
        job.join()

        assertEquals(SyncMark.NONE, settings.settings.first().liveSyncMark)
    }

    private fun refusedLogin() = LoginResponse(
        user_info = UserInfoDto(
            username = "user42",
            auth = "0",
            status = "Expired",
            message = "Your subscription has expired.",
        ),
        server_info = null,
    )

    private companion object {
        const val FIXED_CLOCK = 1_700_000_000_000L
        var dataStoreFiles = 0
    }
}

/**
 * In-memory panel. Row counts are knobs; [liveStreamsFailure] makes exactly
 * one endpoint flake so the coordinator's half-failure path can be tested.
 */
private class FakeXtreamApi : XtreamApi {

    var loginResponse = LoginResponse(
        user_info = UserInfoDto(username = "user42", auth = "1", status = "Active"),
        server_info = ServerInfoDto(url = "panel.example", port = "8080"),
    )

    var liveStreamsFailure: Exception? = null

    /** Holds the live streams call open, standing in for a sync still running (or a process that dies). */
    var liveStreamsGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var liveCategoriesFailure: Exception? = null

    override suspend fun login(endpoint: String, username: String, password: String): LoginResponse =
        loginResponse

    override suspend fun getLiveCategories(
        endpoint: String,
        username: String,
        password: String,
        action: String,
    ): List<CategoryDto> {
        liveCategoriesFailure?.let { throw it }
        return listOf(CategoryDto(category_id = "1", category_name = "Sports", parent_id = "0"))
    }

    private suspend fun liveStreamDtos(
        endpoint: String,
        username: String,
        password: String,
        categoryId: String?,
        action: String,
    ): List<LiveStreamDto> {
        liveStreamsGate?.await()
        liveStreamsFailure?.let { throw it }
        return (1..3).map { n ->
            LiveStreamDto(num = "$n", name = "Channel $n", stream_id = "$n", category_id = "1")
        }
    }

    override suspend fun getVodCategories(
        endpoint: String,
        username: String,
        password: String,
        action: String,
    ) = listOf(CategoryDto(category_id = "2", category_name = "Movies", parent_id = "0"))

    private suspend fun vodStreamDtos(
        endpoint: String,
        username: String,
        password: String,
        categoryId: String?,
        action: String,
    ) = listOf(
        VodStreamDto(name = "The Matrix", stream_id = "501", category_id = "2", container_extension = "mp4"),
        VodStreamDto(name = "Heat", stream_id = "502", category_id = "2", container_extension = "mkv"),
    )

    override suspend fun getSeriesCategories(
        endpoint: String,
        username: String,
        password: String,
        action: String,
    ) = listOf(CategoryDto(category_id = "3", category_name = "Shows", parent_id = "0"))

    private suspend fun seriesStreamDtos(
        endpoint: String,
        username: String,
        password: String,
        categoryId: String?,
        action: String,
    ) = listOf(SeriesStreamDto(name = "Breaking Bad", series_id = "901", category_id = "3"))

    override suspend fun getLiveStreams(endpoint: String, username: String, password: String, categoryId: String?, action: String) =
        dtoBody(liveStreamDtos(endpoint, username, password, categoryId, action))

    override suspend fun getVodStreams(endpoint: String, username: String, password: String, categoryId: String?, action: String) =
        dtoBody(vodStreamDtos(endpoint, username, password, categoryId, action))

    override suspend fun getSeries(endpoint: String, username: String, password: String, categoryId: String?, action: String) =
        dtoBody(seriesStreamDtos(endpoint, username, password, categoryId, action))

    override suspend fun getVodInfo(
        endpoint: String,
        username: String,
        password: String,
        vodId: String,
        action: String,
    ) = VodInfoDto(info = VodInfoDetailDto(plot = "Test plot"), movie_data = MovieDataDto(stream_id = vodId))

    override suspend fun getSeriesInfo(
        endpoint: String,
        username: String,
        password: String,
        seriesId: String,
        action: String,
    ) = SeriesInfoDto(info = SeriesStreamDto(name = "Breaking Bad", series_id = seriesId, category_id = "3"))

    override suspend fun getShortEpg(
        endpoint: String,
        username: String,
        password: String,
        streamId: String,
        limit: Int,
        action: String,
    ) = EpgListingsResponseDto(epg_listings = emptyList())

    override suspend fun getSimpleDataTable(
        endpoint: String,
        username: String,
        password: String,
        streamId: String,
        action: String,
    ) = EpgListingsResponseDto(epg_listings = emptyList())

    override suspend fun getXmlTv(endpoint: String, username: String, password: String): ResponseBody =
        "<tv/>".toResponseBody()
}
