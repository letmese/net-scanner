package com.netscanner.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netscanner.ui.theme.LocalGlassPalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * v5 glassmorphism system (Haze 1.6.5).
 *
 *  - [GlassBackdrop]: aurora canvas marked with hazeSource. Dark mode = deep
 *    navy + cyan/violet/blue/pink glow orbs; light mode = ice blue + softer
 *    orbs. Never blurred itself; chrome surfaces frost it in real time.
 *  - [GlassPill] / [LiquidGlassCard] (chrome ONLY): hazeEffect 19dp blur with
 *    a 15% white tint, 1px 28% white border, soft shadow. Below API 31 the
 *    Haze hazeEffect automatically degrades to a scrim, so no branch needed.
 *  - Content is NEVER blurred (no Modifier.blur anywhere on content nodes).
 */

/** Haze state published by [GlassBackdrop]; null when hosted standalone. */
val LocalGlassHaze = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun GlassBackdrop(content: @Composable () -> Unit) {
    val hazeState = remember { HazeState() }
    val p = LocalGlassPalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(p.canvas)
    ) {
        // Aurora glow layer: the haze sampling source. No self-blur needed —
        // the chrome frost samples this layer; hazeEffect falls back to a
        // scrim below API 31 automatically.
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (p.dark) {
                    orb(0x4D00E5FF, 0.14f, 0.06f, 1.15f)
                    orb(0x4D8A2BE2, 0.88f, 0.90f, 1.30f)
                    orb(0x3838BDF8, 0.05f, 0.62f, 0.95f)
                    orb(0x2EFF6EC7, 0.78f, 0.18f, 0.85f)
                } else {
                    orb(0x3300B8D4, 0.14f, 0.06f, 1.15f)
                    orb(0x2E8A2BE2, 0.88f, 0.90f, 1.30f)
                    orb(0x2638BDF8, 0.05f, 0.62f, 0.95f)
                    orb(0x1EFF6EC7, 0.78f, 0.18f, 0.85f)
                }
            }
        }
        CompositionLocalProvider(LocalGlassHaze provides hazeState) {
            content()
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.orb(
    color: Long, cx: Float, cy: Float, r: Float
) {
    val radius = size.minDimension * r
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(color), Color.Transparent),
            center = Offset(size.width * cx, size.height * cy),
            radius = radius
        ),
        radius = radius,
        center = Offset(size.width * cx, size.height * cy)
    )
}

/** Real-time frost applied to chrome surfaces hosted under a GlassBackdrop. */
fun Modifier.glassFrost(state: HazeState?): Modifier =
    if (state != null) {
        hazeEffect(state) {
            blurRadius = 19.dp
            tints = listOf(HazeTint(Color.White.copy(alpha = 0.15f)))
        }
    } else {
        this
    }

/** Chrome hairline border resolved per mode: dark = lower alpha (no halo). */
@Composable
fun chromeBorder(): Color = LocalGlassPalette.current.border

/** Floating frosted pill bar for the top bar and bottom action bars. */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val p = LocalGlassPalette.current
    val hazeState = LocalGlassHaze.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .glassFrost(hazeState)
            .background(p.pillFill)
            .border(1.dp, chromeBorder(), RoundedCornerShape(28.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * Description text block on a slightly-opaque scrim. Use this for every
 * explanatory / hint text so body copy never sits directly on raw glass:
 * the scrim guarantees WCAG 4.5:1 contrast in both modes and content is
 * never blurred.
 */
@Composable
fun GlassDesc(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp
) {
    val p = LocalGlassPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(p.descScrim)
            .border(1.dp, chromeBorder(), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = p.dim, fontSize = fontSize, lineHeight = fontSize * 1.35f)
    }
}

/** Production frosted glass card: frost + specular border + layered fill. */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalGlassPalette.current
    val hazeState = LocalGlassHaze.current
    val shape = RoundedCornerShape(cornerRadius)

    val specularBorder = if (p.dark) {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.45f),
                Color.White.copy(alpha = 0.12f),
                Color.Transparent
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.85f),
                Color.White.copy(alpha = 0.30f),
                Color(0x14001B33)
            )
        )
    }

    val glassBackground = if (p.dark) {
        Brush.radialGradient(
            listOf(Color(0x3338BDF8), Color(0x1F1E293B), Color(0x2A0F172A))
        )
    } else {
        Brush.radialGradient(
            listOf(Color(0x4DFFFFFF), Color(0x59FFFFFF), Color(0x2ED5E0F0))
        )
    }

    Box(
        modifier = modifier
            .clip(shape)
            .glassFrost(hazeState)
            .background(glassBackground)
            .border(1.2.dp, specularBorder, shape)
            .padding(18.dp)
    ) {
        Column(content = content)
    }
}

/** Small frosted chip for inline actions / status bits. */
@Composable
fun GlassChip(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val p = LocalGlassPalette.current
    val hazeState = LocalGlassHaze.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .glassFrost(hazeState)
            .background(p.pillFill)
            .border(1.dp, chromeBorder(), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
