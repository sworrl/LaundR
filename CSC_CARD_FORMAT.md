# CSC ServiceWorks Laundry Card Format
## MIFARE Classic 1K Block Structure Documentation

**Last Updated:** 2025-12-24
**Status:** Verified and Tested

---

## Overview

CSC ServiceWorks laundry cards use MIFARE Classic 1K with specific block structures for balance tracking, transaction history, and usage counters. This document details the complete format discovered through reverse engineering.

---

## Block 0 - UID and Manufacturer Data

**Format:**
```
Bytes 0-3:  UID (4 bytes)
Byte  4:    BCC (Block Check Character)
Bytes 5-15: Manufacturer data (preserve original)
```

**BCC Calculation:**
```python
bcc = uid[0] ^ uid[1] ^ uid[2] ^ uid[3]
```

**Example:**
```
Block 0: DE AD BE EF 50 08 04 00 04 F0 35 6B 3D B6 E9 90
         \_________/ |
         UID (4B)    BCC (0xDE ^ 0xAD ^ 0xBE ^ 0xEF = 0x50)
```

---

## Block 2 - Transaction Metadata

**Format:**
```
Bytes 0-1:   0x01 0x01 (CSC ServiceWorks signature)
Bytes 2-4:   Transaction ID (24-bit, increments with each use)
Byte  5:     Refill times counter (increments with each top-up) ← DISPLAYED AS "TOPUP COUNT"
Bytes 6-8:   Reserved/Unknown
Bytes 9-10:  Last topup amount (16-bit LE, in cents)
Bytes 11-14: Reserved/Unknown
Byte  15:    XOR Checksum
```

**Checksum Calculation:**
```python
checksum = 0
for i in range(15):  # XOR bytes 0-14
    checksum ^= block2[i]
block2[15] = checksum
```

**CRITICAL:**
- Block 2 will NOT parse without correct checksum!
- **Byte 5 (Refill times counter) is what the Flipper displays as "Topup Count"**
- This is separate from Block 4 bytes 2-3 (transaction counter)
- This is separate from Block 9 bytes 2-3 (topup count in usage tracking)

**Example:**
```
Block 2: 01 01 C5 CB AB 02 00 00 00 D0 07 01 00 00 00 71
         \___/ \________/ |     \___/                 |
         Sig   TX ID      Refills Last topup ($20)   Checksum
```

---

## Blocks 4 & 8 - Balance (Value Block Format)

Both blocks use MIFARE Classic value block format. Block 8 is a backup/mirror of Block 4.

**Format:**
```
Bytes 0-3:   Value (balance in cents, little-endian) + Counter
Bytes 4-7:   Inverted value + counter
Bytes 8-11:  Value copy + counter
Bytes 12-15: Address field (with inversions)
```

**Value Block Structure:**
```python
value_block = [
    value & 0xFF,              # Byte 0: Value low
    (value >> 8) & 0xFF,       # Byte 1: Value high
    0x02, 0x00,                # Bytes 2-3: Counter (little-endian)
    (value & 0xFF) ^ 0xFF,     # Byte 4: Inverted value low
    ((value >> 8) & 0xFF) ^ 0xFF,  # Byte 5: Inverted value high
    0xFD, 0xFF,                # Bytes 6-7: Inverted counter
    value & 0xFF,              # Byte 8: Value copy low
    (value >> 8) & 0xFF,       # Byte 9: Value copy high
    0x02, 0x00,                # Bytes 10-11: Counter copy
    0x04, 0xFB,                # Bytes 12-13: Address (0x04 and ~0x04)
    0x04, 0xFB                 # Bytes 14-15: Address copy
]
```

**Example - $29.00 (2900 cents = 0x0B54):**
```
Block 4: 54 0B 02 00 AB F4 FD FF 54 0B 02 00 04 FB 04 FB
         \______/ |     \______/ |     \______/ |
         Value    Cnt   ~Value   ~Cnt  Copy     Addr
```

---

## Block 9 - Usage Counters (Value Block Format)

Stores usages left and topup count.

**Important Rule:** `usages_left + topup_count = 16960` (always!)

**Format:**
```
Bytes 0-1:   Usages left (16-bit LE)
Bytes 2-3:   Topup count (16-bit LE)
Bytes 4-7:   Inverted counters
Bytes 8-11:  Counter copies
Bytes 12-15: Address field (0x09 0xF6 0x09 0xF6)
```

**Example - 16957 usages left, 3 topups:**
```
Block 9: 3D 42 03 00 C2 BD FC FF 3D 42 03 00 09 F6 09 F6
         \___/ \___/ \______/ |     \______/ |
         16957 3     ~Values  ~Cnt  Copies   Addr
```

**Note:** On each machine use, usages_left decrements and topup_count increments.

---

## Transaction Simulation

To simulate a $20 topup on a card with $9.00:

1. **Update Block 2:**
   - Increment Transaction ID (bytes 2-4)
   - Increment Refill times (byte 5)
   - Set Last topup = $20.00 = 2000 cents = 0x07D0 (bytes 9-10)
   - **Recalculate checksum (byte 15)** ← CRITICAL!

2. **Update Blocks 4 & 8:**
   - New balance = $9.00 + $20.00 = $29.00 = 2900 cents = 0x0B54
   - Build value block with inversions

3. **Update Block 9:**
   - Decrement usages_left by 1
   - Increment topup_count by 1
   - Verify: usages_left + topup_count = 16960

---

## Implementation Status

### ✅ Python GUI (laundr.py)
- Block 0 UID + BCC: **IMPLEMENTED** (lines 2084-2086)
- Block 2 Checksum: **IMPLEMENTED** (lines 1073-1076)
- Block 4/8 Value Blocks: **IMPLEMENTED** (lines 919-930)
- Block 9 Usage Counters: **IMPLEMENTED**

### ✅ Flipper Zero App (laundr_simple.c)
- Block 0 UID + BCC: **IMPLEMENTED** (line 953)
- Block 2 Checksum: **IMPLEMENTED** (lines 573-577)
- Block 4/8 Value Blocks: **IMPLEMENTED** (lines 545-558)
- Block 9 Usage Counters: **IMPLEMENTED** (lines 576-594)

---

## Test Files Created

All files verified to parse correctly on Flipper Zero:

| File | Balance | UID | Usages Left | Topup Count | Purpose |
|------|---------|-----|-------------|-------------|---------|
| `card_10.nfc` | $10.00 | 65 82 E5 D1 | 15055 | 1905 | Test card |
| `card_50.nfc` | $50.00 | DB DC DA 74 | 1166 | 15794 | Test card |
| `card_100.nfc` | $100.00 | D6 11 57 BD | 2169 | 14791 | Test card |
| `card_420.nfc` | $420.00 | 96 4A 67 9C | 10293 | 6667 | Test card |
| `card_655.nfc` | $655.00 | 1B 51 41 04 | 1665 | 15295 | Near-max balance |
| `card_655_35.nfc` | $655.35 | 35 72 12 40 | 2803 | 14157 | **MAX balance** |

**Note:** Balance is limited to 16-bit unsigned integer (0-65535 cents = $0.00-$655.35)

---

## References

- [MIFARE Classic Value Block Format](https://docs.springcard.com/books/SpringCore/PCSC_Operation/APDU_Interpreter/Vendor_instructions/MIFARE_CLASSIC_VALUE)
- [Adafruit MIFARE Guide](https://learn.adafruit.com/adafruit-pn532-rfid-nfc/mifare)
- [MIFARE Classic 1K Datasheet](https://www.nxp.com/docs/en/data-sheet/MF1S50YYX_V1.pdf)

---

## Notes

- **Max balance: $655.35** (65535 cents, 16-bit unsigned limit)
  - Balances exceeding this will **overflow** and wrap around
  - Example: $666.00 (66600 cents) → parses as $10.64 (1064 cents)
  - Example: $1000.00 (100000 cents) → parses as $344.64 (34464 cents)
- Max topup amount: $169.60 (16960 cents)
- Max usages: 16960 total (usages_left + topup_count must always = 16960)
- Block 2 checksum is **mandatory** for Flipper parsing
- BCC must be correct or card will not be recognized
- All value blocks use little-endian byte order
