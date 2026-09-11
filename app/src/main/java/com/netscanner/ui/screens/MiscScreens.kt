package com.netscanner.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.netscanner.core.AppLog
import com.netscanner.core.NetUtils
import com.netscanner.core.Stores
import com.netscanner.core.ToolEngine
import com.netscanner.nav.Navigator
import com.netscanner.svc.SnifferVpnService
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────── DNS Sniffer (VPN) ───────────────────────────

/** Legacy SnifferActivity parity: VpnService that logs every DNS lookup. */
@Composable
fun SnifferScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val p = LocalGlassPalette.current
    var running by remember { mutableStateOf(SnifferVpnService.running.get()) }
    var queries by remember { mutableStateOf(SnifferVpnService.recentQueries()) }
    var needGrant by remember { mutableStateOf(false) }

    fun startSniffer(c: Context) {
        androidx.core.content.ContextCompat.startForegroundService(
            c, Intent(c, SnifferVpnService::class.java)
        )
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (android.net.VpnService.prepare(ctx) == null) {
            startSniffer(ctx)
            running = true
        }
    }

    LaunchedEffect(running) {
        while (isActive) {
            if (running) queries = SnifferVpnService.recentQueries()
            delay(1000)
        }
    }

    GlassScreen("DNS Sniffer", nav) {
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            Text("Status", color = p.dim, fontSize = 12.sp)
            Text(
                if (running) "Capturing DNS queries…" else "Not running",
                color = if (running) p.good else p.dim,
                fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "A local VPN is opened that forwards ONLY DNS traffic (10.111.222.1:53) to a " +
                    "user-space resolver which logs each queried hostname before passing it to the " +
                    "system upstream. Regular traffic is untouched. Android shows a VPN key icon " +
                    "while active — that is expected.",
                color = p.faint, fontSize = 12.sp
            )
            if (needGrant) {
                Spacer(Modifier.height(4.dp))
                Text("Grant the VPN permission in the dialog to start.", color = p.warn, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(
                if (running) "Stop VPN" else "Start VPN",
                {
                    if (running) {
                        ctx.startService(
                            Intent(ctx, SnifferVpnService::class.java)
                                .setAction(SnifferVpnService.ACTION_STOP)
                        )
                        running = false
                    } else {
                        val prep = android.net.VpnService.prepare(ctx)
                        if (prep != null) {
                            needGrant = true
                            vpnLauncher.launch(prep)
                        } else {
                            needGrant = false
                            startSniffer(ctx)
                            running = true
                        }
                    }
                },
                accent = !running
            )
            GlassButton("Clear log", {
                SnifferVpnService.clear()
                queries = emptyList()
            })
        }
        Spacer(Modifier.height(10.dp))
        SectionTitle("Recent queries (${queries.size})")
        LazyColumn(Modifier.weight(1f)) {
            items(queries) { q ->
                Text(
                    q,
                    color = p.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 6.dp)
                )
            }
        }
    }
}

// ─────────────────────────── Wake on LAN ───────────────────────────

/** Legacy WakeOnLanActivity parity: magic packet sender (255.255.255.255:9). */
@Composable
fun WolScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val p = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    var mac by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf(readWolRecent(ctx)) }

    GlassScreen("Wake on LAN", nav) {
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Target MAC")
            GlassTextField(mac, { mac = it }, "e.g. AA:BB:CC:DD:EE:FF")
            Text(
                "Packet is broadcast to 255.255.255.255:9 on UDP as 6×FF + 16×MAC.",
                color = p.faint, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        GlassButton("Send Magic Packet", {
            val norm = mac.replace(":", "").replace("-", "").trim()
            if (norm.length != 12 || !norm.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                result = "Invalid MAC address"
            } else scope.launch {
                val ok = withContext(Dispatchers.IO) { NetUtils.wakeOnLan(mac) }
                result = if (ok) "Magic packet sent to ${mac.uppercase()}" else "Send failed"
                if (ok) {
                    recent = recent.filter { it != mac } + mac
                    Stores.prefs(ctx).edit()
                        .putString("wol_recent", JSONArray(recent.takeLast(8)).toString()).apply()
                }
            }
        }, accent = true)
        if (result.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            KV("Result", result)
        }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SectionTitle("Recent targets")
            recent.reversed().forEach { m ->
                GlassChip(Modifier.padding(2.dp).clickable { mac = m }) {
                    Text(m, color = p.text, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun readWolRecent(ctx: Context): List<String> = try {
    val arr = JSONArray(Stores.prefs(ctx).getString("wol_recent", "[]") ?: "[]")
    (0 until arr.length()).map { arr.getString(it) }
} catch (_: Exception) { emptyList() }

// ─────────────────────────── SSH Client ───────────────────────────

/** Legacy SshActivity parity: quick JSch command runner (password auth). */
@Composable
fun SshScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("root") }
    var pass by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun run(c: String) {
        if (host.isBlank()) { out = "host required"; return }
        busy = true
        scope.launch {
            val sb = StringBuilder()
            val ok = withContext(Dispatchers.IO) {
                try {
                    val jsch = JSch()
                    val session = jsch.getSession(user, host, (port.toIntOrNull() ?: 22))
                    session.setPassword(pass)
                    session.setConfig("StrictHostKeyChecking", "no")
                    session.connect(8000)
                    val ch = session.openChannel("exec") as ChannelExec
                    ch.setCommand(c)
                    ch.inputStream = null
                    val ins = ch.inputStream
                    val err = ch.errStream
                    ch.connect(5000)
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        sb.append(String(buf, 0, n))
                    }
                    while (err.available() > 0) sb.append(err.readBytes().decodeToString())
                    ch.disconnect(); session.disconnect()
                    true
                } catch (e: Exception) {
                    sb.append("ERROR: ").append(e.message)
                    false
                }
            }
            out = (if (out.isNotEmpty()) out + "\n" else "") +
                "$ $c\n" + sb.toString().trimEnd() +
                (if (!ok) "\n(command failed)" else "")
            busy = false
        }
    }

    GlassScreen("SSH", nav) {
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            GlassTextField(host, { host = it }, "host")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassTextField(port, { port = it }, "port", Modifier.weight(1f))
                GlassTextField(user, { user = it }, "user", Modifier.weight(2f))
            }
            Spacer(Modifier.height(6.dp))
            GlassTextField(pass, { pass = it }, "password")
        }
        Spacer(Modifier.height(8.dp))
        SectionTitle("Command")
        GlassTextField(cmd, { cmd = it }, "e.g. uname -a")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlassButton("Run", { run(cmd.ifBlank { "uname -a" }) }, enabled = !busy, accent = true)
            listOf("uname -a", "uptime", "df -h", "free -m", "ip a").forEach { q ->
                GlassChip(Modifier.padding(2.dp).clickable { cmd = q }) {
                    Text(q, color = p.dim, fontSize = 12.sp)
                }
            }
        }
        if (out.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Text(
                    out, color = p.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

// ─────────────────────────── Scan History ───────────────────────────

/** Legacy HistoryActivity parity: saved network scans with device drill-down. */
@Composable
fun HistoryScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val p = LocalGlassPalette.current
    var hist by remember { mutableStateOf(Stores.scanHistory(ctx)) }
    var open by remember { mutableIntStateOf(-1) }
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    GlassScreen("Scan History", nav, actions = {
        GlassButton("Export CSV", {
            val sb = StringBuilder("ts,subnet,ip,name,vendor\n")
            hist.forEach { e ->
                val d = e.devices
                for (i in 0 until d.length()) {
                    val o = d.optJSONObject(i) ?: continue
                    sb.append(e.ts).append(',').append(e.subnet).append(',')
                        .append(o.optString("ip")).append(',')
                        .append('"').append(o.optString("name").replace("\"", "'")).append('"')
                        .append(',').append(o.optString("vendor")).append('\n')
                }
            }
            ToolEngine.exportCsv(ctx, "scan_history.csv", sb.toString())
        })
        GlassButton("Clear", {
            Stores.prefs(ctx).edit().remove("history").apply()
            hist = Stores.scanHistory(ctx)
        })
    }) {
        if (hist.isEmpty()) {
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Text("No scans saved yet.", color = p.faint, fontSize = 13.sp)
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(hist) { idx, e ->
                LiquidGlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                    open = if (open == idx) -1 else idx
                }) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(fmt.format(Date(e.ts)), color = p.dim, fontSize = 12.sp)
                            Text(
                                "${e.subnet} · ${e.count} devices",
                                color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(if (open == idx) "▲" else "▼", color = p.faint, fontSize = 13.sp)
                    }
                    if (open == idx) {
                        Spacer(Modifier.height(4.dp))
                        val d = e.devices
                        if (d.length() == 0) Text("empty scan", color = p.faint, fontSize = 12.sp)
                        for (i in 0 until d.length()) {
                            val o = d.optJSONObject(i) ?: continue
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(
                                    o.optString("ip"), color = p.text, fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    o.optString("name").ifBlank { "?" } +
                                        o.optString("vendor").let { v -> if (v.isNotBlank()) " · $v" else "" },
                                    color = p.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    items: List<T>,
    itemContent: @Composable (Int, T) -> Unit
) {
    items(items.size) { idx -> itemContent(idx, items[idx]) }
}

// ─────────────────────────── App Logs ───────────────────────────

/** Legacy LogsActivity parity: in-app diagnostics log (AppLog ring buffer). */
@Composable
fun LogsScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val p = LocalGlassPalette.current
    var text by remember { mutableStateOf(AppLog.dump()) }
    val clipboard = LocalClipboardManager.current

    GlassScreen("Diagnostics Log", nav, actions = {
        GlassButton("Refresh", { text = AppLog.dump() })
        GlassButton("Copy", {
            clipboard.setText(AnnotatedString(text))
            AppLog.log("log copied to clipboard (${text.length} chars)")
        }, accent = true)
    }) {
        LiquidGlassCard(Modifier.fillMaxWidth().weight(1f)) {
            Text(
                text.ifBlank { "(log is empty)" },
                color = p.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            )
        }
        Text(
            "Log lives in memory only; it resets when the app process dies.",
            color = p.faint, fontSize = 11.sp
        )
    }
}
