package com.netscanner;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v3.9 Auto Speed Tests control screen (opens from dashboard box).
 * Interval dropdown 5-60 min, start/stop of the AutoSpeedService foreground
 * loop, one-off "Test now", legacy 2 AM nightly toggle (NightSpeedWorker),
 * full per-run history with stats, CSV export and clear.
 */
public class AutoSpeedActivity extends AppCompatActivity {

    private static final long[] INTERVALS_MIN = {5, 10, 15, 20, 30, 45, 60};
    private static final int DISPLAY_CAP = 200;   // rows rendered (log itself keeps 3000)

    private TextView statusLine, infoLine, statsLine;
    private Button startBtn, nowBtn, nightBtn;
    private Spinner intervalSpin;
    private LinearLayout histBox;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean localTesting = false;
    private boolean spinGuard = false;
    private long renderedThroughTs = -1;
    private final SimpleDateFormat df =
            new SimpleDateFormat("EEE MMM d, HH:mm", Locale.getDefault());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.root(this);
        Ui.header(this, "\u23F1 Auto Speed Tests", root);

        // ---- status card ----
        LinearLayout card = card(root);
        statusLine = txt(16, 0xFF8A8A99, true);
        card.addView(statusLine);
        infoLine = txt(12, 0xFFB9B9C9, false);
        infoLine.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(infoLine);

        // ---- interval dropdown ----
        LinearLayout sel = card(root);
        LinearLayout rowSel = new LinearLayout(this);
        rowSel.setOrientation(LinearLayout.HORIZONTAL);
        rowSel.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = txt(13, 0xFF8A8A99, false);
        lbl.setText("Run every:");
        lbl.setPadding(0, 0, Ui.dp(this, 12), 0);
        rowSel.addView(lbl);

        List<String> opts = new ArrayList<>();
        for (long m : INTERVALS_MIN) opts.add(m >= 60 ? "1 hour" : m + " minutes");
        ArrayAdapter<String> ad =
                new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, opts) {
                    @Override public View getView(int pos, View cv, ViewGroup pg) {
                        return dark(cv, pos, false);
                    }
                    @Override public View getDropDownView(int pos, View cv, ViewGroup pg) {
                        return dark(cv, pos, true);
                    }
                    private View dark(View cv, int pos, boolean drop) {
                        TextView t = cv instanceof TextView
                                ? (TextView) cv : new TextView(AutoSpeedActivity.this);
                        t.setText(getItem(pos));
                        t.setTextSize(14);
                        t.setTextColor(0xFFEDEDF2);
                        t.setPadding(Ui.dp(AutoSpeedActivity.this, 12),
                                Ui.dp(AutoSpeedActivity.this, 10),
                                Ui.dp(AutoSpeedActivity.this, 12),
                                Ui.dp(AutoSpeedActivity.this, 10));
                        if (drop) t.setBackgroundColor(0xFF181824);
                        return t;
                    }
                };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        intervalSpin = new Spinner(this);
        intervalSpin.setAdapter(ad);
        rowSel.addView(intervalSpin, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sel.addView(rowSel);

        long cur = getSharedPreferences("netscanner", 0).getLong("auto_interval_min", 30L);
        int idx = 4;
        for (int i = 0; i < INTERVALS_MIN.length; i++) if (INTERVALS_MIN[i] == cur) idx = i;
        spinGuard = true;
        intervalSpin.setSelection(idx, false);
        intervalSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (spinGuard) { spinGuard = false; return; }
                getSharedPreferences("netscanner", 0).edit()
                        .putLong("auto_interval_min", INTERVALS_MIN[pos]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        // ---- action buttons ----
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        startBtn = btn("Start");
        startBtn.setOnClickListener(v -> toggleRun());
        btnRow.addView(startBtn, w());
        nowBtn = btn("\u26A1 Test now");
        nowBtn.setOnClickListener(v -> testNow());
        btnRow.addView(nowBtn, w());
        root.addView(btnRow);

        nightBtn = btn(nightLabel());
        nightBtn.setOnClickListener(v -> toggleNight());
        LinearLayout nrow = new LinearLayout(this);
        nrow.setOrientation(LinearLayout.HORIZONTAL);
        nrow.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), 0);
        nrow.addView(nightBtn, w());
        root.addView(nrow);

        // ---- history ----
        statsLine = txt(11, 0xFF8A8A99, false);
        statsLine.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 2));
        root.addView(statsLine);

        histBox = new LinearLayout(this);
        histBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(this);
        sc.addView(histBox);
        root.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout fRow = new LinearLayout(this);
        fRow.setOrientation(LinearLayout.HORIZONTAL);
        fRow.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 12));
        Button exp = btn("\uD83D\uDDC3 Export CSV");
        exp.setOnClickListener(v -> exportCsv());
        fRow.addView(exp, w());
        Button clr = btn("Clear log");
        clr.setOnClickListener(v -> confirmClear());
        fRow.addView(clr, w());
        root.addView(fRow);

        setContentView(root);
        GlassWindow.apply(this);
    }

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refreshStatus();
            refreshHistIfNeeded();
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onResume() { super.onResume(); refresher.run(); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresher); }

    // ---------- status ----------

    private void refreshStatus() {
        boolean on = AutoSpeedService.running;
        boolean testing = AutoSpeedService.testing || localTesting;
        if (testing) {
            statusLine.setText("\u25CC Testing now\u2026 (down then up, ~20 s)");
            statusLine.setTextColor(0xFFFFD166);
        } else if (on) {
            statusLine.setText("\u25CF Running \u00B7 every " + intervalLabel());
            statusLine.setTextColor(0xFF6EE7A0);
        } else {
            statusLine.setText("\u25CB Stopped");
            statusLine.setTextColor(0xFF8A8A99);
        }

        StringBuilder inf = new StringBuilder();
        if (AutoSpeedService.lastDoneTs > 0 && AutoSpeedService.lastDownX10 == 0) {
            inf.append("\u26A0 Last test FAILED");
            if (!AutoSpeedService.lastErr.isEmpty())
                inf.append(" \u2014 ").append(shortErr(AutoSpeedService.lastErr));
        } else if (AutoSpeedService.lastDoneTs > 0)
            inf.append("Last: \u2193 ").append(AutoSpeedService.lastDownX10 / 10.0)
               .append(" \u2191 ").append(AutoSpeedService.lastUpX10 / 10.0)
               .append(" Mbps \u00B7 ").append(df.format(new Date(AutoSpeedService.lastDoneTs)));
        if (on && AutoSpeedService.nextRunTs > 0 && !testing) {
            long s = Math.max(0, (AutoSpeedService.nextRunTs - System.currentTimeMillis()) / 1000);
            if (inf.length() > 0) inf.append("\n");
            inf.append("Next test in ").append(s / 60).append(":")
               .append(String.format(Locale.US, "%02d", s % 60));
        }
        if (inf.length() == 0 && !on)
            inf.append("Pick an interval and press Start \u2014 the first test runs immediately.");
        infoLine.setText(inf);
        startBtn.setText(on ? "Stop" : "Start");
        nowBtn.setEnabled(!testing);
        nowBtn.setAlpha(testing ? 0.4f : 1f);
    }

    private String intervalLabel() {
        long m = getSharedPreferences("netscanner", 0).getLong("auto_interval_min", 30L);
        return m >= 60 ? "1 hour" : m + " min";
    }

    // ---------- actions ----------

    private void toggleRun() {
        if (AutoSpeedService.running) {
            startService(new Intent(this, AutoSpeedService.class)
                    .setAction(AutoSpeedService.ACTION_STOP));
            Toast.makeText(this, "Auto speed stopped", Toast.LENGTH_SHORT).show();
        } else {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 43);
            androidx.core.content.ContextCompat.startForegroundService(this,
                    new Intent(this, AutoSpeedService.class));
            Toast.makeText(this, "Auto speed started \u2014 first test running",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void testNow() {
        if (localTesting || AutoSpeedService.testing) return;
        localTesting = true;
        final AutoSpeedActivity ctx = this;
        new Thread(() -> {
            long ts = System.currentTimeMillis();
            double down = com.netscanner.tools.SpeedTestRunner.downloadTest();
            String err = down > 0 ? "" : com.netscanner.tools.SpeedTestRunner.lastError;
            double up = down > 0 ? com.netscanner.tools.SpeedTestRunner.uploadTest() : 0;
            AutoSpeedService.saveResult(ctx, ts, down, up, err);
            AutoSpeedService.lastDoneTs = ts;
            AutoSpeedService.lastDownX10 = Math.round(down * 10);
            AutoSpeedService.lastUpX10 = Math.round(up * 10);
            AutoSpeedService.lastErr = err;
            final double fDown = down, fUp = up;
            runOnUiThread(() -> {
                localTesting = false;
                renderedThroughTs = -1;
                Toast.makeText(ctx, fDown > 0
                        ? "\u2193 " + r1(fDown) + " \u2191 " + r1(fUp) + " Mbps"
                        : "\u26A0 Test failed \u2014 " + shortErr(err), Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    /** First server error segment of a joined lastError string. */
    private static String shortErr(String s) {
        if (s == null) return "no data";
        int p = s.indexOf(" | ");
        String seg = p > 0 ? s.substring(0, p) : s;
        return seg.length() > 110 ? seg.substring(0, 110) : seg;
    }

    private String nightLabel() {
        boolean on = getSharedPreferences("netscanner", 0).getBoolean("night_tests", false);
        return "\uD83C\uDF19 2 AM nightly test: " + (on ? "ON" : "OFF");
    }

    private void toggleNight() {
        boolean on = !getSharedPreferences("netscanner", 0).getBoolean("night_tests", false);
        getSharedPreferences("netscanner", 0).edit().putBoolean("night_tests", on).apply();
        androidx.work.WorkManager wm = androidx.work.WorkManager.getInstance(this);
        if (on) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.add(java.util.Calendar.DAY_OF_YEAR, 1);
            c.set(java.util.Calendar.HOUR_OF_DAY, 2);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            androidx.work.PeriodicWorkRequest req =
                    new androidx.work.PeriodicWorkRequest.Builder(
                            com.netscanner.tools.NightSpeedWorker.class, 24,
                            java.util.concurrent.TimeUnit.HOURS)
                            .setInitialDelay(c.getTimeInMillis() - System.currentTimeMillis(),
                                    java.util.concurrent.TimeUnit.MILLISECONDS)
                            .build();
            wm.enqueueUniquePeriodicWork("night_speed",
                    androidx.work.ExistingPeriodicWorkPolicy.UPDATE, req);
            Toast.makeText(this, "\uD83C\uDF19 Night tests ON \u2014 next run 2 AM",
                    Toast.LENGTH_LONG).show();
        } else {
            wm.cancelUniqueWork("night_speed");
            Toast.makeText(this, "Night tests OFF", Toast.LENGTH_SHORT).show();
        }
        nightBtn.setText(nightLabel());
    }

    // ---------- history ----------

    private JSONArray loadHist() {
        try {
            return new JSONArray(getSharedPreferences("netscanner", 0)
                    .getString("auto_hist", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void refreshHistIfNeeded() {
        JSONArray h = loadHist();
        long newest = 0;
        JSONObject first = h.optJSONObject(0);
        if (first != null) newest = first.optLong("ts");
        if (newest == renderedThroughTs) return;
        renderedThroughTs = newest;
        histBox.removeAllViews();

        int n = h.length(), fails = 0, good = 0;
        double sum = 0, best = -1, worst = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            JSONObject e = h.optJSONObject(i);
            if (e == null) continue;
            double d = e.optDouble("down", 0);
            if (d <= 0) { fails++; continue; }
            good++;
            sum += d;
            if (d > best) best = d;
            if (d < worst) worst = d;
        }
        statsLine.setText(n == 0 ? "No auto runs yet."
                : good + " runs \u00B7 avg \u2193 " + r1(sum / Math.max(1, good))
                + " \u00B7 best " + r1(best) + " \u00B7 worst " + r1(worst) + " Mbps"
                + (fails > 0 ? " \u00B7 " + fails + " failed" : ""));

        if (n == 0) {
            TextView empty = txt(12, 0xFF55556A, false);
            empty.setText("Start the loop above (or tap Test now) \u2014 "
                    + "every result lands here automatically.");
            empty.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), 0);
            histBox.addView(empty);
            return;
        }
        for (int i = 0; i < Math.min(n, DISPLAY_CAP); i++) {
            JSONObject e = h.optJSONObject(i);
            if (e == null) continue;
            String when = "?";
            try { when = df.format(new Date(e.getLong("ts"))); } catch (Exception ignored) {}

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundColor(((i & 1) == 0) ? 0x00141420 : 0x14142000);
            row.setPadding(Ui.dp(this, 20), Ui.dp(this, 7), Ui.dp(this, 20), Ui.dp(this, 7));

            TextView tvWhen = txt(11, 0xFF77778C, false);
            tvWhen.setText(when);
            row.addView(tvWhen);

            double d = e.optDouble("down", 0);
            TextView tvVal = txt(13, d > 0 ? 0xFFEDEDF2 : 0xFFFF6B6B, true);
            if (d > 0) {
                tvVal.setText("\u2193 " + d + "   \u2191 " + e.optDouble("up", 0) + " Mbps");
            } else {
                String err = e.optString("err", "");
                tvVal.setText("\u26A0 Failed" + (err.isEmpty() ? "" : " \u2014 " + shortErr(err)));
            }
            tvVal.setPadding(0, Ui.dp(this, 1), 0, 0);
            row.addView(tvVal);

            View line = new View(this);
            line.setBackgroundColor(0x18FFFFFF);
            row.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));

            histBox.addView(row);
        }
        if (n > DISPLAY_CAP) {
            TextView more = txt(11, 0xFF55556A, false);
            more.setText("\u2026 plus " + (n - DISPLAY_CAP)
                    + " older runs (Export CSV includes everything)");
            more.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 12));
            histBox.addView(more);
        }
    }

    private void exportCsv() {
        JSONArray h = loadHist();
        if (h.length() == 0) {
            Toast.makeText(this, "no auto runs yet", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "share");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "netscanner_auto_speed.csv");
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            StringBuilder sb = new StringBuilder("timestamp,down_mbps,up_mbps\n");
            for (int i = 0; i < h.length(); i++) {
                JSONObject e = h.optJSONObject(i);
                if (e == null) continue;
                sb.append(iso.format(new Date(e.getLong("ts")))).append(',')
                  .append(e.optDouble("down", 0)).append(',')
                  .append(e.optDouble("up", 0)).append('\n');
            }
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(sb.toString());
            w.close();
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", f);
            Intent s = new Intent(Intent.ACTION_SEND).setType("text/csv")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "Export auto speed CSV"));
        } catch (Exception ex) {
            Toast.makeText(this, "export failed: " + ex, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmClear() {
        int n = loadHist().length();
        if (n == 0) {
            Toast.makeText(this, "log already empty", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear auto speed log?")
                .setMessage("Removes all " + n + " recorded auto runs. "
                        + "Manual Speed History stays untouched.")
                .setPositiveButton("Clear", (d, wh) -> {
                    getSharedPreferences("netscanner", 0).edit()
                            .putString("auto_hist", "[]").apply();
                    renderedThroughTs = -1;
                    refreshHistIfNeeded();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- helpers ----------

    private static String r1(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.glass_card);
        c.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), 0);
        parent.addView(c, lp);
        return c;
    }

    private TextView txt(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private Button btn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(0xFFEDEDF2);
        b.setBackgroundResource(R.drawable.btn_secondary);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams w() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        return lp;
    }
}
