package com.netscanner;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Tiny in-app ring buffer + crash-proof phase checkpoints. */
public final class AppLog {
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private AppLog() {}

    public static synchronized void log(String msg) {
        LINES.addLast(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + msg);
        while (LINES.size() > 400) LINES.removeFirst();
    }

    /** Log + synchronously persist phase marker — survives native crashes and OOM kills. */
    public static void cp(Context c, String phase) {
        log("phase: " + phase);
        try {
            c.getSharedPreferences("netscanner", 0)
                    .edit().putString("last_phase", phase).commit();
        } catch (Exception ignored) {}
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }
}
