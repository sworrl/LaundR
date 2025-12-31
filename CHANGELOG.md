# LaundR Project - Changelog

## Version 4.1 "Tumbling Typhoon" - 2025-12-24

### 🔧 Critical Fixes

#### Block 2 Checksum Implementation
- **Fixed:** Block 2 XOR checksum calculation (byte 15 = XOR of bytes 0-14)
- **Impact:** Cards now parse correctly on Flipper Zero
- **Implementation:**
  - Python GUI: `laundr.py` lines 1073-1076 ✅
  - Flipper App: `laundr_simple.c` lines 573-577 ✅

#### Refill Counter Discovery
- **Discovered:** Block 2 byte 5 is the "Refill Times Counter" displayed by Flipper
- **Previous Issue:** All test cards showed "2" for topup count (hardcoded)
- **Solution:** Generate random refill counters (3-200) for test cards
- **Note:** This is SEPARATE from:
  - Block 4 bytes 2-3 (transaction counter)
  - Block 9 bytes 2-3 (topup count in usage tracking)

#### Balance Overflow Fix
- **Fixed:** $666 and $1000 test cards exceeding 16-bit maximum
- **Root Cause:** Balance stored as 16-bit unsigned int (max 65535 cents = $655.35)
- **Examples of overflow:**
  - $666.00 (66600 cents) → wrapped to $10.64 (1064 cents)
  - $1000.00 (100000 cents) → wrapped to $344.64 (34464 cents)
- **Solution:**
  - Renamed `card_666.nfc` → `card_655.nfc` ($655.00)
  - Renamed `card_1000.nfc` → `card_655_35.nfc` ($655.35, max possible)

### ✨ Enhancements

#### Build Script Improvements
- **Added:** Auto-detection of mounted Flipper Zero
- **Added:** Auto-deploy option when Flipper is detected
- **Checks:** `/run/media/$USER/Flipper*/`, `/media/$USER/Flipper*/`, `/mnt/Flipper*/`
- **User Experience:** One-command build and deploy workflow

#### Test Card Generation
- **Created:** 6 comprehensive test cards with random but accurate data:

  | File | Balance | Refill Count | Transaction Counter | Usages Left | Topup Count (Block 9) |
  |------|---------|--------------|---------------------|-------------|-----------------------|
  | card_10.nfc | $10.00 | 22 | 106 | 6,432 | 10,528 |
  | card_50.nfc | $50.00 | 112 | 12 | 5,712 | 11,248 |
  | card_100.nfc | $100.00 | 148 | 72 | 12,781 | 4,179 |
  | card_420.nfc | $420.00 | 168 | 246 | 5,327 | 11,633 |
  | card_655.nfc | $655.00 | 5 | 74 | 7,134 | 9,826 |
  | card_655_35.nfc | $655.35 | 93 | 137 | 143 | 16,817 |

- **Features:**
  - Random 4-byte UIDs with correct BCC calculation
  - Valid Block 2 checksums
  - Proper value block format with inversions
  - Random usage counters (sum to 16960)
  - All verified to parse correctly on Flipper Zero

### 📚 Documentation

#### Created: CSC_CARD_FORMAT.md
- Complete reverse-engineered format specification
- Block-by-block structure documentation
- Checksum algorithms and validation rules
- Implementation status for both Python GUI and Flipper app
- Test file catalog with verification data
- Examples and edge cases

#### Updated: Python GUI Code
- Already had correct implementations (no changes needed):
  - Block 2 checksum ✓
  - UID BCC calculation ✓
  - Value block format ✓
- Code is monolithic and complete

#### Updated: Flipper App Code
- Added Block 2 checksum calculation in `laundr_update_balance()`
- Already had correct UID BCC calculation
- Already had correct value block format

### 🔬 Technical Discoveries

#### Block Structure Insights

**Block 0 (UID):**
- Bytes 0-3: UID
- Byte 4: BCC = XOR of all UID bytes
- Bytes 5-15: Manufacturer data (preserve)

**Block 2 (Transaction Metadata):**
- Byte 5: **Refill times counter** ← What Flipper displays!
- Bytes 9-10: Last topup amount (must be >= balance)
- Byte 15: XOR checksum (MANDATORY for parsing)

**Block 4/8 (Balance):**
- MIFARE Classic value block format
- Bytes 0-1: Balance (little-endian)
- Bytes 2-3: Transaction counter
- Bytes 4-11: Inversions and copies
- Bytes 12-15: Address field

**Block 9 (Usage Counters):**
- Bytes 0-1: Usages left (decrements)
- Bytes 2-3: Topup count (increments)
- **Rule:** usages_left + topup_count = 16960 (always!)
- Value block format with inversions

### 🛠️ Files Modified

#### Source Code
- `/home/eurrl/Documents/Code & Scripts/LaundR/flipper_app/laundr_simple.c`
  - Line 573-577: Added Block 2 checksum calculation
- `/home/eurrl/Documents/Code & Scripts/LaundR/flipper_app/build_flipper_app.sh`
  - Line 396-422: Added auto-deploy to mounted Flipper

#### Documentation
- `/home/eurrl/Documents/Code & Scripts/LaundR/CSC_CARD_FORMAT.md` (new)
- `/home/eurrl/Documents/Code & Scripts/LaundR/CHANGELOG.md` (new)

#### Test Cards (Desktop)
- `card_10.nfc`, `card_50.nfc`, `card_100.nfc`, `card_420.nfc` (updated)
- `card_655.nfc` (renamed from card_666.nfc, fixed balance)
- `card_655_35.nfc` (renamed from card_1000.nfc, fixed balance)

### ✅ Verification

All test cards verified with:
- ✓ Correct BCC calculation
- ✓ Valid Block 2 checksum
- ✓ Proper value block format
- ✓ Usage counters sum to 16960
- ✓ Parse successfully on Flipper Zero

### 🎯 Known Limitations

- Max balance: $655.35 (65535 cents, 16-bit limit)
- Max topup amount: $169.60 (16960 cents)
- Max usages: 16960 total
- Balances exceeding limits will overflow/wrap around

### 🔜 Future Enhancements

- Transaction simulation in Flipper app
- Balance increment/decrement on device
- Usage counter management
- UID randomization on emulation start
- Card cloning with new UID

---

## Version 4.0 "Tumbling Typhoon" - Previous

Initial release with shadow file system and non-destructive editing.
