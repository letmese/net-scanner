package com.netscanner.net;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/** Protocol-aware service fingerprinting (nmap-lite, root-free). */
public final class Fingerprinter {

    private Fingerprinter() {}

    /** Returns a human-readable software banner for an open port, or null if nothing learned. */
    public static String probe(String ip, int port) {
        try {
            switch (port) {
                case 21: case 23: case 25: case 110: case 119: case 143:
                case 465: case 587: case 993: case 995:
                    return readGreeting(ip, port);
                case 22:
                    String s = readGreeting(ip, port);
                    return s != null && s.startsWith("SSH-") ? s : s;
                case 80: case 81: case 443: case 591: case 3000: case 5000:
                case 8000: case 8008: case 8080: case 8081: case 8443: case 8888: case 9000:
                    return httpProbe(ip, port, isTls(port));
                case 4444: case 5060: case 554: case 1935:
                    return rtspProbe(ip, port);
                case 3306:
                    return mysqlGreeting(ip, port);
                case 5432:
                    String g = readGreeting(ip, port); // postgres sends error-free greeting rarely; try raw
                    return g != null ? g : null;
                case 5900: case 5901: case 5902:
                    String rfb = readGreeting(ip, port);
                    return rfb != null && rfb.startsWith("RFB") ? rfb + " (VNC)" : null;
                case 6379:
                    return redisPing(ip, port);
                default:
                    return readGreeting(ip, port);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isTls(int port) { return port == 443 || port == 8443; }

    /** Connect and read whatever the server sends first (banners). */
    private static String readGreeting(String ip, int port) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ip, port), 1200);
        s.setSoTimeout(1500);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        InputStream in = s.getInputStream();
        byte[] chunk = new byte[256];
        try {
            int n;
            while (buf.size() < 256 && (n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                String soFar = buf.toString("ISO-8859-1");
                if (soFar.contains("\n")) break;
            }
        } catch (Exception ignored) {}
        s.close();
        String resp = sanitize(buf.toString("ISO-8859-1"));
        if (resp.isEmpty()) return null;

        if (port == 3306) {
            // MySQL greeting: [3B len][seq][proto][version NUL-terminated]
            if (resp.length() > 6) {
                int end = resp.indexOf(0, 5);
                if (end < 0) end = Math.min(resp.length(), 40);
                return "MySQL/MariaDB " + resp.substring(5, end);
            }
            return null;
        }
        // take first meaningful line, trim
        int nl = resp.indexOf('\n');
        String line = nl > 0 ? resp.substring(0, nl) : resp;
        line = line.trim();
        if (line.length() < 3 || line.matches("[\\x00-\\x08\\x0e-\\x1f].*")) return null;
        if (line.startsWith("RFB ")) return line + " (VNC)";
        return line.length() > 60 ? line.substring(0, 60) : line;
    }

    private static String httpProbe(String ip, int port, boolean tls) throws Exception {
        Socket s;
        if (tls) {
            TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, new java.security.SecureRandom());
            s = ctx.getSocketFactory().createSocket();
            ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
        } else {
            s = new Socket();
        }
        try {
            s.connect(new InetSocketAddress(ip, port), 1500);
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: " + ip + "\r\nUser-Agent: Mozilla/5.0 NetScanner\r\nConnection: close\r\n\r\n").getBytes());
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] chunk = new byte[512];
            int n;
            boolean sawEnd = false;
            while (buf.size() < 16384 && (n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                String soFar = buf.toString("ISO-8859-1");
                if (!sawEnd && soFar.contains("\r\n\r\n")) sawEnd = true;
                else if (sawEnd) {
                    // after headers, stop once we likely have <title>
                    String low = soFar.toLowerCase();
                    if ((low.contains("</title>") || low.contains("<body")) && buf.size() > 512) break;
                }
            }
            String resp = buf.toString("ISO-8859-1");
            String server = header(resp, "Server:");
            String powered = header(resp, "X-Powered-By:");
            String loc = header(resp, "Location:");
            StringBuilder sb = new StringBuilder();
            int sc = statusLine(resp);
            if (sc > 0) sb.append("HTTP ").append(sc);
            if (server != null) sb.append(sb.length() > 0 ? " · " : "").append(server);
            if (powered != null) sb.append(" · ").append(powered);
            if (loc != null) sb.append(" → ").append(loc);
            String t = extractTitle(resp);
            if (t != null) sb.append(" · “").append(t).append("”");

            // Anonymous 404s: try UPnP self-description & OPTIONS for identity
            if ((sc >= 400 || sb.length() <= 8)) {
                String upnp = upnpDescription(ip, port, tls);
                if (upnp != null) sb.append(" · ").append(upnp);
                else {
                    String allow = optionsProbe(ip, port, tls);
                    if (allow != null && !allow.isEmpty()) sb.append(" · allows: ").append(allow);
                }
            }
            if (tls && sb.length() > 0) sb.append(" [TLS]");
            return sb.length() == 0 ? null : sb.toString();
        } finally {
            s.close();
        }
    }

    private static String rtspProbe(String ip, int port) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ip, port), 1200);
        s.setSoTimeout(1500);
        OutputStream out = s.getOutputStream();
        out.write(("OPTIONS rtsp://" + ip + ":" + port + " RTSP/1.0\r\nCSeq: 1\r\n\r\n").getBytes());
        out.flush();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        InputStream in = s.getInputStream();
        byte[] chunk = new byte[512];
        try {
            int n;
            while (buf.size() < 2048 && (n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.toString("ISO-8859-1").contains("\r\n\r\n")) break;
            }
        } catch (Exception ignored) {}
        s.close();
        String resp = buf.toString("ISO-8859-1");
        String srv = header(resp, "Server:");
        return srv != null ? srv + " (RTSP)" : null;
    }

    private static String mysqlGreeting(String ip, int port) throws Exception {
        String g = readGreeting(ip, port);
        return g;
    }

    private static String redisPing(String ip, int port) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ip, port), 1200);
        s.setSoTimeout(1500);
        OutputStream out = s.getOutputStream();
        out.write("PING\r\n".getBytes());
        out.flush();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        InputStream in = s.getInputStream();
        byte[] chunk = new byte[64];
        try {
            int n = in.read(chunk);
            if (n > 0) buf.write(chunk, 0, n);
        } catch (Exception ignored) {}
        s.close();
        String r = buf.toString("ISO-8859-1").trim();
        if (r.startsWith("+PONG")) {
            // try INFO server for version
            return "Redis (unauthenticated!)";
        }
        return r.isEmpty() ? null : "Redis-like: " + r;
    }

    /** Ask a UPnP device to describe itself via /description.xml */
    private static String upnpDescription(String ip, int port, boolean tls) {
        try {
            String resp = simpleGet(ip, port, tls, "/description.xml");
            if (resp == null) resp = simpleGet(ip, port, tls, "/dd.xml");
            if (resp == null || !resp.contains("<")) return null;
            String low = resp.toLowerCase();
            String name = xmlTag(resp, "friendlyName");
            String mfr = xmlTag(resp, "manufacturer");
            String model = xmlTag(resp, "modelName");
            StringBuilder sb = new StringBuilder();
            if (name != null) sb.append(name);
            if (mfr != null) sb.append(sb.length() > 0 ? " by " : "").append(mfr);
            if (model != null) sb.append(" (").append(model).append(")");
            if (sb.length() > 0) return "UPnP: " + sb;
            if (low.contains("<html") || low.contains("<xml") || low.contains("<?xml"))
                return "serves XML/HTML at /description.xml";
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String optionsProbe(String ip, int port, boolean tls) {
        try {
            String resp = simpleGetRaw(ip, port, tls, "OPTIONS *", null);
            String allow = header(resp, "Allow:");
            String publicH = header(resp, "Public:");
            return allow != null ? allow : publicH;
        } catch (Exception e) {
            return null;
        }
    }

    private static String simpleGet(String ip, int port, boolean tls, String path) throws Exception {
        String r = simpleGetRaw(ip, port, tls, "GET " + path, path);
        return r;
    }

    private static String simpleGetRaw(String ip, int port, boolean tls, String reqLine, String path) throws Exception {
        Socket s;
        if (tls) {
            TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, new java.security.SecureRandom());
            s = ctx.getSocketFactory().createSocket();
        } else {
            s = new Socket();
        }
        try {
            s.connect(new InetSocketAddress(ip, port), 1200);
            s.setSoTimeout(1500);
            OutputStream out = s.getOutputStream();
            out.write((reqLine + " HTTP/1.1\r\nHost: " + ip + "\r\nConnection: close\r\n\r\n").getBytes());
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] chunk = new byte[512];
            int n;
            while (buf.size() < 8192 && (n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toString("ISO-8859-1");
        } finally {
            s.close();
        }
    }

    private static String xmlTag(String xml, String tag) {
        try {
            int a = xml.indexOf("<" + tag + ">", xml.toLowerCase().indexOf("<" + tag.toLowerCase()));
            int open = xml.toLowerCase().indexOf("<" + tag.toLowerCase());
            if (open < 0) return null;
            a = xml.indexOf('>', open);
            int b = xml.toLowerCase().indexOf("</" + tag.toLowerCase() + ">", open);
            if (a < 0 || b <= a) return null;
            return xml.substring(a + 1, b).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractTitle(String resp) {
        try {
            String low = resp.toLowerCase();
            int open = low.indexOf("<title");
            if (open < 0) return null;
            int a = resp.indexOf('>', open);
            int b = low.indexOf("</title>", open);
            if (a < 0 || b <= a) return null;
            String t = resp.substring(a + 1, b).trim();
            return t.isEmpty() ? null : (t.length() > 50 ? t.substring(0, 50) : t);
        } catch (Exception e) {
            return null;
        }
    }

    private static String header(String resp, String name) {
        for (String line : resp.split("\r?\n")) {
            if (line.regionMatches(true, 0, name, 0, name.length()))
                return line.substring(name.length()).trim();
        }
        return null;
    }

    private static int statusLine(String resp) {
        if (!resp.startsWith("HTTP/")) return -1;
        try {
            int sp = resp.indexOf(' ');
            return Integer.parseInt(resp.substring(sp + 1, sp + 4));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\t' || c == '\n' || c == '\r' || (c >= 32 && c < 127)) sb.append(c);
        }
        return sb.toString().trim();
    }

    /** Friendly verdict for common risky services. */
    public static String riskNote(int port) {
        switch (port) {
            case 23: return "Telnet — plaintext passwords, disable it";
            case 21: return "FTP — plaintext login, prefer SFTP";
            case 5555: return "ADB over network — full device control";
            case 445: case 139: return "SMB/NetBIOS — WannaCry territory, don't expose";
            case 3389: return "RDP — brute-force magnet";
            case 6379: return "Redis open without auth = remote shell";
            case 1900: case 5000: return "UPnP — can punch holes in your router";
            default: return null;
        }
    }
}
