package com.austream.network

import com.austream.security.SecurityManager
import com.austream.util.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.SharedFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Represents a connected client with its own send channel
 */
data class ConnectedClientInfo(
    val address: SocketAddress,
    val lastSeen: Long = System.currentTimeMillis(),
    val authenticated: Boolean = false,
    val sendChannel: Channel<ByteArray> = Channel(capacity = 50, onBufferOverflow = BufferOverflow.DROP_OLDEST),
    var sendJob: Job? = null
)

/**
 * Secure UDP server for streaming encrypted audio to authenticated clients.
 * 
 * Security features:
 * - PIN-based authentication (client must send correct PIN hash)
 * - AES-256-GCM encryption for all audio packets
 * - Each client has dedicated send channel for fair delivery
 */
class MulticastServer(
    private val port: Int = 5004,
    private val securityManager: SecurityManager = SecurityManager(),
    var bufferSizeMs: Int = 50  // Configurable sync buffer for clients
) {
    private var socket: DatagramSocket? = null
    private var streamingJob: Job? = null
    private var registrationJob: Job? = null
    private var keepaliveJob: Job? = null
    private var isRunning = false
    private var scope: CoroutineScope? = null
    
    private val sequenceNumber = AtomicLong(0)
    @Volatile
    private var lastPacketSentTime = 0L
    
    // Track connected clients - each with their own send channel
    private val clients = ConcurrentHashMap<String, ConnectedClientInfo>()
    
    companion object {
        const val HEADER_SIZE = 14
        const val CLIENT_TIMEOUT_MS = 10_000L // 10 seconds for faster disconnect detection
    }
    
    /**
     * Get the security manager for PIN access
     */
    fun getSecurityManager(): SecurityManager = securityManager
    
    /**
     * Start the server and begin streaming audio (with security)
     */
    fun start(coroutineScope: CoroutineScope, audioFlow: SharedFlow<ByteArray>, encoder: com.austream.audio.OpusAudioEncoder) {
        if (isRunning) return
        
        // Generate new session PIN
        securityManager.generatePin()
        
        this.scope = coroutineScope
        socket = DatagramSocket(port)
        socket?.broadcast = true
        socket?.reuseAddress = true
        socket?.sendBufferSize = 1024 * 1024
        isRunning = true
        
        // Job to receive client registrations and PIN authentication
        registrationJob = coroutineScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(512)
            while (isActive && isRunning) {
                try {
                    socket?.soTimeout = 100
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    val message = String(packet.data, 0, packet.length)
                    handleClientMessage(message, packet, coroutineScope)
                    
                } catch (e: java.net.SocketTimeoutException) {
                    cleanupStaleClients()
                } catch (e: Exception) {
                    if (isRunning) {
                        e.printStackTrace()
                    }
                }
            }
        }
        
        // Job to stream encrypted audio
        streamingJob = coroutineScope.launch(Dispatchers.IO) {
            audioFlow.collect { pcmData ->
                try {
                    // Skip silence - check if audio level is above threshold
                    if (isSilence(pcmData)) return@collect
                    
                    val encodedData = encoder.encode(pcmData)
                    val packetData = buildEncryptedPacket(encodedData)
                    lastPacketSentTime = System.currentTimeMillis()
                    
                    // Send to each authenticated client's channel (non-blocking)
                    clients.values
                        .filter { it.authenticated }
                        .forEach { client ->
                            client.sendChannel.trySend(packetData)
                        }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        // Keepalive job - send empty packets when no audio is playing
        keepaliveJob = coroutineScope.launch(Dispatchers.IO) {
            lastPacketSentTime = System.currentTimeMillis()
            while (isActive && isRunning) {
                kotlinx.coroutines.delay(2000)  // Check every 2 seconds
                val timeSinceLastPacket = System.currentTimeMillis() - lastPacketSentTime
                if (timeSinceLastPacket > 2000 && clients.values.any { it.authenticated }) {
                    // Send keepalive packet (silence encoded)
                    try {
                        val silenceData = ByteArray(960 * 2 * 2)  // 10ms of silence (stereo 16-bit)
                        val encodedSilence = encoder.encode(silenceData)
                        val packetData = buildEncryptedPacket(encodedSilence)
                        lastPacketSentTime = System.currentTimeMillis()
                        
                        clients.values
                            .filter { it.authenticated }
                            .forEach { client ->
                                client.sendChannel.trySend(packetData)
                            }
                    } catch (e: Exception) {
                        // Ignore encoding errors for silence
                    }
                }
            }
        }
        
        AppLog.info("Audio server started on port $port")
    }
    
    /**
     * Handle incoming client messages
     */
    private fun handleClientMessage(message: String, packet: DatagramPacket, coroutineScope: CoroutineScope) {
        val clientKey = "${packet.address.hostAddress}:${packet.port}"
        val clientAddr = InetSocketAddress(packet.address, packet.port)
        
        when {
            // New authentication request: "AUSTREAM_AUTH:<pin_hash>"
            message.startsWith("AUSTREAM_AUTH:") -> {
                val pinHash = message.substringAfter("AUSTREAM_AUTH:")
                
                if (securityManager.validatePinHash(pinHash)) {
                    // Authentication successful
                    val clientInfo = ConnectedClientInfo(
                        address = clientAddr,
                        authenticated = true
                    )
                    clientInfo.sendJob = startClientSendJob(clientInfo, coroutineScope)
                    clients[clientKey] = clientInfo
                    
                    // Send success response with buffer size
                    val response = "AUSTREAM_OK:$bufferSizeMs".toByteArray()
                    val responsePacket = DatagramPacket(response, response.size, clientAddr.address, clientAddr.port)
                    socket?.send(responsePacket)
                    
                    AppLog.info("Client authenticated: $clientKey")
                } else {
                    // Authentication failed
                    val response = "AUSTREAM_FAIL".toByteArray()
                    val responsePacket = DatagramPacket(response, response.size, clientAddr.address, clientAddr.port)
                    socket?.send(responsePacket)
                }
            }
            
            // Legacy client (no auth) - rejected
            message.startsWith("AUSTREAM_CLIENT") -> {
                val response = "AUSTREAM_NEED_PIN".toByteArray()
                val responsePacket = DatagramPacket(response, response.size, clientAddr.address, clientAddr.port)
                socket?.send(responsePacket)
            }
            
            // Probe request - just check if server is alive (no auth needed)
            message.startsWith("AUSTREAM_PROBE") -> {
                val hostname = try { java.net.InetAddress.getLocalHost().hostName } catch (_: Exception) { "AuStream" }
                val response = "AUSTREAM_ALIVE:$hostname".toByteArray()
                val responsePacket = DatagramPacket(response, response.size, clientAddr.address, clientAddr.port)
                socket?.send(responsePacket)
            }
            
            // Heartbeat from existing client
            message.startsWith("AUSTREAM_HEARTBEAT") -> {
                clients[clientKey]?.let { existing ->
                    clients[clientKey] = existing.copy(lastSeen = System.currentTimeMillis())
                }
            }
        }
    }
    
    /**
     * Start a dedicated send coroutine for a client
     */
    private fun startClientSendJob(client: ConnectedClientInfo, coroutineScope: CoroutineScope): Job {
        return coroutineScope.launch(Dispatchers.IO) {
            val sock = socket ?: return@launch
            val addr = client.address as InetSocketAddress
            
            for (packetData in client.sendChannel) {
                try {
                    val datagram = DatagramPacket(
                        packetData,
                        packetData.size,
                        addr.address,
                        addr.port
                    )
                    sock.send(datagram)
                } catch (e: Exception) {
                    // Client might have disconnected
                }
            }
        }
    }
    
    /**
     * Build an encrypted packet with header
     */
    private fun buildEncryptedPacket(audioData: ByteArray): ByteArray {
        val seq = sequenceNumber.incrementAndGet()
        val timestamp = System.nanoTime()
        
        // Encrypt the audio data
        val encryptedAudio = securityManager.encrypt(audioData)
        
        val packetSize = HEADER_SIZE + encryptedAudio.size
        val buffer = ByteBuffer.allocate(packetSize)
        
        buffer.putInt(seq.toInt())
        buffer.putLong(timestamp)
        buffer.putShort(encryptedAudio.size.toShort())
        buffer.put(encryptedAudio)
        
        return buffer.array()
    }
    
    /**
     * Remove clients that haven't sent a heartbeat recently.
     */
    private fun cleanupStaleClients() {
        val now = System.currentTimeMillis()
        val stale = clients.filterValues { now - it.lastSeen > CLIENT_TIMEOUT_MS }
        stale.forEach { (key, client) ->
            client.sendJob?.cancel()
            client.sendChannel.close()
            clients.remove(key)
        }
    }
    
    /**
     * Stop the server
     */
    fun stop() {
        isRunning = false
        
        // Cancel all client send jobs
        clients.values.forEach { client ->
            client.sendJob?.cancel()
            client.sendChannel.close()
        }
        clients.clear()
        
        streamingJob?.cancel()
        streamingJob = null
        registrationJob?.cancel()
        registrationJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null
        socket?.close()
        socket = null
        sequenceNumber.set(0)
        securityManager.reset()
    }
    
    fun isRunning(): Boolean = isRunning
    fun getConnectedClients(): Int = clients.count { it.value.authenticated }
    fun getPort(): Int = port
    
    /**
     * Check if audio data is silence (all samples below threshold)
     * PCM data is 16-bit signed little-endian
     */
    private fun isSilence(pcmData: ByteArray, threshold: Int = 200): Boolean {
        if (pcmData.size < 2) return true
        
        // Sample a subset of the data for efficiency
        val sampleCount = minOf(pcmData.size / 2, 100)
        val step = (pcmData.size / 2) / sampleCount
        
        repeat(sampleCount) { i ->
            val offset = i * step * 2
            if (offset + 1 >= pcmData.size) return@repeat
            
            // Read 16-bit sample (little-endian)
            val sample = (pcmData[offset].toInt() and 0xFF) or 
                         ((pcmData[offset + 1].toInt()) shl 8)
            
            // If any sample is above threshold, not silence
            if (kotlin.math.abs(sample) > threshold) return false
        }
        
        return true
    }
}
