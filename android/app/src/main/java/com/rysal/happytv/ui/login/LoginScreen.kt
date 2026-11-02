package com.rysal.happytv.ui.login

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.os.SystemClock
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rysal.happytv.ui.theme.HappyTvAppIcon
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.HappyTvIcons
import com.rysal.happytv.ui.theme.LocalHappyTvDesign
import com.rysal.happytv.ui.theme.LocalHappyTvTypography
import com.rysal.happytv.ui.theme.happyTvFocusRing
import com.rysal.happytv.ui.theme.tabular

/**
 * The sign-in screen — design spec §"Screens: splash, launcher banner and sign in"
 * and the `Login.dc.html` scenarios (`profiles`, `first`, `formats`,
 * `cleartext`, `signing`, `wrong`, `offline`).
 *
 * Positions are transcribed in handoff px inside the 1920 x 1080 artboard that
 * [com.rysal.happytv.ui.theme.HappyTvCanvas] lays out, so the screen matches
 * the design rather than approximating it.
 *
 * D-pad order (handoff sticky note): saved servers → Server URL → Common URL
 * formats → Username → Password → eye → Remember me → Sign in → M3U playlist.
 * Each focus stop is a [FocusRequester]; the ViewModel asks for one through
 * [LoginUiState.focusTarget] after every state change that moves focus.
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRememberMeToggle: () -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onChangeServer: () -> Unit,
    onM3uPressed: () -> Unit,
    onOpenUrlFormats: () -> Unit,
    onCloseUrlFormats: () -> Unit,
    onPickUrlFormat: (String) -> Unit,
    onPickSavedServer: (String) -> Unit,
    onSheetUseHttps: () -> Unit,
    onSheetContinueAnyway: () -> Unit,
    onSheetCancel: () -> Unit,
    onFocusHandled: () -> Unit,
) {
    val d = LocalHappyTvDesign.current

    val savedServerFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val formatsLinkFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val eyeFocus = remember { FocusRequester() }
    val rememberFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    val m3uFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val changeServerFocus = remember { FocusRequester() }
    val formatsHandles = remember { UrlFormatsDialogHandles() }
    val sheetHandles = remember { CleartextSheetHandles() }

    /**
     * One resolver for "which FocusRequester does this target mean". Both
     * focus paths go through it — the ViewModel's one-shot
     * [LoginUiState.focusTarget] below and the fields' Gboard Next/Done
     * hand-off (F-32) — so the two can never disagree about where a target
     * lands.
     */
    fun requesterFor(target: LoginFocusTarget): FocusRequester = when (target) {
        LoginFocusTarget.SavedServer -> savedServerFocus
        LoginFocusTarget.ServerUrl -> urlFocus
        LoginFocusTarget.UrlFormatsLink -> formatsLinkFocus
        LoginFocusTarget.Username -> usernameFocus
        LoginFocusTarget.Password -> passwordFocus
        LoginFocusTarget.Eye -> eyeFocus
        LoginFocusTarget.Remember -> rememberFocus
        LoginFocusTarget.Submit -> submitFocus
        LoginFocusTarget.M3u -> m3uFocus
        LoginFocusTarget.Retry -> retryFocus
        LoginFocusTarget.ChangeServer -> changeServerFocus
        LoginFocusTarget.FormatOption -> formatsHandles.firstOption
        LoginFocusTarget.FormatClose -> formatsHandles.close
        LoginFocusTarget.SheetUseHttps -> sheetHandles.useHttps
        LoginFocusTarget.SheetContinueAnyway -> sheetHandles.continueAnyway
        LoginFocusTarget.SheetCancel -> sheetHandles.cancel
    }

    /**
     * F-32: Gboard's Next/Done on a sign-in field. Leaving the field means
     * leaving editing — [TvTextField] ends `editing` on focus loss — so hide
     * the keyboard first (left open it would cover the focus ring we are
     * about to move to, and typing into a non-editing field does nothing)
     * and hand focus to the next stop of the handoff's D-pad order. The
     * password field stops on Sign in and does NOT submit: design spec's flow is
     * OK on the button to confirm.
     */
    val imeKeyboard = LocalSoftwareKeyboardController.current
    // #187: on the TV, focus has been seen to move on a second time just after
    // Next (Username -> Password -> Remember me) with no key reaching the app.
    // After an advance, ask for the target once more a moment later, but only
    // if the user has not pressed anything since: a key press is a deliberate
    // move and wins.
    var lastKeyAtMs by remember { mutableStateOf(0L) }
    var advanceRequest by remember { mutableStateOf<Triple<Int, Long, LoginFocusTarget>?>(null) }
    val advanceFocus: (LoginFocusTarget) -> Unit = { target ->
        imeKeyboard?.hide()
        runCatching { requesterFor(target).requestFocus() }
        advanceRequest = Triple((advanceRequest?.first ?: 0) + 1, SystemClock.uptimeMillis(), target)
    }
    LaunchedEffect(advanceRequest) {
        val (_, startedAt, target) = advanceRequest ?: return@LaunchedEffect
        delay(ADVANCE_SETTLE_MS)
        if (lastKeyAtMs < startedAt) runCatching { requesterFor(target).requestFocus() }
    }

    var revealed by remember { mutableStateOf(false) }
    val signingIn = state.isSigningIn
    val showRail = state.hasSavedServers

    // "Hold OK to reveal the password; the eye turns accent while held."
    val holdReveal: Modifier = Modifier
        .onPreviewKeyEvent { event ->
            val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
            when {
                !isOk -> false
                event.type == KeyEventType.KeyDown -> {
                    revealed = true
                    true
                }
                event.type == KeyEventType.KeyUp -> {
                    revealed = false
                    true
                }
                else -> false
            }
        }
        .pointerInput(Unit) {
            // Touch/mouse hold: same "hold to peek" semantics as the OK key.
            // detectTapGestures' onPress is a suspend block that ends when the
            // pointer lifts, so the reveal can't get stuck on.
            detectTapGestures(onPress = {
                revealed = true
                tryAwaitRelease()
                revealed = false
            })
        }

    // One-shot focus requests from the ViewModel.
    LaunchedEffect(state.focusTarget) {
        val target = state.focusTarget ?: return@LaunchedEffect
        runCatching { requesterFor(target).requestFocus() }
        onFocusHandled()
    }

    // Revealed state only survives while the eye is held.
    LaunchedEffect(signingIn) { if (signingIn) revealed = false }

    Box(
        modifier = Modifier.fillMaxSize().onPreviewKeyEvent {
            lastKeyAtMs = SystemClock.uptimeMillis()
            false
        },
    ) {
        // Decorative watermark: never focusable, never read aloud.
        Text(
            text = "HappyTV",
            style = LocalHappyTvTypography.current.watermark,
            color = HappyTvColors.SigninWatermark,
            maxLines = 1,
            modifier = Modifier
                .offset(x = d.px(WATERMARK_LEFT_PX), y = d.px(WATERMARK_TOP_PX)),
        )

        // ---------------------------------------------------------------- card
        Box(
            modifier = Modifier
                .offset(x = d.px(if (showRail) CARD_LEFT_WITH_RAIL_PX else CARD_LEFT_CENTERED_PX), y = d.px(CARD_TOP_PX))
                .width(d.px(CARD_WIDTH_PX))
                // #164: shadow first. Behind a background that sits outside the shadow's
                // layer, Android paints the shadow over the fill inside the outline too
                // (hard-edged grey bands). Fill and clip go inside the shadowed layer.
                .shadow(
                    elevation = d.px(CARD_SHADOW_PX),
                    shape = RoundedCornerShape(d.px(CARD_RADIUS_PX)),
                    clip = false,
                    ambientColor = HappyTvColors.shadow(CARD_SHADOW_ALPHA),
                    spotColor = HappyTvColors.shadow(CARD_SHADOW_ALPHA),
                )
                .clip(RoundedCornerShape(d.px(CARD_RADIUS_PX)))
                .background(HappyTvColors.Surface)
                .padding(
                    start = d.px(CARD_PADDING_H_PX),
                    end = d.px(CARD_PADDING_H_PX),
                    top = d.px(CARD_PADDING_V_PX),
                    bottom = d.px(CARD_PADDING_V_PX),
                ),
        ) {
            // Signing in dims the form to 55% and takes no input.
            // #161: while a dialog or the cleartext sheet is up, the form behind it is not
            // a focus stop either (no dim: the scrim already covers it), so Down from the
            // dialog's last control cannot land on a hidden field.
            val formModifier = if (signingIn) {
                Modifier
                    .alpha(FORM_DIM_ALPHA)
                    .focusProperties { canFocus = false }
            } else if (state.overlay != LoginOverlay.None) {
                Modifier.focusProperties { canFocus = false }
            } else {
                Modifier
            }

            Column(modifier = formModifier) {
                CardHeader(signingIn = signingIn)

                // ------------------------------------------------------- form
                Column(
                    modifier = Modifier.padding(top = d.px(FORM_TOP_MARGIN_PX)),
                    verticalArrangement = Arrangement.spacedBy(d.px(FORM_ROW_GAP_PX)),
                ) {
                    LabeledField(label = "Server URL", firstRow = true) {
                        Column(verticalArrangement = Arrangement.spacedBy(d.px(FIELD_STACK_GAP_PX)), modifier = Modifier.fillMaxWidth()) {
                            TvTextField(
                                value = state.serverUrl,
                                onValueChange = onServerUrlChange,
                                placeholder = "provider.example:8080",
                                accessibleName = "Server URL",
                                error = state.serverUrlError,
                                focusRequester = urlFocus,
                                // F-32: Next skips the formats link (that one
                                // is reached by D-pad, not by typing) and lands
                                // on Username.
                                onImeAction = { advanceFocus(LoginFocusTarget.Username) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            state.serverUrlError?.let { FieldErrorLine(it) }
                            TvTextLink(
                                label = "Common URL formats",
                                onClick = onOpenUrlFormats,
                                focusRequester = formatsLinkFocus,
                            )
                        }
                    }

                    LabeledField(label = "Username") {
                        Column(verticalArrangement = Arrangement.spacedBy(d.px(FIELD_STACK_GAP_PX)), modifier = Modifier.fillMaxWidth()) {
                        TvTextField(
                            value = state.username,
                            onValueChange = onUsernameChange,
                            placeholder = "",
                            accessibleName = "Username",
                            error = state.usernameError,
                            focusRequester = usernameFocus,
                            // F-32: Next moves on to Password.
                            onImeAction = { advanceFocus(LoginFocusTarget.Password) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        state.usernameError?.let { FieldErrorLine(it) }
                        }
                    }

                    LabeledField(label = "Password") {
                        Column(verticalArrangement = Arrangement.spacedBy(d.px(FIELD_STACK_GAP_PX)), modifier = Modifier.fillMaxWidth()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(d.px(EYE_GAP_PX)), modifier = Modifier.fillMaxWidth()) {
                                TvTextField(
                                    value = state.password,
                                    onValueChange = onPasswordChange,
                                    placeholder = "",
                                    accessibleName = "Password",
                                    isPassword = true,
                                    masked = !revealed,
                                    error = state.passwordError,
                                    focusRequester = passwordFocus,
                                    // F-32: Next on the last field exits editing
                                    // (focus loss ends TvTextField's `editing`,
                                    // which hides Gboard) and parks on Sign in.
                                    // It does not submit — design spec: OK on the
                                    // button is what confirms.
                                    onImeAction = { advanceFocus(LoginFocusTarget.Submit) },
                                    modifier = Modifier.weight(1f, fill = true),
                                )
                                EyeButton(revealed = revealed, modifier = holdReveal.then(Modifier.focusRequester(eyeFocus)))
                            }
                            state.passwordError?.let { FieldErrorLine(it) }
                        }
                    }

                    TvCheckboxRow(
                        checked = state.rememberMe,
                        onToggle = onRememberMeToggle,
                        label = "Remember me on this TV",
                        focusRequester = rememberFocus,
                    )
                }

                // ------------------------------------------------- action row
                Row(
                    modifier = Modifier.padding(top = d.px(ACTION_ROW_TOP_MARGIN_PX)),
                    horizontalArrangement = Arrangement.spacedBy(d.px(BUTTON_GAP_PX)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        signingIn -> TvSubmittingButton()

                        state.isUnreachable -> {
                            TvPrimaryButton(
                                label = "Retry",
                                onClick = onRetry,
                                widthPx = SIGN_IN_WIDTH_PX,
                                focusRequester = retryFocus,
                            )
                            TvSecondaryButton(label = "Change server", onClick = onChangeServer, focusRequester = changeServerFocus)
                        }

                        else -> {
                            TvPrimaryButton(
                                label = "Sign in",
                                onClick = onSubmit,
                                widthPx = SIGN_IN_WIDTH_PX,
                                focusRequester = submitFocus,
                            )
                            // The M3U button is focusable so it can explain itself.
                            M3uButton(onClick = onM3uPressed, focusRequester = m3uFocus)
                        }
                    }
                }

                // ---------------------------------------------- status line
                val status = when {
                    state.isUnreachable -> state.statusError.orEmpty()
                    signingIn -> "Signing in to ${state.serverUrl.ifBlank { "your server" }}…"
                    state.statusError != null -> state.statusError.orEmpty()
                    state.infoLine != null -> state.infoLine.orEmpty()
                    else -> DEFAULT_STATUS_LINE
                }
                TvStatusLine(
                    text = status,
                    isError = state.isUnreachable || (state.statusError != null && !signingIn),
                    modifier = Modifier.padding(top = d.px(STATUS_LINE_TOP_MARGIN_PX)),
                )
            }
        }

        // ------------------------------------------------------- saved servers
        if (showRail) {
            SavedServersRail(
                servers = state.savedServers,
                selectedId = state.selectedServerId,
                onPick = onPickSavedServer,
                firstFocus = savedServerFocus,
                signingIn = signingIn || state.overlay != LoginOverlay.None,
                modifier = Modifier
                    .offset(x = d.px(RAIL_LEFT_PX), y = d.px(CARD_TOP_PX))
                    .width(d.px(RAIL_WIDTH_PX)),
            )
        }

        // ------------------------------------------------------------ overlays
        if (state.overlay is LoginOverlay.UrlFormats) {
            // Same F-66 lesson as the cleartext sheet below: a scrim must be
            // inert. `focusProperties { canFocus = false }` propagates to the
            // whole subtree; this dialog only worked because the extra
            // `focusable()` Box underneath re-opened a focus scope. Dropping the
            // property makes the panel's own options the focus stops directly,
            // which is what the handoff asks for.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(HappyTvColors.Scrim),
                contentAlignment = Alignment.TopCenter,
            ) {
                UrlFormatsDialogPanel(
                    onPick = onPickUrlFormat,
                    onClose = onCloseUrlFormats,
                    handles = formatsHandles,
                    modifier = Modifier.offset(y = d.px(DIALOG_TOP_PX)),
                )
            }
        }

        (state.overlay as? LoginOverlay.CleartextSheet)?.let { sheet ->
            // F-66: the scrim must be inert. `focusProperties { canFocus = false }`
            // does not stay on the node it is written on — it propagates down the
            // whole subtree, so a descendant inside it cannot take focus at all
            // (`requestFocus` returns normally and simply does nothing). Written
            // on this scrim it silently disarmed all three of the sheet's buttons
            // and turned the modal into a D-pad dead end: the QA dump of the open
            // sheet had every one of its 30 nodes `focused="false"` and OK did
            // nothing on any of them, so a plain-HTTP provider could not be signed
            // into at all.
            //
            // The scrim is not a focus stop anyway — it carries no `focusable` —
            // so the property guarded nothing and cost the whole modal. (The
            // URL-formats dialog above survives the same property only because it
            // wraps its panel in an extra `focusable()` Box, which re-opens a
            // focus scope underneath; that is fragile and not worth copying.)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(HappyTvColors.Scrim),
                contentAlignment = Alignment.BottomStart,
            ) {
                CleartextSheetPanel(
                    hostLabel = sheet.hostLabel,
                    onUseHttps = onSheetUseHttps,
                    onContinueAnyway = onSheetContinueAnyway,
                    onCancel = onSheetCancel,
                    handles = sheetHandles,
                    modifier = Modifier.offset(x = d.px(SHEET_LEFT_PX)),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ pieces

/** Icon + wordmark (+ pulsing dot while signing in), then the subtitle. */
@Composable
private fun CardHeader(signingIn: Boolean) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current

    Column(verticalArrangement = Arrangement.spacedBy(d.px(HEADER_GAP_PX))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.px(ICON_WORDMARK_GAP_PX)),
        ) {
            HappyTvAppIcon(
                size = d.px(APP_ICON_PX),
                cornerRadius = d.px(APP_ICON_PX * APP_ICON_CORNER_FRACTION),
            )
            Text(text = "HappyTV", style = typography.wordmark, color = HappyTvColors.TextPrimary)
            if (signingIn) PulsingDot()
        }
        Text(
            text = "Sign in with the details from your provider.",
            style = typography.body,
            color = HappyTvColors.TextSecondary,
        )
    }
}

/** 16 px accent dot, pulsing 35% → 100% on a 1.4 s loop while signing in. */
@Composable
private fun PulsingDot() {
    val d = LocalHappyTvDesign.current
    val transition = rememberInfiniteTransition(label = "signing-dot")
    val alpha by transition.animateFloat(
        initialValue = DOT_ALPHA_MIN,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = DOT_PULSE_MS, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "signing-dot-alpha",
    )
    Box(
        modifier = Modifier
            .size(d.px(DOT_PX))
            .alpha(alpha)
            .clip(RoundedCornerShape(d.px(DOT_PX / 2)))
            .background(HappyTvColors.Accent),
    )
}

/** 96 x 96 eye button — accent while held, secondary otherwise. */
@Composable
private fun EyeButton(revealed: Boolean, modifier: Modifier = Modifier) {
    val d = LocalHappyTvDesign.current
    var focused by remember { mutableStateOf(false) }
    val radius = d.px(EYE_RADIUS_PX)
    Box(
        modifier = modifier
            .size(d.px(EYE_BUTTON_PX))
            // Ring first — a clip above it would erase it (F-33).
            .happyTvFocusRing(focused = focused, radius = radius)
            .clip(RoundedCornerShape(radius))
            .background(HappyTvColors.Overlay)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics { contentDescription = "Hold OK to show password" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = HappyTvIcons.Eye,
            contentDescription = null,
            tint = if (revealed) HappyTvColors.Accent else HappyTvColors.TextSecondary,
            modifier = Modifier.size(d.px(EYE_ICON_PX)),
        )
    }
}

/** The "M3U playlist" button with its v1.1 chip. */
@Composable
private fun M3uButton(onClick: () -> Unit, focusRequester: FocusRequester) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val radius = d.px(M3U_HEIGHT_PX / 2)
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .height(d.px(M3U_HEIGHT_PX))
            // Ring first — a clip above it would erase it (F-33).
            .happyTvFocusRing(focused = focused, radius = radius)
            .clip(RoundedCornerShape(radius))
            .background(HappyTvColors.Raised)
            .onFocusChanged { focused = it.isFocused }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionCenter) {
                    onClick(); true
                } else false
            }
            .padding(start = d.px(M3U_PADDING_START_PX), end = d.px(M3U_PADDING_END_PX)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.px(M3U_GAP_PX)),
    ) {
        Text(text = "M3U playlist", style = typography.bodyBold, color = HappyTvColors.TextTertiary, maxLines = 1)
        Box(
            modifier = Modifier
                .height(d.px(CHIP_HEIGHT_PX))
                .clip(RoundedCornerShape(d.px(CHIP_HEIGHT_PX / 2)))
                .background(HappyTvColors.Overlay)
                .padding(horizontal = d.px(CHIP_PADDING_PX)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "v1.1", style = typography.captionBold, color = HappyTvColors.TextSecondary, maxLines = 1)
        }
    }
}

/** The "Saved on this TV" list — label plus one 164 px button per server. */
@Composable
private fun SavedServersRail(
    servers: List<SavedServer>,
    selectedId: String?,
    onPick: (String) -> Unit,
    firstFocus: FocusRequester,
    signingIn: Boolean,
    modifier: Modifier = Modifier,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current

    Column(
        modifier = modifier.focusProperties {
            // Signing in: the whole list steps out of the D-pad order.
            canFocus = !signingIn
        },
        verticalArrangement = Arrangement.spacedBy(d.px(RAIL_GAP_PX)),
    ) {
        Text(
            text = "Saved on this TV",
            style = typography.captionBold,
            color = HappyTvColors.TextTertiary,
            maxLines = 1,
        )
        servers.forEachIndexed { index, server ->
            SavedServerButton(
                server = server,
                isSelected = server.id == selectedId,
                onClick = { onPick(server.id) },
                focusRequester = if (index == 0) firstFocus else null,
            )
        }
    }
}

/** One saved server — 164 tall, radius 24, surface; name / host / last signed in. */
@Composable
private fun SavedServerButton(
    server: SavedServer,
    isSelected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val radius = d.px(SAVED_SERVER_HEIGHT_PX / 2)
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .height(d.px(SAVED_SERVER_HEIGHT_PX))
            // Ring first — a clip above it would erase the ring and the glow
            // (F-33).
            .happyTvFocusRing(focused = focused, radius = radius)
            .clip(RoundedCornerShape(d.px(SAVED_SERVER_RADIUS_PX)))
            .background(HappyTvColors.Surface)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionCenter) {
                    onClick(); true
                } else false
            }
            .padding(horizontal = d.px(SAVED_SERVER_PADDING_PX)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.px(SAVED_SERVER_GAP_PX)),
    ) {
        Column(modifier = Modifier.weight(1f, fill = true)) {
            Text(text = server.name, style = typography.bodyBold, color = HappyTvColors.TextPrimary, maxLines = 1)
            Text(text = server.hostLabel, style = typography.caption.tabular(), color = HappyTvColors.TextSecondary, maxLines = 1)
            server.lastSignedInLabel?.let {
                Text(text = it, style = typography.caption.tabular(), color = HappyTvColors.TextTertiary, maxLines = 1)
            }
        }
        if (isSelected) {
            Icon(
                imageVector = HappyTvIcons.Check,
                contentDescription = null,
                tint = HappyTvColors.Accent,
                modifier = Modifier.size(d.px(SAVED_SERVER_CHECK_PX)),
            )
        }
    }
}

/** 28/36 600 red line under an invalid field, with the 32 px alert icon. */
@Composable
private fun FieldErrorLine(message: String) {
    val d = LocalHappyTvDesign.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.px(FIELD_ERROR_GAP_PX)),
    ) {
        Icon(
            imageVector = HappyTvIcons.Alert,
            contentDescription = null,
            tint = HappyTvColors.StateError,
            modifier = Modifier.size(d.px(FIELD_ERROR_ICON_PX)),
        )
        Text(
            text = message,
            style = LocalHappyTvTypography.current.captionSemibold,
            color = HappyTvColors.StateError,
            maxLines = 1,
        )
    }
}

/** 240 px label column + the field column, per the handoff's form grid. */
@Composable
private fun LabeledField(
    label: String,
    firstRow: Boolean = false,
    content: @Composable () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    Row(horizontalArrangement = Arrangement.spacedBy(d.px(FORM_COLUMN_GAP_PX))) {
        Box(
            modifier = Modifier
                .width(d.px(LABEL_COLUMN_PX))
                // Rows are spaced by the parent; the label still centers on the
                // 96 px field.
                .height(d.px(FIELD_HEIGHT_PX)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = label,
                style = LocalHappyTvTypography.current.bodySemibold,
                color = HappyTvColors.TextSecondary,
                maxLines = 1,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
    if (!firstRow) {
        // Spacer handled by the parent's spacedBy; nothing to do here.
    }
}

private const val ADVANCE_SETTLE_MS = 300L

// Handoff px constants for the sign-in screen.
private const val DEFAULT_STATUS_LINE = "Don’t have an account? Ask your IPTV provider."
private const val WATERMARK_LEFT_PX = 72
private const val WATERMARK_TOP_PX = 790
private const val CARD_TOP_PX = 40
private const val CARD_LEFT_WITH_RAIL_PX = 128
private const val CARD_LEFT_CENTERED_PX = 440
private const val CARD_WIDTH_PX = 1040
private const val CARD_RADIUS_PX = 32
private const val CARD_SHADOW_PX = 40
private const val CARD_SHADOW_ALPHA = 0.45f
private const val CARD_PADDING_H_PX = 64
private const val CARD_PADDING_V_PX = 40
private const val RAIL_LEFT_PX = 1232
private const val RAIL_WIDTH_PX = 560
private const val RAIL_GAP_PX = 16
private const val SAVED_SERVER_HEIGHT_PX = 164
private const val SAVED_SERVER_RADIUS_PX = 24
private const val SAVED_SERVER_PADDING_PX = 32
private const val SAVED_SERVER_GAP_PX = 24
private const val SAVED_SERVER_CHECK_PX = 40
private const val DIALOG_TOP_PX = 96
private const val SHEET_LEFT_PX = 96
private const val FORM_DIM_ALPHA = 0.55f
private const val FORM_TOP_MARGIN_PX = 32
private const val FORM_ROW_GAP_PX = 16
private const val FORM_COLUMN_GAP_PX = 32
private const val LABEL_COLUMN_PX = 240
private const val FIELD_HEIGHT_PX = 96
private const val FIELD_STACK_GAP_PX = 8
private const val FIELD_ERROR_GAP_PX = 8
private const val FIELD_ERROR_ICON_PX = 32
private const val ACTION_ROW_TOP_MARGIN_PX = 32
private const val BUTTON_GAP_PX = 24
private const val SIGN_IN_WIDTH_PX = 360
private const val STATUS_LINE_TOP_MARGIN_PX = 24
private const val M3U_HEIGHT_PX = 88
private const val M3U_PADDING_START_PX = 40
private const val M3U_PADDING_END_PX = 28
private const val M3U_GAP_PX = 16
private const val CHIP_HEIGHT_PX = 40
private const val CHIP_PADDING_PX = 14
private const val HEADER_GAP_PX = 8
private const val ICON_WORDMARK_GAP_PX = 16
private const val APP_ICON_PX = 52
private const val APP_ICON_CORNER_FRACTION = 0.22f
private const val EYE_GAP_PX = 16
private const val EYE_BUTTON_PX = 96
private const val EYE_RADIUS_PX = 16
private const val EYE_ICON_PX = 44
private const val DOT_PX = 16
private const val DOT_PULSE_MS = 1400
private const val DOT_ALPHA_MIN = 0.35f
