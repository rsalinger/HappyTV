package com.rysal.happytv.ui.guide

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.guide.GuideChannel
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #94: the real [GuideChannelCell], measured. QA found the name still on one line on the TV
 * although `maxLines = 2`: Compose ellipsizes a Text to the lines that fit its max height, and
 * the 56 px row left room for one. The name now gets unbounded height.
 *
 * What this proves: a name with a forced line break gets two lines of height in the real cell
 * (the 1920 x 1080 xhdpi window makes the artboard scale 1), and a one-line name does not.
 * What it does not prove: where the TV's font breaks a long name (Robolectric's text widths are
 * not the device's, so the test uses an explicit newline instead of relying on wrapping), or
 * that the two lines fit the 56 px row: Robolectric gives the first line more than the 26 px line
 * height, so heights here are only compared with each other.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w960dp-h540dp-xhdpi")
class GuideChannelCellTest {

    @get:Rule
    val compose = createComposeRule()

    private fun row(name: String) = GuideRow(
        channel = GuideChannel(
            streamId = "1", num = 18, name = name, genre = null,
            isFavorite = false, hasReplay = false, epgChannelId = null,
        ),
        blocks = emptyList(),
    )

    private val nav = GuideNavigator(
        requesterFor = { FocusRequester() },
        moveFrom = { _, _ -> false },
        focusChanged = { _, _ -> },
        open = { _, _, _ -> },
        play = {},
        longPress = {},
        entryRequester = FocusRequester(),
        menuRequester = FocusRequester(),
    )

    private fun height() =
        compose.onNodeWithTag(CHANNEL_NAME_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot().height.value

    @Test
    fun aLongNameWrapsToTwoLinesAndAShortOneStaysOnOne() {
        val name = mutableStateOf("US: ESPN")
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    GuideChannelCell(
                        row = row(name.value),
                        focus = GuideFocus.ChannelCell(0),
                        nav = nav,
                        entryFocus = null,
                        menuRowFocus = null,
                    )
                }
            }
        }
        compose.waitForIdle()
        val one = height()
        compose.runOnIdle { name.value = "CA: TSN 2\nEast 4K" }
        compose.waitForIdle()
        val two = height()
        println("GuideChannelCellTest one=$one two=$two b=" + compose.onNodeWithTag(CHANNEL_NAME_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot())
        assertTrue("long name should be about two lines: one=$one two=$two", two > one * 1.7f)
        assertTrue("never more than two lines: one=$one two=$two", two < one * 2.3f)
    }
}
