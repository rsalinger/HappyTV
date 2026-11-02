package com.rysal.happytv.data

import android.content.Context
import com.rysal.happytv.BuildConfig
import com.rysal.happytv.data.guide.GuideRepository
import com.rysal.happytv.data.guide.HttpXmlTvFetcher
import com.rysal.happytv.data.network.RefreshCoordinator
import com.rysal.happytv.data.network.buildXtreamApi
import com.rysal.happytv.security.CleartextConsentStore
import com.rysal.happytv.security.PrefsCleartextConsentStore
import com.rysal.happytv.security.SecuredCredentialsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Manual dependency graph for HappyTV v1 — the composition root.
 *
 * Every process-wide singleton the app needs is built here on first access.
 * `HappyTvApp.onCreate` calls [init] with the Application context (that is the
 * entirety of v1's "DI framework": no Hilt, no Koin, no reflection).
 *
 * Conventions for layers added later:
 *  - Add their singleton as one clearly-marked `by lazy` block below, taking
 *    only [requireContext]-style inputs — never an Activity.
 *  - Or, when the type doesn't exist in this layer yet (a Retrofit interface
 *    owned by `data/network`), register the factory through the seam provided
 *    (see [installXtreamApi]) so this file keeps compiling independently.
 *
 * Swap to Hilt in v2 if needed: each `by lazy` becomes a `@Singleton` binding,
 * [init] disappears behind `@HiltAndroidApp`, and call sites swap
 * `Graph.foo` for `@Inject lateinit var foo`. Keeping construction *only* in
 * this file is what makes that mechanical.
 */
object Graph {

    @Volatile
    private var applicationContext: Context? = null

    private val initLock = Any()

    /**
     * Idempotent — safe to call again, first caller wins. Must run before any
     * other member is touched; members resolve the context lazily so [HappyTvApp]
     * can call this without paying startup cost for the database.
     *
     * Only the Application context is retained, so the graph can never leak an
     * Activity into a static field.
     */
    fun init(context: Context) {
        if (applicationContext != null) return
        synchronized(initLock) {
            if (applicationContext == null) {
                applicationContext = context.applicationContext
            }
        }
    }

    private fun requireContext(): Context =
        applicationContext ?: error(
            "Graph.init(context) was never called. HappyTvApp.onCreate must call " +
                "Graph.init(this) before anything touches Graph.",
        )

    /**
     * The Application context, for callers that must hand a [Context] to a
     * framework API (WorkManager rescheduling from Settings). Same guard as
     * [requireContext], just not private.
     */
    val appContext: Context
        get() = requireContext()

    // ---------------------------------------------------------------- database

    /**
     * Room. Version 1 schema; queries must stay off the main thread (Room
     * enforces this — there is deliberately no `allowMainThreadQueries()`).
     */
    val database: HappyTvDatabase by lazy {
        HappyTvDatabase.build(requireContext()).also { HappyTvDatabase.pinInvalidationTriggers(it) }
    }

    // --------------------------------------------------------------- networking

    /**
     * Shared OkHttp client for every Xtream call.
     *
     * Timeouts are generous because IPTV panels are slow, and there is
     * deliberately **no global retry** — panels flake, and the caller (refresh
     * coordinator) decides what is worth retrying. Debug builds log bodies to
     * logcat; note the protocol puts `username`/`password` in the query string,
     * so those appear in debug logs only.
     */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    // #17: BASIC only (request line + status), through the redactor,
                    // because the API puts username and password in the query string.
                    addInterceptor(
                        HttpLoggingInterceptor { message ->
                            android.util.Log.d(
                                "HtvHttp",
                                com.rysal.happytv.ui.player.PlayerUrlBuilder.redactCredentials(message),
                            )
                        }.apply { level = HttpLoggingInterceptor.Level.BASIC },
                    )
                }
            }
            .build()
    }

    /**
     * Our one JSON stack: kotlinx.serialization (Moshi was the alternative,
     * but the wire DTOs are `@Serializable` — one mapper, not two).
     *
     * `ignoreUnknownKeys` + `coerceInputValues`: panels add, drop and mistype
     * fields at will; a new upstream field must never crash a stored parse.
     */
    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }

    // Note: `XtreamApi` itself is constructed by the network layer — see the
    // [installXtreamApi] seam at the bottom of this file.

    // ---------------------------------------------------------------- settings

    /** DataStore-backed user settings (refresh toggles, EPG window, format…). */
    val settings: SettingsRepository by lazy {
        // #18: a typed XMLTV link is kept in the encrypted credentials store.
        SettingsRepository(requireContext().topLevelDataStore, epgOverrideStore = securedCredentials)
    }

    /**
     * Application-wide scope for work that must outlive the screen that asked
     * for it: Settings' "Refresh everything now" (which returns to the
     * dashboard while three catalog downloads keep running) and the sign-out
     * wipe. Supervisored so one failing child never kills the scope.
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    // ---------------------------------------------------------- credentials

    /**
     * Encrypted home of the Xtream credential set — the ONLY place a password
     * is stored in the clear (behind AES256_GCM); see [SecuredCredentialsStore].
     *
     * Construction is a no-op; the Keystore work happens inside the first
     * suspend call. Login flow: `Graph.securedCredentials.save(...)`, app start:
     * `Graph.securedCredentials.current()`.
     */
    val securedCredentials: SecuredCredentialsStore by lazy {
        SecuredCredentialsStore.create(requireContext())
    }

    /**
     * The "This provider doesn't use encryption" sheet's per-server memory
     * (design spec §"Sign in > Unencrypted-server sheet"). Added by the login
     * task; like [securedCredentials] it is cheap to construct and does its
     * I/O on the first suspend call.
     */
    val cleartextConsent: CleartextConsentStore by lazy {
        PrefsCleartextConsentStore.create(requireContext())
    }

    // ----------------------------------------------------- xtreamApi seam

    /**
     * `XtreamApi` is a Retrofit interface owned by `data/network/` — that type
     * does not exist while this file is first compiled, so instead of a typed
     * `val xtreamApi` the network layer registers its factory once:
     *
     * ```kotlin
     * Graph.installXtreamApi {
     *     Retrofit.Builder()
     *         .client(Graph.okHttpClient)
     *         .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
     *         .baseUrl("http://localhost/")            // dynamic per-request @Url
     *         .build()
     *         .create(XtreamApi::class.java)
     * }
     * ```
     *
     * Consumers then get a type-checked singleton:
     * `val api = Graph.xtreamApi<XtreamApi>()`.
     *
     * First access builds it; accessing before registration fails fast with a
     * message naming this function rather than an NPE somewhere in the UI.
     * Swap to Hilt in v2: delete this seam and bind `XtreamApi` with `@Binds`.
     */
    private val xtreamApiLock = Any()

    @Volatile
    private var xtreamApiFactory: (() -> Any)? = null

    @Volatile
    private var xtreamApiInstance: Any? = null

    /** Installs (or replaces) the network layer's API client factory. */
    fun installXtreamApi(factory: () -> Any) {
        synchronized(xtreamApiLock) {
            xtreamApiFactory = factory
            xtreamApiInstance = null
        }
    }

    /** Type-checked access to the registered [installXtreamApi] singleton. */
    fun <T : Any> xtreamApi(type: Class<T>): T {
        // Resolve the singleton under the lock; the type check happens after,
        // so a mis-registered factory fails here with a ClassCastException
        // instead of corrupting state inside the critical section.
        val instance: Any = synchronized(xtreamApiLock) {
            xtreamApiInstance
                ?: (xtreamApiFactory?.invoke() ?: error(
                    "Graph.xtreamApi is not wired yet: the network layer must call " +
                        "Graph.installXtreamApi { … } once at startup (see Graph.installXtreamApi docs).",
                )).also { xtreamApiInstance = it }
        }
        // `Class.cast` is a *runtime* type check — a factory registered for the
        // wrong interface fails here, not later inside unrelated UI code.
        return type.cast(instance)!!
    }

    /** Reified sugar: `Graph.xtreamApi<XtreamApi>()`. Same singleton, checked cast. */
    internal inline fun <reified T : Any> xtreamApi(): T = xtreamApi(T::class.java)

    // ---------------------------------------------- xtream network (data/network)

    /**
     * The Retrofit-backed `XtreamApi` now exists (data/network), so the
     * factory the seam above talks about is registered here, at graph load:
     * `Graph.xtreamApi<XtreamApi>()` then works from any startup path —
     * login, refresh-on-start, EPG — with no per-call wiring. The lambda only
     * *stores* the factory; Retrofit is built on first access, so startup cost
     * stays zero for code paths that never touch the network.
     */
    init {
        installXtreamApi { buildXtreamApi(okHttpClient, json) }
    }

    /**
     * Login + the 3-tile refresh pump. The dashboard collects
     * [RefreshCoordinator.progress] (per-tile `Idle | Loading(n/3) | Done |
     * Error`), login screens call [RefreshCoordinator.login], refresh-on-start
     * calls [RefreshCoordinator.refreshAll]. Built lazily like everything
     * else here; tests construct it directly with their own fakes.
     */
    val refreshCoordinator: RefreshCoordinator by lazy {
        RefreshCoordinator(
            database = database,
            settings = settings,
            credentials = securedCredentials,
            bulk = BackgroundWork.bulk,
            afterSignIn = { householdBackup.importIfPending() },
        )
    }

    /** #19: the household backup file, its export and its one-time restore import. */
    val householdBackup: com.rysal.happytv.backup.HouseholdBackup by lazy {
        com.rysal.happytv.backup.HouseholdBackup(database, settings, requireContext().filesDir)
    }

    // --------------------------------------------------------------- guide

    /**
     * TV guide data access (data/guide) — provider + XMLTV sources over
     * the one `epg_programs` store, plus the favorite/recent/category
     * reads the guide rows need. Session lookups go through
     * [refreshCoordinator]'s repository (credentials → XtreamRepository),
     * so the guide never builds its own client wiring.
     */
    val guideRepository: GuideRepository by lazy {
        GuideRepository(
            epgDao = database.epgProgramDao(),
            liveDao = database.liveStreamDao(),
            categoryDao = database.categoryDao(),
            favoriteDao = database.favoriteDao(),
            recentDao = database.recentChannelDao(),
            settings = settings,
            session = { refreshCoordinator.currentRepository() },
            xmlTvFetcher = HttpXmlTvFetcher(requireContext()),
            // F-47/F-52: derive the panel's xmltv.php URL on the cache-miss
            // path from the saved Xtream credentials. Suspending through
            // SecuredCredentialsStore.current() keeps the Keystore / disk
            // read on Dispatchers.IO (see SecuredCredentialsStore docstring).
            credentials = { securedCredentials.current() },
            bulkDispatcher = BackgroundWork.bulk,
        )
    }
}
