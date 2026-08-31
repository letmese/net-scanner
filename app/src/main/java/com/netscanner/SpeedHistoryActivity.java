package com.netscanner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SpeedHistoryActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.root(this);
        Ui.header(this, "⚡ Speed History", root);
        TextView tv = new TextView(this);
        tv.setTextColor(0xFFB9B9C9); tv.setTextSize(14);
        tv.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 20));
        setContentView(root);

        // chart of download speeds (oldest -> newest)
        try {
            org.json.JSONArray h = new org.json.JSONArray(getSharedPreferences("netscanner", 0)
                    .getString("speed_hist", "[]"));
            if (h.length() >= 2) {
                float[] vals = new float[h.length()];
                for (int i = 0; i < h.length(); i++)
                    vals[h.length() - 1 - i] = (float) h.getJSONObject(i).getDouble("down");
                ChartView chart = new ChartView(this, vals);
                root.addView(chart, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 170)));
            }
        } catch (Exception ignored) {}

        ScrollView sc = new ScrollView(this); sc.addView(tv); root.addView(sc);

        StringBuilder sb = new StringBuilder();
        try {
            JSONArray h = new JSONArray(getSharedPreferences("netscanner", 0)
                    .getString("speed_hist", "[]"));
            if (h.length() == 0) sb.append("No tests yet — run a speed test first.");
            SimpleDateFormat f = new SimpleDateFormat("EEE MMM d, HH:mm", Locale.getDefault());
            for (int i = 0; i < h.length(); i++) {
                JSONObject e = h.getJSONObject(i);
                sb.append(f.format(new Date(e.getLong("ts"))))
                  .append("\n   ⬇ ").append(e.getDouble("down"))
                  .append(" Mbps    ⬆ ").append(e.optDouble("up", 0)).append(" Mbps\n\n");
            }
        } catch (Exception e) { sb.append("Error: ").append(e); }
        tv.setText(sb.toString());
    }
}

/** Minimal line chart of download speeds. */
class ChartView extends android.view.View {
    private final float[] vals;
    ChartView(Context c, float[] v) { super(c); vals = v; }

    @Override protected void onDraw(Canvas c) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF14142A);
        c.drawRect(0, 0, getWidth(), getHeight(), p);
        if (vals.length < 2) return;
        float max = vals[0], min = vals[0];
        for (float v : vals) { if (v > max) max = v; if (v < min) min = v; }
        if (max - min < 0.5f) max = min + 0.5f;
        float pad = 110;
        float w = getWidth() - pad * 2;
        float hgt = getHeight() - pad * 2;

        Paint grid = new Paint();
        grid.setColor(0x18FFFFFF); grid.setStrokeWidth(1);
        for (int i = 1; i < 4; i++) c.drawLine(pad, pad + hgt * i / 4f,
                pad + w, pad + hgt * i / 4f, grid);

        Path path = new Path();
        for (int i = 0; i < vals.length; i++) {
            float x = pad + w * i / (vals.length - 1);
            float y = pad + hgt * (1 - (vals[i] - min) / (max - min));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(0xFF4ADE80); line.setStrokeWidth(6); line.setStyle(Paint.Style.STROKE);
        c.drawPath(path, line);

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(pad + w, pad + hgt * (1 - (vals[vals.length - 1] - min) / (max - min)), 8, dot);

        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(0xFF8A8A99); txt.setTextSize(28);
        c.drawText(max + "", 10, 34, txt);
        c.drawText(min + "", 10, getHeight() - 10, txt);
        c.drawText("Mbps ↓ over time", pad + w - 320, 34, txt);
    }
}
