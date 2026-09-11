package com.netscanner.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netscanner.core.AppLog
import com.netscanner.core.Stores
import com.netscanner.nav.Navigator
import com.netscanner.ui.charts.BarsChart
import com.netscanner.ui.charts.SignalBars
import com.netscanner.ui.glass.GlassChip
import com.netscanner.ui.glass.GlassDesc
import com.netscanner.ui.glass.GlassScreen
import com.netscanner.ui.glass.GlassTextField
import com.netscanner.ui.glass.KV
import com.netscanner.ui.glass.LiquidGlassCard
import com.netscanner.ui.glass.SectionTitle
import com.netscanner.ui.theme.LocalGlassPalette
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

// ─────────────────────────── Signal Meter ───────────────────────────

/** Legacy SignalActivity: live Wi-Fi dBm meter, 1 s poll. */
@Composable
fun SignalScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val p = LocalGlassPalette.current
    var rssi by remember { mutableIntStateOf(-100) }
    var ssid by remember { mutableStateOf("?") }
    var bssid by remember { mutableStateOf("?") }
    var link by remember { mutableStateOf("?") }
    var freq by remember { mutableStateOf("?") }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wi = wm.connectionInfo
                rssi = wi.rssi
                ssid = wi.ssid?.replace("\"", "") ?: "?"
                bssid = wi.bssid ?: "?"
                link = "${wi.linkSpeed} Mbps"
                freq = if (wi.frequency > 4900) "5/6 GHz (${wi.frequency} MHz)" else "2.4 GHz (${wi.frequency} MHz)"
            } catch (_: Exception) {}
            delay(1000)
        }
    }
    val pct = ((rssi + 100) * 2).coerceIn(0, 100)
    val bars = when { pct > 75 -> 4; pct > 50 -> 3; pct > 25 -> 2; else -> 1 }
    val color = when { pct > 60 -> p.good; pct > 35 -> p.warn; else -> p.bad }

    GlassScreen("Signal Meter", nav) {
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalBars(bars, Modifier.height(24.dp), color)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("$rssi dBm", color = color, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("$pct%  ·  $bars/4 bars", color = p.dim, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            KV("SSID", ssid)
            KV("BSSID", bssid)
            KV("Link speed", link)
            KV("Frequency", freq)
        }
        Spacer(Modifier.height(10.dp))
        GlassDesc(
            "Walk around the house and watch the meter — the reading refreshes every " +
                "second. Find the spot where your Wi-Fi drops below 3 bars."
        )
    }
}

// ─────────────────────────── Wi-Fi Analyzer ───────────────────────────

private fun freqToChannel(f: Int): Int = when {
    f in 2412..2472 -> (f - 2412) / 5 + 1
    f == 2484 -> 14
    f in 5915..7115 -> (f - 5950) / 5
    f > 4900 -> (f - 5000) / 5
    else -> 0
}

/** Legacy WifiAnalyzerActivity: live AP list from scan results, 3 s refresh. */
@Composable
fun WifiAnalyzerScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val p = LocalGlassPalette.current
    data class Ap(val ssid: String, val bssid: String, val ch: Int, val level: Int, val sec: String)
    val aps = remember { mutableStateListOf<Ap>() }
    var selected by remember { mutableStateOf<Ap?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val list = wm.scanResults.sortedByDescending { it.level }
                aps.clear()
                list.take(60).forEach { r ->
                    aps.add(Ap(
                        r.SSID.ifEmpty { "(hidden)" }, r.BSSID,
                        freqToChannel(r.frequency), r.level,
                        r.capabilities.split("]").firstOrNull()?.removePrefix("[") ?: ""))
                }
            } catch (_: Exception) {}
            delay(3000)
        }
    }
    Column(Modifier) {
        GlassScreen("Wi-Fi Analyzer", nav, actions = {
            GlassChip { Text("${aps.size} APs", color = p.dim, fontSize = 12.sp) }
        }) {
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                GlassDesc(
                    "APs refresh every 3 s. Location permission + location services " +
                        "must be ON for Android to return scan results. Look for a quiet " +
                        "channel before buying a mesh node or switching your router."
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(aps, key = { it.bssid }) { ap ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selected = ap }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SignalBars(
                            when { ap.level >= -55 -> 4; ap.level >= -67 -> 3; ap.level >= -75 -> 2; else -> 1 },
                            Modifier.height(16.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ap.ssid, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "ch ${ap.ch} · ${ap.sec}", color = p.faint, fontSize = 11.sp
                            )
                        }
                        Text(
                            "${ap.level} dBm", color = p.dim, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
    selected?.let { ap ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { selected = null }) {
                    Text("Close", color = p.accent)
                }
            },
            title = { Text(ap.ssid, color = p.text) },
            text = {
                Column {
                    KV("BSSID", ap.bssid)
                    KV("Channel", ap.ch.toString())
                    KV("Level", "${ap.level} dBm")
                    KV("Security", ap.sec)
                }
            },
            containerColor = p.cardFill.copy(alpha = 1f)
        )
    }
}

// ─────────────────────────── Data Usage ───────────────────────────

private fun humanBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    else -> "$b B"
}

/** Legacy UsageActivity: per-app data usage today via TrafficStats UID deltas. */
@Composable
fun UsageScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val p = LocalGlassPalette.current
    data class AppUsage(val label: String, val rx: Long, val tx: Long)
    var apps by remember { mutableStateOf(listOf<AppUsage>()) }
    var total by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        val day = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date())
        val sp = Stores.prefs(ctx)
        if (sp.getString("usage_day", "") != day) {
            sp.edit().putString("usage_day", day).putString("usage_today", "{}").apply()
        }
        while (true) {
            try {
                val pm = ctx.packageManager
                val today = JSONObject(sp.getString("usage_today", "{}") ?: "{}")
                val snap = JSONObject(sp.getString("usage_snap", "{}") ?: "{}")
                val nowSnap = JSONObject()
                val pm2 = pm.getInstalledPackages(0)
                for (pi in pm2) {
                    val uid = pi.applicationInfo?.uid ?: continue
                    val rx = TrafficStats.getUidRxBytes(uid)
                    val tx = TrafficStats.getUidTxBytes(uid)
                    nowSnap.put(uid.toString(), JSONObject()
                        .put("rx", rx).put("tx", tx))
                    val prev = snap.optJSONObject(uid.toString())
                    if (prev != null) {
                        val drx = rx - prev.optLong("rx", rx)
                        val dtx = tx - prev.optLong("tx", tx)
                        if (drx > 0 || dtx > 0) {
                            today.put(uid.toString(), today.optLong(uid.toString(), 0) + drx + dtx)
                        }
                    }
                }
                sp.edit().putString("usage_snap", nowSnap.toString())
                    .putString("usage_today", today.toString()).apply()

                val list = mutableListOf<AppUsage>()
                var sum = 0L
                for (key in today.keys()) {
                    val b = today.optLong(key, 0)
                    if (b <= 0) continue
                    sum += b
                    val label = try {
                        val uids = pm.getPackagesForUid(key.toInt())
                        if (uids != null && uids.isNotEmpty()) {
                            val ai = pm.getApplicationInfo(uids[0], 0)
                            pm.getApplicationLabel(ai).toString()
                        } else "uid $key"
                    } catch (_: Exception) { "uid $key" }
                    list.add(AppUsage(label, b, 0))
                }
                apps = list.sortedByDescending { it.rx }.take(20)
                total = sum
            } catch (_: Exception) {}
            delay(5000)
        }
    }
    GlassScreen("Data Usage", nav) {
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            Text("Today", color = p.dim, fontSize = 12.sp)
            Text(humanBytes(total), color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Per-app usage since midnight, measured with TrafficStats deltas " +
                    "(device-wide counters; resets on reboot).",
                color = p.faint, fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            BarsChart(
                apps.map { it.label to it.rx.toFloat() },
                Modifier.fillMaxWidth(),
                color = p.accent,
                valueFmt = { humanBytes(it.toLong()) }
            )
        }
    }
}

@Composable
private fun mutableLongStateOfCompat(v: Long): androidx.compose.runtime.MutableState<Long> =
    remember { mutableStateOf(v) }

// ─────────────────────────── Connections ───────────────────────────

private data class Conn(
    val proto: String, val local: String, val remote: String,
    val state: String, val app: String
)

private fun hexIp(h: String, v6: Boolean): String = try {
    if (!v6) {
        val b = ByteArray(4)
        for (i in 0..3) b[i] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        val ip = java.net.InetAddress.getByAddress(b).hostAddress ?: h
        // /proc stores little-endian for v4
        val le = b.reversedArray()
        java.net.InetAddress.getByAddress(le).hostAddress ?: ip
    } else {
        val b = ByteArray(16)
        for (i in 0..15) b[i] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        java.net.InetAddress.getByAddress(b).hostAddress ?: h
    }
} catch (_: Exception) { h }

private fun hexPort(h: String) = h.toInt(16)

private fun parseProcFile(path: String, proto: String, out: MutableList<Conn>) {
    try {
        val lines = java.io.File(path).readLines()
        for (i in 1 until lines.size) {
            val cols = lines[i].trim().split(Regex("\\s+"))
            if (cols.size < 10) continue
            val la = cols[1].split(":")
            val ra = cols[2].split(":")
            if (la.size < 2 || ra.size < 2) continue
            val v6 = path.endsWith("6")
            val state = if (proto.startsWith("TCP")) {
                when (cols[3]) {
                    "01" -> "ESTABLISHED"; "02" -> "SYN_SENT"; "03" -> "SYN_RECV"
                    "04" -> "FIN_WAIT1"; "05" -> "FIN_WAIT2"; "06" -> "TIME_WAIT"
                    "07" -> "CLOSE"; "08" -> "CLOSE_WAIT"; "0A" -> "LISTEN"
                    else -> cols[3]
                }
            } else if (cols[3] == "07" || cols[3] == "FFFFFFFF") "-" else "—"
            out.add(Conn(proto, "${hexIp(la[0], v6)}:${hexPort(la[1])}",
                "${hexIp(ra[0], v6)}:${hexPort(ra[1])}", state, "uid:${cols[7]}"))
        }
    } catch (_: Exception) {}
}

// v5.1.0 fix: Android 10+ SELinux often hides /proc/net entries, leaving the
// table empty. Fall back to the toybox `netstat` binary which returns the
// same socket table for our own UID.
private fun parseNetstat(out: MutableList<Conn>) {
    try {
        val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/netstat", "-tun"))
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        val lines = text.lines()
        for (i in 1 until lines.size) {
            val cols = lines[i].trim().split(Regex("\\s+"))
            if (cols.size < 5) continue
            val proto = cols[0].uppercase(Locale.US)
            if (proto != "TCP" && proto != "UDP") continue
            val local = cols[3]
            val remote = cols[4]
            val state = if (proto == "UDP") "-" else cols.getOrElse(5) { "-" }
            out.add(Conn(proto, local, remote, state, "uid:0"))
        }
    } catch (_: Exception) {}
}

private fun collectConns(out: MutableList<Conn>) {
    parseProcFile("/proc/net/tcp", "TCP", out)
    parseProcFile("/proc/net/tcp6", "TCP6", out)
    parseProcFile("/proc/net/udp", "UDP", out)
    parseProcFile("/proc/net/udp6", "UDP6", out)
    if (out.isEmpty()) parseNetstat(out)
}

/** Legacy ConnectionsActivity: live TCP/UDP table with app mapping. */
@Composable
fun ConnectionsScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val p = LocalGlassPalette.current
    var conns by remember { mutableStateOf(listOf<Conn>()) }
    var filter by remember { mutableStateOf("all") }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        while (true) {
            val list = mutableListOf<Conn>()
            collectConns(list)
            // map uid → app label
            val uidCache = HashMap<String, String>()
            conns = list.map { c ->
                val uid = c.app.removePrefix("uid:")
                val app = uidCache.getOrPut(uid) {
                    try {
                        val names = ctx.packageManager.getPackagesForUid(uid.toInt())
                        if (names != null && names.isNotEmpty()) {
                            try {
                                ctx.packageManager.getApplicationLabel(
                                    ctx.packageManager.getApplicationInfo(names[0], 0)).toString()
                            } catch (_: Exception) { names[0] }
                        } else "uid $uid"
                    } catch (_: Exception) { "uid $uid" }
                }
                c.copy(app = app)
            }
            delay(2000)
        }
    }
    GlassScreen("Connections", nav, actions = {
        GlassChip(Modifier.clickable { refreshTick++ }) { Text("Refresh", color = p.accent, fontSize = 12.sp) }
    }) {
        GlassDesc(
            "Live socket table for apps on this phone. Open your mail app, come back, " +
                "and watch its connections appear — great for spotting chatty apps."
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all", "TCP", "UDP").forEach { f ->
                GlassChip(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { filter = f }
                        .padding(2.dp)
                ) {
                    Text(
                        f, color = if (filter == f) p.accent else p.dim,
                        fontSize = 12.sp, fontWeight = if (filter == f) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            GlassChip { Text("${conns.size} sockets", color = p.dim, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(conns.filter {
                filter == "all" || it.proto.startsWith(filter, ignoreCase = true)
            }) { c ->
                val established = c.state == "ESTABLISHED"
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${c.proto}  ${c.local} → ${c.remote}",
                            color = p.text, fontSize = 12.sp
                        )
                        Text("${c.app} · ${c.state}", color = p.faint, fontSize = 11.sp)
                    }
                    if (established) {
                        androidx.compose.foundation.Canvas(Modifier.width(8.dp).height(8.dp)) {
                            drawCircle(p.good)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Local Ports ───────────────────────────

private val PORT_NAMES = mapOf(
    22 to "ssh", 53 to "dns", 80 to "http", 443 to "https", 445 to "smb",
    5555 to "adb", 1900 to "ssdp", 3389 to "rdp", 5353 to "mdns", 8080 to "http-alt",
    62078 to "iphone-sync", 5900 to "vnc", 111 to "rpcbind", 139 to "netbios",
    5000 to "upnp", 9100 to "printer"
)

/** Legacy LocalPortsActivity: listening sockets on THIS device with app names. */
@Composable
fun LocalPortsScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val p = LocalGlassPalette.current
    data class PortRow(val proto: String, val port: Int, val app: String)
    var rows by remember { mutableStateOf(listOf<PortRow>()) }

    LaunchedEffect(Unit) {
        while (true) {
            val list = mutableListOf<Conn>()
            collectConns(list)
            rows = list.filter { it.state == "LISTEN" || (it.proto.startsWith("UDP") && it.remote.endsWith(":0") && it.state == "—") }
                .distinctBy { it.proto + it.local }
                .map {
                    val port = it.local.substringAfterLast(':').toIntOrNull() ?: 0
                    val uid = it.app.removePrefix("uid:")
                    val app = try {
                        val names = ctx.packageManager.getPackagesForUid(uid.toInt())
                        if (names != null && names.isNotEmpty())
                            ctx.packageManager.getApplicationLabel(
                                ctx.packageManager.getApplicationInfo(names[0], 0)).toString()
                        else "uid $uid"
                    } catch (_: Exception) { "uid $uid" }
                    PortRow(it.proto, port, app)
                }
            delay(3000)
        }
    }
    GlassScreen("Local Ports", nav) {
        GlassDesc(
            "Sockets listening on this device. Anything unexpected may be a debug " +
                "or hidden service worth checking — e.g. port 5555 (adb) left open is a red flag."
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(rows) { r ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${r.proto}:${r.port}", color = p.text, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.width(120.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(PORT_NAMES[r.port] ?: "service", color = p.accent, fontSize = 12.sp)
                        Text(r.app, color = p.faint, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
