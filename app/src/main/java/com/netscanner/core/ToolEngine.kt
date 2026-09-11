package com.netscanner.core

import android.content.ContentValues
import android.content.Context
import android.net.DhcpInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Engine for the single-input tools ported 1:1 from legacy ToolRunnerActivity:
 * mdns, snmp, cert, secaudit, dns, netinfo, subnet, speed, extport, probe,
 * whois, cameras, dnshijack, httpforge. All output goes through [append].
 */
object ToolEngine {

    /** Mutable context injected by the UI layer before invoking tools. */
    @Volatile var appCtx: Context? = null

    /** Export text/CSV content into the public Downloads folder (MediaStore on Q+, legacy path below). */
    fun exportCsv(ctx: Context, fileName: String, content: String) {
        val cleaned = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, cleaned)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            java.io.File(dir, cleaned).writeText(content)
        }
    }

    // ───────────────── mDNS (streaming) ─────────────────

    private val MDNS_TYPES = arrayOf(
        "_googlecast._tcp", "_airplay._tcp", "_http._tcp", "_ipp._tcp", "_printer._tcp",
        "_smb._tcp", "_workstation._tcp", "_adb-tls-connect._tcp", "_hap._tcp",
        "_spotify-connect._tcp", "_dlna._tcp", "_nvstream._tcp"
    )

    /** 12-second streaming mDNS browse. Call from a background thread. */
    fun runMdns(ctx: Context, append: (String) -> Unit) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            append("NSD unavailable on this device")
            return
        }
        val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
        var running = true
        var resolving = false
        val pending = AtomicInteger(MDNS_TYPES.size)
        fun drain() {
            if (resolving || !running) return
            val next = queue.poll() ?: return
            resolving = true
            try {
                nsd.resolveService(next, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) { resolving = false; drain() }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val host = i.host?.hostAddress ?: "?"
                        append("📦 ${next.serviceName} → $host:${i.port}  (${next.serviceType})")
                        resolving = false
                        drain()
                    }
                })
            } catch (e: Exception) {
                resolving = false
                drain()
            }
        }
        append("Browsing ${MDNS_TYPES.size} service types… (12s)")
        for (type in MDNS_TYPES) {
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(t: String) {}
                    override fun onDiscoveryStopped(t: String) {}
                    override fun onStartDiscoveryFailed(t: String, e: Int) { pending.decrementAndGet() }
                    override fun onStopDiscoveryFailed(t: String, e: Int) {}
                    override fun onServiceLost(s: NsdServiceInfo) {}
                    override fun onServiceFound(svc: NsdServiceInfo) {
                        // NsdManager resolves one at a time — enqueue and drain sequentially
                        queue.add(svc)
                        drain()
                    }
                })
            } catch (e: Exception) {
                pending.decrementAndGet()
            }
        }
        try { Thread.sleep(12000) } catch (ignored: InterruptedException) {}
        running = false
        append("✅ mDNS browse finished.")
    }

    // ───────────────── SNMP ─────────────────

    fun runSnmp(input: String, append: (String) -> Unit) {
        val ip = input.trim()
        if (ip.isEmpty()) { append("Enter a device IP"); return }
        val req = snmpGet(
            arrayOf(
                byteArrayOf(0x2b.toByte(), 0x06, 0x01, 0x02, 0x01, 0x01, 0x01, 0x00), // sysDescr
                byteArrayOf(0x2b.toByte(), 0x06, 0x01, 0x02, 0x01, 0x01, 0x05, 0x00)  // sysName
            )
        )
        val s = DatagramSocket()
        s.soTimeout = 2000
        s.send(DatagramPacket(req, req.size, InetAddress.getByName(ip), 161))
        val buf = ByteArray(2048)
        try {
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val r = buf.copyOf(p.length)
            var found = 0
            var i = 0
            while (i < r.size - 2 && found < 2) {
                if ((r[i].toInt() and 0xFF) == 0x04 && r[i + 1] > 2 && r[i + 1] < 200 && i + 2 + r[i + 1] <= r.size) {
                    val len = r[i + 1].toInt() and 0xFF
                    val v = String(r, i + 2, len, StandardCharsets.ISO_8859_1)
                    if (v.matches(Regex("[\\x20-\\x7e]{3,}"))) {
                        append((if (found == 0) "sysDescr: " else "sysName:   ") + v)
                        i += len + 1
                        found++
                    }
                }
                i++
            }
            if (found == 0) append("No SNMP response (device may not enable SNMP or uses a non-public community)")
        } catch (e: SocketTimeoutException) {
            append("⏱ No SNMP reply from $ip (SNMP agent off or filtered)")
        }
        s.close()
    }

    private fun snmpGet(oids: Array<ByteArray>): ByteArray {
        val varbinds = ByteArrayOutputStream()
        for (oid in oids) {
            val vb = ByteArrayOutputStream()
            vb.write(0x06); vb.write(oid.size); vb.write(oid, 0, oid.size)
            vb.write(0x05); vb.write(0x00) // NULL
            val vbBody = vb.toByteArray()
            val vbl = ByteArrayOutputStream()
            vbl.write(0x30); vbl.write(vbBody.size); vbl.write(vbBody, 0, vbBody.size)
            val vblB = vbl.toByteArray()
            varbinds.write(vblB, 0, vblB.size)
        }
        val vblBytes = varbinds.toByteArray()
        val pdu = ByteArrayOutputStream()
        pdu.write(0x02); pdu.write(1); pdu.write(0x01)          // request-id=1
        pdu.write(0x02); pdu.write(1); pdu.write(0x00)          // error-status=0
        pdu.write(0x02); pdu.write(1); pdu.write(0x00)          // error-index=0
        pdu.write(vblBytes, 0, vblBytes.size)
        val pduBytes = pdu.toByteArray()
        val body = ByteArrayOutputStream()
        body.write(0x02); body.write(1); body.write(0x00)       // version 0 (v1)
        val comm = "public".toByteArray()
        body.write(0x04); body.write(comm.size); body.write(comm, 0, comm.size)
        body.write(0xA0); body.write(pduBytes.size); body.write(pduBytes, 0, pduBytes.size)
        val bodyBytes = body.toByteArray()
        val msg = ByteArrayOutputStream()
        msg.write(0x30); msg.write(bodyBytes.size); msg.write(bodyBytes, 0, bodyBytes.size)
        return msg.toByteArray()
    }

    // ───────────────── TLS cert ─────────────────

    fun runCert(input: String, append: (String) -> Unit) {
        val t = input
        val host = if (t.contains(":")) t.split(":")[0] else t
        val port = if (t.contains(":")) t.split(":")[1].toInt() else 443
        append("Connecting to $host:$port …")
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), java.security.SecureRandom())
        val s = ctx.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, port), 3000)
        s.soTimeout = 3000
        s.startHandshake()
        val c = s.session.peerCertificates[0] as X509Certificate
        append("Subject:  ${c.subjectX500Principal.name}")
        append("Issuer:   ${c.issuerX500Principal.name}")
        append("Valid:    ${c.notBefore}  →  ${c.notAfter}")
        val daysLeft = (c.notAfter.time - System.currentTimeMillis()) / 86400000L
        append("Expires in: $daysLeft days" + if (daysLeft < 0) "  ⚠️ EXPIRED" else "")
        try {
            val sans = c.subjectAlternativeNames
            if (sans != null) for (o in sans) {
                val pair = o as List<*>
                if (pair.size > 1) append("SAN: ${pair[1]}")
            }
        } catch (ignored: Exception) {
        }
        append("Sig alg:  ${c.sigAlgName}")
        s.close()
    }

    // ───────────────── HTTP security audit ─────────────────

    fun runSecAudit(input: String, append: (String) -> Unit) {
        var u = input
        if (!u.startsWith("http")) u = "http://$u"
        append("Auditing $u …")
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 4000
        c.instanceFollowRedirects = false
        val code = c.responseCode
        append("Status: $code")
        val checks = arrayOf(
            arrayOf("Strict-Transport-Security", "HSTS (forces HTTPS)"),
            arrayOf("Content-Security-Policy", "CSP (blocks injected scripts)"),
            arrayOf("X-Frame-Options", "Clickjacking protection"),
            arrayOf("X-Content-Type-Options", "MIME sniffing protection"),
            arrayOf("Referrer-Policy", "Referrer leakage control"),
            arrayOf("Permissions-Policy", "Browser feature gating")
        )
        var present = 0
        for (h in checks) {
            val v = c.getHeaderField(h[0])
            if (v != null) { present++; append("✅ ${h[0]} — ${h[1]}") }
            else append("❌ missing ${h[0]} — ${h[1]}")
        }
        val grade = if (present >= 5) "A" else if (present == 4) "B" else if (present == 3) "C"
        else if (present == 2) "D" else if (present == 1) "E" else "F"
        append("\nGrade: $grade  ($present/6 headers present)")
        c.disconnect()
    }

    // ───────────────── DNS toolkit ─────────────────

    fun runDns(input: String, append: (String) -> Unit) {
        var domain = input.trim()
        if (domain.isEmpty()) { append("Enter a domain"); return }
        var server = "8.8.8.8"
        if (domain.contains("@")) {
            server = domain.split("@")[1]
            domain = domain.split("@")[0]
        }
        append("Querying $server for $domain …")
        for (type in intArrayOf(1, 28, 15, 16)) // A AAAA MX TXT
            lookupAndLog(server, domain, type, append)

        append("\nResolver speed test (fresh domain each round):")
        val resolvers = arrayOf(
            arrayOf("System", null as String?),
            arrayOf("Google", "8.8.8.8"),
            arrayOf("Cloudflare", "1.1.1.1"),
            arrayOf("Quad9", "9.9.9.9")
        )
        val domains = arrayOf(
            "www.google.com", "www.cloudflare.com", "www.wikipedia.org",
            "www.bing.com", "www.reddit.com", "www.github.com"
        )
        for (r in resolvers) {
            var total = 0L
            var ok = 0
            for (i in 0 until 3) {
                val dom = domains[(System.nanoTime().toInt() + i) % domains.size]
                val ms = if (r[1] == null) systemDnsMs(dom) else dnsQueryMs(r[1]!!, dom, 1)
                if (ms >= 0) { total += ms; ok++ }
                try { Thread.sleep(120) } catch (ignored: InterruptedException) {}
            }
            append("  " + (r[0] + ":").padEnd(10) + " " + (if (ok == 0) "failed" else "${total / ok} ms"))
        }
    }

    private fun lookupAndLog(server: String, domain: String, type: Int, append: (String) -> Unit) {
        try {
            val q = dnsQuery(domain, type)
            val s = DatagramSocket()
            s.soTimeout = 2000
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val r = buf.copyOf(p.length)
            val answers = ((r[6].toInt() and 0xFF) shl 8) or (r[7].toInt() and 0xFF)
            s.close()
            if (answers == 0) return
            // skip header(12) + question
            var i = 12
            while ((r[i].toInt() and 0xFF) != 0) i += (r[i].toInt() and 0xFF) + 1
            i += 5
            val names = arrayOf("", "A", "", "CNAME", "", "MX", "", "", "", "PTR", "", "", "", "TXT", "", "", "AAAA")
            for (a in 0 until answers) {
                if ((r[i].toInt() and 0xC0) == 0xC0) i += 2
                else { while ((r[i].toInt() and 0xFF) != 0) i += (r[i].toInt() and 0xFF) + 1; i++ }
                val rtype = ((r[i].toInt() and 0xFF) shl 8) or (r[i + 1].toInt() and 0xFF)
                val rdlen = ((r[i + 8].toInt() and 0xFF) shl 8) or (r[i + 9].toInt() and 0xFF)
                val d0 = i + 10
                val v: String = when (rtype) {
                    1 -> "${r[d0].toInt() and 0xFF}.${r[d0 + 1].toInt() and 0xFF}.${r[d0 + 2].toInt() and 0xFF}.${r[d0 + 3].toInt() and 0xFF}"
                    28 -> {
                        val sb = StringBuilder()
                        for (k in 0 until 16 step 2)
                            sb.append(String.format("%x:", ((r[d0 + k].toInt() and 0xFF) shl 8) or (r[d0 + k + 1].toInt() and 0xFF)))
                        sb.substring(0, sb.length - 1)
                    }
                    16 -> {
                        val len = r[d0].toInt() and 0xFF
                        String(r, d0 + 1, len, StandardCharsets.ISO_8859_1)
                    }
                    15 -> "priority " + (((r[d0].toInt() and 0xFF) shl 8) or (r[d0 + 1].toInt() and 0xFF)) + " → " + readName(r, d0 + 2)
                    else -> readName(r, d0)
                }
                append("  " + (if (rtype < names.size) names[rtype] else "T$rtype") + "  " + v)
                i = d0 + rdlen
            }
        } catch (ignored: Exception) {
        }
    }

    private fun readName(r: ByteArray, off: Int): String {
        val sb = StringBuilder()
        var i = off
        var hops = 0
        while (hops++ < 8) {
            val len = r[i].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                val ptr = ((len and 0x3F) shl 8) or (r[i + 1].toInt() and 0xFF)
                sb.append(readName(r, ptr))
                break
            }
            if (sb.isNotEmpty()) sb.append(".")
            sb.append(String(r, i + 1, len, StandardCharsets.ISO_8859_1))
            i += len + 1
        }
        return sb.toString()
    }

    private fun dnsQuery(name: String, type: Int): ByteArray {
        val b = ByteArrayOutputStream()
        val id = Random().nextInt(65536)
        b.write((id shr 8) and 0xFF); b.write(id and 0xFF)
        b.write(0x01); b.write(0x00) // RD
        b.write(0x00); b.write(0x01) // qdcount
        b.write(0); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0)
        for (label in name.split(".")) {
            b.write(label.length)
            b.write(label.toByteArray(), 0, label.length)
        }
        b.write(0)
        b.write((type shr 8) and 0xFF); b.write(type and 0xFF)
        b.write(0); b.write(1) // IN
        return b.toByteArray()
    }

    private fun dnsQueryMs(server: String, name: String, type: Int): Long {
        return try {
            val q = dnsQuery(name, type)
            val s = DatagramSocket()
            s.soTimeout = 1500
            val t0 = System.currentTimeMillis()
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(512)
            s.receive(DatagramPacket(buf, buf.size))
            s.close()
            System.currentTimeMillis() - t0
        } catch (e: Exception) {
            -1
        }
    }

    private fun systemDnsMs(name: String): Long {
        return try {
            val t0 = System.currentTimeMillis()
            InetAddress.getByName(name)
            System.currentTimeMillis() - t0
        } catch (e: Exception) {
            -1
        }
    }

    // ───────────────── Net info ─────────────────

    fun runNetInfo(ctx: Context, append: (String) -> Unit) {
        val n = NetUtils.localNet()
        if (n != null) {
            append("Local IP:    ${n.ip}")
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp: DhcpInfo? = wm.dhcpInfo
            if (dhcp != null) {
                append("Gateway:     ${intToIp(dhcp.gateway)}")
                append("Netmask:     ${intToIp(dhcp.netmask)}")
                append("DNS:         ${intToIp(dhcp.dns1)}")
                append("Lease:       ${dhcp.leaseDuration / 3600} h")
            }
        }
        try {
            val pub = httpGetText("https://api.ipify.org")
            append("Public IP:   $pub")
            val info = httpGetText("https://ipinfo.io/json")
            append("ISP/Org:     ${jsonVal(info, "org")}")
            append("City:        ${jsonVal(info, "city")}, ${jsonVal(info, "region")} ${jsonVal(info, "country")}")
        } catch (e: Exception) {
            append("Public IP lookup failed: ${e.message}")
        }
    }

    private fun intToIp(i: Int): String =
        "${i and 0xFF}.${(i shr 8) and 0xFF}.${(i shr 16) and 0xFF}.${(i shr 24) and 0xFF}"

    private fun jsonVal(json: String, key: String): String {
        val i = json.indexOf("\"$key\":\"")
        if (i < 0) return "?"
        val a = i + key.length + 4
        val b = json.indexOf('"', a)
        return json.substring(a, b)
    }

    fun httpGetText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 5000
        val b = ByteArrayOutputStream()
        val ins = c.inputStream
        val chunk = ByteArray(1024)
        var n = 0
        while (ins.read(chunk).also { n = it } > 0) b.write(chunk, 0, n)
        c.disconnect()
        return b.toString("UTF-8").trim()
    }

    // ───────────────── Subnet calc ─────────────────

    fun runSubnet(input: String, append: (String) -> Unit) {
        val parts = input.trim().split("/")
        try {
            val prefix = if (parts.size > 1) parts[1].toInt() else 24
            val ip = ipToInt(parts[0])
            val mask = if (prefix == 0) 0 else (0xFFFFFFFFu.toInt() shl (32 - prefix))
            val net = ip and mask
            val bcast = net or mask.inv()
            append("Network:     ${intToStr(net)}/$prefix")
            append("Netmask:     ${intToStr(mask)}")
            append("Wildcard:    ${intToStr(mask.inv())}")
            append("First host:  ${intToStr(net + 1)}")
            append("Last host:   ${intToStr(bcast - 1)}")
            append("Broadcast:   ${intToStr(bcast)}")
            val hosts = if (prefix >= 31) (1L shl (32 - prefix)) else ((1L shl (32 - prefix)) - 2)
            append("Usable hosts: $hosts")
        } catch (e: Exception) {
            append("Format: 192.168.1.0/24")
        }
    }

    private fun ipToInt(ip: String): Int {
        val p = ip.split(".")
        return (p[0].toInt() shl 24) or (p[1].toInt() shl 16) or (p[2].toInt() shl 8) or p[3].toInt()
    }

    private fun intToStr(i: Int): String =
        "${(i shr 24) and 0xFF}.${(i shr 16) and 0xFF}.${(i shr 8) and 0xFF}.${i and 0xFF}"

    // ───────────────── Raw probe console ─────────────────

    fun runProbe(input: String, append: (String) -> Unit) {
        var t = input
        if (t.isEmpty()) {
            append("Format: host:port [payload]\nPrefix 'udp ' for UDP. Empty payload = just connect.")
            return
        }
        val udp = t.startsWith("udp ")
        if (udp) t = t.substring(4).trim()
        val sp2 = t.indexOf(' ')
        val hp = if (sp2 > 0) t.substring(0, sp2) else t
        val payload = if (sp2 > 0) t.substring(sp2 + 1) else ""
        val host = if (hp.contains(":")) hp.split(":")[0] else hp
        val port = if (hp.contains(":")) hp.split(":")[1].toInt() else 80
        append((if (udp) "UDP" else "TCP") + " probe → $host:$port")
        if (udp) {
            val s = DatagramSocket()
            s.soTimeout = 2500
            val data = (if (payload.isEmpty()) "\r\n" else payload).toByteArray()
            s.send(DatagramPacket(data, data.size, InetAddress.getByName(host), port))
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
                append("← ${p.length} bytes:")
                append(String(buf, 0, p.length).replace(Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]"), "."))
            } catch (e: SocketTimeoutException) {
                append("(no reply within 2.5s)")
            }
            s.close()
        } else {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 3000)
            s.soTimeout = 2500
            val os = s.getOutputStream()
            os.write((if (payload.isEmpty()) "" else payload + "\r\n").toByteArray())
            os.flush()
            val ins = s.getInputStream()
            val b = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            var n = 0
            while (b.size() < 4096 && ins.read(buf).also { n = it } > 0) b.write(buf, 0, n)
            s.close()
            if (b.size() == 0) append("(connected — server said nothing)")
            else append(b.toString("ISO-8859-1").replace(Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]"), "."))
        }
        AppLog.log("probe done $host:$port")
    }

    // ───────────────── Whois / IP intel (RDAP) ─────────────────

    fun runWhois(input: String, append: (String) -> Unit) {
        val q = input.trim()
        if (q.isEmpty()) { append("Enter a domain (example.com) or IP (1.1.1.1)"); return }
        val isIp = q.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
        val url = "https://rdap.org/" + (if (isIp) "ip/" else "domain/") + q
        append("RDAP lookup…")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/rdap+json")
        val code = c.responseCode
        if (code != 200) { append("HTTP $code from rdap.org"); return }
        val r = c.inputStream.bufferedReader()
        val sb = StringBuilder()
        var ln: String?
        while (r.readLine().also { ln = it } != null) sb.append(ln)
        r.close()
        c.disconnect()
        val o = JSONObject(sb.toString())
        append("Object:  ${o.optString("ldhName", o.optString("handle", q))}")
        if (o.has("name")) append("Name:    ${o.optString("name")}")
        if (o.has("type")) append("Type:    ${o.optString("type")}")
        if (o.has("country")) append("Country: ${o.optString("country")}")
        if (o.has("startAddress"))
            append("Range:   ${o.optString("startAddress")} → ${o.optString("endAddress")}")
        val st = o.optJSONArray("status")
        if (st != null && st.length() > 0) {
            val s2 = StringBuilder()
            for (i in 0 until st.length()) s2.append(st.getString(i)).append(" ")
            append("Status:  ${s2.toString().trim()}")
        }
        val ev = o.optJSONArray("events")
        if (ev != null) for (i in 0 until ev.length())
            append("  ${ev.getJSONObject(i).optString("eventAction")}: ${ev.getJSONObject(i).optString("eventDate")}")
        val ents = o.optJSONArray("entities")
        if (ents != null) for (i in 0 until ents.length()) {
            val e2 = ents.getJSONObject(i)
            val roles = e2.optJSONArray("roles") ?: continue
            for (k in 0 until roles.length()) {
                if ("registrar" == roles.getString(k)) {
                    append("Registrar: ${e2.optString("handle", "?")}")
                    break
                }
            }
        }
        AppLog.log("whois done: $q")
    }

    // ───────────────── IP camera finder ─────────────────

    fun runCameras(ctx: Context, input: String, append: (String) -> Unit) {
        var prefix = input.trim()
        if (prefix.isEmpty()) {
            val n = NetUtils.localNet()
            prefix = n?.prefix ?: ""
        }
        if (prefix.isEmpty()) { append("No network"); return }
        append("Sweeping ${prefix}1–254 for RTSP cameras…")
        val found = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(254)
        val pool = Executors.newFixedThreadPool(64)
        for (i in 1..254) {
            val ip = prefix + i
            pool.execute {
                try {
                    for (port in intArrayOf(554, 8554)) {
                        try {
                            Socket().use { s ->
                                s.connect(InetSocketAddress(ip, port), 600)
                                s.soTimeout = 1500
                                val os = s.getOutputStream()
                                os.write("OPTIONS rtsp://$ip:$port RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
                                os.flush()
                                val b = ByteArray(128)
                                val rn = s.getInputStream().read(b)
                                if (rn > 0 && String(b, 0, rn).startsWith("RTSP/")) {
                                    found.add("$ip:$port")
                                    return@use
                                }
                            }
                        } catch (ignored: Exception) {
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(70, TimeUnit.SECONDS)
        pool.shutdownNow()
        if (found.isEmpty()) {
            append("No RTSP cameras found on this network.")
        } else {
            append("🎥 ${found.size} camera(s) found:")
            for (f in found) {
                val host = f.split(":")[0]
                val title = NetUtils.httpTitle(host)
                append("  $f" + (title?.let { "  — $it" } ?: ""))
            }
            append("\nView streams with a VLC-style app:\n  rtsp://user:pass@IP:554/stream")
        }
        AppLog.log("camera scan: ${found.size} found")
    }

    // ───────────────── DNS hijack detector ─────────────────

    fun runDnsHijack(append: (String) -> Unit) {
        append("Testing resolvers for DNS hijacking…\n")
        val rnd = "nx" + (System.currentTimeMillis() % 1000000) + "-nonexistent.example.com"
        val resolvers = arrayOf(
            arrayOf("System", null as String?),
            arrayOf("Google", "8.8.8.8"),
            arrayOf("Cloudflare", "1.1.1.1"),
            arrayOf("Quad9", "9.9.9.9")
        )
        var hijack = false
        for (r in resolvers) {
            val ans = if (r[1] == null) (if (systemResolves(rnd)) 1 else 0) else dnsAnswerCount(r[1]!!, rnd)
            if (ans > 0) hijack = true
            append("  " + r[0]!!.padEnd(11) + " fake-domain answers: " + ans + (if (ans > 0) "   ⚠️ HIJACK?" else "   ok"))
        }
        append("")
        var first: String? = null
        var mismatch = false
        for (r in resolvers) {
            val ips = if (r[1] == null) systemIps("google.com") else dnsAnswerIps(r[1]!!, "google.com")
            if (ips.isEmpty()) continue
            if (first == null) first = ips
            else if (ips != first) {
                mismatch = true
                append("  ⚠️ ${r[0]} returns different IPs for google.com!")
            }
        }
        append("")
        if (hijack) append("❌ POSSIBLE DNS HIJACKING — a resolver answered for a\n   nonexistent domain. Check your router's DNS settings.")
        else if (mismatch) append("⚠️ Resolver inconsistency detected (could be CDN geo).")
        else append("✅ Clean — all resolvers agree, no NXDOMAIN hijacks.")
        AppLog.log("dnshijack: " + (if (hijack) "SUSPECTED" else "clean"))
    }

    private fun systemResolves(name: String): Boolean =
        try { InetAddress.getByName(name); true } catch (e: Exception) { false }

    private fun systemIps(name: String): String =
        try {
            StringBuilder().apply {
                for (a in InetAddress.getAllByName(name)) append(a.hostAddress).append(",")
            }.toString()
        } catch (e: Exception) {
            ""
        }

    private fun dnsAnswerCount(server: String, name: String): Int {
        return try {
            val q = dnsQuery(name, 1)
            val s = DatagramSocket()
            s.soTimeout = 2000
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(512)
            s.receive(DatagramPacket(buf, buf.size))
            s.close()
            ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        } catch (e: Exception) {
            -1
        }
    }

    private fun dnsAnswerIps(server: String, name: String): String {
        return try {
            val q = dnsQuery(name, 1)
            val s = DatagramSocket()
            s.soTimeout = 2000
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            s.close()
            val r = buf.copyOf(p.length)
            val answers = ((r[6].toInt() and 0xFF) shl 8) or (r[7].toInt() and 0xFF)
            if (answers == 0) return ""
            val sb = StringBuilder()
            var i = 12
            while ((r[i].toInt() and 0xFF) != 0) i += (r[i].toInt() and 0xFF) + 1
            i += 5
            for (a in 0 until answers) {
                if ((r[i].toInt() and 0xC0) == 0xC0) i += 2
                else { while ((r[i].toInt() and 0xFF) != 0) i += (r[i].toInt() and 0xFF) + 1; i++ }
                val rtype = ((r[i].toInt() and 0xFF) shl 8) or (r[i + 1].toInt() and 0xFF)
                val rdlen = ((r[i + 8].toInt() and 0xFF) shl 8) or (r[i + 9].toInt() and 0xFF)
                val d0 = i + 10
                if (rtype == 1)
                    sb.append(r[d0].toInt() and 0xFF).append(".").append(r[d0 + 1].toInt() and 0xFF).append(".")
                        .append(r[d0 + 2].toInt() and 0xFF).append(".").append(r[d0 + 3].toInt() and 0xFF).append(",")
                i = d0 + rdlen
            }
            sb.toString()
        } catch (e: Exception) {
            "?"
        }
    }

    // ───────────────── HTTP request forge ─────────────────

    fun runForge(input: String, append: (String) -> Unit) {
        val t = input
        if (t.isEmpty()) {
            append("Format:  METHOD url | Header: value | body\nExample:\n  POST http://192.168.1.1/api | Content-Type: application/json | {\"cmd\":\"reboot\"}")
            return
        }
        val parts = t.split("|")
        val mu = parts[0].trim().split(Regex("\\s+"), 2)
        val method = mu[0].uppercase()
        var url = if (mu.size > 1) mu[1].trim() else mu[0]
        if (!url.startsWith("http")) url = "http://$url"
        append("$method $url\n")
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 8000
        var body: String? = null
        for (i in 1 until parts.size) {
            val p2 = parts[i].trim()
            if (p2.isNotEmpty() && p2.contains(":") && !p2.startsWith("{") && !p2.startsWith("<") &&
                body == null && p2[0].isLetter()
            ) {
                val ci = p2.indexOf(':')
                c.setRequestProperty(p2.substring(0, ci).trim(), p2.substring(ci + 1).trim())
            } else if (p2.isNotEmpty()) {
                body = p2
            }
        }
        if (body != null) {
            c.doOutput = true
            val os = c.outputStream
            os.write(body.toByteArray())
            os.flush()
            os.close()
        }
        val code = c.responseCode
        append("HTTP $code ${c.responseMessage}\n")
        for (h in c.headerFields.entries)
            if (h.key != null) append("  ${h.key}: ${h.value[0]}")
        val ins = if (code >= 400) c.errorStream else c.inputStream
        if (ins != null) {
            val b2 = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            var n = 0
            while (b2.size() < 4096 && ins.read(buf).also { n = it } > 0) b2.write(buf, 0, n)
            append("\n" + b2.toString("UTF-8").replace(Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]"), "."))
        }
        AppLog.log("forge $method $url → $code")
    }

    // ───────────────── External port check ─────────────────

    fun runExtPort(append: (String) -> Unit) {
        append("Finding your public IP…")
        val ip = httpGetText("https://api.ipify.org")
        append("Public IP: $ip")
        append("Asking hackertarget to scan it (~15s)…")
        try {
            val c = URL("https://api.hackertarget.com/nmap/?q=$ip").openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 60000
            val b = ByteArrayOutputStream()
            val ins = c.inputStream
            val chunk = ByteArray(1024)
            var n = 0
            while (ins.read(chunk).also { n = it } > 0) b.write(chunk, 0, n)
            c.disconnect()
            append(b.toString("UTF-8").trim())
            AppLog.log("extport done for $ip")
        } catch (e: Exception) {
            append("❌ External scan failed: ${e.message}\n(the free API is rate-limited — retry later)")
        }
    }

    // ───────────────── Speed test (in-tool, with live sampling) ─────────────────

    /**
     * Port of ToolRunnerActivity.runSpeed + private dlFrom/upTo. Live sampler
     * pushes cumulative-rate updates via [onRate] (mbps, isDown).
     */
    fun runSpeed(onLog: (String) -> Unit, onRate: (mbps: Float, isDown: Boolean) -> Unit = { _, _ -> }) {
        val t0 = System.currentTimeMillis()
        val ping = NetUtils.pingOnce("1.1.1.1")
        onLog(if (ping >= 0) "Latency: $ping ms" else "Latency: n/a")

        onLog("Testing download (10s, 4 streams)…")
        val down = downloadTestLive(onRate)
        if (down > 0) {
            onLog(String.format("⬇️  Download: %.1f Mbps", down))
            onLog("Testing upload…")
            val up = uploadTestLive(onRate)
            if (up > 0) {
                onLog(String.format("⬆️  Upload: %.1f Mbps", up))
            } else onLog("⬆️  Upload failed — check connection and retry")
            appCtx?.let { Stores.saveSpeed(it, down, up) }
            onLog("✅ Done")
        } else {
            onLog("❌ Download failed on all servers.\nTip: if the DNS Sniffer VPN is ON, turn it OFF and retry.")
        }
    }

    private fun downloadTestLive(onRate: (Float, Boolean) -> Unit): Double {
        val servers = arrayOf(
            arrayOf("https://speed.cloudflare.com/__down?bytes=25000000", "Cloudflare"),
            arrayOf("https://nbg1-speed.hetzner.com/100MB.bin", "Hetzner"),
            arrayOf("https://proof.ovh.net/files/10Mb.dat", "OVH"),
            arrayOf("https://mirror.leaseweb.com/speedtest/1000mb.bin", "Leaseweb")
        )
        for (srv in servers) {
            val mbps = dlFrom(srv[0], 10000, onRate)
            if (mbps > 0) return mbps
        }
        return 0.0
    }

    private fun dlFrom(urlStr: String, msDur: Long, onRate: (Float, Boolean) -> Unit): Double {
        val bytes = AtomicLong()
        val t0 = System.currentTimeMillis()
        val deadline = t0 + msDur
        startRateSampler(t0, deadline, bytes, true, onRate)
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
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) NetScanner/2.6")
                    val code = c.responseCode
                    if (code < 200 || code >= 300) throw java.io.IOException("HTTP $code ${c.responseMessage}")
                    ins = c.inputStream
                    val b = ByteArray(65536)
                    var n = 0
                    while (System.currentTimeMillis() < deadline && ins.read(b).also { n = it } > 0)
                        bytes.addAndGet(n.toLong())
                } catch (ignored: Exception) {
                } finally {
                    try { ins?.close() } catch (ignored: Exception) {}
                    c?.disconnect()
                }
            }
            ts[i]!!.start()
        }
        for (t in ts) try { t!!.join(msDur + 5000) } catch (ignored: InterruptedException) {}
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        if (secs < 0.5 || bytes.get() == 0L) return 0.0
        return bytes.get() * 8 / 1e6 / secs
    }

    private fun uploadTestLive(onRate: (Float, Boolean) -> Unit): Double {
        val bytes = AtomicLong()
        val t0 = System.currentTimeMillis()
        val deadline = t0 + 8000
        startRateSampler(t0, deadline, bytes, false, onRate)
        val ts = arrayOfNulls<Thread>(2)
        for (i in ts.indices) {
            ts[i] = Thread {
                var os: OutputStream? = null
                var c: HttpURLConnection? = null
                try {
                    c = URL("https://speed.cloudflare.com/__up").openConnection() as HttpsURLConnection
                    c.connectTimeout = 5000
                    c.readTimeout = 15000
                    c.doOutput = true
                    c.requestMethod = "POST"
                    c.setRequestProperty("Content-Type", "application/octet-stream")
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
                } catch (ignored: Exception) {
                } finally {
                    try { os?.close() } catch (ignored: Exception) {}
                    c?.disconnect()
                }
            }
            ts[i]!!.start()
        }
        for (t in ts) try { t!!.join(15000) } catch (ignored: InterruptedException) {}
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        if (secs < 4 || bytes.get() == 0L) return 0.0
        return bytes.get() * 8 / 1e6 / secs
    }

    /** Samples cumulative bytes every 500 ms and feeds the live gauges/chart. */
    private fun startRateSampler(
        t0: Long, deadline: Long, bytes: AtomicLong, down: Boolean,
        onRate: (Float, Boolean) -> Unit
    ) {
        val sampler = Thread {
            var prevB = 0L
            var prevT = t0
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500)
                } catch (ignored: InterruptedException) {
                    return@Thread
                }
                val now = System.currentTimeMillis()
                val b = bytes.get()
                val inst = (b - prevB) * 8 / 1e6 / maxOf(0.25, (now - prevT) / 1000.0)
                prevB = b
                prevT = now
                onRate(inst.toFloat(), down)
            }
        }
        sampler.isDaemon = true
        sampler.start()
    }
}
