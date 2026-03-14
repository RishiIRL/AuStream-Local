package com.austream.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.austream.audio.AudioCaptureManager
import com.austream.audio.OpusAudioEncoder
import com.austream.model.ServerState
import com.austream.network.ClockSyncServer
import com.austream.network.MulticastServer
import com.austream.network.NetworkUtils
import com.austream.util.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val scope = rememberCoroutineScope()
    
    // Core services
    val audioCaptureManager = remember { AudioCaptureManager() }
    val opusEncoder = remember { OpusAudioEncoder() }
    val multicastServer = remember { MulticastServer() }
    val clockSyncServer = remember { ClockSyncServer() }
    
    // UI State
    var serverState by remember { mutableStateOf(ServerState()) }
    var isLoading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var qrCodeBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var qrBitmapForUi by remember { mutableStateOf<ImageBitmap?>(null) }
    var connectedClients by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Load available apps on startup
    LaunchedEffect(Unit) {
        isLoading = true
        val apps = audioCaptureManager.getAudioApplications()
        serverState = serverState.copy(availableApps = apps)
        isLoading = false
    }
    
    // Refresh connected clients count and regenerate QR if needed
    LaunchedEffect(serverState.isStreaming) {
        while (serverState.isStreaming) {
            delay(1000)
            connectedClients = multicastServer.getConnectedClients()
        }
    }
    
    // Generate QR code when streaming starts
    LaunchedEffect(serverState.isStreaming) {
        if (serverState.isStreaming) {
            try {
                val ip = NetworkUtils.getLocalIpv4Address()
                val pin = multicastServer.getSecurityManager().getCurrentPin() ?: "000000"
                val hostname = QrCodeGenerator.getHostname()
                val data = QrCodeGenerator.generateConnectionData(ip, 5004, pin, hostname)
                qrCodeBitmap = QrCodeGenerator.generateQrCode(data, 250)
            } catch (t: Throwable) {
                AppLog.error("Failed to generate QR code", t)
                qrCodeBitmap = null
                snackbarHostState.showSnackbar("QR code unavailable (see logs)")
            }
        } else {
            qrCodeBitmap = null
        }
    }

    // Keep the last non-null bitmap around so AnimatedVisibility exit doesn't crash on qrCodeBitmap!!.
    LaunchedEffect(qrCodeBitmap) {
        if (qrCodeBitmap != null) {
            qrBitmapForUi = qrCodeBitmap
        }
    }

    // Clear after exit animation window when streaming stops.
    LaunchedEffect(serverState.isStreaming) {
        if (!serverState.isStreaming) {
            delay(400)
            qrBitmapForUi = null
        }
    }

    fun Modifier.pressPop(
        interactionSource: MutableInteractionSource,
        pressedScale: Float = 1.04f
    ): Modifier = composed {
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (pressed) pressedScale else 1f,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = 0.78f,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
            ),
            label = "pressPop"
        )

        this.graphicsLayer(scaleX = scale, scaleY = scale)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val w = maxWidth
                val h = maxHeight

            val horizontalPadding = (w * 0.06f).coerceIn(18.dp, 32.dp)
            val topPadding = (h * 0.04f).coerceIn(16.dp, 28.dp)
            val sectionSpacing = (h * 0.02f).coerceIn(10.dp, 16.dp)

            // Keep QR large enough to scan but always fit.
            val qrSize = ((w - horizontalPadding * 2f) * 0.58f)
                .coerceIn(170.dp, (h * 0.34f).coerceAtMost(240.dp))

            val cardPadding = (h * 0.028f).coerceIn(14.dp, 20.dp)
            val cardRadius = (w * 0.05f).coerceIn(18.dp, 28.dp)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding)
                        .padding(top = topPadding, bottom = topPadding),
                    verticalArrangement = Arrangement.spacedBy(sectionSpacing)
                ) {
                // Header
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "AuStream",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Stream audio to your devices",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Main content that flexes to window
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(sectionSpacing)
                ) {
                    // Hero / Status card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(cardRadius),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        border = CardDefaults.outlinedCardBorder(enabled = true)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(cardPadding),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                val iconBoxSize = (h * 0.075f).coerceIn(46.dp, 56.dp)
                                val iconSize = (iconBoxSize * 0.5f).coerceIn(22.dp, 28.dp)

                                Box(
                                    modifier = Modifier
                                        .size(iconBoxSize)
                                        .clip(RoundedCornerShape((cardRadius * 0.7f).coerceIn(16.dp, 20.dp)))
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (serverState.isStreaming) Icons.Default.WifiTethering else Icons.Default.Computer,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(iconSize)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (serverState.isStreaming) "Streaming" else "Ready to stream",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (serverState.isStreaming)
                                            "IP: ${NetworkUtils.getLocalIpv4Address()}"
                                        else
                                            "Select an app to stream and press Start",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    label = {
                                        Text(
                                            if (serverState.isStreaming) "$connectedClients" else "Idle",
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (serverState.isStreaming)
                                                        MaterialTheme.colorScheme.secondary
                                                    else
                                                        MaterialTheme.colorScheme.outline
                                                )
                                        )
                                    }
                                )
                            }

                            if (serverState.isStreaming) {
                                val pin = multicastServer.getSecurityManager().getCurrentPin() ?: "------"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Connection PIN",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = pin,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 4.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // Streaming: QR card
                    androidx.compose.animation.AnimatedVisibility(
                        visible = serverState.isStreaming && qrBitmapForUi != null,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(
                            initialOffsetY = { it / 10 },
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = 0.84f,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                            )
                        ),
                        exit = androidx.compose.animation.fadeOut()
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape((cardRadius * 0.9f).coerceIn(18.dp, 24.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            border = CardDefaults.outlinedCardBorder(enabled = true)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding((cardPadding * 0.95f).coerceIn(14.dp, 18.dp)),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Scan to connect",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )

                                // QR needs a white backing for reliable scanning.
                                Surface(
                                    color = Color.White,
                                    contentColor = Color.Black,
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Box(modifier = Modifier.padding(12.dp)) {
                                        val bitmap = qrBitmapForUi
                                        Image(
                                            bitmap = bitmap ?: return@Box,
                                            contentDescription = "Connection QR Code",
                                            modifier = Modifier.size(qrSize)
                                        )
                                    }
                                }

                                Text(
                                    text = "Open AuStream on your phone and scan this QR code\nor connect manually using the IP + PIN.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Idle: audio source selection
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !serverState.isStreaming,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(
                            initialOffsetY = { it / 12 },
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = 0.86f,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                            )
                        ),
                        exit = androidx.compose.animation.fadeOut()
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape((cardRadius * 0.9f).coerceIn(18.dp, 24.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            border = CardDefaults.outlinedCardBorder(enabled = true)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding((cardPadding * 0.95f).coerceIn(14.dp, 18.dp)),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Select audio source",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )

                                ExposedDropdownMenuBox(
                                    expanded = expanded,
                                    onExpandedChange = { expanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = serverState.selectedApp?.name ?: "Choose an application…",
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                        )
                                    )

                                    ExposedDropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false }
                                    ) {
                                        if (isLoading) {
                                            DropdownMenuItem(text = { Text("Loading applications…") }, onClick = {})
                                        } else if (serverState.availableApps.isEmpty()) {
                                            DropdownMenuItem(text = { Text("No applications found") }, onClick = {})
                                        } else {
                                            serverState.availableApps.forEach { app ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            if (app.icon != null) {
                                                                Image(
                                                                    bitmap = app.icon,
                                                                    contentDescription = app.name,
                                                                    modifier = Modifier.size(24.dp)
                                                                )
                                                            } else {
                                                                Icon(
                                                                    Icons.Default.Apps,
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(24.dp),
                                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                            Column {
                                                                Text(app.name)
                                                                Text(
                                                                    text = "PID: ${app.processId}",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                        }
                                                    },
                                                    onClick = {
                                                        serverState = serverState.copy(selectedApp = app)
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                val refreshInteraction = remember { MutableInteractionSource() }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            isLoading = true
                                            val apps = audioCaptureManager.getAudioApplications()
                                            serverState = serverState.copy(availableApps = apps)
                                            isLoading = false
                                        }
                                    },
                                    modifier = Modifier
                                        .height(42.dp)
                                        .pressPop(refreshInteraction, pressedScale = 1.03f),
                                    interactionSource = refreshInteraction
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Refresh applications")
                                }
                            }
                        }
                    }
                }

                // Stream control button pinned to bottom
                val startStopInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                if (serverState.isStreaming) {
                                    audioCaptureManager.stopCapture()
                                    multicastServer.stop()
                                    clockSyncServer.stop()
                                    serverState = serverState.copy(isStreaming = false)
                                    connectedClients = 0
                                } else if (serverState.selectedApp != null) {
                                    // NOTE: These can throw (e.g. ports already in use). Catch so the EXE doesn't just exit.
                                    clockSyncServer.start(scope)
                                    audioCaptureManager.startCapture(scope, serverState.selectedApp)
                                    multicastServer.start(scope, audioCaptureManager.audioFlow, opusEncoder)
                                    serverState = serverState.copy(isStreaming = true)
                                }
                            } catch (t: Throwable) {
                                AppLog.error("Start/stop streaming failed", t)

                                // Best-effort cleanup to keep the UI usable.
                                try { audioCaptureManager.stopCapture() } catch (_: Throwable) {}
                                try { multicastServer.stop() } catch (_: Throwable) {}
                                try { clockSyncServer.stop() } catch (_: Throwable) {}

                                serverState = serverState.copy(isStreaming = false)
                                connectedClients = 0

                                val msg = t.message?.takeIf { it.isNotBlank() }
                                    ?: t::class.simpleName
                                    ?: "Unknown error"
                                snackbarHostState.showSnackbar("Failed to start streaming: $msg")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .pressPop(startStopInteraction, pressedScale = 1.05f),
                    interactionSource = startStopInteraction,
                    shape = RoundedCornerShape((cardRadius * 0.7f).coerceIn(16.dp, 20.dp)),
                    enabled = serverState.selectedApp != null || serverState.isStreaming,
                    colors = if (serverState.isStreaming) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                ) {
                    Icon(
                        imageVector = if (serverState.isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (serverState.isStreaming) "Stop streaming" else "Start streaming",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                }
            }
        }
    }
}
