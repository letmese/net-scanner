package com.netscanner.tools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Simple live line chart of ping latencies. */
public class LatencyView extends View {

    private final ArrayDeque<Integer> samples = new ArrayDeque<>();
    private static final int MAX = 60;
    private final Paint line = new Paint();
    private final Paint grid = new Paint();
    private final Paint text = new Paint();

    public LatencyView(Context c, AttributeSet a) { super(c, a);
        line.setColor(Color.parseColor("#4ADE80"));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3f);
        line.setAntiAlias(true);
        grid.setColor(0xFF2C2C44);
        text.setColor(0xFF8A8A99);
        text.setTextSize(24f);
    }

    public void add(Integer ms) {
        if (samples.size() >= MAX) samples.pollFirst();
        samples.addLast(ms == null ? -1 : ms);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        for (int i = 1; i < 4; i++) canvas.drawLine(0, h * i / 4f, w, h * i / 4f, grid);

        List<Integer> s = new ArrayList<>(samples);
        if (s.isEmpty()) return;
        int maxMs = 20;
        for (Integer v : s) if (v > maxMs) maxMs = v;
        maxMs = Math.max(maxMs, 20);

        Path p = new Path();
        boolean started = false;
        for (int i = 0; i < s.size(); i++) {
            float x = w * i / (float) (MAX - 1);
            Integer v = s.get(i);
            if (v < 0) { started = false; continue; } // lost packet → break line
            float y = h - (v / (float) maxMs) * h;
            if (!started) { p.moveTo(x, y); started = true; }
            else p.lineTo(x, y);
        }
        canvas.drawPath(p, line);
        canvas.drawText(maxMs + " ms", 12, 34, text);
    }
}
