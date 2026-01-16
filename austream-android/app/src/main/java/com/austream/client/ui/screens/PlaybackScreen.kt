package com.austream.client.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.austream.client.network.AuthState
import com.austream.client.service.AudioStreamService
import com.austream.client.ui.theme.AccentPurple
import com.austream.client.ui.theme.SuccessGreen
import com.austream.client.ui.theme.rememberDimensions
import kotlinx.coroutines.delay
import androidx.compose.material3.surfaceColorAtElevation
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun PlaybackScreen(
    serverAddress: String,
    serverName: String,
    prefilledPin: String = "",
    onBack: (disconnected: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val dimens = rememberDimensions()
    
    // PIN entry state - prefill if provided via QR code
    var showPinDialog by remember { mutableStateOf(prefilledPin.isEmpty()) }
    var pin by remember { mutableStateOf(prefilledPin) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    
    // Service binding
    var service by remember { mutableStateOf<AudioStreamService?>(null) }
    var isBound by remember { mutableStateOf(false) }
    var authState by remember { mutableStateOf<AuthState>(AuthState.NotAuthenticated) }
    
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val localBinder = binder as AudioStreamService.LocalBinder
                service = localBinder.getService()
                isBound = true
            }
            
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                isBound = false
            }
        }
    }
    
    // State
    var isPlaying by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1f) }
    var bufferSizeMs by remember { mutableStateOf(50) }
    var networkLatencyMs by remember { mutableStateOf(0L) }
    var packetsLost by remember { mutableStateOf(0L) }
    var packetsReceived by remember { mutableStateOf(0L) }
    
    // Notification permission launcher (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pin.length == 6) {
            startAudioService(context, serverAddress, serverName, pin)
            val intent = Intent(context, AudioStreamService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } else if (!granted) {
            isConnecting = false
            pinError = "Notification permission is required to connect"
        }
    }
    
    // Pulsing animation for streaming indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    // Auto-connect when PIN is prefilled from QR code
    LaunchedEffect(prefilledPin) {
        if (prefilledPin.length == 6) {
            // Small delay to let UI settle
            delay(300)
            
            // Check notification permission and start service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                    // Will need to request permission - show dialog
                    showPinDialog = true
                } else {
                    startAudioService(context, serverAddress, serverName, prefilledPin)
                    val intent = Intent(context, AudioStreamService::class.java)
                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }
            } else {
                startAudioService(context, serverAddress, serverName, prefilledPin)
                val intent = Intent(context, AudioStreamService::class.java)
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        }
    }
    
    // Monitor auth state from service.
    LaunchedEffect(service) {
        service?.let { svc ->
            svc.getAuthState().collect { state ->
                authState = state
                when (state) {
                    is AuthState.Failed -> {
                        pinError = state.reason
                        showPinDialog = true
                        isConnecting = false
                    }
                    AuthState.Authenticated -> {
                        showPinDialog = false
                        pinError = null
                        isConnecting = false
                        // Save successful connection to recent connections (updates PIN if IP exists)
                        com.austream.client.util.RecentConnectionsManager.saveConnection(
                            context,
                            com.austream.client.ui.components.ConnectionData(
                                ip = serverAddress,
                                port = 5004,
                                pin = pin,
                                serverName = serverName
                            )
                        )
                    }
                    AuthState.Authenticating -> {
                        isConnecting = true
                    }
                    AuthState.Disconnected -> {
                        // Server stopped - navigate back with flag
                        onBack(true)
                    }
                    else -> Unit
                }
            }
        }
    }

    // Safety: if the service never responds (e.g., bind succeeded but no network response), stop spinning.
    LaunchedEffect(isConnecting) {
        if (isConnecting) {
            delay(6500)
            if (isConnecting && authState !is AuthState.Authenticated) {
                isConnecting = false
                if (pinError == null) {
                    pinError = "No response from server"
                }
            }
        }
    }
    
    // Update stats periodically
    LaunchedEffect(isBound) {
        while (true) {
            delay(500)
            service?.let {
                val streaming = it.isStreaming() && it.isAuthenticated()
                isPlaying = streaming

                if (streaming) {
                    bufferSizeMs = it.getBufferSizeMs()
                    networkLatencyMs = it.getLatencyMs()
                    packetsLost = it.getPacketsLost()
                    packetsReceived = it.getPacketsReceived()
                } else {
                    bufferSizeMs = 50
                    networkLatencyMs = 0L
                    packetsLost = 0L
                    packetsReceived = 0L
                }
            }
        }
    }
    
    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            if (isBound) {
                context.unbindService(connection)
            }
        }
    }
    
    // PIN Entry Dialog
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { 
                if (authState !is AuthState.Authenticating) {
                    onBack(false)
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = AccentPurple,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "Enter Connection PIN",
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Enter the 6-digit PIN shown on $serverName",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { 
                            if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                                pin = it
                                pinError = null
                                isConnecting = false
                            }
                        },
                        label = { Text("PIN") },
                        placeholder = { Text("000000") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = pinError != null,
                        supportingText = pinError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                        textStyle = LocalTextStyle.current.copy(
                            letterSpacing = 8.sp,
                            textAlign = TextAlign.Center,
                            fontSize = 24.sp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    if (isConnecting || authState == AuthState.Authenticating) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connecting...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pin.length == 6) {
                            isConnecting = true
                            // Check notification permission and start service
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) 
                                    != PackageManager.PERMISSION_GRANTED) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    startAudioService(context, serverAddress, serverName, pin)
                                    val intent = Intent(context, AudioStreamService::class.java)
                                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                                }
                            } else {
                                startAudioService(context, serverAddress, serverName, pin)
                                val intent = Intent(context, AudioStreamService::class.java)
                                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                            }
                        }
                    },
                    enabled = pin.length == 6 && authState != AuthState.Authenticating && !isConnecting
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { onBack(false) }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Back press should confirm disconnect instead of navigating directly
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    BackHandler(enabled = true) {
        showDisconnectConfirm = true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(serverName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = serverAddress,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = dimens.paddingLarge, vertical = dimens.paddingMedium)
                    .padding(bottom = dimens.paddingLarge),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(dimens.paddingLarge))

                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + scaleIn(initialScale = 0.92f, animationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessLow
                    ))
                ) {
                    // Calculate responsive avatar size based on screen
                    val avatarOuterSize = dimens.waveformHeight
                    val avatarInnerSize = (dimens.waveformHeight.value * 0.72f).dp
                    val iconSize = (dimens.waveformHeight.value * 0.31f).dp
                    
                    Box(
                        modifier = Modifier.size(avatarOuterSize),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isPlaying) {
                            Box(
                                modifier = Modifier
                                    .size(avatarOuterSize)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.16f))
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(avatarInnerSize)
                                .clip(CircleShape)
                                .background(
                                    if (isPlaying) MaterialTheme.colorScheme.tertiaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                contentDescription = null,
                                modifier = Modifier.size(iconSize),
                                tint = if (isPlaying) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isPlaying) {
                            val badgeSize = (dimens.waveformHeight.value * 0.22f).dp
                            val badgeIconSize = (badgeSize.value * 0.5f).dp
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(badgeSize)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Secure",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(badgeIconSize)
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(dimens.paddingLarge))
                
                // Status text
                Text(
                    text = when (authState) {
                        AuthState.Authenticated -> if (isPlaying) "Streaming securely" else "Connected"
                        AuthState.Authenticating -> "Authenticating..."
                        is AuthState.Failed -> "Authentication failed"
                        AuthState.Disconnected -> "Server disconnected"
                        AuthState.NotAuthenticated -> "Not connected"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when (authState) {
                        AuthState.Authenticated -> if (isPlaying) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                        is AuthState.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                
                Spacer(modifier = Modifier.height(dimens.paddingSmall))
                
                // Server info with lock icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (authState == AuthState.Authenticated) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Encrypted",
                            modifier = Modifier.size(dimens.iconTiny),
                            tint = SuccessGreen
                        )
                        Spacer(modifier = Modifier.width(dimens.paddingTiny))
                    }
                    Text(
                        text = if (authState == AuthState.Authenticated) "AES-256 Encrypted" else serverAddress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.height(dimens.paddingLarge * 2))
                
                // Volume control
                AnimatedVisibility(
                    visible = true,
                    enter =
                    slideInVertically(
                        animationSpec = spring(
                            dampingRatio = 0.82f,
                            stiffness = Spring.StiffnessLow
                        ),
                        initialOffsetY = { it / 6 }
                    ) +
                        fadeIn(animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow))
                ) {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Volume",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${(volume * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Slider(
                                value = volume,
                                onValueChange = { 
                                    volume = it
                                    service?.setVolume(it)
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Stats card
                AnimatedVisibility(
                    visible = true,
                    enter =
                    slideInVertically(
                        animationSpec = spring(
                            dampingRatio = 0.85f,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        initialOffsetY = { it / 5 }
                    ) +
                        fadeIn(animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow))
                ) {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem(label = "Buffer", value = "${bufferSizeMs}ms")
                            StatItem(label = "Latency", value = "${networkLatencyMs + bufferSizeMs}ms")
                            // Show connection quality based on packet loss
                            val quality = when {
                                packetsReceived == 0L -> "--"
                                packetsLost == 0L -> "Excellent"
                                packetsLost * 100 / (packetsReceived + packetsLost) < 1 -> "Good"
                                packetsLost * 100 / (packetsReceived + packetsLost) < 5 -> "Fair"
                                else -> "Poor"
                            }
                            StatItem(label = "Quality", value = quality)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Stop button
                val stopInteraction = remember { MutableInteractionSource() }
                val stopPressed by stopInteraction.collectIsPressedAsState()
                val stopScale by animateFloatAsState(
                    targetValue = if (stopPressed) 1.06f else 1f,
                    animationSpec = spring(
                        dampingRatio = 0.76f,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "stopScale"
                )

                Button(
                    onClick = { showDisconnectConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .graphicsLayer(scaleX = stopScale, scaleY = stopScale),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    interactionSource = stopInteraction
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Disconnect",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.StopCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Disconnect from server?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "You will stop streaming audio and close the secure connection.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
                confirmButton = {
                    val confirmInteraction = remember { MutableInteractionSource() }
                    val confirmPressed by confirmInteraction.collectIsPressedAsState()
                    val confirmScale by animateFloatAsState(
                        targetValue = if (confirmPressed) 1.06f else 1f,
                        animationSpec = spring(
                            dampingRatio = 0.76f,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "confirmScale"
                    )

                    Button(
                        onClick = {
                            val stopIntent = Intent(context, AudioStreamService::class.java).apply {
                                action = AudioStreamService.ACTION_STOP
                            }
                            context.startService(stopIntent)
                            showDisconnectConfirm = false
                            onBack(false)
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .graphicsLayer(scaleX = confirmScale, scaleY = confirmScale),
                        shape = RoundedCornerShape(14.dp),
                        interactionSource = confirmInteraction
                    ) {
                        Text("Disconnect")
                    }
                },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun startAudioService(context: Context, serverAddress: String, serverName: String, pin: String) {
    val intent = Intent(context, AudioStreamService::class.java).apply {
        action = AudioStreamService.ACTION_START
        putExtra(AudioStreamService.EXTRA_SERVER_ADDRESS, serverAddress)
        putExtra(AudioStreamService.EXTRA_SERVER_NAME, serverName)
        putExtra(AudioStreamService.EXTRA_PIN, pin)
    }
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
    }
}
