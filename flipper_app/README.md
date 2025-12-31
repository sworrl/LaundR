# LaundR - Flipper Zero App

**Security Research Tool for Testing Laundry Card Transaction Protocols**

---

## ⚠️ LEGAL DISCLAIMER

This application is provided for **security research and educational purposes only**.

**Authorized Use Only:**
- Testing your own laundry cards
- Understanding payment system protocols
- Security research with explicit permission
- Educational demonstrations

**Prohibited Use:**
- Defrauding laundry service providers
- Using on cards you don't own
- Bypassing payment systems
- Any unauthorized access

**You are solely responsible for ensuring your use complies with all applicable laws.**

---

## What This App Does

LaundR is a Flipper Zero application that emulates a MIFARE Classic laundry card and **intercepts transaction attempts** from laundry machine readers. It helps you understand:

1. **Does the reader operate offline or online?**
   - Offline: Reader reads balance, deducts cost, writes back
   - Online: Reader validates with server before/after transaction

2. **What blocks does the reader access?**
   - Block 4 (balance)?
   - Block 2 (transaction log)?
   - Block 9 (usage counter)?

3. **Can transactions be faked?**
   - Does the reader verify the write succeeded?
   - Can you reject writes but still start the machine?

---

## Features

### 🎯 Two Modes

**Testing Mode (Default):**
- Emulates your card perfectly
- **Ignores all write operations** from the reader
- Reader thinks it successfully deducted balance
- Your emulated card balance **stays the same**
- Logs all read/write attempts for analysis

**Normal Mode:**
- Emulates your card normally
- **Applies all writes** from the reader
- Balance actually decreases
- Useful for comparison testing

### 📊 Transaction Logging

Logs every operation the reader performs:
- **Block number** accessed
- **Operation type** (read/write/auth)
- **Data** read or written
- **Timestamp** of operation

### 📈 Statistics

Real-time display:
- Total reads
- Total writes
- Total authentications
- Current balance
- Original balance (for comparison)

---

## How It Works

### Normal Laundry Transaction (What Happens Normally)

```
1. You insert card into reader
2. Reader authenticates with Key A
3. Reader READS Block 4 (current balance: $29.00)
4. Reader checks: $29.00 >= $3.00? YES
5. Reader authenticates with Key B (write key)
6. Reader WRITES Block 4 (new balance: $26.00)
7. Reader WRITES Block 2 (transaction log)
8. Reader WRITES Block 8 (backup balance)
9. Machine starts
```

### LaundR Testing Mode (What We're Testing)

```
1. You emulate card with Flipper
2. Reader authenticates with Key A ✓
3. Reader READS Block 4 (LaundR reports: $29.00)
4. Reader checks: $29.00 >= $3.00? YES
5. Reader authenticates with Key B ✓
6. Reader WRITES Block 4 (new balance: $26.00)
   └─> LaundR IGNORES this write!
   └─> Balance stays at $29.00 in emulation
7. Reader WRITES Block 2
   └─> LaundR IGNORES this too!
8. Reader WRITES Block 8
   └─> LaundR IGNORES this too!
9. Does the machine start? ← THIS IS WHAT WE'RE TESTING!
```

---

## Test Scenarios

### Scenario 1: Offline Reader (No Server Validation)

**Expected behavior:**
- Reader reads balance: $29.00
- Reader writes new balance: $26.00
- **Machine starts immediately**
- Reader doesn't verify write succeeded
- LaundR's balance stays $29.00
- **Result: Transaction completed without actually paying!**

**This means:**
- Reader operates completely offline
- No server validation
- Reader trusts the card write succeeded
- Security vulnerability!

---

### Scenario 2: Online Reader (Server Validation)

**Expected behavior:**
- Reader reads balance: $29.00
- Reader contacts server: "Card UID ABC123 wants to start for $3.00"
- Server checks database
- Server responds: "Approved"
- Reader writes new balance: $26.00
- Machine starts
- **Server logs transaction in backend database**

**Even if we ignore writes:**
- LaundR balance stays $29.00
- But server has $26.00 recorded
- Next legitimate top-up might fail or show wrong balance
- **Result: Detection likely**

**This means:**
- Reader validates with server
- Card is secondary storage
- Server is source of truth

---

### Scenario 3: Hybrid (Write Verification)

**Expected behavior:**
- Reader reads balance: $29.00
- Reader writes new balance: $26.00
- **Reader reads back to verify write**
- Reader sees $29.00 (because we ignored write)
- **Machine doesn't start - error displayed**

**This means:**
- Reader verifies writes succeeded
- LaundR detected immediately
- Good security practice

---

### Scenario 4: Pre-authorized Start

**Expected behavior:**
- Reader reads balance: $29.00
- **Machine starts immediately** (reader sends start signal before writing)
- Reader writes new balance: $26.00
- LaundR ignores write
- **Result: Free wash, but detection risk**

**This means:**
- Reader trusts card before validation
- Poor security design

---

## Usage

### 1. Prepare Your Card Dump

First, dump your legitimate laundry card:

```bash
# On Flipper Zero
NFC → Read Card → Save

# Or use LaundR on PC
python3 laundr.py your_card.nfc
```

Make sure you have a working `.nfc` file with:
- All blocks read
- Valid keys (at least Key A)
- Current balance showing correctly

---

### 2. Install LaundR App

**Option A: Build from source (requires Flipper Build Tool)**

```bash
# Clone Flipper firmware
git clone https://github.com/flipperdevices/flipperzero-firmware.git
cd flipperzero-firmware

# Copy LaundR app to applications_user
cp -r /path/to/LaundR/flipper_app applications_user/laundr

# Build
./fbt fap_laundr

# Install (Flipper connected via USB)
./fbt launch_app APPSRC=applications_user/laundr
```

**Option B: Use pre-built .fap file (easier)**

```bash
# Copy .fap file to Flipper SD card
# SD:/apps/NFC/laundr.fap

# Restart Flipper
# Apps → NFC → LaundR
```

---

### 3. Run Test

**Step 1: Load your card**
1. Open LaundR app on Flipper
2. Select "Load Card"
3. Choose your saved .nfc file
4. App loads card data and shows balance

**Step 2: Verify mode**
1. Check that mode shows "TESTING"
2. If not, select "Toggle Mode"
3. Testing mode = writes ignored

**Step 3: Start emulation**
1. Select "Start Emulation"
2. Screen shows "EMULATING CARD"
3. Hold Flipper near laundry machine reader
4. Watch the screen for activity

**Step 4: Observe behavior**
1. Watch statistics:
   - Reads: How many blocks were read
   - Writes: How many blocks were written
   - Balance: Does it change?
2. Watch machine:
   - Does it start?
   - Does it show error?
   - Does it display countdown?

**Step 5: Check log**
1. Stop emulation
2. Select "View Log"
3. Review what blocks were accessed:
   - Block 0: UID check
   - Block 2: Transaction log
   - Block 4: Balance read/write
   - Block 8: Backup balance

---

### 4. Analyze Results

**If machine starts AND balance stays the same:**
- ✅ Reader operates offline
- ✅ No write verification
- ⚠️ Security vulnerability confirmed!

**If machine shows error:**
- ✅ Reader verifies writes
- ✅ Good security
- ℹ️ System detects manipulation

**If machine starts BUT shows wrong balance next time:**
- ✅ Reader has server backend
- ⚠️ Server tracks transactions
- ⚠️ Mismatch will be detected

---

## Code Structure

```c
// Main app state
typedef struct {
    bool emulating;           // Currently emulating?
    bool apply_changes;       // Apply writes or ignore them?
    TransactionLogEntry log[64];  // Operation log
    uint16_t original_balance;    // Starting balance
    uint16_t current_balance;     // Current balance
    uint32_t read_count;      // Statistics
    uint32_t write_count;
    uint32_t auth_count;
} LaundRApp;

// Read callback - called when reader reads a block
bool laundr_read_callback(uint8_t block_num, ...) {
    // Log the read
    // Return block data
    return true; // Allow read
}

// Write callback - called when reader writes a block
bool laundr_write_callback(uint8_t block_num, ...) {
    // Log the write attempt

    if (app->apply_changes) {
        // Normal mode: apply write
        return true;
    } else {
        // Testing mode: ignore write
        return false; // Reject write
    }
}

// Auth callback - called when reader authenticates
bool laundr_auth_callback(uint8_t block_num, ...) {
    // Log authentication
    // Always allow with known keys
    return true;
}
```

---

## Building the App

### Prerequisites

1. **Flipper Zero** (any firmware)
2. **Flipper Build Tool** (fbt)
3. **Git**
4. **GCC ARM toolchain**

### Build Steps

```bash
# 1. Clone Flipper firmware repository
git clone --recursive https://github.com/flipperdevices/flipperzero-firmware.git
cd flipperzero-firmware

# 2. Copy LaundR app into applications_user
mkdir -p applications_user
cp -r /path/to/LaundR/flipper_app applications_user/laundr

# 3. Build the FAP
./fbt fap_laundr

# 4. Output will be in:
# build/f7-firmware-D/apps_data/laundr/laundr.fap

# 5. Copy to Flipper SD card:
# SD:/apps/NFC/laundr.fap
```

### Install on Flipper

```bash
# Option 1: Via USB with qFlipper
# - Connect Flipper via USB
# - Open qFlipper
# - Navigate to SD:/apps/NFC/
# - Upload laundr.fap

# Option 2: Via SD card directly
# - Remove SD card from Flipper
# - Insert into PC
# - Copy laundr.fap to SD:/apps/NFC/
# - Insert SD back into Flipper

# Option 3: Via fbt (if connected)
./fbt launch_app APPSRC=applications_user/laundr
```

---

## Transaction Log Example

```
=== TRANSACTION LOG ===
[0ms]    AUTH Block 0 (Key A) ✓
[10ms]   READ Block 0 (UID)
[15ms]   READ Block 4 (Balance: $29.00)
[20ms]   AUTH Block 4 (Key B) ✓
[25ms]   WRITE Block 4 (New: $26.00) ← IGNORED
[30ms]   WRITE Block 2 (Transaction) ← IGNORED
[35ms]   WRITE Block 8 (Backup) ← IGNORED
[40ms]   READ Block 4 (Verify)
[50ms]   Connection lost

Result: 3 reads, 3 writes (all ignored)
Balance: $29.00 (unchanged)
Machine: ???
```

---

## Expected Reader Behaviors

### Good Security (Recommended)

```c
1. READ Block 4 (get balance)
2. Validate balance >= cost
3. Send request to server → wait for approval
4. WRITE Block 4 (new balance)
5. READ Block 4 (verify write)
6. If verified → start machine
7. If not verified → show error
```

### Poor Security (Vulnerable)

```c
1. READ Block 4 (get balance)
2. Validate balance >= cost
3. WRITE Block 4 (new balance)
4. Start machine immediately
5. No server validation
6. No write verification
```

---

## Interpreting Test Results

| Observation | Meaning | Security Rating |
|-------------|---------|-----------------|
| Machine starts, balance unchanged | Offline, no verification | ❌ Very Poor |
| Machine starts, server shows lower balance | Online, but card not verified | ⚠️ Poor |
| Machine shows error | Write verification works | ✅ Good |
| Machine starts only after server response | Online validation | ✅ Good |
| Card locked after test | Anti-tampering detection | ✅ Excellent |

---

## Troubleshooting

### App won't compile
```bash
# Make sure you're using latest firmware
git pull
git submodule update --init --recursive

# Clean build
./fbt clean
./fbt fap_laundr
```

### Flipper won't emulate card
- Check that .nfc file has all keys
- Verify card type is MIFARE Classic 1K
- Make sure firmware supports NFC emulation

### Reader doesn't respond
- Check card orientation (try flipping)
- Hold steady for 3-5 seconds
- Ensure reader is powered on
- Try with real card first to verify reader works

### Balance shows wrong amount
- Reload the .nfc file
- Check Block 4 in hex editor
- Verify endianness (little-endian)

---

## Research Questions to Answer

1. **Does the reader validate writes?**
   - Start machine → check balance
   - Did balance stay same? → No validation

2. **Is there a backend server?**
   - Use card legitimately several times
   - Check if balance matches card or differs
   - If differs → server is source of truth

3. **What blocks are critical?**
   - Check transaction log
   - Most-read block = critical for validation

4. **Can you replay transactions?**
   - Save card state before wash
   - Do wash
   - Restore old state
   - Try wash again

5. **What keys does reader use?**
   - Check auth log
   - Key A only = read-only access
   - Key B used = write access granted

---

## Next Steps

Based on test results:

**If offline reader (no validation):**
- Document the vulnerability
- Report to operator (responsible disclosure)
- Propose server-side validation

**If online reader:**
- Test if server detects mismatches
- See how system handles desync
- Check if card gets blacklisted

**If hybrid:**
- Understand the validation logic
- Test edge cases
- Document the security model

---

## Contributing

Found interesting behavior? Have improvements?

1. Document your findings
2. Submit issue or PR to LaundR repository
3. Share anonymized transaction logs
4. Help improve security awareness

---

## References

### NFC Protocol Docs
- [MIFARE Classic Datasheet](https://www.nxp.com/docs/en/data-sheet/MF1S50YYX_V1.pdf)
- [Flipper NFC Dev Docs](https://developer.flipper.net/flipperzero/doxygen/nfc.html)
- [ISO/IEC 14443-3 Type A](https://www.iso.org/standard/73596.html)

### Flipper Development
- [Flipper App Development](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/documentation/AppsOnSDCard.md)
- [FAP File Format](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/documentation/FapFileFormat.md)
- [NFC API Reference](https://github.com/flipperdevices/flipperzero-firmware/tree/dev/lib/nfc)

### Security Research
- [Hacking Laundry Cards](https://www.runthebot.me/blog/Hacking%20Laundry%20Cards)
- [MIFARE Classic Vulnerabilities](https://www.cs.ru.nl/~flaviog/publications/Attack-RFID-2008.pdf)
- [Card Payment Security](https://www.pcisecuritystandards.org/)

---

## License

MIT License - See LICENSE file

**Security Disclaimer:** This tool is for authorized security research only. Unauthorized use may violate computer fraud laws, theft of service laws, and terms of service agreements. Always obtain explicit permission before testing systems you don't own.

---

**Built with ❤️ for security researchers and the Flipper Zero community**

---

## Recent Updates (v4.1 - 2025-12-24)

### Critical Fixes
- ✅ **Block 2 Checksum** - Cards now parse correctly (XOR of bytes 0-14)
- ✅ **Refill Counter** - Block 2 byte 5 now properly randomized  
- ✅ **Balance Limits** - Fixed overflow for balances > $655.35
- ✅ **Auto-Deploy** - Build script now auto-detects mounted Flipper

### Build Script Enhancements
```bash
./build_flipper_app.sh
```

The build script now:
- Auto-detects mounted Flipper Zero SD card
- Offers one-click deployment after build
- Caches firmware (skip 1-2 GB download)
- Supports Official, Momentum, Unleashed, RogueMaster

### Documentation Added
- `CSC_CARD_FORMAT.md` - Complete block format specification
- `CHANGELOG.md` - Detailed change history
- Test cards with random but accurate data

### For More Details
- **Format Spec:** See `CSC_CARD_FORMAT.md`
- **Build Guide:** Run `./build_flipper_app.sh`
- **Changelog:** See `CHANGELOG.md`
