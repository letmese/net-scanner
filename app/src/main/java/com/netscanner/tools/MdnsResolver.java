package com.netscanner.tools;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Blocking mDNS browse — returns map of host-address -> service name. */
public final class MdnsResolver {

    private static final String[] TYPES = {
            "_googlecast._tcp", "_airplay._tcp", "_http._tcp", "_printer._tcp",
            "_smb._tcp", "_workstation._tcp", "_hap._tcp", "_spotify-connect._tcp"
    };

    private MdnsResolver() {}

    public static Map<String, String> resolve(Context ctx, long msTimeout) {
        Map<String, String> out = new ConcurrentHashMap<>();
        try {
            NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return out;
            AtomicInteger pending = new AtomicInteger(TYPES.length);
            final NsdManager[] mgr = {nsd};
            for (String type : TYPES) {
                try {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD,
                            new NsdManager.DiscoveryListener() {
                                @Override public void onDiscoveryStarted(String t) {}
                                @Override public void onDiscoveryStopped(String t) {}
                                @Override public void onStartDiscoveryFailed(String t, int e) { pending.decrementAndGet(); }
                                @Override public void onStopDiscoveryFailed(String t, int e) {}
                                @Override public void onServiceLost(NsdServiceInfo s) {}
                                @Override public void onServiceFound(NsdServiceInfo svc) { drain(mgr[0], svc, out); }
                            });
                } catch (Exception e) { pending.decrementAndGet(); }
            }
            Thread.sleep(msTimeout);
            // best effort stop
            return out;
        } catch (Exception e) {
            return out;
        }
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo> Q =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile boolean resolving;

    private static void drain(NsdManager nsd, NsdServiceInfo first, Map<String, String> out) {
        if (first != null) Q.add(first);
        if (resolving) return;
        NsdServiceInfo next = Q.poll();
        if (next == null) return;
        resolving = true;
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int e) { resolving = false; drain(nsd, null, out); }
                @Override public void onServiceResolved(NsdServiceInfo i) {
                    try {
                        if (i.getHost() != null)
                            out.put(i.getHost().getHostAddress(), i.getServiceName());
                    } catch (Exception ignored) {}
                    resolving = false;
                    drain(nsd, null, out);
                }
            });
        } catch (Exception e) { resolving = false; }
    }
}
