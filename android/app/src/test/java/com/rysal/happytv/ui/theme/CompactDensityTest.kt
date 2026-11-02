package com.rysal.happytv.ui.theme

import androidx.compose.ui.text.TextStyle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec #7 section 1: compact type tokens and the 24 px (12 sp) floor. */
class CompactDensityTest {

    // density 2, scale 1: 1 px of artboard == 0.5 sp.
    private val type = HappyTvTypography(HappyTvDesign(density = 2f, scale = 1f))

    private fun px(style: TextStyle) = style.fontSize.value * 2f
    private fun linePx(style: TextStyle) = style.lineHeight.value * 2f

    @Test
    fun `compact tokens match the spec table`() {
        assertEquals(36f to 44f, px(type.compactTitle) to linePx(type.compactTitle))
        assertEquals(32f to 40f, px(type.compactHeading) to linePx(type.compactHeading))
        assertEquals(28f to 36f, px(type.compactRowName) to linePx(type.compactRowName))
        assertEquals(28f to 36f, px(type.compactBody) to linePx(type.compactBody))
        assertEquals(24f to 32f, px(type.compactCaption) to linePx(type.compactCaption))
        assertEquals(32f to 40f, px(type.compactClock) to linePx(type.compactClock))
        assertEquals(700, type.compactTitle.fontWeight!!.weight)
        assertEquals(600, type.compactClock.fontWeight!!.weight)
    }

    @Test
    fun `no compact token is under the 24 px floor`() {
        listOf(
            type.compactTitle, type.compactHeading, type.compactRowName, type.compactBody,
            type.compactCaption, type.compactCaptionSemibold, type.compactClock,
        ).forEach { assertTrue(px(it) >= COMPACT_MIN_TEXT_PX) }
    }

    @Test
    fun `handoff sizes are unchanged for the screens that stay large`() {
        assertEquals(64f, px(type.title))
        assertEquals(44f, px(type.heading))
        assertEquals(36f, px(type.body))
        assertEquals(28f, px(type.caption))
    }

    /**
     * Lint-style check for the changed screens: no hard-coded font size under
     * 24 px (the only way to go under the floor is an explicit `sp(...)`).
     */
    @Test
    fun `changed screens hard-code no text size under the floor`() {
        val root = File("src/main/java/com/rysal/happytv/ui")
        val files = listOf(
            "browse/BrowseAtoms.kt", "browse/LiveTvScreen.kt", "guide/GuideView.kt",
            "guide/GuideDetailsDialog.kt",
            "settings/SettingsScreen.kt", "settings/SettingsControls.kt",
            "catalog/CatalogScreen.kt", "browse/BrowseRail.kt", "catalog/DetailsScreen.kt",
        )
        val sizeCall = Regex("""\bd\.sp\((\d+(?:\.\d+)?)\)""")
        val offenders = mutableListOf<String>()
        files.forEach { name ->
            val file = File(root, name)
            assertTrue("missing $name (cwd=${File(".").absolutePath})", file.exists())
            file.readLines().forEachIndexed { i, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                sizeCall.findAll(line).forEach { m ->
                    // lineHeight overrides are line boxes, not font sizes.
                    if ("lineHeight" in line) return@forEach
                    if (m.groupValues[1].toFloat() < COMPACT_MIN_TEXT_PX) {
                        offenders += "$name:${i + 1}: ${line.trim()}"
                    }
                }
            }
        }
        assertTrue("text under 24 px: $offenders", offenders.isEmpty())
    }
}
