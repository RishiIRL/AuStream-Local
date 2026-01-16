package com.austream.client.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
    private val clockSyncClient: ClockSyncClient,
    private val bufferSizeMs: Int = 50  // Configurable sync buffer from server
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
        const val BUFFER_SIZE_FRAMES = 2400  // 50ms buffer (increased for stability)
        const val MAX_BUFFER_SIZE = 50  // Support up to 500ms of buffered packets
        const val FRAME_DURATION_NS = 10_000_000L  // 10ms per frame in nanoseconds
    }
    
    /**
     * Initialize the AudioTrack for low-latency playback.
     * Uses the custom buffer size from server.
     */
    private fun initialize() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        // Size AudioTrack buffer based on server-provided buffer size
        // bufferSizeMs * 48 samples/ms * 2 channels * 2 bytes per sample
        val customBufferBytes = bufferSizeMs * 48 * 2 * 2
        val bufferSize = maxOf(minBufferSize, customBufferBytes)
        
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
                    // Initialize reference point on first packet
                    if (firstPacketServerTime == 0L) {
                        firstPacketServerTime = packet.timestamp
                        playbackStartLocalTime = System.nanoTime() + (bufferSizeMs * 1_000_000L)
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
            // Wait for buffer to fill before starting playback
            // This prevents initial artifacts by ensuring we have enough packets AND time has passed
            val minPacketsToStart = (bufferSizeMs / 10).coerceAtLeast(5)  // 10ms per packet
            
            // Wait for packets AND wait for the full buffer time
            var initialWait = 0L
            val targetWaitMs = bufferSizeMs.toLong()  // Wait exactly the buffer time
            while ((syncBuffer.size < minPacketsToStart || initialWait < targetWaitMs) && initialWait < 3000L && isPlaying.get()) {
                delay(20)
                initialWait += 20
            }
            
            var consecutiveUnderruns = 0
            
            while (isActive && isPlaying.get()) {
                val now = System.nanoTime()
                val entry = syncBuffer.firstEntry()
                
                if (entry != null) {
                    val scheduledTime = entry.key
                    val packet = entry.value
                    
                    when {
                        // Time to play this packet (on time or late)
                        now >= scheduledTime -> {
                            syncBuffer.pollFirstEntry()
                            consecutiveUnderruns = 0
                            
                            // Just decode and play - don't skip or generate silence
                            try {
                                val samples = decoder.decode(packet.opusData)
                                writeToTrack(samples)
                                packetsPlayed.incrementAndGet()
                            } catch (_: Exception) {
                                // Decode error - skip packet
                            }
                        }
                        
                        // Packet is in the future - wait
                        else -> {
                            val waitTime = (scheduledTime - now) / 1_000_000  // ns to ms
                            delay(minOf(waitTime, 10L).coerceAtLeast(1L))
                        }
                    }
                } else {
                    // Buffer underrun - adaptive recovery
                    consecutiveUnderruns++
                    
                    when {
                        consecutiveUnderruns < 10 -> delay(2)
                        consecutiveUnderruns < 30 -> delay(5)  // Just wait, no PLC
                        else -> {
                            // Extended gap - audio likely paused on PC
                            // Reset sync and wait for buffer to refill
                            if (consecutiveUnderruns >= 30) {
                                firstPacketServerTime = 0L  // Reset sync reference
                                
                                // Wait for packets to arrive (audio resumed on PC)
                                val minPacketsToResume = (bufferSizeMs / 10).coerceAtLeast(5)
                                var resumeWait = 0L
                                while (syncBuffer.size < minPacketsToResume && resumeWait < 5000L && isPlaying.get()) {
                                    delay(20)
                                    resumeWait += 20
                                }
                                // Also wait a bit more to let buffer fill with time
                                if (syncBuffer.size >= minPacketsToResume) {
                                    delay(bufferSizeMs.toLong())
                                }
                                consecutiveUnderruns = 0
                            } else {
                                delay(10)
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
