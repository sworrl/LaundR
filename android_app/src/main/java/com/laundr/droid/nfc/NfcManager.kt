package com.laundr.droid.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

/**
 * NFC Manager for reading and analyzing MIFARE Classic cards (CSC laundry cards)
 */
class NfcManager {

    private val _nfcState = MutableStateFlow(NfcState.IDLE)
    val nfcState: StateFlow<NfcState> = _nfcState.asStateFlow()

    private val _currentCard = MutableStateFlow<MifareCardData?>(null)
    val currentCard: StateFlow<MifareCardData?> = _currentCard.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _savedCards = MutableStateFlow<List<MifareCardData>>(emptyList())
    val savedCards: StateFlow<List<MifareCardData>> = _savedCards.asStateFlow()

    // Keys loaded from dictionary file (Proxmark3 + Flipper combined)
    private var knownKeys: List<ByteArray> = emptyList()
    private var keysLoaded = false

    // Brute force state
    private val _bruteForceProgress = MutableStateFlow(BruteForceProgress())
    val bruteForceProgress: StateFlow<BruteForceProgress> = _bruteForceProgress.asStateFlow()
    private var bruteForceActive = false
    private var currentTag: Tag? = null

    // Verbose scanning progress
    private val _scanProgress = MutableStateFlow(ScanProgress())
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    /**
     * Load MIFARE keys from assets dictionary file
     * Combined dictionary from Proxmark3 and FlipperZero (~2500 keys)
     */
    fun loadKeyDictionary(context: Context) {
        if (keysLoaded) return

        try {
            val keys = mutableListOf<ByteArray>()
            context.assets.open("mf_keys.dic").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim().uppercase()
                    // Skip comments and empty lines, validate hex format
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.length == 12) {
                        try {
                            val keyBytes = trimmed.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            if (keyBytes.size == 6) {
                                keys.add(keyBytes)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            knownKeys = keys
            keysLoaded = true
            log("Loaded ${knownKeys.size} keys from dictionary")
        } catch (e: Exception) {
            log("ERROR loading key dictionary: ${e.message}")
            // Fallback to basic defaults
            knownKeys = listOf(
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
                byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
                byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()),
                byteArrayOf(0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte()),
                byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte()),
            )
            keysLoaded = true
            log("Using ${knownKeys.size} fallback keys")
        }
    }

    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _log.value = _log.value + "[$timestamp] $message"
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    fun getLogText(): String = _log.value.joinToString("\n")

    fun enableForegroundDispatch(activity: Activity, adapter: NfcAdapter?) {
        adapter?.let { nfc ->
            val intent = Intent(activity, activity.javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                activity, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val filters = arrayOf(
                IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
                IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
            )
            val techLists = arrayOf(
                arrayOf(MifareClassic::class.java.name),
                arrayOf(NfcA::class.java.name)
            )
            nfc.enableForegroundDispatch(activity, pendingIntent, filters, techLists)
            log("NFC foreground dispatch enabled")
            _nfcState.value = NfcState.WAITING_FOR_TAG
        }
    }

    fun disableForegroundDispatch(activity: Activity, adapter: NfcAdapter?) {
        adapter?.disableForegroundDispatch(activity)
        _nfcState.value = NfcState.IDLE
    }

    suspend fun processTag(tag: Tag): MifareCardData? {
        _nfcState.value = NfcState.READING
        log("=== TAG DETECTED ===")

        val uid = tag.id.toHexString()
        log("UID: $uid")

        val techList = tag.techList.map { it.substringAfterLast('.') }
        log("Technologies: ${techList.joinToString(", ")}")

        // Check if it's MIFARE Classic
        if (techList.contains("MifareClassic")) {
            return readMifareClassic(tag, uid)
        } else {
            log("Not a MIFARE Classic card - limited read")
            _nfcState.value = NfcState.WAITING_FOR_TAG
            return MifareCardData(
                uid = uid,
                type = "Unknown (${techList.firstOrNull() ?: "?"})",
                size = 0,
                sectors = emptyList(),
                isCSC = false,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    private fun readMifareClassic(tag: Tag, uid: String): MifareCardData? {
        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            log("ERROR: Failed to get MIFARE Classic interface")
            _nfcState.value = NfcState.ERROR
            return null
        }

        // Clear session keys for new card (unless same UID)
        val currentUid = _currentCard.value?.uid
        if (currentUid != uid) {
            sessionFoundKeys.clear()
            log("New card detected - cleared session keys")
        }

        try {
            mifare.connect()
            log("Connected to MIFARE Classic")

            val cardType = when (mifare.type) {
                MifareClassic.TYPE_CLASSIC -> "MIFARE Classic"
                MifareClassic.TYPE_PLUS -> "MIFARE Plus"
                MifareClassic.TYPE_PRO -> "MIFARE Pro"
                else -> "MIFARE Unknown"
            }

            val size = when (mifare.size) {
                MifareClassic.SIZE_MINI -> "320 bytes (Mini)"
                MifareClassic.SIZE_1K -> "1K"
                MifareClassic.SIZE_2K -> "2K"
                MifareClassic.SIZE_4K -> "4K"
                else -> "${mifare.size} bytes"
            }

            log("Type: $cardType $size")
            log("Sectors: ${mifare.sectorCount}")
            log("Blocks: ${mifare.blockCount}")

            val sectors = mutableListOf<SectorData>()
            var isCSC = false

            // Read each sector
            for (sectorIndex in 0 until mifare.sectorCount) {
                val sectorData = readSector(mifare, sectorIndex)
                sectors.add(sectorData)

                // Check for CSC signatures
                if (sectorData.blocks.any { block ->
                        block.data.contains("CSC") ||
                        block.data.startsWith("4353") || // "CS" in hex
                        block.data.contains("4C41554E44") // "LAUND" in hex
                    }) {
                    isCSC = true
                }
            }

            if (isCSC) {
                log("*** CSC LAUNDRY CARD DETECTED ***")
            }

            mifare.close()

            val card = MifareCardData(
                uid = uid,
                type = "$cardType $size",
                size = mifare.size,
                sectors = sectors,
                isCSC = isCSC,
                timestamp = System.currentTimeMillis()
            )

            _currentCard.value = card
            _nfcState.value = NfcState.READ_COMPLETE
            log("=== READ COMPLETE ===")

            return card

        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            _nfcState.value = NfcState.ERROR
            try { mifare.close() } catch (_: Exception) {}
            return null
        }
    }

    // Track keys found during this session for reuse across sectors
    // Using a list to properly handle ByteArray comparison
    private val sessionFoundKeys = mutableListOf<ByteArray>()

    private fun addSessionKey(key: ByteArray) {
        // Check if key already exists using contentEquals
        if (sessionFoundKeys.none { it.contentEquals(key) }) {
            sessionFoundKeys.add(key.copyOf())
            log("★ Added key ${key.toHexString()} to session cache (${sessionFoundKeys.size} keys)")
        }
    }

    /**
     * Attack strategies for key sweep randomization
     */
    enum class AttackStrategy {
        FORWARD,        // Standard order
        REVERSE,        // Backwards through dictionary
        MIDDLE_OUT,     // Start in middle, alternate outward
        RANDOM_SHUFFLE, // Full random shuffle
        CHUNKED,        // Randomize chunks but keep chunk order
        INTERLEAVED     // Alternate between start and end
    }

    private var lastStrategy: AttackStrategy? = null

    private fun buildPrioritizedKeyList(): Pair<List<ByteArray>, AttackStrategy> {
        val prioritized = mutableListOf<ByteArray>()

        // ALWAYS add default keys FIRST - they're most common
        val defaultKeys = listOf(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()),
            byteArrayOf(0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte()),
        )

        defaultKeys.forEach { key ->
            if (prioritized.none { it.contentEquals(key) }) {
                prioritized.add(key)
            }
        }

        // Then session found keys (key reuse is VERY common in laundry cards)
        sessionFoundKeys.forEach { key ->
            if (prioritized.none { it.contentEquals(key) }) {
                prioritized.add(key)
            }
        }

        // Collect dictionary keys (to be reordered)
        val dictKeys = knownKeys.filter { key ->
            prioritized.none { it.contentEquals(key) }
        }.toMutableList()

        // Pick a random attack strategy (avoid repeating last one if possible)
        val strategies = AttackStrategy.values().filter { it != lastStrategy }
        val strategy = strategies.random()
        lastStrategy = strategy

        // Apply strategy to dictionary keys
        val reorderedDict = when (strategy) {
            AttackStrategy.FORWARD -> dictKeys

            AttackStrategy.REVERSE -> dictKeys.reversed()

            AttackStrategy.MIDDLE_OUT -> {
                val result = mutableListOf<ByteArray>()
                val mid = dictKeys.size / 2
                var left = mid - 1
                var right = mid
                while (left >= 0 || right < dictKeys.size) {
                    if (right < dictKeys.size) result.add(dictKeys[right++])
                    if (left >= 0) result.add(dictKeys[left--])
                }
                result
            }

            AttackStrategy.RANDOM_SHUFFLE -> dictKeys.shuffled()

            AttackStrategy.CHUNKED -> {
                val chunkSize = maxOf(dictKeys.size / 8, 50)
                dictKeys.chunked(chunkSize).shuffled().flatten()
            }

            AttackStrategy.INTERLEAVED -> {
                val result = mutableListOf<ByteArray>()
                var start = 0
                var end = dictKeys.size - 1
                while (start <= end) {
                    result.add(dictKeys[start++])
                    if (start <= end) result.add(dictKeys[end--])
                }
                result
            }
        }

        prioritized.addAll(reorderedDict)
        return Pair(prioritized, strategy)
    }

    private fun readSector(mifare: MifareClassic, sectorIndex: Int): SectorData {
        val blockCount = mifare.getBlockCountInSector(sectorIndex)
        val firstBlock = mifare.sectorToBlock(sectorIndex)
        val blocks = mutableListOf<BlockData>()
        var keyAFound: String? = null
        var keyBFound: String? = null
        var keyABytes: ByteArray? = null
        var keyBBytes: ByteArray? = null
        var authAttempts = 0  // Actual hardware auth attempts
        val totalSectors = mifare.sectorCount
        val startTime = System.currentTimeMillis()

        val (prioritizedKeys, attackStrategy) = buildPrioritizedKeyList()
        val sessionKeyCount = sessionFoundKeys.size

        // Pre-convert keys to hex for display (do this ONCE, not in loop)
        val keyHexCache = prioritizedKeys.map { it.toHexString() }

        val strategyName = when (attackStrategy) {
            AttackStrategy.FORWARD -> "→FWD"
            AttackStrategy.REVERSE -> "←REV"
            AttackStrategy.MIDDLE_OUT -> "◇MID"
            AttackStrategy.RANDOM_SHUFFLE -> "⚄RND"
            AttackStrategy.CHUNKED -> "▦CHK"
            AttackStrategy.INTERLEAVED -> "↔INT"
        }
        log("Sector $sectorIndex: $strategyName sweep, ${prioritizedKeys.size} keys...")

        // Update progress - starting new sector
        _scanProgress.value = ScanProgress(
            isScanning = true,
            currentSector = sectorIndex,
            totalSectors = totalSectors,
            currentKey = 0,
            totalKeys = prioritizedKeys.size,
            currentKeyHex = "",
            keysPerSecond = 0f,
            status = "S$sectorIndex $strategyName sweep...",
            foundKeysCount = sessionKeyCount
        )

        // UI update interval - update every key for real-time display
        val uiUpdateInterval = 1

        // PHASE 1: Find Key A
        for ((index, key) in prioritizedKeys.withIndex()) {
            if (keyAFound != null) break

            authAttempts++
            val keyHex = keyHexCache[index]

            // Batch UI updates for speed - only update every N keys
            if (authAttempts % uiUpdateInterval == 0 || index < 10) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                val authRate = if (elapsed > 0.01f) authAttempts / elapsed else 0f

                _scanProgress.value = _scanProgress.value.copy(
                    currentKey = index + 1,
                    currentKeyHex = keyHex,
                    keysPerSecond = authRate,
                    status = "S$sectorIndex $strategyName A: $keyHex"
                )
            }

            try {
                if (mifare.authenticateSectorWithKeyA(sectorIndex, key)) {
                    keyAFound = keyHex
                    keyABytes = key.copyOf()
                    addSessionKey(key)

                    val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                    val authRate = if (elapsed > 0.01f) authAttempts / elapsed else 0f
                    log("Sector $sectorIndex: KEY A = $keyAFound (${authAttempts} tries, ${authRate.toInt()}/s)")
                    _scanProgress.value = _scanProgress.value.copy(
                        status = "★ S$sectorIndex KEY A: $keyAFound",
                        foundKeysCount = sessionFoundKeys.size,
                        keysPerSecond = authRate
                    )
                }
            } catch (e: Exception) {
                if (e.message?.contains("Tag was lost") == true) {
                    log("ERROR: Card removed after $authAttempts attempts!")
                    _nfcState.value = NfcState.ERROR
                    throw e
                }
                // Other exceptions = failed auth, continue
            }
        }

        // PHASE 2: Find Key B ONLY if KeyA wasn't found
        if (keyAFound == null) {
            for ((index, key) in prioritizedKeys.withIndex()) {
                if (keyBFound != null) break

                authAttempts++
                val keyHex = keyHexCache[index]

                // Batch UI updates
                if (authAttempts % uiUpdateInterval == 0) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                    val authRate = if (elapsed > 0.01f) authAttempts / elapsed else 0f

                    _scanProgress.value = _scanProgress.value.copy(
                        currentKey = index + 1,
                        currentKeyHex = keyHex,
                        keysPerSecond = authRate,
                        status = "S$sectorIndex $strategyName B: $keyHex"
                    )
                }

                try {
                    if (mifare.authenticateSectorWithKeyB(sectorIndex, key)) {
                        keyBFound = keyHex
                        keyBBytes = key.copyOf()
                        addSessionKey(key)

                        val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                        val authRate = if (elapsed > 0.01f) authAttempts / elapsed else 0f
                        log("Sector $sectorIndex: KEY B = $keyBFound (${authAttempts} tries, ${authRate.toInt()}/s)")
                        _scanProgress.value = _scanProgress.value.copy(
                            status = "★ S$sectorIndex KEY B: $keyBFound",
                            foundKeysCount = sessionFoundKeys.size,
                            keysPerSecond = authRate
                        )
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("Tag was lost") == true) {
                        log("ERROR: Card removed!")
                        _nfcState.value = NfcState.ERROR
                        throw e
                    }
                }
            }
        } else {
            // KeyA found - try same key for KeyB (common pattern)
            try {
                if (mifare.authenticateSectorWithKeyB(sectorIndex, keyABytes!!)) {
                    keyBFound = keyAFound
                    keyBBytes = keyABytes
                    log("Sector $sectorIndex: KEY B = $keyBFound (same as A)")
                }
            } catch (_: Exception) {}
        }

        // PHASE 3: Read blocks if we found any key
        val authenticated = keyAFound != null || keyBFound != null

        if (authenticated) {
            log("Sector $sectorIndex: CRACKED! KeyA=${keyAFound ?: "?"} KeyB=${keyBFound ?: "?"}")

            // Re-authenticate with found key to read blocks
            val readKey = keyABytes ?: keyBBytes!!
            val useKeyA = keyABytes != null

            try {
                val authSuccess = if (useKeyA) {
                    mifare.authenticateSectorWithKeyA(sectorIndex, readKey)
                } else {
                    mifare.authenticateSectorWithKeyB(sectorIndex, readKey)
                }

                if (authSuccess) {
                    for (blockOffset in 0 until blockCount) {
                        val blockIndex = firstBlock + blockOffset
                        try {
                            val data = mifare.readBlock(blockIndex)
                            val isTrailer = blockOffset == blockCount - 1
                            blocks.add(BlockData(
                                index = blockIndex,
                                data = data.toHexString(),
                                isTrailer = isTrailer,
                                readable = true
                            ))
                        } catch (e: Exception) {
                            log("  Block $blockIndex: Read failed - ${e.message}")
                            blocks.add(BlockData(
                                index = blockIndex,
                                data = "?".repeat(32),
                                isTrailer = blockOffset == blockCount - 1,
                                readable = false
                            ))
                        }
                    }
                } else {
                    log("Sector $sectorIndex: Re-auth failed for read")
                    addPlaceholderBlocks(blocks, firstBlock, blockCount)
                }
            } catch (e: Exception) {
                log("Sector $sectorIndex: Read error - ${e.message}")
                addPlaceholderBlocks(blocks, firstBlock, blockCount)
            }
        } else {
            log("Sector $sectorIndex: No keys found")
            addPlaceholderBlocks(blocks, firstBlock, blockCount)
        }

        return SectorData(
            index = sectorIndex,
            blocks = blocks,
            keyA = keyAFound,
            keyB = keyBFound,
            authenticated = authenticated
        )
    }

    private fun addPlaceholderBlocks(blocks: MutableList<BlockData>, firstBlock: Int, blockCount: Int) {
        for (blockOffset in 0 until blockCount) {
            blocks.add(BlockData(
                index = firstBlock + blockOffset,
                data = "?".repeat(32),
                isTrailer = blockOffset == blockCount - 1,
                readable = false
            ))
        }
    }

    fun saveCard(card: MifareCardData) {
        _savedCards.value = _savedCards.value + card
        log("Card saved: ${card.uid}")
    }

    fun exportToFlipperFormat(card: MifareCardData, outputDir: File): File? {
        try {
            val filename = "laundroid_${card.uid}_${System.currentTimeMillis()}.nfc"
            val file = File(outputDir, filename)

            val content = buildString {
                appendLine("Filetype: Flipper NFC device")
                appendLine("Version: 4")
                appendLine("# Generated by LaunDRoid")
                appendLine("Device type: MIFARE Classic 1K")
                appendLine("UID: ${card.uid.chunked(2).joinToString(" ")}")
                appendLine("ATQA: 00 04")
                appendLine("SAK: 08")
                appendLine("# Blocks")

                for (sector in card.sectors) {
                    for (block in sector.blocks) {
                        val blockData = if (block.readable) {
                            block.data.chunked(2).joinToString(" ")
                        } else {
                            "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"
                        }
                        appendLine("Block ${block.index}: $blockData")
                    }
                }
            }

            file.writeText(content)
            log("Exported to Flipper format: $filename")
            return file

        } catch (e: Exception) {
            log("ERROR exporting: ${e.message}")
            return null
        }
    }

    /**
     * Comprehensive CSC Card Analysis
     * Based on Flipper Zero LaundR implementation
     *
     * CSC ServiceWorks Card Format:
     * - Block 0: UID (4 bytes) + BCC + SAK + ATQA + Manufacturer data
     * - Block 1: ASCII data (may contain provider name like "UBESTWASH")
     * - Block 2: CSC signature (0x0101) + XOR checksum + metadata
     * - Block 4: Primary balance (value block format)
     * - Block 8: Backup balance (value block format)
     * - Block 9: Usage counter
     * - Block 13: Site code
     */
    fun analyzeCSCCard(card: MifareCardData): CSCAnalysis {
        var balance: Int? = null
        var balanceBackup: Int? = null
        var balanceValid = false
        var counter: Int? = null
        var provider: String? = null
        var siteCode: String? = null
        var lastTransaction: String? = null
        val transactions = mutableListOf<CSCTransaction>()

        // Helper to get block data
        fun getBlock(blockIndex: Int): String? {
            val sectorIndex = blockIndex / 4
            val blockOffset = blockIndex % 4
            return card.sectors.getOrNull(sectorIndex)?.blocks?.getOrNull(blockOffset)
                ?.takeIf { it.readable }?.data
        }

        // Block 1: ASCII provider name
        getBlock(1)?.let { data ->
            val ascii = data.hexToAscii()
            when {
                ascii.contains("UBESTWASH", ignoreCase = true) -> provider = "U-Best Wash"
                ascii.contains("CSC", ignoreCase = true) -> provider = "CSC ServiceWorks"
                ascii.contains("SQ", ignoreCase = true) -> provider = "Speed Queen"
            }
        }

        // Block 2: CSC signature and metadata
        getBlock(2)?.let { data ->
            if (data.length >= 32) {
                // Bytes 0-1: CSC signature (0x0101)
                if (data.startsWith("0101")) {
                    provider = provider ?: "CSC ServiceWorks"

                    // Bytes 2-3: Unknown (possibly card type)
                    // Bytes 4-5: Site code (little-endian)
                    try {
                        val siteLo = data.substring(8, 10).toInt(16)
                        val siteHi = data.substring(10, 12).toInt(16)
                        val site = siteLo or (siteHi shl 8)
                        if (site > 0) siteCode = site.toString()
                    } catch (_: Exception) {}

                    // Byte 14: XOR checksum of bytes 0-13
                    // We can validate this to confirm CSC format
                }
            }
        }

        // Block 4: Primary balance (MIFARE value block format)
        // Format: [val_lo][val_hi][00][00][~val_lo][~val_hi][FF][FF][val_lo][val_hi][00][00][addr][~addr][addr][~addr]
        // CSC simplified: [val_lo][val_hi][cnt_lo][cnt_hi][~val_lo][~val_hi][~cnt_lo][~cnt_hi]...
        getBlock(4)?.let { data ->
            if (data.length >= 16) {
                val parsed = parseValueBlock(data)
                if (parsed != null) {
                    balance = parsed.first
                    counter = parsed.second
                    balanceValid = true
                    log("Block 4 Balance: ${parsed.first} cents, Counter: ${parsed.second}")
                }
            }
        }

        // Block 8: Backup balance (should match block 4)
        getBlock(8)?.let { data ->
            if (data.length >= 16) {
                val parsed = parseValueBlock(data)
                if (parsed != null) {
                    balanceBackup = parsed.first
                    log("Block 8 Backup: ${parsed.first} cents")
                }
            }
        }

        // Block 9: Transaction counter / usage data
        getBlock(9)?.let { data ->
            if (data.length >= 8) {
                try {
                    val usageLo = data.substring(0, 2).toInt(16)
                    val usageHi = data.substring(2, 4).toInt(16)
                    val usage = usageLo or (usageHi shl 8)
                    if (counter == null && usage > 0) counter = usage
                    log("Block 9 Usage: $usage")
                } catch (_: Exception) {}
            }
        }

        // Block 13: Site/Location code
        getBlock(13)?.let { data ->
            if (data.length >= 8 && siteCode == null) {
                try {
                    val ascii = data.hexToAscii()
                    if (ascii.isNotBlank() && ascii.length >= 2) {
                        siteCode = ascii.trim()
                    }
                } catch (_: Exception) {}
            }
        }

        // If balance not found in value block format, try raw parsing
        if (balance == null) {
            getBlock(4)?.let { data ->
                if (data.length >= 4) {
                    try {
                        val lo = data.substring(0, 2).toInt(16)
                        val hi = data.substring(2, 4).toInt(16)
                        balance = lo or (hi shl 8)
                        log("Block 4 Raw: $balance cents (unvalidated)")
                    } catch (_: Exception) {}
                }
            }
        }

        // Validate balance consistency
        if (balance != null && balanceBackup != null && balance != balanceBackup) {
            log("WARNING: Balance mismatch! Primary=$balance, Backup=$balanceBackup")
        }

        // Parse transaction history from other blocks (if available)
        // Blocks 5, 6 may contain transaction logs on some cards
        for (blockIdx in listOf(5, 6, 10)) {
            getBlock(blockIdx)?.let { data ->
                if (data.length >= 8 && !data.startsWith("00000000") && !data.startsWith("????????")) {
                    val tx = parseTransaction(data, blockIdx)
                    if (tx != null) transactions.add(tx)
                }
            }
        }

        val isCSC = provider != null || card.isCSC

        return CSCAnalysis(
            estimatedBalance = balance,
            balanceValidated = balanceValid,
            balanceBackup = balanceBackup,
            transactionCounter = counter,
            provider = provider ?: if (isCSC) "Unknown Laundry" else null,
            siteCode = siteCode,
            lastTransaction = lastTransaction,
            transactions = transactions,
            vulnerableToReplay = true
        )
    }

    /**
     * Parse MIFARE value block format
     * Returns Pair(value, counter) or null if invalid
     */
    private fun parseValueBlock(hexData: String): Pair<Int, Int>? {
        if (hexData.length < 16) return null

        try {
            // Value block format (CSC variant):
            // Bytes 0-1: Value (little-endian)
            // Bytes 2-3: Counter (little-endian)
            // Bytes 4-5: ~Value (inverted)
            // Bytes 6-7: ~Counter (inverted)
            // Bytes 8-11: Copy of value/counter
            // Bytes 12-15: Address byte repeated

            val valLo = hexData.substring(0, 2).toInt(16)
            val valHi = hexData.substring(2, 4).toInt(16)
            val cntLo = hexData.substring(4, 6).toInt(16)
            val cntHi = hexData.substring(6, 8).toInt(16)
            val valInvLo = hexData.substring(8, 10).toInt(16)
            val valInvHi = hexData.substring(10, 12).toInt(16)
            val cntInvLo = hexData.substring(12, 14).toInt(16)
            val cntInvHi = hexData.substring(14, 16).toInt(16)

            val value = valLo or (valHi shl 8)
            val count = cntLo or (cntHi shl 8)
            val valueInv = valInvLo or (valInvHi shl 8)
            val countInv = cntInvLo or (cntInvHi shl 8)

            // Validate: value XOR ~value should = 0xFFFF
            val valueValid = (value xor valueInv) == 0xFFFF
            val countValid = (count xor countInv) == 0xFFFF

            if (valueValid) {
                return Pair(value, if (countValid) count else 0)
            }

            // Try standard MIFARE value block format
            // 4 bytes value, 4 bytes ~value, 4 bytes value, 1 byte addr, 1 byte ~addr, 1 byte addr, 1 byte ~addr
            if (hexData.length >= 32) {
                val v0 = hexData.substring(0, 8).toLong(16)
                val v1 = hexData.substring(8, 16).toLong(16)
                val v2 = hexData.substring(16, 24).toLong(16)

                // Convert to little-endian
                val val0 = ((v0 and 0xFF) shl 24) or ((v0 shr 8 and 0xFF) shl 16) or
                        ((v0 shr 16 and 0xFF) shl 8) or (v0 shr 24 and 0xFF)

                if ((val0 xor (v1 and 0xFFFFFFFFL)) == 0xFFFFFFFFL) {
                    return Pair(val0.toInt(), 0)
                }
            }

        } catch (e: Exception) {
            log("Value block parse error: ${e.message}")
        }

        return null
    }

    private fun parseTransaction(hexData: String, blockIndex: Int): CSCTransaction? {
        if (hexData.length < 8) return null

        try {
            // Try to extract meaningful data
            val bytes = hexData.chunked(2).map { it.toInt(16) }
            if (bytes.all { it == 0 || it == 255 }) return null

            // Amount might be in first 2 bytes
            val amount = bytes[0] or (bytes[1] shl 8)
            if (amount in 1..65535) {
                return CSCTransaction(
                    blockIndex = blockIndex,
                    rawData = hexData,
                    amount = amount
                )
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Start brute force attack - tries all keys at maximum speed
     * This is FAST - modern phones can do 100+ auth attempts per second
     */
    fun startBruteForce(tag: Tag, targetSector: Int, onComplete: (String?, String?) -> Unit) {
        if (bruteForceActive) {
            log("Brute force already running!")
            return
        }

        currentTag = tag
        bruteForceActive = true
        val startTime = System.currentTimeMillis()

        _bruteForceProgress.value = BruteForceProgress(
            isRunning = true,
            currentKey = 0,
            totalKeys = knownKeys.size * 2, // A and B
            sector = targetSector,
            keysPerSecond = 0f,
            keyAFound = null,
            keyBFound = null
        )

        log("=== BRUTE FORCE STARTED ===")
        log("Target: Sector $targetSector")
        log("Dictionary: ${knownKeys.size} keys")

        Thread {
            val mifare = MifareClassic.get(tag)
            var keyAFound: String? = null
            var keyBFound: String? = null
            var keysTried = 0
            var lastSpeedUpdate = startTime

            try {
                mifare.connect()

                for (key in knownKeys) {
                    if (!bruteForceActive) break

                    // Try Key A
                    if (keyAFound == null) {
                        try {
                            keysTried++
                            if (mifare.authenticateSectorWithKeyA(targetSector, key)) {
                                keyAFound = key.toHexString()
                                log("KEY A CRACKED: $keyAFound")
                                _bruteForceProgress.value = _bruteForceProgress.value.copy(keyAFound = keyAFound)
                            }
                        } catch (_: Exception) {
                            // Reconnect if needed
                            try {
                                if (!mifare.isConnected) mifare.connect()
                            } catch (_: Exception) {}
                        }
                    }

                    // Try Key B
                    if (keyBFound == null) {
                        try {
                            keysTried++
                            if (mifare.authenticateSectorWithKeyB(targetSector, key)) {
                                keyBFound = key.toHexString()
                                log("KEY B CRACKED: $keyBFound")
                                _bruteForceProgress.value = _bruteForceProgress.value.copy(keyBFound = keyBFound)
                            }
                        } catch (_: Exception) {
                            try {
                                if (!mifare.isConnected) mifare.connect()
                            } catch (_: Exception) {}
                        }
                    }

                    // Both found - we're done
                    if (keyAFound != null && keyBFound != null) break

                    // Update progress every 50 keys
                    if (keysTried % 50 == 0) {
                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastSpeedUpdate) / 1000f
                        val speed = if (elapsed > 0) 50f / elapsed else 0f
                        lastSpeedUpdate = now

                        _bruteForceProgress.value = _bruteForceProgress.value.copy(
                            currentKey = keysTried,
                            keysPerSecond = speed
                        )
                    }
                }

                mifare.close()

            } catch (e: Exception) {
                log("Brute force error: ${e.message}")
                try { mifare.close() } catch (_: Exception) {}
            }

            val totalTime = (System.currentTimeMillis() - startTime) / 1000f
            val avgSpeed = keysTried / totalTime

            log("=== BRUTE FORCE COMPLETE ===")
            log("Tried: $keysTried keys in %.1f seconds".format(totalTime))
            log("Speed: %.1f keys/sec".format(avgSpeed))
            if (keyAFound != null) log("Key A: $keyAFound")
            if (keyBFound != null) log("Key B: $keyBFound")
            if (keyAFound == null && keyBFound == null) log("NO KEYS FOUND - key not in dictionary")

            bruteForceActive = false
            _bruteForceProgress.value = BruteForceProgress(
                isRunning = false,
                currentKey = keysTried,
                totalKeys = knownKeys.size * 2,
                sector = targetSector,
                keysPerSecond = avgSpeed,
                keyAFound = keyAFound,
                keyBFound = keyBFound
            )

            onComplete(keyAFound, keyBFound)
        }.start()
    }

    fun stopBruteForce() {
        bruteForceActive = false
        log("Brute force stopped by user")
    }

    fun isBruteForceActive() = bruteForceActive

    /**
     * TRUE RANDOM BRUTE FORCE - Educational/PoC Mode
     *
     * Attempts random 48-bit keys without repetition.
     * Total keyspace: 2^48 = 281,474,976,710,656 keys (281 trillion)
     *
     * At 100 keys/sec on Android NFC:
     * - 1 million keys = ~2.8 hours
     * - 1 billion keys = ~116 days
     * - Full keyspace = ~89,000 years
     *
     * This demonstrates why dictionary attacks and exploits (Proxmark nested/darkside)
     * are necessary for practical attacks. This mode is for educational purposes.
     */
    private val triedKeys = mutableSetOf<Long>()
    private var randomBruteForceActive = false
    private val _randomBruteForceProgress = MutableStateFlow(RandomBruteForceProgress())
    val randomBruteForceProgress: StateFlow<RandomBruteForceProgress> = _randomBruteForceProgress.asStateFlow()

    fun startRandomBruteForce(tag: Tag, targetSector: Int) {
        if (randomBruteForceActive) {
            log("Random brute force already running!")
            return
        }

        triedKeys.clear()
        randomBruteForceActive = true
        val startTime = System.currentTimeMillis()

        _randomBruteForceProgress.value = RandomBruteForceProgress(
            isRunning = true,
            keysTried = 0,
            sector = targetSector,
            keysPerSecond = 0f,
            keyAFound = null,
            keyBFound = null
        )

        log("=== TRUE RANDOM BRUTE FORCE STARTED ===")
        log("Target: Sector $targetSector")
        log("Keyspace: 281,474,976,710,656 possible keys (48-bit)")
        log("WARNING: This is for educational purposes only!")
        log("Proxmark uses exploits that crack keys in seconds.")

        Thread {
            val mifare = MifareClassic.get(tag)
            var keyAFound: String? = null
            var keyBFound: String? = null
            var keysTried = 0L
            var lastUpdate = startTime

            try {
                mifare.connect()

                while (randomBruteForceActive && (keyAFound == null || keyBFound == null)) {
                    // Generate random 48-bit key
                    var keyLong: Long
                    do {
                        keyLong = Random.nextLong(0, 0x1000000000000L) // 2^48
                    } while (triedKeys.contains(keyLong))

                    triedKeys.add(keyLong)
                    keysTried++

                    val key = longToByteArray(keyLong)
                    val keyHex = key.toHexString()

                    // Try Key A
                    if (keyAFound == null) {
                        try {
                            if (mifare.authenticateSectorWithKeyA(targetSector, key)) {
                                keyAFound = keyHex
                                log("★★★ KEY A FOUND: $keyAFound ★★★")
                                log("Found after $keysTried attempts!")
                                _randomBruteForceProgress.value = _randomBruteForceProgress.value.copy(keyAFound = keyAFound)
                            }
                        } catch (e: Exception) {
                            if (e.message?.contains("Tag was lost") == true) {
                                log("Card removed!")
                                break
                            }
                            try { if (!mifare.isConnected) mifare.connect() } catch (_: Exception) {}
                        }
                    }

                    // Try Key B
                    if (keyBFound == null) {
                        try {
                            if (mifare.authenticateSectorWithKeyB(targetSector, key)) {
                                keyBFound = keyHex
                                log("★★★ KEY B FOUND: $keyBFound ★★★")
                                log("Found after $keysTried attempts!")
                                _randomBruteForceProgress.value = _randomBruteForceProgress.value.copy(keyBFound = keyBFound)
                            }
                        } catch (e: Exception) {
                            if (e.message?.contains("Tag was lost") == true) break
                            try { if (!mifare.isConnected) mifare.connect() } catch (_: Exception) {}
                        }
                    }

                    // Update progress every 100 keys
                    if (keysTried % 100 == 0L) {
                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastUpdate) / 1000f
                        val speed = if (elapsed > 0) 100f / elapsed else 0f
                        lastUpdate = now

                        val totalElapsed = (now - startTime) / 1000f
                        val avgSpeed = if (totalElapsed > 0) keysTried / totalElapsed else 0f

                        // Calculate ETA (in years, for the full keyspace)
                        val remainingKeys = 281474976710656L - keysTried
                        val etaSeconds = if (avgSpeed > 0) remainingKeys / avgSpeed else Float.MAX_VALUE
                        val etaYears = etaSeconds / (365.25 * 24 * 3600)

                        _randomBruteForceProgress.value = _randomBruteForceProgress.value.copy(
                            keysTried = keysTried,
                            keysPerSecond = speed,
                            currentKeyHex = keyHex,
                            etaYears = etaYears.toFloat()
                        )
                    }

                    // Limit memory - clear old keys periodically to prevent OOM
                    if (triedKeys.size > 10_000_000) {
                        log("Warning: Cleared key cache to prevent OOM")
                        triedKeys.clear()
                    }
                }

                mifare.close()

            } catch (e: Exception) {
                log("Error: ${e.message}")
                try { mifare.close() } catch (_: Exception) {}
            }

            val totalTime = (System.currentTimeMillis() - startTime) / 1000f
            val avgSpeed = keysTried / totalTime

            log("=== RANDOM BRUTE FORCE COMPLETE ===")
            log("Tried: $keysTried random keys in %.1f seconds".format(totalTime))
            log("Average: %.1f keys/sec".format(avgSpeed))
            if (keyAFound != null) log("Key A: $keyAFound")
            if (keyBFound != null) log("Key B: $keyBFound")
            if (keyAFound == null && keyBFound == null) {
                log("No keys found. At this rate, full keyspace would take ~${(281474976710656L / avgSpeed / 31536000).toLong()} years")
            }

            randomBruteForceActive = false
            _randomBruteForceProgress.value = _randomBruteForceProgress.value.copy(
                isRunning = false,
                keysTried = keysTried,
                keysPerSecond = avgSpeed
            )
        }.start()
    }

    fun stopRandomBruteForce() {
        randomBruteForceActive = false
        log("Random brute force stopped")
    }

    private fun longToByteArray(value: Long): ByteArray {
        return byteArrayOf(
            ((value shr 40) and 0xFF).toByte(),
            ((value shr 32) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    /**
     * Detect Magic Card type (Gen1a, Gen2, Gen3, Gen4)
     * Magic cards have backdoors that allow UID changes
     */
    fun detectMagicCard(tag: Tag): MagicCardInfo {
        log("=== MAGIC CARD DETECTION ===")

        val nfcA = NfcA.get(tag)
        var gen1a = false
        var gen2 = false
        var gen3 = false

        try {
            nfcA.connect()

            // Gen1a detection: Try backdoor command
            // Backdoor: WUPC (0x40) then HALT (0x50), then WUPC again
            // Gen1a cards respond to 0x40 0x43 (wake up)
            try {
                val wakeCmd = byteArrayOf(0x40.toByte())
                val response = nfcA.transceive(wakeCmd)
                if (response != null && response.isNotEmpty()) {
                    gen1a = true
                    log("Gen1a DETECTED: Responds to backdoor wake command")
                }
            } catch (e: Exception) {
                log("Gen1a test: No response (not Gen1a or error)")
            }

            // Gen2 (CUID) detection: Check if we can write to Block 0
            // We don't actually write, but Gen2 cards allow it
            // This is harder to detect without writing

            // Gen3 detection: Try APDU-style commands
            // Gen3 uses different backdoor via ISO14443-4

            nfcA.close()

        } catch (e: Exception) {
            log("Magic card detection error: ${e.message}")
            try { nfcA.close() } catch (_: Exception) {}
        }

        // Check via MIFARE Classic interface
        val mifare = MifareClassic.get(tag)
        try {
            mifare.connect()

            // Try reading with all-zero key on sector 0 (common on Gen2)
            val zeroKey = byteArrayOf(0, 0, 0, 0, 0, 0)
            try {
                if (mifare.authenticateSectorWithKeyA(0, zeroKey)) {
                    log("Sector 0 unlocked with zero key - possible magic card")
                    // Try to check if block 0 looks writable (manufacturer block)
                    try {
                        val block0 = mifare.readBlock(0)
                        log("Block 0 readable: ${block0.toHexString()}")
                        // On real cards, block 0 is read-only after manufacture
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            mifare.close()

        } catch (e: Exception) {
            try { mifare.close() } catch (_: Exception) {}
        }

        val result = MagicCardInfo(
            isGen1a = gen1a,
            isGen2 = gen2,
            isGen3 = gen3,
            isMagic = gen1a || gen2 || gen3
        )

        if (result.isMagic) {
            log("★ MAGIC CARD DETECTED ★")
            if (gen1a) log("  Type: Gen1a (UID) - Backdoor commands work")
            if (gen2) log("  Type: Gen2 (CUID) - Direct write to Block 0")
            if (gen3) log("  Type: Gen3 (APDU) - APDU backdoor")
        } else {
            log("Not a magic card (or undetected type)")
        }

        log("=== DETECTION COMPLETE ===")
        return result
    }

    /**
     * Check if this is a different card than the current one
     */
    fun isNewCard(tag: Tag): Boolean {
        val newUid = tag.id.toHexString()
        val currentUid = _currentCard.value?.uid
        return newUid != currentUid
    }

    /**
     * Clear current card data (for card switching)
     */
    fun clearCurrentCard() {
        _currentCard.value = null
        _scanProgress.value = ScanProgress()
        log("Card data cleared")
    }
}

enum class NfcState {
    IDLE,
    WAITING_FOR_TAG,
    READING,
    READ_COMPLETE,
    ERROR
}

data class MifareCardData(
    val uid: String,
    val type: String,
    val size: Int,
    val sectors: List<SectorData>,
    val isCSC: Boolean,
    val timestamp: Long
)

data class SectorData(
    val index: Int,
    val blocks: List<BlockData>,
    val keyA: String?,
    val keyB: String?,
    val authenticated: Boolean
)

data class BlockData(
    val index: Int,
    val data: String,
    val isTrailer: Boolean,
    val readable: Boolean
)

data class CSCTransaction(
    val blockIndex: Int,
    val rawData: String,
    val amount: Int
) {
    val amountFormatted: String
        get() = "\$${amount / 100}.${String.format("%02d", amount % 100)}"
}

data class CSCAnalysis(
    val estimatedBalance: Int?,
    val balanceValidated: Boolean = false,
    val balanceBackup: Int? = null,
    val transactionCounter: Int? = null,
    val provider: String? = null,
    val siteCode: String? = null,
    val lastTransaction: String? = null,
    val transactions: List<CSCTransaction> = emptyList(),
    val vulnerableToReplay: Boolean = true
) {
    val balanceInDollars: String
        get() = estimatedBalance?.let {
            "\$${it / 100}.${String.format("%02d", it % 100)}"
        } ?: "Unknown"

    val balanceBackupFormatted: String?
        get() = balanceBackup?.let {
            "\$${it / 100}.${String.format("%02d", it % 100)}"
        }

    val balanceMatches: Boolean
        get() = balanceBackup == null || estimatedBalance == balanceBackup
}

data class BruteForceProgress(
    val isRunning: Boolean = false,
    val currentKey: Int = 0,
    val totalKeys: Int = 0,
    val sector: Int = 0,
    val keysPerSecond: Float = 0f,
    val keyAFound: String? = null,
    val keyBFound: String? = null
) {
    val progress: Float get() = if (totalKeys > 0) currentKey.toFloat() / totalKeys else 0f
    val percentComplete: Int get() = (progress * 100).toInt()
}

data class ScanProgress(
    val isScanning: Boolean = false,
    val currentSector: Int = 0,
    val totalSectors: Int = 16,
    val currentKey: Int = 0,
    val totalKeys: Int = 0,
    val currentKeyHex: String = "",
    val keysPerSecond: Float = 0f,
    val status: String = "Ready",
    val foundKeysCount: Int = 0,  // Keys found this session for reuse
    val sectorsUnlocked: Int = 0   // Count of unlocked sectors
) {
    val sectorProgress: Float get() = if (totalSectors > 0) currentSector.toFloat() / totalSectors else 0f
    val keyProgress: Float get() = if (totalKeys > 0) currentKey.toFloat() / totalKeys else 0f
    val overallProgress: Float get() = (sectorProgress + keyProgress / totalSectors)
}

data class RandomBruteForceProgress(
    val isRunning: Boolean = false,
    val keysTried: Long = 0,
    val sector: Int = 0,
    val keysPerSecond: Float = 0f,
    val currentKeyHex: String = "",
    val keyAFound: String? = null,
    val keyBFound: String? = null,
    val etaYears: Float = 0f
) {
    companion object {
        const val TOTAL_KEYSPACE = 281474976710656L // 2^48
    }
    val progress: Float get() = keysTried.toFloat() / TOTAL_KEYSPACE
    val progressPercent: String get() = "%.12f%%".format(progress * 100)
}

data class MagicCardInfo(
    val isGen1a: Boolean = false,
    val isGen2: Boolean = false,
    val isGen3: Boolean = false,
    val isGen4: Boolean = false,
    val isMagic: Boolean = false
) {
    val type: String get() = when {
        isGen1a -> "Gen1a (UID)"
        isGen2 -> "Gen2 (CUID)"
        isGen3 -> "Gen3 (APDU)"
        isGen4 -> "Gen4 (GDM)"
        else -> "Unknown"
    }
}

// Extension functions
fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }

fun String.hexToAscii(): String {
    return try {
        chunked(2)
            .map { it.toInt(16).toChar() }
            .filter { it.isLetterOrDigit() || it.isWhitespace() || it in ".-_" }
            .joinToString("")
    } catch (_: Exception) { "" }
}
