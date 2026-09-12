package com.netscanner.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netscanner.core.Device
import com.netscanner.core.DeviceTypes
import com.netscanner.core.PortResult
import com.netscanner.core.PortScanner
import com.netscanner.core.ScanEngine
import com.netscanner.core.ScanState
import com.netscanner.core.Stores
import com.netscanner.core.NetUtils
import com.netscanner.nav.Navigator
import com.netscanner.nav.Route
import com.netscanner.ui.glass.GlassChip
import com.netscanner.ui.glass.GlassScreen
import com.netscanner.ui.glass.LiquidGlassCard
import com.netscanner.ui.theme.LocalGlassPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** LAN scanner — v5.3.0: state lives in [ScanState] singleton so results survive navigation/back. */
@Composable
fun ScanScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = ScanState

    fun shareCsv() {
        val csv = Stores.lastScanCsv(ctx)
        if (csv.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "netscan.csv")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        ctx.startActivity(Intent.createChooser(i, "Export scan CSV"))
    }

    fun startScan() {
        if (st.scanning) return
        st.scanning = true
        st.reset()
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val self = NetUtils.localNet()
                    ?: return@withContext emptyList<Device>()
                ScanEngine.scan(
                    ctx, self.prefix,
                    { s -> st.stage = s },
                    { d, t, _ -> st.done = d; st.total = t },
                    { d, t -> st.done = d; st.total = t }
                )
            }
            st.devices = list
            st.stage = if (list.isEmpty()) "No devices found"
            else "${list.size} devices · ${list.count { it.openPorts().isNotEmpty() }} with open ports"
            st.scanning = false
        }
    }

    GlassScreen(title = "Scan Network", nav = nav) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            LiquidGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.stage, color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${st.done}/${st.total}",
                        color = p.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(8.dp))
                // thin progress track
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(p.faint.copy(alpha = 0.25f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(if (st.total == 0) 0f else st.done / st.total.toFloat())
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(p.accent, p.violet)
                                )
                            )
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { startScan() },
                        enabled = !st.scanning,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = p.accent.copy(alpha = 0.85f),
                            contentColor = androidx.compose.ui.graphics.Color(0xFF04222A)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (st.scanning) "Scanning…" else "Start scan", fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        onClick = { shareCsv() },
                        enabled = st.devices.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Export CSV", color = p.text) }
                }
            }
            Spacer(Modifier.height(12.dp))

            st.devices.forEach { d ->
                val isOpen = st.expanded == d.ip
                LiquidGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clickable { st.expanded = if (isOpen) null else d.ip },
                    cornerRadius = 18.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            d.fingerprint?.icon ?: DeviceTypes.emoji(d),
                            fontSize = 22.sp
                        )
                        Spacer(Modifier.height(0.dp))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(
                                d.host ?: DeviceTypes.label(d),
                                color = p.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val kindLine = buildString {
                                d.fingerprint?.kindLabel?.takeIf { it.isNotBlank() }?.let {
                                    append(it)
                                    d.fingerprint?.osLabel?.takeIf { o -> o.isNotBlank() }
                                        ?.let { o -> append(" · ").append(o) }
                                } ?: append(DeviceTypes.label(d))
                            }
                            if (kindLine.isNotBlank()) {
                                Text(kindLine, color = p.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                d.ip + macLine(d),
                                color = p.dim,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (d.isSelf) GlassChip { Text("you", color = p.accent, fontSize = 11.sp) }
                        else if (d.openPorts().isNotEmpty())
                            GlassChip { Text("${d.openPorts().size} open", color = p.accent, fontSize = 11.sp) }
                    }
                    if (d.guess != null || d.risk != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOfNotNull(d.guess, d.risk).joinToString("  ·  "),
                            color = if (d.risk != null) p.warn else p.dim,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // ── expanded: nmap-style per-port detail ──
                    if (isOpen) {
                        Spacer(Modifier.height(10.dp))
                        val open = d.openPorts()
                        if (open.isEmpty()) {
                            Text(
                                if (d.ports == null) "No port data — run a scan"
                                else "No open ports in top-100" + statsLine(d.ports!!),
                                color = p.dim, fontSize = 12.sp
                            )
                        } else {
                            open.forEach { pr -> PortRow(pr) ; Spacer(Modifier.height(6.dp)) }
                            val s = statsLine(d.ports)
                            if (s.isNotEmpty()) {
                                Text(s, color = p.faint, fontSize = 11.sp)
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        d.fingerprint?.reasons?.takeIf { it.isNotEmpty() }?.let { rs ->
                            Text("Signals: " + rs.joinToString(" · "), color = p.faint, fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { nav.push(Route.PortScan(d.ip, d.mac)) },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Full port scan →", color = p.text, fontSize = 12.sp) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun macLine(d: Device): String = when {
    d.mac != null -> {
        val v = d.vendor
        val rnd = if (d.randomMac) " · randomized" else ""
        if (v != null) "  ·  ${d.mac} · $v$rnd" else "  ·  ${d.mac}$rnd"
    }
    d.macHidden -> "  ·  MAC hidden (Android 10+)"
    else -> ""
}

private fun statsLine(ports: List<PortResult>?): String {
    if (ports == null) return ""
    val closed = ports.count { it.state == "CLOSED" }
    val filtered = ports.count { it.state == "FILTERED" }
    val parts = mutableListOf<String>()
    if (closed > 0) parts.add("$closed closed")
    if (filtered > 0) parts.add("$filtered filtered")
    return if (parts.isEmpty()) "" else "  ·  " + parts.joinToString(" · ")
}

/** One open-port row: port/service, banner, how-to-connect hint, risk. */
@Composable
private fun PortRow(pr: PortResult) {
    val p = LocalGlassPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${pr.port} ${pr.service}",
                color = p.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                pr.state,
                color = when (pr.state) {
                    "OPEN" -> p.accent
                    "CLOSED" -> p.dim
                    else -> p.warn
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            if (pr.latencyMs >= 0) {
                Text("  ${pr.latencyMs}ms", color = p.faint, fontSize = 10.sp)
            }
        }
        pr.banner?.let { b ->
            Text(
                b, color = p.dim, fontSize = 11.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        pr.hint?.let { h ->
            Text(
                "→ $h", color = p.accent, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        pr.risk?.let { r ->
            Text("⚠ $r", color = p.warn, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Per-device port scanner — v5.3.0: progress/results live in [ScanState] so they survive back-navigation. */
@Composable
fun PortScanScreen(nav: Navigator, ip: String, mac: String?) {
    val p = LocalGlassPalette.current
    val st = remember(ip) { ScanState.portScan(ip) }
    var customA by remember { mutableStateOf("") }
    var customB by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun runPreset(ports: List<Int>, timeout: Int) {
        if (st.scanning) return
        st.scanning = true
        st.results = emptyList()
        st.progress = 0f
        scope.launch {
            val detailed = withContext(Dispatchers.IO) {
                PortScanner.scanDetailed(
                    ip, ports, timeout,
                    onProgress = { d, t -> st.progress = d / t.toFloat() }
                )
            }
            st.results = detailed.results
            st.elapsed = detailed.elapsedMs
            st.scanning = false
        }
    }

    fun runCustom() {
        val a = customA.toIntOrNull()
        val b = customB.toIntOrNull()
        if (a == null || b == null || a !in 1..65535 || b !in 1..65535 || a > b) return
        runPreset((a..b).toList(), 400)
    }

    GlassScreen(title = "Ports · $ip", nav = nav) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            LiquidGlassCard {
                Text(
                    mac ?: "MAC unknown",
                    color = p.dim, fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(Modifier.clickable { runPreset(PortScanner.TOP20.toList(), 350) }) {
                        Text("Top 20", color = p.text, fontSize = 12.sp)
                    }
                    GlassChip(Modifier.clickable { runPreset(PortScanner.TOP100.toList(), 400) }) {
                        Text("Top 100", color = p.text, fontSize = 12.sp)
                    }
                    GlassChip(Modifier.clickable { runPreset((1..1024).toList(), 250) }) {
                        Text("1-1024", color = p.text, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallField(customA, "start", Modifier.weight(1f)) { customA = it }
                    SmallField(customB, "end", Modifier.weight(1f)) { customB = it }
                    Button(
                        onClick = { runCustom() },
                        enabled = !st.scanning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = p.accent.copy(alpha = 0.85f),
                            contentColor = androidx.compose.ui.graphics.Color(0xFF04222A)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Go") }
                }
                if (st.scanning) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Scanning… ${(st.progress * 100).toInt()}%",
                        color = p.accent, fontSize = 12.sp
                    )
                } else if (st.elapsed > 0) {
                    Spacer(Modifier.height(10.dp))
                    val o = st.results.count { it.state == "OPEN" }
                    val c = st.results.count { it.state == "CLOSED" }
                    val f = st.results.count { it.state == "FILTERED" }
                    Text(
                        "$o open · $c closed · $f filtered · ${(st.elapsed / 1000.0).format1()}s",
                        color = p.dim, fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (st.results.isNotEmpty()) {
                LiquidGlassCard(cornerRadius = 18.dp) {
                    st.results.forEach { pr -> PortRow(pr); Spacer(Modifier.height(6.dp)) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun Double.format1(): String = String.format("%.1f", this)

@Composable
private fun SmallField(
    value: String, hint: String, modifier: Modifier = Modifier, onChange: (String) -> Unit
) {
    val p = LocalGlassPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(5)) },
        modifier = modifier,
        placeholder = { Text(hint, color = p.faint, fontSize = 12.sp) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = p.text, fontSize = 13.sp
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.accent,
            unfocusedBorderColor = p.faint.copy(alpha = 0.5f),
            cursorColor = p.accent
        ),
        shape = RoundedCornerShape(12.dp)
    )
}
