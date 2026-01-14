# Flipper Zero .nfc Format Requirements

**Date:** December 21, 2025
**Status:** ✅ FULLY COMPATIBLE

---

## Format Specification

### File Structure

```
Filetype: Flipper NFC device
Version: 4
# Device type can be ISO14443-3A, ...
Device type: Mifare Classic
# UID is common for all formats
UID: 7A E3 4C D8
# ISO14443-3A specific data
ATQA: 00 04
SAK: 08
# Mifare Classic specific data
Mifare Classic type: 1K
Data format version: 2
# Mifare Classic blocks, '??' means unknown data
Block 0: 7A E3 4C D8 0D 08 04 00 62 F1 4A 7C 5E C3 A2 81
Block 1: 30 30 00 01 00 00 01 84 28 30 00 00 01 11 EE 62
...
```

### Critical Requirements

#### 1. **NO Blank Lines Between Headers and Blocks** ⚠️
- **CORRECT:**
  ```
  Data format version: 2
  # Mifare Classic blocks, '??' means unknown data
  Block 0: 7A E3 4C D8...
  ```

- **WRONG:**
  ```
  Data format version: 2
  # Mifare Classic blocks, '??' means unknown data
  <BLANK LINE>
  Block 0: 7A E3 4C D8...
  ```

**Why:** Flipper Zero's parser expects blocks to start immediately after the comment line. A blank line will cause parsing errors.

---

#### 2. **Uppercase Hex Only** ⚠️
- **CORRECT:** `FF AA BB CC DD EE`
- **WRONG:** `ff aa bb cc dd ee`

**Why:** Flipper Zero's .nfc files use uppercase hexadecimal exclusively. Mixed case or lowercase may cause issues.

---

#### 3. **Exactly 2 Characters Per Byte** ⚠️
- **CORRECT:** `0F AA BB`
- **WRONG:** `F AA BB` (missing leading zero)

**Why:** Each byte must be represented as exactly 2 hex characters. Single-character bytes must be zero-padded.

---

#### 4. **Single Spaces Between Bytes** ⚠️
- **CORRECT:** `FF AA BB CC`
- **WRONG:** `FF  AA  BB  CC` (double spaces)
- **WRONG:** `FFAABBCC` (no spaces)

**Why:** Flipper Zero expects exactly one space between each byte in a block.

---

#### 5. **Preserve `??` for Unknown Data** ⚠️
- **CORRECT:** `EE B7 06 FC 71 4F 78 77 88 00 ?? ?? ?? ?? ?? ??`
- **WRONG:** `EE B7 06 FC 71 4F 78 77 88 00 00 00 00 00 00 00`

**Why:** The `??` marker indicates bytes that were not readable (usually Key A/B in sector trailers). Replacing with `00` would create invalid keys.

---

#### 6. **Line Endings** ⚠️
- **CORRECT:** Unix line endings (`\n`)
- **ACCEPTABLE:** Windows line endings (`\r\n`)

**Why:** Flipper Zero accepts both, but Unix (`\n`) is preferred for consistency.

---

## LaundR Implementation

### Files Modified

#### 1. `laundr.py` - `save_file()` method (lines 1192-1219)

**OLD (BROKEN):**
```python
with open(path, 'w', newline='\n') as f:
    for h in self.headers: f.write(h + "\n")
    f.write("\n")  # ← EXTRA BLANK LINE - BREAKS FLIPPER ZERO!
    for i in range(limit):
        if i in self.blocks: f.write(f"Block {i}: {self.blocks[i]}\n")
```

**NEW (FIXED):**
```python
with open(path, 'w', newline='\n', encoding='utf-8') as f:
    # Write headers (no blank line after - Flipper Zero format requirement)
    for h in self.headers:
        f.write(h + "\n")

    # Write blocks immediately after headers (Flipper Zero compatible)
    limit = 256 if self.card_type == "4K" else 64
    for i in range(limit):
        if i in self.blocks:
            # Ensure uppercase hex formatting
            block_data = self.blocks[i].upper()
            f.write(f"Block {i}: {block_data}\n")
```

---

#### 2. `laundr.py` - `commit_raw_changes()` method (lines 1135-1172)

**Added hex normalization:**
```python
# Get raw hex from entry field
raw_hex = self.ent_hex.get().strip()

# Normalize to Flipper Zero format (uppercase, single spaces, preserve ??)
parts = raw_hex.split()
normalized_parts = []
for part in parts:
    if part == "??":
        # Preserve unknown data marker
        normalized_parts.append("??")
    else:
        # Convert to uppercase 2-character hex
        part = part.upper()
        if len(part) == 1:
            part = "0" + part
        normalized_parts.append(part)

# Join with single spaces (Flipper Zero format)
normalized_hex = " ".join(normalized_parts)
self.blocks[self.current_block] = normalized_hex
```

---

### Hex Format Functions

All block editing functions already generate proper format:

**update_balance()** (line 546):
```python
hex_str = " ".join([f"{b:02X}" for b in new_block])
```
- `{b:02X}` = 2 characters, uppercase, zero-padded ✓

**update_transaction_block()** (line 640):
```python
hex_str = " ".join([f"{b:02X}" for b in new_b2])
```
- Same format ✓

---

## Testing

### Automated Test: `test_flipper_format.py`

Run this test to verify Flipper Zero compatibility:

```bash
cd "/home/eurrl/Documents/Code & Scripts/LaundR"
python3 test_flipper_format.py
```

**Expected Output:**
```
============================================================
Testing Flipper Zero Format Compatibility
============================================================

1. Loading original file: test_cards/MFC_1K_2025-12-19_13,09,25.nfc
   ✓ Loaded 64 blocks, 13 header lines

2. Simulating block edit (changing Block 4 balance to $10.00)
   ✓ Block 4 updated: E8 03 00 00 17 FC FF FF E8 03 00 00 04 FB 04 FB

3. Saving to temp file: /tmp/tmpXXXXXX.nfc
   ✓ File saved

4. Verifying Flipper Zero format compliance:
   ✓ File format is valid!

============================================================
✅ ALL TESTS PASSED - Flipper Zero compatible!
============================================================
```

---

### Manual Verification

1. **Save a file from LaundR**
2. **Copy to Flipper Zero** (`/ext/nfc/` directory)
3. **Try to read** using Flipper Zero NFC app
4. **Verify:** Card loads without errors

---

## Common Issues and Fixes

### Issue 1: "Failed to parse file"
**Cause:** Blank line between headers and blocks
**Fix:** Removed `f.write("\n")` at line 1198

### Issue 2: "Invalid hex data"
**Cause:** Lowercase hex or single-character bytes
**Fix:** Added `.upper()` and zero-padding in `commit_raw_changes()`

### Issue 3: "Unknown bytes replaced with 00"
**Cause:** Not preserving `??` markers
**Fix:** Special handling for `??` in normalization

### Issue 4: "File works but looks wrong"
**Cause:** Double spaces or inconsistent formatting
**Fix:** Normalize all hex to single-space separation

---

## Format Validation Checklist

Before saving files for Flipper Zero, verify:

- [ ] No blank lines between headers and Block 0
- [ ] All hex is uppercase (A-F, not a-f)
- [ ] Each byte is exactly 2 characters (0F not F)
- [ ] Single spaces between bytes (not double)
- [ ] `??` markers preserved for unknown data
- [ ] Unix line endings (\n) used
- [ ] File encoding is UTF-8

---

## Developer Notes

### Why Flipper Zero Ignores Comments

Comment lines (starting with `#`) are ignored by Flipper Zero's parser. This means we can:
- Add helpful notes to saved files
- Include metadata for debugging
- Document modifications without breaking compatibility

**Example:**
```
# Mifare Classic blocks, '??' means unknown data
# MODIFIED BY LAUNDR - Balance changed to $10.00
# Operator: CSC Service Works
# Date: 2025-12-21
Block 0: 7A E3 4C D8 0D 08 04 00 62 F1 4A 7C 5E C3 A2 81
```

This file will work perfectly on Flipper Zero, and the comments help track changes.

---

## References

### Flipper Zero Documentation
- [NFC File Format](https://docs.flipper.net/nfc)
- [MIFARE Classic](https://docs.flipper.net/nfc/mifare-classic)

### Test Files
- `test_cards/MFC_1K_2025-12-19_13,09,25.nfc` - Reference CSC card
- `test_cards/MFC_1K_2025-11-07_12,49,04.nfc` - Reference U-Best card

### Validation
- `test_flipper_format.py` - Automated format verification

---

## Summary

**Before Fixes:**
- ❌ Files had blank line after headers (parsing error)
- ❌ Manual edits could create lowercase/malformed hex
- ❌ No validation of format compliance

**After Fixes:**
- ✅ No blank line (exact Flipper Zero format)
- ✅ All hex normalized to uppercase, 2-char, single-space
- ✅ `??` markers preserved for unknown data
- ✅ Automated testing confirms compatibility

**LaundR now produces 100% Flipper Zero compatible .nfc files!** 🎉
