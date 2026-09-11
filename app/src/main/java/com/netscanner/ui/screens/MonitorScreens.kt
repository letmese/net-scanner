package com.netscanner.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.core.content.ContextCompat
import com.netscanner.core.NetUtils
import com.netscanner.core.ToolEngine
import com.netscanner.nav.Navigator
import com.netscanner.svc.CellService
import com.netscanner.ui.charts.BarsChart
import com.netscanner.ui.charts.LineChart
import com.netscanner.ui.charts.SignalBars
import com.netscanner.ui.glass.GlassButton
import com.netscanner.ui.glass.GlassChip
import com.netscanner.ui.glass.GlassScreen
import com.netscanner.ui.glass.GlassTextField
import com.netscanner.ui.glass.KV
import com.netscanner.ui.glass.LiquidGlassCard
import com.netscanner.ui.glass.SectionTitle
import com.netscanner.ui.theme.LocalGlassPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// ─────────────────────────── Ping Monitor ───────────────────────────

/** Legacy CellMonitorActivity: live signal dashboard, neighbor cells, CSV log. */
@Composable
fun CellMonitorScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    val ctx = LocalContext.current
    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    var monitoring by remember { mutableStateOf(CellService.running) }
    var dbm by remember { mutableStateOf<Int?>(null) }
    var asu by remember { mutableIntStateOf(-1) }
    var bars by remember { mutableIntStateOf(0) }
    var tech by remember { mutableStateOf("--") }
    var operator by remember { mutableStateOf("--") }
    val samples = remember { mutableStateListOf<Float>() }
    val neighbors = remember { mutableStateListOf<String>() }
    val csvRows = remember { mutableStateListOf<String>() }

    // 1 s sampling loop — mirrors the legacy rolling 4-minute plot.
    LaunchedEffect(Unit) {
        while (true) {
            val d = CellService.lastDbm
            if (d != Int.MAX_VALUE) {
                dbm = d
                asu = CellService.lastAsu
                bars = CellService.lastBars
                samples.add(d.toFloat())
                if (samples.size > 240) samples.removeAt(0)
                csvRows.add("${System.currentTimeMillis()},$d,${CellService.lastAsu},${CellService.lastBars}")
                if (csvRows.size > 2000) csvRows.removeAt(0)
            }
            delay(1000)
        }
    }

    // Carrier / tech / neighbor-cell refresh every 3 s.
    LaunchedEffect(granted) {
        while (true) {
            tech = try {
                CellService.networkTypeName(tm.dataNetworkType)
            } catch (_: Exception) { "n/a" }
            operator = try {
                tm.networkOperatorName.ifBlank { "unknown" }
            } catch (_: Exception) { "unknown" }
            if (granted) {
                neighbors.clear()
                try {
                    for (ci in tm.allCellInfo ?: emptyList()) {
                        val line = when (ci) {
                            is CellInfoLte ->
                                "LTE   PCI ${ci.cellIdentity.pci}  EARFCN ${ci.cellIdentity.earfcn}" +
                                    "  ·  RSRP ${ci.cellSignalStrength.rsrp} dBm"
                            is CellInfoGsm ->
                                "GSM   ARFCN ${ci.cellIdentity.arfcn}  BSIC ${ci.cellIdentity.bsic}" +
                                    "  ·  ${ci.cellSignalStrength.dbm} dBm"
                            is CellInfoWcdma ->
                                "WCDMA PSC ${ci.cellIdentity.psc}  ·  ${ci.cellSignalStrength.dbm} dBm"
                            else ->
                                if (Build.VERSION.SDK_INT >= 29 && ci is CellInfoNr)
                                    "5G NR PCI ${(ci.cellIdentity as? android.telephony.CellIdentityNr)?.pci}  ·  ${ci.cellSignalStrength.dbm} dBm"
                                else ""
                        }
                        if (line.isNotBlank()) neighbors.add(line)
                    }
                } catch (_: Exception) {}
            }
            delay(3000)
        }
    }

    val color = when {
        dbm == null -> p.dim
        dbm!! >= -85 -> p.good
        dbm!! >= -95 -> p.accent
        dbm!! >= -105 -> p.warn
        else -> p.bad
    }

    GlassScreen("Cell Monitor", nav, actions = {
        GlassButton("Export CSV", {
            val sb = StringBuilder("ts,dbm,asu,bars\n")
            csvRows.forEach { sb.append(it).append('\n') }
            ToolEngine.exportCsv(ctx, "cell_log.csv", sb.toString())
        })
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassButton(
                if (monitoring) "Stop Monitor" else "Start Monitor",
                {
                    if (monitoring) {
                        ctx.startService(
                            Intent(ctx, CellService::class.java).setAction(CellService.ACTION_STOP)
                        )
                        monitoring = false
                    } else {
                        ContextCompat.startForegroundService(
                            ctx, Intent(ctx, CellService::class.java)
                        )
                        monitoring = true
                    }
                },
                accent = !monitoring
            )
            Spacer(Modifier.width(8.dp))
            if (!granted) {
                GlassButton("Grant Location", {
                    permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                })
            }
        }
        Spacer(Modifier.height(8.dp))

        LiquidGlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    dbm?.toString() ?: "--",
                    color = color, fontSize = 54.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("dBm", color = p.dim, fontSize = 13.sp)
                    Text("ASU ${if (asu >= 0) asu.toString() else "--"}", color = p.dim, fontSize = 13.sp)
                }
                Spacer(Modifier.width(14.dp))
                SignalBars(bars)
            }
            Spacer(Modifier.height(10.dp))
            LineChart(
                samples.toList(), Modifier.fillMaxWidth(),
                color = p.accent, label = "Signal (dBm), rolling 4 minutes sampled every second"
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                KV("Carrier", operator)
                KV("Tech", tech)
                KV("Bars", "$bars / 4")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "RSRP  >= -80 Excellent · >= -90 Good · >= -100 Fair · else Poor\n" +
                    "dBm   >= -85 Excellent · >= -95 Good · >= -105 Fair · else Poor",
                color = p.faint, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(8.dp))

        LiquidGlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Neighbor cells")
            if (neighbors.isEmpty()) {
                Text(
                    if (granted) "No cells reported yet — keep the screen open for a few seconds."
                    else "Grant location permission to list surrounding cells.",
                    color = p.faint, fontSize = 12.sp
                )
            } else {
                neighbors.take(8).forEach { Text(it, color = p.text, fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Start Monitor keeps a foreground notification with live dBm in the status bar. " +
                "Neighbor cells need location permission on this Android version.",
            color = p.faint, fontSize = 12.sp
        )
    }
}

/** Legacy PingMonitorActivity: single-target 1 s latency graph + stats. */
@Composable
fun PingMonitorScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    var target by remember { mutableStateOf(NetUtils.localNet()?.let { it.prefix + "1" } ?: "1.1.1.1") }
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
            val ms = withContext(Dispatchers.IO) { NetUtils.pingOnce(target) }.takeIf { it >= 0 }
            sent++
            if (ms == null) {
                lost++
                lastMs = null
            } else {
                lastMs = ms.toFloat()
                minMs = minOf(minMs, ms.toFloat())
                maxMs = maxOf(maxMs, ms.toFloat())
            }
            samples.add(ms?.toFloat() ?: Float.NaN)
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
                    val ms = NetUtils.pingOnce(r.target).takeIf { it >= 0 }
                    r.sent++
                    if (ms == null) r.lost++
                    synchronized(r.samples) {
                        r.samples.add(ms?.toFloat() ?: Float.NaN)
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
            val localIp = withContext(io) { NetUtils.localNet()?.ip }
            val gw = withContext(io) { NetUtils.localNet()?.let { it.prefix + "1" } }
            set(0, localIp != null,
                if (localIp != null) "ip $localIp · gw ${gw ?: "?"}" else "no interface")
            val gwOk = gw != null && withContext(io) { NetUtils.pingOnce(gw) >= 0 }
            set(1, gwOk, if (gw == null) "no gateway" else "$gw ${if (gwOk) "reachable" else "no reply"}")
            val netOk = withContext(io) { NetUtils.pingOnce("1.1.1.1") }
            set(2, netOk >= 0, if (netOk >= 0) "1.1.1.1 ${String.format("%.0f ms", netOk.toFloat())}" else "no reply")
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
            val hops = mutableListOf<String>()
            withContext(io) {
                NetUtils.traceroute("1.1.1.1") { ttl, ip, ms -> hops.add("$ttl $ip${ms?.let { " $it" } ?: ""}") }
            }
            set(6, hops.isNotEmpty(), if (hops.isEmpty()) "no hops" else hops.joinToString(" → "))
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
                val gw = NetUtils.localNet()?.let { it.prefix + "1" }
                var latScore = 0f
                if (gw != null) {
                    val samples = (1..5).map { NetUtils.pingOnce(gw) }.filter { it >= 0 }
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
                val pings = (1..10).map { NetUtils.pingOnce("1.1.1.1") }.filter { it >= 0 }
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
