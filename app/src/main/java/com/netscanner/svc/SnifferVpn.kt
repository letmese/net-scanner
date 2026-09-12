package com.netscanner.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.netscanner.MainActivity
import com.netscanner.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.LinkedHashSet
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v5.1.5 DNS Sniffer VPN — legacy transparent-interception mechanism (v4.7/4.8
 * port, confirmed base) with a full in-app debug log (Settings → DNS Sniffer
 * Debug) and this round's static fixes:
 *
 *  1. IPv6 DNS servers from LinkProperties are NO LONGER half-routed into the
 *     IPv4-only TUN. Previously addRoute(v6Ip, 32) silently routed chunks of
 *     the IPv6 DNS space into a TUN whose read path drops every non-IPv4
 *     packet — those queries vanished and lookups stalled. Now only IPv4 DNS
 *     IPs are routed; since the builder only configures an IPv4 address and an
 *     IPv4 DNS server (8.8.8.8), the system inside the VPN falls back to IPv4
 *     DNS instead of half-routing IPv6.
 *  2. addDnsServer stays aligned with the legacy builder: a REAL resolver
 *     (8.8.8.8) so the VPN network has a valid DNS configuration.
 *  3. Routes cover every IPv4 DNS server enumerated from all networks'
 *     LinkProperties plus the legacy public resolver set, each as /32 — so
 *     plaintext queries to the network's own resolvers ride the TUN.
 *  4. protect() is called on the forward socket BEFORE it is connected (and
 *     before any traffic); the boolean result is logged — a false/failed
 *     protect is flagged loudly since the socket would loop back into the TUN.
 *  5. The read loop survives malformed packets: per-packet handling is
 *     exception-isolated with stack-trace logging, and TUN read errors back
 *     off (50ms) with a consecutive-failure cap instead of spinning/bricking.
 *  6. The forward pool is REBUILT on every start — stopTun() shuts it down
 *     (interrupting blocked workers), and reusing a shut-down pool would
 *     silently reject every packet after a stop/start cycle.
 *
 * Legacy mechanism kept verbatim (what actually worked for years):
 *  - TUN address 10.111.222.1/32, DNS server 8.8.8.8.
 *  - Routes = real DNS IPs as /32; only DNS rides the TUN, normal data is
 *    untouched.
 *  - read loop: UDP dst:53 packets are forwarded through a PROTECTED socket
 *    to the packet's ORIGINAL destination (captive/ISP/LAN resolver
 *    semantics), falling back to public resolvers; the response is written
 *    back with src = original dst IP, src port 53, dst = original src + port,
 *    UDP checksum 0 (legal "none" on IPv4), IP checksum recomputed.
 *  - The sniffer app itself is excluded from the VPN.
 */
class SnifferVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var stopFlag = false
    private var worker: Thread? = null

    /** Legacy forward pipeline: fixed 4-worker pool, one task per DNS packet.
     *  Nullable + rebuilt in startTun() — shutdownNow() in stopTun() makes a
     *  stale pool reject every future task (static fix #6). */
    private var forwardPool: ThreadPoolExecutor? = null

    /** Legacy route sources: hardcoded publics + the network's IPv4 DNS. */
    private val routedDns = LinkedHashSet<String>()

    override fun onCreate() {
        super.onCreate()
        SnifferDebugLog.i("service: onCreate")
        collectNetworkDns()
    }

    /**
     * Enumerate the device's real DNS servers from every network's
     * LinkProperties (static fix #3 + #1): IPv4 servers are routed as /32,
     * IPv6 servers are deliberately NOT routed (IPv4-only TUN) and logged.
     */
    private fun collectNetworkDns() {
        routedDns.clear()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (net in cm.allNetworks) {
                val lp: LinkProperties? = cm.getLinkProperties(net)
                val ifName = lp?.interfaceName ?: "?"
                lp?.dnsServers?.forEach { s ->
                    val h = s.hostAddress ?: return@forEach
                    if (s is Inet4Address && !s.isLoopbackAddress) {
                        if (routedDns.add(h)) {
                            SnifferDebugLog.i("net-dns: v4 $h [$ifName] — will route /32")
                        }
                    } else {
                        SnifferDebugLog.i(
                            "net-dns: $h [$ifName] — IPv6/loopback, NOT routed " +
                                "(IPv4-only TUN, avoid half-routing IPv6)"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            SnifferDebugLog.w("collectNetworkDns failed: ${e.message}", e)
        }
        routedDns.addAll(DEFAULT_DNS)
        SnifferDebugLog.i("net-dns: final route set = $routedDns")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SnifferDebugLog.i("lifecycle: stop requested (ACTION_STOP)")
            stopSelf()
            return START_NOT_STICKY
        }
        SnifferDebugLog.i("lifecycle: onStartCommand — establishing transparent TUN")
        startAsForeground()
        startTun()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHAN, "DNS Sniffer", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHAN) else Notification.Builder(this)
        b.setContentTitle("DNS Sniffer active")
            .setContentText("Capturing DNS queries -- tap to open")
            .setSmallIcon(R.drawable.ic_stat_net)
            .setOngoing(true)
            .setContentIntent(pi)
        startForeground(NOTIF_ID, b.build())
    }

    private fun startTun() {
        stopTun()
        // Static fix #6: rebuild the forward pool (stopTun shut the old one down).
        forwardPool = java.util.concurrent.Executors.newFixedThreadPool(4) as ThreadPoolExecutor
        SnifferDebugLog.i("pool: forward pool rebuilt (4 workers)")

        try {
            val b = Builder()
            b.setSession("NetScanner DNS Sniffer")
            SnifferDebugLog.i("builder: session=\"NetScanner DNS Sniffer\"")
            b.addAddress("10.111.222.1", 32)
            SnifferDebugLog.i("builder: addAddress 10.111.222.1/32 (TUN local, IPv4-only by design)")
            SnifferDebugLog.i("builder: MTU not set (legacy default, matches old version)")
            b.addDnsServer("8.8.8.8")   // legacy: REAL resolver so the VPN DNS config is valid
            SnifferDebugLog.i("builder: addDnsServer 8.8.8.8 (legacy-aligned real resolver)")

            collectNetworkDns()
            var routed = 0
            routedDns.forEach { ip ->
                try { b.addRoute(ip, 32); routed++; SnifferDebugLog.i("builder: addRoute $ip/32") }
                catch (e: Exception) { SnifferDebugLog.w("builder: addRoute $ip/32 FAILED: ${e.message}", e) }
            }
            SnifferDebugLog.i("builder: $routed DNS routes total (/32 each — DNS-only TUN)")

            try { b.addDisallowedApplication(packageName) } catch (e: Exception) {
                SnifferDebugLog.w("builder: addDisallowedApplication failed: ${e.message}", e)
            }
            SnifferDebugLog.i("builder: self (${packageName}) excluded from the VPN")

            tun = try {
                val p = b.establish()
                if (p != null) SnifferDebugLog.i("establish: OK, tun fd open (fd=${p.fileDescriptor})")
                else SnifferDebugLog.e("establish: returned NULL (check VPN consent / other active VPN)")
                p
            } catch (e: Exception) {
                SnifferDebugLog.e("establish: threw", e)
                null
            }
        } catch (e: Exception) {
            SnifferDebugLog.e("builder: failed", e)
            tun = null
        }
        if (tun == null) {
            // No zombie state — UI must see the VPN as stopped.
            SnifferDebugLog.e("lifecycle: establishment FAILED, service stays up but idle")
            running.set(false)
            return
        }

        val fd = tun!!.fileDescriptor
        stopFlag = false
        SnifferDebugLog.i("lifecycle: TUN established, read loop starting")
        worker = Thread {
            running.set(true)
            val input = FileInputStream(fd)
            val output = FileOutputStream(fd)
            val buf = ByteArray(32767)
            var readErrs = 0
            try {
                while (!stopFlag) {
                    val n = try { input.read(buf) } catch (e: Exception) {
                        readErrs++
                        if (stopFlag || tun == null) {
                            SnifferDebugLog.i("read-loop: TUN closed, exiting")
                            break
                        }
                        if (readErrs > 100) {
                            SnifferDebugLog.e("read-loop: 100 consecutive read errors, exiting", e)
                            break
                        }
                        // Static fix #5: back off instead of spinning; transient
                        // EIO must not brick the sniffer.
                        try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                        continue
                    }
                    if (n < 0) { SnifferDebugLog.i("read-loop: EOF, exiting"); break }
                    if (n == 0) continue
                    readErrs = 0
                    // copyOf is REQUIRED — buf is reused by read().
                    val pkt = buf.copyOf(n)
                    val pool = forwardPool
                    if (pool == null || pool.isShutdown) {
                        SnifferDebugLog.e("read-loop: forward pool unavailable, exiting")
                        break
                    }
                    try {
                        pool.execute {
                            try { handlePacket(pkt, pkt.size, output) }
                            catch (t: Throwable) {
                                // Static fix #5: one bad packet never stops the sniffer.
                                SnifferDebugLog.e("worker: packet handler crashed (sniffer stays alive)", t)
                            }
                        }
                    } catch (e: RejectedExecutionException) {
                        SnifferDebugLog.w("pool: task rejected (pool shutting down): ${e.message}")
                    }
                }
            } finally {
                running.set(false)
                SnifferDebugLog.i("lifecycle: TUN read loop exited")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun poolState(): String {
        val p = forwardPool ?: return "pool=none"
        return "pool(active=${p.activeCount},queued=${p.queue.size})"
    }

    /** Legacy handle(): if UDP dst:53 -> log QNAME+type, forward to the ORIGINAL dst, reply. */
    private fun handlePacket(buf: ByteArray, n: Int, out: FileOutputStream) {
        try {
            if (n < 28) { SnifferDebugLog.packet("drop: too short (${n}B)"); return }
            if (buf[0].toInt() and 0xF0 != 0x40) {
                SnifferDebugLog.packet("drop: not IPv4 (first byte 0x${Integer.toHexString(buf[0].toInt() and 0xFF)})")
                return
            }
            val ihl = (buf[0].toInt() and 0x0F) * 4
            if (n < ihl + 8) {
                SnifferDebugLog.packet("drop: truncated header (n=$n < ihl=$ihl + 8)")
                return
            }
            val proto = buf[9].toInt() and 0xFF
            val src = ipStr(buf, 12)
            val dst = ipStr(buf, 16)
            if (proto != 17) {
                SnifferDebugLog.packet("drop: non-UDP (proto=$proto) $src -> $dst")
                return
            }
            val udpOff = ihl
            val srcPort = ((buf[udpOff].toInt() and 0xFF) shl 8) or (buf[udpOff + 1].toInt() and 0xFF)
            val dstPort = ((buf[udpOff + 2].toInt() and 0xFF) shl 8) or (buf[udpOff + 3].toInt() and 0xFF)
            SnifferDebugLog.packet("intercepted: ${n}B udp $src:$srcPort -> $dst:$dstPort ${poolState()}")
            if (dstPort != 53) {
                SnifferDebugLog.packet("drop: udp to $dst:$dstPort is not DNS")
                return
            }
            val dnsLen = n - udpOff - 8
            if (dnsLen < 12) {
                SnifferDebugLog.packet("drop: DNS payload too short (${dnsLen}B)")
                return
            }
            val dns = buf.copyOfRange(udpOff + 8, n)
            val parsed = parseQuery(dns)
            if (parsed != null) {
                val (domain, qtype) = parsed
                record(domain)
                SnifferDebugLog.dns("query $domain type=${qtypeName(qtype)} (${dnsLen}B) $src:$srcPort -> $dst:53")
            } else {
                SnifferDebugLog.dns("query UNPARSEABLE (${dnsLen}B) $src:$srcPort -> $dst:53")
            }

            // Legacy forwarding semantics: send to the packet's ORIGINAL
            // destination first (ISP / LAN / captive resolvers keep working),
            // then fall back to the public resolvers.
            var resp: ByteArray? = null
            val order = ArrayList<String>(UPSTREAMS.size + 1)
            order.add(dst)
            UPSTREAMS.forEach { if (it != dst) order.add(it) }
            for (up in order) {
                val fr = tryUdpForward(dns, up)
                if (fr.error == null && fr.data != null) {
                    resp = fr.data
                    if (up != dst) {
                        SnifferDebugLog.w("fwd: original dst $dst:53 did not answer, fallback $up replied")
                    }
                    break
                }
            }
            if (resp == null) {
                SnifferDebugLog.e("fwd: ALL upstreams failed for ${parsed?.first ?: dst}:53 — client will retry")
                return
            }

            val outPkt = buildReply(buf, ihl, udpOff, resp)
            try {
                out.write(outPkt)
                SnifferDebugLog.packet("reply: wrote ${outPkt.size}B to TUN ($dst:53 -> $src:$srcPort)")
            } catch (e: Exception) {
                SnifferDebugLog.e("reply: TUN write failed (${outPkt.size}B)", e)
            }
        } catch (t: Throwable) {
            // Static fix #5: malformed packet must never take down the loop.
            SnifferDebugLog.e("handlePacket: unexpected error (sniffer stays alive)", t)
        }
    }

    /**
     * One protected UDP round-trip to `up:53`.
     * Static fix #4: protect() runs BEFORE connect/send and its boolean
     * result is recorded — a non-protected socket would loop back into the
     * TUN itself and never reach the network.
     */
    private fun tryUdpForward(dns: ByteArray, up: String): ForwardResult {
        val t0 = System.nanoTime()
        var prot = false
        try {
            DatagramSocket(null).use { sock ->
                prot = try { protect(sock) } catch (e: Exception) {
                    SnifferDebugLog.w("protect($up) threw: ${e.message}", e)
                    false
                }
                if (!prot) {
                    SnifferDebugLog.w("protect($up) = false — socket NOT protected, forward will loop into TUN")
                }
                sock.soTimeout = 3000
                val addr = InetSocketAddress(InetAddress.getByName(up), 53)
                sock.connect(addr)                       // protect happened before this connect
                sock.send(DatagramPacket(dns, dns.size)) // protect happened before this send
                val rb = ByteArray(4096)
                val rp = DatagramPacket(rb, rb.size)
                sock.receive(rp)
                val ms = (System.nanoTime() - t0) / 1_000_000
                SnifferDebugLog.packet(
                    "fwd: $up:53 OK bytes=${rp.length} latency=${ms}ms protected=yes ${poolState()}")
                return ForwardResult(rb.copyOf(rp.length), ms, null, prot)
            }
        } catch (e: Exception) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            SnifferDebugLog.packet("fwd: $up:53 FAILED after ${ms}ms (protected=$prot) " +
                "${e.javaClass.simpleName}: ${e.message}")
            return ForwardResult(null, ms, e, prot)
        }
    }

    private class ForwardResult(
        val data: ByteArray?,
        val latencyMs: Long,
        val error: Throwable?,
        val protectedFlag: Boolean,
    )

    /**
     * Legacy buildReply semantics: the reply appears to come from the REAL
     * server the app queried (src = original dst IP, src port 53), addressed
     * back to the requester (dst = original src IP/port). UDP checksum is 0
     * (legal "absent" on IPv4), IP checksum recomputed.
     */
    private fun buildReply(req: ByteArray, ihl: Int, udpOff: Int, dns: ByteArray): ByteArray {
        val udpLen = 8 + dns.size
        val total = ihl + udpLen
        val o = ByteArray(total)
        System.arraycopy(req, 0, o, 0, ihl + 8)
        o[2] = ((total shr 8) and 0xFF).toByte(); o[3] = (total and 0xFF).toByte()
        // swap directions: src <- original dst, dst <- original src
        System.arraycopy(req, 16, o, 12, 4)                 // new src = original dst (real server)
        System.arraycopy(req, 12, o, 16, 4)                 // new dst = original src (requester)
        o[udpOff] = 0.toByte(); o[udpOff + 1] = 53.toByte()                    // src port 53 (the DNS server)
        o[udpOff + 2] = req[udpOff]                    // dst port = the requester's ORIGINAL src port
        o[udpOff + 3] = req[udpOff + 1]
        o[udpOff + 4] = ((udpLen shr 8) and 0xFF).toByte(); o[udpOff + 5] = (udpLen and 0xFF).toByte()
        o[udpOff + 6] = 0.toByte(); o[udpOff + 7] = 0.toByte()  // UDP checksum 0 = valid "none"
        System.arraycopy(dns, 0, o, ihl + 8, dns.size)
        // recompute IP header checksum
        var sum = 0L
        o[10] = 0.toByte(); o[11] = 0.toByte()
        var i = 0
        while (i < ihl) {
            sum += ((o[i].toInt() and 0xFF) shl 8) or (o[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        val ck = (sum.toInt().inv()) and 0xFFFF
        o[10] = ((ck shr 8) and 0xFF).toByte(); o[11] = (ck and 0xFF).toByte()
        return o
    }

    private fun ipStr(b: ByteArray, off: Int): String =
        "${b[off].toInt() and 0xFF}.${b[off + 1].toInt() and 0xFF}." +
            "${b[off + 2].toInt() and 0xFF}.${b[off + 3].toInt() and 0xFF}"

    /** QNAME + QTYPE from a DNS query payload, null if malformed. */
    private fun parseQuery(d: ByteArray): Pair<String, Int>? {
        return try {
            if (d.size < 12) return null
            if (d[2].toInt() and 0x80 != 0) return null           // not a query
            val qd = ((d[4].toInt() and 0xFF) shl 8) or (d[5].toInt() and 0xFF)
            if (qd == 0) return null
            var p = 12
            val sb = StringBuilder()
            while (p < d.size) {
                val len = d[p].toInt() and 0xFF
                if (len == 0) { p += 1; break }
                if (len > 63) return null                          // compression ptr: not a plain query
                if (p + 1 + len > d.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(d, p + 1, len, Charsets.ISO_8859_1))
                p += 1 + len
            }
            if (sb.isEmpty()) return null
            var qtype = -1
            if (p + 2 <= d.size) qtype = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
            sb.toString() to qtype
        } catch (_: Exception) {
            null
        }
    }

    private fun qtypeName(t: Int): String = when (t) {
        1 -> "A"; 2 -> "NS"; 5 -> "CNAME"; 6 -> "SOA"; 12 -> "PTR"; 15 -> "MX"
        16 -> "TXT"; 28 -> "AAAA"; 33 -> "SRV"; 65 -> "HTTPS"; 255 -> "ANY"
        -1 -> "?"
        else -> "TYPE$t"
    }

    private fun stopTun() {
        stopFlag = true
        worker?.interrupt()
        worker = null
        try { forwardPool?.shutdownNow() } catch (_: Exception) {}
        forwardPool = null
        try { tun?.close() } catch (e: Exception) { SnifferDebugLog.w("stopTun: close failed: ${e.message}") }
        tun = null
    }

    override fun onDestroy() {
        SnifferDebugLog.i("lifecycle: onDestroy")
        running.set(false)
        stopTun()
        super.onDestroy()
    }

    override fun onRevoke() {
        SnifferDebugLog.i("lifecycle: onRevoke (VPN revoked by system/user)")
        onDestroy()
        super.onRevoke()
    }

    companion object {
        private const val TAG = "SnifferVpn"
        const val ACTION_STOP = "com.netscanner.SNIFFER_STOP"
        const val CHAN = "sniffer"
        const val NOTIF_ID = 41

        /** Legacy publics + fallback chain when the original dst is unreachable. */
        private val DEFAULT_DNS = listOf("8.8.8.8", "1.1.1.1")
        private val UPSTREAMS = listOf("8.8.8.8", "1.1.1.1", "223.5.5.5")

        @JvmStatic
        val running = AtomicBoolean(false)

        private const val KEEP = 300
        private val recent = LinkedHashSet<String>()
        private val order = ArrayDeque<String>()

        @JvmStatic
        fun record(domain: String) {
            synchronized(recent) {
                if (recent.add(domain)) {
                    order.addLast(domain)
                    while (order.size > KEEP) {
                        val old = order.removeFirst()
                        recent.remove(old)
                    }
                }
            }
        }

        @JvmStatic
        fun recentQueries(): List<String> = synchronized(recent) { order.toList().asReversed() }

        @JvmStatic
        fun clear() = synchronized(recent) { recent.clear(); order.clear() }
    }
}
