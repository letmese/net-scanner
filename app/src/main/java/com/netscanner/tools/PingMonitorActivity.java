package com.netscanner.tools;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.netscanner.R;
import com.netscanner.net.NetworkUtils;

import java.util.concurrent.atomic.AtomicBoolean;

public class PingMonitorActivity extends AppCompatActivity {

    private LatencyView chart;
    private TextView stats;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ping_monitor);
        com.netscanner.GlassWindow.apply(this);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        chart = findViewById(R.id.chart);
        stats = findViewById(R.id.tv_stats);
        EditText host = findViewById(R.id.et_host);

        com.netscanner.net.NetworkUtils.LocalNet n = NetworkUtils.localNet();
        String gw = null;
        try {
            WifiManagerProxy wm = new WifiManagerProxy(this);
            gw = wm.gateway();
        } catch (Exception ignored) {}
        host.setText(gw != null ? gw : "1.1.1.1");

        findViewById(R.id.btn_toggle).setOnClickListener(v -> {
            if (running.get()) {
                running.set(false);
                ((TextView) findViewById(R.id.btn_toggle)).setText("▶ Start");
            } else {
                running.set(true);
                ((TextView) findViewById(R.id.btn_toggle)).setText("⏸ Stop");
                startMonitor(host.getText().toString().trim());
            }
        });
    }

    /** tiny helper to read gateway without leaking wifi deps everywhere */
    private static class WifiManagerProxy {
        private final android.net.wifi.WifiManager wm;
        WifiManagerProxy(android.content.Context c) {
            wm = (android.net.wifi.WifiManager) c.getApplicationContext()
                    .getSystemService(android.content.Context.WIFI_SERVICE);
        }
        String gateway() {
            android.net.DhcpInfo d = wm.getDhcpInfo();
            return d == null ? null : ((d.gateway & 0xFF) + "." + ((d.gateway >> 8) & 0xFF)
                    + "." + ((d.gateway >> 16) & 0xFF) + "." + ((d.gateway >> 24) & 0xFF));
        }
    }

    private void startMonitor(String target) {
        if (target.isEmpty()) return;
        new Thread(() -> {
            long sum = 0; int ok = 0, sent = 0; Long prev = null; double jitterSum = 0; int jitterN = 0;
        while (running.get()) {
                sent++;
                int ms = NetworkUtils.pingOnce(target); // real ICMP latency (or -1 = lost)
                boolean up = ms >= 0;
                Integer sample = up ? ms : null;
                chart.add(sample);
                if (up) {
                    ok++; sum += ms;
                    if (prev != null) { jitterSum += Math.abs(ms - prev); jitterN++; }
                    prev = (long) ms;
                } else prev = null;
                final double fAvg = ok > 0 ? sum / (double) ok : 0;
                final double fJit = jitterN > 0 ? jitterSum / jitterN : 0;
                final int fSent = sent, fOk = ok;
                runOnUiThread(() -> stats.setText(String.format(
                        "sent %d   ✓ %d   ✗ %d   avg %.0f ms   jitter %.0f ms   loss %.0f%%",
                        fSent, fOk, fSent - fOk, fAvg, fJit, 100.0 * (fSent - fOk) / fSent)));
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }).start();
    }

    @Override protected void onDestroy() {
        running.set(false);
        super.onDestroy();
    }
}
