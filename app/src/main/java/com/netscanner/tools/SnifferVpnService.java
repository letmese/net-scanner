package com.netscanner.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;

import com.netscanner.R;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Minimal root-free sniffer: VPN routes only public-DNS traffic through the tun,
 * logs every DNS query (which app-level domains are looked up), forwards UDP via
 * a protected socket, and rewrites the reply back into the tun.
 */
public class SnifferVpnService extends android.net.VpnService {


    public static final ConcurrentLinkedDeque<String> LOG = new ConcurrentLinkedDeque<>();
    private volatile boolean running;
    private Thread worker;
    private volatile android.os.ParcelFileDescriptor tunPfd;
    private final java.util.concurrent.ExecutorService forwardPool =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafe();
        if (!running) {
            running = true;
            worker = new Thread(this::loop);
            worker.start();
        }
        return START_STICKY;
    }

    private void startForegroundSafe() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel("sniffer", "DNS Sniffer",
                    NotificationManager.IMPORTANCE_LOW));
        Notification n = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "sniffer")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentText("Monitoring DNS queries").build()
                : new Notification();
        if (Build.VERSION.SDK_INT >= 30)
            startForeground(4243, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(4243, n);
    }

    @Override public void onDestroy() {
        running = false;
        try { if (tunPfd != null) tunPfd.close(); } catch (Exception ignored) {}
        tunPfd = null;
        super.onDestroy();
    }

    @NonNull @Override
    public android.os.IBinder onBind(Intent i) { return super.onBind(i); }

    private static final String[][] ROUTES = {
            {"8.8.8.8", "8.8.4.4"}, {"1.1.1.1", "1.0.0.1"}, {"9.9.9.9", "149.112.112.112"},
            {"208.67.222.222", "208.67.220.220"}, {"94.140.14.14", "94.140.15.15"}
    };

    private void loop() {
        android.os.ParcelFileDescriptor pfd = establishSafely();
        if (pfd == null) { stopSelf(); return; }
        tunPfd = pfd;
        FileDescriptor fd = pfd.getFileDescriptor();
        FileInputStream fin = new FileInputStream(fd);
        FileOutputStream fout = new FileOutputStream(fd);
        ByteBuffer buf = ByteBuffer.allocate(32767);
        try {
            while (running) {
                int len;
                try { len = fin.read(buf.array()); }
                catch (Exception readErr) { Thread.sleep(50); continue; }
                if (len <= 0) { Thread.sleep(20); continue; }
                final byte[] pktCopy = new byte[len];
                System.arraycopy(buf.array(), 0, pktCopy, 0, len);
                try {
                    final FileOutputStream fOut = fout;
                    final int pktLen = len;
                    forwardPool.execute(() -> {
                        try { handle(pktCopy, pktLen, fOut); }
                        catch (Exception perPacket) { /* one bad packet must never stop the sniffer */ }
                    });
                } catch (Exception perPacket) {
                    // one bad packet must never stop the sniffer
                }
            }
        } catch (Exception ignored) {}
    }

    private android.os.ParcelFileDescriptor establishSafely() {
        try {
            android.net.VpnService.Builder b = new android.net.VpnService.Builder()
                    .setSession("NetScanner DNS Sniffer")
                    .addAddress("10.111.222.1", 32)
                    .addDnsServer("8.8.8.8");
            // route the network's own DNS servers too — most queries go there
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
                android.net.DhcpInfo d = wm.getDhcpInfo();
                if (d != null) {
                    if (d.dns1 != 0) try { b.addRoute(intToIp(d.dns1), 32); } catch (Exception ignored) {}
                    if (d.dns2 != 0) try { b.addRoute(intToIp(d.dns2), 32); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            for (String[] group : ROUTES)
                for (String ip : group) try { b.addRoute(ip, 32); } catch (Exception ignored) {}
            return b.establish();
        } catch (Exception e) {
            return null;
        }
    }

    private void handle(byte[] pkt, int len, FileOutputStream out) throws Exception {
        if (len < 34) return;
        int ihl = (pkt[0] & 0x0F) * 4;
        int proto = pkt[9] & 0xFF;
        // dst IP at offset 16..19
        String dst = (pkt[16] & 0xFF) + "." + (pkt[17] & 0xFF) + "." + (pkt[18] & 0xFF) + "." + (pkt[19] & 0xFF);

        if (proto == 17 && len > ihl + 12) { // UDP — catch DNS by port, not just known resolver IPs
            int udpStart = ihl;
            int srcPort = ((pkt[udpStart] & 0xFF) << 8) | (pkt[udpStart + 1] & 0xFF);
            int dstPort = ((pkt[udpStart + 2] & 0xFF) << 8) | (pkt[udpStart + 3] & 0xFF);
            if (dstPort != 53) return;
            int dnsStart = udpStart + 8;
            int dnsLen = len - dnsStart;
            if (dnsLen > 17) logQuery(pkt, dnsStart);

            // forward via protected socket — never let a timeout kill the sniffer
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket(null);
                protect(sock);
                sock.setSoTimeout(3000);
                byte[] dns = new byte[dnsLen];
                System.arraycopy(pkt, dnsStart, dns, 0, dnsLen);
                sock.send(new DatagramPacket(dns, dnsLen, InetAddress.getByName(dst), 53));
                byte[] rbuf = new byte[4096];
                DatagramPacket rp = new DatagramPacket(rbuf, rbuf.length);
                sock.receive(rp); // reply arrives from dst:53 → rewrite to look like original dst:53 reply

                byte[] resp = new byte[rp.getLength()];
                System.arraycopy(rbuf, 0, resp, 0, rp.getLength());

                // build response packet: swap IP src/dst; UDP: src=53 dst=srcPort
                byte[] outPkt = new byte[ihl + 8 + resp.length];
                System.arraycopy(pkt, 0, outPkt, 0, ihl + 8);
                // IP: src = old dst, dst = old src
                System.arraycopy(pkt, 12, outPkt, 16, 4); // dst <- orig src
                System.arraycopy(pkt, 16, outPkt, 12, 4); // src <- orig dst
                // UDP: src port = 53, dst port = srcPort
                outPkt[udpStart] = 0; outPkt[udpStart + 1] = 53;
                outPkt[udpStart + 2] = (byte) ((srcPort >> 8) & 0xFF);
                outPkt[udpStart + 3] = (byte) (srcPort & 0xFF);
                outPkt[udpStart + 4] = (byte) (((8 + resp.length) >> 8) & 0xFF);
                outPkt[udpStart + 5] = (byte) ((8 + resp.length) & 0xFF);
                // zero UDP checksum (allowed over IPv4 = skip verification)
                outPkt[udpStart + 6] = 0; outPkt[udpStart + 7] = 0;
                // fix IP total length + zero IP checksum
                int totalLen = ihl + 8 + resp.length;
                outPkt[2] = (byte) ((totalLen >> 8) & 0xFF);
                outPkt[3] = (byte) (totalLen & 0xFF);
                outPkt[10] = 0; outPkt[11] = 0;
                int sum = checksum(outPkt, ihl);
                outPkt[10] = (byte) ((sum >> 8) & 0xFF);
                outPkt[11] = (byte) (sum & 0xFF);
                System.arraycopy(resp, 0, outPkt, ihl + 8, resp.length);

                out.write(outPkt, 0, totalLen);
            } catch (java.net.SocketTimeoutException ste) {
                // resolver didn't answer in time — client will retry; keep sniffing
            } catch (Exception forwardError) {
                // any other forwarding hiccup: skip this packet, stay alive
            } finally {
                if (sock != null) sock.close();
            }
            return;
        }

        // Non-DNS traffic on routed IPs: forward verbatim via protected raw path is not possible
        // without a full proxy — drop silently. Only DNS routes are captured by design.
    }

    private boolean isDnsServer(String ip) {
        for (String[] g : ROUTES) for (String s : g) if (s.equals(ip)) return true;
        return false;
    }

    private static String intToIp(int i) {
        return (i & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + ((i >> 16) & 0xFF) + "." + (i & 0xFF);
    }

    private int checksum(byte[] hdr, int ihl) {
        long sum = 0;
        for (int i = 0; i < ihl; i += 2) {
            sum += ((hdr[i] & 0xFF) << 8) | (hdr[i + 1] & 0xFF);
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    private void logQuery(byte[] pkt, int dnsStart) {
        try {
            // QNAME starts at dnsStart+12
            int i = dnsStart + 12;
            StringBuilder sb = new StringBuilder();
            while (true) {
                int l = pkt[i] & 0xFF;
                if (l == 0 || l > 63) break;
                if (sb.length() > 0) sb.append(".");
                sb.append(new String(pkt, i + 1, l, java.nio.charset.StandardCharsets.UTF_8));
                i += l + 1;
            }
            if (sb.length() == 0) return;
            LOG.addFirst(java.text.SimpleDateFormat.getDateTimeInstance()
                    .format(new java.util.Date()) + "  🔎 " + sb);
            while (LOG.size() > 200) LOG.removeLast();
        } catch (Exception ignored) {}
    }
}
