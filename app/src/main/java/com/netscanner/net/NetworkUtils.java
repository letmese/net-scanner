package com.netscanner.net;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Network discovery helpers — all root-free. */
public final class NetworkUtils {

    private NetworkUtils() {}

    public static class LocalNet {
        public String ip;      // e.g. 192.168.1.23
        public String prefix;  // e.g. 192.168.1
        public int cidr = 24;
    }

    /** Find the active Wi-Fi/LAN IPv4 + /24 prefix */
    public static LocalNet localNet() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        LocalNet n = new LocalNet();
                        n.ip = addr.getHostAddress();
                        int cut = n.ip.lastIndexOf('.');
                        n.prefix = n.ip.substring(0, cut + 1);
                        return n;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** ICMP ping via system binary (works without root on Android). */
    public static boolean ping(String host) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ping", "-c", "1", "-W", "1", host});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ping every host in the /24 concurrently.
     * Returns list of alive IPs (unsorted).
     */
    public static java.util.List<String> sweep(String prefix, Progress cb) {
        java.util.List<String> alive = Collections.synchronizedList(new java.util.ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch latch = new CountDownLatch(254);
        for (int i = 1; i <= 254; i++) {
            final String host = prefix + i;
            pool.execute(() -> {
                try { if (ping(host)) alive.add(host); }
                finally { latch.countDown(); if (cb != null) cb.onProgress(254 - (int) latch.getCount(), 254); }
            });
        }
        try { latch.await(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        pool.shutdownNow();
        return alive;
    }

    public interface Progress { void onProgress(int done, int total); }

    /** Parse `/proc/net/arp` → ip → mac (skips incomplete entries). Android 10+ usually blocks this. */
    public static Map<String, String> arpTable() {
        Map<String, String> map = new HashMap<>();
        Pattern hex = Pattern.compile("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}");
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 6 || parts[0].equals("IP address")) continue;
                Matcher m = hex.matcher(parts[3]);
                if (m.find() && !parts[2].equals("0x0")) {
                    map.put(parts[0], parts[3].toUpperCase());
                }
            }
        } catch (Exception ignored) {}
        if (map.isEmpty()) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "cat /proc/net/arp"});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 6 || parts[0].equals("IP address")) continue;
                    Matcher m = hex.matcher(parts[3]);
                    if (m.find() && !parts[2].equals("0x0")) map.put(parts[0], parts[3].toUpperCase());
                }
                r.close(); p.waitFor();
            } catch (Exception ignored) {}
        }
        return map;
    }

    /**
     * Neighbor table via `ip neigh` (netlink) — the root-free path on Android 10+.
     * Merged with /proc/net/arp for older devices.
     */
    public static Map<String, String> neighborTable() {
        Map<String, String> map = new HashMap<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ip", "neigh", "show"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                String[] t = line.trim().split("\\s+");
                if (t.length < 4) continue;
                if (!t[0].matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) continue; // IPv4 only
                for (int i = 0; i < t.length - 1; i++) {
                    if (t[i].equals("lladdr")) { map.put(t[0], t[i + 1].toUpperCase()); break; }
                }
            }
            r.close();
            p.waitFor();
        } catch (Exception ignored) {}
        map.putAll(arpTable()); // fill gaps on older Androids
        return map;
    }

    /** Send Wake-on-LAN magic packet (UDP broadcast ×3). Returns true if sent without error. */
    public static boolean wakeOnLan(String mac) {
        try {
            String hex = mac.replace(":", "").replace("-", "");
            if (hex.length() != 12) return false;
            byte[] macBytes = new byte[6];
            for (int i = 0; i < 6; i++)
                macBytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            byte[] packet = new byte[102];
            java.util.Arrays.fill(packet, 0, 6, (byte) 0xFF);
            for (int i = 0; i < 16; i++)
                System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6);
            java.net.DatagramSocket s = new java.net.DatagramSocket();
            s.setBroadcast(true);
            for (int i = 0; i < 3; i++) {
                s.send(new java.net.DatagramPacket(packet, packet.length,
                        java.net.InetAddress.getByName("255.255.255.255"), 9));
                Thread.sleep(100);
            }
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** One ping: ICMP via system binary -> isReachable -> TCP connect time. Returns ms or -1. */
    public static int pingOnce(String host) {
        for (String bin : new String[]{"ping", "/system/bin/ping"}) {
            try {
                long t0 = System.currentTimeMillis();
                Process p = Runtime.getRuntime().exec(new String[]{bin, "-c", "1", "-W", "1", host});
                if (p.waitFor() == 0) return (int) (System.currentTimeMillis() - t0);
            } catch (Exception ignored) {}
        }
        try {
            long t0 = System.currentTimeMillis();
            if (InetAddress.getByName(host).isReachable(1200)) return (int) (System.currentTimeMillis() - t0);
        } catch (Exception ignored) {}
        for (int port : new int[]{443, 80, 53}) {
            try (Socket s = new Socket()) {
                long t0 = System.currentTimeMillis();
                s.connect(new InetSocketAddress(host, port), 1500);
                return (int) (System.currentTimeMillis() - t0);
            } catch (Exception ignored) {}
        }
        return -1;
    }

    public interface HopCallback { void onHop(int ttl, String ip, String ms); }

    /** Traceroute via ping TTL stepping (system binary → root-free). */
    public static void traceroute(String host, HopCallback cb) {
        new Thread(() -> {
            for (int ttl = 1; ttl <= 20; ttl++) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"ping", "-c", "1", "-W", "1", "-t",
                            String.valueOf(ttl), host});
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line, hopIp = null, ms = null;
                    boolean done = false;
                    while ((line = r.readLine()) != null) {
                        int ti = line.indexOf("time=");
                        if (ti >= 0) ms = line.substring(ti + 5).trim();
                        int fi = line.indexOf("From ");
                        if (fi < 0) fi = line.indexOf("from ");
                        if (fi >= 0 && !line.contains("bytes from")) {
                            String rest = line.substring(fi + 5).trim();
                            int sp = rest.indexOf(' ');
                            String cand = sp > 0 ? rest.substring(0, sp) : rest;
                            cand = cand.replace(":", "").replace("(", "").replace(")", "");
                            if (cand.matches("\\d{1,3}(\\.\\d{1,3}){3}")) hopIp = cand;
                        }
                        if (line.contains("bytes from") || line.contains("exceed")) {
                            if (line.contains("bytes from")) {
                                // reached destination
                                if (hopIp == null) hopIp = host;
                                done = true;
                                break;
                            }
                            if (hopIp != null) break; // ttl exceeded at this hop
                        }
                    }
                    r.close();
                    p.waitFor();
                    final String fIp = hopIp, fMs = ms;
                    final int fTtl = ttl;
                    if (fIp != null && cb != null) cb.onHop(fTtl, fIp, fMs);
                    if (done) return;
                } catch (Exception ignored) {}
            }
        }).start();
    }

    public interface SsdpCallback {
        void onDevice(String friendlyName, String manufacturer, String model,
                      String deviceType, String location);
    }

    /**
     * UPnP/SSDP discovery: M-SEARCH multicast, then fetch each device's description.xml.
     * Runs on its own thread; callback invoked per discovered device.
     */
    public static void ssdpDiscover(SsdpCallback cb) {
        new Thread(() -> {
            java.util.Set<String> locations = new java.util.HashSet<>();
            try {
                java.net.DatagramSocket s = new java.net.DatagramSocket();
                s.setSoTimeout(1000);
                byte[] q = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\nST: ssdp:all\r\n\r\n").getBytes();
                java.net.InetAddress group = java.net.InetAddress.getByName("239.255.255.250");
                long deadline = System.currentTimeMillis() + 5000;
                int sends = 0;
                while (System.currentTimeMillis() < deadline) {
                    if (sends < 2) {
                        s.send(new java.net.DatagramPacket(q, q.length, group, 1900));
                        sends++;
                    }
                    try {
                        byte[] buf = new byte[2048];
                        java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                        s.receive(p);
                        String resp = new String(buf, 0, p.getLength());
                        String loc = headerOf(resp, "LOCATION:");
                        if (loc != null) locations.add(loc.trim());
                    } catch (java.net.SocketTimeoutException ignored) {}
                }
                s.close();
            } catch (Exception ignored) {}

            for (String loc : locations) {
                try {
                    java.net.URL u = new java.net.URL(loc);
                    String xml = httpGet(u.getHost(), u.getPort() > 0 ? u.getPort() : 80,
                            u.getPath().isEmpty() ? "/" : u.getPath());
                    if (xml == null || !xml.contains("<")) {
                        if (cb != null) cb.onDevice(null, null, null, null, loc);
                        continue;
                    }
                    String name = tag(xml, "friendlyName");
                    String mfr = tag(xml, "manufacturer");
                    String model = tag(xml, "modelName");
                    String dtype = tag(xml, "deviceType");
                    StringBuilder extra = new StringBuilder();
                    // list service types (what it can do)
                    int i = 0;
                    while (extra.length() < 200) {
                        String st = tagFrom(xml, "serviceType", i++);
                        if (st == null) break;
                        String shortSt = st.replace("urn:schemas-upnp-org:service:", "")
                                .replace("urn:schemas-upnp-org:device:", "");
                        if (!extra.toString().contains(shortSt)) extra.append(shortSt).append(" ");
                        if (i > 12) break;
                    }
                    String combined = dtype != null && dtype.contains("InternetGateway") ? "router/gateway"
                            : (dtype != null && dtype.contains("MediaRenderer") ? "cast/mirroring target"
                            : (dtype != null && dtype.contains("MediaServer") ? "media server"
                            : (dtype != null && dtype.contains("Printer") ? "printer" : null)));
                    if (cb != null) cb.onDevice(
                            name != null ? name : "?",
                            mfr,
                            model != null ? model + (combined != null ? " · looks like a " + combined : "") : combined,
                            extra.length() > 0 ? extra.toString().trim() : null,
                            loc);
                } catch (Exception e) {
                    if (cb != null) cb.onDevice(null, null, null, null, loc);
                }
            }
        }).start();
    }

    private static String headerOf(String resp, String name) {
        for (String line : resp.split("\r?\n")) {
            if (line.regionMatches(true, 0, name, 0, name.length()))
                return line.substring(name.length()).trim();
        }
        return null;
    }

    private static String httpGet(String host, int port, String path) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1500);
            s.setSoTimeout(2500);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes());
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] chunk = new byte[1024];
            int n;
            while (buf.size() < 65536 && (n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toString("ISO-8859-1");
        } catch (Exception e) {
            return null;
        }
    }

    private static String tag(String xml, String t) { return tagFrom(xml, t, 0); }

    private static String tagFrom(String xml, String t, int occurrence) {
        try {
            String low = xml.toLowerCase();
            String needle = "<" + t.toLowerCase();
            int idx = -1;
            for (int k = 0; k <= occurrence; k++) {
                idx = low.indexOf(needle, idx + 1);
                if (idx < 0) return null;
            }
            int a = xml.indexOf('>', idx);
            int b = low.indexOf("</" + t.toLowerCase(), idx);
            if (a < 0 || b <= a) return null;
            return xml.substring(a + 1, b).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** NetBIOS Node Status query (UDP 137) — device names for Windows/Samba/IoT. Blocking <=900ms. */
    public static String netbiosNameSync(String ip) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(700);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            java.util.Random rnd = new java.util.Random();
            int id = rnd.nextInt(65536);
            b.write((id >> 8) & 0xFF); b.write(id & 0xFF);
            b.write(0x00); b.write(0x10); // recursive query flags
            b.write(0x00); b.write(0x01); // qdcount=1
            b.write(0); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0);
            b.write(0x20); // encoded-name length (32)
            byte[] enc = encodeNBName("*");
            b.write(enc, 0, enc.length);
            b.write(0x00);
            b.write(0x00); b.write(0x21); // type NBSTAT
            b.write(0x00); b.write(0x01); // class IN
            byte[] q = b.toByteArray();
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(ip), 137));
            byte[] buf = new byte[1024];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            return parseNbstat(buf, p.getLength());
        } catch (Exception e) {
            return null;
        } finally {
            if (s != null) s.close();
        }
    }

    private static byte[] encodeNBName(String name) {
        StringBuilder sb = new StringBuilder();
        String padded = String.format("%-15s", name).toUpperCase();
        byte[] raw = padded.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (byte c : raw) {
            sb.append((char) ('A' + ((c >> 4) & 0xF)));
            sb.append((char) ('A' + (c & 0xF)));
        }
        sb.append("AA"); // null terminator nibbles
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String parseNbstat(byte[] r, int len) {
        try {
            int answers = ((r[6] & 0xFF) << 8) | (r[7] & 0xFF);
            if (answers == 0 || len < 60) return null;
            int i = 12;
            int l = r[i] & 0xFF; i += 1 + l + 1 + 4;   // question name + type/class
            l = r[i] & 0xFF; i += 1 + l + 1;           // answer name
            i += 8;                                     // type(2)+class(2)+ttl(4)
            i += 2;                                     // rdlength
            int count = r[i] & 0xFF; i++;
            if (count == 0) return null;
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 15; k++) {
                char ch = (char) (r[i + k] & 0xFF);
                if (ch == 0 || ch == ' ') break;
                sb.append(ch);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Best-effort device name: fetch http://ip/ and use its &lt;title&gt; or Server header. */
    public static String httpTitle(String ip) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, 80), 800);
            s.setSoTimeout(1500);
            OutputStream out = s.getOutputStream();
            out.write(("GET / HTTP/1.0\r\nHost: " + ip
                    + "\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n").getBytes());
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] chunk = new byte[1024];
            int n;
            while (buf.size() < 16384 && (n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            String resp = buf.toString("ISO-8859-1");
            int a = resp.toLowerCase().indexOf("<title>");
            int b2 = resp.toLowerCase().indexOf("</title>");
            if (a >= 0 && b2 > a) {
                String t = resp.substring(a + 7, b2).replaceAll("\\s+", " ").trim();
                if (t.length() >= 2 && t.matches("[\\x20-\\x7e]{2,60}")) return t;
            }
            for (String line : resp.split("\\r\\n")) {
                if (line.toLowerCase().startsWith("server:")) {
                    String v = line.substring(7).trim();
                    if (!v.isEmpty()) return v.length() > 40 ? v.substring(0, 40) : v;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Targeted MAC lookup for one IP: nudge (TCP connect) then read neighbor tables. */
    public static String macOf(String ip) {
        String mac = neighborTable().get(ip);
        if (mac != null) return mac;
        for (int port : new int[]{80, 443, 8080}) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, port), 250);
                return neighborTable().get(ip);
            } catch (Exception ignored) {}
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ip", "neigh", "show", ip});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                String[] t = line.trim().split("\\s+");
                for (int i = 0; i < t.length - 1; i++)
                    if (t[i].equals("lladdr")) { r.close(); p.waitFor(); return t[i + 1].toUpperCase(); }
            }
            r.close();
            p.waitFor();
        } catch (Exception ignored) {}
        return arpTable().get(ip);
    }

    public interface MacLookup {
        void onResult(String ip, String mac, boolean reachable);
    }

    /** Query a single MAC by pinging then reading ARP (populates kernel cache). */
    public static void resolveMac(String ip, long timeoutMs, MacLookup cb) {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        ex.execute(() -> {
            boolean up = ping(ip);
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            String mac = arpTable().get(ip);
            cb.onResult(ip, mac, up);
        });
        ex.shutdown();
        try { ex.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
    }
}
