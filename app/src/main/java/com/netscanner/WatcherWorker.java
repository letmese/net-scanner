package com.netscanner;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.netscanner.net.NetworkUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Periodic "who's on my Wi-Fi" watcher — notifies when unknown devices join. */
public class WatcherWorker extends Worker {

    public WatcherWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    @NonNull @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        android.content.SharedPreferences sp = ctx.getSharedPreferences("netscanner", 0);
        if (!sp.getBoolean("watch_enabled", false)) return Result.success();

        NetworkUtils.LocalNet n = NetworkUtils.localNet();
        if (n == null) return Result.retry();

        java.util.List<String> alive = NetworkUtils.sweep(n.prefix, null);
        Map<String, String> macs = NetworkUtils.neighborTable();
        Set<String> ips = new HashSet<>(alive);
        ips.addAll(macs.keySet());

        // current snapshot
        StringBuilder now = new StringBuilder();
        for (String ip : new java.util.TreeSet<>(ips)) {
            now.append(ip).append('=').append(macs.getOrDefault(ip, "-")).append(';');
        }
        String baseline = sp.getString("baseline", "");
        String knownVendors = sp.getString("known_names", "");

        if (baseline.isEmpty()) {
            // first run: record baseline silently
            sp.edit().putString("baseline", now.toString()).apply();
            return Result.success();
        }

        Set<String> oldIps = new HashSet<>();
        for (String e : baseline.split(";")) {
            int i = e.indexOf('=');
            if (i > 0) oldIps.add(e.substring(0, i));
        }

        StringBuilder fresh = new StringBuilder();
        int count = 0;
        for (String ip : new java.util.TreeSet<>(ips)) {
            if (!oldIps.contains(ip)) {
                Device d = new Device(ip);
                d.mac = macs.get(ip);
                String vendor = VendorDb.vendor(d.mac);
                fresh.append(ip).append(vendor != null ? " (" + vendor + ")" : "").append("\\n");
                count++;
            }
        }

        if (count > 0 && canNotify(ctx)) {
            notifyNew(ctx, count, fresh.toString().trim());
        }

        sp.edit().putString("baseline", now.toString()).apply();
        return Result.success();
    }

    private boolean canNotify(Context ctx) {
        if (Build.VERSION.SDK_INT >= 33)
            return ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void notifyNew(Context ctx, int count, String list) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel("watcher", "Wi-Fi Watch",
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(ch);

        Intent i = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Style style = count <= 5
                ? new NotificationCompat.InboxStyle().addLine(list.replace("\n", "\n"))
                : new NotificationCompat.BigTextStyle().bigText(list);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, "watcher")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("👀 New device on your Wi-Fi")
                .setContentText(count + " unknown device(s) joined the network")
                .setStyle(style)
                .setContentIntent(pi)
                .setAutoCancel(true);
        nm.notify(4242, b.build());
    }
}
