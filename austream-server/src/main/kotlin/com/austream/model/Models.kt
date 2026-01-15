package com.austream.model

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Represents an audio-producing application on the system
 */
data class AudioApplication(
    val processId: Int,
    val name: String,
    val executablePath: String,
    val icon: ImageBitmap? = null
)

/**
 * Server state for the streaming session
 */
data class ServerState(
    val isStreaming: Boolean = false,
    val selectedApp: AudioApplication? = null,
    val connectedClients: List<ConnectedClient> = emptyList(),
    val availableApps: List<AudioApplication> = emptyList()
)

/**
 * Represents a connected Android client
 */
data class ConnectedClient(
    val id: String,
    val address: String,
    val name: String,
    val connectedAt: Long = System.currentTimeMillis()
)

/**
 * Audio packet to be sent over the network
 */
data class AudioPacket(
    val sequenceNumber: Long,
    val timestamp: Long,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioPacket
        return sequenceNumber == other.sequenceNumber
    }

    override fun hashCode(): Int = sequenceNumber.hashCode()
}
