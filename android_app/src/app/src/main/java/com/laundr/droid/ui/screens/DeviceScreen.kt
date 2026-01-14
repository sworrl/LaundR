package com.laundr.droid.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.laundr.droid.ble.BleDeviceInfo
import com.laundr.droid.ble.ConnectionState
import com.laundr.droid.ble.GattManager
import com.laundr.droid.ble.GattServiceInfo
import com.laundr.droid.ui.theme.CyberBlue
import com.laundr.droid.ui.theme.CyberGreen
import com.laundr.droid.ui.theme.CyberOrange
import com.laundr.droid.ui.theme.CyberRed
import java.util.UUID

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    deviceInfo: BleDeviceInfo?,
    gattManager: GattManager,
    onBack: () -> Unit,
    onCSCExploit: () -> Unit
) {
    val connectionState by gattManager.connectionState.collectAsState()
    val services by gattManager.services.collectAsState()
    val log by gattManager.log.collectAsState()

    // Auto-connect when screen opens
    LaunchedEffect(deviceInfo) {
        deviceInfo?.let { device ->
            if (connectionState == ConnectionState.DISCONNECTED) {
                gattManager.connect(device.device)
            }
        }
    }

    // Disconnect when leaving
    DisposableEffect(Unit) {
        onDispose {
            gattManager.disconnect()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(deviceInfo?.name ?: "Unknown Device")
                        Text(
                            text = connectionState.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (connectionState) {
                                ConnectionState.READY -> CyberGreen
                                ConnectionState.CONNECTED,
                                ConnectionState.DISCOVERING_SERVICES -> CyberBlue
                                ConnectionState.CONNECTING -> CyberOrange
                                else -> CyberRed
                            }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (deviceInfo?.isCSC == true) {
                        IconButton(onClick = onCSCExploit) {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = "Exploit",
                                tint = CyberRed
                            )
                        }
                    }

                    IconButton(onClick = {
                        if (connectionState == ConnectionState.DISCONNECTED) {
                            deviceInfo?.let { gattManager.connect(it.device) }
                        } else {
                            gattManager.disconnect()
                        }
                    }) {
                        Icon(
                            imageVector = if (connectionState == ConnectionState.DISCONNECTED)
                                Icons.Default.Bluetooth
                            else
                                Icons.Default.BluetoothDisabled,
                            contentDescription = null,
                            tint = if (connectionState == ConnectionState.READY) CyberGreen else CyberOrange
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Device info header
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Address: ${deviceInfo?.address ?: "N/A"}")
                    Text("RSSI: ${deviceInfo?.rssi ?: 0} dBm")
                    Text("Connectable: ${deviceInfo?.isConnectable ?: false}")
                    if (deviceInfo?.isCSC == true) {
                        Text("Type: CSC Device", color = CyberGreen)
                    }
                }
            }

            // Connection status
            if (connectionState == ConnectionState.CONNECTING ||
                connectionState == ConnectionState.DISCOVERING_SERVICES) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = CyberGreen
                )
            }

            // Services list
            if (services.isNotEmpty()) {
                Text(
                    text = "GATT Services (${services.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(8.dp)
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(services) { service ->
                        ServiceCard(
                            service = service,
                            gattManager = gattManager
                        )
                    }
                }
            } else if (connectionState == ConnectionState.READY) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No services discovered")
                }
            } else if (connectionState == ConnectionState.DISCONNECTED) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.BluetoothDisabled,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = CyberRed
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Disconnected")
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { deviceInfo?.let { gattManager.connect(it.device) } },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
                        ) {
                            Text("Reconnect", color = Color.Black)
                        }
                    }
                }
            }

            // Log section
            if (log.isNotEmpty()) {
                Divider()
                Text(
                    text = "Log",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(8.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .height(150.dp)
                        .padding(horizontal = 8.dp)
                ) {
                    items(log.takeLast(20).reversed()) { entry ->
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceCard(
    service: GattServiceInfo,
    gattManager: GattManager
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = CyberBlue
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = getServiceName(service.uuid),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = service.uuid.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Badge(containerColor = CyberBlue) {
                    Text("${service.characteristics.size}")
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                service.characteristics.forEach { char ->
                    CharacteristicRow(
                        serviceUuid = service.uuid,
                        characteristic = char,
                        gattManager = gattManager
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun CharacteristicRow(
    serviceUuid: UUID,
    characteristic: com.laundr.droid.ble.GattCharacteristicInfo,
    gattManager: GattManager
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = characteristic.uuid.toString().take(8) + "...",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "[${characteristic.propertiesString()}]",
                style = MaterialTheme.typography.bodySmall,
                color = CyberOrange
            )
        }

        if (characteristic.canRead) {
            IconButton(
                onClick = { gattManager.readCharacteristic(serviceUuid, characteristic.uuid) }
            ) {
                Icon(Icons.Default.Download, contentDescription = "Read", tint = CyberGreen)
            }
        }

        if (characteristic.canNotify) {
            IconButton(
                onClick = { gattManager.enableNotifications(serviceUuid, characteristic.uuid) }
            ) {
                Icon(Icons.Default.Notifications, contentDescription = "Notify", tint = CyberBlue)
            }
        }
    }
}

fun getServiceName(uuid: UUID): String {
    val shortUuid = uuid.toString().substring(4, 8).uppercase()
    return when (shortUuid) {
        "1800" -> "Generic Access"
        "1801" -> "Generic Attribute"
        "180A" -> "Device Information"
        "180F" -> "Battery Service"
        "FFF0" -> "CSC Payment Service"
        "FFE0" -> "CSC Control Service"
        else -> "Service $shortUuid"
    }
}
