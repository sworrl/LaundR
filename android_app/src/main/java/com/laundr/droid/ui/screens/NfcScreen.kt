package com.laundr.droid.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.nfc.NfcAdapter
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laundr.droid.nfc.*
import com.laundr.droid.ui.theme.CyberBlue
import kotlinx.coroutines.launch
import com.laundr.droid.ui.theme.CyberGreen
import com.laundr.droid.ui.theme.CyberOrange
import com.laundr.droid.ui.theme.CyberRed
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcScreen(
    nfcManager: NfcManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val cardRepository = remember { CardRepository(context) }
    val nfcState by nfcManager.nfcState.collectAsState()
    val currentCard by nfcManager.currentCard.collectAsState()
    val log by nfcManager.log.collectAsState()
    val scanProgress by nfcManager.scanProgress.collectAsState()
    val bruteForceProgress by nfcManager.bruteForceProgress.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var cardSaved by remember { mutableStateOf(false) }

    // Keep screen awake while scanning or brute forcing
    val isActive = nfcState == NfcState.READING ||
                   nfcState == NfcState.WAITING_FOR_TAG ||
                   scanProgress.isScanning ||
                   bruteForceProgress.isRunning

    DisposableEffect(isActive) {
        val activity = context as? ComponentActivity
        if (isActive) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NFC Reader")
                        Text(
                            text = when (nfcState) {
                                NfcState.IDLE -> "Ready"
                                NfcState.WAITING_FOR_TAG -> "Waiting for card..."
                                NfcState.READING -> "Reading..."
                                NfcState.READ_COMPLETE -> "Card read!"
                                NfcState.ERROR -> "Error"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (nfcState) {
                                NfcState.WAITING_FOR_TAG -> CyberOrange
                                NfcState.READING -> CyberBlue
                                NfcState.READ_COMPLETE -> CyberGreen
                                NfcState.ERROR -> CyberRed
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                    if (currentCard != null) {
                        // Save card button
                        IconButton(
                            onClick = { showSaveDialog = true },
                            enabled = !cardSaved
                        ) {
                            Icon(
                                if (cardSaved) Icons.Default.CheckCircle else Icons.Default.Save,
                                contentDescription = "Save Card",
                                tint = if (cardSaved) CyberGreen else CyberOrange
                            )
                        }
                        IconButton(onClick = { showExportDialog = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Export", tint = CyberGreen)
                        }
                    }
                    IconButton(onClick = {
                        val logText = nfcManager.getLogText()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("NFC Log", logText))
                        Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Log")
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
            // NFC Status Card
            NfcStatusCard(nfcAdapter, nfcState, currentCard, scanProgress)

            // Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Card Data") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Sectors") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Brute Force")
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Log (${log.size})") }
                )
            }

            // Tab content
            when (selectedTab) {
                0 -> CardDataTab(currentCard, nfcManager)
                1 -> SectorsTab(currentCard)
                2 -> BruteForceTab(currentCard, nfcManager)
                3 -> LogTab(log, nfcManager)
            }
        }
    }

    // Export Dialog
    if (showExportDialog && currentCard != null) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export Card") },
            text = { Text("Export card data to Flipper .nfc format?") },
            confirmButton = {
                Button(
                    onClick = {
                        val outputDir = File(context.getExternalFilesDir(null), "exports")
                        outputDir.mkdirs()
                        val file = nfcManager.exportToFlipperFormat(currentCard!!, outputDir)
                        if (file != null) {
                            Toast.makeText(context, "Exported: ${file.name}", Toast.LENGTH_LONG).show()
                        }
                        showExportDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
                ) {
                    Text("Export", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Save Card Dialog
    if (showSaveDialog && currentCard != null) {
        val analysis = nfcManager.analyzeCSCCard(currentCard!!)
        val scope = rememberCoroutineScope()

        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            icon = {
                Icon(Icons.Default.Save, null, tint = CyberGreen, modifier = Modifier.size(32.dp))
            },
            title = { Text("Save Card") },
            text = {
                Column {
                    Text("Save card to collection + export Flipper .nfc file")
                    Spacer(Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            InfoRow("UID", currentCard!!.uid)
                            InfoRow("Type", currentCard!!.type)
                            InfoRow("Cracked Sectors", "${currentCard!!.sectors.count { it.authenticated }}/${currentCard!!.sectors.size}")
                            if (analysis?.estimatedBalance != null) {
                                InfoRow("Balance", analysis.balanceInDollars)
                            }
                            analysis?.provider?.let {
                                InfoRow("Provider", it)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Save to collection
                        val savedCard = cardRepository.saveCard(currentCard!!, analysis)
                        cardSaved = true

                        // Auto-export to both Flipper and LaunDR formats
                        scope.launch {
                            try {
                                val nfcFile = cardRepository.exportToFlipperFormat(savedCard)
                                val laundrFile = cardRepository.exportToLaundrFormat(savedCard)
                                Toast.makeText(
                                    context,
                                    "Saved + exported: ${nfcFile.name} & ${laundrFile.name}",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Saved! Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }

                        showSaveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
                ) {
                    Icon(Icons.Default.Save, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Save + Export .nfc", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun NfcStatusCard(
    nfcAdapter: NfcAdapter?,
    nfcState: NfcState,
    currentCard: MifareCardData?,
    scanProgress: ScanProgress
) {
    val infiniteTransition = rememberInfiniteTransition(label = "cyber_anim")
    val isAnimating = nfcState == NfcState.WAITING_FOR_TAG || nfcState == NfcState.READING || scanProgress.isScanning

    // Track key found for celebration
    var showKeyFoundCelebration by remember { mutableStateOf(false) }
    var lastFoundKey by remember { mutableStateOf("") }

    // Track card removed animation
    var showCardRemoved by remember { mutableStateOf(false) }

    // Detect key found
    LaunchedEffect(scanProgress.status) {
        if (scanProgress.status.contains("FOUND") && !showKeyFoundCelebration) {
            lastFoundKey = scanProgress.currentKeyHex
            showKeyFoundCelebration = true
            kotlinx.coroutines.delay(3000)
            showKeyFoundCelebration = false
        }
    }

    // Detect card removed (NFC error state while scanning)
    LaunchedEffect(nfcState) {
        if (nfcState == NfcState.ERROR && !showCardRemoved) {
            showCardRemoved = true
            kotlinx.coroutines.delay(4000)
            showCardRemoved = false
        }
    }

    // Multiple rotation speeds for rings
    val rotation1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "rot1"
    )
    val rotation2 by infiniteTransition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "rot2"
    )
    val rotation3 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "rot3"
    )
    val rotation4 by infiniteTransition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "rot4"
    )

    // Pulse effects
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(1000, easing = EaseInOutSine), RepeatMode.Reverse), label = "pulse"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse), label = "glow"
    )

    // Data stream effect
    val dataScroll by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "data"
    )

    // Calculate ETAs
    val sectorEtaSeconds = if (scanProgress.keysPerSecond > 0 && scanProgress.isScanning) {
        val keysRemaining = scanProgress.totalKeys - scanProgress.currentKey
        (keysRemaining / scanProgress.keysPerSecond).toInt()
    } else 0

    val totalEtaSeconds = if (scanProgress.keysPerSecond > 0 && scanProgress.isScanning) {
        val sectorsRemaining = scanProgress.totalSectors - scanProgress.currentSector - 1
        val currentSectorKeysRemaining = scanProgress.totalKeys - scanProgress.currentKey
        val futureSectorKeys = sectorsRemaining * scanProgress.totalKeys
        ((currentSectorKeysRemaining + futureSectorKeys) / scanProgress.keysPerSecond).toInt()
    } else 0

    fun formatEta(seconds: Int): String = when {
        seconds <= 0 -> "--:--"
        seconds < 60 -> "0:${"%02d".format(seconds)}"
        seconds < 3600 -> "${seconds / 60}:${"%02d".format(seconds % 60)}"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    val primaryColor = when {
        showCardRemoved -> CyberRed
        showKeyFoundCelebration -> CyberGreen
        nfcState == NfcState.READING || scanProgress.isScanning -> CyberBlue
        nfcState == NfcState.WAITING_FOR_TAG -> CyberOrange
        currentCard?.isCSC == true -> CyberGreen
        currentCard != null -> CyberBlue
        else -> Color(0xFF00FFFF)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A12))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            contentAlignment = Alignment.Center
        ) {
            // Matrix-style background
            Canvas(modifier = Modifier.fillMaxSize()) {
                val chars = "01アイウエオカキクケコサシスセソタチツテト"
                for (i in 0..30) {
                    val x = (size.width * ((i * 37 + dataScroll * 100) % 100) / 100f)
                    val y = (size.height * ((i * 23 + dataScroll * 200) % 100) / 100f)
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.1f + (i % 5) * 0.02f),
                        radius = 2f,
                        center = Offset(x, y)
                    )
                }
                // Scan lines
                for (i in 0..20) {
                    val y = (size.height * ((i * 5 + dataScroll * 100) % 100) / 100f)
                    drawLine(
                        color = primaryColor.copy(alpha = 0.03f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                }
            }

            // Cyberpunk animated rings
            if (isAnimating || showKeyFoundCelebration) {
                // Outer glow
                Box(
                    modifier = Modifier
                        .size((200 * pulse).dp)
                        .alpha(glowAlpha * 0.3f)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(primaryColor, Color.Transparent)
                            ),
                            shape = CircleShape
                        )
                )

                // Ring 1 - Outer dashed
                Canvas(modifier = Modifier.size(180.dp).rotate(rotation1)) {
                    val radius = size.minDimension / 2
                    for (i in 0..11) {
                        val angle = Math.toRadians((i * 30).toDouble())
                        val startX = center.x + (radius * 0.85f) * cos(angle).toFloat()
                        val startY = center.y + (radius * 0.85f) * sin(angle).toFloat()
                        val endX = center.x + radius * cos(angle).toFloat()
                        val endY = center.y + radius * sin(angle).toFloat()
                        drawLine(
                            color = primaryColor.copy(alpha = 0.6f),
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = 3f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Ring 2 - Hexagon
                Canvas(modifier = Modifier.size(150.dp).rotate(rotation2)) {
                    val radius = size.minDimension / 2 * 0.9f
                    val path = Path()
                    for (i in 0..5) {
                        val angle = Math.toRadians((i * 60 - 30).toDouble())
                        val x = center.x + radius * cos(angle).toFloat()
                        val y = center.y + radius * sin(angle).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    drawPath(path, primaryColor.copy(alpha = 0.5f), style = Stroke(width = 2f))
                }

                // Ring 3 - Inner spinning arc
                Canvas(modifier = Modifier.size(120.dp).rotate(rotation3)) {
                    drawArc(
                        color = primaryColor,
                        startAngle = 0f,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(width = 4f, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = primaryColor.copy(alpha = 0.5f),
                        startAngle = 180f,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(width = 4f, cap = StrokeCap.Round)
                    )
                }

                // Ring 4 - Core dots
                Canvas(modifier = Modifier.size(90.dp).rotate(rotation4)) {
                    for (i in 0..7) {
                        val angle = Math.toRadians((i * 45).toDouble())
                        val r = size.minDimension / 2 * 0.8f
                        drawCircle(
                            color = if (i % 2 == 0) primaryColor else primaryColor.copy(alpha = 0.4f),
                            radius = if (i % 2 == 0) 6f else 4f,
                            center = Offset(
                                center.x + r * cos(angle).toFloat(),
                                center.y + r * sin(angle).toFloat()
                            )
                        )
                    }
                }
            }

            // Central icon area
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color(0xFF0A0A12).copy(alpha = 0.9f), CircleShape)
                    .border(2.dp, primaryColor.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Card removed shake animation
                val shakeOffset by infiniteTransition.animateFloat(
                    initialValue = -8f, targetValue = 8f,
                    animationSpec = infiniteRepeatable(
                        tween(100, easing = LinearEasing),
                        RepeatMode.Reverse
                    ),
                    label = "shake"
                )

                Icon(
                    imageVector = when {
                        showCardRemoved -> Icons.Default.CreditCardOff
                        showKeyFoundCelebration -> Icons.Default.Key
                        nfcAdapter == null -> Icons.Default.Block
                        nfcState == NfcState.READING || scanProgress.isScanning -> Icons.Default.Memory
                        nfcState == NfcState.WAITING_FOR_TAG -> Icons.Default.Nfc
                        currentCard != null -> Icons.Default.CreditCard
                        else -> Icons.Default.Nfc
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .then(
                            when {
                                showCardRemoved -> Modifier.graphicsLayer { translationX = shakeOffset }
                                showKeyFoundCelebration -> Modifier.scale(pulse)
                                else -> Modifier
                            }
                        ),
                    tint = primaryColor
                )
            }

            // Key Found Celebration Overlay
            if (showKeyFoundCelebration) {
                // Particle explosion effect
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val time = System.currentTimeMillis() % 3000 / 3000f
                    for (i in 0..30) {
                        val angle = Math.toRadians((i * 12 + time * 360).toDouble())
                        val distance = 50f + time * 150f
                        val x = center.x + distance * cos(angle).toFloat()
                        val y = center.y + distance * sin(angle).toFloat()
                        val alpha = (1f - time) * 0.8f
                        drawCircle(
                            color = CyberGreen.copy(alpha = alpha),
                            radius = 4f + (i % 3) * 2f,
                            center = Offset(x, y)
                        )
                    }
                }
            }

            // Card Removed Warning Overlay
            if (showCardRemoved) {
                // Glitchy/broken effect - scattered red fragments
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val time = System.currentTimeMillis() % 2000 / 2000f
                    // Falling fragments
                    for (i in 0..20) {
                        val startAngle = (i * 18 + 90).toDouble()  // Start from top
                        val angle = Math.toRadians(startAngle)
                        val fallDistance = 30f + time * 120f + (i % 5) * 20f
                        val drift = sin(i * 0.5) * 30f * time
                        val x = center.x + fallDistance * cos(angle).toFloat() + drift.toFloat()
                        val y = center.y + fallDistance * sin(angle).toFloat() + (time * 100f)
                        val alpha = (1f - time) * 0.7f
                        // Draw broken card fragment shapes
                        drawRect(
                            color = CyberRed.copy(alpha = alpha),
                            topLeft = Offset(x - 4f, y - 2f),
                            size = androidx.compose.ui.geometry.Size(8f + (i % 3) * 4f, 4f)
                        )
                    }
                    // Static/glitch lines
                    for (i in 0..5) {
                        val y = center.y - 60f + (i * 24f) + (time * 20f) % 24f
                        val glitchAlpha = if (Random.nextFloat() > 0.5f) 0.3f else 0f
                        drawLine(
                            color = CyberRed.copy(alpha = glitchAlpha),
                            start = Offset(center.x - 50f, y),
                            end = Offset(center.x + 50f, y),
                            strokeWidth = 2f
                        )
                    }
                }
            }

            // Status overlay at bottom
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    showCardRemoved -> {
                        Text(
                            "⚠ CARD REMOVED ⚠",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberRed,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "Replace card to continue",
                            fontSize = 12.sp,
                            color = CyberRed.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    showKeyFoundCelebration -> {
                        Text(
                            "★ KEY FOUND ★",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberGreen,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            lastFoundKey,
                            fontSize = 14.sp,
                            color = CyberGreen.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    nfcAdapter == null -> {
                        Text("NFC NOT AVAILABLE", color = CyberRed, fontWeight = FontWeight.Bold)
                    }
                    nfcState == NfcState.READING || scanProgress.isScanning -> {
                        // Sector progress
                        Text(
                            "SECTOR ${scanProgress.currentSector + 1}/${scanProgress.totalSectors}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CyberBlue
                        )
                        LinearProgressIndicator(
                            progress = scanProgress.sectorProgress,
                            modifier = Modifier
                                .width(200.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = CyberGreen,
                            trackColor = Color(0xFF1A1A2E)
                        )
                        Spacer(Modifier.height(4.dp))

                        // Current key
                        if (scanProgress.currentKeyHex.isNotEmpty()) {
                            Text(
                                scanProgress.currentKeyHex,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = CyberOrange
                            )
                        }

                        // Speed & ETA row
                        Row(
                            modifier = Modifier.width(200.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "%.0f k/s".format(scanProgress.keysPerSecond),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = CyberGreen
                            )
                            Text(
                                "SEC: ${formatEta(sectorEtaSeconds)}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = CyberBlue
                            )
                            Text(
                                "CARD: ${formatEta(totalEtaSeconds)}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = CyberOrange
                            )
                        }
                    }
                    nfcState == NfcState.WAITING_FOR_TAG -> {
                        Text(
                            "AWAITING TARGET",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberOrange,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "Hold card to device",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                    currentCard != null -> {
                        if (currentCard.isCSC) {
                            Text(
                                "★ CSC LAUNDRY CARD ★",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberGreen,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            currentCard.uid,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "${currentCard.sectors.count { it.authenticated }}/${currentCard.sectors.size} SECTORS UNLOCKED",
                            fontSize = 11.sp,
                            color = CyberGreen,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    else -> {
                        Text(
                            "SYSTEM READY",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FFFF),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "NFC module online",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Top corners - decorative
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Canvas(modifier = Modifier.size(30.dp)) {
                    drawLine(primaryColor.copy(0.5f), Offset(0f, 20f), Offset(0f, 0f), 2f)
                    drawLine(primaryColor.copy(0.5f), Offset(0f, 0f), Offset(20f, 0f), 2f)
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Canvas(modifier = Modifier.size(30.dp)) {
                    drawLine(primaryColor.copy(0.5f), Offset(size.width, 20f), Offset(size.width, 0f), 2f)
                    drawLine(primaryColor.copy(0.5f), Offset(size.width, 0f), Offset(size.width - 20f, 0f), 2f)
                }
            }

            // Sector Grid Display - 16 sectors arranged around the top
            if (currentCard != null || scanProgress.isScanning) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val totalSectors = currentCard?.sectors?.size ?: scanProgress.totalSectors
                    for (i in 0 until minOf(totalSectors, 16)) {
                        val sector = currentCard?.sectors?.getOrNull(i)
                        val isCurrentSector = scanProgress.isScanning && scanProgress.currentSector == i
                        val isUnlocked = sector?.authenticated == true
                        val isFailed = sector != null && !sector.authenticated && !isCurrentSector &&
                                       (currentCard?.sectors?.take(i)?.all { it.authenticated != null } == true)

                        SectorIndicator(
                            sectorIndex = i,
                            isUnlocked = isUnlocked,
                            isCracking = isCurrentSector,
                            isFailed = isFailed && !isUnlocked,
                            pulse = pulse
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectorIndicator(
    sectorIndex: Int,
    isUnlocked: Boolean,
    isCracking: Boolean,
    isFailed: Boolean,
    pulse: Float
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sector_$sectorIndex")

    // Cracking animation
    val crackingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(300, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "crack_alpha"
    )

    // Unlock celebration animation
    var justUnlocked by remember { mutableStateOf(false) }
    val unlockScale by animateFloatAsState(
        targetValue = if (justUnlocked) 1.5f else 1f,
        animationSpec = spring(dampingRatio = 0.3f, stiffness = 300f),
        label = "unlock_scale"
    )

    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            justUnlocked = true
            kotlinx.coroutines.delay(500)
            justUnlocked = false
        }
    }

    val bgColor = when {
        isUnlocked -> CyberGreen
        isCracking -> CyberOrange.copy(alpha = crackingAlpha)
        isFailed -> CyberRed.copy(alpha = 0.5f)
        else -> Color(0xFF2A2A3E)
    }

    val borderColor = when {
        isUnlocked -> CyberGreen
        isCracking -> CyberOrange
        isFailed -> CyberRed
        else -> Color(0xFF3A3A4E)
    }

    Box(
        modifier = Modifier
            .size(18.dp)
            .scale(if (isCracking) pulse * 0.9f + 0.1f else if (justUnlocked) unlockScale else 1f)
            .background(bgColor.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
            .border(1.dp, borderColor.copy(alpha = 0.7f), RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        when {
            isUnlocked -> {
                Icon(
                    Icons.Default.Check,
                    null,
                    modifier = Modifier.size(10.dp),
                    tint = CyberGreen
                )
            }
            isCracking -> {
                // Spinning indicator
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.dp,
                    color = CyberOrange
                )
            }
            isFailed -> {
                Icon(
                    Icons.Default.Close,
                    null,
                    modifier = Modifier.size(10.dp),
                    tint = CyberRed
                )
            }
            else -> {
                Text(
                    "${sectorIndex}",
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun CardDataTab(currentCard: MifareCardData?, nfcManager: NfcManager) {
    if (currentCard == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No card scanned yet",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Card Info
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Card Information", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("UID", currentCard.uid)
                        InfoRow("Type", currentCard.type)
                        InfoRow("Sectors", currentCard.sectors.size.toString())
                        InfoRow("Authenticated", "${currentCard.sectors.count { it.authenticated }}/${currentCard.sectors.size}")
                    }
                }
            }

            // CSC Analysis - always run for any card
            item {
                val analysis = nfcManager.analyzeCSCCard(currentCard)
                if (analysis != null && (analysis.provider != null || analysis.estimatedBalance != null)) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = CyberGreen.copy(alpha = 0.1f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, null, tint = CyberGreen)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Laundry Card Analysis", style = MaterialTheme.typography.titleMedium, color = CyberGreen)
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            analysis.provider?.let { provider ->
                                InfoRow("Provider", provider)
                            }

                            if (analysis.estimatedBalance != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Balance",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = analysis.balanceInDollars,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontFamily = FontFamily.Monospace,
                                            color = CyberGreen
                                        )
                                        if (analysis.balanceValidated) {
                                            Spacer(Modifier.width(4.dp))
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = "Validated",
                                                tint = CyberGreen,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            analysis.transactionCounter?.let { counter ->
                                InfoRow("Transaction #", counter.toString())
                            }

                            analysis.siteCode?.let { site ->
                                InfoRow("Site Code", site)
                            }

                            analysis.balanceBackupFormatted?.let { backup ->
                                InfoRow("Backup Balance", backup)
                                if (!analysis.balanceMatches) {
                                    Text(
                                        "⚠ Balance mismatch!",
                                        color = CyberOrange,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            InfoRow("Replay Vulnerable", if (analysis.vulnerableToReplay) "YES" else "No")

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "CVE-2025-46018: Card can be cloned with Flipper Zero",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberOrange
                            )
                        }
                    }
                }
            }

            // Keys Found
            item {
                val keysFound = currentCard.sectors.filter { it.authenticated }
                if (keysFound.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Keys Found", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            keysFound.forEach { sector ->
                                sector.keyA?.let { key ->
                                    Text(
                                        text = "Sector ${sector.index} KeyA: $key",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = CyberBlue
                                    )
                                }
                                sector.keyB?.let { key ->
                                    Text(
                                        text = "Sector ${sector.index} KeyB: $key",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = CyberOrange
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun SectorsTab(currentCard: MifareCardData?) {
    if (currentCard == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No card scanned yet",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(currentCard.sectors) { sector ->
                SectorCard(sector)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectorCard(sector: SectorData) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (sector.authenticated)
                MaterialTheme.colorScheme.surfaceVariant
            else
                CyberRed.copy(alpha = 0.1f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (sector.authenticated) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (sector.authenticated) CyberGreen else CyberRed,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sector ${sector.index}",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                sector.blocks.forEach { block ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (block.isTrailer) CyberOrange.copy(alpha = 0.1f)
                                else Color.Transparent
                            )
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = String.format("%02d:", block.index),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.width(28.dp)
                        )
                        Text(
                            text = if (block.readable) {
                                block.data.chunked(2).joinToString(" ")
                            } else {
                                "?? ".repeat(16).trim()
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (block.readable)
                                if (block.isTrailer) CyberOrange else MaterialTheme.colorScheme.onSurface
                            else CyberRed
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BruteForceTab(currentCard: MifareCardData?, nfcManager: NfcManager) {
    val bruteForceProgress by nfcManager.bruteForceProgress.collectAsState()
    var selectedSector by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Warning banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberOrange.copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, null, tint = CyberOrange)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Brute force tries ALL ${nfcManager.bruteForceProgress.value.totalKeys / 2} dictionary keys at max speed",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (currentCard == null) {
            // No card - show instructions
            Icon(
                Icons.Default.Nfc,
                null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Scan a card first",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                text = "Hold card to back of phone, then return here to brute force locked sectors",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        } else {
            // Sector selector
            val lockedSectors = currentCard.sectors.filter { !it.authenticated }

            if (lockedSectors.isEmpty()) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    modifier = Modifier.size(80.dp),
                    tint = CyberGreen
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "All sectors unlocked!",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberGreen
                )
                Text(
                    text = "No brute force needed - all keys found",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    text = "Select sector to attack:",
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(Modifier.height(8.dp))

                // Sector chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    lockedSectors.take(8).forEach { sector ->
                        FilterChip(
                            selected = selectedSector == sector.index,
                            onClick = { selectedSector = sector.index },
                            label = { Text("${sector.index}") },
                            modifier = Modifier.padding(horizontal = 4.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberRed.copy(alpha = 0.3f)
                            )
                        )
                    }
                }

                if (lockedSectors.size > 8) {
                    Text(
                        text = "+${lockedSectors.size - 8} more locked sectors",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Progress display
                if (bruteForceProgress.isRunning) {
                    // Animated progress
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "CRACKING SECTOR ${bruteForceProgress.sector}",
                                style = MaterialTheme.typography.titleMedium,
                                color = CyberRed
                            )

                            Spacer(Modifier.height(16.dp))

                            LinearProgressIndicator(
                                progress = bruteForceProgress.progress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = CyberRed
                            )

                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${bruteForceProgress.currentKey} / ${bruteForceProgress.totalKeys}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${bruteForceProgress.percentComplete}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            Text(
                                text = "%.1f keys/sec".format(bruteForceProgress.keysPerSecond),
                                style = MaterialTheme.typography.titleLarge,
                                color = CyberGreen,
                                fontFamily = FontFamily.Monospace
                            )

                            // Found keys display
                            bruteForceProgress.keyAFound?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "KEY A: $it",
                                    color = CyberGreen,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            bruteForceProgress.keyBFound?.let {
                                Text(
                                    text = "KEY B: $it",
                                    color = CyberBlue,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { nfcManager.stopBruteForce() },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberOrange)
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text("STOP", color = Color.Black)
                    }
                } else {
                    // Start button
                    Button(
                        onClick = {
                            // Need to keep card present for brute force
                            Toast.makeText(
                                context,
                                "Keep card on phone! Starting brute force...",
                                Toast.LENGTH_SHORT
                            ).show()
                            // Note: We need the Tag object, but it's not stored
                            // For now, show message that card needs to be rescanned
                            nfcManager.log("Brute force requires card to stay on phone")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Icon(Icons.Default.Speed, null)
                        Spacer(Modifier.width(8.dp))
                        Text("START BRUTE FORCE", color = Color.White)
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Keep card held against phone during attack",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    // Show last results if any
                    if (bruteForceProgress.keyAFound != null || bruteForceProgress.keyBFound != null) {
                        Spacer(Modifier.height(16.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = CyberGreen.copy(alpha = 0.1f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Last Results - Sector ${bruteForceProgress.sector}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                bruteForceProgress.keyAFound?.let {
                                    Text(
                                        text = "Key A: $it",
                                        fontFamily = FontFamily.Monospace,
                                        color = CyberGreen
                                    )
                                }
                                bruteForceProgress.keyBFound?.let {
                                    Text(
                                        text = "Key B: $it",
                                        fontFamily = FontFamily.Monospace,
                                        color = CyberBlue
                                    )
                                }
                                Text(
                                    text = "Speed: %.1f keys/sec".format(bruteForceProgress.keysPerSecond),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogTab(log: List<String>, nfcManager: NfcManager) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Clear button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { nfcManager.clearLog() }) {
                Icon(Icons.Default.Delete, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear")
            }
        }

        if (log.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No log entries",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                reverseLayout = true
            ) {
                items(log.reversed()) { entry ->
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            entry.contains("ERROR") -> CyberRed
                            entry.contains("CSC") -> CyberGreen
                            entry.contains("===") -> CyberGreen
                            entry.contains("CRACKED") -> CyberGreen
                            entry.contains("KEY A") || entry.contains("KEY B") -> CyberBlue
                            entry.contains("Auth OK") -> CyberBlue
                            entry.contains("Auth FAILED") -> CyberOrange
                            entry.contains("keys/sec") -> CyberGreen
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        }
                    )
                }
            }
        }
    }
}
