package com.austream.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Network utility functions for AuStream server.
 * Handles detection of the best local IP address for client connections.
 */
object NetworkUtils {
    
    // Patterns to identify virtual/unwanted network adapters
    private val virtualAdapterPatterns = listOf(
        "virtual", "vmware", "virtualbox", "hyper-v", "vethernet", "loopback"
    )
    
    /**
     * Get the best local IPv4 address for streaming.
     * Prioritizes: WiFi → Ethernet → Any physical adapter.
     * Skips virtual adapters (VirtualBox, VMware, Hyper-V, etc.)
     *
     * @return The IP address as a string, or "Unknown" if detection fails.
     */
    fun getLocalIpv4Address(): String {
        return getPreferredNetworkAddress()?.hostAddress
            ?: try { InetAddress.getLocalHost().hostAddress } catch (_: Exception) { "Unknown" }
    }

    /**
     * Determine the preferred network address by scanning available interfaces.
     */
    private fun getPreferredNetworkAddress(): InetAddress? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            
            // First: WiFi adapters
            findAddressInInterfaces(interfaces) { name ->
                !isVirtualAdapter(name) && isWifiAdapter(name)
            }?.let { return it }
            
            // Second: Ethernet adapters
            findAddressInInterfaces(interfaces) { name ->
                !isVirtualAdapter(name) && isEthernetAdapter(name)
            }?.let { return it }
            
            // Third: Any physical adapter
            findAddressInInterfaces(interfaces) { name ->
                !isVirtualAdapter(name) && !name.contains("local area connection")
            }
        } catch (_: Exception) {
            null
        }
    }
    
    /**
     * Find a usable IPv4 address in the list of network interfaces.
     */
    private fun findAddressInInterfaces(
        interfaces: List<NetworkInterface>,
        nameFilter: (String) -> Boolean
    ): InetAddress? {
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.displayName.lowercase()
            
            if (!nameFilter(name)) continue
            
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    return addr
                }
            }
        }
        return null
    }
    
    private fun isVirtualAdapter(name: String): Boolean {
        return virtualAdapterPatterns.any { name.contains(it) }
    }
    
    private fun isWifiAdapter(name: String): Boolean {
        return name.contains("wi-fi") || name.contains("wifi") || 
               name.contains("wlan") || name.contains("wireless")
    }
    
    private fun isEthernetAdapter(name: String): Boolean {
        return name.contains("ethernet") || name.contains("eth")
    }
}
