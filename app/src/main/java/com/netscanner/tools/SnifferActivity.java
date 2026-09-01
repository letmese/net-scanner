package com.netscanner.tools;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import com.netscanner.R;

public class SnifferActivity extends Activity {

    private static final int REQ_VPN = 7;
    private TextView out;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sniffer);
        com.netscanner.GlassWindow.apply(this);

        out = findViewById(R.id.tv_output);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_toggle).setOnClickListener(v -> toggle());

        Intent prep = VpnService.prepare(this);
        if (prep != null) startActivityForResult(prep, REQ_VPN);
        else startSniffer();
    }

    private void toggle() {
        stopService(new Intent(this, SnifferVpnService.class));
        SnifferVpnService.LOG.clear();
        out.setText("Stopped.\n\nPress again or reopen to restart.");
        startSniffer();
    }

    private void startSniffer() {
        startService(new Intent(this, SnifferVpnService.class));
        if (!polling) {
            polling = true;
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    StringBuilder sb = new StringBuilder();
                    for (String line : SnifferVpnService.LOG) sb.append(line).append("\n");
                    out.setText(sb.length() > 0 ? sb.toString() : "Listening for DNS queries…\n"
                            + "(routes your network DNS + public resolvers through the sniffer)");
                    handler.postDelayed(this, 1000);
                }
            }, 1000);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN) {
            if (res == RESULT_OK) startSniffer();
            else out.setText("VPN permission denied — the sniffer needs it to intercept DNS.");
        }
    }

    @Override protected void onDestroy() { polling = false; super.onDestroy(); }
}
