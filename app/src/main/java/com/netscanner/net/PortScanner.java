package com.netscanner.net;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** TCP connect scanner — root-free. */
public final class PortScanner {

    private PortScanner() {}

    /** Top 20 ports for quick scans */
    public static final int[] TOP20 = {
            21, 22, 23, 25, 53, 80, 110, 111, 135, 139,
            143, 443, 445, 993, 995, 1723, 3306, 3389, 5900, 8080
    };

    /** Top 100 ports for deep scans */
    public static final int[] TOP100 = {
            20, 21, 22, 23, 25, 26, 37, 53, 79, 80, 81, 88, 106, 110, 111, 113, 119, 135, 139, 143,
            144, 161, 179, 199, 389, 427, 443, 444, 445, 465, 513, 514, 515, 543, 544, 548, 554, 587,
            631, 636, 646, 873, 990, 993, 995, 1025, 1026, 1027, 1028, 1029, 1080, 1099, 1194, 1433,
            1494, 1521, 1720, 1723, 1883, 2049, 2082, 2083, 2181, 2375, 2376, 3128, 3268, 3306, 3389,
            3690, 4444, 4500, 5000, 5060, 5222, 5432, 5555, 5601, 5672, 5900, 5901, 5984, 6379, 6443,
            6666, 6667, 7777, 8000, 8008, 8009, 8080, 8081, 8443, 8888, 9000, 9090, 9200, 11211, 27017,
            50000
    };

    private static final Map<Integer, String> SERVICES = new HashMap<>();
    static {
        SERVICES.put(21, "FTP"); SERVICES.put(22, "SSH"); SERVICES.put(23, "Telnet");
        SERVICES.put(25, "SMTP"); SERVICES.put(53, "DNS"); SERVICES.put(80, "HTTP");
        SERVICES.put(110, "POP3"); SERVICES.put(111, "RPC"); SERVICES.put(135, "MS-RPC");
        SERVICES.put(139, "NetBIOS"); SERVICES.put(143, "IMAP"); SERVICES.put(161, "SNMP");
        SERVICES.put(443, "HTTPS"); SERVICES.put(445, "SMB"); SERVICES.put(465, "SMTPS");
        SERVICES.put(515, "Printer"); SERVICES.put(548, "AFP"); SERVICES.put(554, "RTSP");
        SERVICES.put(631, "IPP"); SERVICES.put(993, "IMAPS"); SERVICES.put(995, "POP3S");
        SERVICES.put(1080, "SOCKS"); SERVICES.put(1433, "MSSQL"); SERVICES.put(1521, "Oracle");
        SERVICES.put(1883, "MQTT"); SERVICES.put(2049, "NFS"); SERVICES.put(3306, "MySQL");
        SERVICES.put(3389, "RDP"); SERVICES.put(5000, "UPnP"); SERVICES.put(5060, "SIP");
        SERVICES.put(5432, "PostgreSQL"); SERVICES.put(5555, "ADB"); SERVICES.put(5672, "AMQP");
        SERVICES.put(5900, "VNC"); SERVICES.put(6379, "Redis"); SERVICES.put(8080, "HTTP-Alt");
        SERVICES.put(8443, "HTTPS-Alt"); SERVICES.put(9100, "HP Print"); SERVICES.put(9200, "Elasticsearch");
        SERVICES.put(27017, "MongoDB");
    }

    public static String service(int port) {
        String s = SERVICES.get(port);
        if (s != null) return s;
        if (port == 3000) return "Dev server";
        if (port == 8008 || port == 8081) return "HTTP-Alt";
        if (port == 8888) return "HTTP-Alt";
        if (port == 9000) return "Sonar/Dev";
        if (port == 50000) return "DB2";
        return "unknown";
    }

    public interface Callback {
        void onProgress(int done, int total);
        void onOpen(int port);
        void onDone(List<Integer> openPorts, long msElapsed);
    }

    /**
     * Concurrent TCP connect scan.
     * @param ports sorted list to scan
     * @param timeoutMs per-connection timeout (recommend 300-800ms on Wi-Fi)
     */
    public static void scan(String host, List<Integer> ports, int timeoutMs, Callback cb) {
        long start = System.currentTimeMillis();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger next = new AtomicInteger(0);
        List<Integer> open = Collections.synchronizedList(new ArrayList<>());
        int threads = Math.min(96, Math.max(16, ports.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(ports.size());
        final int total = ports.size();

        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    int idx = next.getAndIncrement();
                    if (idx >= total) return;
                    int port = ports.get(idx);
                    try {
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(host, port), timeoutMs);
                        open.add(port);
                        s.close();
                        if (cb != null) cb.onOpen(port);
                    } catch (Exception ignored) {
                    } finally {
                        int d = done.incrementAndGet();
                        latch.countDown();
                        if (cb != null) cb.onProgress(d, total);
                    }
                }
            });
        }
        try { latch.await(120, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        pool.shutdownNow();
        Collections.sort(open);
        if (cb != null) cb.onDone(open, System.currentTimeMillis() - start);
    }
}
