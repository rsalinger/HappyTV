package com.rysal.happytv.ui.player

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #94: the scrubber's fill and dot start at the bar's start edge. They used to be
 * centered in the bar and then offset, so the dot sat to the right of where the
 * fill ends and landed on the "Live" badge ("Li●e").
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlayerProgressRowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun live(fraction: Float) = PlayerProgress(
        startLabel = "9:01",
        endLabel = "11:00",
        fillFraction = fraction,
        bufferedFraction = fraction,
        liveEdgeFraction = null,
        dot = ScrubberDot.PulsingRed,
    )

    private fun show(progress: PlayerProgress) {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    PlayerProgressRow(progress, PlayerBadge(BadgeKind.Live, "Live"))
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun fillAndDotStartAtTheBarsStartEdge() {
        show(live(0.25f))
        val track = compose.onNodeWithTag(SCRUBBER_TRACK_TAG).getUnclippedBoundsInRoot()
        val fill = compose.onNodeWithTag(SCRUBBER_FILL_TAG).getUnclippedBoundsInRoot()
        val dot = compose.onNodeWithTag(SCRUBBER_DOT_TAG).getUnclippedBoundsInRoot()

        assertEquals(track.left.value, fill.left.value, 0.5f)
        assertEquals(track.width.value * 0.25f, fill.width.value, 1f)
        // The dot is centered on the end of the fill.
        assertEquals(fill.right.value, (dot.left.value + dot.right.value) / 2f, 1f)
    }

    @Test
    fun theDotNeverReachesTheLiveBadgeText() {
        val fractionState = androidx.compose.runtime.mutableStateOf(0.1f)
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    PlayerProgressRow(live(fractionState.value), PlayerBadge(BadgeKind.Live, "Live"))
                }
            }
        }
        for (fraction in listOf(0.1f, 0.5f, 0.66f, 0.83f, 1f)) {
            compose.runOnIdle { fractionState.value = fraction }
            compose.waitForIdle()
            val dot = compose.onNodeWithTag(SCRUBBER_DOT_TAG).getUnclippedBoundsInRoot()
            val badge = compose.onNodeWithText("Live").getUnclippedBoundsInRoot()
            assertTrue("dot at $fraction overlaps the Live text", dot.right.value <= badge.left.value)
        }
    }

    @Test
    fun theSeekTargetLabelSitsAboveTheDotAndStaysInsideTheBar() {
        val fractionState = androidx.compose.runtime.mutableStateOf(0f)
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    PlayerProgressRow(
                        live(fractionState.value).copy(dot = ScrubberDot.Cream),
                        null,
                        seekable = true,
                        seekTargetLabel = "1:02:14",
                    )
                }
            }
        }
        for (fraction in listOf(0f, 0.5f, 1f)) {
            compose.runOnIdle { fractionState.value = fraction }
            compose.waitForIdle()
            val track = compose.onNodeWithTag(SCRUBBER_TRACK_TAG).getUnclippedBoundsInRoot()
            val dot = compose.onNodeWithTag(SCRUBBER_DOT_TAG).getUnclippedBoundsInRoot()
            val label = compose.onNodeWithTag(SCRUBBER_TARGET_TAG).getUnclippedBoundsInRoot()
            assertTrue("label above the dot at $fraction", label.bottom.value <= dot.top.value)
            assertTrue("label inside the bar at $fraction", label.left.value >= track.left.value - 0.5f)
            assertTrue("label inside the bar at $fraction", label.right.value <= track.right.value + 0.5f)
        }
    }
}
