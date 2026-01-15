package com.austream.network

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple NTP-like clock synchronization server.
 * Clients can query this server to determine clock offset for audio sync.
 */
class ClockSyncServer(
    private val port: Int = 5005
) {
    private var socket: DatagramSocket? = null
    private var serverJob: Job? = null
    private var isRunning = false
    
    // Track connected clients by their address
    private val clients = ConcurrentHashMap<String, Long>()
    
    companion object {
        // Response structure:
        // [8 bytes] Client T1 timestamp (echoed back)
        // [8 bytes] Server T2 timestamp (receive time)
        // [8 bytes] Server T3 timestamp (transmit time)
        const val RESPONSE_SIZE = 24
        const val REQUEST_SIZE = 8
    }
    
    /**
     * Start the clock sync server
     */
    fun start(scope: CoroutineScope) {
        if (isRunning) return
        
        socket = DatagramSocket(port)
        isRunning = true
        
        serverJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(REQUEST_SIZE)
            
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    val receiveTime = System.nanoTime() // T2
                    
                    // Parse client's T1 timestamp
                    val t1Buffer = ByteBuffer.wrap(packet.data, 0, packet.length)
                    val clientT1 = t1Buffer.long
                    
                    // Track this client
                    val clientKey = "${packet.address.hostAddress}:${packet.port}"
                    clients[clientKey] = System.currentTimeMillis()
                    
                    // Send response with T1, T2, T3
                    val transmitTime = System.nanoTime() // T3
                    val response = ByteBuffer.allocate(RESPONSE_SIZE)
                    response.putLong(clientT1)      // Echo T1
                    response.putLong(receiveTime)   // T2
                    response.putLong(transmitTime)  // T3
                    
                    val responsePacket = DatagramPacket(
                        response.array(),
                        RESPONSE_SIZE,
                        packet.address,
                        packet.port
                    )
                    socket?.send(responsePacket)
                    
                } catch (e: Exception) {
                    if (isRunning) {
                        e.printStackTrace()
                    }
                }
            }
        }
        
        // Cleanup old clients periodically
        scope.launch {
            while (isRunning) {
                delay(30000) // Every 30 seconds
                val cutoff = System.currentTimeMillis() - 60000 // 1 minute timeout
                clients.entries.removeIf { it.value < cutoff }
            }
        }
    }
    
    /**
     * Stop the clock sync server
     */
    fun stop() {
        isRunning = false
        serverJob?.cancel()
        serverJob = null
        socket?.close()
        socket = null
        clients.clear()
    }
    
    /**
     * Get count of recently active clients
     */
    fun getClientCount(): Int = clients.size
    
    fun isRunning(): Boolean = isRunning
}
