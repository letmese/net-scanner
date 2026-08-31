package com.netscanner.tools;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.netscanner.R;
import com.netscanner.net.NetworkUtils;

public class NetDiagActivity extends AppCompatActivity {

    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_netdiag);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        output = findViewById(R.id.tv_output);
        output.setMovementMethod(new ScrollingMovementMethod());

        NetworkUtils.LocalNet n = NetworkUtils.localNet();
        if (n != null) ((EditText) findViewById(R.id.et_host)).setText(n.ip);

        findViewById(R.id.btn_ping).setOnClickListener(v -> {
            String host = host();
            if (host == null) return;
            output.setText("Pinging " + host + " ×10 …\n");
            ping10(host);
        });

        findViewById(R.id.btn_ssdp).setOnClickListener(v -> {
            output.append("\nSSDP discovery (UPnP devices on network)…\n");
            NetworkUtils.ssdpDiscover((name, mfr, model, services, location) -> runOnUiThread(() -> {
                StringBuilder sb = new StringBuilder("📦 ");
                sb.append(name == null ? "(unnamed device)" : name);
                if (mfr != null) sb.append(" — ").append(mfr);
                if (model != null) sb.append(" · ").append(model);
                sb.append("\n   ").append(location);
                if (services != null) sb.append("\n   services: ").append(services);
                // highlight if this device lives on a port we care about
                if (location != null && location.contains(":8080"))
                    sb.append("\n   ⬆️ THIS is your :8080 !");
                output.append(sb.toString());
                output.append("\n\n");
            }));
        });

        findViewById(R.id.btn_trace).setOnClickListener(v -> {
            String host = host();
            if (host == null) return;
            output.setText("Traceroute to " + host + ":\n");
            NetworkUtils.traceroute(host, (ttl, ip, ms) -> runOnUiThread(() ->
                    output.append(String.format("  %2d   %-15s  %s%n", ttl, ip,
                            ms == null ? "*" : ms))));
        });
    }

    private String host() {
        String h = ((EditText) findViewById(R.id.et_host)).getText().toString().trim();
        if (h.isEmpty()) { Toast.makeText(this, "Enter a host", Toast.LENGTH_SHORT).show(); return null; }
        return h;
    }

    private void ping10(String host) {
        new Thread(() -> {
            int ok = 0;
            long sum = 0;
            for (int i = 0; i < 10; i++) {
                long t0 = System.currentTimeMillis();
                boolean up = NetworkUtils.ping(host);
                long ms = System.currentTimeMillis() - t0;
                if (up) { ok++; sum += ms; }
                final String line = String.format("  %d: %s %s%n", i + 1,
                        up ? ("reply in " + ms + " ms") : "no reply", "");
                runOnUiThread(() -> output.append(line));
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
            final long avg = ok > 0 ? sum / ok : -1;
            final int fOk = ok;
            runOnUiThread(() -> output.append(String.format(
                    "%nDone — %d/10 replies%s%n", fOk,
                    avg >= 0 ? (", avg " + avg + " ms") : "")));
        }).start();
    }
}
