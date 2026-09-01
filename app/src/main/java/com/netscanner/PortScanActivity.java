package com.netscanner;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.netscanner.net.PortScanner;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;

import java.util.ArrayList;
import java.util.List;

public class PortScanActivity extends AppCompatActivity {

    private TextView tvTarget, tvStatus, tvSummary;
    private ProgressBar progress;
    private RecyclerView list;
    private PortAdapter adapter;
    private boolean scanning = false;
    private String ip;
    private List<Integer> lastPorts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_port_scan);
        GlassWindow.apply(this);

        ip = getIntent().getStringExtra("ip");
        tvTarget = findViewById(R.id.tv_target);
        tvStatus = findViewById(R.id.tv_status);
        tvSummary = findViewById(R.id.tv_summary);
        progress = findViewById(R.id.progress);
        list = findViewById(R.id.list);
        adapter = new PortAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        tvTarget.setText(ip);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        findViewById(R.id.btn_top20).setOnClickListener(v -> scan(PortScanner.TOP20, 500));
        findViewById(R.id.btn_top100).setOnClickListener(v -> {
            List<Integer> l = new ArrayList<>();
            for (int p : PortScanner.TOP100) l.add(p);
            scan(l, 600);
        });
        findViewById(R.id.btn_range).setOnClickListener(v -> {
            try {
                EditText a = findViewById(R.id.et_from), b = findViewById(R.id.et_to);
                int from = Integer.parseInt(a.getText().toString().trim());
                int to = Integer.parseInt(b.getText().toString().trim());
                if (from < 1 || to > 65535 || from > to) throw new NumberFormatException();
                if (to - from > 4000) { Toast.makeText(this, "Max 4000 ports per scan", Toast.LENGTH_SHORT).show(); return; }
                List<Integer> l = new ArrayList<>();
                for (int p = from; p <= to; p++) l.add(p);
                scan(l, 400);
            } catch (Exception e) {
                Toast.makeText(this, "Enter a valid port range", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveProfile() {
        if (lastPorts == null || lastPorts.isEmpty()) {
            Toast.makeText(this, "Run a scan first — then save its port list", Toast.LENGTH_LONG).show();
            return;
        }
        final EditText et = new EditText(this);
        et.setHint("Profile name e.g. gaming");
        new AlertDialog.Builder(this)
                .setTitle("Save port list (" + lastPorts.size() + " ports)")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        String n = et.getText().toString().trim();
                        if (n.isEmpty()) return;
                        JSONObject prof = new JSONObject(getSharedPreferences("netscanner", 0)
                                .getString("port_profiles", "{}"));
                        JSONArray arr = new JSONArray();
                        for (int p2 : lastPorts) arr.put(p2);
                        prof.put(n, arr);
                        getSharedPreferences("netscanner", 0).edit()
                                .putString("port_profiles", prof.toString()).apply();
                        Toast.makeText(this, "Saved '" + n + "' (" + lastPorts.size() + " ports)", Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadProfile() {
        try {
            JSONObject prof = new JSONObject(getSharedPreferences("netscanner", 0)
                    .getString("port_profiles", "{}"));
            if (prof.length() == 0) { Toast.makeText(this, "No saved profiles yet", Toast.LENGTH_SHORT).show(); return; }
            final String[] names = new String[prof.length()];
            int i = 0;
            Iterator<String> it = prof.keys();
            while (it.hasNext()) names[i++] = it.next();
            new AlertDialog.Builder(this)
                    .setTitle("Load profile")
                    .setItems(names, (d, w) -> {
                        try {
                            JSONArray arr = prof.getJSONArray(names[w]);
                            List<Integer> l = new ArrayList<>();
                            for (int k = 0; k < arr.length(); k++) l.add(arr.getInt(k));
                            scan(l, 450);
                        } catch (Exception ignored) {}
                    })
                    .show();
        } catch (Exception ignored) {}
    }

    private void scan(int[] ports, int timeoutMs) {
        List<Integer> l = new ArrayList<>();
        for (int p : ports) l.add(p);
        scan(l, timeoutMs);
    }

    private void scan(List<Integer> ports, int timeoutMs) {
        if (scanning) return;
        scanning = true;
        lastPorts = ports;
        adapter.clear();
        tvSummary.setVisibility(View.GONE);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText("Connecting to " + ip + " …");
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(false);

        PortScanner.scan(ip, ports, timeoutMs, new PortScanner.Callback() {
            @Override public void onProgress(int done, int total) {
                runOnUiThread(() -> {
                    progress.setProgress((int) (done * 100f / total));
                    tvStatus.setText("Scanning " + done + "/" + total + " ports   open: " + adapter.getItemCount());
                });
            }
            @Override public void onOpen(int port) {
                runOnUiThread(() -> adapter.addPort(port));
            }
            @Override public void onDone(List<Integer> openPorts, long ms) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    tvStatus.setVisibility(View.GONE);
                    scanning = false;
                    tvSummary.setVisibility(View.VISIBLE);
                    tvSummary.setText(openPorts.isEmpty()
                            ? "No open TCP ports found (" + (ms / 1000) + "s)"
                            : openPorts.size() + " open port(s) in " + (ms / 1000) + "s");
                });
                grabBanners(openPorts);
            }
        });
    }

    /** Fingerprint every open port (nmap-lite) and show software + risk notes. */
    private void grabBanners(List<Integer> openPorts) {
        if (openPorts.isEmpty()) return;
        new Thread(() -> {
            for (int p : openPorts) {
                String note = com.netscanner.net.Fingerprinter.riskNote(p);
                if (note != null) {
                    final String warn = "⚠️ :" + p + " — " + note;
                    runOnUiThread(() -> adapter.addBanner(warn));
                }
                try {
                    final String banner = com.netscanner.net.Fingerprinter.probe(ip, p);
                    if (banner != null && !banner.isEmpty()) {
                        runOnUiThread(() -> adapter.addBanner("🔎 :" + p + "  " + banner));
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    static class PortAdapter extends RecyclerView.Adapter<PortAdapter.H> {
        static final int TYPE_PORT = 0, TYPE_BANNER = 1;
        final List<Object> items = new ArrayList<>(); // Integer=port, String=banner line
        void clear() { items.clear(); notifyDataSetChanged(); }
        void addPort(int p) { items.add(p); notifyItemInserted(items.size() - 1); }
        void addBanner(String b) { items.add(b); notifyItemInserted(items.size() - 1); }

        @Override public int getItemViewType(int pos) {
            return items.get(pos) instanceof Integer ? TYPE_PORT : TYPE_BANNER;
        }

        @Override public H onCreateViewHolder(ViewGroup parent, int viewType) {
            return new H(LayoutInflater.from(parent.getContext()).inflate(
                    viewType == TYPE_BANNER ? R.layout.item_banner : R.layout.item_port, parent, false));
        }
        @Override public void onBindViewHolder(H h, int pos) {
            Object o = items.get(pos);
            if (o instanceof Integer) {
                int p = (Integer) o;
                h.port.setText(String.valueOf(p));
                h.svc.setText(PortScanner.service(p));
                h.state.setText("OPEN");
            } else {
                h.banner.setText((String) o);
            }
        }
        @Override public int getItemCount() { return items.size(); }

        static class H extends RecyclerView.ViewHolder {
            TextView port, svc, state, banner;
            H(View v) { super(v);
                port = v.findViewById(R.id.t_port);
                svc = v.findViewById(R.id.t_svc);
                state = v.findViewById(R.id.t_state);
                banner = v.findViewById(R.id.t_banner);
            }
        }
    }
}
