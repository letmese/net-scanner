package com.netscanner.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * v5.3.0 state-loss fix: the Scan tab results and the per-device full-port-scan
 * progress used to live in composable-scoped `remember {}` slots, so navigating
 * (e.g. opening the full port scan page) and pressing back recreated the
 * composables and wiped them. Everything now lives on this process-wide
 * singleton — same pattern as CellStore/Stores — and survives any navigation.
 */
object ScanState {

    // ── Scan Network tab ──
    var scanning by mutableStateOf(false)
    var stage by mutableStateOf("Ready")
    var done by mutableStateOf(0)
    var total by mutableStateOf(254)
    var devices by mutableStateOf<List<Device>>(emptyList())
    var expanded by mutableStateOf<String?>(null)

    /** Clear per-run fields before a fresh sweep. */
    fun reset() {
        devices = emptyList()
        expanded = null
        stage = "Sweeping…"
        done = 0
        total = 254
    }

    /** Snapshot-backed UI holder for one device's full port scan page. */
    class PortScanUi {
        var scanning by mutableStateOf(false)
        var progress by mutableStateOf(0f)
        var elapsed by mutableStateOf(0L)
        var results by mutableStateOf<List<PortResult>>(emptyList())
    }

    private val portScans = HashMap<String, PortScanUi>()

    /** Stable per-IP holder — returns the same instance across navigation. */
    fun portScan(ip: String): PortScanUi =
        synchronized(portScans) { portScans.getOrPut(ip) { PortScanUi() } }
}
