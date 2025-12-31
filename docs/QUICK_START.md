# LaundR - Quick Start Guide

## Launch the Application

```bash
python3 laundr.py
# or
python3 laundr.py /path/to/card.nfc
```

## Main Features

### 1. Edit Balance (Simple)
1. Load your .nfc file
2. Find the **Balance** field in Card Profile
3. Click in the field, type new amount (e.g., "50.00")
4. Press **Enter** or click **✓**

**Result:** Balance updated with proper hex encoding

### 2. Edit Balance with Transaction Tracking
1. Make sure **☑ Follow Transaction Rules** is checked (default)
2. Edit balance as above
3. See detailed popup showing:
   - New balance
   - Amount added/deducted
   - Counter incremented
   - Transaction recorded

**Example:**
```
$9.00 → $50.00 with rules enabled:
✓ Balance updated to $50.00
✓ Added: $41.00
✓ Counter: 2 → 3
✓ Transaction recorded in Block 2
```

### 3. Quick Testing (No Transaction Tracking)
1. **Uncheck** ☐ Follow Transaction Rules
2. Edit balance freely
3. Counter and transaction history stay unchanged

**Use when:** Testing different balances without affecting card history

## Card Profile Overview

```
┌─────────────────────────────────────────────────────┐
│ Provider: CSC Service Works    Block: Block 4       │
│                                                       │
│ Balance: $ [9.00] ✓            Last TX: $11.50      │
│                                                       │
│ Counter: 2                     ☑ Follow TX Rules    │
│                                                       │
│ Last Change: +$41.00                                │
└─────────────────────────────────────────────────────┘
```

**What Each Field Means:**
- **Balance:** Current card balance (editable)
- **Counter:** Number of transactions
- **Last Transaction:** Previous transaction amount (from Block 2)
- **Last Change:** Most recent balance change
- **Follow Transaction Rules:** Enable/disable automatic tracking

## Common Tasks

### Top-Up Card (Realistic)
```
1. Check ☑ Follow Transaction Rules
2. Current: $9.00 → Type: "50.00"
3. Press Enter
4. Result: +$41.00 recorded, counter increments
```

### Set Test Balance
```
1. Uncheck ☐ Follow Transaction Rules
2. Type any amount: "100.00"
3. Press Enter
4. Result: Balance changes only
```

### View Transaction History
- **Last Transaction:** Shows previous operation
- **Last Change:** Shows most recent change
- **Counter:** Shows total operations
- Select **Block 2** to see full transaction data

## File Operations

### Open
- File → Open → Select .nfc file
- Or: `python3 laundr.py card.nfc`

### Save
- File → Save As → Choose location
- Saves all changes to new .nfc file

### Export
- Tools → Export to JSON (full card data)
- Tools → Export to CSV (tabular format)
- Tools → Generate Report (forensic analysis)

## Block Inspector

**Select any block** in the left panel to see:
- Raw hex data (editable)
- ASCII representation
- Decoded values (balance, timestamps, etc.)
- Validation status

**Special Blocks:**
- **Block 0:** UID and card info
- **Block 2:** Transaction history
- **Block 4:** Primary balance
- **Block 8:** Backup balance (mirror)
- **Block 9:** System limit value
- **Block 13:** Card ID

## Keyboard Shortcuts

- **Enter:** Apply balance change
- **Type in balance field:** Edit value
- **Click in tree:** Select block
- **Double-click hex:** Edit raw data

## Color Coding

- 🟢 **Green:** Balance blocks (4, 8)
- 🔴 **Red:** Missing/unknown data
- ⚪ **Gray:** Sector trailers (keys)

## Tips

✅ **Always enable "Follow Transaction Rules"** for realistic card simulation
✅ **Save before major changes** to preserve original
✅ **Check "Last Change"** to verify your edit worked
✅ **Block 4 and 8 update together** (automatic mirror)
✅ **Counter increments** show transaction count
✅ **Transaction validates** inverse patterns automatically

⚠️ **Disable transaction rules** only for testing
⚠️ **Balance range:** $0.00 to $655.35 (16-bit limit)
⚠️ **Backup your files** before experimenting

## Validation

All edits maintain card integrity:
- ✓ Balance inverse pattern (XOR = 0xFFFF)
- ✓ Counter inverse pattern
- ✓ Mirror block consistency
- ✓ Transaction continuity
- ✓ Proper hex encoding

## Troubleshooting

**Q: Balance won't update?**
- Check you pressed Enter or clicked ✓
- Verify amount is within $0.00-$655.35

**Q: Counter not incrementing?**
- Make sure ☑ Follow Transaction Rules is checked

**Q: Block 2 not updating?**
- Transaction rules must be enabled
- Balance must actually change (not same value)

**Q: Hex looks wrong?**
- Inverse encoding is automatic
- Example: $9.00 = `84 03` (not `03 84`)

## Quick Reference: Block Structure

### Balance Block (4/8)
```
[Value 2b] [Count 2b] [~Value 2b] [~Count 2b] ...
 $9.00=0x0384  Cnt=2    Inverses
```

### Transaction Block (2)
```
[CSC ID] [TX Counter] ... [Receipt Amount]
 0x0101   0x02ABCBC5      $41.00=0x1004
```

## Need Help?

1. View block decoder for detailed analysis
2. Check TRANSACTION_TRACKING.md for full docs
3. Read LAUNDR_IMPROVEMENTS.md for features list
4. Inspect hex directly in Block Inspector

## Example Workflow

**Scenario: Add $50 to a card**

1. Launch: `python3 laundr.py card.nfc`
2. See current balance: $9.00
3. Verify: ☑ Follow Transaction Rules
4. Click balance field
5. Type: "59.00" (9 + 50)
6. Press Enter
7. See popup: "Added: $50.00, Counter: 2 → 3"
8. Save: File → Save As
9. Done! Card has $59.00, transaction recorded

---

**Ready to go! Start with your card file and explore the features.**
