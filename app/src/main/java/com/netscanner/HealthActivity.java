package com.netscanner;

import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.netscanner.net.NetworkUtils;

import org.json.JSONArray;

public class HealthActivity extends AppCompatActivity {

    private TextView scoreView, detail;
    private LinearLayout root;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = Ui.root(this);
        Ui.header(this, "🩺 Network Health", root);

        scoreView = new TextView(this);
        scoreView.setTextSize(44);
        scoreView.setTextColor(0xFFEDEDF2);
        scoreView.setGravity(android.view.Gravity.CENTER);
        root.addView(scoreView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 120)));

        detail = new TextView(this);
        detail.setTextColor(0xFFB9B9C9); detail.setTextSize(13);
        detail.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 20));
        ScrollView sc = new ScrollView(this); sc.addView(detail); root.addView(sc);
        setContentView(root);
        runChecks();
    }

    private void runChecks() {
        scoreView.setText("…");
        detail.setText("Running checks: latency ×5, jitter, loss, Wi-Fi signal, last speed…");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            int score = 100;

            // latency / jitter / loss
            int ok = 0, sent = 5;
            long sum = 0;
            Long prev = null;
            double jitSum = 0; int jitN = 0;
            for (int i = 0; i < sent; i++) {
                int ms = NetworkUtils.pingOnce("1.1.1.1");
                if (ms >= 0) {
                    ok++; sum += ms;
                    if (prev != null) { jitSum += Math.abs(ms - prev); jitN++; }
                    prev = (long) ms;
                } else prev = null;
                try { Thread.sleep(250); } catch (InterruptedException e) { return; }
            }
            double lossPct = 100.0 * (sent - ok) / sent;
            double avg = ok > 0 ? sum / (double) ok : 0;
            double jitter = jitN > 0 ? jitSum / jitN : 0;

            sb.append("Latency:   ").append(ok > 0 ? String.format("%.0f ms", avg) : "no reply").append('\n');
            sb.append(String.format("Jitter:    %.0f ms%n", jitter));
            sb.append(String.format("Loss:      %.0f%%%n", lossPct));

            score -= Math.round(lossPct * 2.5);
            if (avg > 150 && ok > 0) { score -= 15; sb.append("  ⚠ high latency\n"); }
            else if (avg > 80 && ok > 0) { score -= 7; sb.append("  ⚠ elevated latency\n"); }
            if (jitter > 30) { score -= 12; sb.append("  ⚠ unstable timing\n"); }
            else if (jitter > 15) { score -= 5; }

            // Wi-Fi signal
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo wi = wm.getConnectionInfo();
                int rssi = wi.getRssi();
                int pct = Math.max(0, Math.min(100, (rssi + 100) * 2));
                sb.append(String.format("Signal:    %d dBm (%d%%)%n", rssi, pct));
                if (pct < 40) { score -= 15; sb.append("  ⚠ weak Wi-Fi — move closer\n"); }
                else if (pct < 60) { score -= 7; }
            } catch (Exception ignored) {}

            // last speed test
            try {
                JSONArray h = new JSONArray(getSharedPreferences("netscanner", 0)
                        .getString("speed_hist", "[]"));
                if (h.length() > 0) {
                    double down = h.getJSONObject(0).getDouble("down");
                    sb.append(String.format("Last speed: %.1f Mbps ⬇%n", down));
                    if (down < 10) { score -= 20; sb.append("  ⚠ very slow download\n"); }
                    else if (down < 25) { score -= 10; }
                    else if (down < 50) { score -= 4; }
                } else {
                    sb.append("Last speed: run a speed test to include it\n");
                }
            } catch (Exception ignored) {}

            if (lossPct == 0 && jitter <= 10 && avg > 0 && avg <= 40)
                sb.append("\n✅ Excellent — nothing to fix.");
            else {
                sb.append("\nTips:\n");
                if (avg > 80 || jitter > 15) sb.append("• Try 5 GHz Wi-Fi or ethernet\n");
                if (lossPct > 0) sb.append("• Loss usually = weak signal or congested channel\n");
                if (score < 60) sb.append("• Reboot the router — fixes most degradation\n");
            }

            score = Math.max(0, Math.min(100, score));
            String grade = score >= 90 ? "A" : score >= 75 ? "B" : score >= 60 ? "C"
                    : score >= 45 ? "D" : "F";
            int color = score >= 75 ? 0xFF4ADE80 : score >= 60 ? 0xFFFBBF24 : 0xFFFF6B6B;

            AppLog.log("health score=" + score + grade);
            final int fScore = score;
            final String fText = sb.toString();
            runOnUiThread(() -> {
                scoreView.setText(fScore + "  " + grade);
                scoreView.setTextColor(color);
                detail.setText(fText);
            });
        }).start();
    }
}
