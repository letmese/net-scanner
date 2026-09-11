package com.netscanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netscanner.core.NetUtils
import com.netscanner.nav.Navigator
import com.netscanner.ui.charts.BarsChart
import com.netscanner.ui.charts.LineChart
import com.netscanner.ui.glass.GlassButton
import com.netscanner.ui.glass.GlassChip
import com.netscanner.ui.glass.GlassTextField
import com.netscanner.ui.glass.KV
import com.netscanner.ui.glass.LiquidGlassCard
import com.netscanner.ui.theme.LocalGlassPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// ─────────────────────────── Ping Monitor ───────────────────────────

/** Legacy PingMonitorActivity: single-target 1 s latency graph + stats. */
@Composable
fun PingMonitorScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    var target by remember { mutableStateOf(NetUtils.gateway() ?: "1.1.1.1") }
    var running by remember { mutableStateOf(false) }
    val samples = remember { mutableStateListOf<Float>() }
    var sent by remember { mutableIntStateOf(0) }
    var lost by remember { mutableIntStateOf(0) }
    var minMs by remember { mutableStateOf(Float.MAX_VALUE) }
    var maxMs by remember { mutableStateOf(0f) }
    var lastMs by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (running) {
            val ms = withContext(Dispatchers.IO) { NetUtils.pingOnce(target, 2) }
            sent++
            if (ms == null) {
                lost++
                lastMs = null
            } else {
                lastMs = ms
                minMs = minOf(minMs, ms)
                maxMs = maxOf(maxMs, ms)
            }
            samples.add(ms ?: Float.NaN)
            if (samples.size > 120) samples.removeAt(0)
            delay(1000)
        }
    }
    val jitter = samples.takeLast(30).filter { !it.isNaN() }.zipWithNext().map { abs(it.first - it.second) }
        .takeIf { it.isNotEmpty() }?.average()?.toFloat()

    GlassScreen("Ping Monitor", nav) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(target, { target = it }, "Target host / IP", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            GlassButton(if (running) "Stop" else "Start", { running = !running }, accent = !running)
        }
        Spacer(Modifier.height(8.dp))
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            LineChart(
                samples.toList(),
                Modifier.fillMaxWidth(),
                color = p.accent,
                label = "Latency (ms), last ${samples.size} pings"
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                KV("Now", lastMs?.let { String.format("%.0f ms", it) } ?: "timeout")
                KV("Avg", samples.filter { !it.isNaN() }.takeIf { it.isNotEmpty() }
                    ?.let { String.format("%.0f ms", it.average()) } ?: "--")
                KV("Jitter", jitter?.let { String.format("%.1f ms", it) } ?: "--")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                KV("Min", if (minMs == Float.MAX_VALUE) "--" else String.format("%.0f ms", minMs))
                KV("Max", if (maxMs == 0f) "--" else String.format("%.0f ms", maxMs))
                KV(
                    "Loss", if (sent == 0) "--"
                    else String.format("%.1f%%", lost * 100.0 / sent),
                    if (sent > 0 && lost * 100.0 / sent > 2) p.bad else p.text
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "One ping per second (2 s timeout). Watch for spikes on Wi-Fi dropouts " +
                "or ISP congestion.",
            color = p.faint, fontSize = 12.sp
        )
    }
}

// ─────────────────────────── Multi-Ping ───────────────────────────

/** Legacy MultiPingActivity: concurrent latency watch for several targets. */
@Composable
fun MultiPingScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    data class RowT(val target: String) {
        val samples = mutableListOf<Float>()
        var sent: Int = 0
        var lost: Int = 0
    }
    val rows = remember { mutableStateListOf<RowT>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var beat by remember { mutableIntStateOf(0) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (running) {
            for (r in rows) {
                launch(Dispatchers.IO) {
                    val ms = NetUtils.pingOnce(r.target, 2)
                    r.sent++
                    if (ms == null) r.lost++
                    synchronized(r.samples) {
                        r.samples.add(ms ?: Float.NaN)
                        if (r.samples.size > 60) r.samples.removeAt(0)
                    }
                }
            }
            delay(1000)
            beat++
        }
    }

    GlassScreen("Multi-Ping", nav) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(input, { input = it }, "Add target (IP or host)", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            GlassButton("Add", {
                val t = input.trim()
                if (t.isNotEmpty() && rows.none { it.target == t }) { rows.add(RowT(t)); input = "" }
            })
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (running) "Pause" else "Watch all", { running = !running }, accent = !running)
            GlassButton("Clear", { rows.clear(); running = false })
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.target }) { r ->
                val snaps = synchronized(r.samples) { r.samples.toList() }
                val valid = snaps.filter { !it.isNaN() }
                LiquidGlassCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.target, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                (valid.lastOrNull()?.let { String.format("%.0f ms", it) } ?: "timeout") +
                                    "  ·  loss ${if (r.sent > 0) r.lost * 100 / r.sent else 0}%",
                                color = p.dim, fontSize = 12.sp
                            )
                        }
                        LineChart(snaps, Modifier.width(140.dp).height(48.dp), color = p.accent)
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Network Diagnostics ───────────────────────────

private data class DiagStep(val name: String, val ok: Boolean = false, val detail: String = "…")

/** Legacy NetDiagActivity: step-by-step connectivity diagnostic suite. */
@Composable
fun NetDiagScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    var steps by remember {
        mutableStateOf(listOf(
            DiagStep("Wi-Fi / interface"),
            DiagStep("Gateway reachable"),
            DiagStep("Internet ping"),
            DiagStep("DNS resolution"),
            DiagStep("HTTP check"),
            DiagStep("Public IP"),
            DiagStep("Traceroute")
        ))
    }
    var running by remember { mutableStateOf(false) }

    fun runSuite() {
        scope.launch {
            running = true
            val io = Dispatchers.IO
            suspend fun set(i: Int, ok: Boolean, detail: String) {
                steps = steps.toMutableList().also { it[i] = it[i].copy(ok = ok, detail = detail) }
            }
            for (i in steps.indices) set(i, false, "…")
            val localIp = withContext(io) { NetUtils.localIp() }
            set(0, localIp != null,
                if (localIp != null) "ip $localIp · gw ${NetUtils.gateway()}" else "no interface")
            val gw = withContext(io) { NetUtils.gateway() }
            val gwOk = gw != null && withContext(io) { NetUtils.pingOnce(gw, 2) != null }
            set(1, gwOk, if (gw == null) "no gateway" else "$gw ${if (gwOk) "reachable" else "no reply"}")
            val netOk = withContext(io) { NetUtils.pingOnce("1.1.1.1", 3) }
            set(2, netOk != null, if (netOk != null) "1.1.1.1 ${String.format("%.0f ms", netOk)}" else "no reply")
            val dns = withContext(io) {
                runCatching { java.net.InetAddress.getAllByName("www.google.com") }.getOrNull()
            }
            set(3, dns != null && dns.isNotEmpty(),
                if (dns != null && dns.isNotEmpty()) "${dns.size} records · ${dns[0].hostAddress}" else "resolution failed")
            val http = withContext(io) {
                try {
                    val c = java.net.URL("http://connectivitycheck.gstatic.com/generate_204")
                        .openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 5000; c.readTimeout = 5000
                    val code = c.responseCode; c.disconnect(); code.toString()
                } catch (e: Exception) { "fail: ${e.message?.take(40)}" }
            }
            set(4, http == "204" || http == "200", http)
            val ip = withContext(io) {
                try { java.net.URL("https://api.ipify.org").readText().take(45) } catch (_: Exception) { "n/a" }
            }
            set(5, ip != "n/a", ip)
            val hops = withContext(io) { NetUtils.traceroute("1.1.1.1", 10) }
            set(6, hops.isNotEmpty(), if (hops.isEmpty()) "no hops" else hops.joinToString(" → ") { it.host ?: "?" })
            running = false
        }
    }

    GlassScreen("Network Diagnostics", nav, actions = {
        GlassButton("Run", { runSuite() }, accent = true, enabled = !running)
    }) {
        LazyColumn(Modifier.weight(1f)) {
            items(steps) { s ->
                LiquidGlassCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (s.detail == "…") "…" else if (s.ok) "OK" else "X",
                            color = if (s.detail == "…") p.faint else if (s.ok) p.good else p.bad,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(s.detail, color = p.faint, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Text(
            "Suite: interfaces → gateway → WAN → DNS → HTTP → public IP → traceroute. " +
                "Run it whenever connectivity feels off to isolate the broken layer.",
            color = p.faint, fontSize = 12.sp
        )
    }
}

// ─────────────────────────── Health Score ───────────────────────────

/** Legacy HealthActivity: weighted 0-100 network health score. */
@Composable
fun HealthScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val p = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    var score by remember { mutableStateOf<Int?>(null) }
    var running by remember { mutableStateOf(false) }
    val parts = remember { mutableStateListOf<Pair<String, Float>>() }

    fun compute() {
        scope.launch {
            running = true
            val out = withContext(Dispatchers.IO) {
                val gw = NetUtils.gateway()
                var latScore = 0f
                if (gw != null) {
                    val samples = (1..5).mapNotNull { NetUtils.pingOnce(gw, 2) }
                    if (samples.isNotEmpty()) {
                        val avg = samples.average().toFloat()
                        latScore = when {
                            avg < 5 -> 35f; avg < 15 -> 28f; avg < 40 -> 20f
                            avg < 100 -> 12f; else -> 5f
                        }
                    }
                }
                val t0 = System.currentTimeMillis()
                val dnsOk = runCatching { java.net.InetAddress.getByName("www.google.com") }.isSuccess
                val dnsMs = System.currentTimeMillis() - t0
                val dnsScore = if (dnsOk) when {
                    dnsMs < 100 -> 20f; dnsMs < 300 -> 15f; dnsMs < 800 -> 10f; else -> 6f
                } else 0f
                var wifiScore = 0f
                try {
                    val wm = ctx.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    val pct = ((wm.connectionInfo.rssi + 100) * 2).coerceIn(0, 100)
                    wifiScore = pct * 25f / 100f
                } catch (_: Exception) {}
                val pings = (1..10).mapNotNull { NetUtils.pingOnce("1.1.1.1", 2) }
                val lossScore = 20f * pings.size / 10f
                Triple(
                    (latScore + dnsScore + wifiScore + lossScore).toInt().coerceIn(0, 100),
                    listOf(
                        "Latency" to latScore, "DNS" to dnsScore,
                        "Wi-Fi signal" to wifiScore, "Reliability" to lossScore
                    ), Unit
                )
            }
            score = out.first
            parts.clear(); parts.addAll(out.second)
            running = false
        }
    }
    val color = when {
        score == null -> p.accent
        score!! >= 80 -> p.good
        score!! >= 55 -> p.warn
        else -> p.bad
    }
    GlassScreen("Network Health", nav, actions = {
        GlassButton("Score", { compute() }, accent = true, enabled = !running)
    }) {
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            Text(
                score?.toString() ?: "--",
                color = color, fontSize = 56.sp, fontWeight = FontWeight.Bold
            )
            Text(
                when {
                    score == null -> "Tap Score to run the checks"
                    score!! >= 80 -> "Excellent — nothing to fix"
                    score!! >= 55 -> "Usable, some weak spots"
                    else -> "Poor — see breakdown"
                },
                color = p.dim, fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        if (parts.isNotEmpty()) {
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                BarsChart(parts.toList(), Modifier.fillMaxWidth(), color = p.accent,
                    valueFmt = { "${it.toInt()} pts" })
                Text("weights: latency 35 · dns 20 · wifi 25 · reliability 20",
                    color = p.faint, fontSize = 11.sp)
            }
        }
    }
}
