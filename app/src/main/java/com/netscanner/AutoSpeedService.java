package com.netscanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v3.9 periodic auto speed tests. Foreground service (specialUse) that runs
 * SpeedTestRunner immediately on start, then repeats every N minutes
 * (sp key "auto_interval_min", re-read each cycle so dropdown changes apply).
 * Each result is appended to sp "netscanner": "auto_hist" (full log, cap 3000,
 * shown by AutoSpeedActivity) and "speed_hist" (shared chart history, cap 100).
 * Stop via notification action or a START command carrying ACTION_STOP.
 */
public class AutoSpeedService extends Service {

    public static final String ACTION_STOP = "com.netscanner.autospeed.STOP";
    static final Object SAVE_LOCK = new Object();

    private static final String CH = "auto_speed";
    private static final int NOTIF_ID = 31;
    private static final int AUTO_CAP = 3000;

    /** Live state polled by AutoSpeedActivity. */
    public static volatile boolean running = false;
    public static volatile boolean testing = false;
    public static volatile long lastDoneTs = 0;
    public static volatile long lastDownX10 = 0, lastUpX10 = 0;
    public static volatile long nextRunTs = 0;
    /** Non-empty when the last run produced no data. */
    public static volatile String lastErr = "";

    private final Handler h = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CH, "Auto speed tests",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Periodic background speed tests");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (!startFg()) return START_NOT_STICKY;
        if (!running) {
            running = true;
            h.removeCallbacks(tick);
            tick.run();                       // first test fires immediately
        }
        return START_STICKY;
    }

    /** SDK>=34 requires explicit specialUse type; guarded like CellMonitorService. */
    private boolean startFg() {
        try {
            Notification n = build("starting\u2026");
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIF_ID, n);
            return true;
        } catch (Throwable e) {
            Toast.makeText(this, "auto speed service unavailable", Toast.LENGTH_SHORT).show();
            stopSelf();
            return false;
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            testing = true;
            nextRunTs = 0;
            notifyUi("test running\u2026");
            new Thread(() -> {
                long ts = System.currentTimeMillis();
                double down = com.netscanner.tools.SpeedTestRunner.downloadTest();
                String err = down > 0 ? "" : com.netscanner.tools.SpeedTestRunner.lastError;
                double up = down > 0 ? com.netscanner.tools.SpeedTestRunner.uploadTest() : 0;
                if (!running) return;         // stopped mid-test: drop partial result
                lastErr = err;
                saveResult(AutoSpeedService.this, ts, down, up, err);
                testing = false;
                lastDoneTs = ts;
                lastDownX10 = Math.round(down * 10);
                lastUpX10 = Math.round(up * 10);
                long iv = intervalMs();
                nextRunTs = System.currentTimeMillis() + iv;
                notifyUi(headline());
                h.postDelayed(tick, iv);      // Handler.postDelayed is thread-safe
            }).start();
        }
    };

    long intervalMs() {
        return getSharedPreferences("netscanner", 0)
                .getLong("auto_interval_min", 30L) * 60000L;
    }

    private String headline() {
        if (lastDoneTs <= 0) return "waiting for first result\u2026";
        if (lastDownX10 == 0)
            return "\u26A0 last test failed \u00B7 every " + (intervalMs() / 60000L) + " min";
        return "\u2193 " + (lastDownX10 / 10.0) + " \u2191 " + (lastUpX10 / 10.0)
                + " Mbps \u00B7 every " + (intervalMs() / 60000L) + " min";
    }

    private void notifyUi(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, build(text));
        } catch (Throwable ignored) {}
    }

    private Notification build(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, AutoSpeedActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, AutoSpeedService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Notification.Action stop = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi).build();
        return b.setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("\u23F1 NetScanner auto speed")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .addAction(stop)
                .build();
    }

    /** Appends one result to both histories. Thread-safe, usable from anywhere. */
    public static void saveResult(Context ctx, long ts, double down, double up) {
        saveResult(ctx, ts, down, up, "");
    }

    public static void saveResult(Context ctx, long ts, double down, double up, String err) {
        try {
            JSONObject e = new JSONObject();
            e.put("ts", ts);
            e.put("down", Math.round(down * 10) / 10.0);
            e.put("up", Math.round(up * 10) / 10.0);
            e.put("auto", true);
            if (err != null && !err.isEmpty())
                e.put("err", err.length() > 200 ? err.substring(0, 200) : err);
            SharedPreferences sp = ctx.getSharedPreferences("netscanner", 0);
            synchronized (SAVE_LOCK) {
                JSONArray auto = new JSONArray(sp.getString("auto_hist", "[]"));
                JSONArray outA = new JSONArray();
                outA.put(e);
                for (int i = 0; i < Math.min(auto.length(), AUTO_CAP - 1); i++) outA.put(auto.get(i));
                sp.edit().putString("auto_hist", outA.toString()).apply();

                JSONArray sh = new JSONArray(sp.getString("speed_hist", "[]"));
                JSONArray outS = new JSONArray();
                outS.put(e);
                for (int i = 0; i < Math.min(sh.length(), 99); i++) outS.put(sh.get(i));
                sp.edit().putString("speed_hist", outS.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    private void shutdown() {
        running = false;
        testing = false;
        nextRunTs = 0;
        h.removeCallbacksAndMessages(null);
        try { stopForeground(true); } catch (Throwable ignored) {}
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
