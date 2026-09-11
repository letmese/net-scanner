package com.netscanner.core

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.FileReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Network discovery helpers — all root-free. 1:1 Kotlin port of the legacy
 * com.netscanner.net.NetworkUtils (v4.8.1) with identical wire behavior.
 */
object NetUtils {

    class LocalNet {
        var ip: String = ""      // e.g. 192.168.1.23
        var prefix: String = ""  // e.g. 192.168.1
        var cidr: Int = 24
    }

    /** Find the active Wi-Fi/LAN IPv4 + /24 prefix. */
    fun localNet(): LocalNet? {
        return try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val n = LocalNet()
                        n.ip = addr.hostAddress ?: continue
                        val cut = n.ip.lastIndexOf('.')
                        n.prefix = n.ip.substring(0, cut + 1)
                        return n
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** ICMP ping via system binary (works without root on Android). */
    fun ping(host: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", "1", host))
        p.waitFor() == 0
    } catch (e: Exception) {
        false
    }

    /**
     * Ping every host in the /24 concurrently.
     * Returns list of alive IPs (unsorted). [onProgress] done/total on the pool threads.
     */
    fun sweep(prefix: String, onProgress: ((Int, Int) -> Unit)? = null): List<String> {
        val alive = Collections.synchronizedList(mutableListOf<String>())
        val pool: ExecutorService = Executors.newFixedThreadPool(64)
        val latch = CountDownLatch(254)
        for (i in 1..254) {
            val host = prefix + i
            pool.execute {
                try {
                    if (ping(host)) alive.add(host)
                } finally {
                    latch.countDown()
                    onProgress?.invoke(254 - latch.count.toInt(), 254)
                }
            }
        }
        try {
            latch.await(60, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
        }
        pool.shutdownNow()
        return alive
    }

    /** Parse /proc/net/arp → ip → mac (skips incomplete entries). Android 10+ usually blocks this. */
    fun arpTable(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val hex = Pattern.compile("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")
        fun consume(r: BufferedReader) {
            var line: String?
            while (r.readLine().also { line = it } != null) {
                val parts = line!!.trim().split("\\s+".toRegex()).toTypedArray()
                if (parts.size < 6 || parts[0] == "IP address") continue
                val m = hex.matcher(parts[3])
                if (m.find() && parts[2] != "0x0") map[parts[0]] = parts[3].uppercase()
            }
        }
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { consume(it) }
        } catch (ignored: Exception) {
        }
        if (map.isEmpty()) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cat /proc/net/arp"))
                consume(BufferedReader(InputStreamReader(p.inputStream)))
                p.waitFor()
            } catch (ignored: Exception) {
            }
        }
        return map
    }

    /**
     * Neighbor table via `ip neigh` (netlink) — the root-free path on Android 10+.
     * Merged with /proc/net/arp for older devices.
     */
    fun neighborTable(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show"))
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    val t = line!!.trim().split("\\s+".toRegex()).toTypedArray()
                    if (t.size < 4) continue
                    if (!t[0].matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))) continue // IPv4 only
                    for (i in 0 until t.size - 1) {
                        if (t[i] == "lladdr") {
                            map[t[0]] = t[i + 1].uppercase()
                            break
                        }
                    }
                }
            }
            p.waitFor()
        } catch (ignored: Exception) {
        }
        map.putAll(arpTable()) // fill gaps on older Androids
        return map
    }

    /** Send Wake-on-LAN magic packet (UDP broadcast ×3). Returns true if sent without error. */
    fun wakeOnLan(mac: String): Boolean {
        return try {
            val hex = mac.replace(":", "").replace("-", "")
            if (hex.length != 12) return false
            val macBytes = ByteArray(6)
            for (i in 0 until 6) macBytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            val packet = ByteArray(102)
            java.util.Arrays.fill(packet, 0, 6, 0xFF.toByte())
            for (i in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)
            DatagramSocket().use { s ->
                s.broadcast = true
                repeat(3) {
                    s.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), 9))
                    Thread.sleep(100)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** One ping: ICMP via system binary → isReachable → TCP connect time. Returns ms or -1. */
    fun pingOnce(host: String): Int {
        for (bin in arrayOf("ping", "/system/bin/ping")) {
            try {
                val t0 = System.currentTimeMillis()
                val p = Runtime.getRuntime().exec(arrayOf(bin, "-c", "1", "-W", "1", host))
                if (p.waitFor() == 0) return (System.currentTimeMillis() - t0).toInt()
            } catch (ignored: Exception) {
            }
        }
        try {
            val t0 = System.currentTimeMillis()
            if (InetAddress.getByName(host).isReachable(1200)) return (System.currentTimeMillis() - t0).toInt()
        } catch (ignored: Exception) {
        }
        for (port in intArrayOf(443, 80, 53)) {
            try {
                Socket().use { s ->
                    val t0 = System.currentTimeMillis()
                    s.connect(InetSocketAddress(host, port), 1500)
                    return (System.currentTimeMillis() - t0).toInt()
                }
            } catch (ignored: Exception) {
            }
        }
        return -1
    }

    /** Traceroute via ping TTL stepping (system binary → root-free). Blocking; call off the UI thread. */
    fun traceroute(host: String, cb: (ttl: Int, ip: String, ms: String?) -> Unit) {
        for (ttl in 1..20) {
            try {
                val p = Runtime.getRuntime().exec(
                    arrayOf("ping", "-c", "1", "-W", "1", "-t", ttl.toString(), host)
                )
                var hopIp: String? = null
                var ms: String? = null
                var done = false
                BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        val l = line!!
                        val ti = l.indexOf("time=")
                        if (ti >= 0) ms = l.substring(ti + 5).trim()
                        var fi = l.indexOf("From ")
                        if (fi < 0) fi = l.indexOf("from ")
                        if (fi >= 0 && !l.contains("bytes from")) {
                            val rest = l.substring(fi + 5).trim()
                            val sp = rest.indexOf(' ')
                            var cand = if (sp > 0) rest.substring(0, sp) else rest
                            cand = cand.replace(":", "").replace("(", "").replace(")", "")
                            if (cand.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) hopIp = cand
                        }
                        if (l.contains("bytes from")) {
                            if (hopIp == null) hopIp = host
                            done = true
                            break
                        }
                        if (l.contains("exceed") && hopIp != null) break // ttl exceeded at this hop
                    }
                }
                p.waitFor()
                val fIp = hopIp
                if (fIp != null) cb(ttl, fIp, ms)
                if (done) return
            } catch (ignored: Exception) {
            }
        }
    }

    class SsdpDevice(
        val friendlyName: String?,
        val manufacturer: String?,
        val model: String?,
        val deviceType: String?,
        val location: String
    )

    /**
     * UPnP/SSDP discovery: M-SEARCH multicast, then fetch each device's
     * description.xml. Blocking (call off the UI thread); returns one entry
     * per discovered device.
     */
    fun ssdpDiscover(): List<SsdpDevice> {
        val locations = LinkedHashSet<String>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 1000
                val q = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                val deadline = System.currentTimeMillis() + 5000
                var sends = 0
                while (System.currentTimeMillis() < deadline) {
                    if (sends < 2) {
                        s.send(DatagramPacket(q, q.size, group, 1900))
                        sends++
                    }
                    try {
                        val buf = ByteArray(2048)
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val resp = String(buf, 0, p.length)
                        headerOf(resp, "LOCATION:")?.let { locations.add(it.trim()) }
                    } catch (ignored: java.net.SocketTimeoutException) {
                    }
                }
            }
        } catch (ignored: Exception) {
        }

        val out = mutableListOf<SsdpDevice>()
        for (loc in locations) {
            try {
                val u = java.net.URL(loc)
                val xml = httpGet(
                    u.host,
                    if (u.port > 0) u.port else 80,
                    if (u.path.isEmpty()) "/" else u.path
                )
                if (xml == null || !xml.contains("<")) {
                    out.add(SsdpDevice(null, null, null, null, loc))
                    continue
                }
                val name = xmlTag(xml, "friendlyName")
                val mfr = xmlTag(xml, "manufacturer")
                val model = xmlTag(xml, "modelName")
                val dtype = xmlTag(xml, "deviceType")
                val extra = StringBuilder()
                // list service types (what it can do)
                var i = 0
                while (extra.length < 200) {
                    val st = xmlTagOcc(xml, "serviceType", i++) ?: break
                    val shortSt = st
                        .replace("urn:schemas-upnp-org:service:", "")
                        .replace("urn:schemas-upnp-org:device:", "")
                    if (!extra.contains(shortSt)) extra.append(shortSt).append(" ")
                    if (i > 12) break
                }
                val combined = when {
                    dtype != null && dtype.contains("InternetGateway") -> "router/gateway"
                    dtype != null && dtype.contains("MediaRenderer") -> "cast/mirroring target"
                    dtype != null && dtype.contains("MediaServer") -> "media server"
                    dtype != null && dtype.contains("Printer") -> "printer"
                    else -> null
                }
                out.add(
                    SsdpDevice(
                        name ?: "?",
                        mfr,
                        model?.let { m -> m + (combined?.let { " · looks like a $it" } ?: "") },
                        if (extra.isNotEmpty()) extra.toString().trim() else null,
                        loc
                    )
                )
            } catch (e: Exception) {
                out.add(SsdpDevice(null, null, null, null, loc))
            }
        }
        return out
    }

    private fun headerOf(resp: String, name: String): String? {
        for (line in resp.split("\r?\n".toRegex())) {
            if (line.regionMatches(0, name, 0, name.length, ignoreCase = true))
                return line.substring(name.length).trim()
        }
        return null
    }

    private fun httpGet(host: String, port: Int, path: String): String? {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 1500)
                s.soTimeout = 2500
                s.getOutputStream().write(
                    ("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n").toByteArray()
                )
                s.getOutputStream().flush()
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(1024)
                val ins: InputStream = s.getInputStream()
                var n = 0
                while (buf.size() < 65536 && ins.read(chunk).also { n = it } > 0) buf.write(chunk, 0, n)
                buf.toString("ISO-8859-1")
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun xmlTag(xml: String, t: String): String? = xmlTagOcc(xml, t, 0)

    private fun xmlTagOcc(xml: String, t: String, occurrence: Int): String? {
        return try {
            val low = xml.lowercase()
            val needle = "<" + t.lowercase()
            var idx = -1
            for (k in 0..occurrence) {
                idx = low.indexOf(needle, idx + 1)
                if (idx < 0) return null
            }
            val a = xml.indexOf('>', idx)
            val b = low.indexOf("</" + t.lowercase(), idx)
            if (a < 0 || b <= a) null else xml.substring(a + 1, b).trim()
        } catch (e: Exception) {
            null
        }
    }

    /** NetBIOS Node Status query (UDP 137) — device names for Windows/Samba/IoT. Blocking <=900ms. */
    fun netbiosNameSync(ip: String): String? {
        var s: DatagramSocket? = null
        return try {
            s = DatagramSocket()
            s.soTimeout = 700
            val b = ByteArrayOutputStream()
            val id = java.util.Random().nextInt(65536)
            b.write((id shr 8) and 0xFF); b.write(id and 0xFF)
            b.write(0x00); b.write(0x10) // recursive query flags
            b.write(0x00); b.write(0x01) // qdcount=1
            repeat(6) { b.write(0) }     // rest of header
            b.write(0x20)                // encoded-name length (32)
            val enc = encodeNBName("*")
            b.write(enc, 0, enc.size)
            b.write(0x00)
            b.write(0x00); b.write(0x21) // type NBSTAT
            b.write(0x00); b.write(0x01) // class IN
            val q = b.toByteArray()
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            parseNbstat(buf, p.length)
        } catch (e: Exception) {
            null
        } finally {
            s?.close()
        }
    }

    private fun encodeNBName(name: String): ByteArray {
        val sb = StringBuilder()
        val padded = String.format("%-15s", name).uppercase()
        val raw = padded.toByteArray(Charsets.US_ASCII)
        for (c in raw) {
            sb.append(('A' + ((c.toInt() shr 4) and 0xF)))
            sb.append(('A' + (c.toInt() and 0xF)))
        }
        sb.append("AA") // null terminator nibbles
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun parseNbstat(r: ByteArray, len: Int): String? {
        return try {
            val answers = ((r[6].toInt() and 0xFF) shl 8) or (r[7].toInt() and 0xFF)
            if (answers == 0 || len < 60) return null
            var i = 12
            var l = r[i].toInt() and 0xFF
            i += 1 + l + 1 + 4   // question name + type/class
            l = r[i].toInt() and 0xFF
            i += 1 + l + 1       // answer name
            i += 8               // type(2)+class(2)+ttl(4)
            i += 2               // rdlength
            val count = r[i].toInt() and 0xFF
            i++
            if (count == 0) return null
            val sb = StringBuilder()
            for (k in 0 until 15) {
                val ch = (r[i + k].toInt() and 0xFF).toChar()
                if (ch.code == 0 || ch == ' ') break
                sb.append(ch)
            }
            if (sb.isEmpty()) null else sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    /** Best-effort device name: fetch http://ip/ and use its <title> or Server header. */
    fun httpTitle(ip: String): String? {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, 80), 800)
                s.soTimeout = 1500
                s.getOutputStream().write(
                    ("GET / HTTP/1.0\r\nHost: $ip" +
                            "\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n").toByteArray()
                )
                s.getOutputStream().flush()
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(1024)
                val ins = s.getInputStream()
                var n = 0
                while (buf.size() < 16384 && ins.read(chunk).also { n = it } > 0) buf.write(chunk, 0, n)
                val resp = buf.toString("ISO-8859-1")
                val a = resp.lowercase().indexOf("<title>")
                val b2 = resp.lowercase().indexOf("</title>")
                if (a >= 0 && b2 > a) {
                    val t = resp.substring(a + 7, b2).replace("\\s+".toRegex(), " ").trim()
                    if (t.length >= 2 && t.matches(Regex("[\\x20-\\x7e]{2,60}"))) return t
                }
                for (line in resp.split("\r\n".toRegex())) {
                    if (line.lowercase().startsWith("server:")) {
                        val v = line.substring(7).trim()
                        if (v.isNotEmpty()) return if (v.length > 40) v.substring(0, 40) else v
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Targeted MAC lookup for one IP: nudge (TCP connect) then read neighbor tables. */
    fun macOf(ip: String): String? {
        neighborTable()[ip]?.let { return it }
        for (port in intArrayOf(80, 443, 8080)) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), 250)
                    return neighborTable()[ip]
                }
            } catch (ignored: Exception) {
            }
        }
        try {
            val p = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show", ip))
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    val t = line!!.trim().split("\\s+".toRegex()).toTypedArray()
                    for (i in 0 until t.size - 1)
                        if (t[i] == "lladdr") {
                            p.waitFor()
                            return t[i + 1].uppercase()
                        }
                }
            }
            p.waitFor()
        } catch (ignored: Exception) {
        }
        return arpTable()[ip]
    }
}
