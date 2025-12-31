#!/usr/bin/env python3
"""
LaundR Database Manager
Handles all database operations for operator detection and card analysis
"""

import sqlite3
import os
from typing import List, Dict, Optional, Tuple

DB_PATH = os.path.join(os.path.dirname(__file__), 'laundr.db')

class LaundRDatabase:
    """Database manager for LaundR card analysis"""

    def __init__(self):
        self.conn = None
        self.cursor = None
        self.connect()

    def connect(self):
        """Connect to the database"""
        if not os.path.exists(DB_PATH):
            raise FileNotFoundError(f"Database not found: {DB_PATH}\nRun database/init_db.py first!")

        self.conn = sqlite3.connect(DB_PATH)
        self.conn.row_factory = sqlite3.Row  # Access columns by name
        self.cursor = self.conn.cursor()

    def close(self):
        """Close database connection"""
        if self.conn:
            self.conn.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

    # ========== OPERATOR DETECTION ==========

    def detect_operator(self, blocks: Dict[int, bytes]) -> Tuple[Optional[int], Optional[str], int]:
        """
        Detect operator from card blocks using database signatures
        Returns: (operator_id, operator_name, confidence)
        """
        # Get all signatures ordered by confidence
        self.cursor.execute('''
            SELECT s.operator_id, s.block_number, s.byte_offset, s.byte_length,
                   s.signature_value, s.signature_type, s.confidence,
                   o.name as operator_name
            FROM operator_signatures s
            JOIN operators o ON s.operator_id = o.id
            ORDER BY s.confidence DESC
        ''')

        for row in self.cursor.fetchall():
            block_num = row['block_number']
            if block_num not in blocks:
                continue

            block_data = blocks[block_num]
            offset = row['byte_offset']
            length = row['byte_length']
            expected = row['signature_value']
            sig_type = row['signature_type']

            # Extract actual bytes
            if offset + length > len(block_data):
                continue

            actual = block_data[offset:offset+length]

            # Check signature match
            if sig_type == 'exact':
                if actual == expected:
                    return (row['operator_id'], row['operator_name'], row['confidence'])
            elif sig_type == 'ascii':
                # ASCII comparison (case-insensitive)
                # Convert both to strings for comparison
                actual_str = actual.decode('ascii', errors='ignore') if isinstance(actual, bytes) else str(actual)
                expected_str = expected.decode('ascii', errors='ignore') if isinstance(expected, bytes) else str(expected)

                if actual_str.upper() == expected_str.upper():
                    return (row['operator_id'], row['operator_name'], row['confidence'])

        # No match found - return Unknown operator
        return (3, 'Unknown', 0)

    def get_operator_name(self, operator_id: int) -> str:
        """Get operator name by ID"""
        self.cursor.execute("SELECT name FROM operators WHERE id = ?", (operator_id,))
        row = self.cursor.fetchone()
        return row['name'] if row else 'Unknown'

    # ========== BLOCK STRUCTURE QUERIES ==========

    def get_block_structures(self, operator_id: int, block_number: int) -> List[Dict]:
        """Get all known structures for a specific block and operator"""
        self.cursor.execute('''
            SELECT * FROM block_structures
            WHERE operator_id = ? AND block_number = ?
            ORDER BY priority DESC
        ''', (operator_id, block_number))

        return [dict(row) for row in self.cursor.fetchall()]

    def get_balance_blocks(self, operator_id: int) -> List[int]:
        """Get block numbers that likely contain balance for this operator"""
        self.cursor.execute('''
            SELECT DISTINCT block_number FROM block_structures
            WHERE operator_id = ? AND block_purpose LIKE '%balance%'
            ORDER BY priority DESC
        ''', (operator_id,))

        return [row['block_number'] for row in self.cursor.fetchall()]

    def get_block_purpose(self, operator_id: int, block_number: int) -> Optional[str]:
        """Get the primary purpose of a block"""
        self.cursor.execute('''
            SELECT block_purpose FROM block_structures
            WHERE operator_id = ? AND block_number = ?
            ORDER BY priority DESC LIMIT 1
        ''', (operator_id, block_number))

        row = self.cursor.fetchone()
        return row['block_purpose'] if row else None

    # ========== KEY IDENTIFICATION ==========

    def identify_key(self, key_hex: str) -> Optional[Dict]:
        """Identify a key from the database"""
        key_hex = key_hex.upper().replace(' ', '')
        self.cursor.execute('''
            SELECT key_hex, key_type, description, source
            FROM known_keys
            WHERE key_hex = ?
        ''', (key_hex,))

        row = self.cursor.fetchone()
        return dict(row) if row else None

    def get_keys_count(self) -> int:
        """Get total number of known keys"""
        self.cursor.execute("SELECT COUNT(*) as count FROM known_keys")
        return self.cursor.fetchone()['count']

    # ========== CONFIRMED VALUES (ML) ==========

    def add_confirmed_value(self, operator_id: int, block_number: int, field_name: str,
                           byte_offset: int, byte_length: int, value_cents: Optional[int] = None,
                           value_text: Optional[str] = None, card_uid: Optional[str] = None):
        """Store a user-confirmed correct value"""
        self.cursor.execute('''
            INSERT INTO confirmed_values
            (operator_id, block_number, field_name, byte_offset, byte_length,
             value_cents, value_text, card_uid)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ''', (operator_id, block_number, field_name, byte_offset, byte_length,
              value_cents, value_text, card_uid))
        self.conn.commit()

    def get_confirmed_value(self, operator_id: int, block_number: int, field_name: str) -> Optional[Dict]:
        """Get a confirmed value if it exists"""
        self.cursor.execute('''
            SELECT * FROM confirmed_values
            WHERE operator_id = ? AND block_number = ? AND field_name = ?
            ORDER BY confirmed_at DESC LIMIT 1
        ''', (operator_id, block_number, field_name))

        row = self.cursor.fetchone()
        return dict(row) if row else None

    def is_value_confirmed(self, operator_id: int, block_number: int, field_name: str) -> bool:
        """Check if a value has been confirmed"""
        return self.get_confirmed_value(operator_id, block_number, field_name) is not None

    # ========== UNKNOWN PATTERNS ==========

    def store_unknown_pattern(self, card_uid: str, block_number: int, block_data: bytes, user_notes: str = None):
        """Store an unknown block pattern for later analysis"""
        self.cursor.execute('''
            INSERT INTO unknown_patterns (card_uid, block_number, block_data, user_notes)
            VALUES (?, ?, ?, ?)
        ''', (card_uid, block_number, block_data, user_notes))
        self.conn.commit()

    # ========== STATISTICS ==========

    def get_statistics(self) -> Dict:
        """Get database statistics"""
        stats = {}

        self.cursor.execute("SELECT COUNT(*) as count FROM operators")
        stats['operators'] = self.cursor.fetchone()['count']

        self.cursor.execute("SELECT COUNT(*) as count FROM operator_signatures")
        stats['signatures'] = self.cursor.fetchone()['count']

        self.cursor.execute("SELECT COUNT(*) as count FROM block_structures")
        stats['structures'] = self.cursor.fetchone()['count']

        self.cursor.execute("SELECT COUNT(*) as count FROM known_keys")
        stats['keys'] = self.cursor.fetchone()['count']

        self.cursor.execute("SELECT COUNT(*) as count FROM confirmed_values")
        stats['confirmed_values'] = self.cursor.fetchone()['count']

        return stats

    # ========== OPERATOR MANAGEMENT ==========

    def add_operator(self, name: str, description: str = None) -> int:
        """Add a new operator to the database"""
        self.cursor.execute('''
            INSERT INTO operators (name, description)
            VALUES (?, ?)
        ''', (name, description))
        self.conn.commit()
        return self.cursor.lastrowid

    def add_operator_signature(self, operator_id: int, block_number: int, byte_offset: int,
                               byte_length: int, signature_value: bytes, signature_type: str = 'exact',
                               confidence: int = 100):
        """Add a signature pattern for operator detection"""
        self.cursor.execute('''
            INSERT OR REPLACE INTO operator_signatures
            (operator_id, block_number, byte_offset, byte_length, signature_value, signature_type, confidence)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        ''', (operator_id, block_number, byte_offset, byte_length, signature_value, signature_type, confidence))
        self.conn.commit()

    def add_block_structure(self, operator_id: int, block_number: int, block_purpose: str,
                           encoding_type: str, byte_offset: int = None, byte_length: int = None,
                           has_inverse: bool = False, priority: int = 50, notes: str = None):
        """Add a block structure definition"""
        self.cursor.execute('''
            INSERT INTO block_structures
            (operator_id, block_number, block_purpose, encoding_type, byte_offset, byte_length,
             has_inverse, priority, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', (operator_id, block_number, block_purpose, encoding_type, byte_offset, byte_length,
              has_inverse, priority, notes))
        self.conn.commit()


# Singleton instance
_db_instance = None

def get_database() -> LaundRDatabase:
    """Get or create database singleton"""
    global _db_instance
    if _db_instance is None:
        _db_instance = LaundRDatabase()
    return _db_instance
