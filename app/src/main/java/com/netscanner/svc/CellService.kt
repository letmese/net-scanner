package com.netscanner.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import com.netscanner.MainActivity
import com.netscanner.R

/**
 * v5 Cell Monitor foreground service — listens to signal strength changes and
 * keeps a full (expanded) status-bar notification showing dBm, ASU, bars,
 * network tech and carrier. Parity of the legacy CellMonitorService.
 */
class CellService : android.app.Service() {

    private var tm: TelephonyManager? = null
    private var listener: PhoneStateListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        running = true
        listen()
        return START_STICKY
    }

    private fun listen() {
        if (listener != null) return
        tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        listener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(ss: SignalStrength?) {
                super.onSignalStrengthsChanged(ss)
                val dbm = cellDbm(ss)
                lastDbm = dbm
                lastAsu = ss?.getAsuLevel() ?: -1
                lastBars = barsOf(dbm)
                if (dbm != Integer.MAX_VALUE) updateNotif()
            }
        }
        try { tm?.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) } catch (_: Exception) {}
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
        running = false
        try { tm?.listen(listener, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) {}
        listener = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.netscanner.CELL_STOP"
        const val CHAN = "cellmon"
        const val NOTIF_ID = 42

        @JvmStatic @Volatile var running: Boolean = false
        @JvmStatic @Volatile var lastDbm: Int = Integer.MAX_VALUE
        @JvmStatic @Volatile var lastAsu: Int = -1
        @JvmStatic @Volatile var lastBars: Int = 0

        /** dBm from any radio tech; Integer.MAX_VALUE when unknown. */
        @JvmStatic
        fun cellDbm(ss: SignalStrength?): Int {
            if (ss == null) return Integer.MAX_VALUE
            return try {
                if (Build.VERSION.SDK_INT >= 29) ss.dbm
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
