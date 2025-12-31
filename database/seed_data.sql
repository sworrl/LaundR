-- Seed data for LaundR database
-- Initial known operators and patterns

-- Insert known operators
INSERT OR IGNORE INTO operators (id, name, description) VALUES
    (1, 'CSC Service Works', 'CSC ServiceWorks - Major laundry equipment provider'),
    (2, 'U-Best Wash', 'U-Best Wash Systems - Laundry card operator'),
    (3, 'Unknown', 'Unidentified operator - to be determined');

-- CSC Service Works signatures
INSERT OR IGNORE INTO operator_signatures (operator_id, block_number, byte_offset, byte_length, signature_value, signature_type, confidence) VALUES
    (1, 2, 0, 2, X'0101', 'exact', 100);  -- Block 2, bytes 0-1 = 01 01

-- U-Best Wash signatures
INSERT OR IGNORE INTO operator_signatures (operator_id, block_number, byte_offset, byte_length, signature_value, signature_type, confidence) VALUES
    (2, 1, 0, 11, X'5542455354574153484C41', 'ascii', 100);  -- Block 1 = "UBESTWASHLA" ASCII (11 bytes)

-- CSC Service Works block structures
INSERT OR IGNORE INTO block_structures (operator_id, block_number, block_purpose, encoding_type, byte_offset, byte_length, has_inverse, priority, notes) VALUES
    -- Balance blocks
    (1, 4, 'balance', '16bit_le', 0, 2, 1, 100, 'Primary balance - cents value'),
    (1, 4, 'counter', '16bit_le', 2, 2, 1, 90, 'Transaction counter'),
    (1, 8, 'balance', '16bit_le', 0, 2, 1, 95, 'Mirror balance block'),
    (1, 8, 'counter', '16bit_le', 2, 2, 1, 85, 'Mirror counter'),
    -- Transaction data
    (1, 2, 'last_transaction', '16bit_le', 9, 2, 0, 80, 'Last top-up/transaction amount'),
    (1, 2, 'transaction_id', '32bit_le', 2, 4, 0, 70, 'Sequential transaction ID'),
    -- Usage counter
    (1, 9, 'usages_left', '16bit_le', 0, 2, 0, 90, 'Remaining usage cycles'),
    (1, 9, 'max_value', '32bit_le', 0, 4, 1, 60, 'Maximum balance capacity'),
    -- Metadata
    (1, 1, 'creation_date', 'unix_timestamp', 12, 4, 0, 75, 'Card creation timestamp'),
    (1, 13, 'card_id', 'ascii', 0, 9, 0, 95, 'Unique card identifier');

-- U-Best Wash block structures (from known data)
INSERT OR IGNORE INTO block_structures (operator_id, block_number, block_purpose, encoding_type, byte_offset, byte_length, has_inverse, priority, notes) VALUES
    -- Block 2 possible balances (USER NEEDS TO CONFIRM)
    (2, 2, 'balance_candidate', '16bit_le', 8, 2, 0, 95, 'Possible balance - likely $2.00'),
    (2, 2, 'balance_candidate', '16bit_le', 10, 2, 0, 90, 'Possible balance - likely $1.62'),
    -- Blocks 28-30 (validated value blocks, but purpose unknown)
    (2, 28, 'value_unknown', '32bit_le', 0, 4, 1, 50, 'Validated value block - $2.75 (purpose TBD)'),
    (2, 29, 'value_unknown', '32bit_le', 0, 4, 1, 50, 'Validated value block - $6.00 (purpose TBD)'),
    (2, 30, 'value_unknown', '32bit_le', 0, 4, 1, 30, 'Validated value block - $20.00 (NOT actual balance)'),
    -- Operator identifier
    (2, 1, 'operator_id', 'ascii', 0, 11, 0, 100, 'ASCII operator name');

-- Factory default keys
INSERT OR IGNORE INTO known_keys (key_hex, key_type, description, source) VALUES
    ('FFFFFFFFFFFF', 'factory', 'Factory Default MIFARE', 'standard'),
    ('000000000000', 'factory', 'All Zeros', 'standard'),
    ('A0A1A2A3A4A5', 'mad', 'MAD Key A (NFC Forum)', 'standard'),
    ('D3F7D3F7D3F7', 'mad', 'MAD Key B (NFC Forum)', 'standard'),
    ('B0B1B2B3B4B5', 'factory', 'Factory Variant', 'standard'),
    ('C0C1C2C3C4C5', 'factory', 'Factory Variant', 'standard'),
    ('D0D1D2D3D4D5', 'factory', 'Factory Variant', 'standard'),
    ('AABBCCDDEEFF', 'factory', 'Common Default', 'standard');

-- Note: Additional keys from proxmark3_keys.dic and extended_std_keys.dic
-- will be imported via Python script (too many to list here)
