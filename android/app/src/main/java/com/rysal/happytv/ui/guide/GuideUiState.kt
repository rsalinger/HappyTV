package com.rysal.happytv.ui.guide

import androidx.compose.runtime.Immutable
import com.rysal.happytv.data.guide.GuideChannel
import com.rysal.happytv.data.guide.GuideProgram
import com.rysal.happytv.data.guide.GuideProgramState
import com.rysal.happytv.data.guide.GuideWindow
import com.rysal.happytv.data.guide.stateAt
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Everything the Guide view of the combined Live TV screen renders, derived by
 * pure functions from (channels, stored programs, counts, day, now) - no
 * Compose, no Android, so every rule below is unit-testable (`GuideGridTest`).
 *
 * Geometry is spec #7 section 3.4: a two-hour window, 56 tall rows 4 apart, a
 * 360 wide channel cell, title-only blocks. Block x/w are design px against the
 * reference timeline ([GuideWindow.TIMELINE_WIDTH_PX]); the screen scales them
 * when the grid is narrower ("Keep categories open").
 */

// ------------------------------------------------------------------ days

/** The header's segmented day picker (Yesterday / Today / Tomorrow), Guide view only. */
enum class GuideDay(val dayOffset: Int, val label: String) {
    YESTERDAY(-1, "Yesterday"),
    TODAY(0, "Today"),
    TOMORROW(1, "Tomorrow"),
    ;

    fun previous(): GuideDay = entries.getOrNull(ordinal - 1) ?: this
    fun next(): GuideDay = entries.getOrNull(ordinal + 1) ?: this
}

// ----------------------------------------------------------------- rows

/**
 * One grid cell: either a program or a striped no-data span.
 *
 * `@Immutable` (here and on [GuideRow]) lets Compose skip a row whose data did
 * not change when only focus or the details dialog changed (#10) - the lists
 * inside are built fresh and never mutated.
 */
@Immutable
sealed interface GuideBlock {
    /** Timeline x, design px from the timeline's left edge. */
    val x: Float
    /** Width, design px = duration minus the block gap, clipped to the window. */
    val w: Float

    /**
     * A program block. [key] is the program's start (unique per row after
     * overlap dedupe) and is the focus identity of the block.
     */
    @Immutable
    data class ProgramBlock(
        val program: GuideProgram,
        override val x: Float,
        override val w: Float,
        val state: GuideProgramState,
        /** Ended blocks are focusable **only** when the channel has replay; everything else is. */
        val focusable: Boolean,
        /** Channel genre or null - null means no dot, ever. */
        val genre: String?,
        /** The replay icon follows ended programs on replay channels. */
        val showReplayIcon: Boolean,
        /** "2:00-2:30" (en dash, no spaces) - full times, not clipped ones. */
        val timeLabel: String,
    ) : GuideBlock {
        val key: Long get() = program.startUtcMillis
    }

    /** Striped block: always "No guide info", whatever the reason the span is empty (#172). */
    @Immutable
    data class GapBlock(
        override val x: Float,
        override val w: Float,
        val label: String,
    ) : GuideBlock
}

/** One channel row: the cell's channel + every block in window order. */
@Immutable
data class GuideRow(
    val channel: GuideChannel,
    val blocks: List<GuideBlock>,
) {
    /** Blocks the D-pad may land on (ended x no-replay excluded). */
    val focusablePrograms: List<GuideBlock.ProgramBlock>
        get() = blocks.filterIsInstance<GuideBlock.ProgramBlock>().filter { it.focusable }

    val allPrograms: List<GuideBlock.ProgramBlock>
        get() = blocks.filterIsInstance<GuideBlock.ProgramBlock>()

    val liveProgram: GuideBlock.ProgramBlock?
        get() = allPrograms.firstOrNull { it.state == GuideProgramState.LIVE }

    /**
     * What a channel cell describes (#154): the program on now when the window has it, else the
     * first program the row draws, so a guide moved forward in time (or a row whose on-now
     * program is not drawn) still describes the channel at the time the grid is showing,
     * never "No guide info" while the row has data.
     */
    val channelCellProgram: GuideBlock.ProgramBlock?
        get() = liveProgram ?: allPrograms.firstOrNull()

    fun programAt(startUtcMillis: Long): GuideBlock.ProgramBlock? =
        allPrograms.firstOrNull { it.program.startUtcMillis == startUtcMillis }
}

// ------------------------------------------------------------ info band

/**
 * What the info band (spec #7 section 3.1) draws for one focused program: the
 * channel line, the title, and the time-and-status line. The description is
 * not here: it comes from a single-row read for the focused program only.
 */
@Immutable
data class InfoBandUi(
    /** "101 - Northbay News" (middle dot). */
    val channelLine: String,
    /** The program title, or "No guide info". */
    val title: String,
    /** False when there is no program to describe (title is the placeholder). */
    val hasProgram: Boolean,
    /** "2:00 - 3:00 - On now - 23 min left", or null without a program. */
    val timeLine: String?,
)

const val NO_GUIDE_INFO_TITLE = "No guide info"

/** "101 - Northbay News", or just the name when the panel sent no number. */
fun channelLineOf(num: Int?, name: String): String =
    (listOfNotNull(num?.toString()) + name).joinToString(" · ")

/** The info band for [program] on [channelLine]: range plus the design spec status chip. */
fun infoBandFor(
    channelLine: String,
    program: GuideProgram,
    hasReplay: Boolean,
    nowUtcMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): InfoBandUi = InfoBandUi(
    channelLine = channelLine,
    title = program.title,
    hasProgram = true,
    timeLine = formatRange(program.startUtcMillis, program.endUtcMillis, zone) +
        " · " + statusChip(program, hasReplay, nowUtcMillis, zone),
)

/** The info band for a channel with nothing on: "No guide info". */
fun infoBandNoGuide(channelLine: String): InfoBandUi =
    InfoBandUi(channelLine = channelLine, title = NO_GUIDE_INFO_TITLE, hasProgram = false, timeLine = null)

// ---------------------------------------------------------------- dialog

enum class GuideDialogButton { WATCH_NOW, WATCH_FROM_START, REMIND }

/** The program-details dialog's rendered model. */
data class GuideDialogUi(
    val channel: GuideChannel,
    val program: GuideProgram,
    val state: GuideProgramState,
    /** "101 - Northbay News". */
    val channelLine: String,
    val title: String,
    /** "2:00 - 3:00 - News - On now - 23 min left". */
    val metaLine: String,
    /** 0f..1f progress through the program, non-null only when on now. */
    val progressFraction: Float?,
    /** Buttons in visual order; the dialog focuses [initialButton] first. */
    val buttons: List<GuideDialogButton>,
    val initialButton: GuideDialogButton,
)

// ------------------------------------------------------------ formatting

/** "2:37" - 12-hour, no AM/PM, zero-padded minute. */
fun formatHm(utcMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalTime()
    val hour12 = time.hour % 12
    val h = if (hour12 == 0) 12 else hour12
    val minute = time.minute
    return "$h:${if (minute < 10) "0$minute" else minute}"
}

/** "2:30 - 3:30" - info band/dialog range (en dash, spaces). */
fun formatRange(startUtcMillis: Long, endUtcMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "${formatHm(startUtcMillis, zone)} – ${formatHm(endUtcMillis, zone)}"

/** "2:00-2:30" - the block's time label (en dash, no spaces). */
fun formatBlockTime(startUtcMillis: Long, endUtcMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "${formatHm(startUtcMillis, zone)}–${formatHm(endUtcMillis, zone)}"

/**
 * The five status chips (design spec wording):
 *
 *  - on now -> `"On now - 23 min left"` (round, min 1)
 *  - starts within the hour -> `"Starts in 15 min"`
 *  - starts later -> `"Starts at 9:00"`
 *  - ended + replay channel -> `"Ended - replay available"`
 *  - ended -> `"Ended"`
 */
fun statusChip(program: GuideProgram, hasReplay: Boolean, nowUtcMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String = when {
    program.isLive(nowUtcMillis) -> {
        val left = max(1L, Math.round((program.endUtcMillis - nowUtcMillis) / 60_000.0))
        "On now · $left min left"
    }
    program.startsAt(nowUtcMillis) -> {
        val minutes = Math.round((program.startUtcMillis - nowUtcMillis) / 60_000.0)
        if (minutes <= 60) "Starts in $minutes min" else "Starts at ${formatHm(program.startUtcMillis, zone)}"
    }
    hasReplay -> "Ended · replay available"
    else -> "Ended"
}

// ------------------------------------------------------------- builders

/**
 * Builds one channel's blocks for [window]:
 * programs clipped to the window (rounded), then the striped gaps for whatever
 * the guide didn't cover.
 *
 * @param guideCount how many programs this channel has ever had stored -
 * 0 -> the whole window is one striped span (channel with no guide at all);
 * >0 with uncovered spans -> the uncovered spans. Both read "No guide info" (#172).
 */
fun buildGuideRow(
    channel: GuideChannel,
    programs: List<GuideProgram>,
    guideCount: Int,
    window: GuideWindow,
    nowUtcMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): GuideRow {
    val blocks = ArrayList<GuideBlock>()

    // Window-overlapping programs, start-ordered. Providers pad end times,
    // so overlaps happen; blocks can't collide, so one of the pair goes -
    // and the LIVE program always wins it. "First start wins" alone hid the
    // on-now block behind an ended neighbour that ran a few minutes long
    // (found on TV with a padded fixture: 101's live Daybreak News vanished
    // behind Morning Headline's padded end).
    val overlapping = programs
        .filter { it.endUtcMillis > window.startUtcMillis && it.startUtcMillis < window.endUtcMillis }
        .sortedBy { it.startUtcMillis }
    val schedule = dedupeOverlaps(overlapping, nowUtcMillis)

    for (program in schedule) {
        val state = program.stateAt(nowUtcMillis)
        val x = window.xOf(max(program.startUtcMillis, window.startUtcMillis)).roundToInt()
        val x2 = window.xOf(minOf(program.endUtcMillis, window.endUtcMillis)).roundToInt()
        blocks += GuideBlock.ProgramBlock(
            program = program,
            x = x.toFloat(),
            w = max(0, x2 - x - BLOCK_GAP_PX).toFloat(),
            state = state,
            focusable = state != GuideProgramState.ENDED || channel.hasReplay,
            genre = channel.genre,
            showReplayIcon = state == GuideProgramState.ENDED && channel.hasReplay,
            timeLabel = formatBlockTime(program.startUtcMillis, program.endUtcMillis, zone),
        )
    }

    val label = NO_GUIDE_INFO
    val spans = ArrayList<Pair<Long, Long>>()
    when {
        guideCount <= 0 -> {
            spans += window.startUtcMillis to window.endUtcMillis
        }
        schedule.isEmpty() -> {
            spans += window.startUtcMillis to window.endUtcMillis
        }
        else -> {
            val first = schedule.first()
            val last = schedule.last()
            if (first.startUtcMillis > window.startUtcMillis) {
                spans += window.startUtcMillis to first.startUtcMillis
            }
            if (last.endUtcMillis < window.endUtcMillis) {
                spans += last.endUtcMillis to window.endUtcMillis
            }
        }
    }
    for ((from, to) in spans) {
        val x = window.xOf(from).roundToInt()
        val x2 = window.xOf(to).roundToInt()
        blocks += GuideBlock.GapBlock(
            x = x.toFloat(),
            w = max(0, x2 - x - BLOCK_GAP_PX).toFloat(),
            label = label,
        )
    }

    blocks.sortBy { it.x }
    return GuideRow(channel = channel, blocks = blocks)
}

/**
 * Start-ordered [programs] with overlaps removed: blocks can't collide, so one of an
 * overlapping pair goes. Providers pad end times, and the LIVE program always wins the
 * pair; "first start wins" alone hid the on-now block behind an ended neighbour that ran a
 * few minutes long (found on TV with a padded fixture: 101's live Daybreak News vanished
 * behind Morning Headline's padded end). Shared with the time navigation so a D-pad move
 * only ever targets a program the grid really draws.
 */
internal fun dedupeOverlaps(programs: List<GuideProgram>, nowUtcMillis: Long): List<GuideProgram> {
    val schedule = ArrayList<GuideProgram>(programs.size)
    for (program in programs) {
        val last = schedule.lastOrNull()
        if (last != null && program.startUtcMillis < last.endUtcMillis) {
            val programLive = program.stateAt(nowUtcMillis) == GuideProgramState.LIVE
            val lastLive = last.stateAt(nowUtcMillis) == GuideProgramState.LIVE
            if (programLive && !lastLive) {
                // The padded neighbour lost: swap in the live program. The
                // program before it still ends at or before `last` started
                // (start-ordered), so nothing new collides backwards.
                schedule.removeAt(schedule.lastIndex)
                schedule.add(program)
            }
            continue
        }
        schedule.add(program)
    }
    return schedule
}

// ------------------------------------------------------------ info / dialog

/**
 * The info band for a focused grid element. A program block describes itself; a
 * channel cell describes what is on now (or "No guide info").
 */
fun infoBandForFocus(
    focus: GuideFocus,
    rows: List<GuideRow>,
    nowUtcMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): InfoBandUi? {
    val rowIndex = when (focus) {
        is GuideFocus.ChannelCell -> focus.row
        is GuideFocus.Program -> focus.row
    }
    val row = rows.getOrNull(rowIndex) ?: return null
    val line = channelLineOf(row.channel.num, row.channel.name)
    val block = infoBlockFor(focus, row) ?: return infoBandNoGuide(line)
    return infoBandFor(line, block.program, row.channel.hasReplay, nowUtcMillis, zone)
}

/**
 * The program the info band describes for [focus] on [row]. A focused program that the row no
 * longer draws (the window moved under it: Today chosen in the header after a move forward)
 * falls back to the channel cell's program, never to the program that used to be there (#154).
 */
fun infoBlockFor(focus: GuideFocus, row: GuideRow): GuideBlock.ProgramBlock? = when (focus) {
    is GuideFocus.Program -> row.programAt(focus.startUtcMillis) ?: row.channelCellProgram
    is GuideFocus.ChannelCell -> row.channelCellProgram
}

/** The details dialog for one program (OK on a focused program opens it). */
fun dialogFor(
    row: GuideRow,
    block: GuideBlock.ProgramBlock,
    nowUtcMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): GuideDialogUi {
    val channel = row.channel
    val program = block.program
    val state = block.state

    val meta = ArrayList<String>()
    meta += formatRange(program.startUtcMillis, program.endUtcMillis, zone)
    channel.genre?.let { meta += it }
    meta += statusChip(program, channel.hasReplay, nowUtcMillis, zone)

    val buttons = ArrayList<GuideDialogButton>()
    if (state == GuideProgramState.LIVE) buttons += GuideDialogButton.WATCH_NOW
    if ((state == GuideProgramState.LIVE || state == GuideProgramState.ENDED) && channel.hasReplay) {
        buttons += GuideDialogButton.WATCH_FROM_START
    }
    if (state == GuideProgramState.FUTURE) buttons += GuideDialogButton.REMIND

    return GuideDialogUi(
        channel = channel,
        program = program,
        state = state,
        channelLine = channelLineOf(channel.num, channel.name),
        title = program.title,
        metaLine = meta.joinToString(" · "),
        progressFraction = if (state == GuideProgramState.LIVE) {
            val total = (program.endUtcMillis - program.startUtcMillis).toFloat()
            if (total <= 0f) null
            else ((nowUtcMillis - program.startUtcMillis) / total).coerceIn(0f, 1f)
        } else {
            null
        },
        buttons = buttons,
        // Live focuses Watch now, past focuses Watch from the start, future
        // focuses Remind me - whichever exists (a dialog for an ended x
        // no-replay program is unreachable: not focusable).
        initialButton = when {
            GuideDialogButton.WATCH_NOW in buttons -> GuideDialogButton.WATCH_NOW
            GuideDialogButton.WATCH_FROM_START in buttons -> GuideDialogButton.WATCH_FROM_START
            else -> buttons.firstOrNull() ?: GuideDialogButton.REMIND
        },
    )
}

// ------------------------------------------------------------------ state

/**
 * The grid state - [buildGuideState] is the one pure entry point the ViewModel
 * calls whenever any input (now tick, day, fetch landing) changes. Focus is not
 * part of it: Compose owns focus and the screen asks the pure rules in
 * `GuideFocusNav` for where a key goes.
 */
@Immutable
data class GuideUiState(
    val day: GuideDay,
    /** "2:37" - the Now pill's label. */
    val clockLabel: String,
    val window: GuideWindow,
    val rows: List<GuideRow>,
    /** Grid replaced by "No guide yet" (no stored guide for any row). */
    val isEmptyState: Boolean,
    /** Now line/pill only make sense on Today's grid. */
    val showNowLine: Boolean,
    /** Now's x within the timeline (design px from the timeline's left edge). */
    val nowX: Float,
    /** Ruler labels: window start, +30 min, +60 min, +90 min. */
    val ticks: List<Pair<Float, String>>,
    /**
     * True once channels, the window's programs and the guide counts have all
     * landed - the grid is filled, not just laid out. Drives the `guide.open`
     * timing; set by the ViewModel, not by [buildGuideState].
     */
    val gridReady: Boolean = false,
    /** The selection the rows were built for (an opaque tag the screen compares). */
    val tag: Any? = null,
    /**
     * The grid has moved forward from the day's starting window (#146): Left can go back
     * and Back returns to the starting window before it does anything else.
     */
    val shifted: Boolean = false,
)

fun buildGuideState(
    channels: List<GuideChannel>,
    programsByKey: Map<String, List<GuideProgram>>,
    guideCounts: Map<String, Int>,
    xmltvMode: Boolean,
    day: GuideDay,
    nowUtcMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    /**
     * #10: rows already built for exactly these inputs (channels, programs, counts,
     * day, now). The details dialog changes none of those, so the ViewModel hands the
     * previous rows back instead of rebuilding every one.
     */
    prebuiltRows: List<GuideRow>? = null,
    /**
     * False until the first window of programmes and counts has answered: nothing is
     * known about the store yet, so it is "loading", never "No guide yet" (#43).
     */
    loaded: Boolean = true,
    /**
     * Whether the guide store holds any programme at all. "No guide yet" is about the
     * store, not about this selection: a category whose channels have no guide while
     * the store has data shows striped "No guide info" rows at row height instead.
     */
    storeHasGuide: Boolean = true,
    /**
     * #146: the window start when the grid has moved forward from [day]'s starting window
     * (half-hour aligned); null is the starting window. [day] is then the day the moved
     * grid is on, which the header's picker shows.
     */
    windowStartUtcMillis: Long? = null,
): GuideUiState {
    val base = GuideWindow.fromNow(nowUtcMillis, zone, day.dayOffset)
    val window = if (windowStartUtcMillis == null) base else GuideWindow(windowStartUtcMillis)

    val rows = prebuiltRows ?: channels.map { channel ->
        val key = channel.programKey(xmltvMode)
        val programs = key?.let { programsByKey[it] }.orEmpty()
        val count = key?.let { guideCounts[it] } ?: 0
        buildGuideRow(channel, programs, count, window, nowUtcMillis, zone)
    }

    val hasStoredGuide = channels.any { channel ->
        val key = channel.programKey(xmltvMode)
        key != null && (guideCounts[key] ?: 0) > 0
    }
    // Only an empty store is "No guide yet"; a selection without guide is striped rows.
    val isEmpty = loaded && !hasStoredGuide && !storeHasGuide

    return GuideUiState(
        day = day,
        clockLabel = formatHm(nowUtcMillis, zone),
        window = window,
        rows = rows,
        isEmptyState = isEmpty,
        showNowLine = day == GuideDay.TODAY && window.contains(nowUtcMillis),
        nowX = window.xOf(nowUtcMillis),
        ticks = window.tickInstants().mapIndexed { index, instant ->
            (index * HALF_HOUR_PX).toFloat() to formatHm(instant, zone)
        },
        shifted = windowStartUtcMillis != null,
    )
}

/** Grid geometry constants (spec #7 section 3.4), design px. */
const val CHANNEL_CELL_WIDTH_PX = 360
const val CHANNEL_CELL_GAP_PX = 8
const val ROW_HEIGHT_PX = 56
const val ROW_GAP_PX = 4
const val BLOCK_GAP_PX = 4
const val HALF_HOUR_PX = 340

const val NO_GUIDE_INFO = "No guide info"

// ------------------------------------------------------------ focus target

/**
 * The program the guide focuses when nothing is focused yet: first
 * program on now (a "current program"), else the first focusable program,
 * else null.
 */
fun defaultProgramFocusTarget(rows: List<GuideRow>): Pair<Int, GuideBlock.ProgramBlock>? {
    rows.forEachIndexed { index, row ->
        row.liveProgram?.takeIf { it.focusable }?.let { return index to it }
    }
    rows.forEachIndexed { index, row ->
        row.focusablePrograms.firstOrNull()?.let { return index to it }
    }
    return null
}
