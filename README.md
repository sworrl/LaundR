# LaundR - Universal Laundry Card Security Research Suite

**Multi-platform forensic analysis toolkit for CSC ServiceWorks laundry card security research**

![Version](https://img.shields.io/badge/version-1.7.0-blue)
![Android](https://img.shields.io/badge/Android-14+-green)
![Flipper](https://img.shields.io/badge/Flipper_Zero-Unleashed-orange)
![Python](https://img.shields.io/badge/Python-3.8+-blue)
![License](https://img.shields.io/badge/license-Educational-red)

---

## ⚠️ Disclaimer

**FOR AUTHORIZED SECURITY RESEARCH ONLY**

This toolkit is designed for security researchers studying vulnerabilities in laundry card payment systems. The documented vulnerabilities (CVE-2025-46018, CVE-2025-46019) affect millions of laundry machines worldwide.

**DO NOT** use this tool for:
- Unauthorized access to payment systems
- Theft of services
- Any illegal activity

The authors are not responsible for misuse.

---

## 📦 Components

| Component | Description | Status |
|-----------|-------------|--------|
| **LaunDRoid** (Android) | Mobile NFC/BLE security audit tool | v1.7.0 |
| **LaundR** (Flipper Zero) | Portable card analyzer & emulator | v5.42 |
| **laundr.py** (Python) | Desktop forensic analysis GUI | v2.1 |

---

## 🚀 Quick Start

### Android App - LaunDRoid

The fastest way to get started. Runs on any Android device with NFC.

#### One-Command Install (Linux/macOS)

```bash
# Clone the repo
git clone https://github.com/yourusername/LaundR.git
cd LaundR

# Run the deployment script
./scripts/deploy_android.sh
```

#### One-Command Install (Windows)

```batch
:: Clone the repo and run
git clone https://github.com/yourusername/LaundR.git
cd LaundR
scripts\deploy_android.bat
```

#### Manual Install

1. Enable USB Debugging on your Android phone:
   - Settings → About Phone → Tap "Build Number" 7 times
   - Settings → Developer Options → Enable "USB Debugging"

2. Connect phone via USB and accept the debugging prompt

3. Install via ADB:
   ```bash
   adb install releases/android/LaunDRoid-v1.7.0.apk
   ```

---

## 📱 LaunDRoid (Android App)

**v1.7.0 "Crusty Agitate Goblin"**

Full-featured Android security research tool with cyberpunk UI.

### Features

#### NFC Card Analysis
- **Dictionary Attack**: 5,000+ key dictionary with randomized sweep strategies
- **Real-time Progress**: Live key testing display with ETA
- **CSC Detection**: Auto-identifies ServiceWorks laundry cards
- **Balance Extraction**: Reads balance, transaction count, site codes
- **Card Removed Alert**: Animated warning when card is lost during scan

#### Attack Strategies
The app randomly selects from 6 attack strategies per sector:
- `→FWD` - Forward sweep
- `←REV` - Reverse (end to start)
- `◇MID` - Middle-out expansion
- `⚄RND` - Full random shuffle
- `▦CHK` - Chunked blocks
- `↔INT` - Interleaved (start/end)

#### Export Formats
- `.nfc` - Standard Flipper Zero format
- `.laundr` - Extended format with metadata (MasterCard flag, MaxValue, AutoReloader, etc.)
- `.json` - Full data dump with keys

#### BLE Scanner
- Discovers CSC laundry machines via Bluetooth
- Service UUID detection for ServiceWorks devices
- Signal strength monitoring
- Machine organization by laundry room

#### Master Card Generator
- Generate CSC master cards for security testing
- Auto-randomizes UID and identifiers
- Configurable balance amounts
- NFC ownership (prevents wallet interference)

### Building from Source

```bash
cd android_app
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

---

## 🐬 LaundR (Flipper Zero App)

Portable MIFARE Classic analyzer and emulator for Flipper Zero.

### Features

- **Auto-Load**: Scans for cards on startup
- **Live Balance**: Real-time balance display from card
- **Master Card Mode**: Generate test master cards
- **Card Emulation**: Full MIFARE Classic emulation
- **Export**: Save cards to Flipper SD card

### Installation

1. Copy `releases/flipper/laundr.fap` to your Flipper's SD card:
   ```
   /ext/apps/NFC/laundr.fap
   ```

2. Or use qFlipper to transfer the file

3. Launch from Apps → NFC → LaundR

### Building from Source

Requires [Unleashed Firmware](https://github.com/DarkFlippers/unleashed-firmware) SDK:

```bash
cd flipper_app
./build_flipper_app.sh
# Output: output/laundr.fap
```

---

## 🐍 laundr.py (Python GUI)

Desktop forensic analysis tool with machine learning features.

### Features

- **Universal Detection**: Works with any MIFARE Classic laundry card
- **Balance Editing**: Modify values with transaction rule compliance
- **Key Dictionary**: 5,342+ known MIFARE keys
- **Machine Learning**: Learns confirmed values for better detection
- **Multi-format Export**: NFC, JSON, binary dumps

### Installation

```bash
# Install dependencies
pip install tkinter

# Run
python3 python/laundr.py
```

### Usage

1. Open a `.nfc` or `.mfd` dump file
2. View decoded values and sector data
3. Double-click values to confirm accuracy
4. Export modified cards

---

## 📁 Project Structure

```
LaundR/
├── android_app/           # Android source code
│   ├── src/              # Kotlin source files
│   ├── build.gradle.kts  # Build configuration
│   └── gradlew           # Gradle wrapper
├── flipper_app/          # Flipper Zero source
│   ├── src/              # C source files
│   ├── application.fam   # App manifest
│   └── build_flipper_app.sh
├── python/               # Python tool
│   ├── laundr.py         # Main GUI
│   └── database/         # SQLite storage
├── releases/             # Pre-built binaries
│   ├── android/          # APK files
│   └── flipper/          # FAP files
├── scripts/              # Deployment scripts
│   ├── deploy_android.sh  # Linux/macOS
│   └── deploy_android.bat # Windows
├── assets/               # Icons and images
├── docs/                 # Additional documentation
└── database/             # Key dictionaries
```

---

## 🔑 Key Dictionary

The included dictionary contains 5,000+ MIFARE Classic keys:

| Source | Count | Description |
|--------|-------|-------------|
| Proxmark3 | 2,500+ | Community-contributed keys |
| MifareClassicTool | 1,500+ | Android app dictionary |
| CSC Specific | 500+ | ServiceWorks operator keys |
| Factory Defaults | 100+ | Manufacturer defaults |

---

## 🛡️ Security Research

### Documented Vulnerabilities

#### CVE-2025-46018 - Weak Key Storage
CSC ServiceWorks laundry cards use predictable MIFARE Classic keys that can be recovered through dictionary attacks. Once keys are known, card data can be read and cloned.

#### CVE-2025-46019 - Insufficient Balance Validation
Card balance values lack cryptographic integrity verification, allowing modification of stored credits without detection by payment terminals.

### Affected Systems
- CSC ServiceWorks payment terminals
- SpeedQueen connected machines
- Various university/apartment laundry systems
- Estimated 1M+ machines worldwide

### Responsible Disclosure
These vulnerabilities were reported to CSC ServiceWorks. This toolkit is released for defensive security research to help operators assess their exposure.

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Submit a pull request

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## 📄 License

Educational and research use only. See [LICENSE](LICENSE) for details.

---

## 🙏 Credits

- **Flipper Zero Team** - Hardware and SDK
- **Proxmark3 Community** - Key research
- **MifareClassicTool** - Android NFC foundation
- **Security Researchers** - Vulnerability discovery

---

## 📞 Support

- **Issues**: [GitHub Issues](https://github.com/yourusername/LaundR/issues)
- **Discussions**: [GitHub Discussions](https://github.com/yourusername/LaundR/discussions)

---

*For authorized security testing only. The authors assume no liability for misuse.*
