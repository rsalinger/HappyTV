package com.rysal.happytv.ui.catalog

import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.browse.RailEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules of the one-screen Movies / Shows (#72): grid geometry, rail contents, wording. */
class CatalogScreenLogicTest {

    // ------------------------------------------------------------------ grid geometry

    private val viewportTop = GRID_VIEWPORT_TOP_PX
    private val bottomSafeTop = 1080 - SAFE_BOTTOM_PX

    /** A cell is the poster plus its two-line caption (#93). */
    private fun cellHeight(m: CatalogGridMetrics): Int = m.cardHeightPx + CAPTION_GAP_PX + CAPTION_HEIGHT_PX

    private fun rowsBottom(m: CatalogGridMetrics, rows: Int): Int =
        // top ring room + the cells + the gaps between them, then the last row's ring.
        viewportTop + RING_MARGIN_PX + rows * cellHeight(m) + (rows - 1) * ROW_GAP_PX + RING_MARGIN_PX

    @Test
    fun `seven columns and two full rows with their captions fit above the bottom safe area when the rail is folded`() {
        val m = catalogGridMetrics(availableWidthPx = 1728)
        assertEquals(7, COLUMNS)
        assertTrue("two rows end at ${rowsBottom(m, 2)}", rowsBottom(m, 2) <= bottomSafeTop)
    }

    @Test
    fun `seven columns and two full rows with their captions fit when the rail is kept open`() {
        val m = catalogGridMetrics(availableWidthPx = 1824 - 520)
        assertTrue(rowsBottom(m, 2) <= bottomSafeTop)
        assertTrue("seven cards and their gaps fit the narrow pane", 7 * (m.cardWidthPx + MIN_COLUMN_GAP_PX) <= 1824 - 520)
    }

    @Test
    fun `cards stay near two to three and the size follows the width only down to the cap`() {
        val wide = catalogGridMetrics(1728)
        assertEquals(MAX_CARD_WIDTH_PX, wide.cardWidthPx)
        assertTrue(wide.cardHeightPx <= MAX_CARD_HEIGHT_PX)
        val ratio = wide.cardWidthPx.toFloat() / wide.cardHeightPx
        assertTrue("aspect $ratio", ratio in 0.62f..0.78f)
        val narrow = catalogGridMetrics(1304)
        assertTrue(narrow.cardWidthPx < wide.cardWidthPx)
        assertEquals(wide.cardWidthPx.toFloat() / wide.cardHeightPx, narrow.cardWidthPx.toFloat() / narrow.cardHeightPx, 0.03f)
    }

    // ------------------------------------------------------------------ rail contents

    private val categories = listOf(
        Category(id = "drama", type = StreamType.VOD, name = "Drama"),
        Category(id = "comedy", type = StreamType.VOD, name = "Comedy"),
    )

    @Test
    fun `the rail pins Favorites, Recently added and All, then lists provider categories with counts`() {
        val rail = catalogRailEntries(
            StreamType.VOD, categories, mapOf("drama" to 12, "comedy" to 3), totalItems = 15, favoriteCount = 2,
            selected = "drama",
        )
        assertEquals(
            listOf(
                CatalogViewModel.FAVORITES_CATEGORY_ID,
                CatalogViewModel.RECENT_CATEGORY_ID,
                CatalogViewModel.ALL_CATEGORY_ID,
                "__divider__",
                "drama",
                "comedy",
            ),
            rail.map { it.key },
        )
        val rows = rail.filterIsInstance<RailEntry.Row>().associateBy { it.key }
        assertEquals("Favorites", rows.getValue(CatalogViewModel.FAVORITES_CATEGORY_ID).label)
        assertEquals(2, rows.getValue(CatalogViewModel.FAVORITES_CATEGORY_ID).count)
        assertEquals("Recently added", rows.getValue(CatalogViewModel.RECENT_CATEGORY_ID).label)
        assertEquals("All movies", rows.getValue(CatalogViewModel.ALL_CATEGORY_ID).label)
        assertEquals(15, rows.getValue(CatalogViewModel.ALL_CATEGORY_ID).count)
        assertEquals(12, rows.getValue("drama").count)
        assertTrue("provider categories open a hide menu", rows.getValue("drama").hasMenu)
        assertFalse("pinned rows do not", rows.getValue(CatalogViewModel.ALL_CATEGORY_ID).hasMenu)
    }

    @Test
    fun `only the selected row is marked selected and shows say shows`() {
        val rail = catalogRailEntries(
            StreamType.SERIES, categories, emptyMap(), totalItems = 0, favoriteCount = 0,
            selected = CatalogViewModel.RECENT_CATEGORY_ID,
        )
        val selected = rail.filterIsInstance<RailEntry.Row>().filter { it.selected }
        assertEquals(listOf(CatalogViewModel.RECENT_CATEGORY_ID), selected.map { it.key })
        assertEquals("All shows", rail.filterIsInstance<RailEntry.Row>().first { it.key == CatalogViewModel.ALL_CATEGORY_ID }.label)
    }

    @Test
    fun `Continue watching is not listed while nothing is in progress`() {
        val rail = catalogRailEntries(StreamType.VOD, categories, emptyMap(), 0, 0, "drama")
        assertTrue(rail.filterIsInstance<RailEntry.Row>().none { it.label == "Continue watching" })
        val zero = catalogRailEntries(StreamType.VOD, categories, emptyMap(), 0, 0, "drama", continueCount = 0)
        assertEquals(rail.map { it.key }, zero.map { it.key })
    }

    @Test
    fun `Continue watching is the first pinned row once something is in progress`() {
        val rail = catalogRailEntries(
            StreamType.SERIES, categories, emptyMap(), 0, 0, CatalogViewModel.CONTINUE_CATEGORY_ID, continueCount = 3,
        )
        val first = rail.first() as RailEntry.Row
        assertEquals(CatalogViewModel.CONTINUE_CATEGORY_ID, first.key)
        assertEquals("Continue watching", first.label)
        assertEquals(3, first.count)
        assertTrue(first.selected)
        assertEquals(CatalogViewModel.FAVORITES_CATEGORY_ID, rail[1].key)
    }

    @Test
    fun `a remembered Continue watching row is used only while it has items`() {
        val cat = listOf("drama")
        assertEquals(
            CatalogViewModel.CONTINUE_CATEGORY_ID,
            CatalogViewModel.initialCategoryId(CatalogViewModel.CONTINUE_CATEGORY_ID, cat, hasFavorites = false, hasContinue = true),
        )
        assertEquals(
            CatalogViewModel.RECENT_CATEGORY_ID,
            CatalogViewModel.initialCategoryId(CatalogViewModel.CONTINUE_CATEGORY_ID, cat, hasFavorites = false, hasContinue = false),
        )
        assertEquals(
            CatalogViewModel.FAVORITES_CATEGORY_ID,
            CatalogViewModel.initialCategoryId(CatalogViewModel.CONTINUE_CATEGORY_ID, cat, hasFavorites = true, hasContinue = false),
        )
    }

    @Test
    fun `an episode label needs both numbers`() {
        assertEquals("S2 E3", CatalogViewModel.episodeLabel(2, 3))
        assertEquals(null, CatalogViewModel.episodeLabel(2, null))
        assertEquals(null, CatalogViewModel.episodeLabel(null, 3))
    }

    @Test
    fun `a movie resume point reads as the clock time, an episode as its season and episode (74)`() {
        assertEquals("Resume from 41:20", ResumePoint(2_480_000L, null, null).buttonLabel)
        assertEquals("Resume S1 E1", ResumePoint(3_909_000L, "e1", "S1 E1").buttonLabel)
        // No label known: the clock is all there is.
        assertEquals("Resume from 1:05:09", ResumePoint(3_909_000L, "e1", null).buttonLabel)
    }

    // ------------------------------------------------------------------ details wording

    @Test
    fun `runtime reads as hours and minutes`() {
        assertEquals("1 h 30 min", formatRuntime("01:30:00"))
        assertEquals("2 h 16 min", formatRuntime("02:16:00"))
        assertEquals("2 h", formatRuntime("02:00:00"))
        assertEquals("45 min", formatRuntime("00:45:10"))
        assertEquals("45 min", formatRuntime("45:00"))
        assertEquals("1 h 30 min", formatRuntime("90"))
        assertEquals("1 h 30 min", formatRuntime("5400s"))
    }

    @Test
    fun `an unusable runtime is left out rather than shown raw`() {
        assertNull(formatRuntime(null))
        assertNull(formatRuntime(""))
        assertNull(formatRuntime("00:00:00"))
        assertNull(formatRuntime("soon"))
    }

    @Test
    fun `the rating names its scale`() {
        assertEquals("Rating 7.4/10", ratingText(7.4))
        assertEquals("Rating 3.8/10", ratingText(3.8))
        assertEquals("Rating 8.0/10", ratingText(8.0))
    }

    @Test
    fun `a trailing year leaves the title and is returned on its own`() {
        assertEquals("Dark Matter" to "2015", splitTitleYear("Dark Matter (2015)"))
        assertEquals("Dark Matter" to "2015", splitTitleYear("Dark Matter [2015]"))
        assertEquals("Blade Runner 2049" to null, splitTitleYear("Blade Runner 2049"))
        assertEquals("1917" to null, splitTitleYear("1917"))
        assertEquals("Show (US) (2020)".let { "Show (US)" to "2020" }, splitTitleYear("Show (US) (2020)"))
        assertEquals("Heat (1995) Directors cut" to null, splitTitleYear("Heat (1995) Directors cut"))
    }
}
