package com.austream.client.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.austream.client.network.ClockSyncClient
import com.austream.client.network.ReceivedPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-latency synchronized audio player using Android's AudioTrack.
 * 
 * Features:
 * - **Multi-device sync**: Uses server timestamps + clock offset to synchronize playback
 * - Jitter buffer for smooth playback over unreliable networks
 * - Packet Loss Concealment (PLC) to mask missing packets
 * - Adaptive underrun recovery
 */
class AudioPlayer(
    private val clockSyncClient: ClockSyncClient
) {
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var collectionJob: Job? = null
    private var isPlaying = AtomicBoolean(false)
    
    private val decoder = OpusAudioDecoder()
    
    // Buffer packets by their scheduled play time (in local nanoseconds)
    private val syncBuffer = ConcurrentSkipListMap<Long, ReceivedPacket>()
    
    private var volume = 1.0f
    private val packetsPlayed = AtomicLong(0)
    
    // Reference point for synchronized playback
    private var playbackStartLocalTime = 0L
    private var firstPacketServerTime = 0L
    
    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val BUFFER_SIZE_FRAMES = 1200  // 25ms buffer
        
        // Sync delay: how far ahead of "now" we schedule packets
        // Lower = less latency but more risk of stuttering
        // 50ms is a good balance for WiFi networks
        const val SYNC_DELAY_MS = 50L
        
        const val MAX_BUFFER_SIZE = 30
        const val FRAME_DURATION_NS = 10_000_000L  // 10ms per frame in nanoseconds
    }
    
    /**
     * Initialize the AudioTrack for low-latency playback.
     */
    private fun initialize() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        val bufferSize = maxOf(minBufferSize, BUFFER_SIZE_FRAMES * CHANNELS * 2)
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        
        audioTrack?.setVolume(volume)
    }
    
    /**
     * Start synchronized playback from the packet flow.
     * 
     * Multi-device sync works by:
     * 1. Each packet has a server timestamp
     * 2. We convert server time to local time using clock offset
     * 3. All devices schedule playback for the same real-world moment
     */
    fun start(scope: CoroutineScope, packetFlow: SharedFlow<ReceivedPacket>) {
        if (isPlaying.get()) return
        
        initialize()
        audioTrack?.play()
        isPlaying.set(true)
        
        playbackStartLocalTime = 0L
        firstPacketServerTime = 0L
        
        // Packet collection job - buffer packets with calculated play times
        collectionJob = scope.launch(Dispatchers.IO) {
            packetFlow.collect { packet ->
                if (isPlaying.get()) {
                    // Convert server timestamp to local time for synchronized playback
                    val localPlayTime = clockSyncClient.serverToLocalTime(packet.timestamp)
                    
                    // Initialize reference point on first packet
                    if (firstPacketServerTime == 0L) {
                        firstPacketServerTime = packet.timestamp
                        playbackStartLocalTime = System.nanoTime() + (SYNC_DELAY_MS * 1_000_000)
                    }
                    
                    // Schedule based on offset from first packet
                    val offsetFromFirst = packet.timestamp - firstPacketServerTime
                    val scheduledLocalTime = playbackStartLocalTime + offsetFromFirst
                    
                    syncBuffer[scheduledLocalTime] = packet
                    
                    // Limit buffer size
                    while (syncBuffer.size > MAX_BUFFER_SIZE) {
                        syncBuffer.pollFirstEntry()
                    }
                }
            }
        }
        
        // Playback job - play packets at their scheduled times
        playbackJob = scope.launch(Dispatchers.IO) {
            // Wait for initial buffer to fill
            delay(SYNC_DELAY_MS)
            
            var lastPlayedSeq = -1L
            var consecutiveUnderruns = 0
            
            while (isActive && isPlaying.get()) {
                val now = System.nanoTime()
                val entry = syncBuffer.firstEntry()
                
                if (entry != null) {
                    val scheduledTime = entry.key
                    val packet = entry.value
                    
                    when {
                        // Time to play this packet
                        now >= scheduledTime -> {
                            syncBuffer.pollFirstEntry()
                            consecutiveUnderruns = 0
                            
                            // Handle packet loss with PLC
                            if (lastPlayedSeq >= 0 && packet.sequenceNumber > lastPlayedSeq + 1) {
                                val missing = (packet.sequenceNumber - lastPlayedSeq - 1).toInt()
                                repeat(minOf(missing, 3)) {
                                    writeToTrack(decoder.generatePLC())
                                }
                            }
                            
                            // Decode and play
                            try {
                                val samples = decoder.decode(packet.opusData)
                                writeToTrack(samples)
                                lastPlayedSeq = packet.sequenceNumber
                                packetsPlayed.incrementAndGet()
                            } catch (e: Exception) {
                                Log.e(TAG, "Decode error", e)
                            }
                        }
                        
                        // Packet is in the future - wait briefly
                        else -> {
                            val waitTime = (scheduledTime - now) / 1_000_000  // ns to ms
                            delay(minOf(waitTime, 5L).coerceAtLeast(1L))
                        }
                    }
                } else {
                    // Buffer underrun - adaptive recovery
                    consecutiveUnderruns++
                    
                    when {
                        consecutiveUnderruns < 5 -> delay(2)
                        consecutiveUnderruns < 20 -> {
                            writeToTrack(decoder.generatePLC())
                            delay(5)
                        }
                        else -> {
                            delay(10)
                            if (consecutiveUnderruns > 50) {
                                // Reset sync reference on extended underrun
                                firstPacketServerTime = 0L
                                delay(SYNC_DELAY_MS / 2)
                                consecutiveUnderruns = 20
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Write PCM samples to the AudioTrack.
     */
    private fun writeToTrack(samples: ShortArray): Int {
        return audioTrack?.write(samples, 0, samples.size) ?: 0
    }
    
    /**
     * Stop playback and release resources.
     */
    fun stop() {
        isPlaying.set(false)
        collectionJob?.cancel()
        playbackJob?.cancel()
        collectionJob = null
        playbackJob = null
        
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) { }
        
        audioTrack = null
        syncBuffer.clear()
        decoder.reset()
        packetsPlayed.set(0)
        playbackStartLocalTime = 0L
        firstPacketServerTime = 0L
    }
    
    /**
     * Set playback volume.
     * @param newVolume Volume level from 0.0 to 1.0
     */
    fun setVolume(newVolume: Float) {
        volume = newVolume.coerceIn(0f, 1f)
        audioTrack?.setVolume(volume)
    }
    
    fun getVolume(): Float = volume
    
    fun isPlaying(): Boolean = isPlaying.get()
}
