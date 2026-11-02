package com.rysal.happytv.ui.catalog

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.browse.GenreTag
import com.rysal.happytv.ui.browse.HtPrimaryButton
import com.rysal.happytv.ui.browse.HtSecondaryButton
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Details layout pieces stay whole and inside the safe area (#79). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DetailsSafeAreaTest {

    @get:Rule
    val compose = createComposeRule()

    /** Only nodes the layout actually placed: composed-but-unplaced content draws nothing. */
    private fun count(text: String) =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().count { it.layoutInfo.isPlaced }

    @Test
    fun favoriteLabelIsNotCutInTheNarrowTextColumn() {
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                val d = browseDesign()
                Column {
                    // The 720 px text column is narrower than both buttons need; then a wide one.
                    listOf(720, 1700).forEach { width ->
                        Column(Modifier.size(width = d.px(width), height = d.px(120))) {
                            Row(Modifier.fitContentWidth(), horizontalArrangement = Arrangement.spacedBy(d.px(24))) {
                                HtPrimaryButton(label = "Play first episode", onClick = {})
                                HtSecondaryButton(label = "Add to favorites", onClick = {})
                            }
                        }
                    }
                }
            } }
        }
        compose.waitForIdle()
        val nodes = compose.onAllNodes(hasText("Add to favorites")).fetchSemanticsNodes()
        assertEquals(2, nodes.size)
        assertTrue("label has width", nodes[0].boundsInRoot.width > 0f)
        assertEquals(nodes[1].boundsInRoot.width, nodes[0].boundsInRoot.width, 0.5f)
    }

    @Test
    fun onlyWholeGenreChipsAreShown() {
        val genres = listOf("Adventure", "Documentary", "Drama", "Comedy")
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                val d = browseDesign()
                Column(Modifier.size(width = d.px(260), height = d.px(200))) {
                    FittingChipRow(spacing = d.px(12)) { genres.forEach { GenreTag(it) } }
                }
            } }
        }
        compose.waitForIdle()
        val shown = genres.filter { count(it) > 0 }
        assertTrue("at least one chip fits", shown.isNotEmpty())
        assertTrue("not all four fit", shown.size < 4)
        assertEquals("a prefix of the list, in order", genres.take(shown.size), shown)
    }

    @Test
    fun castDropsWhenItCannotFit() {
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                val d = browseDesign()
                Column(Modifier.size(width = d.px(720), height = d.px(300))) {
                    Spacer(Modifier.height(d.px(100)))
                    DropIfItDoesNotFit { Text("Cast row", Modifier.height(d.px(400))) }
                }
            } }
        }
        compose.waitForIdle()
        assertEquals(0, count("Cast row"))
    }

    @Test
    fun castShowsWhenThereIsRoom() {
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                val d = browseDesign()
                Column(Modifier.size(width = d.px(720), height = d.px(700))) {
                    Spacer(Modifier.height(d.px(100)))
                    DropIfItDoesNotFit { Text("Cast row", Modifier.height(d.px(400))) }
                }
            } }
        }
        compose.waitForIdle()
        assertEquals(1, count("Cast row"))
    }
}
