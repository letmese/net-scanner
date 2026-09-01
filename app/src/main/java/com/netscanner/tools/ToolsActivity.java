package com.netscanner.tools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.netscanner.HistoryActivity;
import com.netscanner.LocalPortsActivity;
import com.netscanner.R;
import com.netscanner.SpeedHistoryActivity;
import com.netscanner.UsageActivity;
import com.netscanner.WolActivity;

import java.util.ArrayList;
import java.util.List;

/** Hub — 2-column grid of glassy tool boxes. */
public class ToolsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tools_hub);
        com.netscanner.GlassWindow.apply(this);

        LinearLayout grid = findViewById(R.id.tool_grid);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        List<Object[]> items = new ArrayList<>();
        items.add(box("🩺", "Health Score", "Grade your connection",
                () -> startActivity(new Intent(this, com.netscanner.HealthActivity.class))));
        items.add(box("⚡", "Speed Test", "Download / upload",
                () -> startActivity(tool("speed"))));
        items.add(box("📈", "Ping Monitor", "Latency graph & jitter",
                () -> startActivity(new Intent(this, PingMonitorActivity.class))));
        items.add(box("📊", "Connection Monitor", "Live per-app traffic",
                () -> startActivity(new Intent(this, ConnectionsActivity.class))));
        items.add(box("📶", "Wi-Fi Analyzer", "Channels & signal",
                () -> startActivity(new Intent(this, WifiAnalyzerActivity.class))));
        items.add(box("📡", "Signal Meter", "Live RSSI strength",
                () -> startActivity(new Intent(this, com.netscanner.SignalActivity.class))));
        items.add(box("🌐", "Net Diag", "Ping · trace · SSDP",
                () -> startActivity(new Intent(this, NetDiagActivity.class))));
        items.add(box("🔌", "My Ports", "Listening ports",
                () -> startActivity(new Intent(this, LocalPortsActivity.class))));
        items.add(box("🕵️", "DNS Sniffer", "See app DNS queries (VPN)",
                () -> startActivity(new Intent(this, SnifferActivity.class))));
        items.add(box("🖥", "SSH Client", "Log into routers",
                () -> startActivity(new Intent(this, SshActivity.class))));
        items.add(box("🧪", "Raw Probe", "Custom TCP/UDP payloads",
                () -> startActivity(tool("probe"))));
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
        items.add(box("📋", "Logs", "Crash & event diagnostics",
                () -> startActivity(new Intent(this, com.netscanner.LogsActivity.class))));

        float d = getResources().getDisplayMetrics().density;
        int i = 0;
        while (i < items.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            int m = (int) (5 * d);
            rp.setMargins(m, m, m, 0);
            row.setLayoutParams(rp);
            for (int k = 0; k < 2; k++) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setBackgroundResource(R.drawable.glass_card);
                cell.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                        (int) (116 * d), 1f);
                if (k == 0) cp.rightMargin = (int) (4 * d); else cp.leftMargin = (int) (4 * d);
                cell.setLayoutParams(cp);
                cell.setPadding((int) (8 * d), (int) (12 * d), (int) (8 * d), (int) (12 * d));
                if (i < items.size()) {
                    final Object[] it = items.get(i++);
                    TextView em = new TextView(this);
                    em.setText((String) it[0]); em.setTextSize(23);
                    em.setGravity(Gravity.CENTER);
                    cell.addView(em);
                    TextView ti = new TextView(this);
                    ti.setText((String) it[1]); ti.setTextSize(13);
                    ti.setTextColor(0xFFEDEDF2);
                    ti.setTypeface(null, android.graphics.Typeface.BOLD);
                    ti.setGravity(Gravity.CENTER);
                    cell.addView(ti);
                    TextView su = new TextView(this);
                    su.setText((String) it[2]); su.setTextSize(9);
                    su.setTextColor(0xFF8A8A99);
                    su.setGravity(Gravity.CENTER);
                    su.setMaxLines(2);
                    cell.addView(su);
                    cell.setOnClickListener(v -> {
                        Object a = it[3];
                        if (a instanceof Intent) startActivity((Intent) a);
                        else ((Runnable) a).run();
                    });
                }
                row.addView(cell);
            }
            grid.addView(row);
        }
    }

    private Object[] box(String emoji, String title, String sub, Runnable action) {
        return new Object[]{emoji, title, sub, action};
    }

    private Intent tool(String id) {
        Intent i = new Intent(this, ToolRunnerActivity.class);
        i.putExtra("tool", id);
        i.putExtra("title", id);
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
