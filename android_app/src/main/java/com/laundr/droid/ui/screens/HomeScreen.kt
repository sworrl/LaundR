package com.laundr.droid.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.laundr.droid.R
import com.laundr.droid.ui.theme.CyberGreen
import com.laundr.droid.ui.theme.CyberOrange
import kotlinx.coroutines.delay

/**
 * Version codename system - each digit position has funny words
 * Format: Major.Minor.Patch = "Word1 Word2 Word3"
 */
object VersionCodename {
    private val majorWords = listOf(
        "Soggy", "Crusty", "Wrinkly", "Tumbling", "Spinning",
        "Damp", "Fluffy", "Static", "Soapy", "Sudsing"
    )

    private val minorWords = listOf(
        "Sock", "Lint", "Quarter", "Dryer", "Fabric",
        "Rinse", "Spin", "Agitate", "Delicate", "Bleach"
    )

    private val patchWords = listOf(
        "Goblin", "Bandit", "Wizard", "Ninja", "Pirate",
        "Viking", "Gremlin", "Phantom", "Rascal", "Trickster"
    )

    fun getCodename(major: Int, minor: Int, patch: Int): String {
        val m = majorWords.getOrElse(major % majorWords.size) { "Unknown" }
        val n = minorWords.getOrElse(minor % minorWords.size) { "Unknown" }
        val p = patchWords.getOrElse(patch % patchWords.size) { "Unknown" }
        return "$m $n $p"
    }

    // Current version
    const val VERSION = "1.7.1"
    val CODENAME = getCodename(1, 7, 1) // "Crusty Agitate Bandit"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToNfc: () -> Unit,
    onNavigateToQRScan: () -> Unit,
    onNavigateToMasterCard: () -> Unit,
    onNavigateToSavedCards: () -> Unit,
    onNavigateToLaundryRooms: () -> Unit,
    onNavigateToLog: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    // Logo pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "logo")
    val logoGlow by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_glow"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LaunDRoid") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo/Title area
            item {
                Spacer(modifier = Modifier.height(24.dp))

                Image(
                    painter = painterResource(id = R.drawable.laundr_icon),
                    contentDescription = "LaunDRoid Logo",
                    modifier = Modifier
                        .size(100.dp)
                        .scale(logoGlow)
                        .clip(RoundedCornerShape(20.dp))
                )

                Text(
                    text = "LaunDRoid",
                    style = MaterialTheme.typography.titleLarge,
                    color = CyberGreen
                )

                Text(
                    text = "BLE Security Audit Tool",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "v${VersionCodename.VERSION}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = CyberGreen.copy(alpha = 0.7f)
                    )
                    Text(
                        text = " - ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Text(
                        text = VersionCodename.CODENAME,
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberOrange.copy(alpha = 0.8f)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Menu buttons with staggered animation
            item {
                AnimatedMenuButton(
                    icon = Icons.Default.BluetoothSearching,
                    title = "BLE Scanner",
                    subtitle = "Find nearby BLE devices",
                    onClick = onNavigateToScan,
                    delay = 0
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                AnimatedMenuButton(
                    icon = Icons.Default.Nfc,
                    title = "NFC Reader",
                    subtitle = "Read & dump MIFARE cards",
                    onClick = onNavigateToNfc,
                    delay = 50
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                AnimatedMenuButton(
                    icon = Icons.Default.QrCodeScanner,
                    title = "QR Scanner",
                    subtitle = "Scan laundry machine QR codes",
                    onClick = onNavigateToQRScan,
                    delay = 100
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                AnimatedMenuButton(
                    icon = Icons.Default.CreditCard,
                    title = "Master Card",
                    subtitle = "Generate CSC master cards",
                    onClick = onNavigateToMasterCard,
                    delay = 150
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                AnimatedMenuButton(
                    icon = Icons.Default.Folder,
                    title = "Saved Cards",
                    subtitle = "View & export cracked cards",
                    onClick = onNavigateToSavedCards,
                    delay = 200
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                AnimatedMenuButton(
                    icon = Icons.Default.LocalLaundryService,
                    title = "Laundry Rooms",
                    subtitle = "Manage machines & rooms",
                    onClick = onNavigateToLaundryRooms,
                    delay = 250
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                AnimatedMenuButton(
                    icon = Icons.Default.Terminal,
                    title = "View Logs",
                    subtitle = "Scan and connection logs",
                    onClick = onNavigateToLog,
                    delay = 300
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                AnimatedMenuButton(
                    icon = Icons.Default.Info,
                    title = "About",
                    subtitle = "CVE info and credits",
                    onClick = onNavigateToAbout,
                    delay = 350
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Footer
            item {
                Text(
                    text = "For authorized security testing only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedMenuButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    delay: Int = 0
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(delay.toLong())
        visible = true
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300),
        label = "menu_alpha"
    )

    val animatedOffset by animateFloatAsState(
        targetValue = if (visible) 0f else 30f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "menu_offset"
    )

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(animatedAlpha)
            .graphicsLayer { translationX = animatedOffset },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated icon
            val iconTransition = rememberInfiniteTransition(label = "icon_$title")
            val iconAlpha by iconTransition.animateFloat(
                initialValue = 0.7f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "icon_alpha"
            )

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CyberGreen.copy(alpha = iconAlpha),
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

// Keep MenuButton for backward compatibility
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    AnimatedMenuButton(icon = icon, title = title, subtitle = subtitle, onClick = onClick)
}
