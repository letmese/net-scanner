package com.netscanner.tools;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.netscanner.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

/** Runs a speed test at ~2 AM daily and appends to history. */
public class NightSpeedWorker extends Worker {

    public NightSpeedWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    @NonNull @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (!ctx.getSharedPreferences("netscanner", 0).getBoolean("night_tests", false))
            return Result.success();

        AppLog.log("night speed test running…");
        double down = SpeedTestRunner.downloadTest();
        double up = down > 0 ? SpeedTestRunner.uploadTest() : 0;
        AppLog.log("night speed: " + down + "/" + up + " Mbps"
                + (down > 0 ? "" : " — FAILED: " + SpeedTestRunner.lastError));

        try {
            org.json.JSONArray h = new org.json.JSONArray(ctx.getSharedPreferences("netscanner", 0)
                    .getString("speed_hist", "[]"));
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("down", Math.round(down * 10) / 10.0);
            e.put("up", Math.round(up * 10) / 10.0);
            e.put("night", true);
            JSONArray out = new JSONArray();
            out.put(e);
            for (int i = 0; i < Math.min(h.length(), 99); i++) out.put(h.get(i));
            ctx.getSharedPreferences("netscanner", 0).edit()
                    .putString("speed_hist", out.toString()).apply();
        } catch (Exception ignored) {}
        return Result.success();
    }
}
