package com.rysal.happytv.data.network

import android.util.Log
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.BackgroundWork
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.SyncMark
import com.rysal.happytv.data.dao.CatalogWrite
import com.rysal.happytv.data.dao.syncStream
import com.rysal.happytv.data.entity.LoginProfile
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.security.Credentials
import com.rysal.happytv.security.SecuredCredentialsStore
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Outcome of [RefreshCoordinator.login] — the UI only ever shows one of these two. */
sealed interface LoginResult {
    /** Credentials accepted *and* persisted (encrypted store + Room profile). */
    data class Success(val userInfo: UserInfoDto?, val serverInfo: ServerInfoDto?) : LoginResult

    /** Either the panel refused (`auth: 0`) or we never got an answer. */
    data class Failure(val message: String) : LoginResult
}

/**
 * Login + the 3-tile refresh — the app's data pump.
 *
 * **Login** ([login]) probes `player_api.php`, then persists the credential
 * set twice, on purpose:
 *  1. plaintext → [SecuredCredentialsStore] (EncryptedSharedPreferences), the
 *     only place a password exists in the clear;
 *  2. a salted SHA-256 fingerprint + display metadata → the `login_profiles`
 *     Room row, so the settings screen can show `host:port`/username without
 *     decrypting anything. The fingerprint is deliberately *not* the password
 *     and never authenticates: if the vault and the row ever disagree, the
 *     vault wins and the user re-enters the password.
 *
 * **Refresh** ([refreshAll], [refreshLiveOnly]…) launches the six catalog
 * calls concurrently — two per tile, each `async` on the low-priority [bulk]
 * pool — waits for all of them, writes each successful half to its DAO as a
 * diff against the stored rows (#43: unchanged rows are not rewritten), and
 * reports per-tile states into [progress] plus a session-wide aggregate into
 * [XtreamRepository.refreshState].
 *
 * Failure semantics: a tile's half that failed is *reported*, not retried
 * (IPTV panels flake; a retry loop is worse than a visible retry button), and
 * nothing it would have replaced is deleted — old categories/rows stay
 * readable behind the Error state until the next successful refresh.
 *
 * Concurrency: one refresh per tile at a time ([tileLocks]); different tiles
 * still run fully in parallel, so a slow VOD catalog never blocks a live
 * refresh.
 */
class RefreshCoordinator(
    private val database: HappyTvDatabase,
    private val settings: SettingsRepository,
    private val credentials: SecuredCredentialsStore,
    private val api: XtreamApi = Graph.xtreamApi(XtreamApi::class.java),
    private val json: Json = Graph.json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Where the catalog fetches and writes run (#43): a small, low-priority
     * pool, so a 20 s refresh of a 21k-channel catalog cannot starve the UI
     * thread on a 4-core device. Defaults to [io] (tests); production passes
     * [BackgroundWork.bulk] through `Graph`.
     */
    private val bulk: CoroutineDispatcher = io,
    private val clockUtcMillis: () -> Long = System::currentTimeMillis,
    /**
     * #19: runs once after each successful sign-in (production: imports a
     * restored household backup). Failures are logged by type and never fail
     * the sign-in.
     */
    private val afterSignIn: suspend () -> Unit = {},
) : RefreshGateway {

    private val _progress = MutableStateFlow(RefreshProgress())

    /** The three tiles' states — collect this from the dashboard ViewModel. */
    override val progress: StateFlow<RefreshProgress> = _progress.asStateFlow()

    /**
     * The live sign-in state behind the dashboard header (F-45).
     *
     * Always *derived* from [SecuredCredentialsStore] — one source of truth:
     * `savedAtUtcMillis` is written by exactly one caller (a successful
     * [login]), so it is the sign-in wall-clock the header shows, and
     * [signOut] wiping the store is what drops this back to the empty state.
     * [publishSession] re-reads the store under [sessionMutex], so the last
     * publish to run always reflects the latest persisted session — no stale
     * one-shot value can outlive a sign-in.
     */
    private val _sessionState = MutableStateFlow(SessionState(resolved = false))
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    /** Serializes derive-and-publish so a stale read can't overwrite a newer session. */
    private val sessionMutex = Mutex()

    private val sessionScope = CoroutineScope(SupervisorJob() + io)

    init {
        // Cold start: the store may already hold a session (remembered
        // credentials survive process death). Prime once; login()/signOut()
        // publish every later transition.
        sessionScope.launch {
            try {
                publishSession()
            } catch (e: Exception) {
                // An unreadable store must not leave the dashboard resolving
                // forever: treat it as signed out (the user can sign in again).
                _sessionState.compareAndSet(SessionState(resolved = false), SessionState())
            }
        }
    }

    /**
     * Seeds the session as signed in from a credential read that already
     * happened elsewhere (the launch router, issue #30), so the first
     * dashboard frame needn't wait for this coordinator's own cold-start read.
     * Only effective while the session is still unresolved: any real publish
     * ([login], [signOut], the cold-start prime) wins. Does no I/O.
     */
    fun primeSignedIn(savedAtUtcMillis: Long) {
        _sessionState.update {
            if (it.resolved) it else SessionState(signedIn = true, lastSignedInAtUtcMillis = savedAtUtcMillis)
        }
    }

    /** One lock per tile: taps queue behind an in-flight refresh of that tile only. */
    private val tileLocks: Map<StreamType, Mutex> =
        StreamType.values().associateWith { Mutex() }

    @Volatile
    private var sessionRepository: XtreamRepository? = null

    @Volatile
    private var lastRunStartedAtUtcMillis: Long? = null

    // ------------------------------------------------------------------ auth

    /**
     * Validates [username]/[password] against the panel, then persists them
     * and the [LoginProfile] row. Never throws for panel-level problems: a
     * refused login, an expired account or an unreachable host all come back
     * as [LoginResult.Failure] with a human-readable message.
     *
     * @param displayName label for the Room profile row; defaults to the host.
     */
    suspend fun login(
        host: String,
        port: Int,
        useTls: Boolean,
        username: String,
        password: String,
        displayName: String = host.trim().substringBefore(':').ifBlank { host },
    ): LoginResult {
        val repository = XtreamRepository(host, port, useTls, username, password, api, json)
        val response = try {
            repository.login()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LoginResult.Failure(
                "Couldn't reach ${repository.origin} — ${e.message ?: e::class.java.simpleName}",
            )
        }
        val info = response.user_info
        if (!response.isAuthenticated) {
            Log.w(TAG, "Login refused by ${repository.origin}: status=${info?.status}, msg=${info?.message}")
            return LoginResult.Failure(describeAuthFailure(info))
        }
        warnIfExpiringSoon(info)

        // 1) The one plaintext copy, encrypted at rest by Android Keystore.
        credentials.save(host, port, useTls, username, password)
        // 2) Room row: display metadata + fingerprint, never the password.
        database.loginProfileDao().upsert(
            LoginProfile(
                name = displayName,
                host = host,
                port = port,
                username = username,
                password = passwordFingerprint(password),
                useTls = useTls,
            ),
        )
        // 3) Cache user_info (allowed output formats, exp date…) for fast
        //    hydration before the first refresh of the next launch.
        settings.setUserInfo(info?.let { json.encodeToString(it).toByteArray(Charsets.UTF_8) })

        sessionRepository = repository
        // Publish the new session (signed-in + the persisted stamp) before the
        // login screen even fades out — the dashboard header updates from this
        // flow, so it flips the moment the panel accepts (F-45).
        publishSession()
        try {
            afterSignIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "post-sign-in step failed: ${e::class.java.simpleName}")
        }
        Log.i(TAG, "Logged in to ${repository.origin}")
        return LoginResult.Success(info, response.server_info)
    }

    /** True when the encrypted store holds a credential set. */
    override suspend fun isSignedIn(): Boolean = credentials.isPresent()

    /**
     * Re-derives [sessionState] from the credential store and publishes it.
     *
     * Called after every session transition ([login] after the save, [signOut]
     * after the wipe) and once at construction for the cold-start case. The
     * derive-and-publish pair runs under [sessionMutex] so a slow read can
     * never overwrite the result of a newer one — the value is always a
     * coherent snapshot of the store, never a remembered one-shot.
     */
    private suspend fun publishSession() = sessionMutex.withLock {
        val stored = credentials.current()
        _sessionState.value = if (stored == null) {
            SessionState()
        } else {
            SessionState(signedIn = true, lastSignedInAtUtcMillis = stored.savedAtUtcMillis)
        }
    }

    /**
     * The session repository built from the stored credentials — for callers
     * that need the API directly (EPG fetches, VOD/series detail). Caches the
     * instance per process; null when nobody is logged in.
     */
    suspend fun currentRepository(): XtreamRepository? {
        sessionRepository?.let { return it }
        val stored: Credentials = credentials.current() ?: return null
        return XtreamRepository(
            host = stored.host,
            port = stored.port,
            useTls = stored.useTls,
            username = stored.username,
            password = stored.password,
            api = api,
            json = json,
        ).also { sessionRepository = it }
    }

    /**
     * Sign out: wipe the encrypted credentials, the Room profile row and the
     * cached `user_info`. Catalog tables are left alone — they are replaced
     * wholesale by the next account's first refresh, and leaving them means a
     * mis-tap doesn't cost the user their whole guide.
     */
    override suspend fun signOut() {
        credentials.wipe()
        database.loginProfileDao().clear()
        settings.setUserInfo(null)
        sessionRepository = null
        // The wipe is what makes the header say "Not signed in" again —
        // publish it like every other session transition (F-45).
        publishSession()
        StreamType.values().forEach { setState(it, RefreshState.Idle) }
    }

    /**
     * Wipe every saved list: the three catalogs, their categories, favorites,
     * recently watched, hidden rows and the stored guide.
     *
     * Settings owns the two promises that need this — design spec §"Settings"
     * "Refresh everything now" ("Clears the saved lists and downloads them
     * again") and "Switch account" ("Signs you out of this server and clears
     * its saved lists"). [signOut] deliberately does **not** do it: a mis-tap
     * elsewhere shouldn't cost the user their guide, but an explicit,
     * confirmed settings action should.
     */
    suspend fun clearCatalogs() {
        val startedAt = clockUtcMillis()
        clearCatalogRows()
        val rowsDoneAt = clockUtcMillis()
        database.favoriteDao().clear()
        database.recentChannelDao().clear()
        database.hiddenChannelDao().clear()
        database.hiddenCategoryDao().clear()
        val epgStartedAt = clockUtcMillis()
        database.epgProgramDao().deleteAll()
        val epgDoneAt = clockUtcMillis()
        StreamType.values().forEach { setState(it, RefreshState.Idle) }
        publishAggregate()
        // #160: where a sign-out's seconds go (catalog rows, the small tables, the guide).
        HtvPerf.log("signout.clear", epgDoneAt - startedAt, "catalog=${rowsDoneAt - startedAt}ms guide=${epgDoneAt - epgStartedAt}ms")
    }

    /**
     * Settings' "Refresh everything now" (#48): drop the downloaded catalog
     * (channels, movies, shows and their categories) and download it again,
     * while keeping what the household chose: favorites, recently watched,
     * hidden channels and hidden categories. Ids the provider no longer lists
     * stay unmatched, as on any ordinary refresh; the category sync re-applies
     * hidden flags from the durable hidden-category rows.
     *
     * The guide is deliberately **not** cleared here. The staged import (F-65)
     * keeps serving the previous guide until its short promotion, so the grid
     * never shows "No guide yet" while the new one downloads.
     */
    suspend fun refreshEverything() {
        // #166: the tiles say "updating" from the moment the household confirms, not only once
        // the old rows are gone (that delete takes tens of seconds on a full catalog).
        StreamType.values().forEach { setState(it, RefreshState.Loading(step = 0, ofSteps = SUB_STEPS)) }
        publishAggregate()
        clearCatalogRows()
        refreshAll()
    }

    private suspend fun lastRefreshOf(type: StreamType): Long {
        val s = settings.settings.first()
        return when (type) {
            StreamType.LIVE -> s.lastLiveRefreshUtcMillis
            StreamType.VOD -> s.lastVodRefreshUtcMillis
            StreamType.SERIES -> s.lastSeriesRefreshUtcMillis
        }
    }

    private suspend fun clearCatalogRows() {
        // #195: the stamps outlive the rows, so a restart between the delete and the end of the
        // download would read an empty or half-filled catalog as complete.
        StreamType.values().forEach { settings.setSyncMark(it, SyncMark.PARTIAL) }
        database.liveStreamDao().deleteAll()
        database.vodStreamDao().deleteAll()
        database.seriesStreamDao().deleteAll()
        StreamType.values().forEach { database.categoryDao().deleteByType(it) }
    }

    // -------------------------------------------------------------- refresh

    /** Refresh all three tiles in parallel (the 6 concurrent catalog calls). */
    override suspend fun refreshAll() {
        val repository = currentRepository()
        if (repository == null) {
            val message = NOT_LOGGED_IN
            StreamType.values().forEach { setState(it, RefreshState.Error(message)) }
            return
        }
        val startedAt = clockUtcMillis()
        lastRunStartedAtUtcMillis = startedAt
        coroutineScope {
            launch { refreshTile(repository, StreamType.LIVE, startedAt,
                syncStreams = { repository.withLiveStreams { database.liveStreamDao().syncStream(it) } }) }
            launch { refreshTile(repository, StreamType.VOD, startedAt,
                syncStreams = { repository.withVodStreams { database.vodStreamDao().syncStream(it) } }) }
            launch { refreshTile(repository, StreamType.SERIES, startedAt,
                syncStreams = { repository.withSeries { database.seriesStreamDao().syncStream(it) } }) }
        }
        Log.i(TAG, "refreshAll finished in ${clockUtcMillis() - startedAt} ms — ${_progress.value.doneChannels} rows")
    }

    /** One tile's refresh (the dashboard's per-tile "Refresh" buttons). */
    override suspend fun refreshLiveOnly() = refreshOnly(StreamType.LIVE)

    override suspend fun refreshVodOnly() = refreshOnly(StreamType.VOD)

    override suspend fun refreshSeriesOnly() = refreshOnly(StreamType.SERIES)

    /** `provider.example:8080` for the dashboard's About panel. */
    override suspend fun serverLabel(): String = currentRepository()?.origin ?: NOT_LOGGED_IN

    private suspend fun refreshOnly(type: StreamType) {
        val repository = currentRepository()
        if (repository == null) {
            setState(type, RefreshState.Error(NOT_LOGGED_IN))
            return
        }
        val startedAt = clockUtcMillis()
        lastRunStartedAtUtcMillis = startedAt
        coroutineScope {
            launch {
                when (type) {
                    StreamType.LIVE -> refreshTile(repository, type, startedAt,
                        syncStreams = { repository.withLiveStreams { database.liveStreamDao().syncStream(it) } })
                    StreamType.VOD -> refreshTile(repository, type, startedAt,
                        syncStreams = { repository.withVodStreams { database.vodStreamDao().syncStream(it) } })
                    StreamType.SERIES -> refreshTile(repository, type, startedAt,
                        syncStreams = { repository.withSeries { database.seriesStreamDao().syncStream(it) } })
                }
            }
        }
    }

    /**
     * One tile = three sub-steps: categories fetched (1/3), catalog rows
     * fetched and written (2/3), categories written (3/3 → [RefreshState.Done]).
     * The two fetches run as parallel [async]s on [bulk]; the catalog rows are
     * decoded and written to Room chunk by chunk while they download (#130), the
     * categories after both settled. No Room transaction spans the sync.
     */
    private suspend fun refreshTile(
        repository: XtreamRepository,
        type: StreamType,
        startedAt: Long,
        syncStreams: suspend () -> CatalogWrite,
    ) = tileLocks.getValue(type).withLock {
        setState(type, RefreshState.Loading(step = 0, ofSteps = SUB_STEPS))
        val fetchesFinished = AtomicInteger(0)
        val onFetchSettled = {
            val step = fetchesFinished.incrementAndGet().coerceAtMost(STEP_ITEMS)
            setState(type, RefreshState.Loading(step = step, ofSteps = SUB_STEPS))
            Unit
        }

        // #195: mark the catalog before the first chunk lands. A process that dies from here on
        // leaves RUNNING/PARTIAL behind, so the next start knows the stored rows are not a whole
        // catalog. A tile that was never completed, or was already marked, stays PARTIAL.
        val startedOverComplete = settings.syncMark(type) == SyncMark.NONE && lastRefreshOf(type) > 0L
        settings.setSyncMark(type, if (startedOverComplete) SyncMark.RUNNING else SyncMark.PARTIAL)
        // Where the mark lands if the run ends any way but a full success (failure, cancellation):
        // a catalog that was complete before is as complete as it was (the sync only upserts and
        // deletes last); one that was already partial stays partial.
        var endMark = if (startedOverComplete) SyncMark.NONE else SyncMark.PARTIAL

        val result: RefreshState = try { coroutineScope {
            val categories = async(bulk) { runCatching { repository.getCategories(type) } }
            val streams = async(bulk) {
                // #130: decode, compare and upsert chunk by chunk as the body arrives; the rows the
                // provider dropped are deleted only once the whole list decoded. A failure midway
                // leaves every stored row in place.
                val syncStartedAt = clockUtcMillis()
                runCatching { syncStreams() }.also {
                    HtvPerf.log(
                        "refresh.${type.name.lowercase(Locale.ROOT)}.streams",
                        clockUtcMillis() - syncStartedAt,
                        it.fold({ w -> "upserted=${w.upserted} removed=${w.removed} unchanged=${w.unchanged}" }, { "failed" }),
                    )
                }
            }
            categories.invokeOnCompletion { onFetchSettled() }
            streams.invokeOnCompletion { onFetchSettled() }
            val categoriesResult = categories.await()
            val streamsResult = streams.await()

            // Sub-step 3/3: persist whatever the panel actually gave us. A
            // failed half simply isn't written — the previous rows survive.
            //
            // #43: no outer transaction any more. Both writes are diffs that
            // touch only changed rows (a steady-state refresh writes nothing),
            // and a first load is written in short chunks, so no single
            // transaction holds the database for the whole catalog.
            try {
                withContext(bulk) {
                    val writeStartedAt = clockUtcMillis()
                    val categoriesRewritten = categoriesResult.getOrNull()?.let {
                        database.categoryDao().syncAll(type, it)
                    }
                    val write = streamsResult.getOrNull()
                    HtvPerf.log(
                        "refresh.${type.name.lowercase(Locale.ROOT)}.write",
                        clockUtcMillis() - writeStartedAt,
                        "upserted=${write?.upserted ?: 0} removed=${write?.removed ?: 0} " +
                            "unchanged=${write?.unchanged ?: 0} categories=${categoriesRewritten ?: "skipped"}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Persisting ${type.label} catalog failed", e)
                return@coroutineScope RefreshState.Error(
                    "${type.label}: couldn't save the catalog — ${e.message ?: e::class.java.simpleName}",
                )
            }

            val failure = categoriesResult.exceptionOrNull() ?: streamsResult.exceptionOrNull()
            if (failure != null) {
                Log.w(TAG, "${type.label} refresh failed", failure)
                // F-41: if the catalog rows landed, this type's data really did
                // refresh even though the other half flaked — stamp it, or a
                // partial load keeps lastRefreshedAt=0 forever and every BACK
                // from a child screen fires a full refreshAll(). Rows that did
                // NOT land (streams half failed) stay unmarked: there is
                // nothing fresh to claim.
                if (streamsResult.isSuccess) {
                    settings.markRefreshed(type, clockUtcMillis())
                }
                RefreshState.Error(failure.describeFor(type))
            } else {
                val rows = streamsResult.getOrThrow().total
                settings.markRefreshed(type, clockUtcMillis())
                endMark = SyncMark.NONE
                RefreshState.Done(channels = rows, ms = clockUtcMillis() - startedAt)
            }
        } } finally {
            withContext(NonCancellable) { settings.setSyncMark(type, endMark) }
        }

        setState(type, result)
    }

    // -------------------------------------------------------------- plumbing

    /** Per-tile state update; also republishes the session aggregate. */
    private fun setState(type: StreamType, state: RefreshState) {
        _progress.update { it.with(type, state) }
        publishAggregate()
    }

    /**
     * The 3-tile run as a single [RefreshState], written to the session
     * repository's flow: `Loading(n/3)` while tiles complete, `Done` with the
     * total row count, or the first tile's `Error`.
     */
    private fun publishAggregate() {
        val repository = sessionRepository ?: return
        val progress = _progress.value
        val states = listOf(progress.live, progress.vod, progress.series)
        val error = states.filterIsInstance<RefreshState.Error>().firstOrNull()
        val done = states.filterIsInstance<RefreshState.Done>()
        val aggregate = when {
            error != null -> error
            done.size == 3 -> RefreshState.Done(
                channels = done.sumOf { it.channels },
                ms = clockUtcMillis() - (lastRunStartedAtUtcMillis ?: clockUtcMillis()),
            )
            else -> RefreshState.Loading(step = done.size, ofSteps = 3)
        }
        repository.reportRefresh(aggregate)
    }

    private fun describeAuthFailure(info: UserInfoDto?): String {
        val status = info?.status?.blankToNull()?.lowercase(Locale.ROOT)
        val message = info?.message?.blankToNull()
        return when (status) {
            null -> message ?: "Wrong username or password."
            "expired" -> message ?: "Subscription expired."
            "banned" -> message ?: "Account suspended by the provider."
            "disabled" -> message ?: "Account disabled by the provider."
            else -> message ?: "Login refused ($status)."
        }
    }

    /** Panels report `auth: 1` with a past `exp_date`; log it, don't block login. */
    private fun warnIfExpiringSoon(info: UserInfoDto?) {
        val expDate = JsonCoerce.toLongOrNull(info?.exp_date) ?: return
        val nowSeconds = clockUtcMillis() / 1000
        if (expDate < nowSeconds) {
            Log.w(TAG, "Panel accepted the login but exp_date=$expDate is in the past — subscription looks expired")
        }
    }

    private companion object {
        const val TAG = "RefreshCoordinator"

        /** Sub-steps per tile: categories (1), rows (2), persisted (3). */
        const val SUB_STEPS = 3

        /** Index of the last fetch sub-step, for `Loading(n/3)` clamping. */
        const val STEP_ITEMS = 2

        const val NOT_LOGGED_IN = "Not logged in — add your provider's credentials first."
    }
}

/**
 * Salted SHA-256 fingerprint for [LoginProfile.password] — the "opaque value"
 * the schema documents. It identifies a credential set without carrying it:
 * the vault already holds the plaintext, and this column must never be able
 * to yield it back (device/DB exfiltration shouldn't hand over passwords).
 */
internal fun passwordFingerprint(plainPassword: String): String {
    val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(salt + plainPassword.toByteArray(Charsets.UTF_8))
    return "sha256:" + salt.toHex() + ":" + digest.toHex()
}

private const val SALT_BYTES = 16

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    String.format(Locale.ROOT, "%02x", byte)
}

/** Error text a tile can show: names the failure mode before the detail. */
private fun Throwable.describeFor(type: StreamType): String {
    val what = when (this) {
        is IOException -> "Network problem talking to the panel"
        is SerializationException -> "Panel sent a response we couldn't parse"
        else -> "Refresh failed"
    }
    return "${type.label}: $what — ${message ?: javaClass.simpleName}"
}
