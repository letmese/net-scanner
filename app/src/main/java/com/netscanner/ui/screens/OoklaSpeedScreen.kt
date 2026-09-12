package com.netscanner.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netscanner.core.OoklaSpeed
import com.netscanner.nav.Navigator
import com.netscanner.ui.charts.DualLineChart
import com.netscanner.ui.charts.GaugeArc
import com.netscanner.ui.glass.GlassButton
import com.netscanner.ui.glass.GlassChip
import com.netscanner.ui.glass.GlassScreen
import com.netscanner.ui.glass.KV
import com.netscanner.ui.glass.LiquidGlassCard
import com.netscanner.ui.theme.GlassColors
import com.netscanner.ui.theme.LocalGlassPalette
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v5.3.0 Speed Test tab — Ookla-style: big gauges, live throughput graph,
 * jitter / loaded latency / bufferbloat grade / packet loss, ISP & server
 * info, and tap-to-inspect local JSON history. Engine: [OoklaSpeed].
 */
@Composable
fun OoklaSpeedScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val o = OoklaSpeed
    var histV by remember { mutableStateOf(0) }
    var openTs by remember { mutableStateOf<Long?>(null) }
    val hist = remember(histV) { OoklaSpeed.history(ctx) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    GlassScreen("Speed Test", nav) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            // ── gauges + control ──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).height(130.dp)) {
                        GaugeArc(
                            if (o.gaugeDown.isNaN()) 0f else o.gaugeDown,
                            1000f, "DOWN", GlassColors.Accent, Modifier.fillMaxSize()
                        )
                    }
                    Box(Modifier.weight(1f).height(130.dp)) {
                        GaugeArc(
                            if (o.gaugeUp.isNaN()) 0f else o.gaugeUp,
                            1000f, "UP", GlassColors.Violet, Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (o.phase) {
                        "idle" -> "Ready — jitter, bufferbloat, loss & ISP info"
                        "ping" -> "Idle latency…"
                        "download" -> "Download…"
                        "upload" -> "Upload…"
                        "done" -> o.status
                        "error" -> "Failed: ${o.err}"
                        else -> o.status
                    },
                    color = when (o.phase) {
                        "error" -> p.bad
                        "done" -> p.good
                        else -> p.dim
                    },
                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                GlassButton(
                    when {
                        o.running -> "Cancel"
                        o.phase == "done" -> "Run again"
                        else -> "GO"
                    },
                    {
                        if (o.running) {
                            o.cancel()
                        } else {
                            o.start(ctx)
                            histV++
                        }
                    },
                    accent = !o.running
                )
            }
            Spacer(Modifier.height(10.dp))

            // ── live throughput graph ──
            val pts = remember(o.downSamples.size, o.upSamples.size) {
                buildList {
                    o.downSamples.forEach { (t, v) -> add(Triple(t, v, Float.NaN)) }
                    o.upSamples.forEach { (t, v) -> add(Triple(t, Float.NaN, v)) }
                }
            }
            if (pts.size >= 2) {
                LiquidGlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                    DualLineChart(pts, Modifier.fillMaxWidth().height(120.dp))
                    Spacer(Modifier.height(4.dp))
                    Row {
                        GlassChip { Text("● down", color = p.accent, fontSize = 11.sp) }
                        Spacer(Modifier.width(8.dp))
                        GlassChip { Text("● up", color = p.violet, fontSize = 11.sp) }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // ── Ookla-style quality metrics ──
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Text("Connection quality", color = p.dim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCell("Idle ping", o.idlePing?.let { fmtMs(it) }, Modifier.weight(1f))
                    MetricCell("Jitter", o.jitter?.let { fmtMs(it) }, Modifier.weight(1f))
                    MetricCell("Packet loss", o.loss?.let { String.format(Locale.US, "%.1f%%", it) }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCell("Loaded ↓", o.loadedDown?.let { fmtMs(it) }, Modifier.weight(1f))
                    MetricCell("Loaded ↑", o.loadedUp?.let { fmtMs(it) }, Modifier.weight(1f))
                    GradeCell(o.grade, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── connection metadata ──
            if (o.publicIp != null || o.isp != null || o.org != null || o.asInfo != null || o.serverLoc() != null) {
                LiquidGlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                    Text("Connection", color = p.dim, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    o.publicIp?.let { KV("Public IP", it) }
                    (o.isp ?: o.org)?.let { KV("ISP", it) }
                    o.asInfo?.let { KV("AS", it) }
                    o.serverLoc()?.let { KV("Server", it) }
                }
                Spacer(Modifier.height(10.dp))
            }

            // ── local history (JSON in app files) ──
            LiquidGlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("History", color = p.dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${hist.size} tests", color = p.faint, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                if (hist.isEmpty()) {
                    Text(
                        "No tests yet — results are stored on this device",
                        color = p.faint, fontSize = 12.sp
                    )
                }
                hist.take(12).forEach { e ->
                    val ts = e.optLong("ts")
                    val isOpen = openTs == ts
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { openTs = if (isOpen) null else ts }
                            .padding(vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                fmt.format(Date(ts)),
                                color = p.faint, fontSize = 11.sp,
                                modifier = Modifier.width(92.dp)
                            )
                            Text(
                                String.format(
                                    Locale.US, "↓%.1f  ↑%.1f Mbps",
                                    e.optDouble("down", 0.0), e.optDouble("up", 0.0)
                                ),
                                color = p.text, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            val g = e.optString("grade", "")
                            if (g.isNotEmpty()) {
                                Text(
                                    g, color = gradeColor(g),
                                    fontSize = 13.sp, fontWeight = FontWeight.Black
                                )
                            }
                        }
                        if (isOpen) {
                            Spacer(Modifier.height(4.dp))
                            Column(Modifier.padding(start = 8.dp)) {
                                optMs(e, "idle")?.let { KV("Idle ping", it) }
                                optMs(e, "jitter")?.let { KV("Jitter", it) }
                                e.optDouble("loss", Double.NaN).takeIf { !it.isNaN() }?.let {
                                    KV("Packet loss", String.format(Locale.US, "%.1f%%", it))
                                }
                                optMs(e, "ldown")?.let { KV("Loaded ↓", it) }
                                optMs(e, "lup")?.let { KV("Loaded ↑", it) }
                                e.optString("ip", "").takeIf { it.isNotEmpty() }?.let { KV("Public IP", it) }
                                e.optString("isp", "").takeIf { it.isNotEmpty() }?.let { KV("ISP", it) }
                                e.optString("as", "").takeIf { it.isNotEmpty() }?.let { KV("AS", it) }
                                e.optString("loc", "").takeIf { it.isNotEmpty() }?.let { KV("Server", it) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun optMs(e: JSONObject, key: String): String? =
    e.optDouble(key, Double.NaN).takeIf { !it.isNaN() }?.let { fmtMs(it.toFloat()) }

private fun fmtMs(v: Float): String =
    if (v >= 100f) "${v.toInt()} ms" else String.format(Locale.US, "%.1f ms", v)

@Composable
private fun MetricCell(label: String, value: String?, modifier: Modifier = Modifier) {
    val p = LocalGlassPalette.current
    Column(modifier) {
        Text(value ?: "--", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = p.dim, fontSize = 11.sp)
    }
}

@Composable
private fun GradeCell(grade: String?, modifier: Modifier = Modifier) {
    val p = LocalGlassPalette.current
    Column(modifier) {
        Text(
            grade ?: "--",
            color = grade?.let { gradeColor(it) } ?: p.text,
            fontSize = if (grade != null) 20.sp else 16.sp,
            fontWeight = FontWeight.Black
        )
        Text("Bufferbloat", color = p.dim, fontSize = 11.sp)
    }
}

private fun gradeColor(g: String): Color = when (g) {
    "A+", "A" -> GlassColors.Good
    "B" -> GlassColors.Blue
    "C" -> GlassColors.Warn
    else -> GlassColors.Bad
}
