package com.rysal.happytv.ui.login

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.HappyTvIcons
import com.rysal.happytv.ui.theme.LocalHappyTvDesign
import com.rysal.happytv.ui.theme.LocalHappyTvTypography
import com.rysal.happytv.ui.theme.happyTvFocusRing
import kotlinx.coroutines.delay

/**
 * The sign-in screen's widgets — one per component in design spec
 * "Components": buttons, text fields, the checkbox row and the status line.
 *
 * Every focusable piece uses [happyTvFocusRing], so the screen has exactly
 * one focus treatment, and every size goes through
 * [com.rysal.happytv.ui.theme.LocalHappyTvDesign] so it stays proportional to
 * the 1920 x 1080 artboard.
 */

// ------------------------------------------------------------------- buttons

/**
 * Primary button — accent fill, on-accent 36/44 800, pill.
 *
 * @param widthPx fixed width in handoff px (360 for Sign in / Retry); omit to
 * hug the label with the handoff's 0 48 padding (the sheet's buttons).
 */
@Composable
fun TvPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    widthPx: Int? = null,
    focusRequester: FocusRequester? = null,
    contentDescription: String? = null,
) {
    TvButton(
        label = label,
        onClick = onClick,
        containerColor = HappyTvColors.Accent,
        labelColor = HappyTvColors.OnAccent,
        labelStyle = LocalHappyTvTypography.current.bodyExtraBold,
        modifier = modifier,
        widthPx = widthPx,
        focusRequester = focusRequester,
        contentDescription = contentDescription,
    )
}

/** Secondary button — overlay fill, primary 36/44 700, pill. */
@Composable
fun TvSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    widthPx: Int? = null,
    focusRequester: FocusRequester? = null,
    contentDescription: String? = null,
) {
    TvButton(
        label = label,
        onClick = onClick,
        containerColor = HappyTvColors.Overlay,
        labelColor = HappyTvColors.TextPrimary,
        labelStyle = LocalHappyTvTypography.current.bodyBold,
        modifier = modifier,
        widthPx = widthPx,
        focusRequester = focusRequester,
        contentDescription = contentDescription,
    )
}

/**
 * The "Sign in" button while submitting: overlay fill, no label, a 6 px
 * indeterminate bar inset 48 px from each side (design spec "Buttons", Submitting).
 */
@Composable
fun TvSubmittingButton(modifier: Modifier = Modifier) {
    val d = LocalHappyTvDesign.current
    val radius = d.px(BUTTON_HEIGHT_PX / 2)
    Box(
        modifier = modifier
            .size(d.px(SIGN_IN_WIDTH_PX), d.px(BUTTON_HEIGHT_PX))
            .clip(RoundedCornerShape(radius))
            .background(HappyTvColors.Overlay)
            .semantics { contentDescription = "Signing in" },
        contentAlignment = Alignment.Center,
    ) {
        val transition = rememberInfiniteTransition(label = "submitting")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                // design spec "Motion": a segment 40% of the track wide slides from
                // -100% to +250% of its own width, 1400 ms, cubic-bezier(0.4,0,0.2,1).
                animation = tween(
                    durationMillis = INDETERMINATE_MS,
                    easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f),
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "submitting-progress",
        )
        val inset = d.px(BUTTON_INSET_PX)
        val trackHeight = d.px(PROGRESS_HEIGHT_PX)
        val trackColor = HappyTvColors.ProgressTrack
        val accent = HappyTvColors.Accent
        Box(
            modifier = Modifier
                .padding(horizontal = inset)
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(trackColor)
                .drawWithContent {
                    drawContent()
                    val track = size.width
                    val segment = track * SEGMENT_FRACTION
                    // -100% → +250% of the segment's own width.
                    val x = -segment + (track * 1.5f + segment) * progress
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(x, 0f),
                        size = Size(segment, size.height),
                        cornerRadius = CornerRadius(size.height / 2),
                    )
                },
        )
    }
}

/**
 * Pill-shaped text link ("Common URL formats") — transparent fill, accent
 * 28/36 700, 44 tall with the handoff's 0 12 padding.
 */
@Composable
fun TvTextLink(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val radius = d.px(TEXT_LINK_HEIGHT_PX / 2)
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(d.px(TEXT_LINK_HEIGHT_PX))
            // Ring first — see [happyTvFocusRing]: a clip above it would erase
            // the ring and the glow (F-33).
            .happyTvFocusRing(focused = focused, radius = radius)
            .clip(RoundedCornerShape(radius))
            .background(Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .semantics { role = Role.Button; contentDescription = label }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionCenter) {
                    onClick(); true
                } else false
            }
            .padding(horizontal = d.px(TEXT_LINK_PADDING_PX)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = label, style = typography.captionBold, color = HappyTvColors.Accent, maxLines = 1)
    }
}

// --------------------------------------------------------------- text field

/**
 * A sign-in text field — 96 tall, radius 16, overlay fill, padding 0 28,
 * 36/44 500 text, tertiary placeholder, and the handoff's error treatment:
 * a 3 px inset error outline *inside* the focus ring.
 *
 * Typing uses the system keyboard and nothing else (handoff rules: no custom
 * keyboard). The field is a focus stop that shows the value; OK turns it into
 * a real editable field and opens Gboard, so D-pad traversal across the form
 * never pops the keyboard by itself.
 *
 * Password fields are masked with 0.1 em letter spacing ([masked]).
 *
 * Note on `android:privateImeOptions="horizontalAlignment=right"`: the handoff
 * asks for it so the TV keyboard floats over the saved-server list rather than
 * the form. Compose 1.6 has no public API for `EditorInfo.privateImeOptions`
 * (it lands in `KeyboardOptions.privateOptions` in Compose 1.9); tracked for
 * v1.1 rather than reached through reflection.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    accessibleName: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    masked: Boolean = isPassword,
    error: String? = null,
    focusRequester: FocusRequester? = null,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val radius = d.px(FIELD_RADIUS_PX)
    val keyboard = LocalSoftwareKeyboardController.current

    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    // The field's own focus stop, so leaving editing can put focus back on it
    // (the removed editor takes focus with it, and a screen with no focus owner
    // hands the next D-pad key to the first stop on screen, Server URL).
    val containerFocus = remember { FocusRequester() }
    var restoreFocus by remember { mutableStateOf(false) }
    var editorEverFocused by remember { mutableStateOf(false) }
    val editorFocus = remember { FocusRequester() }

    // OK on a field opens the system keyboard (handoff sticky note).
    val startEditing = {
        editorEverFocused = false
        editing = true
    }
    LaunchedEffect(editing) {
        if (editing) {
            editorFocus.requestFocus()
            // The editor lands in the focus system a frame after the
            // container's focusable modifier is switched off; asking for the
            // keyboard before that binds nothing (mHaveConnection=false on
            // device) and the field silently refuses to accept text.
            delay(KEYBOARD_SETTLE_MS)
            if (editorEverFocused) {
                keyboard?.show()
            } else {
                // The editor never took focus — back out so the D-pad keeps
                // working instead of stranding the screen with no focus stop.
                editing = false
            }
        }
    }

    // Runs once the container is focusable again (editing is false).
    LaunchedEffect(restoreFocus, editing) {
        if (restoreFocus && !editing) {
            restoreFocus = false
            runCatching { containerFocus.requestFocus() }
        }
    }

    val horizontalPadding = d.px(FIELD_PADDING_PX)
    val outlineStroke = d.px(ERROR_OUTLINE_PX)

    Box(
        modifier = modifier
            .height(d.px(FIELD_HEIGHT_PX))
            // Ring first — a clip above it would erase the ring and the glow
            // (F-33).
            .happyTvFocusRing(focused = focused || editing, radius = radius)
            .clip(RoundedCornerShape(radius))
            .background(HappyTvColors.Overlay)
            .drawWithContent {
                drawContent()
                if (error != null) {
                    // 3 px inset error outline, inside the ring; a focused
                    // error field shows both (design spec "Focus ring").
                    val inset = outlineStroke.toPx()
                    drawRoundRect(
                        color = HappyTvColors.StateError,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = CornerRadius(radius.toPx() - inset),
                        style = Stroke(width = inset),
                    )
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusRequester(containerFocus)
            .focusable(enabled = !editing)
            // #165: the field's name for screen readers and the UI dump. The visible
            // "Username"/"Password" labels sit beside the field, not inside it, so an
            // empty field exposed nothing. The value is never put here (a password
            // must not be read out).
            .semantics {
                contentDescription = if (error != null) "$accessibleName. $error" else accessibleName
            }
            // #187: with the keyboard up the system hands Back to the keyboard
            // first, so the preview handler below never sees it and the field
            // stayed in editing after the keyboard hid. This hook runs before
            // the keyboard does: end editing here and hide it ourselves.
            .onPreInterceptKeyBeforeSoftKeyboard { event ->
                if (editing && event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) {
                        editing = false; restoreFocus = true; keyboard?.hide()
                    }
                    true
                } else false
            }
            .onPreviewKeyEvent { event ->
                val isOk = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                when {
                    !editing && event.type == KeyEventType.KeyUp && isOk -> {
                        startEditing(); true
                    }
                    editing && event.type == KeyEventType.KeyUp && event.key == Key.Back -> {
                        editing = false; restoreFocus = true; keyboard?.hide(); true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val base: TextStyle = typography.body
        val valueStyle = base.copy(
            color = HappyTvColors.TextPrimary,
            letterSpacing = if (masked) d.sp(PASSWORD_LETTER_SPACING_EM * baseFontSizeSp) else TextUnit.Unspecified,
        )
        val placeholderStyle = base.copy(color = HappyTvColors.TextPlaceholder)

        if (editing) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = valueStyle,
                singleLine = true,
                cursorBrush = SolidColor(HappyTvColors.Accent),
                visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .focusRequester(editorFocus)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            editorEverFocused = true
                        } else if (editorEverFocused) {
                            // Only leave editing once the editor really held
                            // focus: the container's focusable switch fires one
                            // unfocused event before the editor attaches, and
                            // cancelling there aborts editing before the
                            // keyboard can bind.
                            editing = false
                        }
                    },
            )
        } else {
            val shown = when {
                value.isEmpty() -> placeholder
                masked -> "•".repeat(value.length)
                else -> value
            }
            Text(
                text = shown,
                style = if (value.isEmpty()) placeholderStyle else valueStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = horizontalPadding),
            )
        }
    }
}

// --------------------------------------------------------- selection controls

/** "Remember me on this TV" — one focus stop, 72 tall, pill, padding 0 28 0 12. */
@Composable
fun TvCheckboxRow(
    checked: Boolean,
    onToggle: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val radius = d.px(CHECKBOX_ROW_HEIGHT_PX / 2)
    var focused by remember { mutableStateOf(false) }
    val edgeStroke = d.px(CHECKBOX_EDGE_PX)
    Row(
        modifier = modifier
            .height(d.px(CHECKBOX_ROW_HEIGHT_PX))
            // Ring first — a clip above it would erase the ring and the glow
            // (F-33).
            .happyTvFocusRing(focused = focused, radius = radius)
            .clip(RoundedCornerShape(radius))
            .background(Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .semantics {
                role = Role.Checkbox
                contentDescription = label
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionCenter) {
                    onToggle(); true
                } else false
            }
            .padding(
                start = d.px(CHECKBOX_ROW_PADDING_START_PX),
                end = d.px(CHECKBOX_ROW_PADDING_END_PX),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.px(CHECKBOX_GAP_PX)),
    ) {
        Box(
            modifier = Modifier
                .size(d.px(CHECKBOX_SIZE_PX))
                .clip(RoundedCornerShape(d.px(CHECKBOX_RADIUS_PX)))
                .background(if (checked) HappyTvColors.Accent else Color.Transparent)
                .drawWithContent {
                    drawContent()
                    if (!checked) {
                        val stroke = edgeStroke.toPx()
                        drawRoundRect(
                            color = HappyTvColors.TextTertiary,
                            topLeft = Offset(stroke, stroke),
                            size = Size(size.width - 2 * stroke, size.height - 2 * stroke),
                            cornerRadius = CornerRadius(d.px(CHECKBOX_RADIUS_PX).toPx() - stroke),
                            style = Stroke(width = stroke),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = HappyTvIcons.Check,
                    contentDescription = null,
                    tint = HappyTvColors.OnAccent,
                    modifier = Modifier.size(d.px(CHECKBOX_CHECK_PX)),
                )
            }
        }
        Text(text = label, style = typography.bodySemibold, color = HappyTvColors.TextPrimary, maxLines = 1)
    }
}

// -------------------------------------------------------------- status line

/**
 * The status line — one slot at the bottom of the card holding help text, the
 * signing-in message, the M3U note or a red error with an icon. Errors
 * *replace* the help text, so the card never changes height (design spec
 * "Sign in", status line).
 */
@Composable
fun TvStatusLine(
    text: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    Row(
        modifier = modifier
            .height(d.px(STATUS_LINE_MIN_HEIGHT_PX))
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.px(STATUS_LINE_GAP_PX)),
    ) {
        if (isError) {
            Icon(
                imageVector = HappyTvIcons.Alert,
                contentDescription = null,
                tint = HappyTvColors.StateError,
                modifier = Modifier.size(d.px(STATUS_ICON_PX)),
            )
        }
        Text(
            text = text,
            style = typography.captionSemibold,
            color = if (isError) HappyTvColors.StateError else HappyTvColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
    }
}

// ------------------------------------------------------------------ shared

@Composable
private fun TvButton(
    label: String,
    onClick: () -> Unit,
    containerColor: Color,
    labelColor: Color,
    labelStyle: TextStyle,
    modifier: Modifier,
    widthPx: Int?,
    focusRequester: FocusRequester?,
    contentDescription: String?,
) {
    val d = LocalHappyTvDesign.current
    val radius = d.px(BUTTON_HEIGHT_PX / 2)
    var focused by remember { mutableStateOf(false) }
    val sized = if (widthPx != null) Modifier.size(d.px(widthPx), d.px(BUTTON_HEIGHT_PX)) else Modifier.height(d.px(BUTTON_HEIGHT_PX))
    Box(
        modifier = modifier
            .then(sized)
            // Ring first — a clip above it would erase the ring and the glow
            // (F-33) — and in the ring colour that contrasts this fill: Accent
            // fill gets the near-white ring, everything else keeps Accent.
            .happyTvFocusRing(
                focused = focused,
                radius = radius,
                focusRingColor = HappyTvColors.focusRingOn(containerColor),
            )
            .clip(RoundedCornerShape(radius))
            .background(containerColor)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .semantics {
                role = Role.Button
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionCenter) {
                    onClick(); true
                } else false
            }
            .padding(horizontal = d.px(BUTTON_PADDING_PX)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = labelStyle,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Handoff px constants for this component set (px at the 1920 x 1080 artboard).
private const val BUTTON_HEIGHT_PX = 88
private const val BUTTON_PADDING_PX = 48
private const val SIGN_IN_WIDTH_PX = 360
private const val BUTTON_INSET_PX = 48
private const val PROGRESS_HEIGHT_PX = 6
private const val SEGMENT_FRACTION = 0.4f
private const val INDETERMINATE_MS = 1400
private const val TEXT_LINK_HEIGHT_PX = 44
private const val TEXT_LINK_PADDING_PX = 12
private const val FIELD_HEIGHT_PX = 96
private const val FIELD_RADIUS_PX = 16
private const val FIELD_PADDING_PX = 28
private const val ERROR_OUTLINE_PX = 3
private const val KEYBOARD_SETTLE_MS = 60L

/** Handoff: password masking is 0.1 em, computed against the field's 36 px text. */
private const val PASSWORD_LETTER_SPACING_EM = 0.1f
private const val baseFontSizeSp = 18f

private const val CHECKBOX_ROW_HEIGHT_PX = 72
private const val CHECKBOX_ROW_PADDING_START_PX = 12
private const val CHECKBOX_ROW_PADDING_END_PX = 28
private const val CHECKBOX_GAP_PX = 20
private const val CHECKBOX_SIZE_PX = 48
private const val CHECKBOX_RADIUS_PX = 12
private const val CHECKBOX_EDGE_PX = 3
private const val CHECKBOX_CHECK_PX = 32
private const val STATUS_LINE_MIN_HEIGHT_PX = 36
private const val STATUS_LINE_GAP_PX = 12
private const val STATUS_ICON_PX = 32
