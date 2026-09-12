package com.netscanner.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/**
 * v5.3.0 Ookla-style speed test engine. Keeps the proven HTTP measurement
 * approach of [SpeedTestRunner] (which stays untouched for AutoSpeed/legacy
 * tools) and adds around it:
 *  - idle ping + jitter + TCP packet-loss estimate (no root needed)
 *  - loaded latency sampled under download AND upload load (bufferbloat grade)
 *  - connection metadata: public IP / edge colo (Cloudflare cdn-cgi/trace),
 *    ISP / org / AS / city (ip-api.com) fetched at test time
 *  - ~5 Hz throughput sampling for the live graph
 *  - JSON result history in app files (tap-to-inspect in the UI)
 *
 * All UI-facing fields are snapshot state on this singleton so the Speed Test
 * tab survives navigation (same rationale as [ScanState]).
 */
object OoklaSpeed {

    private const val UA = "Mozilla/5.0 (Linux; Android 16) NetScanner/5.3"
    private const val PROBE_HOST = "speed.cloudflare.com"
    private const val PROBE_PORT = 443
    private const val DL_URL = "https://speed.cloudflare.com/__down?bytes=100000000"
    private const val UP_URL = "https://speed.cloudflare.com/__up"
    private const val HIST_FILE = "speed_history.json"

    // ── live UI state ──
    /** idle → ping → download → upload → done | error */
    var phase by mutableStateOf("idle")
    var status by mutableStateOf("")
    var err by mutableStateOf("")
    var gaugeDown by mutableStateOf(Float.NaN)
    var gaugeUp by mutableStateOf(Float.NaN)

    /** (t seconds since phase start, mbps) live throughput samples for the graph. */
    val downSamples = mutableStateListOf<Pair<Float, Float>>()
    val upSamples = mutableStateListOf<Pair<Float, Float>>()

    // ── result fields (null = not measured / not feasible) ──
    var idlePing by mutableStateOf<Float?>(null)
    var jitter by mutableStateOf<Float?>(null)
    var loss by mutableStateOf<Float?>(null)          // TCP connect-failure ratio, %
    var loadedDown by mutableStateOf<Float?>(null)    // avg RTT under download load, ms
    var loadedUp by mutableStateOf<Float?>(null)      // avg RTT under upload load, ms
    var grade by mutableStateOf<String?>(null)        // bufferbloat A+..F
    var publicIp by mutableStateOf<String?>(null)
    var isp by mutableStateOf<String?>(null)
    var org by mutableStateOf<String?>(null)
    var asInfo by mutableStateOf<String?>(null)
    var edgeColo by mutableStateOf<String?>(null)     // Cloudflare edge airport code
    var serverCity by mutableStateOf<String?>(null)   // ip-api city/country of egress IP

    @Volatile private var cancelled = false
    @Volatile private var thread: Thread? = null
    val running: Boolean get() = thread?.isAlive == true

    fun start(ctx: Context) {
        if (running) return
        cancelled = false
        thread = Thread { run(ctx.applicationContext) }.apply {
            name = "ookla-speed"
            start()
        }
    }

    fun cancel() {
        cancelled = true
        phase = "idle"
        status = "Cancelled"
    }

    /** "City, Country · edge LAX" style server label for the last/current test. */
    fun serverLoc(): String? = listOfNotNull(
        serverCity?.takeIf { it.isNotBlank() },
        edgeColo?.let { "edge $it" }
    ).joinToString(" · ").ifEmpty { null }

    /** Waveform/Ookla-style bufferbloat grade from worst latency delta. */
    fun bloatGrade(deltaMs: Float): String = when {
        deltaMs <= 5f -> "A+"
        deltaMs <= 30f -> "A"
        deltaMs <= 60f -> "B"
        deltaMs <= 200f -> "C"
        deltaMs <= 400f -> "D"
        else -> "F"
    }

    // ─────────────────────────── engine ───────────────────────────

    private fun run(ctx: Context) {
        try {
            resetLive()

            // 1) idle latency / jitter / loss
            phase = "ping"
            status = "Measuring idle latency…"
            val rtts = mutableListOf<Int>()
            var fails = 0
            repeat(12) {
                if (cancelled) return
                val r = tcpPing(PROBE_HOST, PROBE_PORT, 1200)
                if (r == null) fails++ else rtts.add(r)
                try { Thread.sleep(120) } catch (e: InterruptedException) { return }
            }
            if (rtts.isEmpty()) {
                err = "No latency samples — offline, or DNS Sniffer VPN is ON?"
                phase = "error"
                return
            }
            idlePing = rtts.average().toFloat()
            jitter = jitterOf(rtts)
            loss = fails * 100f / (fails + rtts.size)

            // 2) connection metadata (best-effort, non-fatal)
            if (!cancelled) fetchConnectionInfo()

            // 3) download phase + loaded latency
            phase = "download"
            status = "Download test…"
            val dlLoaded = mutableListOf<Int>()
            val down = throughputPhase(download = true, loadedSink = dlLoaded)
            if (cancelled) return
            loadedDown = if (dlLoaded.size >= 3) dlLoaded.average().toFloat() else null

            // 4) upload phase + loaded latency
            phase = "upload"
            status = "Upload test…"
            val ulLoaded = mutableListOf<Int>()
            val up = throughputPhase(download = false, loadedSink = ulLoaded)
            if (cancelled) return
            loadedUp = if (ulLoaded.size >= 3) ulLoaded.average().toFloat() else null

            // 5) bufferbloat grade from worst-direction latency delta
            val idle = idlePing ?: 0f
            grade = listOfNotNull(loadedDown, loadedUp)
                .maxOrNull()
                ?.let { bloatGrade(it - idle) }

            phase = "done"
            status = String.format(
                "%.1f ↓ / %.1f ↑ Mbps · %d ms idle · jitter %s · grade %s",
                down, up, (idlePing ?: 0f).toInt(),
                jitter?.let { String.format("%.1f ms", it) } ?: "n/a",
                grade ?: "n/a"
            )
            saveResult(ctx, down, up)
        } catch (e: InterruptedException) {
            // user cancelled — leave state as cancel() left it
        } catch (e: Throwable) {
            err = e.message ?: e.toString()
            phase = "error"
        }
    }

    private fun resetLive() {
        gaugeDown = Float.NaN
        gaugeUp = Float.NaN
        downSamples.clear()
        upSamples.clear()
        idlePing = null
        jitter = null
        loss = null
        loadedDown = null
        loadedUp = null
        grade = null
        err = ""
        status = ""
        publicIp = null
        isp = null
        org = null
        asInfo = null
        edgeColo = null
        serverCity = null
    }

    /**
     * Multi-stream throughput against Cloudflare with a ~5 Hz sampler feeding
     * the gauge/graph and a parallel prober recording loaded latency.
     * Returns avg Mbps over the whole window (total bytes / total seconds).
     */
    private fun throughputPhase(download: Boolean, loadedSink: MutableList<Int>): Double {
        val bytes = AtomicLong()
        val errRef = AtomicReference<String?>(null)
        val t0 = System.currentTimeMillis()
        val dur = if (download) 10_000L else 8_000L
        val deadline = t0 + dur

        val nStreams = if (download) 4 else 2
        val workers = (0 until nStreams).map {
            Thread {
                var ins: InputStream? = null
                var os: OutputStream? = null
                var c: HttpURLConnection? = null
                try {
                    c = (if (download) URL(DL_URL) else URL(UP_URL))
                        .openConnection() as HttpsURLConnection
                    c.connectTimeout = 5000
                    c.readTimeout = 15000
                    if (download) {
                        c.setRequestProperty("Accept-Encoding", "identity")
                    } else {
                        c.doOutput = true
                        c.requestMethod = "POST"
                        c.setRequestProperty("Content-Type", "application/octet-stream")
                    }
                    c.setRequestProperty("User-Agent", UA)
                    if (download) {
                        val code = c.responseCode
                        if (code < 200 || code >= 300) throw IOException("HTTP $code")
                        ins = c.inputStream
                        val b = ByteArray(65536)
                        var n: Int
                        while (!cancelled && System.currentTimeMillis() < deadline) {
                            n = ins.read(b)
                            if (n <= 0) break
                            bytes.addAndGet(n.toLong())
                        }
                    } else {
                        os = c.outputStream
                        val chunk = ByteArray(32768)
                        var sent = 0L
                        while (!cancelled && System.currentTimeMillis() < deadline && sent < 25_000_000L) {
                            os.write(chunk)
                            os.flush()
                            sent += chunk.size
                            bytes.addAndGet(chunk.size.toLong())
                            if ((sent and 0x3FFFFL) == 0L) Thread.sleep(5)
                        }
                    }
                } catch (e: Exception) {
                    errRef.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                } finally {
                    try { ins?.close() } catch (ignored: Exception) {}
                    try { os?.close() } catch (ignored: Exception) {}
                    c?.disconnect()
                }
            }
        }
        workers.forEach { it.start() }

        // sampler: 200 ms buckets → smoothed instantaneous rate
        val sampler = Thread {
            var last = 0L
            var lastT = System.currentTimeMillis()
            while (!cancelled && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(200) } catch (e: InterruptedException) { return@Thread }
                val now = System.currentTimeMillis()
                val n = bytes.get()
                val dt = now - lastT
                if (dt >= 100) {
                    val mbps = (n - last) * 8 / 1e6 / (dt / 1000.0)
                    val t = (now - t0) / 1000f
                    if (mbps.isFinite()) {
                        val prev: Double =
                            if (download && !gaugeDown.isNaN()) gaugeDown.toDouble()
                            else if (!download && !gaugeUp.isNaN()) gaugeUp.toDouble()
                            else mbps
                        val smooth = prev * 0.55 + mbps * 0.45
                        if (download) {
                            gaugeDown = smooth.toFloat()
                            downSamples.add(t to smooth.toFloat())
                        } else {
                            gaugeUp = smooth.toFloat()
                            upSamples.add(t to smooth.toFloat())
                        }
                    }
                    last = n
                    lastT = now
                }
            }
        }

        // loaded-latency prober: TCP pings under load (bufferbloat signal)
        val prober = Thread {
            while (!cancelled && System.currentTimeMillis() < deadline - 400) {
                tcpPing(PROBE_HOST, PROBE_PORT, 900)?.let { loadedSink.add(it) }
                try { Thread.sleep(500) } catch (e: InterruptedException) { return@Thread }
            }
        }
        sampler.start()
        prober.start()

        workers.forEach { try { it.join(dur + 8000) } catch (ignored: InterruptedException) {} }
        try { sampler.join(2500) } catch (ignored: InterruptedException) {}
        try { prober.join(2500) } catch (ignored: InterruptedException) {}

        val secs = (System.currentTimeMillis() - t0) / 1000.0
        val b = bytes.get()
        return if (secs < 1.0 || b == 0L) 0.0 else b * 8 / 1e6 / secs
    }

    /** One TCP-connect RTT sample (no root needed); null on timeout/ refusal. */
    private fun tcpPing(host: String, port: Int, timeoutMs: Int): Int? = try {
        Socket().use { s ->
            val t0 = System.currentTimeMillis()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            (System.currentTimeMillis() - t0).toInt()
        }
    } catch (e: Exception) {
        null
    }

    /** Ookla-style jitter: mean absolute delta between consecutive samples. */
    private fun jitterOf(rtts: List<Int>): Float {
        if (rtts.size < 2) return 0f
        var sum = 0.0
        for (i in 1 until rtts.size) sum += Math.abs(rtts[i] - rtts[i - 1])
        return (sum / (rtts.size - 1)).toFloat()
    }

    /** Cloudflare trace (public IP + edge colo) then ip-api.com (ISP/AS/city). */
    private fun fetchConnectionInfo() {
        try {
            val c = URL("https://www.cloudflare.com/cdn-cgi/trace")
                .openConnection() as HttpsURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode in 200..299) {
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val map = txt.lineSequence()
                    .map { l -> l.split('=', limit = 2) }
                    .filter { it.size == 2 }
                    .associate { it[0] to it[1] }
                publicIp = map["ip"]
                edgeColo = map["colo"]
            }
            c.disconnect()
        } catch (ignored: Exception) {
        }
        try {
            val q = publicIp?.takeIf { it.isNotBlank() } ?: ""
            val c = URL("http://ip-api.com/json/$q?fields=isp,org,as,city,country")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode in 200..299) {
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                isp = o.optString("isp").takeIf { it.isNotBlank() }
                org = o.optString("org").takeIf { it.isNotBlank() }
                asInfo = o.optString("as").takeIf { it.isNotBlank() }
                serverCity = listOf(
                    o.optString("city").takeIf { it.isNotBlank() },
                    o.optString("country").takeIf { it.isNotBlank() }
                ).filter { it != null }.joinToString(", ").ifEmpty { null }
            }
            c.disconnect()
        } catch (ignored: Exception) {
        }
    }

    // ─────────────────────────── history ───────────────────────────

    fun history(ctx: Context): List<JSONObject> = try {
        val f = File(ctx.filesDir, HIST_FILE)
        if (f.exists()) {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }
        } else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveResult(ctx: Context, down: Double, up: Double) {
        try {
            val e = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("down", Math.round(down * 10) / 10.0)
                .put("up", Math.round(up * 10) / 10.0)
            idlePing?.let { e.put("idle", Math.round(it * 10) / 10.0) }
            jitter?.let { e.put("jitter", Math.round(it * 10) / 10.0) }
            loss?.let { e.put("loss", Math.round(it * 10) / 10.0) }
            loadedDown?.let { e.put("ldown", Math.round(it * 10) / 10.0) }
            loadedUp?.let { e.put("lup", Math.round(it * 10) / 10.0) }
            grade?.let { e.put("grade", it) }
            publicIp?.let { e.put("ip", it) }
            isp?.let { e.put("isp", it) }
            asInfo?.let { e.put("as", it) }
            serverLoc()?.let { e.put("loc", it) }
            val arr = JSONArray()
            arr.put(e)
            history(ctx).take(49).forEach { arr.put(it) }
            File(ctx.filesDir, HIST_FILE).writeText(arr.toString())
            // keep the legacy Speed History screen (speed_hist) in sync
            Stores.saveSpeed(ctx, down, up)
        } catch (ignored: Exception) {
        }
    }
}
