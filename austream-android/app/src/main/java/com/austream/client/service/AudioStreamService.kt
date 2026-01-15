package com.austream.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.austream.client.AuStreamApp
import com.austream.client.MainActivity
import com.austream.client.audio.AudioPlayer
import com.austream.client.network.AuthState
import com.austream.client.network.ClockSyncClient
import com.austream.client.network.MulticastReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that keeps audio streaming active even when the app is in background
 * or the screen is locked. Now supports PIN-based authentication.
 */
class AudioStreamService : Service() {
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var clockSyncClient: ClockSyncClient? = null
    private var multicastReceiver: MulticastReceiver? = null
    private var audioPlayer: AudioPlayer? = null
    
    private var serverAddress: String = ""
    private var serverName: String = ""
    private var pin: String = ""
    private var isStreaming = false
    
    companion object {
        private const val TAG = "AudioStreamService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "austream_playback"
        
        const val ACTION_START = "com.austream.client.START"
        const val ACTION_STOP = "com.austream.client.STOP"
        const val EXTRA_SERVER_ADDRESS = "server_address"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_PIN = "pin"
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): AudioStreamService = this@AudioStreamService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Acquire wake lock to prevent CPU from sleeping
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AuStream::AudioPlayback"
        )
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS) ?: ""
                serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Unknown"
                pin = intent.getStringExtra(EXTRA_PIN) ?: ""
                startStreaming()
            }
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    private fun startStreaming() {
        if (isStreaming || serverAddress.isEmpty() || pin.isEmpty()) return
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
        
        // Acquire wake lock
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours max
        
        // Acquire multicast lock
        (application as AuStreamApp).acquireMulticastLock()
        
        // Initialize components
        clockSyncClient = ClockSyncClient(serverAddress).apply {
            start(serviceScope)
        }
        
        multicastReceiver = MulticastReceiver(serverAddress, 5004).apply {
            // Start with PIN authentication
            startWithPin(serviceScope, serverAddress, pin)
        }
        
        // Monitor authentication state
        serviceScope.launch {
            multicastReceiver?.authState?.collect { state ->
                when (state) {
                    AuthState.Authenticated -> {
                        updateNotification("Streaming from $serverName")
                        
                        // Start audio player only after authentication
                        if (audioPlayer == null) {
                            audioPlayer = AudioPlayer(clockSyncClient!!).apply {
                                start(serviceScope, multicastReceiver!!.packetFlow)
                            }
                        }
                    }
                    is AuthState.Failed -> {
                        Log.e(TAG, "Authentication failed: ${state.reason}")
                        updateNotification("Auth failed: ${state.reason}")
                    }
                    AuthState.Authenticating -> {
                        updateNotification("Authenticating...")
                    }
                    AuthState.NotAuthenticated -> {}
                }
            }
        }
        
        isStreaming = true
    }
    
    private fun stopStreaming() {
        audioPlayer?.stop()
        audioPlayer = null
        
        multicastReceiver?.stop()
        multicastReceiver = null
        
        clockSyncClient?.stop()
        clockSyncClient = null
        
        (application as AuStreamApp).releaseMulticastLock()
        
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        
        isStreaming = false
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when AuStream is streaming audio"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, AudioStreamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AuStream")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }
    
    fun setVolume(volume: Float) {
        audioPlayer?.setVolume(volume)
    }
    
    fun getPacketsReceived(): Long = multicastReceiver?.getPacketsReceived() ?: 0
    fun getPacketsLost(): Long = multicastReceiver?.getPacketsLost() ?: 0
    fun getLatencyMs(): Long = (clockSyncClient?.roundTripTime ?: 0) / 1_000_000
    fun isStreaming(): Boolean = isStreaming
    fun isAuthenticated(): Boolean = multicastReceiver?.isAuthenticated() ?: false
    fun getAuthState(): StateFlow<AuthState>? = multicastReceiver?.authState
    
    override fun onDestroy() {
        stopStreaming()
        serviceScope.cancel()
        super.onDestroy()
    }
}
