package com.netscanner;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Guaranteed frosted-glass backdrop for NetScanner v4.1 screens (visual layer only).
 *
 * <p>Instead of depending on the system cross-window blur (which many OEM ROMs -
 * including ColorOS - disable, producing plain translucent boxes), this inserts an
 * in-window frost layer whose background is the device wallpaper, blurred with
 * {@link RenderEffect}. Blurring a bitmap in-app works on every ROM, so the frosted
 * glass look is guaranteed regardless of {@code isCrossWindowBlurEnabled()}.
 *
 * <p>The system window blur radius is still applied as an enhancement when the
 * platform reports cross-window blur support, but the frosted look never relies on it.
 *
 * <p>On API &lt; 31 this is a no-op: the theme provides the opaque aurora background.
 */
public final class GlassWindow {
    private static final String BACKDROP_TAG = "netscanner_glass_backdrop";
    private static final String WALL_TAG = "netscanner_wallpaper";
    private static final int WINDOW_BLUR_RADIUS = 80;      // px behind the window (enhancement)
    private static final float FROST_BLUR = 24f;           // px in-app frost

    private GlassWindow() {}

    /**
     * Call once per Activity, right after {@code setContentView(...)}.
     */
    public static void apply(Activity activity) {
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return; // fallback = theme bg

        ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
        if (content == null) return;

        boolean sysBlur = false;
        try {
            WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) sysBlur = wm.isCrossWindowBlurEnabled();
        } catch (Throwable ignored) {
        }

        // System blur as enhancement only; never relied upon for the glass look.
        window.setBackgroundBlurRadius(sysBlur ? WINDOW_BLUR_RADIUS : 0);
        window.setBackgroundDrawableResource(R.drawable.bg_root_translucent);

        FrameLayout frost = (FrameLayout) content.findViewWithTag(BACKDROP_TAG);
        if (frost == null) {
            frost = new FrameLayout(activity);
            frost.setTag(BACKDROP_TAG);

            View wallView = new View(activity);
            wallView.setTag(WALL_TAG);
            frost.addView(wallView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View tint = new View(activity);
            tint.setBackgroundResource(R.drawable.bg_root_translucent);
            frost.addView(tint, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            content.addView(frost, 0, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Blur the whole layer in-app -> guaranteed frost on any ROM.
            frost.setRenderEffect(RenderEffect.createBlurEffect(
                    FROST_BLUR, FROST_BLUR, Shader.TileMode.CLAMP));

            // Fill the wallpaper once layout is measured (center-crop, no distortion).
            content.post(new Runnable() {
                @Override public void run() {
                    View wv = frost.findViewWithTag(WALL_TAG);
                    if (wv == null || frost.getWidth() <= 0 || frost.getHeight() <= 0) return;
                    Bitmap bmp = loadWallpaper(activity);
                    if (bmp == null) return;
                    Bitmap cropped = centerCrop(bmp, frost.getWidth(), frost.getHeight());
                    if (cropped != null) {
                        wv.setBackground(new BitmapDrawable(activity.getResources(), cropped));
                    }
                }
            });
        }
    }

    private static Bitmap loadWallpaper(Activity activity) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(activity);
            Drawable d = wm.getDrawable();
            if (d == null) return null;
            if (d instanceof BitmapDrawable) {
                Bitmap b = ((BitmapDrawable) d).getBitmap();
                if (b != null && !b.isRecycled()) return b;
            }
            int w = Math.max(d.getIntrinsicWidth(), 64);
            int h = Math.max(d.getIntrinsicHeight(), 64);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap centerCrop(Bitmap src, int viewW, int viewH) {
        if (src == null || viewW <= 0 || viewH <= 0) return null;
        float scale = Math.max((float) viewW / src.getWidth(), (float) viewH / src.getHeight());
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        int dw = Math.max(1, Math.round(src.getWidth() * scale));
        int dh = Math.max(1, Math.round(src.getHeight() * scale));
        Bitmap scaled;
        try {
            scaled = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Throwable t) {
            scaled = Bitmap.createScaledBitmap(src, dw, dh, true);
        }
        int x = (dw - viewW) / 2;
        int y = (dh - viewH) / 2;
        x = Math.max(0, x);
        y = Math.max(0, y);
        int w = Math.min(viewW, dw - x);
        int h = Math.min(viewH, dh - y);
        try {
            return Bitmap.createBitmap(scaled, x, y, Math.max(1, w), Math.max(1, h));
        } catch (Throwable t) {
            return scaled;
        }
    }
}
