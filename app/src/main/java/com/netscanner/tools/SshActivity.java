package com.netscanner.tools;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.netscanner.R;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class SshActivity extends AppCompatActivity {

    private TextView out;
    private Session session;
    private String lastHost, lastUser, lastPass;
    private int lastPort = 22;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ssh);

        findViewById(R.id.btn_back).setOnClickListener(v -> {
            disconnect();
            finish();
        });
        out = findViewById(R.id.tv_output);
        out.setMovementMethod(new ScrollingMovementMethod());

        findViewById(R.id.btn_connect).setOnClickListener(v -> connect());
        findViewById(R.id.btn_run).setOnClickListener(v -> runCmd());
        findViewById(R.id.btn_disconnect).setOnClickListener(v -> disconnect());
    }

    private void connect() {
        lastHost = ((EditText) findViewById(R.id.et_host)).getText().toString().trim();
        lastUser = ((EditText) findViewById(R.id.et_user)).getText().toString().trim();
        lastPass = ((EditText) findViewById(R.id.et_pass)).getText().toString();
        String portStr = ((EditText) findViewById(R.id.et_port)).getText().toString().trim();
        lastPort = portStr.isEmpty() ? 22 : Integer.parseInt(portStr);
        if (lastHost.isEmpty() || lastUser.isEmpty()) return;

        log("Connecting " + lastUser + "@" + lastHost + ":" + lastPort + " …");
        new Thread(() -> {
            try {
                disconnectQuiet();
                JSch jsch = new JSch();
                session = jsch.getSession(lastUser, lastHost, lastPort);
                session.setPassword(lastPass);
                session.setConfig("StrictHostKeyChecking", "no");
                session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
                session.connect(5000);
                log("✅ Connected — type a command (e.g. ufw status / iptables -L -n)");
            } catch (Exception e) {
                log("❌ " + e.getMessage());
                session = null;
            }
        }).start();
    }

    private void runCmd() {
        if (session == null || !session.isConnected()) { log("Not connected"); return; }
        String cmd = ((EditText) findViewById(R.id.et_cmd)).getText().toString().trim();
        if (cmd.isEmpty()) return;
        new Thread(() -> {
            try {
                ChannelExec ch = (ChannelExec) session.openChannel("exec");
                ch.setCommand(cmd);
                ch.setErrStream(System.err);
                InputStream in = ch.getInputStream();
                ch.connect(3000);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                ch.disconnect();
                String result = buf.toString().trim();
                log("$ " + cmd + (result.isEmpty() ? "\n(no output)" : "\n" + result));
            } catch (Exception e) {
                log("❌ " + e.getMessage());
            }
        }).start();
    }

    private void disconnect() {
        disconnectQuiet();
        log("Disconnected.");
    }

    private void disconnectQuiet() {
        try { if (session != null && session.isConnected()) session.disconnect(); } catch (Exception ignored) {}
        session = null;
    }

    private void log(String s) { runOnUiThread(() -> out.append(s + "\n")); }

    @Override protected void onDestroy() { disconnectQuiet(); super.onDestroy(); }
}
