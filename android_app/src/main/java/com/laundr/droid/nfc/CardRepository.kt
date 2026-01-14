package com.laundr.droid.nfc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Persisted card storage with auto-save, export, import, and share functionality
 */
class CardRepository(private val context: Context) {

    companion object {
        private const val TAG = "CardRepository"
        private const val CARDS_FILE = "saved_cards.json"
        private const val EXPORT_DIR = "exports"
    }

    private val _savedCards = MutableStateFlow<List<SavedCard>>(emptyList())
    val savedCards: StateFlow<List<SavedCard>> = _savedCards.asStateFlow()

    private val cardsFile: File
        get() = File(context.filesDir, CARDS_FILE)

    val exportDir: File
        get() = File(context.getExternalFilesDir(null), EXPORT_DIR).also { it.mkdirs() }

    init {
        loadCards()
    }

    private fun loadCards() {
        try {
            if (cardsFile.exists()) {
                val json = cardsFile.readText()
                val array = JSONArray(json)
                val cards = mutableListOf<SavedCard>()

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    cards.add(SavedCard.fromJson(obj))
                }

                _savedCards.value = cards.sortedByDescending { it.timestamp }
                Log.i(TAG, "Loaded ${cards.size} saved cards")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cards: ${e.message}")
        }
    }

    private fun persistCards() {
        try {
            val array = JSONArray()
            _savedCards.value.forEach { card ->
                array.put(card.toJson())
            }
            cardsFile.writeText(array.toString(2))
            Log.i(TAG, "Persisted ${_savedCards.value.size} cards")
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting cards: ${e.message}")
        }
    }

    /**
     * Auto-save a cracked card
     */
    fun saveCard(cardData: MifareCardData, analysis: CSCAnalysis?): SavedCard {
        val existingIndex = _savedCards.value.indexOfFirst { it.uid == cardData.uid }

        val savedCard = SavedCard(
            uid = cardData.uid,
            type = cardData.type,
            isCSC = cardData.isCSC,
            balance = analysis?.estimatedBalance,
            provider = analysis?.provider,
            sectors = cardData.sectors,
            timestamp = System.currentTimeMillis(),
            nickname = null
        )

        if (existingIndex >= 0) {
            // Update existing card
            val updatedList = _savedCards.value.toMutableList()
            updatedList[existingIndex] = savedCard
            _savedCards.value = updatedList.sortedByDescending { it.timestamp }
            Log.i(TAG, "Updated existing card: ${cardData.uid}")
        } else {
            // Add new card
            _savedCards.value = (_savedCards.value + savedCard).sortedByDescending { it.timestamp }
            Log.i(TAG, "Saved new card: ${cardData.uid}")
        }

        persistCards()
        return savedCard
    }

    /**
     * Delete a saved card
     */
    fun deleteCard(uid: String) {
        _savedCards.value = _savedCards.value.filter { it.uid != uid }
        persistCards()
    }

    /**
     * Update card nickname
     */
    fun setNickname(uid: String, nickname: String?) {
        val cards = _savedCards.value.toMutableList()
        val index = cards.indexOfFirst { it.uid == uid }
        if (index >= 0) {
            cards[index] = cards[index].copy(nickname = nickname)
            _savedCards.value = cards
            persistCards()
        }
    }

    /**
     * Update card photo
     */
    fun setCardPhoto(uid: String, photoPath: String?) {
        val cards = _savedCards.value.toMutableList()
        val index = cards.indexOfFirst { it.uid == uid }
        if (index >= 0) {
            cards[index] = cards[index].copy(photoPath = photoPath)
            _savedCards.value = cards
            persistCards()
            Log.i(TAG, "Set photo for card $uid: $photoPath")
        }
    }

    /**
     * Export card to LaunDR .laundr format (extended Flipper format with metadata)
     * This format is compatible with the Flipper LaunDR app
     */
    suspend fun exportToLaundrFormat(card: SavedCard, isMasterCard: Boolean = false): File = withContext(Dispatchers.IO) {
        val filename = "LaunDRoid_${card.displayName}_${formatTimestamp(card.timestamp)}.laundr"
        val file = File(exportDir, filename.replace(" ", "_").replace("/", "-"))

        val content = buildString {
            appendLine("Filetype: LaunDR Card")
            appendLine("Version: 1")
            appendLine("# Generated by LaunDRoid Android")
            appendLine("# UID: ${card.uid}")
            appendLine("#")
            appendLine("# === LaunDR Metadata ===")
            appendLine("# MasterCard: $isMasterCard")
            appendLine("# MaxValue: 65535")  // CSC max value in cents ($655.35)
            appendLine("# AutoReloader: true")  // Most CSC cards are auto-reloaders
            card.balance?.let {
                appendLine("# Balance: $it")
                appendLine("# BalanceFormatted: ${card.balanceFormatted}")
            }
            card.provider?.let { appendLine("# Provider: $it") }
            appendLine("# CrackedSectors: ${card.crackedSectors}/${card.totalSectors}")
            appendLine("# IsCSC: ${card.isCSC}")
            appendLine("# Timestamp: ${card.timestamp}")
            appendLine("#")
            appendLine("# === Keys Found ===")
            for (sector in card.sectors) {
                if (sector.authenticated) {
                    append("# Sector ${sector.index}: ")
                    sector.keyA?.let { append("KeyA=$it ") }
                    sector.keyB?.let { append("KeyB=$it") }
                    appendLine()
                }
            }
            appendLine("#")
            appendLine("# === Flipper NFC Format ===")
            appendLine("Device type: ${if (card.type.contains("4K")) "MIFARE Classic 4K" else "MIFARE Classic 1K"}")
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
        Log.i(TAG, "Exported to LaunDR format: ${file.name}")
        file
    }

    /**
     * Export card to Flipper .nfc format
     */
    suspend fun exportToFlipperFormat(card: SavedCard): File = withContext(Dispatchers.IO) {
        val filename = "LaunDRoid_${card.displayName}_${formatTimestamp(card.timestamp)}.nfc"
        val file = File(exportDir, filename.replace(" ", "_").replace("/", "-"))

        val content = buildString {
            appendLine("Filetype: Flipper NFC device")
            appendLine("Version: 4")
            appendLine("# Generated by LaunDRoid")
            appendLine("# UID: ${card.uid}")
            card.provider?.let { appendLine("# Provider: $it") }
            card.balance?.let { appendLine("# Balance: \$${it / 100}.${String.format("%02d", it % 100)}") }
            appendLine("Device type: ${if (card.type.contains("4K")) "MIFARE Classic 4K" else "MIFARE Classic 1K"}")
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
        Log.i(TAG, "Exported to Flipper format: ${file.name}")
        file
    }

    /**
     * Export card to JSON format (full data with keys)
     */
    suspend fun exportToJson(card: SavedCard): File = withContext(Dispatchers.IO) {
        val filename = "LaunDRoid_${card.displayName}_${formatTimestamp(card.timestamp)}.json"
        val file = File(exportDir, filename.replace(" ", "_").replace("/", "-"))
        file.writeText(card.toJson().toString(2))
        file
    }

    /**
     * Export all cards as single JSON backup
     */
    suspend fun exportAllCards(): File = withContext(Dispatchers.IO) {
        val filename = "LaunDRoid_backup_${formatTimestamp(System.currentTimeMillis())}.json"
        val file = File(exportDir, filename)

        val array = JSONArray()
        _savedCards.value.forEach { array.put(it.toJson()) }

        val backup = JSONObject().apply {
            put("version", 1)
            put("app", "LaunDRoid")
            put("exported", System.currentTimeMillis())
            put("card_count", _savedCards.value.size)
            put("cards", array)
        }

        file.writeText(backup.toString(2))
        Log.i(TAG, "Exported ${_savedCards.value.size} cards to backup")
        file
    }

    /**
     * Import cards from JSON backup
     */
    suspend fun importFromJson(jsonString: String): Int = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(jsonString)
            val cardsArray = if (json.has("cards")) {
                json.getJSONArray("cards")
            } else {
                JSONArray(jsonString)
            }

            var imported = 0
            val currentCards = _savedCards.value.toMutableList()

            for (i in 0 until cardsArray.length()) {
                try {
                    val cardJson = cardsArray.getJSONObject(i)
                    val card = SavedCard.fromJson(cardJson)

                    // Check if already exists
                    val existingIndex = currentCards.indexOfFirst { it.uid == card.uid }
                    if (existingIndex >= 0) {
                        // Update if newer
                        if (card.timestamp > currentCards[existingIndex].timestamp) {
                            currentCards[existingIndex] = card
                            imported++
                        }
                    } else {
                        currentCards.add(card)
                        imported++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import card at index $i: ${e.message}")
                }
            }

            _savedCards.value = currentCards.sortedByDescending { it.timestamp }
            persistCards()

            Log.i(TAG, "Imported $imported cards")
            imported
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}")
            0
        }
    }

    /**
     * Import from Flipper .nfc file
     */
    suspend fun importFromFlipperFormat(content: String): SavedCard? = withContext(Dispatchers.IO) {
        try {
            val lines = content.lines()
            var uid = ""
            var deviceType = ""
            val blocks = mutableListOf<BlockData>()

            for (line in lines) {
                when {
                    line.startsWith("UID:") -> {
                        uid = line.substringAfter("UID:").trim().replace(" ", "")
                    }
                    line.startsWith("Device type:") -> {
                        deviceType = line.substringAfter("Device type:").trim()
                    }
                    line.startsWith("Block ") -> {
                        val parts = line.split(":")
                        if (parts.size == 2) {
                            val blockNum = parts[0].substringAfter("Block ").trim().toIntOrNull()
                            val data = parts[1].trim().replace(" ", "")
                            if (blockNum != null && data.length == 32) {
                                blocks.add(BlockData(
                                    index = blockNum,
                                    data = data,
                                    isTrailer = (blockNum + 1) % 4 == 0,
                                    readable = true
                                ))
                            }
                        }
                    }
                }
            }

            if (uid.isEmpty() || blocks.isEmpty()) {
                Log.w(TAG, "Invalid Flipper file format")
                return@withContext null
            }

            // Group blocks into sectors
            val sectors = blocks.groupBy { it.index / 4 }.map { (sectorIndex, sectorBlocks) ->
                val trailer = sectorBlocks.find { it.isTrailer }
                var keyA: String? = null
                var keyB: String? = null

                // Extract keys from trailer if readable
                if (trailer != null && trailer.readable && trailer.data.length == 32) {
                    keyA = trailer.data.substring(0, 12)
                    keyB = trailer.data.substring(20, 32)
                }

                SectorData(
                    index = sectorIndex,
                    blocks = sectorBlocks.sortedBy { it.index },
                    keyA = keyA,
                    keyB = keyB,
                    authenticated = keyA != null || keyB != null
                )
            }.sortedBy { it.index }

            val savedCard = SavedCard(
                uid = uid,
                type = deviceType.ifEmpty { "MIFARE Classic 1K" },
                isCSC = false,
                balance = null,
                provider = null,
                sectors = sectors,
                timestamp = System.currentTimeMillis(),
                nickname = "Imported"
            )

            // Save and return
            _savedCards.value = (_savedCards.value + savedCard).sortedByDescending { it.timestamp }
            persistCards()

            Log.i(TAG, "Imported Flipper card: $uid")
            savedCard
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import Flipper file: ${e.message}")
            null
        }
    }

    /**
     * Get shareable text for card
     */
    fun getShareText(card: SavedCard): String {
        return buildString {
            appendLine("=== LaunDRoid Card Export ===")
            appendLine("UID: ${card.uid}")
            appendLine("Type: ${card.type}")
            card.provider?.let { appendLine("Provider: $it") }
            card.balance?.let {
                appendLine("Balance: \$${it / 100}.${String.format("%02d", it % 100)}")
            }
            appendLine()
            appendLine("Sector Keys:")
            for (sector in card.sectors) {
                if (sector.authenticated) {
                    append("  Sector ${sector.index}: ")
                    sector.keyA?.let { append("KeyA=$it ") }
                    sector.keyB?.let { append("KeyB=$it") }
                    appendLine()
                }
            }
            appendLine()
            appendLine("Exported: ${formatTimestamp(System.currentTimeMillis())}")
        }
    }

    private fun formatTimestamp(ts: Long): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ts))
    }
}

/**
 * Saved card with all extracted data
 */
data class SavedCard(
    val uid: String,
    val type: String,
    val isCSC: Boolean,
    val balance: Int?,
    val provider: String?,
    val sectors: List<SectorData>,
    val timestamp: Long,
    val nickname: String?,
    val photoPath: String? = null
) {
    val displayName: String
        get() = nickname ?: uid.takeLast(8)

    val balanceFormatted: String?
        get() = balance?.let { "\$${it / 100}.${String.format("%02d", it % 100)}" }

    val crackedSectors: Int
        get() = sectors.count { it.authenticated }

    val totalSectors: Int
        get() = sectors.size

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("uid", uid)
            put("type", type)
            put("isCSC", isCSC)
            put("balance", balance)
            put("provider", provider)
            put("timestamp", timestamp)
            put("nickname", nickname)
            put("photoPath", photoPath)

            val sectorsArray = JSONArray()
            for (sector in sectors) {
                sectorsArray.put(JSONObject().apply {
                    put("index", sector.index)
                    put("keyA", sector.keyA)
                    put("keyB", sector.keyB)
                    put("authenticated", sector.authenticated)

                    val blocksArray = JSONArray()
                    for (block in sector.blocks) {
                        blocksArray.put(JSONObject().apply {
                            put("index", block.index)
                            put("data", block.data)
                            put("isTrailer", block.isTrailer)
                            put("readable", block.readable)
                        })
                    }
                    put("blocks", blocksArray)
                })
            }
            put("sectors", sectorsArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): SavedCard {
            val sectorsArray = json.getJSONArray("sectors")
            val sectors = mutableListOf<SectorData>()

            for (i in 0 until sectorsArray.length()) {
                val sectorJson = sectorsArray.getJSONObject(i)
                val blocksArray = sectorJson.getJSONArray("blocks")
                val blocks = mutableListOf<BlockData>()

                for (j in 0 until blocksArray.length()) {
                    val blockJson = blocksArray.getJSONObject(j)
                    blocks.add(BlockData(
                        index = blockJson.getInt("index"),
                        data = blockJson.getString("data"),
                        isTrailer = blockJson.getBoolean("isTrailer"),
                        readable = blockJson.getBoolean("readable")
                    ))
                }

                sectors.add(SectorData(
                    index = sectorJson.getInt("index"),
                    blocks = blocks,
                    keyA = sectorJson.optString("keyA").takeIf { it.isNotEmpty() },
                    keyB = sectorJson.optString("keyB").takeIf { it.isNotEmpty() },
                    authenticated = sectorJson.getBoolean("authenticated")
                ))
            }

            return SavedCard(
                uid = json.getString("uid"),
                type = json.getString("type"),
                isCSC = json.getBoolean("isCSC"),
                balance = if (json.isNull("balance")) null else json.getInt("balance"),
                provider = json.optString("provider").takeIf { it.isNotEmpty() },
                sectors = sectors,
                timestamp = json.getLong("timestamp"),
                nickname = json.optString("nickname").takeIf { it.isNotEmpty() },
                photoPath = json.optString("photoPath").takeIf { it.isNotEmpty() }
            )
        }
    }
}
