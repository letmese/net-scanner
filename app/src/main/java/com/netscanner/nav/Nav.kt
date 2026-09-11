package com.netscanner.nav

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.activity.compose.BackHandler
import com.netscanner.ui.theme.ThemeMode

/**
 * Tiny pure-Compose navigation: a snapshot state stack of [Route]s.
 * Zero extra dependencies; back handling pops the stack, Home is root.
 *
 * Also hosts app-level snapshot state (appearance mode) so Settings can
 * retint the whole tree live through GlassTheme.
 */
sealed interface Route {
    data object Home : Route
    data object Scan : Route
    data class PortScan(val ip: String, val mac: String?) : Route
    data object MultiPing : Route
    data object PingMonitor : Route
    data object AutoSpeed : Route
    data object SpeedTest : Route
    data object SpeedHistory : Route
    data object Usage : Route
    data object Connections : Route
    data object WifiAnalyzer : Route
    data object Signal : Route
    data object CellMonitor : Route
    data object DnsTester : Route
    data object NetDiag : Route
    data object LocalPorts : Route
    data object Sniffer : Route
    data object Ssh : Route
    data object Tools : Route
    data class Tool(val id: String) : Route
    data object Wol : Route
    data object History : Route
    data object Logs : Route
    data object Health : Route
    data object Settings : Route
}

/** Global navigation stack owned by the app root. */
class Navigator {
    val stack: SnapshotStateList<Route> = mutableStateListOf(Route.Home)

    /** Appearance mode (Light/Dark/System) — snapshot state, read by GlassTheme. */
    var themeMode: ThemeMode by mutableStateOf(ThemeMode.SYSTEM)

    val current: Route get() = stack.last()

    fun push(route: Route) {
        if (stack.last() != route) stack.add(route)
    }

    fun pop(): Boolean =
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            true
        } else false

    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

/** Composable helper: pop on back press when not at root. */
@Composable
fun Navigator.BackHandlerEnabled() {
    val nav = this
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
}

/** Placeholder used until all screens are ported (compile milestone). */
@Composable
fun NotImplemented(route: Route) {
    Text("Under construction: $route")
}
