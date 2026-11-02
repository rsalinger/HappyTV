package com.rysal.happytv.ui.guide

import com.rysal.happytv.data.guide.GuideProgramState

/**
 * The guide's D-pad model (spec #7 section 3.4), as pure functions:
 *
 * > Left / Right moves between programs. Up / Down changes channel and
 * > keeps the time. Past programs are skipped unless the channel has
 * > replay. Left from the channel cell fans the category rail out.
 *
 * Compose owns focus. These functions only decide **where a key goes** from the
 * element that has focus: the element reports its own identity, so a key can
 * never act on a stale idea of what is focused (the "first OK does nothing"
 * bug of the old ViewModel-owned focus).
 */
sealed interface GuideFocus {
    /** The left cell of a row (channel number, monogram, name, star). */
    data class ChannelCell(val row: Int) : GuideFocus

    /**
     * A program block. [startUtcMillis] is the program's start - the
     * stable identity when rows rebuild underneath the focus.
     */
    data class Program(val row: Int, val startUtcMillis: Long) : GuideFocus
}

/** Remote arrow keys the guide routes (OK and Back are actions, not moves). */
enum class GuideKey { LEFT, RIGHT, UP, DOWN }

/** Where a key that cannot move inside the grid goes instead. */
enum class GuideEdge {
    /** Left from a channel cell: fan out the category rail. */
    RAIL,

    /** Up from the first row: the header's day picker and toggle (Compose's own search). */
    HEADER,

    /** Down from the last row, Left/Right at a row's end: nothing, but the key is still consumed. */
    STOP,
}

/**
 * The focus a fresh guide opens on: the first program on now, else the first
 * focusable program, else the first channel cell; null with no rows.
 */
fun initialFocus(rows: List<GuideRow>): GuideFocus? {
    defaultProgramFocusTarget(rows)?.let { (index, block) -> return GuideFocus.Program(index, block.key) }
    if (rows.isNotEmpty()) return GuideFocus.ChannelCell(0)
    return null
}

/**
 * Keeps [focus] valid after any rebuild (window shifted, fetch landed, day
 * changed, a focused program aged into "ended x no replay" while the app sat
 * open). Never moves focus for its own sake - only when the target is gone or
 * no longer focusable. Null with no rows.
 */
fun revalidateFocus(focus: GuideFocus, rows: List<GuideRow>): GuideFocus? {
    if (rows.isEmpty()) return null
    return when (focus) {
        is GuideFocus.ChannelCell -> if (focus.row in rows.indices) focus else initialFocus(rows)
        is GuideFocus.Program -> {
            val row = rows.getOrNull(focus.row) ?: return initialFocus(rows)
            val block = row.programAt(focus.startUtcMillis)
            when {
                block != null && block.focusable -> focus
                // Gone from the window, aged into ended x no-replay, or the
                // row refilled: re-pick inside this row, never jump rows.
                else -> row.focusableInRowPreferLive(focus.row)
            }
        }
    }
}

/** Same row, re-pick: the program on now, else the first focusable, else the cell. */
private fun GuideRow.focusableInRowPreferLive(rowIndex: Int): GuideFocus {
    liveProgram?.takeIf { it.focusable }?.let { return GuideFocus.Program(rowIndex, it.key) }
    focusablePrograms.firstOrNull()?.let { return GuideFocus.Program(rowIndex, it.key) }
    return GuideFocus.ChannelCell(rowIndex)
}

/**
 * One D-pad move. Returns the new focus, or null for "no move inside the grid"
 * (see [edgeFor] for what the screen does with such a key).
 */
fun moveFocus(current: GuideFocus, key: GuideKey, rows: List<GuideRow>): GuideFocus? = when (current) {
    is GuideFocus.ChannelCell -> {
        val row = rows.getOrNull(current.row)
        when {
            row == null -> null
            // Right enters the row: the program on now, else the first.
            key == GuideKey.RIGHT -> rowEntryFocus(current.row, row)
            key == GuideKey.UP ->
                if (current.row == 0) null
                else verticalTarget(rows, current.row - 1, keepTime = null)
            key == GuideKey.DOWN ->
                if (current.row == rows.lastIndex) null
                else verticalTarget(rows, current.row + 1, keepTime = null)
            else -> null // Left: the rail (see [edgeFor])
        }
    }

    is GuideFocus.Program -> {
        val row = rows.getOrNull(current.row)
        if (row == null) {
            null
        } else {
            val focusables = row.focusablePrograms
            val index = focusables.indexOfFirst { it.key == current.startUtcMillis }
            when (key) {
                // Left walks programs, then the channel cell (row edge).
                GuideKey.LEFT -> when {
                    index <= 0 -> GuideFocus.ChannelCell(current.row)
                    else -> GuideFocus.Program(current.row, focusables[index - 1].key)
                }
                GuideKey.RIGHT -> when {
                    index < 0 -> rowEntryFocus(current.row, row)
                    index == focusables.lastIndex -> null
                    else -> GuideFocus.Program(current.row, focusables[index + 1].key)
                }
                GuideKey.UP ->
                    if (current.row == 0) null
                    else verticalTarget(rows, current.row - 1, current.startUtcMillis)
                GuideKey.DOWN ->
                    if (current.row == rows.lastIndex) null
                    else verticalTarget(rows, current.row + 1, current.startUtcMillis)
            }
        }
    }
}

/**
 * What a key that [moveFocus] could not move does: Left from a channel cell
 * fans the rail out (spec #7 section 3.2), Up from the first row hands over to
 * the header, anything else stops at the grid's edge.
 */
fun edgeFor(current: GuideFocus, key: GuideKey): GuideEdge = when {
    current is GuideFocus.ChannelCell && key == GuideKey.LEFT -> GuideEdge.RAIL
    key == GuideKey.UP -> GuideEdge.HEADER
    else -> GuideEdge.STOP
}

/**
 * Left/Right into a row: the program on now, else the first focusable one - the
 * "current program" landing spot. A row with nothing focusable lands on its
 * channel cell.
 */
private fun rowEntryFocus(rowIndex: Int, row: GuideRow): GuideFocus =
    row.focusableInRowPreferLive(rowIndex)

/** The entry spot of the first row: where OK / Right on a category lands. */
fun firstRowFocus(rows: List<GuideRow>): GuideFocus? =
    rows.firstOrNull()?.let { rowEntryFocus(0, it) }

/**
 * Up/Down: same vertical line in the neighbouring row - "changes channel and
 * keeps the time". With [keepTime], the program containing that instant (or the
 * nearest by range distance); without it, the program on now. A row with no
 * focusable program lands on its cell.
 */
private fun verticalTarget(rows: List<GuideRow>, rowIndex: Int, keepTime: Long?): GuideFocus {
    val row = rows.getOrNull(rowIndex) ?: return GuideFocus.ChannelCell(rowIndex)
    val focusables = row.focusablePrograms
    if (focusables.isEmpty()) return GuideFocus.ChannelCell(rowIndex)

    val target = if (keepTime == null) {
        focusables.firstOrNull { it.state == GuideProgramState.LIVE } ?: focusables.first()
    } else {
        // Containing program first (half-open [start, end) - a keepTime
        // sitting exactly on a boundary belongs to the program that starts
        // there, not the one that just ended), else nearest range distance.
        focusables.firstOrNull { block ->
            keepTime >= block.program.startUtcMillis && keepTime < block.program.endUtcMillis
        } ?: focusables.minByOrNull { block ->
            val program = block.program
            when {
                keepTime < program.startUtcMillis -> program.startUtcMillis - keepTime
                keepTime >= program.endUtcMillis -> keepTime - program.endUtcMillis
                else -> 0L
            }
        }!!
    }
    return GuideFocus.Program(rowIndex, target.key)
}

/**
 * Left/Right between the dialog's buttons (visual order; the
 * disabled-looking "Remind me" is still a focus stop).
 */
fun moveDialogFocus(
    current: GuideDialogButton,
    key: GuideKey,
    dialog: GuideDialogUi,
): GuideDialogButton? {
    val index = dialog.buttons.indexOf(current)
    if (index < 0) return null
    return when (key) {
        GuideKey.LEFT -> dialog.buttons.getOrNull(index - 1)
        GuideKey.RIGHT -> dialog.buttons.getOrNull(index + 1)
        else -> null
    }
}
