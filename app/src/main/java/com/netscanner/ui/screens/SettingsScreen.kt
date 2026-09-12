package com.netscanner.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netscanner.core.Stores
import com.netscanner.nav.Navigator
import com.netscanner.svc.SnifferDebugLog
import com.netscanner.svc.SnifferVpnService
import com.netscanner.ui.glass.GlassButton
import com.netscanner.ui.glass.GlassChip
import com.netscanner.ui.glass.GlassDesc
import com.netscanner.ui.glass.GlassScreen
import com.netscanner.ui.glass.LiquidGlassCard
import com.netscanner.ui.glass.SectionTitle
import com.netscanner.ui.theme.LocalGlassPalette
import com.netscanner.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Settings (Misc) screen — v5.1.0. Hosts the Light / Dark / System
 * appearance switch. The choice is written to the "netscanner" prefs via
 * [Stores] and applied live through GlassTheme per-mode glass tokens
 * (dark: higher fill alpha, lower border alpha).
 */
@Composable
fun SettingsScreen(nav: Navigator) {
    val p = LocalGlassPalette.current
    val ctx = LocalContext.current

    GlassScreen("Settings", nav) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle("Appearance")
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Text("Theme mode", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Applies instantly and is remembered across launches.",
                    color = p.dim, fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = nav.themeMode == mode
                        GlassChip(
                            Modifier
                                .weight(1f)
                                .clickable {
                                    nav.themeMode = mode
                                    Stores.setThemeMode(ctx, mode)
                                }
                        ) {
                            Text(
                                if (selected) "● ${mode.label}" else "○ ${mode.label}",
                                color = if (selected) p.accent else p.dim,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            GlassDesc(
                "Dark mode raises glass fill alpha so surfaces stay legible over the aurora, " +
                    "and lowers border alpha to avoid bright halos. Light mode keeps the airy " +
                    "ice-blue glass. System follows your device setting."
            )
            Spacer(Modifier.height(16.dp))

            // ── v5.1.5: DNS Sniffer debug log (live view / copy / save / share) ──
            SectionTitle("DNS Sniffer Debug")
            val snifferRunning = SnifferVpnService.running.get()
            val clipboard = LocalClipboardManager.current
            var logLines by remember { mutableStateOf(SnifferDebugLog.snapshot()) }
            var savedPath by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                while (isActive) {
                    logLines = SnifferDebugLog.snapshot()
                    delay(1000)
                }
            }
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Live debug log (${logLines.size}/500)",
                            color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (snifferRunning) "VPN is running — capturing…" else "VPN idle",
                            color = if (snifferRunning) p.good else p.dim, fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                ) {
                    items(logLines.asReversed()) { line ->
                        Text(
                            line,
                            color = p.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 6, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Copy", {
                        val text = SnifferDebugLog.dump()
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(ctx, "Copied ${logLines.size} lines", Toast.LENGTH_SHORT).show()
                    }, accent = true)
                    GlassButton("Save", {
                        val f = SnifferDebugLog.saveTo(ctx)
                        savedPath = f?.absolutePath
                        Toast.makeText(
                            ctx,
                            if (f != null) "Saved to ${f.name}" else "Save failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    })
                    GlassButton("Share", {
                        val f = SnifferDebugLog.saveTo(ctx)
                        if (f == null) {
                            Toast.makeText(ctx, "Save failed — cannot share", Toast.LENGTH_SHORT).show()
                            return@GlassButton
                        }
                        savedPath = f.absolutePath
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "NetScanner DNS Sniffer debug log")
                            putExtra(Intent.EXTRA_STREAM, SnifferDebugLog.shareUri(ctx, f))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share DNS Sniffer debug log"))
                    })
                }
                savedPath?.let { path ->
                    Spacer(Modifier.height(6.dp))
                    Text("Saved: $path", color = p.accent, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                GlassDesc(
                    "Ring buffer of the last 500 timestamped entries from the DNS Sniffer VPN: " +
                        "every builder parameter passed to establish(), intercepted packets " +
                        "(rate-limited), parsed DNS queries (domain + type), forward attempts " +
                        "(upstream IP, socket protected yes/no, response size, latency, failures " +
                        "with stack traces), reply writes, thread pool activity, lifecycle events " +
                        "and all exceptions. Start DNS Sniffer, reproduce the problem, then copy " +
                        "or share this log."
                )
            }
            Spacer(Modifier.height(16.dp))

            SectionTitle("About")
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Text("NetScanner", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("v5.1.5 (build 59)", color = p.accent, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pure Kotlin + Jetpack Compose port of the legacy NetScanner toolbox: " +
                        "LAN scan, multi ping, connection & cell monitors, DNS sniffer VPN, " +
                        "speed tools and 24 utility tiles — wrapped in a liquid-glass UI.",
                    color = p.dim, fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            Spacer(Modifier.height(4.dp))
        }
    }
}
