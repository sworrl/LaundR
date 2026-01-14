package com.laundr.droid.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.laundr.droid.ui.theme.CyberBlue
import com.laundr.droid.ui.theme.CyberGreen
import com.laundr.droid.ui.theme.CyberOrange
import com.laundr.droid.ui.theme.CyberRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About LaunDRoid") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App info
            Text(
                text = "🧺",
                style = MaterialTheme.typography.displayLarge
            )
            Text(
                text = "LaunDRoid",
                style = MaterialTheme.typography.titleLarge,
                color = CyberGreen
            )
            Text(
                text = "v1.0.0",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "BLE Security Audit Tool",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(24.dp))

            // CVE Info
            Text(
                text = "Supported Vulnerabilities",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            // CVE-2025-46018
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = CyberRed.copy(alpha = 0.1f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = CyberRed
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "CVE-2025-46018",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyberRed
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "CSC Pay Mobile App Payment Bypass",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Affected: CSC Pay v2.19.4\n" +
                                "Fixed: v2.20.0\n" +
                                "CWE: CWE-284 (Improper Access Control)\n" +
                                "Severity: Medium",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Attack: Disconnect Bluetooth during payment handshake " +
                                "to bypass payment authentication.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // API Vulnerability
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = CyberOrange.copy(alpha = 0.1f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Api,
                            contentDescription = null,
                            tint = CyberOrange
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "CSC Go API Vulnerability",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyberOrange
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Discovered: May 2024 (UC Santa Cruz)\n" +
                                "Impact: 1M+ machines worldwide\n" +
                                "Status: Partially patched",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(24.dp))

            // Disclaimer
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "AUTHORIZED USE ONLY",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This tool is intended for authorized security testing " +
                                "and audit purposes only. Use only on systems you own " +
                                "or have explicit permission to test. Unauthorized access " +
                                "to computer systems is illegal.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Credits
            Text(
                text = "Part of the LaundR Security Suite",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                text = "Companion app for Flipper Zero",
                style = MaterialTheme.typography.bodySmall,
                color = CyberBlue
            )
        }
    }
}
