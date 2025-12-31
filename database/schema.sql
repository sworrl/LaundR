-- LaundR Database Schema
-- Dynamic card operator and structure database

-- Operator signatures and identification
CREATE TABLE IF NOT EXISTS operators (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Operator signature patterns for auto-detection
CREATE TABLE IF NOT EXISTS operator_signatures (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operator_id INTEGER,
    block_number INTEGER NOT NULL,
    byte_offset INTEGER NOT NULL,
    byte_length INTEGER NOT NULL,
    signature_value BLOB NOT NULL,  -- Hex bytes
    signature_type TEXT,  -- 'exact', 'ascii', 'pattern'
    confidence INTEGER DEFAULT 100,  -- 0-100
    FOREIGN KEY (operator_id) REFERENCES operators(id),
    UNIQUE(operator_id, block_number, byte_offset)
);

-- Block structure definitions per operator
CREATE TABLE IF NOT EXISTS block_structures (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operator_id INTEGER,
    block_number INTEGER NOT NULL,
    block_purpose TEXT,  -- 'balance', 'counter', 'transaction', 'metadata', etc.
    encoding_type TEXT,  -- '16bit_le', '32bit_le', 'bcd', 'ascii', etc.
    byte_offset INTEGER,
    byte_length INTEGER,
    has_inverse BOOLEAN DEFAULT 0,
    priority INTEGER DEFAULT 50,  -- Higher = more likely to be the field
    notes TEXT,
    FOREIGN KEY (operator_id) REFERENCES operators(id)
);

-- Known MIFARE Classic keys
CREATE TABLE IF NOT EXISTS known_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    key_hex TEXT NOT NULL UNIQUE,
    key_type TEXT,  -- 'factory', 'mad', 'operator', 'transit', 'access', etc.
    description TEXT,
    source TEXT,  -- 'proxmark3', 'mct', 'user', etc.
    operator_id INTEGER,  -- NULL if generic
    FOREIGN KEY (operator_id) REFERENCES operators(id)
);

-- User-confirmed values (machine learning)
CREATE TABLE IF NOT EXISTS confirmed_values (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operator_id INTEGER,
    block_number INTEGER NOT NULL,
    field_name TEXT NOT NULL,
    byte_offset INTEGER,
    byte_length INTEGER,
    value_cents INTEGER,  -- For money values
    value_text TEXT,  -- For other values
    confidence INTEGER DEFAULT 100,
    user_confirmed BOOLEAN DEFAULT 1,
    confirmed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    card_uid TEXT,  -- Optional: track which card this came from
    FOREIGN KEY (operator_id) REFERENCES operators(id)
);

-- Unknown operator patterns (for learning)
CREATE TABLE IF NOT EXISTS unknown_patterns (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    card_uid TEXT NOT NULL,
    block_number INTEGER NOT NULL,
    block_data BLOB NOT NULL,
    user_notes TEXT,
    discovered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Block access keys (for full read/write capability)
CREATE TABLE IF NOT EXISTS sector_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operator_id INTEGER,
    sector_number INTEGER NOT NULL,
    key_a TEXT,  -- 12 hex chars
    key_b TEXT,  -- 12 hex chars
    access_bits TEXT,  -- 6 hex chars
    source TEXT,  -- 'extracted', 'dictionary', 'cracked', 'user'
    FOREIGN KEY (operator_id) REFERENCES operators(id)
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_signatures_operator ON operator_signatures(operator_id);
CREATE INDEX IF NOT EXISTS idx_signatures_block ON operator_signatures(block_number);
CREATE INDEX IF NOT EXISTS idx_structures_operator ON block_structures(operator_id);
CREATE INDEX IF NOT EXISTS idx_structures_block ON block_structures(block_number);
CREATE INDEX IF NOT EXISTS idx_confirmed_operator ON confirmed_values(operator_id);
CREATE INDEX IF NOT EXISTS idx_keys_operator ON known_keys(operator_id);
CREATE INDEX IF NOT EXISTS idx_keys_hex ON known_keys(key_hex);
