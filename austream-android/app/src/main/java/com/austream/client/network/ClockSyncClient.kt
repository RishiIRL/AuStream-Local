package com.austream.client.network

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * NTP-like clock synchronization client.
 * Calculates clock offset between this device and the server for audio sync.
 */
class ClockSyncClient(
    private val serverAddress: String,
    private val serverPort: Int = 5005
) {
    private var syncJob: Job? = null
    private var socket: DatagramSocket? = null
    
    // Clock offset in nanoseconds (positive = server ahead, negative = server behind)
    private val _clockOffset = AtomicLong(0)
    val clockOffset: Long get() = _clockOffset.get()
    
    // Round-trip time in nanoseconds
    private val _roundTripTime = AtomicLong(0)
    val roundTripTime: Long get() = _roundTripTime.get()
    
    companion object {
        private const val TAG = "ClockSyncClient"
        private const val SYNC_INTERVAL_MS = 2000L
        private const val REQUEST_SIZE = 8
        private const val RESPONSE_SIZE = 24
    }
    
    /**
     * Start periodic clock synchronization
     */
    fun start(scope: CoroutineScope) {
        syncJob?.cancel()
        
        syncJob = scope.launch(Dispatchers.IO) {
            socket = DatagramSocket()
            socket?.soTimeout = 1000
            
            while (isActive) {
                try {
                    performSync()
                } catch (_: Exception) { }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Perform a single clock sync exchange
     */
    private fun performSync() {
        val socket = this.socket ?: return
        
        val serverAddr = InetAddress.getByName(serverAddress)
        
        // T1: Client send time
        val t1 = System.nanoTime()
        
        // Send request with T1
        val requestBuffer = ByteBuffer.allocate(REQUEST_SIZE)
        requestBuffer.putLong(t1)
        
        val requestPacket = DatagramPacket(
            requestBuffer.array(),
            REQUEST_SIZE,
            serverAddr,
            serverPort
        )
        socket.send(requestPacket)
        
        // Receive response
        val responseBuffer = ByteArray(RESPONSE_SIZE)
        val responsePacket = DatagramPacket(responseBuffer, RESPONSE_SIZE)
        socket.receive(responsePacket)
        
        // T4: Client receive time
        val t4 = System.nanoTime()
        
        // Parse response
        val buffer = ByteBuffer.wrap(responsePacket.data)
        val echoT1 = buffer.long  // T1 echoed back
        val t2 = buffer.long       // Server receive time
        val t3 = buffer.long       // Server send time
        
        // Calculate clock offset using NTP formula
        // offset = ((T2 - T1) + (T3 - T4)) / 2
        val offset = ((t2 - echoT1) + (t3 - t4)) / 2
        _clockOffset.set(offset)
        
        // Calculate round-trip time
        val rtt = (t4 - echoT1) - (t3 - t2)
        _roundTripTime.set(rtt)
    }
    
    /**
     * Convert a server timestamp to local time
     */
    fun serverToLocalTime(serverTimestamp: Long): Long {
        return serverTimestamp - clockOffset
    }
    
    /**
     * Stop clock synchronization
     */
    fun stop() {
        syncJob?.cancel()
        syncJob = null
        socket?.close()
        socket = null
    }
}
