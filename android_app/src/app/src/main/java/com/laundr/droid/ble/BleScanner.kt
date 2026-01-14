package com.laundr.droid.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BleDeviceInfo(
    val device: BluetoothDevice,
    val name: String?,
    val address: String,
    val rssi: Int,
    val isConnectable: Boolean,
    val serviceUuids: List<String>,
    val manufacturerData: Map<Int, ByteArray>,
    val rawScanRecord: ByteArray?,
    val lastSeen: Long = System.currentTimeMillis(),
    val isCSC: Boolean = false  // Detected as CSC device
)

class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"

        // Known CSC ServiceWorks identifiers - STRICT matching only
        val CSC_SERVICE_UUIDS = listOf(
            "0000fff0-0000-1000-8000-00805f9b34fb",  // Common CSC service
            "0000ffe0-0000-1000-8000-00805f9b34fb"   // Alternate
        )

        // EXACT CSC device name patterns - must be specific
        val CSC_EXACT_NAMES = listOf(
            "CSC", "CSC-", "ServiceWorks", "SW-", "SPEEDQUEEN", "SPEED QUEEN",
            "CSC_", "CSCGO", "CSC GO"
        )

        // Patterns that MIGHT indicate laundry (but need manual confirmation)
        val POSSIBLE_LAUNDRY_PATTERNS = listOf(
            "LG_", "SAMSUNG_", "MAYTAG", "WHIRLPOOL", "KENMORE"
        )
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bleScanner: BluetoothLeScanner? = null

    private val _devices = MutableStateFlow<Map<String, BleDeviceInfo>>(emptyMap())
    val devices: StateFlow<Map<String, BleDeviceInfo>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanLog = MutableStateFlow<List<String>>(emptyList())
    val scanLog: StateFlow<List<String>> = _scanLog.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            processResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { processResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            addLog("ERROR: Scan failed (code: $errorCode)")
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun processResult(result: ScanResult) {
        val device = result.device
        val scanRecord = result.scanRecord

        // Extract service UUIDs
        val serviceUuids = scanRecord?.serviceUuids?.map { it.toString() } ?: emptyList()

        // Extract manufacturer data
        val manufacturerData = mutableMapOf<Int, ByteArray>()
        scanRecord?.manufacturerSpecificData?.let { data ->
            for (i in 0 until data.size()) {
                manufacturerData[data.keyAt(i)] = data.valueAt(i)
            }
        }

        // Check if this is a CSC device
        val deviceName = device.name ?: scanRecord?.deviceName
        val isCSC = isCSCDevice(deviceName, serviceUuids, manufacturerData)

        val deviceInfo = BleDeviceInfo(
            device = device,
            name = deviceName,
            address = device.address,
            rssi = result.rssi,
            isConnectable = result.isConnectable,
            serviceUuids = serviceUuids,
            manufacturerData = manufacturerData,
            rawScanRecord = scanRecord?.bytes,
            isCSC = isCSC
        )

        val currentDevices = _devices.value.toMutableMap()
        val isNew = !currentDevices.containsKey(device.address)
        currentDevices[device.address] = deviceInfo
        _devices.value = currentDevices

        // Feed to WarDriveLogger for persistent logging and auto-probing
        if (WarDriveLogger.isRunning.value) {
            WarDriveLogger.onDeviceDiscovered(deviceInfo)
        }

        if (isNew) {
            val cscTag = if (isCSC) " [CSC DETECTED]" else ""
            addLog("Found: ${deviceName ?: "Unknown"} (${device.address}) RSSI: ${result.rssi}$cscTag")
        }
    }

    private fun isCSCDevice(
        name: String?,
        serviceUuids: List<String>,
        manufacturerData: Map<Int, ByteArray>
    ): Boolean {
        // Check service UUIDs first (most reliable)
        serviceUuids.forEach { uuid ->
            if (CSC_SERVICE_UUIDS.any { it.equals(uuid, ignoreCase = true) }) {
                Log.d(TAG, "CSC detected by service UUID: $uuid")
                return true
            }
        }

        // Check EXACT name patterns only - be strict
        name?.let { deviceName ->
            val upperName = deviceName.uppercase()
            CSC_EXACT_NAMES.forEach { pattern ->
                if (upperName.startsWith(pattern.uppercase()) ||
                    upperName == pattern.uppercase()) {
                    Log.d(TAG, "CSC detected by name: $deviceName matches $pattern")
                    return true
                }
            }
        }

        return false
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_isScanning.value) {
            Log.w(TAG, "Already scanning")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            addLog("ERROR: Bluetooth not available or disabled")
            return
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            addLog("ERROR: BLE scanner not available")
            return
        }

        // Clear previous results
        _devices.value = emptyMap()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        // No filters - scan for all devices
        val filters = emptyList<ScanFilter>()

        try {
            bleScanner?.startScan(filters, settings, scanCallback)
            _isScanning.value = true
            addLog("=== SCAN STARTED ===")
            Log.i(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
            addLog("ERROR: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) return

        try {
            bleScanner?.stopScan(scanCallback)
            _isScanning.value = false
            addLog("=== SCAN STOPPED === (${_devices.value.size} devices found)")
            Log.i(TAG, "BLE scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scan", e)
        }
    }

    fun clearDevices() {
        _devices.value = emptyMap()
        addLog("Device list cleared")
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val logEntry = "[$timestamp] $message"
        _scanLog.value = _scanLog.value + logEntry
    }

    fun clearLog() {
        _scanLog.value = emptyList()
    }

    fun getLogText(): String = _scanLog.value.joinToString("\n")
}
