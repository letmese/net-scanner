package com.netscanner.tools;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.netscanner.AppLog;
import com.netscanner.R;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/** Generic runner for the single-input tools (mDNS, SNMP, TLS cert, DNS, security audit, netinfo, subnet, speed). */
public class ToolRunnerActivity extends AppCompatActivity {

    private String tool;
    private TextView out;
    private EditText input;
    private NsdManager nsd;
    private volatile boolean mdnsRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool_runner);

        tool = getIntent().getStringExtra("tool");
        String title = getIntent().getStringExtra("title");
        String hint = getIntent().getStringExtra("hint");

        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tv_title)).setText(title);
        out = findViewById(R.id.tv_output);
        out.setMovementMethod(new ScrollingMovementMethod());
        input = findViewById(R.id.et_input);
        input.setHint(hint == null || hint.isEmpty() ? "input" : hint);
        if ("mdns".equals(tool) || "netinfo".equals(tool) || "speed".equals(tool)
                || "extport".equals(tool))
            input.setVisibility(android.view.View.GONE);

        findViewById(R.id.btn_run).setOnClickListener(v -> run());
        run(); // auto-run for input-less tools
    }

    private void log(String s) {
        AppLog.log(s);
        runOnUiThread(() -> {
            try { out.append(s + "\n"); }
            catch (Throwable t) { AppLog.log("ui append failed: " + t); }
        });
    }

    private void run() {
        out.setText("");
        new Thread(() -> {
            try {
                switch (tool) {
                    case "mdns": runMdns(); break;
                    case "snmp": runSnmp(); break;
                    case "cert": runCert(); break;
                    case "secaudit": runSecAudit(); break;
                    case "dns": runDns(); break;
                    case "netinfo": runNetInfo(); break;
                    case "subnet": runSubnet(); break;
                    case "speed": runSpeed(); break;
                    case "extport": runExtPort(); break;
                    case "probe": runProbe(); break;
                    case "whois": runWhois(); break;
                    case "cameras": runCameras(); break;
                    case "dnshijack": runDnsHijack(); break;
                    case "httpforge": runForge(); break;
                }
            } catch (Throwable e) {
                AppLog.log("tool '" + tool + "' fatal: " + e);
                log("❌ " + e);
            }
        }).start();
    }

    private String in() { return input.getText().toString().trim(); }

    // ───────────────── mDNS ─────────────────
    private static final String[] MDNS_TYPES = {
            "_googlecast._tcp", "_airplay._tcp", "_http._tcp", "_ipp._tcp", "_printer._tcp",
            "_smb._tcp", "_workstation._tcp", "_adb-tls-connect._tcp", "_hap._tcp",
            "_spotify-connect._tcp", "_dlna._tcp", "_nvstream._tcp"
    };

    private final java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo> mdnsQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean resolving;

    private void runMdns() {
        mdnsRunning = true;
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        AtomicInteger pending = new AtomicInteger(MDNS_TYPES.length);
        log("Browsing " + MDNS_TYPES.length + " service types… (12s)");
        for (String type : MDNS_TYPES) {
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, new NsdManager.DiscoveryListener() {
                    @Override public void onDiscoveryStarted(String t) {}
                    @Override public void onDiscoveryStopped(String t) {}
                    @Override public void onStartDiscoveryFailed(String t, int e) { pending.decrementAndGet(); }
                    @Override public void onStopDiscoveryFailed(String t, int e) {}
                    @Override public void onServiceLost(NsdServiceInfo s) {}
                    @Override public void onServiceFound(NsdServiceInfo svc) {
                        // NsdManager resolves one at a time — enqueue and drain sequentially
                        mdnsQueue.add(svc);
                        drainQueue();
                    }
                });
            } catch (Exception e) {
                pending.decrementAndGet();
            }
        }
        try { Thread.sleep(12000); } catch (InterruptedException ignored) {}
        mdnsRunning = false;
        log("✅ mDNS browse finished.");
    }

    private void drainQueue() {
        if (resolving || !mdnsRunning) return;
        NsdServiceInfo next = mdnsQueue.poll();
        if (next == null) return;
        resolving = true;
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int e) {
                    resolving = false; drainQueue();
                }
                @Override public void onServiceResolved(NsdServiceInfo i) {
                    String host = i.getHost() != null ? i.getHost().getHostAddress() : "?";
                    log("📦 " + next.getServiceName() + " → " + host + ":" + i.getPort()
                            + "  (" + next.getServiceType() + ")");
                    resolving = false; drainQueue();
                }
            });
        } catch (Exception e) {
            resolving = false; drainQueue();
        }
    }

    @Override protected void onDestroy() {
        mdnsRunning = false;
        super.onDestroy();
    }

    // ───────────────── SNMP ─────────────────
    private void runSnmp() throws Exception {
        String ip = in();
        if (ip.isEmpty()) { log("Enter a device IP"); return; }
        byte[] req = snmpGet(new byte[][]{
                {0x2b, 0x06, 0x01, 0x02, 0x01, 0x01, 0x01, 0x00}, // sysDescr
                {0x2b, 0x06, 0x01, 0x02, 0x01, 0x01, 0x05, 0x00}  // sysName
        });
        DatagramSocket s = new DatagramSocket();
        s.setSoTimeout(2000);
        s.send(new DatagramPacket(req, req.length, InetAddress.getByName(ip), 161));
        byte[] buf = new byte[2048];
        try {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            byte[] r = java.util.Arrays.copyOf(p.getData(), p.getLength());
            int found = 0;
            for (int i = 0; i < r.length - 2 && found < 2; i++) {
                if ((r[i] & 0xFF) == 0x04 && r[i + 1] > 2 && r[i + 1] < 200 && i + 2 + r[i + 1] <= r.length) {
                    String val = new String(r, i + 2, r[i + 1], StandardCharsets.ISO_8859_1);
                    if (val.matches("[\\x20-\\x7e]{3,}")) {
                        log((found == 0 ? "sysDescr: " : "sysName:   ") + val);
                        i += r[i + 1] + 1;
                        found++;
                    }
                }
            }
            if (found == 0) log("No SNMP response (device may not enable SNMP or uses a non-public community)");
        } catch (java.net.SocketTimeoutException e) {
            log("⏱ No SNMP reply from " + ip + " (SNMP agent off or filtered)");
        }
        s.close();
    }

    private byte[] snmpGet(byte[][] oids) {
        ByteArrayOutputStream varbinds = new ByteArrayOutputStream();
        for (byte[] oid : oids) {
            ByteArrayOutputStream vb = new ByteArrayOutputStream();
            vb.write(0x06); vb.write(oid.length); vb.write(oid, 0, oid.length);
            vb.write(0x05); vb.write(0x00); // NULL
            byte[] vbBody = vb.toByteArray();
            ByteArrayOutputStream vbl = new ByteArrayOutputStream();
            vbl.write(0x30); vbl.write(vbBody.length); vbl.write(vbBody, 0, vbBody.length);
            varbinds.write(vbl.toByteArray(), 0, vbl.size());
        }
        byte[] vblBytes = varbinds.toByteArray();
        ByteArrayOutputStream pdu = new ByteArrayOutputStream();
        pdu.write(0x02); pdu.write(1); pdu.write(0x01);          // request-id=1
        pdu.write(0x02); pdu.write(1); pdu.write(0x00);          // error-status=0
        pdu.write(0x02); pdu.write(1); pdu.write(0x00);          // error-index=0
        pdu.write(vblBytes, 0, vblBytes.length);
        byte[] pduBytes = pdu.toByteArray();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x02); body.write(1); body.write(0x00);       // version 0 (v1)
        byte[] comm = "public".getBytes();
        body.write(0x04); body.write(comm.length); body.write(comm, 0, comm.length);
        body.write(0xA0); body.write(pduBytes.length); body.write(pduBytes, 0, pduBytes.length);
        byte[] bodyBytes = body.toByteArray();
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        msg.write(0x30); msg.write(bodyBytes.length); msg.write(bodyBytes, 0, bodyBytes.length);
        return msg.toByteArray();
    }

    // ───────────────── TLS cert ─────────────────
    private void runCert() throws Exception {
        String t = in();
        String host = t.contains(":") ? t.split(":")[0] : t;
        int port = t.contains(":") ? Integer.parseInt(t.split(":")[1]) : 443;
        log("Connecting to " + host + ":" + port + " …");
        TrustManager tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, new java.security.SecureRandom());
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
        s.connect(new InetSocketAddress(host, port), 3000);
        s.setSoTimeout(3000);
        s.startHandshake();
        X509Certificate c = (X509Certificate) s.getSession().getPeerCertificates()[0];
        log("Subject:  " + c.getSubjectX500Principal().getName());
        log("Issuer:   " + c.getIssuerX500Principal().getName());
        log("Valid:    " + c.getNotBefore() + "  →  " + c.getNotAfter());
        long daysLeft = (c.getNotAfter().getTime() - System.currentTimeMillis()) / 86400000L;
        log("Expires in: " + daysLeft + " days" + (daysLeft < 0 ? "  ⚠️ EXPIRED" : ""));
        try {
            java.util.Collection<?> sans = c.getSubjectAlternativeNames();
            if (sans != null) for (Object o : sans) {
                java.util.List<?> pair = (java.util.List<?>) o;
                if (pair.size() > 1) log("SAN: " + pair.get(1));
            }
        } catch (Exception ignored) {}
        log("Sig alg:  " + c.getSigAlgName());
        s.close();
    }

    // ───────────────── HTTP security audit ─────────────────
    private void runSecAudit() throws Exception {
        String u = in();
        if (!u.startsWith("http")) u = "http://" + u;
        log("Auditing " + u + " …");
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(4000);
        c.setInstanceFollowRedirects(false);
        int code = c.getResponseCode();
        log("Status: " + code);
        String[][] checks = {
                {"Strict-Transport-Security", "HSTS (forces HTTPS)"},
                {"Content-Security-Policy", "CSP (blocks injected scripts)"},
                {"X-Frame-Options", "Clickjacking protection"},
                {"X-Content-Type-Options", "MIME sniffing protection"},
                {"Referrer-Policy", "Referrer leakage control"},
                {"Permissions-Policy", "Browser feature gating"}
        };
        int present = 0;
        for (String[] h : checks) {
            String v = c.getHeaderField(h[0]);
            if (v != null) { present++; log("✅ " + h[0] + " — " + h[1]); }
            else log("❌ missing " + h[0] + " — " + h[1]);
        }
        String grade = present >= 5 ? "A" : present == 4 ? "B" : present == 3 ? "C"
                : present == 2 ? "D" : present == 1 ? "E" : "F";
        log("\nGrade: " + grade + "  (" + present + "/6 headers present)");
        c.disconnect();
    }

    // ───────────────── DNS toolkit ─────────────────
    private void runDns() throws Exception {
        String domain = in();
        if (domain.isEmpty()) { log("Enter a domain"); return; }
        String server = "8.8.8.8";
        if (domain.contains("@")) { server = domain.split("@")[1]; domain = domain.split("@")[0]; }
        log("Querying " + server + " for " + domain + " …");
        for (int type : new int[]{1, 28, 15, 16}) // A AAAA MX TXT
            lookupAndLog(server, domain, type);

        log("\nResolver speed test (fresh domain each round):" );
        String[][] resolvers = {{"System", null}, {"Google", "8.8.8.8"}, {"Cloudflare", "1.1.1.1"}, {"Quad9", "9.9.9.9"}};
        String[] domains = {"www.google.com", "www.cloudflare.com", "www.wikipedia.org",
                "www.bing.com", "www.reddit.com", "www.github.com"};
        for (String[] r : resolvers) {
            long total = 0; int ok = 0;
            for (int i = 0; i < 3; i++) {
                String dom = domains[(int) ((System.currentTimeMillis() / 1000) % domains.length)] + ".";
                long ms = r[1] == null ? systemDnsMs(dom) : dnsQueryMs(r[1], dom, 1);
                if (ms >= 0) { total += ms; ok++; }
                try { Thread.sleep(120); } catch (InterruptedException ignored) {}
            }
            log("  " + String.format("%-10s %s", r[0] + ":", ok == 0 ? "failed" : (total / ok) + " ms"));
        }
    }

    private void lookupAndLog(String server, String domain, int type) {
        try {
            byte[] q = dnsQuery(domain, type);
            DatagramSocket s = new DatagramSocket();
            s.setSoTimeout(2000);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(server), 53));
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            byte[] r = java.util.Arrays.copyOf(p.getData(), p.getLength());
            int answers = ((r[6] & 0xFF) << 8) | (r[7] & 0xFF);
            s.close();
            if (answers == 0) return;
            // skip header(12) + question
            int i = 12;
            while ((r[i] & 0xFF) != 0) i += (r[i] & 0xFF) + 1;
            i += 5;
            String[] names = {"", "A", "", "CNAME", "", "MX", "", "", "", "PTR", "", "", "", "TXT", "", "", "AAAA"};
            for (int a = 0; a < answers; a++) {
                if ((r[i] & 0xC0) == 0xC0) i += 2; else { while ((r[i] & 0xFF) != 0) i += (r[i] & 0xFF) + 1; i++; }
                int rtype = ((r[i] & 0xFF) << 8) | (r[i + 1] & 0xFF);
                int rdlen = ((r[i + 8] & 0xFF) << 8) | (r[i + 9] & 0xFF);
                int d0 = i + 10;
                String val;
                if (rtype == 1) val = (r[d0] & 0xFF) + "." + (r[d0+1] & 0xFF) + "." + (r[d0+2] & 0xFF) + "." + (r[d0+3] & 0xFF);
                else if (rtype == 28) {
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < 16; k += 2)
                        sb.append(String.format("%x:", ((r[d0+k]&0xFF)<<8)|(r[d0+k+1]&0xFF)));
                    val = sb.substring(0, sb.length() - 1);
                }
                else if (rtype == 16) {
                    int len = r[d0] & 0xFF;
                    val = new String(r, d0 + 1, len, StandardCharsets.ISO_8859_1);
                }
                else if (rtype == 15) val = "priority " + (((r[d0]&0xFF)<<8)|(r[d0+1]&0xFF)) + " → " + readName(r, d0 + 2);
                else val = readName(r, d0);
                log("  " + (rtype < names.length ? names[rtype] : "T" + rtype) + "  " + val);
                i = d0 + rdlen;
            }
        } catch (Exception ignored) {}
    }

    private String readName(byte[] r, int off) {
        StringBuilder sb = new StringBuilder();
        int i = off, hops = 0;
        while (hops++ < 8) {
            int len = r[i] & 0xFF;
            if (len == 0) break;
            if ((len & 0xC0) == 0xC0) {
                int ptr = ((len & 0x3F) << 8) | (r[i + 1] & 0xFF);
                String rest = readName(r, ptr);
                sb.append(rest);
                break;
            }
            if (sb.length() > 0) sb.append(".");
            sb.append(new String(r, i + 1, len, StandardCharsets.ISO_8859_1));
            i += len + 1;
        }
        return sb.toString();
    }

    private byte[] dnsQuery(String name, int type) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        java.util.Random rnd = new java.util.Random();
        int id = rnd.nextInt(65536);
        b.write((id >> 8) & 0xFF); b.write(id & 0xFF);
        b.write(0x01); b.write(0x00); // RD
        b.write(0x00); b.write(0x01); // qdcount
        b.write(0); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0);
        for (String label : name.split("\\.")) {
            b.write(label.length());
            b.write(label.getBytes(), 0, label.length());
        }
        b.write(0);
        b.write((type >> 8) & 0xFF); b.write(type & 0xFF);
        b.write(0); b.write(1); // IN
        return b.toByteArray();
    }

    private long dnsQueryMs(String server, String name, int type) {
        try {
            byte[] q = dnsQuery(name, type);
            DatagramSocket s = new DatagramSocket();
            s.setSoTimeout(1500);
            long t0 = System.currentTimeMillis();
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(server), 53));
            byte[] buf = new byte[512];
            s.receive(new DatagramPacket(buf, buf.length));
            s.close();
            return System.currentTimeMillis() - t0;
        } catch (Exception e) { return -1; }
    }

    private long systemDnsMs(String name) {
        try {
            long t0 = System.currentTimeMillis();
            InetAddress.getByName(name);
            return System.currentTimeMillis() - t0;
        } catch (Exception e) { return -1; }
    }

    // ───────────────── Net info ─────────────────
    private void runNetInfo() throws Exception {
        com.netscanner.net.NetworkUtils.LocalNet n = com.netscanner.net.NetworkUtils.localNet();
        if (n != null) {
            log("Local IP:    " + n.ip);
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.DhcpInfo dhcp = wm.getDhcpInfo();
            if (dhcp != null) {
                log("Gateway:     " + intToIp(dhcp.gateway));
                log("Netmask:     " + intToIp(dhcp.netmask));
                log("DNS:         " + intToIp(dhcp.dns1));
                log("Lease:       " + (dhcp.leaseDuration / 3600) + " h");
            }
        }
        try {
            String pub = httpGetText("https://api.ipify.org");
            log("Public IP:   " + pub);
            String info = httpGetText("https://ipinfo.io/json");
            log("ISP/Org:     " + jsonVal(info, "org"));
            log("City:        " + jsonVal(info, "city") + ", " + jsonVal(info, "region") + " " + jsonVal(info, "country"));
        } catch (Exception e) {
            log("Public IP lookup failed: " + e.getMessage());
        }
    }

    private String intToIp(int i) {
        return (i & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + ((i >> 16) & 0xFF) + "." + ((i >> 24) & 0xFF);
    }

    private String jsonVal(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"");
        if (i < 0) return "?";
        int a = i + key.length() + 4;
        int b = json.indexOf('"', a);
        return json.substring(a, b);
    }

    private String httpGetText(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(5000);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        InputStream in = c.getInputStream();
        byte[] chunk = new byte[1024];
        int n;
        while ((n = in.read(chunk)) > 0) b.write(chunk, 0, n);
        c.disconnect();
        return b.toString("UTF-8").trim();
    }

    // ───────────────── Subnet calc ─────────────────
    private void runSubnet() {
        String[] parts = in().split("/");
        try {
            int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : 24;
            int ip = ipToInt(parts[0]);
            int mask = prefix == 0 ? 0 : (0xFFFFFFFF << (32 - prefix));
            int net = ip & mask;
            int bcast = net | (~mask);
            log("Network:     " + intToStr(net) + "/" + prefix);
            log("Netmask:     " + intToStr(mask));
            log("Wildcard:    " + intToStr(~mask));
            log("First host:  " + intToStr(net + 1));
            log("Last host:   " + intToStr(bcast - 1));
            log("Broadcast:   " + intToStr(bcast));
            long hosts = prefix >= 31 ? (1L << (32 - prefix)) : ((1L << (32 - prefix)) - 2);
            log("Usable hosts: " + hosts);
        } catch (Exception e) {
            log("Format: 192.168.1.0/24");
        }
    }

    private int ipToInt(String ip) {
        String[] p = ip.split("\\.");
        return (Integer.parseInt(p[0]) << 24) | (Integer.parseInt(p[1]) << 16)
                | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
    }

    private String intToStr(int i) {
        return ((i >> 24) & 0xFF) + "." + ((i >> 16) & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + (i & 0xFF);
    }

    // ───────────────── Raw probe console ─────────────────
    private void runProbe() throws Exception {
        String t = in();
        if (t.isEmpty()) {
            log("Format: host:port [payload]\nPrefix 'udp ' for UDP. Empty payload = just connect.");
            return;
        }
        boolean udp = t.startsWith("udp ");
        if (udp) t = t.substring(4).trim();
        int sp2 = t.indexOf(' ');
        String hp = sp2 > 0 ? t.substring(0, sp2) : t;
        String payload = sp2 > 0 ? t.substring(sp2 + 1) : "";
        String host = hp.contains(":") ? hp.split(":")[0] : hp;
        int port = hp.contains(":") ? Integer.parseInt(hp.split(":")[1]) : 80;
        log((udp ? "UDP" : "TCP") + " probe → " + host + ":" + port);
        if (udp) {
            java.net.DatagramSocket s = new java.net.DatagramSocket();
            s.setSoTimeout(2500);
            byte[] data = (payload.isEmpty() ? "\r\n" : payload).getBytes();
            s.send(new java.net.DatagramPacket(data, data.length, InetAddress.getByName(host), port));
            byte[] buf = new byte[2048];
            java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
                log("← " + p.getLength() + " bytes:");
                log(new String(buf, 0, p.getLength()).replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "."));
            } catch (java.net.SocketTimeoutException e) {
                log("(no reply within 2.5s)");
            }
            s.close();
        } else {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(2500);
            OutputStream os = s.getOutputStream();
            os.write((payload.isEmpty() ? "" : payload + "\r\n").getBytes());
            os.flush();
            InputStream is = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while (b.size() < 4096 && (n = is.read(buf)) > 0) b.write(buf, 0, n);
            s.close();
            if (b.size() == 0) log("(connected — server said nothing)");
            else log(b.toString("ISO-8859-1").replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "."));
        }
        com.netscanner.AppLog.log("probe done " + host + ":" + port);
    }

    // ───────────────── Whois / IP intel (RDAP) ─────────────────
    private void runWhois() throws Exception {
        String q = in();
        if (q.isEmpty()) { log("Enter a domain (example.com) or IP (1.1.1.1)"); return; }
        boolean isIp = q.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        String url = "https://rdap.org/" + (isIp ? "ip/" : "domain/") + q;
        log("RDAP lookup…");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/rdap+json");
        int code = c.getResponseCode();
        if (code != 200) { log("HTTP " + code + " from rdap.org"); return; }
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String ln;
        while ((ln = r.readLine()) != null) sb.append(ln);
        r.close();
        c.disconnect();
        JSONObject o = new JSONObject(sb.toString());
        log("Object:  " + o.optString("ldhName", o.optString("handle", q)));
        if (o.has("name")) log("Name:    " + o.optString("name"));
        if (o.has("type")) log("Type:    " + o.optString("type"));
        if (o.has("country")) log("Country: " + o.optString("country"));
        if (o.has("startAddress"))
            log("Range:   " + o.optString("startAddress") + " → " + o.optString("endAddress"));
        org.json.JSONArray st = o.optJSONArray("status");
        if (st != null && st.length() > 0) {
            StringBuilder s2 = new StringBuilder();
            for (int i = 0; i < st.length(); i++) s2.append(st.getString(i)).append(" ");
            log("Status:  " + s2.toString().trim());
        }
        org.json.JSONArray ev = o.optJSONArray("events");
        if (ev != null) for (int i = 0; i < ev.length(); i++)
            log("  " + ev.getJSONObject(i).optString("eventAction")
                    + ": " + ev.getJSONObject(i).optString("eventDate"));
        org.json.JSONArray ents = o.optJSONArray("entities");
        if (ents != null) for (int i = 0; i < ents.length(); i++) {
            JSONObject e2 = ents.getJSONObject(i);
            org.json.JSONArray roles = e2.optJSONArray("roles");
            if (roles == null) continue;
            for (int k = 0; k < roles.length(); k++) {
                if ("registrar".equals(roles.getString(k))) {
                    log("Registrar: " + e2.optString("handle", "?"));
                    k = roles.length();
                }
            }
        }
        com.netscanner.AppLog.log("whois done: " + q);
    }

    // ───────────────── IP camera finder ─────────────────
    private void runCameras() throws Exception {
        String prefix = in().trim();
        if (prefix.isEmpty()) {
            com.netscanner.net.NetworkUtils.LocalNet n = com.netscanner.net.NetworkUtils.localNet();
            prefix = n != null ? n.prefix : "";
        }
        if (prefix.isEmpty()) { log("No network"); return; }
        log("Sweeping " + prefix + "1–254 for RTSP cameras…");
        java.util.List<String> found =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(254);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(64);
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            pool.execute(() -> {
                try {
                    for (int port : new int[]{554, 8554}) {
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(ip, port), 600);
                            s.setSoTimeout(1500);
                            OutputStream os = s.getOutputStream();
                            os.write(("OPTIONS rtsp://" + ip + ":" + port
                                    + "/ RTSP/1.0\r\nCSeq: 1\r\n\r\n").getBytes());
                            os.flush();
                            byte[] b = new byte[128];
                            int rn = s.getInputStream().read(b);
                            if (rn > 0 && new String(b, 0, rn).startsWith("RTSP/")) {
                                found.add(ip + ":" + port);
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                } finally { latch.countDown(); }
            });
        }
        latch.await(70, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdownNow();
        if (found.isEmpty()) {
            log("No RTSP cameras found on this network.");
        } else {
            log("🎥 " + found.size() + " camera(s) found:");
            for (String f : found) {
                String host = f.split(":")[0];
                String title = com.netscanner.net.NetworkUtils.httpTitle(host);
                log("  " + f + (title != null ? "  — " + title : ""));
            }
            log("\nView streams with a VLC-style app:\n  rtsp://user:pass@IP:554/stream");
        }
        com.netscanner.AppLog.log("camera scan: " + found.size() + " found");
    }

    // ───────────────── DNS hijack detector ─────────────────
    private void runDnsHijack() throws Exception {
        log("Testing resolvers for DNS hijacking…\n");
        String rnd = "nx" + (System.currentTimeMillis() % 1000000) + "-nonexistent.example.com";
        String[][] resolvers = {{"System", null}, {"Google", "8.8.8.8"},
                {"Cloudflare", "1.1.1.1"}, {"Quad9", "9.9.9.9"}};
        boolean hijack = false;
        for (String[] r : resolvers) {
            int ans = r[1] == null
                    ? systemResolves(rnd) ? 1 : 0
                    : dnsAnswerCount(r[1], rnd);
            if (ans > 0) hijack = true;
            log("  " + String.format("%-11s", r[0]) + " fake-domain answers: "
                    + ans + (ans > 0 ? "   ⚠️ HIJACK?" : "   ok"));
        }
        log("");
        String first = null;
        boolean mismatch = false;
        for (String[] r : resolvers) {
            String ips = r[1] == null ? systemIps("google.com") : dnsAnswerIps(r[1], "google.com");
            if (ips.isEmpty()) continue;
            if (first == null) first = ips;
            else if (!ips.equals(first)) {
                mismatch = true;
                log("  ⚠️ " + r[0] + " returns different IPs for google.com!");
            }
        }
        log("");
        if (hijack) log("❌ POSSIBLE DNS HIJACKING — a resolver answered for a\n   nonexistent domain. Check your router's DNS settings.");
        else if (mismatch) log("⚠️ Resolver inconsistency detected (could be CDN geo).");
        else log("✅ Clean — all resolvers agree, no NXDOMAIN hijacks.");
        com.netscanner.AppLog.log("dnshijack: " + (hijack ? "SUSPECTED" : "clean"));
    }

    private boolean systemResolves(String name) {
        try { InetAddress.getByName(name); return true; }
        catch (Exception e) { return false; }
    }

    private String systemIps(String name) {
        try {
            StringBuilder sb = new StringBuilder();
            for (InetAddress a : InetAddress.getAllByName(name))
                sb.append(a.getHostAddress()).append(",");
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private int dnsAnswerCount(String server, String name) {
        try {
            byte[] q = dnsQuery(name, 1);
            DatagramSocket s = new DatagramSocket();
            s.setSoTimeout(2000);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(server), 53));
            byte[] buf = new byte[512];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            s.close();
            return ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
        } catch (Exception e) { return -1; }
    }

    private String dnsAnswerIps(String server, String name) {
        try {
            byte[] q = dnsQuery(name, 1);
            DatagramSocket s = new DatagramSocket();
            s.setSoTimeout(2000);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(server), 53));
            byte[] buf = new byte[1024];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            s.close();
            byte[] r = java.util.Arrays.copyOf(p.getData(), p.getLength());
            int answers = ((r[6] & 0xFF) << 8) | (r[7] & 0xFF);
            if (answers == 0) return "";
            StringBuilder sb = new StringBuilder();
            int i = 12;
            while ((r[i] & 0xFF) != 0) i += (r[i] & 0xFF) + 1;
            i += 5;
            for (int a = 0; a < answers; a++) {
                if ((r[i] & 0xC0) == 0xC0) i += 2;
                else { while ((r[i] & 0xFF) != 0) i += (r[i] & 0xFF) + 1; i++; }
                int rtype = ((r[i] & 0xFF) << 8) | (r[i + 1] & 0xFF);
                int rdlen = ((r[i + 8] & 0xFF) << 8) | (r[i + 9] & 0xFF);
                int d0 = i + 10;
                if (rtype == 1)
                    sb.append((r[d0]&0xFF)).append(".").append((r[d0+1]&0xFF)).append(".")
                      .append((r[d0+2]&0xFF)).append(".").append((r[d0+3]&0xFF)).append(",");
                i = d0 + rdlen;
            }
            return sb.toString();
        } catch (Exception e) { return "?"; }
    }

    // ───────────────── HTTP request forge ─────────────────
    private void runForge() throws Exception {
        String t = in();
        if (t.isEmpty()) {
            log("Format:  METHOD url | Header: value | body\nExample:\n  POST http://192.168.1.1/api | Content-Type: application/json | {\"cmd\":\"reboot\"}");
            return;
        }
        String[] parts = t.split("\\|");
        String[] mu = parts[0].trim().split("\\s+", 2);
        String method = mu[0].toUpperCase();
        String url = mu.length > 1 ? mu[1].trim() : mu[0];
        if (!url.startsWith("http")) url = "http://" + url;
        log(method + " " + url + "\n");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        String body = null;
        for (int i = 1; i < parts.length; i++) {
            String p2 = parts[i].trim();
            if (!p2.isEmpty() && p2.contains(":") && !p2.startsWith("{") && !p2.startsWith("<") && body == null
                    && Character.isLetter(p2.charAt(0))) {
                int ci = p2.indexOf(':');
                c.setRequestProperty(p2.substring(0, ci).trim(), p2.substring(ci + 1).trim());
            } else if (!p2.isEmpty()) {
                body = p2;
            }
        }
        if (body != null) {
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes());
            os.flush();
            os.close();
        }
        int code = c.getResponseCode();
        log("HTTP " + code + " " + c.getResponseMessage() + "\n");
        for (java.util.Map.Entry<String, java.util.List<String>> h
                : c.getHeaderFields().entrySet())
            if (h.getKey() != null)
                log("  " + h.getKey() + ": " + h.getValue().get(0));
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is != null) {
            ByteArrayOutputStream b2 = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while (b2.size() < 4096 && (n = is.read(buf)) > 0) b2.write(buf, 0, n);
            log("\n" + b2.toString("UTF-8").replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "."));
        }
        com.netscanner.AppLog.log("forge " + method + " " + url + " → " + code);
    }

    // ───────────────── External port check ─────────────────
    private void runExtPort() throws Exception {
        log("Finding your public IP…");
        String ip = httpGetText("https://api.ipify.org");
        log("Public IP: " + ip);
        log("Asking hackertarget to scan it (~15s)…");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://api.hackertarget.com/nmap/?q=" + ip).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(60000);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] chunk = new byte[1024];
            int n;
            while ((n = in.read(chunk)) > 0) b.write(chunk, 0, n);
            c.disconnect();
            log(b.toString("UTF-8").trim());
            com.netscanner.AppLog.log("extport done for " + ip);
        } catch (Exception e) {
            log("❌ External scan failed: " + e.getMessage()
                    + "\n(the free API is rate-limited — retry later)");
        }
    }

    // ───────────────── Speed test ─────────────────
    private void runSpeed() {
        int ping = com.netscanner.net.NetworkUtils.pingOnce("1.1.1.1");
        log(ping >= 0 ? String.format("Latency: %d ms", ping) : "Latency: n/a");

        log("Testing download (10s, 4 streams)…");
        double down = downloadTest();
        if (down > 0) {
            log(String.format("⬇️  Download: %.1f Mbps", down));
            log("Testing upload…");
            double up = uploadTest();
            if (up > 0) log(String.format("⬆️  Upload: %.1f Mbps", up));
            else log("⬆️  Upload failed — check connection and retry");
            try {
                org.json.JSONArray h = new org.json.JSONArray(getSharedPreferences("netscanner", 0)
                        .getString("speed_hist", "[]"));
                org.json.JSONObject e = new org.json.JSONObject();
                e.put("ts", System.currentTimeMillis());
                e.put("down", Math.round(down * 10) / 10.0);
                e.put("up", Math.round(up * 10) / 10.0);
                org.json.JSONArray out = new org.json.JSONArray();
                out.put(e);
                for (int i = 0; i < Math.min(h.length(), 99); i++) out.put(h.get(i));
                getSharedPreferences("netscanner", 0).edit()
                        .putString("speed_hist", out.toString()).apply();
                com.netscanner.AppLog.log("speed result saved: " + down + "/" + up + " Mbps");
            } catch (Exception ignored) {}
            log("✅ Done");
        } else {
            log("❌ Download failed on all servers."
                    + (speedErr != null ? "\nLast error: " + speedErr : "")
                    + "\nTip: if the DNS Sniffer VPN is ON, turn it OFF and retry.");
        }
    }

    private volatile String speedErr;

    private double downloadTest() {
        String[][] servers = {
                {"https://speed.cloudflare.com/__down?bytes=25000000", "Cloudflare"},
                {"https://nbg1-speed.hetzner.com/100MB.bin", "Hetzner"},
                {"https://proof.ovh.net/files/10Mb.dat", "OVH"},
                {"https://mirror.leaseweb.com/speedtest/1000mb.bin", "Leaseweb"},
        };
        for (String[] srv : servers) {
            speedErr = null;
            double mbps = dlFrom(srv[0], 10000);
            if (mbps > 0) return mbps;
            log("⚠️ " + srv[1] + " failed" + (speedErr != null ? " — " + speedErr : ""));
        }
        return 0;
    }

    private double dlFrom(String urlStr, long msDur) {
        final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
        final long t0 = System.currentTimeMillis();
        final long deadline = t0 + msDur;
        Thread[] ts = new Thread[4];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                InputStream in = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpsURLConnection) new URL(urlStr).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("Accept-Encoding", "identity");
                    c.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 16) NetScanner/2.6");
                    int code = c.getResponseCode();
                    if (code < 200 || code >= 300)
                        throw new java.io.IOException("HTTP " + code + " " + c.getResponseMessage());
                    in = c.getInputStream();
                    byte[] b = new byte[65536];
                    int n;
                    while (System.currentTimeMillis() < deadline && (n = in.read(b)) > 0)
                        bytes.addAndGet(n);
                } catch (Exception e) {
                    if (speedErr == null) speedErr = e.toString();
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    if (c != null) c.disconnect();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) try { t.join(msDur + 5000); } catch (InterruptedException ignored) {}
        double secs = (System.currentTimeMillis() - t0) / 1000.0;
        if (secs < 0.5 || bytes.get() == 0) return 0;
        return bytes.get() * 8 / 1e6 / secs;
    }

    private double uploadTest() {
        final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
        final long t0 = System.currentTimeMillis();
        final long deadline = t0 + 8000;
        Thread[] ts = new Thread[2];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                OutputStream os = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpsURLConnection) new URL("https://speed.cloudflare.com/__up").openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(15000);
                    c.setDoOutput(true);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/octet-stream");
                    os = c.getOutputStream();
                    byte[] chunk = new byte[32768];
                    long sent = 0;
                    while (System.currentTimeMillis() < deadline && sent < 12_000_000L) {
                        os.write(chunk);
                        os.flush();                       // push into socket, keep buffers small
                        sent += chunk.length;
                        bytes.addAndGet(chunk.length);
                        if ((sent & 0x3FFFF) == 0) Thread.sleep(5); // let the wire drain
                    }
                    os.flush();
                    c.getResponseCode();
                } catch (Exception e) {
                    if (speedErr == null) speedErr = e.toString();
                } finally {
                    try { if (os != null) os.close(); } catch (Exception ignored) {}
                    if (c != null) c.disconnect();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) try { t.join(15000); } catch (InterruptedException ignored) {}
        double secs = (System.currentTimeMillis() - t0) / 1000.0;
        if (secs < 4 || bytes.get() == 0) return 0;
        return bytes.get() * 8 / 1e6 / secs;
    }
}
