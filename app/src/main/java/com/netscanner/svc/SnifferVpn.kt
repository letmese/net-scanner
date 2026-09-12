package com.netscanner.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
 * v5 DNS Sniffer VPN — establishes a local TUN that ONLY routes the fake DNS
 * server (10.111.222.1:53) into the tunnel, so all system DNS traffic flows
 * through us while normal data traffic stays untouched (no full NAT needed).
 * Plaintext queries are parsed from the UDP/53 payload and recorded for the
 * UI; the query is forwarded to a real upstream resolver through a
 * protected socket, and the response is written back into the TUN.
 *
 * v5.1.2 fixes for the reported "bad DNS config" (pages never load while the
 * VPN is up):
 *  1. buildReply() reused the REQUEST's UDP checksum for the reply — a
 *     guaranteed-invalid checksum, so the kernel dropped every DNS response.
 *     IPv4 UDP checksum is now zeroed (0 = "no checksum", always legal).
 *  2. The app itself is excluded from the VPN (addDisallowedApplication) so
 *     the forwarder can never be looped back into its own TUN.
 *  3. Upstream resolution falls back across 8.8.8.8 / 1.1.1.1 / 223.5.5.5
 *     (a blocked upstream no longer kills every lookup).
 *  4. establish() failure now flips `running` off and logs, instead of
 *     leaving a zombie "active" state.
 */
class SnifferVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var stopFlag = false
    private var worker: Thread? = null
    @Volatile private var upstream: String = UPSTREAMS[0]

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d(TAG, "onStartCommand — establishing TUN (excluded app: $packageName)")
        startAsForeground()
        running.set(true)
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
            .setContentText("Capturing DNS queries — tap to open")
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
                .setMtu(32767)
                .addAddress("10.111.222.1", 32)
                .addDnsServer("10.111.222.1")
                .addRoute("10.111.222.1", 32)   // ONLY the fake DNS rides the TUN
            // v5.1.2: never route the sniffer's own traffic into its own TUN —
            // protects the forwarder socket from routing loops.
            try { b.addDisallowedApplication(packageName) } catch (e: Exception) {
                Log.w(TAG, "addDisallowedApplication failed: ${e.message}")
            }
            tun = b.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed: ${e.message}")
            tun = null
        }
        if (tun == null) {
            // v5.1.2: no zombie state — UI must see the VPN as stopped.
            running.set(false)
            return
        }

        val fd = tun!!.fileDescriptor
        stopFlag = false
        Log.d(TAG, "TUN established, worker loop starting")
        worker = Thread {
            running.set(true)
            val input = FileInputStream(fd)
            val output = FileOutputStream(fd)
            val buf = ByteArray(32767)
            try {
                while (!stopFlag) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    handlePacket(buf, n, output)
                }
            } catch (_: Throwable) {
            } finally {
                running.set(false)
                Log.d(TAG, "worker loop exited")
            }
        }.apply { isDaemon = true; start() }
    }

    /** Parse one TUN packet: if UDP dst:53 → sniff + forward + respond. */
    private fun handlePacket(buf: ByteArray, n: Int, out: FileOutputStream) {
        if (n < 28) return                                  // IP(20)+UDP(8)+DNS(12) minimum-ish
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

        // v5.1.2: forward through a protected socket with upstream fallback —
        // try the sticky upstream first, then the rest of the list.
        var resp: ByteArray? = null
        val order = ArrayList<String>(UPSTREAMS.size)
        order.add(upstream)
        UPSTREAMS.forEach { if (it != upstream) order.add(it) }
        for (u in order) {
            resp = tryUdpForward(dns, u)
            if (resp != null) {
                if (u != upstream) {
                    Log.d(TAG, "upstream switched to $u")
                    upstream = u
                }
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
            sock.soTimeout = 2500
            sock.send(DatagramPacket(dns, dns.size,
                InetSocketAddress(InetAddress.getByName(up), 53)))
            val rb = ByteArray(4096)
            val rp = DatagramPacket(rb, rb.size)
            sock.receive(rp)
            rb.copyOf(rp.length)
        }
    } catch (_: Exception) { null }

    /**
     * Rebuild IPv4+UDP reply: src=10.111.222.1:53, dst=original inner src.
     * v5.1.2 CRITICAL FIX: the UDP checksum must NOT be copied from the
     * request — a checksum over the reply payload is invalid and the kernel
     * silently drops the datagram, which is exactly the reported
     * "bad DNS config" symptom. IPv4 treats UDP checksum 0 as "absent",
     * which is always accepted, so we zero it.
     */
    private fun buildReply(req: ByteArray, ihl: Int, udpOff: Int, dns: ByteArray): ByteArray {
        val udpLen = 8 + dns.size
        val total = ihl + udpLen
        val o = ByteArray(total)
        System.arraycopy(req, 0, o, 0, ihl + 8)
        o[2] = ((total shr 8) and 0xFF).toByte(); o[3] = (total and 0xFF).toByte()
        val appIp = req.copyOfRange(12, 16)
        o[12] = 10.toByte(); o[13] = 111.toByte(); o[14] = 222.toByte(); o[15] = 1.toByte()     // new src = fake DNS
        System.arraycopy(appIp, 0, o, 16, 4)                // dst = app inner IP
        o[udpOff] = ((udpLen shr 8) and 0xFF).toByte(); o[udpOff + 1] = (udpLen and 0xFF).toByte()
        o[udpOff + 2] = 0.toByte(); o[udpOff + 3] = 53.toByte()               // src port 53
        o[udpOff + 4] = ((req[udpOff].toInt() and 0xFF)).toByte()  // keep the requester's src port
        o[udpOff + 5] = ((req[udpOff + 1].toInt() and 0xFF)).toByte()
        o[udpOff + 6] = 0.toByte(); o[udpOff + 7] = 0.toByte()  // UDP checksum 0 = valid "none" on IPv4
        System.arraycopy(dns, 0, o, ihl + 8, dns.size)
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
        } catch (_: Exception) { null }
    }

    private fun stopTun() {
        stopFlag = true
        worker?.interrupt()
        worker = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
    }

    override fun onDestroy() {
        running.set(false)
        stopTun()
        super.onDestroy()
    }

    override fun onRevoke() { onDestroy(); super.onRevoke() }

    companion object {
        private const val TAG = "SnifferVpn"
        const val ACTION_STOP = "com.netscanner.SNIFFER_STOP"
        const val CHAN = "sniffer"
        const val NOTIF_ID = 41

        /** v5.1.2: forwarder tries these in order and sticks with the first that answers. */
        private val UPSTREAMS = listOf("8.8.8.8", "1.1.1.1", "223.5.5.5")

        @JvmStatic val running = AtomicBoolean(false)

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
