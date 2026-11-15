package com.rysal.happytv.ui.browse

import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import com.rysal.happytv.ui.theme.HappyTvDesign

/** A rectangle in 1920 x 1080 artboard px. */
data class ArtRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Where a popup menu's top-left corner goes, in artboard px. */
data class MenuPlacement(val x: Int, val y: Int)

/**
 * #232: places a popup menu beside the item it belongs to. To the right of [item] with a
 * [MENU_ANCHOR_GAP_PX] gap and its top level with the item's; when that would cross the right
 * safe edge, to the left of the item; when neither side fits, centered horizontally. Vertically the
 * whole menu is clamped between the top and bottom safe edges. [menuHeight] is the measured height.
 */
fun placeMenuBeside(item: ArtRect, menuWidth: Int, menuHeight: Int): MenuPlacement {
    val right = item.right + MENU_ANCHOR_GAP_PX
    val left = item.left - MENU_ANCHOR_GAP_PX - menuWidth
    val x = when {
        right + menuWidth <= MENU_SAFE_RIGHT_PX -> right
        left >= MENU_SAFE_LEFT_PX -> left
        else -> (ARTBOARD_W_PX - menuWidth) / 2
    }
    val maxY = (MENU_SAFE_BOTTOM_PX - menuHeight).coerceAtLeast(MENU_SAFE_TOP_PX)
    val y = item.top.coerceIn(MENU_SAFE_TOP_PX, maxY)
    return MenuPlacement(x, y)
}

/** The item's current bounds in artboard px, or null when it is not attached (nothing to anchor to). */
fun LayoutCoordinates?.toArtRect(design: HappyTvDesign): ArtRect? {
    if (this == null || !isAttached) return null
    val b = boundsInRoot()
    return ArtRect(
        left = design.artboardPx(b.left).toInt(),
        top = design.artboardPx(b.top).toInt(),
        right = design.artboardPx(b.right).toInt(),
        bottom = design.artboardPx(b.bottom).toInt(),
    )
}

/** Holds an item's layout coordinates without recomposing; read when the menu opens. */
class MenuAnchorHolder {
    var coordinates: LayoutCoordinates? = null
}

const val MENU_ANCHOR_GAP_PX = 24
const val MENU_SAFE_LEFT_PX = 96
const val MENU_SAFE_RIGHT_PX = 1824
const val MENU_SAFE_TOP_PX = 54
const val MENU_SAFE_BOTTOM_PX = 1026
private const val ARTBOARD_W_PX = 1920
