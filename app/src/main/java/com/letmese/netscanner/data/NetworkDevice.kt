package com.letmese.netscanner.data

data class NetworkDevice(
    val ip: String,
    val name: String = "",
    val mac: String = "",
    val vendor: String = "Unknown",
    val openPorts: String = "",
    val isOnline: Boolean = true,
    val lastSeen: Long = System.currentTimeMillis()
) {
    var isExpanded: Boolean = false
    
    fun getDisplayName(): String {
        return if (name.isNotBlank()) name else "Unknown Device"
    }
}