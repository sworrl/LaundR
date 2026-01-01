# LaundR - Universal Laundry Card Analyzer

**Forensic analysis tool for MIFARE Classic 1K laundry cards with multi-operator support**

**Includes Flipper Zero app for transaction protocol testing**

![Version](https://img.shields.io/badge/version-2.1-blue)
![Python](https://img.shields.io/badge/python-3.8+-green)
![Flipper](https://img.shields.io/badge/flipper-app-orange)
![License](https://img.shields.io/badge/license-Educational-red)

---

## Features

### 🔍 Universal Card Detection
- **Works with ANY laundry card operator** - CSC Service Works, U-Best Wash, and more
- Auto-detects provider from card signatures and ASCII identifiers
- Scans all 64 blocks dynamically for balance data
- Supports both 16-bit and 32-bit value block formats

### 💰 Balance Editing with Transaction Tracking
- Edit balance values directly from GUI
- Optional "Follow Transaction Rules" mode
- Automatically increments counters and updates transaction history
- Shows add/deduct amounts like real laundry card machines

### 🎯 Complete Flipper Zero Parity
Decodes **ALL** values shown by Flipper Zero plus hidden data:
- Provider identification (CSC Service Works, U-Best Wash, etc.)
- UID and card manufacturing data
- Current balance and transaction history
- Top-up count and usages left
- Card creation timestamps
- Transaction IDs and receipt amounts

### 🗝️ Key Dictionary Integration
- **5,342+ known MIFARE keys** from Proxmark3 and MifareClassicTool
- Automatic key identification for sector trailers
- Recognizes factory defaults, MAD keys, and common operator keys
- Helps with card analysis and authentication research

### 🧠 Machine Learning - User-Confirmed Values
- **Double-click** any decoded value to mark it as "correct" (green highlight)
- Stores confirmed values for future card detection
- Improves accuracy for similar cards from same operator
- JSON storage for portability and sharing

### 🔬 Comprehensive Decoding Methods
- **No stone left unturned** - scans every byte offset with multiple encodings:
  - 16-bit little-endian / big-endian
  - 32-bit little-endian / big-endian
  - BCD (Binary Coded Decimal)
  - Inverse pattern validation (XOR)
  - ASCII interpretation
  - Unix timestamps
  - Counter values

### 📊 Advanced Analysis
- Complete hex editor with live decoding
- Block-by-block memory map
- Sector trailer access bit analysis
- Value block inverse validation
- Mirror block consistency checking
- Export to JSON, CSV, and forensic reports

---

## Quick Start

### Installation
```bash
# Clone the repository
git clone https://github.com/yourusername/LaundR.git
cd LaundR

# Run the application
python3 laundr.py
```

No dependencies required - uses Python standard library only!

### Basic Usage
```bash
# Open a card file
python3 laundr.py /path/to/your/card.nfc

# Or use File → Open from the GUI
python3 laundr.py
```

### Edit Balance
1. Load your .nfc file
2. Find the **Balance** field in Card Profile
3. Click in the field, type new amount (e.g., "50.00")
4. Press **Enter** or click **✓**
5. See transaction details in popup

### Mark Correct Values (Machine Learning)
1. Select any block from the memory map
2. View decoded values in "Decoding Analysis" panel
3. **Double-click** the value you confirm is correct
4. It turns **green** and is stored for future cards
5. Helps improve detection accuracy

---

## 🎮 Flipper Zero App

LaundR includes a **Flipper Zero application** for testing laundry machine transaction protocols in real-time!

### What It Does

The Flipper app emulates your laundry card and intercepts transaction attempts to help you understand:

- **Does the reader validate writes?** - Test if the machine checks that balance was actually deducted
- **Online or offline?** - Determine if the reader contacts a server
- **Which blocks are accessed?** - See exactly what data the reader reads/writes
- **Can transactions be faked?** - Test security vulnerabilities

### Two Modes

**🎯 Test Mode (Default):**
- Emulates card perfectly
- **Ignores all write attempts** from reader
- Reader thinks it deducted $3.00, but your balance stays the same
- Logs all operations for analysis
- **Tests if machine starts without actually paying**

**✓ Normal Mode:**
- Applies writes normally
- Balance actually decreases
- Use for baseline comparison

### Quick Start

```bash
# 1. Build the app (see flipper_app/BUILD.md for details)
cd flipper-firmware
cp -r /path/to/LaundR/flipper_app applications_user/laundr
./fbt fap_laundr

# 2. Install on Flipper
# Copy build/f7-firmware-D/apps_data/laundr/laundr.fap to SD:/apps/NFC/

# 3. Run on Flipper
# Apps → NFC → LaundR

# 4. Test transaction
# Load Card → Start Emulation → Hold near laundry reader
```

### Example Test Results

**Scenario 1: Vulnerable Reader (Offline, No Validation)**
```
Reader reads: $29.00
Reader writes: $26.00 (deduct $3.00)
LaundR ignores write
Balance stays: $29.00
Machine starts! ← FREE WASH (security vulnerability)
```

**Scenario 2: Secure Reader (Write Verification)**
```
Reader reads: $29.00
Reader writes: $26.00
LaundR ignores write
Reader reads back: $29.00 (verifies write)
Machine shows error ← Security works!
```

**Scenario 3: Online Reader (Server Validation)**
```
Reader reads: $29.00
Reader contacts server
Server approves transaction
Reader writes: $26.00
LaundR ignores write
Machine starts, but server records $26.00
Next top-up will show mismatch ← Detection likely
```

### Documentation

Full documentation in `flipper_app/`:
- **[README.md](flipper_app/README.md)** - Complete app guide, test scenarios, interpretation
- **[BUILD.md](flipper_app/BUILD.md)** - Build instructions for all firmware versions
- **[laundr_simple.c](flipper_app/laundr_simple.c)** - Simplified app source code

### Features

- ✅ Transaction logging (reads, writes, authentications)
- ✅ Real-time statistics display
- ✅ Test mode vs Normal mode toggle
- ✅ Balance tracking
- ✅ Operation log viewer
- ✅ Simple menu-driven UI

### Use Cases

1. **Security Research** - Test if laundry readers validate transactions
2. **Protocol Analysis** - Understand offline vs online operation
3. **Vulnerability Testing** - Identify security weaknesses
4. **Educational** - Learn about RFID transaction protocols

**⚠️ For authorized security research only!**

---

## Project Structure

```
LaundR/
├── laundr.py               # Main application (GUI + analysis engine)
├── LICENSE                 # MIT License with security disclaimer
├── README.md               # This file
│
├── flipper_app/            # 🎮 Flipper Zero App
│   ├── application.fam     # Flipper app manifest
│   ├── laundr_simple.c     # Simple transaction tester (recommended)
│   ├── laundr.c            # Advanced version (WIP)
│   ├── laundr.png          # App icon (10x10)
│   ├── README.md           # Complete app documentation
│   ├── BUILD.md            # Build instructions
│   └── laundr_icon.txt     # Icon creation guide
│
├── assets/
│   └── mifare_keys/        # 5,342+ known MIFARE keys
│       ├── proxmark3_keys.dic       (3,220 keys)
│       └── extended_std_keys.dic    (2,122 keys)
│
├── database/               # SQLite database for operator signatures
│   ├── laundr.db           # Card operator database
│   ├── db_manager.py       # Database interface
│   ├── init_db.py          # Database initialization
│   └── seed_data.sql       # Operator definitions
│
├── docs/                   # Documentation
│   ├── FLIPPER_ZERO_DECODING.md     # Complete Flipper parity map
│   ├── MULTI_OPERATOR_SUPPORT.md    # Universal scanner docs
│   ├── TRANSACTION_TRACKING.md      # Transaction rules guide
│   ├── LEGIT_MODE_IMPLEMENTATION.md # Legit vs Hack mode
│   ├── CARD_WRITING_GUIDE.md        # How to write to cards
│   └── FLIPPER_CSC_PARSER_FIX.md    # Flipper checksum fix
│
└── user_data/              # User configuration (created at runtime)
    ├── laundr_config.json           # App settings
    └── confirmed_values.json        # ML confirmed values
```

---

## Supported Card Operators

| Operator | Detection | Balance Format | Status |
|----------|-----------|----------------|--------|
| **CSC Service Works** | Block 2: `01 01` | 16-bit split (Blocks 4, 8) | ✅ Fully supported |
| **U-Best Wash** | Block 1: "UBEST" ASCII | 32-bit (Blocks 28-30) | ✅ Fully supported |
| **Generic/Unknown** | Auto-scan all blocks | Dynamic detection | ✅ Works automatically |

**Add new operators:** Just load the card - LaundR auto-detects!

---

## Key Features Explained

### Universal Scanner
Instead of hardcoding Block 4 for balance, LaundR:
1. Scans **all 64 blocks** for value patterns
2. Validates using inverse patterns (XOR = 0xFFFF or 0xFFFFFFFF)
3. Applies operator-specific priorities
4. Selects highest-confidence match

### Transaction Tracking
When "Follow Transaction Rules" is enabled:
- Calculates difference (e.g., $9.00 → $50.00 = +$41.00)
- Updates balance in both Block 4 and Block 8 (mirror)
- Increments counter
- Records transaction amount in Block 2
- Increments transaction ID

### Confirmed Values (ML Feature)
- Double-click decoded values to mark as correct
- Stored in `user_data/confirmed_values.json`
- Format: `{provider: {block_id: {field: value}}}`
- Future cards from same provider use this knowledge

---

## Example Workflows

### Scenario 1: Top-Up a CSC Card
```
1. Load CSC card file
2. Current balance: $9.00
3. Enable ☑ "Follow Transaction Rules"
4. Type new balance: "59.00"
5. Press Enter
6. Popup shows: "Added $50.00, Counter: 2 → 3"
7. Block 2 updated with $50.00 receipt
8. Save file
```

### Scenario 2: Analyze Unknown Card
```
1. Load .nfc file from Flipper Zero
2. LaundR auto-detects provider from signatures
3. Scans all blocks for balance
4. Shows all possible interpretations
5. Double-click the value you know is correct
6. Confirmed value stored for future cards
```

### Scenario 3: Find Hidden Data
```
1. Load card file
2. Select Block 2 from memory map
3. View "Decoding Analysis" panel
4. See comprehensive scan of ALL byte offsets
5. Find values Flipper Zero missed
6. Double-click to confirm correct interpretation
```

---

## Key Dictionary Usage

LaundR automatically loads **5,342 known MIFARE keys** from:
- Proxmark3 RfidResearchGroup dictionary
- MifareClassicTool extended standard keys

When analyzing sector trailers, keys are identified:
- `FFFFFFFFFFFF` → "Factory Default (MIFARE)"
- `A0A1A2A3A4A5` → "MAD Key A (NFC Forum)"
- `D3F7D3F7D3F7` → "MAD Key B (NFC Forum)"
- Other known keys → "Known Key #XXX from dictionary"

---

## Export Options

### JSON Export
Complete card dump with metadata:
```json
{
  "provider": "CSC Service Works",
  "balance": "$9.00",
  "blocks": {...},
  "analysis": {...}
}
```

### CSV Export
Tabular format for spreadsheet analysis

### Forensic Report
Human-readable analysis report with:
- Card profile summary
- Block-by-block breakdown
- Decoder results
- Validation status

---

## Technical Details

### Card Format
- **MIFARE Classic 1K**: 64 blocks × 16 bytes = 1024 bytes
- **Sector trailers**: Every 4th block (3, 7, 11, ...)
- **Value blocks**: XOR inverse validation
- **Little-endian**: Multi-byte values

### Validation
All edits maintain integrity:
- ✓ Balance inverse: `Value XOR ~Value = 0xFFFF`
- ✓ Counter inverse: `Counter XOR ~Counter = 0xFFFF`
- ✓ Mirror blocks: Block 4 = Block 8
- ✓ BCC checksum: UID validation

### Encoding Methods Used
1. **16-bit LE/BE** - Standard integers
2. **32-bit LE/BE** - Extended values
3. **BCD** - Binary Coded Decimal (for displays)
4. **Inverse validation** - XOR pattern checking
5. **ASCII** - Text identifiers
6. **Unix timestamps** - Date/time fields

---

## Tips & Best Practices

✅ **Always enable "Follow Transaction Rules"** for realistic simulation
✅ **Save before major edits** to preserve original
✅ **Use double-click** to confirm correct values for ML
✅ **Check comprehensive scans** in Block 2 for hidden balances
✅ **Compare Blocks 4 and 8** - they should match

⚠️ **Balance range**: $0.00 to $655.35 (16-bit limit for CSC)
⚠️ **Backup files** before experimenting
⚠️ **Different operators** may use different block locations

---

## Documentation

### Desktop App
- **[QUICK_START.md](docs/QUICK_START.md)** - User quick reference
- **[FLIPPER_ZERO_DECODING.md](docs/FLIPPER_ZERO_DECODING.md)** - Complete Flipper parity map
- **[MULTI_OPERATOR_SUPPORT.md](docs/MULTI_OPERATOR_SUPPORT.md)** - Universal scanner explained
- **[TRANSACTION_TRACKING.md](docs/TRANSACTION_TRACKING.md)** - Transaction rules guide
- **[LEGIT_MODE_IMPLEMENTATION.md](docs/LEGIT_MODE_IMPLEMENTATION.md)** - Legit vs Hack mode
- **[CARD_WRITING_GUIDE.md](docs/CARD_WRITING_GUIDE.md)** - Writing to physical cards

### Flipper Zero App
- **[flipper_app/README.md](flipper_app/README.md)** - Complete app guide & test scenarios
- **[flipper_app/BUILD.md](flipper_app/BUILD.md)** - Build instructions for all firmwares

---

## Decoding Examples

### CSC Service Works Card
```
Block 0:  2B B9 91 B5 ... → UID: 3,046,226,219
Block 1:  ... 62 EE 11 01 → Created: 2022-08-05 23:58:09
Block 2:  01 01 ... 7E 04 → Provider: CSC, Last TX: $11.50
Block 4:  84 03 02 00 ... → Balance: $9.00, Counter: 2
Block 9:  3E 42 0F 00 ... → Usages: 16,958 cycles
Block 13: 41 5A 37 36 ... → Card ID: "AZ7602046"
```

### U-Best Wash Card
```
Block 1:  UBESTWASHLA ... → Provider: U-Best Wash
Block 2:  ... C8 00 A2 00 → $2.00, $1.62 (actual balance?)
Block 28: 13 01 00 00 ... → $2.75 (validated)
Block 29: 58 02 00 00 ... → $6.00 (validated)
Block 30: D0 07 00 00 ... → $20.00 (validated, but incorrect?)
```

**Note:** U-Best cards require user confirmation of correct balance via double-click!

---

## FAQ

**Q: Why does my U-Best card show $20 but it has less?**
A: Different operators store balance differently. Use the comprehensive scan in Block 2 to find all possible values, then double-click the correct one.

**Q: Can I use this for other MIFARE Classic cards?**
A: Yes! LaundR works with any MIFARE Classic 1K card. The universal scanner adapts automatically.

**Q: Will editing damage my real card?**
A: LaundR only edits the .nfc file, not your physical card. Always test with Flipper Zero emulation first.

**Q: How do I contribute new operator support?**
A: Just load the card - if LaundR auto-detects it, you're done! If not, double-click correct values to train the system.

**Q: How can I contribute card dumps for research?**
A: See [CONTRIBUTING.md](CONTRIBUTING.md) for instructions on submitting .nfc files to help expand operator support and build master key databases.

---

## Legal Disclaimer - READ BEFORE USE

**THIS SOFTWARE IS FOR AUTHORIZED USE ONLY**

By using this software, you agree to the following terms:

### Lawful Use Only

This tool is provided **exclusively** for:
- Academic research and education about RFID/NFC technology
- Authorized security testing with explicit written permission
- Analyzing cards that you legally own
- Understanding MIFARE Classic protocols for defensive security purposes

### Prohibited Uses

**Using this software to commit fraud, theft, or any illegal activity is a CRIME.**

The following uses are strictly prohibited and may violate federal and state laws including the Computer Fraud and Abuse Act (18 U.S.C. 1030), wire fraud statutes, and state theft laws:

- Modifying laundry card balances without authorization
- Using modified cards to obtain services without payment
- Circumventing payment systems
- Accessing or modifying cards you do not own
- Any commercial exploitation of vulnerabilities discovered

### Legal Consequences

Unauthorized modification of payment cards can result in:
- **Federal charges** carrying penalties of up to 20 years imprisonment
- **State theft/fraud charges** with additional penalties
- **Civil liability** for damages to card operators
- **Permanent criminal record** affecting employment and housing

### Your Responsibility

**You are solely responsible for ensuring your use of this software complies with all applicable laws.** The authors provide this tool for legitimate security research and accept no liability for misuse.

If you are unsure whether your intended use is legal, **consult an attorney before proceeding.**

### Authorized Research Context

Legitimate uses include:
- CTF (Capture The Flag) competitions
- University coursework on embedded security
- Penetration testing with signed authorization
- Personal research on cards you purchased and own
- Contributing to responsible disclosure of vulnerabilities

---

## Contributing Card Dumps

Help expand LaundR's capabilities by contributing .nfc card dumps from different laundry operators. Community contributions help us:

- Identify new operators and their data formats
- Build comprehensive MIFARE key databases
- Improve automatic provider detection
- Create master reference cards for research

### What We Need

| Priority | Card Types |
|----------|------------|
| High | Cards from operators NOT yet supported (non-CSC, non-U-Best) |
| Medium | CSC/U-Best cards from different regions |
| Medium | Cards with known balances (for validation) |
| Helpful | Before/after transaction pairs |

**Operators we want to add:**
- Heartland Payment Systems
- WASH Multifamily
- Mac-Gray
- Caldwell & Gregory
- Any regional/local operators

### How to Submit

**Option 1: Pull Request**
```bash
# Fork repo, then:
git checkout -b add-my-cards
# Add files to contributed_cards/
git commit -m "Add [Operator] cards from [Region]"
# Submit PR
```

**Option 2: GitHub Issue**
1. Open issue titled "Card Contribution: [Operator Name]"
2. Attach .nfc file(s)
3. Include details below

### Required Information

When submitting, include:
```
Operator: [Name on machine/card]
Region: [City, State/Country]
Known Balance: [If known, e.g., $5.25]
Capture Device: [Flipper Zero, Proxmark3, etc.]
Notes: [Any observations]
```

### Rules for Contributions

**You MUST:**
- Only submit cards you legally own/purchased
- Verify no personal data is embedded
- Have obtained the card through legitimate means

**We will NOT accept:**
- Stolen or found cards
- Cards you don't own
- Anything that facilitates fraud

### Building Master Keys

We're building a database of MIFARE keys used by laundry operators. If you've extracted keys from your own card using legitimate methods, contribute them to help others analyze similar cards.

See [CONTRIBUTING.md](CONTRIBUTING.md) for full details.

---

## Credits

- **Proxmark3 RfidResearchGroup** - MIFARE key dictionary
- **MifareClassicTool** - Extended standard keys
- **Flipper Zero** - NFC dump format compatibility
- **MIFARE Classic** - NXP Semiconductors

---

## Version History

**v2.1** (2025-12-21)
- 🎮 **Flipper Zero app added** - Transaction protocol tester
- ✨ Test Mode vs Normal Mode (write interception)
- ✨ Real-time transaction logging
- ✨ Legit Mode vs Hack Mode (refill counter simulation)
- ✨ Complete project reorganization
- 📚 Comprehensive documentation updates

**v2.0** (2025-12-21)
- ✨ Multi-operator support (CSC, U-Best, Generic)
- ✨ User-confirmed values with machine learning
- ✨ 5,342+ key dictionary integration
- ✨ Comprehensive decoding (no stone unturned)
- ✨ Double-click to mark correct values
- ✨ Block 2 checksum fix for Flipper parsing

**v1.0** (Initial Release)
- Basic CSC card support
- Transaction tracking
- Flipper Zero parity

---

## License

**Educational Use Only** - For learning about RFID technology and security research.

---

**Made with ❤️ for the security research community**

*LaundR - Leave no byte unturned.*
