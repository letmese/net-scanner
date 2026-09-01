package com.netscanner;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * "My Ports" — shows which apps on THIS phone are listening on which ports.
 * Root-free port closure: deep-link to the app's system page → Force Stop closes its sockets.
 */
public class LocalPortsActivity extends AppCompatActivity {

    private PortAdapter adapter;
    private TextView tvNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local_ports);
        GlassWindow.apply(this);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        tvNote = findViewById(R.id.tv_note);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PortAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btn_refresh).setOnClickListener(v -> refresh());
        refresh();
    }

    private void refresh() {
        adapter.clear();
        tvNote.setVisibility(View.GONE);
        new Thread(() -> {
            List<Listener> found = readListeners();
            runOnUiThread(() -> {
                if (found.isEmpty()) {
                    tvNote.setVisibility(View.VISIBLE);
                    tvNote.setText("No listening TCP sockets visible.\n(Some Android versions hide other apps' sockets.)");
                } else {
                    for (Listener l : found) adapter.add(l);
                    tvNote.setVisibility(View.VISIBLE);
                    tvNote.setText(found.size() + " listening port(s) — tap an app to open its system page, then Force Stop to close its ports.");
                }
            });
        }).start();
    }

    private static class Listener {
        int port; String app; String pkg; boolean system;
    }

    /** Parse /proc/net/tcp{,6} for LISTEN (st=0A) sockets and map uid→package. */
    private List<Listener> readListeners() {
        List<Listener> out = new ArrayList<>();
        ArrayList<int[]> entries = new ArrayList<>(); // {port, uid}
        collect("/proc/net/tcp", entries);
        collect("/proc/net/tcp6", entries);

        android.content.pm.PackageManager pm = getPackageManager();
        for (int[] e : entries) {
            Listener l = new Listener();
            l.port = e[0];
            int uid = e[1];
            if (uid >= 10000) {
                try {
                    String[] pkgs = pm.getPackagesForUid(uid);
                    if (pkgs != null && pkgs.length > 0) {
                        l.pkg = pkgs[0];
                        l.app = pm.getApplicationLabel(pm.getApplicationInfo(l.pkg, 0)).toString();
                    }
                } catch (Exception ignored) {}
            }
            if (l.app == null) {
                l.system = true;
                switch (uid) {
                    case 0: l.app = "Android system (root)"; break;
                    case 1000: l.app = "Android system"; break;
                    case 1010: l.app = "Wi-Fi system"; break;
                    default: l.app = "System uid " + uid; break;
                }
            }
            out.add(l);
        }
        out.sort((a, b) -> Integer.compare(a.port, b.port));
        return out;
    }

    private void collect(String path, List<int[]> out) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] t = line.trim().split("\\s+");
                if (t.length < 8 || !t[3].equals("0A")) continue; // 0A = LISTEN
                try {
                    int port = Integer.parseInt(t[1].split(":")[1], 16);
                    int uid = Integer.parseInt(t[7]);
                    out.add(new int[]{port, uid});
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    static class PortAdapter extends RecyclerView.Adapter<PortAdapter.H> {
        final List<Listener> items = new ArrayList<>();
        void clear() { items.clear(); notifyDataSetChanged(); }
        void add(Listener l) { items.add(l); notifyItemInserted(items.size() - 1); }

        @NonNull @Override public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new H(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_listener, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull H h, int pos) {
            Listener l = items.get(pos);
            h.port.setText(":" + l.port);
            h.app.setText(l.system ? "🔒 " + l.app : "📦 " + l.app);
            h.pkg.setText(l.pkg != null ? l.pkg : "cannot be stopped without root");
            h.itemView.setOnClickListener(v -> {
                if (l.pkg == null) return;
                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + l.pkg));
                v.getContext().startActivity(i);
            });
        }
        @Override public int getItemCount() { return items.size(); }

        static class H extends RecyclerView.ViewHolder {
            TextView port, app, pkg;
            H(View v) { super(v);
                port = v.findViewById(R.id.t_port);
                app = v.findViewById(R.id.t_app);
                pkg = v.findViewById(R.id.t_pkg);
            }
        }
    }
}
