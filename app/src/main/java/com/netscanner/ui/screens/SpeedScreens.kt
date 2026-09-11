package com.netscanner.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
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
import com.netscanner.core.Stores
import com.netscanner.core.ToolEngine
import com.netscanner.nav.Navigator
import com.netscanner.svc.AutoSpeedService
import com.netscanner.ui.charts.DualLineChart
import com.netscanner.ui.glass.GlassButton
import com.netscanner.ui.glass.GlassChip
import com.netscanner.ui.glass.GlassDesc
import com.netscanner.ui.glass.GlassScreen
import com.netscanner.ui.glass.KV
import com.netscanner.ui.glass.LiquidGlassCard
import com.netscanner.ui.theme.LocalGlassPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────── Auto Speed ───────────────────────────

/** Legacy AutoSpeedActivity: periodic background speed tests (foreground service). */
@Composable
fun AutoSpeedScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val p = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(AutoSpeedService.running) }
    var night by remember { mutableStateOf(Stores.nightSpeed(ctx)) }
    var interval by remember { mutableStateOf(AutoSpeedService.intervalMin(ctx)) }
    var testing by remember { mutableStateOf(AutoSpeedService.testing.get()) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        running = AutoSpeedService.running
        interval = AutoSpeedService.intervalMin(ctx)
        testing = AutoSpeedService.testing.get()
    }
    val hist = remember(refresh) { readAutoHist(ctx) }
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    GlassScreen("Auto Speed Test", nav) {
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            Text("Status", color = p.dim, fontSize = 12.sp)
            Text(
                if (running) "Running — testing every ${interval} min" else "Stopped",
                color = if (running) p.good else p.dim, fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
            if (AutoSpeedService.lastDoneTs > 0) {
                Spacer(Modifier.height(6.dp))
                KV(
                    "Last test",
                    "↓${AutoSpeedService.lastDownX10 / 10.0} / ↑${AutoSpeedService.lastUpX10 / 10.0} Mbps  ·  " +
                        fmt.format(Date(AutoSpeedService.lastDoneTs))
                )
                if (AutoSpeedService.lastErr.isNotEmpty()) KV("Error", AutoSpeedService.lastErr)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Interval", color = p.dim, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 15, 30, 60).forEach { m ->
                GlassChip(Modifier.clickable {
                    Stores.prefs(ctx).edit().putLong("auto_interval_min", m.toLong()).apply()
                    interval = m.toLong(); refresh++
                }.padding(1.dp)) {
                    Text(
                        "${m}m", fontSize = 13.sp,
                        color = if (interval == m.toLong()) p.accent else p.dim,
                        fontWeight = if (interval == m.toLong()) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(
                if (running) "Stop service" else "Start service",
                {
                    if (running) AutoSpeedService.stop(ctx) else AutoSpeedService.start(ctx)
                    running = !running; refresh++
                },
                accent = !running
            )
            GlassButton(if (testing) "Testing…" else "Test now", {
                scope.launch {
                    withContext(Dispatchers.IO) { AutoSpeedService.runOnce(ctx) }
                    refresh++
                }
            }, enabled = !testing)
            GlassChip(Modifier.clickable {
                Stores.setNightSpeed(ctx, !night); night = !night
            }.padding(2.dp)) {
                Text(
                    if (night) "Night: ON" else "Night: OFF",
                    color = if (night) p.accent else p.dim, fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("History (auto_hist)", color = p.dim, fontSize = 12.sp)
        LazyColumn(Modifier.weight(1f)) {
            items(hist) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 6.dp)) {
                    Text(fmt.format(Date(e.optLong("ts"))), color = p.faint, fontSize = 12.sp,
                        modifier = Modifier.width(110.dp))
                    Text(
                        "↓${e.optDouble("down", 0.0)}  ↑${e.optDouble("up", 0.0)} Mbps",
                        color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Export CSV", {
                val csv = StringBuilder("ts,down_mbps,up_mbps\n")
                hist.forEach { e ->
                    csv.append(e.optLong("ts")).append(',')
                        .append(e.optDouble("down", 0.0)).append(',')
                        .append(e.optDouble("up", 0.0)).append('\n')
                }
                ToolEngine.exportCsv(ctx, "auto_speed_hist.csv", csv.toString())
            })
            GlassButton("Clear", {
                Stores.prefs(ctx).edit().putString("auto_hist", "[]").apply(); refresh++
            })
        }
    }
}

private fun readAutoHist(ctx: Context): List<JSONObject> = try {
    val arr = JSONArray(Stores.prefs(ctx).getString("auto_hist", "[]") ?: "[]")
    (0 until arr.length()).map { arr.getJSONObject(it) }
} catch (_: Exception) { emptyList() }

// ─────────────────────────── Speed History ───────────────────────────

/** Manual speed-test history (speed_hist), shared with the tool screen. */
@Composable
fun SpeedHistoryScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val p = LocalGlassPalette.current
    var hist by remember { mutableStateOf(Stores.speedHistory(ctx)) }
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    GlassScreen("Speed History", nav) {
        if (hist.isNotEmpty()) {
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                DualLineChart(
                    hist.take(60).mapIndexed { i, e ->
                        Triple(i.toFloat(), e.down.toFloat(), e.up.toFloat())
                    },
                    Modifier.fillMaxWidth().height(110.dp)
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    GlassChip { Text("● down", color = p.accent, fontSize = 11.sp) }
                    Spacer(Modifier.width(8.dp))
                    GlassChip { Text("● up", color = p.violet, fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(hist) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 6.dp)) {
                    Text(fmt.format(Date(e.ts)), color = p.faint, fontSize = 12.sp,
                        modifier = Modifier.width(140.dp))
                    Text(
                        String.format(Locale.US, "↓ %.1f", e.down),
                        color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        String.format(Locale.US, "↑ %.1f Mbps", e.up),
                        color = p.violet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Export CSV", {
                val csv = StringBuilder("ts,down_mbps,up_mbps\n")
                hist.forEach { csv.append(it.ts).append(',').append(it.down).append(',').append(it.up).append('\n') }
                ToolEngine.exportCsv(ctx, "speed_hist.csv", csv.toString())
            })
            GlassButton("Clear", {
                Stores.prefs(ctx).edit().putString("speed_hist", "[]").apply()
                hist = Stores.speedHistory(ctx)
            })
        }
    }
}

// ─────────────────────────── DNS Tester ───────────────────────────

/** Legacy DnsTesterActivity: probe known resolvers, show the Private DNS command. */
@Composable
fun DnsTesterScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val servers = listOf(
        "Cloudflare" to "1dot1dot1dot1.cloudflare-dns.com",
        "Cloudflare Family" to "family.cloudflare-dns.com",
        "Google" to "dns.google",
        "Quad9" to "dns.quad9.net",
        "Quad9 Unsecured" to "dns9.quad9.net",
        "AdGuard" to "dns.adguard.com",
        "AdGuard Family" to "family.adguard-dns.com",
        "AdGuard Unfiltered" to "unfiltered.adguard-dns.com",
        "CleanBrowsing Security" to "security-filter-dns.cleanbrowsing.org",
        "CleanBrowsing Family" to "family-filter-dns.cleanbrowsing.org",
        "CleanBrowsing Adult" to "adult-filter-dns.cleanbrowsing.org",
        "Cisco OpenDNS" to "doh.opendns.com",
        "Cisco OpenDNS Family" to "doh.familyshield.opendns.com",
        "NextDNS" to "dns.nextdns.io",
        "ControlD" to "freedns.controld.com",
        "Mullvad" to "dns.mullvad.net",
        "dns0.eu" to "dns0.eu",
        "dns0.eu Open" to "open.dns0.eu",
        "dns0.eu Kids" to "kids.dns0.eu",
        "CIRA Shield" to "canadianshield.cira.ca",
        "DNS.SB" to "dns.sb",
        "Yandex" to "common.dot.dns.yandex.net"
    )
    data class Res(val name: String, val host: String, val ms: Int?, val ip: String)
    var results by remember { mutableStateOf(listOf<Res>()) }
    var testing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    fun probe() {
        testing = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                servers.map { (name, host) ->
                    val t0 = System.currentTimeMillis()
                    var ms: Int? = null
                    var ip = "?"
                    try {
                        val addrs = java.net.InetAddress.getAllByName(host)
                        ip = addrs.firstOrNull()?.hostAddress ?: "?"
                        ms = (System.currentTimeMillis() - t0).toInt()
                    } catch (_: Exception) {}
                    Res(name, host, ms, ip)
                }.sortedBy { it.ms ?: Int.MAX_VALUE }
            }
            results = out
            testing = false
        }
    }
    LaunchedEffect(Unit) { probe() }

    GlassScreen("DNS Tester", nav, actions = {
        GlassButton("Re-probe", { probe() }, enabled = !testing, accent = true)
    }) {
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            GlassDesc(
                "Probes ${servers.size} public resolvers and ranks by DNS resolution " +
                    "latency. Tap a row to reveal the adb command that sets it as " +
                    "Android Private DNS — the first step when web pages load slowly " +
                    "but streaming works fine."
            )
            if (note.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(note, color = p.accent, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(results) { r ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 6.dp)
                        .clickable {
                            note = "adb shell settings put global private_dns_hostname ${r.host}"
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(r.host, color = p.faint, fontSize = 11.sp)
                    }
                    Text(
                        r.ms?.let { "${it} ms" } ?: "fail",
                        color = when {
                            r.ms == null -> p.bad
                            r.ms < 80 -> p.good
                            r.ms < 250 -> p.warn
                            else -> p.text
                        },
                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
