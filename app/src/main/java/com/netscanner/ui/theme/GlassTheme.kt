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
 * Glass design tokens (v5.0.0). The glass system is anchored on a deep navy
 * canvas in dark mode and a pale ice-blue canvas in light mode; every text
 * color is derived from the mode so contrast stays safe over both the raw
 * aurora backdrop and the frosted chrome surfaces.
 */
object GlassColors {
    // Canvas
    val Navy0F = Color(0xFF0F172A)
    val IceE7 = Color(0xFFE7EEF8)

    // Text
    val TextDark = Color(0xFFE0E6ED)   // on dark canvas
    val DimDark = Color(0xFFA0AAB5)
    val FaintDark = Color(0xFF66748C)
    val TextLight = Color(0xFF152238)  // on light canvas
    val DimLight = Color(0xFF4A586B)
    val FaintLight = Color(0xFF7C8AA0)

    // Accents (shared)
    val Accent = Color(0xFF00F5FF)     // cyan
    val Good = Color(0xFF00FF88)
    val Warn = Color(0xFFFFD400)
    val Bad = Color(0xFFFF2A6D)
    val Violet = Color(0xFF8A2BE2)
    val Blue = Color(0xFF38BDF8)
    val Pink = Color(0xFFFF6EC7)

    // Chrome fills (alpha over canvas)
    val PillFillDark = Color(0x26000000)
    val CardFillDark = Color(0x1F1E293B)
    val PillFillLight = Color(0x2EFFFFFF)
    val CardFillLight = Color(0x40FFFFFF)
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
    val accent: Color = GlassColors.Accent,
    val good: Color = GlassColors.Good,
    val warn: Color = GlassColors.Warn,
    val bad: Color = GlassColors.Bad,
    val violet: Color = GlassColors.Violet,
    val blue: Color = GlassColors.Blue
)

val LocalGlassPalette = staticCompositionLocalOf { GlassPalette(dark = true, canvas = GlassColors.Navy0F, text = GlassColors.TextDark, dim = GlassColors.DimDark, faint = GlassColors.FaintDark, pillFill = GlassColors.PillFillDark, cardFill = GlassColors.CardFillDark) }

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

@Composable
fun glassPalette(): GlassPalette {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        GlassPalette(true, GlassColors.Navy0F, GlassColors.TextDark, GlassColors.DimDark, GlassColors.FaintDark, GlassColors.PillFillDark, GlassColors.CardFillDark)
    } else {
        GlassPalette(false, GlassColors.IceE7, GlassColors.TextLight, GlassColors.DimLight, GlassColors.FaintLight, GlassColors.PillFillLight, GlassColors.CardFillLight)
    }
}

@Composable
fun GlassTheme(content: @Composable () -> Unit) {
    val palette = glassPalette()
    CompositionLocalProvider(LocalGlassPalette provides palette) {
        MaterialTheme(
            colorScheme = if (palette.dark) DarkScheme else LightScheme,
            content = content
        )
    }
}
