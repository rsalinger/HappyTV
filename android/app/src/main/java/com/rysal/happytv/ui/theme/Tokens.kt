package com.rysal.happytv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * HappyTV design tokens — single source of truth for Compose.
 *
 * Described in `docs/DESIGN.md`. The original design spec writes
 * measurements at 1920×1080 px (1 dp = 2 px); every value here is already
 * halved so Compose dp/sp units land correctly on the 960×540 dp TV master.
 *
 * Token names match the design spec verbatim so a designer reading either side
 * sees the same words.
 */
object Tokens {

    // ── Brand & accent ────────────────────────────────────────────────────

    /** Sunset orange #FF7A45 — the one accent. Focus ring, primary, progress. */
    val BrandSun: Color = Color(0xFFFF7A45)

    val BrandCream: Color = Color(0xFFFFF1E2)
    val BrandEspresso: Color = Color(0xFF2A1810)
    val BrandDusk: Color = Color(0xFF3A2230)
    val BrandBannerTv: Color = Color(0xFFB35530)

    // ── Surfaces (warm-dark, back to front) ───────────────────────────────

    /** Ground #13100E — background of every screen and the splash. */
    val SurfaceGround: Color = Color(0xFF13100E)

    /** Surface #1C1815 — dashboard tiles, status pills, sign-in card, channel rows. */
    val SurfaceSurface: Color = Color(0xFF1C1815)

    /** Raised #26211D — popup menus, dialogs, the bottom sheet. */
    val SurfaceRaised: Color = Color(0xFF26211D)

    /** Overlay #302A25 — text fields, secondary buttons, chips. */
    val SurfaceOverlay: Color = Color(0xFF302A25)

    /** Accent tint #341F16 — accent at 14% over Ground. */
    val SurfaceAccentTint: Color = Color(0xFF341F16)

    val SurfaceLine: Color = Color(0xFF453D36)
    val SurfaceLoading: Color = Color(0xFF2B2521)
    val StateErrorTint: Color = Color(0xFF321C1B)
    val StateStaleTint: Color = Color(0xFF3E311D)
    val StateSuccessTint: Color = Color(0xFF1E3A2C)
    val SurfaceSignInWatermark: Color = Color(0xFF191512)

    // ── Text and icons ────────────────────────────────────────────────────

    val TextPrimary: Color = Color(0xFFFFF1E2)
    val TextSecondary: Color = Color(0xFFCFC6BD)
    val TextTertiary: Color = Color(0xFF93877C)
    val TextDisabledIcon: Color = Color(0xFF5E554D)
    val TextOnAccent: Color = Color(0xFF1A0F0A)

    // ── State colors ──────────────────────────────────────────────────────

    val StateSuccess: Color = Color(0xFF5CC98F)
    val StateError: Color = Color(0xFFF2666B)
    val StateStale: Color = Color(0xFFF2B544)

    // ── Light mode (#126) ─────────────────────────────────────────────────
    // Mirrors `LightPalette` in HappyTvTheme.kt and the `light_*` entries in
    // res/values/colors.xml. Dark stays the default; the values above are dark.

    val LightGround: Color = Color(0xFFF6EFE7)
    val LightSurface: Color = Color(0xFFFFFAF4)
    val LightRaised: Color = Color(0xFFFFFFFF)
    val LightOverlay: Color = Color(0xFFECE2D7)
    val LightLine: Color = Color(0xFFD6C8BA)
    val LightTextPrimary: Color = Color(0xFF2A1810)
    val LightTextSecondary: Color = Color(0xFF5A4A3F)
    val LightTextTertiary: Color = Color(0xFF706055)
    val LightTextOnAccent: Color = Color(0xFFFFFFFF)
    /** Sunset orange deepened so the focus ring reads 3:1 on paper. */
    val LightAccent: Color = Color(0xFFC2410C)
}
