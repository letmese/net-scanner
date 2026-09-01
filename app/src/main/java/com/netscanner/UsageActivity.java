package com.netscanner;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.TrafficStats;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class UsageActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.root(this);
        Ui.header(this, "📊 Data Usage", root);
        TextView tv = new TextView(this);
        tv.setTextColor(0xFFB9B9C9); tv.setTextSize(13);
        tv.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 20));
        ScrollView sc = new ScrollView(this); sc.addView(tv); root.addView(sc);
        setContentView(root);
        GlassWindow.apply(this);

        try {
            android.content.SharedPreferences sp = getSharedPreferences("netscanner", 0);
            String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            JSONObject snap = new JSONObject(sp.getString("usage_snap", "{}"));
            if (!sp.getString("usage_date", "").equals(today)) {
                snap = takeSnapshot();
                sp.edit().putString("usage_snap", snap.toString())
                        .putString("usage_date", today).apply();
            }
            PackageManager pm = getPackageManager();
            Map<Integer, String> names = new HashMap<>();
            for (PackageInfo pi : pm.getInstalledPackages(0))
                names.put(pi.applicationInfo.uid, pi.applicationInfo.loadLabel(pm).toString());

            List<long[]> rows = new ArrayList<>();
            long total = 0;
            Iterator<String> it = snap.keys();
            while (it.hasNext()) {
                String uidS = it.next();
                JSONObject o = snap.getJSONObject(uidS);
                int uid = Integer.parseInt(uidS);
                long dRx = TrafficStats.getUidRxBytes(uid) - o.getLong("rx");
                long dTx = TrafficStats.getUidTxBytes(uid) - o.getLong("tx");
                if (dRx < 0) dRx = 0;
                if (dTx < 0) dTx = 0;
                long d = dRx + dTx;
                if (d <= 0) continue;
                total += d;
                rows.add(new long[]{d, uid});
            }
            rows.sort((x, y) -> Long.compare(y[0], x[0]));
            StringBuilder sb = new StringBuilder();
            if (rows.isEmpty()) {
                sb.append("No usage recorded yet today.\n\nDeltas are measured from the first time you open this screen each day.");
            } else {
                sb.insert(0, "TOTAL TODAY: " + humanize(total) + "\n\n");
                int shown = 0;
                for (long[] r : rows) {
                    if (shown++ >= 25) break;
                    sb.append(String.format("%-26s %s\n",
                            names.getOrDefault((int) r[1], "uid " + r[1]), humanize(r[0])));
                }
            }
            tv.setText(sb.toString());
        } catch (Exception e) { tv.setText("Error: " + e); }
    }

    private JSONObject takeSnapshot() throws Exception {
        JSONObject snap = new JSONObject();
        PackageManager pm = getPackageManager();
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            int uid = pi.applicationInfo.uid;
            long rx = TrafficStats.getUidRxBytes(uid), tx = TrafficStats.getUidTxBytes(uid);
            if (rx < 0 && tx < 0) continue;
            if (rx + tx <= 0) continue;
            JSONObject o = new JSONObject();
            o.put("rx", rx); o.put("tx", tx);
            snap.put(String.valueOf(uid), o);
        }
        return snap;
    }

    static String humanize(long b2) {
        if (b2 >= 1024L * 1024 * 1024) return String.format("%.2f GB", b2 / 1e9);
        if (b2 >= 1024L * 1024) return String.format("%.1f MB", b2 / 1e6);
        if (b2 >= 1024) return String.format("%.1f KB", b2 / 1024.0);
        return b2 + " B";
    }
}
