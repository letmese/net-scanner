package com.netscanner;

import android.app.Activity;
import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

/**
 * Real frosted-glass setup for the NetScanner v4.1 screens (visual layer only).
 *
 * <p>On Android 12+ (API 31+) this does two things, per AOSP window-blur guidelines:
 * <ol>
 *   <li><b>Window-level blur behind</b>: the window is translucent and a
 *       background blur radius of ~80px is applied so whatever sits behind the app
 *       (launcher / wallpaper) is actually blurred through the window background.</li>
 *   <li><b>In-window RenderEffect blur</b>: a full-screen aurora backdrop is inserted
 *       behind the activity content and blurred with {@link RenderEffect}, so the
 *       gradient behind the glass cards reads as real frosted glass while the cards
 *       themselves stay sharp.</li>
 * </ol>
 *
 * <p>Cross-window blur availability is queried with
 * {@link WindowManager#isCrossWindowBlurEnabled()}; when blur is disabled the
 * window background falls back to the opaque aurora so readability is preserved.
 * (The runtime listener for blur toggling is not part of the public SDK, so the
 * state is sampled on each {@link #apply} call.)
 *
 * <p>On API &lt; 31 this is a no-op: the theme provides the opaque aurora background.
 */
public final class GlassWindow {
    private static final String BACKDROP_TAG = "netscanner_glass_backdrop";
    private static final int WINDOW_BLUR_RADIUS = 80;      // px behind the window
    private static final float AURORA_BLUR = 26f;          // px in-window frost

    private GlassWindow() {}

    /**
     * Call once per Activity, right after {@code setContentView(...)}.
     */
    public static void apply(Activity activity) {
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window == null) return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return; // fallback = theme bg

        WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            applyState(activity, false);
            return;
        }

        boolean enabled = false;
        try {
            enabled = wm.isCrossWindowBlurEnabled();
        } catch (Throwable ignored) {
        }
        applyState(activity, enabled);
    }

    private static void applyState(Activity activity, boolean enabled) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Window window = activity.getWindow();
        ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
        if (window == null || content == null) return;

        // 1) Window-level blur: radius + translucent/opaque window background
        window.setBackgroundBlurRadius(enabled ? WINDOW_BLUR_RADIUS : 0);
        window.setBackgroundDrawableResource(
                enabled ? R.drawable.bg_root_translucent : R.drawable.bg_root);

        // 2) In-window frosted aurora backdrop behind the content.
        //    When cross-window blur is enabled we insert a translucent pure-aurora
        //    layer (no dark base) and blur it, so the wallpaper blurred behind the
        //    window still shows through the glass. When disabled we fall back to the
        //    opaque aurora (un-blurred) so readability is preserved.
        View backdrop = content.findViewWithTag(BACKDROP_TAG);
        if (backdrop == null) {
            backdrop = new View(activity);
            backdrop.setTag(BACKDROP_TAG);
            backdrop.setBackgroundResource(R.drawable.aurora_frost);
            content.addView(backdrop, 0,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                               ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (enabled) {
            backdrop.setBackgroundResource(R.drawable.aurora_frost);
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(
                    AURORA_BLUR, AURORA_BLUR, Shader.TileMode.CLAMP));
        } else {
            // Cross-window blur unavailable/disabled: opaque aurora, un-blurred,
            // window bg above becomes opaque for readability.
            backdrop.setBackgroundResource(R.drawable.bg_root);
            backdrop.setRenderEffect(null);
        }
    }
}
