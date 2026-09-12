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

    /** Idle-latency probe candidates (v5.3.2): first reachable target wins,
     *  so a blocked/unreachable Cloudflare edge no longer kills the test. */
    private val PING_TARGETS = listOf(
        "speed.cloudflare.com" to 443,
        "1.1.1.1" to 443,       // Cloudflare DNS — raw IP, no DNS lookup needed
        "223.5.5.5" to 443,     // AliDNS — reachable on networks where CF is not
        "8.8.8.8" to 53
    )

    /** Download endpoint candidates, tried in order after a liveness probe.
     *  v5.3.3 evidence (laptop, same LAN as the phone, curl):
     *    __down?bytes=100000000  -> HTTP 403 (bytes param exceeds CF limit)
     *    __down?bytes=25000000   -> HTTP 200, 25,000,000 bytes
     *    __down?bytes=10000000   -> HTTP 200, 10,000,000 bytes
     *  The 100M request is what broke downloads on v5.3.2. proof.ovh.net
     *  (12 s for 2.4 MB), speedtest.tele2.net:443 (connect refused),
     *  thinkbroadband (abrupt close), cachefly (24-byte redirect page),
     *  mirror.leaseweb.com (404) and speed.hetzner.de (dead, also banned)
     *  all failed live testing — Cloudflare remains the only verified chain. */
    private val DL_ENDPOINTS = listOf(
        "https://speed.cloudflare.com/__down?bytes=25000000",
        "https://speed.cloudflare.com/__down?bytes=10000000"
    )

    /** Upload endpoint candidates, tried in order after a liveness probe.
     *  NOTE: speed.cloudflare.com/__up returns 403 unless an Origin header
     *  for https://speed.cloudflare.com is present — probe/measure set it.
     *  tele2 http upload.php verified 200 on this LAN (https port refused). */
    private val UL_ENDPOINTS = listOf(
        "https://speed.cloudflare.com/__up",
        "http://speedtest.tele2.net/upload.php"
    )

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

    /** v5.3.3: endpoint actually used + bytes moved per direction (result row). */
    var viaDl by mutableStateOf<String?>(null)
    var viaUl by mutableStateOf<String?>(null)
    var dlMb by mutableStateOf<Double?>(null)
    var ulMb by mutableStateOf<Double?>(null)

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

            // 1) idle latency / jitter / loss (v5.3.2: first reachable probe
            //    target wins; a dead Cloudflare edge rotates to 1.1.1.1 etc.)
            phase = "ping"
            status = "Measuring idle latency…"
            val rtts = mutableListOf<Int>()
            var fails = 0
            var probeIdx = 0
            repeat(12) {
                if (cancelled) return
                val target = PING_TARGETS[probeIdx % PING_TARGETS.size]
                val r = tcpPing(target.first, target.second, 1200)
                if (r == null) {
                    fails++
                    probeIdx++   // rotate to the next candidate target
                } else {
                    rtts.add(r)  // keep this target for loaded-latency probing
                }
                try { Thread.sleep(120) } catch (e: InterruptedException) { return }
            }
            if (rtts.isEmpty()) {
                err = "No latency samples from any of " +
                    PING_TARGETS.joinToString(", ") { it.first } +
                    " — offline, or DNS Sniffer VPN is ON?"
                phase = "error"
                return
            }
            val probeTarget = PING_TARGETS[probeIdx % PING_TARGETS.size]
            idlePing = rtts.average().toFloat()
            jitter = jitterOf(rtts)
            loss = fails * 100f / (fails + rtts.size)

            // 2) connection metadata (best-effort, non-fatal)
            if (!cancelled) fetchConnectionInfo()

            // 3) download phase + loaded latency (v5.3.2: endpoint probe +
            //    fallback chain; failures surface in err/done status, never silent)
            val dlErr = StringBuilder()
            val ulErr = StringBuilder()
            var down = 0.0
            var up = 0.0

            phase = "download"
            status = "Download test…"
            val dlLoaded = mutableListOf<Int>()
            val dlEp = pickEndpoint(download = true, errSink = dlErr)
            if (dlEp != null) {
                down = throughputPhase(
                    download = true, loadedSink = dlLoaded,
                    probe = probeTarget, endpoint = dlEp, errSink = dlErr
                )
            }
            if (cancelled) return
            loadedDown = if (dlLoaded.size >= 3) dlLoaded.average().toFloat() else null

            // 4) upload phase + loaded latency (same fallback treatment)
            phase = "upload"
            status = "Upload test…"
            val ulLoaded = mutableListOf<Int>()
            val ulEp = pickEndpoint(download = false, errSink = ulErr)
            if (ulEp != null) {
                up = throughputPhase(
                    download = false, loadedSink = ulLoaded,
                    probe = probeTarget, endpoint = ulEp, errSink = ulErr
                )
            }
            if (cancelled) return
            loadedUp = if (ulLoaded.size >= 3) ulLoaded.average().toFloat() else null

            // 5) bufferbloat grade from worst-direction latency delta
            val idle = idlePing ?: 0f
            grade = listOfNotNull(loadedDown, loadedUp)
                .maxOrNull()
                ?.let { bloatGrade(it - idle) }

            // v5.3.2: any failed direction is VISIBLE — both dead = error state,
            // one dead = done with the failure appended to the result line.
            val failed = mutableListOf<String>()
            if (dlErr.isNotEmpty()) failed.add("download ($dlErr)")
            if (ulErr.isNotEmpty()) failed.add("upload ($ulErr)")
            if (down == 0.0 && up == 0.0 && failed.isNotEmpty()) {
                err = failed.joinToString(" · ")
                phase = "error"
                return
            }

            phase = "done"
            status = buildString {
                append(
                    String.format(
                        "%.1f ↓ / %.1f ↑ Mbps · %d ms idle · jitter %s · grade %s",
                        down, up, (idlePing ?: 0f).toInt(),
                        jitter?.let { String.format("%.1f ms", it) } ?: "n/a",
                        grade ?: "n/a"
                    )
                )
                // v5.3.3: show which endpoint moved the data and how much
                viaDl?.let { h ->
                    append(" · ↓ via ").append(h)
                    dlMb?.let { mb -> append(String.format(" (%.1f MB)", mb)) }
                }
                viaUl?.let { h ->
                    append(" · ↑ via ").append(h)
                    ulMb?.let { mb -> append(String.format(" (%.1f MB)", mb)) }
                }
                if (failed.isNotEmpty()) append(" · FAILED ").append(failed.joinToString(" / "))
            }
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
        viaDl = null
        viaUl = null
        dlMb = null
        ulMb = null
        err = ""
        status = ""
        publicIp = null
        isp = null
        org = null
        asInfo = null
        edgeColo = null
        serverCity = null
    }

    /** Short host label for endpoint URLs (error lines / probe status). */
    private fun hostOf(ep: String): String = try { URL(ep).host } catch (e: Exception) { ep }

    /**
     * v5.3.2 liveness probe + fallback selection: try each candidate with a
     * tiny request; return the first endpoint that answers, or null after
     * recording every failure reason in [errSink] (surfaced in the UI).
     */
    private fun pickEndpoint(download: Boolean, errSink: StringBuilder): String? {
        val fails = mutableListOf<String>()
        for (ep in if (download) DL_ENDPOINTS else UL_ENDPOINTS) {
            if (cancelled) return null
            status = "Probing ${hostOf(ep)}…"
            val e = probeEndpoint(ep, download)
            if (e == null) return ep
            fails.add("${hostOf(ep)}: $e")
        }
        errSink.append("all endpoints unreachable — ").append(fails.joinToString("; "))
        return null
    }

    /** Tiny probe request; null = endpoint OK, non-null = failure reason. */
    private fun probeEndpoint(ep: String, download: Boolean): String? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(ep).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("User-Agent", UA)
            if (download) {
                c.setRequestProperty("Accept-Encoding", "identity")
                val code = c.responseCode
                if (code !in 200..299) "HTTP $code"
                else { c.inputStream.use { it.read(ByteArray(32768)) }; null }
            } else {
                // speed.cloudflare.com/__up 403s without this Origin header
                c.setRequestProperty("Origin", "https://speed.cloudflare.com")
                c.doOutput = true
                c.requestMethod = "POST"
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.setChunkedStreamingMode(65536)
                c.outputStream.use { it.write(ByteArray(131072)); it.flush() }
                val code = c.responseCode
                if (code in 200..299) null else "HTTP $code"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            c?.disconnect()
        }
    }

    /**
     * Multi-stream throughput against the probed endpoint with a ~5 Hz sampler
     * feeding the gauge/graph and a parallel prober recording loaded latency.
     * Worker errors land in [errSink]; zero bytes transferred is reported too.
     * Returns avg Mbps over the whole window (total bytes / total seconds).
     */
    private fun throughputPhase(
        download: Boolean,
        loadedSink: MutableList<Int>,
        probe: Pair<String, Int>,
        endpoint: String,
        errSink: StringBuilder
    ): Double {
        val bytes = AtomicLong()
        val errRef = AtomicReference<String?>(null)
        val t0 = System.currentTimeMillis()
        val dur = if (download) 10_000L else 8_000L
        val deadline = t0 + dur

        val nStreams = if (download) 4 else 2
        val workers = (0 until nStreams).map {
            Thread {
                try {
                    if (download) {
                        // v5.3.3: a 25 MB response finishes in ~1 s on fast
                        // links, so keep reopening the stream until the window
                        // ends — the old single-shot read stopped counting at
                        // EOF and diluted the average toward zero.
                        var restarts = 0
                        while (!cancelled && System.currentTimeMillis() < deadline && restarts < 40) {
                            var eof = false
                            var conn: HttpURLConnection? = null
                            try {
                                conn = URL(endpoint).openConnection() as HttpURLConnection
                                conn.connectTimeout = 5000
                                conn.readTimeout = 15000
                                conn.setRequestProperty("User-Agent", UA)
                                conn.setRequestProperty("Accept-Encoding", "identity")
                                val code = conn.responseCode
                                if (code < 200 || code >= 300) throw IOException("HTTP $code")
                                val stream = conn.inputStream
                                val b = ByteArray(65536)
                                var n: Int
                                while (!cancelled && System.currentTimeMillis() < deadline) {
                                    n = stream.read(b)
                                    if (n <= 0) { eof = true; break }
                                    bytes.addAndGet(n.toLong())
                                }
                                try { stream.close() } catch (ignored: Exception) {}
                            } finally {
                                conn?.disconnect()
                            }
                            if (!eof) break   // window over or cancelled
                            restarts++
                        }
                    } else {
                        var c: HttpURLConnection? = null
                        var os: OutputStream? = null
                        try {
                            c = URL(endpoint).openConnection() as HttpURLConnection
                            c.connectTimeout = 5000
                            c.readTimeout = 15000
                            c.setRequestProperty("User-Agent", UA)
                            // speed.cloudflare.com/__up 403s without this Origin header
                            c.setRequestProperty("Origin", "https://speed.cloudflare.com")
                            c.doOutput = true
                            c.requestMethod = "POST"
                            c.setRequestProperty("Content-Type", "application/octet-stream")
                            // v5.3.2: stream the body for real. HttpURLConnection's
                            // default buffering mode never puts data on the wire
                            // until close(), so the old gauge counted bytes into a
                            // memory buffer instead of measuring the network.
                            c.setChunkedStreamingMode(65536)
                            os = c.outputStream
                            val chunk = ByteArray(65536)
                            var sent = 0L
                            while (!cancelled && System.currentTimeMillis() < deadline && sent < 25_000_000L) {
                                os.write(chunk)
                                os.flush()
                                sent += chunk.size
                                bytes.addAndGet(chunk.size.toLong())
                                if ((sent and 0x3FFFFL) == 0L) Thread.sleep(5)
                            }
                            // verify the server actually accepted the body
                            val code = c.responseCode
                            if (code < 200 || code >= 300) throw IOException("HTTP $code")
                        } finally {
                            try { os?.close() } catch (ignored: Exception) {}
                            c?.disconnect()
                        }
                    }
                } catch (e: Exception) {
                    errRef.compareAndSet(null, e.message ?: e.javaClass.simpleName)
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

        // loaded-latency prober: TCP pings under load (bufferbloat signal);
        // v5.3.2 targets the same reachable host the idle ping locked onto
        val prober = Thread {
            while (!cancelled && System.currentTimeMillis() < deadline - 400) {
                tcpPing(probe.first, probe.second, 900)?.let { loadedSink.add(it) }
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
        // v5.3.2: surface worker failures and zero-data runs instead of a silent 0.0
        val workerErr = errRef.get()
        if (workerErr != null) {
            errSink.append("${hostOf(endpoint)} ${if (download) "down" else "up"}: $workerErr")
        } else if (b == 0L) {
            errSink.append("${hostOf(endpoint)}: no data transferred")
        }
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
