package com.laundr.droid.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Magic Card Writer for writing to Gen1a, Gen2, and Gen4 MIFARE Classic magic cards
 * These cards have backdoor commands that allow writing to Block 0 (UID block)
 *
 * Gen1a: Responds to 0x40 (unlock) then 0x43 (write block 0)
 * Gen2: Direct write to block 0 after auth with Key A
 * Gen4: Uses password-protected commands (default: 00000000)
 */
object MagicCardWriter {
    private const val TAG = "MagicCardWriter"

    // Gen1a unlock commands
    private val GEN1A_UNLOCK = byteArrayOf(0x40)
    private val GEN1A_WIPE = byteArrayOf(0x41)
    private val GEN1A_WRITE = byteArrayOf(0x43)
    private val GEN1A_READ = byteArrayOf(0x44)

    // Gen4 default password
    private val GEN4_DEFAULT_PASSWORD = byteArrayOf(0x00, 0x00, 0x00, 0x00)

    // Gen4 commands
    private val GEN4_CMD_GET_VERSION = byteArrayOf(0xCF.toByte())
    private val GEN4_CMD_WRITE_BLOCK = byteArrayOf(0xCD.toByte())
    private val GEN4_CMD_READ_BLOCK = byteArrayOf(0xCE.toByte())
    private val GEN4_CMD_SET_CONFIG = byteArrayOf(0xF0.toByte())

    enum class MagicCardType {
        UNKNOWN,
        GEN1A,      // Chinese magic card, responds to unlock commands
        GEN2,       // Direct write, CUID
        GEN4        // Ultimate magic card, password protected
    }

    data class WriteResult(
        val success: Boolean,
        val blocksWritten: Int,
        val error: String? = null,
        val cardType: MagicCardType = MagicCardType.UNKNOWN
    )

    /**
     * Detect magic card type
     */
    suspend fun detectMagicCardType(tag: Tag): MagicCardType = withContext(Dispatchers.IO) {
        try {
            val nfcA = NfcA.get(tag) ?: return@withContext MagicCardType.UNKNOWN
            nfcA.connect()
            nfcA.timeout = 500

            try {
                // Try Gen1a unlock command
                val response = nfcA.transceive(GEN1A_UNLOCK)
                if (response != null && response.isNotEmpty()) {
                    Log.i(TAG, "Gen1a magic card detected (responded to 0x40)")
                    return@withContext MagicCardType.GEN1A
                }
            } catch (e: Exception) {
                Log.d(TAG, "Not Gen1a: ${e.message}")
            }

            try {
                // Try Gen4 version command with default password
                val cmd = byteArrayOf(
                    0xCF.toByte(),
                    0x00, 0x00, 0x00, 0x00, // default password
                    0x70  // get version
                )
                val response = nfcA.transceive(cmd)
                if (response != null && response.size >= 2) {
                    Log.i(TAG, "Gen4 magic card detected")
                    return@withContext MagicCardType.GEN4
                }
            } catch (e: Exception) {
                Log.d(TAG, "Not Gen4: ${e.message}")
            }

            // Try Gen2 - authenticate and try to write block 0
            try {
                val mifare = MifareClassic.get(tag)
                if (mifare != null) {
                    mifare.connect()
                    // Gen2 cards allow direct auth to sector 0 and write block 0
                    if (mifare.authenticateSectorWithKeyA(0, MifareClassic.KEY_DEFAULT)) {
                        Log.i(TAG, "Possible Gen2 (CUID) magic card")
                        mifare.close()
                        return@withContext MagicCardType.GEN2
                    }
                    mifare.close()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Gen2 check failed: ${e.message}")
            }

            nfcA.close()
            MagicCardType.UNKNOWN
        } catch (e: Exception) {
            Log.e(TAG, "Magic card detection failed: ${e.message}")
            MagicCardType.UNKNOWN
        }
    }

    /**
     * Write card data to a magic card
     * @param tag The NFC tag
     * @param cardData The MasterCardData to write
     * @param cardType The type of magic card (auto-detect if null)
     */
    suspend fun writeToMagicCard(
        tag: Tag,
        cardData: MasterCardData,
        cardType: MagicCardType? = null
    ): WriteResult = withContext(Dispatchers.IO) {
        val detectedType = cardType ?: detectMagicCardType(tag)
        Log.i(TAG, "Writing to $detectedType magic card")

        when (detectedType) {
            MagicCardType.GEN1A -> writeGen1a(tag, cardData)
            MagicCardType.GEN2 -> writeGen2(tag, cardData)
            MagicCardType.GEN4 -> writeGen4(tag, cardData)
            MagicCardType.UNKNOWN -> WriteResult(
                success = false,
                blocksWritten = 0,
                error = "Unknown card type - not a magic card or unsupported type",
                cardType = detectedType
            )
        }
    }

    /**
     * Write to Gen1a magic card using unlock commands
     */
    private fun writeGen1a(tag: Tag, cardData: MasterCardData): WriteResult {
        val nfcA = NfcA.get(tag) ?: return WriteResult(
            success = false,
            blocksWritten = 0,
            error = "NfcA not available",
            cardType = MagicCardType.GEN1A
        )

        try {
            nfcA.connect()
            nfcA.timeout = 1000

            var blocksWritten = 0

            // Unlock card
            try {
                nfcA.transceive(GEN1A_UNLOCK)
            } catch (e: Exception) {
                Log.d(TAG, "Unlock response (expected): ${e.message}")
            }

            // Write all 64 blocks using Gen1a backdoor write (0x43)
            for (blockIndex in 0 until 64) {
                try {
                    // Re-unlock before each block write (required for Gen1a)
                    try { nfcA.transceive(GEN1A_UNLOCK) } catch (e: Exception) { }

                    // Build Gen1a backdoor write command: 0x43 + block number + 16 bytes data
                    // Note: 0xA0 is standard MIFARE write which causes ERR 44 on some Gen1a cards
                    val writeCmd = ByteArray(18)
                    writeCmd[0] = 0x43.toByte()  // Gen1a backdoor write command
                    writeCmd[1] = blockIndex.toByte()
                    System.arraycopy(cardData.blocks[blockIndex], 0, writeCmd, 2, 16)

                    val response = nfcA.transceive(writeCmd)
                    if (response != null) {
                        blocksWritten++
                        Log.d(TAG, "Wrote block $blockIndex")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write block $blockIndex: ${e.message}")
                    // For trailer blocks, this might be expected
                    if (blockIndex % 4 != 3) {
                        // Non-trailer block failed
                    }
                }
            }

            nfcA.close()

            return WriteResult(
                success = blocksWritten > 0,
                blocksWritten = blocksWritten,
                error = if (blocksWritten == 0) "No blocks written" else null,
                cardType = MagicCardType.GEN1A
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gen1a write failed: ${e.message}")
            return WriteResult(
                success = false,
                blocksWritten = 0,
                error = e.message,
                cardType = MagicCardType.GEN1A
            )
        } finally {
            try { nfcA.close() } catch (e: Exception) { }
        }
    }

    /**
     * Write to Gen2 (CUID) magic card using standard MIFARE commands
     */
    private fun writeGen2(tag: Tag, cardData: MasterCardData): WriteResult {
        val mifare = MifareClassic.get(tag) ?: return WriteResult(
            success = false,
            blocksWritten = 0,
            error = "MifareClassic not available",
            cardType = MagicCardType.GEN2
        )

        try {
            mifare.connect()

            var blocksWritten = 0
            val sectorCount = mifare.sectorCount

            // Try to authenticate and write each sector
            for (sector in 0 until sectorCount) {
                val firstBlock = mifare.sectorToBlock(sector)
                val blockCount = mifare.getBlockCountInSector(sector)

                // Try to authenticate
                val authed = mifare.authenticateSectorWithKeyA(sector, MifareClassic.KEY_DEFAULT) ||
                        mifare.authenticateSectorWithKeyA(sector, MasterCard.CSC_KEY_A) ||
                        mifare.authenticateSectorWithKeyB(sector, MifareClassic.KEY_DEFAULT)

                if (authed) {
                    // Write all blocks in sector
                    for (i in 0 until blockCount) {
                        val blockIndex = firstBlock + i
                        if (blockIndex < 64) {
                            try {
                                mifare.writeBlock(blockIndex, cardData.blocks[blockIndex])
                                blocksWritten++
                                Log.d(TAG, "Wrote block $blockIndex")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to write block $blockIndex: ${e.message}")
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to authenticate sector $sector")
                }
            }

            mifare.close()

            return WriteResult(
                success = blocksWritten > 0,
                blocksWritten = blocksWritten,
                error = if (blocksWritten == 0) "Authentication failed for all sectors" else null,
                cardType = MagicCardType.GEN2
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gen2 write failed: ${e.message}")
            return WriteResult(
                success = false,
                blocksWritten = 0,
                error = e.message,
                cardType = MagicCardType.GEN2
            )
        } finally {
            try { mifare.close() } catch (e: Exception) { }
        }
    }

    /**
     * Write to Gen4 (Ultimate Magic) card using password-protected commands
     */
    private fun writeGen4(tag: Tag, cardData: MasterCardData): WriteResult {
        val nfcA = NfcA.get(tag) ?: return WriteResult(
            success = false,
            blocksWritten = 0,
            error = "NfcA not available",
            cardType = MagicCardType.GEN4
        )

        try {
            nfcA.connect()
            nfcA.timeout = 1000

            var blocksWritten = 0

            // Write all 64 blocks using Gen4 write command
            for (blockIndex in 0 until 64) {
                try {
                    // Gen4 write command: CF + password (4 bytes) + CD + block + data (16 bytes)
                    val writeCmd = ByteArray(23)
                    writeCmd[0] = 0xCF.toByte()
                    // Password (default 00000000)
                    writeCmd[1] = 0x00
                    writeCmd[2] = 0x00
                    writeCmd[3] = 0x00
                    writeCmd[4] = 0x00
                    writeCmd[5] = 0xCD.toByte() // Write block command
                    writeCmd[6] = blockIndex.toByte()
                    System.arraycopy(cardData.blocks[blockIndex], 0, writeCmd, 7, 16)

                    val response = nfcA.transceive(writeCmd)
                    if (response != null && response.isNotEmpty()) {
                        blocksWritten++
                        Log.d(TAG, "Wrote block $blockIndex")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write block $blockIndex: ${e.message}")
                }
            }

            nfcA.close()

            return WriteResult(
                success = blocksWritten > 0,
                blocksWritten = blocksWritten,
                error = if (blocksWritten == 0) "Gen4 write failed - check password" else null,
                cardType = MagicCardType.GEN4
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gen4 write failed: ${e.message}")
            return WriteResult(
                success = false,
                blocksWritten = 0,
                error = e.message,
                cardType = MagicCardType.GEN4
            )
        } finally {
            try { nfcA.close() } catch (e: Exception) { }
        }
    }

    /**
     * Wipe a Gen1a magic card to factory defaults
     */
    suspend fun wipeGen1a(tag: Tag): WriteResult = withContext(Dispatchers.IO) {
        val nfcA = NfcA.get(tag) ?: return@withContext WriteResult(
            success = false,
            blocksWritten = 0,
            error = "NfcA not available"
        )

        try {
            nfcA.connect()
            nfcA.timeout = 1000

            // Send wipe command
            try {
                nfcA.transceive(GEN1A_UNLOCK)
            } catch (e: Exception) { }

            try {
                nfcA.transceive(GEN1A_WIPE)
                nfcA.close()
                return@withContext WriteResult(
                    success = true,
                    blocksWritten = 64,
                    cardType = MagicCardType.GEN1A
                )
            } catch (e: Exception) {
                nfcA.close()
                return@withContext WriteResult(
                    success = false,
                    blocksWritten = 0,
                    error = "Wipe command failed: ${e.message}",
                    cardType = MagicCardType.GEN1A
                )
            }
        } catch (e: Exception) {
            return@withContext WriteResult(
                success = false,
                blocksWritten = 0,
                error = e.message
            )
        }
    }
}
