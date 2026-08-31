package com.netscanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import java.util.List;

/**
 * v3.7 status-bar dBm: low-importance ongoing notification ("cell_status" channel)
 * refreshed every 1 s from TelephonyManager.getAllCellInfo(), e.g.
 * "LTE -87 dBm · RSRP -95 · B3" (tech + dBm + best extra metric).
 * Foreground type specialUse; toggled from CellMonitorActivity (sp key "sb_dbm").
 */
public class CellMonitorService extends Service {

    private static final String CH = "cell_status";
    private static final int NOTIF_ID = 28;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private boolean fgOk;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CH, "Cell signal dBm",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Live cell signal strength in the status bar");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startFg();
        if (!fgOk) return START_NOT_STICKY;
        if (!running) {
            running = true;
            h.removeCallbacks(tick);
            tick.run();
        }
        return START_STICKY;
    }

    /** SDK>=34 requires the explicit specialUse type; both paths guarded. */
    private void startFg() {
        try {
            Notification n = build("Starting…");
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIF_ID, n);
            fgOk = true;
        } catch (SecurityException | android.app.ForegroundServiceStartNotAllowedException e) {
            fgOk = false;
            Toast.makeText(this, "status bar dBm unavailable", Toast.LENGTH_SHORT).show();
            stopSelf();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            String txt = brief();
            try {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIF_ID, build(txt));
            } catch (Throwable ignored) {}
            h.postDelayed(this, 1000);
        }
    };

    private Notification build(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, CellMonitorActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("\uD83D\uDCE1 NetScanner")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    /** "LTE -87 dBm · RSRP -95 · B3" style headline from the best registered cell. */
    private String brief() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            List<CellInfo> cells = tm.getAllCellInfo();
            CellInfo best = null;
            if (cells != null) for (CellInfo ci : cells)
                if (ci.isRegistered() && (best == null || rank(ci) > rank(best))) best = ci;
            if (best == null) return "no cell data";
            StringBuilder sb = new StringBuilder();
            if (best instanceof CellInfoLte) {
                CellIdentityLte id = ((CellInfoLte) best).getCellIdentity();
                CellSignalStrengthLte ss = ((CellInfoLte) best).getCellSignalStrength();
                sb.append("LTE ").append(dBm(ss.getDbm())).append(" dBm");
                if (ss.getRsrp() != Integer.MAX_VALUE) sb.append(" \u00B7 RSRP ").append(ss.getRsrp());
                int band = CellMonitorActivity.lteBand(id.getEarfcn());
                if (band > 0) sb.append(" \u00B7 B").append(band);
            } else if (Build.VERSION.SDK_INT >= 29 && best instanceof android.telephony.CellInfoNr) {
                android.telephony.CellIdentityNr id =
                        (android.telephony.CellIdentityNr) best.getCellIdentity();
                android.telephony.CellSignalStrengthNr ss =
                        (android.telephony.CellSignalStrengthNr) best.getCellSignalStrength();
                int rsrp = ss.getSsRsrp();
                sb.append("NR ").append(rsrp != Integer.MAX_VALUE ? rsrp : dBm(ss.getDbm())).append(" dBm");
                if (rsrp != Integer.MAX_VALUE && rsrp != ss.getDbm())
                    sb.append(" \u00B7 SS-RSRP ").append(rsrp);
                int band = CellMonitorActivity.nrBand(Build.VERSION.SDK_INT >= 30 ? id.getNrarfcn() : -1);
                if (band > 0) sb.append(" \u00B7 n").append(band);
            } else if (best instanceof android.telephony.CellInfoWcdma) {
                CellIdentityWcdma id = ((android.telephony.CellInfoWcdma) best).getCellIdentity();
                CellSignalStrengthWcdma ss = ((android.telephony.CellInfoWcdma) best).getCellSignalStrength();
                sb.append("WCDMA ").append(dBm(ss.getDbm()))
                        .append(" \u00B7 RSCP ").append(dBm(ss.getDbm()));
                int band = CellMonitorActivity.wcdmaBand(id.getUarfcn());
                if (band > 0) sb.append(" \u00B7 B").append(band);
            } else if (best instanceof CellInfoGsm) {
                CellIdentityGsm id = ((CellInfoGsm) best).getCellIdentity();
                CellSignalStrengthGsm ss = ((CellInfoGsm) best).getCellSignalStrength();
                sb.append("GSM ").append(dBm(ss.getDbm()))
                        .append(" \u00B7 RXL ").append(dBm(ss.getRssi()));
                if (!CellMonitorActivity.gsmBandName(id.getArfcn()).startsWith("ARFCN"))
                    sb.append(" \u00B7 ").append(CellMonitorActivity.gsmBandName(id.getArfcn()));
            } else if (best instanceof CellInfoCdma) {
                sb.append("CDMA ").append(dBm(((CellInfoCdma) best).getCellSignalStrength().getDbm()))
                        .append(" dBm");
            } else return "cell info unavailable";
            return sb.toString();
        } catch (Throwable t) {
            return "no cell data";
        }
    }

    private static String dBm(int v) { return v == Integer.MAX_VALUE ? "?" : String.valueOf(v); }

    private static int rank(CellInfo ci) {
        if (Build.VERSION.SDK_INT >= 29 && ci instanceof android.telephony.CellInfoNr) return 5;
        if (ci instanceof CellInfoLte) return 4;
        if (ci instanceof android.telephony.CellInfoWcdma) return 3;
        if (ci instanceof CellInfoGsm) return 2;
        return 1;
    }

    @Override public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        try { stopForeground(true); } catch (Throwable ignored) {}
        stopSelf();
        super.onDestroy();
    }
}
