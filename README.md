<p align="center">
  <img src="assets/laundr_logo.png" alt="LaundR Logo" width="200" height="200">
</p>

<h1 align="center">LaundR</h1>
<h3 align="center">Universal Laundry Card Security Research Suite</h3>

<p align="center">
  <em>Multi-platform forensic analysis toolkit for CSC ServiceWorks laundry card security research</em>
</p>

<p align="center">
  <a href="#-quick-start">Quick Start</a> •
  <a href="#-components">Components</a> •
  <a href="#-flipper-zero-app">Flipper App</a> •
  <a href="#-card-format">Card Format</a> •
  <a href="#-api-reference">API</a> •
  <a href="#-license">License</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Version-5.61-blue?style=for-the-badge" alt="Version 5.61">
  <img src="https://img.shields.io/badge/Android-14+-green?style=for-the-badge&logo=android&logoColor=white" alt="Android 14+">
  <img src="https://img.shields.io/badge/Flipper_Zero-Unleashed-orange?style=for-the-badge" alt="Flipper Zero">
  <img src="https://img.shields.io/badge/Python-3.8+-3776AB?style=for-the-badge&logo=python&logoColor=white" alt="Python 3.8+">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/License-Educational_Research-red?style=flat-square" alt="License">
  <img src="https://img.shields.io/badge/CVE--2025--46018-Documented-critical?style=flat-square" alt="CVE-2025-46018">
  <img src="https://img.shields.io/badge/CVE--2025--46019-Documented-critical?style=flat-square" alt="CVE-2025-46019">
  <img src="https://img.shields.io/badge/PRs-Welcome-ff69b4?style=flat-square" alt="PRs Welcome">
</p>

---

## Table of Contents

<details>
<summary>Click to expand</summary>

- [Legal Disclaimer](#-legal-disclaimer)
- [Quick Start](#-quick-start)
- [Components](#-components)
- [LaunDRoid (Android)](#-laundroid-android-app)
- [Flipper Zero App](#-flipper-zero-app)
  - [Features](#features)
  - [Installation](#installation)
  - [Building from Source](#building-from-source)
  - [Usage Guide](#usage-guide)
  - [HACK vs LEGIT Mode](#hack-vs-legit-mode)
  - [Transaction Logging](#transaction-logging)
  - [Operational Security](#operational-security)
- [Python Desktop Tool](#-python-desktop-tool)
- [CSC Card Format](#-csc-card-format-specification)
  - [Block Structure](#block-structure)
  - [Balance Format](#balance-format-blocks-4--8)
  - [Transaction Metadata](#transaction-metadata-block-2)
  - [Usage Counters](#usage-counters-block-9)
- [Security Research](#-security-research)
  - [Documented Vulnerabilities](#documented-vulnerabilities)
  - [Test Scenarios](#test-scenarios)
- [Key Dictionary](#-key-dictionary)
- [Project Structure](#-project-structure)
- [Troubleshooting](#-troubleshooting)
- [Contributing](#-contributing)
- [Changelog](#-changelog)
- [License](#-license)
- [Credits](#-credits)

</details>

---

## Legal Disclaimer

> [!CAUTION]
> **FOR AUTHORIZED SECURITY RESEARCH ONLY**

This toolkit is designed for security researchers studying vulnerabilities in laundry card payment systems. The documented vulnerabilities (CVE-2025-46018, CVE-2025-46019) affect millions of laundry machines worldwide.

<table>
<tr>
<td width="50%">

**Authorized Use:**
- Testing your own laundry cards
- Understanding payment system protocols
- Security research with explicit permission
- Educational demonstrations
- Responsible disclosure research

</td>
<td width="50%">

**Prohibited Use:**
- Defrauding laundry service providers
- Using on cards you don't own
- Bypassing payment systems
- Theft of services
- Any unauthorized access

</td>
</tr>
</table>

**You are solely responsible for ensuring your use complies with all applicable laws.**

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## Quick Start

> [!TIP]
> The Flipper Zero app is the fastest way to get started for field research!

### One-Command Install (Flipper Zero)

```bash
# Clone and build
git clone https://github.com/yourusername/LaundR.git
cd LaundR/flipper_app
./build_flipper_app.sh

# Copy to Flipper
# Output: dist/laundr.fap → SD:/apps/NFC/
```

### One-Command Install (Android)

```bash
# Linux/macOS
./scripts/deploy_android.sh

# Windows
scripts\deploy_android.bat
```

### Quick Test (No Hardware)

```bash
# Python desktop tool
pip install tkinter
python3 python/laundr.py
```

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## Components

| Component | Platform | Description | Version |
|-----------|----------|-------------|---------|
| **LaundR** | Flipper Zero | Portable card analyzer & emulator with HACK mode | v5.61 |
| **LaunDRoid** | Android | Mobile NFC/BLE security audit tool | v1.7.0 |
| **laundr.py** | Python | Desktop forensic analysis GUI | v2.1 |

### System Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        LaundR Ecosystem                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐          │
│  │   Android    │    │ Flipper Zero │    │   Desktop    │          │
│  │  LaunDRoid   │    │   LaundR     │    │  laundr.py   │          │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘          │
│         │                   │                   │                   │
│         ▼                   ▼                   ▼                   │
│  ┌──────────────────────────────────────────────────────┐          │
│  │              .nfc / .laundr File Format              │          │
│  │         (Compatible across all platforms)            │          │
│  └──────────────────────────────────────────────────────┘          │
│                            │                                        │
│                            ▼                                        │
│  ┌──────────────────────────────────────────────────────┐          │
│  │           CSC ServiceWorks Laundry Reader            │          │
│  │      (MIFARE Classic 1K NFC Communication)           │          │
│  └──────────────────────────────────────────────────────┘          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## LaunDRoid (Android App)

**v1.7.0 "Crusty Agitate Goblin"**

Full-featured Android security research tool with cyberpunk UI.

### Features

<table>
<tr>
<td width="50%">

**NFC Card Analysis**
- Dictionary Attack with 5,000+ keys
- Real-time progress with ETA
- CSC auto-detection
- Balance extraction
- Card removed alerts

</td>
<td width="50%">

**Attack Strategies**
- `→FWD` - Forward sweep
- `←REV` - Reverse sweep
- `◇MID` - Middle-out
- `⚄RND` - Random shuffle
- `▦CHK` - Chunked blocks
- `↔INT` - Interleaved

</td>
</tr>
</table>

### Export Formats

| Format | Description |
|--------|-------------|
| `.nfc` | Standard Flipper Zero format |
| `.laundr` | Extended format with metadata (MasterCard flag, MaxValue, AutoReloader) |
| `.json` | Full data dump with keys |

### BLE Scanner

- Discovers CSC laundry machines via Bluetooth
- Service UUID detection for ServiceWorks devices
- Signal strength monitoring
- Machine organization by laundry room

### Building from Source

```bash
cd android_app
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## Flipper Zero App

**v5.61 "Purple Thunder"** - Portable MIFARE Classic analyzer and emulator

<p align="center">
  <img src="https://img.shields.io/badge/HACK_Mode-Writes_Blocked-red?style=for-the-badge" alt="HACK Mode">
  <img src="https://img.shields.io/badge/MFKey32-Nonce_Capture-blue?style=for-the-badge" alt="MFKey32">
  <img src="https://img.shields.io/badge/Purple_LED-Emulating-purple?style=for-the-badge" alt="Purple LED">
</p>

### Features

| Feature | Description |
|---------|-------------|
| **HACK Mode** | Blocks write operations - balance never decreases |
| **LEGIT Mode** | Allows normal operations for comparison testing |
| **MFKey32 Capture** | Passive nonce harvesting for key recovery |
| **Transaction Stats** | Persistent counters across sessions |
| **UID Rotation** | Auto-randomize UID between transactions |
| **Master Card** | Built-in CSC test card generator |
| **Purple LED** | Solid purple indicator when emulating |

### Installation

**Option A: Pre-built Binary**

```bash
# Copy to Flipper SD card
cp releases/flipper/laundr.fap /media/Flipper/apps/NFC/

# Or use qFlipper to transfer
```

**Option B: Build from Source**

```bash
cd flipper_app
./build_flipper_app.sh
# Output: dist/laundr.fap
```

### Building from Source

#### Prerequisites

- Flipper Zero (any firmware)
- Computer with USB or SD card reader
- Git and Python 3

#### Automated Build

```bash
cd flipper_app
./build_flipper_app.sh

# Options:
./build_flipper_app.sh -b              # Build only (use cached firmware)
./build_flipper_app.sh -d              # Build and deploy to Flipper
./build_flipper_app.sh -f momentum     # Use Momentum firmware
./build_flipper_app.sh -f unleashed    # Use Unleashed firmware
./build_flipper_app.sh -c              # Clean build
```

#### Manual Build

```bash
# Clone firmware
git clone --depth 1 https://github.com/DarkFlippers/unleashed-firmware.git
cd unleashed-firmware

# Copy LaundR source
mkdir -p applications_user/laundr
cp /path/to/LaundR/flipper_app/laundr_simple.c applications_user/laundr/
cp /path/to/LaundR/flipper_app/application.fam applications_user/laundr/

# Build
./fbt fap_laundr

# Output: build/f7-firmware-D/.extapps/laundr.fap
```

### Usage Guide

#### Step 1: Load a Card

```
Apps → NFC → LaundR
├── CSC SW MasterCard    ← Built-in test card ($50.00)
└── Load Card            ← Load from SD card
```

#### Step 2: Configure Mode

| Mode | Behavior | Use Case |
|------|----------|----------|
| **HACK** (Default) | All writes blocked, balance unchanged | Testing if readers verify writes |
| **LEGIT** | Writes applied normally | Comparison testing |

#### Step 3: Start Emulation

```
Start Emulation
├── Purple LED indicates active emulation
├── Hold Flipper near laundry reader
├── Watch transaction stats update
└── Press BACK to stop
```

#### Step 4: Analyze Results

```
Transaction Stats
├── Session: 5 txns       ← Current session
├── All Time: 127 txns    ← Persistent total
├── Reads: 47
├── Writes: 23
├── Blocked: 23           ← Writes prevented in HACK mode
└── Saved: $69.00         ← Money "saved" by blocking
```

### HACK vs LEGIT Mode

```
┌─────────────────────────────────────────────────────────────┐
│                    HACK MODE (Default)                      │
├─────────────────────────────────────────────────────────────┤
│  Reader: "Deduct $3.00 from balance"                        │
│  LaundR: "Sure!" (but doesn't actually change anything)     │
│  Reader: "Transaction complete!"                            │
│  LaundR: Balance still $50.00                               │
│                                                             │
│  Result: Machine may start, your balance unchanged          │
│  Purpose: Test if reader verifies writes                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                      LEGIT MODE                             │
├─────────────────────────────────────────────────────────────┤
│  Reader: "Deduct $3.00 from balance"                        │
│  LaundR: Applies the write to emulated card                 │
│  Reader: "Transaction complete!"                            │
│  LaundR: Balance now $47.00                                 │
│                                                             │
│  Result: Normal transaction behavior                        │
│  Purpose: Comparison testing, realistic simulation          │
└─────────────────────────────────────────────────────────────┘
```

### Transaction Logging

LaundR logs all operations to CSV for analysis:

```csv
timestamp,tx_num,uid,provider,balance_before,balance_after,charge,mode,writes,reads
1704067200,1,DEADBEEF,CSC ServiceWorks,5000,4700,-300,HACK,3,5
1704067260,2,DEADBEEF,CSC ServiceWorks,5000,4400,-300,HACK,3,5
```

**Log Locations:**
- `/ext/apps_data/laundr/logs/transactions.csv` - Machine-readable
- `/ext/apps_data/laundr/logs/transactions.log` - Human-readable
- `/ext/apps_data/laundr/logs/system.log` - Debug info

### Operational Security

> [!WARNING]
> Reader systems log card UIDs. Sanitize identifiable data for anonymity.

#### Unique Identifiers in Card Dumps

| Block | Data | Tracking Risk |
|-------|------|---------------|
| Block 0, Bytes 0-3 | **UID** | **HIGH** - Logged by readers |
| Block 0, Byte 4 | **BCC** | Must match UID XOR |
| Block 13 | **Serial String** | Human-readable card ID |
| Block 2 | **Transaction ID** | Usage pattern fingerprint |

#### Recommended Sanitization

```bash
# 1. Randomize UID (Block 0 bytes 0-3)
# 2. Recalculate BCC: UID[0] ^ UID[1] ^ UID[2] ^ UID[3]
# 3. Randomize card serial string (Block 13)
# 4. Reset transaction counters (Block 2)
```

**LaundR's Master Card auto-randomizes UID on each load!**

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## Python Desktop Tool

**laundr.py** - Desktop forensic analysis with ML features

### Features

- Universal MIFARE Classic detection
- Balance editing with rule compliance
- Key dictionary (5,342+ keys)
- Machine learning for value detection
- Multi-format export

### Installation

```bash
pip install tkinter
python3 python/laundr.py
```

### Usage

1. Open a `.nfc` or `.mfd` dump file
2. View decoded values and sector data
3. Double-click values to confirm accuracy
4. Export modified cards

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## CSC Card Format Specification

> [!NOTE]
> Complete reverse-engineered format for CSC ServiceWorks MIFARE Classic 1K cards.

### Block Structure

```
MIFARE Classic 1K Layout (16 sectors × 4 blocks = 64 blocks)

Sector 0:
├── Block 0:  UID + BCC + Manufacturer Data
├── Block 1:  System Data
├── Block 2:  Transaction Metadata + Checksum
└── Block 3:  Sector Trailer (Keys + Access Bits)

Sector 1:
├── Block 4:  BALANCE (Value Block Format) ← Primary
├── Block 5:  Reserved
├── Block 6:  Reserved
└── Block 7:  Sector Trailer

Sector 2:
├── Block 8:  BALANCE BACKUP (Mirror of Block 4)
├── Block 9:  Usage Counters (Value Block)
├── Block 10: Reserved
└── Block 11: Sector Trailer

Sectors 3-15: Reserved / Unused
```

### Balance Format (Blocks 4 & 8)

MIFARE Classic Value Block format:

```
Byte:  0  1  2  3  4  5  6  7  8  9  10 11 12 13 14 15
      [Value ] [Cnt] [~Value] [~Cnt] [Value ] [Cnt] [Addr  ]

Example: $29.00 (2900 cents = 0x0B54)
Block 4: 54 0B 02 00 AB F4 FD FF 54 0B 02 00 04 FB 04 FB
         └─────┘     └─────┘     └─────┘     └─────┘
         Value       ~Value      Copy        Address
```

**Python Implementation:**

```python
def encode_balance(cents):
    value_block = bytearray(16)
    value_block[0:2] = cents.to_bytes(2, 'little')
    value_block[2:4] = b'\x02\x00'  # Counter
    value_block[4:6] = (cents ^ 0xFFFF).to_bytes(2, 'little')
    value_block[6:8] = b'\xFD\xFF'  # ~Counter
    value_block[8:10] = cents.to_bytes(2, 'little')
    value_block[10:12] = b'\x02\x00'
    value_block[12:16] = b'\x04\xFB\x04\xFB'  # Address
    return value_block
```

**Maximum Balance:** $655.35 (65535 cents, 16-bit limit)

### Transaction Metadata (Block 2)

```
Byte:  0  1  2  3  4  5  6  7  8  9  10 11 12 13 14 15
      [Sig ] [TX ID   ] [R] [Reserved] [TopUp] [Rsv] [CS]

Sig:     0x01 0x01 (CSC ServiceWorks signature)
TX ID:   24-bit transaction counter
R:       Refill times counter (displayed as "Topup Count")
TopUp:   Last topup amount in cents
CS:      XOR checksum of bytes 0-14
```

**Checksum Calculation (CRITICAL):**

```python
checksum = 0
for i in range(15):
    checksum ^= block2[i]
block2[15] = checksum
```

### Usage Counters (Block 9)

```
Bytes 0-1:   Usages left (decrements on use)
Bytes 2-3:   Topup count (increments on use)
Bytes 4-15:  Value block inversions/copies

RULE: usages_left + topup_count = 16960 (always!)
```

### Test Cards

| File | Balance | Refills | Usages Left |
|------|---------|---------|-------------|
| card_10.nfc | $10.00 | 22 | 6,432 |
| card_50.nfc | $50.00 | 112 | 5,712 |
| card_100.nfc | $100.00 | 148 | 12,781 |
| card_655_35.nfc | $655.35 | 93 | 143 |

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## Security Research

### Documented Vulnerabilities

#### CVE-2025-46018 - Weak Key Storage

<table>
<tr><td><strong>Severity</strong></td><td>High</td></tr>
<tr><td><strong>Attack Vector</strong></td><td>Physical (NFC proximity)</td></tr>
<tr><td><strong>Description</strong></td><td>CSC ServiceWorks laundry cards use predictable MIFARE Classic keys recoverable through dictionary attacks. Once keys are known, card data can be read and cloned.</td></tr>
<tr><td><strong>Impact</strong></td><td>Full card content disclosure, cloning capability</td></tr>
</table>

#### CVE-2025-46019 - Insufficient Balance Validation

<table>
<tr><td><strong>Severity</strong></td><td>High</td></tr>
<tr><td><strong>Attack Vector</strong></td><td>Physical (NFC proximity)</td></tr>
<tr><td><strong>Description</strong></td><td>Card balance values lack cryptographic integrity verification, allowing modification of stored credits without detection by payment terminals.</td></tr>
<tr><td><strong>Impact</strong></td><td>Balance manipulation, service theft</td></tr>
</table>

### Affected Systems

- CSC ServiceWorks payment terminals
- SpeedQueen connected machines
- University/apartment laundry systems
- **Estimated 1M+ machines worldwide**

### Test Scenarios

#### Scenario 1: Offline Reader (Vulnerable)

```
Reader: READ balance ($29.00)
Reader: CHECK balance >= $3.00 ✓
Reader: WRITE new balance ($26.00)
LaundR: IGNORES write (HACK mode)
Reader: START machine
Result: Machine starts, balance unchanged!

Security Rating: ❌ VERY POOR
```

#### Scenario 2: Write Verification (Secure)

```
Reader: READ balance ($29.00)
Reader: WRITE new balance ($26.00)
LaundR: IGNORES write (HACK mode)
Reader: READ balance to verify
Reader: SEES $29.00 (write failed!)
Reader: SHOW ERROR, don't start

Security Rating: ✅ GOOD
```

#### Scenario 3: Server Validation (Secure)

```
Reader: READ balance ($29.00)
Reader: CONTACT server for approval
Server: APPROVED, log transaction
Reader: WRITE new balance ($26.00)
Reader: START machine
Result: Server has record, card is secondary

Security Rating: ✅ GOOD
```

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## Key Dictionary

The included dictionary contains 5,000+ MIFARE Classic keys:

| Source | Count | Description |
|--------|-------|-------------|
| Proxmark3 | 2,500+ | Community-contributed keys |
| MifareClassicTool | 1,500+ | Android app dictionary |
| CSC Specific | 500+ | ServiceWorks operator keys |
| Factory Defaults | 100+ | Manufacturer defaults |

**Known CSC Keys:**

```
Key A (Read):  EE B7 06 FC 71 4F
Key B (Write): F4 F7 D6 87 DB 0B  ← MFKey32 cracked
Default:       FF FF FF FF FF FF
```

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## Project Structure

```
LaundR/
├── android_app/              # Android source code
│   ├── src/                  # Kotlin source files
│   ├── build.gradle.kts      # Build configuration
│   └── gradlew               # Gradle wrapper
├── flipper_app/              # Flipper Zero source
│   ├── laundr_simple.c       # Main app (4500+ lines)
│   ├── application.fam       # App manifest
│   ├── build_flipper_app.sh  # Automated builder
│   └── dist/                 # Built .fap files
├── python/                   # Python tool
│   ├── laundr.py             # Main GUI
│   └── database/             # SQLite storage
├── releases/                 # Pre-built binaries
│   ├── android/              # APK files
│   └── flipper/              # FAP files
├── scripts/                  # Deployment scripts
├── assets/                   # Icons and images
├── database/                 # Key dictionaries
└── test_cards/               # Sample .nfc files
```

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## Troubleshooting

### Build Issues

<details>
<summary><strong>Build failed - furi.h not found</strong></summary>

```bash
# Clone with submodules
git submodule update --init --recursive
```

</details>

<details>
<summary><strong>App doesn't appear on Flipper</strong></summary>

1. Verify file is at `SD:/apps/NFC/laundr.fap`
2. Check filename is lowercase
3. Restart Flipper

</details>

<details>
<summary><strong>Reader doesn't respond</strong></summary>

1. Try flipping card orientation
2. Hold steady for 3-5 seconds
3. Test with real card first
4. Check reader is powered on

</details>

<details>
<summary><strong>Balance shows wrong amount</strong></summary>

1. Reload the .nfc file
2. Verify Block 4 in hex editor
3. Check endianness (little-endian)
4. Verify Block 2 checksum

</details>

### Runtime Issues

<details>
<summary><strong>Transaction count not persisting</strong></summary>

Stats are saved to `/ext/apps_data/laundr/mastercard_stats.txt`. Check file exists and is writable.

</details>

<details>
<summary><strong>KeyB nonces showing 0</strong></summary>

Nonces only capture on FAILED auth attempts. If master card has the key, successful auths don't generate nonces - this is expected.

</details>

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Code Style

- C: Follow Flipper Zero SDK conventions
- Python: PEP 8
- Kotlin: Official Kotlin style guide

### Testing

- Test on both Unleashed and Momentum firmware
- Verify card parsing on Flipper's built-in NFC app
- Include test cards for new features

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## Changelog

### v5.61 "Purple Thunder" - 2025-01-14

- **Fixed:** Persistent transaction stats across sessions
- **Added:** Clear Stats menu option
- **Added:** Solid purple LED when emulating (no more blinking)
- **Fixed:** All-time counter now syncs with saved stats

### v5.60 - 2025-01-14

- **Added:** Solid purple LED indicator during emulation
- **Changed:** Removed distracting cyan blink

### v5.59 - 2025-01-14

- **Added:** Master card stats persistence to file
- **Added:** Transaction count survives app restart

### v4.1 "Tumbling Typhoon" - 2024-12-24

- **Fixed:** Block 2 XOR checksum calculation
- **Fixed:** Balance overflow for amounts > $655.35
- **Added:** Auto-deploy to mounted Flipper
- **Added:** CSC_CARD_FORMAT.md documentation

<details>
<summary>View full changelog</summary>

### v4.0 - Initial Release

- Shadow file system
- Non-destructive editing
- HACK/LEGIT mode toggle
- Basic transaction logging

</details>

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## License

**Educational and research use only.**

This software is provided for authorized security research. The authors assume no liability for misuse. See [LICENSE](LICENSE) for details.

<p align="right"><a href="#table-of-contents">Back to top</a></p>

---

## Credits

- **Flipper Zero Team** - Hardware and SDK
- **Proxmark3 Community** - Key research
- **MifareClassicTool** - Android NFC foundation
- **Security Researchers** - Vulnerability discovery
- **DarkFlippers** - Unleashed firmware
- **Next-Flip** - Momentum firmware

---

## Support

- **Issues**: [GitHub Issues](https://github.com/yourusername/LaundR/issues)
- **Discussions**: [GitHub Discussions](https://github.com/yourusername/LaundR/discussions)

---

<p align="center">
  <em>For authorized security testing only. The authors assume no liability for misuse.</em>
</p>

<p align="center">
  <strong>Built with care for security researchers and the Flipper Zero community</strong>
</p>
