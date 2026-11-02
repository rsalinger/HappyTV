package com.rysal.happytv.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.browseType
import com.rysal.happytv.ui.browse.htvClickable
import com.rysal.happytv.ui.theme.HappyTvColors

/**
 * Settings' control set — design spec §"Components > Selection controls" and
 * §"Components > Lists and rows" at handoff px, laid out the way
 * `design-source/Settings.dc.html` pins them.
 *
 * Two rules every piece here keeps:
 *  - The whole row is the focus stop for switches and actions (the handoff
 *    says so literally); for a segmented control, **each segment** is its own
 *    stop, so the row itself never takes focus.
 *  - Nothing changes size, shape or position on focus — only the shared focus
 *    ring from `ui/browse` (which is applied last, so it is never clipped).
 */

private const val ROW_RADIUS_PX = 24
/** Compact density (spec #7 section 1): rows are 88 tall, was 112. */
private const val ROW_HEIGHT_MIN_PX = 88
private const val ROW_PAD_X_PX = 32
private const val ROW_PAD_PLAIN_Y_PX = 16
/** Control rows inset 12 so a 64 px control still reads as 88 tall. */
private const val ROW_PAD_CONTROL_Y_PX = 12
private const val ROW_CONTROL_GAP_PX = 32
private const val CHEVRON_PX = 40

// ------------------------------------------------------------ vertical focus

/**
 * One vertical stop in a Settings pane: a single row, or a whole segmented row.
 * Moving onto the stop lands on [entry] (for a segmented row: the selected
 * segment); every focus target in [members] leaves the stop up or down.
 */
class SettingsFocusStop(val entry: FocusRequester, val members: List<FocusRequester>)

/**
 * #59: the pane's rows in order. Compose's geometric search skipped the
 * segmented rows (their segments sit at the right edge, the toggles span the
 * pane) and could fall out of the pane into the rail, so vertical D-pad moves
 * are explicit: Up and Down go to the neighbouring stop, and Down on the last
 * stop stays put instead of leaking to the rail.
 */
class SettingsFocusChain(
    val stops: List<SettingsFocusStop>,
    /**
     * #162: the rail row of the section the pane belongs to. Left from a single-control row, or
     * from the first segment of a segmented row, goes here and nowhere else.
     */
    val leftTo: FocusRequester? = null,
) {
    fun indexOf(requester: FocusRequester): Int = stops.indexOfFirst { requester in it.members }
}

val LocalSettingsFocusChain: ProvidableCompositionLocal<SettingsFocusChain?> = compositionLocalOf { null }

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Modifier.settingsVerticalFocus(requester: FocusRequester?): Modifier {
    val chain = LocalSettingsFocusChain.current ?: return this
    if (requester == null) return this
    val index = chain.indexOf(requester)
    if (index < 0) return this
    val above = chain.stops.getOrNull(index - 1)?.entry
    val below = chain.stops.getOrNull(index + 1)?.entry ?: FocusRequester.Cancel
    val leftEdge = chain.stops[index].members.first() == requester
    val rail = chain.leftTo
    return this.focusProperties {
        if (above != null) up = above
        down = below
        if (leftEdge && rail != null) left = rail
    }
}

// --------------------------------------------------------------------- switch

/**
 * The switch (design spec "Selection controls"): 96 × 52 track (radius 26) with a
 * 40 px knob inset 6. On = accent track, on-accent knob at the right; off =
 * `#453D36` track, secondary knob at the left. Slides in 200 ms.
 */
@Composable
fun HtSwitch(on: Boolean, modifier: Modifier = Modifier) {
    val d = browseDesign()
    val track = d.px(SWITCH_TRACK_W_PX)
    val knob = d.px(SWITCH_KNOB_PX)
    val inset = d.px(SWITCH_KNOB_INSET_PX)
    val travel by animateDpAsState(
        targetValue = if (on) track - knob - inset else inset,
        animationSpec = tween(durationMillis = SWITCH_SLIDE_MS),
        label = "settings-switch-knob",
    )
    Box(
        modifier = modifier
            .size(width = track, height = d.px(SWITCH_TRACK_H_PX))
            .background(
                if (on) HappyTvColors.Accent else HappyTvColors.Line,
                RoundedCornerShape(d.px(SWITCH_TRACK_H_PX / 2)),
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = travel)
                .size(knob)
                .background(
                    if (on) HappyTvColors.OnAccent else HappyTvColors.TextSecondary,
                    RoundedCornerShape(knob / 2),
                ),
        )
    }
}

private const val SWITCH_TRACK_W_PX = 96
private const val SWITCH_TRACK_H_PX = 52
private const val SWITCH_KNOB_PX = 40
private const val SWITCH_KNOB_INSET_PX = 6
private const val SWITCH_SLIDE_MS = 200

// ---------------------------------------------------------- segmented control

/**
 * The segmented control (design spec "Selection controls"): a pill well with 8 px
 * padding — Surface generally, **Ground inside a settings row** — holding pill
 * segments. Selected: Overlay fill, Primary 700. Others: transparent,
 * Secondary 600. Every segment is its own focus stop; OK selects.
 *
 * @param contentDescription names the group for screen readers ("Stream
 *   format", "Search in"); each segment also carries its own label.
 * @param focusRequesters optional per-segment requesters (the caller needs
 *   them to park focus when a row appears or disappears).
 */
@Composable
fun HtSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    wellColor: Color = HappyTvColors.Surface,
    segmentHeightPx: Int = 72,
    segmentPaddingPx: Int = 28,
    compact: Boolean = false,
    focusRequesters: List<FocusRequester?> = emptyList(),
) {
    val d = browseDesign()
    val type = browseType()
    val wellShape = RoundedCornerShape(d.px(segmentHeightPx / 2 + SEGMENTED_WELL_PAD_PX))
    Row(
        modifier = modifier
            .background(wellColor, wellShape)
            .padding(d.px(SEGMENTED_WELL_PAD_PX))
            .semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(d.px(SEGMENTED_GAP_PX)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val requester = focusRequesters.getOrNull(index)
            Box(
                modifier = Modifier
                    .height(d.px(segmentHeightPx))
                    .background(
                        if (selected) HappyTvColors.Overlay else Color.Transparent,
                        RoundedCornerShape(d.px(segmentHeightPx / 2)),
                    )
                    .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                    .settingsVerticalFocus(requester)
                    .htvClickable(radiusPx = segmentHeightPx / 2, onClick = { onSelect(index) })
                    .padding(horizontal = d.px(segmentPaddingPx)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = if (compact) {
                        type.compactCaptionSemibold.copy(
                            fontWeight = if (selected) FontWeight.W700 else FontWeight.W600,
                        )
                    } else if (selected) {
                        type.captionBold
                    } else {
                        type.captionSemibold
                    },
                    color = if (selected) HappyTvColors.TextPrimary else HappyTvColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

private const val SEGMENTED_WELL_PAD_PX = 8
private const val SEGMENTED_GAP_PX = 8

// ------------------------------------------------------------------------ rows

/** Info row: label left, value right, focusable but inert on OK. */
@Composable
fun SettingsInfoRow(
    label: String,
    value: String,
    onFocused: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val d = browseDesign()
    SettingsRowShell(
        modifier = modifier,
        onFocused = onFocused,
        focusRequester = focusRequester,
        controlPad = false,
    ) {
        Text(
            text = label,
            style = browseType().compactRowName,
            color = HappyTvColors.TextPrimary,
            maxLines = 1,
        )
        Text(
            text = value,
            style = browseType().compactBody,
            color = HappyTvColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = d.px(ROW_CONTROL_GAP_PX)),
        )
    }
}

/** Toggle row: label over caption on the left, the switch on the right. */
@Composable
fun SettingsSwitchRow(
    label: String,
    caption: String,
    on: Boolean,
    onToggle: () -> Unit,
    onFocused: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    SettingsRowShell(
        modifier = modifier,
        onFocused = onFocused,
        focusRequester = focusRequester,
        onClick = onToggle,
        controlPad = true,
    ) {
        // weight(1f): a long label must not squeeze the switch out of the row.
        LabelAndCaption(label = label, caption = caption, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(browseDesign().px(ROW_CONTROL_GAP_PX)))
        HtSwitch(on = on)
    }
}

/** Action row: label over caption, a 40 px chevron on the right. */
@Composable
fun SettingsActionRow(
    label: String,
    caption: String,
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    SettingsRowShell(
        modifier = modifier,
        onFocused = onFocused,
        focusRequester = focusRequester,
        onClick = onClick,
    ) {
        LabelAndCaption(label = label, caption = caption)
        Spacer(Modifier.width(browseDesign().px(ROW_CONTROL_GAP_PX)))
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = HappyTvColors.TextSecondary,
            modifier = Modifier.size(browseDesign().px(CHEVRON_PX)),
        )
    }
}

/**
 * Segmented row: label over caption, the control on the right. The row itself
 * is not focusable — its segments are.
 */
@Composable
fun SettingsSegmentedRow(
    label: String,
    caption: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    focusRequesters: List<FocusRequester?> = emptyList(),
) {
    val d = browseDesign()
    Row(
        modifier = modifier
            .heightIn(min = d.px(ROW_HEIGHT_MIN_PX))
            .background(HappyTvColors.Surface, RoundedCornerShape(d.px(ROW_RADIUS_PX)))
            .padding(
                start = d.px(ROW_PAD_X_PX),
                top = d.px(ROW_PAD_CONTROL_Y_PX),
                end = d.px(ROW_PAD_CONTROL_Y_PX),
                bottom = d.px(ROW_PAD_CONTROL_Y_PX),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label takes what the control leaves, so every option fits ("Last
        // category" was clipped to "Last" when the label claimed its full width).
        LabelAndCaption(label = label, caption = caption, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(d.px(ROW_CONTROL_GAP_PX)))
        HtSegmented(
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            contentDescription = contentDescription,
            wellColor = HappyTvColors.Ground,
            segmentHeightPx = 56,
            segmentPaddingPx = 24,
            compact = true,
            focusRequesters = focusRequesters,
        )
    }
}

/**
 * Text-field row (the XMLTV link): label over caption, then the 620 × 80
 * settings link field. [field] draws the field itself so the caller owns its
 * focus and the IME.
 */
@Composable
fun SettingsFieldRow(
    label: String,
    caption: String,
    modifier: Modifier = Modifier,
    field: @Composable () -> Unit,
) {
    val d = browseDesign()
    Row(
        modifier = modifier
            .heightIn(min = d.px(ROW_HEIGHT_MIN_PX))
            .background(HappyTvColors.Surface, RoundedCornerShape(d.px(ROW_RADIUS_PX)))
            .padding(
                start = d.px(ROW_PAD_X_PX),
                top = d.px(ROW_PAD_CONTROL_Y_PX),
                end = d.px(ROW_PAD_CONTROL_Y_PX),
                bottom = d.px(ROW_PAD_CONTROL_Y_PX),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelAndCaption(label = label, caption = caption)
        Spacer(Modifier.width(d.px(ROW_CONTROL_GAP_PX)))
        field()
    }
}

// --------------------------------------------------------------------- shared

/**
 * The row frame every settings row shares (design spec "Lists and rows": at least
 * 112 tall, radius 24, Surface, padding 20 32 — 16 for control rows).
 */
@Composable
private fun SettingsRowShell(
    modifier: Modifier,
    onFocused: (() -> Unit)?,
    focusRequester: FocusRequester?,
    onClick: (() -> Unit)? = null,
    controlPad: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val d = browseDesign()
    val padY = if (controlPad) ROW_PAD_CONTROL_Y_PX else ROW_PAD_PLAIN_Y_PX
    Row(
        modifier = modifier
            .heightIn(min = d.px(ROW_HEIGHT_MIN_PX))
            .background(HappyTvColors.Surface, RoundedCornerShape(d.px(ROW_RADIUS_PX)))
            // Every row is a focus stop: actions and toggles fire on OK, info
            // rows do nothing ("Info rows are focusable … but do nothing on OK").
            // Requester before the focusable it targets, or requestFocus() is a no-op.
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .settingsVerticalFocus(focusRequester)
            .htvClickable(radiusPx = ROW_RADIUS_PX, onClick = onClick ?: { }, onFocused = onFocused)
            .padding(
                start = d.px(ROW_PAD_X_PX),
                top = d.px(padY),
                end = d.px(ROW_PAD_X_PX),
                bottom = d.px(padY),
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Label 28/36 700 over caption 24/32 500 tertiary (compact) (design spec "Settings row"). */
@Composable
private fun LabelAndCaption(label: String, caption: String, modifier: Modifier = Modifier) {
    val type = browseType()
    Column(modifier = modifier) {
        Text(text = label, style = type.compactRowName, color = HappyTvColors.TextPrimary, maxLines = 1)
        Text(text = caption, style = type.compactCaption, color = HappyTvColors.TextTertiary, maxLines = 2)
    }
}

/** 620 × 80, radius 16, Overlay — design spec "Text fields > Settings link field". */
@Composable
fun Modifier.htvSettingsFieldShape(): Modifier {
    val d = browseDesign()
    return this
        .width(d.px(XMLTV_FIELD_W_PX))
        .height(d.px(XMLTV_FIELD_H_PX))
        .clip(RoundedCornerShape(d.px(XMLTV_FIELD_RADIUS_PX)))
        .background(HappyTvColors.Overlay)
        .padding(horizontal = d.px(XMLTV_FIELD_PAD_X_PX))
}

private const val XMLTV_FIELD_W_PX = 620
private const val XMLTV_FIELD_H_PX = 80
private const val XMLTV_FIELD_RADIUS_PX = 16
private const val XMLTV_FIELD_PAD_X_PX = 24
