package com.netscanner;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.OverlayManager;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v3.7 "Cell Tower" — deep merge of NetMonster + Network Cell Info Lite.
 * Five tabs: Cells (dual-SIM serving + neighbors) · Gauges · Graph (live plot)
 * Log (change history + CSV) · Info (SIM/device/permissions).
 * Extras: LTE/NR frequency-MHz decode, EN-DC flag, per-SIM change log with
 * optional sound alert, persistent status-bar dBm notification (CellMonitorService).
 */
public class CellMonitorActivity extends AppCompatActivity {

    private static final int MAX_LOG = 400;
    private static final int MAX_SAMPLES = 240; // 4 min at 1 Hz

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final ArrayDeque<float[]> samples = new ArrayDeque<>(); // [sim0 dbm, sim1 dbm]
    private volatile boolean running;
    private long tickCount;
    private List<SimCtx> sims = new ArrayList<>();
    private int gaugeSel = 0;
    private boolean dualReal; // both sims parsed AND reports differ

    // ---- views
    private Button[] tabBtns;
    private LinearLayout[] tabPages;
    private final ScrollView[] tabScrolls = new ScrollView[6];
    private TextView[] simHead = new TextView[2];
    private TextView[] simDetail = new TextView[2];
    private TextView nbBox;
    private TextView[] gaugeCards = new TextView[2];
    private Button[] gaugeChips = new Button[2];
    private SignalGraphView graphView;
    private TextView legend;
    private TextView logBox, infoBox;
    private Button btnAlert, btnStatus;
    private ToneGenerator toneGen;
    private long lastBeepAt;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.root(this);
        Ui.header(this, "\uD83D\uDCE1 Cell Tower", root);

        buildTabStrip(root);
        buildToggleRow(root);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        tabPages = new LinearLayout[6];
        tabPages[0] = page(content, 0); buildCellsTab(tabPages[0]);
        tabPages[1] = page(content, 1); buildGaugesTab(tabPages[1]);
        tabPages[2] = page(content, 2); buildGraphTab(tabPages[2]);
        tabPages[3] = page(content, 3); buildLogTab(tabPages[3]);
        tabPages[4] = page(content, 4); buildInfoTab(tabPages[4]);
        tabPages[5] = page(content, 5); buildMapTab(tabPages[5]);
        setContentView(root);
        selectTab(0);

        loadPersistedLog();
        refreshLogView();
        requestPerms();
        resolveSims();
    }

    // ---------- tab strip ----------

    private static final String[] TABS = {"\uD83D\uDCE1 Cells", "\uD83D\uDCCA Gauges",
            "\uD83D\uDCC8 Graph", "\uD83D\uDDD4 Log", "\u2139\uFE0F Info", "\uD83D\uDDFA\uFE0F Map"};

    private void buildTabStrip(LinearLayout root) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), 0);
        tabBtns = new Button[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            Button t = new Button(this);
            t.setText(TABS[i]);
            t.setTextSize(12);
            t.setAllCaps(false);
            t.setBackgroundResource(R.drawable.pill_bg);
            row.addView(t, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            ((LinearLayout.LayoutParams) t.getLayoutParams()).rightMargin = Ui.dp(this, 6);
            t.setOnClickListener(v -> selectTab(idx));
            tabBtns[i] = t;
        }
        hs.addView(row);
        root.addView(hs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void selectTab(int idx) {
        for (int i = 0; i < TABS.length; i++) {
            tabBtns[i].setBackgroundResource(i == idx ? R.drawable.pill_active_bg : R.drawable.pill_bg);
            tabScrolls[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        if (idx == 2 && graphView != null) graphView.invalidate();
        if (idx == 5) { refreshMapMarkers(); locateAndPin(false); }
    }

    private void buildToggleRow(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), 0);
        btnStatus = pill("\uD83D\uDCCC Status-bar dBm");
        btnStatus.setOnClickListener(v -> toggleStatusNotif());
        row.addView(btnStatus, weight());
        btnAlert = pill("\uD83D\uDD14 Change alert");
        btnAlert.setOnClickListener(v -> toggleAlert());
        row.addView(btnAlert, weight());
        root.addView(row);
        refreshToggles();
    }

    private void refreshToggles() {
        boolean alert = getSharedPreferences("netscanner", 0).getBoolean("cell_change_sound", false);
        boolean stat = getSharedPreferences("netscanner", 0).getBoolean("sb_dbm", false);
        btnAlert.setText(alert ? "\uD83D\uDD14 Alert ON" : "\uD83D\uDD14 Alert OFF");
        btnAlert.setBackgroundResource(alert ? R.drawable.pill_active_bg : R.drawable.pill_bg);
        btnStatus.setText(stat ? "\uD83D\uDCCC Status dBm ON" : "\uD83D\uDCCC Status dBm OFF");
        btnStatus.setBackgroundResource(stat ? R.drawable.pill_active_bg : R.drawable.pill_bg);
    }

    private void toggleAlert() {
        boolean cur = getSharedPreferences("netscanner", 0).getBoolean("cell_change_sound", false);
        getSharedPreferences("netscanner", 0).edit().putBoolean("cell_change_sound", !cur).apply();
        refreshToggles();
    }

    private void toggleStatusNotif() {
        boolean cur = getSharedPreferences("netscanner", 0).getBoolean("sb_dbm", false);
        boolean next = !cur;
        getSharedPreferences("netscanner", 0).edit().putBoolean("sb_dbm", next).apply();
        if (next) startForegroundService(new Intent(this, CellMonitorService.class));
        else stopService(new Intent(this, CellMonitorService.class));
        refreshToggles();
    }

    private LinearLayout page(FrameLayout parent, int idx) {
        ScrollView sc = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 24));
        sc.addView(body);
        sc.setVisibility(View.GONE);
        tabScrolls[idx] = sc;
        parent.addView(sc, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return body;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = Ui.dp(this, 6);
        return p;
    }

    // ---------- Cells tab ----------

    private void buildCellsTab(LinearLayout body) {
        for (int i = 0; i < 2; i++) {
            final int si = i;
            simHead[i] = new TextView(this);
            simHead[i].setTextSize(19);
            simHead[i].setTypeface(null, android.graphics.Typeface.BOLD);
            simHead[i].setGravity(Gravity.CENTER);
            simHead[i].setPadding(0, Ui.dp(this, i == 0 ? 10 : 18), 0, Ui.dp(this, 6));
            simHead[i].setOnClickListener(v -> { gaugeSel = si; syncGaugeSel(); });
            body.addView(simHead[i]);
            simDetail[i] = card(body, 13);
        }
        section(body, "NEIGHBOR CELLS");
        nbBox = card(body, 13);
    }

    // ---------- Gauges tab ----------

    private void buildGaugesTab(LinearLayout body) {
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, Ui.dp(this, 10), 0, 0);
        for (int i = 0; i < 2; i++) {
            final int si = i;
            gaugeChips[i] = pill("SIM " + (i + 1));
            gaugeChips[i].setOnClickListener(v -> { gaugeSel = si; syncGaugeSel(); });
            chips.addView(gaugeChips[i], weight());
        }
        body.addView(chips);
        for (int i = 0; i < 2; i++) {
            gaugeCards[i] = card(body, 17);
            ((TextView) gaugeCards[i]).setTypeface(android.graphics.Typeface.MONOSPACE);
            ((TextView) gaugeCards[i]).setLineSpacing(Ui.dp(this, 4), 1f);
        }
        section(body, "HOW TO READ");
        TextView hint = card(body, 12);
        hint.setTextColor(0xFF9A9AAE);
        hint.setText("RSRP  ≥ −80 Excellent · ≥ −90 Good · ≥ −100 Fair · else Poor\n"
                + "RSRQ  ≥ −10 Excellent · ≥ −15 Good · ≥ −20 Fair\n"
                + "SNR   ≥ 20 Excellent · ≥ 13 Good · ≥ 0 Fair\n"
                + "Bars fill left→right toward the better end of each scale.");
        syncGaugeSel();
    }

    private void syncGaugeSel() {
        boolean two = sims.size() > 1;
        gaugeChips[1].setVisibility(two ? View.VISIBLE : View.GONE);
        if (!two) gaugeSel = 0;
        for (int i = 0; i < 2; i++) {
            gaugeChips[i].setBackgroundResource(
                    i == gaugeSel ? R.drawable.pill_active_bg : R.drawable.pill_bg);
            gaugeCards[i].setVisibility(i == gaugeSel ? View.VISIBLE : View.GONE);
        }
    }

    // ---------- Graph tab ----------

    private void buildGraphTab(LinearLayout body) {
        legend = new TextView(this);
        legend.setTextSize(13);
        legend.setTypeface(android.graphics.Typeface.MONOSPACE);
        legend.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 6));
        body.addView(legend);
        graphView = new SignalGraphView(this);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 230));
        gp.leftMargin = Ui.dp(this, 2); gp.rightMargin = Ui.dp(this, 2);
        body.addView(graphView, gp);
        section(body, "NOTES");
        TextView n = card(body, 12);
        n.setTextColor(0xFF9A9AAE);
        n.setText("Rolling 4-minute plot sampled every second — one line per active SIM.\n"
                + "Orange = SIM 1 · Cyan = SIM 2. Higher on the chart = stronger signal.\n"
                + "Sampling continues while other tabs are open.");
    }

    /** Canvas line chart of rolling dBm samples, fixed −130..−40 scale. */
    private class SignalGraphView extends View {
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint l1 = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint l2 = new Paint(Paint.ANTI_ALIAS_FLAG);

        SignalGraphView(android.content.Context c) {
            super(c);
            grid.setColor(0x22FFFFFF); grid.setStrokeWidth(1f);
            label.setColor(0xFF7A7A8E); label.setTextSize(20f);
            l1.setColor(0xFFFF6B4A); l1.setStrokeWidth(4f); l1.setStyle(Paint.Style.STROKE);
            l2.setColor(0xFF38BDF8); l2.setStrokeWidth(4f); l2.setStyle(Paint.Style.STROKE);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float padL = Ui.dp(getContext(), 34), padB = Ui.dp(getContext(), 16);
            float top = 0, bot = h - padB;
            cv.drawColor(0x14000000);
            for (int dbm = -130; dbm <= -40; dbm += 10) {
                float y = yFor(dbm, top, bot);
                cv.drawLine(padL, y, w, y, grid);
                cv.drawText(String.valueOf(dbm), 0, y + 6f, label);
            }
            synchronized (samples) {
                int n = samples.size();
                if (n < 2) return;
                drawSeries(cv, samples, 0, l1, padL, w, top, bot);
                if (dualReal || hasSecond()) drawSeries(cv, samples, 1, l2, padL, w, top, bot);
            }
        }

        private boolean hasSecond() {
            for (float[] s : samples) if (s[1] > -900) return true;
            return false;
        }

        private void drawSeries(Canvas cv, ArrayDeque<float[]> dq, int ch, Paint p,
                                float padL, float w, float top, float bot) {
            int n = dq.size();
            float step = (w - padL) / (MAX_SAMPLES - 1);
            int skip = MAX_SAMPLES - n;
            float px = padL + skip * step, py = Float.NaN;
            for (float[] s : dq) {
                float v = s[ch];
                float nx = px + step;
                if (v > -900) {
                    float ny = yFor(v, top, bot);
                    if (!Float.isNaN(py)) cv.drawLine(px, py, nx, ny, p);
                    py = ny;
                } else py = Float.NaN;
                px = nx;
            }
        }

        private float yFor(float dbm, float top, float bot) {
            float f = (dbm + 130f) / 90f; // -130..-40 → 0..1
            return bot - Math.max(0, Math.min(1, f)) * (bot - top);
        }
    }

    // ---------- Log tab ----------

    private void buildLogTab(LinearLayout body) {
        section(body, "CELL CHANGE LOG");
        logBox = card(body, 11);
        logBox.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        Button csv = btn("\u2B07 Export CSV");
        csv.setOnClickListener(v -> exportCsv());
        row.addView(csv, weight());
        Button kml = btn("\uD83D\uDDFA Export KML");
        kml.setOnClickListener(v -> exportKml());
        row.addView(kml, weight());
        Button clr = btn("Clear");
        clr.setOnClickListener(v -> {
            synchronized (logLines) { logLines.clear(); persistLog(); refreshLogView(); }
        });
        row.addView(clr, weight());
        body.addView(row);
        refreshLogView();
    }

    // ---------- Info tab ----------

    private void buildInfoTab(LinearLayout body) {
        section(body, "SIMULATIONS & DEVICE");
        infoBox = card(body, 13);
        infoBox.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    // ---------- Map tab (v3.8 "My Towers" — zero-key, own data only) ----------

    private MapView mapView;
    private TextView mapStats;
    private TextView mapDbg;
    private TextView mapEmpty;
    private Button styleBtn;
    private RawTileFetcher rawTiles;

    /** One unique tower parsed from geo-tagged cell_log entries. */
    private static class TowerHit {
        String key, tech, idSeg, carrier, dbm, lastTime;
        int count;
        double sumLat, sumLon;
        double avgLat() { return sumLat / Math.max(count, 1); }
        double avgLon() { return sumLon / Math.max(count, 1); }
    }

    /** Parse every "@lat,lon"-tagged log line; dedupe by tech+id, average fixes. */
    private List<TowerHit> geoTowers() {
        Map<String, TowerHit> byKey = new HashMap<>();
        synchronized (logLines) {
            for (String l : logLines) {
                Matcher m = GEO_P.matcher(l);
                if (!m.find()) continue;
                try {
                    double lat = Double.parseDouble(m.group(1));
                    double lon = Double.parseDouble(m.group(2));
                    if (lat == 0.0 && lon == 0.0) continue;
                    int arrow = l.indexOf("⤳ ");
                    if (arrow < 0) continue;
                    String rest = l.substring(arrow + 2).trim();
                    int sp1 = rest.indexOf(' ');
                    if (sp1 <= 0) continue;
                    String tech = rest.substring(0, sp1);
                    String tail = rest.substring(sp1 + 1);
                    int segEnd = tail.indexOf(" · ");
                    String idSeg = segEnd > 0 ? tail.substring(0, segEnd) : tail.trim();
                    String mid = segEnd > 0 ? tail.substring(segEnd + 3).trim() : "";
                    String carrier = mid, dbmTxt = "?";
                    int dBmAt = mid.indexOf(" dBm");
                    if (dBmAt > 0) {
                        carrier = mid.substring(0, dBmAt).trim();
                        dbmTxt = mid.substring(dBmAt + 1).trim();
                    }
                    String time = l.length() >= 8 ? l.substring(0, 8) : "";
                    String key = tech + "|" + idSeg;
                    TowerHit t = byKey.get(key);
                    if (t == null) {
                        t = new TowerHit();
                        t.key = key; t.tech = tech; t.idSeg = idSeg;
                        t.sumLat = lat; t.sumLon = lon; t.count = 0;
                        byKey.put(key, t);
                    }
                    t.sumLat += lat; t.sumLon += lon; t.count++;
                    t.carrier = carrier; t.dbm = dbmTxt; t.lastTime = time;
                } catch (NumberFormatException ignored) {}
            }
        }
        List<TowerHit> out = new ArrayList<>(byKey.values());
        Collections.sort(out, (a, b) -> Integer.compare(b.count, a.count));
        return out;
    }

    private void buildMapTab(LinearLayout body) {
        Configuration.getInstance().setUserAgentValue("NetScanner/3.9 (Android)");
        File tileCache = new File(getCacheDir(), "osmdroid");
        tileCache.mkdirs();
        Configuration.getInstance().setOsmdroidBasePath(tileCache);
        Configuration.getInstance().setOsmdroidTileCache(tileCache);

        mapStats = card(body, 13);
        mapStats.setTypeface(android.graphics.Typeface.MONOSPACE);

        // live tile-loader diagnostics — failures are never invisible now
        mapDbg = new TextView(this);
        mapDbg.setTextSize(10);
        mapDbg.setTextColor(0xFF6E6E82);
        mapDbg.setTypeface(android.graphics.Typeface.MONOSPACE);
        mapDbg.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), 0);
        mapDbg.setText("tiles: waiting…");
        body.addView(mapDbg);

        // action row: instant GPS pin + tile style toggle
        LinearLayout mrow = new LinearLayout(this);
        mrow.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(mrow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        Button locateBtn = pill("\uD83D\uDCCD Locate now");
        locateBtn.setOnClickListener(v -> locateAndPin(true));
        mrow.addView(locateBtn, weight());
        styleBtn = pill("");
        styleBtn.setOnClickListener(v -> {
            boolean dark = !getSharedPreferences("netscanner", 0)
                    .getBoolean("map_style_dark", true);
            getSharedPreferences("netscanner", 0).edit()
                    .putBoolean("map_style_dark", dark).apply();
            applyMapStyle();
        });
        mrow.addView(styleBtn, weight());

        mapEmpty = new TextView(this);
        mapEmpty.setTextSize(13);
        mapEmpty.setTextColor(0xFF9A9AAE);
        mapEmpty.setBackgroundResource(R.drawable.glass_card);
        mapEmpty.setPadding(Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18));
        mapEmpty.setText("No located cells yet.\n\nKeep Location ON and carry the phone — every cell change while GPS has a fix drops a pin here automatically. Or tap \uD83D\uDCCD Locate now to pin your current cell instantly.");
        body.addView(mapEmpty);

        // Replace osmdroid's ENTIRE tile-download pipeline with our own
        // direct-HTTP fetcher. osmdroid's stock chain (network check, IO
        // facade, sqlite writer) fails silently into an empty grid on some
        // modern Android builds — this path uses the same plain HTTPS code
        // that our speed tests prove works, with host fallback + diagnostics.
        boolean dark0 = getSharedPreferences("netscanner", 0)
                .getBoolean("map_style_dark", true);
        rawTiles = new RawTileFetcher(handler, (ok, fail, err) -> {
            if (mapDbg != null)
                mapDbg.setText("tiles \u2713" + ok + " \u2717" + fail
                        + (err.isEmpty() ? "" : " \u00B7 "
                           + (err.length() > 90 ? err.substring(0, 90) : err)));
        });
        rawTiles.setStyle(dark0);
        org.osmdroid.tileprovider.MapTileProviderArray provider =
                new org.osmdroid.tileprovider.MapTileProviderArray(
                        TileSourceFactory.MAPNIK,
                        new org.osmdroid.tileprovider.util.SimpleRegisterReceiver(this),
                        new org.osmdroid.tileprovider.modules.MapTileModuleProviderBase[]{rawTiles}) {
                    @Override protected boolean isDowngradedMode() { return false; }
                    @Override protected boolean isDowngradedMode(long i) { return false; }
                };
        mapView = new MapView(this, provider);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.0);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 340));
        mp.topMargin = Ui.dp(this, 10);
        mapView.setLayoutParams(mp);
        body.addView(mapView);
        // stop the outer ScrollView from stealing vertical map drags
        mapView.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                case android.view.MotionEvent.ACTION_MOVE:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                default:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });

        TextView hint = new TextView(this);
        hint.setText("Tiles © OpenStreetMap contributors · Dark style © CARTO — pins = towers YOUR phone logged with a GPS fix. No external cell database.");
        hint.setTextSize(11);
        hint.setTextColor(0xFF6E6E82);
        hint.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), 0);
        body.addView(hint);

        applyMapStyle();
        refreshMapMarkers();
    }

    private void applyMapStyle() {
        if (mapView == null || rawTiles == null) return;
        boolean dark = getSharedPreferences("netscanner", 0)
                .getBoolean("map_style_dark", true);
        try { rawTiles.setStyle(dark); } catch (Throwable ignored) {}
        if (styleBtn != null)
            styleBtn.setText(dark ? "\uD83C\uDFA8 Dark" : "\u2600 Light");
        mapView.invalidate();
    }

    /** CARTO dark basemap (fastly CDN) — matches the app theme and gives an
     * alternate tile host in case OSM itself is slow/blocked. */
    private org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase cartoDark() {
        return new org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
                "CARTO Dark", 0, 19, 256, ".png",
                new String[]{"https://a.basemaps.cartocdn.com",
                        "https://b.basemaps.cartocdn.com",
                        "https://c.basemaps.cartocdn.com"}) {
            @Override
            public String getTileURLString(long pMapTileIndex) {
                return getBaseUrl()
                        + "/" + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
                        + "/" + org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
                        + "/" + org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) + ".png";
            }
        };
    }

    /**
     * Minimal direct-HTTP tile fetcher — replaces osmdroid's downloader chain
     * (network availability check, IO facade, sqlite writer) which can fail
     * silently into an empty grid on modern Android. Plain HTTPS, host
     * fallback per tile, success/failure counters surfaced in the UI.
     */
    private static final class RawTileFetcher extends
            org.osmdroid.tileprovider.modules.MapTileModuleProviderBase {

        interface Diag { void onStat(int ok, int fail, String lastErr); }

        private volatile String[] templates = new String[0];
        private volatile Diag diag;
        private final android.os.Handler ui;
        private int okCount = 0, failCount = 0;
        private volatile String lastErr = "";

        RawTileFetcher(android.os.Handler uiHandler, Diag d) {
            super(4, 16);
            ui = uiHandler;
            diag = d;
        }

        void setStyle(boolean dark) {
            templates = dark
                    ? new String[]{"https://a.basemaps.cartocdn.com/dark_all",
                                   "https://b.basemaps.cartocdn.com/dark_all",
                                   "https://c.basemaps.cartocdn.com/dark_all"}
                    : new String[]{"https://tile.openstreetmap.org",
                                   "https://a.tile.openstreetmap.org"};
            okCount = 0;
            failCount = 0;
            lastErr = "";
        }

        @Override protected String getName() { return "RawHTTP tiles"; }
        @Override protected String getThreadGroupName() { return "rawhttp"; }
        @Override public boolean getUsesDataConnection() { return true; }
        @Override public int getMinimumZoomLevel() { return 0; }
        @Override public int getMaximumZoomLevel() { return 19; }
        @Override public void setTileSource(
                org.osmdroid.tileprovider.tilesource.ITileSource s) { /* style via setStyle */ }

        @Override public org.osmdroid.tileprovider.modules.MapTileModuleProviderBase.TileLoader
                getTileLoader() {
            return new TileLoader() {
                @Override public Drawable loadTile(final long idx) {
                    final int z = org.osmdroid.util.MapTileIndex.getZoom(idx);
                    final int x = org.osmdroid.util.MapTileIndex.getX(idx);
                    final int y = org.osmdroid.util.MapTileIndex.getY(idx);
                    if (z < 0 || z > 19 || x < 0 || y < 0) return null;
                    final String[] ts = templates;
                    Exception err = null;
                    for (String t : ts) {
                        HttpURLConnection c = null;
                        try {
                            c = (HttpsURLConnection) new URL(
                                    t + "/" + z + "/" + x + "/" + y + ".png").openConnection();
                            c.setConnectTimeout(6000);
                            c.setReadTimeout(12000);
                            c.setRequestProperty("User-Agent",
                                    "Mozilla/5.0 (Linux; Android 16) NetScanner/3.9");
                            if (c.getResponseCode() != 200) {
                                err = new java.io.IOException("HTTP " + c.getResponseCode());
                                continue;
                            }
                            android.graphics.Bitmap bmp = android.graphics.BitmapFactory
                                    .decodeStream(c.getInputStream());
                            if (bmp == null) { err = new java.io.IOException("bad image"); continue; }
                            okCount++;
                            fire("");
                            return new org.osmdroid.tileprovider.ExpirableBitmapDrawable(bmp);
                        } catch (Exception e) {
                            err = e;
                        } finally {
                            if (c != null) c.disconnect();
                        }
                    }
                    failCount++;
                    lastErr = err == null ? "unknown" : String.valueOf(err);
                    fire(lastErr);
                    return null;
                }
            };
        }

        private void fire(final String errNow) {
            final Diag d = diag;
            if (d == null || ui == null) return;
            ui.post(() -> d.onStat(okCount, failCount,
                    errNow.isEmpty() ? lastErr : errNow));
        }
    }

    /** Fresh location → pin the current serving cell. manual bypasses debounce. */
    private void locateAndPin(final boolean manual) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 42);
            if (manual) Toast.makeText(this,
                    "Grant location permission first", Toast.LENGTH_SHORT).show();
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        final boolean[] got = {false};
        android.location.LocationListener ll = new android.location.LocationListener() {
            @Override public void onLocationChanged(Location l) {
                synchronized (got) { if (got[0]) return; got[0] = true; }
                pinCurrentCell(l == null ? 0 : l.getLatitude(),
                        l == null ? 0 : l.getLongitude(), manual);
            }
            @Override public void onProviderDisabled(String p) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onStatusChanged(String p, int st, android.os.Bundle x) {}
        };
        boolean fired = false;
        for (final String prov : new String[]{LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!lm.isProviderEnabled(prov)) continue;
                if (Build.VERSION.SDK_INT >= 30)
                    lm.getCurrentLocation(prov, null, getMainExecutor(), loc -> {
                        synchronized (got) { if (got[0]) return; got[0] = true; }
                        pinCurrentCell(loc == null ? 0 : loc.getLatitude(),
                                loc == null ? 0 : loc.getLongitude(), manual);
                    });
                else
                    lm.requestSingleUpdate(prov, ll, Looper.getMainLooper());
                fired = true;
            } catch (Throwable ignored) {}
        }
        if (!fired) {   // nothing live — fall back to freshest cached fix
            double[] f = lastFix();
            if (f != null) pinCurrentCell(f[0], f[1], manual);
            else if (manual) Toast.makeText(this,
                    "No location providers available", Toast.LENGTH_SHORT).show();
        }
    }

    private void pinCurrentCell(double lat, double lon, boolean manual) {
        if ((lat == 0 && lon == 0)) { noFix(manual); return; }
        SimCtx s = sims.isEmpty() ? null : sims.get(0);
        if (s == null || s.serving == null) {
            if (manual) Toast.makeText(this,
                    "No serving cell right now", Toast.LENGTH_SHORT).show();
            return;
        }
        long now = System.currentTimeMillis();
        SharedPreferences sp = getSharedPreferences("netscanner", 0);
        String k = "visit_" + s.serving.identityKey.hashCode();
        if (!manual && now - sp.getLong(k, 0L) < 600000L) return;  // 10-min debounce
        sp.edit().putLong(k, now).apply();
        String lat6 = String.valueOf(Math.round(lat * 1e6) / 1e6);
        String lon6 = String.valueOf(Math.round(lon * 1e6) / 1e6);
        addLog(fmt.format(new Date()) + " [" + simTag(0) + "] \u2933 "
                + s.rat + " " + s.serving.shortId + " · "
                + carrierName(s.tm) + " · " + s.serving.primaryDbm + " dBm @"
                + lat6 + "," + lon6);
        if (tabPages[5] != null && tabPages[5].getVisibility() == View.VISIBLE)
            refreshMapMarkers();
        if (manual) Toast.makeText(this,
                "\uD83D\uDCCD Current cell pinned", Toast.LENGTH_SHORT).show();
    }

    private void noFix(boolean manual) {
        if (manual) Toast.makeText(this,
                "No GPS fix — try near a window", Toast.LENGTH_SHORT).show();
    }

    /** Rebuild pins + stats from the current log; called on tab entry. */
    private void refreshMapMarkers() {
        if (mapView == null || mapStats == null || mapEmpty == null) return;
        List<TowerHit> towers = geoTowers();
        int visits = 0;
        for (TowerHit t : towers) visits += t.count;
        boolean empty = towers.isEmpty();
        mapEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mapView.setVisibility(empty ? View.GONE : View.VISIBLE);
        mapStats.setText(empty ? "MY TOWERS"
                : towers.size() + " tower(s) · " + visits + " GPS-tagged visit(s)");
        if (empty) return;

        mapView.getOverlays().clear();
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (TowerHit t : towers) {
            Marker mk = new Marker(mapView);
            mk.setPosition(new GeoPoint(t.avgLat(), t.avgLon()));
            mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            try {
                mk.setIcon(new android.graphics.drawable.BitmapDrawable(getResources(), pinBitmap(t.tech)));
            } catch (Throwable ignored) {}
            mk.setTitle(t.tech + " · " + t.idSeg);
            mk.setSnippet(t.carrier + " · " + t.dbm + " · seen " + t.count
                    + "× · last " + t.lastTime);
            mapView.getOverlays().add(mk);
            minLat = Math.min(minLat, t.avgLat()); maxLat = Math.max(maxLat, t.avgLat());
            minLon = Math.min(minLon, t.avgLon()); maxLon = Math.max(maxLon, t.avgLon());
        }
        mapView.invalidate();
        try {
            org.osmdroid.util.BoundingBox box =
                    new org.osmdroid.util.BoundingBox(maxLat, maxLon, minLat, minLon);
            final double cLat = (minLat + maxLat) / 2, cLon = (minLon + maxLon) / 2;
            mapView.post(() -> {
                try {
                    if (towers.size() == 1)
                        mapView.getController().setZoom(17.0);
                    else mapView.zoomToBoundingBox(box, false, Ui.dp(CellMonitorActivity.this, 48));
                    mapView.getController().setCenter(new GeoPoint(cLat, cLon));
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** Round pin, white ring, color by radio tech. */
    private android.graphics.Bitmap pinBitmap(String tech) {
        int color;
        switch (tech == null ? "" : tech) {
            case "NR": color = 0xFFA78BFA; break;
            case "LTE": color = 0xFFFF6B4A; break;
            case "WCDMA": color = 0xFF38BDF8; break;
            default: color = 0xFF94A3B8;
        }
        int s = Ui.dp(this, 24);
        android.graphics.Bitmap b =
                android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(s / 2f, s / 2f, s / 2f, p);
        p.setColor(color);
        c.drawCircle(s / 2f, s / 2f, s / 2f - Ui.dp(this, 3), p);
        p.setColor(0xFF0B0B14);
        p.setTextSize(s * 0.42f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        c.drawText("▲", s / 2f, s * 0.62f, p);
        return b;
    }

    private String infoText() {
        StringBuilder sb = new StringBuilder();
        sb.append("DEVICE\n");
        sb.append(kv("Model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL));
        sb.append('\n').append(kv("Android",
                android.os.Build.VERSION.RELEASE + " (SDK " + android.os.Build.VERSION.SDK_INT + ")"));
        sb.append('\n').append(kv("Radio", shortStr(android.os.Build.getRadioVersion())));
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            sb.append('\n').append(kv("SIM slots", String.valueOf(tm == null ? '?' : tm.getPhoneCount())));
        } catch (Throwable ignored) {}

        boolean phonePerm = checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
        sb.append("\n\nSUBSCRIPTIONS").append(phonePerm ? "" : "  (grant Phone perm)");
        try {
            SubscriptionManager sm = (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> subs = sm == null ? null :
                    (phonePerm ? sm.getActiveSubscriptionInfoList() : null);
            if (subs != null && !subs.isEmpty()) {
                for (SubscriptionInfo si : subs) {
                    sb.append('\n').append(kv("SIM " + (si.getSimSlotIndex() + 1),
                            str(si.getDisplayName()) + " · " + str(si.getCountryIso())
                                    .toUpperCase(Locale.US)));
                    TelephonyManager stm = ((TelephonyManager) getSystemService(TELEPHONY_SERVICE))
                            .createForSubscriptionId(si.getSubscriptionId());
                    sb.append('\n').append(kv(" PLMN",
                            str(stm.getNetworkOperator()) + " · " + str(stm.getNetworkOperatorName())));
                    sb.append('\n').append(kv(" State",
                            "roaming=" + stm.isNetworkRoaming()
                                    + " · data=" + dataStateName(stm.getDataState())));
                }
            } else sb.append('\n').append(kv("", phonePerm ? "none active" : "permission denied"));
        } catch (Throwable t) {
            sb.append('\n').append(kv("", "unavailable"));
        }

        sb.append("\n\nPERMISSIONS");
        sb.append('\n').append(kv(" Location", perm(Manifest.permission.ACCESS_FINE_LOCATION)));
        sb.append('\n').append(kv(" Phone", perm(Manifest.permission.READ_PHONE_STATE)));
        if (Build.VERSION.SDK_INT >= 33)
            sb.append('\n').append(kv(" Notifications", perm(Manifest.permission.POST_NOTIFICATIONS)));

        sb.append("\n\nLIMITS");
        sb.append('\n').append("Map tab plots YOUR logged cells only (zero-key). External tower DB (OpenCellID) would need an API key — Mozilla MLS shut down 2024, not portable.");
        return sb.toString();
    }

    private static String shortStr(String s) { return s == null || s.isEmpty() ? "—" : s; }
    private static String str(CharSequence c) { return c == null ? "—" : c.toString(); }
    private static String dataStateName(int s) {
        switch (s) {
            case TelephonyManager.DATA_CONNECTED: return "connected";
            case TelephonyManager.DATA_CONNECTING: return "connecting";
            case TelephonyManager.DATA_DISCONNECTED: return "disconnected";
            default: return "suspended";
        }
    }
    private String perm(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED ? "granted ✓" : "denied ✗";
    }

    // ---------- permissions & SIM resolution ----------

    private void requestPerms() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 42);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 42) {
            resolveSims();
            syncGaugeSel();
        }
    }

    private static class SimCtx {
        int subId = -1;
        String label = "";
        String mcc = "", mnc = "";
        String lastCellKey = "";
        TelephonyManager tm;
        Serving serving;
        String rat = "—";
        List<String> neighbors = new ArrayList<>();
        String lastKey = "";
        int dbm = -1;
    }

    private void resolveSims() {
        List<SimCtx> out = new ArrayList<>();
        TelephonyManager base = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        try {
            boolean phonePerm = checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED;
            SubscriptionManager sm = (SubscriptionManager)
                    getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> subs = (sm != null && phonePerm)
                    ? sm.getActiveSubscriptionInfoList() : null;
            if (subs != null) for (SubscriptionInfo si : subs) {
                if (out.size() >= 2) break;
                SimCtx s = new SimCtx();
                s.subId = si.getSubscriptionId();
                s.tm = base.createForSubscriptionId(s.subId);
                String name = si.getDisplayName() != null ? si.getDisplayName().toString() : "";
                s.label = "SIM " + (si.getSimSlotIndex() + 1)
                        + (name.isEmpty() ? "" : " · " + name);
                try { s.mcc = si.getMccString() != null ? si.getMccString() : ""; }
                catch (Throwable ignored) {}
                try { s.mnc = si.getMncString() != null ? si.getMncString() : ""; }
                catch (Throwable ignored) {}
                out.add(s);
            }
        } catch (Throwable ignored) {}
        if (out.isEmpty()) {
            SimCtx s = new SimCtx();
            s.tm = base;
            s.label = "Active SIM";
            out.add(s);
        }
        sims = out;
        for (int i = 0; i < 2; i++) {
            simHead[i].setVisibility(i < sims.size() ? View.VISIBLE : View.GONE);
            simDetail[i].setVisibility(i < sims.size() ? View.VISIBLE : View.GONE);
        }
        syncGaugeSel();
    }

    // ---------- polling ----------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { poll(); } catch (Throwable ignored) {}
            handler.postDelayed(this, 1000);
        }
    };

    private void poll() {
        tickCount++;
        dualReal = false;
        boolean changed = false;
        List<String> allNb = new ArrayList<>();

        java.util.HashSet<String> claimed = new java.util.HashSet<>();
        for (int i = 0; i < sims.size(); i++) {
            SimCtx s = sims.get(i);
            List<CellInfo> cells = safeCells(s.tm);
            s.serving = null;
            s.neighbors.clear();
            if (cells != null) {
                // Per-SIM serving pick: modems often report ALL radios in every
                // getAllCellInfo() list regardless of subscription scoping, so
                // picking the global-best registered cell twice handed SIM 1's
                // tower to SIM 2 ("identical results"). Match by subscription
                // PLMN first, then keep each SIM's previous cell, then fall back
                // to the strongest still-unclaimed registered cell.
                CellInfo plmnBest = null, stickyBest = null, bestCi = null;
                for (CellInfo ci : cells) {
                    if (!ci.isRegistered()) continue;
                    String k = cellKey(ci);
                    if (claimed.contains(k)) continue;
                    if (bestCi == null || rank(ci) > rank(bestCi)) bestCi = ci;
                    if (k.equals(s.lastCellKey)) stickyBest = ci;
                    if (plmnMatches(s, ci)
                            && (plmnBest == null || rank(ci) > rank(plmnBest))) plmnBest = ci;
                }
                CellInfo chosen = plmnBest != null ? plmnBest
                        : stickyBest != null ? stickyBest : bestCi;
                if (chosen != null) {
                    s.serving = parse(chosen);
                    s.lastCellKey = s.serving.identityKey;
                    claimed.add(s.serving.identityKey);
                } else {
                    s.lastCellKey = "";
                }
                for (CellInfo ci : cells) {
                    if (!ci.isRegistered()) {
                        String l = neighborLine(ci);
                        if (l != null) s.neighbors.add(l);
                    }
                }
            }
            updateSimViews(i, s);

            if (s.serving != null) {
                String key = s.rat + "|" + s.serving.identityKey;
                if (!key.equals(s.lastKey)) {
                    if (!s.lastKey.isEmpty()) {
                        addLog(fmt.format(new Date()) + " [" + simTag(i) + "] ⤳ "
                                + s.rat + " " + s.serving.shortId + " · " + carrierName(s.tm)
                                + " · " + s.serving.primaryDbm + " dBm" + atFix());
                        changed = true;
                    }
                    s.lastKey = key;
                }
            }
            allNb.addAll(s.neighbors);
        }

        // identical modem reports → collapse second card note
        if (sims.size() == 2 && sims.get(0).serving != null && sims.get(1).serving != null) {
            String k0 = sims.get(0).serving.identityKey, k1 = sims.get(1).serving.identityKey;
            dualReal = !k0.equals(k1);
        }

        // neighbors dedupe across sims
        StringBuilder nb = new StringBuilder();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>(allNb);
        for (String l : seen) { if (nb.length() > 0) nb.append('\n'); nb.append(l); }
        nbBox.setText(nb.length() == 0
                ? "No neighbor cells reported right now." : nb.toString());

        // graph sample
        float d0 = sims.get(0).serving != null ? sims.get(0).dbm : -999;
        float d1 = sims.size() > 1 && sims.get(1).serving != null ? sims.get(1).dbm : -999;
        synchronized (samples) {
            samples.addLast(new float[]{d0, d1});
            while (samples.size() > MAX_SAMPLES) samples.removeFirst();
        }
        StringBuilder lg = new StringBuilder();
        lg.append("● ").append(sims.size() > 0 ? sims.get(0).label : "").append(' ')
                .append(d0 > -900 ? (int) d0 + " dBm" : "no signal");
        if (sims.size() > 1) lg.append("\n● ").append(sims.get(1).label).append(' ')
                .append(d1 > -900 ? (int) d1 + " dBm" : "no signal");
        legend.setText(lg.toString());

        // gauges
        for (int i = 0; i < sims.size() && i < 2; i++) {
            Serving sv = sims.get(i).serving;
            gaugeCards[i].setText(sv == null ? "No cell data for this SIM."
                    : (sims.get(i).rat + "   " + bars(sv.levelPct) + "  "
                       + sv.quality.toUpperCase(Locale.US) + "\n\n" + sv.gaugeText));
        }

        if (tickCount % 5 == 3) infoBox.setText(infoText());

        if (changed && getSharedPreferences("netscanner", 0).getBoolean("cell_change_sound", false)) playBlip();

        if (graphView != null && tabPages[2].getVisibility() == View.VISIBLE) graphView.invalidate();
    }

    private String simTag(int i) { return "S" + (i + 1); }

    private void updateSimViews(int i, SimCtx s) {
        if (s.serving == null) {
            s.rat = "—"; s.dbm = -1;
            simHead[i].setText(s.label + "  ·  No cell data");
            simHead[i].setTextColor(0xFFF87171);
            List<CellInfo> cells = safeCells(s.tm);
            simDetail[i].setText(cells == null
                    ? "Location permission required to read cell info."
                    : (cells.isEmpty() ? "Empty cell list — airplane mode? No SIM on this slot?"
                    : "Visible cells but none registered yet…"));
            return;
        }
        Serving sv = s.serving;
        boolean nrReg = false, hasNr = false;
        List<CellInfo> cells = safeCells(s.tm);
        if (cells != null) for (CellInfo ci : cells) {
            if (isNr(ci)) { hasNr = true; if (ci.isRegistered()) nrReg = true; }
        }
        s.rat = labelRat(sv.raw, nrReg, hasNr);
        s.dbm = sv.primaryDbm;
        simHead[i].setText(s.label + "\n" + s.rat + "   " + bars(sv.levelPct) + "  "
                + sv.primaryDbm + " dBm  ·  " + sv.quality);
        simHead[i].setTextColor(colorFor(sv.quality));
        String extra = "";
        if (i == 1 && sims.size() == 2 && sims.get(0).serving != null
                && sims.get(0).serving.identityKey.equals(sv.identityKey)) {
            extra = "\n(same cell as SIM 1 — shared site, or modem reports one registration)";
        }
        simDetail[i].setText(sv.detailText + extra);
    }

    private List<CellInfo> safeCells(TelephonyManager tm) {
        try { return tm.getAllCellInfo(); } catch (Throwable t) { return null; }
    }

    /** Lightweight identity key mirroring Serving.identityKey (no full parse). */
    private static String cellKey(CellInfo ci) {
        try {
            if (ci instanceof CellInfoLte) {
                CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
                return "LTE:" + id.getCi() + ":" + id.getPci();
            }
            if (Build.VERSION.SDK_INT >= 29 && ci instanceof android.telephony.CellInfoNr) {
                android.telephony.CellIdentityNr id =
                        (android.telephony.CellIdentityNr) ci.getCellIdentity();
                return "NR:" + id.getNci() + ":" + id.getPci();
            }
            if (ci instanceof CellInfoWcdma) {
                CellIdentityWcdma id = ((CellInfoWcdma) ci).getCellIdentity();
                return "3G:" + id.getCid() + ":" + id.getPsc();
            }
            if (ci instanceof CellInfoGsm) {
                CellIdentityGsm id = ((CellInfoGsm) ci).getCellIdentity();
                return "2G:" + id.getCid() + ":" + id.getLac();
            }
            if (ci instanceof CellInfoCdma)
                return "CDMA:" + ((CellInfoCdma) ci).getCellIdentity().getBasestationId();
        } catch (Throwable ignored) {}
        return String.valueOf(System.identityHashCode(ci));
    }

    /** Does this cell's PLMN match the SIM subscription's operator? */
    private static boolean plmnMatches(SimCtx s, CellInfo ci) {
        if (s.mcc == null || s.mcc.isEmpty()) return false;
        String[] p = idPlmn(ci);
        if (!s.mcc.equals(p[0]) || p[1].isEmpty()) return false;
        if (p[1].equals(s.mnc)) return true;
        try { // MNC zero-padding differs between sources ("4" vs "04")
            return Integer.parseInt(s.mnc) == Integer.parseInt(p[1]);
        } catch (NumberFormatException nf) {
            return false;
        }
    }

    /** [mcc, mnc] strings of a cell's identity ("" when unknown); base
     * CellIdentity doesn't expose them in this SDK — go per concrete type. */
    private static String[] idPlmn(CellInfo ci) {
        try {
            if (ci instanceof CellInfoLte) {
                CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
                return new String[]{nz(id.getMccString()), nz(id.getMncString())};
            }
            if (Build.VERSION.SDK_INT >= 29 && ci instanceof android.telephony.CellInfoNr) {
                android.telephony.CellIdentityNr id =
                        (android.telephony.CellIdentityNr) ci.getCellIdentity();
                return new String[]{nz(id.getMccString()), nz(id.getMncString())};
            }
            if (ci instanceof CellInfoWcdma) {
                CellIdentityWcdma id = ((CellInfoWcdma) ci).getCellIdentity();
                return new String[]{nz(id.getMccString()), nz(id.getMncString())};
            }
            if (ci instanceof CellInfoGsm) {
                CellIdentityGsm id = ((CellInfoGsm) ci).getCellIdentity();
                return new String[]{nz(id.getMccString()), nz(id.getMncString())};
            }
        } catch (Throwable ignored) {}
        return new String[]{"", ""};
    }

    private static String nz(String s) { return s != null ? s : ""; }

    /** Short beep + one vibration on serving-cell identity change (debounced ≥900 ms). */
    private void playBlip() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBeepAt < 900) return;
        lastBeepAt = now;
        try {
            if (toneGen == null)
                toneGen = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        } catch (Throwable t) {
            try { toneGen.release(); } catch (Throwable ignored2) {}
            toneGen = null;
        }
        vibrateOnce();
    }

    private void vibrateOnce() {
        try {
            Vibrator v = Build.VERSION.SDK_INT >= 31
                    ? ((VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE)).getDefaultVibrator()
                    : (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(60);
        } catch (Throwable ignored) {}
    }

    /** Best-effort operator name (READ_PHONE_STATE required on API 30+); never throws. */
    private String carrierName(TelephonyManager tm) {
        try {
            String n = tm.getNetworkOperatorName();
            return n == null || n.isEmpty() ? "—" : n;
        } catch (Throwable t) { return "—"; }
    }

    /** "@lat,lon" suffix from the freshest last-known fix — powers KML export. */
    private String atFix() {
        double[] ll = lastFix();
        return ll == null ? "" : " @" + ll[0] + "," + ll[1];
    }

    private double[] lastFix() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String p : lm.getAllProviders()) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (SecurityException ignored) { }
            }
            if (best == null) return null;
            return new double[]{Math.round(best.getLatitude() * 1e6) / 1e6,
                    Math.round(best.getLongitude() * 1e6) / 1e6};
        } catch (Throwable t) { return null; }
    }

    // ---------- per-tech parsing ----------

    private Serving parse(CellInfo ci) {
        Serving s = new Serving();
        s.raw = ci;
        if (ci instanceof CellInfoLte) {
            CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
            CellSignalStrengthLte ss = ((CellInfoLte) ci).getCellSignalStrength();
            long ciVal = id.getCi();
            int enb = ciVal > 0 ? (int) (ciVal / 256) : -1;
            int band = lteBand(id.getEarfcn());
            s.bandTxt = band > 0 ? ("Band " + band + "  (EARFCN " + id.getEarfcn() + ")") : "EARFCN " + u(id.getEarfcn());
            float mhz = lteFreqMhz(id.getEarfcn());
            s.detailText = join(
                    kv("Operator PLMN", plmn(id.getMccString(), id.getMncString())),
                    kv("CI", u(ciVal)), kv("eNB", enb >= 0 ? String.valueOf(enb) : "—"),
                    kv("CID", enb >= 0 ? String.valueOf(ciVal % 256) : "—"),
                    kv("TAC", u(id.getTac())), kv("PCI", u(id.getPci())),
                    kv("Band", s.bandTxt),
                    kv("DL freq", mhz > 0 ? String.format(Locale.US, "%.1f MHz", mhz) : "—"), "",
                    kv("RSRP", q(ss.getRsrp(), "dBm", rsrpQuality(ss.getRsrp()))),
                    kv("RSRQ", q(ss.getRsrq(), "dB", rsrqQuality(ss.getRsrq()))),
                    kv("RSSI", q(rssiApi29(ss), "dBm", rssiQuality(rssiApi29(ss)))),
                    kv("SNR", q(ss.getRssnr(), "dB", snrQuality(ss.getRssnr()))),
                    kv("CQI", u(cqiApi29(ss))),
                    taDistance(ss.getTimingAdvance()));
            s.primaryDbm = ss.getRsrp() != Integer.MAX_VALUE ? ss.getRsrp() : ss.getDbm();
            s.quality = rsrpQuality(s.primaryDbm);
            s.levelPct = levelPct(s.primaryDbm);
            s.identityKey = "LTE:" + ciVal + ":" + id.getPci();
            s.shortId = "PCI " + u(id.getPci()) + " eNB " + (enb >= 0 ? enb : -1);
            s.gaugeText = "RSRP " + barFor(-120, -60, ss.getRsrp())
                    + "\nRSRQ " + barFor(-24, 0, ss.getRsrq())
                    + "\nRSSI " + barFor(-110, -50, rssiApi29(ss))
                    + "\nSNR  " + barFor(-10, 35, ss.getRssnr());
        } else if (Build.VERSION.SDK_INT >= 29 && ci instanceof android.telephony.CellInfoNr) {
            s = parseNr((android.telephony.CellInfoNr) ci);
        } else if (ci instanceof CellInfoWcdma) {
            CellIdentityWcdma id = ((CellInfoWcdma) ci).getCellIdentity();
            CellSignalStrengthWcdma ss = ((CellInfoWcdma) ci).getCellSignalStrength();
            int rscp = ss.getDbm(); // WCDMA getDbm() reports CPICH RSCP
            int band = wcdmaBand(id.getUarfcn());
            s.bandTxt = band > 0 ? ("Band " + band + "  (UARFCN " + id.getUarfcn() + ")") : "UARFCN " + u(id.getUarfcn());
            int rnc = id.getCid() > 0 ? (int) (id.getCid() >> 16) : -1;
            int scid = id.getCid() > 0 ? (int) (id.getCid() & 0xFFFF) : -1;
            s.detailText = join(
                    kv("Operator PLMN", plmn(id.getMccString(), id.getMncString())),
                    kv("CID", u(id.getCid())), kv("RNC", rnc >= 0 ? String.valueOf(rnc) : "—"),
                    kv("SCID", scid >= 0 ? String.valueOf(scid) : "—"),
                    kv("LAC", u(id.getLac())), kv("PSC", u(id.getPsc())),
                    kv("Band", s.bandTxt), "",
                    kv("RSCP", q(rscp, "dBm", rsrpQuality(rscp))),
                    kv("EC/IO", q(ss.getEcNo(), "dB", rsrqQuality(ss.getEcNo()))));
            s.primaryDbm = ss.getDbm();
            s.quality = rsrpQuality(rscp != Integer.MAX_VALUE ? rscp : ss.getDbm());
            s.levelPct = levelPct(s.primaryDbm);
            s.identityKey = "3G:" + id.getCid() + ":" + id.getPsc();
            s.shortId = "PSC " + u(id.getPsc());
            s.gaugeText = "RSCP " + barFor(-115, -40, rscp)
                    + "\nEC/Io " + barFor(-24, 0, ss.getEcNo());
        } else if (ci instanceof CellInfoGsm) {
            CellIdentityGsm id = ((CellInfoGsm) ci).getCellIdentity();
            CellSignalStrengthGsm ss = ((CellInfoGsm) ci).getCellSignalStrength();
            int bsic = Build.VERSION.SDK_INT >= 30 ? id.getBsic() : -1;
            s.bandTxt = "ARFCN " + u(id.getArfcn());
            s.detailText = join(
                    kv("Operator PLMN", plmn(id.getMccString(), id.getMncString())),
                    kv("CID", u(id.getCid())), kv("LAC", u(id.getLac())),
                    kv("BSIC", bsic >= 0 ? String.valueOf(bsic & 0x3F) : "—"),
                    kv("Band", gsmBandName(id.getArfcn())), "",
                    kv("RXL/RSSI", q(ss.getRssi(), "dBm", gsmQuality(ss.getRssi()))),
                    taDistance(Build.VERSION.SDK_INT >= 29 ? ss.getTimingAdvance() : -1));
            s.primaryDbm = ss.getDbm();
            s.quality = gsmQuality(ss.getRssi() != Integer.MAX_VALUE ? ss.getRssi() : ss.getDbm());
            s.levelPct = levelPct(s.primaryDbm);
            s.identityKey = "2G:" + id.getCid() + ":" + id.getLac();
            s.shortId = "CID " + u(id.getCid());
            s.gaugeText = "RXL  " + barFor(-110, -50, ss.getRssi());
        } else if (ci instanceof CellInfoCdma) {
            CellIdentityCdma id = ((CellInfoCdma) ci).getCellIdentity();
            CellSignalStrengthCdma ss = ((CellInfoCdma) ci).getCellSignalStrength();
            s.bandTxt = "CDMA";
            s.detailText = join(
                    kv("BSID", u(id.getBasestationId())),
                    kv("SID/NID", u(id.getSystemId()) + " / " + u(id.getNetworkId())),
                    kv("Tower pos", (id.getLatitude() == 0 && id.getLongitude() == 0) ? "—"
                            : (id.getLatitude() / 14400.0) + ", " + (id.getLongitude() / 14400.0)), "",
                    kv("CDMA RSSI", q(ss.getCdmaDbm(), "dBm", gsmQuality(ss.getCdmaDbm()))),
                    kv("EVDO EC/IO", q(ss.getEvdoEcio(), "dB", rsrqQuality(ss.getEvdoEcio()))));
            s.primaryDbm = ss.getDbm();
            s.quality = gsmQuality(ss.getCdmaDbm());
            s.levelPct = levelPct(s.primaryDbm);
            s.identityKey = "CDMA:" + id.getBasestationId();
            s.shortId = "BSID " + u(id.getBasestationId());
            s.gaugeText = "RXL  " + barFor(-105, -60, ss.getCdmaDbm());
        }
        if (s.primaryDbm == Integer.MAX_VALUE) s.primaryDbm = -1;
        return s;
    }

    /** Isolated: NR classes exist only on API 29+. */
    private Serving parseNr(android.telephony.CellInfoNr ci) {
        Serving s = new Serving();
        s.raw = ci;
        android.telephony.CellIdentityNr id = (android.telephony.CellIdentityNr) ci.getCellIdentity();
        android.telephony.CellSignalStrengthNr ss =
                (android.telephony.CellSignalStrengthNr) ci.getCellSignalStrength();
        int arfcn = Build.VERSION.SDK_INT >= 30 ? id.getNrarfcn() : -1;
        int band = nrBand(arfcn);
        s.bandTxt = band > 0 ? ("Band n" + band + "  (NR-ARFCN " + arfcn + ")")
                : (arfcn > 0 ? "NR-ARFCN " + arfcn : "5G NR");
        long nci = id.getNci();
        float mhz = nrFreqMhz(arfcn);
        s.detailText = join(
                kv("Operator PLMN", plmn(id.getMccString(), id.getMncString())),
                kv("NCI", nci > 0 ? String.valueOf(nci) : "—"),
                kv("TAC", u(id.getTac())), kv("PCI", u(id.getPci())),
                kv("Band", s.bandTxt),
                kv("Freq", mhz > 0 ? String.format(Locale.US, "%.1f MHz", mhz) : "—"), "",
                kv("SS-RSRP", q(ss.getSsRsrp(), "dBm", rsrpQuality(ss.getSsRsrp()))),
                kv("SS-RSRQ", q(ss.getSsRsrq(), "dB", rsrqQuality(ss.getSsRsrq()))),
                kv("SINR", q(ss.getSsSinr(), "dB", snrQuality(ss.getSsSinr()))));
        s.primaryDbm = ss.getSsRsrp() != Integer.MAX_VALUE ? ss.getSsRsrp() : ss.getDbm();
        s.quality = rsrpQuality(s.primaryDbm);
        s.levelPct = levelPct(s.primaryDbm);
        s.identityKey = "NR:" + nci + ":" + id.getPci();
        s.shortId = "PCI " + u(id.getPci());
        s.gaugeText = "RSRP " + barFor(-120, -60, ss.getSsRsrp())
                + "\nRSRQ " + barFor(-24, 0, ss.getSsRsrq())
                + "\nSINR " + barFor(-10, 35, ss.getSsSinr());
        return s;
    }

    private static class Serving {
        CellInfo raw;
        String gaugeText = "", detailText = "", identityKey = "", shortId = "", bandTxt = "";
        int primaryDbm = Integer.MAX_VALUE;
        String quality = "?";
        int levelPct = 0;
    }

    // ---------- neighbors / RAT labeling ----------

    private boolean isNr(CellInfo ci) {
        return Build.VERSION.SDK_INT >= 29 && ci instanceof android.telephony.CellInfoNr;
    }

    private int rank(CellInfo ci) {
        if (isNr(ci)) return 5;
        if (ci instanceof CellInfoLte) return 4;
        if (ci instanceof CellInfoWcdma) return 3;
        if (ci instanceof CellInfoGsm) return 2;
        return 1;
    }

    private String labelRat(CellInfo ci, boolean nrReg, boolean hasNr) {
        String base;
        if (ci instanceof CellInfoLte) base = "4G LTE";
        else if (ci instanceof CellInfoWcdma) base = "3G WCDMA";
        else if (ci instanceof CellInfoGsm) base = "2G GSM";
        else if (ci instanceof CellInfoCdma) base = "CDMA";
        else if (isNr(ci)) base = nrReg ? "5G SA" : "5G NR";
        else base = "?";
        if (hasNr && ci instanceof CellInfoLte) base += "+NSA (EN-DC)";
        return base;
    }

    private String neighborLine(CellInfo ci) {
        if (ci instanceof CellInfoLte) {
            CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
            CellSignalStrengthLte ss = ((CellInfoLte) ci).getCellSignalStrength();
            int b = lteBand(id.getEarfcn());
            return "LTE  PCI " + u(id.getPci()) + (b > 0 ? " B" + b : "")
                    + "  RSRP " + u(ss.getRsrp()) + " dBm"
                    + "  RSRQ " + u(ss.getRsrq()) + " dB  SNR " + u(ss.getRssnr());
        } else if (Build.VERSION.SDK_INT >= 29 && ci instanceof android.telephony.CellInfoNr) {
            android.telephony.CellIdentityNr id = (android.telephony.CellIdentityNr) ci.getCellIdentity();
            android.telephony.CellSignalStrengthNr ss =
                    (android.telephony.CellSignalStrengthNr) ci.getCellSignalStrength();
            int b = nrBand(Build.VERSION.SDK_INT >= 30 ? id.getNrarfcn() : -1);
            return "NR   PCI " + u(id.getPci()) + (b > 0 ? " n" + b : "")
                    + "  RSRP " + u(ss.getSsRsrp()) + " dBm";
        } else if (ci instanceof CellInfoWcdma) {
            CellIdentityWcdma id = ((CellInfoWcdma) ci).getCellIdentity();
            CellSignalStrengthWcdma ss = ((CellInfoWcdma) ci).getCellSignalStrength();
            return "WCDMA PSC " + u(id.getPsc()) + "  RSCP " + u(ss.getDbm()) + " dBm";
        } else if (ci instanceof CellInfoGsm) {
            CellIdentityGsm id = ((CellInfoGsm) ci).getCellIdentity();
            CellSignalStrengthGsm ss = ((CellInfoGsm) ci).getCellSignalStrength();
            return "GSM  CID " + u(id.getCid()) + "  RXL " + u(ss.getRssi()) + " dBm";
        }
        return null;
    }

    // ---------- band tables ----------

    /** LTE EARFCN → {band, lo, hi, dlLowX10, offsDL} per TS 36.101. */
    private static final int[][] LTE_BANDS = {
            {1,0,599,21100,0},{2,600,1199,19300,600},{3,1200,1949,18050,1200},
            {4,1950,2399,21100,1950},{5,2400,2649,8690,2400},{6,2650,2749,8750,2650},
            {7,2750,3449,26200,2750},{8,3450,3799,9250,3450},{9,3800,4149,18449,3800},
            {10,4150,4749,21100,4150},{11,4750,4949,14279,4750},{12,5010,5179,7290,5010},
            {13,5180,5279,7460,5180},{14,5280,5379,7580,5280},{17,5730,5849,7340,5730},
            {18,5850,5999,8600,5850},{19,6000,6149,8750,6000},{20,6150,6449,7910,6150},
            {21,6450,6599,14959,6450},{22,6600,7399,35100,6600},{23,7500,7699,21800,7500},
            {24,7700,8039,15250,7700},{25,8040,8689,19300,8040},{26,8690,9039,8590,8690},
            {27,9040,9209,8520,9040},{28,9210,9659,7580,9210},{29,9660,9769,7170,9660},
            {30,9770,9869,23050,9770},{31,9870,9919,4625,9870},{32,9920,10359,14520,9920},
            {33,36000,36199,19000,36000},{34,36200,36349,20100,36200},{35,36350,36949,18500,36350},
            {36,36950,37549,19300,36950},{37,37550,37749,19100,37550},{38,37750,38249,25700,37750},
            {39,38250,38649,18800,38250},{40,38650,39649,23000,38650},{41,39650,41589,24960,39650},
            {42,41590,43589,34000,41590},{43,43590,45589,36000,43590},{44,45590,46589,7030,45590},
            {46,46790,54539,51500,46790},{48,55240,56739,35500,55240},{50,58240,59239,14320,58240},
            {51,59240,60239,14270,59240},{53,61240,62239,24835,61240},{65,65536,66435,21100,65536},
            {66,66436,67335,21100,66436},{67,67336,67535,7380,67336},{68,67536,67835,7530,67536},
            {69,67836,68335,7340,67836},{70,68336,68585,16950,68336},{71,68586,68935,6170,68586},
            {72,68936,68985,4510,68936},{73,68986,69035,4500,68986},{74,69036,69165,14270,69036},
            {75,69166,69265,14320,69166},{76,69266,69365,14320,69266},{85,70936,71695,7280,70936}};

    static int lteBand(int e) {
        if (e < 0) return -1;
        for (int[] r : LTE_BANDS) if (e >= r[1] && e <= r[2]) return r[0];
        return -1;
    }

    static float lteFreqMhz(int e) {
        if (e < 0) return -1;
        for (int[] r : LTE_BANDS) if (e >= r[1] && e <= r[2]) return r[3] / 10f + 0.1f * (e - r[4]);
        return -1;
    }

    /** NR ARFCN → FR1 band, approximate TS 38.101 ranges. */
    static int nrBand(int a) {
        if (a < 0) return -1;
        int[][] t = {{1,422000,434000},{2,386000,399000},{3,361000,376000},{5,173800,178800},
                {7,500000,538000},{8,185000,192000},{12,139200,141200},{13,145800,147400},
                {14,150600,151400},{18,172000,175000},{20,158200,164200},{24,132600,133800},
                {25,193000,199000},{26,171700,178800},{28,151600,160600},{29,143400,145600},
                {30,460900,466100},{34,402000,405000},{38,480980,491500},{39,384000,389000},
                {40,460000,480000},{41,499200,539000},{46,595500,620500},{48,567000,597000},
                {50,285400,295400},{51,285400,286400},{53,496700,499000},{66,398000,422000},
                {70,339000,340500},{71,123400,130400},{74,285400,295000},{75,285400,286400},
                {76,295000,295000},{77,620000,680000},{78,620000,653333},{79,693333,700000},
                {85,142800,143400},{90,565000,610000}};
        for (int[] r : t) if (a >= r[1] && a <= r[2]) return r[0];
        return -1;
    }

    /** NR ARFCN → DL frequency MHz per TS 38.104. */
    static float nrFreqMhz(int a) {
        if (a < 0) return -1;
        if (a < 600000) return 0.005f * a;
        if (a <= 2016666) return 3000f + 0.015f * (a - 600000);
        return -1;
    }

    /** WCDMA UARFCN → common DL bands (TS 25.101 ranges). */
    static int wcdmaBand(int n) {
        if (n <= 0) return -1;
        int[][] t = {{1,10562,10838},{2,9662,9938},{3,11625,11999},{4,15360,15695},
                {5,4357,4458},{6,4387,4413},{8,2937,3088},{19,712,738}};
        for (int[] r : t) if (n >= r[1] && n <= r[2]) return r[0];
        return -1;
    }

    static String gsmBandName(int arfcn) {
        if (arfcn <= 124 && arfcn >= 1) return "GSM 900";
        if (arfcn >= 512 && arfcn <= 885) return "DCS 1800";
        if (arfcn >= 128 && arfcn <= 251) return "GSM 850";
        if (arfcn >= 512 && arfcn <= 810) return "PCS 1900";
        if (arfcn >= 975 && arfcn <= 1023) return "E-GSM 900";
        return "ARFCN " + arfcn;
    }

    // ---------- quality labels / helpers ----------

    private static String q(int v, String unit, String qual) {
        return (v == Integer.MAX_VALUE || v <= -2000 ? "—" : v + " " + unit) + "   · " + qual;
    }

    private static String rsrpQuality(int v) {
        if (v == Integer.MAX_VALUE) return "—";
        return v >= -80 ? "Excellent" : v >= -90 ? "Good" : v >= -100 ? "Fair" : "Poor";
    }
    private static String rsrqQuality(int v) {
        if (v == Integer.MAX_VALUE) return "—";
        return v >= -10 ? "Excellent" : v >= -15 ? "Good" : v >= -20 ? "Fair" : "Poor";
    }
    private static String snrQuality(int v) {
        if (v == Integer.MAX_VALUE) return "—";
        return v >= 20 ? "Excellent" : v >= 13 ? "Good" : v >= 0 ? "Fair" : "Poor";
    }
    private static String rssiQuality(int v) {
        if (v == Integer.MAX_VALUE) return "—";
        return v >= -65 ? "Excellent" : v >= -75 ? "Good" : v >= -85 ? "Fair" : "Poor";
    }
    private static String gsmQuality(int v) {
        if (v == Integer.MAX_VALUE) return "—";
        return v >= -70 ? "Excellent" : v >= -85 ? "Good" : v >= -95 ? "Fair" : "Poor";
    }
    private static int colorFor(String qual) {
        switch (qual) {
            case "Excellent": return 0xFF4ADE80;
            case "Good": return 0xFFA3E635;
            case "Fair": return 0xFFFBBF24;
            default: return 0xFFF87171;
        }
    }

    private static int levelPct(int dbm) {
        int pct = (dbm + 120) * 100 / 60;
        return pct < 0 ? 0 : Math.min(pct, 100);
    }

    private static String bars(int pct) {
        return pct > 75 ? "▂▄▆█" : pct > 50 ? "▂▄▆▂" : pct > 25 ? "▂▄▂▂" : "▂▂▂▂";
    }

    private static String barFor(int lo, int hi, int v) {
        if (v == Integer.MAX_VALUE || v <= -2000) return "[ —— no data ]";
        int c = Math.max(lo, Math.min(hi, v));
        int idx = (c - lo) * 12 / (hi - lo);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 12; i++) sb.append(i <= idx ? '█' : '·');
        return sb.append(']').append(' ').append(v).toString();
    }

    private String taDistance(int ta) {
        if (ta <= 0 || ta == Integer.MAX_VALUE) return kv("TA → tower ≈", "—");
        long m = ta * 78L;
        return kv("TA → tower ≈", m >= 1600 ? (m / 1000) + " km" : m + " m");
    }

    private static int rssiApi29(CellSignalStrengthLte ss) {
        return Build.VERSION.SDK_INT >= 29 ? ss.getRssi() : Integer.MAX_VALUE;
    }

    private static int cqiApi29(CellSignalStrengthLte ss) {
        return Build.VERSION.SDK_INT >= 29 ? ss.getCqi() : Integer.MAX_VALUE;
    }

    private static String plmn(String mcc, String mnc) {
        if (mcc == null && mnc == null) return "—";
        return (mcc == null ? "?" : mcc) + "-" + (mnc == null ? "?" : mnc);
    }

    private static String u(int v) { return (v < 0 || v == Integer.MAX_VALUE) ? "—" : String.valueOf(v); }
    private static String u(long v) { return v <= 0 ? "—" : String.valueOf(v); }
    private static String kv(String k, String v) { return pad(k, 14) + v; }
    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) sb.append(' ');
        return sb.toString();
    }
    private static String join(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) { if (sb.length() > 0) sb.append('\n'); sb.append(l); }
        return sb.toString();
    }

    // ---------- log persistence / export ----------

    private void addLog(String line) {
        synchronized (logLines) {
            logLines.addLast(line);
            while (logLines.size() > MAX_LOG) logLines.removeFirst();
            persistLog();
        }
        refreshLogView();
    }

    private void loadPersistedLog() {
        try {
            String saved = getSharedPreferences("netscanner", 0).getString("cell_log", "");
            synchronized (logLines) {
                logLines.clear();
                if (!saved.isEmpty()) for (String l : saved.split("\n")) logLines.addLast(l);
            }
        } catch (Throwable ignored) {}
    }

    private void persistLog() {
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (logLines) {
                for (String l : logLines) { if (sb.length() > 0) sb.append('\n'); sb.append(l); }
            }
            getSharedPreferences("netscanner", 0).edit().putString("cell_log", sb.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private void refreshLogView() {
        StringBuilder sb = new StringBuilder();
        synchronized (logLines) {
            var it = logLines.descendingIterator();
            int shown = 0;
            while (it.hasNext() && shown++ < 60) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(it.next());
            }
        }
        logBox.setText(sb.length() == 0
                ? "(no changes yet — walk around or toggle airplane mode)" : sb.toString());
    }

    private void exportCsv() {
        StringBuilder sb = new StringBuilder("time,sim,event\n");
        synchronized (logLines) {
            for (String l : logLines) {
                String clean = l.replace("\"", "'");
                sb.append('"').append(clean).append('"').append('\n');
            }
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "NetScanner cell log");
        i.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(i, "Export cell log"));
    }

    private static final java.util.regex.Pattern GEO_P =
            java.util.regex.Pattern.compile("@(-?\\d+(?:\\.\\d+)?),(-?(?:\\d+(?:\\.\\d+)?))\\s*$");

    /** KML of geo-tagged cell_log entries → cache/share/netscanner_cells.kml via FileProvider. */
    private void exportKml() {
        List<String[]> pts = new ArrayList<>(); // name, desc, lon, lat
        synchronized (logLines) {
            for (String l : logLines) {
                java.util.regex.Matcher m = GEO_P.matcher(l);
                if (!m.find()) continue;
                try {
                    pts.add(new String[]{placemarkName(l), placemarkDesc(l, m.start()),
                            coordTxt(Double.parseDouble(m.group(2))),
                            coordTxt(Double.parseDouble(m.group(1)))});
                } catch (NumberFormatException ignored) {}
            }
        }
        if (pts.isEmpty()) {
            Toast.makeText(this, "no located cells yet", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n"
                + "<name>NetScanner cell log</name>\n");
        for (String[] p : pts) {
            sb.append("<Placemark><name>").append(xmlEsc(p[0]))
              .append("</name><description>").append(xmlEsc(p[1]))
              .append("</description><Point><coordinates>")
              .append(p[2]).append(',').append(p[3])
              .append("</coordinates></Point></Placemark>\n");
        }
        sb.append("</Document></kml>\n");
        try {
            File dir = new File(getCacheDir(), "share");
            dir.mkdirs();
            File f = new File(dir, "netscanner_cells.kml");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/vnd.google-earth.kml+xml");
            i.putExtra(Intent.EXTRA_STREAM,
                    FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f));
            i.putExtra(Intent.EXTRA_SUBJECT, "NetScanner located cells (KML)");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Export cells KML"));
        } catch (Exception e) {
            Toast.makeText(this, "KML export failed", Toast.LENGTH_SHORT).show();
        }
    }

    /** "HH:MM tech" per spec. */
    private static String placemarkName(String l) {
        int sp = l.indexOf(' ');
        String time = sp > 5 ? l.substring(0, 5) : sp > 0 ? l.substring(0, sp) : "";
        int arrow = l.indexOf("⤳ ");
        String tech = "CELL";
        if (arrow >= 0) {
            String rest = l.substring(arrow + 2);
            int sp2 = rest.indexOf(' ');
            tech = sp2 > 0 ? rest.substring(0, sp2) : rest.trim();
        }
        return time + " " + tech;
    }

    /** "[Sx] CID-info · carrier · dBm" segment between the tech token and "@lat,lon". */
    private static String placemarkDesc(String l, int atIdx) {
        int arrow = l.indexOf("⤳ ");
        if (arrow < 0) return "";
        int start = Math.min(l.length(), arrow + 2);
        int end = Math.min(Math.max(atIdx, start), l.length());
        String seg = l.substring(start, end).trim();
        int sp = seg.indexOf(' ');
        return sp > 0 ? seg.substring(sp + 1).trim() : seg;
    }

    private static String coordTxt(double v) {
        String s = String.format(Locale.US, "%.6f", v);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String xmlEsc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- small UI builders ----------

    private void section(LinearLayout parent, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(11); t.setTextColor(0xFF6E6E82);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 4));
        parent.addView(t);
    }

    private TextView card(LinearLayout parent, float sizeSp) {
        TextView t = new TextView(this);
        t.setTextSize(sizeSp);
        t.setTextColor(0xFFD9D9E3);
        t.setBackgroundResource(R.drawable.glass_card);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        t.setText("Reading…");
        parent.addView(t);
        return t;
    }

    private Button btn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(0xFFEDEDF2);
        b.setBackgroundResource(R.drawable.btn_secondary);
        b.setAllCaps(false);
        return b;
    }

    private Button pill(String label) {
        Button b = btn(label);
        b.setBackgroundResource(R.drawable.pill_bg);
        b.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        return b;
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        for (SimCtx s : sims) s.lastKey = ""; // don't log the resume jump
        handler.post(tick);
        if (getSharedPreferences("netscanner", 0).getBoolean("sb_dbm", false))
            startForegroundService(new Intent(this, CellMonitorService.class));
        if (mapView != null) mapView.onResume();
    }

    @Override protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacks(tick);
        persistLog();
        if (mapView != null) mapView.onPause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (toneGen != null) {
            try { toneGen.release(); } catch (Throwable ignored) {}
            toneGen = null;
        }
        persistLog();
        if (mapView != null) { try { mapView.onDetach(); } catch (Throwable ignored) {} }
    }
}
