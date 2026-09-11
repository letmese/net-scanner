package com.netscanner.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.netscanner.MainActivity
import com.netscanner.R
import com.netscanner.core.SpeedTestRunner
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v5 Auto Speed Tests foreground loop — runs a full down+up test every
 * interval minutes, keeps per-run history in prefs ("auto_hist", cap 3000),
 * and shows the latest numbers in an ongoing notification. Parity of the
 * legacy AutoSpeedService, including the static state the UI reads.
 */
class AutoSpeedService : android.app.Service() {

    @Volatile private var stopFlag = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFlag = true
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        running = true
        if (worker?.isAlive != true) {
            stopFlag = false
            worker = Thread {
                try {
                    while (!stopFlag) {
                        runOnce(this)
                        nextRunTs = System.currentTimeMillis() + intervalMin(this) * 60_000L
                        // sleep in 1s slices so Stop reacts quickly
                        var slept = 0L
                        val target = nextRunTs - System.currentTimeMillis()
                        while (slept < target && !stopFlag) {
                            Thread.sleep(1000)
                            slept += 1000
                            updateNotif()
                        }
                    }
                } catch (_: InterruptedException) {
                } finally {
                    running = false
                }
            }.apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHAN, "Auto Speed Tests", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHAN) else Notification.Builder(this)
        b.setContentTitle("Auto speed running")
            .setContentText("Measuring now…")
            .setSmallIcon(R.drawable.ic_stat_net)
            .setOngoing(true)
            .setContentIntent(pi)
        startForeground(NOTIF_ID, b.build())
    }

    private fun updateNotif() {
        val s = StringBuilder()
        if (testing.get()) s.append("Testing now…")
        else if (lastDownX10 > 0) {
            s.append("↓ ").append(lastDownX10 / 10.0).append("  ↑ ")
                .append(lastUpX10 / 10.0).append(" Mbps")
        }
        if (nextRunTs > 0 && !testing.get()) {
            val sec = (nextRunTs - System.currentTimeMillis()) / 1000
            if (sec > 0) s.append(" · next in ").append(sec / 60).append(":")
                .append(String.format(Locale.US, "%02d", sec % 60))
        }
        val b = Notification.Builder(this, CHAN)
            .setContentTitle("Auto speed · every ${intervalMin(this)} min")
            .setContentText(s.toString())
            .setSmallIcon(R.drawable.ic_stat_net)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIF_ID, b.build()) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopFlag = true
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.netscanner.AUTO_SPEED_STOP"
        const val CHAN = "autospeed"
        const val NOTIF_ID = 43
        private const val HIST_CAP = 3000

        @JvmStatic @Volatile var running = false
        @JvmStatic val testing = java.util.concurrent.atomic.AtomicBoolean(false)
        @JvmStatic @Volatile var lastDoneTs: Long = 0
        @JvmStatic @Volatile var lastDownX10: Long = 0
        @JvmStatic @Volatile var lastUpX10: Long = 0
        @JvmStatic @Volatile var lastErr: String = ""
        @JvmStatic @Volatile var nextRunTs: Long = 0

        @JvmStatic
        fun intervalMin(ctx: Context): Long =
            ctx.getSharedPreferences("netscanner", 0).getLong("auto_interval_min", 30L)

        /** One down+up test; persists to auto_hist and updates the static state. */
        @JvmStatic
        fun runOnce(ctx: Context): Pair<Double, Double> {
            testing.set(true)
            val ts = System.currentTimeMillis()
            val down = SpeedTestRunner.downloadTest()
            val err = if (down > 0) "" else SpeedTestRunner.lastError
            val up = if (down > 0) SpeedTestRunner.uploadTest() else 0.0
            saveResult(ctx, ts, down, up, err)
            lastDoneTs = ts
            lastDownX10 = Math.round(down * 10)
            lastUpX10 = Math.round(up * 10)
            lastErr = err
            testing.set(false)
            return down to up
        }

        @JvmStatic
        fun saveResult(ctx: Context, ts: Long, down: Double, up: Double, err: String) {
            try {
                val p = ctx.getSharedPreferences("netscanner", 0)
                val arr = JSONArray(p.getString("auto_hist", "[]") ?: "[]")
                val o = JSONObject()
                o.put("ts", ts)
                o.put("down", down)
                o.put("up", up)
                if (err.isNotEmpty()) o.put("err", err)
                arr.put(o)
                while (arr.length() > HIST_CAP) arr.remove(0)
                p.edit().putString("auto_hist", arr.toString()).apply()
            } catch (_: Exception) {}
        }

        @JvmStatic
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AutoSpeedService::class.java))
        }

        @JvmStatic
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AutoSpeedService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** 2 AM nightly auto speed test (legacy NightSpeedWorker parity). */
class NightSpeedWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        AutoSpeedService.runOnce(applicationContext)
        return Result.success()
    }

    companion object {
        @JvmStatic
        fun iso(ts: Long): String =
            SimpleDateFormat("EEE MMM d, HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
