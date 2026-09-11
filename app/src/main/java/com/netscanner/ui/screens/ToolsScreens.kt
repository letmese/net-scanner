package com.netscanner.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.netscanner.core.AppLog
import com.netscanner.core.ToolEngine
import com.netscanner.nav.Navigator
import com.netscanner.nav.Route
import com.netscanner.ui.glass.GlassScreen
import com.netscanner.ui.glass.LiquidGlassCard
import com.netscanner.ui.glass.GlassChip
import com.netscanner.ui.glass.GlassDesc
import com.netscanner.ui.glass.UseChip
import com.netscanner.ui.theme.LocalGlassPalette
import com.netscanner.ui.theme.GlassColors

/** 24-tile catalog — exact port of legacy ToolsActivity grid. */
data class ToolTile(
    val emoji: String, val title: String, val sub: String,
    val use: String, val dest: Route
)

object ToolsCatalog {
    val tiles = listOf(
        ToolTile("🩺", "Health Score", "Grade your connection", "e.g. find out if call lag is Wi-Fi or ISP", Route.Health),
        ToolTile("⚡", "Speed Test", "Download / upload", "e.g. verify the new router is really faster", Route.Tool("speed")),
        ToolTile("📈", "Ping Monitor", "Latency graph & jitter", "e.g. watch jitter spike as a call freezes", Route.PingMonitor),
        ToolTile("📊", "Connection Monitor", "Live per-app traffic", "e.g. see which app eats your data", Route.Connections),
        ToolTile("📶", "Wi-Fi Analyzer", "Channels & signal", "e.g. pick a quiet channel for your AP", Route.WifiAnalyzer),
        ToolTile("📡", "Signal Meter", "Live RSSI strength", "e.g. walk each room to find dead zones", Route.Signal),
        ToolTile("🌐", "Net Diag", "Ping · trace · SSDP", "e.g. find the exact hop that fails", Route.NetDiag),
        ToolTile("🔌", "My Ports", "Listening ports", "e.g. spot adb :5555 left open", Route.LocalPorts),
        ToolTile("🕵️", "DNS Sniffer", "See app DNS queries (VPN)", "e.g. catch a tracker phoning home", Route.Sniffer),
        ToolTile("🖥", "SSH Client", "Log into routers", "e.g. reboot OpenWrt from the couch", Route.Ssh),
        ToolTile("🧪", "Raw Probe", "Custom TCP/UDP payloads", "e.g. test a port after a firewall change", Route.Tool("probe")),
        ToolTile("🔎", "Whois / Intel", "RDAP ownership data", "e.g. ID the owner of a suspicious IP", Route.Tool("whois")),
        ToolTile("🌍", "External Ports", "Internet-side scan", "e.g. confirm WAN ports are closed", Route.Tool("extport")),
        ToolTile("📡", "mDNS Discovery", "Cast, AirPlay, printers", "e.g. find the Chromecast's IP", Route.Tool("mdns")),
        ToolTile("🏷", "SNMP Probe", "Device name & model", "e.g. ID the mystery box at .1.23", Route.Tool("snmp")),
        ToolTile("🔒", "TLS Inspector", "Certificate details", "e.g. spot an expired NAS cert", Route.Tool("cert")),
        ToolTile("🛡", "HTTP Audit", "Security headers grade", "e.g. score your self-hosted page", Route.Tool("secaudit")),
        ToolTile("🌐", "DNS Toolkit", "Lookups + resolver speed", "e.g. debug why a domain won't resolve", Route.Tool("dns")),
        ToolTile("🌍", "Public IP", "IP, ISP, gateway info", "e.g. confirm your VPN changed the IP", Route.Tool("netinfo")),
        ToolTile("🧮", "Subnet Calc", "CIDR → ranges", "e.g. split /24 into guest + IoT VLANs", Route.Tool("subnet")),
        ToolTile("📈", "Speed History", "Past results + chart", "e.g. show ISP a week of slow evenings", Route.SpeedHistory),
        ToolTile("📊", "Data Usage", "Per-app daily usage", "e.g. find the app burning your plan", Route.Usage),
        ToolTile("🐺", "Wake-on-LAN", "Saved wake profiles", "e.g. wake the NAS before backup", Route.Wol),
        ToolTile("📋", "Logs", "Crash & event diagnostics", "e.g. attach it when reporting a bug", Route.Logs)
    )

    data class ToolDef(
        val id: String, val title: String, val hint: String,
        val inputless: Boolean, val autorun: Boolean
    )

    val tools: Map<String, ToolDef> = mapOf(
        "mdns" to ToolDef("mdns", "mDNS Discovery", "", true, true),
        "snmp" to ToolDef("snmp", "SNMP Probe", "Device IP (v1 public)", false, false),
        "cert" to ToolDef("cert", "TLS Cert Inspector", "host:port e.g. 192.168.1.1:443", false, false),
        "secaudit" to ToolDef("secaudit", "HTTP Security Audit", "http://device:port", false, false),
        "dns" to ToolDef("dns", "DNS Toolkit", "domain e.g. google.com", false, false),
        "netinfo" to ToolDef("netinfo", "Public IP", "", true, true),
        "subnet" to ToolDef("subnet", "Subnet Calculator", "e.g. 192.168.1.0/24", false, false),
        "speed" to ToolDef("speed", "Speed Test", "", true, true),
        "extport" to ToolDef("extport", "External Ports", "", true, true),
        "probe" to ToolDef("probe", "Raw Probe", "host:port [payload]", false, false),
        "whois" to ToolDef("whois", "Whois / Intel", "example.com or 1.1.1.1", false, false),
        "cameras" to ToolDef("cameras", "Camera Finder", "prefix (blank = this net)", false, false),
        "dnshijack" to ToolDef("dnshijack", "DNS Hijack Detector", "", true, true),
        "httpforge" to ToolDef("httpforge", "HTTP Forge", "METHOD url | Header: value | body", false, false)
    )

    /** Dispatch a tool run on a worker thread; output streams via [onLog]. */
    fun run(def: ToolDef, input: String, onLog: (String) -> Unit, onRate: (Float, Boolean) -> Unit = { _, _ -> }) {
        Thread {
            try {
                val ctx = ToolEngine.appCtx
                when (def.id) {
                    "mdns" -> ctx?.let { ToolEngine.runMdns(it, onLog) }
                    "snmp" -> ToolEngine.runSnmp(input, onLog)
                    "cert" -> ToolEngine.runCert(input, onLog)
                    "secaudit" -> ToolEngine.runSecAudit(input, onLog)
                    "dns" -> ToolEngine.runDns(input, onLog)
                    "netinfo" -> ctx?.let { ToolEngine.runNetInfo(it, onLog) }
                    "subnet" -> ToolEngine.runSubnet(input, onLog)
                    "speed" -> ToolEngine.runSpeed(onLog, onRate)
                    "extport" -> ToolEngine.runExtPort(onLog)
                    "probe" -> ToolEngine.runProbe(input, onLog)
                    "whois" -> ToolEngine.runWhois(input, onLog)
                    "cameras" -> ctx?.let { ToolEngine.runCameras(it, input, onLog) }
                    "dnshijack" -> ToolEngine.runDnsHijack(onLog)
                    "httpforge" -> ToolEngine.runForge(input, onLog)
                }
            } catch (e: Throwable) {
                AppLog.log("tool '${def.id}' fatal: $e")
                onLog("❌ $e")
            }
        }.start()
    }
}

/** Tools hub — 2-column frosted tile grid (port of activity_tools_hub). */
@Composable
fun ToolsScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    val accents = listOf(
        p.accent, GlassColors.Good, GlassColors.Violet, GlassColors.Bad,
        GlassColors.Blue, GlassColors.Pink, GlassColors.Warn, p.accent
    )
    GlassScreen(title = "Toolbox", nav = nav) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ToolsCatalog.tiles.chunked(2).forEachIndexed { rowIdx, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { colIdx, tile ->
                        val accent = accents[(rowIdx * 2 + colIdx) % accents.size]
                        LiquidGlassCard(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.1f)
                                .clickable { nav.push(tile.dest) },
                            cornerRadius = 20.dp
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Column(Modifier.align(Alignment.TopStart)) {
                                    Text(tile.emoji, fontSize = 20.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        tile.title,
                                        color = p.text,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(tile.sub, color = p.dim, fontSize = 11.sp, maxLines = 1)
                                    Spacer(Modifier.height(6.dp))
                                    UseChip(tile.use)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Generic tool runner screen (port of ToolRunnerActivity, incl. live speed gauges). */
@Composable
fun ToolRunScreen(nav: Navigator, id: String) {
    val def = ToolsCatalog.tools[id] ?: return
    val p = LocalGlassPalette.current
    val lines = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var gaugeDown by remember { mutableStateOf(Float.NaN) }
    var gaugeUp by remember { mutableStateOf(Float.NaN) }
    val listState = rememberLazyListState()

    fun log(s: String) {
        AppLog.log(s)
        lines.add(s)
    }

    fun run() {
        if (running) return
        lines.clear()
        gaugeDown = Float.NaN
        gaugeUp = Float.NaN
        running = true
        ToolsCatalog.run(def, input.trim(), { s -> log(s) }, { mbps, down ->
            if (down) gaugeDown = mbps else gaugeUp = mbps
        })
        running = false // tools stream async; UI log keeps appending
    }

    // Auto-run for input-less tools (legacy onCreate behavior)
    LaunchedEffect(def.id) {
        if (def.autorun) run()
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    GlassScreen(title = def.title, nav = nav) {
        Column(Modifier.fillMaxSize()) {
            // v5.1.1: show the tool's real-life example on the run screen too
            ToolsCatalog.tiles.firstOrNull { t -> (t.dest as? Route.Tool)?.id == id }?.let { t ->
                GlassDesc(t.use)
                Spacer(Modifier.height(8.dp))
            }
            if (id == "speed") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiquidGlassCard(Modifier.weight(1f), cornerRadius = 20.dp) {
                        Box(Modifier.fillMaxWidth().height(120.dp)) {
                            com.netscanner.ui.charts.GaugeArc(
                                if (gaugeDown.isNaN()) 0f else gaugeDown,
                                1000f, "DOWN", GlassColors.Accent,
                                Modifier.fillMaxSize()
                            )
                        }
                    }
                    LiquidGlassCard(Modifier.weight(1f), cornerRadius = 20.dp) {
                        Box(Modifier.fillMaxWidth().height(120.dp)) {
                            com.netscanner.ui.charts.GaugeArc(
                                if (gaugeUp.isNaN()) 0f else gaugeUp,
                                1000f, "UP", GlassColors.Violet,
                                Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            if (!def.inputless) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(def.hint.ifEmpty { "input" }, color = p.faint, fontSize = 13.sp)
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = p.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = p.accent,
                        unfocusedBorderColor = p.faint.copy(alpha = 0.5f),
                        cursorColor = p.accent
                    ),
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = { run() },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = p.accent.copy(alpha = 0.85f),
                    contentColor = androidx.compose.ui.graphics.Color(0xFF04222A)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (def.autorun) "Run again" else "Run", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))

            LiquidGlassCard(Modifier.weight(1f), cornerRadius = 18.dp) {
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(lines) { ln ->
                            Text(
                                ln,
                                color = p.text,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
