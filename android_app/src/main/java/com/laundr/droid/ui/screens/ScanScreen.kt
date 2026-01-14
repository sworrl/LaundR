package com.laundr.droid.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.laundr.droid.ble.BleDeviceInfo
import com.laundr.droid.ble.BleScanner
import com.laundr.droid.ble.LaundryRoom
import com.laundr.droid.ble.LaundryRoomManager
import com.laundr.droid.ble.MachineInfo
import com.laundr.droid.ui.theme.CyberBlue
import com.laundr.droid.ui.theme.CyberGreen
import com.laundr.droid.ui.theme.CyberOrange
import com.laundr.droid.ui.theme.CyberRed

// Device type enum for tagging
enum class DeviceTag {
    UNKNOWN, WASHER, DRYER, NOT_LAUNDRY
}

// Saved device data class
data class SavedDevice(
    val address: String,
    val name: String?,
    val tag: DeviceTag
)

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ScanScreen(
    bleScanner: BleScanner,
    onDeviceClick: (BleDeviceInfo) -> Unit,
    onCSCExploit: (BleDeviceInfo) -> Unit,
    onBack: () -> Unit
) {
    val devices by bleScanner.devices.collectAsState()
    val isScanning by bleScanner.isScanning.collectAsState()
    val context = LocalContext.current
    // Use singleton LaundryRoomManager from Application
    val laundryRoomManager = (context.applicationContext as com.laundr.droid.LaunDRoidApp).laundryRoomManager
    val rooms by laundryRoomManager.rooms.collectAsState()

    // Tab state: 0 = Washers/Dryers, 1 = Other
    var selectedTab by remember { mutableStateOf(0) }

    // Add all to room dialog
    var showAddAllDialog by remember { mutableStateOf(false) }
    var addedCount by remember { mutableStateOf(0) }
    var showAddedSnackbar by remember { mutableStateOf(false) }

    // Load saved device tags from SharedPreferences
    var savedDevices by remember { mutableStateOf(loadSavedDevices(context)) }

    // Required permissions
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    val permissionState = rememberMultiplePermissionsState(permissions)

    // Auto-start scanning when permissions granted
    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (permissionState.allPermissionsGranted && !isScanning) {
            bleScanner.startScan()
        }
    }

    // Stop scanning when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            bleScanner.stopScan()
        }
    }

    // Categorize devices - respect NOT_LAUNDRY tag
    val allDevices = devices.values.toList()
    val washerDryerDevices = allDevices.filter { device ->
        val tag = savedDevices[device.address]?.tag
        // Exclude if explicitly marked as NOT_LAUNDRY
        if (tag == DeviceTag.NOT_LAUNDRY) return@filter false
        // Include if CSC detected OR tagged as washer/dryer
        device.isCSC || tag in listOf(DeviceTag.WASHER, DeviceTag.DRYER)
    }.sortedByDescending { it.rssi }

    val otherDevices = allDevices.filter { device ->
        val tag = savedDevices[device.address]?.tag
        // Include if: NOT CSC, AND (not tagged as washer/dryer OR tagged as NOT_LAUNDRY)
        (!device.isCSC && tag !in listOf(DeviceTag.WASHER, DeviceTag.DRYER)) ||
        tag == DeviceTag.NOT_LAUNDRY
    }.sortedByDescending { it.rssi }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BLE Scanner")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Animated scanning indicator
                            if (isScanning) {
                                ScanningDot()
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = if (isScanning) "Scanning..." else "Paused",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isScanning) CyberGreen else CyberOrange
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Device count badge with pulse animation
                    AnimatedDeviceCount(count = devices.size)
                    Spacer(modifier = Modifier.width(8.dp))

                    // Toggle scan
                    IconButton(onClick = {
                        if (isScanning) bleScanner.stopScan() else bleScanner.startScan()
                    }) {
                        Icon(
                            imageVector = if (isScanning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isScanning) "Pause" else "Resume",
                            tint = if (isScanning) CyberOrange else CyberGreen
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = {
            if (showAddedSnackbar) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    containerColor = CyberGreen,
                    contentColor = Color.Black,
                    action = {
                        TextButton(onClick = { showAddedSnackbar = false }) {
                            Text("OK", color = Color.Black)
                        }
                    }
                ) {
                    Text("Added $addedCount machines to room")
                }
            }
        }
    ) { padding ->
        if (!permissionState.allPermissionsGranted) {
            // Permission request UI
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = CyberRed
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "BLE Permissions Required",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "LaunDRoid needs Bluetooth and Location permissions to scan for devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { permissionState.launchMultiplePermissionRequest() },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
                ) {
                    Text("Grant Permissions", color = Color.Black)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Tab Row
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = CyberGreen
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.LocalLaundryService,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Laundry (${washerDryerDevices.size})")
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Devices,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Other (${otherDevices.size})")
                            }
                        }
                    )
                }

                // Add All to Room button (only on laundry tab with devices)
                if (selectedTab == 0 && washerDryerDevices.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = { showAddAllDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberBlue)
                        ) {
                            Icon(Icons.Default.AddCircle, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add All to Room (${washerDryerDevices.size})", color = Color.White)
                        }
                    }
                }

                // Device list based on selected tab
                val displayDevices = if (selectedTab == 0) washerDryerDevices else otherDevices

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displayDevices, key = { it.address }) { device ->
                        val deviceTag = savedDevices[device.address]?.tag

                        DeviceCard(
                            device = device,
                            deviceTag = deviceTag,
                            showTagOptions = selectedTab == 1, // Only show tagging in Other tab
                            onClick = { onDeviceClick(device) },
                            onExploit = { onCSCExploit(device) },
                            onTag = { tag ->
                                // Save the tag
                                val saved = SavedDevice(device.address, device.name, tag)
                                savedDevices = savedDevices + (device.address to saved)
                                saveSavedDevices(context, savedDevices)
                            }
                        )
                    }

                    if (displayDevices.isEmpty() && isScanning) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    // Radar animation
                                    RadarAnimation()
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = if (selectedTab == 0)
                                            "Scanning for laundry devices..."
                                        else
                                            "Scanning for other devices...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    if (displayDevices.isEmpty() && !isScanning) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No devices found. Tap play to scan.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add All to Room Dialog
    if (showAddAllDialog) {
        AddAllToRoomDialog(
            rooms = rooms,
            deviceCount = washerDryerDevices.size,
            onDismiss = { showAddAllDialog = false },
            onCreateRoom = { name ->
                laundryRoomManager.createRoom(name)
            },
            onSelectRoom = { room ->
                var count = 0
                washerDryerDevices.forEach { device ->
                    val machine = laundryRoomManager.parseMachineFromDevice(device)
                    if (machine != null) {
                        laundryRoomManager.addMachineToRoom(room.id, machine)
                        count++
                    }
                }
                addedCount = count
                showAddAllDialog = false
                showAddedSnackbar = true
            }
        )
    }
}

/**
 * Animated scanning dot indicator
 */
@Composable
fun ScanningDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "scan_dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(CyberGreen, CircleShape)
    )
}

/**
 * Animated device count badge
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedDeviceCount(count: Int) {
    var previousCount by remember { mutableStateOf(count) }
    val scale = remember { Animatable(1f) }

    LaunchedEffect(count) {
        if (count != previousCount) {
            previousCount = count
            scale.animateTo(
                targetValue = 1.3f,
                animationSpec = tween(100)
            )
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            )
        }
    }

    Badge(
        containerColor = CyberGreen,
        modifier = Modifier.scale(scale.value)
    ) {
        Text("$count")
    }
}

/**
 * Radar scanning animation
 */
@Composable
fun RadarAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")

    val ring1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1"
    )

    val ring2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing, delayMillis = 500),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring2"
    )

    val ring3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing, delayMillis = 1000),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring3"
    )

    Box(
        modifier = Modifier.size(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = size.width / 2
            val maxRadius = size.width / 2

            // Draw expanding rings
            listOf(ring1, ring2, ring3).forEach { progress ->
                val radius = maxRadius * progress
                val alpha = 1f - progress
                drawCircle(
                    color = CyberGreen.copy(alpha = alpha * 0.5f),
                    radius = radius,
                    style = Stroke(width = 3.dp.toPx())
                )
            }

            // Center dot
            drawCircle(
                color = CyberGreen,
                radius = 8.dp.toPx()
            )
        }
    }
}

/**
 * Add All to Room Dialog
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAllToRoomDialog(
    rooms: List<LaundryRoom>,
    deviceCount: Int,
    onDismiss: () -> Unit,
    onCreateRoom: (String) -> Unit,
    onSelectRoom: (LaundryRoom) -> Unit
) {
    var showCreateRoom by remember { mutableStateOf(false) }
    var newRoomName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Add All Machines to Room")
        },
        text = {
            Column {
                Text(
                    "$deviceCount laundry devices will be added",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(16.dp))

                if (showCreateRoom) {
                    OutlinedTextField(
                        value = newRoomName,
                        onValueChange = { newRoomName = it },
                        label = { Text("Room Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showCreateRoom = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                if (newRoomName.isNotBlank()) {
                                    onCreateRoom(newRoomName)
                                    showCreateRoom = false
                                    newRoomName = ""
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
                        ) {
                            Text("Create", color = Color.Black)
                        }
                    }
                } else {
                    // Room list
                    if (rooms.isEmpty()) {
                        Text(
                            "No rooms yet. Create one first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    } else {
                        rooms.forEach { room ->
                            Card(
                                onClick = { onSelectRoom(room) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.MeetingRoom, null, tint = CyberBlue)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(room.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "${room.machineCount} machines",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, null)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showCreateRoom = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Create New Room")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCard(
    device: BleDeviceInfo,
    deviceTag: DeviceTag?,
    showTagOptions: Boolean,
    onClick: () -> Unit,
    onExploit: () -> Unit,
    onTag: (DeviceTag) -> Unit
) {
    // Animate new devices appearing
    var visible by remember { mutableStateOf(false) }
    val animatedAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300),
        label = "card_alpha"
    )
    val animatedScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.8f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "card_scale"
    )

    LaunchedEffect(Unit) {
        visible = true
    }

    // RSSI animation
    val rssiColor = when {
        device.rssi > -50 -> CyberGreen
        device.rssi > -70 -> CyberBlue
        device.rssi > -85 -> CyberOrange
        else -> CyberRed
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(animatedAlpha)
            .scale(animatedScale),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isCSC)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (device.isCSC) {
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(CyberGreen)
            )
        } else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // CSC/Tag indicator with pulse for CSC devices
                if (device.isCSC) {
                    PulsingBadge(text = "CSC", color = CyberGreen)
                } else if (deviceTag == DeviceTag.WASHER) {
                    Badge(
                        containerColor = CyberBlue,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("WASHER", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                } else if (deviceTag == DeviceTag.DRYER) {
                    Badge(
                        containerColor = CyberOrange,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("DRYER", color = Color.Black, style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Device name
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name ?: "Unknown Device",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (device.isCSC) CyberGreen else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                // Animated RSSI indicator
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${device.rssi} dBm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = rssiColor
                    )
                    // Animated signal bars
                    AnimatedSignalBars(rssi = device.rssi, color = rssiColor)
                }
            }

            // Service UUIDs
            if (device.serviceUuids.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Services: ${device.serviceUuids.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            // CSC Exploit button
            if (device.isCSC) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onExploit,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberRed),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("CVE Test", color = Color.White)
                    }
                    // Not a washer button for false positives
                    OutlinedButton(
                        onClick = { onTag(DeviceTag.NOT_LAUNDRY) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberOrange)
                    ) {
                        Icon(Icons.Default.RemoveCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Not Laundry")
                    }
                }
            }

            // Tag options for Other devices
            if (showTagOptions && !device.isCSC) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onTag(DeviceTag.WASHER) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = CyberBlue
                        )
                    ) {
                        Icon(Icons.Default.LocalLaundryService, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Washer")
                    }
                    OutlinedButton(
                        onClick = { onTag(DeviceTag.DRYER) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = CyberOrange
                        )
                    ) {
                        Icon(Icons.Default.Dry, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Dryer")
                    }
                }
            }
        }
    }
}

/**
 * Pulsing badge for CSC devices
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulsingBadge(text: String, color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_badge")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "badge_scale"
    )

    Badge(
        containerColor = color,
        modifier = Modifier
            .padding(end = 8.dp)
            .scale(scale)
    ) {
        Text(text, color = Color.Black, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Animated signal strength bars
 */
@Composable
fun AnimatedSignalBars(rssi: Int, color: Color) {
    val activeBars = when {
        rssi > -50 -> 4
        rssi > -65 -> 3
        rssi > -80 -> 2
        else -> 1
    }

    Row {
        repeat(4) { index ->
            val active = index < activeBars
            SignalBar(
                index = index,
                active = active,
                color = color
            )
        }
    }
}

@Composable
private fun SignalBar(index: Int, active: Boolean, color: Color) {
    val alpha = if (active) {
        val infiniteTransition = rememberInfiniteTransition(label = "bar_$index")
        infiniteTransition.animateFloat(
            initialValue = 0.7f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(500 + index * 100),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_alpha_$index"
        ).value
    } else {
        0.2f
    }

    Box(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .width(4.dp)
            .height((8 + index * 4).dp)
            .background(color.copy(alpha = alpha))
    )
}

// Persistence functions for saved devices
private fun loadSavedDevices(context: Context): Map<String, SavedDevice> {
    val prefs = context.getSharedPreferences("laundr_devices", Context.MODE_PRIVATE)
    val result = mutableMapOf<String, SavedDevice>()

    prefs.all.forEach { (address, value) ->
        if (value is String) {
            val parts = value.split("|")
            if (parts.size >= 2) {
                val name = parts[0].takeIf { it != "null" }
                val tag = try { DeviceTag.valueOf(parts[1]) } catch (_: Exception) { DeviceTag.UNKNOWN }
                result[address] = SavedDevice(address, name, tag)
            }
        }
    }

    return result
}

private fun saveSavedDevices(context: Context, devices: Map<String, SavedDevice>) {
    val prefs = context.getSharedPreferences("laundr_devices", Context.MODE_PRIVATE)
    val editor = prefs.edit()
    editor.clear()

    devices.forEach { (address, device) ->
        editor.putString(address, "${device.name ?: "null"}|${device.tag.name}")
    }

    editor.apply()
}
