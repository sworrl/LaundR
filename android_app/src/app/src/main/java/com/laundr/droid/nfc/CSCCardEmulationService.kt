package com.laundr.droid.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

/**
 * NFC Host Card Emulation (HCE) Service for CSC Card Emulation
 *
 * IMPORTANT LIMITATIONS:
 * Android HCE only supports ISO-DEP (ISO 14443-4) protocol.
 * MIFARE Classic uses ISO 14443-3A with proprietary Crypto-1 authentication,
 * which CANNOT be emulated via Android HCE.
 *
 * CSC ServiceWorks readers use MIFARE Classic, so this service WILL NOT work
 * directly with CSC machines.
 *
 * For actual MIFARE Classic emulation, you need:
 * - Flipper Zero
 * - Proxmark3
 * - Chameleon Mini/Ultra
 * - ACR122U with custom firmware
 *
 * This service is provided for:
 * 1. Educational purposes
 * 2. Future compatibility if CSC ever updates to ISO-DEP
 * 3. Testing with ISO-DEP compatible readers
 * 4. Potential use with external emulation hardware via USB/BT
 */
class CSCCardEmulationService : HostApduService() {

    companion object {
        private const val TAG = "CSCCardHCE"

        // ISO 7816-4 Status Words
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_UNKNOWN = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())

        // Active emulation card (set from UI)
        @Volatile
        var activeCard: MasterCardData? = null

        @Volatile
        var isEmulating = false

        // Emulation mode
        @Volatile
        var hackMode = true  // true = block balance writes

        // Transaction logging
        private val transactionLog = mutableListOf<String>()

        fun getLog(): List<String> = transactionLog.toList()
        fun clearLog() = transactionLog.clear()

        private fun log(msg: String) {
            val entry = "[${System.currentTimeMillis() % 100000}] $msg"
            transactionLog.add(entry)
            Log.i(TAG, msg)
        }

        /**
         * Start emulation with the given card
         */
        fun startEmulation(card: MasterCardData, hackModeEnabled: Boolean = true) {
            activeCard = card
            hackMode = hackModeEnabled
            isEmulating = true
            clearLog()
            log("=== EMULATION STARTED ===")
            log("UID: ${card.uid}")
            log("Balance: ${card.balanceFormatted}")
            log("Mode: ${if (hackMode) "HACK (block charges)" else "LEGIT"}")
            log("")
            log("NOTE: Android HCE does NOT support MIFARE Classic!")
            log("This will only work with ISO-DEP readers.")
            log("For CSC machines, use Flipper Zero or Proxmark3.")
        }

        fun stopEmulation() {
            isEmulating = false
            log("=== EMULATION STOPPED ===")
            activeCard = null
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            log("ERROR: Invalid APDU received")
            return SW_UNKNOWN
        }

        val card = activeCard
        if (card == null || !isEmulating) {
            log("ERROR: No card loaded for emulation")
            return SW_UNKNOWN
        }

        // Log the incoming command
        val cmdHex = commandApdu.joinToString(" ") { "%02X".format(it) }
        log("<<< CMD: $cmdHex")

        // Parse APDU structure
        val cla = commandApdu[0].toInt() and 0xFF
        val ins = commandApdu[1].toInt() and 0xFF
        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF

        log("    CLA=$cla INS=$ins P1=$p1 P2=$p2")

        // Handle different command classes
        val response = when {
            // SELECT command (ISO 7816-4)
            cla == 0x00 && ins == 0xA4 -> handleSelect(commandApdu, card)

            // READ BINARY (ISO 7816-4)
            cla == 0x00 && ins == 0xB0 -> handleReadBinary(p1, p2, commandApdu, card)

            // UPDATE BINARY (ISO 7816-4) - for writes
            cla == 0x00 && ins == 0xD6 -> handleUpdateBinary(p1, p2, commandApdu, card)

            // GET DATA
            cla == 0x00 && ins == 0xCA -> handleGetData(p1, p2, card)

            // MIFARE specific commands (won't work via HCE but log them)
            ins == 0x60 || ins == 0x61 -> {
                log("    MIFARE AUTH command - NOT SUPPORTED via HCE!")
                log("    Use Flipper/Proxmark for MIFARE Classic emulation")
                SW_INS_NOT_SUPPORTED
            }

            else -> {
                log("    Unknown command")
                SW_INS_NOT_SUPPORTED
            }
        }

        val respHex = response.joinToString(" ") { "%02X".format(it) }
        log(">>> RSP: $respHex")

        return response
    }

    private fun handleSelect(apdu: ByteArray, card: MasterCardData): ByteArray {
        log("    SELECT command")

        // Return basic card info
        val fci = buildFCI(card)
        return fci + SW_OK
    }

    private fun handleReadBinary(p1: Int, p2: Int, apdu: ByteArray, card: MasterCardData): ByteArray {
        val offset = (p1 shl 8) or p2
        val length = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 16

        log("    READ BINARY: offset=$offset, len=$length")

        // Map offset to block number (16 bytes per block)
        val blockNum = offset / 16
        val blockOffset = offset % 16

        if (blockNum >= 64) {
            log("    ERROR: Block $blockNum out of range")
            return byteArrayOf(0x6A.toByte(), 0x82.toByte()) // File not found
        }

        val blockData = card.blocks[blockNum]
        val available = 16 - blockOffset
        val toRead = minOf(length, available)

        val data = blockData.copyOfRange(blockOffset, blockOffset + toRead)
        log("    Block $blockNum: ${data.joinToString(" ") { "%02X".format(it) }}")

        return data + SW_OK
    }

    private fun handleUpdateBinary(p1: Int, p2: Int, apdu: ByteArray, card: MasterCardData): ByteArray {
        val offset = (p1 shl 8) or p2
        val blockNum = offset / 16

        log("    UPDATE BINARY: block=$blockNum")

        // Extract data to write
        val dataLength = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
        val data = if (apdu.size > 5) apdu.copyOfRange(5, 5 + dataLength) else byteArrayOf()

        log("    Data: ${data.joinToString(" ") { "%02X".format(it) }}")

        // Check if this is a balance write (block 4 or 8)
        if (blockNum == 4 || blockNum == 8) {
            log("    *** BALANCE WRITE DETECTED ***")

            if (hackMode) {
                // Parse what the reader is trying to write
                if (data.size >= 4) {
                    val newBalance = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    val origBalance = card.balanceCents
                    val change = newBalance - origBalance

                    if (change < 0) {
                        log("    CHARGE BLOCKED! Reader tried: -\$${-change / 100}.${String.format("%02d", -change % 100)}")
                        log("    Balance unchanged: ${card.balanceFormatted}")
                        // Return success to fool the reader
                        return SW_OK
                    }
                }
                log("    HACK MODE: Write blocked, returning success")
                return SW_OK
            } else {
                log("    LEGIT MODE: Allowing balance write")
                // Actually update the card data
                System.arraycopy(data, 0, card.blocks[blockNum], 0, minOf(data.size, 16))
                return SW_OK
            }
        }

        // Allow other writes
        if (blockNum < 64 && data.isNotEmpty()) {
            System.arraycopy(data, 0, card.blocks[blockNum], 0, minOf(data.size, 16))
        }

        return SW_OK
    }

    private fun handleGetData(p1: Int, p2: Int, card: MasterCardData): ByteArray {
        log("    GET DATA: P1=$p1, P2=$p2")

        // Return UID
        if (p1 == 0x00 && p2 == 0x00) {
            val uid = card.uid.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return uid + SW_OK
        }

        return SW_INS_NOT_SUPPORTED
    }

    private fun buildFCI(card: MasterCardData): ByteArray {
        // File Control Information - simplified response
        return byteArrayOf(
            0x6F, 0x0E,  // FCI Template tag + length
            0x84.toByte(), 0x04,  // DF Name tag + length
            0x43, 0x53, 0x43, 0x00,  // "CSC" + null
            0x85.toByte(), 0x02,  // Proprietary data tag + length
            0x10, 0x00   // 1K card indicator
        )
    }

    override fun onDeactivated(reason: Int) {
        val reasonStr = when (reason) {
            DEACTIVATION_LINK_LOSS -> "Link Lost"
            DEACTIVATION_DESELECTED -> "Deselected"
            else -> "Unknown"
        }
        log("DEACTIVATED: $reasonStr")
    }
}
