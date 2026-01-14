package com.laundr.droid.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * War Drive Logger for LaundR - Logs ALL BLE devices and auto-probes potential washers/dryers
 *
 * Features:
 * - Logs every discovered BLE device with timestamp, RSSI, location, services
 * - Auto-detects potential laundry devices by name patterns
 * - Quick probe: connect -> discover services -> disconnect (non-intrusive)
 * - In-depth probe: read characteristics, try CSC exploit patterns
 * - Persistent CSV logging for later analysis
 * - Session stats and real-time log view
 */
@SuppressLint("MissingPermission")
object WarDriveLogger {
    private const val TAG = "WarDrive"
    private const val LOG_FILE = "wardrive_ble_log.csv"
    private const val PROBE_TIMEOUT_MS = 8000L
    private const val QUICK_PROBE_TIMEOUT_MS = 4000L

    private var context: Context? = null
    private var logFile: File? = null

    // State flows
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<Map<String, WarDriveDevice>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, WarDriveDevice>> = _discoveredDevices.asStateFlow()

    private val _sessionLog = MutableStateFlow<List<String>>(emptyList())
    val sessionLog: StateFlow<List<String>> = _sessionLog.asStateFlow()

    private val _stats = MutableStateFlow(WarDriveStats())
    val stats: StateFlow<WarDriveStats> = _stats.asStateFlow()

    private val _probeQueue = MutableStateFlow<List<String>>(emptyList())
    val probeQueue: StateFlow<List<String>> = _probeQueue.asStateFlow()

    // Tracking
    private val probedDevices = ConcurrentHashMap<String, ProbeResult>()
    private val pendingProbes = ConcurrentHashMap<String, BluetoothDevice>()
    private var probeJob: Job? = null

    // Laundry detection patterns (expanded)
    private val LAUNDRY_NAME_PATTERNS = listOf(
        // CSC ServiceWorks
        "CSC", "CSCGO", "CSC-", "CSC_", "SERVICEMASTER", "SERVICEWORKS", "SW-",
        // Speed Queen
        "SPEEDQUEEN", "SPEED QUEEN", "SQ-", "SQCOMMERCIAL",
        // Maytag
        "MAYTAG", "MYT-", "MAYTAG_",
        // Whirlpool
        "WHIRLPOOL", "WP-", "WHIRLPOOL_",
        // LG
        "LG_WASHER", "LG_DRYER", "LG-W", "LG-D", "THINQ",
        // Samsung
        "SAMSUNG_W", "SAMSUNG_D", "SMARTTHINGS",
        // Electrolux
        "ELECTROLUX", "ELX-",
        // Huebsch
        "HUEBSCH", "HUE-",
        // Generic laundry
        "WASHER", "DRYER", "LAUNDRY", "COIN-OP", "COINOP"
    )

    // Service UUIDs that indicate laundry devices
    private val LAUNDRY_SERVICE_UUIDS = listOf(
        "0000fff0-0000-1000-8000-00805f9b34fb",  // CSC primary
        "0000ffe0-0000-1000-8000-00805f9b34fb",  // CSC alternate
        "0000180a-0000-1000-8000-00805f9b34fb",  // Device Info (common)
        "49535343-fe7d-4ae5-8fa9-9fafd205e455",  // Nordic UART (some IoT washers)
    )

    fun init(ctx: Context) {
        context = ctx.applicationContext
        logFile = File(ctx.filesDir, LOG_FILE)

        // Create log file with header if it doesn't exist
        if (!logFile!!.exists()) {
            logFile!!.writeText(buildString {
                append("timestamp,address,name,rssi,lat,lon,")
                append("is_laundry,laundry_type,services,characteristics,")
                append("manufacturer_id,manufacturer_data,probe_status,probe_details\n")
            })
        }

        log("WarDriveLogger initialized")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _sessionLog.value = (_sessionLog.value + "[$timestamp] $msg").takeLast(500)
    }

    /**
     * Start war driving - begins logging and auto-probing
     */
    fun start() {
        if (_isRunning.value) return

        _isRunning.value = true
        log("=== WAR DRIVE STARTED ===")

        // Start probe worker
        probeJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && _isRunning.value) {
                processProbeQueue()
                delay(1000)
            }
        }
    }

    /**
     * Stop war driving
     */
    fun stop() {
        _isRunning.value = false
        probeJob?.cancel()
        probeJob = null
        log("=== WAR DRIVE STOPPED ===")
        log("Session: ${_stats.value}")
    }

    /**
     * Called when a device is discovered during BLE scanning
     * This is the main entry point - call this from BleScanner
     */
    fun onDeviceDiscovered(deviceInfo: BleDeviceInfo) {
        if (!_isRunning.value) return

        val address = deviceInfo.address
        val name = deviceInfo.name
        val now = System.currentTimeMillis()

        // Detect if this might be a laundry device
        val laundryType = detectLaundryType(name, deviceInfo.serviceUuids)
        val isLaundry = laundryType != LaundryType.NONE

        // Get existing device data
        val existing = _discoveredDevices.value[address]

        // Build device record
        val device = WarDriveDevice(
            address = address,
            name = name,
            rssi = deviceInfo.rssi,
            isLaundry = isLaundry,
            laundryType = laundryType,
            firstSeen = existing?.firstSeen ?: now,
            lastSeen = now,
            location = null,  // Location disabled for now
            advertisedServices = deviceInfo.serviceUuids,
            discoveredServices = existing?.discoveredServices ?: emptyList(),
            discoveredCharacteristics = existing?.discoveredCharacteristics ?: emptyList(),
            manufacturerId = deviceInfo.manufacturerData.keys.firstOrNull(),
            manufacturerData = deviceInfo.manufacturerData.values.firstOrNull()?.toHexString() ?: "",
            probeStatus = existing?.probeStatus ?: ProbeStatus.PENDING,
            probeDetails = existing?.probeDetails ?: "",
            rawDevice = deviceInfo.device
        )

        // Update device map
        _discoveredDevices.value = _discoveredDevices.value + (address to device)

        // Update stats
        updateStats()

        // Queue for probing if it's a potential laundry device and hasn't been probed
        if (isLaundry && !probedDevices.containsKey(address) && !pendingProbes.containsKey(address)) {
            pendingProbes[address] = deviceInfo.device
            _probeQueue.value = pendingProbes.keys.toList()
            log("LAUNDRY DETECTED: ${name ?: address} ($laundryType) - queued for probe")
        }

        // Log first sighting to file
        if (existing == null) {
            appendToLog(device)
        }
    }

    /**
     * Detect what type of laundry device this might be
     */
    private fun detectLaundryType(name: String?, serviceUuids: List<String>): LaundryType {
        // Check service UUIDs first (most reliable)
        serviceUuids.forEach { uuid ->
            val uuidLower = uuid.lowercase()
            if (uuidLower.contains("fff0") || uuidLower.contains("ffe0")) {
                return LaundryType.CSC
            }
        }

        // Check name patterns
        if (name == null) return LaundryType.NONE

        val upperName = name.uppercase()

        // CSC patterns
        if (upperName.startsWith("CSC") || upperName.contains("SERVICEMASTER") ||
            upperName.contains("SERVICEWORKS")) {
            return LaundryType.CSC
        }

        // Speed Queen
        if (upperName.contains("SPEEDQUEEN") || upperName.contains("SPEED QUEEN") ||
            upperName.startsWith("SQ")) {
            return LaundryType.SPEED_QUEEN
        }

        // Generic washer detection
        if (upperName.contains("WASHER") || upperName.contains("WASH")) {
            return LaundryType.GENERIC_WASHER
        }

        // Generic dryer detection
        if (upperName.contains("DRYER") || upperName.contains("DRY")) {
            return LaundryType.GENERIC_DRYER
        }

        // Check remaining patterns
        LAUNDRY_NAME_PATTERNS.forEach { pattern ->
            if (upperName.contains(pattern.uppercase())) {
                return LaundryType.POSSIBLE
            }
        }

        return LaundryType.NONE
    }

    /**
     * Process the probe queue - quick connect to gather service info
     */
    private suspend fun processProbeQueue() {
        val entry = pendingProbes.entries.firstOrNull() ?: return
        val (address, device) = entry

        pendingProbes.remove(address)
        _probeQueue.value = pendingProbes.keys.toList()

        val deviceName = device.name ?: address
        log("PROBING: $deviceName")

        // Update status to probing
        updateDeviceProbeStatus(address, ProbeStatus.PROBING, "Connecting...")

        try {
            val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                probeDevice(device)
            }

            if (result != null) {
                probedDevices[address] = result

                // Update device with probe results
                val existing = _discoveredDevices.value[address]
                if (existing != null) {
                    val updated = existing.copy(
                        discoveredServices = result.services,
                        discoveredCharacteristics = result.characteristics,
                        probeStatus = if (result.success) ProbeStatus.SUCCESS else ProbeStatus.FAILED,
                        probeDetails = result.details
                    )
                    _discoveredDevices.value = _discoveredDevices.value + (address to updated)
                    appendToLog(updated) // Update log with probe results

                    log("PROBE COMPLETE: $deviceName - ${result.services.size} services, ${result.characteristics.size} chars")

                    // If we found interesting services, log them
                    result.services.forEach { svc ->
                        if (svc.contains("fff", ignoreCase = true) || svc.contains("ffe", ignoreCase = true)) {
                            log("  CSC SERVICE: $svc")
                        }
                    }

                    _stats.value = _stats.value.copy(
                        devicesProbed = _stats.value.devicesProbed + 1,
                        successfulProbes = if (result.success) _stats.value.successfulProbes + 1 else _stats.value.successfulProbes
                    )
                }
            } else {
                log("PROBE TIMEOUT: $deviceName")
                updateDeviceProbeStatus(address, ProbeStatus.TIMEOUT, "Connection timeout")
            }
        } catch (e: Exception) {
            log("PROBE ERROR: $deviceName - ${e.message}")
            updateDeviceProbeStatus(address, ProbeStatus.FAILED, e.message ?: "Unknown error")
        }
    }

    /**
     * Quick probe - connect, discover services, read device info, disconnect
     */
    private suspend fun probeDevice(device: BluetoothDevice): ProbeResult {
        val ctx = context ?: return ProbeResult(false, "No context")

        return suspendCancellableCoroutine { continuation ->
            var gatt: BluetoothGatt? = null
            var completed = false
            val services = mutableListOf<String>()
            val characteristics = mutableListOf<String>()
            val detailsBuilder = StringBuilder()

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            detailsBuilder.append("Connected. ")
                            g.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (!completed) {
                                completed = true
                                val result = if (services.isNotEmpty()) {
                                    ProbeResult(true, detailsBuilder.toString(), services.toList(), characteristics.toList())
                                } else {
                                    ProbeResult(false, "Disconnected before service discovery")
                                }
                                continuation.resume(result) {}
                            }
                            g.close()
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        detailsBuilder.append("${g.services.size} services. ")

                        g.services.forEach { service ->
                            val svcUuid = service.uuid.toString()
                            services.add(svcUuid)

                            service.characteristics.forEach { char ->
                                val charUuid = char.uuid.toString()
                                characteristics.add("${svcUuid.takeLast(8)}/${charUuid.takeLast(8)}")

                                // Check for CSC-specific characteristics
                                val charLower = charUuid.lowercase()
                                if (charLower.contains("fff1") || charLower.contains("fff2") ||
                                    charLower.contains("ffe1") || charLower.contains("ffe2")) {
                                    detailsBuilder.append("CSC_CHAR:$charUuid ")
                                }
                            }
                        }

                        completed = true
                        g.disconnect()
                        continuation.resume(
                            ProbeResult(true, detailsBuilder.toString(), services.toList(), characteristics.toList())
                        ) {}
                    } else {
                        completed = true
                        g.disconnect()
                        continuation.resume(ProbeResult(false, "Service discovery failed: $status")) {}
                    }
                }
            }

            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(ctx, false, callback)
            }

            continuation.invokeOnCancellation {
                if (!completed) {
                    gatt?.disconnect()
                    gatt?.close()
                }
            }
        }
    }

    private fun updateDeviceProbeStatus(address: String, status: ProbeStatus, details: String) {
        val existing = _discoveredDevices.value[address] ?: return
        val updated = existing.copy(probeStatus = status, probeDetails = details)
        _discoveredDevices.value = _discoveredDevices.value + (address to updated)
    }

    private fun updateStats() {
        val devices = _discoveredDevices.value.values
        _stats.value = _stats.value.copy(
            totalDevices = devices.size,
            laundryDevices = devices.count { it.isLaundry },
            cscDevices = devices.count { it.laundryType == LaundryType.CSC },
            lastUpdate = System.currentTimeMillis()
        )
    }

    private fun appendToLog(device: WarDriveDevice) {
        try {
            val line = buildString {
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                append(",")
                append(device.address)
                append(",")
                append("\"${device.name ?: ""}\"")
                append(",")
                append(device.rssi)
                append(",")
                append(device.location?.lat ?: "")
                append(",")
                append(device.location?.lon ?: "")
                append(",")
                append(device.isLaundry)
                append(",")
                append(device.laundryType)
                append(",")
                append("\"${device.discoveredServices.joinToString(";")}\"")
                append(",")
                append("\"${device.discoveredCharacteristics.joinToString(";")}\"")
                append(",")
                append(device.manufacturerId ?: "")
                append(",")
                append(device.manufacturerData)
                append(",")
                append(device.probeStatus)
                append(",")
                append("\"${device.probeDetails}\"")
                append("\n")
            }

            logFile?.appendText(line)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to log: ${e.message}")
        }
    }

    fun getLogFilePath(): String? = logFile?.absolutePath

    fun clearSession() {
        _discoveredDevices.value = emptyMap()
        _sessionLog.value = emptyList()
        probedDevices.clear()
        pendingProbes.clear()
        _probeQueue.value = emptyList()
        _stats.value = WarDriveStats()
    }

    fun clearAllLogs() {
        clearSession()
        logFile?.delete()
        logFile?.writeText(buildString {
            append("timestamp,address,name,rssi,lat,lon,")
            append("is_laundry,laundry_type,services,characteristics,")
            append("manufacturer_id,manufacturer_data,probe_status,probe_details\n")
        })
        log("All logs cleared")
    }

    fun exportLog(): String {
        val sb = StringBuilder()
        sb.appendLine("=== LAUNDR WAR DRIVE LOG ===")
        sb.appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine()
        sb.appendLine("SESSION STATS:")
        sb.appendLine("  Total devices: ${_stats.value.totalDevices}")
        sb.appendLine("  Laundry devices: ${_stats.value.laundryDevices}")
        sb.appendLine("  CSC devices: ${_stats.value.cscDevices}")
        sb.appendLine("  Devices probed: ${_stats.value.devicesProbed}")
        sb.appendLine("  Successful probes: ${_stats.value.successfulProbes}")
        sb.appendLine()

        sb.appendLine("LAUNDRY DEVICES FOUND:")
        _discoveredDevices.value.values
            .filter { it.isLaundry }
            .sortedByDescending { it.lastSeen }
            .forEach { device ->
                sb.appendLine("  ${device.name ?: "Unknown"} (${device.address})")
                sb.appendLine("    Type: ${device.laundryType}")
                sb.appendLine("    RSSI: ${device.rssi} dBm")
                sb.appendLine("    Location: ${device.location?.let { "${it.lat}, ${it.lon}" } ?: "Unknown"}")
                sb.appendLine("    Probe: ${device.probeStatus} - ${device.probeDetails}")
                if (device.discoveredServices.isNotEmpty()) {
                    sb.appendLine("    Services: ${device.discoveredServices.size}")
                    device.discoveredServices.forEach { svc ->
                        sb.appendLine("      - $svc")
                    }
                }
                sb.appendLine()
            }

        sb.appendLine("ALL DEVICES (${_discoveredDevices.value.size}):")
        _discoveredDevices.value.values.sortedByDescending { it.rssi }.forEach { device ->
            val laundryTag = if (device.isLaundry) " [${device.laundryType}]" else ""
            sb.appendLine("  ${device.address}: ${device.name ?: "Unknown"} (${device.rssi}dBm)$laundryTag")
        }

        return sb.toString()
    }

    /**
     * Manually trigger a probe for a specific device
     */
    fun manualProbe(device: BluetoothDevice) {
        val address = device.address
        if (!pendingProbes.containsKey(address)) {
            pendingProbes[address] = device
            _probeQueue.value = pendingProbes.keys.toList()
            log("Manual probe queued: ${device.name ?: address}")
        }
    }

    /**
     * Get count of CSC devices for quick check
     */
    fun getCSCCount(): Int = _discoveredDevices.value.values.count { it.laundryType == LaundryType.CSC }
}

// Data classes

data class WarDriveDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val isLaundry: Boolean,
    val laundryType: LaundryType,
    val firstSeen: Long,
    val lastSeen: Long,
    val location: LatLon?,
    val advertisedServices: List<String>,
    val discoveredServices: List<String>,
    val discoveredCharacteristics: List<String>,
    val manufacturerId: Int?,
    val manufacturerData: String,
    val probeStatus: ProbeStatus,
    val probeDetails: String,
    val rawDevice: BluetoothDevice
)

data class LatLon(val lat: Double, val lon: Double)

data class WarDriveStats(
    val totalDevices: Int = 0,
    val laundryDevices: Int = 0,
    val cscDevices: Int = 0,
    val devicesProbed: Int = 0,
    val successfulProbes: Int = 0,
    val lastUpdate: Long = 0
) {
    override fun toString(): String =
        "Total: $totalDevices, Laundry: $laundryDevices, CSC: $cscDevices, Probed: $devicesProbed/$successfulProbes"
}

data class ProbeResult(
    val success: Boolean,
    val details: String,
    val services: List<String> = emptyList(),
    val characteristics: List<String> = emptyList()
)

enum class LaundryType {
    NONE,
    CSC,
    SPEED_QUEEN,
    GENERIC_WASHER,
    GENERIC_DRYER,
    POSSIBLE
}

enum class ProbeStatus {
    PENDING,
    PROBING,
    SUCCESS,
    FAILED,
    TIMEOUT
}

// Extension
private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }
