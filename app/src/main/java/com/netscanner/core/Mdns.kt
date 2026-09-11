package com.netscanner.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Blocking mDNS browse — returns map of host-address → service name. Port of legacy MdnsResolver. */
object Mdns {

    private val TYPES = arrayOf(
        "_googlecast._tcp", "_airplay._tcp", "_http._tcp", "_printer._tcp",
        "_smb._tcp", "_workstation._tcp", "_hap._tcp", "_spotify-connect._tcp"
    )

    private val Q = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var resolving = false

    fun resolve(ctx: Context, msTimeout: Long): Map<String, String> {
        val out = ConcurrentHashMap<String, String>()
        return try {
            val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return out
            val pending = AtomicInteger(TYPES.size)
            for (type in TYPES) {
                try {
                    nsd.discoverServices(
                        type, NsdManager.PROTOCOL_DNS_SD,
                        object : NsdManager.DiscoveryListener {
                            override fun onDiscoveryStarted(t: String) {}
                            override fun onDiscoveryStopped(t: String) {}
                            override fun onStartDiscoveryFailed(t: String, e: Int) { pending.decrementAndGet() }
                            override fun onStopDiscoveryFailed(t: String, e: Int) {}
                            override fun onServiceLost(s: NsdServiceInfo) {}
                            override fun onServiceFound(svc: NsdServiceInfo) { drain(nsd, svc, out) }
                        }
                    )
                } catch (e: Exception) {
                    pending.decrementAndGet()
                }
            }
            Thread.sleep(msTimeout)
            // best effort stop
            out
        } catch (e: Exception) {
            out
        }
    }

    private fun drain(nsd: NsdManager, first: NsdServiceInfo?, out: MutableMap<String, String>) {
        if (first != null) Q.add(first)
        if (resolving) return
        val next = Q.poll() ?: return
        resolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                    resolving = false
                    drain(nsd, null, out)
                }

                override fun onServiceResolved(i: NsdServiceInfo) {
                    try {
                        i.host?.let { out[it.hostAddress ?: ""] = i.serviceName }
                    } catch (ignored: Exception) {
                    }
                    resolving = false
                    drain(nsd, null, out)
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }
}
