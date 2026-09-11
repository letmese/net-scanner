package com.netscanner.core

import android.content.Context
import com.netscanner.ui.theme.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Tiny in-app ring buffer + crash-proof phase checkpoints. Port of legacy AppLog. */
object AppLog {
    private val LINES = ArrayDeque<String>()

    @Synchronized
    fun log(msg: String) {
        LINES.addLast(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg)
        while (LINES.size > 400) LINES.removeFirst()
    }

    /** Log + synchronously persist phase marker — survives native crashes and OOM kills. */
    fun cp(ctx: Context, phase: String) {
        log("phase: $phase")
        try {
            ctx.getSharedPreferences("netscanner", 0)
                .edit().putString("last_phase", phase).commit()
        } catch (ignored: Exception) {
        }
    }

    @Synchronized
    fun dump(): String {
        val sb = StringBuilder()
        for (l in LINES) sb.append(l).append('\n')
        return sb.toString()
    }
}

/**
 * SharedPreferences-backed persistence — identical keys/payloads as the
 * legacy app ("netscanner" prefs): scan history (30 entries), last scan CSV,
 * speed history (100 entries), Wi-Fi Watch toggle + baseline, night speed.
 */
object Stores {

    fun prefs(ctx: Context) = ctx.getSharedPreferences("netscanner", 0)

    // ---- Scan history ----
    data class ScanEntry(val ts: Long, val subnet: String, val count: Int, val devices: JSONArray) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ts", ts); put("subnet", subnet); put("count", count); put("devices", devices)
        }
    }

    fun saveScanHistory(ctx: Context, subnet: String, count: Int, devices: JSONArray) {
        try {
            val sp = prefs(ctx)
            val hist = JSONArray(sp.getString("history", "[]"))
            val e = JSONObject()
            e.put("ts", System.currentTimeMillis())
            e.put("subnet", "$subnet" + "0/24")
            e.put("count", count)
            e.put("devices", devices)
            val out = JSONArray()
            out.put(e)
            for (i in 0 until minOf(hist.length(), 29)) out.put(hist.get(i))
            sp.edit().putString("history", out.toString()).apply()
        } catch (ignored: Exception) {
        }
    }

    fun scanHistory(ctx: Context): List<ScanEntry> {
        val out = mutableListOf<ScanEntry>()
        try {
            val arr = JSONArray(prefs(ctx).getString("history", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(ScanEntry(o.getLong("ts"), o.getString("subnet"), o.getInt("count"), o.getJSONArray("devices")))
            }
        } catch (ignored: Exception) {
        }
        return out
    }

    fun saveLastScanCsv(ctx: Context, csv: String) {
        try {
            prefs(ctx).edit().putString("last_scan_csv", csv).apply()
        } catch (ignored: Exception) {
        }
    }

    fun lastScanCsv(ctx: Context): String = prefs(ctx).getString("last_scan_csv", "") ?: ""

    // ---- Speed history (entries {ts, down, up} Mbps) ----
    data class SpeedEntry(val ts: Long, val down: Double, val up: Double)

    fun saveSpeed(ctx: Context, down: Double, up: Double) {
        try {
            val sp = prefs(ctx)
            val hist = JSONArray(sp.getString("speed_hist", "[]"))
            val e = JSONObject()
            e.put("ts", System.currentTimeMillis())
            e.put("down", Math.round(down * 10) / 10.0)
            e.put("up", Math.round(up * 10) / 10.0)
            val out = JSONArray()
            out.put(e)
            for (i in 0 until minOf(hist.length(), 99)) out.put(hist.get(i))
            sp.edit().putString("speed_hist", out.toString()).apply()
        } catch (ignored: Exception) {
        }
    }

    fun speedHistory(ctx: Context): List<SpeedEntry> {
        val out = mutableListOf<SpeedEntry>()
        try {
            val arr = JSONArray(prefs(ctx).getString("speed_hist", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(SpeedEntry(o.getLong("ts"), o.getDouble("down"), o.getDouble("up")))
            }
        } catch (ignored: Exception) {
        }
        return out
    }

    // ---- Wi-Fi Watch ----
    fun watchEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("watch_enabled", false)

    fun setWatchEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("watch_enabled", on).apply()
    }

    fun resetBaseline(ctx: Context) {
        prefs(ctx).edit().putString("baseline", "").apply()
    }

    fun baseline(ctx: Context): String = prefs(ctx).getString("baseline", "") ?: ""

    fun setBaseline(ctx: Context, value: String) {
        prefs(ctx).edit().putString("baseline", value).apply()
    }

    // ---- Night speed ----
    fun nightSpeed(ctx: Context): Boolean = prefs(ctx).getBoolean("night_speed", false)

    fun setNightSpeed(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("night_speed", on).apply()
    }

    // ---- Appearance (Light / Dark / System) — persisted in Settings ----
    fun themeMode(ctx: Context): ThemeMode =
        try {
            ThemeMode.valueOf(prefs(ctx).getString("theme_mode", null) ?: ThemeMode.SYSTEM.name)
        } catch (ignored: Exception) {
            ThemeMode.SYSTEM
        }

    fun setThemeMode(ctx: Context, mode: ThemeMode) {
        prefs(ctx).edit().putString("theme_mode", mode.name).apply()
    }
}
