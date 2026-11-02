package com.rysal.happytv.ui.main

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #176: the About this data view puts focus on Close; its text rows are not stops. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DashboardAboutMenuFocusTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `about view focuses close and closing calls back`() {
        var state by mutableStateOf(DashboardUiState(menuTile = StreamType.LIVE, menuShowingAbout = false))
        var closed = 0
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    TileLongPressMenu(
                        state = state,
                        onRefresh = {},
                        onShowAbout = { state = state.copy(menuShowingAbout = true) },
                        onClose = { closed++ },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Refresh Live TV now").assertIsFocused()

        compose.onNodeWithContentDescription("About this data").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Close").assertIsFocused()
        assertEquals(0, closed)
    }
}
