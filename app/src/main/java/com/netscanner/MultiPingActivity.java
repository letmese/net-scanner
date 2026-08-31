package com.netscanner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.netscanner.net.NetworkUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Live latency for several targets at once, color-coded. */
public class MultiPingActivity extends AppCompatActivity {

    private static final int[] COLORS = {0xFF4ADE80, 0xFF60A5FA, 0xFFFBBF24, 0xFFF472B6};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Target> targets = new ArrayList<>();
    private LinearLayout list;
    private volatile boolean running;
    private EditText custom;

    static class Target {
        String name;
        String host;
        TextView label;
        SparkView chart;
        TextView stats;
        final ArrayDeque<Float> samples = new ArrayDeque<>();
        int ok, sent;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.root(this);
        Ui.header(this, "📈 Multi-Ping", root);

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        int d = Ui.dp(this, 1);
        addRow.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        custom = new EditText(this);
        custom.setHint("Add target: IP or host");
        custom.setTextColor(0xFFEDEDF2);
        custom.setTextScaleX(1f);
        addRow.addView(custom, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = new TextView(this);
        go.setText("＋ Add");
        go.setTextColor(0xFF4ADE80); go.setTypeface(null, android.graphics.Typeface.BOLD);
        go.setPadding(d * 20, 0, 0, 0);
        go.setOnClickListener(v -> {
            String h = custom.getText().toString().trim();
            if (h.isEmpty()) return;
            addTarget(h, h);
            custom.setText("");
        });
        addRow.addView(go);
        root.addView(addRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(this);
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        String gw = "Gateway";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.DhcpInfo di = wm.getDhcpInfo();
            if (di != null)
                gw = ((di.gateway & 0xFF) + "." + ((di.gateway >> 8) & 0xFF)
                        + "." + ((di.gateway >> 16) & 0xFF) + "." + ((di.gateway >> 24) & 0xFF));
        } catch (Exception ignored) {}
        addTarget("Gateway", gw.equals("Gateway") ? "192.168.1.1" : gw);
        addTarget("Cloudflare DNS", "1.1.1.1");
    }

    private void addTarget(String name, String host) {
        if (targets.size() >= 4) { Toast.makeText(this, "Max 4 targets", Toast.LENGTH_SHORT).show(); return; }
        Target t = new Target();
        t.name = name; t.host = host;
        int color = COLORS[targets.size() % COLORS.length];

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.glass_card);
        int d = Ui.dp(this, 1);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMargins(d * 14, d * 8, d * 14, d * 8);
        box.setPadding(d * 12, d * 10, d * 12, d * 10);
        box.setLayoutParams(bp);

        t.label = new TextView(this);
        t.label.setText(name + "  (" + host + ")");
        t.label.setTextColor(color);
        t.label.setTypeface(null, android.graphics.Typeface.BOLD);
        t.label.setTextSize(13);
        box.addView(t.label);

        t.chart = new SparkView(this, color);
        box.addView(t.chart, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 70)));

        t.stats = new TextView(this);
        t.stats.setTextColor(0xFF8A8A99); t.stats.setTextSize(11);
        t.stats.setText("waiting…");
        box.addView(t.stats);

        list.addView(box);
        targets.add(t);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            for (final Target t : targets) {
                new Thread(() -> {
                    final int ms = NetworkUtils.pingOnce(t.host);
                    t.sent++;
                    if (ms >= 0) {
                        t.ok++;
                        synchronized (t.samples) {
                            t.samples.addLast((float) ms);
                            while (t.samples.size() > 60) t.samples.removeFirst();
                        }
                    }
                    handler.post(() -> {
                        synchronized (t.samples) {
                            t.chart.setData(t.samples);
                            t.chart.invalidate();
                        }
                        t.stats.setText(t.ok + "/" + t.sent + " ok"
                                + (ms >= 0 ? "  ·  last " + ms + " ms" : "  ·  timeout"));
                    });
                }).start();
            }
            handler.postDelayed(this, 1200);
        }
    };

    @Override protected void onResume() { super.onResume(); running = true; handler.post(tick); }
    @Override protected void onPause() { super.onPause(); running = false; handler.removeCallbacks(tick); }

    /** Tiny scrolling latency sparkline. */
    static class SparkView extends android.view.View {
        private final int color;
        private ArrayDeque<Float> data;

        SparkView(Context c, int color) { super(c); this.color = color; }
        void setData(ArrayDeque<Float> d) { data = d; }

        @Override protected void onDraw(Canvas c) {
            Paint bg = new Paint();
            bg.setColor(0xFF101020);
            c.drawRect(0, 0, getWidth(), getHeight(), bg);
            if (data == null || data.isEmpty()) return;
            Float[] arr = data.toArray(new Float[0]);
            float max = 1;
            for (float v : arr) if (v > max) max = v;
            float maxV = Math.max(max * 1.2f, 20f);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color); p.setStrokeWidth(4); p.setStyle(Paint.Style.STROKE);
            Path path = new Path();
            float w = getWidth() - 8, h = getHeight() - 8;
            for (int i = 0; i < arr.length; i++) {
                float x = 4 + w * i / Math.max(1, arr.length - 1);
                float y = 4 + h * (1 - arr[i] / maxV);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            c.drawPath(path, p);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(0xFF8A8A99); t.setTextSize(22);
            c.drawText((int) max + " ms", 8, 26, t);
        }
    }
}
