package com.netscanner;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.netscanner.net.NetworkUtils;
import com.netscanner.tools.ConnectionsActivity;
import com.netscanner.tools.PingMonitorActivity;
import com.netscanner.tools.SnifferActivity;
import com.netscanner.tools.SshActivity;
import com.netscanner.tools.ToolRunnerActivity;
import com.netscanner.tools.WifiAnalyzerActivity;

import java.util.ArrayList;
import java.util.List;

/** Dashboard — Fluent-style tile grid; scan opens its own page. */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // ---------- header ----------
        TextView overline = new TextView(this);
        overline.setText("N E T S C A N N E R");
        overline.setTextSize(10);
        overline.setLetterSpacing(0.22f);
        overline.setTextColor(Ui.TEXT_FAINT);
        overline.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        overline.setPadding((int) (20 * d), (int) (64 * d), (int) (20 * d), (int) (4 * d));
        root.addView(overline);

        TextView title = new TextView(this);
        title.setText("Network Toolkit");
        title.setTextSize(28);
        title.setTextColor(Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setPadding((int) (20 * d), 0, (int) (20 * d), (int) (8 * d));
        root.addView(title);

        root.addView(statusPill());

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), 0);
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.addView(grid);
        root.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // ---------- tiles ----------
        List<Object[]> items = new ArrayList<>();
        items.add(box("🔍", "Scan Network", "Find devices on your LAN",
                () -> startActivity(new Intent(this, ScanActivity.class))));
        items.add(box("🩺", "Health Score", "Grade your connection",
                () -> startActivity(new Intent(this, HealthActivity.class))));
        items.add(box("⚡", "Speed Test", "Download / upload",
                () -> startActivity(tool("speed"))));
        items.add(box("📈", "Ping Monitor", "Latency graph & jitter",
                () -> startActivity(new Intent(this, PingMonitorActivity.class))));
        items.add(box("🎯", "Multi-Ping", "Track several hosts live",
                () -> startActivity(new Intent(this, MultiPingActivity.class))));
        items.add(box("\u23F1", "Auto Speed", "Interval tests + full log",
                () -> startActivity(new Intent(this, AutoSpeedActivity.class))));
        items.add(box("📊", "Connection Monitor", "Live per-app traffic",
                () -> startActivity(new Intent(this, ConnectionsActivity.class))));
        items.add(box("📶", "Wi-Fi Analyzer", "Channels & signal",
                () -> startActivity(new Intent(this, WifiAnalyzerActivity.class))));
        items.add(box("📡", "Signal Meter", "Live RSSI strength",
                () -> startActivity(new Intent(this, SignalActivity.class))));
        items.add(box("🗼", "Cell Monitor", "Towers · RSRP · neighbors",
                () -> startActivity(new Intent(this, CellMonitorActivity.class))));
        items.add(box("🧭", "DNS Tester", "Rank public DNS · set Private DNS",
                () -> startActivity(new Intent(this, DnsTesterActivity.class))));
        items.add(box("🌐", "Net Diag", "Ping · trace · SSDP",
                () -> startActivity(new Intent(this, com.netscanner.tools.NetDiagActivity.class))));
        items.add(box("🔌", "My Ports", "Listening ports",
                () -> startActivity(new Intent(this, LocalPortsActivity.class))));
        items.add(box("🕵️", "DNS Sniffer", "See app DNS queries",
                () -> startActivity(new Intent(this, SnifferActivity.class))));
        items.add(box("🖥", "SSH Client", "Log into routers",
                () -> startActivity(new Intent(this, SshActivity.class))));
        items.add(box("🧪", "Raw Probe", "Custom TCP/UDP payloads",
                () -> startActivity(tool("probe"))));
        items.add(box("🎥", "Camera Finder", "Find RTSP cams on LAN",
                () -> startActivity(tool("cameras"))));
        items.add(box("🛡", "DNS Hijack Test", "Catch DNS tampering",
                () -> startActivity(tool("dnshijack"))));
        items.add(box("⚒", "HTTP Forge", "Craft raw requests",
                () -> startActivity(tool("httpforge"))));
        items.add(box("🔎", "Whois / Intel", "RDAP ownership data",
                () -> startActivity(tool("whois"))));
        items.add(box("🌍", "External Ports", "Internet-side scan",
                () -> startActivity(tool("extport"))));
        items.add(box("📡", "mDNS Discovery", "Cast, AirPlay, printers",
                () -> startActivity(tool("mdns"))));
        items.add(box("🏷", "SNMP Probe", "Device name & model",
                () -> startActivity(toolIntent("snmp", "SNMP Probe", "Device IP (v1 public)"))));
        items.add(box("🔒", "TLS Inspector", "Certificate details",
                () -> startActivity(toolIntent("cert", "TLS Cert Inspector", "host:port e.g. 192.168.1.1:443"))));
        items.add(box("🛡", "HTTP Audit", "Security headers grade",
                () -> startActivity(toolIntent("secaudit", "HTTP Security Audit", "http://device:port"))));
        items.add(box("🌐", "DNS Toolkit", "Lookups + resolver speed",
                () -> startActivity(toolIntent("dns", "DNS Toolkit", "domain e.g. google.com"))));
        items.add(box("🌍", "Public IP", "IP, ISP, gateway info",
                () -> startActivity(tool("netinfo"))));
        items.add(box("🧮", "Subnet Calc", "CIDR → ranges",
                () -> startActivity(toolIntent("subnet", "Subnet Calculator", "e.g. 192.168.1.0/24"))));
        items.add(box("📈", "Speed History", "Past results + chart",
                () -> startActivity(new Intent(this, SpeedHistoryActivity.class))));
        items.add(box("📊", "Data Usage", "Per-app daily usage",
                () -> startActivity(new Intent(this, UsageActivity.class))));
        items.add(box("🐺", "Wake-on-LAN", "Saved wake profiles",
                () -> startActivity(new Intent(this, WolActivity.class))));
        items.add(box("🕘", "Scan History", "Past scans",
                () -> startActivity(new Intent(this, HistoryActivity.class))));
        items.add(box("📋", "Logs", "Crash & event diagnostics",
                () -> startActivity(new Intent(this, LogsActivity.class))));

        int[] tints = tintsFor(items.size());

        int i = 0;
        while (i < items.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = (int) (10 * d);
            row.setLayoutParams(rp);
            for (int k = 0; k < 2; k++) {
                if (i >= items.size() && k > 0) break;
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setBackgroundResource(R.drawable.glass_card);
                // floating frosted-glass panel: soft elevation shadow + rounded clip
                cell.setElevation((int) (6 * d));
                cell.setClipToOutline(true);
                cell.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(android.view.View view, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 20 * d);
                    }
                });
                int pad = (int) (14 * d);
                cell.setPadding(pad, pad, pad, pad);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                        (int) (138 * d), 1f);
                if (k == 0) cp.rightMargin = (int) (5 * d); else cp.leftMargin = (int) (5 * d);
                cell.setLayoutParams(cp);

                final Object[] it = items.get(i++);
                int tint = tints[Math.min(i - 1, tints.length - 1)];

                TextView em = Ui.chip(this, (String) it[0], tint, 42, 19f);
                cell.addView(em, new LinearLayout.LayoutParams(
                        (int) (42 * d), (int) (42 * d)));

                TextView ti = new TextView(this);
                ti.setText((String) it[1]);
                ti.setTextSize(13);
                ti.setTextColor(Ui.TEXT);
                ti.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                ti.setMaxLines(1);
                ti.setEllipsize(android.text.TextUtils.TruncateAt.END);
                ti.setPadding(0, (int) (12 * d), 0, 0);
                cell.addView(ti, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                TextView su = new TextView(this);
                su.setText((String) it[2]);
                su.setTextSize(10);
                su.setTextColor(Ui.TEXT_DIM);
                su.setMaxLines(2);
                su.setLineSpacing((int) (1 * d), 1f);
                su.setPadding(0, (int) (3 * d), 0, 0);
                cell.addView(su, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                cell.setOnClickListener(v -> {
                    Object a = it[3];
                    if (a instanceof Intent) startActivity((Intent) a);
                    else ((Runnable) a).run();
                });
                row.addView(cell);
            }
            grid.addView(row);
        }

        TextView foot = new TextView(this);
        foot.setText("NetScanner v4.1 · Liquid Glass");
        foot.setTextSize(10);
        foot.setTextColor(Ui.TEXT_FAINT);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, (int) (14 * d), 0, (int) (28 * d));
        grid.addView(foot);
    }

    /** Live network status pill under the title. */
    private LinearLayout statusPill() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackgroundResource(R.drawable.pill_bg);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.setMargins((int) (20 * d), 0, (int) (20 * d), 0);
        pill.setLayoutParams(pp);
        pill.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));

        TextView dot = new TextView(this);
        dot.setTextSize(9);
        String label;
        int dotColor;
        try {
            // real connectivity first — SSID alone lies when location is off
            boolean wifi = false, cell = false;
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.Network nw = cm.getActiveNetwork();
                android.net.NetworkCapabilities caps =
                        nw == null ? null : cm.getNetworkCapabilities(nw);
                if (caps != null && caps.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    wifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
                    cell = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
                }
            }
            NetworkUtils.LocalNet n = NetworkUtils.localNet();

            String ssid = "";
            if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    WifiInfo wi = wm.getConnectionInfo();
                    if (wi != null && wi.getSSID() != null)
                        ssid = wi.getSSID().replace("\"", "").trim();
                }
                if (ssid.isEmpty() || ssid.contains("unknown") || ssid.contains("<"))
                    ssid = "Wi-Fi";
            }

            if (n != null && n.ip != null) {
                dotColor = wifi ? Ui.GOOD : (cell ? Ui.GOOD : Ui.WARN);
                label = wifi ? ssid : (cell ? "Mobile data" : "Connected");
                label += "  \u00B7  " + n.ip;
            } else if (cell) {
                dotColor = Ui.GOOD;
                label = "Mobile data";
            } else if (wifi) {
                dotColor = Ui.WARN;
                label = ssid + "  ·  no address";
            } else {
                dotColor = Ui.BAD;
                label = "Offline";
            }
        } catch (Throwable t) {
            dotColor = Ui.BAD;
            label = "Offline";
        }
        dot.setTextColor(dotColor);
        dot.setText("\u25CF");
        pill.addView(dot);

        TextView txt = new TextView(this);
        txt.setText(label);
        txt.setTextSize(11);
        txt.setTextColor(0xFFB9C4D6);
        txt.setPadding((int) (6 * d), 0, 0, 0);
        pill.addView(txt);
        return pill;
    }

    /** Hand-tuned category tints, one per dashboard tile. */
    private int[] tintsFor(int n) {
        return new int[]{
                0, 3, 5, 1, 2, 6, 7, 1, 3, 7,
                1, 2, 0, 9, 8, 5, 9, 9, 8, 1,
                9, 0, 2, 9, 9, 1, 0, 4, 3, 7,
                8, 1, 10
        };
    }

    private void toggleNightTests() {
        boolean on = !getSharedPreferences("netscanner", 0).getBoolean("night_tests", false);
        getSharedPreferences("netscanner", 0).edit().putBoolean("night_tests", on).apply();
        androidx.work.WorkManager wm = androidx.work.WorkManager.getInstance(this);
        if (on) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.add(java.util.Calendar.DAY_OF_YEAR, 1);
            c.set(java.util.Calendar.HOUR_OF_DAY, 2);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            androidx.work.PeriodicWorkRequest req =
                    new androidx.work.PeriodicWorkRequest.Builder(
                            com.netscanner.tools.NightSpeedWorker.class, 24,
                            java.util.concurrent.TimeUnit.HOURS)
                            .setInitialDelay(
                                    c.getTimeInMillis() - System.currentTimeMillis(),
                                    java.util.concurrent.TimeUnit.MILLISECONDS)
                            .build();
            wm.enqueueUniquePeriodicWork("night_speed",
                    androidx.work.ExistingPeriodicWorkPolicy.UPDATE, req);
            Toast.makeText(this, "🌙 Night tests ON — next run 2 AM", Toast.LENGTH_LONG).show();
        } else {
            wm.cancelUniqueWork("night_speed");
            Toast.makeText(this, "Night tests OFF", Toast.LENGTH_SHORT).show();
        }
    }

    private Object[] box(String emoji, String title, String sub, Runnable action) {
        return new Object[]{emoji, title, sub, action};
    }

    private Intent tool(String id) {
        Intent i = new Intent(this, ToolRunnerActivity.class);
        i.putExtra("tool", id);
        return i;
    }

    private Intent toolIntent(String id, String title, String hint) {
        Intent i = new Intent(this, ToolRunnerActivity.class);
        i.putExtra("tool", id);
        i.putExtra("title", title);
        i.putExtra("hint", hint);
        return i;
    }
}
