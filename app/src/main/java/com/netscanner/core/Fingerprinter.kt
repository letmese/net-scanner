package com.netscanner.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Protocol-aware service fingerprinting (nmap-lite, root-free).
 * 1:1 Kotlin port of legacy Fingerprinter — identical probes and verdicts.
 */
object Fingerprinter {

    /** Returns a human-readable software banner for an open port, or null if nothing learned. */
    fun probe(ip: String, port: Int): String? {
        return try {
            when (port) {
                21, 23, 25, 110, 119, 143, 465, 587, 993, 995 ->
                    readGreeting(ip, port)
                22 ->
                    readGreeting(ip, port) // SSH-* banner or raw line
                80, 81, 443, 591, 3000, 5000, 8000, 8008, 8080, 8081, 8443, 8888, 9000 ->
                    httpProbe(ip, port, isTls(port))
                4444, 5060, 554, 1935 ->
                    rtspProbe(ip, port)
                3306 ->
                    readGreeting(ip, port)
                5432 ->
                    readGreeting(ip, port)
                5900, 5901, 5902 -> {
                    val rfb = readGreeting(ip, port)
                    if (rfb != null && rfb.startsWith("RFB")) "$rfb (VNC)" else null
                }
                6379 ->
                    redisPing(ip, port)
                else ->
                    readGreeting(ip, port)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isTls(port: Int) = port == 443 || port == 8443

    private fun trustAllSslSocket(): Socket {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), java.security.SecureRandom())
        val s = ctx.socketFactory.createSocket()
        (s as? SSLSocket)?.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
        return s
    }

    /** Connect and read whatever the server sends first (banners). */
    private fun readGreeting(ip: String, port: Int): String? {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), 1200)
            s.soTimeout = 1500
            val buf = ByteArrayOutputStream()
            val ins: InputStream = s.getInputStream()
            val chunk = ByteArray(256)
            try {
                var n: Int
                while (buf.size() < 256) {
                    n = ins.read(chunk)
                    if (n <= 0) break
                    buf.write(chunk, 0, n)
                    if (buf.toString("ISO-8859-1").contains("\n")) break
                }
            } catch (ignored: Exception) {
            }
            val resp = sanitize(buf.toString("ISO-8859-1"))
            if (resp.isEmpty()) return null

            if (port == 3306) {
                // MySQL greeting: [3B len][seq][proto][version NUL-terminated]
                if (resp.length > 6) {
                    var end = resp.indexOf(0.toChar(), 5)
                    if (end < 0) end = minOf(resp.length, 40)
                    return "MySQL/MariaDB " + resp.substring(5, end)
                }
                return null
            }
            val nl = resp.indexOf('\n')
            var line = if (nl > 0) resp.substring(0, nl) else resp
            line = line.trim()
            if (line.length < 3 || line.matches(Regex("[\\x00-\\x08\\x0e-\\x1f].*"))) return null
            if (line.startsWith("RFB ")) return "$line (VNC)"
            return if (line.length > 60) line.substring(0, 60) else line
        }
    }

    private fun httpProbe(ip: String, port: Int, tls: Boolean): String? {
        val s = if (tls) trustAllSslSocket() else Socket()
        try {
            s.connect(InetSocketAddress(ip, port), 1500)
            s.soTimeout = 2000
            val out: OutputStream = s.getOutputStream()
            out.write(
                ("GET / HTTP/1.1\r\nHost: $ip\r\nUser-Agent: Mozilla/5.0 NetScanner\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.flush()
            val buf = ByteArrayOutputStream()
            val ins = s.getInputStream()
            val chunk = ByteArray(512)
            var n: Int
            var sawEnd = false
            while (buf.size() < 16384) {
                n = ins.read(chunk)
                if (n <= 0) break
                buf.write(chunk, 0, n)
                val soFar = buf.toString("ISO-8859-1")
                if (!sawEnd && soFar.contains("\r\n\r\n")) sawEnd = true
                else if (sawEnd) {
                    val low = soFar.lowercase()
                    if ((low.contains("</title>") || low.contains("<body")) && buf.size() > 512) break
                }
            }
            val resp = buf.toString("ISO-8859-1")
            val server = header(resp, "Server:")
            val powered = header(resp, "X-Powered-By:")
            val loc = header(resp, "Location:")
            val sb = StringBuilder()
            val sc = statusLine(resp)
            if (sc > 0) sb.append("HTTP ").append(sc)
            if (server != null) sb.append(if (sb.isNotEmpty()) " · " else "").append(server)
            if (powered != null) sb.append(" · ").append(powered)
            if (loc != null) sb.append(" → ").append(loc)
            extractTitle(resp)?.let { sb.append(" · “").append(it).append("”") }

            // Anonymous 404s: try UPnP self-description & OPTIONS for identity
            if (sc >= 400 || sb.length <= 8) {
                val upnp = upnpDescription(ip, port, tls)
                if (upnp != null) sb.append(" · ").append(upnp)
                else {
                    val allow = optionsProbe(ip, port, tls)
                    if (!allow.isNullOrEmpty()) sb.append(" · allows: ").append(allow)
                }
            }
            if (tls && sb.isNotEmpty()) sb.append(" [TLS]")
            return if (sb.isEmpty()) null else sb.toString()
        } finally {
            s.close()
        }
    }

    private fun rtspProbe(ip: String, port: Int): String? {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), 1200)
            s.soTimeout = 1500
            val out: OutputStream = s.getOutputStream()
            out.write(("OPTIONS rtsp://$ip:$port RTSP/1.0\r\nCSeq: 1\r\n\r\n").toByteArray())
            out.flush()
            val buf = ByteArrayOutputStream()
            val ins = s.getInputStream()
            val chunk = ByteArray(512)
            try {
                var n: Int
                while (buf.size() < 2048) {
                    n = ins.read(chunk)
                    if (n <= 0) break
                    buf.write(chunk, 0, n)
                    if (buf.toString("ISO-8859-1").contains("\r\n\r\n")) break
                }
            } catch (ignored: Exception) {
            }
            val resp = buf.toString("ISO-8859-1")
            val srv = header(resp, "Server:")
            return srv?.let { "$it (RTSP)" }
        }
    }

    private fun redisPing(ip: String, port: Int): String? {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), 1200)
            s.soTimeout = 1500
            s.getOutputStream().write("PING\r\n".toByteArray())
            s.getOutputStream().flush()
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(64)
            try {
                val n = s.getInputStream().read(chunk)
                if (n > 0) buf.write(chunk, 0, n)
            } catch (ignored: Exception) {
            }
            val r = buf.toString("ISO-8859-1").trim()
            if (r.startsWith("+PONG")) return "Redis (unauthenticated!)"
            return if (r.isEmpty()) null else "Redis-like: $r"
        }
    }

    /** Ask a UPnP device to describe itself via /description.xml */
    private fun upnpDescription(ip: String, port: Int, tls: Boolean): String? {
        return try {
            var resp = simpleGet(ip, port, tls, "/description.xml")
            if (resp == null) resp = simpleGet(ip, port, tls, "/dd.xml")
            if (resp == null || !resp.contains("<")) return null
            val low = resp.lowercase()
            val name = xmlTag(resp, "friendlyName")
            val mfr = xmlTag(resp, "manufacturer")
            val model = xmlTag(resp, "modelName")
            val sb = StringBuilder()
            if (name != null) sb.append(name)
            if (mfr != null) sb.append(if (sb.isNotEmpty()) " by " else "").append(mfr)
            if (model != null) sb.append(" (").append(model).append(")")
            if (sb.isNotEmpty()) return "UPnP: $sb"
            if (low.contains("<html") || low.contains("<xml") || low.contains("<?xml"))
                "serves XML/HTML at /description.xml"
            else null
        } catch (e: Exception) {
            null
        }
    }

    private fun optionsProbe(ip: String, port: Int, tls: Boolean): String? {
        return try {
            val resp = simpleGetRaw(ip, port, tls, "OPTIONS *", null)
            val allow = header(resp, "Allow:")
            val publicH = header(resp, "Public:")
            allow ?: publicH
        } catch (e: Exception) {
            null
        }
    }

    private fun simpleGet(ip: String, port: Int, tls: Boolean, path: String): String? =
        simpleGetRaw(ip, port, tls, "GET $path", path)

    private fun simpleGetRaw(ip: String, port: Int, tls: Boolean, reqLine: String, path: String?): String {
        val s = if (tls) trustAllSslSocket() else Socket()
        try {
            s.connect(InetSocketAddress(ip, port), 1200)
            s.soTimeout = 1500
            val out: OutputStream = s.getOutputStream()
            out.write((reqLine + " HTTP/1.1\r\nHost: $ip\r\nConnection: close\r\n\r\n").toByteArray())
            out.flush()
            val buf = ByteArrayOutputStream()
            val ins = s.getInputStream()
            val chunk = ByteArray(512)
            var n: Int
            while (buf.size() < 8192) {
                n = ins.read(chunk)
                if (n <= 0) break
                buf.write(chunk, 0, n)
            }
            return buf.toString("ISO-8859-1")
        } finally {
            s.close()
        }
    }

    private fun xmlTag(xml: String, tag: String): String? {
        return try {
            val open = xml.lowercase().indexOf("<" + tag.lowercase())
            if (open < 0) return null
            val a = xml.indexOf('>', open)
            val b = xml.lowercase().indexOf("</" + tag.lowercase() + ">", open)
            if (a < 0 || b <= a) null else xml.substring(a + 1, b).trim()
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTitle(resp: String): String? {
        return try {
            val low = resp.lowercase()
            val open = low.indexOf("<title")
            if (open < 0) return null
            val a = resp.indexOf('>', open)
            val b = low.indexOf("</title>", open)
            if (a < 0 || b <= a) null
            else {
                val t = resp.substring(a + 1, b).trim()
                if (t.isEmpty()) null else if (t.length > 50) t.substring(0, 50) else t
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun header(resp: String, name: String): String? {
        for (line in resp.split("\r?\n".toRegex())) {
            if (line.regionMatches(0, name, 0, name.length, ignoreCase = true))
                return line.substring(name.length).trim()
        }
        return null
    }

    private fun statusLine(resp: String): Int {
        if (!resp.startsWith("HTTP/")) return -1
        return try {
            val sp = resp.indexOf(' ')
            resp.substring(sp + 1, sp + 4).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    private fun sanitize(s: String): String {
        val sb = StringBuilder()
        for (c in s.toCharArray()) {
            if (c == '\t' || c == '\n' || c == '\r' || (c.code in 32..126)) sb.append(c)
        }
        return sb.toString().trim()
    }

    /** Friendly verdict for common risky services. */
    fun riskNote(port: Int): String? = when (port) {
        23 -> "Telnet — plaintext passwords, disable it"
        21 -> "FTP — plaintext login, prefer SFTP"
        5555 -> "ADB over network — full device control"
        445, 139 -> "SMB/NetBIOS — WannaCry territory, don't expose"
        3389 -> "RDP — brute-force magnet"
        6379 -> "Redis open without auth = remote shell"
        1900, 5000 -> "UPnP — can punch holes in your router"
        else -> null
    }
}
