package com.letmese.netscanner.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class NetworkScanner(private val context: Context) {

    companion object {
        private const val TAG = "NetworkScanner"
        // Common ports to scan
        private val COMMON_PORTS = intArrayOf(
            21, 22, 23, 25, 53, 80, 110, 135, 139, 143, 443, 445,
            993, 995, 1723, 3306, 3389, 5432, 5900, 8080, 8443
        )
        private val PORT_TIMEOUT_MS = 500L
    }

    interface ScanCallback {
        fun onDeviceFound(device: NetworkDevice)
        fun onScanComplete(devices: List<NetworkDevice>)
        fun onError(error: String)
    }

    fun scanNetwork(callback: ScanCallback) = CoroutineScope(Dispatchers.IO).launch {
        try {
            val localIp = getLocalIpAddress()
            if (localIp == null) {
                callback.onError("Could not determine local IP address")
                return@launch
            }

            val subnet = getSubnet(localIp)
            val devices = mutableListOf<NetworkDevice>()

            // Add local device first
            val localDevice = getLocalDeviceInfo(localIp)
            devices.add(localDevice)
            callback.onDeviceFound(localDevice)

            // Scan subnet for other devices
            val ipsToScan = generateIpsToScan(subnet)
            
            for (ip in ipsToScan) {
                if (ip != localIp) {
                    val device = scanDevice(ip)
                    if (device != null) {
                        devices.add(device)
                        callback.onDeviceFound(device)
                    }
                }
            }

            callback.onScanComplete(devices)
        } catch (e: Exception) {
            Log.e(TAG, "Scan error", e)
            callback.onError(e.message ?: "Unknown error")
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return null
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
            
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                return intToIp(wifiInfo.ipAddress)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                val addresses = Collections.list(iface.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Fallback IP detection failed", e)
            null
        }
    }

    private fun intToIp(ip: Int): String {
        return ((ip and 0xFF).toString() + "." +
                ((ip shr 8) and 0xFF).toString() + "." +
                ((ip shr 16) and 0xFF).toString() + "." +
                ((ip shr 24) and 0xFF).toString())
    }

    private fun getSubnet(ip: String): String {
        val parts = ip.split(".")
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }

    private fun generateIpsToScan(subnet: String): List<String> {
        return (1..254).map { "$subnet$it" }
    }

    private fun getLocalDeviceInfo(ip: String): NetworkDevice {
        var name = getHostname()
        if (name.isBlank()) name = "This Device"
        
        val mac = getMacAddress()
        val vendor = lookupVendor(mac)
        
        return NetworkDevice(
            ip = ip,
            name = name,
            mac = mac,
            vendor = vendor,
            isOnline = true
        )
    }

    private fun getHostname(): String {
        return try {
            InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            ""
        }
    }

    private fun getMacAddress(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isLoopback && iface.hardwareAddress != null) {
                    return iface.hardwareAddress!!.joinToString(":") { String.format("%02X", it) }
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun lookupVendor(mac: String): String {
        // Simple vendor lookup - in production use OUI database
        if (mac.isBlank()) return "Unknown"
        val oui = mac.substring(0, 8).uppercase()
        return when (oui) {
            "00:1A:2B" -> "Intel"
            "00:1B:63" -> "Apple"
            "00:21:29" -> "ASUS"
            "00:23:24" -> "Apple"
            "00:25:00" -> "Apple"
            "00:26:08" -> "Apple"
            "00:26:4A" -> "Dell"
            "00:26:B6" -> "Apple"
            "00:27:0E" -> "Apple"
            "00:27:10" -> "Apple"
            "00:27:32" -> "Apple"
            "00:27:56" -> "Apple"
            "00:27:75" -> "Apple"
            "00:27:AB" -> "Apple"
            "00:27:C4" -> "Apple"
            "00:28:AF" -> "Apple"
            "00:29:22" -> "Apple"
            "00:29:43" -> "Apple"
            "00:29:81" -> "Apple"
            "00:2A:95" -> "Apple"
            "00:2B:62" -> "Apple"
            "00:2C:B3" -> "Apple"
            "00:2D:07" -> "Apple"
            "00:2E:35" -> "Apple"
            "00:2F:3A" -> "Apple"
            "00:30:48" -> "Apple"
            "00:31:46" -> "Apple"
            "00:32:89" -> "Apple"
            "00:33:88" -> "Apple"
            "00:34:17" -> "Apple"
            "00:35:87" -> "Apple"
            "00:36:76" -> "Apple"
            "00:37:2D" -> "Apple"
            "00:38:E7" -> "Apple"
            "00:39:3C" -> "Apple"
            "00:3A:9F" -> "Apple"
            "00:3B:8D" -> "Apple"
            "00:3C:93" -> "Apple"
            "00:3D:2F" -> "Apple"
            "00:3E:E1" -> "Apple"
            "00:3F:5A" -> "Apple"
            "00:40:0A" -> "Apple"
            "00:40:96" -> "Apple"
            "00:41:5A" -> "Apple"
            "00:42:6E" -> "Apple"
            "00:43:3A" -> "Apple"
            "00:44:89" -> "Apple"
            "00:45:0A" -> "Apple"
            "00:46:36" -> "Apple"
            "00:47:4B" -> "Apple"
            "00:48:6A" -> "Apple"
            "00:49:7E" -> "Apple"
            "00:4A:8D" -> "Apple"
            "00:4B:46" -> "Apple"
            "00:4C:5E" -> "Apple"
            "00:4D:32" -> "Apple"
            "00:4E:7A" -> "Apple"
            "00:4F:9D" -> "Apple"
            else -> "Unknown"
        }
    }

    private fun scanDevice(ip: String): NetworkDevice? {
        return withContext(Dispatchers.IO) {
            // Quick ping check
            if (!pingHost(ip)) return@withContext null
            
            val name = getHostnameFromIp(ip)
            val mac = getMacFromArp(ip)
            val vendor = lookupVendor(mac)
            val openPorts = scanCommonPorts(ip)
            
            NetworkDevice(
                ip = ip,
                name = name,
                mac = mac,
                vendor = vendor,
                openPorts = openPorts.joinToString(", "),
                isOnline = true
            )
        }
    }

    private fun pingHost(ip: String): Boolean {
        return try {
            val address = InetAddress.getByName(ip)
            address.isReachable(1000)
        } catch (e: Exception) {
            false
        }
    }

    private fun getHostnameFromIp(ip: String): String {
        return try {
            val address = InetAddress.getByName(ip)
            val hostname = address.canonicalHostName
            if (hostname != ip) hostname else "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun getMacFromArp(ip: String): String {
        return try {
            val process = Runtime.getRuntime().exec("arp -n $ip")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.contains(ip)) {
                    val parts = line.split("\\s+".toRegex()).dropLastWhile { it.isBlank() }.toList()
                    if (parts.size >= 3) {
                        val mac = parts[2]
                        if (mac.matches("\\S+:\\S+:\\S+:\\S+:\\S+:\\S+".toRegex())) {
                            return mac
                        }
                    }
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun scanCommonPorts(ip: String): List<Int> {
        val openPorts = mutableListOf<Int>()
        
        for (port in COMMON_PORTS) {
            if (isPortOpen(ip, port)) {
                openPorts.add(port)
            }
        }
        
        return openPorts
    }

    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            withTimeout(PORT_TIMEOUT_MS) {
                Socket().apply {
                    connect(java.net.InetSocketAddress(ip, port), PORT_TIMEOUT_MS.toInt())
                    close()
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getNetworkInfo(): NetworkInfo {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        return NetworkInfo(
            ssid = wifiInfo.ssid.replace("\"", ""),
            bssid = wifiInfo.bssid,
            localIp = getLocalIpAddress() ?: "Unknown",
            gateway = getGateway(),
            subnetMask = getSubnetMask(),
            dns = getDns(),
            signalStrength = wifiManager.calculateSignalLevel(wifiInfo.rssi, 5),
            frequency = wifiInfo.frequency,
            linkSpeed = "${wifiInfo.linkSpeed} Mbps"
        )
    }

    private fun getGateway(): String {
        return try {
            val process = Runtime.getRuntime().exec("ip route")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.startsWith("default")) {
                    val parts = line.split("\\s+".toRegex()).dropLastWhile { it.isBlank() }.toList()
                    if (parts.size >= 3) return parts[2]
                }
            }
            "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun getSubnetMask(): String {
        return try {
            val process = Runtime.getRuntime().exec("ip addr")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.contains("inet ") && !line.contains("127.0.0.1")) {
                    val parts = line.split("\\s+".toRegex()).dropLastWhile { it.isBlank() }.toList()
                    for (part in parts) {
                        if (part.contains("/")) {
                            val cidr = part.split("/")[1].toInt()
                            return cidrToMask(cidr)
                        }
                    }
                }
            }
            "255.255.255.0"
        } catch (e: Exception) {
            "255.255.255.0"
        }
    }

    private fun cidrToMask(cidr: Int): String {
        val mask = (0xFFFFFFFF shl (32 - cidr)) and 0xFFFFFFFF
        return ((mask shr 24) and 0xFF).toString() + "." +
               ((mask shr 16) and 0xFF).toString() + "." +
               ((mask shr 8) and 0xFF).toString() + "." +
               (mask and 0xFF).toString()
    }

    private fun getDns(): String {
        return try {
            val process = Runtime.getRuntime().exec("getprop net.dns1")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val dns = reader.readLine() ?: "Unknown"
            dns.trim()
        } catch (e: Exception) {
            "Unknown"
        }
    }

    data class NetworkInfo(
        val ssid: String,
        val bssid: String,
        val localIp: String,
        val gateway: String,
        val subnetMask: String,
        val dns: String,
        val signalStrength: Int,
        val frequency: Int,
        val linkSpeed: String
    )
}