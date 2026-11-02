@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.rysal.happytv.ui.main

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.clickable
import com.rysal.happytv.ui.browse.ignoreOrphanEnterUp
import com.rysal.happytv.ui.browse.okHoldKeys
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.theme.HappyTvAppIcon
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.HappyTvIcons
import com.rysal.happytv.ui.theme.HappyTvTheme
import com.rysal.happytv.ui.theme.HappyTvThemeState
import com.rysal.happytv.ui.theme.LocalHappyTvDesign
import com.rysal.happytv.ui.theme.LocalHappyTvTypography
import com.rysal.happytv.ui.theme.happyTvFocusRing
import com.rysal.happytv.ui.theme.tabular
import java.util.Calendar
import kotlinx.coroutines.delay

/**
 * The dashboard — HappyTV's signature screen (design spec §"Screens: dashboard",
 * §"Components > Dashboard tile", §"Tile refresh motion").
 *
 * Everything is laid out in handoff px inside the 1920×1080 artboard that
 * [HappyTvCanvas] scales to the window, so the numbers in the code are the
 * numbers on the design canvas.
 *
 * Focus model: every focus stop owns a [FocusRequester]; D-pad keys are routed
 * explicitly (Left/Right across tiles, Down from a tile to its pill, Down
 * again to TV guide → Settings, Up from the tiles to Search) so the order
 * matches the design spec sticky note instead of relying on geometry. Long-press
 * OK (pointer hold, OK-key repeat, or the remote's Menu key) opens the tile
 * menu.
 */
@Composable
fun DashboardScreen(
    viewModelFactory: androidx.lifecycle.ViewModelProvider.Factory = DashboardViewModel.Factory(),
    onOpenLiveTv: () -> Unit = {},
    onOpenMovies: () -> Unit = {},
    onOpenShows: () -> Unit = {},
    onOpenTvGuide: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSignIn: () -> Unit = {},
) {
    val viewModel: DashboardViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        // Auth is NOT primed here (F-45): the header observes the coordinator's
        // live session StateFlow, so a sign-in that lands while this Activity is
        // stopped is already reflected when the composition resumes — no
        // one-shot read, no app restart.
        viewModel.onEnteredForeground()
    }

    // Back from the player or guide: silent refresh when some tile's data is
    // actually stale — stamped and > 30 min old, or never loaded at all (F-41:
    // an unmarked-but-Done tile no longer counts as stale). See
    // DashboardViewModel.staleForReturnRefresh.
    // (Manual observer rather than LifecycleResumeEffect: lifecycle is pinned
    // to 2.7 for Compose 1.6 compatibility — see libs.versions.toml — and 2.7
    // has no resume effect.)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumedOnce by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (resumedOnce) {
                    viewModel.onReturnedFromChild()
                } else {
                    resumedOnce = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    HappyTvTheme {
        HappyTvCanvas {
            DashboardContent(
                state = state,
                viewModel = viewModel,
                onOpenLiveTv = onOpenLiveTv,
                onOpenMovies = onOpenMovies,
                onOpenShows = onOpenShows,
                onOpenTvGuide = onOpenTvGuide,
                onOpenSettings = onOpenSettings,
                onOpenSearch = onOpenSearch,
                onOpenSignIn = onOpenSignIn,
            )
        }
    }
}

/** All 200 ms motion steps use cubic-bezier(0.42, 0, 0.58, 1) — no springs. */
private val EaseInOutCubic = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

@Composable
private fun DashboardContent(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    onOpenLiveTv: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenShows: () -> Unit,
    onOpenTvGuide: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSignIn: () -> Unit,
) {
    val d = LocalHappyTvDesign.current

    // 15 s tick — the clock and the "Updated N minutes ago" stamps age by the
    // minute with instant text swaps.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            now = System.currentTimeMillis()
        }
    }

    // Focus stops, in D-pad order.
    val searchFocus = remember { FocusRequester() }
    val themeFocus = remember { FocusRequester() }
    val bodyFocus = remember { StreamType.entries.map { FocusRequester() } }
    val pillFocus = remember { StreamType.entries.map { FocusRequester() } }
    val guideFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val bannerFocus = remember { FocusRequester() }
    val retryAllFocus = remember { FocusRequester() }
    val detailsSignOutFocus = remember { FocusRequester() }
    val welcomeSignInFocus = remember { FocusRequester() }

    val openTile: (StreamType) -> Unit = { type ->
        when (type) {
            StreamType.LIVE -> onOpenLiveTv()
            StreamType.VOD -> onOpenMovies()
            StreamType.SERIES -> onOpenShows()
        }
    }

    // Initial focus: Live TV's body signed in, the welcome strip's Sign in
    // button signed out. Re-runs when the signed-in flip lands.
    // While the session is unresolved (cold start, issue #30) nothing is
    // focused: tiles are inert and there is no sign-in affordance to land on.
    LaunchedEffect(state.sessionResolved, state.isSignedIn) {
        if (!state.sessionResolved) return@LaunchedEffect
        if (state.isSignedIn) bodyFocus[0].requestFocus()
        else welcomeSignInFocus.requestFocus()
    }
    // The banner takes focus the moment it appears ("The connection banner at
    // top 178 takes focus" — design spec nothing-refreshed artboard).
    LaunchedEffect(state.showConnectionBanner) {
        if (state.showConnectionBanner && state.isSignedIn) bannerFocus.requestFocus()
    }
    // Details panel: Retry all focused on open.
    LaunchedEffect(state.connectionDetailsExpanded) {
        if (state.connectionDetailsExpanded) retryAllFocus.requestFocus()
    }
    // Retry all → "moves focus to Live TV's pill".
    val retryAll = {
        viewModel.retryAll()
        pillFocus[0].requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HappyTvColors.Ground),
    ) {
        /* ------------------------------------------------------------ header */
        DashboardHeader(
            now = now,
            lastSignedInAtUtcMillis = state.lastSignedInAtUtcMillis,
            isSignedIn = state.isSignedIn,
            sessionResolved = state.sessionResolved,
            onSearchClick = onOpenSearch,
            searchFocus = searchFocus,
            downFromSearch = { bodyFocus[0].requestFocus() },
            themeFocus = themeFocus,
            lightMode = HappyTvThemeState.light,
            onToggleTheme = { viewModel.setLightMode(!HappyTvThemeState.light) },
            downFromTheme = {
                if (state.isSignedIn) bodyFocus[StreamType.entries.lastIndex].requestFocus()
                else welcomeSignInFocus.requestFocus()
            },
        )

        /* ------------------------------------------------ connection banner */
        if (state.showConnectionBanner) {
            ConnectionBanner(
                message = state.connectionMessage,
                expanded = state.connectionDetailsExpanded,
                onToggleDetails = viewModel::toggleConnectionDetails,
                focus = bannerFocus,
            )
        }
        if (state.showConnectionBanner && state.connectionDetailsExpanded) {
            ConnectionDetailsPanel(
                whatFailed = "Channels, movies and shows",
                server = state.serverLabel,
                lastGoodUpdate = state.lastGoodUpdateText,
                onRetryAll = retryAll,
                onSignOut = viewModel::signOut,
                retryAllFocus = retryAllFocus,
                signOutFocus = detailsSignOutFocus,
                onClose = viewModel::toggleConnectionDetails,
            )
        }

        /* ------------------------------------------------------------- tiles */
        Row(
            modifier = Modifier
                .offset(x = d.px(96), y = d.px(270))
                .width(d.px(1728)),
            horizontalArrangement = Arrangement.spacedBy(d.px(48)),
        ) {
            StreamType.entries.forEachIndexed { index, type ->
                val tile = state.tiles.first { it.type == type }
                TileColumn(
                    tile = tile,
                    viewModel = viewModel,
                    bodyFocus = bodyFocus[index],
                    pillFocus = pillFocus[index],
                    onOpen = { openTile(type) },
                    onRefresh = { viewModel.refreshTile(type) },
                    onLongPress = { viewModel.openLongPressMenu(type) },
                    upFromBody = {
                        if (index == StreamType.entries.lastIndex) themeFocus.requestFocus()
                        else searchFocus.requestFocus()
                    },
                    downFromBody = { pillFocus[index].requestFocus() },
                    leftFromBody = {
                        if (index > 0) bodyFocus[index - 1].requestFocus()
                    },
                    rightFromBody = {
                        if (index < StreamType.entries.lastIndex) bodyFocus[index + 1].requestFocus()
                    },
                    upFromPill = { bodyFocus[index].requestFocus() },
                    downFromPill = { guideFocus.requestFocus() },
                    leftFromPill = {
                        if (index > 0) pillFocus[index - 1].requestFocus()
                    },
                    rightFromPill = {
                        if (index < StreamType.entries.lastIndex) pillFocus[index + 1].requestFocus()
                    },
                )
            }
        }

        /* ---------------------------------------------------- welcome strip */
        if (state.sessionResolved && !state.isSignedIn) {
            WelcomeStrip(
                focus = welcomeSignInFocus,
                onSignIn = onOpenSignIn,
                upFromSignIn = { searchFocus.requestFocus() },
                downFromSignIn = { settingsFocus.requestFocus() },
            )
        }

        /* ------------------------------------------------------- second row */
        SecondRow(
            signedIn = state.isSignedIn,
            guideFocus = guideFocus,
            settingsFocus = settingsFocus,
            onOpenTvGuide = onOpenTvGuide,
            onOpenSettings = onOpenSettings,
            upFromRow = { pillFocus[1].requestFocus() },
            leftFromSettings = { guideFocus.requestFocus() },
            rightFromGuide = { settingsFocus.requestFocus() },
        )

        /* ------------------------------------------------- long-press menu */
        RestoreFocusWhenMenuCloses(state.menuTile) { type -> bodyFocus[StreamType.entries.indexOf(type)].requestFocus() }
        if (state.menuTile != null) {
            TileLongPressMenu(
                state = state,
                onRefresh = viewModel::refreshTile,
                onShowAbout = viewModel::menuShowAbout,
                onClose = {
                    viewModel.closeLongPressMenu()
                },
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Header                                                                    */
/* -------------------------------------------------------------------------- */

@Composable
private fun DashboardHeader(
    now: Long,
    lastSignedInAtUtcMillis: Long,
    isSignedIn: Boolean,
    sessionResolved: Boolean,
    onSearchClick: () -> Unit,
    searchFocus: FocusRequester,
    downFromSearch: () -> Unit,
    themeFocus: FocusRequester,
    lightMode: Boolean,
    onToggleTheme: () -> Unit,
    downFromTheme: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current

    Row(
        modifier = Modifier
            .offset(x = d.px(96), y = d.px(54))
            .size(d.px(1728), d.px(104)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HappyTvAppIcon(
                    size = d.px(52),
                    cornerRadius = d.px(11),
                )
                Spacer(Modifier.width(d.px(16)))
                Text(
                    text = "HappyTV",
                    style = typography.wordmark,
                    color = HappyTvColors.TextPrimary,
                )
            }
            Text(
                // Blank while the session is unresolved: never "Not signed in" on a cold start.
                text = if (sessionResolved) {
                    lastSignedInLabel(lastSignedInAtUtcMillis, now, isSignedIn)
                } else {
                    ""
                },
                style = typography.caption,
                color = HappyTvColors.TextTertiary,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Clock: 64/72 600, tabular, 12-hour without AM/PM ("2:37").
            val cal = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
            val hour12 = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            Text(
                text = "%d:%02d".format(hour12, cal.get(Calendar.MINUTE)),
                style = typography.clock.tabular(),
                color = HappyTvColors.TextPrimary,
            )
            Spacer(Modifier.width(d.px(40)))
            HeaderSearchPill(
                onClick = onSearchClick,
                focus = searchFocus,
                down = downFromSearch,
                right = { themeFocus.requestFocus() },
            )
            Spacer(Modifier.width(d.px(24)))
            HeaderThemeButton(
                lightMode = lightMode,
                onClick = onToggleTheme,
                focus = themeFocus,
                left = { searchFocus.requestFocus() },
                down = downFromTheme,
            )
        }
    }
}

/** Pill with icon — 88 tall, padding 0 40 0 32, 40 px icon + 36/44 700, gap 16. */
@Composable
private fun HeaderSearchPill(
    onClick: () -> Unit,
    focus: FocusRequester,
    down: () -> Unit,
    right: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .height(d.px(88))
            .background(HappyTvColors.Surface, RoundedCornerShape(percent = 50))
            .happyTvFocusRing(isFocused, radius = d.px(44))
            .focusRequester(focus)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource)
            .dpadNav(down = down, right = right)
            .semantics { contentDescription = "Search" }
            .padding(start = d.px(32), end = d.px(40)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = HappyTvIcons.Search,
                contentDescription = null,
                tint = HappyTvColors.TextPrimary,
                modifier = Modifier.size(d.px(40)),
            )
            Spacer(Modifier.width(d.px(16)))
            Text(
                text = "Search",
                style = typography.bodyBold,
                color = HappyTvColors.TextPrimary,
            )
        }
    }
}

/**
 * #126: the light/dark switch, an 88 px round button right of Search. A sun in
 * dark mode ("Switch to light mode"), a moon in light mode ("Switch to dark
 * mode"). OK flips the whole app at once; focus stays on the button.
 */
@Composable
private fun HeaderThemeButton(
    lightMode: Boolean,
    onClick: () -> Unit,
    focus: FocusRequester,
    left: () -> Unit,
    down: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val label = if (lightMode) "Switch to dark mode" else "Switch to light mode"

    Box(
        modifier = Modifier
            .size(d.px(88))
            .background(HappyTvColors.Surface, androidx.compose.foundation.shape.CircleShape)
            .happyTvFocusRing(isFocused, radius = d.px(44))
            .focusRequester(focus)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource)
            .dpadNav(left = left, down = down)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (lightMode) HappyTvIcons.Moon else HappyTvIcons.Sun,
            contentDescription = null,
            tint = HappyTvColors.TextPrimary,
            modifier = Modifier.size(d.px(44)),
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Connection banner + details panel                                         */
/* -------------------------------------------------------------------------- */

/** 1728 × 68 pill, error tint, one focus stop. Failed state only. */
@Composable
private fun ConnectionBanner(
    message: String,
    expanded: Boolean,
    onToggleDetails: () -> Unit,
    focus: FocusRequester,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .offset(x = d.px(96), y = d.px(178))
            .size(d.px(1728), d.px(68))
            .background(HappyTvColors.StateErrorTint, RoundedCornerShape(percent = 50))
            .happyTvFocusRing(isFocused, radius = d.px(34))
            .focusRequester(focus)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggleDetails,
            )
            .focusable(interactionSource = interactionSource)
            .semantics { contentDescription = "Connection issues. $message" }
            .padding(start = d.px(24), end = d.px(32)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.px(16)),
    ) {
        Icon(
            imageVector = HappyTvIcons.Alert,
            contentDescription = null,
            tint = HappyTvColors.StateError,
            modifier = Modifier.size(d.px(36)),
        )
        Text(
            text = "Connection issues",
            style = typography.captionBold,
            color = HappyTvColors.TextPrimary,
        )
        Text(
            text = message,
            style = typography.caption,
            color = HappyTvColors.TextSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (expanded) "Hide details" else "Details",
            style = typography.captionBold,
            color = HappyTvColors.Accent,
        )
    }
}

/** Details panel: top 262, left 96, 880 wide, Raised, radius 24, padding 40 48 44. */
@Composable
private fun ConnectionDetailsPanel(
    whatFailed: String,
    server: String,
    lastGoodUpdate: String,
    onRetryAll: () -> Unit,
    onSignOut: () -> Unit,
    retryAllFocus: FocusRequester,
    signOutFocus: FocusRequester,
    onClose: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current

    Column(
        modifier = Modifier
            .offset(x = d.px(96), y = d.px(262))
            .width(d.px(880))
            .shadow(
                elevation = d.px(32),
                shape = RoundedCornerShape(d.px(24)),
                ambientColor = HappyTvColors.shadow(0.55f),
                spotColor = HappyTvColors.shadow(0.55f),
                clip = false,
            )
            .background(HappyTvColors.Raised, RoundedCornerShape(d.px(24)))
            .padding(start = d.px(48), end = d.px(48), top = d.px(40), bottom = d.px(44)),
    ) {
        Text(
            text = "Connection issues",
            style = typography.heading,
            color = HappyTvColors.TextPrimary,
        )
        Spacer(Modifier.height(d.px(28)))
        DetailRow("What failed", whatFailed)
        DetailRow("Server", server)
        DetailRow("Last good update", lastGoodUpdate)
        Spacer(Modifier.height(d.px(32)))
        Row(horizontalArrangement = Arrangement.spacedBy(d.px(24))) {
            PrimaryButton(
                label = "Retry all",
                onClick = onRetryAll,
                focus = retryAllFocus,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                label = "Sign out",
                onClick = onSignOut,
                focus = signOutFocus,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Label column 300 px, values beside it. */
@Composable
private fun DetailRow(label: String, value: String) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    Row(modifier = Modifier.padding(bottom = d.px(16))) {
        Text(
            text = label,
            style = typography.captionSemibold,
            color = HappyTvColors.TextTertiary,
            modifier = Modifier.width(d.px(300)),
        )
        Text(
            text = value,
            style = typography.body,
            color = HappyTvColors.TextSecondary,
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Tile                                                                      */
/* -------------------------------------------------------------------------- */

/**
 * Two focus stops stacked 16 px apart: the body (544 × 420) and the status
 * pill (544 × 88). Signed out: the body takes no focus and the pill is hidden.
 */
@Composable
private fun TileColumn(
    tile: DashboardTile,
    viewModel: DashboardViewModel,
    bodyFocus: FocusRequester,
    pillFocus: FocusRequester,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onLongPress: () -> Unit,
    upFromBody: () -> Unit,
    downFromBody: () -> Unit,
    leftFromBody: () -> Unit,
    rightFromBody: () -> Unit,
    upFromPill: () -> Unit,
    downFromPill: () -> Unit,
    leftFromPill: () -> Unit,
    rightFromPill: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    Column(modifier = Modifier.width(d.px(544))) {
        TileBody(
            tile = tile,
            focus = bodyFocus,
            onOpen = onOpen,
            onLongPress = onLongPress,
            up = upFromBody,
            down = downFromBody,
            left = leftFromBody,
            right = rightFromBody,
        )
        if (tile.status !is TileStatus.Hidden) {
            Spacer(Modifier.height(d.px(16)))
            TileStatusPill(
                tile = tile,
                focus = pillFocus,
                onRefresh = onRefresh,
                up = upFromPill,
                down = downFromPill,
                left = leftFromPill,
                right = rightFromPill,
            )
        }
    }
}

/**
 * Body: radius 24, Surface, padding 44 top / 48 sides / 40 bottom, contents
 * pushed to top and bottom. OK opens the tile's screen; long-press OK opens
 * the tile menu.
 */
@Composable
private fun TileBody(
    tile: DashboardTile,
    focus: FocusRequester,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    up: () -> Unit,
    down: () -> Unit,
    left: () -> Unit,
    right: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // Inert unless signed in; dimmed only when we know it is signed out (the
    // neutral resolving state keeps the first-load look but is not clickable).
    val interactive = signedInOf(tile)
    val signedOut = tile.counts is TileCounts.SignedOut

    Box(
        modifier = Modifier
            .size(d.px(544), d.px(420))
            .background(HappyTvColors.Surface, RoundedCornerShape(d.px(24)))
            .happyTvFocusRing(isFocused, radius = d.px(24))
            .focusRequester(focus)
            .then(
                if (!interactive) {
                    // Body takes no focus and does nothing when signed out
                    // or while the session is still resolving.
                    Modifier.semantics { contentDescription = tile.bodyDescription }
                } else {
                    // Up is routed from *outside* the clickable: a handler written after
                    // combinedClickable().focusable() did not fire on the TV, so Up on the
                    // Shows tile fell back to geometry (Search) instead of the theme toggle.
                    Modifier.dpadNav(up = up).okHoldKeys(onLongClick = onLongPress).combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onLongClick = onLongPress,
                        onClick = onOpen,
                    ).focusable(interactionSource = interactionSource)
                        .dpadNav(up = up, down = down, left = left, right = right)
                        .semantics { contentDescription = tile.bodyDescription }
                },
            )
            .padding(start = d.px(48), end = d.px(48), top = d.px(44), bottom = d.px(40)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = tile.iconVector(),
                    contentDescription = null,
                    tint = if (signedOut) HappyTvColors.DisabledIcon else HappyTvColors.Accent,
                    modifier = Modifier.size(d.px(96)),
                )
                Spacer(Modifier.weight(1f))
                if (tile.showStaleBadge) StaleBadge()
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = tile.title,
                style = typography.display,
                color = if (signedOut) HappyTvColors.TextTertiary else HappyTvColors.TextPrimary,
            )
            when (val c = tile.counts) {
                is TileCounts.Placeholders -> {
                    Spacer(Modifier.height(d.px(12)))
                    PlaceholderBar(width = 264)
                    Spacer(Modifier.height(d.px(16)))
                    PlaceholderBar(width = 200)
                }
                is TileCounts.SignedOut -> {
                    Spacer(Modifier.height(d.px(12)))
                    Text(
                        text = c.prompt,
                        style = typography.body,
                        color = HappyTvColors.TextTertiary,
                    )
                }
                is TileCounts.Lines -> {
                    Spacer(Modifier.height(d.px(12)))
                    Text(
                        text = c.first,
                        style = typography.body.tabular(),
                        color = HappyTvColors.TextSecondary,
                    )
                    Text(
                        text = c.second,
                        style = typography.body.tabular(),
                        color = HappyTvColors.TextSecondary,
                    )
                }
            }
        }
    }
}

/** 264 × 28 / 200 × 28, radius 14, Loading. Never "0 channels". */
@Composable
private fun PlaceholderBar(width: Int) {
    val d = LocalHappyTvDesign.current
    Box(
        modifier = Modifier
            .size(d.px(width), d.px(28))
            .background(HappyTvColors.Loading, RoundedCornerShape(d.px(14))),
    )
}

/** 48 tall, padding 0 20, radius 24, stale tint, "Stale" 28/36 700 #F2B544. */
@Composable
private fun StaleBadge() {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    Box(
        modifier = Modifier
            .height(d.px(48))
            .background(HappyTvColors.StateStaleTint, RoundedCornerShape(d.px(24)))
            .padding(horizontal = d.px(20)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Stale",
            style = typography.captionBold,
            color = HappyTvColors.StateStale,
        )
    }
}

/**
 * Status pill: radius 44, Surface, padding 0 28, items 12 apart, with the
 * progress track 28 px from each side and 14 px from the bottom. The bar and
 * the stamp are mutually exclusive ("never both, never neither").
 */
@Composable
private fun TileStatusPill(
    tile: DashboardTile,
    focus: FocusRequester,
    onRefresh: () -> Unit,
    up: () -> Unit,
    down: () -> Unit,
    left: () -> Unit,
    right: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val status = tile.status

    // Bar motion: fill grows 0 → 1 on Complete, collapses to 0.35, and the
    // track fades to 60% while collapsing.
    val fill by animateFloatAsState(
        targetValue = when (status) {
            is TileStatus.Completing -> 1f
            is TileStatus.Collapsing -> 0.35f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 200, easing = EaseInOutCubic),
        label = "bar-fill",
    )
    val trackAlpha by animateFloatAsState(
        targetValue = if (status is TileStatus.Collapsing) 0.6f else 1f,
        animationSpec = tween(durationMillis = 200, easing = EaseInOutCubic),
        label = "track-alpha",
    )
    // The check grows 60% → 80% → 100% size and 0% → 50% → 100% opacity.
    val checkScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = keyframes {
            durationMillis = 200
            0.6f at 0 using EaseInOutCubic
            0.8f at 100 using EaseInOutCubic
            1f at 200 using EaseInOutCubic
        },
        label = "check-scale",
    )
    val checkAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = keyframes {
            durationMillis = 200
            0f at 0 using EaseInOutCubic
            0.5f at 100 using EaseInOutCubic
            1f at 200 using EaseInOutCubic
        },
        label = "check-alpha",
    )

    Box(
        modifier = Modifier
            .size(d.px(544), d.px(88))
            .background(HappyTvColors.Surface, RoundedCornerShape(percent = 50))
            .happyTvFocusRing(isFocused, radius = d.px(44))
            .focusRequester(focus)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onRefresh,
            )
            .focusable(interactionSource = interactionSource)
            .dpadNav(up = up, down = down, left = left, right = right)
            .semantics { contentDescription = tile.pillDescription },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = d.px(28)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.px(12)),
        ) {
            // 40 px status icon slot.
            Box(modifier = Modifier.size(d.px(40)), contentAlignment = Alignment.Center) {
                when (status) {
                    is TileStatus.Collapsing -> Icon(
                        imageVector = HappyTvIcons.Check,
                        contentDescription = null,
                        tint = HappyTvColors.StateSuccess,
                        modifier = Modifier
                            .size(d.px(40))
                            .graphicsLayer { scaleX = checkScale; scaleY = checkScale }
                            .alpha(checkAlpha),
                    )
                    is TileStatus.Stamp -> when (status.age) {
                        TileStatus.AgeTier.FRESH -> Icon(
                            imageVector = HappyTvIcons.Check,
                            contentDescription = null,
                            tint = HappyTvColors.StateSuccess,
                            modifier = Modifier.size(d.px(40)),
                        )
                        TileStatus.AgeTier.RECENT -> Icon(
                            imageVector = HappyTvIcons.Clock,
                            contentDescription = null,
                            tint = HappyTvColors.TextTertiary,
                            modifier = Modifier.size(d.px(40)),
                        )
                        TileStatus.AgeTier.STALE -> Icon(
                            imageVector = HappyTvIcons.Clock,
                            contentDescription = null,
                            tint = HappyTvColors.StateStale,
                            modifier = Modifier.size(d.px(40)),
                        )
                    }
                    is TileStatus.Failed -> Icon(
                        imageVector = HappyTvIcons.Alert,
                        contentDescription = null,
                        tint = HappyTvColors.StateError,
                        modifier = Modifier.size(d.px(40)),
                    )
                    else -> Unit // Updating / Completing: no icon.
                }
            }
            Text(
                text = when (status) {
                    is TileStatus.Updating, is TileStatus.Completing -> "Updating…"
                    is TileStatus.Collapsing -> "Updated just now"
                    is TileStatus.Stamp -> status.text
                    is TileStatus.Failed -> "Couldn't reach server"
                    is TileStatus.Hidden -> ""
                },
                style = typography.captionSemibold,
                color = if (status is TileStatus.Failed) {
                    HappyTvColors.StateError
                } else {
                    HappyTvColors.TextSecondary
                },
            )
            Spacer(Modifier.weight(1f))
            when (status) {
                is TileStatus.Failed -> Text(
                    text = "Retry",
                    style = typography.caption.copy(fontWeight = FontWeight.ExtraBold),
                    color = HappyTvColors.Accent,
                )
                is TileStatus.Stamp -> Icon(
                    imageVector = HappyTvIcons.Refresh,
                    contentDescription = null,
                    tint = HappyTvColors.TextSecondary,
                    modifier = Modifier.size(d.px(40)),
                )
                else -> Unit // Updating / Complete / Collapsing: nothing at the right.
            }
        }

        // Progress track: 28 px from each side, 14 px from the bottom, 6 px
        // tall, radius 3. The indeterminate segment and the fill are accent.
        if (status is TileStatus.Updating ||
            status is TileStatus.Completing ||
            status is TileStatus.Collapsing
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = d.px(28), end = d.px(28), bottom = d.px(14))
                    .fillMaxWidth()
                    .height(d.px(6))
                    .alpha(trackAlpha)
                    .clip(RoundedCornerShape(d.px(3)))
                    .background(HappyTvColors.ProgressTrack),
            ) {
                if (status is TileStatus.Updating) {
                    IndeterminateSegment()
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fill)
                            .height(d.px(6))
                            .background(HappyTvColors.Accent),
                    )
                }
            }
        }
    }
}

/**
 * The indeterminate segment: loops every 1.4 s and fades out in 120 ms as the
 * determinate fill starts (design spec "Exact properties").
 */
@Composable
private fun IndeterminateSegment() {
    val d = LocalHappyTvDesign.current
    val transition = rememberInfiniteTransition(label = "indeterminate")
    val x by transition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
        ),
        label = "indeterminate-x",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = size.width * x },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.35f)
                .height(d.px(6))
                .background(HappyTvColors.Accent),
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Second row, welcome strip, menu                                           */
/* -------------------------------------------------------------------------- */

/** top 842, 1728 × 128, radius 24, Surface, padding 0 40, items 28 apart. */
@Composable
private fun SecondRow(
    signedIn: Boolean,
    guideFocus: FocusRequester,
    settingsFocus: FocusRequester,
    onOpenTvGuide: () -> Unit,
    onOpenSettings: () -> Unit,
    upFromRow: () -> Unit,
    leftFromSettings: () -> Unit,
    rightFromGuide: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    Row(
        modifier = Modifier
            .offset(x = d.px(96), y = d.px(842))
            .size(d.px(1728), d.px(128)),
        horizontalArrangement = Arrangement.spacedBy(d.px(48)),
    ) {
        SecondRowCard(
            title = "TV guide",
            subtitle = "See what's on now and later.",
            icon = HappyTvIcons.Guide,
            widthPx = 1136,
            enabled = signedIn,
            focus = guideFocus,
            onClick = onOpenTvGuide,
            up = upFromRow,
            right = rightFromGuide,
        )
        SecondRowCard(
            title = "Settings",
            subtitle = "Account, refresh, guide",
            icon = HappyTvIcons.Sliders,
            widthPx = 544,
            enabled = true,
            focus = settingsFocus,
            onClick = onOpenSettings,
            up = upFromRow,
            left = leftFromSettings,
        )
    }
}

@Composable
private fun SecondRowCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    widthPx: Int,
    enabled: Boolean,
    focus: FocusRequester,
    onClick: () -> Unit,
    up: () -> Unit = {},
    left: () -> Unit = {},
    right: () -> Unit = {},
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .size(d.px(widthPx), d.px(128))
            .background(HappyTvColors.Surface, RoundedCornerShape(d.px(24)))
            .happyTvFocusRing(isFocused && enabled, radius = d.px(24))
            .focusRequester(focus)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    ).focusable(interactionSource = interactionSource)
                        .dpadNav(up = up, left = left, right = right)
                        .semantics { contentDescription = "$title. $subtitle" }
                } else {
                    Modifier.semantics { contentDescription = "$title, unavailable" }
                },
            )
            .padding(horizontal = d.px(40)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.px(28)),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) HappyTvColors.Accent else HappyTvColors.DisabledIcon,
                modifier = Modifier.size(d.px(56)),
            )
            Column {
                Text(
                    text = title,
                    style = typography.heading,
                    color = if (enabled) HappyTvColors.TextPrimary else HappyTvColors.TextTertiary,
                )
                Text(
                    text = subtitle,
                    style = typography.caption,
                    color = HappyTvColors.TextSecondary,
                )
            }
        }
    }
}

/** Signed out: top 706, 1728 × 88 pill, accent tint, padding 0 8 0 44. */
@Composable
private fun WelcomeStrip(
    focus: FocusRequester,
    onSignIn: () -> Unit,
    upFromSignIn: () -> Unit,
    downFromSignIn: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .offset(x = d.px(96), y = d.px(706))
            .size(d.px(1728), d.px(88))
            .background(HappyTvColors.AccentTint, RoundedCornerShape(percent = 50))
            .padding(start = d.px(44), end = d.px(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Welcome to HappyTV. Sign in to your IPTV provider to get started.",
            style = typography.body,
            color = HappyTvColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(d.px(24)))
        Box(
            modifier = Modifier
                .height(d.px(72))
                .background(HappyTvColors.Accent, RoundedCornerShape(percent = 50))
                // Accent fill → near-white ring (F-33).
                .happyTvFocusRing(isFocused, radius = d.px(36), focusRingColor = HappyTvColors.focusRingOn(HappyTvColors.Accent))
                .focusRequester(focus)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onSignIn,
                )
                .focusable(interactionSource = interactionSource)
                .dpadNav(up = upFromSignIn, down = downFromSignIn)
                .semantics { contentDescription = "Sign in" }
                .padding(horizontal = d.px(48)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Sign in",
                style = typography.bodyExtraBold,
                color = HappyTvColors.OnAccent,
            )
        }
    }
}

/**
 * #192: gives focus back to the tile that opened the menu once the menu has left the
 * composition. Requesting it while the menu still holds focus is unreliable.
 */
@Composable
internal fun RestoreFocusWhenMenuCloses(menuTile: StreamType?, restore: (StreamType) -> Unit) {
    var opener by remember { mutableStateOf<StreamType?>(null) }
    LaunchedEffect(menuTile) {
        if (menuTile != null) {
            opener = menuTile
        } else {
            opener?.let(restore)
            opener = null
        }
    }
}

/**
 * Long-press menu: scrim over the dashboard, the menu 600 wide at top 420,
 * left 760. "About this data" replaces the items with two rows and a full
 *-width Close. Back or Close returns focus to the tile.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TileLongPressMenu(
    state: DashboardUiState,
    onRefresh: (StreamType) -> Unit,
    onShowAbout: () -> Unit,
    onClose: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val type = state.menuTile ?: return
    val title = state.menuTitle ?: return
    val itemFocus = remember { List(2) { FocusRequester() } }
    val closeFocus = remember { FocusRequester() }
    // The first item takes focus; About moves focus to Close (#176), the only actionable
    // thing in that view.
    LaunchedEffect(state.menuShowingAbout) {
        if (state.menuShowingAbout) closeFocus.requestFocus() else itemFocus[0].requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HappyTvColors.ScrimMenu)
            .pointerInput(type) { detectTapGestures { onClose() } }
            // #192: trap focus. Any direction with no target inside the menu is cancelled
            // instead of searching the tiles behind the scrim.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back && event.type == KeyEventType.KeyDown) {
                    onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .offset(x = d.px(760), y = d.px(420))
                .width(d.px(600))
                .shadow(
                    elevation = d.px(32),
                    shape = RoundedCornerShape(d.px(24)),
                    ambientColor = HappyTvColors.shadow(0.55f),
                    spotColor = HappyTvColors.shadow(0.55f),
                    clip = false,
                )
                .background(HappyTvColors.Raised, RoundedCornerShape(d.px(24)))
                .padding(d.px(16)),
        ) {
            Text(
                text = title,
                style = LocalHappyTvTypography.current.captionSemibold,
                color = HappyTvColors.TextTertiary,
                modifier = Modifier.padding(start = d.px(24), end = d.px(24), top = d.px(12), bottom = d.px(8)),
            )
            if (!state.menuShowingAbout) {
                MenuItem(
                    label = "Refresh $title now",
                    icon = HappyTvIcons.Refresh,
                    focus = itemFocus[0],
                    onClick = {
                        onRefresh(type)
                        onClose()
                    },
                    down = { itemFocus[1].requestFocus() },
                )
                MenuItem(
                    label = "About this data",
                    icon = HappyTvIcons.Info,
                    focus = itemFocus[1],
                    onClick = onShowAbout,
                    up = { itemFocus[0].requestFocus() },
                )
            } else {
                MenuAboutRow(label = "Last updated", value = state.aboutLastUpdated)
                MenuAboutRow(label = "Server", value = state.serverLabel)
                SecondaryButton(
                    label = "Close",
                    onClick = onClose,
                    focus = closeFocus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = d.px(ABOUT_CLOSE_GAP_PX)),
                )
            }
        }
    }
}

/** 88 px item, radius 16, transparent, padding 0 24: 40 px icon + 36/44 600, 20 apart. */
@Composable
private fun MenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    focus: FocusRequester,
    onClick: () -> Unit,
    up: () -> Unit = {},
    down: () -> Unit = {},
) {
    val d = LocalHappyTvDesign.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .height(d.px(88))
            .fillMaxWidth()
            .background(Color.Transparent, RoundedCornerShape(d.px(16)))
            .happyTvFocusRing(isFocused, radius = d.px(16))
            .focusRequester(focus)
            .ignoreOrphanEnterUp()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource)
            .dpadNav(up = up, down = down)
            .semantics { contentDescription = label }
            .padding(horizontal = d.px(24)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.px(20)),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = HappyTvColors.TextSecondary,
                modifier = Modifier.size(d.px(40)),
            )
            Text(
                text = label,
                style = LocalHappyTvTypography.current.bodySemibold,
                color = HappyTvColors.TextPrimary,
            )
        }
    }
}

/** Space between the About view's last row and its Close button (#176). */
internal const val ABOUT_CLOSE_GAP_PX = 24

/** About view row (plain text, not focusable: Close is the only stop): label 28/36 600 Tertiary, value 36/44 500. */
@Composable
private fun MenuAboutRow(label: String, value: String) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = d.px(24), end = d.px(24), top = d.px(12), bottom = d.px(12)),
    ) {
        Text(text = label, style = typography.captionSemibold, color = HappyTvColors.TextTertiary)
        Text(text = value, style = typography.body, color = HappyTvColors.TextPrimary)
    }
}

/* -------------------------------------------------------------------------- */
/*  Buttons (design spec §Components > Buttons)                                   */
/* -------------------------------------------------------------------------- */

/** Primary: 88 tall, pill, accent, on-accent 36/44 800. */
@Composable
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val d = LocalHappyTvDesign.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .height(d.px(88))
            .background(HappyTvColors.Accent, RoundedCornerShape(percent = 50))
            // Accent fill → near-white ring (F-33).
            .happyTvFocusRing(isFocused, radius = d.px(44), focusRingColor = HappyTvColors.focusRingOn(HappyTvColors.Accent))
            .focusRequester(focus)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = LocalHappyTvTypography.current.bodyExtraBold,
            color = HappyTvColors.OnAccent,
        )
    }
}

/** Secondary: same size, Overlay, Primary 36/44 700. */
@Composable
private fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val d = LocalHappyTvDesign.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .height(d.px(88))
            .background(HappyTvColors.Overlay, RoundedCornerShape(percent = 50))
            .happyTvFocusRing(isFocused, radius = d.px(44))
            .focusRequester(focus)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = LocalHappyTvTypography.current.bodyBold,
            color = HappyTvColors.TextPrimary,
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Focus plumbing + pure helpers                                             */
/* -------------------------------------------------------------------------- */

/**
 * Explicit D-pad routing — only the directions a stop declares are consumed,
 * everything else falls through (so the OK key still reaches `clickable`).
 */
internal fun Modifier.dpadNav(
    up: (() -> Unit)? = null,
    down: (() -> Unit)? = null,
    left: (() -> Unit)? = null,
    right: (() -> Unit)? = null,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val move: (() -> Unit)? = when (event.key) {
        Key.DirectionUp -> up
        Key.DirectionDown -> down
        Key.DirectionLeft -> left
        Key.DirectionRight -> right
        else -> null
    }
    if (move == null) false else {
        move()
        true
    }
}

/** Whether the tile knows the user is signed in (its status is not Hidden). */
private fun signedInOf(tile: DashboardTile): Boolean = tile.status !is TileStatus.Hidden

/** Per-tile icon vector — Live TV / Movies / Shows, from the design spec set. */
private fun DashboardTile.iconVector(): androidx.compose.ui.graphics.vector.ImageVector =
    when (type) {
        StreamType.LIVE -> HappyTvIcons.LiveTv
        StreamType.VOD -> HappyTvIcons.Movie
        StreamType.SERIES -> HappyTvIcons.Show
    }

/**
 * "Last signed in 12 hours ago" / "Signed in just now" right after sign-in /
 * "Not signed in" when signed out (design spec header copy).
 */
internal fun lastSignedInLabel(
    epochMillis: Long,
    now: Long,
    isSignedIn: Boolean,
): String {
    if (!isSignedIn || epochMillis <= 0L) return "Not signed in"
    val deltaMin = ((now - epochMillis) / 60_000L).toInt().coerceAtLeast(0)
    return when {
        deltaMin < 1 -> "Signed in just now"
        deltaMin == 1 -> "Last signed in 1 minute ago"
        deltaMin < 60 -> "Last signed in $deltaMin minutes ago"
        deltaMin < 120 -> "Last signed in 1 hour ago"
        else -> "Last signed in ${deltaMin / 60} hours ago"
    }
}

/** Unused-typed-arg guard for Dp imports in some toolchains. */
@Suppress("unused")
private val unusedDp: Dp? = null
