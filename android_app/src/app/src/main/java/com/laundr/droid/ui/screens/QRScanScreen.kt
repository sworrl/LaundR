package com.laundr.droid.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.laundr.droid.ble.LaundryRoom
import com.laundr.droid.ble.LaundryRoomManager
import com.laundr.droid.ble.MachineInfo
import com.laundr.droid.ui.theme.CyberBlue
import com.laundr.droid.ui.theme.CyberGreen
import com.laundr.droid.ui.theme.CyberOrange
import com.laundr.droid.ui.theme.CyberRed
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

data class ScannedQRCode(
    val rawValue: String,
    val format: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun QRScanScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Use singleton LaundryRoomManager from Application
    val laundryRoomManager = (context.applicationContext as com.laundr.droid.LaunDRoidApp).laundryRoomManager

    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    var scannedCodes by remember { mutableStateOf<List<ScannedQRCode>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var lastScanned by remember { mutableStateOf("") }

    // Machine selection state
    var showMachineDialog by remember { mutableStateOf(false) }
    var pendingQRCode by remember { mutableStateOf<ScannedQRCode?>(null) }
    val rooms by laundryRoomManager.rooms.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("QR Scanner")
                        Text(
                            text = if (isScanning) "Scanning..." else "Paused",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isScanning) CyberGreen else CyberOrange
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Badge(containerColor = CyberGreen) {
                        Text("${scannedCodes.size}")
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { isScanning = !isScanning }) {
                        Icon(
                            if (isScanning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = if (isScanning) CyberOrange else CyberGreen
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!cameraPermission.status.isGranted) {
                // Permission request
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = CyberOrange
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Camera Permission Required", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Grant camera access to scan QR codes on laundry machines",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { cameraPermission.launchPermissionRequest() },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
                    ) {
                        Text("Grant Permission", color = Color.Black)
                    }
                }
            } else {
                // Camera preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(2.dp, CyberGreen, RoundedCornerShape(16.dp))
                ) {
                    if (isScanning) {
                        CameraPreviewWithCapture(
                            onQRCodeScanned = { barcode, imagePath ->
                                val value = barcode.rawValue ?: return@CameraPreviewWithCapture
                                if (value != lastScanned) {
                                    lastScanned = value
                                    val format = when (barcode.format) {
                                        Barcode.FORMAT_QR_CODE -> "QR"
                                        Barcode.FORMAT_DATA_MATRIX -> "DataMatrix"
                                        Barcode.FORMAT_CODE_128 -> "Code128"
                                        Barcode.FORMAT_CODE_39 -> "Code39"
                                        else -> "Barcode"
                                    }
                                    val scannedCode = ScannedQRCode(value, format, imagePath = imagePath)
                                    scannedCodes = listOf(scannedCode) + scannedCodes
                                }
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Paused", color = CyberOrange)
                        }
                    }

                    // Crosshair overlay
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .border(2.dp, CyberGreen.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        )
                    }
                }

                // Scanned codes list
                Text(
                    text = "Scanned Codes",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                if (scannedCodes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Point camera at QR code",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(scannedCodes) { code ->
                            QRCodeCard(
                                code = code,
                                onSaveToMachine = {
                                    pendingQRCode = code
                                    showMachineDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Machine selection dialog
    if (showMachineDialog && pendingQRCode != null) {
        MachineSelectionDialog(
            rooms = rooms,
            qrCode = pendingQRCode!!,
            onDismiss = {
                showMachineDialog = false
                pendingQRCode = null
            },
            onSelectMachine = { room, machine ->
                laundryRoomManager.setMachineQR(
                    roomId = room.id,
                    bleAddress = machine.bleAddress,
                    qrCode = pendingQRCode!!.rawValue,
                    qrImagePath = pendingQRCode!!.imagePath
                )
                showMachineDialog = false
                pendingQRCode = null
            }
        )
    }
}

@Composable
private fun CameraPreviewWithCapture(onQRCodeScanned: (Barcode, String?) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember { BarcodeScanning.getClient() }

    // Track if we've already processed this code to avoid duplicate captures
    var lastProcessedValue by remember { mutableStateOf("") }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { previewView ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(executor) { imageProxy ->
                        @androidx.camera.core.ExperimentalGetImage
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )
                            barcodeScanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    barcodes.firstOrNull()?.let { barcode ->
                                        val value = barcode.rawValue ?: return@let
                                        if (value != lastProcessedValue) {
                                            lastProcessedValue = value
                                            // Capture frame as image
                                            val imagePath = saveImageFromProxy(context, imageProxy)
                                            onQRCodeScanned(barcode, imagePath)
                                        }
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("QRScan", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

/**
 * Save camera frame to file
 */
@androidx.camera.core.ExperimentalGetImage
private fun saveImageFromProxy(context: android.content.Context, imageProxy: ImageProxy): String? {
    return try {
        val mediaImage = imageProxy.image ?: return null

        // Convert YUV to Bitmap
        val yBuffer = mediaImage.planes[0].buffer
        val uBuffer = mediaImage.planes[1].buffer
        val vBuffer = mediaImage.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            mediaImage.width,
            mediaImage.height,
            null
        )

        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, mediaImage.width, mediaImage.height),
            85,
            out
        )

        val imageBytes = out.toByteArray()
        var bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        // Rotate if needed
        if (imageProxy.imageInfo.rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        // Save to file
        val qrDir = File(context.filesDir, "qr_images")
        qrDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(qrDir, "QR_$timestamp.jpg")

        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
        }

        Log.i("QRScan", "Saved QR image to ${file.absolutePath}")
        file.absolutePath
    } catch (e: Exception) {
        Log.e("QRScan", "Failed to save QR image", e)
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QRCodeCard(
    code: ScannedQRCode,
    onSaveToMachine: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(containerColor = CyberBlue) {
                    Text(code.format, style = MaterialTheme.typography.labelSmall)
                }
                if (code.imagePath != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(containerColor = CyberGreen) {
                        Text("IMG", style = MaterialTheme.typography.labelSmall, color = Color.Black)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(code.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = code.rawValue,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )

            // Parse machine info if applicable
            if (code.rawValue.contains("csc", ignoreCase = true) ||
                code.rawValue.contains("laundry", ignoreCase = true) ||
                code.rawValue.contains("machine", ignoreCase = true)) {
                Spacer(Modifier.height(8.dp))
                Badge(containerColor = CyberGreen) {
                    Text("Laundry Machine", style = MaterialTheme.typography.labelSmall, color = Color.Black)
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(8.dp))

            // Save to machine button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onSaveToMachine,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberGreen)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save to Machine")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MachineSelectionDialog(
    rooms: List<LaundryRoom>,
    qrCode: ScannedQRCode,
    onDismiss: () -> Unit,
    onSelectMachine: (LaundryRoom, MachineInfo) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Save QR to Machine")
        },
        text = {
            if (rooms.isEmpty() || rooms.all { it.machines.isEmpty() }) {
                Column {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = CyberOrange,
                        modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No machines found. Add machines to a laundry room first via BLE Scanner.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rooms.filter { it.machines.isNotEmpty() }.forEach { room ->
                        item {
                            Text(
                                text = room.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = CyberGreen
                            )
                        }
                        items(room.machines) { machine ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectMachine(room, machine) },
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
                                    Icon(
                                        imageVector = when (machine.machineType) {
                                            com.laundr.droid.ble.MachineType.WASHER -> Icons.Default.LocalLaundryService
                                            com.laundr.droid.ble.MachineType.DRYER -> Icons.Default.Dry
                                            else -> Icons.Default.DevicesOther
                                        },
                                        contentDescription = null,
                                        tint = CyberBlue
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = machine.displayName,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = machine.bleAddress,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                        if (machine.qrCode != null) {
                                            Spacer(Modifier.height(4.dp))
                                            Badge(containerColor = CyberOrange) {
                                                Text("Has QR", style = MaterialTheme.typography.labelSmall, color = Color.Black)
                                            }
                                        }
                                    }
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
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
