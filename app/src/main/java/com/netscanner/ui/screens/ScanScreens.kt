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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netscanner.core.Device
import com.netscanner.core.DeviceTypes
import com.netscanner.core.PortScanner
import com.netscanner.core.ScanEngine
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

/** LAN scanner (port of ScanActivity). */
@Composable
fun ScanScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("Ready") }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(254) }
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }

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
        if (scanning) return
        scanning = true
        devices = emptyList()
        stage = "Sweeping…"
        done = 0
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val self = NetUtils.localNet()
                    ?: return@withContext emptyList<Device>()
                ScanEngine.scan(ctx, self.prefix, { s -> stage = s }, { d, t, _ ->
                    done = d; total = t
                })
            }
            devices = list
            stage = if (list.isEmpty()) "No devices found" else "${list.size} devices found"
            scanning = false
        }
    }

    GlassScreen(title = "Scan Network", nav = nav) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            LiquidGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stage, color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(
                        "$done/$total",
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
                            .fillMaxWidth(done / total.toFloat())
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
                        enabled = !scanning,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = p.accent.copy(alpha = 0.85f),
                            contentColor = androidx.compose.ui.graphics.Color(0xFF04222A)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (scanning) "Scanning…" else "Start scan", fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        onClick = { shareCsv() },
                        enabled = devices.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Export CSV", color = p.text) }
                }
            }
            Spacer(Modifier.height(12.dp))

            devices.forEach { d ->
                LiquidGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clickable { nav.push(Route.PortScan(d.ip, d.mac)) },
                    cornerRadius = 18.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .padding(4.dp)
                        ) { Text(DeviceTypes.emoji(d), fontSize = 22.sp) }
                        Spacer(Modifier.height(0.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.host ?: DeviceTypes.label(d),
                                color = p.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                d.ip + (d.mac?.let { "  ·  $it" } ?: "") + "  ·  " + DeviceTypes.label(d),
                                color = p.dim,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                        if (d.isSelf) GlassChip { Text("you", color = p.accent, fontSize = 11.sp) }
                    }
                    if (d.guess != null || d.risk != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOfNotNull(d.guess, d.risk).joinToString("  ·  "),
                            color = if (d.risk != null) p.warn else p.dim,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Per-device port scanner (port of DevicePortActivity). */
@Composable
fun PortScanScreen(nav: Navigator, ip: String, mac: String?) {
    val p = LocalGlassPalette.current
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var elapsed by remember { mutableStateOf(0L) }
    var open by remember { mutableStateOf<List<Int>>(emptyList()) }
    var customA by remember { mutableStateOf("") }
    var customB by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun runPreset(ports: List<Int>, timeout: Int) {
        if (scanning) return
        scanning = true
        open = emptyList()
        progress = 0f
        scope.launch {
            val (found, ms) = withContext(Dispatchers.IO) {
                PortScanner.scan(
                    ip, ports, timeout,
                    onProgress = { d, t -> progress = d / t.toFloat() },
                    onOpen = { }
                )
            }
            open = found
            elapsed = ms
            scanning = false
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
                        enabled = !scanning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = p.accent.copy(alpha = 0.85f),
                            contentColor = androidx.compose.ui.graphics.Color(0xFF04222A)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Go") }
                }
                if (scanning) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Scanning… ${(progress * 100).toInt()}%",
                        color = p.accent, fontSize = 12.sp
                    )
                } else if (elapsed > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${open.size} open · ${(elapsed / 1000.0).format1()}s",
                        color = p.dim, fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (open.isNotEmpty()) {
                LiquidGlassCard(cornerRadius = 18.dp) {
                    open.chunked(3).forEach { rowPorts ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowPorts.forEach { port ->
                                GlassChip(Modifier.weight(1f)) {
                                    Text(
                                        "$port ${PortScanner.service(port)}",
                                        color = p.text, fontSize = 11.sp, maxLines = 1
                                    )
                                }
                            }
                            repeat(3 - rowPorts.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
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
