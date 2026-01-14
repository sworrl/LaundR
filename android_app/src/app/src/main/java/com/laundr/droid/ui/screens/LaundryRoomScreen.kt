package com.laundr.droid.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laundr.droid.ble.*
import com.laundr.droid.ui.theme.*

// Room verification status
enum class RoomVerificationStatus {
    UNKNOWN,      // Not enough data
    VERIFIED,     // 2+ devices detected - we're at this room
    PARTIAL,      // 1 device detected
    NOT_DETECTED  // No devices from this room found
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaundryRoomScreen(
    laundryRoomManager: LaundryRoomManager,
    bleScanner: BleScanner,
    onBack: () -> Unit,
    onNavigateToDevice: (String) -> Unit
) {
    val context = LocalContext.current
    val rooms by laundryRoomManager.rooms.collectAsState()
    val selectedRoom by laundryRoomManager.selectedRoom.collectAsState()
    val bleDevices by bleScanner.devices.collectAsState()
    val isScanning by bleScanner.isScanning.collectAsState()

    var showCreateRoomDialog by remember { mutableStateOf(false) }
    var showAddMachineDialog by remember { mutableStateOf(false) }
    var newRoomName by remember { mutableStateOf("") }
    var newRoomLocation by remember { mutableStateOf("") }

    // Auto-interrogate: Start scanning when entering a room
    LaunchedEffect(selectedRoom) {
        if (selectedRoom != null && selectedRoom!!.machines.isNotEmpty() && !isScanning) {
            bleScanner.startScan()
        }
    }

    // Update machine status when BLE scan results change
    LaunchedEffect(bleDevices) {
        laundryRoomManager.updateMachineStatus(bleDevices)
    }

    // Calculate room verification status based on detected devices
    val roomVerificationStatus = remember(selectedRoom, bleDevices) {
        if (selectedRoom == null || selectedRoom!!.machines.isEmpty()) {
            RoomVerificationStatus.UNKNOWN
        } else {
            // Count how many of this room's machines are currently detected
            val detectedCount = selectedRoom!!.machines.count { machine ->
                bleDevices.containsKey(machine.bleAddress)
            }
            when {
                detectedCount >= 2 -> RoomVerificationStatus.VERIFIED
                detectedCount == 1 -> RoomVerificationStatus.PARTIAL
                else -> RoomVerificationStatus.NOT_DETECTED
            }
        }
    }

    val detectedMachineCount = remember(selectedRoom, bleDevices) {
        selectedRoom?.machines?.count { machine ->
            bleDevices.containsKey(machine.bleAddress)
        } ?: 0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selectedRoom?.name ?: "Laundry Rooms")
                            // Room verification badge
                            if (selectedRoom != null && selectedRoom!!.machines.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                when (roomVerificationStatus) {
                                    RoomVerificationStatus.VERIFIED -> {
                                        Badge(containerColor = CyberGreen) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                null,
                                                modifier = Modifier.size(12.dp),
                                                tint = Color.Black
                                            )
                                        }
                                    }
                                    RoomVerificationStatus.PARTIAL -> {
                                        Badge(containerColor = CyberOrange) {
                                            Text("?", color = Color.Black)
                                        }
                                    }
                                    RoomVerificationStatus.NOT_DETECTED -> {
                                        if (isScanning) {
                                            Badge(containerColor = CyberRed.copy(alpha = 0.7f)) {
                                                Text("×", color = Color.White)
                                            }
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                        if (selectedRoom != null) {
                            Text(
                                text = when (roomVerificationStatus) {
                                    RoomVerificationStatus.VERIFIED -> "✓ Verified ($detectedMachineCount/${selectedRoom!!.machineCount} detected)"
                                    RoomVerificationStatus.PARTIAL -> "1/${selectedRoom!!.machineCount} detected (need 2+ to verify)"
                                    RoomVerificationStatus.NOT_DETECTED -> if (isScanning) "Scanning..." else "${selectedRoom!!.inRangeCount}/${selectedRoom!!.machineCount} in range"
                                    else -> "${selectedRoom!!.inRangeCount}/${selectedRoom!!.machineCount} in range"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when (roomVerificationStatus) {
                                    RoomVerificationStatus.VERIFIED -> CyberGreen
                                    RoomVerificationStatus.PARTIAL -> CyberOrange
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedRoom != null) {
                            laundryRoomManager.selectRoom("")
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Scan toggle
                    IconButton(onClick = {
                        if (isScanning) bleScanner.stopScan() else bleScanner.startScan()
                    }) {
                        Icon(
                            if (isScanning) Icons.Default.Stop else Icons.Default.BluetoothSearching,
                            contentDescription = if (isScanning) "Stop Scan" else "Start Scan",
                            tint = if (isScanning) CyberGreen else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (selectedRoom != null) {
                        IconButton(onClick = { showAddMachineDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Machine")
                        }
                    } else {
                        IconButton(onClick = { showCreateRoomDialog = true }) {
                            Icon(Icons.Default.AddHome, contentDescription = "Create Room")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (selectedRoom != null) {
            // Show machines in selected room
            LaundryRoomDetail(
                room = selectedRoom!!,
                bleDevices = bleDevices,
                isScanning = isScanning,
                onMachineClick = { machine ->
                    onNavigateToDevice(machine.bleAddress)
                },
                onRemoveMachine = { machine ->
                    laundryRoomManager.removeMachineFromRoom(selectedRoom!!.id, machine.bleAddress)
                    Toast.makeText(context, "Removed ${machine.displayName}", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.padding(padding)
            )
        } else {
            // Show room list
            RoomList(
                rooms = rooms,
                onRoomClick = { room ->
                    laundryRoomManager.selectRoom(room.id)
                },
                onDeleteRoom = { room ->
                    laundryRoomManager.deleteRoom(room.id)
                    Toast.makeText(context, "Deleted ${room.name}", Toast.LENGTH_SHORT).show()
                },
                onCreateRoom = { showCreateRoomDialog = true },
                modifier = Modifier.padding(padding)
            )
        }
    }

    // Create room dialog
    if (showCreateRoomDialog) {
        AlertDialog(
            onDismissRequest = { showCreateRoomDialog = false },
            title = { Text("Create Laundry Room") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newRoomName,
                        onValueChange = { newRoomName = it },
                        label = { Text("Room Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRoomLocation,
                        onValueChange = { newRoomLocation = it },
                        label = { Text("Location (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newRoomName.isNotBlank()) {
                            laundryRoomManager.createRoom(
                                newRoomName,
                                newRoomLocation.takeIf { it.isNotBlank() }
                            )
                            newRoomName = ""
                            newRoomLocation = ""
                            showCreateRoomDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateRoomDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add machine dialog
    if (showAddMachineDialog && selectedRoom != null) {
        AddMachineDialog(
            bleDevices = bleDevices,
            existingAddresses = selectedRoom!!.machines.map { it.bleAddress }.toSet(),
            laundryRoomManager = laundryRoomManager,
            onDismiss = { showAddMachineDialog = false },
            onAddMachine = { device, nickname ->
                val machine = laundryRoomManager.parseMachineFromDevice(device)
                if (machine != null) {
                    laundryRoomManager.addMachineToRoom(selectedRoom!!.id, machine, nickname)
                    Toast.makeText(context, "Added ${nickname ?: machine.displayName}", Toast.LENGTH_SHORT).show()
                }
                showAddMachineDialog = false
            }
        )
    }
}

@Composable
private fun RoomList(
    rooms: List<LaundryRoom>,
    onRoomClick: (LaundryRoom) -> Unit,
    onDeleteRoom: (LaundryRoom) -> Unit,
    onCreateRoom: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (rooms.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Home,
                    null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
                Text(
                    "No laundry rooms",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    "Create a room to organize your machines",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Button(onClick = onCreateRoom) {
                    Icon(Icons.Default.AddHome, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Create Room")
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(rooms, key = { it.id }) { room ->
                RoomCard(
                    room = room,
                    onClick = { onRoomClick(room) },
                    onDelete = { onDeleteRoom(room) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomCard(
    room: LaundryRoom,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(CyberGreen.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.LocalLaundryService, null, tint = CyberGreen)
                }

                Column {
                    Text(
                        room.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    room.location?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Text(
                        "${room.machineCount} machines (${room.washerCount}W / ${room.dryerCount}D)",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberGreen
                    )
                }
            }

            Row {
                if (room.inRangeCount > 0) {
                    Badge(
                        containerColor = CyberGreen,
                        contentColor = Color.Black
                    ) {
                        Text("${room.inRangeCount}")
                    }
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Icons.Default.Delete,
                        null,
                        tint = CyberRed.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Room?") },
            text = { Text("Delete \"${room.name}\" and all its machines?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text("Delete", color = CyberRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun LaundryRoomDetail(
    room: LaundryRoom,
    bleDevices: Map<String, BleDeviceInfo>,
    isScanning: Boolean,
    onMachineClick: (MachineInfo) -> Unit,
    onRemoveMachine: (MachineInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    if (room.machines.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.DevicesOther,
                    null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
                Text(
                    "No machines",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    "Tap + to add machines from BLE scan",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                if (!isScanning) {
                    Text(
                        "Start scanning to find nearby machines",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberOrange
                    )
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(room.machines, key = { it.bleAddress }) { machine ->
                MachineCard(
                    machine = machine,
                    isInScan = bleDevices.containsKey(machine.bleAddress),
                    onClick = { onMachineClick(machine) },
                    onRemove = { onRemoveMachine(machine) }
                )
            }
        }
    }
}

@Composable
private fun MachineCard(
    machine: MachineInfo,
    isInScan: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    var showRemoveConfirm by remember { mutableStateOf(false) }

    val statusColor = when {
        isInScan -> CyberGreen
        machine.inRange -> CyberOrange
        else -> CyberRed
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isInScan)
                CyberGreen.copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Machine icon with status indicator
                Box {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                statusColor.copy(alpha = 0.2f),
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            when (machine.machineType) {
                                MachineType.WASHER -> Icons.Default.LocalLaundryService
                                MachineType.DRYER -> Icons.Default.AcUnit
                                else -> Icons.Default.DevicesOther
                            },
                            null,
                            tint = statusColor
                        )
                    }

                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                            .background(statusColor, CircleShape)
                    )
                }

                Column {
                    Text(
                        machine.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        machine.bleName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when {
                                isInScan -> "In Range (${machine.rssi}dBm)"
                                machine.inRange -> "Nearby"
                                else -> "Out of Range"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                        Text(
                            machine.provider,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = { showRemoveConfirm = true }) {
                    Icon(
                        Icons.Default.Close,
                        null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                Text(
                    machine.lastSeenFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove Machine?") },
            text = { Text("Remove \"${machine.displayName}\" from this room?") },
            confirmButton = {
                TextButton(onClick = { onRemove(); showRemoveConfirm = false }) {
                    Text("Remove", color = CyberRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMachineDialog(
    bleDevices: Map<String, BleDeviceInfo>,
    existingAddresses: Set<String>,
    laundryRoomManager: LaundryRoomManager,
    onDismiss: () -> Unit,
    onAddMachine: (BleDeviceInfo, String?) -> Unit
) {
    var selectedDevice by remember { mutableStateOf<BleDeviceInfo?>(null) }
    var nickname by remember { mutableStateOf("") }

    // Filter to show only laundry-related devices not already in room
    val availableDevices = bleDevices.values
        .filter { it.address !in existingAddresses }
        .filter { device ->
            laundryRoomManager.parseMachineFromDevice(device) != null
        }
        .sortedByDescending { it.rssi }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Machine") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (availableDevices.isEmpty()) {
                    Text(
                        "No laundry devices found. Make sure BLE scanning is active.",
                        color = CyberOrange
                    )
                } else {
                    Text("Select a device:", style = MaterialTheme.typography.labelMedium)

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(availableDevices) { device ->
                            val machineInfo = laundryRoomManager.parseMachineFromDevice(device)
                            val isSelected = selectedDevice?.address == device.address

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedDevice = device
                                        nickname = machineInfo?.displayName ?: device.name ?: ""
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected)
                                        CyberGreen.copy(alpha = 0.2f)
                                    else
                                        MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            selectedDevice = device
                                            nickname = machineInfo?.displayName ?: device.name ?: ""
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            machineInfo?.displayName ?: (device.name ?: "Unknown"),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            device.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Text(
                                        "${device.rssi}dBm",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CyberGreen
                                    )
                                }
                            }
                        }
                    }

                    if (selectedDevice != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            label = { Text("Nickname (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedDevice?.let { onAddMachine(it, nickname.takeIf { it.isNotBlank() }) }
                },
                enabled = selectedDevice != null
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
