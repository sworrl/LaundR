# Building LaundR for Flipper Zero

## Quick Start

### Prerequisites

1. **Flipper Zero** with any firmware (Official, Unleashed, Momentum, RogueMaster, etc.)
2. **Computer** with USB connection to Flipper
3. **Flipper Build Tool (fbt)** - comes with firmware repository

### Step 1: Get Flipper Firmware

```bash
# Clone the firmware repository
git clone --recursive https://github.com/flipperdevices/flipperzero-firmware.git
cd flipperzero-firmware

# Or if you're using custom firmware:
# Unleashed: https://github.com/DarkFlippers/unleashed-firmware
# Momentum: https://github.com/Next-Flip/Momentum-Firmware
# RogueMaster: https://github.com/RogueMaster/flipperzero-firmware-wPlugins
```

### Step 2: Copy LaundR App

```bash
# Create applications_user directory if it doesn't exist
mkdir -p applications_user

# Copy the LaundR app
cp -r /path/to/LaundR/flipper_app applications_user/laundr
```

Your directory structure should look like:
```
flipperzero-firmware/
├── applications/
├── applications_user/
│   └── laundr/
│       ├── application.fam
│       ├── laundr_simple.c
│       ├── laundr.png (icon)
│       └── README.md
├── fbt
└── ...
```

### Step 3: Build the App

```bash
# Build just the LaundR app
./fbt fap_laundr

# Or build all external apps
./fbt fap_dist

# The .fap file will be in:
# build/f7-firmware-D/apps_data/laundr/laundr.fap
```

### Step 4: Install on Flipper

**Option A: Via USB with fbt**
```bash
# Connect Flipper via USB
# Build and launch directly
./fbt launch_app APPSRC=applications_user/laundr
```

**Option B: Via qFlipper**
1. Connect Flipper via USB
2. Open qFlipper
3. Navigate to File Manager → SD Card → apps → NFC
4. Upload `laundr.fap` from build directory
5. Restart Flipper
6. Apps → NFC → LaundR

**Option C: Via SD Card**
1. Remove SD card from Flipper
2. Insert into computer
3. Copy `laundr.fap` to `SD:/apps/NFC/`
4. Eject safely
5. Insert SD back into Flipper
6. Apps → NFC → LaundR

## Troubleshooting

### Build Errors

**Error: `furi.h` not found**
```bash
# Make sure you cloned with --recursive
git submodule update --init --recursive
```

**Error: Invalid application manifest**
```bash
# Check application.fam syntax
# Make sure all required fields are present
```

**Error: Undefined reference to NFC functions**
```bash
# The NFC API changed between firmware versions
# Use the correct firmware version or update the code
```

### Icon Issues

**Missing icon error**
```bash
# Create a placeholder icon
cd applications_user/laundr
python3 -c "from PIL import Image; Image.new('1', (10,10), 1).save('laundr.png')"

# Or copy any 10x10 PNG
cp ../../assets/icons/NFC/NFC_10px.png laundr.png
```

### Runtime Errors

**App crashes on startup**
- Check logs: `./fbt cli` then `log` command
- Verify malloc/free balance
- Check for buffer overflows

**App doesn't appear in menu**
- Verify .fap is in correct directory: `SD:/apps/NFC/`
- Check file permissions
- Try restarting Flipper

## Advanced Build Options

### Building for Different Firmware

**Official Firmware**
```bash
git clone https://github.com/flipperdevices/flipperzero-firmware.git
cd flipperzero-firmware
# ... copy app ...
./fbt fap_laundr
```

**Unleashed Firmware**
```bash
git clone https://github.com/DarkFlippers/unleashed-firmware.git
cd unleashed-firmware
# ... copy app ...
./fbt fap_laundr
```

**Momentum Firmware**
```bash
git clone https://github.com/Next-Flip/Momentum-Firmware.git
cd Momentum-Firmware
# ... copy app ...
./fbt fap_laundr
```

### Customizing the Build

Edit `application.fam`:

```python
App(
    appid="laundr",
    name="LaundR",
    apptype=FlipperAppType.EXTERNAL,
    entry_point="laundr_app",
    requires=["gui", "nfc"],  # Add more dependencies if needed
    stack_size=4 * 1024,      # Increase if needed
    fap_icon="laundr.png",
    fap_category="NFC",
    fap_version="1.0",
    fap_description="Security research tool",
    fap_author="Your Name",
    fap_weburl="https://your-url.com",
)
```

### Debugging

**Enable verbose logging:**

In `laundr_simple.c`, add more `FURI_LOG_I/D/W/E` calls:

```c
FURI_LOG_D(TAG, "Debug: variable = %d", variable);
FURI_LOG_I(TAG, "Info: state changed to %d", state);
FURI_LOG_W(TAG, "Warning: unusual condition");
FURI_LOG_E(TAG, "Error: something went wrong");
```

**View logs:**
```bash
# Connect via USB
./fbt cli

# In the CLI:
> log
```

## File Size Optimization

**Current app size:** ~10-20 KB

**To reduce size:**
- Remove unused scenes
- Simplify UI
- Remove debug strings
- Optimize buffer sizes

**To check size:**
```bash
ls -lh build/f7-firmware-D/apps_data/laundr/laundr.fap
```

## Development Workflow

### Quick Iteration

```bash
# Make changes to laundr_simple.c
vim applications_user/laundr/laundr_simple.c

# Rebuild and launch (Flipper must be connected)
./fbt launch_app APPSRC=applications_user/laundr

# View logs in another terminal
./fbt cli
> log
```

### Testing Changes

1. Edit source files
2. `./fbt fap_laundr`
3. Copy to Flipper SD card
4. Test on Flipper
5. Check logs for errors
6. Repeat

## API Reference

### Commonly Used APIs

**GUI:**
```c
#include <gui/gui.h>
gui = furi_record_open(RECORD_GUI);
view_port = view_port_alloc();
```

**Input:**
```c
#include <input/input.h>
// InputKey: Up, Down, Left, Right, Ok, Back
// InputType: Press, Release, Short, Long
```

**Storage:**
```c
#include <storage/storage.h>
storage = furi_record_open(RECORD_STORAGE);
```

**NFC (for future implementation):**
```c
#include <nfc/nfc.h>
#include <nfc/nfc_device.h>
```

### Flipper Firmware Docs

- [Official Docs](https://developer.flipper.net)
- [API Reference](https://developer.flipper.net/flipperzero/doxygen/)
- [App Examples](https://github.com/flipperdevices/flipperzero-firmware/tree/dev/applications)

## Next Steps

Once you have the basic app working:

1. **Add NFC emulation** - Real MIFARE Classic emulation
2. **File loading** - Load .nfc files from SD card
3. **Advanced logging** - Save transaction logs to file
4. **UI improvements** - Better graphics, animations
5. **Configuration** - Save/load settings

See the full `laundr.c` for advanced features (currently a work in progress).
