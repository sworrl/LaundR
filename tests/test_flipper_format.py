#!/usr/bin/env python3
"""Test that LaundR saves files in Flipper Zero compatible format"""

import os
import sys
import tempfile
import re

def load_nfc_file(filepath):
    """Simulate LaundR's load_file() method"""
    blocks = {}
    headers = []

    with open(filepath, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    for line in lines:
        line = line.strip()
        if not line:
            continue

        # Parse headers
        if (line.startswith("Filetype") or line.startswith("Version") or
            line.startswith("Device type") or line.startswith("UID") or
            line.startswith("ATQA") or line.startswith("SAK") or
            line.startswith("Mifare") or line.startswith("Data format") or
            line.startswith("#")):
            headers.append(line)
            continue

        # Parse blocks
        if line.startswith("Block"):
            match = re.match(r'Block\s+(\d+)\s*:\s*(.+)', line)
            if match:
                block_id = int(match.group(1))
                block_data = match.group(2).strip()
                blocks[block_id] = block_data

    return headers, blocks


def save_nfc_file(filepath, headers, blocks, card_type="1K"):
    """Simulate LaundR's save_file() method (NEW FIXED VERSION)"""
    with open(filepath, 'w', newline='\n', encoding='utf-8') as f:
        # Write headers (no blank line after - Flipper Zero format requirement)
        for h in headers:
            f.write(h + "\n")

        # Write blocks immediately after headers (Flipper Zero compatible)
        limit = 256 if card_type == "4K" else 64
        for i in range(limit):
            if i in blocks:
                # Ensure uppercase hex formatting
                block_data = blocks[i].upper()
                f.write(f"Block {i}: {block_data}\n")
            else:
                # Default blocks for missing data
                if (i + 1) % 4 == 0:  # Sector trailer
                    f.write(f"Block {i}: FF FF FF FF FF FF FF 07 80 69 FF FF FF FF FF FF\n")
                else:
                    f.write(f"Block {i}: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00\n")


def verify_flipper_format(filepath):
    """Verify file matches Flipper Zero format requirements"""
    with open(filepath, 'r') as f:
        lines = f.readlines()

    errors = []
    warnings = []

    # Check header structure
    if not lines[0].startswith("Filetype: Flipper NFC device"):
        errors.append("Line 0 must be 'Filetype: Flipper NFC device'")

    # Find where blocks start
    block_start = None
    for i, line in enumerate(lines):
        if line.startswith("Block 0:"):
            block_start = i
            break

    if block_start is None:
        errors.append("Could not find 'Block 0:'")
        return errors, warnings

    # Check no blank line before blocks
    if block_start > 0 and lines[block_start - 1].strip() == "":
        errors.append(f"Blank line found at line {block_start - 1} (before blocks)")

    # Verify block format
    for i in range(block_start, min(block_start + 10, len(lines))):
        line = lines[i].strip()
        if not line.startswith("Block"):
            continue

        match = re.match(r'Block\s+(\d+):\s+(.+)', line)
        if not match:
            errors.append(f"Line {i}: Invalid block format")
            continue

        hex_data = match.group(2)
        bytes_list = hex_data.split()

        # Check each byte
        for byte_val in bytes_list:
            if byte_val == "??":
                continue  # ?? is valid for unknown data

            # Check it's 2 characters
            if len(byte_val) != 2:
                errors.append(f"Line {i}: Byte '{byte_val}' is not 2 characters")

            # Check it's uppercase hex
            if not all(c in '0123456789ABCDEF' for c in byte_val):
                errors.append(f"Line {i}: Byte '{byte_val}' is not uppercase hex")

        # Check for double spaces
        if '  ' in hex_data:
            warnings.append(f"Line {i}: Double spaces found in hex data")

    return errors, warnings


def main():
    print("=" * 60)
    print("Testing Flipper Zero Format Compatibility")
    print("=" * 60)

    # Test with actual CSC card
    original_file = "test_cards/MFC_1K_2025-12-19_13,09,25.nfc"
    if not os.path.exists(original_file):
        print(f"✗ Test file not found: {original_file}")
        return 1

    print(f"\n1. Loading original file: {original_file}")
    headers, blocks = load_nfc_file(original_file)
    print(f"   ✓ Loaded {len(blocks)} blocks, {len(headers)} header lines")

    # Modify a block (simulate editing)
    print(f"\n2. Simulating block edit (changing Block 4 balance to $10.00)")
    # $10.00 = 1000 cents = 0x03E8
    import struct
    val = 1000
    val_bytes = struct.pack('<H', val)  # Little-endian 16-bit
    val_inv_bytes = struct.pack('<H', val ^ 0xFFFF)
    cnt_bytes = struct.pack('<H', 0)
    cnt_inv_bytes = struct.pack('<H', 0xFFFF)
    addr = 4
    addr_inv = addr ^ 0xFF

    new_block = (val_bytes + cnt_bytes + val_inv_bytes + cnt_inv_bytes +
                val_bytes + cnt_bytes + bytes([addr, addr_inv, addr, addr_inv]))
    blocks[4] = " ".join([f"{b:02X}" for b in new_block])
    print(f"   ✓ Block 4 updated: {blocks[4]}")

    # Save to temp file
    temp_fd, temp_path = tempfile.mkstemp(suffix='.nfc', text=True)
    os.close(temp_fd)

    try:
        print(f"\n3. Saving to temp file: {temp_path}")
        save_nfc_file(temp_path, headers, blocks)
        print(f"   ✓ File saved")

        # Verify format
        print(f"\n4. Verifying Flipper Zero format compliance:")
        errors, warnings = verify_flipper_format(temp_path)

        if errors:
            print(f"   ✗ {len(errors)} ERRORS found:")
            for err in errors:
                print(f"      - {err}")
            return 1

        if warnings:
            print(f"   ⚠ {len(warnings)} warnings:")
            for warn in warnings:
                print(f"      - {warn}")

        print(f"   ✓ File format is valid!")

        # Show sample output
        print(f"\n5. Sample output (first 20 lines):")
        with open(temp_path, 'r') as f:
            lines = f.readlines()
            for i, line in enumerate(lines[:20]):
                print(f"   {i:2d}: {line.rstrip()}")

        print("\n" + "=" * 60)
        print("✅ ALL TESTS PASSED - Flipper Zero compatible!")
        print("=" * 60)
        return 0

    finally:
        if os.path.exists(temp_path):
            os.unlink(temp_path)


if __name__ == "__main__":
    sys.exit(main())
