package com.netscanner.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netscanner.core.Stores
import com.netscanner.nav.Navigator
import com.netscanner.ui.glass.GlassChip
import com.netscanner.ui.glass.GlassDesc
import com.netscanner.ui.glass.GlassScreen
import com.netscanner.ui.glass.LiquidGlassCard
import com.netscanner.ui.glass.SectionTitle
import com.netscanner.ui.theme.LocalGlassPalette
import com.netscanner.ui.theme.ThemeMode

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

            SectionTitle("About")
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Text("NetScanner", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("v5.1.0 (build 54)", color = p.accent, fontSize = 13.sp)
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
