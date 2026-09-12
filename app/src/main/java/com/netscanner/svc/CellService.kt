package com.netscanner.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.netscanner.MainActivity
import com.netscanner.R

/**
 * v5 Cell Monitor foreground service — samples signal strength at 1 Hz and
 * keeps a full (expanded) status-bar notification showing dBm, ASU, bars,
 * network tech and carrier. Parity of the legacy CellMonitorService.
 *
 * v5.1.2 fix: the service is now the SINGLE writer of [CellStore] — a 1 Hz
 * sampler thread reads `signalStrength` directly (getSignalStrength needs no
 * runtime permission, so samples flow even before the user grants phone
 * access) and appends every reading into the store the UI reads. The legacy
 * listener / API31+ TelephonyCallback are kept only for instant notification
 * refreshes. Lifecycle, first-sample and reflection fallbacks are logged.
 */
class CellService : android.app.Service() {

    private var tm: TelephonyManager? = null
    private var listener: PhoneStateListener? = null
    private var cb: TelephonyCallback? = null
    private var cbTm: TelephonyManager? = null
    private var sampler: Thread? = null

    /** Selected subscription id for per-SIM sampling; MIN_VALUE = default SIM. */
    @Volatile private var sampleSubId: Int = Int.MIN_VALUE
    private var lastNotifDbm = Int.MAX_VALUE
    private var lastNotifBars = -1
    private var lastNotifAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "stop requested — shutting down sampler")
            stopSelf()
            return START_NOT_STICKY
        }
        val sub = intent?.getIntExtra(EXTRA_SUB_ID, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (sub != Int.MIN_VALUE) sampleSubId = sub
        Log.d(TAG, "onStartCommand sub=${if (sampleSubId == Int.MIN_VALUE) "default" else sampleSubId} alreadyRunning=$running")
        startAsForeground()
        running = true
        listen()        // instant notification refresh only
        startSampler()  // the real 1 Hz data producer
        return START_STICKY
    }

    /**
     * v5.1.2: 1 Hz sampler — the single writer of CellStore. Reads
     * TelephonyManager.signalStrength directly; getSignalStrength() requires
     * no runtime permission, so this produces live data even with zero
     * permissions granted. Per-SIM devices sample the subscription chosen
     * in the UI via EXTRA_SUB_ID.
     */
    private fun startSampler() {
        if (sampler?.isAlive == true) return
        sampler = Thread {
            Log.d(TAG, "1 Hz sampler started (sub=${if (sampleSubId == Int.MIN_VALUE) "default" else sampleSubId})")
            var firstLogged = false
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    val m = if (sampleSubId != Int.MIN_VALUE) {
                        try { tm?.createForSubscriptionId(sampleSubId) } catch (_: Exception) { tm }
                    } else tm
                    val ss = try { m?.signalStrength } catch (_: Exception) { null }
                    val dbm = cellDbm(ss)
                    if (dbm != Integer.MAX_VALUE) {
                        val asu = try {
                            SignalStrength::class.java.getMethod("getAsuLevel").invoke(ss) as Int
                        } catch (_: Exception) { -1 }
                        val bars = try { ss?.level ?: 0 } catch (_: Exception) { 0 }
                        CellStore.appendSample(dbm, asu, bars, "service")
                        if (!firstLogged) {
                            Log.d(TAG, "first live sample: dbm=$dbm asu=$asu bars=$bars — signal source confirmed firing")
                            firstLogged = true
                        }
                        throttledNotif()
                    } else {
                        CellStore.setNoSource(
                            "sampler: signalStrength not readable yet (present=${ss != null})"
                        )
                    }
                } catch (e: Exception) {
                    CellStore.setNoSource("sampler error: ${e.message}")
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
            Log.d(TAG, "sampler exited")
        }.apply { name = "cell-sampler"; isDaemon = true; start() }
    }

    /** Notification refresh limited to bar changes or a 15 s cadence. */
    private fun throttledNotif() {
        val now = System.currentTimeMillis()
        if (lastDbm != lastNotifDbm || lastBars != lastNotifBars || now - lastNotifAt > 15_000) {
            lastNotifDbm = lastDbm
            lastNotifBars = lastBars
            lastNotifAt = now
            updateNotif()
        }
    }

    private fun hasPhonePerm(): Boolean =
        checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun listen() {
        if (listener != null || cb != null) return
        val mgr = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        tm = mgr

        // API 31+: public TelephonyCallback (works when READ_PHONE_STATE is
        // granted; not subject to the listen() silent-no-op behavior that
        // broke this service on modern Android).
        if (Build.VERSION.SDK_INT >= 31 && hasPhonePerm()) {
            val c = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(ss: SignalStrength) {
                    handleSignalStrength(ss)
                }
            }
            try {
                mgr.registerTelephonyCallback(mainExecutor, c)
                cb = c
                cbTm = mgr
                return
            } catch (_: Exception) {
                cb = null
                cbTm = null
            }
        }

        // Fallback: legacy listener (API < 31, or permission missing at 31+).
        listener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(ss: SignalStrength?) {
                handleSignalStrength(ss)
            }
        }
        try {
            mgr.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        } catch (_: Exception) {}
    }

    private fun handleSignalStrength(ss: SignalStrength?) {
        // v5.1.2: the 1 Hz sampler thread in startSampler() is the single
        // writer of CellStore. This callback path (TelephonyCallback /
        // PhoneStateListener) is kept ONLY for instant notification refresh —
        // writing store state from two producers duplicated samples.
        Log.d(TAG, "listener fired — refreshing notification")
        throttledNotif()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHAN, "Cell Monitor", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHAN) else Notification.Builder(this)
        b.setContentTitle("Signal monitor")
            .setContentText("Measuring cell signal…")
            .setSmallIcon(R.drawable.ic_stat_net)
            .setOngoing(true)
            .setContentIntent(pi)
        startForeground(NOTIF_ID, b.build())
    }

    private fun updateNotif() {
        val dbm = lastDbm
        if (dbm == Integer.MAX_VALUE) return
        val op = try {
            tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: "unknown"
        } catch (_: Exception) { "unknown" }
        val tech = try {
            networkTypeName(tm?.dataNetworkType ?: 0)
        } catch (_: Exception) { "?" }
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHAN) else Notification.Builder(this)
        val big = android.text.SpannableString(
            "$dbm dBm · ASU $lastAsu · ${barsStr(lastBars)}\n$op · $tech")
        b
            .setContentTitle("Signal: $dbm dBm ${barsStr(lastBars)}")
            .setContentText("$op · $tech · ASU $lastAsu")
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setSmallIcon(R.drawable.ic_stat_net)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIF_ID, b.build()) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — stopping sampler (samples=${CellStore.sampleCount})")
        running = false
        sampler?.interrupt()
        sampler = null
        val c = cb
        val m = cbTm
        if (c != null && m != null) {
            try { m.unregisterTelephonyCallback(c) } catch (_: Exception) {}
        }
        try { tm?.listen(listener, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) {}
        listener = null
        cb = null
        cbTm = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CellService"
        const val ACTION_STOP = "com.netscanner.CELL_STOP"

        /** EXTRA for per-SIM sampling: subscription id, or omit for default SIM. */
        const val EXTRA_SUB_ID = "com.netscanner.extra.SUB_ID"
        const val CHAN = "cellmon"
        const val NOTIF_ID = 42

        @JvmStatic @Volatile var running: Boolean = false

        // v5.1.2: the three scalars are now read-only views of the unified
        // CellStore (single 1 Hz writer). Keep the legacy names so existing
        // call sites keep compiling.
        @JvmStatic val lastDbm: Int get() = CellStore.lastDbm
        @JvmStatic val lastAsu: Int get() = CellStore.lastAsu
        @JvmStatic val lastBars: Int get() = CellStore.lastBars

        @Volatile private var loggedReflFail = false

        /** dBm from any radio tech; Integer.MAX_VALUE when unknown. */
        @JvmStatic
        fun cellDbm(ss: SignalStrength?): Int {
            if (ss == null) return Integer.MAX_VALUE
            return try {
                val refl = try {
                    SignalStrength::class.java.getMethod("getDbm").invoke(ss) as Int
                } catch (e: Exception) {
                    // v5.1.2: log once — silent reflection failure was one of
                    // the suspected dead-pipeline causes.
                    if (!loggedReflFail) {
                        Log.w(TAG, "getDbm reflection failed (${e.javaClass.simpleName}: ${e.message}); falling back to gsm/cdma decode")
                        loggedReflFail = true
                    }
                    Integer.MAX_VALUE
                }
                if (refl != Integer.MAX_VALUE && refl != 0) refl
                else {
                    val g = ss.gsmSignalStrength
                    if (g != 99) -113 + 2 * g
                    else if (ss.cdmaDbm > 0) ss.cdmaDbm
                    else ss.evdoDbm
                }
            } catch (_: Exception) { Integer.MAX_VALUE }
        }

        @JvmStatic fun barsOf(dbm: Int): Int = when {
            dbm >= -85 -> 4
            dbm >= -95 -> 3
            dbm >= -105 -> 2
            dbm != Integer.MAX_VALUE -> 1
            else -> 0
        }

        @JvmStatic fun barsStr(bars: Int): String =
            if (bars <= 0) "no signal" else "▮".repeat(bars) + "▯".repeat(4 - bars)

        @JvmStatic
        fun networkTypeName(t: Int): String = when (t) {
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            else -> "unknown"
        }
    }
}
