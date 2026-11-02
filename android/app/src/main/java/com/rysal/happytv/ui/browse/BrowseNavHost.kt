package com.rysal.happytv.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rysal.happytv.data.LiveTvView
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.settings.EXTRA_SETTINGS_SECTION
import com.rysal.happytv.ui.settings.SECTION_GUIDE
import com.rysal.happytv.ui.settings.SettingsActivity
import com.rysal.happytv.ui.catalog.CatalogViewModel
import com.rysal.happytv.ui.catalog.CatalogScreen
import com.rysal.happytv.ui.catalog.DetailsScreen
import com.rysal.happytv.ui.catalog.ResumePoint
import com.rysal.happytv.ui.player.PlayerActivity
import com.rysal.happytv.ui.player.PlayerLaunch
import com.rysal.happytv.ui.player.PlayerLaunch.Companion.put
import com.rysal.happytv.ui.search.SearchActivity
import com.rysal.happytv.ui.search.SearchScope

/**
 * The browse route graph (design spec's three browse screens, wired as the
 * task spec asks: one Compose NavHost, no Leanback fragments):
 *
 *  - `live_tv` — the combined Live TV screen
 *  - `catalog/{type}` — Movies or Shows: the category rail and the poster grid on one screen
 *  - `details/{type}/{streamId}` — the details page
 *
 * `search/{type?}/{categoryId?}` deliberately is **not** here: Search is a
 * modal overlay owned by the settings/polish task (t_7d608d3c), and every
 * Search pill in these headers hands back to its host through `onSearchClick`.
 *
 * Each of the three entry activities ([LiveTvActivity], [MoviesActivity],
 * [ShowsActivity]) hosts this same graph with a different start destination,
 * so the dashboard's existing tile intents keep working unchanged.
 *
 * @param onRefreshLive the ⋮ menu's "Refresh channels"; the activity owns it
 *   because the refresh coordinator lives on the application scope.
 * @param onRefreshCatalog the categories screen's ⋮ "Refresh".
 */
@Composable
fun BrowseNavHost(
    startRoute: BrowseStartRoute,
    onExit: () -> Unit,
    onRefreshLive: () -> Unit = {},
    onRefreshCatalog: (StreamType) -> Unit = {},
    /**
     * F-67: the catalog screens' ViewModel seam, threaded down so the graph can
     * be rendered against a test database. Defaults to the real
     * `CatalogViewModel.Factory` per catalog, exactly as before; the tests
     * pass an in-memory Room factory instead.
     */
    catalogViewModelFactory: (StreamType) -> ViewModelProvider.Factory? = { null },
    /**
     * Spec #7 section 3.6: the dashboard's TV guide tile opens Live TV in Guide
     * view whatever was used last; null follows the remembered view.
     */
    liveTvStartView: LiveTvView? = null,
    /** The Live TV activity's player host (#16); null plays live in the player screen as before. */
    livePlayback: LivePlayback? = null,
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    NavHost(
        navController = navController,
        // F-67: always the route *template*. Compose Navigation binds path
        // arguments out of the route string only on `navigate()` — a start
        // destination never parses them, so `startDestination =
        // "catalog/shows"` used to leave `{type}` null and `catalogFrom`
        // silently rendered the MOVIES catalog. The values for the start entry
        // come from [startRoute] as `navArgument` defaults instead, which is
        // why every `{type}` destination below takes its default from there.
        startDestination = startRoute.route,
    ) {
        composable(BrowseRoutes.LIVE_TV) {
            LiveTvScreen(
                onBack = { navController.exit(onExit) },
                onSearchClick = { context.openSearch(SearchScope.CHANNELS) },
                onPlayChannel = { stream ->
                    if (livePlayback != null) livePlayback.onPlayChannel(stream.id) else context.playContent(stream.id, "live")
                },
                onWatchFromStart = { stream ->
                    // A channel with no guide row has no program start to rewind
                    // to: it opens live (a program replay goes through onPlayReplay).
                    if (livePlayback != null) livePlayback.onPlayChannel(stream.id) else context.playContent(stream.id, "live")
                },
                playback = livePlayback,
                onRefreshChannels = onRefreshLive,
                startView = liveTvStartView,
                onPlayReplay = { streamId, startUtcMillis, durationMinutes ->
                    context.playReplay(streamId, startUtcMillis, durationMinutes)
                },
                onOpenGuideSettings = { context.openGuideSettings() },
            )
        }

        composable(
            route = BrowseRoutes.CATALOG,
            arguments = listOf(stringArgument(TYPE_ARG, startRoute)),
        ) { backStack ->
            val catalog = catalogFrom(backStack.arguments?.getString(TYPE_ARG))
            CatalogScreen(
                streamType = catalog.streamType,
                viewModelFactory = catalogViewModelFactory(catalog.streamType)
                    ?: CatalogViewModel.Factory(catalog.streamType, withRail = true),
                onBack = { navController.exit(onExit) },
                onSearchClick = { context.openSearch(catalog.searchScope()) },
                onItemClick = { item ->
                    navController.navigate(BrowseRoutes.details(catalog, item.id))
                },
                onRefresh = { onRefreshCatalog(catalog.streamType) },
            )
        }

        composable(
            route = BrowseRoutes.DETAILS,
            arguments = listOf(
                stringArgument(TYPE_ARG, startRoute),
                stringArgument(STREAM_ID_ARG, startRoute),
            ),
        ) { backStack ->
            val catalog = catalogFrom(backStack.arguments?.getString(TYPE_ARG))
            val streamId = backStack.arguments?.getString(STREAM_ID_ARG).orEmpty()
            DetailsScreen(
                streamType = catalog.streamType,
                streamId = streamId,
                onBack = { navController.exit(onExit) },
                onPlay = { detail, resume ->
                    context.playContent(
                        streamId = detail.id,
                        contentType = if (catalog.streamType == StreamType.VOD) "vod" else "series",
                        resume = resume,
                    )
                },
            )
        }
    }
}

/** Back at the graph's root leaves the screen; deeper, it walks one step back. */
private fun androidx.navigation.NavHostController.exit(onExit: () -> Unit) {
    if (!navigateUp()) onExit()
}

/**
 * A path argument, defaulted to the start destination's value for it.
 *
 * F-67: the `defaultValue` is what makes the **start destination** resolve.
 * Compose Navigation parses a path segment out of the route string only for
 * `navigate()`; the start destination is matched by route pattern and then
 * filled from these defaults. Without it the start entry's `arguments["type"]`
 * is null, and `catalogFrom` used to turn that null into MOVIES — which is how
 * OK on the Shows tile produced a screen titled "Movies", and how Search's
 * deep link into a show lost its `{streamId}`.
 *
 * A `navigate()` to a concrete route (`catalog/shows`) still overrides the
 * default, so the in-graph jumps are unaffected.
 *
 * An argument the start route does not supply (every `{type}` destination
 * under [BrowseStartRoute.liveTv], say) stays nullable rather than defaulting
 * to null — a String argument may not be both non-nullable and null-valued,
 * and throwing from `catalogFrom` on a genuinely absent argument is exactly
 * the loud failure F-67 asked for.
 */
internal fun stringArgument(name: String, startRoute: BrowseStartRoute) = navArgument(name) {
    type = NavType.StringType
    val startValue = startRoute.arguments.getString(name)
    if (startValue != null) {
        defaultValue = startValue
    } else {
        nullable = true
    }
}

/**
 * The `{type}` path segment → catalog.
 *
 * F-67: this used to end in `?: CatalogRoute.MOVIES`, so *any* routing slip
 * rendered the wrong catalog and nothing logged. That silent fallback is why
 * the Shows tile's wrong catalog survived four QA passes — and it hid the
 * fact that MoviesActivity was only ever correct by coincidence. There is no
 * sensible default here: a missing or unknown `type` means the route and the
 * argument bundle disagree, which is a programming error, so it throws.
 */
internal fun catalogFrom(route: String?): CatalogRoute =
    CatalogRoute.values().firstOrNull { it.route == route }
        ?: error(
            "No CatalogRoute for type=${route?.let { "\"$it\"" } ?: "null"}. " +
                "Every '{type}' route must be reached either through navigate() " +
                "(BrowseRoutes.catalog/details, which encode the " +
                "catalog in the path) or as a start destination hosted by a " +
                "catalog activity, which supplies the value as the navArgument " +
                "default — see BrowseStartRoute. See F-67.",
        )

/**
 * The graph's entry point: a route **template** plus the values for its path
 * arguments.
 *
 * F-67: Compose Navigation binds `{type}` / `{categoryId}` / `{streamId}` out of
 * a route *string* only when the route is reached through `navigate()`. A
 * **start destination** never has its path parsed — `NavHost(startDestination =
 * "catalog/shows")` matches the `catalog/{type}` destination and leaves
 * `arguments["type"] == null`. That null was then swallowed by `catalogFrom`'s
 * `?: CatalogRoute.MOVIES`, so the Shows tile rendered the **Movies** catalog
 * with `ShowsActivity` correctly on top of the task. Movies only ever looked
 * right because its fallback happened to match; Search's deep link into a
 * *show* lost `{streamId}` the same way and opened a movie-details page with an
 * empty id.
 *
 * So the start entry's argument values ride along here and reach the
 * destinations as each `navArgument`'s `defaultValue` (see [stringArgument]).
 * A later `navigate()` still overrides them, so in-graph jumps are unaffected.
 *
 * @param route the matching `BrowseRoutes` template, e.g. `catalog/{type}`
 * @param arguments the path-argument values, keyed by placeholder name.
 */
data class BrowseStartRoute(val route: String, val arguments: Bundle) {
    /** The catalog this host browses; null for Live TV / an unparameterized route. */
    val catalog: CatalogRoute?
        get() = arguments.getString(TYPE_ARG)?.let { value ->
            CatalogRoute.values().firstOrNull { it.route == value }
        }

    companion object {
        /** Live TV takes no path arguments. */
        fun liveTv() = BrowseStartRoute(BrowseRoutes.LIVE_TV, Bundle())
    }
}

/** `catalog/{type}` — the tile's screen. */
fun catalogStartRoute(catalog: CatalogRoute) = BrowseStartRoute(
    route = BrowseRoutes.CATALOG,
    arguments = Bundle().apply { putString(TYPE_ARG, catalog.route) },
)

/** `details/{type}/{streamId}` — Search's deep link into one item. */
fun detailsStartRoute(catalog: CatalogRoute, streamId: String) = BrowseStartRoute(
    route = BrowseRoutes.DETAILS,
    arguments = Bundle().apply {
        putString(TYPE_ARG, catalog.route)
        putString(STREAM_ID_ARG, streamId)
    },
)

/** Route names shared by the three browse activities. */
object BrowseRoutes {
    const val LIVE_TV = "live_tv"
    const val CATALOG = "catalog/{type}"
    const val DETAILS = "details/{type}/{streamId}"

    fun catalog(catalog: CatalogRoute) = "catalog/${catalog.route}"

    fun details(catalog: CatalogRoute, streamId: String) =
        "details/${catalog.route}/$streamId"
}

/** The `{type}` path segment name — shared by all three parameterized routes. */
const val TYPE_ARG = "type"

/** The `{streamId}` path segment name. */
const val STREAM_ID_ARG = "streamId"

/** "movies" / "shows" — the `{type}` path segment. */
enum class CatalogRoute(val route: String, val streamType: StreamType) {
    MOVIES("movies", StreamType.VOD),
    SHOWS("shows", StreamType.SERIES),
    ;

    /** The Search scope this catalog opens the overlay scoped to. */
    fun searchScope(): SearchScope =
        if (streamType == StreamType.VOD) SearchScope.MOVIES else SearchScope.SHOWS
}

/**
 * The Search overlay, scoped to where it was opened from (design spec §"Screens:
 * search": "from Live TV, Movies or Shows it would start scoped to that type").
 */
private fun Context.openSearch(scope: SearchScope) {
    startActivity(
        Intent(this, SearchActivity::class.java).apply {
            putExtra(SearchActivity.EXTRA_SCOPE, scope.name)
        },
    )
}

/**
 * Replay of one program: the player turns `isReplay` + start + duration into the
 * provider's timeshift URL (the guide dialog's "Watch from the start").
 */
private fun Context.playReplay(streamId: String, startUtcMillis: Long, durationMinutes: Int) {
    startActivity(
        Intent(this, PlayerActivity::class.java).apply {
            put(
                PlayerLaunch.replay(
                    streamId = streamId,
                    startUtcMillis = startUtcMillis,
                    durationMinutes = durationMinutes,
                ),
            )
        },
    )
}

/** The empty guide's "Open guide settings": Settings, at the Guide section. */
private fun Context.openGuideSettings() {
    startActivity(
        Intent(this, SettingsActivity::class.java).apply {
            putExtra(EXTRA_SETTINGS_SECTION, SECTION_GUIDE)
        },
    )
}

/**
 * Opens the player for one stream.
 *
 * `contentType` ("live" / "vod" / "series") decides the [StreamType] on the
 * launch contract; the player resolves credentials (and, for movies, the row's
 * `container_extension`) itself, so the browse screens only pass what the row
 * already knows.
 */
private fun Context.playContent(streamId: String, contentType: String, resume: ResumePoint? = null) {
    val streamType = when (contentType) {
        "vod" -> StreamType.VOD
        "series" -> StreamType.SERIES
        else -> StreamType.LIVE
    }
    startActivity(
        Intent(this, PlayerActivity::class.java).apply {
            put(
                PlayerLaunch(
                    streamType = streamType,
                    streamId = streamId,
                    // Resume (#78): the position, and for a show the episode that was in progress.
                    startPositionMillis = resume?.positionMillis ?: 0L,
                    episodeId = resume?.episodeId,
                    startOver = resume?.startOver == true,
                ),
            )
        },
    )
}
