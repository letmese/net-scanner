package com.netscanner.core

/**
 * nmap-class device/OS fingerprinting — root-free multi-signal classifier.
 * v5.2.0.
 *
 * Raw-socket TCP/IP stack fingerprinting (nmap -O) is impossible without
 * root on Android, so the verdict is produced from a weighted multi-signal
 * score: open-port heuristics, service banners, mDNS / SSDP / NetBIOS
 * probes, hostname patterns and the OUI vendor. Confidence reflects how
 * strongly the signals agree.
 */

enum class DeviceKind { PHONE, TABLET, PC, TV, ROUTER, PRINTER, NAS, CAMERA, MEDIA, SERVER, IOT, UNKNOWN }
enum class OsGuess { WINDOWS, MACOS, LINUX, ANDROID, IOS, ROUTER_FW, PRINTER_FW, UNKNOWN }
enum class Confidence { LOW, MEDIUM, HIGH }

/** Immutable verdict consumed by the UI and stored on Device. */
data class Fingerprint(
    val icon: String,
    val kindLabel: String,
    val osLabel: String,
    val confidence: Confidence,
    val reasons: List<String>
)

object DeviceFingerprint {

    fun classify(
        ip: String,
        host: String?,
        mac: String?,
        isSelf: Boolean,
        ports: List<PortResult>,
        mdnsName: String? = null,
        ssdp: NetUtils.SsdpDevice? = null,
        netbiosName: String? = null
    ): Fingerprint {
        val open = ports.filter { it.state == "OPEN" }.map { it.port }.toSet()
        val banners = ports.filter { it.state == "OPEN" && it.banner != null }
            .joinToString(" | ") { it.banner!!.lowercase() }
        val vendor = VendorDb.vendor(mac)?.lowercase()
        val hn = (host ?: "").lowercase()
        val md = (mdnsName ?: "").lowercase()

        val kindScore = HashMap<DeviceKind, Int>()
        val osScore = HashMap<OsGuess, Int>()
        val reasons = mutableListOf<String>()

        fun bump(k: DeviceKind, o: OsGuess?, kd: Int, od: Int, why: String) {
            if (kd > 0) {
                kindScore[k] = (kindScore[k] ?: 0) + kd
                if (kindScore[k] == kd || (kindScore[k] ?: 0) >= 5) reasons.add(why)
            }
            if (o != null && od > 0) osScore[o] = (osScore[o] ?: 0) + od
        }

        if (isSelf) bump(DeviceKind.PHONE, OsGuess.ANDROID, 9, 4, "this device")

        // ── hostname patterns ──
        when {
            Regex("desktop-|win-|-pc$|^pc-|^win-").containsMatchIn(hn) ->
                bump(DeviceKind.PC, OsGuess.WINDOWS, 3, 3, "hostname \"$host\"")
            hn.contains("iphone") ->
                bump(DeviceKind.PHONE, OsGuess.IOS, 5, 5, "hostname \"$host\"")
            hn.contains("ipad") ->
                bump(DeviceKind.TABLET, OsGuess.IOS, 5, 5, "hostname \"$host\"")
            hn.startsWith("android-") || hn.startsWith("android_") ->
                bump(DeviceKind.PHONE, OsGuess.ANDROID, 4, 4, "hostname \"$host\"")
            hn.contains("macbook") || hn.startsWith("mac-") || hn.contains("imac") ->
                bump(DeviceKind.PC, OsGuess.MACOS, 4, 4, "hostname \"$host\"")
            hn.contains("router") || hn.contains("gateway") || hn.contains("openwrt") ||
                hn.contains("fritz") || hn.contains("unifi") || hn.contains("mikrotik") ->
                bump(DeviceKind.ROUTER, OsGuess.ROUTER_FW, 4, 3, "hostname \"$host\"")
            hn.startsWith("hp") || hn.startsWith("brn") || hn.startsWith("brother") ||
                hn.startsWith("brw") || hn.contains("epson") || hn.contains("canon") ||
                hn.contains("pixma") || hn.contains("laserjet") ->
                bump(DeviceKind.PRINTER, OsGuess.PRINTER_FW, 3, 3, "hostname \"$host\"")
            hn.contains("synology") || hn.contains("diskstation") ||
                hn.contains("qnap") || hn.contains("nas") ->
                bump(DeviceKind.NAS, OsGuess.LINUX, 4, 2, "hostname \"$host\"")
            hn.startsWith("esp_") || hn.startsWith("tuya") || hn.startsWith("wled") ->
                bump(DeviceKind.IOT, null, 3, 0, "hostname \"$host\"")
        }

        // ── open-port heuristics ──
        if (3389 in open) bump(DeviceKind.PC, OsGuess.WINDOWS, 3, 3, "RDP (3389) open")
        if (135 in open || (445 in open && 139 in open))
            bump(DeviceKind.PC, OsGuess.WINDOWS, 2, 2, "MS-RPC/SMB open")
        if (62078 in open) bump(DeviceKind.PHONE, OsGuess.IOS, 5, 5, "Apple lockdown port 62078")
        if (5555 in open) bump(DeviceKind.PHONE, OsGuess.ANDROID, 3, 3, "Android ADB on 5555")
        if (9100 in open || 631 in open || 515 in open)
            bump(DeviceKind.PRINTER, OsGuess.PRINTER_FW, 4, 3, "printer service open")
        if (5000 in open && 5001 in open)
            bump(DeviceKind.NAS, OsGuess.LINUX, 3, 1, "DSM-style pair 5000/5001")
        if (32400 in open) bump(DeviceKind.MEDIA, OsGuess.LINUX, 4, 1, "Plex server 32400")
        if (554 in open) bump(DeviceKind.CAMERA, null, 2, 0, "RTSP stream 554")
        if (8008 in open || 8009 in open)
            bump(DeviceKind.TV, OsGuess.ANDROID, 2, 1, "Chromecast protocol ports")
        if (53 in open && (22 in open || 80 in open || 443 in open))
            bump(DeviceKind.ROUTER, OsGuess.ROUTER_FW, 2, 2, "DNS + management web")
        if (1900 in open || 5000 in open) bump(DeviceKind.ROUTER, OsGuess.ROUTER_FW, 1, 1, "UPnP endpoint")
        if (23 in open) bump(DeviceKind.ROUTER, null, 1, 0, "Telnet open (router/IoT class)")
        if (2049 in open && 445 in open) bump(DeviceKind.NAS, OsGuess.LINUX, 2, 1, "NFS + SMB")
        if (111 in open) bump(DeviceKind.SERVER, OsGuess.LINUX, 1, 1, "rpcbind open (Unix)")

        // ── banner heuristics ──
        if (banners.isNotEmpty()) {
            if (banners.contains("microsoft-iis") || banners.contains("microsoft-httpapi"))
                bump(DeviceKind.SERVER, OsGuess.WINDOWS, 3, 4, "IIS banner")
            if (banners.contains("openssh"))
                bump(DeviceKind.SERVER, OsGuess.LINUX, 1, 2, "OpenSSH banner")
            if (banners.contains("synology"))
                bump(DeviceKind.NAS, OsGuess.LINUX, 5, 3, "Synology banner")
            if (banners.contains("plex"))
                bump(DeviceKind.MEDIA, OsGuess.LINUX, 5, 2, "Plex banner")
            if (banners.contains("lighttpd") || banners.contains("boa") ||
                banners.contains("goahead") || banners.contains("mini_httpd"))
                bump(DeviceKind.IOT, OsGuess.ROUTER_FW, 1, 1, "embedded httpd banner")
            if (banners.contains("busybox") || banners.contains("utelnetd"))
                bump(DeviceKind.IOT, OsGuess.LINUX, 2, 1, "BusyBox banner")
            if (banners.contains("friendlyname") || banners.contains("upnp"))
                bump(DeviceKind.ROUTER, OsGuess.ROUTER_FW, 1, 1, "UPnP description")
            if (banners.contains("airprint") || banners.contains("ipp"))
                bump(DeviceKind.PRINTER, OsGuess.PRINTER_FW, 3, 2, "AirPrint/IPP banner")
        }

        // ── OUI vendor ──
        if (vendor != null) {
            when {
                vendor == "apple" -> bump(DeviceKind.PHONE, OsGuess.IOS, 2, 2, "Apple OUI")
                vendor == "espressif" || vendor == "tuya" ->
                    bump(DeviceKind.IOT, null, 4, 0, "$vendor chip OUI")
                vendor == "raspberry pi" -> bump(DeviceKind.PC, OsGuess.LINUX, 3, 3, "Raspberry Pi OUI")
                vendor == "synology" || vendor == "qnap" ->
                    bump(DeviceKind.NAS, OsGuess.LINUX, 5, 2, "vendor OUI ($vendor)")
                vendor == "brother" || vendor == "seiko epson" || vendor == "hp" ->
                    bump(DeviceKind.PRINTER, OsGuess.PRINTER_FW, 3, 3, "vendor OUI ($vendor)")
                vendor in setOf(
                    "tp-link", "netgear", "asus", "d-link", "mikrotik", "ubiquiti",
                    "huawei", "technicolor", "arris", "sercomm", "cisco", "tenda"
                ) -> bump(DeviceKind.ROUTER, OsGuess.ROUTER_FW, 2, 2, "network-gear OUI ($vendor)")
                vendor in setOf(
                    "samsung", "xiaomi", "google", "oppo", "oneplus", "vivo", "motorola"
                ) -> bump(DeviceKind.PHONE, OsGuess.ANDROID, 2, 2, "phone vendor OUI ($vendor)")
            }
        }

        // ── mDNS ──
        if (md.isNotEmpty()) {
            when {
                md.contains("chromecast") || md.contains("google tv") || md.contains("nest") ->
                    bump(DeviceKind.TV, OsGuess.ANDROID, 4, 3, "mDNS: \"$mdnsName\"")
                md.contains("apple tv") || md.contains("airplay") ->
                    bump(DeviceKind.TV, OsGuess.IOS, 3, 3, "mDNS: \"$mdnsName\"")
                md.contains("printer") || md.contains("ipp") ->
                    bump(DeviceKind.PRINTER, OsGuess.PRINTER_FW, 3, 3, "mDNS: \"$mdnsName\"")
                md.contains("homekit") || md.contains("_hap") ->
                    bump(DeviceKind.IOT, null, 2, 0, "HomeKit accessory")
                md.contains("spotify") -> bump(DeviceKind.MEDIA, null, 2, 0, "Spotify Connect")
            }
        }

        // ── SSDP / UPnP ──
        ssdp?.let {
            val s = "${it.deviceType ?: ""} ${it.model ?: ""}".lowercase()
            when {
                s.contains("internetgateway") ->
                    bump(DeviceKind.ROUTER, OsGuess.ROUTER_FW, 5, 4, "SSDP gateway device")
                s.contains("mediarenderer") ->
                    bump(DeviceKind.TV, null, 3, 0, "SSDP media renderer")
                s.contains("mediaserver") ->
                    bump(DeviceKind.NAS, OsGuess.LINUX, 2, 1, "SSDP media server")
                s.contains("printer") ->
                    bump(DeviceKind.PRINTER, OsGuess.PRINTER_FW, 4, 3, "SSDP printer")
            }
        }

        // ── NetBIOS ──
        if (netbiosName != null)
            bump(DeviceKind.PC, OsGuess.WINDOWS, 2, 2, "NetBIOS name \"$netbiosName\"")

        // ── verdict ──
        val kind = kindScore.maxByOrNull { it.value }?.key ?: DeviceKind.UNKNOWN
        val os = osScore.maxByOrNull { it.value }?.key ?: OsGuess.UNKNOWN
        val kBest = kindScore[kind] ?: 0
        val oBest = osScore[os] ?: 0
        val conf = when {
            kBest >= 5 || oBest >= 5 -> Confidence.HIGH
            kBest >= 3 || oBest >= 3 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return Fingerprint(
            icon = iconFor(kind),
            kindLabel = labelFor(kind),
            osLabel = osLabelFor(os, conf),
            confidence = conf,
            reasons = reasons.distinct().take(3)
        )
    }

    private fun iconFor(k: DeviceKind): String = when (k) {
        DeviceKind.PHONE -> "📱"
        DeviceKind.TABLET -> "📲"
        DeviceKind.PC -> "🖥"
        DeviceKind.TV -> "📺"
        DeviceKind.ROUTER -> "📡"
        DeviceKind.PRINTER -> "🖨"
        DeviceKind.NAS -> "💾"
        DeviceKind.CAMERA -> "📷"
        DeviceKind.MEDIA -> "🎬"
        DeviceKind.SERVER -> "🗄"
        DeviceKind.IOT -> "💡"
        DeviceKind.UNKNOWN -> "❔"
    }

    private fun labelFor(k: DeviceKind): String = when (k) {
        DeviceKind.PHONE -> "Phone"
        DeviceKind.TABLET -> "Tablet"
        DeviceKind.PC -> "PC / laptop"
        DeviceKind.TV -> "TV / streamer"
        DeviceKind.ROUTER -> "Router / gateway"
        DeviceKind.PRINTER -> "Printer"
        DeviceKind.NAS -> "NAS"
        DeviceKind.CAMERA -> "Camera"
        DeviceKind.MEDIA -> "Media server"
        DeviceKind.SERVER -> "Server"
        DeviceKind.IOT -> "IoT gadget"
        DeviceKind.UNKNOWN -> ""
    }

    private fun osLabelFor(o: OsGuess, conf: Confidence): String {
        val base = when (o) {
            OsGuess.WINDOWS -> "Windows"
            OsGuess.MACOS -> "macOS"
            OsGuess.LINUX -> "Linux"
            OsGuess.ANDROID -> "Android"
            OsGuess.IOS -> "iOS"
            OsGuess.ROUTER_FW -> "router firmware"
            OsGuess.PRINTER_FW -> "printer firmware"
            OsGuess.UNKNOWN -> return ""
        }
        return when (conf) {
            Confidence.HIGH -> base
            Confidence.MEDIUM -> "probably $base"
            Confidence.LOW -> "maybe $base"
        }
    }
}
