package com.austream.client.network

import android.util.Log
import com.austream.client.security.SecurityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Data class representing a received audio packet
 */
data class ReceivedPacket(
    val sequenceNumber: Long,
    val timestamp: Long,
    val opusData: ByteArray,
    val receivedAt: Long = System.nanoTime()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ReceivedPacket
        return sequenceNumber == other.sequenceNumber
    }

    override fun hashCode(): Int = sequenceNumber.hashCode()
}

/**
 * Authentication state
 */
sealed class AuthState {
    object NotAuthenticated : AuthState()
    object Authenticating : AuthState()
    object Authenticated : AuthState()
    data class Failed(val reason: String) : AuthState()
}

/**
 * Secure UDP receiver for audio packets.
 * Handles PIN authentication and AES decryption.
 */
class MulticastReceiver(
    private val serverAddress: String,
    private val port: Int = 5004
) {
    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null
    private var isRunning = false
    
    private val securityManager = SecurityManager()
    
    private val _authState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
    val authState: StateFlow<AuthState> = _authState
    
    private val _packetFlow = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = 100)
    val packetFlow: SharedFlow<ReceivedPacket> = _packetFlow
    
    private val packetsReceived = AtomicLong(0)
    private val packetsLost = AtomicLong(0)
    private val decryptionErrors = AtomicLong(0)
    private var lastSequenceNumber = -1L
    
    companion object {
        private const val TAG = "SecureAudioReceiver"
        const val HEADER_SIZE = 14
        const val MAX_PACKET_SIZE = 4096 // Larger for encrypted data
    }
    
    /**
     * Authenticate with PIN and start receiving packets.
     */
    fun startWithPin(scope: CoroutineScope, serverAddr: String, pin: String) {
        if (isRunning) return
        
        securityManager.setPin(pin)
        
        socket = DatagramSocket()
        socket?.soTimeout = 3000 // Longer timeout for auth response
        isRunning = true
        
        val serverInetAddr = InetAddress.getByName(serverAddr)
        
        _authState.value = AuthState.Authenticating
        
        // Authenticate with server
        scope.launch(Dispatchers.IO) {
            try {
                // Send authentication request with PIN hash
                val pinHash = securityManager.hashPin(pin)
                val authMessage = "AUSTREAM_AUTH:$pinHash".toByteArray()
                val authPacket = DatagramPacket(authMessage, authMessage.size, serverInetAddr, port)
                socket?.send(authPacket)
                
                // Wait for response
                val responseBuffer = ByteArray(256)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket?.receive(responsePacket)
                
                val response = String(responsePacket.data, 0, responsePacket.length)
                
                when {
                    response == "AUSTREAM_OK" -> {
                        _authState.value = AuthState.Authenticated
                        startReceiving(scope, serverInetAddr)
                    }
                    response == "AUSTREAM_FAIL" -> {
                        _authState.value = AuthState.Failed("Invalid PIN")
                        stop()
                    }
                    else -> {
                        _authState.value = AuthState.Failed("Unknown response: $response")
                        stop()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Authentication error", e)
                _authState.value = AuthState.Failed("Connection error: ${e.message}")
                stop()
            }
        }
    }
    
    /**
     * Start receiving and decrypting audio packets
     */
    private fun startReceiving(scope: CoroutineScope, serverInetAddr: InetAddress) {
        socket?.soTimeout = 100 // Short timeout for audio packets
        
        // Heartbeat to keep connection alive
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                try {
                    val message = "AUSTREAM_HEARTBEAT".toByteArray()
                    val packet = DatagramPacket(message, message.size, serverInetAddr, port)
                    socket?.send(packet)
                } catch (_: Exception) { }
                delay(5000)
            }
        }
        
        // Receive encrypted audio packets
        receiveJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(MAX_PACKET_SIZE)
            
            while (isActive && isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    if (packet.length >= HEADER_SIZE) {
                        val receivedPacket = parseAndDecryptPacket(buffer, packet.length)
                        if (receivedPacket != null) {
                            _packetFlow.emit(receivedPacket)
                            packetsReceived.incrementAndGet()
                            
                            // Track packet loss
                            if (lastSequenceNumber >= 0 && receivedPacket.sequenceNumber > lastSequenceNumber + 1) {
                                val lost = receivedPacket.sequenceNumber - lastSequenceNumber - 1
                                packetsLost.addAndGet(lost)
                            }
                            lastSequenceNumber = receivedPacket.sequenceNumber
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Normal timeout, continue
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Receive error", e)
                    }
                }
            }
        }
    }
    
    /**
     * Parse and decrypt a received packet
     */
    private fun parseAndDecryptPacket(data: ByteArray, length: Int): ReceivedPacket? {
        if (length < HEADER_SIZE) return null
        
        val buffer = ByteBuffer.wrap(data)
        val sequenceNumber = buffer.getInt().toLong() and 0xFFFFFFFFL
        val timestamp = buffer.getLong()
        val payloadLength = buffer.getShort().toInt() and 0xFFFF
        
        if (payloadLength <= 0 || payloadLength > length - HEADER_SIZE) {
            return null
        }
        
        val encryptedData = ByteArray(payloadLength)
        buffer.get(encryptedData)
        
        // Decrypt the audio data
        val decryptedData = securityManager.decrypt(encryptedData)
        if (decryptedData == null) {
            decryptionErrors.incrementAndGet()
            return null
        }
        
        return ReceivedPacket(
            sequenceNumber = sequenceNumber,
            timestamp = timestamp,
            opusData = decryptedData
        )
    }
    
    /**
     * Stop receiving and clean up resources.
     */
    fun stop() {
        isRunning = false
        receiveJob?.cancel()
        receiveJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.close()
        socket = null
        lastSequenceNumber = -1
        securityManager.reset()
        _authState.value = AuthState.NotAuthenticated
    }
    
    fun getPacketsReceived(): Long = packetsReceived.get()
    fun getPacketsLost(): Long = packetsLost.get()
    fun getDecryptionErrors(): Long = decryptionErrors.get()
    fun isRunning(): Boolean = isRunning
    fun isAuthenticated(): Boolean = _authState.value == AuthState.Authenticated
}
