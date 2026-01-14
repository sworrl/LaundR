package com.laundr.droid.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.laundr.droid.ble.BleScanner
import com.laundr.droid.ble.GattManager
import com.laundr.droid.ui.theme.CyberGreen
import com.laundr.droid.ui.theme.CyberOrange
import com.laundr.droid.ui.theme.CyberRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    bleScanner: BleScanner,
    gattManager: GattManager,
    onBack: () -> Unit
) {
    val scanLog by bleScanner.scanLog.collectAsState()
    val gattLog by gattManager.log.collectAsState()
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val logText = when (selectedTab) {
                            0 -> bleScanner.getLogText()
                            else -> gattManager.getLogText()
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("LaunDRoid Log", logText))
                        Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = {
                        when (selectedTab) {
                            0 -> bleScanner.clearLog()
                            else -> gattManager.clearLog()
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
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
            // Tab selector
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Scan Log (${scanLog.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("GATT Log (${gattLog.size})") }
                )
            }

            // Log content
            val currentLog = when (selectedTab) {
                0 -> scanLog
                else -> gattLog
            }

            if (currentLog.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        text = "No log entries yet",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(currentLog.reversed()) { entry ->
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                entry.contains("ERROR") -> CyberRed
                                entry.contains("CSC") -> CyberGreen
                                entry.contains("Found:") -> CyberOrange
                                entry.contains("===") -> CyberGreen
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            }
                        )
                    }
                }
            }
        }
    }
}
