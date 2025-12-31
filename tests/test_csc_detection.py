#!/usr/bin/env python3
"""Debug script to test CSC card detection"""

import sys
import os
import re

# Add database to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'database'))
from db_manager import LaundRDatabase

def parse_nfc_file(filepath):
    """Parse NFC file and extract blocks"""
    blocks = {}
    with open(filepath, 'r') as f:
        for line in f:
            line = line.strip()
            if line.startswith('Block '):
                match = re.match(r'Block\s+(\d+):\s+([0-9A-Fa-f\s?]+)', line)
                if match:
                    block_id = int(match.group(1))
                    hex_data = match.group(2).replace(' ', '').replace('?', '0')
                    try:
                        block_data = bytes.fromhex(hex_data)
                        blocks[block_id] = block_data
                    except:
                        pass
    return blocks

# Test with CSC card
print("=" * 60)
print("Testing CSC Card Detection")
print("=" * 60)

filepath = "test_cards/MFC_1K_2025-12-19_13,09,25.nfc"
print(f"\nLoading: {filepath}")

blocks = parse_nfc_file(filepath)
print(f"Loaded {len(blocks)} blocks")

# Check Block 2
if 2 in blocks:
    print(f"\nBlock 2 data: {blocks[2].hex().upper()}")
    print(f"First 2 bytes: {blocks[2][0:2].hex().upper()}")
else:
    print("\n⚠️  Block 2 not found!")

# Test database detection
db = LaundRDatabase()
op_id, op_name, conf = db.detect_operator(blocks)

print(f"\n=== Detection Result ===")
print(f"Operator ID: {op_id}")
print(f"Operator Name: {op_name}")
print(f"Confidence: {conf}%")

if op_name == "CSC Service Works":
    print("\n✓ SUCCESS: CSC detected correctly!")
else:
    print(f"\n✗ FAIL: Expected 'CSC Service Works', got '{op_name}'")

# Get balance blocks
balance_blocks = db.get_balance_blocks(op_id)
print(f"\nBalance blocks for operator {op_id}: {balance_blocks}")

db.close()
