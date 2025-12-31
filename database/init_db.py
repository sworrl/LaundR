#!/usr/bin/env python3
"""
LaundR Database Initialization
Creates and populates the SQLite database with known operators and keys
"""

import sqlite3
import os
import re

# Database location
DB_PATH = os.path.join(os.path.dirname(__file__), 'laundr.db')
SCHEMA_PATH = os.path.join(os.path.dirname(__file__), 'schema.sql')
SEED_PATH = os.path.join(os.path.dirname(__file__), 'seed_data.sql')
KEYS_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'assets', 'mifare_keys')

def init_database():
    """Initialize database with schema and seed data"""
    print(f"Creating database: {DB_PATH}")

    # Create database connection
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    # Load and execute schema
    print("Creating tables...")
    with open(SCHEMA_PATH, 'r') as f:
        schema_sql = f.read()
        cursor.executescript(schema_sql)

    # Load and execute seed data
    print("Inserting seed data...")
    with open(SEED_PATH, 'r') as f:
        seed_sql = f.read()
        cursor.executescript(seed_sql)

    conn.commit()
    print("Database schema and seed data created successfully!")

    return conn, cursor

def import_mifare_keys(cursor):
    """Import all MIFARE keys from .dic files"""
    if not os.path.exists(KEYS_DIR):
        print(f"Keys directory not found: {KEYS_DIR}")
        return

    keys_imported = 0
    keys_skipped = 0

    for filename in os.listdir(KEYS_DIR):
        if not (filename.endswith('.dic') or filename.endswith('.keys')):
            continue

        filepath = os.path.join(KEYS_DIR, filename)
        source = os.path.splitext(filename)[0]  # e.g., 'proxmark3_keys'

        print(f"Importing keys from: {filename}")

        try:
            with open(filepath, 'r') as f:
                for line_num, line in enumerate(f, 1):
                    line = line.strip()

                    # Skip comments and empty lines
                    if not line or line.startswith('#'):
                        continue

                    # Extract hex key (12 characters)
                    key_match = re.search(r'[0-9A-Fa-f]{12}', line)
                    if not key_match:
                        continue

                    key_hex = key_match.group(0).upper()

                    # Determine key type from common patterns
                    key_type = 'generic'
                    description = None

                    if key_hex == 'FFFFFFFFFFFF':
                        continue  # Already in seed data
                    elif key_hex == '000000000000':
                        continue  # Already in seed data
                    elif key_hex.startswith('A0A1'):
                        key_type = 'mad'
                    elif key_hex.startswith('D3F7'):
                        key_type = 'mad'
                    elif key_hex == key_hex[:6] + key_hex[:6]:
                        key_type = 'pattern'  # Repeating pattern
                        description = f'Repeating pattern: {key_hex[:6]}'

                    # Extract description from comment if available
                    if '#' in line:
                        comment = line.split('#', 1)[1].strip()
                        if comment and not description:
                            description = comment[:100]  # Limit length

                    # Insert into database
                    try:
                        cursor.execute('''
                            INSERT INTO known_keys (key_hex, key_type, description, source)
                            VALUES (?, ?, ?, ?)
                        ''', (key_hex, key_type, description, source))
                        keys_imported += 1
                    except sqlite3.IntegrityError:
                        # Key already exists (duplicate)
                        keys_skipped += 1

        except Exception as e:
            print(f"Error importing {filename}: {e}")

    print(f"Keys imported: {keys_imported}")
    print(f"Keys skipped (duplicates): {keys_skipped}")

def show_statistics(cursor):
    """Display database statistics"""
    print("\n=== Database Statistics ===")

    # Count operators
    cursor.execute("SELECT COUNT(*) FROM operators")
    print(f"Operators: {cursor.fetchone()[0]}")

    # Count signatures
    cursor.execute("SELECT COUNT(*) FROM operator_signatures")
    print(f"Operator Signatures: {cursor.fetchone()[0]}")

    # Count block structures
    cursor.execute("SELECT COUNT(*) FROM block_structures")
    print(f"Block Structures: {cursor.fetchone()[0]}")

    # Count keys
    cursor.execute("SELECT COUNT(*) FROM known_keys")
    print(f"Known Keys: {cursor.fetchone()[0]}")

    # List operators
    print("\nRegistered Operators:")
    cursor.execute("SELECT id, name, description FROM operators")
    for row in cursor.fetchall():
        print(f"  {row[0]}: {row[1]} - {row[2]}")

def main():
    """Main initialization function"""
    print("=" * 60)
    print("LaundR Database Initialization")
    print("=" * 60)
    print()

    # Initialize database
    conn, cursor = init_database()

    # Import MIFARE keys
    print("\nImporting MIFARE keys...")
    import_mifare_keys(cursor)
    conn.commit()

    # Show statistics
    show_statistics(cursor)

    # Close connection
    conn.close()

    print("\n" + "=" * 60)
    print("Database initialization complete!")
    print(f"Database location: {DB_PATH}")
    print("=" * 60)

if __name__ == '__main__':
    main()
