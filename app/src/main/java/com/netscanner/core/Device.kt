package com.netscanner.core

/** A discovered host on the LAN. Port of legacy Device/DeviceTypes/VendorDb. */

data class Device(
    val ip: String,
    var mac: String? = null,      // may be null
    var host: String? = null,     // hostname (resolved)
    var isSelf: Boolean = false,
    var reachable: Boolean = false,
    var risk: String? = null,     // e.g. "Telnet open"
    var guess: String? = null     // e.g. "likely IP camera"
) {
    fun lastOctet(): String = ip.substring(ip.lastIndexOf('.') + 1)
}

object VendorDb {
    private val OUI = mapOf(
        // Apple
        "F0:18:98" to "Apple", "AC:DE:48" to "Apple", "A4:83:E7" to "Apple",
        "D0:03:4B" to "Apple", "F8:FF:C2" to "Apple", "78:FD:94" to "Apple",
        "00:1A:11" to "Google", "F4:F5:D8" to "Google", "30:FD:38" to "Google",
        "DC:A6:32" to "Raspberry Pi", "B8:27:EB" to "Raspberry Pi", "E4:5F:01" to "Raspberry Pi",
        "08:00:27" to "VirtualBox", "52:54:00" to "QEMU/KVM", "00:0C:29" to "VMware",
        "00:50:56" to "VMware", "00:05:69" to "VMware",
        // Routers/network gear
        "C8:D7:19" to "TP-Link", "50:C7:BF" to "TP-Link", "AC:84:C6" to "TP-Link",
        "14:CC:20" to "TP-Link", "A0:F3:C1" to "TP-Link", "F4:F2:6D" to "TP-Link",
        "04:D4:C4" to "TP-Link",
        "00:1A:2B" to "Ayecom", "04:8D:38" to "Asus", "AC:22:0B" to "Asus",
        "40:B0:76" to "Asus", "50:46:5D" to "Asus", "88:D7:F6" to "Asus",
        "BC:EE:7B" to "Asus", "C8:60:00" to "Asus", "D8:50:E6" to "Asus",
        "FC:EC:DA" to "Ubiquiti", "24:A4:3C" to "Ubiquiti", "74:AC:B9" to "Ubiquiti",
        "68:D7:9A" to "Ubiquiti", "B4:FB:E4" to "Ubiquiti",
        "00:24:01" to "Netgear", "9C:3D:CF" to "Netgear", "A0:40:A0" to "Netgear",
        "B0:39:56" to "Netgear", "C4:04:15" to "Netgear", "20:4E:7F" to "Qualcomm",
        "44:94:FC" to "Netgear", "6C:B0:CE" to "Netgear",
        "00:1F:33" to "Netgear", "A0:63:91" to "Netgear",
        "34:6B:D3" to "D-Link", "C8:D3:A3" to "D-Link", "00:17:9A" to "D-Link",
        "28:10:7B" to "SerComm", "74:9E:AF" to "SerComm",
        "00:0D:B9" to "PC Engines", "E0:63:DA" to "MikroTik", "64:D1:54" to "MikroTik",
        "CC:2D:E0" to "MikroTik", "48:8F:5A" to "MikroTik",
        "00:26:4A" to "Arris", "58:23:8C" to "Arris",
        "F0:9F:C2" to "Huawei", "28:6E:D4" to "Huawei", "34:6F:24" to "Huawei",
        "78:1D:BA" to "Huawei", "C8:0C:C8" to "Huawei",
        "88:66:A5" to "Technicolor", "00:03:E8" to "Technicolor",
        // Phones / tablets
        "38:C9:86" to "Samsung", "40:0E:85" to "Samsung", "50:85:69" to "Samsung",
        "84:38:35" to "Samsung", "A8:06:00" to "Samsung", "F4:7B:5E" to "Samsung",
        "18:E8:29" to "Xiaomi", "28:ED:6A" to "Xiaomi", "64:09:80" to "Xiaomi",
        "78:02:F8" to "Xiaomi", "AC:C1:EE" to "Xiaomi", "EC:D0:9F" to "Xiaomi",
        "F8:A4:5F" to "Xiaomi", "50:2B:73" to "Oppo", "C0:11:73" to "Oppo",
        "08:FC:88" to "OnePlus", "48:BF:6B" to "OnePlus", "64:A2:F9" to "OnePlus",
        "30:FD:B2" to "Vivo", "3C:5A:B4" to "Google", "54:60:09" to "Honor",
        "20:82:C0" to "Motorola", "44:23:07" to "Intel", "98:FA:E8" to "Intel",
        "A0:AF:BD" to "Intel", "D4:6A:6A" to "Intel", "84:16:F9" to "TP-Link",
        "3C:97:0E" to "Wistron", "24:69:68" to "AzureWave", "00:1D:7E" to "Cisco",
        "58:97:1E" to "Cisco", "F8:66:F2" to "Cisco", "00:25:45" to "Cisco",
        // IoT
        "24:0A:C4" to "Espressif", "5C:CF:7F" to "Espressif", "30:AE:A4" to "Espressif",
        "BC:DD:C2" to "Espressif", "68:C6:3A" to "Espressif", "B4:E6:2D" to "Tuya",
        "10:D5:61" to "Realtek", "00:E0:4C" to "Realtek", "52:54:AB" to "Realtek"
    )

    /** Vendor for a MAC, or null. Key = first 3 octets uppercase. */
    fun vendor(mac: String?): String? {
        if (mac == null || mac.length < 8) return null
        return OUI[mac.substring(0, 8).uppercase()]
    }
}

object DeviceTypes {
    /** Device type guessing from vendor + hostname. Returns an emoji + label. */
    fun emoji(d: Device): String {
        if (d.isSelf) return "📱"
        var v = VendorDb.vendor(d.mac)
        if (v == null && d.host != null) v = d.host
        if (v == null) return "💻"
        val s = v.lowercase()
        if (s.contains("raspberry")) return "🥧"
        if (v.contains("Espressif") || s.contains("tuya") || s.contains("arduino")) return "🤖"
        if (s.contains("tp-link") || s.contains("asus") || s.contains("netgear") ||
            s.contains("d-link") || s.contains("mikrotik") || s.contains("ubiquiti") ||
            s.contains("huawei") || s.contains("technicolor") || s.contains("arris") ||
            s.contains("sercomm") || s.contains("cisco") || s.contains("router") ||
            s.contains("gateway") || s.contains("openwrt")
        ) return "📡"
        if (s.contains("apple") || s.contains("google") || s.contains("xiaomi") ||
            s.contains("samsung") || s.contains("oppo") || s.contains("oneplus") ||
            s.contains("vivo") || s.contains("honor") || s.contains("motorola") ||
            s.contains("pixel") || s.contains("iphone") || s.contains("ipad") ||
            s.contains("android")
        ) return "📱"
        if (s.contains("vmware") || s.contains("virtualbox") || s.contains("qemu")) return "🖥"
        return "💻"
    }

    fun label(d: Device): String {
        if (d.isSelf) return "This phone"
        val v = VendorDb.vendor(d.mac)
        if (v != null) return v
        if (d.host != null) return d.host!!
        return "Host " + d.lastOctet()
    }
}
