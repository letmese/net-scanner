package com.netscanner;

/** Device type guessing from vendor + hostname. Returns an emoji + label. */
public final class DeviceTypes {

    public static String emoji(Device d) {
        if (d.isSelf) return "📱";
        String v = VendorDb.vendor(d.mac);
        if (v == null && d.host != null) v = d.host;
        if (v == null) return "💻";
        String s = v.toLowerCase();
        if (s.contains("raspberry")) return "🥧";
        if (v.contains("Espressif") || s.contains("tuya") || s.contains("arduino")) return "🤖";
        if (s.contains("tp-link") || s.contains("asus") || s.contains("netgear")
                || s.contains("d-link") || s.contains("mikrotik") || s.contains("ubiquiti")
                || s.contains("huawei") || s.contains("technicolor") || s.contains("arris")
                || s.contains("sercomm") || s.contains("cisco") || s.contains("router")
                || s.contains("gateway") || s.contains("openwrt")) return "📡";
        if (s.contains("apple") || s.contains("google") || s.contains("xiaomi")
                || s.contains("samsung") || s.contains("oppo") || s.contains("oneplus")
                || s.contains("vivo") || s.contains("honor") || s.contains("motorola")
                || s.contains("pixel") || s.contains("iphone") || s.contains("ipad")
                || s.contains("android")) return "📱";
        if (s.contains("vmware") || s.contains("virtualbox") || s.contains("qemu")) return "🖥";
        return "💻";
    }

    public static String label(Device d) {
        if (d.isSelf) return "This phone";
        String v = VendorDb.vendor(d.mac);
        if (v != null) return v;
        if (d.host != null) return d.host;
        return "Host " + d.lastOctet();
    }
}
