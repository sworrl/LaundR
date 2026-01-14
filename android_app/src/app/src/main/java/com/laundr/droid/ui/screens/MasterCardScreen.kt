package com.laundr.droid.ui.screens

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.laundr.droid.LocalNfcManager
import com.laundr.droid.MainActivity
import com.laundr.droid.nfc.MagicCardDetectionResult
import com.laundr.droid.nfc.MasterCard
import com.laundr.droid.nfc.MasterCardData
import com.laundr.droid.ui.theme.CyberBlue
import com.laundr.droid.ui.theme.CyberGreen
import com.laundr.droid.ui.theme.CyberOrange
import com.laundr.droid.ui.theme.CyberRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Stats for MasterCard usage
 */
data class MasterCardStats(
    val totalCardsGenerated: Int = 0,
    val totalExports: Int = 0,
    val totalWrites: Int = 0,
    val lastGenerated: Long = 0,
    val generationHistory: List<GenerationRecord> = emptyList()
)

data class GenerationRecord(
    val uid: String,
    val balance: Int,
    val timestamp: Long
)

// DetectedMagicCard removed - now using MagicCardDetectionResult from NfcManager

/**
 * Check if device MIGHT support MIFARE Classic emulation
 * This is a very limited list - most devices don't support this
 */
object MifareEmulationSupport {
    // Devices that historically had some MIFARE emulation capability
    private val MAYBE_SUPPORTED_DEVICES = listOf(
        "Nexus S",
        "Galaxy Nexus",
        "Xperia S",
        "Xperia P",
        "N9",  // Nokia N9
    )

    // Chinese OEMs with transit card features (locked but might work)
    private val CHINESE_OEM_MODELS = listOf(
        "MI ", "Redmi", "POCO",  // Xiaomi
        "VOG", "ELE", "JNY",     // Huawei
        "OPPO", "OnePlus",
        "vivo"
    )

    fun isEmulationMaybeSupported(): Boolean {
        val model = Build.MODEL ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""

        // Check known devices
        if (MAYBE_SUPPORTED_DEVICES.any { model.contains(it, ignoreCase = true) }) {
            return true
        }

        // Check Chinese OEMs (have transit features, might expose more)
        if (CHINESE_OEM_MODELS.any {
            model.contains(it, ignoreCase = true) ||
            manufacturer.contains(it, ignoreCase = true)
        }) {
            return true
        }

        return false
    }

    fun getDeviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterCardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // Get NfcManager for magic card detection
    val nfcManager = LocalNfcManager.current

    // Balance in cents - auto-generates card when changed
    var balanceCents by remember { mutableIntStateOf(5000) }
    var customAmountText by remember { mutableStateOf("") }
    // Start at 1 to trigger initial generation with random values
    var regenerateCounter by remember { mutableIntStateOf(1) }

    // Stats tracking
    var stats by remember { mutableStateOf(loadStats(context)) }

    // Auto-generate card whenever balance OR counter changes
    val generatedCard by remember(balanceCents, regenerateCounter) {
        derivedStateOf {
            val card = MasterCard.generateMasterCard(balanceCents = balanceCents, randomizeUid = true)
            // Record generation
            val newStats = stats.copy(
                totalCardsGenerated = stats.totalCardsGenerated + 1,
                lastGenerated = System.currentTimeMillis(),
                generationHistory = (listOf(GenerationRecord(card.uid, balanceCents, System.currentTimeMillis())) +
                        stats.generationHistory).take(50) // Keep last 50
            )
            stats = newStats
            saveStats(context, newStats)
            card
        }
    }

    // Write to magic card state
    var isWriteMode by remember { mutableStateOf(false) }
    var writeStatus by remember { mutableStateOf<String?>(null) }

    // Magic card detection - observe NfcManager state
    val detectedMagicCard by nfcManager.lastDetectedMagicCard.collectAsState()
    var isWaitingForCard by remember { mutableStateOf(false) }

    // Track the tag for writing
    var pendingWriteTag by remember { mutableStateOf<Tag?>(null) }

    // Check device emulation support
    val emulationSupported = remember { MifareEmulationSupport.isEmulationMaybeSupported() }
    val deviceInfo = remember { MifareEmulationSupport.getDeviceInfo() }

    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }

    // Update waiting state when detection completes
    LaunchedEffect(detectedMagicCard) {
        if (detectedMagicCard != null) {
            isWaitingForCard = false
            pendingWriteTag = detectedMagicCard?.tag
        }
    }

    // Cleanup detection mode on dispose
    DisposableEffect(Unit) {
        onDispose {
            nfcManager.disableMagicCardDetection()
            nfcManager.clearMagicCardResult()
        }
    }

    // ALWAYS claim NFC ownership while on this screen (prevents wallet popup)
    DisposableEffect(lifecycleOwner) {
        val activity = context as? MainActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && activity != null) {
                val intent = Intent(context, activity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                // Claim ALL NFC tags while this screen is active
                nfcAdapter?.enableForegroundDispatch(activity, pendingIntent, null, null)
            } else if (event == Lifecycle.Event.ON_PAUSE && activity != null) {
                nfcAdapter?.disableForegroundDispatch(activity)
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Ensure we release NFC on dispose
            (context as? MainActivity)?.let { activity ->
                try {
                    nfcAdapter?.disableForegroundDispatch(activity)
                } catch (_: Exception) {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CSC Master Card")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Nfc,
                                null,
                                modifier = Modifier.size(12.dp),
                                tint = CyberGreen
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "NFC Owned",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberGreen
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
                    // Randomize button
                    IconButton(onClick = {
                        regenerateCounter++
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Randomize", tint = CyberGreen)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Warning banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CyberRed.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = CyberRed)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "For authorized security research only",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberRed
                            )
                            Text(
                                text = "CVE-2025-46018",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberRed.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Device emulation notice
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (emulationSupported) CyberOrange.copy(alpha = 0.1f)
                        else CyberBlue.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            null,
                            tint = if (emulationSupported) CyberOrange else CyberBlue
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (emulationSupported)
                                    "Device may support MIFARE emulation"
                                else
                                    "MIFARE emulation not supported",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (emulationSupported) CyberOrange else CyberBlue
                            )
                            Text(
                                text = deviceInfo,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            if (!emulationSupported) {
                                Text(
                                    text = "Use a magic card (Gen1a/Gen2/Gen4) to write data",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            // Card visualization - always shows current generated card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1A1A2E)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF16213E),
                                            Color(0xFF0F3460)
                                        )
                                    )
                                )
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CSC ServiceWorks",
                                    color = CyberGreen,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    Icons.Default.Nfc,
                                    null,
                                    tint = CyberGreen,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            // UID - randomized (clickable to regenerate)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { regenerateCounter++ }
                            ) {
                                Text(
                                    text = generatedCard.uid.chunked(2).joinToString(" "),
                                    color = Color.White,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(Modifier.width(8.dp))
                                Badge(containerColor = CyberOrange) {
                                    Text("RAND", style = MaterialTheme.typography.labelSmall, color = Color.Black)
                                }
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Tap to randomize",
                                    modifier = Modifier.size(16.dp),
                                    tint = CyberOrange.copy(alpha = 0.7f)
                                )
                            }

                            Spacer(Modifier.weight(1f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("BALANCE", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = generatedCard.balanceFormatted,
                                            color = CyberGreen,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Badge(containerColor = CyberBlue) {
                                            Text("SET", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                        }
                                    }
                                }
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    modifier = Modifier.clickable { regenerateCounter++ }
                                ) {
                                    Text("SITE CODE", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = generatedCard.siteCode,
                                            color = CyberOrange,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Badge(containerColor = CyberOrange) {
                                            Text("RAND", style = MaterialTheme.typography.labelSmall, color = Color.Black)
                                        }
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Tap to randomize",
                                            modifier = Modifier.size(12.dp),
                                            tint = CyberOrange.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Balance selector - quick amounts
            item {
                Text(
                    text = "Set Balance",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1000 to "$10", 2500 to "$25", 5000 to "$50", 10000 to "$100").forEach { (cents, label) ->
                        FilterChip(
                            selected = balanceCents == cents,
                            onClick = {
                                balanceCents = cents
                                customAmountText = ""
                            },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberGreen.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = balanceCents == 65535,
                        onClick = {
                            balanceCents = 65535
                            customAmountText = ""
                        },
                        label = { Text("MAX") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyberRed.copy(alpha = 0.3f)
                        )
                    )

                    Spacer(Modifier.width(8.dp))

                    // Custom amount input
                    OutlinedTextField(
                        value = customAmountText,
                        onValueChange = { input ->
                            customAmountText = input
                            input.toDoubleOrNull()?.let { dollars ->
                                val cents = (dollars * 100).toInt().coerceIn(0, 65535)
                                balanceCents = cents
                            }
                        },
                        label = { Text("Custom $") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
            }

            // Magic Card Detection and Write Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            detectedMagicCard != null -> CyberGreen.copy(alpha = 0.1f)
                            isWaitingForCard -> CyberOrange.copy(alpha = 0.1f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when {
                            // Magic card detected - show info and write button
                            detectedMagicCard != null -> {
                                val card = detectedMagicCard!!
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CreditCard,
                                        null,
                                        tint = if (card.canWrite) CyberGreen else CyberOrange
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            if (card.magicInfo.isMagic) "Magic Card Detected" else "Card Detected",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (card.canWrite) CyberGreen else CyberOrange
                                        )
                                        Text(
                                            "UID: ${card.uid.chunked(2).joinToString(" ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            card.typeDescription,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(16.dp))

                                if (isWriteMode) {
                                    val infiniteTransition = rememberInfiniteTransition(label = "write_pulse")
                                    val pulseScale by infiniteTransition.animateFloat(
                                        initialValue = 1f,
                                        targetValue = 1.1f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(600),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "pulse"
                                    )

                                    Icon(
                                        Icons.Default.Nfc,
                                        null,
                                        modifier = Modifier.size(48.dp).scale(pulseScale),
                                        tint = CyberOrange
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "WRITING...",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = CyberOrange
                                    )
                                    writeStatus?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (it.contains("success", ignoreCase = true)) CyberGreen else Color.White
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            isWriteMode = false
                                            writeStatus = null
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberRed)
                                    ) {
                                        Text("Done")
                                    }
                                } else {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                isWriteMode = true
                                                writeStatus = "Writing master card data..."

                                                // Perform write in background
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    pendingWriteTag?.let { tag ->
                                                        val (success, message) = nfcManager.writeMasterCard(tag, generatedCard)
                                                        writeStatus = message
                                                        if (success) {
                                                            stats = stats.copy(totalWrites = stats.totalWrites + 1)
                                                            saveStats(context, stats)
                                                        }
                                                    } ?: run {
                                                        writeStatus = "Error: Card no longer available. Please re-detect."
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (card.canWrite) CyberGreen else CyberOrange
                                            ),
                                            modifier = Modifier.weight(1f),
                                            enabled = card.canWrite
                                        ) {
                                            Icon(Icons.Default.Edit, null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("WRITE", color = Color.Black, fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                nfcManager.clearMagicCardResult()
                                                pendingWriteTag = null
                                                isWaitingForCard = false
                                            }
                                        ) {
                                            Icon(Icons.Default.Close, null)
                                        }
                                    }

                                    if (!card.canWrite) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            "This card cannot be written. Use a magic card (Gen1a/Gen2/Gen4).",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyberRed.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }

                            // Waiting for card - show scan animation
                            isWaitingForCard -> {
                                val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
                                val pulseScale by infiniteTransition.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 1.15f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(800),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "pulse"
                                )

                                Icon(
                                    Icons.Default.Nfc,
                                    null,
                                    modifier = Modifier.size(64.dp).scale(pulseScale),
                                    tint = CyberOrange
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "TAP MAGIC CARD TO DETECT",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CyberOrange
                                )
                                Text(
                                    "Gen1a, Gen2, or Gen4 required",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Spacer(Modifier.height(16.dp))
                                OutlinedButton(
                                    onClick = {
                                        nfcManager.disableMagicCardDetection()
                                        isWaitingForCard = false
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberRed)
                                ) {
                                    Text("Cancel")
                                }
                            }

                            // Default - show detect button
                            else -> {
                                Text(
                                    "Step 1: Detect Magic Card",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        nfcManager.enableMagicCardDetection()
                                        isWaitingForCard = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberOrange),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Nfc, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("DETECT MAGIC CARD", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "A UID-changeable magic card is required to write master card data",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Export buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val flipperData = MasterCard.exportToFlipperFormat(generatedCard)
                            val outputDir = File(context.getExternalFilesDir(null), "exports")
                            outputDir.mkdirs()
                            val filename = "CSC_Master_${generatedCard.uid}.nfc"
                            File(outputDir, filename).writeText(flipperData)
                            stats = stats.copy(totalExports = stats.totalExports + 1)
                            saveStats(context, stats)
                            Toast.makeText(context, "Saved: $filename", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
                    ) {
                        Icon(Icons.Default.Save, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Export .nfc", color = Color.Black)
                    }

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val flipperData = MasterCard.exportToFlipperFormat(generatedCard)
                            clipboard.setPrimaryClip(ClipData.newPlainText("MasterCard", flipperData))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                }
            }

            // Randomized data details section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Data Fields", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Tap RAND fields to regenerate",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberOrange.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        DataFieldRow("UID (Block 0)", generatedCard.uid, "RANDOMIZED", CyberOrange, onClick = { regenerateCounter++ })
                        DataFieldRow("Key A", generatedCard.keyA, "FIXED (CSC Default)", CyberBlue)
                        DataFieldRow("Key B", generatedCard.keyB, "FIXED (CSC Default)", CyberBlue)
                        DataFieldRow("Balance (Block 4)", generatedCard.balanceFormatted, "USER SET", CyberGreen)
                        DataFieldRow("Backup Balance (Block 8)", generatedCard.balanceFormatted, "MIRRORED", CyberGreen)
                        DataFieldRow("Site Code (Block 2)", generatedCard.siteCode, "RANDOMIZED", CyberOrange, onClick = { regenerateCounter++ })
                        DataFieldRow("Counter (Block 9)", "00000001", "FIXED", CyberBlue)
                        DataFieldRow("Provider (Block 1)", "CSC", "FIXED", CyberBlue)
                    }
                }
            }

            // Usage Stats
            item {
                var statsExpanded by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    onClick = { statsExpanded = !statsExpanded }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Analytics, null, tint = CyberBlue)
                                Spacer(Modifier.width(8.dp))
                                Text("Usage Stats", style = MaterialTheme.typography.titleMedium)
                            }
                            Icon(
                                if (statsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }

                        // Summary row always visible
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            StatBadge("Generated", stats.totalCardsGenerated.toString(), CyberGreen)
                            StatBadge("Exported", stats.totalExports.toString(), CyberOrange)
                            StatBadge("Written", stats.totalWrites.toString(), CyberBlue)
                        }

                        if (statsExpanded && stats.generationHistory.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Divider()
                            Spacer(Modifier.height(8.dp))
                            Text("Recent Generations", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))

                            stats.generationHistory.take(5).forEach { record ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        record.uid.takeLast(8),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        "\$${record.balance / 100}.${"%02d".format(record.balance % 100)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CyberGreen
                                    )
                                    Text(
                                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(record.timestamp)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Usage note
            item {
                Text(
                    text = "UID auto-randomizes on balance change. Tap refresh to re-randomize with same balance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataFieldRow(
    label: String,
    value: String,
    status: String,
    statusColor: Color,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = value,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(containerColor = statusColor.copy(alpha = 0.3f)) {
                Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor)
            }
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Tap to randomize",
                    modifier = Modifier.size(14.dp),
                    tint = statusColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun StatBadge(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

// Stats persistence
private fun loadStats(context: Context): MasterCardStats {
    return try {
        val file = File(context.filesDir, "mastercard_stats.json")
        if (file.exists()) {
            val json = JSONObject(file.readText())
            MasterCardStats(
                totalCardsGenerated = json.optInt("totalCardsGenerated", 0),
                totalExports = json.optInt("totalExports", 0),
                totalWrites = json.optInt("totalWrites", 0),
                lastGenerated = json.optLong("lastGenerated", 0),
                generationHistory = emptyList() // Don't persist full history
            )
        } else {
            MasterCardStats()
        }
    } catch (_: Exception) {
        MasterCardStats()
    }
}

private fun saveStats(context: Context, stats: MasterCardStats) {
    try {
        val json = JSONObject().apply {
            put("totalCardsGenerated", stats.totalCardsGenerated)
            put("totalExports", stats.totalExports)
            put("totalWrites", stats.totalWrites)
            put("lastGenerated", stats.lastGenerated)
        }
        File(context.filesDir, "mastercard_stats.json").writeText(json.toString())
    } catch (_: Exception) {}
}
