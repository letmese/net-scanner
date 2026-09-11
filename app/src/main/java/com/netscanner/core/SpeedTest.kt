package com.netscanner.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/**
 * Headless speed test engine — mirrors the in-app test (ToolRunnerActivity):
 * multi-server download fallback, User-Agent header, HTTP status check and a
 * readable [lastError] when everything fails. Used by AutoSpeedService,
 * SpeedTest screen and NightSpeedWorker. Port of legacy SpeedTestRunner.
 */
object SpeedTestRunner {

    /** Reason the last fully-failed run produced no data ("" on success). */
    @Volatile
    var lastError: String = ""

    /** Error from the most recent individual server attempt (internal). */
    @Volatile
    private var serverErr = ""

    private const val UA = "Mozilla/5.0 (Linux; Android 16) NetScanner/3.9"

    private val DL_SERVERS = arrayOf(
        arrayOf("https://speed.cloudflare.com/__down?bytes=25000000", "Cloudflare"),
        arrayOf("https://nbg1-speed.hetzner.com/100MB.bin", "Hetzner"),
        arrayOf("https://proof.ovh.net/files/10Mb.dat", "OVH"),
        arrayOf("https://mirror.leaseweb.com/speedtest/1000mb.bin", "Leaseweb")
    )

    private val UP_SERVERS = arrayOf(
        "https://speed.cloudflare.com/__up",
        "https://httpbin.org/post",
        "https://postman-echo.com/post"
    )

    fun downloadTest(): Double {
        val errs = StringBuilder()
        for (srv in DL_SERVERS) {
            serverErr = ""
            val mbps = dlFrom(srv[0], 10000)
            if (mbps > 0) {
                lastError = ""
                return mbps
            }
            if (errs.isNotEmpty()) errs.append(" | ")
            errs.append(srv[1]).append(": ")
                .append(if (serverErr.isEmpty()) "no data" else serverErr)
        }
        lastError = errs.toString()
        return 0.0
    }

    fun uploadTest(): Double {
        val errs = StringBuilder()
        for (up in UP_SERVERS) {
            serverErr = ""
            val mbps = upTo(up, 8000)
            if (mbps > 0) return mbps
            if (errs.isNotEmpty()) errs.append(" | ")
            errs.append(hostOf(up)).append(": ")
                .append(if (serverErr.isEmpty()) "no data" else serverErr)
        }
        if (errs.isNotEmpty()) lastError = errs.toString()
        return 0.0
    }

    private fun hostOf(url: String): String =
        try {
            URL(url).host
        } catch (e: Exception) {
            url
        }

    private fun dlFrom(urlStr: String, msDur: Long): Double {
        val bytes = AtomicLong()
        val err = AtomicReference<String?>(null)
        val t0 = System.currentTimeMillis()
        val deadline = t0 + msDur
        val ts = arrayOfNulls<Thread>(4)
        for (i in ts.indices) {
            ts[i] = Thread {
                var ins: InputStream? = null
                var c: HttpURLConnection? = null
                try {
                    c = URL(urlStr).openConnection() as HttpsURLConnection
                    c.connectTimeout = 5000
                    c.readTimeout = 15000
                    c.setRequestProperty("Accept-Encoding", "identity")
                    c.setRequestProperty("User-Agent", UA)
                    val code = c.responseCode
                    if (code < 200 || code >= 300) throw IOException("HTTP $code")
                    ins = c.inputStream
                    val b = ByteArray(65536)
                    var n: Int
                    while (System.currentTimeMillis() < deadline && ins.read(b).also { n = it } > 0)
                        bytes.addAndGet(n.toLong())
                } catch (e: Exception) {
                    err.compareAndSet(null, shortDesc(e))
                } finally {
                    try { ins?.close() } catch (ignored: Exception) {}
                    c?.disconnect()
                }
            }
            ts[i]!!.start()
        }
        for (t in ts) try { t!!.join(msDur + 5000L) } catch (ignored: InterruptedException) {}
        if (bytes.get() == 0L && err.get() != null) serverErr = err.get()!!
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        if (secs < 0.5 || bytes.get() == 0L) return 0.0
        return bytes.get() * 8 / 1e6 / secs
    }

    private fun upTo(urlStr: String, msDur: Long): Double {
        val bytes = AtomicLong()
        val err = AtomicReference<String?>(null)
        val t0 = System.currentTimeMillis()
        val deadline = t0 + msDur
        val ts = arrayOfNulls<Thread>(2)
        for (i in ts.indices) {
            ts[i] = Thread {
                var os: OutputStream? = null
                var c: HttpURLConnection? = null
                try {
                    c = URL(urlStr).openConnection() as HttpsURLConnection
                    c.connectTimeout = 5000
                    c.readTimeout = 15000
                    c.doOutput = true
                    c.requestMethod = "POST"
                    c.setRequestProperty("Content-Type", "application/octet-stream")
                    c.setRequestProperty("User-Agent", UA)
                    os = c.outputStream
                    val chunk = ByteArray(32768)
                    var sent = 0L
                    while (System.currentTimeMillis() < deadline && sent < 12_000_000L) {
                        os.write(chunk)
                        os.flush() // push into socket, keep buffers small
                        sent += chunk.size
                        bytes.addAndGet(chunk.size.toLong())
                        if ((sent and 0x3FFFFL) == 0L) Thread.sleep(5) // let the wire drain
                    }
                    os.flush()
                    c.responseCode
                } catch (e: Exception) {
                    err.compareAndSet(null, shortDesc(e))
                } finally {
                    try { os?.close() } catch (ignored: Exception) {}
                    c?.disconnect()
                }
            }
            ts[i]!!.start()
        }
        for (t in ts) try { t!!.join(msDur + 5000L) } catch (ignored: InterruptedException) {}
        if (err.get() != null) serverErr = err.get()!!
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        if (secs < 4 || bytes.get() == 0L) return 0.0
        return bytes.get() * 8 / 1e6 / secs
    }

    /** Compact exception description for logs/toasts. */
    private fun shortDesc(e: Exception): String {
        val m = e.message
        val s = e.javaClass.simpleName + if (m == null) "" else ": $m"
        return if (s.length > 120) s.substring(0, 120) else s
    }
}
