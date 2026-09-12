package com.netscanner.core

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** nmap-style per-port verdict — v5.2.0. */
data class PortResult(
    val port: Int,
    val state: String,          // "OPEN" / "CLOSED" / "FILTERED"
    val service: String,
    val banner: String? = null, // software/version from Fingerprinter probes
    val hint: String? = null,   // how-to-connect line from ConnHints
    val risk: String? = null,   // Fingerprinter.riskNote for exposed services
    val latencyMs: Int = -1
)

/** Full result of a detailed scan: all probed ports + elapsed ms. */
data class PortScanDetailed(
    val results: List<PortResult>,
    val elapsedMs: Long,
    val hostUp: Boolean
)

/** TCP connect scanner — root-free. 1:1 Kotlin port of legacy PortScanner. */
object PortScanner {

    /** Top 20 ports for quick scans */
    val TOP20 = intArrayOf(
        21, 22, 23, 25, 53, 80, 110, 111, 135, 139,
        143, 443, 445, 993, 995, 1723, 3306, 3389, 5900, 8080
    )

    /** Top 100 ports for deep scans */
    val TOP100 = intArrayOf(
        20, 21, 22, 23, 25, 26, 37, 53, 79, 80, 81, 88, 106, 110, 111, 113, 119, 135, 139, 143,
        144, 161, 179, 199, 389, 427, 443, 444, 445, 465, 513, 514, 515, 543, 544, 548, 554, 587,
        631, 636, 646, 873, 990, 993, 995, 1025, 1026, 1027, 1028, 1029, 1080, 1099, 1194, 1433,
        1494, 1521, 1720, 1723, 1883, 2049, 2082, 2083, 2181, 2375, 2376, 3128, 3268, 3306, 3389,
        3690, 4444, 4500, 5000, 5060, 5222, 5432, 5555, 5601, 5672, 5900, 5901, 5984, 6379, 6443,
        6666, 6667, 7777, 8000, 8008, 8009, 8080, 8081, 8443, 8888, 9000, 9090, 9200, 11211, 27017,
        50000
    )

    private val SERVICES = mapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet",
        25 to "SMTP", 53 to "DNS", 80 to "HTTP",
        110 to "POP3", 111 to "RPC", 135 to "MS-RPC",
        139 to "NetBIOS", 143 to "IMAP", 161 to "SNMP",
        443 to "HTTPS", 445 to "SMB", 465 to "SMTPS",
        515 to "Printer", 548 to "AFP", 554 to "RTSP",
        631 to "IPP", 993 to "IMAPS", 995 to "POP3S",
        1080 to "SOCKS", 1433 to "MSSQL", 1521 to "Oracle",
        1883 to "MQTT", 2049 to "NFS", 3306 to "MySQL",
        3389 to "RDP", 5000 to "UPnP", 5060 to "SIP",
        5432 to "PostgreSQL", 5555 to "ADB", 5672 to "AMQP",
        5900 to "VNC", 6379 to "Redis", 8080 to "HTTP-Alt",
        8443 to "HTTPS-Alt", 9100 to "HP Print", 9200 to "Elasticsearch",
        27017 to "MongoDB"
    )

    fun service(port: Int): String {
        SERVICES[port]?.let { return it }
        return when (port) {
            3000 -> "Dev server"
            8008, 8081, 8888 -> "HTTP-Alt"
            9000 -> "Sonar/Dev"
            50000 -> "DB2"
            else -> "unknown"
        }
    }

    /**
     * Concurrent TCP connect scan (blocking; call off the UI thread).
     * @param ports sorted list to scan
     * @param timeoutMs per-connection timeout (recommend 300-800ms on Wi-Fi)
     * @param cancel flag polled between ports — set true to abort early
     */
    fun scan(
        host: String,
        ports: List<Int>,
        timeoutMs: Int,
        onProgress: ((Int, Int) -> Unit)? = null,
        onOpen: ((Int) -> Unit)? = null,
        cancel: () -> Boolean = { false }
    ): Pair<List<Int>, Long> {
        val start = System.currentTimeMillis()
        val done = AtomicInteger()
        val next = AtomicInteger(0)
        val open = Collections.synchronizedList(mutableListOf<Int>())
        val threads = minOf(96, maxOf(16, ports.size))
        val pool: ExecutorService = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(ports.size)
        val total = ports.size

        repeat(threads) {
            pool.execute {
                while (!Thread.currentThread().isInterrupted && !cancel()) {
                    val idx = next.getAndIncrement()
                    if (idx >= total) return@execute
                    val port = ports[idx]
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(host, port), timeoutMs)
                            open.add(port)
                        }
                        onOpen?.invoke(port)
                    } catch (ignored: Exception) {
                    } finally {
                        val d = done.incrementAndGet()
                        latch.countDown()
                        onProgress?.invoke(d, total)
                    }
                }
            }
        }
        try {
            latch.await(120, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
        }
        pool.shutdownNow()
        Collections.sort(open)
        return open to (System.currentTimeMillis() - start)
    }

    /**
     * nmap-style connect scan with per-port state — v5.2.0.
     *
     * State mapping (nmap connect-scan semantics):
     *  - connected              → OPEN   (+ banner grab + latency)
     *  - connection refused     → CLOSED (host up, service not listening)
     *  - timeout / unreachable  → FILTERED (firewall drop / no route)
     * Blocking; call off the UI thread.
     */
    fun scanDetailed(
        host: String,
        ports: List<Int>,
        timeoutMs: Int,
        onProgress: ((Int, Int) -> Unit)? = null,
        grabBanners: Boolean = true,
        cancel: () -> Boolean = { false }
    ): PortScanDetailed {
        val start = System.currentTimeMillis()
        val done = AtomicInteger()
        val next = AtomicInteger(0)
        val results = Collections.synchronizedList(mutableListOf<PortResult>())
        var anyOpen = false
        val threads = minOf(96, maxOf(16, ports.size))
        val pool: ExecutorService = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(ports.size)
        val total = ports.size

        repeat(threads) {
            pool.execute {
                while (!Thread.currentThread().isInterrupted && !cancel()) {
                    val idx = next.getAndIncrement()
                    if (idx >= total) return@execute
                    val port = ports[idx]
                    val svc = service(port)
                    try {
                        Socket().use { s ->
                            val t0 = System.currentTimeMillis()
                            s.connect(InetSocketAddress(host, port), timeoutMs)
                            val ms = (System.currentTimeMillis() - t0).toInt()
                            anyOpen = true
                            var banner: String? = null
                            if (grabBanners) {
                                banner = try { Fingerprinter.probe(host, port) } catch (ignored: Exception) { null }
                            }
                            results.add(
                                PortResult(
                                    port = port,
                                    state = "OPEN",
                                    service = svc,
                                    banner = banner,
                                    hint = ConnHints.hint(host, port),
                                    risk = Fingerprinter.riskNote(port),
                                    latencyMs = ms
                                )
                            )
                        }
                    } catch (e: ConnectException) {
                        // refused (or e.g. ENETUNREACH — treated below via host-up hint)
                        val msg = e.message?.lowercase() ?: ""
                        if (msg.contains("refused")) {
                            results.add(PortResult(port, "CLOSED", svc))
                        } else {
                            results.add(PortResult(port, "FILTERED", svc))
                        }
                    } catch (e: SocketTimeoutException) {
                        results.add(PortResult(port, "FILTERED", svc))
                    } catch (ignored: IOException) {
                        // host unreachable, EHOSTUNREACH, ECONNRESET, network down…
                        results.add(PortResult(port, "FILTERED", svc))
                    } catch (ignored: Exception) {
                        results.add(PortResult(port, "FILTERED", svc))
                    } finally {
                        val d = done.incrementAndGet()
                        latch.countDown()
                        onProgress?.invoke(d, total)
                    }
                }
            }
        }
        try {
            latch.await(180, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
        }
        pool.shutdownNow()
        return PortScanDetailed(
            results = results.sortedWith(compareByDescending<PortResult> { it.state == "OPEN" }.thenBy { it.port }),
            elapsedMs = System.currentTimeMillis() - start,
            hostUp = anyOpen
        )
    }
}
