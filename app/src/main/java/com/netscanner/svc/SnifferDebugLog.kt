package com.netscanner.svc

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * v5.1.5 DNS Sniffer debug ring buffer.
 *
 * Keeps the last 500 timestamped, level-tagged entries covering the whole
 * VPN lifecycle so a failing device can paste the evidence back to us:
 *   - VpnService.establish() result with ALL builder parameters
 *   - intercepted packet summaries (rate-limited)
 *   - parsed DNS queries (domain + type)
 *   - forward attempts: upstream IP, socket protected yes/no, response size,
 *     latency ms, failure + stack trace
 *   - reply write results
 *   - thread pool activity
 *   - lifecycle (create / start / revoke / destroy / loop exit)
 *   - every exception with full stack
 *
 * Lives in memory only; the Settings screen snapshots / copies / saves it.
 */
object SnifferDebugLog {
    private const val CAPACITY = 500
    private val lines = ArrayDeque<String>()
    private var seq = 0L
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // Rate-limit windows: packet summaries <=10/s, dns queries <=30/s,
    // overflow is counted and reported so bursts never flood the buffer.
    private var pktWin = 0L; private var pktShown = 0; private var pktSup = 0
    private var dnsWin = 0L; private var dnsShown = 0; private var dnsSup = 0

    @Synchronized
    fun log(level: String, msg: String, tr: Throwable? = null) {
        seq++
        val sb = StringBuilder()
        sb.append(seq).append("  ").append(ts.format(Date())).append(' ')
            .append(level).append(' ').append(msg)
        if (tr != null) sb.append("\n").append(tr.stackTraceToString())
        lines.addLast(sb.toString())
        while (lines.size > CAPACITY) lines.removeFirst()
    }

    fun d(msg: String) = log("D", msg)
    fun i(msg: String) = log("I", msg)
    fun w(msg: String, tr: Throwable? = null) = log("W", msg, tr)
    fun e(msg: String, tr: Throwable? = null) = log("E", msg, tr)

    /** Rate-limited per-packet summary (<= 10 lines/s, overflow counted). */
    @Synchronized
    fun packet(msg: String) {
        val now = System.currentTimeMillis()
        if (now - pktWin >= 1000) {
            if (pktSup > 0) log("D", "pkt: +$pktSup packet summaries suppressed (rate limit 10/s)")
            pktWin = now; pktShown = 0; pktSup = 0
        }
        if (pktShown < 10) { log("D", "pkt: $msg"); pktShown++ } else pktSup++
    }

    /** Rate-limited DNS query log (<= 30 lines/s, overflow counted). */
    @Synchronized
    fun dns(msg: String) {
        val now = System.currentTimeMillis()
        if (now - dnsWin >= 1000) {
            if (dnsSup > 0) log("D", "dns: +$dnsSup query lines suppressed (rate limit 30/s)")
            dnsWin = now; dnsShown = 0; dnsSup = 0
        }
        if (dnsShown < 30) { log("D", "dns: $msg"); dnsShown++ } else dnsSup++
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() { lines.clear(); seq = 0; pktSup = 0; dnsSup = 0 }

    @Synchronized
    fun dump(): String = buildString {
        appendLine("NetScanner DNS Sniffer debug log — ${ts.format(Date())} — ${lines.size} entries (ring of $CAPACITY)")
        lines.forEach { appendLine(it) }
    }

    /** Save the dump into the app's external files dir (no permission needed). */
    fun saveTo(ctx: Context): File? = try {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = File(dir, "netscanner_sniffer_debug_${System.currentTimeMillis()}.txt")
        f.writeText(dump())
        f
    } catch (_: Exception) { null }

    /** Content URI for the share sheet (external-files-path in file_paths.xml). */
    fun shareUri(ctx: Context, f: File) =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
}
