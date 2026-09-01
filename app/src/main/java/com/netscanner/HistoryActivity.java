package com.netscanner;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.netscanner.net.PortScanner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Scan history + export. */
public class HistoryActivity extends AppCompatActivity {

    private final SimpleDateFormat fmt =
            new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        GlassWindow.apply(this);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));

        List<JSONObject> entries = new ArrayList<>();
        try {
            JSONArray hist = new JSONArray(getSharedPreferences("netscanner", 0)
                    .getString("history", "[]"));
            for (int i = 0; i < hist.length(); i++) entries.add(hist.getJSONObject(i));
        } catch (Exception ignored) {}

        TextView empty = findViewById(R.id.tv_empty);
        if (entries.isEmpty()) empty.setVisibility(View.VISIBLE);
        else {
            list.setAdapter(new RecyclerView.Adapter<H>() {
                @NonNull @Override public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                    return new H(LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_history, parent, false));
                }
                @Override public void onBindViewHolder(@NonNull H h, int pos) {
                    JSONObject e = entries.get(pos);
                    h.title.setText(fmt.format(new Date(e.optLong("ts"))) + "   ·   "
                            + e.optString("subnet") + "   ·   " + e.optInt("count") + " devices");
                    StringBuilder sb = new StringBuilder();
                    try {
                        JSONArray devs = e.getJSONArray("devices");
                        for (int i = 0; i < Math.min(devs.length(), 12); i++) {
                            JSONObject d = devs.getJSONObject(i);
                            sb.append("• ").append(d.optString("ip"))
                              .append(d.optString("mac").isEmpty() ? "" : "  (" + d.optString("mac") + ")")
                              .append("\n");
                        }
                        if (devs.length() > 12) sb.append("… +").append(devs.length() - 12).append(" more");
                    } catch (Exception ignored) {}
                    h.detail.setText(sb.toString().trim());
                    h.itemView.setOnClickListener(v -> exportEntry(e));
                }
                @Override public int getItemCount() { return entries.size(); }
            });
        }
    }

    private void exportEntry(JSONObject e) {
        try {
            StringBuilder csv = new StringBuilder("timestamp,subnet,ip,mac,type\n");
            JSONArray devs = e.getJSONArray("devices");
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(e.optLong("ts")));
            for (int i = 0; i < devs.length(); i++) {
                JSONObject d = devs.getJSONObject(i);
                csv.append('"').append(ts).append("\",\"").append(e.optString("subnet"))
                   .append("\",\"").append(d.optString("ip")).append("\",\"")
                   .append(d.optString("mac")).append("\",\"").append(d.optString("type")).append("\"\n");
            }
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/csv");
            i.putExtra(Intent.EXTRA_SUBJECT, "NetScanner scan " + ts);
            i.putExtra(Intent.EXTRA_TEXT, csv.toString());
            startActivity(Intent.createChooser(i, "Export scan"));
        } catch (Exception ignored) {}
    }

    static class H extends RecyclerView.ViewHolder {
        TextView title, detail;
        H(View v) { super(v); title = v.findViewById(R.id.t_title); detail = v.findViewById(R.id.t_detail); }
    }
}
