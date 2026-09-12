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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v5.1.3 DNS Sniffer VPN -- FAITHFUL mechanism port of the proven legacy
 * v4.7/4.8 SnifferVpnService (transparent interception), replacing the
 * v5.1.2 fake-DNS-server design:
 *
 *  Legacy mechanism (what actually worked for years):
 *   - TUN address 10.111.222.1/32, DNS server 8.8.8.8 (a REAL resolver, so
 *     the VPN network has a valid DNS config).
 *   - Routes = the real public DNS IPs (8.8.8.8/32, 1.1.1.1/32) PLUS the
 *     network's own DHCP/system DNS servers /32. Any plaintext UDP/53
 *     query the device sends therefore rides the TUN -- nothing else is
 *     captured, normal data is untouched.
 *   - read loop: catch UDP packets whose dst port is 53, forward the
 *     payload through a PROTECTED socket to the packet's ORIGINAL
 *     destination (preserves captive/ISP/LAN resolver semantics), then
 *     write the response back into the TUN with:
 *       src ip = original dst (the real server the app queried)
 *       src port = 53, dst = original src + its port
 *       UDP checksum = 0 (legal "no checksum" on IPv4)
 *       IP checksum recomputed.
 *   - QNAMEs are parsed and pushed into the log deque for the UI.
 *
 *  Kept from v5.1.2 (harmless hardening): the sniffer app itself is
 *  excluded from the VPN, and if the original destination does not answer
 *  we fall back to 8.8.8.8 / 1.1.1.1 before giving up.
 */
class SnifferVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var stopFlag = false
    private var worker: Thread? = null

    /** Legacy forward pipeline: fixed 4-worker pool, one thread per DNS packet. */
    private val forwardPool = java.util.concurrent.Executors.newFixedThreadPool(4)

    /** Legacy route sources: hardcoded publics + whatever the network uses. */
    private val routedDns = LinkedHashSet<String>()

    override fun onCreate() {
        super.onCreate()
        collectNetworkDns()
    }

    /** Legacy getDhcpDns(): current network LinkProperties DNS servers. */
    private fun collectNetworkDns() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nets = cm.allNetworks
            for (net in nets) {
                val lp: LinkProperties? = cm.getLinkProperties(net)
                lp?.dnsServers?.forEach { s ->
                    val h = s.hostAddress
                    if (h != null && !s.isLoopbackAddress) routedDns.add(h)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "collectNetworkDns failed: ${e.message}")
        }
        routedDns.addAll(DEFAULT_DNS)
        Log.d(TAG, "routed DNS targets: $routedDns")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d(TAG, "onStartCommand -- establishing transparent TUN")
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
        try {
            val b = Builder()
                .setSession("NetScanner DNS Sniffer")
                .addAddress("10.111.222.1", 32)
                .addDnsServer("8.8.8.8")            // legacy: a REAL resolver so the VPN network's DNS config is valid
            // Legacy route set: every DNS server the device could ever use,
            // each as a /32 -- only DNS rides the TUN.
            collectNetworkDns()
            routedDns.forEach { ip ->
                try { b.addRoute(ip, 32) } catch (e: Exception) {
                    Log.w(TAG, "route $ip failed: ${e.message}")
                }
            }
            // Hardening kept from v5.1.2: never route our own forwarder back
            // into the TUN.
            try { b.addDisallowedApplication(packageName) } catch (e: Exception) {
                Log.w(TAG, "addDisallowedApplication failed: ${e.message}")
            }
            tun = b.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed: ${e.message}")
            tun = null
        }
        if (tun == null) {
            // v5.1.2 hardening: no zombie state -- UI must see the VPN as stopped.
            running.set(false)
            return
        }

        val fd = tun!!.fileDescriptor
        stopFlag = false
        Log.d(TAG, "TUN established, legacy loop() starting")
        worker = Thread {
            running.set(true)
            val input = FileInputStream(fd)
            val output = FileOutputStream(fd)
            val buf = ByteArray(32767)
            try {
                while (!stopFlag) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    // Legacy pipeline: forward each packet on the thread pool
                    // (4 workers) so one slow upstream never stalls the TUN
                    // read loop. copyOf is REQUIRED -- buf is reused by read().
                    val pkt = buf.copyOf(n)
                    forwardPool.execute {
                        try { handlePacket(pkt, pkt.size, output) } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {
            } finally {
                running.set(false)
                Log.d(TAG, "worker loop exited")
            }
        }.apply { isDaemon = true; start() }
    }

    /** Legacy handle(): if UDP dst:53 -> log QNAME, forward to the ORIGINAL dst, reply. */
    private fun handlePacket(buf: ByteArray, n: Int, out: FileOutputStream) {
        if (n < 28) return                                  // IP(20)+UDP(8)+DNS header(12)
        if (buf[0].toInt() and 0xF0 != 0x40) return         // IPv4 only
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (n < ihl + 8) return
        val proto = buf[9].toInt() and 0xFF
        if (proto != 17) return                             // UDP only
        val udpOff = ihl
        val dstPort = ((buf[udpOff + 2].toInt() and 0xFF) shl 8) or (buf[udpOff + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val dnsLen = n - udpOff - 8
        if (dnsLen < 12) return
        val dns = buf.copyOfRange(udpOff + 8, n)
        val domain = queryDomain(dns)
        if (domain != null) record(domain)

        // Legacy forwarding semantics: send to the packet's ORIGINAL
        // destination first (ISP / LAN / captive resolvers keep working),
        // then fall back to the public resolvers.
        val dstIp = ByteArray(4)
        System.arraycopy(buf, 16, dstIp, 0, 4)
        val originalDst = dstIp.joinToString(".") { (it.toInt() and 0xFF).toString() }

        var resp: ByteArray? = null
        val order = ArrayList<String>(UPSTREAMS.size + 1)
        order.add(originalDst)
        UPSTREAMS.forEach { if (it != originalDst) order.add(it) }
        for (up in order) {
            resp = tryUdpForward(dns, up)
            if (resp != null) {
                if (up != originalDst) Log.d(TAG, "original dst $originalDst failed, answered by $up")
                break
            }
        }
        if (resp == null) Log.w(TAG, "all upstreams failed for ${domain ?: "?"}")

        if (resp != null) {
            val outPkt = buildReply(buf, ihl, udpOff, resp)
            try { out.write(outPkt) } catch (_: Exception) {}
        }
    }

    /** One protected UDP round-trip to `up:53`; null on any failure. */
    private fun tryUdpForward(dns: ByteArray, up: String): ByteArray? = try {
        DatagramSocket().use { sock ->
            try { protect(sock) } catch (_: Exception) {}
            sock.soTimeout = 3000
            sock.send(DatagramPacket(dns, dns.size,
                InetSocketAddress(InetAddress.getByName(up), 53)))
            val rb = ByteArray(4096)
            val rp = DatagramPacket(rb, rb.size)
            sock.receive(rp)
            rb.copyOf(rp.length)
        }
    } catch (_: Exception) {
        null
    }

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

    /** Extract QNAME from a DNS query payload, null if malformed. */
    private fun queryDomain(d: ByteArray): String? {
        return try {
            if (d[2].toInt() and 0x80 != 0) return null           // not a query
            val qd = ((d[4].toInt() and 0xFF) shl 8) or (d[5].toInt() and 0xFF)
            if (qd == 0) return null
            var p = 12
            val sb = StringBuilder()
            while (p < d.size) {
                val len = d[p].toInt() and 0xFF
                if (len == 0) break
                if (p + 1 + len > d.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(d, p + 1, len, Charsets.ISO_8859_1))
                p += 1 + len
            }
            if (sb.isEmpty()) null else sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun stopTun() {
        stopFlag = true
        worker?.interrupt()
        worker = null
        forwardPool.shutdownNow()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
    }

    override fun onDestroy() {
        running.set(false)
        stopTun()
        super.onDestroy()
    }

    override fun onRevoke() {
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
