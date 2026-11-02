package com.rysal.happytv.ui.browse

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-67: the Shows dashboard tile opened a screen titled **"Movies"**.
 *
 * The graph was wired correctly end to end — `ShowsActivity` was genuinely on
 * top of the task, `startRoute()` handed `"catalog/shows"` to the `NavHost`,
 * and the `categories/{type}` destination matched. But Compose Navigation only
 * parses a path argument out of the route *string* when the route is reached
 * via `navigate()`. A **start destination** is matched by route pattern and its
 * arguments come from each `navArgument`'s `defaultValue`, so
 * `arguments["type"]` came back **null for both catalogs** — and
 * `catalogFrom`'s `?: CatalogRoute.MOVIES` turned that null into a perfectly
 * normal-looking Movies screen.
 *
 * Two things had to break for this to reach a TV: the start destination lost
 * its `{type}` value, and the resulting null was silently defaulted. Movies
 * only ever looked right because its fallback happened to match the catalog it
 * wanted — the same broken code path was underneath it.
 *
 * These tests drive the real `NavHostController` the app uses, so a regression
 * in argument binding is caught here rather than on a dongle.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BrowseCatalogRouteTest {

    // ------------------------------------------------------- the framework itself

    /**
     * The graph [BrowseNavHost] declares, rebuilt here against a bare
     * controller so assertions can read the start entry's resolved arguments.
     * The argument declarations mirror `stringArgument(name, startRoute)` —
     * that default is the mechanism under test, so it is reproduced exactly.
     */
    private fun browseGraph(
        navController: NavHostController,
        startRoute: BrowseStartRoute,
    ): NavGraph = navController.createGraph(startRoute.route) {
        composable(BrowseRoutes.LIVE_TV) {}
        composable(BrowseRoutes.CATALOG, arguments = listOf(stringArgument(TYPE_ARG, startRoute))) {}
        composable(
            route = BrowseRoutes.DETAILS,
            arguments = listOf(
                stringArgument(TYPE_ARG, startRoute),
                stringArgument(STREAM_ID_ARG, startRoute),
            ),
        ) {}
    }

    private fun controller(): NavHostController {
        val navController = NavHostController(ApplicationProvider.getApplicationContext())
        // NavHost() registers this navigator itself; doing it by hand is what
        // lets this test drive the graph without composing anything.
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        return navController
    }

    /** Resolves [startRoute] the way `NavHost` does and reads back [arg]. */
    private fun resolvedArg(startRoute: BrowseStartRoute, arg: String = TYPE_ARG): String? {
        val navController = controller()
        navController.setGraph(browseGraph(navController, startRoute), startRoute.arguments)
        // The route must have resolved to the parameterized destination, or the
        // argument read below would be meaningless.
        assertEquals(
            "start route ${startRoute.route} did not resolve to its destination",
            startRoute.route,
            navController.currentBackStackEntry?.destination?.route,
        )
        return navController.currentBackStackEntry?.arguments?.getString(arg)
    }

    // ------------------------------------------------- AC1: the reported symptom

    @Test
    fun `the Shows start route resolves the type argument to shows`() {
        // The exact regression: OK on the Shows tile → ShowsActivity →
        // startRoute(CatalogRoute.SHOWS, intent) → catalogStartRoute(SHOWS).
        assertEquals(
            CatalogRoute.SHOWS.route,
            resolvedArg(catalogStartRoute(CatalogRoute.SHOWS)),
        )
    }

    @Test
    fun `the Movies start route resolves the type argument to movies`() {
        assertEquals(
            CatalogRoute.MOVIES.route,
            resolvedArg(catalogStartRoute(CatalogRoute.MOVIES)),
        )
    }

    /**
     * What the bug actually was, pinned so the mechanism cannot be quietly
     * reintroduced: naming a concrete route as a start destination matches the
     * destination but leaves `{type}` null, because nothing parses the path.
     * `BrowseStartRoute` exists precisely so this shape never reaches
     * `NavHost` again.
     */
    @Test
    fun `a concrete start destination route still loses its type argument`() {
        val navController = controller()
        val graph = navController.createGraph("catalog/shows") {
            composable(
                route = BrowseRoutes.CATALOG,
                arguments = listOf(navArgument(TYPE_ARG) { type = NavType.StringType }),
            ) {}
        }
        navController.graph = graph
        assertEquals(
            BrowseRoutes.CATALOG,
            navController.currentBackStackEntry?.destination?.route,
        )
        assertNull(
            "path arguments are only bound by navigate(), never parsed out of a " +
                "start destination route — that is why the old code needed a " +
                "silent MOVIES fallback",
            navController.currentBackStackEntry?.arguments?.getString(TYPE_ARG),
        )
    }

    // --------------------------------------------- AC3: route → catalog mapping

    @Test
    fun `each start route carries its own catalog through the framework`() {
        val starts = listOf(
            BrowseRoutes.CATALOG to catalogStartRoute(CatalogRoute.SHOWS),
            BrowseRoutes.DETAILS to detailsStartRoute(CatalogRoute.SHOWS, "s1"),
        )
        starts.forEach { (expectedRoute, startRoute) ->
            assertEquals(expectedRoute, startRoute.route)
            assertEquals(
                "start route ${startRoute.route} lost its {type}",
                CatalogRoute.SHOWS.route,
                resolvedArg(startRoute),
            )
        }
    }

    @Test
    fun `each start route carries its own catalog for movies too`() {
        listOf(
            catalogStartRoute(CatalogRoute.MOVIES),
            detailsStartRoute(CatalogRoute.MOVIES, "m1"),
        ).forEach { startRoute ->
            assertEquals(CatalogRoute.MOVIES.route, resolvedArg(startRoute))
        }
    }

    @Test
    fun `the details start route keeps its streamId`() {
        assertEquals(
            "show-42",
            resolvedArg(detailsStartRoute(CatalogRoute.SHOWS, "show-42"), STREAM_ID_ARG),
        )
    }

    /**
     * A concrete `navigate()` must still override the default, otherwise fixing
     * the start destination would have broken every in-graph jump (categories →
     * details).
     */
    @Test
    fun `navigate overrides the start-destination default`() {
        val navController = controller()
        navController.setGraph(
            browseGraph(navController, catalogStartRoute(CatalogRoute.SHOWS)),
            Bundle(),
        )
        navController.navigate(BrowseRoutes.catalog(CatalogRoute.MOVIES))
        assertEquals(
            CatalogRoute.MOVIES.route,
            navController.currentBackStackEntry?.arguments?.getString(TYPE_ARG),
        )
    }

    @Test
    fun `Live TV takes the unparameterized route with no arguments`() {
        val startRoute = BrowseStartRoute.liveTv()
        assertEquals(BrowseRoutes.LIVE_TV, startRoute.route)
        assertTrue("live_tv has no path arguments", startRoute.arguments.isEmpty)
        assertNull(startRoute.catalog)
    }

    // ---------------------------------------- AC2: no silent wrong-catalog default

    /**
     * The reported *root cause*: `catalogFrom` ending in
     * `?: CatalogRoute.MOVIES`. Any unresolved `type` — a lost argument, a typo
     * in a route string — rendered the wrong catalog with no crash and no log,
     * which is what let this survive four QA passes. It now throws, and this
     * pins that a missing `type` can no longer resolve to MOVIES.
     */
    @Test
    fun `an unresolved type argument is loud, never a default catalog`() {
        val failure = runCatching { catalogFrom(null) }.exceptionOrNull()
        assertTrue(
            "an unresolved {type} must throw instead of defaulting to MOVIES",
            failure is IllegalStateException,
        )
        assertTrue(
            "the message should name the offending type so the slip is diagnosable",
            failure?.message?.contains("null") == true,
        )
    }

    @Test
    fun `an unknown type segment is loud too`() {
        val failure = runCatching { catalogFrom("films") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("films") == true)
    }

    @Test
    fun `the known type segments still resolve to their catalogs`() {
        assertEquals(CatalogRoute.SHOWS, catalogFrom("shows"))
        assertEquals(CatalogRoute.MOVIES, catalogFrom("movies"))
    }

    // ------------------------------------ the activities' own start-route choice

    @Test
    fun `Search's shows deep link lands on the shows details route`() {
        val intent = Intent().putExtra(MoviesActivity.EXTRA_DETAILS_ID, "show-7")
        val startRoute = startRoute(CatalogRoute.SHOWS, intent)
        assertEquals(BrowseRoutes.DETAILS, startRoute.route)
        assertEquals("show-7", startRoute.arguments.getString(STREAM_ID_ARG))
        assertEquals("shows", startRoute.arguments.getString(TYPE_ARG))
    }

    @Test
    fun `Search's shows deep link survives the framework too`() {
        val intent = Intent().putExtra(MoviesActivity.EXTRA_DETAILS_ID, "show-7")
        assertEquals("shows", resolvedArg(startRoute(CatalogRoute.SHOWS, intent)))
        assertEquals(
            "show-7",
            resolvedArg(startRoute(CatalogRoute.SHOWS, intent), STREAM_ID_ARG),
        )
    }

    @Test
    fun `a plain tile launch lands on the categories route for its own catalog`() {
        val startRoute = startRoute(CatalogRoute.SHOWS, Intent())
        assertEquals(BrowseRoutes.CATALOG, startRoute.route)
        assertEquals("shows", startRoute.arguments.getString(TYPE_ARG))
    }

    @Test
    fun `a blank details id is treated as no deep link`() {
        val intent = Intent().putExtra(MoviesActivity.EXTRA_DETAILS_ID, "")
        assertEquals(BrowseRoutes.CATALOG, startRoute(CatalogRoute.MOVIES, intent).route)
    }

    @Test
    fun `the start route's catalog is readable for both tiles`() {
        assertEquals(CatalogRoute.SHOWS, catalogStartRoute(CatalogRoute.SHOWS).catalog)
        assertEquals(CatalogRoute.MOVIES, catalogStartRoute(CatalogRoute.MOVIES).catalog)
    }
}