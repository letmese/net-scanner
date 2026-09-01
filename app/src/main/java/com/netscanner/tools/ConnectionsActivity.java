package com.netscanner.tools;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.netscanner.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live per-app network traffic monitor.
 * /proc/net/tcp is empty for normal apps on Android 10+, so we use TrafficStats
 * UID counters instead: sample every second, show per-app down/up rates + session totals.
 * Works on every Android version, no root needed.
 */
public class ConnectionsActivity extends AppCompatActivity {

    private static final long POLL_MS = 1000;

    private ConnAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;
    private TextView totals;

    /** uid → cumulative bytes since boot, from previous sample */
    private final Map<Integer, long[]> last = new HashMap<>();
    private long lastTotalRx, lastTotalTx, lastStamp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connections);
        com.netscanner.GlassWindow.apply(this);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        totals = findViewById(R.id.tv_totals);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ConnAdapter();
        list.setAdapter(adapter);

        lastStamp = SystemClock.elapsedRealtime() - POLL_MS;
        refresh();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running) return;
                refresh();
                handler.postDelayed(this, POLL_MS);
            }
        }, POLL_MS);
    }

    @Override protected void onDestroy() { running = false; super.onDestroy(); }

    static class AppTraffic {
        String name;
        long rxRate, txRate;          // bytes/sec right now
        long rxTotal, txTotal;        // session totals (since screen opened)
    }

    private void refresh() {
        new Thread(() -> {
            long nowElapsed = SystemClock.elapsedRealtime();
            double secs = (nowElapsed - lastStamp) / 1000.0;
            if (secs < 0.2) secs = 0.2;

            // device totals
            long totRx = TrafficStats.getTotalRxBytes();
            long totTx = TrafficStats.getTotalTxBytes();
            final String fTot;
            if (!firstSample) {
                sessionRx += Math.max(0, totRx - lastTotalRx);
                sessionTx += Math.max(0, totTx - lastTotalTx);
                fTot = String.format("⬇ %s/s   ⬆ %s/s   this session: ⬇ %s  ⬆ %s",
                        fmt(totRx - lastTotalRx, secs), fmt(totTx - lastTotalTx, secs),
                        humanize(sessionRx), humanize(sessionTx));
            } else {
                fTot = "measuring…";
            }

            // enumerate candidate uids once (installed apps + system)
            List<AppTraffic> rows = new ArrayList<>();
            PackageManager pm = getPackageManager();
            Map<Integer, String> names = new HashMap<>();
            try {
                for (PackageInfo pi : pm.getInstalledPackages(0))
                    names.put(pi.applicationInfo.uid,
                            pi.applicationInfo.loadLabel(pm).toString());
            } catch (Exception ignored) {}

            for (Map.Entry<Integer, String> e : names.entrySet()) {
                int uid = e.getKey();
                long rx = TrafficStats.getUidRxBytes(uid);
                long tx = TrafficStats.getUidTxBytes(uid);
                if (rx < 0 && tx < 0) continue; // unsupported
                long[] prev = last.get(uid);
                long[] cur = {rx, tx};
                last.put(uid, cur);
                if (prev == null) continue; // first sample — no rate yet

                long drx = Math.max(0, rx - prev[0]);
                long dtx = Math.max(0, tx - prev[1]);
                if (drx == 0 && dtx == 0 && !sessionTotals.containsKey(uid)) continue;

                long[] st = sessionTotals.get(uid);
                if (st == null) { st = new long[2]; sessionTotals.put(uid, st); }
                st[0] += drx; st[1] += dtx;

                AppTraffic a = new AppTraffic();
                a.name = e.getValue();
                a.rxRate = (long) (drx / secs);
                a.txRate = (long) (dtx / secs);
                a.rxTotal = st[0];
                a.txTotal = st[1];
                rows.add(a);
            }

            lastTotalRx = totRx;
            lastTotalTx = totTx;
            firstSample = false;
            lastStamp = nowElapsed;

            rows.sort((x, y) -> Long.compare(y.rxRate + y.txRate, x.rxRate + x.txRate));
            List<AppTraffic> top = rows.size() > 30 ? rows.subList(0, 30) : rows;
            runOnUiThread(() -> {
                totals.setText(fTot);
                adapter.set(top);
            });
        }).start();
    }

    private final Map<Integer, long[]> sessionTotals = new HashMap<>();
    private long sessionRx, sessionTx;
    private boolean firstSample = true;

    static String fmt(long bytes, double secs) {
        double bps = bytes / secs;
        if (bps >= 1024 * 1024) return String.format("%.1f MB", bps / 1024 / 1024);
        if (bps >= 1024) return String.format("%.1f KB", bps / 1024);
        return String.format("%d B", (long) bps);
    }

    static String humanize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
        if (bytes >= 1024L * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024);
        if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    static class ConnAdapter extends RecyclerView.Adapter<ConnAdapter.H> {
        final List<AppTraffic> items = new ArrayList<>();
        void set(List<AppTraffic> l) { items.clear(); items.addAll(l); notifyDataSetChanged(); }

        @NonNull @Override public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new H(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app_traffic, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull H h, int pos) {
            AppTraffic a = items.get(pos);
            h.name.setText(a.name);
            h.total.setText(humanize(a.rxTotal) + " ↓ · " + humanize(a.txTotal) + " ↑ this session");
            h.down.setText("⬇ " + fmt(a.rxRate, 1));
            h.up.setText("⬆ " + fmt(a.txRate, 1));
        }
        @Override public int getItemCount() { return items.size(); }

        static class H extends RecyclerView.ViewHolder {
            TextView name, total, down, up;
            H(View v) { super(v);
                name = v.findViewById(R.id.t_app_name);
                total = v.findViewById(R.id.t_app_total);
                down = v.findViewById(R.id.t_rate_down);
                up = v.findViewById(R.id.t_rate_up);
            }
        }
    }
}
