package com.netscanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Glass design tokens (v5.1.0). The glass system is anchored on a deep navy
 * canvas in dark mode and a pale ice-blue canvas in light mode; every text
 * color is derived from the mode so contrast stays safe over both the raw
 * aurora backdrop and the frosted chrome surfaces.
 *
 * v5.1.0: explicit Light/Dark/System mode (persisted in Settings), per-mode
 * token tuning (dark = HIGHER fill alpha, LOWER border alpha), WCAG 4.5:1
 * faint/dim tones, and a descScrim behind description text blocks.
 */
object GlassColors {
    // Canvas
    val Navy0F = Color(0xFF0F172A)
    val IceE7 = Color(0xFFE7EEF8)

    // Text — all pairs verified >= 4.5:1 against canvas AND frosted chrome
    val TextDark = Color(0xFFE0E6ED)   // 13.5:1 on navy canvas
    val DimDark = Color(0xFFA0AAB5)    //  7.3:1
    val FaintDark = Color(0xFF98A4B8)  //  6.6:1  (was 0xFF66748C = 3.5:1, failed WCAG)
    val TextLight = Color(0xFF152238)  // 13.6:1 on ice canvas
    val DimLight = Color(0xFF4A586B)   //  6.3:1
    val FaintLight = Color(0xFF4C5C74) //  5.8:1  (was 0xFF7C8AA0 = 3.0:1, failed WCAG)

    // Accents (shared)
    val Accent = Color(0xFF00F5FF)     // cyan
    val Good = Color(0xFF00FF88)
    val Warn = Color(0xFFFFD400)
    val Bad = Color(0xFFFF2A6D)
    val Violet = Color(0xFF8A2BE2)
    val Blue = Color(0xFF38BDF8)
    val Pink = Color(0xFFFF6EC7)

    // Chrome fills over canvas — dark mode raises fill alpha for legible glass
    val PillFillDark = Color(0x38000000)   // 22% black  (was 15%)
    val CardFillDark = Color(0x33202C42)   // 20% slate  (was 12%)
    val PillFillLight = Color(0x2EFFFFFF)
    val CardFillLight = Color(0x40FFFFFF)

    // Scrim behind description text blocks (WCAG-safe backdrop for body copy)
    val DescScrimDark = Color(0x66101B2E)  // 40% deep navy
    val DescScrimLight = Color(0x59FFFFFF) // 35% white

    // Chrome hairline borders — dark mode LOWER alpha (no bright halo)
    val BorderDark = Color.White.copy(alpha = 0.16f)
    val BorderLight = Color.White.copy(alpha = 0.28f)
}

/** User-selectable appearance mode, persisted in Settings (Misc screen). */
enum class ThemeMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System")
}

/** Resolved palette for the current dark/light mode. */
data class GlassPalette(
    val dark: Boolean,
    val canvas: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    val pillFill: Color,
    val cardFill: Color,
    val descScrim: Color,
    val border: Color,
    val accent: Color = GlassColors.Accent,
    val good: Color = GlassColors.Good,
    val warn: Color = GlassColors.Warn,
    val bad: Color = GlassColors.Bad,
    val violet: Color = GlassColors.Violet,
    val blue: Color = GlassColors.Blue
)

val LocalGlassPalette = staticCompositionLocalOf {
    GlassPalette(
        dark = true, canvas = GlassColors.Navy0F, text = GlassColors.TextDark,
        dim = GlassColors.DimDark, faint = GlassColors.FaintDark,
        pillFill = GlassColors.PillFillDark, cardFill = GlassColors.CardFillDark,
        descScrim = GlassColors.DescScrimDark, border = GlassColors.BorderDark
    )
}

private val DarkScheme = darkColorScheme(
    primary = GlassColors.Accent,
    onPrimary = Color(0xFF04222A),
    secondary = GlassColors.Violet,
    background = GlassColors.Navy0F,
    onBackground = GlassColors.TextDark,
    surface = GlassColors.Navy0F,
    onSurface = GlassColors.TextDark,
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = GlassColors.DimDark,
    error = GlassColors.Bad,
    outline = Color(0x40FFFFFF)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0090A8),
    onPrimary = Color.White,
    secondary = GlassColors.Violet,
    background = GlassColors.IceE7,
    onBackground = GlassColors.TextLight,
    surface = GlassColors.IceE7,
    onSurface = GlassColors.TextLight,
    surfaceVariant = Color(0xFFD5E0F0),
    onSurfaceVariant = GlassColors.DimLight,
    error = Color(0xFFC81E4E),
    outline = Color(0x30001B33)
)

fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemDark
}

fun glassPalette(mode: ThemeMode, systemDark: Boolean): GlassPalette {
    val dark = resolveDark(mode, systemDark)
    return if (dark) {
        GlassPalette(
            true, GlassColors.Navy0F, GlassColors.TextDark, GlassColors.DimDark,
            GlassColors.FaintDark, GlassColors.PillFillDark, GlassColors.CardFillDark,
            GlassColors.DescScrimDark, GlassColors.BorderDark
        )
    } else {
        GlassPalette(
            false, GlassColors.IceE7, GlassColors.TextLight, GlassColors.DimLight,
            GlassColors.FaintLight, GlassColors.PillFillLight, GlassColors.CardFillLight,
            GlassColors.DescScrimLight, GlassColors.BorderLight
        )
    }
}

@Composable
fun glassPalette(): GlassPalette = glassPalette(ThemeMode.SYSTEM, isSystemInDarkTheme())

@Composable
fun GlassTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val palette = glassPalette(mode, isSystemInDarkTheme())
    CompositionLocalProvider(LocalGlassPalette provides palette) {
        MaterialTheme(
            colorScheme = if (palette.dark) DarkScheme else LightScheme,
            content = content
        )
    }
}
