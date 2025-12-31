# Transaction Tracking Feature

## Overview
LaundR now includes intelligent transaction tracking that simulates real laundry card behavior when editing balances.

## New UI Elements

### Card Profile Section
```
┌─────────────────────────────────────────────────────────────┐
│ Provider: CSC Service Works        Active Block: Block 4    │
│                                                               │
│ Balance: $ [9.00] ✓                Last Transaction: $11.50 │
│                                                               │
│ Counter: 2                         ☑ Follow Transaction Rules│
│                                                               │
│ Last Change: +$41.00                                         │
└─────────────────────────────────────────────────────────────┘
```

### Components
1. **Editable Balance Field** - Type new amount and press Enter or click ✓
2. **Counter Display** - Shows number of transactions
3. **Last Transaction** - Amount from previous transaction (Block 2)
4. **Follow Transaction Rules** - Checkbox to enable/disable transaction tracking
5. **Last Change** - Shows the most recent balance change (in green if positive)

## How It Works

### With "Follow Transaction Rules" ENABLED (✓)

When you change the balance from $9.00 to $50.00:

**What Happens:**
1. ✅ Calculates difference: $50.00 - $9.00 = **+$41.00**
2. ✅ Updates Block 4 with new balance ($50.00)
3. ✅ Updates Block 8 (mirror/backup) identically
4. ✅ Increments counter: 2 → **3**
5. ✅ Updates Block 2 receipt field with transaction amount (**$41.00**)
6. ✅ Increments Block 2 transaction ID
7. ✅ Shows detailed message with all changes

**Success Dialog:**
```
Balance Updated
───────────────
Updated to $50.00
Added: $41.00
Counter: 2 → 3
Transaction recorded in Block 2
```

### With "Follow Transaction Rules" DISABLED (☐)

When you change the balance:

**What Happens:**
1. ✅ Updates Block 4 with new balance
2. ✅ Updates Block 8 (mirror/backup)
3. ❌ Counter stays the same
4. ❌ Block 2 is NOT modified
5. ✅ Shows simple update message

**Success Dialog:**
```
Balance Updated
───────────────
Updated to $50.00
Added: $41.00
```

## Block Updates in Detail

### Example: Adding $41.00 ($9.00 → $50.00)

#### Block 4 (Balance) - BEFORE
```
84 03 02 00 7B FC FD FF 84 03 02 00 04 FB 04 FB
│  │  │  │
│  │  └──┴─ Counter: 0x0002 = 2
└──┴─────── Balance: 0x0384 = 900¢ = $9.00
```

#### Block 4 (Balance) - AFTER
```
88 13 03 00 77 EC FC FF 88 13 03 00 04 FB 04 FB
│  │  │  │
│  │  └──┴─ Counter: 0x0003 = 3 ← INCREMENTED
└──┴─────── Balance: 0x1388 = 5000¢ = $50.00 ← UPDATED
```

#### Block 2 (Transaction) - BEFORE
```
01 01 C5 CB AB 02 00 00 00 7E 04 01 00 00 00 DC
      │  │  │  │           │  │
      │  │  │  │           └──┴─ Receipt: 0x047E = $11.50
      └──┴──┴──┴────────────────── TX ID: 0x02ABCBC5
```

#### Block 2 (Transaction) - AFTER
```
01 01 C6 CB AB 02 00 00 00 04 10 01 00 00 00 DC
      │  │  │  │           │  │
      │  │  │  │           └──┴─ Receipt: 0x1004 = $41.00 ← UPDATED
      └──┴──┴──┴────────────────── TX ID: 0x02ABCBC6 ← INCREMENTED
```

## Use Cases

### 1. Top-Up Scenario
**Real-world:** Customer adds $50 to their card at the machine
**LaundR:**
- Enable "Follow Transaction Rules"
- Change balance from $9.00 to $59.00
- Result: Automatically records +$50.00 transaction

### 2. Testing Scenario
**Real-world:** You want to test different balances
**LaundR:**
- Disable "Follow Transaction Rules"
- Change balance freely without affecting transaction history
- Counter stays the same

### 3. Forensic Analysis
**Real-world:** Examining transaction history
**LaundR:**
- View "Last Transaction" to see previous operation
- View "Last Change" to see difference
- Counter shows total number of operations

### 4. Deduction Scenario
**Real-world:** Customer uses $5 for laundry
**LaundR:**
- Enable "Follow Transaction Rules"
- Change balance from $50.00 to $45.00
- Result: Records -$5.00 transaction (shows as "Deducted: $5.00")

## Transaction Data Flow

```
┌──────────────────────────────────────────────────────────────┐
│                    USER EDITS BALANCE                         │
│                    $9.00 → $50.00                            │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
              ┌──────────────────────┐
              │ Calculate Difference │
              │    $50 - $9 = +$41   │
              └──────────┬───────────┘
                         │
        ┌────────────────┴────────────────┐
        │                                  │
        ▼                                  ▼
┌───────────────┐              ┌──────────────────────┐
│   Block 4     │              │  Follow TX Rules?    │
│  Update to    │              │                       │
│   $50.00      │              │  ☑ YES    ☐ NO      │
└───────┬───────┘              └──────────┬───────────┘
        │                                  │
        │                         ┌────────┴────────┐
        │                         │                  │
        │                    YES  │                  │ NO
        │                         ▼                  ▼
        │                ┌─────────────────┐   ┌──────────┐
        │                │ Increment       │   │   Skip   │
        │                │ Counter: 2 → 3  │   │          │
        │                └────────┬────────┘   └──────────┘
        │                         │
        │                         ▼
        │                ┌─────────────────┐
        │                │  Update Block 2 │
        │                │  Receipt: $41   │
        │                │  TX ID: +1      │
        │                └─────────────────┘
        │                         │
        └─────────────────────────┴──────────────────────┐
                                                          │
                                                          ▼
                                                 ┌────────────────┐
                                                 │ Update Block 8 │
                                                 │   (Mirror)     │
                                                 └────────────────┘
```

## Validation

All updates maintain card integrity:

✅ **Balance inverse**: `Value XOR ~Value = 0xFFFF`
✅ **Counter inverse**: `Counter XOR ~Counter = 0xFFFF`
✅ **Mirror consistency**: Block 4 = Block 8
✅ **Transaction continuity**: Counter increments sequentially
✅ **Receipt accuracy**: Matches the actual transaction amount

## GUI Workflow

1. **Load Card**
   - File → Open → Select .nfc file
   - Card Profile displays current state

2. **Enable Transaction Tracking**
   - Check "☑ Follow Transaction Rules" (enabled by default)

3. **Edit Balance**
   - Click in balance field
   - Type new amount: "50.00"
   - Press Enter or click ✓

4. **Review Changes**
   - Dialog shows: what changed, amount added/deducted, counter changes
   - "Last Change" displays the transaction amount
   - "Last Transaction" shows it was recorded

5. **Save**
   - File → Save As
   - Write modified card to new .nfc file

## Example Session

```
Initial State:
  Balance: $9.00
  Counter: 2
  Last Transaction: $11.50

Action 1: Add $41.00 (with rules enabled)
  New Balance: $50.00
  Counter: 2 → 3
  Last Transaction: $11.50 → $41.00
  Last Change: +$41.00

Action 2: Use $5.00 (with rules enabled)
  New Balance: $45.00
  Counter: 3 → 4
  Last Transaction: $41.00 → $5.00
  Last Change: -$5.00 (shown as red)

Action 3: Test with $100 (rules disabled)
  New Balance: $100.00
  Counter: 4 (unchanged)
  Last Transaction: $5.00 (unchanged)
  Last Change: +$55.00 (but not recorded)
```

## Technical Details

### Counter Management
- Stored in Block 4 bytes 2-3 (little-endian)
- Inverse stored in bytes 6-7
- Auto-increments when balance changes (if rules enabled)
- Range: 0 to 65535

### Transaction ID
- Stored in Block 2 bytes 2-5 (32-bit little-endian)
- Increments with each transaction
- Appears to be a sequential counter
- Example: 0x02ABCBC5 → 0x02ABCBC6

### Receipt Field
- Stored in Block 2 bytes 9-10 (16-bit little-endian)
- Always stores absolute value (positive)
- Represents the transaction amount (not balance)
- Range: $0.00 to $655.35

## Benefits

1. **Realistic Simulation** - Mimics real laundry card machine behavior
2. **Forensic Accuracy** - Transaction history matches real cards
3. **Flexible Testing** - Can disable rules for experimental changes
4. **Data Integrity** - All blocks stay synchronized
5. **User Clarity** - Clear indication of what changed and by how much
6. **Audit Trail** - Counter and transaction logs provide history

## Tips

- **Always enable "Follow Transaction Rules"** when simulating real card usage
- **Disable for testing** when you just want to set a specific balance
- **Check "Last Change"** to see if your edit was recorded as expected
- **Watch the counter** to track total number of operations
- **Save before major edits** to preserve original state
