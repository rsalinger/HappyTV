package com.rysal.happytv.ui.browse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.network.RefreshCoordinator
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.launch

/**
 * Movies destination — one screen with the category rail and the poster grid, then details
 * (spec #72).
 *
 * Both catalog activities host the same [BrowseNavHost] with a different start
 * route, so `catalog/{type}` and
 * `details/{type}/{streamId}` behave identically whichever tile opened them.
 */
class MoviesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = lifecycleScope
        setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    BrowseNavHost(
                        startRoute = startRoute(CatalogRoute.MOVIES, intent),
                        onExit = { finish() },
                        onRefreshCatalog = { type ->
                            scope.launch { Graph.refreshCoordinator.refreshOnly(type) }
                        },
                    )
                }
            }
        }
    }

    companion object {
        /** Search's "open this result's details" deep link (see [SearchActivity]). */
        const val EXTRA_DETAILS_ID = "com.rysal.happytv.extra.DETAILS_ID"
        const val EXTRA_DETAILS_CATALOG = "com.rysal.happytv.extra.DETAILS_CATALOG"
    }
}

/** Shows destination — same graph, `SERIES` catalog. */
class ShowsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = lifecycleScope
        setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    BrowseNavHost(
                        startRoute = startRoute(CatalogRoute.SHOWS, intent),
                        onExit = { finish() },
                        onRefreshCatalog = { type ->
                            scope.launch { Graph.refreshCoordinator.refreshOnly(type) }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The graph's start route: the details page when the caller (Search) passed
 * one, otherwise the catalog screen.
 *
 * F-67: both branches return a [BrowseStartRoute] — the route **template**
 * plus an explicit arguments Bundle — because a Compose NavHost start
 * destination never parses `{type}` out of the route string. Naming the
 * concrete route (`"catalog/shows"`) used to match the destination and
 * leave `type` null, which `catalogFrom` then silently turned into MOVIES.
 */
internal fun startRoute(
    catalog: CatalogRoute,
    intent: android.content.Intent,
): BrowseStartRoute {
    val detailsId = intent.getStringExtra(MoviesActivity.EXTRA_DETAILS_ID)
    return if (detailsId.isNullOrBlank()) {
        catalogStartRoute(catalog)
    } else {
        detailsStartRoute(catalog, detailsId)
    }
}

/**
 * One entry point for "refresh just this catalog" — the coordinator exposes
 * `refreshLiveOnly` / `refreshVodOnly` / `refreshSeriesOnly`.
 */
private suspend fun RefreshCoordinator.refreshOnly(type: StreamType) {
    when (type) {
        StreamType.LIVE -> refreshLiveOnly()
        StreamType.VOD -> refreshVodOnly()
        StreamType.SERIES -> refreshSeriesOnly()
    }
}
