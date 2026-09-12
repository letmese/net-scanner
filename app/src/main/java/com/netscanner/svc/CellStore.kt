package com.netscanner.svc

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * v5.1.2 unified Cell-Monitor sample store — the single source of truth that
 * the 1 Hz foreground-service sampler writes and every Cell Monitor tab
 * (Gauges / Graph / Log / CSV export) reads.
 *
 * v5.1.1 defect it fixes: the service only kept three @Volatile scalars that
 * were updated solely by (often permission-muted) listener callbacks, while
 * the UI kept its own screen-local sample/log lists that died the moment the
 * composable left composition. Tabs therefore showed nothing or stale data.
 * Now there is exactly one store and exactly one 1 Hz writer.
 */
object CellStore {
    private const val TAG = "CellStore"
    const val MAX_SAMPLES = 240   // 4 minutes @ 1 Hz
    const val MAX_EVENTS = 150
    const val MAX_ROWS = 2000

    @Volatile var lastDbm: Int = Int.MAX_VALUE; private set
    @Volatile var lastAsu: Int = -1; private set
    @Volatile var lastBars: Int = 0; private set

    /** "service" | "screen" — which writer produced the latest sample. */
    @Volatile var lastSource: String = "--"; private set

    /** Last sampling failure reason (shown in the Gauges empty state). */
    @Volatile var lastError: String = ""; private set
    @Volatile var lastSampleAt: Long = 0L; private set
    @Volatile var sampleCount: Long = 0L; private set

    private val samples = ArrayDeque<Float>()   // Graph tab (dBm series)
    private val rows = ArrayDeque<String>()     // CSV export rows
    private val events = ArrayDeque<String>()   // Log tab entries, oldest first
    private var lastLoggedDbm: Int? = null
    private var lastLoggedBars = -1
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * Append one live sample. Called at 1 Hz by CellService's sampler thread
     * (and by the screen fallback loop only when the service is dead).
     */
    @Synchronized
    fun appendSample(
        dbm: Int, asu: Int, bars: Int, source: String,
        ts: Long = System.currentTimeMillis()
    ) {
        lastDbm = dbm; lastAsu = asu; lastBars = bars
        lastSource = source; lastSampleAt = ts; sampleCount++
        lastError = ""
        samples.addLast(dbm.toFloat())
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        rows.addLast("$ts,$dbm,$asu,$bars")
        while (rows.size > MAX_ROWS) rows.removeFirst()
        val prev = lastLoggedDbm
        if (prev == null || abs(dbm - prev) >= 3 || bars != lastLoggedBars) {
            val dir = when {
                prev == null -> "  first"
                dbm > prev -> "  \u25B2 +${dbm - prev}"
                else -> "  \u25BC ${dbm - prev}"
            }
            val tag = when {
                bars > lastLoggedBars && lastLoggedBars >= 0 -> "  (bars up)"
                bars < lastLoggedBars && lastLoggedBars >= 0 -> "  (bars down)"
                else -> ""
            }
            events.addLast("${timeFmt.format(Date(ts))}  $dbm dBm  ${CellService.barsStr(bars)}$dir$tag")
            while (events.size > MAX_EVENTS) events.removeFirst()
            lastLoggedDbm = dbm
            lastLoggedBars = bars
        }
    }

    /** Record a sampling failure so the UI can surface it instead of staying silently empty. */
    fun setNoSource(err: String) {
        lastError = err
        Log.w(TAG, err)
    }

    @Synchronized fun snapshotSamples(): List<Float> = samples.toList()
    @Synchronized fun snapshotRows(): List<String> = rows.toList()

    /** Newest event first (legacy log-tab order). */
    @Synchronized fun snapshotEvents(): List<String> = events.toList().asReversed()

    /** Wipe history (called when the user explicitly stops monitoring). */
    fun reset() {
        synchronized(this) {
            samples.clear(); rows.clear(); events.clear()
            lastLoggedDbm = null; lastLoggedBars = -1
        }
        lastDbm = Int.MAX_VALUE; lastAsu = -1; lastBars = 0
        lastSource = "--"; lastError = ""
        sampleCount = 0L; lastSampleAt = 0L
        Log.d(TAG, "store reset")
    }
}
