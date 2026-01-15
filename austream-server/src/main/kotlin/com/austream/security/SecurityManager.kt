package com.austream.security

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.util.Base64

/**
 * Manages security for AuStream server.
 * Handles PIN generation, key derivation, and AES-256-GCM encryption.
 */
class SecurityManager {
    
    private var currentPin: String? = null
    private var sessionKey: ByteArray? = null
    private val secureRandom = SecureRandom()
    
    companion object {
        private const val PIN_LENGTH = 6
        private const val SALT = "AuStreamSalt2024" // Fixed salt for simplicity
        private const val PBKDF2_ITERATIONS = 10000
        private const val KEY_LENGTH = 256
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }
    
    /**
     * Generate a new session PIN.
     * @return The generated 6-digit PIN.
     */
    fun generatePin(): String {
        val pin = StringBuilder()
        repeat(PIN_LENGTH) {
            pin.append(secureRandom.nextInt(10))
        }
        currentPin = pin.toString()
        sessionKey = deriveKey(currentPin!!)
        return currentPin!!
    }
    
    /**
     * Get current PIN (for display on server UI)
     */
    fun getCurrentPin(): String? = currentPin
    
    /**
     * Check if security is enabled (PIN has been generated)
     */
    fun isEnabled(): Boolean = sessionKey != null
    
    /**
     * Validate a client's PIN hash against the current session PIN.
     * @param clientHash The SHA-256 hash of the PIN sent by the client.
     * @return True if the hash matches, false otherwise.
     */
    fun validatePinHash(clientHash: String): Boolean {
        val pin = currentPin ?: return false
        return clientHash == hashPin(pin)
    }
    
    /**
     * Hash PIN for comparison (client sends hash, not plain PIN)
     */
    fun hashPin(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest((pin + SALT).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }
    
    /**
     * Derive encryption key from PIN using PBKDF2
     */
    private fun deriveKey(pin: String): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), SALT.toByteArray(), PBKDF2_ITERATIONS, KEY_LENGTH)
        return factory.generateSecret(spec).encoded
    }
    
    /**
     * Encrypt audio data using AES-256-GCM
     * Returns: nonce (12 bytes) + encrypted data + auth tag
     */
    fun encrypt(data: ByteArray): ByteArray {
        val key = sessionKey ?: throw IllegalStateException("No session key - call generatePin first")
        
        // Generate random nonce
        val nonce = ByteArray(GCM_NONCE_LENGTH)
        secureRandom.nextBytes(nonce)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val encrypted = cipher.doFinal(data)
        
        // Prepend nonce to encrypted data
        val result = ByteArray(nonce.size + encrypted.size)
        System.arraycopy(nonce, 0, result, 0, nonce.size)
        System.arraycopy(encrypted, 0, result, nonce.size, encrypted.size)
        
        return result
    }
    
    /**
     * Decrypt audio data using AES-256-GCM
     * Input format: nonce (12 bytes) + encrypted data + auth tag
     */
    fun decrypt(encryptedData: ByteArray): ByteArray {
        val key = sessionKey ?: throw IllegalStateException("No session key")
        
        if (encryptedData.size < GCM_NONCE_LENGTH + 16) { // Minimum: nonce + auth tag
            throw IllegalArgumentException("Encrypted data too short")
        }
        
        // Extract nonce
        val nonce = encryptedData.copyOfRange(0, GCM_NONCE_LENGTH)
        val ciphertext = encryptedData.copyOfRange(GCM_NONCE_LENGTH, encryptedData.size)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(ciphertext)
    }
    
    /**
     * Reset security state
     */
    fun reset() {
        currentPin = null
        sessionKey = null
    }
}
