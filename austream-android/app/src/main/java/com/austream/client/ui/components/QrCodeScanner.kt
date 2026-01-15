package com.austream.client.ui.components

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Parsed connection data from QR code
 */
data class ConnectionData(
    val ip: String,
    val port: Int,
    val pin: String,
    val serverName: String
)

/**
 * Parse connection data from QR code string
 * Format: austream://<ip>:<port>?pin=<pin>&name=<hostname>
 */
fun parseConnectionData(data: String): ConnectionData? {
    return try {
        if (!data.startsWith("austream://")) return null
        
        val rest = data.removePrefix("austream://")
        val parts = rest.split("?", limit = 2)
        val hostPort = parts[0].split(":")
        val ip = hostPort[0]
        val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 5004
        
        var pin = ""
        var name = "AuStream Server"
        
        if (parts.size > 1) {
            val params = parts[1].split("&")
            for (param in params) {
                val kv = param.split("=", limit = 2)
                if (kv.size == 2) {
                    when (kv[0]) {
                        "pin" -> pin = kv[1]
                        "name" -> name = java.net.URLDecoder.decode(kv[1], "UTF-8")
                    }
                }
            }
        }
        
        ConnectionData(ip, port, pin, name)
    } catch (e: Exception) {
        Log.e("QrScanner", "Failed to parse QR data: $data", e)
        null
    }
}

/**
 * QR code scanner composable using CameraX and ML Kit
 */
@Composable
fun QrCodeScanner(
    modifier: Modifier = Modifier,
    onQrCodeScanned: (ConnectionData) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasScanned by remember { mutableStateOf(false) }
    
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }
    
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    
                    val barcodeScanner = BarcodeScanning.getClient()
                    
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                if (hasScanned) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                
                                val mediaImage = imageProxy.image
                                if (mediaImage != null) {
                                    val image = InputImage.fromMediaImage(
                                        mediaImage,
                                        imageProxy.imageInfo.rotationDegrees
                                    )
                                    
                                    barcodeScanner.process(image)
                                        .addOnSuccessListener { barcodes ->
                                            for (barcode in barcodes) {
                                                if (barcode.valueType == Barcode.TYPE_TEXT || 
                                                    barcode.valueType == Barcode.TYPE_URL) {
                                                    val rawValue = barcode.rawValue ?: continue
                                                    
                                                    if (rawValue.startsWith("austream://")) {
                                                        val connectionData = parseConnectionData(rawValue)
                                                        if (connectionData != null && !hasScanned) {
                                                            hasScanned = true
                                                            onQrCodeScanned(connectionData)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        .addOnFailureListener { e ->
                                            Log.e("QrScanner", "Barcode scanning failed", e)
                                        }
                                        .addOnCompleteListener {
                                            imageProxy.close()
                                        }
                                } else {
                                    imageProxy.close()
                                }
                            }
                        }
                    
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                    
                } catch (e: Exception) {
                    Log.e("QrScanner", "Camera initialization failed", e)
                    onError("Camera initialization failed: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))
            
            previewView
        }
    )
}
