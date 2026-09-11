package com.netscanner.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
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
 * Parity with the legacy SnifferVpnService (query capture + working DNS).
 */
class SnifferVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var stopFlag = false
    private var worker: Thread? = null
    @Volatile private var upstream: String = "1.1.1.1"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
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
            tun = Builder()
                .setSession("NetScanner DNS Sniffer")
                .setMtu(32767)
                .addAddress("10.111.222.1", 32)
                .addDnsServer("10.111.222.1")
                .addRoute("10.111.222.1", 32)   // ONLY the fake DNS rides the TUN
                .establish() ?: return
        } catch (_: Exception) { return }

        val fd = tun!!.fileDescriptor
        stopFlag = false
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
        val srcPort = ((buf[udpOff].toInt() and 0xFF) shl 8) or (buf[udpOff + 1].toInt() and 0xFF)
        val dstPort = ((buf[udpOff + 2].toInt() and 0xFF) shl 8) or (buf[udpOff + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val dnsLen = n - udpOff - 8
        if (dnsLen < 12) return
        val dns = buf.copyOfRange(udpOff + 8, n)
        val domain = queryDomain(dns)
        if (domain != null) record(domain)

        // Forward through a protected socket to the upstream resolver.
        val resp: ByteArray? = try {
            DatagramSocket().use { sock ->
                try { protect(sock) } catch (_: Exception) {}
                sock.soTimeout = 4000
                sock.send(DatagramPacket(dns, dns.size,
                    InetSocketAddress(InetAddress.getByName(upstream), 53)))
                val rb = ByteArray(4096)
                val rp = DatagramPacket(rb, rb.size)
                sock.receive(rp)
                rb.copyOf(rp.length)
            }
        } catch (_: Exception) { null }

        if (resp != null) {
            val outPkt = buildReply(buf, ihl, udpOff, srcPort, resp)
            out.write(outPkt)
        }
    }

    /** Rebuild IPv4+UDP reply: src=10.111.222.1:53, dst=original inner src. */
    private fun buildReply(req: ByteArray, ihl: Int, udpOff: Int,
                           srcPort: Int, dns: ByteArray): ByteArray {
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
        o[udpOff + 4] = ((srcPort shr 8) and 0xFF).toByte(); o[udpOff + 5] = (srcPort and 0xFF).toByte()
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
        const val ACTION_STOP = "com.netscanner.SNIFFER_STOP"
        const val CHAN = "sniffer"
        const val NOTIF_ID = 41

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
