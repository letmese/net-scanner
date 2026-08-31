package com.netscanner;

import android.content.Intent;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class LogsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        TextView tv = findViewById(R.id.tv_log);
        tv.setMovementMethod(new ScrollingMovementMethod());

        StringBuilder sb = new StringBuilder();
        String crash = getSharedPreferences("netscanner", 0).getString("last_crash", null);
        sb.append("=== LAST CRASH ===\n");
        sb.append(crash == null ? "(none saved)\n" : crash.trim() + "\n");
        String phase = getSharedPreferences("netscanner", 0).getString("last_phase", null);
        sb.append("=== LAST PHASE ===\n");
        sb.append(phase == null ? "(none)\n" : phase + "\n");
        sb.append("\n=== EVENT LOG ===\n");
        String ev = AppLog.dump();
        sb.append(ev.isEmpty() ? "(empty)" : ev);
        tv.setText(sb.toString());

        findViewById(R.id.btn_share).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "NetScanner logs");
            i.putExtra(Intent.EXTRA_TEXT, tv.getText().toString());
            startActivity(Intent.createChooser(i, "Share logs"));
        });
    }
}
