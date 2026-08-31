package com.letmese.netscanner.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        private const val PORT_TIMEOUT_MS = 500L
        private const val HOST_TIMEOUT_MS = 300L
    }

    interface ScanCallback {
        fun onDeviceFound(device: NetworkDevice)
        fun onScanComplete(devices: List<NetworkDevice>)
        fun onError(error: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun scanNetwork(callback: ScanCallback) {
        scope.launch {
            try {
                val localIp = withContext(Dispatchers.IO) { getLocalIpAddress() }
                if (localIp == null) {
                    callback.onError("Could not determine local IP address")
                    return@launch
                }

                val subnet = localIp.substringBeforeLast('.')
                val ips = (1..254).map { "$subnet.$it" }

                // Ping sweep in parallel; collect reachable IPs
                val reachableIps = ips.map { ip ->
                    async {
                        if (pingHost(ip)) ip else null
                    }
                }.awaitAll().filterNotNull()

                val devices = withContext(Dispatchers.IO) {
                    reachableIps.mapNotNull { ip ->
                        scanDeviceSync(ip)
                    }
                }

                devices.forEach { callback.onDeviceFound(it) }
                callback.onScanComplete(devices)
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                callback.onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Synchronous, non-suspend version of scanDevice. Caller must invoke from
     * a coroutine context (already wrapped in withContext(Dispatchers.IO) above).
     */
    private fun scanDeviceSync(ip: String): NetworkDevice? {
        val name = getHostnameFromIp(ip) ?: "Unknown"
        val mac = getMacFromArp(ip) ?: "Unknown"
        val vendor = lookupVendor(mac)
        val openPorts = scanCommonPorts(ip)
        return NetworkDevice(
            ip = ip,
            name = name,
            mac = mac,
            vendor = vendor,
            isReachable = true,
            openPorts = openPorts
        )
    }

    private fun pingHost(ip: String): Boolean {
        return try {
            val address = InetAddress.getByName(ip)
            address.isReachable(HOST_TIMEOUT_MS.toInt())
        } catch (e: Exception) {
            false
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLocalIpAddress error", e)
        }
        return null
    }

    private fun getHostnameFromIp(ip: String): String? {
        return try {
            val address = InetAddress.getByName(ip)
            address.hostName?.takeIf { it != ip }
        } catch (e: Exception) {
            null
        }
    }

    private fun getMacFromArp(ip: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("ip neigh")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line != null && line.contains(ip)) {
                    val parts = line.split("\\s+".toRegex()).dropLastWhile { it.isBlank() }
                    if (parts.size >= 3) {
                        val mac = parts[2]
                        if (mac.matches("\\S+:\\S+:\\S+:\\S+:\\S+:\\S+".toRegex())) {
                            return mac
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun lookupVendor(mac: String): String {
        val prefix = mac.take(8).uppercase()
        return when (prefix) {
            "00:1A:2B" -> "Apple"
            "00:50:F2" -> "Microsoft"
            "00:0C:29" -> "VMware"
            "00:1B:44" -> "Apple"
            "00:25:00" -> "Apple"
            "B8:27:EB" -> "Raspberry Pi"
            "DC:A6:32" -> "Raspberry Pi"
            "00:4D:32" -> "Apple"
            "00:4E:7A" -> "Apple"
            "00:4F:9D" -> "Apple"
            else -> "Unknown"
        }
    }

    private fun scanCommonPorts(ip: String): List<Int> {
        return COMMON_PORTS.filter { port -> isPortOpen(ip, port) }
    }

    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            withTimeoutOrNull(PORT_TIMEOUT_MS) {
                Socket().apply {
                    connect(java.net.InetSocketAddress(ip, port), PORT_TIMEOUT_MS.toInt())
                    close()
                }
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun getNetworkInfo(): NetworkInfo {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        return NetworkInfo(
            ssid = (wifiInfo.ssid ?: "Unknown").replace("\"", ""),
            bssid = wifiInfo.bssid ?: "Unknown",
            localIp = getLocalIpAddress() ?: "Unknown",
            gateway = getGateway(),
            subnetMask = getSubnetMask(),
            dns = getDns(),
            signalStrength = wifiInfo.rssi,
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
                if (line != null && line.startsWith("default")) {
                    val parts = line.split("\\s+".toRegex()).dropLastWhile { it.isBlank() }
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
                if (line != null && line.contains("inet ") && !line.contains("127.0.0.1")) {
                    val parts = line.split("\\s+".toRegex()).dropLastWhile { it.isBlank() }
                    for (part in parts) {
                        if (part.contains("/")) {
                            val cidr = part.split("/")[1].toInt()
                            return cidrToSubnet(cidr)
                        }
                    }
                }
            }
            "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun cidrToSubnet(cidr: Int): String {
        val mask = (0xFFFFFFFF shl (32 - cidr)) and 0xFFFFFFFFL
        return "${(mask shr 24) and 0xFF}.${(mask shr 16) and 0xFF}.${(mask shr 8) and 0xFF}.${mask and 0xFF}"
    }

    private fun getDns(): String {
        return try {
            val process = Runtime.getRuntime().exec("getprop net.dns1")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.readLine()?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
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
) {
    val isConnected: Boolean
        get() = ssid != "Unknown" && ssid != "<unknown ssid>"
}
