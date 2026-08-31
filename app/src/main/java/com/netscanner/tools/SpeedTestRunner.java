package com.netscanner.tools;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;

/**
 * Headless speed test engine — mirrors the in-app test (ToolRunnerActivity):
 * multi-server download fallback, User-Agent header, HTTP status check and a
 * readable {@link #lastError} when everything fails. Used by AutoSpeedService,
 * AutoSpeedActivity (⚡ Test now) and NightSpeedWorker.
 */
public final class SpeedTestRunner {

    private SpeedTestRunner() {}

    /** Reason the last fully-failed run produced no data ("" on success). */
    public static volatile String lastError = "";

    /** Error from the most recent individual server attempt (internal). */
    private static volatile String serverErr = "";

    private static final String UA = "Mozilla/5.0 (Linux; Android 16) NetScanner/3.9";

    private static final String[][] DL_SERVERS = {
            {"https://speed.cloudflare.com/__down?bytes=25000000", "Cloudflare"},
            {"https://nbg1-speed.hetzner.com/100MB.bin", "Hetzner"},
            {"https://proof.ovh.net/files/10Mb.dat", "OVH"},
            {"https://mirror.leaseweb.com/speedtest/1000mb.bin", "Leaseweb"},
    };

    private static final String[] UP_SERVERS = {
            "https://speed.cloudflare.com/__up",
            "https://httpbin.org/post",
            "https://postman-echo.com/post",
    };

    public static double downloadTest() {
        StringBuilder errs = new StringBuilder();
        for (String[] srv : DL_SERVERS) {
            serverErr = "";
            double mbps = dlFrom(srv[0], 10000);
            if (mbps > 0) { lastError = ""; return mbps; }
            if (errs.length() > 0) errs.append(" | ");
            errs.append(srv[1]).append(": ")
                .append(serverErr.isEmpty() ? "no data" : serverErr);
        }
        lastError = errs.toString();
        return 0;
    }

    public static double uploadTest() {
        StringBuilder errs = new StringBuilder();
        for (String up : UP_SERVERS) {
            serverErr = "";
            double mbps = upTo(up, 8000);
            if (mbps > 0) return mbps;
            if (errs.length() > 0) errs.append(" | ");
            errs.append(hostOf(up)).append(": ")
                .append(serverErr.isEmpty() ? "no data" : serverErr);
        }
        if (!errs.toString().isEmpty()) lastError = errs.toString();
        return 0;
    }

    private static String hostOf(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return url; }
    }

    private static double dlFrom(final String urlStr, long msDur) {
        final AtomicLong bytes = new AtomicLong();
        final AtomicReference<String> err = new AtomicReference<>(null);
        final long t0 = System.currentTimeMillis();
        final long deadline = t0 + msDur;
        Thread[] ts = new Thread[4];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                InputStream in = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpsURLConnection) new URL(urlStr).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("Accept-Encoding", "identity");
                    c.setRequestProperty("User-Agent", UA);
                    int code = c.getResponseCode();
                    if (code < 200 || code >= 300)
                        throw new java.io.IOException("HTTP " + code);
                    in = c.getInputStream();
                    byte[] b = new byte[65536];
                    int n;
                    while (System.currentTimeMillis() < deadline && (n = in.read(b)) > 0)
                        bytes.addAndGet(n);
                } catch (Exception e) {
                    err.compareAndSet(null, shortDesc(e));
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    if (c != null) c.disconnect();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) try { t.join(msDur + 5000L); } catch (InterruptedException ignored) {}
        if (bytes.get() == 0 && err.get() != null) serverErr = err.get();
        double secs = (System.currentTimeMillis() - t0) / 1000.0;
        if (secs < 0.5 || bytes.get() == 0) return 0;
        return bytes.get() * 8 / 1e6 / secs;
    }

    private static double upTo(final String urlStr, long msDur) {
        final AtomicLong bytes = new AtomicLong();
        final AtomicReference<String> err = new AtomicReference<>(null);
        final long t0 = System.currentTimeMillis();
        final long deadline = t0 + msDur;
        Thread[] ts = new Thread[2];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                OutputStream os = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpsURLConnection) new URL(urlStr).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(15000);
                    c.setDoOutput(true);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/octet-stream");
                    c.setRequestProperty("User-Agent", UA);
                    os = c.getOutputStream();
                    byte[] chunk = new byte[32768];
                    long sent = 0;
                    while (System.currentTimeMillis() < deadline && sent < 12_000_000L) {
                        os.write(chunk);
                        os.flush();                       // push into socket, keep buffers small
                        sent += chunk.length;
                        bytes.addAndGet(chunk.length);
                        if ((sent & 0x3FFFF) == 0) Thread.sleep(5); // let the wire drain
                    }
                    os.flush();
                    c.getResponseCode();
                } catch (Exception e) {
                    err.compareAndSet(null, shortDesc(e));
                } finally {
                    try { if (os != null) os.close(); } catch (Exception ignored) {}
                    if (c != null) c.disconnect();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) try { t.join(msDur + 5000L); } catch (InterruptedException ignored) {}
        if (err.get() != null) serverErr = err.get();
        double secs = (System.currentTimeMillis() - t0) / 1000.0;
        if (secs < 4 || bytes.get() == 0) return 0;
        return bytes.get() * 8 / 1e6 / secs;
    }

    /** Compact exception description for logs/toasts. */
    private static String shortDesc(Exception e) {
        String m = e.getMessage();
        String s = e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
