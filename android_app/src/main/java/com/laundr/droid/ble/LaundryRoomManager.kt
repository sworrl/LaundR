package com.laundr.droid.ble

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Laundry Room Manager - Manages laundry rooms, machines, and their BLE devices
 *
 * Features:
 * - Parse machine numbers from BLE device names
 * - Group machines into named laundry rooms
 * - Track machine status (in-range, last seen, connection status)
 * - Persist machine configurations
 * - Out-of-range detection
 */
class LaundryRoomManager(private val context: Context) {

    companion object {
        private const val TAG = "LaundryRoomManager"
        private const val ROOMS_FILE = "laundry_rooms.json"

        // Machine type detection patterns
        private val WASHER_PATTERNS = listOf(
            Regex(".*[WW][-_]?(\\d+).*", RegexOption.IGNORE_CASE),
            Regex(".*WASHER[-_]?(\\d+).*", RegexOption.IGNORE_CASE),
            Regex(".*WASH[-_]?(\\d+).*", RegexOption.IGNORE_CASE)
        )
        private val DRYER_PATTERNS = listOf(
            Regex(".*[DD][-_]?(\\d+).*", RegexOption.IGNORE_CASE),
            Regex(".*DRYER[-_]?(\\d+).*", RegexOption.IGNORE_CASE),
            Regex(".*DRY[-_]?(\\d+).*", RegexOption.IGNORE_CASE)
        )

        // CSC device name patterns
        private val CSC_NAME_PATTERN = Regex("CSC[-_]?([WD])[-_]?(\\d+)", RegexOption.IGNORE_CASE)

        // Out of range threshold (ms) - consider out of range after 60 seconds
        private const val OUT_OF_RANGE_THRESHOLD = 60_000L
    }

    private val roomsFile: File
        get() = File(context.filesDir, ROOMS_FILE)

    private val _rooms = MutableStateFlow<List<LaundryRoom>>(emptyList())
    val rooms: StateFlow<List<LaundryRoom>> = _rooms.asStateFlow()

    private val _selectedRoom = MutableStateFlow<LaundryRoom?>(null)
    val selectedRoom: StateFlow<LaundryRoom?> = _selectedRoom.asStateFlow()

    init {
        loadRooms()
    }

    /**
     * Parse machine info from BLE device - captures ALL available BLE data for offline viewing
     */
    fun parseMachineFromDevice(device: BleDeviceInfo): MachineInfo? {
        val name = device.name ?: return null
        val now = System.currentTimeMillis()

        // Extract all BLE data for offline storage
        val manufacturerDataHex = device.manufacturerData.mapKeys { it.key.toString() }
            .mapValues { entry -> entry.value.joinToString("") { "%02X".format(it) } }

        val rawScanHex = device.rawScanRecord?.joinToString("") { "%02X".format(it) }

        // Helper to create MachineInfo with all BLE data
        fun createMachine(
            machineNumber: Int,
            machineType: MachineType,
            provider: String
        ) = MachineInfo(
            bleAddress = device.address,
            bleName = name,
            machineNumber = machineNumber,
            machineType = machineType,
            provider = provider,
            lastSeen = now,
            rssi = device.rssi,
            // Extended BLE data
            serviceUuids = device.serviceUuids,
            manufacturerDataHex = manufacturerDataHex,
            rawScanRecordHex = rawScanHex,
            isConnectable = device.isConnectable,
            isCSC = device.isCSC,
            firstDiscovered = now,
            addedToRoomAt = now
        )

        // Try CSC pattern first (most specific)
        CSC_NAME_PATTERN.find(name)?.let { match ->
            val typeChar = match.groupValues[1].uppercase()
            val number = match.groupValues[2].toIntOrNull() ?: 0
            val type = if (typeChar == "W") MachineType.WASHER else MachineType.DRYER
            return createMachine(number, type, "CSC ServiceWorks")
        }

        // Try washer patterns
        for (pattern in WASHER_PATTERNS) {
            pattern.find(name)?.let { match ->
                val number = match.groupValues[1].toIntOrNull() ?: 0
                return createMachine(number, MachineType.WASHER, detectProvider(device))
            }
        }

        // Try dryer patterns
        for (pattern in DRYER_PATTERNS) {
            pattern.find(name)?.let { match ->
                val number = match.groupValues[1].toIntOrNull() ?: 0
                return createMachine(number, MachineType.DRYER, detectProvider(device))
            }
        }

        // Generic laundry device (no number parsed)
        if (device.isCSC || name.uppercase().contains("CSC") ||
            name.uppercase().contains("LAUND") ||
            name.uppercase().contains("WASH") ||
            name.uppercase().contains("DRY")) {
            return createMachine(0, MachineType.UNKNOWN, detectProvider(device))
        }

        return null
    }

    private fun detectProvider(device: BleDeviceInfo): String {
        val name = device.name?.uppercase() ?: ""
        return when {
            name.contains("CSC") || device.isCSC -> "CSC ServiceWorks"
            name.contains("SPEED") && name.contains("QUEEN") -> "Speed Queen"
            name.contains("MAYTAG") -> "Maytag"
            name.contains("WHIRLPOOL") -> "Whirlpool"
            name.contains("LG") -> "LG"
            name.contains("SAMSUNG") -> "Samsung"
            else -> "Unknown"
        }
    }

    /**
     * Create a new laundry room
     */
    fun createRoom(name: String, location: String? = null): LaundryRoom {
        val room = LaundryRoom(
            id = UUID.randomUUID().toString(),
            name = name,
            location = location,
            machines = emptyList(),
            createdAt = System.currentTimeMillis()
        )
        _rooms.value = _rooms.value + room
        persistRooms()
        return room
    }

    /**
     * Delete a laundry room
     */
    fun deleteRoom(roomId: String) {
        _rooms.value = _rooms.value.filter { it.id != roomId }
        if (_selectedRoom.value?.id == roomId) {
            _selectedRoom.value = null
        }
        persistRooms()
    }

    /**
     * Select a room
     */
    fun selectRoom(roomId: String) {
        _selectedRoom.value = _rooms.value.find { it.id == roomId }
    }

    /**
     * Add machine to room
     */
    fun addMachineToRoom(roomId: String, machine: MachineInfo, nickname: String? = null) {
        val roomIndex = _rooms.value.indexOfFirst { it.id == roomId }
        if (roomIndex < 0) return

        val room = _rooms.value[roomIndex]
        val updatedMachine = machine.copy(nickname = nickname ?: machine.displayName)
        val updatedMachines = room.machines.filter { it.bleAddress != machine.bleAddress } + updatedMachine
        val updatedRoom = room.copy(machines = updatedMachines)

        _rooms.value = _rooms.value.toMutableList().also { it[roomIndex] = updatedRoom }

        if (_selectedRoom.value?.id == roomId) {
            _selectedRoom.value = updatedRoom
        }

        persistRooms()
        Log.i(TAG, "Added machine ${machine.displayName} to room ${room.name}")
    }

    /**
     * Remove machine from room
     */
    fun removeMachineFromRoom(roomId: String, bleAddress: String) {
        val roomIndex = _rooms.value.indexOfFirst { it.id == roomId }
        if (roomIndex < 0) return

        val room = _rooms.value[roomIndex]
        val updatedMachines = room.machines.filter { it.bleAddress != bleAddress }
        val updatedRoom = room.copy(machines = updatedMachines)

        _rooms.value = _rooms.value.toMutableList().also { it[roomIndex] = updatedRoom }

        if (_selectedRoom.value?.id == roomId) {
            _selectedRoom.value = updatedRoom
        }

        persistRooms()
    }

    /**
     * Update machine status from BLE scan results
     */
    fun updateMachineStatus(devices: Map<String, BleDeviceInfo>) {
        val currentTime = System.currentTimeMillis()

        val updatedRooms = _rooms.value.map { room ->
            val updatedMachines = room.machines.map { machine ->
                val device = devices[machine.bleAddress]
                if (device != null) {
                    // Device found in scan - update
                    machine.copy(
                        lastSeen = currentTime,
                        rssi = device.rssi,
                        inRange = true,
                        status = MachineStatus.IDLE // Would need actual status from BLE
                    )
                } else {
                    // Check if out of range
                    val timeSinceLastSeen = currentTime - machine.lastSeen
                    machine.copy(
                        inRange = timeSinceLastSeen < OUT_OF_RANGE_THRESHOLD,
                        status = if (timeSinceLastSeen >= OUT_OF_RANGE_THRESHOLD)
                            MachineStatus.OUT_OF_RANGE else machine.status
                    )
                }
            }
            room.copy(machines = updatedMachines)
        }

        _rooms.value = updatedRooms

        // Update selected room
        _selectedRoom.value?.let { selected ->
            _selectedRoom.value = updatedRooms.find { it.id == selected.id }
        }
    }

    /**
     * Rename machine
     */
    fun renameMachine(roomId: String, bleAddress: String, newName: String) {
        val roomIndex = _rooms.value.indexOfFirst { it.id == roomId }
        if (roomIndex < 0) return

        val room = _rooms.value[roomIndex]
        val machineIndex = room.machines.indexOfFirst { it.bleAddress == bleAddress }
        if (machineIndex < 0) return

        val machines = room.machines.toMutableList()
        machines[machineIndex] = machines[machineIndex].copy(nickname = newName)

        val updatedRoom = room.copy(machines = machines)
        _rooms.value = _rooms.value.toMutableList().also { it[roomIndex] = updatedRoom }

        if (_selectedRoom.value?.id == roomId) {
            _selectedRoom.value = updatedRoom
        }

        persistRooms()
    }

    /**
     * Add NFC card to room
     */
    fun addCardToRoom(roomId: String, uid: String, nickname: String?, balance: Int?, photoPath: String? = null) {
        val roomIndex = _rooms.value.indexOfFirst { it.id == roomId }
        if (roomIndex < 0) return

        val room = _rooms.value[roomIndex]
        val existingIndex = room.cards.indexOfFirst { it.uid == uid }

        val card = RoomCard(
            uid = uid,
            nickname = nickname,
            balance = balance,
            photoPath = photoPath,
            addedAt = System.currentTimeMillis()
        )

        val updatedCards = if (existingIndex >= 0) {
            room.cards.toMutableList().also { it[existingIndex] = card }
        } else {
            room.cards + card
        }

        val updatedRoom = room.copy(cards = updatedCards)
        _rooms.value = _rooms.value.toMutableList().also { it[roomIndex] = updatedRoom }

        if (_selectedRoom.value?.id == roomId) {
            _selectedRoom.value = updatedRoom
        }

        persistRooms()
        Log.i(TAG, "Added card $uid to room ${room.name}")
    }

    /**
     * Remove card from room
     */
    fun removeCardFromRoom(roomId: String, uid: String) {
        val roomIndex = _rooms.value.indexOfFirst { it.id == roomId }
        if (roomIndex < 0) return

        val room = _rooms.value[roomIndex]
        val updatedCards = room.cards.filter { it.uid != uid }
        val updatedRoom = room.copy(cards = updatedCards)

        _rooms.value = _rooms.value.toMutableList().also { it[roomIndex] = updatedRoom }

        if (_selectedRoom.value?.id == roomId) {
            _selectedRoom.value = updatedRoom
        }

        persistRooms()
    }

    /**
     * Set room photo
     */
    fun setRoomPhoto(roomId: String, photoPath: String?) {
        val roomIndex = _rooms.value.indexOfFirst { it.id == roomId }
        if (roomIndex < 0) return

        val room = _rooms.value[roomIndex]
        val updatedRoom = room.copy(photoPath = photoPath)

        _rooms.value = _rooms.value.toMutableList().also { it[roomIndex] = updatedRoom }

        if (_selectedRoom.value?.id == roomId) {
            _selectedRoom.value = updatedRoom
        }

        persistRooms()
    }

    /**
     * Set room notes
     */
    fun setRoomNotes(roomId: String, notes: String?) {
        val roomIndex = _rooms.value.indexOfFirst { it.id == roomId }
        if (roomIndex < 0) return

        val room = _rooms.value[roomIndex]
        val updatedRoom = room.copy(notes = notes)

        _rooms.value = _rooms.value.toMutableList().also { it[roomIndex] = updatedRoom }

        if (_selectedRoom.value?.id == roomId) {
            _selectedRoom.value = updatedRoom
        }

        persistRooms()
    }

    /**
     * Set machine photo
     */
    fun setMachinePhoto(roomId: String, bleAddress: String, photoPath: String?) {
        val roomIndex = _rooms.value.indexOfFirst { it.id == roomId }
        if (roomIndex < 0) return

        val room = _rooms.value[roomIndex]
        val machineIndex = room.machines.indexOfFirst { it.bleAddress == bleAddress }
        if (machineIndex < 0) return

        val machines = room.machines.toMutableList()
        machines[machineIndex] = machines[machineIndex].copy(photoPath = photoPath)

        val updatedRoom = room.copy(machines = machines)
        _rooms.value = _rooms.value.toMutableList().also { it[roomIndex] = updatedRoom }

        if (_selectedRoom.value?.id == roomId) {
            _selectedRoom.value = updatedRoom
        }

        persistRooms()
    }

    /**
     * Set card photo
     */
    fun setCardPhoto(roomId: String, uid: String, photoPath: String?) {
        val roomIndex = _rooms.value.indexOfFirst { it.id == roomId }
        if (roomIndex < 0) return

        val room = _rooms.value[roomIndex]
        val cardIndex = room.cards.indexOfFirst { it.uid == uid }
        if (cardIndex < 0) return

        val cards = room.cards.toMutableList()
        cards[cardIndex] = cards[cardIndex].copy(photoPath = photoPath)

        val updatedRoom = room.copy(cards = cards)
        _rooms.value = _rooms.value.toMutableList().also { it[roomIndex] = updatedRoom }

        if (_selectedRoom.value?.id == roomId) {
            _selectedRoom.value = updatedRoom
        }

        persistRooms()
    }

    /**
     * Set machine QR code and optional image
     */
    fun setMachineQR(roomId: String, bleAddress: String, qrCode: String, qrImagePath: String? = null) {
        val roomIndex = _rooms.value.indexOfFirst { it.id == roomId }
        if (roomIndex < 0) return

        val room = _rooms.value[roomIndex]
        val machineIndex = room.machines.indexOfFirst { it.bleAddress == bleAddress }
        if (machineIndex < 0) return

        val machines = room.machines.toMutableList()
        machines[machineIndex] = machines[machineIndex].copy(
            qrCode = qrCode,
            qrImagePath = qrImagePath
        )

        val updatedRoom = room.copy(machines = machines)
        _rooms.value = _rooms.value.toMutableList().also { it[roomIndex] = updatedRoom }

        if (_selectedRoom.value?.id == roomId) {
            _selectedRoom.value = updatedRoom
        }

        persistRooms()
        Log.i(TAG, "Set QR code for machine at $bleAddress in room ${room.name}")
    }

    /**
     * Get all machines from all rooms (for QR assignment picker)
     */
    fun getAllMachines(): List<Pair<LaundryRoom, MachineInfo>> {
        return _rooms.value.flatMap { room ->
            room.machines.map { machine -> room to machine }
        }
    }

    /**
     * Export room report for client
     */
    fun exportRoomReport(room: LaundryRoom): String {
        return buildString {
            appendLine("=" .repeat(50))
            appendLine("LAUNDRY ROOM AUDIT REPORT")
            appendLine("=" .repeat(50))
            appendLine()
            appendLine("Room: ${room.name}")
            room.location?.let { appendLine("Location: $it") }
            appendLine("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
            appendLine()

            appendLine("-".repeat(50))
            appendLine("MACHINES (${room.machineCount})")
            appendLine("-".repeat(50))
            room.machines.forEach { machine ->
                appendLine("  ${machine.displayName}")
                appendLine("    Type: ${machine.machineType}")
                appendLine("    BLE: ${machine.bleAddress}")
                appendLine("    Provider: ${machine.provider}")
                appendLine("    Status: ${machine.statusText}")
                appendLine()
            }

            appendLine("-".repeat(50))
            appendLine("NFC CARDS (${room.cardCount})")
            appendLine("-".repeat(50))
            room.cards.forEach { card ->
                appendLine("  ${card.nickname ?: card.uid}")
                appendLine("    UID: ${card.uid}")
                card.balance?.let {
                    appendLine("    Balance: \$${it / 100}.${String.format("%02d", it % 100)}")
                }
                appendLine()
            }

            room.notes?.let {
                appendLine("-".repeat(50))
                appendLine("NOTES")
                appendLine("-".repeat(50))
                appendLine(it)
            }

            appendLine()
            appendLine("=" .repeat(50))
            appendLine("Generated by LaunDRoid")
        }
    }

    private fun loadRooms() {
        try {
            if (roomsFile.exists()) {
                val json = roomsFile.readText()
                val array = JSONArray(json)
                val loadedRooms = mutableListOf<LaundryRoom>()

                for (i in 0 until array.length()) {
                    loadedRooms.add(LaundryRoom.fromJson(array.getJSONObject(i)))
                }

                _rooms.value = loadedRooms
                Log.i(TAG, "Loaded ${loadedRooms.size} laundry rooms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading rooms: ${e.message}")
        }
    }

    private fun persistRooms() {
        try {
            val array = JSONArray()
            _rooms.value.forEach { room ->
                array.put(room.toJson())
            }
            roomsFile.writeText(array.toString(2))
            Log.i(TAG, "Persisted ${_rooms.value.size} rooms")
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting rooms: ${e.message}")
        }
    }
}

enum class MachineType {
    WASHER,
    DRYER,
    COMBO,
    UNKNOWN
}

enum class MachineStatus {
    IDLE,
    IN_USE,
    AVAILABLE,
    OUT_OF_ORDER,
    OUT_OF_RANGE,
    CONNECTING,
    CONNECTED,
    UNKNOWN
}

data class MachineInfo(
    val bleAddress: String,
    val bleName: String,
    val machineNumber: Int,
    val machineType: MachineType,
    val provider: String,
    val lastSeen: Long,
    val rssi: Int,
    val nickname: String? = null,
    val inRange: Boolean = true,
    val status: MachineStatus = MachineStatus.IDLE,
    val photoPath: String? = null,
    val notes: String? = null,
    val qrCode: String? = null,
    val qrImagePath: String? = null,
    // Extended BLE data for offline viewing
    val serviceUuids: List<String> = emptyList(),
    val manufacturerDataHex: Map<String, String> = emptyMap(), // Company ID -> hex data
    val rawScanRecordHex: String? = null,
    val isConnectable: Boolean = true,
    val isCSC: Boolean = false,
    val firstDiscovered: Long = System.currentTimeMillis(),
    val addedToRoomAt: Long = System.currentTimeMillis(),
    val txPowerLevel: Int? = null,
    // GATT data (populated if we connect) - stored as serializable format
    val gattServices: List<StoredGattService> = emptyList()
) {
    val displayName: String
        get() = nickname ?: buildString {
            append(when (machineType) {
                MachineType.WASHER -> "Washer"
                MachineType.DRYER -> "Dryer"
                MachineType.COMBO -> "Combo"
                MachineType.UNKNOWN -> "Machine"
            })
            if (machineNumber > 0) {
                append(" #$machineNumber")
            }
        }

    val statusText: String
        get() = when (status) {
            MachineStatus.IDLE -> "Idle"
            MachineStatus.IN_USE -> "In Use"
            MachineStatus.AVAILABLE -> "Available"
            MachineStatus.OUT_OF_ORDER -> "Out of Order"
            MachineStatus.OUT_OF_RANGE -> "Out of Range"
            MachineStatus.CONNECTING -> "Connecting..."
            MachineStatus.CONNECTED -> "Connected"
            MachineStatus.UNKNOWN -> "Unknown"
        }

    val lastSeenFormatted: String
        get() = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(lastSeen))

    fun toJson(): JSONObject = JSONObject().apply {
        put("bleAddress", bleAddress)
        put("bleName", bleName)
        put("machineNumber", machineNumber)
        put("machineType", machineType.name)
        put("provider", provider)
        put("lastSeen", lastSeen)
        put("rssi", rssi)
        put("nickname", nickname)
        put("inRange", inRange)
        put("status", status.name)
        put("photoPath", photoPath)
        put("notes", notes)
        put("qrCode", qrCode)
        put("qrImagePath", qrImagePath)
        // Extended BLE data
        put("serviceUuids", JSONArray(serviceUuids))
        put("manufacturerDataHex", JSONObject(manufacturerDataHex))
        put("rawScanRecordHex", rawScanRecordHex)
        put("isConnectable", isConnectable)
        put("isCSC", isCSC)
        put("firstDiscovered", firstDiscovered)
        put("addedToRoomAt", addedToRoomAt)
        put("txPowerLevel", txPowerLevel)
        // GATT services
        val servicesArray = JSONArray()
        gattServices.forEach { servicesArray.put(it.toJson()) }
        put("storedGattServices", servicesArray)
    }

    companion object {
        fun fromJson(json: JSONObject): MachineInfo {
            // Parse service UUIDs
            val uuids = mutableListOf<String>()
            json.optJSONArray("serviceUuids")?.let { arr ->
                for (i in 0 until arr.length()) {
                    uuids.add(arr.getString(i))
                }
            }

            // Parse manufacturer data
            val mfgData = mutableMapOf<String, String>()
            json.optJSONObject("manufacturerDataHex")?.let { obj ->
                obj.keys().forEach { key ->
                    mfgData[key] = obj.getString(key)
                }
            }

            // Parse GATT services
            val services = mutableListOf<StoredGattService>()
            json.optJSONArray("storedGattServices")?.let { arr ->
                for (i in 0 until arr.length()) {
                    services.add(StoredGattService.fromJson(arr.getJSONObject(i)))
                }
            }

            return MachineInfo(
                bleAddress = json.getString("bleAddress"),
                bleName = json.getString("bleName"),
                machineNumber = json.getInt("machineNumber"),
                machineType = try { MachineType.valueOf(json.getString("machineType")) } catch (_: Exception) { MachineType.UNKNOWN },
                provider = json.getString("provider"),
                lastSeen = json.getLong("lastSeen"),
                rssi = json.optInt("rssi", -100),
                nickname = json.optString("nickname").takeIf { it.isNotEmpty() },
                inRange = json.optBoolean("inRange", false),
                status = try { MachineStatus.valueOf(json.optString("status", "UNKNOWN")) } catch (_: Exception) { MachineStatus.UNKNOWN },
                photoPath = json.optString("photoPath").takeIf { it.isNotEmpty() },
                notes = json.optString("notes").takeIf { it.isNotEmpty() },
                qrCode = json.optString("qrCode").takeIf { it.isNotEmpty() },
                qrImagePath = json.optString("qrImagePath").takeIf { it.isNotEmpty() },
                serviceUuids = uuids,
                manufacturerDataHex = mfgData,
                rawScanRecordHex = json.optString("rawScanRecordHex").takeIf { it.isNotEmpty() },
                isConnectable = json.optBoolean("isConnectable", true),
                isCSC = json.optBoolean("isCSC", false),
                firstDiscovered = json.optLong("firstDiscovered", System.currentTimeMillis()),
                addedToRoomAt = json.optLong("addedToRoomAt", System.currentTimeMillis()),
                txPowerLevel = if (json.has("txPowerLevel") && !json.isNull("txPowerLevel")) json.getInt("txPowerLevel") else null,
                gattServices = services
            )
        }
    }
}

/**
 * Stored GATT Service information for offline viewing (JSON serializable)
 */
data class StoredGattService(
    val uuid: String,
    val name: String?,
    val characteristics: List<StoredGattCharacteristic>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uuid", uuid)
        put("name", name)
        val charsArray = JSONArray()
        characteristics.forEach { charsArray.put(it.toJson()) }
        put("characteristics", charsArray)
    }

    companion object {
        fun fromJson(json: JSONObject): StoredGattService {
            val chars = mutableListOf<StoredGattCharacteristic>()
            json.optJSONArray("characteristics")?.let { arr ->
                for (i in 0 until arr.length()) {
                    chars.add(StoredGattCharacteristic.fromJson(arr.getJSONObject(i)))
                }
            }
            return StoredGattService(
                uuid = json.getString("uuid"),
                name = json.optString("name").takeIf { it.isNotEmpty() },
                characteristics = chars
            )
        }

        // Convert from GattManager's GattServiceInfo
        fun fromGattServiceInfo(service: GattServiceInfo): StoredGattService {
            return StoredGattService(
                uuid = service.uuid.toString(),
                name = getServiceName(service.uuid.toString()),
                characteristics = service.characteristics.map { StoredGattCharacteristic.fromGattCharacteristicInfo(it) }
            )
        }

        private fun getServiceName(uuid: String): String? {
            return when (uuid.uppercase().substring(4, 8)) {
                "1800" -> "Generic Access"
                "1801" -> "Generic Attribute"
                "180A" -> "Device Information"
                "180F" -> "Battery Service"
                "FFF0" -> "CSC Custom Service"
                "FFE0" -> "CSC Alt Service"
                else -> null
            }
        }
    }
}

/**
 * Stored GATT Characteristic information (JSON serializable)
 */
data class StoredGattCharacteristic(
    val uuid: String,
    val name: String?,
    val properties: Int,
    val valueHex: String?,
    val valueString: String?
) {
    val propertiesString: String
        get() = buildString {
            if (properties and 0x01 != 0) append("BROADCAST ")
            if (properties and 0x02 != 0) append("READ ")
            if (properties and 0x04 != 0) append("WRITE_NO_RESP ")
            if (properties and 0x08 != 0) append("WRITE ")
            if (properties and 0x10 != 0) append("NOTIFY ")
            if (properties and 0x20 != 0) append("INDICATE ")
            if (properties and 0x40 != 0) append("SIGNED_WRITE ")
            if (properties and 0x80 != 0) append("EXTENDED ")
        }.trim()

    fun toJson(): JSONObject = JSONObject().apply {
        put("uuid", uuid)
        put("name", name)
        put("properties", properties)
        put("valueHex", valueHex)
        put("valueString", valueString)
    }

    companion object {
        fun fromJson(json: JSONObject): StoredGattCharacteristic = StoredGattCharacteristic(
            uuid = json.getString("uuid"),
            name = json.optString("name").takeIf { it.isNotEmpty() },
            properties = json.optInt("properties", 0),
            valueHex = json.optString("valueHex").takeIf { it.isNotEmpty() },
            valueString = json.optString("valueString").takeIf { it.isNotEmpty() }
        )

        // Convert from GattManager's GattCharacteristicInfo
        fun fromGattCharacteristicInfo(char: GattCharacteristicInfo): StoredGattCharacteristic {
            val valueHex = char.value?.joinToString("") { "%02X".format(it) }
            val valueStr = char.value?.let { bytes ->
                try {
                    String(bytes, Charsets.UTF_8).filter { it.isLetterOrDigit() || it.isWhitespace() || it in ".,!?-_" }
                } catch (_: Exception) { null }
            }
            return StoredGattCharacteristic(
                uuid = char.uuid.toString(),
                name = getCharacteristicName(char.uuid.toString()),
                properties = char.properties,
                valueHex = valueHex,
                valueString = valueStr
            )
        }

        private fun getCharacteristicName(uuid: String): String? {
            return when (uuid.uppercase().substring(4, 8)) {
                "2A00" -> "Device Name"
                "2A01" -> "Appearance"
                "2A04" -> "Peripheral Params"
                "2A19" -> "Battery Level"
                "2A24" -> "Model Number"
                "2A25" -> "Serial Number"
                "2A26" -> "Firmware Rev"
                "2A27" -> "Hardware Rev"
                "2A28" -> "Software Rev"
                "2A29" -> "Manufacturer"
                "FFF1" -> "CSC Command"
                "FFF2" -> "CSC Response"
                "FFF3" -> "CSC Data"
                "FFE1" -> "CSC Alt Command"
                else -> null
            }
        }
    }
}

/**
 * Saved NFC card reference for a laundry room
 */
data class RoomCard(
    val uid: String,
    val nickname: String?,
    val balance: Int?,
    val photoPath: String?,
    val addedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("nickname", nickname)
        put("balance", balance)
        put("photoPath", photoPath)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): RoomCard = RoomCard(
            uid = json.getString("uid"),
            nickname = json.optString("nickname").takeIf { it.isNotEmpty() },
            balance = if (json.isNull("balance")) null else json.optInt("balance"),
            photoPath = json.optString("photoPath").takeIf { it.isNotEmpty() },
            addedAt = json.getLong("addedAt")
        )
    }
}

data class LaundryRoom(
    val id: String,
    val name: String,
    val location: String?,
    val machines: List<MachineInfo>,
    val cards: List<RoomCard> = emptyList(),
    val photoPath: String? = null,
    val notes: String? = null,
    val createdAt: Long
) {
    val machineCount: Int get() = machines.size
    val inRangeCount: Int get() = machines.count { it.inRange }
    val washerCount: Int get() = machines.count { it.machineType == MachineType.WASHER }
    val dryerCount: Int get() = machines.count { it.machineType == MachineType.DRYER }
    val cardCount: Int get() = cards.size

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("location", location)
        put("photoPath", photoPath)
        put("notes", notes)
        put("createdAt", createdAt)

        val machinesArray = JSONArray()
        machines.forEach { machinesArray.put(it.toJson()) }
        put("machines", machinesArray)

        val cardsArray = JSONArray()
        cards.forEach { cardsArray.put(it.toJson()) }
        put("cards", cardsArray)
    }

    companion object {
        fun fromJson(json: JSONObject): LaundryRoom {
            val machinesArray = json.getJSONArray("machines")
            val machines = mutableListOf<MachineInfo>()
            for (i in 0 until machinesArray.length()) {
                machines.add(MachineInfo.fromJson(machinesArray.getJSONObject(i)))
            }

            val cardsArray = json.optJSONArray("cards") ?: JSONArray()
            val cards = mutableListOf<RoomCard>()
            for (i in 0 until cardsArray.length()) {
                cards.add(RoomCard.fromJson(cardsArray.getJSONObject(i)))
            }

            return LaundryRoom(
                id = json.getString("id"),
                name = json.getString("name"),
                location = json.optString("location").takeIf { it.isNotEmpty() },
                machines = machines,
                cards = cards,
                photoPath = json.optString("photoPath").takeIf { it.isNotEmpty() },
                notes = json.optString("notes").takeIf { it.isNotEmpty() },
                createdAt = json.getLong("createdAt")
            )
        }
    }
}
