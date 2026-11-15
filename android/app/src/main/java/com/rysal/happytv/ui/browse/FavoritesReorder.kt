package com.rysal.happytv.ui.browse

import androidx.compose.runtime.Immutable

/**
 * #221: reordering the Favorites list in Live TV. The pure rules, so they are testable without
 * Compose or Room; [BrowseViewModel] holds the state and persists each place.
 */

/** What reorder mode remembers: the lineup as the viewer has it now, and the channel picked up, if any. */
@Immutable
data class ReorderState(
    /** Channel ids in the working order (top first). Changes with every move; saved only when placed. */
    val order: List<String>,
    /** The selected channel, or null while nothing is picked up. */
    val selectedId: String? = null,
    /** Where [selectedId] was when it was selected, 0-based: Back puts it here again. */
    val startIndex: Int = -1,
    /** What the last place or undo did, shown in the mode panel until the next selection. */
    val notice: ReorderNotice? = null,
)

/** A short confirmation in the mode panel: "Placed X at 3" or "X is back at 5". */
@Immutable
data class ReorderNotice(val kind: Kind, val channelId: String, val position: Int) {
    enum class Kind { PLACED, RESTORED }
}

/** The mode as the screen draws it. */
@Immutable
data class ReorderUi(
    val selectedId: String?,
    /** 1-based position of the selected channel now, 0 when nothing is selected. */
    val position: Int,
    /** 1-based position it had when selected; differs from [position] once it has moved. */
    val startPosition: Int,
    val total: Int,
    val notice: ReorderNotice?,
)

/** [order] with [id] taken out and put at [index] (clamped to the list). Returns [order] when nothing changes. */
internal fun List<String>.movedTo(id: String, index: Int): List<String> {
    val from = indexOf(id)
    if (from < 0) return this
    val to = index.coerceIn(0, lastIndex)
    if (to == from) return this
    val result = toMutableList()
    result.removeAt(from)
    result.add(to, id)
    return result
}

/**
 * Rows in the working [order]; a row the order does not name (a favorite added meanwhile) keeps
 * its place after the named ones, and an id with no row is skipped.
 */
internal fun List<LiveChannelRow>.inOrder(order: List<String>): List<LiveChannelRow> {
    val rank = HashMap<String, Int>(order.size * 2)
    order.forEachIndexed { i, id -> rank[id] = i }
    return sortedBy { rank[it.stream.id] ?: Int.MAX_VALUE }
}

/** How many places one key press moves a channel after the key has been held [repeatCount] times. */
internal fun reorderStepFor(repeatCount: Int): Int = when {
    repeatCount < 6 -> 1
    repeatCount < 16 -> 2
    repeatCount < 32 -> 4
    else -> 8
}

/** The panel's state for [rows] (already in the working order). */
internal fun ReorderState.toUi(rows: List<LiveChannelRow>): ReorderUi {
    val position = selectedId?.let { id -> rows.indexOfFirst { it.stream.id == id } + 1 } ?: 0
    return ReorderUi(
        selectedId = selectedId?.takeIf { position > 0 },
        position = position,
        startPosition = if (selectedId != null && startIndex >= 0) startIndex + 1 else 0,
        total = rows.size,
        notice = notice,
    )
}
