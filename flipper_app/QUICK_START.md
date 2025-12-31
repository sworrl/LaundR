# LaundR Flipper App - Quick Start Guide

**Don't know anything about Flipper apps? No problem!**

This guide will get you from zero to running the LaundR app on your Flipper in **3 simple steps**.

---

## What You Need

- ✅ A computer (Linux, Mac, or WSL on Windows)
- ✅ Internet connection
- ✅ Your Flipper Zero
- ✅ USB cable (to connect Flipper) OR SD card reader

**That's it!** The automated script handles everything else.

---

## Super Simple Method - Automated Build

### Step 1: Run the Build Script

```bash
cd "/home/eurrl/Documents/Code & Scripts/LaundR/flipper_app"
./build_flipper_app.sh
```

**What it does:**
- ✓ Checks you have git and python3
- ✓ Downloads Flipper firmware (~1-2 GB, one-time)
- ✓ Copies LaundR app into firmware
- ✓ Builds the app
- ✓ Saves `laundr.fap` to `output/` folder

**Time:** First build: 10-20 minutes | Later builds: 1-2 minutes

**Output:**
```
✓ Build successful!
Output: flipper_app/output/laundr.fap
Size: ~15 KB
```

### Step 2: Copy to Flipper

**Option A: Using qFlipper (Recommended)**

1. Connect Flipper to PC via USB
2. Open qFlipper
3. Go to: File Manager → SD Card → apps → NFC
4. Click "Upload"
5. Select `flipper_app/output/laundr.fap`
6. Done!

**Option B: SD Card Directly**

1. Remove SD card from Flipper
2. Insert into PC
3. Copy `laundr.fap` to: `SD:/apps/NFC/`
4. Eject safely
5. Insert back into Flipper
6. Done!

### Step 3: Run the App

On your Flipper:
```
Apps → NFC → LaundR
```

🎉 **That's it! The app is running!**

---

## What the App Does

1. **Load Card** - Simulates loading your laundry card ($29.00)
2. **Toggle Mode** - Switch between Test Mode and Normal Mode
3. **Start Emulation** - Begins emulating your card
4. **Press OK** - Simulates a laundry machine transaction
5. **View Log** - See what operations happened

---

## Test Without a Real Reader

You can test the app without going to a laundry room:

```
1. Apps → NFC → LaundR
2. Load Card (loads simulated $29.00 card)
3. Toggle Mode (make sure "Test Mode" is active)
4. Start Emulation
5. Press OK to simulate transaction
6. Watch the log fill up!
7. Balance stays $29.00 (write ignored)
8. View Log to see operations
```

This simulates what would happen if you held it near a real laundry reader.

---

## Troubleshooting

### "Build failed"

**Most common issues:**

1. **Missing git:**
   ```bash
   sudo apt install git
   ```

2. **Missing python3:**
   ```bash
   sudo apt install python3
   ```

3. **Not enough disk space:**
   - Need ~3 GB free
   - Check with: `df -h`

4. **Network issues:**
   - Firmware download requires good internet
   - Can take 10-20 minutes depending on speed

### "Can't find laundr.fap"

Check the output folder:
```bash
ls -lh flipper_app/output/
```

Should see: `laundr.fap` (~15 KB)

### "App doesn't appear on Flipper"

1. Make sure you copied to: `SD:/apps/NFC/` (not just `SD:/apps/`)
2. Try restarting Flipper
3. Check file name is exactly: `laundr.fap` (lowercase)

### "App crashes on startup"

This is the simple version that simulates transactions. For real NFC emulation, we'd need to enhance it (documented in advanced guides).

---

## Updating the App

Made changes to the code? Just run the script again:

```bash
cd "/home/eurrl/Documents/Code & Scripts/LaundR/flipper_app"
./build_flipper_app.sh
```

It will:
- ✓ Update the firmware (if needed)
- ✓ Copy your latest changes
- ✓ Rebuild the app
- ✓ Save new `laundr.fap`

Then copy the new file to your Flipper.

---

## Using Real Card Files

**The app currently simulates a card, but you can use real ones:**

### Step 1: Read Your Card with Flipper

```
Flipper → NFC → Read Card
→ Detecting...
→ Reading with dictionary...
→ 28/32 keys found
→ Save → "my_laundry_card.nfc"
```

### Step 2: Analyze in LaundR Desktop

```bash
# Copy .nfc from Flipper SD to PC
python3 laundr.py my_laundry_card.nfc

# View balance, edit if needed, save
```

### Step 3: Future Enhancement

Right now the Flipper app simulates transactions. In the future, we'll add:
- ✓ Load `.nfc` files from SD card
- ✓ Real NFC emulation (not just simulation)
- ✓ Save transaction logs

For now, use the simulation to understand how the app works!

---

## Advanced: Custom Firmware

The script works with **official Flipper firmware**. If you use custom firmware:

**Unleashed:**
```bash
cd ~/flipper-build
rm -rf flipperzero-firmware
git clone --recursive https://github.com/DarkFlippers/unleashed-firmware.git flipperzero-firmware
cd flipperzero-firmware
# Then copy LaundR app and build as normal
```

**Momentum:**
```bash
git clone --recursive https://github.com/Next-Flip/Momentum-Firmware.git flipperzero-firmware
```

**RogueMaster:**
```bash
git clone --recursive https://github.com/RogueMaster/flipperzero-firmware-wPlugins.git flipperzero-firmware
```

---

## File Sizes

```
Flipper firmware repo: ~1-2 GB
Build artifacts:       ~500 MB
Final .fap file:       ~15 KB
```

**Total disk usage:** ~2-3 GB (one-time, reusable for updates)

**Cleanup after building:**
```bash
# Delete build directory to save space
rm -rf ~/flipper-build

# Keep only the .fap file
# It's already in flipper_app/output/
```

---

## What's Next?

Once you have the app running:

1. **Test the simulation** - Press OK to simulate transactions
2. **Read a real card** - Use Flipper NFC app
3. **Analyze in LaundR desktop** - Understand the data
4. **Test at laundry room** - See if readers validate writes (future enhancement)

---

## Getting Help

**Script issues:**
- Check you have git and python3 installed
- Make sure you have 3 GB free disk space
- Check internet connection

**Flipper issues:**
- Make sure SD card is working
- Try restarting Flipper
- Check file is in correct folder: `SD:/apps/NFC/`

**App issues:**
- Check the logs in the app (View Log menu)
- For advanced debugging, see BUILD.md

---

## Summary

**Three simple steps:**

1. **Build:** `./build_flipper_app.sh`
2. **Copy:** `output/laundr.fap` → Flipper SD card `apps/NFC/`
3. **Run:** Apps → NFC → LaundR

**That's it!** You don't need to know anything about Flipper development. The script does everything automatically.

🎮 **Happy testing!**
