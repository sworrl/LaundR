# 🎮 LaundR Flipper App - START HERE

**New to Flipper development? Perfect! This guide is for you.**

---

## ⚡ Super Fast Start (3 Commands)

```bash
cd "/home/eurrl/Documents/Code & Scripts/LaundR/flipper_app"
./build_flipper_app.sh
# Wait for build to finish (~10-20 min first time)
# Copy output/laundr.fap to your Flipper SD card at: SD:/apps/NFC/
```

Done! That's literally it.

---

## 📁 What's in This Folder?

```
flipper_app/
├── START_HERE.md              ← You are here!
├── QUICK_START.md             ← Simple step-by-step guide
├── build_flipper_app.sh       ← 🔥 ONE-CLICK BUILD SCRIPT
│
├── laundr_simple.c            ← App source code (650 lines)
├── application.fam            ← App manifest (tells Flipper about the app)
├── laundr.png                 ← App icon (10x10 pixels)
│
├── README.md                  ← Complete documentation (500+ lines)
├── BUILD.md                   ← Advanced build instructions
├── CARD_READING.md            ← How to read cards & use .nfc files
│
└── output/                    ← Build output goes here
    └── laundr.fap             ← This is what you copy to Flipper!
```

---

## 🎯 What You Actually Need to Know

### 1. The Build Script Does Everything

You don't need to understand Flipper development. The script:
- ✅ Downloads everything needed
- ✅ Builds the app automatically
- ✅ Puts the output in `output/laundr.fap`

### 2. The .fap File is the App

The `.fap` file is like an `.exe` on Windows or `.app` on Mac.

**Copy it to your Flipper:**
- Location: `SD:/apps/NFC/laundr.fap`
- Size: ~15 KB
- That's it!

### 3. No Installation on PC Needed

Everything runs on your Flipper, not your PC.

---

## 🚀 Step-by-Step Guide

### Option 1: I Just Want It to Work (Recommended)

1. **Run the build script:**
   ```bash
   ./build_flipper_app.sh
   ```

2. **Wait for it to finish** (grabs coffee ☕)

3. **Copy the output file to Flipper:**
   ```bash
   # Using qFlipper (USB):
   # - Connect Flipper
   # - Open qFlipper
   # - File Manager → SD Card → apps → NFC
   # - Upload: output/laundr.fap

   # OR using SD card directly:
   # - Remove SD from Flipper
   # - Copy output/laundr.fap to SD:/apps/NFC/
   # - Put SD back in Flipper
   ```

4. **Run on Flipper:**
   ```
   Apps → NFC → LaundR
   ```

Done! 🎉

### Option 2: I Want to Understand More

Read the guides in this order:
1. **QUICK_START.md** - Simple guide with pictures (coming soon)
2. **README.md** - What the app does, how to use it
3. **BUILD.md** - Advanced build options, troubleshooting
4. **CARD_READING.md** - Working with real cards

### Option 3: I'm a Developer

Check out:
- `laundr_simple.c` - Main app code
- `application.fam` - App configuration
- `README.md` - Architecture and test scenarios
- `BUILD.md` - Build system details

---

## 🛠️ Requirements

**To build:**
- Linux/Mac/WSL
- git
- python3
- ~3 GB disk space
- Internet connection

**To use:**
- Flipper Zero
- SD card
- USB cable OR SD card reader

---

## ❓ Common Questions

### "What does this app do?"

Tests if laundry machine readers actually validate that your balance was deducted, or if they just trust the card.

**Test Mode:**
- Reader tries to write: "$29.00 → $26.00"
- App ignores the write
- Balance stays $29.00
- **Does the machine still start?** ← That's what we're testing!

### "Do I need to know C programming?"

**Nope!** The build script handles everything. You just run it.

### "How long does building take?"

- **First time:** 10-20 minutes (downloads firmware)
- **After that:** 1-2 minutes (just rebuilds)

### "Can I use this with real cards?"

**Yes!** Two ways:

1. **Read card with Flipper NFC app** → Save as .nfc file
2. **Use that file with LaundR** (future feature, currently simulated)

For now, the app simulates transactions so you can test it works.

### "Is this safe to use?"

**For research:** Yes!
**For fraud:** NO!

This is for:
- ✅ Educational purposes
- ✅ Security research
- ✅ Understanding RFID systems
- ❌ NOT for getting free laundry

### "What if it doesn't work?"

1. Check `QUICK_START.md` troubleshooting section
2. Make sure you have git and python3 installed
3. Ensure ~3 GB free disk space
4. Try running the build script again

---

## 📖 Documentation Quick Links

| Want to... | Read this |
|-----------|-----------|
| **Build the app quickly** | Run `./build_flipper_app.sh` |
| **Understand what it does** | README.md |
| **Fix build problems** | QUICK_START.md → Troubleshooting |
| **Use real cards** | CARD_READING.md |
| **Customize the app** | BUILD.md + edit laundr_simple.c |

---

## 🎯 Your Next Steps

**Right now:**
```bash
./build_flipper_app.sh
```

**While it's building:**
- Read QUICK_START.md
- Charge your Flipper Zero
- Find your USB cable or SD card reader

**After build finishes:**
- Copy `output/laundr.fap` to Flipper
- Run: Apps → NFC → LaundR
- Play with the simulation (Load Card → Start Emulation → Press OK)

**Later:**
- Read README.md to understand test scenarios
- Read your real laundry card with Flipper NFC app
- Analyze it in LaundR desktop app

---

## 💡 Pro Tips

1. **First build is slow** - Downloads ~2 GB of firmware, one-time only
2. **Keep the build folder** - Reuse it for updates (saves time)
3. **Test simulation first** - Before going to laundry room
4. **Read the logs** - App shows what operations happened
5. **Use qFlipper** - Easiest way to copy files to Flipper

---

## 🎮 Ready?

**Just run:**
```bash
./build_flipper_app.sh
```

**That's it!** Everything else is automatic.

The script will tell you exactly what to do next when it's done.

Happy hacking! 🚀
