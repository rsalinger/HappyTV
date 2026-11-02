package com.rysal.happytv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import android.util.Log
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.HappyTvDarkColors
import com.rysal.happytv.ui.theme.HappyTvPlaceholders
import com.rysal.happytv.ui.theme.HappyTvTypography
import com.rysal.happytv.ui.theme.LocalHappyTvDesign
import com.rysal.happytv.ui.theme.LocalHappyTvTypography
import com.rysal.happytv.ui.theme.happyTvFocusRing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The browse screens' atom set — one place for the pieces design spec
 * "Components" defines once and Live TV, Movies and Shows all reuse: the
 * inner-screen header, round icon buttons, the Search pill, popup menus,
 * empty states, buttons, chips, monograms and the bottom fade.
 *
 * Rules every atom here obeys, straight from the handoff:
 *  - Sizes come from [LocalHappyTvDesign] as **handoff px** (`d.px(96)`), so a
 *    1920 x 1080 artboard stays proportional on any TV density.
 *  - Text comes from [LocalHappyTvTypography] (Manrope 500-800, tabular).
 *  - The only focus treatment is [happyTvFocusRing]; nothing changes size,
 *    background or position when it takes focus.
 *  - "Long-press OK" is holding OK (~500 ms, [OkPressGate]) or the remote's
 *    Menu key — both open the surface's context menu, dashboard convention
 *    included (see `DashboardScreen.TileBody`).
 *
 * Live TV owns this file; Movies/Shows import from it (see `ui/catalog/`).
 */

// ------------------------------------------------------------------ helpers

/** Handoff px converter for the current window. */
@Composable
fun browseDesign() = LocalHappyTvDesign.current

/** The handoff type table for the current window. */
@Composable
fun browseType(): HappyTvTypography = LocalHappyTvTypography.current

/**
 * "1 movie", "2 movies", "1,432 channels": the count with its unit, singular for exactly one
 * (#173). [unit] is the plural noun ("channels", "movies", "shows", "categories").
 */
fun formatCount(value: Int, unit: String): String {
    val noun = if (value == 1) singularOf(unit) else unit
    return "${formatCount(value)} $noun"
}

private fun singularOf(plural: String): String = when {
    plural == "categories" -> "category"
    plural.endsWith("s") -> plural.dropLast(1)
    else -> plural
}

/** "1,432" — grouped, the same tabular figures every count uses. */
fun formatCount(value: Int): String {
    // Hand-rolled grouping: `NumberFormat.getIntegerInstance` per call cost ~250 ms of
    // main-thread time across one Live TV open on the Chromecast (ICU lookups, a new
    // formatter for every rail row and heading), for a thousands separator.
    if (value in 0..999) return value.toString()
    val digits = Math.abs(value.toLong()).toString()
    val out = StringBuilder(digits.length + digits.length / 3 + 1)
    if (value < 0) out.append('-')
    val first = digits.length % 3
    if (first > 0) out.append(digits, 0, first)
    var i = first
    while (i < digits.length) {
        if (out.isNotEmpty() && out.last() != '-') out.append(',')
        out.append(digits, i, i + 3)
        i += 3
    }
    return out.toString()
}

/**
 * "12 minutes ago" / "3 hours ago" — the dashboard's and the browse screens'
 * shared relative stamp.
 */
fun relativeTimeLabel(utcMillis: Long, nowUtcMillis: Long = System.currentTimeMillis()): String {
    if (utcMillis <= 0L) return "Never"
    val delta = (nowUtcMillis - utcMillis).coerceAtLeast(0L)
    return when {
        delta < 60_000L -> "just now"
        delta < 3_600_000L -> unitAgo(delta / 60_000L, "minute")
        delta < 86_400_000L -> unitAgo(delta / 3_600_000L, "hour")
        else -> unitAgo(delta / 86_400_000L, "day")
    }
}

/** "1 hour ago" / "3 hours ago": singular for exactly one. */
private fun unitAgo(n: Long, unit: String): String = if (n == 1L) "1 $unit ago" else "$n ${unit}s ago"

private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm")

/** A handoff-px position: `offsetPx(664, 206)` is the pane's corner on the artboard. */
@Composable
fun Modifier.offsetPx(xPx: Int, yPx: Int): Modifier {
    val d = browseDesign()
    return offset(x = d.px(xPx), y = d.px(yPx))
}

/**
 * The shared focusable surface: the one focus ring, OK = [onClick], and
 * long-press OK = [onLongClick].
 *
 * Long-press is two gestures (F-43): the remote's **Menu** key, and *holding*
 * OK (DPAD_CENTER / ENTER down, threshold [LONG_PRESS_OK_MILLIS], up) — the
 * Chromecast HD remote has no Menu button, so the hold is the one users get.
 * `combinedClickable` can't help here: it only knows touch long-press.
 *
 * The key handling sits in an `onPreviewKeyEvent` *ahead of* `clickable` so
 * it sees every event first and can swallow exactly the releases a hold must
 * not turn into a click (foundation fires `onClick` on enter-key up
 * unconditionally — see [OkPressGate]). A plain short press passes both
 * events straight through to `clickable`, unchanged.
 *
 * The chain order matters: the ring is applied *outside* the clip so a 4 px
 * ring plus its glow is never cut off by the element's own corner radius.
 *
 * @param ringColor the ring stroke. A surface filled with Accent passes
 * [HappyTvColors.focusRingOn] (near-white) so the ring never matches its own
 * fill — the F-33 "invisible focus on the orange button" bug.
 */
@Composable
fun Modifier.htvClickable(
    radiusPx: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    ringColor: Color = HappyTvColors.Accent,
    /**
     * For a surface that already tracks its own focus (the guide's channel cell, composed
     * for every row the D-pad scrolls in): skips the interaction-source collector and the
     * focus effect, two coroutines per row (#52). [onFocused] is not used with it.
     */
    focusedOverride: Boolean? = null,
): Modifier {
    val d = browseDesign()
    val radius = d.px(radiusPx)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused = if (focusedOverride != null) {
        focusedOverride
    } else {
        val collected by interactionSource.collectIsFocusedAsState()
        // Live TV's D-pad note: moving down the category list changes the category
        // *without* pressing OK, so some surfaces react to focus, not to clicks.
        LaunchedEffect(collected) {
            if (collected) onFocused?.invoke()
        }
        collected
    }
    return this
        .happyTvFocusRing(focused = isFocused, radius = radius, focusRingColor = ringColor)
        .clip(RoundedCornerShape(radius))
        .okHoldKeys(enabled = enabled, onLongClick = onLongClick)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
        .then(if (selected) Modifier.background(HappyTvColors.Surface) else Modifier)
}

/**
 * The remote's two long-press gestures (the Menu key, holding OK) and the release rules
 * around them, as one modifier; [OkPressGate] holds the decisions. Place it *ahead of*
 * `clickable` so it sees every key first. Shared by [htvClickable] and the dashboard
 * tiles (#145): after a hold, the release is swallowed so it neither clicks this node
 * nor the menu item that took focus.
 */
@Composable
fun Modifier.okHoldKeys(enabled: Boolean = true, onLongClick: (() -> Unit)?): Modifier {
    // F-43: the press gate must outlive the recompositions a menu open causes
    // (state write → recomposition mid-press), while its callbacks must never
    // go stale — hence the rememberUpdatedState indirection.
    val currentLongClick by rememberUpdatedState(onLongClick)
    val pressGate = remember { OkPressGate { currentLongClick?.invoke() } }
    val keyScope = rememberCoroutineScope()
    DisposableEffect(pressGate) { onDispose { pressGate.cancelHold() } }
    return onPreviewKeyEvent { event ->
        if (!enabled) return@onPreviewKeyEvent false
        val isEnterKey = event.key == Key.DirectionCenter ||
            event.key == Key.Enter ||
            event.key == Key.NumPadEnter
        when {
            // The Menu key: for remotes that have one (fires on down).
            event.key == Key.Menu && event.type == KeyEventType.KeyDown &&
                currentLongClick != null -> {
                currentLongClick?.invoke()
                true
            }
            // Hold OK: first down arms the 500 ms timer; every up is
            // decided by the gate (swallow after a hold, pass otherwise).
            isEnterKey && event.type == KeyEventType.KeyDown -> {
                val repeatCount = event.nativeKeyEvent.repeatCount
                if (repeatCount == 0) {
                    pressGate.cancelHold()
                    if (currentLongClick != null) {
                        pressGate.holdJob = keyScope.launch {
                            delay(LONG_PRESS_OK_MILLIS)
                            pressGate.onHoldElapsed()
                        }
                    }
                }
                pressGate.onEnterDown(
                    repeatCount,
                    longPressFlag = event.nativeKeyEvent.flags and
                        android.view.KeyEvent.FLAG_LONG_PRESS != 0,
                )
            }
            isEnterKey && event.type == KeyEventType.KeyUp -> {
                pressGate.cancelHold()
                pressGate.onEnterUp()
            }
            else -> false
        }
    }
}

/**
 * Ignore an enter-key release (and its repeats) this node never saw the press for, so a
 * hold that opened a menu cannot click the item that took focus mid-hold (#145). Place
 * ahead of `clickable`. See [OrphanEnterGuard].
 */
@Composable
fun Modifier.ignoreOrphanEnterUp(): Modifier {
    val guard = remember { OrphanEnterGuard() }
    return onPreviewKeyEvent { event ->
        val isEnterKey = event.key == Key.DirectionCenter ||
            event.key == Key.Enter ||
            event.key == Key.NumPadEnter
        when {
            !isEnterKey -> false
            event.type == KeyEventType.KeyDown -> guard.onDown(event.nativeKeyEvent.repeatCount)
            event.type == KeyEventType.KeyUp -> guard.onUp()
            else -> false
        }
    }
}

/** `htvClickable` for something that also needs its own fill. */
@Composable
fun Modifier.htvClickableSurface(
    radiusPx: Int,
    background: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    focusedOverride: Boolean? = null,
): Modifier = this
    .background(background, RoundedCornerShape(browseDesign().px(radiusPx)))
    .htvClickable(
        focusedOverride = focusedOverride,
        radiusPx = radiusPx,
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
        selected = selected,
        enabled = enabled,
        // Accent fill → near-white ring, so a focused primary button (Details
        // "Play", Settings confirm) never wears an Accent ring on an Accent
        // fill (F-33).
        ringColor = HappyTvColors.focusRingOn(background),
    )

// ------------------------------------------------------------------- header

/**
 * Compact inner-screen header (spec #7 §2): top 54, 1728 wide, **72 tall**.
 *
 * One row, nothing wraps, so nothing can clip: 64 px round back button, a
 * 24 gap, the title (36/44 700), a 16 gap, then the subtitle (24/32 500
 * Tertiary) on the **same line**, ellipsized when it runs out of room. On the
 * right, 24 apart: the screen's own controls ([trailing], e.g. the guide's
 * day picker), the clock, the Search pill and the more button — the last two
 * only when the screen supplies a handler.
 *
 * Used by Live TV, Settings, Movies/Shows categories, the poster grid and
 * details. The guide draws the same geometry with its own focus boxes
 * ([HtHeaderTitleLine], [HEADER_HEIGHT_PX]).
 */
@Composable
fun HtHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    moreEnabled: Boolean = true,
    /** #168: lets a screen hand focus back to the More options button when its menu closes. */
    moreFocusRequester: FocusRequester? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val d = browseDesign()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(d.px(HEADER_HEIGHT_PX)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Weighted so a long subtitle never pushes the right-side controls off
        // (F-35); the right Row stays unweighted and always fits.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HtRoundIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                // Nothing lies left of Back. Left is swallowed here so a directional search can
                // never leave the header for a node that is off screen or folded away (#117).
                modifier = Modifier.onPreviewKeyEvent { it.key == Key.DirectionLeft },
            )
            if (title.isNotEmpty() || subtitle.isNotEmpty()) {
                Spacer(Modifier.width(d.px(HEADER_GAP_PX)))
                HtHeaderTitleLine(
                    title = title,
                    subtitle = subtitle,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(d.px(HEADER_GAP_PX)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            trailing()
            HtClock()
            if (onSearchClick != null) HtSearchPill(onClick = onSearchClick)
            if (onMoreClick != null) {
                HtRoundIconButton(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "More options",
                    onClick = onMoreClick,
                    enabled = moreEnabled,
                    // Before the clickable surface in the chain, so it finds the button (see DESIGN.md).
                    modifier = if (moreFocusRequester != null) Modifier.focusRequester(moreFocusRequester) else Modifier,
                )
            }
        }
    }
}

/**
 * The header's title and subtitle on **one** line (spec #7 §2).
 *
 * Space is shared max-min fairly: the shorter of the two keeps its natural
 * width and the longer ellipsizes into what is left, so a long category name
 * in the title (poster grid) or in the subtitle (Live TV) both shrink without
 * ever wrapping or being clipped by the header's 72 px height.
 *
 * includeFontPadding=false: Manrope's natural line box is 1.366em, which would
 * push a 44 px line past the 72 px header (F-35).
 */
@Composable
fun HtHeaderTitleLine(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val d = browseDesign()
    val type = browseType()
    val noPadding = PlatformTextStyle(includeFontPadding = false)
    Layout(
        modifier = modifier,
        content = {
            Text(
                text = title,
                style = type.compactTitle.copy(platformStyle = noPadding),
                color = HappyTvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = type.compactCaption.copy(platformStyle = noPadding),
                color = HappyTvColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) { measurables, constraints ->
        val gap = if (subtitle.isEmpty() || title.isEmpty()) 0 else d.px(HEADER_TITLE_GAP_PX).roundToPx()
        val available = (constraints.maxWidth - gap).coerceAtLeast(0)
        val titleNatural = measurables[0].maxIntrinsicWidth(constraints.maxHeight)
        val subtitleNatural = measurables[1].maxIntrinsicWidth(constraints.maxHeight)
        val (titleWidth, subtitleWidth) = shareHeaderWidth(titleNatural, subtitleNatural, available)
        val titleP = measurables[0].measure(Constraints(maxWidth = titleWidth))
        val subtitleP = measurables[1].measure(Constraints(maxWidth = subtitleWidth))
        val lineHeight = maxOf(titleP.height, subtitleP.height)
        // Claim the whole slot the parent offers (the header gives it a weight).
        // Reporting only the content width made Compose centre the content in
        // the slot, pushing the title 300+ px away from the back button.
        val contentWidth = titleP.width + gap + subtitleP.width
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else contentWidth
        layout(width, lineHeight) {
            titleP.placeRelative(0, (lineHeight - titleP.height) / 2)
            subtitleP.placeRelative(titleP.width + gap, (lineHeight - subtitleP.height) / 2)
        }
    }
}

/**
 * Max-min fair split of [available] px between a title and a subtitle that
 * want [titleNatural] and [subtitleNatural]. Pure so the one-line rule is
 * unit-testable.
 */
internal fun shareHeaderWidth(titleNatural: Int, subtitleNatural: Int, available: Int): Pair<Int, Int> {
    if (titleNatural + subtitleNatural <= available) return titleNatural to subtitleNatural
    val half = available / 2
    return when {
        titleNatural <= half -> titleNatural to (available - titleNatural)
        subtitleNatural <= half -> (available - subtitleNatural) to subtitleNatural
        else -> half to (available - half)
    }
}

/** Inner-screen clock — compact 32/40 600, tabular, re-read every 30 s. */
@Composable
fun HtClock(modifier: Modifier = Modifier) {
    val type = browseType()
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val text = remember(tick) {
        clockFormatter.format(Instant.now().atZone(ZoneId.systemDefault()).toLocalTime())
    }
    Text(
        text = text,
        modifier = modifier,
        style = type.compactClock,
        color = HappyTvColors.TextSecondary,
    )
}

/** The compact 64 px round icon button (spec #7 §2; was 88). */
@Composable
fun HtRoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizePx: Int = HEADER_BUTTON_PX,
    iconPx: Int = 32,
    enabled: Boolean = true,
) {
    val d = browseDesign()
    Box(
        modifier = modifier
            .size(d.px(sizePx))
            .htvClickableSurface(
                radiusPx = sizePx / 2,
                background = HappyTvColors.Surface,
                onClick = onClick,
                enabled = enabled,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) HappyTvColors.TextPrimary else HappyTvColors.TextTertiary,
            modifier = Modifier.size(d.px(iconPx)),
        )
    }
}

/** Search pill for headers — 64 tall, Surface, 32 px icon + "Search" 28/36 700. */
@Composable
fun HtSearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val d = browseDesign()
    val type = browseType()
    Row(
        modifier = modifier
            .height(d.px(HEADER_BUTTON_PX))
            .htvClickableSurface(
                radiusPx = HEADER_BUTTON_PX / 2,
                background = HappyTvColors.Surface,
                onClick = onClick,
            )
            .padding(start = d.px(24), end = d.px(32)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = HappyTvColors.TextPrimary,
            modifier = Modifier.size(d.px(32)),
        )
        Spacer(Modifier.width(d.px(12)))
        Text(text = "Search", style = type.compactRowName, color = HappyTvColors.TextPrimary)
    }
}

/** The 128 px fade that closes every scrolling screen (design spec "Translucent layers"). */
@Composable
fun HtBottomFade(modifier: Modifier = Modifier, heightPx: Int = 128) {
    val d = browseDesign()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(d.px(heightPx))
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            HappyTvColors.ScrollFadeStart,
                            HappyTvColors.ScrollFadeEnd,
                        ),
                    ),
                )
            },
    )
}

// -------------------------------------------------------------------- menus

/** One 88 px popup-menu row: 40 px Secondary icon + 36/44 600 label, 20 apart. */
@Composable
fun HtMenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    iconTint: Color = HappyTvColors.TextSecondary,
    labelColor: Color = HappyTvColors.TextPrimary,
    focusRequester: FocusRequester? = null,
) {
    val d = browseDesign()
    val type = browseType()
    Row(
        modifier = modifier
            .height(d.px(if (caption != null) 112 else 88))
            // Before `htvClickable`: a FocusRequester only finds targets that
            // come *after* it in the chain, so asking for focus on one placed
            // behind the focusable is a silent no-op.
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .htvClickable(radiusPx = 16, onClick = onClick)
            .padding(horizontal = d.px(24)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(d.px(40)),
        )
        Spacer(Modifier.width(d.px(20)))
        Column {
            Text(text = label, style = type.bodySemibold, color = labelColor, maxLines = 1)
            if (caption != null) {
                Text(
                    text = caption,
                    style = type.caption,
                    color = HappyTvColors.TextTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The popup-menu surface (design spec "Components > Overlays"): 600 wide, padding
 * 16, radius 24, Raised, `0 32px 80px` shadow, a caption header naming the
 * item, then 88 px items. [anchorX]/[anchorY] are handoff px on the artboard.
 *
 * The scrim is *not* focusable — Back closes the menu and hands focus back to
 * whatever opened it, exactly as the handoff asks. Two focus hooks make that
 * true for a D-pad (F-43):
 *  - [initialFocus] takes the D-pad as soon as the menu opens (directional
 *    focus alone would keep it on the row behind the scrim);
 *  - Back is caught here first, while focus is inside the menu — the screens'
 *    `BackHandler` covers focus that never made it in.
 *
 * Without [initialFocus] the menu behaves as it always has: focus stays on
 * whatever opened it.
 */
@Composable
fun HtPopupMenu(
    title: String,
    onDismiss: () -> Unit,
    anchorX: Int,
    anchorY: Int,
    modifier: Modifier = Modifier,
    widthPx: Int = 600,
    initialFocus: FocusRequester? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val d = browseDesign()
    val type = browseType()
    if (initialFocus != null) {
        LaunchedEffect(initialFocus) { initialFocus.requestFocus() }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HappyTvColors.ScrimMenu)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back && event.type == KeyEventType.KeyDown) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .offsetPx(anchorX, anchorY)
                .width(d.px(widthPx))
                .background(HappyTvColors.Raised, RoundedCornerShape(d.px(24)))
                .padding(d.px(16)),
        ) {
            Text(
                text = title,
                style = type.captionSemibold,
                color = HappyTvColors.TextTertiary,
                modifier = Modifier.padding(
                    start = d.px(24),
                    end = d.px(24),
                    top = d.px(12),
                    bottom = d.px(8),
                ),
            )
            content()
        }
    }
}

// -------------------------------------------------------------- empty state

/**
 * The empty state (design spec "Components > Cards > Empty state"): a 96 px
 * outline icon, a 44/52 700 heading, 36/44 500 Secondary body copy and one
 * button 16 px below, left-aligned.
 */
@Composable
fun HtEmptyState(
    icon: ImageVector,
    heading: String,
    body: String,
    buttonLabel: String,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val d = browseDesign()
    val type = browseType()
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = HappyTvColors.TextTertiary,
            modifier = Modifier.size(d.px(96)),
        )
        Spacer(Modifier.height(d.px(24)))
        Text(text = heading, style = type.heading, color = HappyTvColors.TextPrimary)
        Spacer(Modifier.height(d.px(12)))
        Text(
            text = body,
            style = type.body,
            color = HappyTvColors.TextSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(d.px(16)))
        if (primary) {
            HtPrimaryButton(
                label = buttonLabel,
                onClick = onButtonClick,
                focusRequester = focusRequester,
            )
        } else {
            HtSecondaryButton(
                label = buttonLabel,
                onClick = onButtonClick,
                focusRequester = focusRequester,
            )
        }
    }
}

// ------------------------------------------------------------------ buttons

/** Primary button — accent, on-accent 36/44 800, 88 tall, optional leading icon. */
@Composable
fun HtPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    HtButton(
        label = label,
        onClick = onClick,
        containerColor = HappyTvColors.Accent,
        labelColor = if (enabled) HappyTvColors.OnAccent else HappyTvColors.TextTertiary,
        iconTint = if (enabled) HappyTvColors.OnAccent else HappyTvColors.TextTertiary,
        icon = icon,
        enabled = enabled,
        modifier = modifier,
        focusRequester = focusRequester,
    )
}

/** Secondary button — Overlay, Primary 36/44 700, 88 tall, optional leading icon. */
@Composable
fun HtSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = HappyTvColors.TextPrimary,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    HtButton(
        label = label,
        onClick = onClick,
        containerColor = HappyTvColors.Overlay,
        labelColor = if (enabled) HappyTvColors.TextPrimary else HappyTvColors.TextTertiary,
        iconTint = if (enabled) iconTint else HappyTvColors.TextTertiary,
        icon = icon,
        enabled = enabled,
        modifier = modifier,
        focusRequester = focusRequester,
    )
}

@Composable
private fun HtButton(
    label: String,
    onClick: () -> Unit,
    containerColor: Color,
    labelColor: Color,
    iconTint: Color,
    icon: ImageVector?,
    enabled: Boolean,
    modifier: Modifier,
    focusRequester: FocusRequester?,
) {
    val d = browseDesign()
    val type = browseType()
    Row(
        modifier = modifier
            .height(d.px(88))
            // The requester must precede the focusable it targets: placed after
            // htvClickableSurface it binds to nothing and requestFocus() is a
            // silent no-op (the Settings confirmation dialogs never took focus).
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .htvClickableSurface(radiusPx = 44, background = containerColor, onClick = onClick, enabled = enabled)
            .padding(horizontal = if (icon != null) d.px(40) else d.px(48)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(d.px(36)),
            )
            Spacer(Modifier.width(d.px(16)))
        }
        Text(text = label, style = type.bodyExtraBold, color = labelColor, maxLines = 1)
    }
}

// -------------------------------------------------------------------- chips

/**
 * Replay chip — 40 tall, Overlay, 28 px replay icon + "Replay" 28/36 600; the
 * [compact] form (list rows, spec #7 section 3.5) is 36 tall with a 24 px icon
 * and 24/32 600 text.
 */
@Composable
fun ReplayChip(modifier: Modifier = Modifier, compact: Boolean = false) {
    val d = browseDesign()
    val type = browseType()
    val height = if (compact) 36 else 40
    Row(
        modifier = modifier
            .height(d.px(height))
            .background(HappyTvColors.Overlay, RoundedCornerShape(d.px(height / 2)))
            .padding(start = d.px(12), end = d.px(16)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Replay,
            contentDescription = null,
            tint = HappyTvColors.TextSecondary,
            modifier = Modifier.size(d.px(if (compact) 24 else 28)),
        )
        Spacer(Modifier.width(d.px(8)))
        Text(
            text = "Replay",
            style = if (compact) type.compactCaptionSemibold else type.captionSemibold,
            color = HappyTvColors.TextSecondary,
            maxLines = 1,
        )
    }
}

/** Genre tag on details — 48 tall, Overlay, 28/36 600 Secondary. */
@Composable
fun GenreTag(label: String, modifier: Modifier = Modifier) {
    val d = browseDesign()
    val type = browseType()
    Box(
        modifier = modifier
            .height(d.px(48))
            .background(HappyTvColors.Overlay, RoundedCornerShape(d.px(24)))
            .padding(horizontal = d.px(20)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = type.captionSemibold, color = HappyTvColors.TextSecondary, maxLines = 1)
    }
}

// -------------------------------------------------------------- placeholders

/**
 * Channel monograms (design spec "Placeholder colors"): a colored rectangle with
 * the channel's initials — seven background/letter pairs, picked from the
 * name so a channel always looks the same.
 */
@Composable
fun HtMonogram(
    name: String,
    widthPx: Int,
    heightPx: Int,
    radiusPx: Int,
    modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle? = null,
) {
    val d = browseDesign()
    val type = browseType()
    val palette = HappyTvPlaceholders.MonogramPairs[
        (name.hashCode().let { if (it < 0) -it else it }) % HappyTvPlaceholders.MonogramPairs.size
    ]
    Box(
        modifier = modifier
            .size(width = d.px(widthPx), height = d.px(heightPx))
            .background(palette.first, RoundedCornerShape(d.px(radiusPx))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = monogramOf(name),
            style = textStyle ?: type.heading,
            color = palette.second,
            fontWeight = FontWeight.W800,
        )
    }
}

/** "Northbay News" -> "NN"; "101" -> "10". */
fun monogramOf(name: String): String {
    val words = name.split(' ', '-', '.', '_').filter { it.isNotBlank() }
    if (words.isEmpty()) return "?"
    val letters = if (words.size == 1) {
        words[0].take(2)
    } else {
        "${words[0].first()}${words[1].first()}"
    }
    return letters.uppercase(Locale.US)
}

/** Poster art stand-in - mock artwork is fictional by design (handoff "Rules to keep"). */
@Composable
fun HtPosterArt(name: String, modifier: Modifier = Modifier, flat: Boolean = false) {
    val color = posterPlaceholderColor(name)
    // [flat]: one solid colour. The poster grid paints hundreds of these while scrolling and a
    // gradient per card under the image is paint it never needs (#72).
    Box(
        modifier = if (flat) {
            modifier.background(color)
        } else {
            modifier.background(Brush.verticalGradient(listOf(color, HappyTvDarkColors.Ground)))
        },
    )
}

/** The placeholder colour for [name]: stable per title. */
internal fun posterPlaceholderColor(name: String): Color = HappyTvPlaceholders.PosterPalette[
    (name.hashCode().let { if (it < 0) -it else it }) % HappyTvPlaceholders.PosterPalette.size
]

/**
 * Poster artwork loaded from a panel-provided URL, falling back to the
 * [HtPosterArt] placeholder when there's no URL or the load fails (F-49).
 *
 * Panel rows carry the poster in `stream_icon` for VOD (TMDB
 * `https://image.tmdb.org/t/p/...`) and in `cover` for series - both HTTPS,
 * so the default Coil ImageLoader is enough (cleartext policy gates only
 * `http://` and would need a `networkSecurityConfig` override to matter,
 * which it doesn't for TMDB).
 *
 * A failed request logs one line under the `HtPosterImage` tag (the URL and the throwable):
 * Coil swallows fetch/decode errors by design and the grid just shows the placeholder, so
 * logcat is the only way to tell "no URL reached the composable" from "Coil failed on a URL
 * we did hand it".
 *
 * @param posterUrl The URL to load; null/blank paints only the placeholder.
 * @param name Used for both the a11y content description and the fallback
 *   gradient's stable per-item color.
 * @param sizePx The size the art is drawn at, in device pixels (#72). With it Coil decodes at
 *   that size, not the source's, which is what keeps a 7-across grid scrolling smoothly.
 * @param flatPlaceholder paint the placeholder as one solid colour (see [HtPosterArt]).
 */
@Composable
fun HtPosterImage(
    posterUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
    sizePx: androidx.compose.ui.unit.IntSize? = null,
    flatPlaceholder: Boolean = false,
) {
    val cleanUrl = posterUrl?.takeIf { it.isNotBlank() }
    if (flatPlaceholder) {
        // The grid's path: one node per card, and the placeholder colour is Coil's own placeholder,
        // not a second box painted under the image (every pixel would be drawn twice while scrolling).
        val color = remember(name) { posterPlaceholderColor(name) }
        if (cleanUrl == null) {
            Box(modifier = modifier.background(color))
        } else {
            val context = LocalContext.current
            val request = remember(cleanUrl, sizePx, name) {
                ImageRequest.Builder(context)
                    .data(cleanUrl)
                    .apply { if (sizePx != null) size(sizePx.width, sizePx.height) }
                    .crossfade(false)
                    .placeholder(android.graphics.drawable.ColorDrawable(color.toArgb()))
                    .error(android.graphics.drawable.ColorDrawable(color.toArgb()))
                    .listener(
                        onError = { _, result ->
                            Log.e(
                                POSTER_LOG_TAG,
                                "failed name=$name url=${com.rysal.happytv.ui.player.PlayerUrlBuilder.redactCredentials(cleanUrl)}: ${result.throwable}",
                            )
                        },
                    )
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = modifier,
            )
        }
        return
    }
    Box(modifier = modifier) {
        // Always paint the fallback first: if the URL is blank or Coil errors
        // (bad host, non-image body), the placeholder shows through instead
        // of an empty hole.
        HtPosterArt(name = name, modifier = Modifier.fillMaxSize(), flat = false)
        if (cleanUrl != null) {
            val context = LocalContext.current
            // Built once per (url, size): a recomposition must not make a new request.
            val request = remember(cleanUrl, sizePx, name) {
                ImageRequest.Builder(context)
                    .data(cleanUrl)
                    .apply { if (sizePx != null) size(sizePx.width, sizePx.height) }
                    .crossfade(false)
                    .listener(
                        onError = { _, result ->
                            Log.e(
                                POSTER_LOG_TAG,
                                "failed name=$name url=${com.rysal.happytv.ui.player.PlayerUrlBuilder.redactCredentials(cleanUrl)}: ${result.throwable}",
                            )
                        },
                    )
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** logcat tag for [HtPosterImage]'s per-request lines. */
private const val POSTER_LOG_TAG = "HtPosterImage"

// ------------------------------------------------------------------- shared

/** Compact inner-screen header height, artboard px (spec #7 section 2; was 112). */
internal const val HEADER_HEIGHT_PX = 72

/** Header top (safe area) and the artboard y where bodies may start. */
internal const val HEADER_TOP_PX = 54
internal const val HEADER_BOTTOM_PX = HEADER_TOP_PX + HEADER_HEIGHT_PX

/** Round back / more button and Search pill height. */
internal const val HEADER_BUTTON_PX = 64

/** Gap between header controls and after the back button (spec #7 section 2). */
internal const val HEADER_GAP_PX = 24

/** Gap between the title and the subtitle on their shared line. */
internal const val HEADER_TITLE_GAP_PX = 16
