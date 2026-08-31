package com.netscanner.tools;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.netscanner.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class WifiAnalyzerActivity extends AppCompatActivity {

    private NetAdapter adapter;
    private WifiManager wm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_analyzer);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NetAdapter();
        list.setAdapter(adapter);

        if (Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 2);
        }

        findViewById(R.id.btn_refresh).setOnClickListener(v -> scan());
        scan();
        // first getScanResults() is often stale/empty — rescan once results land
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { return; }
            runOnUiThread(() -> { if (!isDestroyed() && adapter.getItemCount() == 0) scan(); });
        }).start();
    }

    private void scan() {
        wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        try { wm.startScan(); } catch (Exception ignored) {}
        List<ScanResult> results = wm.getScanResults();
        Collections.sort(results, Comparator.comparingInt(r -> r.level));
        Collections.reverse(results);
        adapter.set(results);
        if (results.isEmpty())
            Toast.makeText(this, "No results — grant location permission and rescan", Toast.LENGTH_LONG).show();
    }

    /** Manual signal bars (avoids calculateSignalLevel API differences). */
    static String bars(int dBm) {
        int level = dBm >= -50 ? 4 : dBm >= -60 ? 3 : dBm >= -70 ? 2 : dBm >= -80 ? 1 : 0;
        return new String[]{"____", "▂___", "▂▄__", "▂▄▆_", "▂▄▆█"}[level];
    }

    static String band(int freq) { return freq > 5000 ? "5 GHz" : freq < 3000 ? "2.4 GHz" : "?"; }

    static int channelOf(int freq) {
        if (freq >= 2412 && freq <= 2472) return (freq - 2412) / 5 + 1;
        if (freq == 2484) return 14;
        if (freq >= 5170 && freq <= 5825) return (freq - 5170) / 5 + 34;
        return 0;
    }

    static int sameChannelCount(List<ScanResult> all, int freq) {
        int ch = channelOf(freq), count = 0;
        for (ScanResult r : all) if (channelOf(r.frequency) == ch) count++;
        return count;
    }

    static class NetAdapter extends RecyclerView.Adapter<NetAdapter.H> {
        final List<ScanResult> items = new ArrayList<>();
        void set(List<ScanResult> l) { items.clear(); items.addAll(l); notifyDataSetChanged(); }

        @NonNull @Override public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new H(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_network, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull H h, int pos) {
            ScanResult r = items.get(pos);
            h.ssid.setText(r.SSID == null || r.SSID.isEmpty() ? "(hidden)" : r.SSID);
            String caps = r.capabilities.replace("[", " ").replace("]", " ").trim();
            h.meta.setText(band(r.frequency) + " ch" + channelOf(r.frequency)
                    + "   ·   " + r.BSSID + "   ·   " + caps);
            int same = sameChannelCount(items, r.frequency);
            h.congestion.setText(same > 3 ? "⚠️ crowded channel (" + same + " networks)"
                    : same > 1 ? "~ shared with " + (same - 1) + " network(s)" : "clear channel");
            h.signal.setText(bars(r.level) + " " + r.level + " dBm");
            int color = r.level > -50 ? 0xFF4ADE80 : r.level > -65 ? 0xFFFACC15 : 0xFFEF4444;
            h.signal.setTextColor(color);
        }
        @Override public int getItemCount() { return items.size(); }

        static class H extends RecyclerView.ViewHolder {
            TextView ssid, meta, signal, congestion;
            H(View v) { super(v);
                ssid = v.findViewById(R.id.t_ssid);
                meta = v.findViewById(R.id.t_meta);
                signal = v.findViewById(R.id.t_signal);
                congestion = v.findViewById(R.id.t_congestion);
            }
        }
    }
}
