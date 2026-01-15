package com.austream.client.security

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages security for AuStream Android client.
 * Handles PIN hashing, key derivation, and AES-256-GCM decryption.
 */
class SecurityManager {
    
    private var sessionKey: ByteArray? = null
    
    companion object {
        private const val SALT = "AuStreamSalt2024" // Must match server
        private const val PBKDF2_ITERATIONS = 10000
        private const val KEY_LENGTH = 256
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }
    
    /**
     * Hash PIN for sending to server (server compares hashes).
     * @param pin The 6-digit PIN entered by the user.
     * @return Base64-encoded SHA-256 hash.
     */
    fun hashPin(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest((pin + SALT).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
    
    /**
     * Set the PIN and derive encryption key
     */
    fun setPin(pin: String) {
        sessionKey = deriveKey(pin)
    }
    
    /**
     * Check if security key is set
     */
    fun isEnabled(): Boolean = sessionKey != null
    
    /**
     * Derive encryption key from PIN using PBKDF2
     */
    private fun deriveKey(pin: String): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), SALT.toByteArray(), PBKDF2_ITERATIONS, KEY_LENGTH)
        return factory.generateSecret(spec).encoded
    }
    
    /**
     * Decrypt audio data using AES-256-GCM
     * Input format: nonce (12 bytes) + encrypted data + auth tag
     */
    fun decrypt(encryptedData: ByteArray): ByteArray? {
        val key = sessionKey ?: return null
        
        if (encryptedData.size < GCM_NONCE_LENGTH + 16) {
            return null
        }
        
        return try {
            // Extract nonce
            val nonce = encryptedData.copyOfRange(0, GCM_NONCE_LENGTH)
            val ciphertext = encryptedData.copyOfRange(GCM_NONCE_LENGTH, encryptedData.size)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            // Decryption failed (wrong key, corrupted data, etc.)
            null
        }
    }
    
    /**
     * Reset security state
     */
    fun reset() {
        sessionKey = null
    }
}
