package com.netscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.netscanner.core.AppLog
import com.netscanner.core.ToolEngine
import com.netscanner.nav.BackHandlerEnabled
import com.netscanner.nav.Navigator
import com.netscanner.nav.Route
import com.netscanner.ui.glass.GlassBackdrop
import com.netscanner.ui.screens.AutoSpeedScreen
import com.netscanner.ui.screens.CellMonitorScreen
import com.netscanner.ui.screens.ConnectionsScreen
import com.netscanner.ui.screens.DnsTesterScreen
import com.netscanner.ui.screens.HealthScreen
import com.netscanner.ui.screens.HistoryScreen
import com.netscanner.ui.screens.HomeScreen
import com.netscanner.ui.screens.LocalPortsScreen
import com.netscanner.ui.screens.LogsScreen
import com.netscanner.ui.screens.MultiPingScreen
import com.netscanner.ui.screens.NetDiagScreen
import com.netscanner.ui.screens.PingMonitorScreen
import com.netscanner.ui.screens.PortScanScreen
import com.netscanner.ui.screens.ScanScreen
import com.netscanner.ui.screens.SignalScreen
import com.netscanner.ui.screens.SnifferScreen
import com.netscanner.ui.screens.SpeedHistoryScreen
import com.netscanner.ui.screens.SshScreen
import com.netscanner.ui.screens.ToolsScreen
import com.netscanner.ui.screens.ToolRunScreen
import com.netscanner.ui.screens.UsageScreen
import com.netscanner.ui.screens.WifiAnalyzerScreen
import com.netscanner.ui.screens.WolScreen
import com.netscanner.ui.theme.GlassTheme

/**
 * NetScanner v5.0.0 — pure Kotlin + Jetpack Compose (Material 3).
 * Single activity; every screen is a Compose route. No XML UI besides the
 * manifest and launcher icon resources.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ToolEngine.appCtx = applicationContext
        setContent {
            GlassTheme {
                val nav = Navigator()
                nav.BackHandlerEnabled()
                GlassBackdrop {
                    when (val route = nav.current) {
                        Route.Home -> HomeScreen(nav)
                        Route.Scan -> ScanScreen(nav)
                        is Route.PortScan -> PortScanScreen(nav, route.ip, route.mac)
                        Route.MultiPing -> MultiPingScreen(nav)
                        Route.PingMonitor -> PingMonitorScreen(nav)
                        Route.AutoSpeed -> AutoSpeedScreen(nav)
                        Route.SpeedTest -> ToolRunScreen(nav, "speed")
                        Route.SpeedHistory -> SpeedHistoryScreen(nav)
                        Route.Usage -> UsageScreen(nav)
                        Route.Connections -> ConnectionsScreen(nav)
                        Route.WifiAnalyzer -> WifiAnalyzerScreen(nav)
                        Route.Signal -> SignalScreen(nav)
                        Route.CellMonitor -> CellMonitorScreen(nav)
                        Route.DnsTester -> DnsTesterScreen(nav)
                        Route.NetDiag -> NetDiagScreen(nav)
                        Route.LocalPorts -> LocalPortsScreen(nav)
                        Route.Sniffer -> SnifferScreen(nav)
                        Route.Ssh -> SshScreen(nav)
                        Route.Tools -> ToolsScreen(nav)
                        is Route.Tool -> ToolRunScreen(nav, route.id)
                        Route.Wol -> WolScreen(nav)
                        Route.History -> HistoryScreen(nav)
                        Route.Logs -> LogsScreen(nav)
                        Route.Health -> HealthScreen(nav)
                    }
                }
            }
        }
    }
}
