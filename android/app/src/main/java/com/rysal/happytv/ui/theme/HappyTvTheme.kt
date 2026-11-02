package com.rysal.happytv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Constraints
import com.rysal.happytv.R
import kotlin.math.min
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.lightColorScheme

/**
 * HappyTV's design system in Compose — design spec "Color", "Type, grid,
 * spacing, corners, shadows, focus and motion" and "Components".
 *
 * Two things live here, because every screen needs both:
 *
 *  1. [HappyTvColors] — the token set, values copied verbatim from
 *     `res/values/colors.xml` (which mirrors the handoff).
 *  2. [HappyTvDesign] — the design canvas. Artboards are 1920 x 1080 "design
 *     px" at 1 dp = 2 px, but a TV's density is not always 2.0 (the 1080p
 *     emulator is tvdpi, 1.33), so a fixed dp layout would render at two
 *     thirds of the screen. [HappyTvCanvas] instead scales the whole 1920 x
 *     1080 artboard to the window, uniformly, and hands every screen a
 *     converter: `design.px(96)` is "96 px in the handoff" wherever the app
 *     runs. Text uses [HappyTvDesign.sp] for the same reason.
 *
 * Screens compose inside [HappyTvTheme], which supplies both through
 * [LocalHappyTvDesign] / [LocalHappyTvTypography].
 */

// --------------------------------------------------------------------- color

/**
 * One complete set of color tokens. [DarkPalette] is the original scheme and
 * the default; [LightPalette] is the warm-paper variant (#126). Contrast of
 * the light set is pinned by `LightPaletteContrastTest`.
 */
@Immutable
class HappyTvPalette(
    val isLight: Boolean,
    val Ground: Color,
    val Surface: Color,
    val Raised: Color,
    val Overlay: Color,
    val AccentTint: Color,
    val Line: Color,
    val Loading: Color,
    val SigninWatermark: Color,
    val GuideGridline: Color,
    val GuideDetailsTrack: Color,
    val ScrollFadeStart: Color,
    val ScrollFadeEnd: Color,
    val ScrimSearch: Color,
    val SearchFadeStart: Color,
    val SearchFadeEnd: Color,
    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextTertiary: Color,
    val TextPlaceholder: Color,
    val DisabledIcon: Color,
    val OnAccent: Color,
    val Accent: Color,
    val AccentGlow: Color,
    val StateSuccess: Color,
    val StateError: Color,
    val StateStale: Color,
    val StateErrorTint: Color,
    val StateStaleTint: Color,
    val ProgressTrack: Color,
)

/** The original scheme: warm near-black, cream text. Values mirror `res/values/colors.xml`. */
val DarkPalette = HappyTvPalette(
    isLight = false,
    Ground = Color(0xFF13100E),
    Surface = Color(0xFF1C1815),
    Raised = Color(0xFF26211D),
    Overlay = Color(0xFF302A25),
    AccentTint = Color(0xFF341F16),
    Line = Color(0xFF453D36),
    Loading = Color(0xFF2B2521),
    SigninWatermark = Color(0xFF191512),
    GuideGridline = Color(0xFF1E1A17),
    /** rgba(255, 255, 255, 0.12) — the guide details progress track. */
    GuideDetailsTrack = Color(0x1FFFFFFF),
    /** Fade under scrolling screens: Ground transparent → Ground at 85%. */
    ScrollFadeStart = Color(0x0013100E),
    ScrollFadeEnd = Color(0xD913100E),
    /** The scrim behind Search: fully opaque Ground (#67). */
    ScrimSearch = Color(0xFF13100E),
    /** Fade at the bottom of search: (10, 8, 7, 0) → (10, 8, 7, 0.95). */
    SearchFadeStart = Color(0x000A0807),
    SearchFadeEnd = Color(0xF20A0807),
    TextPrimary = Color(0xFFFFF1E2),
    TextSecondary = Color(0xFFCFC6BD),
    TextTertiary = Color(0xFF93877C),
    /** Tertiary on Overlay fails AA at 4.0 — handoff open question 2 settled on this. */
    TextPlaceholder = Color(0xFFA0948A),
    DisabledIcon = Color(0xFF5E554D),
    OnAccent = Color(0xFF1A0F0A),
    Accent = Color(0xFFFF7A45),
    /** Accent at 38% — the focus glow (design spec "Focus ring"). */
    AccentGlow = Color(0x61FF7A45),
    StateSuccess = Color(0xFF5CC98F),
    StateError = Color(0xFFF2666B),
    StateStale = Color(0xFFF2B544),
    /** Error tint — the connection banner's pill fill. */
    StateErrorTint = Color(0xFF321C1B),
    /** Stale tint — the Stale badge fill. */
    StateStaleTint = Color(0xFF3E311D),
    /** rgba(255, 255, 255, 0.08) — progress tracks. */
    ProgressTrack = Color(0x14FFFFFF),
)

/**
 * Light mode (#126): warm cream/paper surfaces, espresso text, the same
 * sunset-orange accent deepened until the focus ring reads 3:1 on paper
 * (`#FF7A45` is about 2.6:1 on near-white) with white on-accent text.
 */
val LightPalette = HappyTvPalette(
    isLight = true,
    Ground = Color(0xFFF6EFE7),
    Surface = Color(0xFFFFFAF4),
    Raised = Color(0xFFFFFFFF),
    Overlay = Color(0xFFECE2D7),
    AccentTint = Color(0xFFFBE3D3),
    Line = Color(0xFFD6C8BA),
    Loading = Color(0xFFE3D8CC),
    SigninWatermark = Color(0xFFEFE6DC),
    GuideGridline = Color(0xFFE9DFD4),
    GuideDetailsTrack = Color(0x1F2A1810),
    ScrollFadeStart = Color(0x00F6EFE7),
    ScrollFadeEnd = Color(0xD9F6EFE7),
    ScrimSearch = Color(0xFFF6EFE7),
    SearchFadeStart = Color(0x00F6EFE7),
    SearchFadeEnd = Color(0xF2F6EFE7),
    TextPrimary = Color(0xFF2A1810),
    TextSecondary = Color(0xFF5A4A3F),
    TextTertiary = Color(0xFF706055),
    TextPlaceholder = Color(0xFF6C5C51),
    DisabledIcon = Color(0xFFA89A8D),
    OnAccent = Color(0xFFFFFFFF),
    Accent = Color(0xFFC2410C),
    AccentGlow = Color(0x61C2410C),
    StateSuccess = Color(0xFF1E7A4A),
    StateError = Color(0xFFB5323A),
    StateStale = Color(0xFF8A5A00),
    StateErrorTint = Color(0xFFFBE4E2),
    StateStaleTint = Color(0xFFF7EAC8),
    ProgressTrack = Color(0x142A1810),
)

/**
 * The app-wide light/dark switch (#126). Snapshot state, so every composition
 * and draw lambda that reads a [HappyTvColors] token re-runs when it flips,
 * with no CompositionLocal (several activities never wrap in [HappyTvTheme]).
 * `HappyTvApp` sets it from the saved setting before any activity starts.
 */
object HappyTvThemeState {
    var light: Boolean by mutableStateOf(false)
}

/**
 * Design tokens, resolved against a palette. Mirrors `res/values/colors.xml`
 * and `Tokens.kt` (the dark values); keep the three in step.
 *
 * [HappyTvColors] follows the user's light/dark choice. [HappyTvDarkColors] is
 * the dark set for anything drawn over video or artwork.
 */
abstract class HappyTvColorSet {
    protected abstract val palette: HappyTvPalette

    // Surfaces, back to front
    val Ground: Color get() = palette.Ground
    val Surface: Color get() = palette.Surface
    val Raised: Color get() = palette.Raised
    val Overlay: Color get() = palette.Overlay
    val AccentTint: Color get() = palette.AccentTint
    val Line: Color get() = palette.Line
    val Loading: Color get() = palette.Loading
    val SigninWatermark: Color get() = palette.SigninWatermark
    /** Guide grid line (design spec "Color > Surfaces"). */
    val GuideGridline: Color get() = palette.GuideGridline

    // ── Translucent layers (design spec "Translucent layers") ──────────────────

    val GuideDetailsTrack: Color get() = palette.GuideDetailsTrack
    val ScrollFadeStart: Color get() = palette.ScrollFadeStart
    val ScrollFadeEnd: Color get() = palette.ScrollFadeEnd
    val ScrimSearch: Color get() = palette.ScrimSearch
    val SearchFadeStart: Color get() = palette.SearchFadeStart
    val SearchFadeEnd: Color get() = palette.SearchFadeEnd
    val ProgressTrack: Color get() = palette.ProgressTrack

    // Text and icons
    val TextPrimary: Color get() = palette.TextPrimary
    val TextSecondary: Color get() = palette.TextSecondary
    val TextTertiary: Color get() = palette.TextTertiary
    val TextPlaceholder: Color get() = palette.TextPlaceholder
    val DisabledIcon: Color get() = palette.DisabledIcon
    val OnAccent: Color get() = palette.OnAccent

    // Accent + focus
    val Accent: Color get() = palette.Accent
    val AccentGlow: Color get() = palette.AccentGlow

    val isLight: Boolean get() = palette.isLight

    // State colors
    val StateSuccess: Color get() = palette.StateSuccess
    val StateError: Color get() = palette.StateError
    val StateStale: Color get() = palette.StateStale
    val StateErrorTint: Color get() = palette.StateErrorTint
    val StateStaleTint: Color get() = palette.StateStaleTint

    // ── Fixed in both modes (drawn over artwork, or a dark scrim over a screen) ──

    /** Poster title strip: (10, 8, 7, 0) → (10, 8, 7, 0.92) at the 50% stop. */
    val PosterStripStart = Color(0x000A0807)
    val PosterStripEnd = Color(0xEB0A0807)
    /** Poster badges: rgba(19, 16, 14, 0.88). */
    val PosterBadge = Color(0xE113100E)
    /**
     * Behind dialogs and sheets. Dark: rgba(8, 6, 5, 0.60). Light: Ground at 64%,
     * a soft warm cream wash (a dark scrim over a cream screen reads grey-brown).
     * [HappyTvDarkColors] always gives the dark one, for anything over video.
     */
    val Scrim: Color get() = if (palette.isLight) Color(0xA3F6EFE7) else Color(0x99080605)
    /** Behind popup menus. Dark: rgba(8, 6, 5, 0.55). Light: Ground at 55%. */
    val ScrimMenu: Color get() = if (palette.isLight) Color(0x8CF6EFE7) else Color(0x8C080605)

    /** Shadows are always black at the alpha the handoff names. */
    fun shadow(alpha: Float): Color = Color(0xFF000000).copy(alpha = alpha)

    /**
     * The focus-ring stroke to draw against a [fill] of this color (F-33).
     *
     * Accent-on-Accent has no contrast: the Sign in button read as unfocused
     * on the Chromecast HD because both the ring and the glow are Accent, and
     * so is the fill. A filled Accent surface therefore gets the [TextPrimary]
     * ring (near-white in dark, espresso in light), which reads on the accent
     * fill *and* on the surfaces around it.
     *
     * NB the F-33 note asked for `OnAccent` "(near-white)" — `OnAccent` is
     * the label color *on* an accent fill, not the ring; [TextPrimary] is the
     * color that note meant.
     *
     * Every other fill keeps the Accent ring.
     */
    fun focusRingOn(fill: Color): Color = if (fill == Accent) TextPrimary else Accent
}

/** Tokens for the user's current mode (light or dark). */
object HappyTvColors : HappyTvColorSet() {
    override val palette: HappyTvPalette get() = if (HappyTvThemeState.light) LightPalette else DarkPalette
}

/** Tokens that stay dark in light mode: the player and anything over video. */
object HappyTvDarkColors : HappyTvColorSet() {
    override val palette: HappyTvPalette get() = DarkPalette
}

// -------------------------------------------------------------------- design

/**
 * Converts a handoff measurement (design px at 1920 x 1080, 1 dp = 2 px) into
 * the value this window actually needs. Uniform: the artboard keeps its
 * proportions and letterboxes on anything that is not 16:9.
 */
@Immutable
class HappyTvDesign internal constructor(
    private val density: Float,
    private val scale: Float,
) {
    /** Handoff px -> Dp on this window (e.g. `px(96)` == 48 dp at density 2). */
    fun px(value: Float): Dp = (value * scale / density).dp
    fun px(value: Int): Dp = px(value.toFloat())

    /** Handoff px -> sp (handoff rule: text px / 2 == sp at density 2). */
    fun sp(value: Float): TextUnit = (value * scale / density).sp
    fun sp(value: Int): TextUnit = sp(value.toFloat())
}

/** Fallback for previews/tests outside [HappyTvTheme]: a 1080p, density-2 TV. */
private val DefaultDesign = HappyTvDesign(density = 2f, scale = 1f)

val LocalHappyTvDesign = staticCompositionLocalOf { DefaultDesign }

// ------------------------------------------------------------------ typography

/**
 * Manrope, weights 500-800, bundled as a variable font in `res/font`. Styles
 * are named after the handoff's type table ("36/44 600" = size/line-height).
 *
 * Numbers use tabular figures everywhere ([tabular]); clocks, counts and times
 * must not shift as digits change.
 */
@OptIn(ExperimentalTextApi::class)
val Manrope: FontFamily = FontFamily(
    Font(R.font.manrope, FontWeight.W500, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope, FontWeight.W600, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope, FontWeight.W700, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.manrope, FontWeight.W800, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

/** Smallest text size on TV, artboard px (12 sp) — spec #7 §1. */
const val COMPACT_MIN_TEXT_PX = 24

/** The handoff's type table, sized for this window. */
@Immutable
class HappyTvTypography(private val d: HappyTvDesign) {
    /** Splash wordmark, 120/128 800 -3%. */
    val splashWordmark: TextStyle = style(120, 128, FontWeight.W800, letterSpacingPercent = -3f)

    /** Display, 72/80 800 -2% — dashboard tile titles, movie/show titles. */
    val display: TextStyle = style(72, 80, FontWeight.W800, letterSpacingPercent = -2f)

    /** Title, 64/72 700 -1% — screen titles. */
    val title: TextStyle = style(64, 72, FontWeight.W700, letterSpacingPercent = -1f)

    /** Dashboard clock, 64/72 600, no tracking. */
    val clock: TextStyle = style(64, 72, FontWeight.W600)

    /** Wordmark in headers, 44/52 800 -2%. */
    val wordmark: TextStyle = style(44, 52, FontWeight.W800, letterSpacingPercent = -2f)

    /** Heading, 44/52 700 — dialog titles, category names. */
    val heading: TextStyle = style(44, 52, FontWeight.W700)

    /** Body, 36/44 500 — counts, field text, descriptions. */
    val body: TextStyle = style(36, 44, FontWeight.W500)

    /** Body 600 — field labels on sign in, checkbox labels. */
    val bodySemibold: TextStyle = style(36, 44, FontWeight.W600)

    /** Body 700 — row names, secondary button labels. */
    val bodyBold: TextStyle = style(36, 44, FontWeight.W700)

    /** Body 800 — primary button labels. */
    val bodyExtraBold: TextStyle = style(36, 44, FontWeight.W800)

    /** Caption, 28/36 500 — the smallest text on TV. */
    val caption: TextStyle = style(28, 36, FontWeight.W500)

    /** Caption 600 — status lines, chips, times, field errors. */
    val captionSemibold: TextStyle = style(28, 36, FontWeight.W600)

    /** Caption 700 — section labels, text links, chip text. */
    val captionBold: TextStyle = style(28, 36, FontWeight.W700)

    // ---- Compact density (spec #7 §1) -------------------------------------
    // Used by Live TV, the guide, Settings and the inner-screen header. The
    // dashboard, sign-in and player keep the design spec sizes above. Floor: no
    // text under 24 px artboard (12 sp) — see [COMPACT_MIN_TEXT_PX].

    /** Compact screen title, 36/44 700 — the inner-screen header title. */
    val compactTitle: TextStyle = style(36, 44, FontWeight.W700, letterSpacingPercent = -1f)

    /** Compact heading, 32/40 700 — pane headings, dialog titles. */
    val compactHeading: TextStyle = style(32, 40, FontWeight.W700)

    /** Compact row name, 28/36 700 — channel names, settings labels, menu items. */
    val compactRowName: TextStyle = style(28, 36, FontWeight.W700)

    /** Compact body, 28/36 500 — now lines, values, descriptions. */
    val compactBody: TextStyle = style(28, 36, FontWeight.W500)

    /** Compact caption, 24/32 500 — counts, times, chips, labels. */
    val compactCaption: TextStyle = style(24, 32, FontWeight.W500)

    /** Compact caption 600. */
    val compactCaptionSemibold: TextStyle = style(24, 32, FontWeight.W600)

    /** Compact clock, 32/40 600 — the inner-screen header clock (tabular). */
    val compactClock: TextStyle = style(32, 40, FontWeight.W600).tabular()

    /** The giant dim wordmark on sign in, 440/440 800 -5%. */
    val watermark: TextStyle = style(440, 440, FontWeight.W800, letterSpacingPercent = -5f)

    /** Centered copy inside progress-less panels (unused so far). */
    val centered: TextStyle get() = body.copy(textAlign = TextAlign.Center)

    private fun style(
        sizePx: Int,
        lineHeightPx: Int,
        weight: FontWeight,
        letterSpacingPercent: Float = 0f,
    ): TextStyle = TextStyle(
        fontFamily = Manrope,
        fontWeight = weight,
        fontSize = d.sp(sizePx),
        lineHeight = d.sp(lineHeightPx),
        letterSpacing = d.sp(sizePx * letterSpacingPercent / 100f),
    )
}

/** `font-variant-numeric: tabular-nums` — handoff "Typeface". */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

val LocalHappyTvTypography = staticCompositionLocalOf { HappyTvTypography(DefaultDesign) }

// --------------------------------------------------------------------- theme

/**
 * The window shell: Material3 following the app's light/dark choice
 * ([HappyTvThemeState], #126; dark is the default) with HappyTV's tokens as the
 * Material defaults, so any third-party composable that reads `MaterialTheme`
 * still lands on brand colors. Screens that need exact handoff values read the
 * locals instead. [alwaysDark] is for the full-screen player, which sits on video.
 */
@Composable
fun HappyTvTheme(alwaysDark: Boolean = false, content: @Composable () -> Unit) {
    val colors: HappyTvColorSet = if (alwaysDark) HappyTvDarkColors else HappyTvColors
    val light = !alwaysDark && HappyTvThemeState.light
    val scheme = if (light) {
        lightColorScheme(
            primary = colors.Accent,
            onPrimary = colors.OnAccent,
            background = colors.Ground,
            onBackground = colors.TextPrimary,
            surface = colors.Surface,
            onSurface = colors.TextPrimary,
            surfaceVariant = colors.Overlay,
            onSurfaceVariant = colors.TextSecondary,
            error = colors.StateError,
            outline = colors.Line,
        )
    } else {
        darkColorScheme(
            primary = colors.Accent,
            onPrimary = colors.OnAccent,
            background = colors.Ground,
            onBackground = colors.TextPrimary,
            surface = colors.Surface,
            onSurface = colors.TextPrimary,
            surfaceVariant = colors.Overlay,
            onSurfaceVariant = colors.TextSecondary,
            error = colors.StateError,
            outline = colors.Line,
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = happyTvMaterialTypography(),
        // NB: F-01 ("CompositionLocal LocalLifecycleOwner not present") is fixed
        // by pinning lifecycle-runtime-compose to 2.7.0 in
        // gradle/libs.versions.toml, not by a shim here: 2.8's
        // collectAsStateWithLifecycle reads a lifecycle-owned LocalLifecycleOwner
        // that only compose-ui 1.7+ provides, and a CompositionLocalProvider
        // workaround in this theme would miss every activity that doesn't wrap
        // in HappyTvTheme (all of ui/browse).
        content = content,
    )
}

/**
 * Material3's named styles, so any third-party composable reading
 * `MaterialTheme.typography` gets TV sizes instead of the phone defaults.
 *
 * Only the two slots the app-wide M3 components actually reach are
 * overridden — `titleLarge` (44/52 700 −1%) and `bodyMedium` (36/44 500) —
 * and both in handoff px like everything else. Screens draw with
 * [LocalHappyTvTypography], which is the same table one step richer.
 *
 * Built from the fallback design (1080p, density 2): this composable runs
 * inside [HappyTvTheme], i.e. *before* a window's real scale exists in
 * [HappyTvCanvas]. The values are correct at the 1920×1080 master, which is
 * the only size the M3 defaults are ever consulted at.
 */
@Composable
private fun happyTvMaterialTypography(): Typography {
    val d = LocalHappyTvDesign.current
    return Typography(
        titleLarge = TextStyle(
            fontFamily = Manrope,
            fontWeight = FontWeight.W700,
            fontSize = d.sp(M3_TITLE_LARGE_PX),
            lineHeight = d.sp(M3_TITLE_LARGE_LINE_PX),
            // −1% tracking, per the handoff's Heading style.
            letterSpacing = d.sp(M3_TITLE_LARGE_PX * -0.01f),
        ),
        bodyMedium = TextStyle(
            fontFamily = Manrope,
            fontWeight = FontWeight.W500,
            fontSize = d.sp(M3_BODY_MEDIUM_PX),
            lineHeight = d.sp(M3_BODY_MEDIUM_LINE_PX),
        ),
    )
}

private const val M3_TITLE_LARGE_PX = 44
private const val M3_TITLE_LARGE_LINE_PX = 52
private const val M3_BODY_MEDIUM_PX = 36
private const val M3_BODY_MEDIUM_LINE_PX = 44

/**
 * Lays out one 1920 x 1080 artboard, scaled to the window, on a [Ground]
 * field. Everything inside works in handoff px through [LocalHappyTvDesign].
 */
@Composable
fun HappyTvCanvas(content: @Composable () -> Unit) {
    HappyTvArtboard(background = HappyTvColors.Ground, content = content)
}

/**
 * The same artboard as [HappyTvCanvas] with **no field behind it** — for the
 * modal overlays (Search) whose window is translucent, so whatever screen
 * opened them stays visible underneath the overlay's own scrim.
 */
@Composable
fun HappyTvOverlayCanvas(content: @Composable () -> Unit) {
    HappyTvArtboard(background = null, content = content)
}

@Composable
private fun HappyTvArtboard(background: Color?, content: @Composable () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .let { if (background != null) it.background(background) else it },
        contentAlignment = Alignment.Center,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val scale = min(
            constraints.maxWidth.toFloat() / ARTBOARD_WIDTH_PX,
            constraints.maxHeight.toFloat() / ARTBOARD_HEIGHT_PX,
        )
        val design = remember(density, scale) { HappyTvDesign(density, scale) }
        CompositionLocalProvider(LocalHappyTvDesign provides design) {
            Box(
                modifier = Modifier.size(design.px(ARTBOARD_WIDTH_PX), design.px(ARTBOARD_HEIGHT_PX)),
                contentAlignment = Alignment.TopStart,
            ) {
                content()
            }
        }
    }
}

private const val ARTBOARD_WIDTH_PX = 1920f
private const val ARTBOARD_HEIGHT_PX = 1080f

/**
 * The handoff's one focus treatment: a 4 px accent ring that follows the
 * element's own corner radius, plus the accent glow
 * (`0 0 28px 8px rgba(255, 122, 69, 0.38)`).
 *
 * It never changes the element's size, background or position, and it snaps
 * in and out instantly (no animation, per "Motion"). The ring is drawn just
 * outside the element's bounds, so the glow needs room around the element —
 * the handoff asks containers for 40 px, which every layout here keeps.
 *
 * **Chain order is part of the contract:** because the ring (and the glow)
 * live *outside* the bounds, this modifier has to be applied *before* the
 * caller's `.clip(...)`/`.background(...)` — a clip above it erases both, and
 * the element ends up with no focus treatment at all. That is F-33's root
 * cause on sign-in: every widget there clipped first, so only the elevation
 * glow survived, tinted *inside* the fill — invisible where the fill is
 * already Accent.
 *
 * @param radius the element's own corner radius, so the ring follows it.
 * @param focusRingColor the ring stroke. Defaults to [HappyTvColors.Accent];
 * a surface filled with Accent passes [HappyTvColors.focusRingOn] (near-white)
 * so the ring never matches its own fill (F-33).
 */
@Composable
fun Modifier.happyTvFocusRing(
    focused: Boolean,
    radius: Dp,
    focusRingColor: Color = HappyTvColors.Accent,
    glowColor: Color = HappyTvColors.AccentGlow,
): Modifier {
    val d = LocalHappyTvDesign.current
    if (!focused) return this
    val ringWidth = d.px(FOCUS_RING_PX)
    val shape = RoundedCornerShape(radius)
    val glow = glowColor
    val ring = focusRingColor
    val bands = FOCUS_GLOW_BANDS
    return this.drawWithContent {
        // The glow lives only outside the element's bounds. It used to be an elevation
        // shadow, which Android also paints inside the outline whenever the background sits
        // outside the shadow's layer (Settings rows, guide blocks): a darker rectangle
        // inside the ring (#26). Clipping the inside out makes the result independent of
        // where the caller puts `background`/`clip` in the chain.
        val outline = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius.toPx())))
        }
        val ringPx = ringWidth.toPx()
        val bandPx = d.px(FOCUS_GLOW_PX).toPx() / bands
        clipPath(outline, ClipOp.Difference) {
            for (i in 0 until bands) {
                val inset = i * bandPx + bandPx / 2f
                drawRoundRect(
                    color = glow.copy(alpha = glow.alpha * focusGlowFalloff(i, bands)),
                    topLeft = Offset(-inset, -inset),
                    size = Size(size.width + 2 * inset, size.height + 2 * inset),
                    cornerRadius = CornerRadius(radius.toPx() + inset),
                    style = Stroke(width = bandPx),
                )
            }
        }
        drawContent()
        // The ring sits outside the bounds: offset outward by a full stroke
        // so a 4 px ring never eats into the element's own fill.
        drawRoundRect(
            color = ring,
            topLeft = Offset(-ringPx, -ringPx),
            size = Size(size.width + 2 * ringPx, size.height + 2 * ringPx),
            cornerRadius = CornerRadius(radius.toPx() + ringPx),
            style = Stroke(width = ringPx),
        )
    }
}

/** Alpha multiplier for glow band [index] of [bands] (0 = touching the element): quadratic fade to nothing. */
internal fun focusGlowFalloff(index: Int, bands: Int): Float {
    val t = (index + 0.5f) / bands
    return (1f - t) * (1f - t)
}

private const val FOCUS_GLOW_BANDS = 8
private const val FOCUS_RING_PX = 4f
private const val FOCUS_GLOW_PX = 22f
