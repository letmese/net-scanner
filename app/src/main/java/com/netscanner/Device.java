package com.netscanner;

/** A discovered host on the LAN. */
public class Device {
    public String ip;
    public String mac;       // may be null
    public String host;      // hostname (resolved)
    public boolean isSelf;
    public boolean reachable;
    public String risk;      // e.g. "Telnet open"
    public String guess;     // e.g. "likely IP camera"

    public Device(String ip) { this.ip = ip; }

    public String lastOctet() {
        int i = ip.lastIndexOf('.');
        return ip.substring(i + 1);
    }
}
