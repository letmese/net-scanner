package com.netscanner;

import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class SignalActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView big, info;
    private volatile boolean running;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.root(this);
        Ui.header(this, "📶 Signal Meter", root);
        big = new TextView(this);
        big.setTextColor(0xFF4ADE80); big.setTextSize(30);
        big.setPadding(Ui.dp(this, 20), Ui.dp(this, 40), Ui.dp(this, 20), Ui.dp(this, 20));
        root.addView(big);
        info = new TextView(this);
        info.setTextColor(0xFFB9B9C9); info.setTextSize(14);
        info.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        root.addView(info);
        setContentView(root);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(WIFI_SERVICE);
                WifiInfo wi = wm.getConnectionInfo();
                int rssi = wi.getRssi();
                int pct = (rssi + 100) * 2;
                if (pct < 0) pct = 0;
                if (pct > 100) pct = 100;
                String bars = pct > 75 ? "▂▄▆█" : pct > 50 ? "▂▄▆▂" : pct > 25 ? "▂▄▂▂" : "▂▂▂▂";
                big.setText(rssi + " dBm  " + bars + "  " + pct + "%");
                String ssid = wi.getSSID() == null ? "?" : wi.getSSID().replace("\"", "");
                info.setText("SSID:  " + ssid
                        + "\nBSSID: " + wi.getBSSID()
                        + "\nLink speed: " + wi.getLinkSpeed() + " Mbps"
                        + "\nFrequency: " + wi.getFrequency() + " MHz"
                        + "\n\nWalk around the house and watch the meter.");
            } catch (Exception ignored) {}
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onResume() { super.onResume(); running = true; handler.post(tick); }
    @Override protected void onPause() { super.onPause(); running = false; handler.removeCallbacks(tick); }
}
