package com.netscanner.svc

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * One parsed serving-cell snapshot — faithful port of the legacy
 * CellMonitorActivity.Serving (v4.8.1). All n/a metrics keep the legacy
 * Integer.MAX_VALUE sentinel; [dbm] is the legacy primaryDbm (RSRP on
 * LTE/NR, RSCP on WCDMA, RSSI-ish dBm on GSM/CDMA), -1 when unknown.
 */
data class Serving(
    val rat: String,
    val dbm: Int,
    val asu: Int,
    val rsrp: Int,
    val rsrq: Int,
    val sinr: Int,
    val band: Int,
    val identityKey: String,
    val shortId: String
)

/**
 * v5.1.3 unified Cell-Monitor store — the single source of truth, now
 * carrying the FULL legacy data shape instead of a bare dBm scalar:
 * per-SIM [Serving] snapshots, per-SIM graph series, neighbor-cell lines
 * and tower-change events, all produced by the 1 Hz getAllCellInfo()
 * sampler in CellService (the proven legacy pipeline).
 */
object CellStore {
    private const val TAG = "CellStore"
    const val MAX_SAMPLES = 240   // 4 minutes @ 1 Hz
    const val MAX_EVENTS = 150
    const val MAX_ROWS = 2000
    const val MAX_NEIGHBORS = 40

    @Volatile var lastDbm: Int = Int.MAX_VALUE; private set
    @Volatile var lastAsu: Int = -1; private set
    @Volatile var lastBars: Int = 0; private set

    /** "service" — which writer produced the latest sample. */
    @Volatile var lastSource: String = "--"; private set

    /** Last sampling failure reason (shown in the Gauges empty state). */
    @Volatile var lastError: String = ""; private set
    @Volatile var lastSampleAt: Long = 0L; private set
    @Volatile var sampleCount: Long = 0L; private set

    /** Per-SIM serving snapshots (index 0 = SIM1, 1 = SIM2; null = no cell). */
    @Volatile var serving0: Serving? = null; private set
    @Volatile var serving1: Serving? = null; private set
    @Volatile var simCount: Int = 0; private set

    /** Per-SIM neighbor-cell text lines (legacy neighborLine format). */
    private val neighbors = ArrayDeque<String>()

    private val samples0 = ArrayDeque<Float>()   // Graph tab per-SIM dBm series
    private val samples1 = ArrayDeque<Float>()
    private val rows = ArrayDeque<String>()      // CSV export rows
    private val events = ArrayDeque<String>()    // Log tab entries, oldest first
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Called once per 1 Hz service tick before per-SIM updates. */
    fun beginTick() {
        // keep last tick's neighbors until replaced at end of tick
    }

    /**
     * Publish one per-SIM tick result. graphDbm uses the legacy -999
     * "no sample" sentinel; a serving snapshot of null keeps the previous
     * graph value at -999 so the graph shows the gap instead of a fake
     * carry-over.
     */
    @Synchronized
    fun setSim(index: Int, sv: Serving?) {
        if (index == 0) serving0 = sv else serving1 = sv
        simCount = maxOf(simCount, index + 1)
        val q = if (index == 0) samples0 else samples1
        val v = sv?.dbm ?: -999
        q.addLast(v.toFloat())
        while (q.size > MAX_SAMPLES) q.removeFirst()

        // Aggregate scalars: prefer SIM 1, fall back to SIM 2 (legacy rule).
        val best = serving0 ?: serving1
        if (best != null && best.dbm != -1) {
            lastDbm = best.dbm
            lastAsu = best.asu
            lastBars = CellService.barsOf(best.dbm)
            lastSource = "service"
            lastError = ""
        } else if (serving0 == null && serving1 == null) {
            lastDbm = Int.MAX_VALUE
            lastAsu = -1
            lastBars = 0
        }
        lastSampleAt = System.currentTimeMillis()
        sampleCount++
        if (best != null) {
            rows.addLast("${lastSampleAt},${best.dbm},${best.asu},${lastBars}")
            while (rows.size > MAX_ROWS) rows.removeFirst()
        }
    }

    /** Publish this tick's neighbor-cell lines (already deduped by the service). */
    @Synchronized
    fun setNeighbors(lines: List<String>) {
        neighbors.clear()
        lines.forEach {
            neighbors.addLast(it)
            while (neighbors.size > MAX_NEIGHBORS) neighbors.removeFirst()
        }
    }

    /** Tower / tech change event (legacy Log tab semantics). */
    @Synchronized
    fun appendEvent(line: String) {
        events.addLast(line)
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    /** Record a sampling failure so the UI can surface it instead of staying silently empty. */
    fun setNoSource(err: String) {
        lastError = err
        Log.w(TAG, err)
    }

    @Synchronized fun servingFor(index: Int): Serving? =
        if (index == 0) serving0 else serving1

    /** Render a legacy metric (RSRP/RSRQ/SINR): "--" when the radio didn't report it. */
    fun metricStr(v: Int): String = if (v == Int.MAX_VALUE) "--" else v.toString()

    @Synchronized fun snapshotSamples(sim: Int): List<Float> =
        (if (sim == 0) samples0 else samples1).toList()

    @Synchronized fun snapshotRows(): List<String> = rows.toList()

    /** Neighbor lines, newest additions last (legacy order). */
    @Synchronized fun snapshotNeighbors(): List<String> = neighbors.toList()

    /** Newest event first (legacy log-tab order). */
    @Synchronized fun snapshotEvents(): List<String> = events.toList().asReversed()

    /** Wipe history (called when the user explicitly stops monitoring). */
    fun reset() {
        synchronized(this) {
            samples0.clear(); samples1.clear(); rows.clear(); events.clear(); neighbors.clear()
            serving0 = null; serving1 = null; simCount = 0
        }
        lastDbm = Int.MAX_VALUE; lastAsu = -1; lastBars = 0
        lastSource = "--"; lastError = ""
        sampleCount = 0L; lastSampleAt = 0L
        Log.d(TAG, "store reset")
    }
}
