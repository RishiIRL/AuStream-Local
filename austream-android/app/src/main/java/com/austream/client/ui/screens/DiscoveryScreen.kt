package com.austream.client.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Divider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.austream.client.ui.components.ConnectionData
import com.austream.client.ui.components.QrCodeScanner
import com.austream.client.util.RecentConnectionsManager
import com.austream.client.ui.theme.rememberDimensions
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    onServerSelected: (address: String, name: String, pin: String) -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dimens = rememberDimensions()
    
    // Load recent connections from SharedPreferences
    val recentConnections = remember { mutableStateListOf<ConnectionData>() }
    
    // Reload connections when screen becomes visible (e.g., after returning from PlaybackScreen)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                recentConnections.clear()
                recentConnections.addAll(RecentConnectionsManager.loadConnections(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    var showQrScanner by remember { mutableStateOf(false) }
    var showIpSheet by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var manualIp by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    var scannedData by remember { mutableStateOf<ConnectionData?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }


    
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            showQrScanner = true
        } else {
            errorMessage = "Camera permission required for QR scanning"
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // QR Scanner fullscreen overlay
        AnimatedVisibility(
            visible = showQrScanner && hasCameraPermission,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                QrCodeScanner(
                    modifier = Modifier.fillMaxSize(),
                    onQrCodeScanned = { data ->
                        scannedData = data
                        showQrScanner = false
                        val hostOnly = sanitizeHostAndPort(data.ip).first
                        // Don't add to recent connections here - will be added after successful auth
                        onServerSelected(hostOnly, data.serverName, data.pin)
                        scope.launch { snackbarHostState.showSnackbar("Connecting to ${data.serverName}...") }
                    },
                    onError = { error ->
                        errorMessage = error
                        showQrScanner = false
                    }
                )
                
                // Close button
                IconButton(
                    onClick = { showQrScanner = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .statusBarsPadding()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            RoundedCornerShape(50)
                        )
                ) {
                    Icon(Icons.Default.Close, "Close scanner")
                }
                
                // Scanning hint
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(32.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Point camera at QR code on your PC",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        
        // Main content
        AnimatedVisibility(visible = !showQrScanner, enter = fadeIn(), exit = fadeOut()) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    LargeTopAppBar(
                        modifier = Modifier.padding(bottom = 10.dp),
                        title = {
                            val collapsedFraction = scrollBehavior.state.collapsedFraction
                            val showSubtitle = collapsedFraction < 0.55f
                            val titlePad = if (collapsedFraction > 0.55f) 10.dp else 8.dp

                            Column(modifier = Modifier.padding(vertical = titlePad)) {
                                Text(
                                    "AuStream",
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (showSubtitle) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        "Listen from your PC",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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
                Column(
                    modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        .padding(horizontal = dimens.paddingMedium, vertical = dimens.paddingSmall)
                        .padding(bottom = dimens.paddingLarge)
                                .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AnimatedVisibility(
                        visible = true,
                        enter =
                        slideInVertically(
                            animationSpec = spring(
                                dampingRatio = 0.82f,
                                stiffness = Spring.StiffnessLow
                            ),
                            initialOffsetY = { fullHeight -> fullHeight / 8 }
                        ) +
                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = 150,
                                    easing = LinearOutSlowInEasing
                                )
                            ) +
                            scaleIn(
                                initialScale = 0.94f,
                                animationSpec = spring(
                                    dampingRatio = 0.78f,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                    ) {
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(
                                    animationSpec = spring(
                                        dampingRatio = 0.85f,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                ),
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = dimens.paddingLarge, vertical = dimens.paddingLarge),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(dimens.paddingMedium)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(dimens.heroContainer)
                                        .clip(RoundedCornerShape(dimens.radiusLarge))
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Computer,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(dimens.iconLarge)
                                    )
                                }

                                Text(
                                    text = "Pair with your PC",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    text = "Scan the QR from AuStream Server or enter the IP manually.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )

                                Divider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)

                                val primaryInteraction = remember { MutableInteractionSource() }
                                val primaryPressed by primaryInteraction.collectIsPressedAsState()
                                val primaryScale by animateFloatAsState(
                                    targetValue = if (primaryPressed) 1.05f else 1f,
                                    animationSpec = spring(
                                        dampingRatio = 0.78f,
                                        stiffness = Spring.StiffnessMedium
                                    ),
                                    label = "primaryButtonScale"
                                )

                                val secondaryInteraction = remember { MutableInteractionSource() }
                                val secondaryPressed by secondaryInteraction.collectIsPressedAsState()
                                val secondaryScale by animateFloatAsState(
                                    targetValue = if (secondaryPressed) 1.04f else 1f,
                                    animationSpec = spring(
                                        dampingRatio = 0.8f,
                                        stiffness = Spring.StiffnessMedium
                                    ),
                                    label = "secondaryButtonScale"
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(dimens.paddingSmall)) {
                                    Button(
                                        onClick = {
                                            if (hasCameraPermission) {
                                                showQrScanner = true
                                            } else {
                                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(dimens.buttonHeight)
                                            .graphicsLayer(
                                                scaleX = primaryScale,
                                                scaleY = primaryScale
                                            ),
                                        shape = RoundedCornerShape(dimens.radiusMedium),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        contentPadding = PaddingValues(horizontal = dimens.paddingMedium, vertical = dimens.paddingSmall),
                                        interactionSource = primaryInteraction
                                    ) {
                                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                                        Spacer(modifier = Modifier.width(dimens.paddingSmall))
                                        Text("Scan QR")
                                    }

                                    FilledTonalButton(
                                        onClick = { showIpSheet = true },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(dimens.buttonHeight)
                                            .graphicsLayer(
                                                scaleX = secondaryScale,
                                                scaleY = secondaryScale
                                            ),
                                        shape = RoundedCornerShape(dimens.radiusMedium),
                                        contentPadding = PaddingValues(horizontal = dimens.paddingMedium, vertical = dimens.paddingSmall),
                                        interactionSource = secondaryInteraction
                                    ) {
                                        Icon(Icons.Default.Link, contentDescription = null)
                                        Spacer(modifier = Modifier.width(dimens.paddingSmall))
                                        Text("Enter manually")
                                    }
                                }
                            }
                        }
                    }

                    if (recentConnections.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(dimens.paddingLarge))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(dimens.paddingSmall)
                        ) {
                            Text(
                                text = "Recent connections",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            recentConnections.forEach { connection ->
                                val (hostOnly, _) = sanitizeHostAndPort(connection.ip)

                                val recentInteraction = remember { MutableInteractionSource() }
                                val recentPressed by recentInteraction.collectIsPressedAsState()
                                val recentScale by animateFloatAsState(
                                    targetValue = if (recentPressed) 1.04f else 1f,
                                    animationSpec = spring(
                                        dampingRatio = 0.8f,
                                        stiffness = Spring.StiffnessMedium
                                    ),
                                    label = "recentConnectScale"
                                )

                                ElevatedCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(connection.serverName, style = MaterialTheme.typography.titleMedium)
                                            Text(connection.ip, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            if (connection.pin.isNotBlank()) {
                                                Text("PIN: ${connection.pin}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }

                                        FilledTonalButton(
                                            onClick = {
                                                onServerSelected(hostOnly, connection.serverName, connection.pin)
                                            },
                                            modifier = Modifier.graphicsLayer(
                                                scaleX = recentScale,
                                                scaleY = recentScale
                                            ),
                                            interactionSource = recentInteraction,
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Text("Connect")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showIpSheet) {
        ModalBottomSheet(
            onDismissRequest = { showIpSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Enter server IP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    label = { Text("Server IP address") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!isValidIp(manualIp)) {
                                manualError = "Enter a valid IP"
                                return@KeyboardActions
                            }

                            val (hostOnly, _) = sanitizeHostAndPort(manualIp)
                            manualError = null
                            // Don't add to recent connections here - will be added after successful auth
                            onServerSelected(hostOnly, "AuStream Server", "")
                            showIpSheet = false
                        }
                    ),
                    leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                    isError = manualError != null,
                    modifier = Modifier.fillMaxWidth()
                )

                if (manualError != null) {
                    Text(
                        manualError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                val cancelInteraction = remember { MutableInteractionSource() }
                val cancelPressed by cancelInteraction.collectIsPressedAsState()
                val cancelScale by animateFloatAsState(
                    targetValue = if (cancelPressed) 1.04f else 1f,
                    animationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "cancelScale"
                )

                val connectInteraction = remember { MutableInteractionSource() }
                val connectPressed by connectInteraction.collectIsPressedAsState()
                val connectScale by animateFloatAsState(
                    targetValue = if (connectPressed) 1.06f else 1f,
                    animationSpec = spring(
                        dampingRatio = 0.76f,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "connectScale"
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(
                        onClick = { showIpSheet = false },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .graphicsLayer(scaleX = cancelScale, scaleY = cancelScale),
                        interactionSource = cancelInteraction,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            if (!isValidIp(manualIp)) {
                                manualError = "Enter a valid IP"
                                return@Button
                            }

                            val (hostOnly, _) = sanitizeHostAndPort(manualIp)
                            manualError = null
                            // Don't add to recent connections here - will be added after successful auth
                            onServerSelected(hostOnly, "AuStream Server", "")
                            showIpSheet = false
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .graphicsLayer(scaleX = connectScale, scaleY = connectScale),
                        interactionSource = connectInteraction,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Connect")
                    }
                }
            }
        }
    }
}

/**
 * Validate IP address format
 */
private fun isValidIp(input: String): Boolean {
    if (input.isBlank()) return false

    val (host, _) = sanitizeHostAndPort(input)

    // Accept IPv4
    val ipv4Parts = host.split(".")
    val isIpv4 = ipv4Parts.size == 4 && ipv4Parts.all { part ->
        val num = part.toIntOrNull()
        num != null && num in 0..255
    }

    // Accept simple hostnames (letters, digits, dash, dot)
    val hostnameRegex = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*").toPattern()
    val isHostname = hostnameRegex.matcher(host).matches()

    return isIpv4 || isHostname
}

private fun sanitizeHostAndPort(input: String): Pair<String, Int> {
    val trimmed = input.trim()
    val parts = trimmed.split(":")
    val host = parts.firstOrNull().orEmpty()
    val port = parts.getOrNull(1)?.toIntOrNull() ?: 5004
    return host to port
}
