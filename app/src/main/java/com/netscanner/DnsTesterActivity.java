package com.netscanner;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v3.10 DNS Tester — tests latency of major public DNS resolvers with raw
 * UDP queries (no library), ranks them, and can apply the winner system-wide
 * via Android Private DNS (needs WRITE_SECURE_SETTINGS, grantable over adb).
 */
public class DnsTesterActivity extends AppCompatActivity {

    /** name, IPv4, second IP, DoT hostname ("" = cannot be set as Private DNS). */
    private static final String[][] SERVERS = {
            {"Cloudflare", "1.1.1.1", "1.0.0.1", "one.one.one.one"},
            {"Cloudflare Malware", "1.1.1.2", "1.0.0.2", "security.cloudflare-dns.com"},
            {"Google Public", "8.8.8.8", "8.8.4.4", "dns.google"},
            {"Quad9", "9.9.9.9", "149.112.112.112", "dns.quad9.net"},
            {"Quad9 Unfiltered", "9.9.9.10", "149.112.112.10", "dns10.quad9.net"},
            {"AdGuard", "94.140.14.14", "94.140.15.15", "dns.adguard.com"},
            {"AdGuard Family", "94.140.14.15", "94.140.15.16", "family.adguard-dns.com"},
            {"OpenDNS", "208.67.222.222", "208.67.220.220", ""},
            {"NextDNS", "45.90.28.0", "45.90.30.0", ""},
            {"CleanBrowsing Sec", "185.228.168.9", "185.228.169.9",
                    "security-filtered.dns.cleanbrowsing.org"},
            {"CleanBrowsing Fam", "185.228.168.10", "185.228.169.11",
                    "family-filter.dns.cleanbrowsing.org"},
            {"Comodo Secure", "8.26.56.26", "8.20.247.20", ""},
            {"DNS.WATCH", "84.200.69.80", "84.200.70.40", ""},
            {"Yandex", "77.88.8.8", "77.88.8.1", "common.dot.yandex.net"},
            {"CZ.NIC ODVR", "193.17.47.1", "185.43.135.1", "odvr.nic.cz"},
            {"Verisign", "64.6.64.6", "64.6.65.6", "dot.verisign.com"},
            {"Hurricane Electric", "74.82.42.42", "", "ordns.he.net"},
            {"Level3", "209.244.0.3", "209.244.0.4", ""},
            {"ControlD Free", "76.76.2.0", "76.76.10.0", "p0.freedns.controld.com"},
            {"dns0.eu", "193.110.81.0", "185.253.5.0", "dns0.eu"},
            {"DNSForge", "176.9.93.198", "176.9.186.165", "dnsforge.de"},
            {"CIRA Shield", "149.112.121.10", "149.112.122.10",
                    "protected.canadianshield.cira.ca"},
    };

    private static final int PROBES = 4;          // attempts per server
    private static final int TIMEOUT_MS = 2000;   // per attempt

    private TextView curLine, statusLine;
    private Button testBtn, grantBtn;
    private LinearLayout listBox;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger finished = new AtomicInteger();

    private final long[] best = new long[SERVERS.length];   // -1 = failed/not tested
    private final boolean[] tested = new boolean[SERVERS.length];
    private volatile boolean testing = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.root(this);
        Ui.header(this, "\uD83E\uDDED DNS Tester", root);

        // ---- current Private DNS ----
        LinearLayout cCard = card(root);
        curLine = txt(13, 0xFFEDEDF2, false);
        cCard.addView(curLine);

        // ---- actions ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        testBtn = btn("\u25B6 Test all servers");
        testBtn.setOnClickListener(v -> testAll());
        row.addView(testBtn, w());
        grantBtn = btn("\u270E ADB grant");
        grantBtn.setOnClickListener(v -> adbHelp());
        row.addView(grantBtn, w());
        root.addView(row);

        statusLine = txt(12, 0xFF8A8A99, false);
        statusLine.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), 0);
        root.addView(statusLine);

        // ---- server list ----
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(this);
        sc.addView(listBox);
        root.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = txt(10, 0xFF55556A, false);
        foot.setText("Latency = best of " + PROBES + " real UDP queries (\u00B7 port 53). "
                + "\"Set\" applies the server as system-wide Private DNS "
                + "(encrypted DoT \u2014 requires one-time ADB permission grant).");
        foot.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 12));
        root.addView(foot);

        renderInitial();
        setContentView(root);
        GlassWindow.apply(this);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshCurrent();
        refreshButtons();
    }

    // ---------- UI helpers ----------

    private void renderInitial() {
        listBox.removeAllViews();
        for (int i = 0; i < SERVERS.length; i++) listBox.addView(rowView(i, i));
    }

    /** One server row: rank/name/ips left, latency+Set right. */
    private View rowView(int rankPos, final int i) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        r.setBackgroundColor(((i & 1) == 0) ? 0x00141420 : 0x14142000);

        TextView rank = txt(13, 0xFF77778C, true);
        rank.setText(rankLabel(rankPos, i));
        rank.setMinWidth(Ui.dp(this, 34));
        r.addView(rank);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = txt(14, 0xFFEDEDF2, true);
        String dots = SERVERS[i][3].isEmpty()
                ? "" : "  \uD83D\uDD12";
        name.setText(SERVERS[i][0] + dots + (isActive(i) ? "  \u25CF" : ""));
        name.setTextColor(isActive(i) ? 0xFF6EE7A0 : 0xFFEDEDF2);
        mid.addView(name);
        TextView ips = txt(11, 0xFF8A8A99, false);
        ips.setText(SERVERS[i][1] + (SERVERS[i][2].isEmpty() ? "" : " / " + SERVERS[i][2])
                + (SERVERS[i][3].isEmpty() ? "" : "\n\u2192 " + SERVERS[i][3]));
        mid.addView(ips);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        r.addView(mid, mp);

        TextView lat = txt(14, 0xFFB9B9C9, true);
        lat.setGravity(Gravity.END);
        lat.setText(latText(i));
        lat.setTag("lat" + i);
        r.addView(lat, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button set = btn("Set");
        set.setTextSize(11);
        set.setOnClickListener(v -> trySet(i));
        set.setEnabled(!SERVERS[i][3].isEmpty());
        set.setAlpha(SERVERS[i][3].isEmpty() ? 0.35f : 1f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        r.addView(set, sp);
        return r;
    }

    private String rankLabel(int rankPos, int i) {
        if (!tested[i]) return "";
        switch (rankPos) {
            case 0: return "\uD83E\uDD47";
            case 1: return "\uD83E\uDD48";
            case 2: return "\uD83E\uDD49";
            default: return String.valueOf(rankPos + 1);
        }
    }

    private String latText(int i) {
        if (!tested[i]) return testing ? "\u2026" : "\u2014";
        if (best[i] < 0) return "fail";
        return best[i] + " ms";
    }

    private void updateLatView(final int i) {
        ui.post(() -> {
            View v = listBox.findViewWithTag("lat" + i);
            if (!(v instanceof TextView)) return;
            TextView tv = (TextView) v;
            tv.setText(latText(i));
            tv.setTextColor(best[i] < 0 ? 0xFFF87171
                    : best[i] < 40 ? 0xFF6EE7A0
                    : best[i] < 100 ? 0xFFC7E76E
                    : best[i] < 250 ? 0xFFFFD166 : 0xFFFF8A5C);
        });
    }

    // ---------- testing ----------

    private void testAll() {
        if (testing) return;
        testing = true;
        finished.set(0);
        testBtn.setEnabled(false);
        testBtn.setText("Testing\u2026");
        for (int i = 0; i < SERVERS.length; i++) {
            best[i] = -1;
            tested[i] = false;
            updateLatView(i);
        }
        statusLine.setText("Probing " + SERVERS.length
                + " servers \u00D7 " + PROBES + " queries each\u2026");

        final ExecutorService pool = Executors.newFixedThreadPool(16);
        final CountDownLatch latch = new CountDownLatch(SERVERS.length);
        for (int i = 0; i < SERVERS.length; i++) {
            final int idx = i;
            pool.submit(() -> {
                long min = Long.MAX_VALUE;
                for (int p = 0; p < PROBES; p++) {
                    long t = singleProbe(SERVERS[idx][1]);
                    if (t >= 0 && t < min) min = t;
                }
                best[idx] = min == Long.MAX_VALUE ? -1 : min;
                tested[idx] = true;
                updateLatView(idx);
                int done = finished.incrementAndGet();
                ui.post(() -> statusLine.setText(done + " / " + SERVERS.length
                        + " servers probed\u2026"));
                latch.countDown();
            });
        }
        new Thread(() -> {
            try { latch.await(); } catch (InterruptedException ignored) {}
            pool.shutdown();
            ui.post(() -> {
                testing = false;
                testBtn.setEnabled(true);
                testBtn.setText("\u25B6 Test again");
                renderRanked();
                StringBuilder sb = new StringBuilder("Fastest: ");
                int w = winner();
                sb.append(w < 0 ? "none reachable" :
                        SERVERS[w][0] + " (" + best[w] + " ms)");
                statusLine.setText(sb.toString());
            });
        }).start();
    }

    private void renderRanked() {
        Integer[] order = new Integer[SERVERS.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, bi) -> {
            long x = tested[a] ? (best[a] < 0 ? Long.MAX_VALUE : best[a]) : Long.MAX_VALUE - 1;
            long y = tested[bi] ? (best[bi] < 0 ? Long.MAX_VALUE : best[bi]) : Long.MAX_VALUE - 1;
            return Long.compare(x, y);
        });
        listBox.removeAllViews();
        int pos = 0;
        for (int i : order) listBox.addView(rowView(tested[i] ? pos++ : -1, i));
    }

    private int winner() {
        int w = -1;
        for (int i = 0; i < SERVERS.length; i++)
            if (tested[i] && best[i] >= 0 && (w < 0 || best[i] < best[w])) w = i;
        return w;
    }

    /** Raw DNS A-query over UDP; returns RTT ms or -1. */
    static long singleProbe(String serverIp) {
        final int id = (int) (System.nanoTime() & 0xFFFF);
        byte[] q = queryBuf(id);
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(TIMEOUT_MS);
            sock.connect(new InetSocketAddress(
                    InetAddress.getByName(serverIp), 53));
            long t0 = System.nanoTime();
            sock.send(new DatagramPacket(q, q.length));
            byte[] rb = new byte[512];
            DatagramPacket rp = new DatagramPacket(rb, rb.length);
            sock.receive(rp);
            long dt = (System.nanoTime() - t0) / 1_000_000L;
            byte[] resp = rp.getData();
            int len = rp.getLength();
            if (len < 12) return -1;
            if (((resp[0] & 0xFF) != ((id >> 8) & 0xFF))
                    || ((resp[1] & 0xFF) != (id & 0xFF))) return -1;   // id mismatch
            if ((resp[2] & 0x80) == 0) return -1;                      // not a response
            if ((resp[3] & 0x0F) != 0) return -1;                      // RCODE != NOERROR
            return dt;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Minimal DNS query packet for an A record. */
    private static byte[] queryBuf(int id) {
        String[] labels = PROBE_HOST.split("\\.");
        int len = 12 + 1 + PROBE_HOST.length() + labels.length + 4;
        byte[] out = new byte[len];
        out[0] = (byte) ((id >> 8) & 0xFF);
        out[1] = (byte) (id & 0xFF);
        out[2] = 0x01;      // RD
        out[3] = 0x00;
        out[4] = 0; out[5] = 1;     // QDCOUNT 1
        int p = 12;
        for (String l : labels) {
            out[p++] = (byte) l.length();
            for (int k = 0; k < l.length(); k++) out[p++] = (byte) l.charAt(k);
        }
        out[p++] = 0;
        out[p++] = 0; out[p++] = 1;         // QTYPE A
        out[p++] = 0; out[p] = 1;           // QCLASS IN
        return out;
    }

    private static final String PROBE_HOST = "www.gstatic.com";

    // ---------- Private DNS ----------

    private boolean canWriteSecure() {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private String curMode() {
        if (Build.VERSION.SDK_INT < 28) return null;
        try {
            return Settings.Global.getString(getContentResolver(), "private_dns_mode");
        } catch (Throwable t) { return null; }
    }

    private String curSpec() {
        if (Build.VERSION.SDK_INT < 28) return null;
        try {
            return Settings.Global.getString(getContentResolver(), "private_dns_specifier");
        } catch (Throwable t) { return null; }
    }

    private void refreshCurrent() {
        String mode = curMode(), spec = curSpec();
        String s;
        if (Build.VERSION.SDK_INT < 28)
            s = "Private DNS needs Android 9+.";
        else if ("hostname".equals(mode))
            s = "Private DNS ON \u2192 " + spec;
        else if ("opportunistic".equals(mode))
            s = "Private DNS: automatic (opportunistic)";
        else
            s = "Private DNS: OFF";
        s += "\nTap \u270E ADB grant if \"Set\" says permission is missing.";
        curLine.setText(s);
    }

    private boolean isActive(int i) {
        String spec = curSpec();
        if (spec == null || spec.isEmpty()) return false;
        String d = SERVERS[i][3];
        return (!d.isEmpty() && spec.equalsIgnoreCase(d))
                || spec.equals(SERVERS[i][1]) || spec.equals(SERVERS[i][2]);
    }

    private void refreshButtons() {
        grantBtn.setText(canWriteSecure() ? "\u2714 Permission OK" : "\u270E ADB grant");
    }

    private void trySet(int i) {
        String host = SERVERS[i][3];
        if (host.isEmpty()) {
            Toast.makeText(this, SERVERS[i][0]
                    + " has no public encrypted (DoT) endpoint \u2014 "
                    + "Android Private DNS only supports DoT.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!canWriteSecure()) { adbHelp(); return; }
        try {
            Settings.Global.putString(getContentResolver(),
                    "private_dns_specifier", host);
            Settings.Global.putString(getContentResolver(),
                    "private_dns_mode", "hostname");
            Toast.makeText(this, "Private DNS set to " + host
                    + " \u2014 whole phone now uses " + SERVERS[i][0],
                    Toast.LENGTH_LONG).show();
            renderRanked();
            refreshCurrent();
        } catch (Exception ex) {
            Toast.makeText(this, "Failed: " + ex, Toast.LENGTH_LONG).show();
            adbHelp();
        }
    }

    private void adbHelp() {
        if (canWriteSecure()) {
            Toast.makeText(this, "Permission already granted \u2714",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final String cmd =
                "adb shell pm grant com.netscanner android.permission.WRITE_SECURE_SETTINGS";
        new AlertDialog.Builder(this)
                .setTitle("One-time permission")
                .setMessage(
                        "Changing the whole phone's DNS needs the "
                        + "WRITE_SECURE_SETTINGS permission, which Android only "
                        + "grants through ADB (one time, survives reboot):\n\n"
                        + "1. Enable USB debugging (Developer options)\n"
                        + "2. Connect to a PC with adb, run:\n\n"
                        + cmd + "\n\n"
                        + "Or use a local SHIZUKU/ADB terminal app on the phone.\n\n"
                        + "Alternative without any permission: system Settings "
                        + "\u2192 Network \u2192 Private DNS \u2192 enter the "
                        + "hostname manually (shown under each server).")
                .setPositiveButton("Copy command", (d, w) -> {
                    try {
                        ClipboardManager cm = (ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("adb", cmd));
                        Toast.makeText(this, "Command copied", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------- shared helpers (same style as other screens) ----------

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.glass_card);
        c.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), 0);
        parent.addView(c, lp);
        return c;
    }

    private TextView txt(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private Button btn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(0xFFEDEDF2);
        b.setBackgroundResource(R.drawable.btn_secondary);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams w() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        return lp;
    }
}
