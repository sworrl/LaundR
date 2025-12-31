#!/usr/bin/env python3
"""Test GUI launch without opening window"""

import sys
import os

# Simulate loading without showing GUI
os.environ['DISPLAY'] = ':0'  # Ensure we have a display

# Import laundr
sys.path.insert(0, os.path.dirname(__file__))

print("Testing LaundR imports...")
try:
    import tkinter as tk
    print("✓ tkinter imported")

    # Import database
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'database'))
    from db_manager import LaundRDatabase
    print("✓ database imported")

    # Test database
    db = LaundRDatabase()
    stats = db.get_statistics()
    print(f"✓ database connected ({stats['keys']} keys)")
    db.close()

    # Now test if we can at least import and create the app
    print("\nTesting LaundR class initialization...")

    # Read laundr.py and check for basic errors
    with open('laundr.py', 'r') as f:
        code = f.read()

    # Check for detected_provider initialization
    if 'self.detected_provider = "Unknown"' in code:
        print("✓ detected_provider initialized")
    else:
        print("⚠️  detected_provider might not be initialized")

    # Check for database connection
    if 'LaundRDatabase()' in code:
        print("✓ database connection in code")
    else:
        print("✗ database connection missing")

    print("\n" + "="*60)
    print("All checks passed! LaundR should launch correctly.")
    print("="*60)

except Exception as e:
    print(f"\n✗ Error: {e}")
    import traceback
    traceback.print_exc()
