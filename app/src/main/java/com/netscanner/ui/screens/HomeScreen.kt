package com.netscanner.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.netscanner.core.NetUtils
import com.netscanner.nav.Navigator
import com.netscanner.nav.Route
import com.netscanner.ui.glass.GlassPill
import com.netscanner.ui.glass.LiquidGlassCard
import com.netscanner.ui.theme.GlassColors
import com.netscanner.ui.theme.LocalGlassPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dashboard tiles — exact port of the legacy MainActivity item list. */
data class HomeTile(
    val emoji: String, val title: String, val sub: String,
    val use: String, val tintIdx: Int, val dest: Route
)

private val TINTS = intArrayOf(
    0xFF00D4FF.toInt(), 0xFF00A3FF.toInt(), 0xFF00F5FF.toInt(), 0xFF00FF88.toInt(),
    0xFF00FFD1.toInt(), 0xFFFFD400.toInt(), 0xFFFF7A00.toInt(), 0xFFB44CFF.toInt(),
    0xFFFF4FD8.toInt(), 0xFFFF2A6D.toInt(), 0xFF7EC8FF.toInt()
)

private val HOME_TILES = listOf(
    HomeTile("🔍", "Scan Network", "Find devices on your LAN", "e.g. ID the unknown device next door", 0, Route.Scan),
    HomeTile("🩺", "Health Score", "Grade your connection", "e.g. blame the ISP before the router", 3, Route.Health),
    HomeTile("⚡", "Speed Test", "Download / upload", "e.g. verify the new router is faster", 5, Route.Tool("speed")),
    HomeTile("📈", "Ping Monitor", "Latency graph & jitter", "e.g. see when a call freezes", 1, Route.PingMonitor),
    HomeTile("🎯", "Multi-Ping", "Track several hosts live", "e.g. watch router + NAS + TV at once", 2, Route.MultiPing),
    HomeTile("⏱", "Auto Speed", "Interval tests + full log", "e.g. prove evening slowdowns to the ISP", 6, Route.AutoSpeed),
    HomeTile("📊", "Connection Monitor", "Live per-app traffic", "e.g. see which app eats your data", 7, Route.Connections),
    HomeTile("📶", "Wi-Fi Analyzer", "Channels & signal", "e.g. pick a quiet channel", 1, Route.WifiAnalyzer),
    HomeTile("📡", "Signal Meter", "Live RSSI strength", "e.g. find Wi-Fi dead zones room by room", 3, Route.Signal),
    HomeTile("🗼", "Cell Monitor", "Towers · RSRP · neighbors", "e.g. find the window with best signal", 7, Route.CellMonitor),
    HomeTile("🧭", "DNS Tester", "Rank public DNS · set Private DNS", "e.g. fix slow page loads via faster DNS", 1, Route.DnsTester),
    HomeTile("🌐", "Net Diag", "Ping · trace · SSDP", "e.g. find the exact hop that fails", 2, Route.NetDiag),
    HomeTile("🔌", "My Ports", "Listening ports", "e.g. spot adb :5555 left open", 0, Route.LocalPorts),
    HomeTile("🕵️", "DNS Sniffer", "See app DNS queries", "e.g. catch a tracker phoning home", 9, Route.Sniffer),
    HomeTile("🖥", "SSH Client", "Log into routers", "e.g. reboot OpenWrt from the couch", 8, Route.Ssh),
    HomeTile("🧪", "Raw Probe", "Custom TCP/UDP payloads", "e.g. test a port after firewall change", 5, Route.Tool("probe")),
    HomeTile("🎥", "Camera Finder", "Find RTSP cams on LAN", "e.g. locate the baby-cam's IP", 9, Route.Tool("cameras")),
    HomeTile("🛡", "DNS Hijack Test", "Catch DNS tampering", "e.g. detect router-level DNS tampering", 9, Route.Tool("dnshijack")),
    HomeTile("⚒", "HTTP Forge", "Craft raw requests", "e.g. replay a login request safely", 8, Route.Tool("httpforge")),
    HomeTile("🔎", "Whois / Intel", "RDAP ownership data", "e.g. ID the owner of a suspicious IP", 1, Route.Tool("whois")),
    HomeTile("🌍", "External Ports", "Internet-side scan", "e.g. confirm WAN ports are closed", 9, Route.Tool("extport")),
    HomeTile("📡", "mDNS Discovery", "Cast, AirPlay, printers", "e.g. find the Chromecast's IP", 0, Route.Tool("mdns")),
    HomeTile("🏷", "SNMP Probe", "Device name & model", "e.g. ID the mystery box at .1.23", 2, Route.Tool("snmp")),
    HomeTile("🔒", "TLS Inspector", "Certificate details", "e.g. spot an expired NAS cert", 9, Route.Tool("cert")),
    HomeTile("🛡", "HTTP Audit", "Security headers grade", "e.g. score your self-hosted page", 9, Route.Tool("secaudit")),
    HomeTile("🌐", "DNS Toolkit", "Lookups + resolver speed", "e.g. debug why a domain won't resolve", 1, Route.Tool("dns")),
    HomeTile("🌍", "Public IP", "IP, ISP, gateway info", "e.g. confirm your VPN changed the IP", 0, Route.Tool("netinfo")),
    HomeTile("🧮", "Subnet Calc", "CIDR → ranges", "e.g. split /24 into guest + IoT VLANs", 4, Route.Tool("subnet")),
    HomeTile("📈", "Speed History", "Past results + chart", "e.g. show ISP a week of slow evenings", 3, Route.SpeedHistory),
    HomeTile("📊", "Data Usage", "Per-app daily usage", "e.g. find the app burning your plan", 7, Route.Usage),
    HomeTile("🐺", "Wake-on-LAN", "Saved wake profiles", "e.g. wake the NAS before backup", 8, Route.Wol),
    HomeTile("🕘", "Scan History", "Past scans", "e.g. re-check devices found last week", 1, Route.History),
    HomeTile("📋", "Logs", "Crash & event diagnostics", "e.g. attach it when reporting a bug", 10, Route.Logs),
    HomeTile("🧰", "Toolbox", "All 24 tools in one hub", "e.g. jump straight to any tool", 2, Route.Tools)
)

/** Circular frosted tinted glyph chip (port of Ui.styleChip). */
@Composable
private fun TintChip(emoji: String, tintIdx: Int) {
    val tint = Color(TINTS[tintIdx.coerceIn(0, TINTS.size - 1)])
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        tint.copy(alpha = 0.30f),
                        tint.copy(alpha = 0.09f)
                    )
                )
            )
            .border(1.dp, Color(0x4D00F5FF), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 20.sp)
    }
}

/** Live connectivity status pill (port of applyNetworkStatus). */
private fun computeStatus(ctx: Context): Pair<Color, String> = try {
    var wifi = false
    var cell = false
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (cm != null) {
        val nw = cm.activeNetwork
        val caps = if (nw == null) null else cm.getNetworkCapabilities(nw)
        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            cell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
    }
    val n = NetUtils.localNet()

    var ssid = ""
    if (wifi) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wi = wm?.connectionInfo
        if (wi != null && wi.ssid != null) ssid = wi.ssid.replace("\"", "").trim()
        if (ssid.isEmpty() || ssid.contains("unknown") || ssid.contains("<")) ssid = "Wi-Fi"
    }

    if (n != null && n.ip.isNotEmpty()) {
        val c = if (wifi || cell) GlassColors.Good else GlassColors.Warn
        val label = if (wifi) ssid else if (cell) "Mobile data" else "Connected"
        Pair(c, "$label  ·  ${n.ip}")
    } else if (cell) {
        Pair(GlassColors.Good, "Mobile data")
    } else if (wifi) {
        Pair(GlassColors.Warn, "$ssid  ·  no address")
    } else {
        Pair(GlassColors.Bad, "Offline")
    }
} catch (t: Throwable) {
    Pair(GlassColors.Bad, "Offline")
}

/** Home dashboard — status pill + 2-column glass tile grid. */
@Composable
fun HomeScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    val ctx = LocalContext.current

    // POST_NOTIFICATIONS on 33+ (legacy MainActivity behavior)
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var status by remember { mutableStateOf<Pair<Color, String>?>(null) }
    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.IO) { computeStatus(ctx) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "NetScanner",
                color = p.text,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            GlassPill(Modifier.clickable { nav.push(Route.Settings) }) {
                Text("⚙", color = p.text, fontSize = 15.sp)
                Spacer(Modifier.size(6.dp))
                Text(
                    "v5.1.0",
                    color = p.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        GlassPill(Modifier.fillMaxWidth()) {
            val s = status
            if (s == null) {
                Text("Checking network…", color = p.faint, fontSize = 13.sp)
            } else {
                Text("●", color = s.first, fontSize = 14.sp)
                Spacer(Modifier.size(8.dp))
                Text(s.second, color = p.text, fontSize = 13.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            HOME_TILES.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { tile ->
                        LiquidGlassCard(
                            modifier = Modifier
                                .weight(1f)
                                .height(158.dp)
                                .clickable { nav.push(tile.dest) },
                            cornerRadius = 22.dp
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Column(Modifier.align(Alignment.TopStart)) {
                                    TintChip(tile.emoji, tile.tintIdx)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        tile.title,
                                        color = p.text,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(
                                        tile.sub,
                                        color = p.dim,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        tile.use,
                                        color = p.faint,
                                        fontSize = 10.sp,
                                        lineHeight = 12.sp,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
