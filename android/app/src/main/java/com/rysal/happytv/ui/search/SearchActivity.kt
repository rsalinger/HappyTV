package com.rysal.happytv.ui.search

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.browse.CatalogRoute
import com.rysal.happytv.ui.browse.LiveTvActivity
import com.rysal.happytv.ui.browse.MoviesActivity
import com.rysal.happytv.ui.browse.ShowsActivity
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme

/**
 * Search host (design spec §"Screens: search").
 *
 * A **modal overlay**, not a screen: the window is translucent (see
 * `Theme.HappyTv.Search`), so the dashboard — or Live TV, Movies or Shows —
 * stays visible behind the overlay's 70% black scrim. Which screen opened it
 * decides the starting scope, passed in [EXTRA_SCOPE]; from the dashboard it
 * searches everything.
 */
class SearchActivity : ComponentActivity() {

    private val startScope: SearchScope by lazy {
        SearchScope.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SCOPE).orEmpty() }
            ?: SearchScope.ALL
    }

    private val viewModel: SearchViewModel by viewModels { SearchViewModel.factory(startScope) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    SearchOverlay(
                        onBack = { finish() },
                        onPlayChannel = ::playChannel,
                        onOpenDetails = ::openDetails,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    /** Channel result card → Live TV, playing that channel full screen. */
    private fun playChannel(streamId: String) {
        // #16: live plays in the Live TV host, so Back lands on the channels with a preview.
        startActivity(LiveTvActivity.playIntent(this, streamId))
    }

    /** Movie / show result poster → that catalog's details page. */
    private fun openDetails(streamId: String, streamType: StreamType) {
        val (activity, route) = when (streamType) {
            StreamType.VOD -> MoviesActivity::class.java to CatalogRoute.MOVIES
            else -> ShowsActivity::class.java to CatalogRoute.SHOWS
        }
        startActivity(
            Intent(this, activity).apply {
                putExtra(EXTRA_DETAILS_ID, streamId)
                putExtra(EXTRA_DETAILS_CATALOG, route.name)
            },
        )
    }

    companion object {
        /** Which scope the overlay opens scoped to — see [SearchScope]. */
        const val EXTRA_SCOPE = "com.rysal.happytv.extra.SEARCH_SCOPE"

        /**
         * Jump straight to a details page instead of the categories list. The
         * names live on [MoviesActivity]'s companion, which
         * [ShowsActivity] shares.
         */
        const val EXTRA_DETAILS_ID = MoviesActivity.EXTRA_DETAILS_ID
        const val EXTRA_DETAILS_CATALOG = MoviesActivity.EXTRA_DETAILS_CATALOG
    }
}
