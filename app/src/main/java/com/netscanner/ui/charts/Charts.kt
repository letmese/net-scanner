package com.netscanner.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netscanner.ui.theme.LocalGlassPalette

/**
 * Pure-Canvas chart components (ports of the legacy LatencyView / SpeedView /
 * chart tabs). No third-party chart library; everything draws on Canvas.
 */

private fun chartStrokeWidth(): Float = 2.5f

/** Single-series line chart with soft gradient fill (legacy LatencyView port). */
@Composable
fun LineChart(
    samples: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = LocalGlassPalette.current.accent,
    fillAlpha: Float = 0.25f,
    fixedMax: Float? = null,
    label: String? = null
) {
    val p = LocalGlassPalette.current
    val valid = samples.filter { !it.isNaN() }
    Column(modifier) {
        if (label != null) {
            Text(label, color = p.dim, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            // grid
            val grid = p.faint.copy(alpha = 0.35f)
            for (i in 1..3) {
                val y = size.height * i / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
            }
            if (valid.size < 2) {
                drawLine(p.faint, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
                return@Canvas
            }
            val maxV = fixedMax ?: maxOf(valid.max(), 0.0001f)
            val minV = 0f
            val stepX = size.width / (valid.size - 1).coerceAtLeast(1)
            fun pt(i: Int): Offset =
                Offset(i * stepX, size.height - ((valid[i] - minV) / maxV) * size.height)

            val path = Path()
            for (i in valid.indices) {
                val q = pt(i)
                if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                fill,
                Brush.verticalGradient(
                    listOf(color.copy(alpha = fillAlpha), Color.Transparent),
                    startY = 0f, endY = size.height
                )
            )
            drawPath(path, color, style = Stroke(chartStrokeWidth(), cap = StrokeCap.Round))
        }
    }
}

/** Dual-series throughput chart for the live speed test (t, down, up) — NaN skips a series. */
@Composable
fun DualLineChart(
    points: List<Triple<Float, Float, Float>>,
    modifier: Modifier = Modifier,
    downColor: Color = LocalGlassPalette.current.accent,
    upColor: Color = LocalGlassPalette.current.violet
) {
    val p = LocalGlassPalette.current
    Canvas(modifier) {
        val grid = p.faint.copy(alpha = 0.35f)
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        val usable = points.filter { !it.second.isNaN() || !it.third.isNaN() }
        if (usable.size < 2) return@Canvas
        val maxV = maxOf(
            usable.maxOf { maxOf(if (it.second.isNaN()) 0f else it.second, if (it.third.isNaN()) 0f else it.third) },
            0.0001f
        )
        val tMin = usable.first().first
        val tMax = maxOf(usable.last().first, tMin + 0.5f)
        fun x(t: Float) = (t - tMin) / (tMax - tMin) * size.width
        fun y(v: Float) = size.height - (v / maxV) * size.height

        for (series in 0..1) {
            val path = Path()
            var started = false
            for ((t, d, u) in usable) {
                val v = if (series == 0) d else u
                if (v.isNaN()) continue
                val q = Offset(x(t), y(v))
                if (!started) { path.moveTo(q.x, q.y); started = true } else path.lineTo(q.x, q.y)
            }
            if (started) drawPath(
                path,
                if (series == 0) downColor else upColor,
                style = Stroke(chartStrokeWidth(), cap = StrokeCap.Round)
            )
        }
    }
}

/** 270° arc gauge with centered value text (legacy SpeedView port). */
@Composable
fun GaugeArc(
    value: Float,
    max: Float,
    label: String,
    color: Color = LocalGlassPalette.current.accent,
    modifier: Modifier = Modifier,
    unit: String = "Mbps"
) {
    val p = LocalGlassPalette.current
    val frac = if (max <= 0f) 0f else (value / max).coerceIn(0f, 1f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeW = 12.dp.toPx()
            val diameter = minOf(size.width, size.height) - strokeW * 1.4f
            val tl = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val sweep = 270f
            val startAngle = 135f
            drawArc(
                color = p.faint.copy(alpha = 0.30f),
                startAngle, sweep, false,
                topLeft = tl, size = androidx.compose.ui.geometry.Size(diameter, diameter),
                style = Stroke(strokeW, cap = StrokeCap.Round)
            )
            if (frac > 0f) drawArc(
                brush = Brush.sweepGradient(
                    0f to color.copy(alpha = 0.55f), 1f to color,
                    center = Offset(size.width / 2f, size.height / 2f)
                ),
                startAngle, sweep * frac, false,
                topLeft = tl, size = androidx.compose.ui.geometry.Size(diameter, diameter),
                style = Stroke(strokeW, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (value.isNaN() || value <= 0f) "--" else String.format("%.1f", value),
                color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold
            )
            Text(unit, color = p.dim, fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
            Text(label, color = p.dim, fontSize = 12.sp)
        }
    }
}

/** Simple horizontal bar rows (usage / connections / channel occupancy). */
@Composable
fun BarsChart(
    entries: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    color: Color = LocalGlassPalette.current.accent,
    valueFmt: (Float) -> String = { String.format("%.1f", it) }
) {
    val p = LocalGlassPalette.current
    val maxV = (entries.maxOfOrNull { it.second } ?: 1f).coerceAtLeast(0.0001f)
    Column(modifier) {
        if (entries.isEmpty()) {
            Text("No data yet", color = p.faint, fontSize = 13.sp)
        }
        entries.forEach { (k, v) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(k, color = p.dim, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                Box(Modifier.weight(1f).height(8.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(
                            p.faint.copy(alpha = 0.25f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                        )
                        if (v > 0f) drawRoundRect(
                            color,
                            size = androidx.compose.ui.geometry.Size(
                                size.width * (v / maxV).coerceIn(0f, 1f), size.height
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    valueFmt(v), color = p.text, fontSize = 12.sp,
                    textAlign = TextAlign.End, modifier = Modifier.width(64.dp)
                )
            }
        }
    }
}

/** 4-bar signal strength indicator. */
@Composable
fun SignalBars(level: Int, modifier: Modifier = Modifier, color: Color = LocalGlassPalette.current.good) {
    val p = LocalGlassPalette.current
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Canvas(Modifier.padding(horizontal = 1.dp).width(5.dp).height((4 + i * 4).dp)) {
                drawRoundRect(
                    if (level >= i) color else p.faint.copy(alpha = 0.35f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}
