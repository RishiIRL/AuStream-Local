package com.austream.client

import android.app.Application
import android.net.wifi.WifiManager

class AuStreamApp : Application() {
    
    lateinit var multicastLock: WifiManager.MulticastLock
        private set
    
    override fun onCreate() {
        super.onCreate()
        
        // Acquire multicast lock to receive UDP multicast packets
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("AuStream")
        multicastLock.setReferenceCounted(true)
    }
    
    fun acquireMulticastLock() {
        if (!multicastLock.isHeld) {
            multicastLock.acquire()
        }
    }
    
    fun releaseMulticastLock() {
        if (multicastLock.isHeld) {
            multicastLock.release()
        }
    }
}
