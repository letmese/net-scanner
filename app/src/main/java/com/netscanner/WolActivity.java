package com.netscanner;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

public class WolActivity extends AppCompatActivity {

    private LinearLayout list;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.root(this);
        Ui.header(this, "🐺 Wake-on-LAN", root);
        TextView add = new TextView(this);
        add.setText("＋  Add device");
        add.setTextColor(0xFF0E0E16); add.setTextSize(14);
        add.setTypeface(null, android.graphics.Typeface.BOLD);
        add.setGravity(Gravity.CENTER);
        add.setBackgroundColor(0xFF4ADE80);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        lp.setMargins(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        add.setLayoutParams(lp);
        add.setOnClickListener(v -> addDialog());
        root.addView(add);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(this); sc.addView(list); root.addView(sc);
        setContentView(root);
        GlassWindow.apply(this);
        render();
    }

    private JSONArray load() throws Exception {
        return new JSONArray(getSharedPreferences("netscanner", 0)
                .getString("wol_profiles", "[]"));
    }

    private void save(JSONArray a) {
        getSharedPreferences("netscanner", 0).edit()
                .putString("wol_profiles", a.toString()).apply();
    }

    private void render() {
        list.removeAllViews();
        try {
            JSONArray a = load();
            if (a.length() == 0) {
                TextView e = new TextView(this);
                e.setText("No saved devices yet.\n\nAdd your PC or NAS, then wake it with one tap.\nTip: run a scan first — long-press a device to see its MAC.");
                e.setTextColor(0xFF8A8A99); e.setTextSize(13);
                e.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), 0);
                list.addView(e);
            }
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
                TextView name = new TextView(this);
                name.setText("⚡ " + o.getString("name") + "\n" + o.getString("mac"));
                name.setTextColor(0xFFEDEDF2); name.setTextSize(14);
                name.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                name.setOnClickListener(v -> {
                    boolean ok = com.netscanner.net.NetworkUtils.wakeOnLan(o.optString("mac"));
                    Toast.makeText(this, ok ? "⚡ Magic packet sent ×3" : "WoL failed — bad MAC?",
                            Toast.LENGTH_SHORT).show();
                });
                TextView del = new TextView(this);
                del.setText("✕"); del.setTextColor(0xFFFF6B6B); del.setTextSize(18);
                del.setPadding(Ui.dp(this, 20), 0, 0, 0);
                final int idx = i;
                del.setOnClickListener(v -> {
                    try {
                        JSONArray arr = load();
                        JSONArray out = new JSONArray();
                        for (int k = 0; k < arr.length(); k++) if (k != idx) out.put(arr.get(k));
                        save(out); render();
                    } catch (Exception ignored) {}
                });
                row.addView(name); row.addView(del);
                list.addView(row);
            }
        } catch (Exception ignored) {}
    }

    private void addDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int d = Ui.dp(this, 16);
        box.setPadding(d, 0, d, 0);
        EditText name = new EditText(this); name.setHint("Name e.g. Gaming PC");
        EditText mac = new EditText(this); mac.setHint("MAC e.g. AA:BB:CC:DD:EE:FF");
        box.addView(name); box.addView(mac);
        new AlertDialog.Builder(this)
                .setTitle("Add Wake-on-LAN device")
                .setView(box)
                .setPositiveButton("Save", (dlg, w) -> {
                    try {
                        String m = mac.getText().toString().trim();
                        String n = name.getText().toString().trim();
                        if (m.isEmpty() || n.isEmpty()) return;
                        JSONArray a = load();
                        JSONObject o = new JSONObject();
                        o.put("name", n); o.put("mac", m);
                        a.put(o);
                        save(a); render();
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
