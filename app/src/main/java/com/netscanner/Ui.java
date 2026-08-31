package com.netscanner;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared programmatic "Liquid Glass" UI kit for the v4.1 screens. */
public final class Ui {
    private Ui() {}

    // ---- design tokens ----
    public static final int TEXT        = 0xFFF2F5FA;
    public static final int TEXT_DIM    = 0xFF93A0B4;
    public static final int TEXT_FAINT  = 0xFF5D6B82;
    public static final int ACCENT      = 0xFF4CC2FF;
    public static final int GOOD        = 0xFF34D399;
    public static final int WARN        = 0xFFFBBF24;
    public static final int BAD         = 0xFFFB7185;
    // frosted-glass additions (bright primary / secondary text for glass surfaces)
    public static final int TEXT_BRIGHT  = 0xFFFFFFFF;
    public static final int TEXT_MID     = 0xFFC6D0E0;
    public static final int ACCENT_GLOW  = 0xFF38BDF8;
    public static final int ACCENT_SOFT  = 0x334CC2FF;
    public static final int HAIRLINE     = 0x4D9FB6D0;
    public static final int GLASS_TINT   = 0x26FFFFFF;
    /** Category tints for icon chips (curated, harmonised on dark). */
    public static final int[] TINTS = {
            0xFF38BDF8, // sky
            0xFF60A5FA, // blue
            0xFF22D3EE, // cyan
            0xFF34D399, // emerald
            0xFF2DD4BF, // mint
            0xFFFBBF24, // amber
            0xFFFB923C, // orange
            0xFFA78BFA, // violet
            0xFFE879F9, // fuchsia
            0xFFFB7185, // rose
            0xFF94A3B8  // slate
    };

    public static LinearLayout root(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackgroundResource(R.drawable.bg_root);
        return r;
    }

    public static LinearLayout header(Context c, String title, LinearLayout parent) {
        return header(c, title, null, parent);
    }

    /** Nav bar: circular back chip + medium-weight title (+ optional subtitle line). */
    public static LinearLayout header(Context c, String title, String sub, LinearLayout parent) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(c, 16), dp(c, 112), dp(c, 16), dp(c, 12));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = new TextView(c);
        back.setText("\u2039");
        back.setTextSize(24);
        back.setTextColor(TEXT);
        back.setGravity(Gravity.CENTER);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0x2BFFFFFF);
        chipBg.setStroke(dp(c, 1), 0x33FFFFFF);
        chipBg.setCornerRadius(dp(c, 19));
        back.setBackground(chipBg);
        LinearLayout.LayoutParams bp =
                new LinearLayout.LayoutParams(dp(c, 38), dp(c, 38));
        bp.rightMargin = dp(c, 14);
        back.setLayoutParams(bp);
        back.setOnClickListener(v -> ((Activity) c).finish());
        row.addView(back);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(21);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        row.addView(t);
        bar.addView(row);

        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(c);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(TEXT_DIM);
            s.setPadding(dp(c, 52), dp(c, 3), 0, 0);
            bar.addView(s);
        }
        parent.addView(bar);
        return bar;
    }

    /** Frosted tinted chip that hosts a glyph — tile/row icon with glow layering + hairline. */
    public static TextView chip(Context c, String glyph, int tint, int sizeDp, float textSp) {
        TextView v = new TextView(c);
        v.setText(glyph);
        v.setTextSize(textSp);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setOrientation(GradientDrawable.Orientation.TL_BR);
        bg.setColors(new int[]{
                (tint & 0x00FFFFFF) | 0x4D000000, // top-left glow
                (tint & 0x00FFFFFF) | 0x2B000000, // base tint
                (tint & 0x00FFFFFF) | 0x17000000  // deeper bottom-right
        });
        bg.setCornerRadius(dp(c, sizeDp * 35 / 100));
        bg.setStroke(dp(c, 1), 0x38FFFFFF);
        v.setBackground(bg);
        return v;
    }

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }
}
