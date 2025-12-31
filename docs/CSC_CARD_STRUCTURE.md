# CSC ServiceWorks MIFARE Classic 1K Card Structure

## Overview
CSC ServiceWorks laundry cards use MIFARE Classic 1K chips with a specific data layout.
This document describes the block structure for security research purposes.

## Card Specifications
- **Type:** MIFARE Classic 1K
- **Memory:** 1024 bytes (64 blocks x 16 bytes)
- **Sectors:** 16 sectors (4 blocks each)
- **Authentication:** Key A = `EE B7 06 FC 71 4F` (sectors 0-3)

---

## Block Structure

### Block 0 - Manufacturer Block (Read-Only on real cards)
| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0-3 | 4 | UID | Unique Identifier (factory programmed) |
| 4 | 1 | BCC | Block Check Character (XOR of UID bytes) |
| 5 | 1 | SAK | Select Acknowledge (0x08 = Classic 1K) |
| 6-7 | 2 | ATQA | Answer To Request Type A (0x0004) |
| 8-15 | 8 | MFR Data | Manufacturer data (batch/lot info) |

**Example:** `DB DC DA 74 A9 08 04 00 04 F0 35 6B 3D B6 E9 90`

---

### Block 1 - Card Configuration
| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0-1 | 2 | Prefix | Card prefix ASCII "00" (0x30 0x30) |
| 2-3 | 2 | Version | Card version (0x0001) |
| 4-5 | 2 | Reserved | Zero padding |
| 6-7 | 2 | Issuer | Issuer code (0x8401 little-endian) |
| 8-9 | 2 | System | System ID ASCII "(0" |
| 10-11 | 2 | Reserved | Zero padding |
| 12-13 | 2 | Flags | Configuration flags (0x01 0x11) |
| 14-15 | 2 | Checksum | Block checksum |

**Example:** `30 30 00 01 00 00 01 84 28 30 00 00 01 11 EE 62`

---

### Block 2 - Card Serial & Balance Backup
| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0-1 | 2 | Header | Version indicator (0x0101) |
| 2-4 | 3 | Type ID | CSC card type (0xC5CBAB) |
| 5 | 1 | Serial | Card serial number (unique per card) |
| 6-8 | 3 | Reserved | Zero padding |
| 9-10 | 2 | Balance | Balance backup (little-endian, cents) |
| 11 | 1 | Flags | Card flags (0x01) |
| 12-14 | 3 | Reserved | Zero padding |
| 15 | 1 | Checksum | Block checksum |

**Example:** `01 01 C5 CB AB 70 00 00 00 88 13 01 00 00 00 4F`
- Serial: 0x70 (112)
- Balance: 0x1388 = 5000 cents = $50.00

---

### Block 3 - Sector 0 Trailer (Keys & Access Bits)
| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0-5 | 6 | Key A | Authentication Key A |
| 6-9 | 4 | Access | Access control bits |
| 10-15 | 6 | Key B | Authentication Key B (often unknown) |

**Example:** `EE B7 06 FC 71 4F 78 77 88 00 ?? ?? ?? ?? ?? ??`
- Key A: `EE B7 06 FC 71 4F`

---

### Block 4 - Main Balance Block (Sector 1, Block 0)
| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0-1 | 2 | Balance | Current balance (little-endian, cents) |
| 2-3 | 2 | Counter | Transaction counter |
| 4-5 | 2 | Bal Inv | Balance XOR 0xFFFF (validation) |
| 6-7 | 2 | Cnt Inv | Counter XOR 0xFFFF (validation) |
| 8-9 | 2 | Bal Copy | Balance redundant copy |
| 10-11 | 2 | Cnt Copy | Counter redundant copy |
| 12-13 | 2 | Checksum1 | First checksum |
| 14-15 | 2 | Checksum2 | Second checksum |

**Example:** `88 13 0C 00 77 EC F3 FF 88 13 0C 00 04 FB 04 FB`
- Balance: 0x1388 = $50.00
- Counter: 0x000C = 12 transactions
- Validation: Balance XOR Bal_Inv = 0xFFFF (valid)

---

### Block 8 - Balance Mirror (Sector 2, Block 0)
Same structure as Block 4. Provides redundancy for balance data.

---

### Block 9 - Transaction Timestamp/Hash
| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0-3 | 4 | Timestamp | Transaction timestamp or hash |
| 4-7 | 4 | TS Inv | Timestamp inverted (validation) |
| 8-11 | 4 | TS Copy | Timestamp redundant copy |
| 12-15 | 4 | Checksum | Block checksum |

**Example:** `50 16 F0 2B AF E9 0F D4 50 16 F0 2B 09 F6 09 F6`
- Unique per card, changes with each transaction

---

### Block 10 - Network/System Identifier
| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0-1 | 2 | Prefix | Card prefix "00" |
| 2-3 | 2 | Version | Version (0x0001) |
| 4-5 | 2 | Reserved | Zero padding |
| 6-7 | 2 | Issuer | Issuer code (0x8401) |
| 8-9 | 2 | System | System prefix "(0" |
| 10-12 | 3 | Network | Network ID "NET" |
| 13 | 1 | Flags | System flags (0x11) |
| 14-15 | 2 | Reserved | Zero padding |

**Example:** `30 30 00 01 00 00 01 84 28 30 4E 45 54 11 00 00`

---

### Block 13 - Location/Site Identifier (SENSITIVE!)
| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0-8 | 9 | Site Code | ASCII site/location identifier |
| 9-10 | 2 | Version | Site version (0x0202) |
| 11-15 | 5 | Reserved | Zero padding |

**Example:** `41 5A 37 36 30 32 30 34 36 02 02 00 00 00 00 00`
- Site Code: "AZ7602046"

**WARNING:** This field identifies the physical location where cards are used!

---

## Identifying Fields Summary

| Block | Field | Unique? | Risk Level |
|-------|-------|---------|------------|
| 0 | UID | Per card | Medium |
| 0 | Manufacturer Data | Per batch | Low |
| 2 | Card Serial | Per card | Medium |
| 9 | Timestamp | Per card | Low |
| 13 | Site Code | Per location | **HIGH** |

---

## Balance Encoding

Balance is stored in cents as a 16-bit little-endian value with XOR validation:

```
Balance: $50.00 = 5000 cents = 0x1388
Little-endian: 88 13
Inverted (XOR 0xFFFF): 77 EC

Block 4 format: [Balance][Counter][Bal_Inv][Cnt_Inv][Balance][Counter][Checksum][Checksum]
Example:        88 13    0C 00    77 EC    F3 FF    88 13    0C 00    04 FB    04 FB
```

---

## Sanitization Guidelines

To anonymize a card file, randomize:
1. **Block 0 [0-4]:** UID and BCC
2. **Block 0 [8-15]:** Manufacturer data
3. **Block 2 [5]:** Card serial number
4. **Block 9:** Transaction timestamp
5. **Block 13 [0-8]:** Site code

---

*Document generated for LaundR security research project*
*Last updated: 2024-12-28*
