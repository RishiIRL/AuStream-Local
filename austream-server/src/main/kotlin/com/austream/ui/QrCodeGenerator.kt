package com.austream.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * Generates QR code for connection info.
 * Format: austream://<ip>:<port>?pin=<pin>&name=<hostname>
 */
object QrCodeGenerator {
    
    /**
     * Generate connection data as a URL-like string
     */
    fun generateConnectionData(ip: String, port: Int, pin: String, hostname: String): String {
        val encodedName = hostname.replace(" ", "%20")
        return "austream://$ip:$port?pin=$pin&name=$encodedName"
    }
    
    /**
     * Generate a QR code as a Compose ImageBitmap
     */
    fun generateQrCode(data: String, size: Int = 300): ImageBitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size, hints)
        
        val bufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bufferedImage.setRGB(x, y, if (bitMatrix.get(x, y)) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
        
        return bufferedImage.toComposeImageBitmap()
    }
    
    /**
     * Get the local hostname
     */
    fun getHostname(): String {
        return try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            "AuStream Server"
        }
    }
}
