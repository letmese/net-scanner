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
import android.telephony.SubscriptionManager
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.netscanner.ui.glass.GlassDesc
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// ─────────────────────────── Ping Monitor ───────────────────────────

/** One active SIM subscription (v5.1.1 dual-SIM support). */
private data class SimEntry(val subId: Int, val slot: Int, val name: String, val carrier: String)

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
    var phoneGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val phoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { phoneGranted = it }

    var monitoring by remember { mutableStateOf(CellService.running) }

    // v5.1.1 dual-SIM: enumerate every active subscription and build a
    // per-subscription TelephonyManager. The selector is hidden entirely
    // when only one SIM is active — single-SIM devices render exactly as
    // before, with no broken empty section.
    val sims = remember { mutableStateListOf<SimEntry>() }
    var selSub by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(phoneGranted) {
        if (!phoneGranted) {
            sims.clear()
            selSub = null
            return@LaunchedEffect
        }
        try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager
            val list = sm?.activeSubscriptionInfoList ?: emptyList()
            sims.clear()
            list.forEach { si ->
                sims.add(
                    SimEntry(
                        si.subscriptionId,
                        si.simSlotIndex,
                        si.displayName?.toString()?.ifBlank { null } ?: "SIM",
                        si.carrierName?.toString().orEmpty()
                    )
                )
            }
            selSub = when {
                sims.size <= 1 -> null
                sims.any { it.subId == selSub } -> selSub
                else -> sims[0].subId
            }
        } catch (_: Exception) {
            sims.clear()
            selSub = null
        }
    }
    // Per-SIM TelephonyManager: the selected subscription when dual-SIM,
    // the device default otherwise.
    val activeTm = remember(selSub) {
        val s = selSub
        if (s == null) tm else tm.createForSubscriptionId(s)
    }
    val currentTm by rememberUpdatedState(activeTm)
    var dbm by remember { mutableStateOf<Int?>(null) }
    var asu by remember { mutableIntStateOf(-1) }
    var bars by remember { mutableIntStateOf(0) }
    var tech by remember { mutableStateOf("--") }
    var operator by remember { mutableStateOf("--") }
    val samples = remember { mutableStateListOf<Float>() }
    val neighbors = remember { mutableStateListOf<String>() }
    val csvRows = remember { mutableStateListOf<String>() }
    // v5.1.0: restored legacy 6-tab layout (Cells / Gauges / Graph / Log / Info / Map)
    val tabs = listOf("Cells", "Gauges", "Graph", "Log", "Info", "Map")
    var tab by remember { mutableStateOf("Cells") }
    val events = remember { mutableStateListOf<String>() }
    var lastLoggedDbm by remember { mutableStateOf<Int?>(null) }
    var lastLoggedBars by remember { mutableIntStateOf(-1) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    // 1 s sampling loop — mirrors the legacy rolling 4-minute plot.
    // v5.1.1: reads signal DIRECTLY from the per-SIM TelephonyManager every
    // second (no listener permission traps), falling back to CellService
    // state when READ_PHONE_STATE is missing. This is what brings the
    // Gauges tab to life: the old path relied solely on the service
    // listener, which Android 10/11+ silently mutes without permissions,
    // so dbm stayed null and the gauge rendered nothing forever.
    LaunchedEffect(Unit) {
        while (true) {
            var d: Int? = null
            if (phoneGranted) {
                try {
                    val ss = currentTm.signalStrength
                    if (ss != null) {
                        // getDbm()/getAsuLevel() are not in the public SDK
                        // stubs — use the reflection helper (same as service).
                        val v = CellService.cellDbm(ss)
                        if (v != Int.MAX_VALUE) {
                            d = v
                            asu = try {
                                SignalStrength::class.java.getMethod("getAsuLevel")
                                    .invoke(ss) as Int
                            } catch (_: Exception) { -1 }
                            bars = ss.level
                        }
                    }
                } catch (_: Exception) {}
            }
            if (d == null) {
                val s = CellService.lastDbm
                if (s != Int.MAX_VALUE) {
                    d = s
                    asu = CellService.lastAsu
                    bars = CellService.lastBars
                }
            }
            val dd = d
            if (dd != null) {
                dbm = dd
                samples.add(dd.toFloat())
                if (samples.size > 240) samples.removeAt(0)
                csvRows.add("${System.currentTimeMillis()},$dd,$asu,$bars")
                if (csvRows.size > 2000) csvRows.removeAt(0)
                // Log tab: record every >=3 dB shift or bar change (legacy log tab parity)
                val prev = lastLoggedDbm
                if (prev == null || abs(dd - prev) >= 3 || bars != lastLoggedBars) {
                    val dir = when {
                        prev == null -> "  first"
                        dd > prev -> "  ▲ +${dd - prev}"
                        dd < prev -> "  ▼ ${dd - prev}"
                        else -> ""
                    }
                    val tag = when {
                        bars > lastLoggedBars && lastLoggedBars >= 0 -> "  (bars up)"
                        bars < lastLoggedBars && lastLoggedBars >= 0 -> "  (bars down)"
                        else -> ""
                    }
                    events.add(0, "${timeFmt.format(Date())}  $dd dBm  ${CellService.barsStr(bars)}$dir$tag")
                    if (events.size > 150) events.removeAt(events.size - 1)
                    lastLoggedDbm = dd
                    lastLoggedBars = bars
                }
            }
            delay(1000)
        }
    }

    // Carrier / tech / neighbor-cell refresh every 3 s (selected SIM).
    LaunchedEffect(granted, selSub) {
        while (true) {
            tech = try {
                CellService.networkTypeName(currentTm.dataNetworkType)
            } catch (_: Exception) { "n/a" }
            operator = try {
                currentTm.networkOperatorName.ifBlank { "unknown" }
            } catch (_: Exception) { "unknown" }
            if (granted) {
                neighbors.clear()
                try {
                    for (ci in currentTm.allCellInfo ?: emptyList()) {
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
        // Control row: start/stop + permission + live badge
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
            if (!phoneGranted) {
                Spacer(Modifier.width(8.dp))
                GlassButton("Grant Phone", {
                    phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                })
            }
            Spacer(Modifier.weight(1f))
            Text(
                dbm?.let { "$it dBm" } ?: "--",
                color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))

        // Tab bar — restored legacy 6-tab layout
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEach { t ->
                val selected = tab == t
                GlassChip(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { tab = t }
                        .padding(2.dp)
                ) {
                    Text(
                        t, color = if (selected) p.accent else p.dim,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // v5.1.1: per-SIM selector — rendered only when 2+ SIMs are active
        if (sims.size > 1) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sims.forEach { s ->
                    val selected = selSub == s.subId
                    GlassChip(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { selSub = s.subId }
                            .padding(2.dp)
                    ) {
                        Text(
                            "SIM${s.slot + 1} · ${s.name}",
                            color = if (selected) p.accent else p.dim,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        when (tab) {
            "Cells" -> {
                LiquidGlassCard(Modifier.fillMaxWidth()) {
                    SectionTitle("Neighbor cells")
                    if (neighbors.isEmpty()) {
                        Text(
                            if (granted) "No cells reported yet — keep the screen open for a few seconds."
                            else "Grant location permission to list surrounding cells.",
                            color = p.dim, fontSize = 12.sp
                        )
                    } else {
                        neighbors.forEach { Text(it, color = p.text, fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                GlassDesc(
                    "Each row is a tower the modem can see — serving plus neighbors. " +
                        "Use it to check whether you are camped on the strongest tower."
                )
            }
            "Gauges" -> {
                LiquidGlassCard(Modifier.fillMaxWidth()) {
                    val pct = dbm?.let { (((it + 110).toFloat() / 55f) * 100f).coerceIn(0f, 100f) } ?: 0f
                    Box(Modifier.fillMaxWidth().height(150.dp)) {
                        com.netscanner.ui.charts.GaugeArc(
                            pct, 100f, "SIGNAL", color, Modifier.fillMaxSize(), unit = "%"
                        )
                    }
                    if (dbm == null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "No live sample yet — tap Start Monitor, or grant Phone + " +
                                "Location permissions so dBm can be read.",
                            color = p.dim, fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignalBars(bars)
                        Spacer(Modifier.width(14.dp))
                        KV("dBm", dbm?.toString() ?: "--")
                        KV("ASU", if (asu >= 0) asu.toString() else "--")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        KV("Carrier", operator)
                        KV("Tech", tech)
                        KV("Bars", "$bars / 4")
                    }
                    // v5.1.1: at-a-glance per-SIM signal on dual-SIM devices
                    if (sims.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        sims.forEach { s ->
                            val sdbm = try {
                                CellService.cellDbm(tm.createForSubscriptionId(s.subId).signalStrength)
                            } catch (_: Exception) { Int.MAX_VALUE }
                            val shown =
                                sdbm.takeIf { it != Int.MAX_VALUE }?.toString() ?: "--"
                            Text(
                                "SIM${s.slot + 1} ${s.name} · ${s.carrier.ifBlank { "?" }} · $shown dBm",
                                color = p.dim, fontSize = 11.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                GlassDesc(
                    "dBm  >= -85 Excellent · >= -95 Good · >= -105 Fair · else Poor. " +
                        "Compare gauges room-to-room to find dead zones."
                )
            }
            "Graph" -> {
                LiquidGlassCard(Modifier.fillMaxWidth()) {
                    LineChart(
                        samples.toList(), Modifier.fillMaxWidth(),
                        color = p.accent,
                        label = "Signal (dBm), rolling 4 minutes sampled every second"
                    )
                }
                Spacer(Modifier.height(8.dp))
                GlassDesc(
                    "One sample per second for the last 4 minutes. Steps or cliffs here " +
                        "usually mean handovers between towers or new interference."
                )
            }
            "Log" -> {
                LiquidGlassCard(Modifier.fillMaxWidth().weight(1f)) {
                    SectionTitle("Signal change log")
                    if (events.isEmpty()) {
                        Text(
                            "Waiting for signal changes — every >=3 dB shift or bar change " +
                                "is recorded here.",
                            color = p.dim, fontSize = 12.sp
                        )
                    } else {
                        LazyColumn {
                            items(events) { e ->
                                Text(
                                    e, color = p.text, fontSize = 12.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                GlassDesc(
                    "Timestamped history of signal drops — handy for correlating with " +
                        "the moment calls started failing."
                )
            }
            "Info" -> {
                LiquidGlassCard(Modifier.fillMaxWidth()) {
                    SectionTitle("Radio & SIM info")
                    // v5.1.1: list every active SIM when dual-SIM, then the
                    // detail block for the currently selected one.
                    if (sims.size > 1) {
                        sims.forEach { s ->
                            KV(
                                "SIM${s.slot + 1} (${s.name})",
                                s.carrier.ifBlank { "unknown" }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        SectionTitle("Selected SIM")
                    }
                    KV("Carrier", operator)
                    KV(
                        "Operator code",
                        try { currentTm.networkOperator?.ifBlank { "?" } ?: "?" } catch (_: Exception) { "?" }
                    )
                    KV(
                        "Country",
                        try { currentTm.networkCountryIso?.uppercase(Locale.US) ?: "?" } catch (_: Exception) { "?" }
                    )
                    KV("Radio tech", tech)
                    KV(
                        "Phone type",
                        try {
                            when (currentTm.phoneType) {
                                TelephonyManager.PHONE_TYPE_GSM -> "GSM"
                                TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                                TelephonyManager.PHONE_TYPE_NONE -> "none"
                                else -> "other"
                            }
                        } catch (_: Exception) { "?" }
                    )
                    KV(
                        "SIM state",
                        try {
                            when (currentTm.simState) {
                                TelephonyManager.SIM_STATE_READY -> "Ready"
                                TelephonyManager.SIM_STATE_ABSENT -> "Absent"
                                TelephonyManager.SIM_STATE_PIN_REQUIRED,
                                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "Locked"
                                else -> "unknown"
                            }
                        } catch (_: Exception) { "?" }
                    )
                    KV(
                        "Roaming",
                        try { if (currentTm.isNetworkRoaming) "Yes" else "No" } catch (_: Exception) { "?" }
                    )
                }
                Spacer(Modifier.height(8.dp))
                GlassDesc(
                    "Who the phone is attached to right now. 'Roaming: Yes' abroad often " +
                        "explains why data feels slow or capped."
                )
            }
            "Map" -> {
                LiquidGlassCard(Modifier.fillMaxWidth()) {
                    SectionTitle("Tower radar (approximate)")
                    androidx.compose.foundation.Canvas(
                        Modifier.fillMaxWidth().height(240.dp)
                    ) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val rMax = minOf(cx, cy) * 0.86f
                        for (i in 1..3) {
                            drawCircle(
                                color = Color.White.copy(alpha = 0.14f),
                                radius = rMax * i / 3f,
                                center = Offset(cx, cy),
                                style = Stroke(width = 1.5f)
                            )
                        }
                        drawCircle(
                            Color(0xFF00FF88), radius = 7f,
                            center = Offset(cx, cy - rMax * 0.72f)
                        )
                        neighbors.forEachIndexed { idx, _ ->
                            val ang = ((idx + 1) * 47f) % 360f
                            val rad = Math.toRadians(ang.toDouble())
                            val rr = rMax * (0.35f + (idx % 3) * 0.18f)
                            drawCircle(
                                Color(0xFF38BDF8), radius = 5f,
                                center = Offset(
                                    cx + (rr * kotlin.math.cos(rad)).toFloat(),
                                    cy + (rr * kotlin.math.sin(rad)).toFloat()
                                )
                            )
                        }
                        drawCircle(Color(0xFF00F5FF), radius = 9f, center = Offset(cx, cy))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("● cyan this phone · ● green serving tower · ● blue neighbors",
                        color = p.dim, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                GlassDesc(
                    "Schematic view only — Android hides true tower coordinates, so azimuths " +
                        "are illustrative. The Cells tab shows the real PCI/EARFCN identifiers."
                )
            }
        }
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
        GlassDesc(
            "One ping per second (2 s timeout). Watch for spikes on Wi-Fi dropouts " +
                "or ISP congestion — e.g. keep it running during a video call to see " +
                "exactly when the frame freezes."
        )
    }
}

// ─────────────────────────── Multi-Ping ───────────────────────────

/** Legacy MultiPingActivity: concurrent latency watch for several targets. */
@Composable
fun MultiPingScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    // v5.1.0 fix: rows are snapshot-backed so the UI recomposes on each
    // sample (plain mutable fields never triggered recomposition — the
    // table stayed frozen at "timeout" even while pings ran).
    class RowT(val target: String) {
        val samples = mutableStateListOf<Float>()
        var sent: Int by mutableStateOf(0)
        var lost: Int by mutableStateOf(0)
    }
    val rows = remember { mutableStateListOf<RowT>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (running) {
            for (r in rows) {
                launch(Dispatchers.IO) {
                    val ms = NetUtils.pingOnce(r.target).takeIf { it >= 0 }
                    r.sent++
                    if (ms == null) r.lost++
                    r.samples.add(ms?.toFloat() ?: Float.NaN)
                    if (r.samples.size > 60) r.samples.removeAt(0)
                }
            }
            delay(1000)
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
                val snaps = r.samples.toList()
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
        GlassDesc(
            "Suite: interfaces → gateway → WAN → DNS → HTTP → public IP → traceroute. " +
                "Run it whenever connectivity feels off to isolate the broken layer — " +
                "before blaming the router or the ISP."
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
