package com.austream.client.util

import android.content.Context
import com.austream.client.ui.components.ConnectionData

/**
 * Utility for persisting recent connections to SharedPreferences.
 */
object RecentConnectionsManager {
    private const val PREFS_NAME = "austream_recent_connections"
    private const val KEY_CONNECTIONS = "connections"
    private const val MAX_CONNECTIONS = 10

    /**
     * Save or update a connection. If IP exists, updates the PIN and moves to top.
     */
    fun saveConnection(context: Context, connection: ConnectionData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = loadConnections(context).toMutableList()
        
        // Remove any existing entry with same IP
        existing.removeAll { it.ip == connection.ip }
        
        // Add new connection at the beginning
        existing.add(0, connection)
        
        // Limit to MAX_CONNECTIONS
        val limited = existing.take(MAX_CONNECTIONS)
        
        // Serialize and save
        val serialized = limited.joinToString(";") { 
            "${it.ip}|${it.port}|${it.pin}|${it.serverName}" 
        }
        prefs.edit().putString(KEY_CONNECTIONS, serialized).apply()
    }

    /**
     * Load all recent connections
     */
    fun loadConnections(context: Context): List<ConnectionData> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serialized = prefs.getString(KEY_CONNECTIONS, "") ?: ""
        
        if (serialized.isBlank()) return emptyList()
        
        return serialized.split(";").mapNotNull { entry ->
            val parts = entry.split("|", limit = 4)
            if (parts.size == 4) {
                val port = parts[1].toIntOrNull() ?: 5004
                ConnectionData(parts[0], port, parts[2], parts[3])
            } else null
        }
    }

    /**
     * Clear all recent connections
     */
    fun clearConnections(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CONNECTIONS).apply()
    }
}
