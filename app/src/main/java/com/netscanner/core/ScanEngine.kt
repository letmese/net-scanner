package com.netscanner.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The full LAN scan pipeline from legacy ScanActivity.runScanBody, re-shaped
 * as a blocking engine (call from Dispatchers.IO):
 * sweep → neighbor tables (+TCP nudge) → targeted MAC lookup → hostnames
 * (NetBIOS → DNS → HTTP title) → weak-service risk pass → device guessing →
 * mDNS names → CSV/history persistence. Progress + stage callbacks drive UI.
 */
object ScanEngine {

    /** Legacy device-guessing rules from open ports CSV string. */
    fun guessDevice(open: String): String? {
        if (open.isEmpty()) return null
        val p = mutableSetOf<Int>()
        for (x in open.split(",")) {
            try {
                if (x.isNotBlank()) p.add(x.trim().toInt())
            } catch (ignored: Exception) {
            }
        }
        if (p.contains(32400)) return "Plex media server"
        if (p.contains(9100) || p.contains(631)) return "likely printer"
        if (p.contains(554)) return "likely IP camera"
        if (p.contains(5000) || p.contains(5001)) return "likely Synology NAS"
        if (p.contains(22) && p.contains(80) && p.contains(8080)) return "likely router"
        if (p.contains(443) && p.contains(80)) return "web server / NAS"
        if (p.contains(80) || p.contains(8080)) return "has web interface"
        if (p.contains(443)) return "HTTPS service"
        return null
    }

    /**
     * @param prefix subnet prefix, e.g. "192.168.1."
     */
    fun scan(
        ctx: Context,
        prefix: String,
        onStage: (String) -> Unit = {},
        onSweepProgress: (done: Int, total: Int, found: Int) -> Unit = { _, _, _ -> }
    ): List<Device> {
        AppLog.log("scan start $prefix")
        AppLog.cp(ctx, "scan_start")

        val alive = NetUtils.sweep(prefix) { done, total ->
            onSweepProgress(done, total, 0)
        }
        AppLog.log("sweep done, alive=${alive.size}")
        AppLog.cp(ctx, "sweep_done alive=${alive.size}")

        var macs = NetUtils.neighborTable()
        // nudge hosts lacking a MAC — even a TCP RST forces the kernel to ARP-resolve
        for (ip in alive) {
            if (!macs.containsKey(ip)) {
                for (port in intArrayOf(80, 443, 8080)) {
                    try {
                        Socket().use { s -> s.connect(InetSocketAddress(ip, port), 250) }
                        break
                    } catch (ignored: Exception) {
                    }
                }
            }
        }
        if (alive.isNotEmpty()) {
            try { Thread.sleep(300) } catch (ignored: InterruptedException) {}
            macs = NetUtils.neighborTable()
        }
        AppLog.log("macs resolved=${macs.size}")
        AppLog.cp(ctx, "macs=${macs.size}")

        val ips = LinkedHashSet(alive)
        ips.addAll(macs.keys)
        val self = NetUtils.localNet()
        if (self != null && self.ip.startsWith(prefix)) ips.add(self.ip)

        val devices = mutableListOf<Device>()
        val arr = JSONArray()
        for (ip in ips) {
            val d = Device(ip)
            d.mac = macs[ip]
            d.reachable = alive.contains(ip)
            d.isSelf = self != null && ip == self.ip
            devices.add(d)
            try {
                arr.put(
                    JSONObject()
                        .put("ip", d.ip)
                        .put("mac", d.mac ?: "")
                        .put("type", DeviceTypes.label(d))
                )
            } catch (ignored: Exception) {
            }
        }

        // last resort: per-host targeted neighbor query for anything still missing
        onStage("Resolving MACs…")
        val mex = Executors.newFixedThreadPool(24)
        val extra = ConcurrentHashMap<String, String>()
        val mlatch = CountDownLatch(devices.size)
        for (d in devices) {
            if (d.mac != null) {
                mlatch.countDown()
                continue
            }
            val fip = d.ip
            mex.execute {
                try {
                    NetUtils.macOf(fip)?.let { extra[fip] = it }
                } finally {
                    mlatch.countDown()
                }
            }
        }
        try { mlatch.await(15, TimeUnit.SECONDS) } catch (ignored: InterruptedException) {}
        mex.shutdownNow()
        for (d in devices) if (d.mac == null) d.mac = extra[d.ip]

        onStage("Resolving hostnames…")
        val hex = Executors.newFixedThreadPool(24)
        val hlatch = CountDownLatch(devices.size)
        for (d in devices) {
            val fd = d
            hex.execute {
                try {
                    val nb = NetUtils.netbiosNameSync(fd.ip)
                    if (!nb.isNullOrBlank()) {
                        fd.host = nb.trim()
                        AppLog.log("name(nb) ${fd.ip} = ${nb.trim()}")
                        return@execute
                    }
                    val host = InetAddress.getByName(fd.ip).hostName
                    if (host != null && host != fd.ip) {
                        fd.host = host
                        AppLog.log("name(dns) ${fd.ip} = $host")
                        return@execute
                    }
                    val http = NetUtils.httpTitle(fd.ip)
                    if (http != null) {
                        fd.host = http
                        AppLog.log("name(http) ${fd.ip} = $http")
                    }
                } catch (ignored: Exception) {
                } finally {
                    hlatch.countDown()
                }
            }
        }
        try { hlatch.await(6, TimeUnit.SECONDS) } catch (ignored: InterruptedException) {}
        hex.shutdownNow()
        AppLog.cp(ctx, "hostnames_done")

        // weak-service flags: Telnet/FTP open = risky
        try {
            val rex = Executors.newFixedThreadPool(24)
            val rlatch = CountDownLatch(devices.size)
            for (d in devices) {
                if (!d.reachable) {
                    rlatch.countDown()
                    continue
                }
                val fd = d
                rex.execute {
                    try {
                        val ports = intArrayOf(23, 21, 80, 443, 554, 9100, 631, 5000, 32400, 8080)
                        val open = StringBuilder()
                        for (port in ports) {
                            try {
                                Socket().use { s ->
                                    s.connect(InetSocketAddress(fd.ip, port), 400)
                                    if (port == 23) fd.risk = (fd.risk?.let { "$it, " } ?: "") + "Telnet open"
                                    if (port == 21) fd.risk = (fd.risk?.let { "$it, " } ?: "") + "FTP open"
                                    open.append(port).append(',')
                                }
                            } catch (ignored: Exception) {
                            }
                        }
                        fd.guess = guessDevice(open.toString())
                    } finally {
                        rlatch.countDown()
                    }
                }
            }
            rlatch.await(8, TimeUnit.SECONDS)
            rex.shutdownNow()
            AppLog.log("risk pass done")
        } catch (ignored: Exception) {
        }

        Collections.sort(devices) { a, b -> a.lastOctet().toInt().compareTo(b.lastOctet().toInt()) }
        val savedArr = arr
        AppLog.log("building list, devices=${devices.size}")
        AppLog.cp(ctx, "ui_update_posted")

        // mDNS names (Chromecast, AirPlay, printers, smart home)
        try {
            val mdns = Mdns.resolve(ctx.applicationContext, 4000)
            AppLog.log("mdns names=${mdns.size}")
            for (d in devices) {
                if (d.host == null) {
                    mdns[d.ip]?.let { d.host = it }
                }
            }
        } catch (ignored: Exception) {
        }

        // persist CSV for the export card
        try {
            val csv = StringBuilder("ip,hostname,mac,type,reachable\n")
            for (d in devices) {
                csv.append(d.ip).append(',')
                    .append('"').append(d.host?.replace("\"", "'") ?: "").append('"').append(',')
                    .append('"').append(d.mac ?: "").append('"').append(',')
                    .append('"').append(DeviceTypes.label(d)).append('"').append(',')
                    .append(d.reachable).append('\n')
            }
            Stores.saveLastScanCsv(ctx, csv.toString())
        } catch (ignored: Exception) {
        }
        AppLog.cp(ctx, "list_built devices=${devices.size}")

        if (devices.isNotEmpty()) {
            Stores.saveScanHistory(ctx, prefix, devices.size, savedArr)
        }
        return devices
    }
}
