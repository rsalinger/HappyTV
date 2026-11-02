package com.rysal.happytv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.rysal.happytv.ui.theme.happyTvFocusRing
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rysal.happytv.BuildConfig
import com.rysal.happytv.data.BackWhileWatching
import com.rysal.happytv.data.LiveTvOpenOn
import com.rysal.happytv.ui.focus.awaitFocus
import com.rysal.happytv.ui.focus.htvFocusTarget
import com.rysal.happytv.ui.focus.indexAfterRemoval
import com.rysal.happytv.ui.focus.tryRequest
import com.rysal.happytv.ui.focus.requestWhenAttached
import com.rysal.happytv.ui.browse.HEADER_BOTTOM_PX
import com.rysal.happytv.ui.browse.HEADER_TOP_PX
import com.rysal.happytv.ui.browse.HtHeader
import com.rysal.happytv.ui.browse.HtPrimaryButton
import androidx.compose.ui.text.font.FontWeight
import com.rysal.happytv.ui.browse.HtSecondaryButton
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.browseType
import com.rysal.happytv.ui.browse.htvClickableSurface
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.tabular

/**
 * Settings — design spec §"Screens: settings": one screen in two panes, five
 * sections on the left, the chosen section's settings on the right, and every
 * change written straight through (there is no Save button).
 *
 * Focus follows the artboards: it starts on the open section in the rail
 * (except the signed-out Account pane, whose card starts on **Sign in**);
 * moving down the rail swaps the pane without pressing OK — the same
 * convention Live TV's rail uses — and Right steps into the pane's first stop.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onGoToDashboard: () -> Unit,
    onGoToSignIn: () -> Unit,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val d = browseDesign()
    val type = browseType()

    // Row focus keys, so a confirmation can hand focus back to whatever opened
    // it (design spec: "Cancel or Back returns focus to the row that opened the
    // dialog").
    val rowFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    var lastFocusedKey by remember { mutableStateOf<String?>(null) }
    fun focusOf(key: String): FocusRequester = rowFocus.getOrPut(key) { FocusRequester() }

    val railRequester = remember {
        SettingsSection.entries.associateWith { focusOf(RAIL_KEY_PREFIX + it.extra) }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.GoToDashboard -> onGoToDashboard()
                SettingsEffect.GoToSignIn -> onGoToSignIn()
            }
        }
    }

    // Seed focus once the account state has answered: on the signed-out
    // Account pane that is the card's Sign in button, everywhere else the rail.
    var focusSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(focusSeeded, state.accountResolved, state.section) {
        if (focusSeeded || !state.accountResolved) return@LaunchedEffect
        if (state.section == SettingsSection.ACCOUNT && !state.signedIn) {
            focusOf(FOCUS_KEY_SIGN_IN).requestFocus()
            lastFocusedKey = FOCUS_KEY_SIGN_IN
        } else {
            railRequester.getValue(state.section).requestFocus()
            lastFocusedKey = RAIL_KEY_PREFIX + state.section.extra
        }
        focusSeeded = true
    }

    val chain = settingsFocusChain(state, ::focusOf, leftTo = railRequester.getValue(state.section))

    val dismissDialog: () -> Unit = { viewModel.dismissDialog() }

    // Hand focus back to the row that opened the dialog once the dialog has left
    // the composition. Asking while the dialog still holds focus (inside its
    // focus trap) is dropped, so this runs after the removal.
    var dialogWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.dialog) {
        if (state.dialog == null && dialogWasOpen) {
            val key = lastFocusedKey ?: (RAIL_KEY_PREFIX + state.section.extra)
            focusOf(key).requestFocus()
        }
        dialogWasOpen = state.dialog != null
    }

    BackHandler(enabled = state.dialog != null) { dismissDialog() }
    // #160: while sign-out clears the saved lists, Back does nothing rather than leave a half-signed-out screen.
    BackHandler(enabled = state.signingOut) { }

    Box(modifier = modifier.fillMaxSize()) {
        // Compact header (spec #7 section 2): 72 tall, title and subtitle on one line.
        HtHeader(
            title = "Settings",
            subtitle = "Changes save right away and stay on this TV.",
            onBack = onBack,
            modifier = Modifier
                .offset(x = d.px(HEADER_X_PX), y = d.px(HEADER_Y_PX))
                .width(d.px(CONTENT_WIDTH_PX)),
        )

        // ── Sections rail: left 96, top 206, 520 wide, 8 apart ────────────
        Column(
            modifier = Modifier
                .offset(x = d.px(RAIL_X_PX), y = d.px(PANE_TOP_PX))
                .width(d.px(RAIL_WIDTH_PX))
                .focusProperties { canFocus = !state.signingOut },
            verticalArrangement = Arrangement.spacedBy(d.px(RAIL_GAP_PX)),
        ) {
            SettingsSection.entries.forEach { section ->
                SectionRow(
                    label = section.label,
                    selected = state.section == section,
                    onClick = { viewModel.selectSection(section) },
                    onFocused = {
                        lastFocusedKey = RAIL_KEY_PREFIX + section.extra
                        viewModel.selectSection(section)
                    },
                    focusRequester = railRequester.getValue(section),
                    rightTo = chain.stops.firstOrNull()?.entry,
                )
            }
        }

        // ── Pane: x 664 → 1824, top 206, 1160 wide ───────────────────────
        Column(
            modifier = Modifier
                .offset(x = d.px(PANE_X_PX), y = d.px(PANE_TOP_PX))
                .width(d.px(PANE_WIDTH_PX))
                .focusProperties {
                    canFocus = !state.signingOut
                    // #162: Left out of the pane always returns to the open section's rail row.
                    // Without it the geometric search picked the rail row level with the focused
                    // row, and the rail selects on focus, so the pane switched sections.
                    exit = { direction ->
                        if (direction == FocusDirection.Left) railRequester.getValue(state.section) else FocusRequester.Default
                    }
                }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(d.px(PANE_TOP_GAP_PX)),
        ) {
            Text(text = state.section.label, style = type.compactHeading, color = HappyTvColors.TextPrimary)
            CompositionLocalProvider(LocalSettingsFocusChain provides chain) {
            Column(verticalArrangement = Arrangement.spacedBy(d.px(ROW_GAP_PX))) {
                when (state.section) {
                    SettingsSection.ACCOUNT -> AccountPane(
                        state = state,
                        viewModel = viewModel,
                        focusOf = ::focusOf,
                        onRowFocused = { lastFocusedKey = it },
                    )
                    SettingsSection.REFRESH -> RefreshPane(
                        state = state,
                        viewModel = viewModel,
                        focusOf = ::focusOf,
                        onRowFocused = { lastFocusedKey = it },
                    )
                    SettingsSection.LIVE_TV -> LiveTvPane(
                        state = state,
                        viewModel = viewModel,
                        focusOf = ::focusOf,
                    )
                    SettingsSection.GUIDE -> GuidePane(
                        state = state,
                        viewModel = viewModel,
                        focusOf = ::focusOf,
                        onRowFocused = { lastFocusedKey = it },
                    )
                    SettingsSection.HIDDEN -> HiddenItemsPane(
                        state = state,
                        viewModel = viewModel,
                        focusOf = ::focusOf,
                        onRowFocused = { lastFocusedKey = it },
                    )
                    SettingsSection.PLAYER -> PlayerPane(
                        state = state,
                        viewModel = viewModel,
                        focusOf = ::focusOf,
                        onRowFocused = { lastFocusedKey = it },
                    )
                    SettingsSection.ABOUT -> AboutPane(
                        focusOf = ::focusOf,
                        onRowFocused = { lastFocusedKey = it },
                        onReset = viewModel::resetToDefaults,
                        resetDone = state.resetDone,
                    )
                }
            }
            }
        }

        if (state.signingOut) {
            SigningOutCard()
        }

        state.dialog?.let { dialog ->
            SettingsConfirmDialog(
                dialog = dialog,
                serverName = state.account?.serverName,
                onConfirm = viewModel::confirmDialog,
                onDismiss = dismissDialog,
            )
        }
    }
}

// ------------------------------------------------------------------ rail rows

@Composable
private fun SectionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester?,
    rightTo: FocusRequester?,
) {
    val d = browseDesign()
    val type = browseType()
    Row(
        modifier = Modifier
            .width(d.px(RAIL_WIDTH_PX))
            .height(d.px(RAIL_ROW_H_PX))
            // Requester before the focusable it targets, or requestFocus() is a no-op.
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // #59: Right always enters the pane on its first row.
            .focusProperties { if (rightTo != null) right = rightTo }
            .htvClickableSurface(
                radiusPx = RAIL_ROW_H_PX / 2,
                background = if (selected) HappyTvColors.Surface else HappyTvColors.Ground,
                onClick = onClick,
                onFocused = onFocused,
            )
            .padding(horizontal = d.px(32)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = if (selected) type.compactRowName else type.compactRowName.copy(fontWeight = FontWeight.W600),
            color = if (selected) HappyTvColors.TextPrimary else HappyTvColors.TextSecondary,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ pane rows

/**
 * The current pane's rows in the order [AccountPane] … [AboutPane] draw them
 * (#59). Keep this beside those panes: a new row needs a stop here, or vertical
 * D-pad movement steps over it. `SettingsVerticalFocusTest` walks every pane.
 */
private fun settingsFocusChain(
    state: SettingsUiState,
    focusOf: (String) -> FocusRequester,
    leftTo: FocusRequester? = null,
): SettingsFocusChain {
    fun single(key: String) = focusOf(key).let { SettingsFocusStop(it, listOf(it)) }
    fun segmented(prefix: String, names: List<String>, selected: Int): SettingsFocusStop {
        val all = names.map { focusOf(prefix + it) }
        return SettingsFocusStop(all[selected.coerceIn(all.indices)], all)
    }
    val settings = state.settings
    val stops = when (state.section) {
        SettingsSection.ACCOUNT ->
            if (!state.signedIn || state.account == null) {
                listOf(FOCUS_KEY_SIGN_IN, FOCUS_KEY_BACKUP).map(::single)
            } else {
                // The switch sits after the read-only rows and before Switch account, so a stray OK
                // from the row above can only toggle a harmless switch; Switch account itself
                // only opens a confirmation.
                listOf(
                    FOCUS_KEY_SERVER, FOCUS_KEY_USERNAME, FOCUS_KEY_SIGNED_IN,
                    FOCUS_KEY_BACKUP, FOCUS_KEY_SWITCH_ACCOUNT,
                ).map(::single)
            }
        SettingsSection.REFRESH -> listOf(
            single(FOCUS_KEY_REFRESH_ON_START),
            segmented(
                FOCUS_KEY_STREAM_FORMAT,
                StreamFormatOption.entries.map { it.name },
                StreamFormatOption.fromStored(settings.preferredStreamFormat).ordinal,
            ),
            single(FOCUS_KEY_REFRESH_ALL),
        )
        SettingsSection.LIVE_TV -> listOf(
            segmented(
                FOCUS_KEY_LIVE_TV_OPEN_ON,
                LiveTvOpenOn.entries.map { it.name },
                settings.liveTvOpenOn.ordinal,
            ),
            single(FOCUS_KEY_KEEP_CATEGORIES_OPEN),
        )
        SettingsSection.GUIDE -> buildList {
            val source = if (state.effectiveXmlTvUrl.isBlank()) GuideSource.PROVIDER else GuideSource.XMLTV
            add(segmented(FOCUS_KEY_GUIDE_SOURCE, GuideSource.entries.map { it.name }, source.ordinal))
            if (state.xmltvLinkShown) add(single(FOCUS_KEY_XMLTV_FIELD))
            add(
                segmented(
                    FOCUS_KEY_GUIDE_HOURS,
                    GuideRefreshOption.entries.map { it.hours.toString() },
                    GuideRefreshOption.fromHours(settings.epgRefreshHours).ordinal,
                ),
            )
        }
        // #123: the rail's Right lands on the pane's first row; the rows then move by the lazy
        // list's own traversal (the list is windowed, so its rows cannot all be chain stops).
        SettingsSection.HIDDEN -> listOf(
            single(
                state.hiddenCategories.firstOrNull()?.let(::hiddenCategoryFocusKey) ?: FOCUS_KEY_HIDDEN_NO_CATEGORIES,
            ),
        )
        SettingsSection.PLAYER -> listOf(
            single(FOCUS_KEY_REPLAY_FROM_START),
            segmented(
                FOCUS_KEY_BACK_WHILE_WATCHING,
                BackWhileWatching.entries.map { it.name },
                settings.backWhileWatching.ordinal,
            ),
            segmented(
                FOCUS_KEY_ASPECT,
                AspectRatioOption.entries.map { it.name },
                AspectRatioOption.fromStored(settings.aspectRatio).ordinal,
            ),
            single(FOCUS_KEY_UNENCRYPTED_SIGN_IN),
        )
        SettingsSection.ABOUT ->
            listOf(FOCUS_KEY_VERSION, FOCUS_KEY_BUILD, FOCUS_KEY_YOUR_DATA, FOCUS_KEY_RESET).map(::single)
    }
    return SettingsFocusChain(stops, leftTo = leftTo)
}

/** Account — the signed-out state is one card with a focused Sign in. */
@Composable
private fun AccountPane(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    focusOf: (String) -> FocusRequester,
    onRowFocused: (String) -> Unit,
) {
    val account = state.account
    if (!state.signedIn || account == null) {
        SignedOutCard(
            onSignIn = viewModel::goToSignIn,
            focusRequester = focusOf(FOCUS_KEY_SIGN_IN),
        )
        BackupRow(state, viewModel, focusOf, onRowFocused)
        return
    }
    SettingsInfoRow(
        label = "Server",
        value = account.serverLabel,
        onFocused = { onRowFocused(FOCUS_KEY_SERVER) },
        focusRequester = focusOf(FOCUS_KEY_SERVER),
    )
    SettingsInfoRow(
        label = "Username",
        value = account.username,
        onFocused = { onRowFocused(FOCUS_KEY_USERNAME) },
        focusRequester = focusOf(FOCUS_KEY_USERNAME),
    )
    SettingsInfoRow(
        label = "Signed in",
        value = account.signedInLabel(System.currentTimeMillis()),
        onFocused = { onRowFocused(FOCUS_KEY_SIGNED_IN) },
        focusRequester = focusOf(FOCUS_KEY_SIGNED_IN),
    )
    BackupRow(state, viewModel, focusOf, onRowFocused)
    SettingsActionRow(
        label = "Switch account",
        caption = "Signs you out of this server and clears its saved lists.",
        onClick = viewModel::switchAccount,
        onFocused = { onRowFocused(FOCUS_KEY_SWITCH_ACCOUNT) },
        focusRequester = focusOf(FOCUS_KEY_SWITCH_ACCOUNT),
    )
}

/** #19: device-level, so it shows signed in or out (a restored install is signed out). */
@Composable
private fun BackupRow(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    focusOf: (String) -> FocusRequester,
    onRowFocused: (String) -> Unit,
) {
    val on = state.settings.backupHousehold
    SettingsSwitchRow(
        label = BackupCopy.LABEL,
        caption = if (on) BackupCopy.CAPTION_ON else BackupCopy.CAPTION_OFF,
        on = on,
        onToggle = { viewModel.setBackupHousehold(!on) },
        onFocused = { onRowFocused(FOCUS_KEY_BACKUP) },
        focusRequester = focusOf(FOCUS_KEY_BACKUP),
    )
}

/** design spec §"Settings > States > Not signed in". */
@Composable
private fun SignedOutCard(
    onSignIn: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val d = browseDesign()
    val type = browseType()
    Column(
        modifier = Modifier
            .background(HappyTvColors.Surface, RoundedCornerShape(d.px(24)))
            .padding(d.px(40)),
        verticalArrangement = Arrangement.spacedBy(d.px(24)),
    ) {
        Text(
            text = "Sign in to set up your provider.",
            style = type.compactBody,
            color = HappyTvColors.TextSecondary,
        )
        HtPrimaryButton(
            label = "Sign in",
            onClick = onSignIn,
            focusRequester = focusRequester,
        )
    }
}

@Composable
private fun RefreshPane(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    focusOf: (String) -> FocusRequester,
    onRowFocused: (String) -> Unit,
) {
    val settings = state.settings
    SettingsSwitchRow(
        label = "Refresh on start",
        caption = "Update channels, movies and shows when HappyTV opens, unless updated in the last 30 minutes.",
        on = settings.refreshOnStart,
        onToggle = { viewModel.setRefreshOnStart(!settings.refreshOnStart) },
        onFocused = { onRowFocused(FOCUS_KEY_REFRESH_ON_START) },
        focusRequester = focusOf(FOCUS_KEY_REFRESH_ON_START),
    )
    SettingsSegmentedRow(
        label = "Stream format",
        caption = "Try TS if channels stutter or won't start.",
        options = StreamFormatOption.entries.map { it.label },
        selectedIndex = StreamFormatOption.fromStored(settings.preferredStreamFormat).ordinal,
        onSelect = { index -> viewModel.setStreamFormat(StreamFormatOption.entries[index]) },
        contentDescription = "Stream format",
        focusRequesters = StreamFormatOption.entries.map { focusOf(FOCUS_KEY_STREAM_FORMAT + it.name) },
    )
    SettingsActionRow(
        label = "Refresh everything now",
        caption = RefreshEverythingCopy.ROW_CAPTION,
        onClick = viewModel::refreshEverything,
        onFocused = { onRowFocused(FOCUS_KEY_REFRESH_ALL) },
        focusRequester = focusOf(FOCUS_KEY_REFRESH_ALL),
    )
}

@Composable
private fun LiveTvPane(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    focusOf: (String) -> FocusRequester,
) {
    val settings = state.settings
    SettingsSegmentedRow(
        label = "Open Live TV on",
        caption = "What you see first when you open Live TV.",
        options = LiveTvOpenOn.entries.map { it.label },
        selectedIndex = settings.liveTvOpenOn.ordinal,
        onSelect = { index -> viewModel.setLiveTvOpenOn(LiveTvOpenOn.entries[index]) },
        contentDescription = "Open Live TV on",
        focusRequesters = LiveTvOpenOn.entries.map { focusOf(FOCUS_KEY_LIVE_TV_OPEN_ON + it.name) },
    )
    SettingsSwitchRow(
        label = "Keep categories open",
        caption = "Keep the category list open in Live TV, Movies and Shows instead of folding it away.",
        on = settings.keepCategoriesOpen,
        onToggle = { viewModel.setKeepCategoriesOpen(!settings.keepCategoriesOpen) },
        onFocused = {},
        focusRequester = focusOf(FOCUS_KEY_KEEP_CATEGORIES_OPEN),
    )
}

@Composable
private fun GuidePane(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    focusOf: (String) -> FocusRequester,
    onRowFocused: (String) -> Unit,
) {
    val settings = state.settings
    // #94: the automatic guide is "From provider"; "XMLTV link" is highlighted
    // only when the user chose it or has a link of their own, and then the
    // link field is visible. Control and field read the same state.
    val source = state.guideSource
    SettingsSegmentedRow(
        label = "Guide source",
        caption = "Where the TV guide comes from.",
        options = GuideSource.entries.map { it.label },
        selectedIndex = source.ordinal,
        onSelect = { index -> viewModel.chooseGuideSource(GuideSource.entries[index]) },
        contentDescription = "Guide source",
        focusRequesters = GuideSource.entries.map { focusOf(FOCUS_KEY_GUIDE_SOURCE + it.name) },
    )
    if (state.xmltvLinkShown) {
        XmltvLinkRow(
            // #18: never a credential — the typed link, redacted. #94: before
            // one is typed, say so rather than showing the automatic label.
            value = if (state.hasTypedLink) state.effectiveXmlTvUrl else "No link typed yet",
            onValueChange = viewModel::setXmltvUrl,
            focusRequester = focusOf(FOCUS_KEY_XMLTV_FIELD),
            onFocused = { onRowFocused(FOCUS_KEY_XMLTV_FIELD) },
        )
    }
    SettingsSegmentedRow(
        label = "Update the guide every",
        caption = "How often HappyTV checks for new guide data.",
        options = GuideRefreshOption.entries.map { it.label },
        selectedIndex = GuideRefreshOption.fromHours(settings.epgRefreshHours).ordinal,
        onSelect = { index -> viewModel.setGuideRefreshHours(GuideRefreshOption.entries[index]) },
        contentDescription = "Update the guide every",
        focusRequesters = GuideRefreshOption.entries.map { focusOf(FOCUS_KEY_GUIDE_HOURS + it.hours) },
    )
}

/**
 * #123: Settings > Hidden items. Everything the user hid, whichever screen hid it, in two groups:
 * categories (from Live TV, Movies and Shows) and channels. Selecting a row unhides it at once.
 * One lazy list, so hundreds of hidden channels stay responsive.
 *
 * Focus after an unhide (#114): the next row in the group, else the previous one, else the
 * group's empty-state row.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun HiddenItemsPane(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    focusOf: (String) -> FocusRequester,
    onRowFocused: (String) -> Unit,
) {
    val d = browseDesign()
    val type = browseType()
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var focusTarget by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(focusTarget) {
        val target = focusTarget ?: return@LaunchedEffect
        // The empty-state row only exists once its group has emptied: keep asking until it is there.
        awaitFocus(landed = { focusedKey == target }, request = { focusOf(target).tryRequest() })
        focusTarget = null
    }
    val onFocused: (String) -> Unit = { key ->
        focusedKey = key
        onRowFocused(key)
    }
    val categoryKeys = state.hiddenCategories.map { hiddenCategoryFocusKey(it) }
    val channelKeys = state.hiddenChannels.map { hiddenChannelFocusKey(it) }

    // Rows use the lazy list's own vertical traversal, not the pane's fixed chain.
    CompositionLocalProvider(LocalSettingsFocusChain provides null) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .offset(x = -d.px(HIDDEN_LIST_BLEED_PX))
                .width(d.px(PANE_WIDTH_PX + 2 * HIDDEN_LIST_BLEED_PX))
                .height(d.px(HIDDEN_LIST_HEIGHT_PX))
                .focusGroup()
                // Up from the first row and Down from the last stay in the pane.
                .focusProperties {
                    exit = { direction ->
                        if (direction == FocusDirection.Up || direction == FocusDirection.Down) {
                            FocusRequester.Cancel
                        } else {
                            FocusRequester.Default
                        }
                    }
                },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = d.px(HIDDEN_LIST_BLEED_PX),
                vertical = d.px(HIDDEN_LIST_BLEED_PX),
            ),
            verticalArrangement = Arrangement.spacedBy(d.px(ROW_GAP_PX)),
        ) {
            item(key = "heading:categories") {
                Text("Categories", style = type.compactCaptionSemibold, color = HappyTvColors.TextSecondary)
            }
            if (state.hiddenCategories.isEmpty()) {
                item(key = FOCUS_KEY_HIDDEN_NO_CATEGORIES) {
                    SettingsInfoRow(
                        label = "No hidden categories",
                        value = "",
                        onFocused = { onFocused(FOCUS_KEY_HIDDEN_NO_CATEGORIES) },
                        focusRequester = focusOf(FOCUS_KEY_HIDDEN_NO_CATEGORIES),
                    )
                }
            } else {
                itemsIndexed(state.hiddenCategories, key = { _, row -> hiddenCategoryFocusKey(row) }) { index, hidden ->
                    val key = categoryKeys[index]
                    SettingsActionRow(
                        label = "Show ${hidden.name}",
                        caption = "Hidden from ${hidden.catalogLabel}",
                        onClick = {
                            focusTarget = categoryKeys.getOrNull(indexAfterRemoval(index, categoryKeys.size) ?: -1)
                                ?: FOCUS_KEY_HIDDEN_NO_CATEGORIES
                            viewModel.unhideCategory(hidden.id, hidden.type)
                        },
                        onFocused = { onFocused(key) },
                        focusRequester = focusOf(key),
                    )
                }
            }
            item(key = "heading:channels") {
                Text(
                    "Channels",
                    style = type.compactCaptionSemibold,
                    color = HappyTvColors.TextSecondary,
                    modifier = Modifier.padding(top = d.px(HIDDEN_GROUP_GAP_PX)),
                )
            }
            if (state.hiddenChannels.isEmpty()) {
                item(key = FOCUS_KEY_HIDDEN_NO_CHANNELS) {
                    SettingsInfoRow(
                        label = "No hidden channels",
                        value = "",
                        onFocused = { onFocused(FOCUS_KEY_HIDDEN_NO_CHANNELS) },
                        focusRequester = focusOf(FOCUS_KEY_HIDDEN_NO_CHANNELS),
                    )
                }
            } else {
                itemsIndexed(state.hiddenChannels, key = { _, row -> hiddenChannelFocusKey(row) }) { index, channel ->
                    val key = channelKeys[index]
                    SettingsActionRow(
                        label = channel.label,
                        caption = channel.caption,
                        onClick = {
                            focusTarget = channelKeys.getOrNull(indexAfterRemoval(index, channelKeys.size) ?: -1)
                                ?: FOCUS_KEY_HIDDEN_NO_CHANNELS
                            viewModel.unhideChannel(channel.id)
                        },
                        onFocused = { onFocused(key) },
                        focusRequester = focusOf(key),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerPane(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    focusOf: (String) -> FocusRequester,
    onRowFocused: (String) -> Unit,
) {
    val settings = state.settings
    SettingsSwitchRow(
        label = "Start replays from the beginning",
        caption = "On channels with replay, open what's on from its start instead of live.",
        on = settings.replayFromStart,
        onToggle = { viewModel.setReplayFromStart(!settings.replayFromStart) },
        onFocused = { onRowFocused(FOCUS_KEY_REPLAY_FROM_START) },
        focusRequester = focusOf(FOCUS_KEY_REPLAY_FROM_START),
    )
    SettingsSegmentedRow(
        label = "Back while watching",
        caption = "Show channels keeps the picture playing in a small window.",
        options = BackWhileWatching.entries.map { it.label },
        selectedIndex = settings.backWhileWatching.ordinal,
        onSelect = { index -> viewModel.setBackWhileWatching(BackWhileWatching.entries[index]) },
        contentDescription = "Back while watching",
        focusRequesters = BackWhileWatching.entries.map { focusOf(FOCUS_KEY_BACK_WHILE_WATCHING + it.name) },
    )
    SettingsSegmentedRow(
        label = "Aspect ratio",
        caption = "Auto fits the picture without cropping.",
        options = AspectRatioOption.entries.map { it.label },
        selectedIndex = AspectRatioOption.fromStored(settings.aspectRatio).ordinal,
        onSelect = { index -> viewModel.setAspectRatio(AspectRatioOption.entries[index]) },
        contentDescription = "Aspect ratio",
        focusRequesters = AspectRatioOption.entries.map { focusOf(FOCUS_KEY_ASPECT + it.name) },
    )
    SettingsSwitchRow(
        label = UnencryptedSignInCopy.LABEL,
        caption = if (settings.allowSignInOverUnencrypted) UnencryptedSignInCopy.CAPTION_ON else UnencryptedSignInCopy.CAPTION_OFF,
        on = settings.allowSignInOverUnencrypted,
        onToggle = { viewModel.setAllowSignInOverUnencrypted(!settings.allowSignInOverUnencrypted) },
        onFocused = { onRowFocused(FOCUS_KEY_UNENCRYPTED_SIGN_IN) },
        focusRequester = focusOf(FOCUS_KEY_UNENCRYPTED_SIGN_IN),
    )
}

@Composable
private fun AboutPane(
    focusOf: (String) -> FocusRequester,
    onRowFocused: (String) -> Unit,
    onReset: () -> Unit,
    resetDone: Boolean,
) {
    SettingsInfoRow(
        label = "Version",
        value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        onFocused = { onRowFocused(FOCUS_KEY_VERSION) },
        focusRequester = focusOf(FOCUS_KEY_VERSION),
    )
    SettingsInfoRow(
        label = "Build",
        value = BuildConfig.BUILD_TYPE.replaceFirstChar { it.uppercase() },
        onFocused = { onRowFocused(FOCUS_KEY_BUILD) },
        focusRequester = focusOf(FOCUS_KEY_BUILD),
    )
    SettingsInfoRow(
        label = "Your data",
        value = "Favorites, history and settings stay on this TV.",
        onFocused = { onRowFocused(FOCUS_KEY_YOUR_DATA) },
        focusRequester = focusOf(FOCUS_KEY_YOUR_DATA),
    )
    SettingsActionRow(
        label = "Reset to defaults",
        caption = if (resetDone) ResetDefaultsCopy.DONE_CAPTION else ResetDefaultsCopy.ROW_CAPTION,
        onClick = onReset,
        onFocused = { onRowFocused(FOCUS_KEY_RESET) },
        focusRequester = focusOf(FOCUS_KEY_RESET),
    )
}

// ---------------------------------------------------------------- text field

/**
 * The XMLTV link field. Focus alone only draws the ring; **OK** starts typing
 * and opens the system keyboard (design spec), so D-pad Right from the rail can't
 * trap focus in an open editor (#18). While not editing it shows [value] —
 * a non-secret label or a redacted link, never a credential. Editing starts
 * from an empty buffer so the stored link is never revealed; each keystroke
 * saves (trimmed) and a cleared field falls back to auto. Back leaves editing.
 *
 * The editor-focus guard mirrors `TvTextField` (fa82b64): the editor lands in
 * the focus system a frame after the container's `focusable` is switched off,
 * so the keyboard is shown after a settle delay and an early "unfocused"
 * event cannot cancel editing before the IME binds.
 *
 * A leading `0` is just text; the buffer is a String and nothing indexes off it.
 */
@Composable
internal fun XmltvLinkRow(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
) {
    val type = browseType()
    val d = browseDesign()
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editorEverFocused by remember { mutableStateOf(false) }
    var buffer by remember { mutableStateOf("") }
    val editorFocus = remember { FocusRequester() }
    // The field's own requester, for taking focus back when Back leaves the editor.
    val fieldFocus = remember { FocusRequester() }
    var returnFocusTick by remember { mutableStateOf(0) }

    // Back in the editor removes the node that held focus; without this the next
    // D-pad key lands on the header (#53). Wait for the field to be focusable again.
    LaunchedEffect(returnFocusTick) {
        if (returnFocusTick == 0) return@LaunchedEffect
        fieldFocus.requestWhenAttached(landed = { focused })
    }

    LaunchedEffect(editing) {
        if (editing) {
            editorFocus.requestFocus()
            kotlinx.coroutines.delay(XMLTV_KEYBOARD_SETTLE_MS)
            if (editorEverFocused) keyboard?.show() else editing = false
        }
    }

    SettingsFieldRow(label = "XMLTV link", caption = "Press OK to type a different guide link") {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .happyTvFocusRing(focused = focused || editing, radius = d.px(16))
                .htvSettingsFieldShape()
                .htvFocusTarget(focusRequester)
                .settingsVerticalFocus(focusRequester)
                .focusRequester(fieldFocus)
                .onFocusChanged { change ->
                    focused = change.isFocused
                    if (change.isFocused) onFocused()
                }
                .focusable(enabled = !editing)
                .onPreviewKeyEvent { event ->
                    val isOk = event.key == Key.DirectionCenter ||
                        event.key == Key.Enter ||
                        event.key == Key.NumPadEnter
                    when {
                        !editing && event.type == KeyEventType.KeyUp && isOk -> {
                            buffer = ""
                            editorEverFocused = false
                            editing = true
                            true
                        }
                        editing && event.type == KeyEventType.KeyUp && event.key == Key.Back -> {
                            editing = false
                            keyboard?.hide()
                            returnFocusTick++
                            true
                        }
                        else -> false
                    }
                },
        ) {
            if (editing) {
                if (buffer.isEmpty()) {
                    Text(
                        text = "http://provider.example/xmltv.php",
                        style = type.compactCaption,
                        color = HappyTvColors.TextPlaceholder,
                    )
                }
                BasicTextField(
                    value = buffer,
                    onValueChange = {
                        buffer = it
                        onValueChange(it)
                    },
                    textStyle = type.compactCaption.copy(color = HappyTvColors.TextPrimary).tabular(),
                    cursorBrush = SolidColor(HappyTvColors.Accent),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(editorFocus)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                editorEverFocused = true
                            } else if (editorEverFocused) {
                                editing = false
                            }
                        },
                )
            } else {
                Text(
                    text = value,
                    style = type.compactCaption,
                    color = HappyTvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val XMLTV_KEYBOARD_SETTLE_MS = 60L

// -------------------------------------------------------------------- dialog

/**
 * The settings confirmation (design spec §"Settings > Confirmations"): 1040 wide
 * at top 300, `raised`, scrim `rgba(8, 6, 5, 0.60)`, padding 56 64 64.
 *
 * Every confirmation opens with Cancel focused (DESIGN.md): refreshing, signing out,
 * resetting and the unencrypted override all change something the household may
 * not want changed by a stray OK.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SettingsConfirmDialog(
    dialog: SettingsDialog,
    serverName: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val d = browseDesign()
    val type = browseType()
    val primary = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }

    val title: String
    val body: String
    val confirmLabel: String
    when (dialog) {
        SettingsDialog.REFRESH_EVERYTHING -> {
            title = RefreshEverythingCopy.TITLE
            body = RefreshEverythingCopy.BODY
            confirmLabel = RefreshEverythingCopy.CONFIRM
        }
        SettingsDialog.SIGN_OUT -> {
            title = SignOutCopy.title(serverName)
            body = SignOutCopy.BODY
            confirmLabel = SignOutCopy.CONFIRM
        }
        SettingsDialog.RESET_DEFAULTS -> {
            title = ResetDefaultsCopy.TITLE
            body = ResetDefaultsCopy.BODY
            confirmLabel = ResetDefaultsCopy.CONFIRM
        }
        SettingsDialog.ALLOW_UNENCRYPTED_SIGN_IN -> {
            title = UnencryptedSignInCopy.TITLE
            body = UnencryptedSignInCopy.BODY
            confirmLabel = UnencryptedSignInCopy.CONFIRM
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HappyTvColors.Scrim)
            // Trap the D-pad: while the dialog is open focus cannot leave it for
            // the rail or rows behind. (Not `canFocus = false`: that propagates
            // to the buttons, see F-66.)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .offset(y = d.px(DIALOG_TOP_PX))
                .width(d.px(DIALOG_WIDTH_PX))
                .shadow(
                    elevation = d.px(DIALOG_SHADOW_PX),
                    shape = RoundedCornerShape(d.px(DIALOG_RADIUS_PX)),
                    ambientColor = HappyTvColors.shadow(DIALOG_SHADOW_ALPHA),
                    spotColor = HappyTvColors.shadow(DIALOG_SHADOW_ALPHA),
                )
                .background(HappyTvColors.Raised, RoundedCornerShape(d.px(DIALOG_RADIUS_PX)))
                .padding(start = d.px(64), top = d.px(56), end = d.px(64), bottom = d.px(64)),
            verticalArrangement = Arrangement.spacedBy(d.px(40)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(d.px(16))) {
                Text(text = title, style = type.compactHeading, color = HappyTvColors.TextPrimary)
                Text(text = body, style = type.compactBody, color = HappyTvColors.TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(d.px(24))) {
                // Primary first, per the handoff's button row.
                HtPrimaryButton(label = confirmLabel, onClick = onConfirm, focusRequester = primary)
                HtSecondaryButton(label = "Cancel", onClick = onDismiss, focusRequester = cancel)
            }
        }
    }

    LaunchedEffect(dialog) {
        // DESIGN.md (#166): every confirmation opens with Cancel focused.
        cancel.requestFocus()
    }
}

/**
 * #160: shown from the moment Sign out is confirmed until the sign-in screen opens. It is a
 * dialog-sized card on the same scrim as the confirmations, with one focus stop that takes OK and
 * the arrows so nothing behind it can be reached while the saved lists are being cleared.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SigningOutCard() {
    val d = browseDesign()
    val type = browseType()
    val holder = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HappyTvColors.Scrim)
            .testTag("signing-out-card")
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .offset(y = d.px(DIALOG_TOP_PX))
                .width(d.px(DIALOG_WIDTH_PX))
                .shadow(
                    elevation = d.px(DIALOG_SHADOW_PX),
                    shape = RoundedCornerShape(d.px(DIALOG_RADIUS_PX)),
                    ambientColor = HappyTvColors.shadow(DIALOG_SHADOW_ALPHA),
                    spotColor = HappyTvColors.shadow(DIALOG_SHADOW_ALPHA),
                )
                .background(HappyTvColors.Raised, RoundedCornerShape(d.px(DIALOG_RADIUS_PX)))
                .focusRequester(holder)
                .focusable()
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                }
                .padding(start = d.px(64), top = d.px(56), end = d.px(64), bottom = d.px(64)),
            verticalArrangement = Arrangement.spacedBy(d.px(16)),
        ) {
            Text(text = SignOutCopy.PROGRESS_TITLE, style = type.compactHeading, color = HappyTvColors.TextPrimary)
            Text(text = SignOutCopy.PROGRESS_BODY, style = type.compactBody, color = HappyTvColors.TextSecondary)
        }
    }
    LaunchedEffect(Unit) { holder.requestFocus() }
}

// ------------------------------------------------------------------- geometry

private const val HEADER_X_PX = 96
private const val HEADER_Y_PX = HEADER_TOP_PX
private const val CONTENT_WIDTH_PX = 1728
private const val RAIL_X_PX = 96
private const val RAIL_WIDTH_PX = 520
private const val RAIL_ROW_H_PX = 64
private const val RAIL_GAP_PX = 8
private const val PANE_X_PX = 664
private const val PANE_WIDTH_PX = 1160
private const val PANE_TOP_PX = HEADER_BOTTOM_PX + 40
private const val PANE_TOP_GAP_PX = 24
private const val ROW_GAP_PX = 12
private const val DIALOG_TOP_PX = 300
private const val DIALOG_WIDTH_PX = 1040
private const val DIALOG_RADIUS_PX = 32
private const val DIALOG_SHADOW_PX = 40
private const val DIALOG_SHADOW_ALPHA = 0.55f

// --------------------------------------------------------------- focus keys

private const val RAIL_KEY_PREFIX = "section:"
private const val FOCUS_KEY_SIGN_IN = "account:sign-in"
private const val FOCUS_KEY_SERVER = "account:server"
private const val FOCUS_KEY_USERNAME = "account:username"
private const val FOCUS_KEY_SIGNED_IN = "account:signed-in"
private const val FOCUS_KEY_SWITCH_ACCOUNT = "account:switch"
private const val FOCUS_KEY_REFRESH_ON_START = "refresh:on-start"
private const val FOCUS_KEY_REFRESH_ALL = "refresh:all"
private const val FOCUS_KEY_STREAM_FORMAT = "refresh:stream-format:"
private const val FOCUS_KEY_LIVE_TV_OPEN_ON = "livetv:open-on:"
private const val FOCUS_KEY_KEEP_CATEGORIES_OPEN = "livetv:keep-open"
private const val FOCUS_KEY_GUIDE_SOURCE = "guide:source:"
private const val FOCUS_KEY_XMLTV_FIELD = "guide:xmltv"
private const val FOCUS_KEY_GUIDE_HOURS = "guide:hours:"
private const val FOCUS_KEY_HIDDEN_NO_CATEGORIES = "hidden:categories:none"
private const val FOCUS_KEY_HIDDEN_NO_CHANNELS = "hidden:channels:none"
private const val FOCUS_KEY_HIDDEN_CATEGORY_PREFIX = "hidden:category:"
private const val FOCUS_KEY_HIDDEN_CHANNEL_PREFIX = "hidden:channel:"

private fun hiddenCategoryFocusKey(row: HiddenCategoryRow): String =
    FOCUS_KEY_HIDDEN_CATEGORY_PREFIX + row.type.name + ":" + row.id

private fun hiddenChannelFocusKey(row: HiddenChannelRow): String = FOCUS_KEY_HIDDEN_CHANNEL_PREFIX + row.id

/** The Hidden items list is a window below the pane heading; room for the focus ring on every side. */
private const val HIDDEN_LIST_BLEED_PX = 8
private const val HIDDEN_LIST_HEIGHT_PX = 1080 - PANE_TOP_PX - 40 - PANE_TOP_GAP_PX - 40
private const val HIDDEN_GROUP_GAP_PX = 12
private const val FOCUS_KEY_REPLAY_FROM_START = "player:replay-from-start"
private const val FOCUS_KEY_UNENCRYPTED_SIGN_IN = "player:unencrypted-sign-in"
private const val FOCUS_KEY_BACKUP = "account:backup"
private const val FOCUS_KEY_ASPECT = "player:aspect:"
private const val FOCUS_KEY_BACK_WHILE_WATCHING = "player:back-while-watching:"
private const val FOCUS_KEY_VERSION = "about:version"
private const val FOCUS_KEY_BUILD = "about:build"
private const val FOCUS_KEY_YOUR_DATA = "about:your-data"
private const val FOCUS_KEY_RESET = "about:reset"
