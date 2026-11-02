package com.rysal.happytv.ui.browse

import com.rysal.happytv.data.entity.LiveStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [channelPaneContent] — F-46's loading-state mapping, without Compose.
 *
 * The rule the finding demands: while the first channel list is in flight the
 * pane shows the skeleton, and the "No channels to show in …" copy may only
 * appear once loading has resolved to a genuinely empty selection. The bug
 * this pins down: the pane used to draw the empty state off `channels.isEmpty()`,
 * which is also true of every state *before* the catalog answers — so a
 * 39 361-channel panel showed "No channels to show in All channels" for ~5 s.
 */
class ChannelPaneContentTest {

    private fun row(id: String) = LiveChannelRow(
        stream = LiveStream(id = id, categoryId = "news", num = 1, name = "Channel $id"),
    )

    @Test
    fun `a loading state maps to the skeleton, never the empty copy`() {
        val content = channelPaneContent(LiveTvUiState(isLoading = true))
        assertEquals(ChannelPaneContent.Loading, content)
    }

    @Test
    fun `loading wins even when the state also reports hidden channels`() {
        // The empty-state mapping must not fire while data is in flight, or the
        // finding's flash comes back through the hidden-count variant.
        val content = channelPaneContent(
            LiveTvUiState(isLoading = true, hiddenChannelCount = 2),
        )
        assertEquals(ChannelPaneContent.Loading, content)
    }

    @Test
    fun `rows win over the loading flag`() {
        val rows = listOf(row("1"))
        val content = channelPaneContent(LiveTvUiState(isLoading = true, channels = rows))
        assertEquals(ChannelPaneContent.Rows(rows), content)
    }

    @Test
    fun `an empty catalog resolves to the generic empty state, not an endless skeleton`() {
        val content = channelPaneContent(LiveTvUiState(isLoading = false))
        assertEquals(ChannelPaneContent.Empty.NothingHere, content)
    }

    @Test
    fun `favorites with nothing starred map to the favorites empty state`() {
        val content = channelPaneContent(
            LiveTvUiState(selectedSelection = LiveCategorySelection.Favorites),
        )
        assertEquals(ChannelPaneContent.Empty.NoFavorites, content)
    }

    @Test
    fun `a selection whose channels are all hidden maps to the hidden empty state`() {
        val content = channelPaneContent(
            LiveTvUiState(
                selectedSelection = LiveCategorySelection.Category("news", "News"),
                hiddenChannelCount = 3,
            ),
        )
        assertEquals(ChannelPaneContent.Empty.AllHidden(3), content)
    }

    /**
     * F-53 regression guard: the F-46 replay folded the empty-state dispatch
     * from a state probe onto this sealed model, and dropping the
     * recently-watched case would have silently deleted the "No recently
     * watched channels" state that the RC already ships.
     */
    @Test
    fun `recently watched with nothing watched maps to its own empty state`() {
        val content = channelPaneContent(
            LiveTvUiState(selectedSelection = LiveCategorySelection.RecentlyWatched),
        )
        assertEquals(ChannelPaneContent.Empty.RecentlyWatched, content)
    }

    @Test
    fun `recently watched keeps its empty state even with hidden channels`() {
        // Ordering: the selection-specific state wins over the hidden count,
        // the same way favorites does — otherwise switching to a fully hidden
        // recent list would read "N are hidden" over a selection that has
        // simply never been watched.
        val content = channelPaneContent(
            LiveTvUiState(
                selectedSelection = LiveCategorySelection.RecentlyWatched,
                hiddenChannelCount = 2,
            ),
        )
        assertEquals(ChannelPaneContent.Empty.RecentlyWatched, content)
    }

    @Test
    fun `recently watched rows win over the loading skeleton`() {
        val rows = listOf(row("9"))
        val content = channelPaneContent(
            LiveTvUiState(
                selectedSelection = LiveCategorySelection.RecentlyWatched,
                isLoading = true,
                channels = rows,
            ),
        )
        assertEquals(ChannelPaneContent.Rows(rows), content)
    }
}
