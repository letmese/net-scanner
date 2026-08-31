package com.netscanner;

import java.util.HashMap;
import java.util.Map;

/** Mini OUI database — top vendor MAC prefixes. Key = first 3 octets uppercase. */
public final class VendorDb {
    private static final Map<String, String> OUI = new HashMap<>();
    static {
        // Apple
        OUI.put("F0:18:98","Apple"); OUI.put("AC:DE:48","Apple"); OUI.put("A4:83:E7","Apple");
        OUI.put("D0:03:4B","Apple"); OUI.put("F8:FF:C2","Apple"); OUI.put("78:FD:94","Apple");
        OUI.put("00:1A:11","Google"); OUI.put("F4:F5:D8","Google"); OUI.put("30:FD:38","Google");
        OUI.put("DC:A6:32","Raspberry Pi"); OUI.put("B8:27:EB","Raspberry Pi"); OUI.put("E4:5F:01","Raspberry Pi");
        OUI.put("08:00:27","VirtualBox"); OUI.put("52:54:00","QEMU/KVM"); OUI.put("00:0C:29","VMware");
        OUI.put("00:50:56","VMware"); OUI.put("00:05:69","VMware");
        // Routers/network gear
        OUI.put("C8:D7:19","TP-Link"); OUI.put("50:C7:BF","TP-Link"); OUI.put("AC:84:C6","TP-Link");
        OUI.put("14:CC:20","TP-Link"); OUI.put("A0:F3:C1","TP-Link"); OUI.put("F4:F2:6D","TP-Link");
        OUI.put("04:D4:C4","TP-Link");
        OUI.put("00:1A:2B","Ayecom"); OUI.put("04:8D:38","Asus"); OUI.put("AC:22:0B","Asus");
        OUI.put("40:B0:76","Asus"); OUI.put("50:46:5D","Asus"); OUI.put("88:D7:F6","Asus");
        OUI.put("BC:EE:7B","Asus"); OUI.put("C8:60:00","Asus"); OUI.put("D8:50:E6","Asus");
        OUI.put("FC:EC:DA","Ubiquiti"); OUI.put("24:A4:3C","Ubiquiti"); OUI.put("74:AC:B9","Ubiquiti");
        OUI.put("68:D7:9A","Ubiquiti"); OUI.put("B4:FB:E4","Ubiquiti");
        OUI.put("00:24:01","Netgear"); OUI.put("9C:3D:CF","Netgear"); OUI.put("A0:40:A0","Netgear");
        OUI.put("B0:39:56","Netgear"); OUI.put("C4:04:15","Netgear"); OUI.put("20:4E:7F","Qualcomm");
        OUI.put("44:94:FC","Netgear"); OUI.put("6C:B0:CE","Netgear");
        OUI.put("00:1F:33","Netgear"); OUI.put("A0:63:91","Netgear");
        OUI.put("34:6B:D3","D-Link"); OUI.put("C8:D3:A3","D-Link"); OUI.put("00:17:9A","D-Link");
        OUI.put("28:10:7B","SerComm"); OUI.put("74:9E:AF","SerComm");
        OUI.put("00:0D:B9","PC Engines"); OUI.put("E0:63:DA","MikroTik"); OUI.put("64:D1:54","MikroTik");
        OUI.put("CC:2D:E0","MikroTik"); OUI.put("48:8F:5A","MikroTik");
        OUI.put("00:26:4A","Arris"); OUI.put("58:23:8C","Arris");
        OUI.put("F0:9F:C2","Huawei"); OUI.put("28:6E:D4","Huawei"); OUI.put("34:6F:24","Huawei");
        OUI.put("78:1D:BA","Huawei"); OUI.put("C8:0C:C8","Huawei");
        OUI.put("88:66:A5","Technicolor"); OUI.put("00:03:E8","Technicolor");
        // Phones / tablets
        OUI.put("38:C9:86","Samsung"); OUI.put("40:0E:85","Samsung"); OUI.put("50:85:69","Samsung");
        OUI.put("84:38:35","Samsung"); OUI.put("A8:06:00","Samsung"); OUI.put("F4:7B:5E","Samsung");
        OUI.put("18:E8:29","Xiaomi"); OUI.put("28:ED:6A","Xiaomi"); OUI.put("64:09:80","Xiaomi");
        OUI.put("78:02:F8","Xiaomi"); OUI.put("AC:C1:EE","Xiaomi"); OUI.put("EC:D0:9F","Xiaomi");
        OUI.put("F8:A4:5F","Xiaomi"); OUI.put("50:2B:73","Oppo"); OUI.put("C0:11:73","Oppo");
        OUI.put("08:FC:88","OnePlus"); OUI.put("48:BF:6B","OnePlus"); OUI.put("64:A2:F9","OnePlus");
        OUI.put("30:FD:B2","Vivo"); OUI.put("3C:5A:B4","Google"); OUI.put("54:60:09","Honor");
        OUI.put("20:82:C0","Motorola"); OUI.put("44:23:07","Intel"); OUI.put("98:FA:E8","Intel");
        OUI.put("A0:AF:BD","Intel"); OUI.put("D4:6A:6A","Intel"); OUI.put("84:16:F9","TP-Link");
        OUI.put("3C:97:0E","Wistron"); OUI.put("24:69:68","AzureWave"); OUI.put("00:1D:7E","Cisco");
        OUI.put("58:97:1E","Cisco"); OUI.put("F8:66:F2","Cisco"); OUI.put("00:25:45","Cisco");
        // IoT
        OUI.put("24:0A:C4","Espressif"); OUI.put("5C:CF:7F","Espressif"); OUI.put("30:AE:A4","Espressif");
        OUI.put("BC:DD:C2","Espressif"); OUI.put("68:C6:3A","Espressif"); OUI.put("B4:E6:2D","Tuya");
        OUI.put("10:D5:61","Realtek"); OUI.put("00:E0:4C","Realtek"); OUI.put("52:54:AB","Realtek");
    }
    private static final Map<String,String> M = new HashMap<>(OUI);

    public static String vendor(String mac) {
        if (mac == null || mac.length() < 8) return null;
        return M.get(mac.substring(0, 8).toUpperCase());
    }
}
