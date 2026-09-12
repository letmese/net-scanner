package com.netscanner.svc

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.netscanner.MainActivity
import com.netscanner.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v5 Cell Monitor foreground service.
 *
 * v5.1.3 -- mechanism port of the PROVEN legacy v4.7/4.8 pipeline
 * (CellMonitorActivity.poll/parse + CellMonitorService):
 *
 *  - Sampling source is TelephonyManager.getAllCellInfo() polled at 1 Hz,
 *    NOT TelephonyManager.getSignalStrength() + SignalStrength.getDbm()
 *    reflection. The legacy app never had a "no dBm" problem because every
 *    CellInfo carries its own public CellSignalStrength getters
 *    (getDbm/getRsrp/getRsrq/getRssnr/getSsRsrp...) that always report.
 *  - Per-SIM serving pick is the legacy PLMN -> sticky -> rank-best
 *    algorithm (modems report ALL radios in every getAllCellInfo list;
 *    the pick logic prevents handing SIM 1's tower to SIM 2).
 *  - Per tick the service publishes Serving snapshots, per-SIM graph
 *    samples, neighbor lines and tower-change events into CellStore --
 *    exactly what the legacy UI fed per tick.
 *
 * The status-bar notification mirrors the store (legacy CellMonitorService).
 * Listener/callback paths are gone -- the legacy pipeline needs none.
 */
class CellService : android.app.Service() {

    private class SimCtx(
        val subId: Int,
        val tm: TelephonyManager,
        val label: String,
        val mcc: String,
        val mnc: String
    ) {
        var lastCellKey: String = ""
    }

    private var sims: List<SimCtx> = emptyList()
    private var baseTm: TelephonyManager? = null
    private var sampler: Thread? = null
    private val handler = Handler(Looper.getMainLooper())

    private var lastNotifDbm = Int.MAX_VALUE
    private var lastNotifBars = -1
    private var lastNotifAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "stop requested -- shutting down sampler")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d(TAG, "onStartCommand -- starting legacy-style 1 Hz getAllCellInfo sampler")
        startAsForeground()
        running = true
        resolveSims()
        startSampler()
        return START_STICKY
    }

    /** Legacy resolveSims(): active subscriptions when phone access is granted, else the default SIM. */
    private fun resolveSims() {
        val out = ArrayList<SimCtx>()
        val base = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        baseTm = base
        try {
            val phonePerm = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
            val sm = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val subs: List<SubscriptionInfo>? =
                if (sm != null && phonePerm) sm.activeSubscriptionInfoList else null
            subs?.forEach { si ->
                if (out.size >= 2) return@forEach
                val mcc = try { si.mccString ?: "" } catch (_: Throwable) { "" }
                val mnc = try { si.mncString ?: "" } catch (_: Throwable) { "" }
                val name = si.displayName?.toString()?.takeIf { it.isNotBlank() } ?: ""
                out.add(
                    SimCtx(
                        si.subscriptionId,
                        base.createForSubscriptionId(si.subscriptionId),
                        "SIM ${si.simSlotIndex + 1}" + (if (name.isEmpty()) "" else " · $name"),
                        mcc, mnc
                    )
                )
            }
        } catch (_: Throwable) {
        }
        if (out.isEmpty()) out.add(SimCtx(Int.MIN_VALUE, base, "Active SIM", "", ""))
        sims = out
        Log.d(TAG, "resolveSims: ${out.size} SIM(s) [${out.joinToString { it.label }}]")
    }

    private fun startSampler() {
        if (sampler?.isAlive == true) return
        sampler = Thread {
            Log.d(TAG, "1 Hz getAllCellInfo sampler started")
            var firstLogged = false
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    poll()
                    if (!firstLogged) {
                        Log.d(TAG, "first tick done -- serving0=${CellStore.serving0 != null}")
                        firstLogged = true
                    }
                } catch (e: Exception) {
                    CellStore.setNoSource("sampler error: ${e.message}")
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
            Log.d(TAG, "sampler exited")
        }.apply { name = "cell-sampler"; isDaemon = true; start() }
    }

    // ---------- legacy poll() pipeline ----------

    private fun safeCells(tm: TelephonyManager): List<CellInfo>? = try {
        tm.allCellInfo
    } catch (_: Throwable) {
        null
    }

    private fun rank(ci: CellInfo): Int = when {
        Build.VERSION.SDK_INT >= 29 && ci is CellInfoNr -> 5
        ci is CellInfoLte -> 4
        ci is CellInfoWcdma -> 3
        ci is CellInfoGsm -> 2
        ci is CellInfoCdma -> 1
        else -> 0
    }

    private fun plmnMatches(s: SimCtx, ci: CellInfo): Boolean {
        if (s.mcc.isEmpty()) return false
        val id = ci.cellIdentity
        // Base CellIdentity mcc/mncString are API29+ and missing from this
        // compile stub; resolve reflectively (null on older devices -> the
        // sticky/rank fallback picks the serving cell).
        val m = idString(id, "mccString") ?: return false
        val n = idString(id, "mncString") ?: return false
        return m == s.mcc && n == s.mnc
    }

    private fun idString(id: Any, m: String): String? = try {
        id.javaClass.getMethod(m).invoke(id) as? String
    } catch (_: Throwable) {
        null
    }

    private fun idInt(id: Any, m: String): Int = try {
        (id.javaClass.getMethod(m).invoke(id) as? Number)?.toInt() ?: Int.MAX_VALUE
    } catch (_: Throwable) {
        Int.MAX_VALUE
    }

    /** Legacy per-tick pipeline: per-SIM serving pick, neighbors, events, graph samples. */
    private fun poll() {
        CellStore.beginTick()
        val claimed = HashSet<String>()
        val allNb = ArrayList<String>()

        sims.forEachIndexed { i, s ->
            val cells = safeCells(s.tm)
            var sv: Serving? = null
            val nb = ArrayList<String>()
            if (cells == null) {
                CellStore.setNoSource(
                    "getAllCellInfo returned null -- grant Location permission and disable airplane mode"
                )
            } else if (cells.isEmpty()) {
                CellStore.setNoSource(
                    "Empty cell list -- airplane mode? No SIM on this slot?"
                )
            } else {
                var plmnBest: CellInfo? = null
                var stickyBest: CellInfo? = null
                var bestCi: CellInfo? = null
                for (ci in cells) {
                    if (!ci.isRegistered) continue
                    val k = identityKey(ci)
                    if (claimed.contains(k)) continue
                    if (bestCi == null || rank(ci) > rank(bestCi)) bestCi = ci
                    if (k == s.lastCellKey) stickyBest = ci
                    if (plmnMatches(s, ci) &&
                        (plmnBest == null || rank(ci) > rank(plmnBest))
                    ) plmnBest = ci
                }
                val prevKey = s.lastCellKey
                val chosen = plmnBest ?: stickyBest ?: bestCi
                if (chosen != null) {
                    sv = parse(chosen)
                    s.lastCellKey = sv.identityKey
                    claimed.add(sv.identityKey)
                } else {
                    s.lastCellKey = ""
                }
                // Legacy tower-change log event: fires when the serving cell
                // identity changed since the previous tick.
                if (sv != null && prevKey.isNotEmpty() && s.lastCellKey != prevKey) {
                    val carrier = try {
                        s.tm.networkOperatorName ?: ""
                    } catch (_: Throwable) {
                        ""
                    }
                    CellStore.appendEvent(
                        tickEvent(System.currentTimeMillis(), i, sv, carrier)
                    )
                }
                for (ci in cells) {
                    if (!ci.isRegistered) neighborLine(ci)?.let { nb.add(it) }
                }
            }
            CellStore.setSim(i, sv)
            allNb.addAll(nb)
        }

        val seen = LinkedHashSet(allNb)
        CellStore.setNeighbors(seen.toList())

        handler.post { throttledNotif() }
    }

    private fun throttledNotif() {
        val dbm = CellStore.lastDbm
        val bars = CellStore.lastBars
        val now = System.currentTimeMillis()
        if (dbm == Int.MAX_VALUE) return
        if (dbm != lastNotifDbm || bars != lastNotifBars || now - lastNotifAt > 15_000) {
            lastNotifDbm = dbm
            lastNotifBars = bars
            lastNotifAt = now
            updateNotif(dbm, bars)
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHAN, "Cell Monitor", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHAN) else Notification.Builder(this)
        b.setContentTitle("Signal monitor")
            .setContentText("Measuring cell signal...")
            .setSmallIcon(R.drawable.ic_stat_net)
            .setOngoing(true)
            .setContentIntent(pi)
        startForeground(NOTIF_ID, b.build())
    }

    private fun updateNotif(dbm: Int, bars: Int) {
        val op = try {
            baseTm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        val tech = try {
            networkTypeName(baseTm?.dataNetworkType ?: 0)
        } catch (_: Exception) {
            "?"
        }
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHAN) else Notification.Builder(this)
        val big = android.text.SpannableString("$dbm dBm · ${barsStr(bars)}\n$op · $tech")
        b.setContentTitle("Signal: $dbm dBm ${barsStr(bars)}")
            .setContentText("$op · $tech")
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setSmallIcon(R.drawable.ic_stat_net)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_ID, b.build())
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy -- stopping sampler")
        running = false
        sampler?.interrupt()
        sampler = null
        super.onDestroy()
    }

    // ---------- legacy parse(): CellInfo -> Serving ----------

    private fun u(v: Int?): String = if (v == null || v == Int.MAX_VALUE) "?" else v.toString()

    private fun identityKey(ci: CellInfo): String {
        val id = ci.cellIdentity
        return when {
            Build.VERSION.SDK_INT >= 29 && ci is CellInfoNr -> {
                val nid = id as? CellIdentityNr
                "NR|PCI${nid?.pci}|NCI${nid?.nci}"
            }
            ci is CellInfoLte -> "LTE|PCI${(id as CellIdentityLte).pci}|E${(id as CellIdentityLte).earfcn}"
            ci is CellInfoWcdma -> "W|PSC${(id as CellIdentityWcdma).psc}|U${(id as CellIdentityWcdma).uarfcn}"
            ci is CellInfoGsm -> "G|CID${(id as CellIdentityGsm).cid}|A${(id as CellIdentityGsm).arfcn}"
            ci is CellInfoCdma -> "C|BSID${(id as CellIdentityCdma).basestationId}|${(id as CellIdentityCdma).networkId}"
            else -> ci.javaClass.simpleName
        }
    }

    private fun parse(ci: CellInfo): Serving = when {
        Build.VERSION.SDK_INT >= 29 && ci is CellInfoNr -> parseNr(ci)
        ci is CellInfoLte -> parseLte(ci)
        ci is CellInfoWcdma -> parseWcdma(ci)
        ci is CellInfoGsm -> parseGsm(ci)
        ci is CellInfoCdma -> parseCdma(ci)
        else -> Serving("?", -1, -1, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, -1, "?", "?")
    }

    private fun bandOf(vararg raw: Int): Int =
        raw.firstOrNull { it != Int.MAX_VALUE && it > 0 } ?: -1

    private fun parseLte(ci: CellInfoLte): Serving {
        val ss: CellSignalStrengthLte = ci.cellSignalStrength
        val id: CellIdentityLte = ci.cellIdentity
        val rsrp = ss.rsrp
        val dbm = if (rsrp != Int.MAX_VALUE) rsrp else ss.dbm
        val band = bandOf(
            idInt(id, "band"),   // getBand() missing from stub -> reflective
            try {
                if (Build.VERSION.SDK_INT >= 28) id.bands.firstOrNull() ?: Int.MAX_VALUE
                else Int.MAX_VALUE
            } catch (_: Throwable) {
                Int.MAX_VALUE
            }
        )
        return Serving(
            "LTE",
            if (dbm == Int.MAX_VALUE) -1 else dbm,
            try { ss.asuLevel } catch (_: Throwable) { -1 },
            rsrp, ss.rsrq, ss.rssnr,
            band,
            "LTE|PCI${id.pci}|E${id.earfcn}",
            "PCI ${u(id.pci)} · EARFCN ${u(id.earfcn)}"
        )
    }

    private fun parseNr(ci: CellInfoNr): Serving {
        // Stub returns the base CellSignalStrength from CellInfoNr; cast to NR.
        val ss = ci.cellSignalStrength as? CellSignalStrengthNr
        val id = ci.cellIdentity as? CellIdentityNr
        if (ss == null || id == null) {
            val dbm = try { ci.cellSignalStrength.dbm } catch (_: Throwable) { Int.MAX_VALUE }
            return Serving(
                "NR",
                if (dbm == Int.MAX_VALUE) -1 else dbm, -1,
                Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, -1, "NR", "NR cell"
            )
        }
        val rsrp = ss.ssRsrp
        val dbm = if (rsrp != Int.MAX_VALUE) rsrp else ss.dbm
        val band = bandOf(
            try { id.bands?.firstOrNull() ?: Int.MAX_VALUE } catch (_: Throwable) { Int.MAX_VALUE }
        )
        return Serving(
            "NR",
            if (dbm == Int.MAX_VALUE) -1 else dbm,
            try { ss.asuLevel } catch (_: Throwable) { -1 },
            rsrp, ss.ssRsrq, ss.ssSinr,
            band,
            "NR|PCI${id.pci}|NCI${id.nci}",
            "PCI ${u(id.pci)} · NCI ${id.nci}"
        )
    }

    private fun parseWcdma(ci: CellInfoWcdma): Serving {
        val ss: CellSignalStrengthWcdma = ci.cellSignalStrength
        val id: CellIdentityWcdma = ci.cellIdentity
        // Legacy note: WCDMA getDbm() reports CPICH RSCP
        val dbm = ss.dbm
        return Serving(
            "WCDMA",
            if (dbm == Int.MAX_VALUE) -1 else dbm,
            try { ss.asuLevel } catch (_: Throwable) { -1 },
            Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE,
            bandOf(try { id.uarfcn } catch (_: Throwable) { Int.MAX_VALUE }),
            "W|PSC${id.psc}|U${id.uarfcn}",
            "PSC ${u(id.psc)} · UARFCN ${u(id.uarfcn)}"
        )
    }

    private fun parseGsm(ci: CellInfoGsm): Serving {
        val ss: CellSignalStrengthGsm = ci.cellSignalStrength
        val id: CellIdentityGsm = ci.cellIdentity
        val rssi = try { ss.rssi } catch (_: Throwable) { Int.MAX_VALUE }
        val dbm = if (rssi != Int.MAX_VALUE) rssi else ss.dbm
        return Serving(
            "GSM",
            if (dbm == Int.MAX_VALUE) -1 else dbm,
            try { ss.asuLevel } catch (_: Throwable) { -1 },
            Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE,
            bandOf(try { id.arfcn } catch (_: Throwable) { Int.MAX_VALUE }),
            "G|CID${id.cid}|A${id.arfcn}",
            "CID ${u(id.cid)} · ARFCN ${u(id.arfcn)}"
        )
    }

    private fun parseCdma(ci: CellInfoCdma): Serving {
        val ss: CellSignalStrengthCdma = ci.cellSignalStrength
        val id: CellIdentityCdma = ci.cellIdentity
        val dbm = ss.dbm
        return Serving(
            "CDMA",
            if (dbm == Int.MAX_VALUE) -1 else dbm,
            -1,
            Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE,
            -1,
            "C|BSID${id.basestationId}|${id.networkId}",
            "BSID ${u(id.basestationId)} · NID ${u(id.networkId)}"
        )
    }

    private fun neighborLine(ci: CellInfo): String? = try {
        val id = ci.cellIdentity
        when {
            Build.VERSION.SDK_INT >= 29 && ci is CellInfoNr -> {
                val nid = id as? CellIdentityNr
                "5G NR  PCI ${u(nid?.pci)}  ·  ${ci.cellSignalStrength.dbm} dBm (neighbor)"
            }
            ci is CellInfoLte ->
                "LTE    PCI ${u((id as CellIdentityLte).pci)}  EARFCN ${u(id.earfcn)}  ·  ${ci.cellSignalStrength.dbm} dBm (neighbor)"
            ci is CellInfoGsm ->
                "GSM    ARFCN ${u((id as CellIdentityGsm).arfcn)}  BSIC ${u(idInt(id, "bsic"))}  ·  ${ci.cellSignalStrength.dbm} dBm (neighbor)"
            ci is CellInfoWcdma ->
                "WCDMA  PSC ${u((id as CellIdentityWcdma).psc)}  ·  ${ci.cellSignalStrength.dbm} dBm (neighbor)"
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val TAG = "CellService"
        const val ACTION_STOP = "com.netscanner.CELL_STOP"
        const val CHAN = "cellmon"
        const val NOTIF_ID = 42

        @JvmStatic
        @Volatile
        var running: Boolean = false

        /** Legacy tick event format for the Log tab. */
        @JvmStatic
        fun tickEvent(ts: Long, simIndex: Int, sv: Serving, carrier: String): String {
            val tag = if (simIndex >= 0) "[S${simIndex + 1}]" else ""
            return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts)) +
                " $tag ⤳ ${sv.rat} ${sv.shortId} · ${carrier.ifBlank { "?" }} · ${sv.dbm} dBm"
        }

        @JvmStatic
        fun barsOf(dbm: Int): Int = when {
            dbm >= -85 -> 4
            dbm >= -95 -> 3
            dbm >= -105 -> 2
            dbm != Int.MAX_VALUE && dbm != -1 -> 1
            else -> 0
        }

        @JvmStatic
        fun barsStr(bars: Int): String =
            if (bars <= 0) "no signal" else "▮".repeat(bars) + "▯".repeat(4 - bars)

        @JvmStatic
        fun networkTypeName(t: Int): String = when (t) {
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            else -> "unknown"
        }
    }
}
