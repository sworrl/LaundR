package com.laundr.droid.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class GattServiceInfo(
    val uuid: UUID,
    val type: Int,
    val characteristics: List<GattCharacteristicInfo>
)

data class GattCharacteristicInfo(
    val uuid: UUID,
    val properties: Int,
    val value: ByteArray?,
    val descriptors: List<UUID>
) {
    val canRead: Boolean get() = (properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
    val canWrite: Boolean get() = (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
    val canWriteNoResponse: Boolean get() = (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    val canNotify: Boolean get() = (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
    val canIndicate: Boolean get() = (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    fun propertiesString(): String {
        val props = mutableListOf<String>()
        if (canRead) props.add("R")
        if (canWrite) props.add("W")
        if (canWriteNoResponse) props.add("WNR")
        if (canNotify) props.add("N")
        if (canIndicate) props.add("I")
        return props.joinToString(",")
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCOVERING_SERVICES,
    READY,
    DISCONNECTING
}

class GattManager(private val context: Context) {

    companion object {
        private const val TAG = "GattManager"
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var currentDevice: BluetoothDevice? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _services = MutableStateFlow<List<GattServiceInfo>>(emptyList())
    val services: StateFlow<List<GattServiceInfo>> = _services.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _lastReadValue = MutableStateFlow<Pair<UUID, ByteArray>?>(null)
    val lastReadValue: StateFlow<Pair<UUID, ByteArray>?> = _lastReadValue.asStateFlow()

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    addLog("Connected to ${gatt.device.address}")
                    _connectionState.value = ConnectionState.DISCOVERING_SERVICES
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    addLog("Disconnected (status: $status)")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _services.value = emptyList()
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Services discovered: ${gatt.services.size} services")
                val serviceInfos = gatt.services.map { service ->
                    GattServiceInfo(
                        uuid = service.uuid,
                        type = service.type,
                        characteristics = service.characteristics.map { char ->
                            GattCharacteristicInfo(
                                uuid = char.uuid,
                                properties = char.properties,
                                value = null,
                                descriptors = char.descriptors.map { it.uuid }
                            )
                        }
                    )
                }
                _services.value = serviceInfos
                _connectionState.value = ConnectionState.READY

                // Log all discovered services
                gatt.services.forEach { service ->
                    addLog("  Service: ${service.uuid}")
                    service.characteristics.forEach { char ->
                        val charInfo = GattCharacteristicInfo(
                            uuid = char.uuid,
                            properties = char.properties,
                            value = null,
                            descriptors = emptyList()
                        )
                        addLog("    Char: ${char.uuid} [${charInfo.propertiesString()}]")
                    }
                }
            } else {
                addLog("Service discovery failed: $status")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val hexValue = value.joinToString(" ") { "%02X".format(it) }
                addLog("Read ${characteristic.uuid}: $hexValue")
                _lastReadValue.value = Pair(characteristic.uuid, value)
            } else {
                addLog("Read failed: ${characteristic.uuid} (status: $status)")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Write success: ${characteristic.uuid}")
            } else {
                addLog("Write failed: ${characteristic.uuid} (status: $status)")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val hexValue = value.joinToString(" ") { "%02X".format(it) }
            addLog("Notify ${characteristic.uuid}: $hexValue")
            _lastReadValue.value = Pair(characteristic.uuid, value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Descriptor write success")
            } else {
                addLog("Descriptor write failed: $status")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            addLog("Already connected or connecting")
            return
        }

        currentDevice = device
        _connectionState.value = ConnectionState.CONNECTING
        addLog("Connecting to ${device.address}...")

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTING
        addLog("Disconnecting...")
        bluetoothGatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    fun readCharacteristic(serviceUuid: UUID, charUuid: UUID) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(serviceUuid) ?: run {
            addLog("Service not found: $serviceUuid")
            return
        }
        val characteristic = service.getCharacteristic(charUuid) ?: run {
            addLog("Characteristic not found: $charUuid")
            return
        }

        addLog("Reading $charUuid...")
        gatt.readCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, value: ByteArray, writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(serviceUuid) ?: run {
            addLog("Service not found: $serviceUuid")
            return
        }
        val characteristic = service.getCharacteristic(charUuid) ?: run {
            addLog("Characteristic not found: $charUuid")
            return
        }

        val hexValue = value.joinToString(" ") { "%02X".format(it) }
        addLog("Writing to $charUuid: $hexValue")

        characteristic.writeType = writeType
        gatt.writeCharacteristic(characteristic, value, writeType)
    }

    @SuppressLint("MissingPermission")
    fun enableNotifications(serviceUuid: UUID, charUuid: UUID) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(serviceUuid) ?: return
        val characteristic = service.getCharacteristic(charUuid) ?: return

        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            addLog("Enabled notifications for $charUuid")
        }
    }

    fun getConnectedDevice(): BluetoothDevice? = currentDevice

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val logEntry = "[$timestamp] $message"
        _log.value = _log.value + logEntry
        Log.d(TAG, message)
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    fun getLogText(): String = _log.value.joinToString("\n")
}
