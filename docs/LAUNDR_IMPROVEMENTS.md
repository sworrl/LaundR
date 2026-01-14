# LaundR.py Enhancement Summary

## File Analyzed
`/home/eurrl/Desktop/MFC_1K_2025-12-19_13,09,25.nfc`

## Card Details Discovered
- **Provider**: CSC Service Works (signature: 0x0101)
- **Card ID**: AZ7602046 (Block 13)
- **Balance**: $9.00 (Block 4)
- **Backup Balance**: Block 8 (mirrors Block 4)
- **Counter**: 2
- **Last Transaction**: $11.50 (Block 2, bytes 9-10)
- **Transaction ID**: 0x02ABCBC5 (Block 2, bytes 2-5)

## New Features Added

### 1. **Editable Balance in Overview** ✅
- Balance is now directly editable from the Card Profile section
- Click the checkmark or press Enter to update
- Automatically updates BOTH Block 4 and Block 8 (mirror/backup)
- Proper inverse encoding: `Val ^ 0xFFFF` and `Cnt ^ 0xFFFF`
- Validates range: $0.00 to $655.35 (16-bit limit)

**Format Used:**
```
[Val 2b] [Cnt 2b] [~Val 2b] [~Cnt 2b] [Val 2b] [Cnt 2b] [Addr] [~Addr] [Addr] [~Addr]
```

### 2. **Enhanced Decoders**
Added specialized decoders for:

#### Block 0 (UID)
- UID: 7A E3 4C D8
- BCC check byte
- SAK, ATQA values

#### Block 2 (Transaction/Receipt)
- Provider signature detection (0x0101 = CSC)
- Last transaction amount ($11.50 at bytes 9-10)
- Transaction ID/Counter (bytes 2-5)

#### Block 4 & 8 (Balance)
- Laundry value format with validation
- Shows if inverse values are valid (✓ VALID or ⚠ NO INVERSE)
- Displays counter value
- Indicates mirror/backup relationship

#### Block 9 (System Value)
- Large value block: 999,998
- Interprets as currency: $9,999.98
- Likely a max balance limit or system configuration

#### Block 12 (Counter/System Data)
- Value block format or counter values
- Detected value: 258 (or similar system data)

#### Block 13 (Card ID)
- ASCII card identifier: "AZ7602046"

#### Blocks 1 & 10
- ASCII content extraction
- Shows "00" and "NET" identifiers

### 3. **Improved UI**
- Balance field is now editable with green checkmark button
- Counter display added to show transaction count
- Better metadata labels for all special blocks
- Color coding:
  - **Green**: Balance blocks (4, 8)
  - **Red**: Unknown/partial data
  - **Gray**: Sector trailers

### 4. **Data Validation**
- Inverse validation for value blocks
- Hex data format checking
- Range validation for balance edits
- Mirror block consistency checks

## Hex Block Breakdown

### Block 4 Analysis (Primary Balance)
```
84 03 02 00 7B FC FD FF 84 03 02 00 04 FB 04 FB
│  │  │  │  │  │  │  │  │  │  │  │  │  │  │  │
│  │  │  │  │  │  │  │  │  │  │  │  └──┴──┴──┴── Addr bytes: 04 FB 04 FB
│  │  │  │  │  │  │  │  └──┴──┴──┴────────────── Duplicate val+cnt
│  │  │  │  └──┴──┴──┴─────────────────────────── ~Val ~Cnt (inverses)
└──┴──┴──┴────────────────────────────────────── Val=0x0384 (900¢) Cnt=0x0002
```

**Validation:**
- Value: 0x0384 XOR 0xFC7B = 0xFFFF ✓
- Counter: 0x0002 XOR 0xFFFD = 0xFFFF ✓

### Block 2 Analysis (Transaction)
```
01 01 C5 CB AB 02 00 00 00 7E 04 01 00 00 00 DC
│  │  │  │  │  │           │  │
│  │  │  │  │  │           │  └── Receipt: 0x047E = 1150¢ = $11.50
│  │  └──┴──┴──┴──────────────── Transaction ID: 0x02ABCBC5
└──┴─────────────────────────────── CSC Signature: 0x0101
```

## Testing

### Balance Encoding Test
```python
# Original: $9.00, counter=2
84 03 02 00 7B FC FD FF 84 03 02 00 04 FB 04 FB ✓ MATCH

# Test: $50.00, counter=2
88 13 02 00 77 EC FD FF 88 13 02 00 04 FB 04 FB ✓ VALID

# Test: $100.00, counter=5
10 27 05 00 EF D8 FA FF 10 27 05 00 04 FB 04 FB ✓ VALID
```

## Usage

### Edit Balance from GUI
1. Load the .nfc file
2. View current balance in Card Profile section
3. Click in the balance field and enter new amount (e.g., "50.00")
4. Press Enter or click the ✓ button
5. Blocks 4 and 8 are automatically updated with proper encoding

### Decode Analysis
- Select any block in the left panel
- View decoded information in the "Block Inspector" section
- See multiple interpretations (currency, timestamps, integers, etc.)
- Access bits validation for sector trailers

## Files Modified
- `laundr.py` - Main application with all enhancements

## Command Line Usage
```bash
# GUI with file
python3 laundr.py /path/to/card.nfc

# Generate report (no GUI)
python3 laundr.py -o report.txt card.nfc

# Export to JSON
python3 laundr.py --json export.json card.nfc

# Export to CSV
python3 laundr.py --csv export.csv card.nfc
```
