package com.netscanner;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.netscanner.net.NetworkUtils;
import com.netscanner.tools.ToolsActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ScanActivity extends AppCompatActivity {

    private TextView tvNet, tvStatus, btnWatch;
    private EditText etSubnet;
    private ProgressBar progress;
    private RecyclerView list;
    private DeviceAdapter adapter;
    private boolean scanning = false;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        GlassWindow.apply(this);
        sp = getSharedPreferences("netscanner", 0);

        // crash trap: persist any fatal stack trace, show it on next launch
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t2, e) -> {
            try { sp.edit().putString("last_crash", Log.getStackTraceString(e)).commit(); }
            catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(t2, e);
        });
        String lastCrash = sp.getString("last_crash", null);
        if (lastCrash != null) {
            sp.edit().remove("last_crash").apply();
            new AlertDialog.Builder(this)
                    .setTitle("Last crash")
                    .setMessage(lastCrash.length() > 1800 ? lastCrash.substring(0, 1800) : lastCrash)
                    .setPositiveButton("Send to dev (screenshot)", null)
                    .show();
        }

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        tvNet = findViewById(R.id.tv_net);
        tvStatus = findViewById(R.id.tv_status);
        btnWatch = findViewById(R.id.btn_watch);
        etSubnet = findViewById(R.id.et_subnet);
        progress = findViewById(R.id.progress);
        list = findViewById(R.id.list);
        adapter = new DeviceAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.btn_scan).setOnClickListener(v -> startScan());
        findViewById(R.id.btn_history).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        findViewById(R.id.btn_tools).setOnClickListener(v ->
                startActivity(new Intent(this, ToolsActivity.class)));
        findViewById(R.id.btn_localports).setOnClickListener(v ->
                startActivity(new Intent(this, LocalPortsActivity.class)));

        NetworkUtils.LocalNet n = NetworkUtils.localNet();
        if (n == null) {
            tvNet.setText("No Wi-Fi/LAN connection.\nConnect to a network and reopen.");
            etSubnet.setText("");
            findViewById(R.id.btn_scan).setEnabled(false);
        } else {
            tvNet.setText("Your IP  " + n.ip);
            etSubnet.setText(n.prefix);
            etSubnet.setHint("Subnet e.g. 192.168.1");
        }
        updateWatchBtn();

        btnWatch.setOnClickListener(v -> {
            boolean on = !sp.getBoolean("watch_enabled", false);
            sp.edit().putBoolean("watch_enabled", on).apply();
            applyWatch(on);
            updateWatchBtn();
            Toast.makeText(this, on ? "Wi-Fi Watch ON — checking every 15 min"
                    : "Wi-Fi Watch OFF", Toast.LENGTH_SHORT).show();
        });
    }

    private static String guessDevice(String open) {
        if (open.isEmpty()) return null;
        java.util.Set<Integer> p = new java.util.HashSet<>();
        for (String x : open.split(",")) {
            try { if (!x.isEmpty()) p.add(Integer.parseInt(x.trim())); } catch (Exception ignored) {}
        }
        if (p.contains(32400)) return "Plex media server";
        if (p.contains(9100) || p.contains(631)) return "likely printer";
        if (p.contains(554)) return "likely IP camera";
        if (p.contains(5000) || p.contains(5001)) return "likely Synology NAS";
        if (p.contains(22) && p.contains(80) && p.contains(8080)) return "likely router";
        if (p.contains(443) && p.contains(80)) return "web server / NAS";
        if (p.contains(80) || p.contains(8080)) return "has web interface";
        if (p.contains(443)) return "HTTPS service";
        return null;
    }

    private void updateWatchBtn() {
        boolean on = sp.getBoolean("watch_enabled", false);
        btnWatch.setText(on ? "👀 Watch ON" : "👀 Watch OFF");
        btnWatch.setTextColor(on ? 0xFF4ADE80 : 0xFF8A8A99);
    }

    private void applyWatch(boolean on) {
        WorkManager wm = WorkManager.getInstance(this);
        if (on) {
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(WatcherWorker.class,
                    15, TimeUnit.MINUTES).build();
            wm.enqueueUniquePeriodicWork("wifi-watch",
                    ExistingPeriodicWorkPolicy.UPDATE, req);
            // reset baseline so first run records fresh
            sp.edit().putString("baseline", "").apply();
        } else {
            wm.cancelUniqueWork("wifi-watch");
        }
    }

    private String subnetPrefix() {
        String s = etSubnet.getText().toString().trim();
        if (s.matches("\\d{1,3}(\\.\\d{1,3}){2}\\.?")) {
            if (!s.endsWith(".")) s += ".";
            return s;
        }
        NetworkUtils.LocalNet n = NetworkUtils.localNet();
        return n != null ? n.prefix : null;
    }

    private void startScan() {
        if (scanning) return;
        final String prefix = subnetPrefix();
        if (prefix == null) { Toast.makeText(this, "No network", Toast.LENGTH_SHORT).show(); return; }

        scanning = true;
        AppLog.log("scan start " + prefix);
        AppLog.cp(this, "scan_start");
        adapter.setItems(new ArrayList<>());
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText("Pinging " + prefix + "1–254 …");
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(false);

        final Thread t = new Thread(() -> {
            try {
                runScanBody(prefix);
            } catch (Throwable e) {
                AppLog.log("SCAN FATAL: " + e);
                runOnUiThread(() -> {
                    scanning = false;
                    progress.setVisibility(View.GONE);
                    tvStatus.setVisibility(View.VISIBLE);
                    tvStatus.setText("Scan error: " + e.getClass().getSimpleName()
                            + (e.getMessage() != null ? " — " + e.getMessage() : ""));
                });
            }
        });
        t.start();
    }

    private void runScanBody(String prefix) {
            List<String> alive = NetworkUtils.sweep(prefix, (done, total) ->
                    runOnUiThread(() -> {
                        progress.setProgress((int) (done * 100f / total));
                        tvStatus.setText("Probing hosts… " + done + "/" + total
                                + "   found: " + adapter.getItemCount());
                    }));
            AppLog.log("sweep done, alive=" + alive.size());
            AppLog.cp(this, "sweep_done alive=" + alive.size());

            Map<String, String> macs = NetworkUtils.neighborTable();
            // nudge hosts lacking a MAC — even a TCP RST forces the kernel to ARP-resolve
            for (String ip : alive) {
                if (!macs.containsKey(ip)) {
                    for (int port : new int[]{80, 443, 8080}) {
                        try (java.net.Socket s = new java.net.Socket()) {
                            s.connect(new java.net.InetSocketAddress(ip, port), 250);
                            break;
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (!alive.isEmpty()) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                macs = NetworkUtils.neighborTable();
            }
            AppLog.log("macs resolved=" + macs.size());
            AppLog.cp(this, "macs=" + macs.size());
            java.util.Set<String> ips = new java.util.HashSet<>(alive);
            ips.addAll(macs.keySet());
            NetworkUtils.LocalNet self = NetworkUtils.localNet();
            if (self != null && self.ip.startsWith(prefix)) ips.add(self.ip);

            List<Device> devices = new ArrayList<>();
            JSONArray arr = new JSONArray();
            for (String ip : ips) {
                Device d = new Device(ip);
                d.mac = macs.get(ip);
                d.reachable = alive.contains(ip);
                d.isSelf = self != null && ip.equals(self.ip);
                // resolved concurrently below
                devices.add(d);
                JSONObject o = new JSONObject();
                try {
                    o.put("ip", d.ip);
                    o.put("mac", d.mac == null ? "" : d.mac);
                    o.put("type", DeviceTypes.label(d));
                    arr.put(o);
                } catch (Exception ignored) {}
            }
            // last resort: per-host targeted neighbor query for anything still missing
            java.util.concurrent.ExecutorService mex = java.util.concurrent.Executors.newFixedThreadPool(24);
            java.util.concurrent.ConcurrentMap<String, String> extra = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.concurrent.CountDownLatch mlatch = new java.util.concurrent.CountDownLatch(devices.size());
            for (Device d : devices) {
                if (d.mac != null) { mlatch.countDown(); continue; }
                final String fip = d.ip;
                mex.execute(() -> {
                    try {
                        String m = NetworkUtils.macOf(fip);
                        if (m != null) extra.put(fip, m);
                    } finally { mlatch.countDown(); }
                });
            }
            try { mlatch.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            mex.shutdownNow();
            for (Device d : devices) if (d.mac == null) d.mac = extra.get(d.ip);

            java.util.concurrent.ExecutorService hex = java.util.concurrent.Executors.newFixedThreadPool(24);
            java.util.concurrent.CountDownLatch hlatch = new java.util.concurrent.CountDownLatch(devices.size());
            for (Device d : devices) {
                final Device fd = d;
                hex.execute(() -> {
                    try {
                        String nb = NetworkUtils.netbiosNameSync(fd.ip);
                        if (nb != null && !nb.trim().isEmpty()) {
                            fd.host = nb.trim();
                            AppLog.log("name(nb) " + fd.ip + " = " + nb.trim());
                            return;
                        }
                        String host = InetAddress.getByName(fd.ip).getHostName();
                        if (host != null && !host.equals(fd.ip)) {
                            fd.host = host;
                            AppLog.log("name(dns) " + fd.ip + " = " + host);
                            return;
                        }
                        String http = NetworkUtils.httpTitle(fd.ip);
                        if (http != null) {
                            fd.host = http;
                            AppLog.log("name(http) " + fd.ip + " = " + http);
                        }
                    } catch (Exception ignored) {
                    } finally { hlatch.countDown(); }
                });
            }
            try { hlatch.await(6, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            hex.shutdownNow();
            AppLog.cp(this, "hostnames_done");

            // weak-service flags: Telnet/FTP open = risky
            try {
                java.util.concurrent.ExecutorService rex = java.util.concurrent.Executors.newFixedThreadPool(24);
                java.util.concurrent.CountDownLatch rlatch = new java.util.concurrent.CountDownLatch(devices.size());
                for (Device d : devices) {
                    if (!d.reachable) { rlatch.countDown(); continue; }
                    final Device fd = d;
                    rex.execute(() -> {
                        try {
                            int[] ports = {23, 21, 80, 443, 554, 9100, 631, 5000, 32400, 8080};
                            StringBuilder open = new StringBuilder();
                            for (int port : ports) {
                                try (java.net.Socket s = new java.net.Socket()) {
                                    s.connect(new java.net.InetSocketAddress(fd.ip, port), 400);
                                    if (port == 23)
                                        fd.risk = (fd.risk == null ? "" : fd.risk + ", ") + "Telnet open";
                                    if (port == 21)
                                        fd.risk = (fd.risk == null ? "" : fd.risk + ", ") + "FTP open";
                                    open.append(port).append(',');
                                } catch (Exception ignored) {}
                            }
                            fd.guess = guessDevice(open.toString());
                        } finally { rlatch.countDown(); }
                    });
                }
                rlatch.await(8, TimeUnit.SECONDS);
                rex.shutdownNow();
                AppLog.log("risk pass done");
            } catch (Exception ignored) {}

            Collections.sort(devices, Comparator.comparingInt(a -> Integer.parseInt(a.lastOctet())));
            final JSONArray savedArr = arr;
            AppLog.log("building list, devices=" + devices.size());
            AppLog.cp(this, "ui_update_posted");

            // mDNS names (Chromecast, AirPlay, printers, smart home)
            try {
                Map<String, String> mdns = com.netscanner.tools.MdnsResolver.resolve(
                        getApplicationContext(), 4000);
                AppLog.log("mdns names=" + mdns.size());
                for (Device d : devices) {
                    if (d.host == null) {
                        String h = mdns.get(d.ip);
                        if (h != null) d.host = h;
                    }
                }
            } catch (Exception ignored) {}

            // persist CSV for the export card
            try {
                StringBuilder csv = new StringBuilder("ip,hostname,mac,type,reachable\n");
                for (Device d : devices) {
                    csv.append(d.ip).append(',')
                       .append('"').append(d.host == null ? "" : d.host.replace("\"", "'")).append('"').append(',')
                       .append('"').append(d.mac == null ? "" : d.mac).append('"').append(',')
                       .append('"').append(DeviceTypes.label(d)).append('"').append(',')
                       .append(d.reachable).append('\n');
                }
                sp.edit().putString("last_scan_csv", csv.toString()).apply();
            } catch (Exception ignored) {}
            AppLog.cp(this, "list_built devices=" + devices.size());

            runOnUiThread(() -> {
                adapter.setItems(devices);
                progress.setVisibility(View.GONE);
                tvStatus.setVisibility(View.GONE);
                scanning = false;
                if (devices.isEmpty()) {
                    tvStatus.setVisibility(View.VISIBLE);
                    tvStatus.setText("No devices found.");
                } else {
                    saveHistory(prefix, devices.size(), savedArr);
                    Toast.makeText(this, devices.size() + " device(s) — tap to port scan, long-press for tools",
                            Toast.LENGTH_LONG).show();
                }
            });
    }

    private void saveHistory(String subnet, int count, JSONArray devices) {
        try {
            JSONArray hist = new JSONArray(sp.getString("history", "[]"));
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("subnet", subnet + "0/24");
            e.put("count", count);
            e.put("devices", devices);
            JSONArray out = new JSONArray();
            out.put(e);
            for (int i = 0; i < Math.min(hist.length(), 29); i++) out.put(hist.get(i));
            sp.edit().putString("history", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    static class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.H> {
        final List<Device> items = new ArrayList<>();

        void setItems(List<Device> list) { items.clear(); items.addAll(list); notifyDataSetChanged(); }
        int itemCount() { return items.size(); }

        @Override public H onCreateViewHolder(ViewGroup parent, int viewType) {
            return new H(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_device, parent, false));
        }

        @Override public void onBindViewHolder(H h, int pos) {
            Device d = items.get(pos);
            h.ip.setText(DeviceTypes.emoji(d) + "  " + d.ip);
            String lbl = d.isSelf ? "This phone" : DeviceTypes.label(d);
            if (d.risk != null) lbl += "\n" + "⚠️ " + d.risk;
            if (d.guess != null) lbl += "\n" + "🤖 " + d.guess;
            h.host.setText(lbl);
            h.mac.setText(d.mac != null ? "MAC " + d.mac : "MAC unavailable");
            h.state.setText(d.isSelf ? "YOU" : (d.reachable ? "UP" : "ARP"));
            h.state.setTextColor(0xFF4ADE80);
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(v.getContext(), PortScanActivity.class);
                i.putExtra("ip", d.ip);
                i.putExtra("mac", d.mac == null ? "" : d.mac);
                v.getContext().startActivity(i);
            });
            h.itemView.setOnLongClickListener(v -> {
                new AlertDialog.Builder(v.getContext())
                        .setTitle(d.ip)
                        .setItems(d.mac != null
                                        ? new CharSequence[]{"⚡ Wake-on-LAN", "📶 Ping"}
                                        : new CharSequence[]{"📶 Ping"},
                                (dlg, which) -> {
                                    int idx = which;
                                    if (d.mac == null && idx == 0) idx = 1;
                                    if (idx == 0) {
                                        boolean ok = NetworkUtils.wakeOnLan(d.mac);
                                        Toast.makeText(v.getContext(), ok
                                                ? "⚡ Magic packet sent ×3"
                                                : "WoL failed — bad MAC?", Toast.LENGTH_SHORT).show();
                                    } else {
                                        int ms = NetworkUtils.pingOnce(d.ip);
                                        Toast.makeText(v.getContext(), ms < 0
                                                ? "❌ No reply" : ("📶 Reply in " + ms + " ms"),
                                                Toast.LENGTH_SHORT).show();
                                    }
                                })
                        .show();
                return true;
            });
        }

        @Override public int getItemCount() { return items.size(); }

        static class H extends RecyclerView.ViewHolder {
            TextView ip, host, mac, state;
            H(View v) { super(v);
                ip = v.findViewById(R.id.t_ip);
                host = v.findViewById(R.id.t_host);
                mac = v.findViewById(R.id.t_mac);
                state = v.findViewById(R.id.t_state);
            }
        }
    }
}
