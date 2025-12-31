#!/usr/bin/env python3
import tkinter as tk
from tkinter import ttk, filedialog, messagebox, Menu
import struct
import re
import json
import os
import sys
import argparse
from datetime import datetime
import csv
import copy
from typing import Dict, List, Optional, Any

# Import database manager
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'database'))
from db_manager import LaundRDatabase

CONFIG_FILE = os.path.join(os.path.dirname(__file__), "user_data", "laundr_config.json")
KEYS_DIR = os.path.join(os.path.dirname(__file__), "assets", "mifare_keys")

# Old UserConfirmedValues and KeyDictionary classes removed
# Now using database/db_manager.py for all data storage

# ============================================================================
# HISTORY MANAGEMENT - Unlimited Undo/Redo
# ============================================================================

class HistoryEntry:
    """Single history entry representing a card state"""
    def __init__(self, blocks: Dict, description: str, metadata: Dict = None):
        self.blocks = copy.deepcopy(blocks)  # Deep copy to prevent mutation
        self.timestamp = datetime.now()
        self.description = description
        self.metadata = metadata or {}

    def __repr__(self):
        return f"<HistoryEntry: {self.description} at {self.timestamp.strftime('%H:%M:%S')}>"

class HistoryManager:
    """Manages unlimited undo/redo history for card edits"""

    def __init__(self, max_history: int = 1000):
        self.max_history = max_history
        self.history: List[HistoryEntry] = []
        self.current_index: int = -1  # Points to current state in history

    def push(self, blocks: Dict, description: str, metadata: Dict = None):
        """
        Add a new state to history

        Args:
            blocks: Current block data
            description: Human-readable description of this state
            metadata: Additional data (operator, balance, etc.)
        """
        # If we're in the middle of history (after undo), discard future states
        if self.current_index < len(self.history) - 1:
            self.history = self.history[:self.current_index + 1]

        # Create new entry
        entry = HistoryEntry(blocks, description, metadata)
        self.history.append(entry)

        # Enforce max history limit
        if len(self.history) > self.max_history:
            self.history.pop(0)  # Remove oldest
        else:
            self.current_index += 1

    def can_undo(self) -> bool:
        """Check if undo is available"""
        return self.current_index > 0

    def can_redo(self) -> bool:
        """Check if redo is available"""
        return self.current_index < len(self.history) - 1

    def undo(self) -> Optional[HistoryEntry]:
        """
        Undo to previous state

        Returns:
            Previous HistoryEntry or None if can't undo
        """
        if not self.can_undo():
            return None

        self.current_index -= 1
        return self.history[self.current_index]

    def redo(self) -> Optional[HistoryEntry]:
        """
        Redo to next state

        Returns:
            Next HistoryEntry or None if can't redo
        """
        if not self.can_redo():
            return None

        self.current_index += 1
        return self.history[self.current_index]

    def current(self) -> Optional[HistoryEntry]:
        """Get current history entry"""
        if 0 <= self.current_index < len(self.history):
            return self.history[self.current_index]
        return None

    def get_history_list(self, max_entries: int = 50) -> List[str]:
        """
        Get list of history descriptions for UI display

        Args:
            max_entries: Maximum number of entries to return

        Returns:
            List of formatted history strings
        """
        result = []
        start = max(0, len(self.history) - max_entries)

        for i in range(start, len(self.history)):
            entry = self.history[i]
            prefix = "→ " if i == self.current_index else "  "
            timestamp = entry.timestamp.strftime('%H:%M:%S')
            result.append(f"{prefix}[{timestamp}] {entry.description}")

        return result

    def clear(self):
        """Clear all history"""
        self.history.clear()
        self.current_index = -1

    def get_stats(self) -> Dict[str, Any]:
        """Get history statistics"""
        return {
            'total_entries': len(self.history),
            'current_index': self.current_index,
            'can_undo': self.can_undo(),
            'can_redo': self.can_redo(),
            'max_history': self.max_history,
            'oldest_entry': self.history[0].timestamp if self.history else None,
            'newest_entry': self.history[-1].timestamp if self.history else None
        }

    def export_history(self) -> List[Dict]:
        """Export history for debugging/analysis"""
        return [
            {
                'timestamp': entry.timestamp.isoformat(),
                'description': entry.description,
                'metadata': entry.metadata,
                'blocks_count': len(entry.blocks)
            }
            for entry in self.history
        ]

# ============================================================================
# CONFIG MANAGEMENT
# ============================================================================

class ConfigManager:
    def __init__(self):
        self.recents = []
        self.load_config()

    def load_config(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, 'r') as f:
                    data = json.load(f)
                    self.recents = data.get("recents", [])
            except: pass

    def add_recent(self, filepath):
        if filepath in self.recents: self.recents.remove(filepath)
        self.recents.insert(0, filepath)
        self.recents = self.recents[:5]
        self.save_config()

    def save_config(self):
        try:
            with open(CONFIG_FILE, 'w') as f:
                json.dump({"recents": self.recents}, f)
        except: pass

class LaundRApp:
    def __init__(self, root):
        self.root = root
        self.root.title("LaundR Perfected - Forensic Analyzer")
        self.root.geometry("1450x950")

        # Set window icon
        try:
            # Try different icon sizes (smaller files load faster)
            for icon_size in ["64", "128", "256"]:
                icon_path = os.path.join(os.path.dirname(__file__), "assets", f"LaundR_app_{icon_size}.png")
                if os.path.exists(icon_path):
                    icon = tk.PhotoImage(file=icon_path)
                    self.root.iconphoto(True, icon)
                    break
        except Exception as e:
            # Icon loading is optional, don't crash if it fails
            pass

        self.config = ConfigManager()
        self.db = LaundRDatabase()  # Database-driven intelligence
        self.history = HistoryManager(max_history=1000)  # Unlimited undo/redo (up to 1000 states)
        self.blocks = {}
        self.headers = []
        self.filename = None
        self.shadow_filename = None  # Shadow file (.laundr extension)
        self.current_block = None
        self.card_type = "1K"

        # INTELLIGENCE VARS (now database-driven)
        self.detected_operator_id = 3  # 3 = Unknown
        self.detected_operator_name = "Unknown"
        self.detected_provider = "Unknown"  # Backward compatibility
        self.detected_confidence = 0
        self.detected_balance_block = None  # Will be determined by database query

        # Detect and apply system theme
        self.dark_mode = self.detect_dark_mode()
        self.apply_theme()

        self.tag_unknown = "unknown"
        self.tag_money = "money"
        self.tag_key = "key"

        # --- MENU ---
        menubar = Menu(root)
        file_menu = Menu(menubar, tearoff=0)
        file_menu.add_command(label="Open .nfc File", command=self.browse_file)
        self.recent_menu = Menu(file_menu, tearoff=0)
        file_menu.add_cascade(label="Open Recent", menu=self.recent_menu)
        self.update_recent_menu()
        file_menu.add_separator()
        file_menu.add_command(label="Clone Card...", command=self.clone_card)
        file_menu.add_command(label="Save As...", command=self.save_file)
        file_menu.add_command(label="Save to Shadow", command=self.save_shadow)
        file_menu.add_command(label="Exit", command=root.quit)
        menubar.add_cascade(label="File", menu=file_menu)

        # Edit menu with undo/redo
        edit_menu = Menu(menubar, tearoff=0)
        edit_menu.add_command(label="Undo", command=self.undo, accelerator="Ctrl+Z")
        edit_menu.add_command(label="Redo", command=self.redo, accelerator="Ctrl+Y")
        menubar.add_cascade(label="Edit", menu=edit_menu)

        # Bind keyboard shortcuts
        root.bind('<Control-z>', lambda e: self.undo())
        root.bind('<Control-y>', lambda e: self.redo())

        tools_menu = Menu(menubar, tearoff=0)
        tools_menu.add_command(label="Edit UID...", command=self.edit_uid)
        tools_menu.add_command(label="Generate Random Card...", command=self.generate_random_card)
        tools_menu.add_separator()
        tools_menu.add_command(label="Force Re-Scan", command=self.run_full_scan)
        tools_menu.add_command(label="Fix Access Bits (Factory Default)", command=self.fix_access_bits)
        tools_menu.add_separator()
        tools_menu.add_command(label="Export to JSON", command=self.export_json)
        tools_menu.add_command(label="Export to CSV", command=self.export_csv)
        tools_menu.add_command(label="Generate Report", command=self.generate_report)
        menubar.add_cascade(label="Tools", menu=tools_menu)
        root.config(menu=menubar)

        # --- LAYOUT ---
        self.paned = tk.PanedWindow(root, orient=tk.HORIZONTAL, sashrelief=tk.RAISED, sashwidth=6)
        self.paned.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)

        # === LEFT PANEL ===
        left_frame = ttk.Frame(self.paned)
        self.paned.add(left_frame, width=480)

        lbl_map = tk.Label(left_frame, text="Memory Map (Red = Missing Data)", font=("Segoe UI", 11, "bold"), bg=self.colors['frame_bg'], fg=self.colors['fg'], anchor="w", padx=10, pady=5)
        lbl_map.pack(fill="x")

        # Search Frame
        search_frame = tk.Frame(left_frame, bg=self.colors['frame_bg'])
        search_frame.pack(fill="x", padx=5, pady=5)
        tk.Label(search_frame, text="Filter:", bg=self.colors['frame_bg'], fg=self.colors['fg']).pack(side="left")
        self.search_var = tk.StringVar()
        self.search_var.trace('w', lambda *args: self.filter_blocks())
        search_entry = tk.Entry(search_frame, textvariable=self.search_var, bg=self.colors['entry_bg'], fg=self.colors['entry_fg'])
        search_entry.pack(side="left", fill="x", expand=True, padx=5)
        tk.Button(search_frame, text="Clear", command=lambda: self.search_var.set(""), bg=self.colors['button_bg'], fg=self.colors['button_fg']).pack(side="left")

        tree_scroll = ttk.Scrollbar(left_frame)
        tree_scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.tree = ttk.Treeview(left_frame, columns=("Block", "Hex", "Meta"), show="headings", yscrollcommand=tree_scroll.set)

        self.tree.heading("Block", text="ID"); self.tree.column("Block", width=40, anchor="center")
        self.tree.heading("Hex", text="Raw Data"); self.tree.column("Hex", width=120, anchor="w")
        self.tree.heading("Meta", text="Analysis"); self.tree.column("Meta", width=250, anchor="w")

        # Configure Colors (using theme colors)
        self.tree.tag_configure(self.tag_unknown, background=self.colors['unknown_bg'], foreground=self.colors['unknown_fg'])
        self.tree.tag_configure(self.tag_money, background=self.colors['money_bg'], foreground=self.colors['money_fg'])
        self.tree.tag_configure(self.tag_key, background=self.colors['key_bg'], foreground=self.colors['key_fg'])

        self.tree.pack(fill=tk.BOTH, expand=True)
        tree_scroll.config(command=self.tree.yview)
        self.tree.bind("<<TreeviewSelect>>", self.on_block_select)

        # === RIGHT PANEL ===
        right_frame = ttk.Frame(self.paned)
        self.paned.add(right_frame)

        # 1. INTELLIGENCE DASHBOARD
        self.info_frame = tk.LabelFrame(right_frame, text="Card Profile", font=("Segoe UI", 10, "bold"), padx=15, pady=10, bg=self.colors['label_bg'])
        self.info_frame.pack(fill="x", pady=(0, 10))

        tk.Label(self.info_frame, text="Provider:", bg=self.colors['label_bg'], fg=self.colors['label_fg']).grid(row=0, column=0, sticky="w")
        self.lbl_provider = tk.Label(self.info_frame, text="--", font=("Segoe UI", 11, "bold"), fg="#00008B" if not self.dark_mode else "#64B5F6", bg=self.colors['label_bg'])
        self.lbl_provider.grid(row=0, column=1, sticky="w", padx=(0, 20))

        tk.Label(self.info_frame, text="Active Block:", bg=self.colors['label_bg'], fg=self.colors['label_fg']).grid(row=0, column=2, sticky="w")
        self.lbl_active_block = tk.Label(self.info_frame, text="--", font=("Consolas", 10), bg=self.colors['label_bg'], fg=self.colors['fg'])
        self.lbl_active_block.grid(row=0, column=3, sticky="w")

        tk.Label(self.info_frame, text="Balance:", bg=self.colors['label_bg'], fg=self.colors['label_fg']).grid(row=1, column=0, sticky="w")

        # Make balance editable
        balance_edit_frame = tk.Frame(self.info_frame, bg=self.colors['label_bg'])
        balance_edit_frame.grid(row=1, column=1, sticky="w", padx=(0, 20))

        tk.Label(balance_edit_frame, text="$", font=("Segoe UI", 16, "bold"), fg="#2E7D32" if not self.dark_mode else "#81C784", bg=self.colors['label_bg']).pack(side="left")
        self.ent_balance = tk.Entry(balance_edit_frame, font=("Segoe UI", 14, "bold"), fg="#2E7D32" if not self.dark_mode else "#81C784", bg=self.colors['entry_bg'],
                                     width=8, bd=1, relief="solid")
        self.ent_balance.pack(side="left")
        self.ent_balance.bind("<Return>", self.update_balance)
        tk.Button(balance_edit_frame, text="✓", command=self.update_balance, bg="#4CAF50" if not self.dark_mode else "#388E3C", fg="white",
                  font=("Segoe UI", 10, "bold"), padx=5, cursor="hand2").pack(side="left", padx=3)

        # Quick adjust buttons
        tk.Button(balance_edit_frame, text="+$5", command=lambda: self.quick_adjust_balance(5.00),
                  bg="#2196F3" if not self.dark_mode else "#1976D2", fg="white",
                  font=("Segoe UI", 9, "bold"), padx=5, cursor="hand2").pack(side="left", padx=2)
        tk.Button(balance_edit_frame, text="-$5", command=lambda: self.quick_adjust_balance(-5.00),
                  bg="#FF9800" if not self.dark_mode else "#F57C00", fg="white",
                  font=("Segoe UI", 9, "bold"), padx=5, cursor="hand2").pack(side="left", padx=2)

        tk.Label(self.info_frame, text="Counter:", bg=self.colors['label_bg'], fg=self.colors['label_fg']).grid(row=2, column=0, sticky="w")
        self.lbl_counter = tk.Label(self.info_frame, text="--", font=("Consolas", 10), bg=self.colors['label_bg'], fg=self.colors['fg'])
        self.lbl_counter.grid(row=2, column=1, sticky="w")

        tk.Label(self.info_frame, text="Last Top-Up:", bg=self.colors['label_bg'], fg=self.colors['label_fg']).grid(row=1, column=2, sticky="w")
        self.lbl_receipt = tk.Label(self.info_frame, text="--", font=("Consolas", 10), bg=self.colors['label_bg'], fg=self.colors['fg'])
        self.lbl_receipt.grid(row=1, column=3, sticky="w")

        # Last change display
        tk.Label(self.info_frame, text="Last Change:", bg=self.colors['label_bg'], fg=self.colors['label_fg']).grid(row=2, column=2, sticky="w")
        self.lbl_last_change = tk.Label(self.info_frame, text="--", font=("Consolas", 10, "bold"), fg="#1976D2" if not self.dark_mode else "#64B5F6", bg=self.colors['label_bg'])
        self.lbl_last_change.grid(row=2, column=3, sticky="w")

        # Usages Left (from Block 9)
        tk.Label(self.info_frame, text="Usages Left:", bg=self.colors['label_bg'], fg=self.colors['label_fg']).grid(row=3, column=0, sticky="w")
        self.lbl_usages = tk.Label(self.info_frame, text="--", font=("Consolas", 10), bg=self.colors['label_bg'], fg=self.colors['fg'])
        self.lbl_usages.grid(row=3, column=1, sticky="w")

        # Mode Selection (radio buttons)
        tk.Label(self.info_frame, text="Mode:", bg=self.colors['label_bg'], fg=self.colors['label_fg'], font=("Segoe UI", 9, "bold")).grid(row=4, column=0, sticky="w", pady=(10,0))

        self.mode_var = tk.StringVar(value="normal")

        # Normal Mode - No special processing
        self.radio_normal = tk.Radiobutton(
            self.info_frame,
            text="Normal (No tracking)",
            variable=self.mode_var,
            value="normal",
            bg=self.colors['label_bg'],
            fg=self.colors['fg'],
            font=("Segoe UI", 9),
            activebackground=self.colors['label_bg'],
            selectcolor=self.colors['label_bg']
        )
        self.radio_normal.grid(row=5, column=0, columnspan=2, sticky="w", padx=(20,0))

        # Legit Mode - Simulates real top-up (transaction tracking + refill counter)
        self.radio_legit = tk.Radiobutton(
            self.info_frame,
            text="🎯 Legit Mode (Real top-up simulation)",
            variable=self.mode_var,
            value="legit",
            bg=self.colors['label_bg'],
            fg="#2E7D32" if not self.dark_mode else "#81C784",
            font=("Segoe UI", 9, "bold"),
            activebackground=self.colors['label_bg'],
            selectcolor=self.colors['label_bg']
        )
        self.radio_legit.grid(row=6, column=0, columnspan=4, sticky="w", padx=(20,0))

        # Hack Mode - For testing/research only
        self.radio_hack = tk.Radiobutton(
            self.info_frame,
            text="⚠️ Hack Mode (Testing only - bypasses validation)",
            variable=self.mode_var,
            value="hack",
            bg=self.colors['label_bg'],
            fg="#D32F2F" if not self.dark_mode else "#EF5350",
            font=("Segoe UI", 9, "bold"),
            activebackground=self.colors['label_bg'],
            selectcolor=self.colors['label_bg']
        )
        self.radio_hack.grid(row=7, column=0, columnspan=4, sticky="w", padx=(20,0))

        # Hack mode warning label
        self.lbl_hack_warning = tk.Label(
            self.info_frame,
            text="",
            bg=self.colors['label_bg'],
            fg="#D32F2F" if not self.dark_mode else "#EF5350",
            font=("Segoe UI", 8, "italic"),
            wraplength=400
        )
        self.lbl_hack_warning.grid(row=8, column=0, columnspan=4, sticky="w", padx=(40,0))

        # Update warning when mode changes
        def update_mode_warning(*args):
            if self.mode_var.get() == "hack":
                self.lbl_hack_warning.config(text="⚠️ For security research only. Does not update refill tracking.")
            else:
                self.lbl_hack_warning.config(text="")

        self.mode_var.trace('w', update_mode_warning)

        # 2. RAW INSPECTOR & DECODER
        self.editor_frame = tk.LabelFrame(right_frame, text="Block Inspector", font=("Segoe UI", 10, "bold"), padx=15, pady=10, bg=self.colors['label_bg'])
        self.editor_frame.pack(fill="both", expand=True)

        self.ent_hex = tk.Entry(self.editor_frame, font=("Consolas", 16), bd=1, relief="solid", bg=self.colors['entry_bg'], fg=self.colors['entry_fg'])
        self.ent_hex.pack(fill="x", pady=(0, 5), ipady=5)
        self.ent_hex.bind("<KeyRelease>", self.on_hex_edit)

        self.ent_ascii = tk.Entry(self.editor_frame, font=("Consolas", 12), state="readonly", bd=0, bg=self.colors['frame_bg'], fg=self.colors['fg'])
        self.ent_ascii.pack(fill="x", pady=(0, 10))

        tk.Label(self.editor_frame, text="DECODING ANALYSIS:", font=("Segoe UI", 8, "bold"), fg=self.colors['label_fg'], bg=self.colors['label_bg']).pack(anchor="w")
        self.decoder_tree = ttk.Treeview(self.editor_frame, columns=("Type", "Value"), show="headings", height=10)
        self.decoder_tree.heading("Type", text="Method")
        self.decoder_tree.heading("Value", text="Result")
        self.decoder_tree.column("Type", width=200, anchor="w")
        self.decoder_tree.column("Value", width=400, anchor="w")
        self.decoder_tree.pack(fill="both", expand=True)

        # Configure tags for confirmed values (green highlight) - using theme colors
        self.decoder_tree.tag_configure("confirmed", background=self.colors['confirmed_bg'], foreground=self.colors['confirmed_fg'])

        # Double-click to mark as confirmed correct value
        self.decoder_tree.bind("<Double-Button-1>", self.on_decoder_double_click)

        # Actions
        btn_frame = tk.Frame(right_frame, pady=5, bg=self.colors['bg'])
        btn_frame.pack(fill="x")
        tk.Button(btn_frame, text="COMMIT RAW CHANGES", command=self.commit_raw_changes, bg=self.colors['button_bg'], fg=self.colors['button_fg'], font=("Segoe UI", 10), padx=15).pack(side="left")
        tk.Button(btn_frame, text="SAVE FILE", command=self.save_file, bg="#4CAF50" if not self.dark_mode else "#388E3C", fg="white", font=("Segoe UI", 10, "bold"), padx=15).pack(side="right")

    # --- CORE LOGIC ---
    def load_file(self, filepath):
        """Load and parse an NFC file with improved error handling"""
        try:
            # Validate file exists
            if not os.path.exists(filepath):
                messagebox.showerror("Error", f"File not found: {filepath}")
                return

            # Validate file extension
            if not filepath.lower().endswith('.nfc'):
                response = messagebox.askyesno("Warning",
                    f"File doesn't have .nfc extension. Continue anyway?")
                if not response:
                    return

            self.filename = filepath
            self.config.add_recent(filepath)
            self.update_recent_menu()
            self.root.title(f"LaundR Perfected - {os.path.basename(filepath)}")
            self.blocks = {}
            self.headers = []

            # Read and parse file
            with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
                lines = f.readlines()

            block_count = 0
            for line_num, line in enumerate(lines, 1):
                line = line.strip()
                if not line:
                    continue

                # Parse headers
                if (line.startswith("Filetype") or line.startswith("Version") or
                    line.startswith("Device type") or line.startswith("UID") or
                    line.startswith("ATQA") or line.startswith("SAK") or
                    line.startswith("Mifare") or line.startswith("Data format") or
                    (line.startswith("#"))):
                    self.headers.append(line)
                    continue

                # Parse blocks
                if line.startswith("Block"):
                    match = re.match(r'Block\s+(\d+)\s*:\s*(.+)', line)
                    if match:
                        block_id = int(match.group(1))
                        block_data = match.group(2).strip()

                        # Validate hex data format
                        if not self._validate_block_data(block_data):
                            print(f"Warning: Invalid hex data in block {block_id} (line {line_num})")

                        self.blocks[block_id] = block_data
                        block_count += 1

            # Validate we got some data
            if not self.blocks:
                messagebox.showerror("Error", "No valid block data found in file")
                return

            # Determine card size
            max_blk = max(self.blocks.keys()) if self.blocks else 0
            self.card_type = "4K" if max_blk > 63 else "1K"

            # Validate block count
            expected_blocks = 256 if self.card_type == "4K" else 64
            if block_count < expected_blocks * 0.5:  # Less than 50% of expected blocks
                messagebox.showwarning("Warning",
                    f"File contains only {block_count} blocks. Expected around {expected_blocks} for {self.card_type} card.")

            self.run_full_scan()

        except UnicodeDecodeError:
            messagebox.showerror("Error", "File encoding error. File may be corrupted.")
        except MemoryError:
            messagebox.showerror("Error", "File too large to load.")
        except Exception as e:
            messagebox.showerror("Error", f"Load Failed: {type(e).__name__}: {str(e)}")

    def _validate_block_data(self, data):
        """Validate that block data is properly formatted hex"""
        # Remove spaces and check if it's valid hex
        clean = data.replace(" ", "").replace("?", "0")
        if len(clean) != 32:  # 16 bytes = 32 hex chars
            return False
        try:
            int(clean, 16)
            return True
        except ValueError:
            return False

    def detect_dark_mode(self):
        """Detect if system is using dark mode"""
        try:
            # Method 1: Check GTK theme (Linux)
            if sys.platform == "linux":
                try:
                    result = os.popen("gsettings get org.gnome.desktop.interface gtk-theme").read().strip()
                    if 'dark' in result.lower():
                        return True

                    # Also check color scheme preference
                    result = os.popen("gsettings get org.gnome.desktop.interface color-scheme").read().strip()
                    if 'dark' in result.lower():
                        return True
                except:
                    pass

            # Method 2: Check macOS dark mode
            elif sys.platform == "darwin":
                try:
                    result = os.popen("defaults read -g AppleInterfaceStyle").read().strip()
                    return result == "Dark"
                except:
                    pass

            # Method 3: Check Windows dark mode
            elif sys.platform == "win32":
                try:
                    import winreg
                    registry = winreg.ConnectRegistry(None, winreg.HKEY_CURRENT_USER)
                    key = winreg.OpenKey(registry, r'Software\Microsoft\Windows\CurrentVersion\Themes\Personalize')
                    value, _ = winreg.QueryValueEx(key, 'AppsUseLightTheme')
                    return value == 0  # 0 = dark mode
                except:
                    pass
        except:
            pass

        # Default to light mode if detection fails
        return False

    def apply_theme(self):
        """Apply theme colors based on detected mode"""
        if self.dark_mode:
            # Dark mode colors
            self.colors = {
                'bg': '#2b2b2b',
                'fg': '#e0e0e0',
                'select_bg': '#404040',
                'select_fg': '#ffffff',
                'entry_bg': '#3c3c3c',
                'entry_fg': '#e0e0e0',
                'button_bg': '#404040',
                'button_fg': '#e0e0e0',
                'label_bg': '#2b2b2b',
                'label_fg': '#e0e0e0',
                'frame_bg': '#2b2b2b',
                'tree_bg': '#2b2b2b',
                'tree_fg': '#e0e0e0',
                'tree_select_bg': '#404040',
                'tree_select_fg': '#ffffff',
                'unknown_bg': '#5c3030',  # Dark red
                'unknown_fg': '#ffcccc',
                'money_bg': '#2d4f2d',    # Dark green
                'money_fg': '#ccffcc',
                'key_bg': '#3c3c3c',      # Dark gray
                'key_fg': '#999999',
                'confirmed_bg': '#2d4f2d',  # Dark green
                'confirmed_fg': '#81C784',
            }
        else:
            # Light mode colors (original)
            self.colors = {
                'bg': 'white',
                'fg': 'black',
                'select_bg': '#e0e0e0',
                'select_fg': 'black',
                'entry_bg': 'white',
                'entry_fg': 'black',
                'button_bg': '#f0f0f0',
                'button_fg': 'black',
                'label_bg': 'white',
                'label_fg': '#666666',
                'frame_bg': '#f0f0f0',
                'tree_bg': 'white',
                'tree_fg': 'black',
                'tree_select_bg': '#e0e0e0',
                'tree_select_fg': 'black',
                'unknown_bg': '#ffcccc',  # Light red
                'unknown_fg': 'black',
                'money_bg': '#ccffcc',    # Light green
                'money_fg': 'black',
                'key_bg': '#e6e6e6',      # Light gray
                'key_fg': '#555555',
                'confirmed_bg': '#C8E6C9',  # Light green
                'confirmed_fg': '#1B5E20',
            }

        # Apply ttk styles
        style = ttk.Style()
        style.theme_use('clam')

        # Configure Treeview
        style.configure("Treeview",
            background=self.colors['tree_bg'],
            foreground=self.colors['tree_fg'],
            fieldbackground=self.colors['tree_bg'],
            rowheight=28,
            font=('Segoe UI', 10))

        style.configure("Treeview.Heading",
            background=self.colors['button_bg'],
            foreground=self.colors['fg'],
            font=('Segoe UI', 10, 'bold'))

        style.map('Treeview',
            background=[('selected', self.colors['tree_select_bg'])],
            foreground=[('selected', self.colors['tree_select_fg'])])

        # Configure Frame
        style.configure("TFrame", background=self.colors['frame_bg'])
        style.configure("TLabelframe", background=self.colors['frame_bg'])
        style.configure("TLabelframe.Label", background=self.colors['frame_bg'], foreground=self.colors['fg'])

        # Configure root window
        self.root.configure(bg=self.colors['bg'])

    def update_recent_menu(self):
        self.recent_menu.delete(0, "end")
        for f in self.config.recents: self.recent_menu.add_command(label=f, command=lambda p=f: self.load_file(p))

    def browse_file(self):
        path = filedialog.askopenfilename(filetypes=[("NFC Files", "*.nfc;*.laundr"), ("All Files", "*.*")])
        if path:
            # Check if shadow file exists and ask user
            if path.endswith('.nfc'):
                shadow_path = os.path.splitext(path)[0] + '.laundr'
                if os.path.exists(shadow_path):
                    use_shadow = messagebox.askyesno(
                        "Shadow File Found",
                        f"Found shadow file:\n{os.path.basename(shadow_path)}\n\n" +
                        "Load shadow file instead of original?\n\n" +
                        "Shadow files contain your modifications."
                    )
                    if use_shadow:
                        path = shadow_path

            self.load_file(path)

    def is_sector_trailer(self, b_id):
        if self.card_type == "1K": return (b_id + 1) % 4 == 0
        else:
            if b_id < 128: return (b_id + 1) % 4 == 0
            else: return (b_id + 1 - 128) % 16 == 0

    def get_bytes_at(self, block_id, start, length):
        if block_id not in self.blocks: return None
        raw_str = self.blocks[block_id]
        parts = raw_str.split()
        if len(parts) != 16:
            clean = raw_str.replace(" ", "")
            if len(clean) % 2 == 0: parts = [clean[i:i+2] for i in range(0, len(clean), 2)]
            else: return None
        if len(parts) < start + length: return None
        target_slice = parts[start : start + length]
        if any("?" in p for p in target_slice): return None
        try: return bytes.fromhex("".join(target_slice))
        except: return None

    # --- SCANNING ENGINE (FLEX Detector for ANY Operator) ---
    def run_full_scan(self):
        """Universal scanner using database-driven operator detection"""
        self.detected_operator_id = 3  # Unknown
        self.detected_operator_name = "Unknown"
        self.detected_confidence = 0
        self.detected_balance_block = None
        balance_candidates = []

        # STEP 1: DATABASE-DRIVEN PROVIDER DETECTION
        # Convert string blocks to bytes for database detection
        blocks_bytes = {}
        for block_id, block_str in self.blocks.items():
            try:
                hex_clean = block_str.replace(' ', '').replace('?', '0')
                blocks_bytes[block_id] = bytes.fromhex(hex_clean)
            except:
                pass

        self.detected_operator_id, self.detected_operator_name, self.detected_confidence = \
            self.db.detect_operator(blocks_bytes)

        # Store for backward compatibility (will be removed later)
        self.detected_provider = self.detected_operator_name

        # STEP 2: UNIVERSAL BALANCE SCANNER (scans ALL blocks dynamically)
        # Scan ALL blocks for value block patterns
        for block_id in range(64):  # Scan all Mifare 1K blocks
            if block_id not in self.blocks:
                continue

            # Skip sector trailers
            if self.is_sector_trailer(block_id):
                continue

            block_data = self.get_bytes_at(block_id, 0, 16)
            if not block_data or len(block_data) < 8:
                continue

            # Check for 16-bit value block (CSC style: Val(2) Cnt(2) ~Val(2) ~Cnt(2))
            try:
                val_16 = struct.unpack('<H', block_data[0:2])[0]
                cnt_16 = struct.unpack('<H', block_data[2:4])[0]
                val_16_inv = struct.unpack('<H', block_data[4:6])[0]
                cnt_16_inv = struct.unpack('<H', block_data[6:8])[0]

                val_valid = (val_16 ^ val_16_inv) == 0xFFFF
                cnt_valid = (cnt_16 ^ cnt_16_inv) == 0xFFFF

                if (val_valid or cnt_valid) and 0 < val_16 < 50000:
                    confidence = "High" if (val_valid and cnt_valid) else "Medium"
                    balance_candidates.append((block_id, val_16, f"16-bit Value ({confidence})"))
            except:
                pass

            # Check for 32-bit value block (U-Best style: Val(4) ~Val(4) Val(4) Addr(4))
            try:
                val_32 = struct.unpack('<I', block_data[0:4])[0]
                val_32_inv = struct.unpack('<I', block_data[4:8])[0]

                if (val_32 ^ val_32_inv) == 0xFFFFFFFF:
                    # Convert to cents
                    if val_32 < 1000000:  # Reasonable limit for money
                        balance_candidates.append((block_id, val_32, "32-bit Value Block"))
                    # Also check as 16-bit usage counter
                    if val_32 > 50000:  # Too large for direct cents, might be usage counter
                        usage_16 = struct.unpack('<H', block_data[0:2])[0]
                        if 1000 < usage_16 < 65535:
                            balance_candidates.append((block_id, usage_16, f"Usage Counter ({usage_16:,})"))
            except:
                pass

        # STEP 3: DATABASE-DRIVEN BALANCE BLOCK SELECTION
        # Get balance blocks from database for this operator
        db_balance_blocks = self.db.get_balance_blocks(self.detected_operator_id)

        if balance_candidates and db_balance_blocks:
            # Build priority map from database (lower index = higher priority)
            block_priority = {block: idx for idx, block in enumerate(db_balance_blocks)}

            # Sort: non-zero first, then by database priority, then by confidence
            balance_candidates.sort(key=lambda x: (
                x[1] == 0,  # Non-zero first
                block_priority.get(x[0], 99),  # Database priority
                "High" not in x[2]  # High confidence first
            ))

            self.detected_balance_block = balance_candidates[0][0]
        elif balance_candidates:
            # No database info, use first non-zero candidate
            balance_candidates.sort(key=lambda x: (x[1] == 0, "High" not in x[2]))
            self.detected_balance_block = balance_candidates[0][0]
        elif db_balance_blocks:
            # No candidates found, use first database suggestion
            self.detected_balance_block = db_balance_blocks[0]
        else:
            # Complete fallback - Block 4 is most common
            self.detected_balance_block = 4

        # Save initial state to history
        self.history.push(
            self.blocks,
            f"Loaded {self.filename or 'card'}",
            {"operator": self.detected_operator_name}
        )

        self.refresh_ui()

    def filter_blocks(self):
        """Filter the tree view based on search criteria"""
        search_text = self.search_var.get().lower()
        if not search_text:
            self.refresh_ui()
            return

        # Clear tree
        for i in self.tree.get_children():
            self.tree.delete(i)

        # Rebuild with filter
        limit = 256 if self.card_type == "4K" else 64
        for i in range(limit):
            if i not in self.blocks:
                continue

            raw = self.blocks[i]
            # Check if search matches block number, hex data, or would match meta
            if (search_text in str(i) or
                search_text in raw.lower() or
                (i == self.detected_balance_block and "balance" in search_text) or
                (self.is_sector_trailer(i) and "trailer" in search_text)):

                disp = (raw[:15] + '...') if len(raw) > 15 else raw
                meta = "Data"
                tag = ""

                if "??" in raw:
                    tag = self.tag_unknown
                    meta = "Partial / Unknown"
                elif i == self.detected_balance_block:
                    tag = self.tag_money
                    meta = ">> ACTIVE BALANCE <<"
                elif self.is_sector_trailer(i):
                    tag = self.tag_key
                    meta = "Sector Trailer"
                elif i == 0:
                    meta = "UID / Manufacturer"
                elif i == 2:
                    meta = "Receipt Record"

                self.tree.insert("", "end", iid=i, values=(f"{i:02d}", disp, meta), tags=(tag,))

    def update_balance(self, event=None):
        """Update the balance value in blocks with proper inverse encoding"""
        try:
            # Get old balance for transaction tracking
            old_cents = 0
            if self.detected_balance_block and self.detected_balance_block in self.blocks:
                old_bal_bytes = self.get_bytes_at(self.detected_balance_block, 0, 2)
                if old_bal_bytes:
                    old_cents = struct.unpack('<H', old_bal_bytes)[0]

            # Parse new dollar amount
            balance_str = self.ent_balance.get().replace("$", "").strip()
            balance_dollars = float(balance_str)
            cents = int(balance_dollars * 100)

            # Validate range
            if cents < 0 or cents > 65535:
                messagebox.showerror("Error", "Balance must be between $0.00 and $655.35")
                return

            # Calculate transaction amount (difference)
            transaction_cents = cents - old_cents
            transaction_dollars = transaction_cents / 100

            # Get current counter value
            current_counter = 0
            if self.detected_balance_block and self.detected_balance_block in self.blocks:
                cnt_bytes = self.get_bytes_at(self.detected_balance_block, 2, 2)
                if cnt_bytes:
                    current_counter = struct.unpack('<H', cnt_bytes)[0]

            # Determine mode behavior
            mode = self.mode_var.get()

            # Legit Mode: Always increment counter for transactions
            # Hack Mode: Update balance only, no tracking
            # Normal Mode: No tracking
            new_counter = current_counter
            if mode == "legit" and transaction_cents != 0:
                new_counter = current_counter + 1

            # Build the laundry card value block format
            # Format: [Val 2b] [Cnt 2b] [~Val 2b] [~Cnt 2b] [Val 2b] [Cnt 2b] [Addr 1b] [~Addr 1b] [Addr 1b] [~Addr 1b]
            val_bytes = struct.pack('<H', cents)
            cnt_bytes = struct.pack('<H', new_counter)
            val_inv_bytes = struct.pack('<H', cents ^ 0xFFFF)
            cnt_inv_bytes = struct.pack('<H', new_counter ^ 0xFFFF)

            # Address bytes (usually 04 FB for laundry cards)
            addr = 0x04
            addr_inv = addr ^ 0xFF

            # Build complete block
            new_block = (val_bytes + cnt_bytes + val_inv_bytes + cnt_inv_bytes +
                        val_bytes + cnt_bytes + bytes([addr, addr_inv, addr, addr_inv]))

            # Convert to hex string with spaces
            hex_str = " ".join([f"{b:02X}" for b in new_block])

            # Update primary balance block
            if self.detected_balance_block:
                self.blocks[self.detected_balance_block] = hex_str

                # Check if there's a backup block (usually Block 8 mirrors Block 4)
                backup_block = None
                if self.detected_balance_block == 4:
                    backup_block = 8
                elif self.detected_balance_block == 8:
                    backup_block = 4

                # Update backup block if it exists and has the same structure
                if backup_block and backup_block in self.blocks:
                    old_backup = self.get_bytes_at(backup_block, 0, 16)
                    if old_backup and len(old_backup) == 16:
                        # Check if it's a mirror by comparing structure
                        old_val = struct.unpack('<H', old_backup[0:2])[0]
                        old_val_inv = struct.unpack('<H', old_backup[4:6])[0]
                        if (old_val ^ old_val_inv) == 0xFFFF:
                            self.blocks[backup_block] = hex_str

                # Update Block 2 (transaction/receipt) based on mode
                # Legit Mode: Full transaction tracking with refill counter
                # Hack Mode: Update balance only, skip Block 2
                # Normal Mode: No tracking
                if mode == "legit" and 2 in self.blocks and transaction_cents != 0:
                    self.update_transaction_block(abs(transaction_cents), new_counter, legit_mode=True)
                elif mode == "hack":
                    # Hack mode: skip Block 2 updates entirely
                    pass

                # Save to history
                self.history.push(
                    self.blocks,
                    f"Updated balance to ${balance_dollars:.2f}",
                    {"operator": self.detected_operator_name, "balance": balance_dollars}
                )

            # Build success message based on mode
            success_msg = f"Updated to ${balance_dollars:.2f}"
            if transaction_cents != 0:
                change_type = "Added" if transaction_cents > 0 else "Deducted"
                success_msg += f"\n{change_type}: ${abs(transaction_dollars):.2f}"

                if mode == "legit":
                    success_msg += f"\n\nCounter: {current_counter} → {new_counter}"
                    success_msg += "\n\n🎯 LEGIT MODE:"
                    success_msg += "\n   ✓ Refill counter incremented"
                    success_msg += "\n   ✓ Refilled balance updated"
                    success_msg += "\n   ✓ Transaction recorded in Block 2"
                    success_msg += "\n   ✓ Simulates real top-up"
                elif mode == "hack":
                    success_msg += f"\n\n⚠️ HACK MODE:"
                    success_msg += "\n   • Balance updated ONLY"
                    success_msg += "\n   • Refill tracking UNCHANGED"
                    success_msg += "\n   • Block 2 NOT updated"
                    success_msg += "\n   • For testing purposes only!"
                else:  # normal mode
                    success_msg += f"\n\nNormal Mode: Balance updated, no tracking"

            messagebox.showinfo("Balance Updated", success_msg)
            self.refresh_ui()

            # Auto-save to shadow file
            if self.filename:
                self.save_shadow()

        except ValueError:
            messagebox.showerror("Error", "Invalid dollar amount. Use format: 12.34")
        except Exception as e:
            messagebox.showerror("Error", f"Update failed: {e}")

    def update_transaction_block(self, transaction_cents, new_counter, legit_mode=False):
        """
        Update Block 2 with transaction information

        Args:
            transaction_cents: Amount of transaction in cents
            new_counter: New counter value
            legit_mode: If True, simulates real top-up (updates refill times and refilled balance)
                       If False, only updates balance without touching refill tracking
        """
        try:
            if 2 not in self.blocks:
                return

            # Get current Block 2 data
            b2_data = self.get_bytes_at(2, 0, 16)
            if not b2_data or len(b2_data) != 16:
                return

            # Build new Block 2
            # Keep signature and existing data, update transaction amount and counter
            new_b2 = bytearray(b2_data)

            # Block 2 Structure (CSC ServiceWorks):
            # Offset 0-1:   0x0101 (CSC signature)
            # Offset 2-4:   Transaction ID (24-bit, increments with each use)
            # Offset 5:     Refill times (8-bit counter, increments with each top-up)
            # Offset 6-8:   Reserved/Unknown
            # Offset 9-10:  Refilled balance (16-bit LE, last top-up amount in cents)
            # Offset 11-14: Reserved/Unknown
            # Offset 15:    Checksum (XOR of bytes 0-14 must = 0)

            # Update transaction ID at bytes 2-4 (24-bit, increment it)
            if len(new_b2) >= 5:
                # Read as 3-byte value (little-endian)
                old_tx_id = new_b2[2] | (new_b2[3] << 8) | (new_b2[4] << 16)
                new_tx_id = (old_tx_id + 1) & 0xFFFFFF  # Keep it 24-bit
                new_b2[2] = new_tx_id & 0xFF
                new_b2[3] = (new_tx_id >> 8) & 0xFF
                new_b2[4] = (new_tx_id >> 16) & 0xFF

            # LEGIT MODE: Update refill tracking fields
            if legit_mode:
                # Increment refill times counter at byte 5
                if len(new_b2) >= 6:
                    current_refills = new_b2[5]
                    new_b2[5] = (current_refills + 1) & 0xFF  # Increment, wrap at 255

                # Update refilled balance at bytes 9-10 (TOTAL balance after top-up, not the amount added)
                # Example: If card had $1.50 and user added $10, this stores $11.50
                if len(new_b2) >= 11:
                    # Get current balance from balance block to store as "refilled balance"
                    current_balance_cents = 0
                    if self.detected_balance_block and self.detected_balance_block in self.blocks:
                        bal_bytes = self.get_bytes_at(self.detected_balance_block, 0, 2)
                        if bal_bytes:
                            current_balance_cents = struct.unpack('<H', bal_bytes)[0]
                    new_b2[9:11] = struct.pack('<H', current_balance_cents)
            else:
                # HACK MODE: Don't touch refill tracking
                # Bytes 5, 9-10 remain unchanged
                pass

            # CRITICAL: Recalculate Block 2 checksum for Flipper Zero CSC parser
            # The CSC parser requires XOR of all 16 bytes = 0
            # Calculate checksum: XOR of first 15 bytes
            checksum = 0
            for i in range(15):
                checksum ^= new_b2[i]
            new_b2[15] = checksum  # Set byte 15 to make total XOR = 0

            # Convert to hex string
            hex_str = " ".join([f"{b:02X}" for b in new_b2])
            self.blocks[2] = hex_str

        except Exception as e:
            print(f"Warning: Failed to update transaction block: {e}")

    def refresh_ui(self):
        # Header Info
        self.lbl_provider.config(text=self.detected_provider)
        self.lbl_active_block.config(text=f"Block {self.detected_balance_block}" if self.detected_balance_block else "--")

        # Parse Balance (Improved Support)
        if self.detected_balance_block and self.detected_balance_block in self.blocks:
            bal_bytes = self.get_bytes_at(self.detected_balance_block, 0, 2)
            if bal_bytes:
                cents = struct.unpack('<H', bal_bytes)[0]
                self.ent_balance.delete(0, "end")
                self.ent_balance.insert(0, f"{cents/100:.2f}")
            else:
                self.ent_balance.delete(0, "end")
                self.ent_balance.insert(0, "0.00")

            # Parse counter
            cnt_bytes = self.get_bytes_at(self.detected_balance_block, 2, 2)
            if cnt_bytes:
                counter = struct.unpack('<H', cnt_bytes)[0]
                self.lbl_counter.config(text=str(counter))
            else:
                self.lbl_counter.config(text="--")
        else:
            self.ent_balance.delete(0, "end")
            self.ent_balance.insert(0, "0.00")
            self.lbl_counter.config(text="--")

        # Parse Receipt/Last Transaction (operator-specific)
        # Query database for operator's last_transaction field
        last_txn_structures = [s for s in self.db.get_block_structures(self.detected_operator_id, 2)
                               if s['block_purpose'] == 'last_transaction']

        if last_txn_structures:
            # Use operator-specific transaction field
            txn_struct = last_txn_structures[0]
            offset = txn_struct['byte_offset'] if txn_struct['byte_offset'] else 9
            length = txn_struct['byte_length'] if txn_struct['byte_length'] else 2

            rec_bytes = self.get_bytes_at(2, offset, length)
            if rec_bytes:
                rcents = struct.unpack('<H', rec_bytes)[0]
                self.lbl_receipt.config(text=f"${rcents/100:.2f}")

                # Also show in "Last Change" field
                if rcents > 0:
                    # Try to determine if it was add or subtract by comparing to balance
                    if self.detected_balance_block and self.detected_balance_block in self.blocks:
                        bal_bytes = self.get_bytes_at(self.detected_balance_block, 0, 2)
                        if bal_bytes:
                            current_balance = struct.unpack('<H', bal_bytes)[0]
                            # If transaction is less than balance, it was likely an add
                            # This is a heuristic - we show it with + or as absolute
                            self.lbl_last_change.config(text=f"+${rcents/100:.2f}", fg="#2E7D32" if not self.dark_mode else "#81C784")
                    else:
                        self.lbl_last_change.config(text=f"${rcents/100:.2f}")
                else:
                    self.lbl_last_change.config(text="--")
            else:
                self.lbl_receipt.config(text="--")
                self.lbl_last_change.config(text="--")
        else:
            # Operator doesn't have defined last_transaction field
            self.lbl_receipt.config(text="--")
            self.lbl_last_change.config(text="--")

        # Parse Usages Left (Block 9, bytes 0-1)
        if 9 in self.blocks:
            usages_bytes = self.get_bytes_at(9, 0, 2)
            if usages_bytes:
                usages_left = struct.unpack('<H', usages_bytes)[0]
                self.lbl_usages.config(text=f"{usages_left:,}")
            else:
                self.lbl_usages.config(text="--")
        else:
            self.lbl_usages.config(text="--")

        # TREE MAP REBUILD
        for i in self.tree.get_children(): self.tree.delete(i)

        limit = 256 if self.card_type == "4K" else 64
        for i in range(limit):
            if i in self.blocks:
                raw = self.blocks[i]
                disp = (raw[:15] + '...') if len(raw) > 15 else raw

                meta = "Data"
                tag = ""

                # Tag Logic
                if "??" in raw:
                    tag = self.tag_unknown
                    meta = "Partial / Unknown"
                elif i == self.detected_balance_block:
                    tag = self.tag_money
                    meta = ">> ACTIVE BALANCE <<"
                elif (i == 4 or i == 8) and i != self.detected_balance_block:
                    # Block 4/8 mirror but not the active balance
                    tag = self.tag_money
                    meta = "Balance Mirror/Backup"
                elif self.is_sector_trailer(i):
                    tag = self.tag_key
                    meta = "Sector Trailer"
                elif i == 0:
                    meta = "UID / Manufacturer"
                elif i == 1:
                    meta = "System ID / Card Creation"
                elif i == 2:
                    meta = "Receipt / Transaction"
                elif i == 9:
                    meta = "Usages Left (16,958)"
                elif i == 10:
                    meta = "Network Identifier (NET)"
                elif i == 12:
                    meta = "Config / System Data"
                elif i == 13:
                    meta = "Card ID (AZ7602046)"

                self.tree.insert("", "end", iid=i, values=(f"{i:02d}", disp, meta), tags=(tag,))
            else:
                self.tree.insert("", "end", iid=i, values=(f"{i:02d}", "--", "Empty"))

    # --- DECODER ENGINE ---
    def on_block_select(self, event):
        selected = self.tree.selection()
        if not selected: return
        b_id = int(selected[0])
        self.current_block = b_id

        if b_id in self.blocks:
            raw = self.blocks[b_id]
            self.ent_hex.delete(0, "end")
            self.ent_hex.insert(0, raw)
            self.run_decoders(b_id, raw)
        else:
            self.ent_hex.delete(0, "end")
            for i in self.decoder_tree.get_children(): self.decoder_tree.delete(i)

    def on_hex_edit(self, event):
        if self.current_block is None: return
        self.run_decoders(self.current_block, self.ent_hex.get())

    def on_decoder_double_click(self, event):
        """Double-click handler to mark decoder values as confirmed correct"""
        selection = self.decoder_tree.selection()
        if not selection:
            return

        item = selection[0]
        values = self.decoder_tree.item(item, 'values')
        if not values or len(values) < 2:
            return

        field, value = values[0], values[1]

        # Check if already confirmed
        current_tags = self.decoder_tree.item(item, 'tags')
        if 'confirmed' in current_tags:
            # Unconfirm if double-clicked again
            self.decoder_tree.item(item, tags=())
            messagebox.showinfo("Unconfirmed", f"'{field}' is no longer marked as confirmed.")
            return

        # Mark as confirmed
        self.decoder_tree.item(item, tags=('confirmed',))

        # Store in database
        if self.current_block is not None:
            # Extract byte offset/length from field name if present (e.g., "Bytes 8-9")
            byte_offset = None
            byte_length = None
            offset_match = re.search(r'Bytes? (\d+)-(\d+)', field)
            if offset_match:
                byte_offset = int(offset_match.group(1))
                byte_length = int(offset_match.group(2)) - byte_offset + 1

            # Try to extract cents value if it's a money value
            value_cents = None
            cents_match = re.search(r'\((\d+) cents\)', value)
            if cents_match:
                value_cents = int(cents_match.group(1))

            # Get card UID for tracking
            card_uid = None
            if 0 in self.blocks:
                uid_bytes = self.get_bytes_at(0, 0, 4)
                if uid_bytes:
                    card_uid = uid_bytes.hex().upper()

            # Store in database
            self.db.add_confirmed_value(
                self.detected_operator_id,
                self.current_block,
                field,
                byte_offset if byte_offset is not None else 0,
                byte_length if byte_length is not None else 0,
                value_cents,
                value,
                card_uid
            )

            messagebox.showinfo(
                "Value Confirmed",
                f"Marked '{field}' = '{value}' as CORRECT for {self.detected_operator_name} cards.\n\n"
                f"Stored in database for future card detection.\n"
                f"Block: {self.current_block}"
            )

    def run_decoders(self, b_id, raw_str):
        for i in self.decoder_tree.get_children(): self.decoder_tree.delete(i)
        clean = raw_str.replace("??", "00").replace(" ", "")

        try: data = bytearray.fromhex(clean)
        except: return

        # ASCII
        ascii_show = "".join([chr(b) if 32 <= b <= 126 else '.' for b in data])
        self.ent_ascii.config(state="normal"); self.ent_ascii.delete(0, "end"); self.ent_ascii.insert(0, ascii_show); self.ent_ascii.config(state="readonly")

        # 1. LAUNDRY SPLIT VALUE (Val 2b, Cnt 2b)
        try:
            cents = struct.unpack('<H', data[0:2])[0]
            cnt = struct.unpack('<H', data[2:4])[0]
            cents_inv = struct.unpack('<H', data[4:6])[0]
            cnt_inv = struct.unpack('<H', data[6:8])[0]

            # Check for inverse validation
            val_valid = (cents ^ cents_inv) == 0xFFFF
            cnt_valid = (cnt ^ cnt_inv) == 0xFFFF

            if cents < 50000 and cnt < 65000:
                status = " ✓ VALID" if (val_valid and cnt_valid) else " ⚠ NO INVERSE"

                # Dynamic label based on block number
                if b_id == 4:
                    label = "💵 Current Balance"
                elif b_id == 8:
                    label = "💾 Backup Balance"
                elif b_id == 9:
                    label = "🎯 Usage Counter Value"
                else:
                    label = "Value Block Format"

                self.decoder_tree.insert("", "end", values=(label, f"${cents/100:.2f} (Count: {cnt}){status}"))

                # Check for backup/mirror blocks
                if b_id == 4 or b_id == 8:
                    mirror_id = 8 if b_id == 4 else 4
                    if mirror_id in self.blocks:
                        # Verify they match
                        try:
                            mirror_data = self.get_bytes_at(mirror_id, 0, 2)
                            if mirror_data:
                                mirror_cents = struct.unpack('<H', mirror_data)[0]
                                if mirror_cents == cents:
                                    self.decoder_tree.insert("", "end", values=("  → Mirror Check", f"Block {mirror_id} matches ✓"))
                                else:
                                    self.decoder_tree.insert("", "end", values=("  → Mirror Check", f"Block {mirror_id} MISMATCH! (${mirror_cents/100:.2f})"))
                        except:
                            pass
        except: pass

        # 2. STANDARD MIFARE VALUE BLOCK
        if len(data) == 16:
            try:
                v1 = struct.unpack('<I', data[0:4])[0]
                v1_inv = struct.unpack('<I', data[4:8])[0]
                v1_dup = struct.unpack('<I', data[8:12])[0]

                if (v1 ^ v1_inv) == 0xFFFFFFFF:
                    valid = " [VALID]" if v1 == v1_dup else " [MISMATCH]"
                    self.decoder_tree.insert("", "end", values=("Standard Value Block", f"Int: {v1}{valid}"))

                    # Try to interpret as cents
                    if v1 < 1000000:
                        self.decoder_tree.insert("", "end", values=("  → As Currency", f"${v1/100:.2f}"))
            except: pass

        # 3. TIMESTAMP DETECTION
        try:
            # Try 32-bit Unix timestamp
            timestamp = struct.unpack('<I', data[0:4])[0]
            if 946684800 < timestamp < 2147483647:  # Between 2000 and 2038
                dt = datetime.fromtimestamp(timestamp)
                self.decoder_tree.insert("", "end", values=("Unix Timestamp (LE)", dt.strftime('%Y-%m-%d %H:%M:%S')))

            # Try big-endian
            timestamp_be = struct.unpack('>I', data[0:4])[0]
            if 946684800 < timestamp_be < 2147483647:
                dt_be = datetime.fromtimestamp(timestamp_be)
                self.decoder_tree.insert("", "end", values=("Unix Timestamp (BE)", dt_be.strftime('%Y-%m-%d %H:%M:%S')))
        except: pass

        # 4. ACCESS BITS (If Trailer) - Enhanced with key dictionary
        if self.is_sector_trailer(b_id):
            key_a = data[0:6].hex().upper()
            acc = data[6:9].hex().upper()
            key_b = data[10:16].hex().upper()

            # Identify keys using database
            key_a_info = self.db.identify_key(key_a)
            key_b_info = self.db.identify_key(key_b)

            key_a_display = key_a
            key_b_display = key_b

            if key_a_info:
                key_desc = key_a_info.get('description') or key_a_info.get('key_type', 'Known')
                key_a_display += f" ({key_desc})"
            if key_b_info:
                key_desc = key_b_info.get('description') or key_b_info.get('key_type', 'Known')
                key_b_display += f" ({key_desc})"

            self.decoder_tree.insert("", "end", values=("Key A", key_a_display))
            self.decoder_tree.insert("", "end", values=("Access Bits", acc))
            self.decoder_tree.insert("", "end", values=("Key B", key_b_display))

            # Additional key analysis
            if key_a == key_b:
                self.decoder_tree.insert("", "end", values=("Key Warning", "Both keys are identical"))

            # Decode access bits
            try:
                c1, c2, c3 = data[6], data[7], data[8]
                # Basic access bit validation
                c1_inv = (~c1) & 0xFF
                if (c1 ^ c3) != 0xFF:
                    self.decoder_tree.insert("", "end", values=("Access Status", "WARNING: Invalid access bits"))
                else:
                    self.decoder_tree.insert("", "end", values=("Access Status", "Valid"))
            except: pass

        # 5. UID ANALYSIS (Block 0) - Enhanced CSC Structure
        if b_id == 0:
            uid = data[0:4]
            uid_hex = uid.hex().upper()
            bcc = data[4]
            sak = data[5]
            atqa = data[6:8]
            mfr_data = data[8:16]

            # Calculate expected BCC
            expected_bcc = uid[0] ^ uid[1] ^ uid[2] ^ uid[3]
            bcc_valid = "✓" if bcc == expected_bcc else f"✗ (expected {expected_bcc:02X})"

            # Decode SAK type
            sak_types = {0x08: "MIFARE Classic 1K", 0x18: "MIFARE Classic 4K",
                        0x09: "MIFARE Mini", 0x00: "Ultralight", 0x20: "Plus/DESFire"}
            sak_type = sak_types.get(sak, "Unknown")

            # UID as decimal (for CSC readers)
            uid_decimal = (uid[0] << 24) | (uid[1] << 16) | (uid[2] << 8) | uid[3]

            self.decoder_tree.insert("", "end", values=("═══ BLOCK 0: MANUFACTURER ═══", ""))
            self.decoder_tree.insert("", "end", values=("UID (Hex)", uid_hex))
            self.decoder_tree.insert("", "end", values=("UID (Decimal)", f"{uid_decimal:,}"))
            self.decoder_tree.insert("", "end", values=("BCC (Checksum)", f"0x{bcc:02X} {bcc_valid}"))
            self.decoder_tree.insert("", "end", values=("SAK", f"0x{sak:02X} = {sak_type}"))
            self.decoder_tree.insert("", "end", values=("ATQA", f"{atqa.hex().upper()} (LE: 0x{struct.unpack('<H', atqa)[0]:04X})"))
            self.decoder_tree.insert("", "end", values=("Manufacturer Data", mfr_data.hex().upper()))

        # 5b. BLOCK 1 - PROVIDER IDENTIFICATION (CSC Card Configuration)
        if b_id == 1:
            self.decoder_tree.insert("", "end", values=("═══ BLOCK 1: CARD CONFIG ═══", ""))

            # CSC Structure decode
            if len(data) >= 16:
                prefix = data[0:2]
                version = struct.unpack('>H', data[2:4])[0]
                issuer_code = struct.unpack('<H', data[6:8])[0]
                system_id = data[8:10]
                config_flags = data[12:14]
                checksum = data[14:16]

                # Decode ASCII prefix
                prefix_ascii = ''.join(chr(b) if 32 <= b <= 126 else '.' for b in prefix)
                system_ascii = ''.join(chr(b) if 32 <= b <= 126 else '.' for b in system_id)

                self.decoder_tree.insert("", "end", values=("Card Prefix", f"'{prefix_ascii}' (0x{prefix.hex().upper()})"))
                self.decoder_tree.insert("", "end", values=("Version", f"0x{version:04X}"))
                self.decoder_tree.insert("", "end", values=("Issuer Code", f"0x{issuer_code:04X} ({issuer_code})"))
                self.decoder_tree.insert("", "end", values=("System ID", f"'{system_ascii}'"))
                self.decoder_tree.insert("", "end", values=("Config Flags", f"0x{config_flags.hex().upper()}"))
                self.decoder_tree.insert("", "end", values=("Checksum", f"0x{checksum.hex().upper()}"))

            # Show detected provider
            if self.detected_operator_id != 3:  # 3 = Unknown
                self.decoder_tree.insert("", "end", values=("Detected Provider", self.detected_operator_name))

        # 6. BLOCK 2 - CSC CARD SERIAL & BALANCE BACKUP
        if b_id == 2:
            try:
                self.decoder_tree.insert("", "end", values=("═══ BLOCK 2: SERIAL/BACKUP ═══", ""))

                if len(data) >= 16:
                    # CSC Structure
                    header = data[0:2]
                    card_type_id = data[2:5]
                    serial_byte = data[5]
                    balance_backup = struct.unpack('<H', data[9:11])[0]
                    flags = data[11]
                    checksum = data[15]

                    self.decoder_tree.insert("", "end", values=("Header", f"0x{header.hex().upper()} (version)"))
                    self.decoder_tree.insert("", "end", values=("Card Type ID", f"0x{card_type_id.hex().upper()} (CSC)"))
                    self.decoder_tree.insert("", "end", values=("Card Serial#", f"0x{serial_byte:02X} ({serial_byte})"))
                    self.decoder_tree.insert("", "end", values=("Balance Backup", f"${balance_backup/100:.2f} ({balance_backup} cents)"))
                    self.decoder_tree.insert("", "end", values=("Flags", f"0x{flags:02X}"))
                    self.decoder_tree.insert("", "end", values=("Checksum", f"0x{checksum:02X}"))

                # Check operator-specific structures from database
                structures = self.db.get_block_structures(self.detected_operator_id, 2)

                # Provider signature detection
                if self.detected_operator_id != 3:  # 3 = Unknown
                    if len(data) >= 2 and data[0:2] == b'\x01\x01':
                        self.decoder_tree.insert("", "end", values=("Provider Signature", f"{self.detected_operator_name} (0x0101)"))

            except: pass

        # 7. BLOCK 9 - USAGES LEFT & MAX VALUE ⭐ KEY DISCOVERY
        if b_id == 9:
            try:
                # CRITICAL: Block 9 contains overlapping data
                # Bytes 0-1 (16-bit): Usage counter
                # Bytes 0-3 (32-bit): Max value
                if len(data) >= 8:
                    # Usages left (what Flipper Zero displays)
                    usages_16bit = struct.unpack('<H', data[0:2])[0]
                    self.decoder_tree.insert("", "end", values=("🎯 Usages Left", f"{usages_16bit:,} cycles"))

                    # Full 32-bit value
                    v9 = struct.unpack('<I', data[0:4])[0]
                    v9_inv = struct.unpack('<I', data[4:8])[0]

                    if (v9 ^ v9_inv) == 0xFFFFFFFF:
                        self.decoder_tree.insert("", "end", values=("Full Value (32-bit)", f"{v9:,}"))
                        if v9 > 50000:
                            self.decoder_tree.insert("", "end", values=("  → As Currency", f"${v9/100:.2f}"))
                            self.decoder_tree.insert("", "end", values=("Theory", "Max balance limit or total capacity"))

                        # Show the relationship
                        self.decoder_tree.insert("", "end", values=("Data Structure", "16-bit counter overlaps 32-bit value"))
            except: pass

        # 8. BLOCK 12 - ADDITIONAL COUNTER/VALUE
        if b_id == 12:
            try:
                if len(data) >= 8:
                    v12 = struct.unpack('<I', data[0:4])[0]
                    v12_inv = struct.unpack('<I', data[4:8])[0]
                    # Check for inverse pattern
                    if (v12 ^ v12_inv) == 0xFFFFFFFF:
                        self.decoder_tree.insert("", "end", values=("Value Block", f"{v12:,}"))
                    else:
                        # Try as separate values
                        v12_a = struct.unpack('<H', data[0:2])[0]
                        v12_b = struct.unpack('<H', data[2:4])[0]
                        if v12_a > 0 or v12_b > 0:
                            self.decoder_tree.insert("", "end", values=("Counter Values", f"A={v12_a}, B={v12_b}"))
            except: pass

        # 9. BLOCK 1 - SYSTEM IDENTIFIERS & TIMESTAMP
        if b_id == 1:
            try:
                # Check for ASCII patterns
                ascii_clean = "".join([chr(b) if 32 <= b <= 126 else '' for b in data]).strip()
                if ascii_clean and len(ascii_clean) >= 2:
                    self.decoder_tree.insert("", "end", values=("ASCII Content", f'"{ascii_clean}"'))

                # Timestamp at bytes 12-15
                if len(data) >= 16:
                    ts = struct.unpack('<I', data[12:16])[0]
                    if 946684800 < ts < 2147483647:  # Between 2000 and 2038
                        dt = datetime.fromtimestamp(ts)
                        self.decoder_tree.insert("", "end", values=("Card Creation Date", dt.strftime('%Y-%m-%d %H:%M:%S')))
                        self.decoder_tree.insert("", "end", values=("  → Timestamp", f"0x{ts:08X}"))

                # Config value at bytes 6-7
                if len(data) >= 8:
                    config_val = struct.unpack('<H', data[6:8])[0]
                    self.decoder_tree.insert("", "end", values=("Config/Firmware", f"0x{config_val:04X} ({config_val})"))
            except: pass

        # 10. BLOCK 10 - CSC NETWORK/SYSTEM IDENTIFIER
        if b_id == 10:
            try:
                self.decoder_tree.insert("", "end", values=("═══ BLOCK 10: NETWORK ID ═══", ""))

                if len(data) >= 16:
                    # CSC Structure
                    prefix = data[0:2]
                    version = struct.unpack('>H', data[2:4])[0]
                    issuer_code = struct.unpack('<H', data[6:8])[0]
                    system_prefix = data[8:10]
                    network_id = data[10:13]
                    sys_flags = data[13]

                    prefix_ascii = ''.join(chr(b) if 32 <= b <= 126 else '.' for b in prefix)
                    system_ascii = ''.join(chr(b) if 32 <= b <= 126 else '.' for b in system_prefix)
                    network_ascii = ''.join(chr(b) if 32 <= b <= 126 else '.' for b in network_id)

                    self.decoder_tree.insert("", "end", values=("Card Prefix", f"'{prefix_ascii}'"))
                    self.decoder_tree.insert("", "end", values=("Version", f"0x{version:04X}"))
                    self.decoder_tree.insert("", "end", values=("Issuer Code", f"0x{issuer_code:04X} ({issuer_code})"))
                    self.decoder_tree.insert("", "end", values=("System Prefix", f"'{system_ascii}'"))
                    self.decoder_tree.insert("", "end", values=("🌐 Network ID", f"'{network_ascii}'"))
                    self.decoder_tree.insert("", "end", values=("System Flags", f"0x{sys_flags:02X} ({sys_flags})"))

                    if network_ascii == 'NET':
                        self.decoder_tree.insert("", "end", values=("Network Status", "CSC ServiceWorks Network"))
            except: pass

        # 11. BLOCK 13 - CSC LOCATION/SITE IDENTIFIER (⚠️ SENSITIVE)
        if b_id == 13:
            try:
                self.decoder_tree.insert("", "end", values=("═══ BLOCK 13: SITE ID ⚠️ ═══", ""))

                if len(data) >= 16:
                    # CSC Structure - Site code in first 9 bytes
                    site_code_bytes = data[0:9]
                    version = data[9:11]
                    reserved = data[11:16]

                    # Decode site code as ASCII
                    site_code = ''.join(chr(b) if 32 <= b <= 126 else '' for b in site_code_bytes).strip()

                    if site_code and len(site_code) >= 3:
                        self.decoder_tree.insert("", "end", values=("🏢 Site/Location Code", f'"{site_code}"'))
                        self.decoder_tree.insert("", "end", values=("⚠️ Privacy Warning", "Identifies physical location!"))
                    else:
                        self.decoder_tree.insert("", "end", values=("Site Code (Raw)", site_code_bytes.hex().upper()))

                    self.decoder_tree.insert("", "end", values=("Version", f"0x{version.hex().upper()}"))

                    # Check if reserved area has data
                    if any(b != 0 for b in reserved):
                        self.decoder_tree.insert("", "end", values=("Reserved Data", reserved.hex().upper()))
            except: pass

        # 10. DETECT PATTERNS
        # All zeros
        if all(b == 0 for b in data):
            self.decoder_tree.insert("", "end", values=("Pattern", "All zeros (empty block)"))

        # All 0xFF
        if all(b == 0xFF for b in data):
            self.decoder_tree.insert("", "end", values=("Pattern", "All 0xFF (erased/default)"))

        # Repeating bytes
        if len(set(data)) == 1 and len(data) > 4:
            self.decoder_tree.insert("", "end", values=("Pattern", f"Repeating byte: {data[0]:02X}"))

        # 11. INTEGER INTERPRETATIONS (first 4 bytes - for non-special blocks)
        if len(data) >= 4 and b_id not in [0, 2, 4, 8, 9, 12] and not self.is_sector_trailer(b_id):
            try:
                uint32_le = struct.unpack('<I', data[0:4])[0]
                uint32_be = struct.unpack('>I', data[0:4])[0]
                int32_le = struct.unpack('<i', data[0:4])[0]

                # Only show if non-zero and interesting
                if uint32_le > 0:
                    self.decoder_tree.insert("", "end", values=("UInt32 (LE)", f"{uint32_le:,}"))
                if uint32_be != uint32_le and uint32_be > 0:
                    self.decoder_tree.insert("", "end", values=("UInt32 (BE)", f"{uint32_be:,}"))
                if int32_le < 0:
                    self.decoder_tree.insert("", "end", values=("Int32 (LE)", f"{int32_le:,}"))
            except: pass

    def commit_raw_changes(self):
        if self.current_block is None: return

        # Get raw hex from entry field
        raw_hex = self.ent_hex.get().strip()

        # Normalize to Flipper Zero format (uppercase, single spaces, preserve ??)
        # Split by whitespace and normalize each byte
        parts = raw_hex.split()
        normalized_parts = []
        for part in parts:
            if part == "??":
                # Preserve unknown data marker
                normalized_parts.append("??")
            else:
                # Convert to uppercase 2-character hex
                part = part.upper()
                # Ensure it's 2 characters (pad if needed)
                if len(part) == 1:
                    part = "0" + part
                normalized_parts.append(part)

        # Join with single spaces (Flipper Zero format)
        normalized_hex = " ".join(normalized_parts)
        self.blocks[self.current_block] = normalized_hex

        # Update display to show normalized format
        self.ent_hex.delete(0, "end")
        self.ent_hex.insert(0, normalized_hex)

        # Save to history
        self.history.push(
            self.blocks,
            f"Edited Block {self.current_block}",
            {"block": self.current_block}
        )

        self.refresh_ui()

    def undo(self):
        """Undo to previous state"""
        if not self.history.can_undo():
            messagebox.showinfo("Undo", "No more undo history available")
            return

        entry = self.history.undo()
        if entry:
            # Restore blocks from history
            self.blocks = entry.blocks.copy()
            self.refresh_ui()
            self.run_decoders(self.current_block, self.blocks.get(self.current_block, ""))

            # Show status
            self.root.title(f"LaundR - Undone: {entry.description}")

    def redo(self):
        """Redo to next state"""
        if not self.history.can_redo():
            messagebox.showinfo("Redo", "No more redo history available")
            return

        entry = self.history.redo()
        if entry:
            # Restore blocks from history
            self.blocks = entry.blocks.copy()
            self.refresh_ui()
            self.run_decoders(self.current_block, self.blocks.get(self.current_block, ""))

            # Show status
            self.root.title(f"LaundR - Redone: {entry.description}")

    def fix_access_bits(self):
        count = 0
        for b_id in self.blocks:
            if self.is_sector_trailer(b_id):
                parts = self.blocks[b_id].split()
                if len(parts) == 16:
                    parts[6:9] = ["FF", "07", "80"]
                    self.blocks[b_id] = " ".join(parts)
                    count += 1
        self.refresh_ui()
        messagebox.showinfo("Result", f"Reset {count} trailers.")

    def save_file(self):
        path = filedialog.asksaveasfilename(defaultextension=".nfc", filetypes=[("NFC Files", "*.nfc")])
        if not path: return
        try:
            with open(path, 'w', newline='\n', encoding='utf-8') as f:
                # Write headers (no blank line after - Flipper Zero format requirement)
                for h in self.headers:
                    f.write(h + "\n")

                # Write blocks immediately after headers (Flipper Zero compatible)
                limit = 256 if self.card_type == "4K" else 64
                for i in range(limit):
                    if i in self.blocks:
                        # Ensure uppercase hex formatting
                        block_data = self.blocks[i].upper()
                        f.write(f"Block {i}: {block_data}\n")
                    else:
                        # Default blocks for missing data
                        if self.is_sector_trailer(i):
                            f.write(f"Block {i}: FF FF FF FF FF FF FF 07 80 69 FF FF FF FF FF FF\n")
                        else:
                            f.write(f"Block {i}: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00\n")

            self.config.add_recent(path)
            self.update_recent_menu()
            messagebox.showinfo("Saved", "File saved successfully.\n\nFormat: Flipper Zero compatible")
        except Exception as e:
            messagebox.showerror("Error", f"Failed to save file:\n{str(e)}")

    def save_shadow(self):
        """Save to shadow file (.laundr extension) - keeps original untouched"""
        if not self.filename:
            messagebox.showwarning("No File", "Please load a card file first")
            return

        # Create shadow path by replacing .nfc with .laundr
        base_path = os.path.splitext(self.filename)[0]
        shadow_path = base_path + ".laundr"

        try:
            with open(shadow_path, 'w', newline='\n', encoding='utf-8') as f:
                # Write headers
                for h in self.headers:
                    f.write(h + "\n")

                # Write blocks
                limit = 256 if self.card_type == "4K" else 64
                for i in range(limit):
                    if i in self.blocks:
                        block_data = self.blocks[i].upper()
                        f.write(f"Block {i}: {block_data}\n")
                    else:
                        if self.is_sector_trailer(i):
                            f.write(f"Block {i}: FF FF FF FF FF FF FF 07 80 69 FF FF FF FF FF FF\n")
                        else:
                            f.write(f"Block {i}: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00\n")

            self.shadow_filename = shadow_path
            messagebox.showinfo("Shadow Saved", f"Shadow file saved:\n{os.path.basename(shadow_path)}\n\nOriginal file untouched")
        except Exception as e:
            messagebox.showerror("Error", f"Failed to save shadow file:\n{str(e)}")

    def clone_card(self):
        """Clone current card to a new file"""
        if not self.blocks:
            messagebox.showwarning("No Card", "Please load a card file first")
            return

        path = filedialog.asksaveasfilename(
            defaultextension=".nfc",
            filetypes=[("NFC Files", "*.nfc")],
            title="Clone Card As..."
        )
        if not path:
            return

        try:
            with open(path, 'w', newline='\n', encoding='utf-8') as f:
                # Write headers
                for h in self.headers:
                    f.write(h + "\n")

                # Write blocks
                limit = 256 if self.card_type == "4K" else 64
                for i in range(limit):
                    if i in self.blocks:
                        block_data = self.blocks[i].upper()
                        f.write(f"Block {i}: {block_data}\n")
                    else:
                        if self.is_sector_trailer(i):
                            f.write(f"Block {i}: FF FF FF FF FF FF FF 07 80 69 FF FF FF FF FF FF\n")
                        else:
                            f.write(f"Block {i}: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00\n")

            self.config.add_recent(path)
            self.update_recent_menu()
            messagebox.showinfo("Cloned", f"Card cloned successfully:\n{os.path.basename(path)}")
        except Exception as e:
            messagebox.showerror("Error", f"Failed to clone card:\n{str(e)}")

    def quick_adjust_balance(self, amount):
        """Quickly adjust balance by a fixed amount (+/-$5)"""
        if self.detected_balance_block is None:
            messagebox.showwarning("No Balance", "No balance block detected")
            return

        try:
            # Get current balance
            bal_bytes = self.get_bytes_at(self.detected_balance_block, 0, 2)
            if not bal_bytes:
                messagebox.showerror("Error", "Could not read current balance")
                return

            current_cents = struct.unpack('<H', bal_bytes)[0]
            current_dollars = current_cents / 100.0

            # Calculate new balance
            new_dollars = current_dollars + amount
            if new_dollars < 0:
                messagebox.showwarning("Invalid", "Balance cannot be negative")
                return

            # Update balance entry field
            self.ent_balance.delete(0, tk.END)
            self.ent_balance.insert(0, f"{new_dollars:.2f}")

            # Trigger update
            self.update_balance()

            # Auto-save to shadow file
            if self.filename:
                self.save_shadow()

        except Exception as e:
            messagebox.showerror("Error", f"Failed to adjust balance:\n{str(e)}")

    def export_json(self):
        """Export card data to JSON format"""
        path = filedialog.asksaveasfilename(defaultextension=".json", filetypes=[("JSON Files", "*.json")])
        if not path: return
        try:
            export_data = {
                "metadata": {
                    "filename": self.filename,
                    "card_type": self.card_type,
                    "provider": self.detected_provider,
                    "balance_block": self.detected_balance_block,
                    "export_date": datetime.now().isoformat()
                },
                "headers": self.headers,
                "blocks": {}
            }

            for block_id, data in self.blocks.items():
                block_info = {
                    "raw": data,
                    "is_trailer": self.is_sector_trailer(block_id),
                    "is_balance": block_id == self.detected_balance_block
                }

                # Add decoded info
                clean = data.replace("??", "00").replace(" ", "")
                try:
                    block_bytes = bytearray.fromhex(clean)
                    block_info["ascii"] = "".join([chr(b) if 32 <= b <= 126 else '.' for b in block_bytes])
                except:
                    pass

                export_data["blocks"][str(block_id)] = block_info

            with open(path, 'w') as f:
                json.dump(export_data, f, indent=2)

            messagebox.showinfo("Exported", f"Data exported to {path}")
        except Exception as e:
            messagebox.showerror("Export Error", str(e))

    def export_csv(self):
        """Export card data to CSV format"""
        path = filedialog.asksaveasfilename(defaultextension=".csv", filetypes=[("CSV Files", "*.csv")])
        if not path: return
        try:
            with open(path, 'w', newline='') as f:
                writer = csv.writer(f)
                writer.writerow(["Block", "Hex Data", "ASCII", "Type", "Notes"])

                limit = 256 if self.card_type == "4K" else 64
                for i in range(limit):
                    if i not in self.blocks:
                        continue

                    raw = self.blocks[i]
                    ascii_data = ""
                    block_type = "Data"
                    notes = ""

                    if self.is_sector_trailer(i):
                        block_type = "Sector Trailer"
                    elif i == self.detected_balance_block:
                        block_type = "Balance Block"
                        bal_bytes = self.get_bytes_at(i, 0, 2)
                        if bal_bytes:
                            cents = struct.unpack('<H', bal_bytes)[0]
                            notes = f"${cents/100:.2f}"
                    elif i == 0:
                        block_type = "UID"

                    clean = raw.replace("??", "00").replace(" ", "")
                    try:
                        block_bytes = bytearray.fromhex(clean)
                        ascii_data = "".join([chr(b) if 32 <= b <= 126 else '.' for b in block_bytes])
                    except:
                        pass

                    writer.writerow([i, raw, ascii_data, block_type, notes])

            messagebox.showinfo("Exported", f"Data exported to {path}")
        except Exception as e:
            messagebox.showerror("Export Error", str(e))

    def generate_report(self):
        """Generate a text-based forensic report"""
        path = filedialog.asksaveasfilename(defaultextension=".txt", filetypes=[("Text Files", "*.txt")])
        if not path: return
        try:
            with open(path, 'w') as f:
                f.write("=" * 70 + "\n")
                f.write("LaundR Forensic Analysis Report\n")
                f.write("=" * 70 + "\n\n")

                f.write(f"Analysis Date: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
                f.write(f"Source File: {self.filename or 'Unknown'}\n")
                f.write(f"Card Type: Mifare Classic {self.card_type}\n\n")

                f.write("-" * 70 + "\n")
                f.write("CARD INTELLIGENCE\n")
                f.write("-" * 70 + "\n")
                f.write(f"Detected Provider: {self.detected_provider}\n")
                f.write(f"Balance Block: {self.detected_balance_block}\n")

                # Balance
                if self.detected_balance_block and self.detected_balance_block in self.blocks:
                    bal_bytes = self.get_bytes_at(self.detected_balance_block, 0, 2)
                    if bal_bytes:
                        cents = struct.unpack('<H', bal_bytes)[0]
                        f.write(f"Current Balance: ${cents/100:.2f}\n")

                # Receipt
                rec_bytes = self.get_bytes_at(2, 9, 2)
                if rec_bytes:
                    rcents = struct.unpack('<H', rec_bytes)[0]
                    f.write(f"Last Transaction: ${rcents/100:.2f}\n")

                # UID
                if 0 in self.blocks:
                    uid_bytes = self.get_bytes_at(0, 0, 4)
                    if uid_bytes:
                        f.write(f"Card UID: {uid_bytes.hex().upper()}\n")

                f.write("\n" + "-" * 70 + "\n")
                f.write("MEMORY ANALYSIS\n")
                f.write("-" * 70 + "\n\n")

                for block_id in sorted(self.blocks.keys()):
                    raw = self.blocks[block_id]
                    f.write(f"Block {block_id:02d}: {raw}\n")

                    if self.is_sector_trailer(block_id):
                        f.write(f"  -> Sector Trailer\n")
                    elif block_id == self.detected_balance_block:
                        f.write(f"  -> ACTIVE BALANCE BLOCK\n")
                    elif "??" in raw:
                        f.write(f"  -> Contains unknown data\n")

                    # Add ASCII if interesting
                    clean = raw.replace("??", "00").replace(" ", "")
                    try:
                        block_bytes = bytearray.fromhex(clean)
                        ascii_str = "".join([chr(b) if 32 <= b <= 126 else '' for b in block_bytes])
                        if ascii_str.strip():
                            f.write(f"  -> ASCII: {ascii_str}\n")
                    except:
                        pass

                    f.write("\n")

                f.write("=" * 70 + "\n")
                f.write("End of Report\n")
                f.write("=" * 70 + "\n")

            messagebox.showinfo("Report Generated", f"Forensic report saved to {path}")
        except Exception as e:
            messagebox.showerror("Report Error", str(e))

    def edit_uid(self):
        """Edit the UID of the currently loaded card"""
        if not self.blocks or 0 not in self.blocks:
            messagebox.showerror("Error", "No card loaded! Please load a card first.")
            return

        # Get current UID from block 0 (first 4 bytes)
        current_uid_bytes = self.get_bytes_at(0, 0, 4)
        if not current_uid_bytes:
            messagebox.showerror("Error", "Could not read current UID from block 0")
            return

        current_uid = current_uid_bytes.hex().upper()
        current_uid_formatted = ' '.join([current_uid[i:i+2] for i in range(0, len(current_uid), 2)])

        # Create dialog
        dialog = tk.Toplevel(self.root)
        dialog.title("Edit Card UID")
        dialog.geometry("400x200")
        dialog.transient(self.root)
        dialog.grab_set()

        # Center dialog
        dialog.update_idletasks()
        x = (dialog.winfo_screenwidth() // 2) - (400 // 2)
        y = (dialog.winfo_screenheight() // 2) - (200 // 2)
        dialog.geometry(f"400x200+{x}+{y}")
        dialog.configure(bg=self.colors['frame_bg'])

        # Main frame
        main_frame = tk.Frame(dialog, bg=self.colors['frame_bg'], padx=20, pady=20)
        main_frame.pack(fill="both", expand=True)

        # Current UID display
        tk.Label(main_frame, text="Current UID:", font=("Segoe UI", 10, "bold"),
                bg=self.colors['frame_bg'], fg=self.colors['label_fg']).pack(anchor="w", pady=(0, 5))
        tk.Label(main_frame, text=current_uid_formatted, font=("Courier", 11),
                bg=self.colors['label_bg'], fg=self.colors['label_fg'],
                padx=10, pady=5).pack(anchor="w", pady=(0, 15))

        # Option to allow different UID length
        allow_diff_len = tk.BooleanVar(value=False)
        tk.Checkbutton(main_frame, text="Allow different UID length (4 or 7 bytes)",
                      variable=allow_diff_len, bg=self.colors['frame_bg'],
                      fg=self.colors['label_fg'], selectcolor=self.colors['entry_bg']).pack(anchor="w", pady=(0, 10))

        # New UID entry
        current_uid_len = len(current_uid_bytes)
        tk.Label(main_frame, text=f"New UID ({current_uid_len*2} hex chars for {current_uid_len}-byte UID):",
                font=("Segoe UI", 10, "bold"),
                bg=self.colors['frame_bg'], fg=self.colors['label_fg']).pack(anchor="w", pady=(0, 5))
        uid_entry = tk.Entry(main_frame, font=("Courier", 11), width=20,
                            bg=self.colors['entry_bg'], fg=self.colors['entry_fg'])
        uid_entry.pack(anchor="w", pady=(0, 20))
        uid_entry.insert(0, current_uid)
        uid_entry.select_range(0, tk.END)
        uid_entry.focus()

        def apply_uid():
            new_uid_hex = uid_entry.get().replace(" ", "").replace(":", "").upper()

            # Validate length
            if len(new_uid_hex) % 2 != 0:
                messagebox.showerror("Error", "UID must have an even number of hex characters")
                return

            new_uid_len = len(new_uid_hex) // 2

            # Check if length matches original or if different length is allowed
            if not allow_diff_len.get() and new_uid_len != current_uid_len:
                messagebox.showerror("Error",
                    f"UID length mismatch! Original is {current_uid_len} bytes.\n"
                    f"Check 'Allow different UID length' to override.")
                return

            # Validate supported lengths (4-byte or 7-byte)
            if new_uid_len not in [4, 7]:
                messagebox.showerror("Error",
                    "UID must be either 4 bytes (8 hex chars) or 7 bytes (14 hex chars).\n"
                    "MIFARE Classic typically uses 4-byte UIDs.")
                return

            try:
                new_uid_bytes = bytes.fromhex(new_uid_hex)
            except ValueError:
                messagebox.showerror("Error", "Invalid hex characters in UID")
                return

            # Calculate BCC (check byte): XOR of all UID bytes
            bcc = 0
            for b in new_uid_bytes:
                bcc ^= b

            # Update block 0: UID + BCC + rest stays the same
            if 0 not in self.blocks:
                messagebox.showerror("Error", "Block 0 not found")
                return

            # Preserve existing block 0 data beyond UID + BCC
            old_block_str = self.blocks[0]
            # Convert string "2B B9 91 B5..." to bytes
            old_block_bytes = bytes.fromhex(old_block_str.replace(' ', ''))

            new_block = bytearray(new_uid_bytes)  # UID (4 or 7 bytes)
            new_block.append(bcc)  # BCC (1 byte)

            # Add remaining bytes from old block
            # For 4-byte UID: rest starts at byte 5 (11 bytes remain)
            # For 7-byte UID: rest starts at byte 8 (8 bytes remain)
            start_pos = new_uid_len + 1
            if start_pos < len(old_block_bytes):
                new_block.extend(old_block_bytes[start_pos:])

            # Ensure block is exactly 16 bytes
            while len(new_block) < 16:
                new_block.append(0x00)
            if len(new_block) > 16:
                new_block = new_block[:16]

            # Update the block - convert bytes back to space-separated hex string
            self.blocks[0] = ' '.join([f'{b:02X}' for b in new_block])

            # Update headers if they exist (UID line in file header)
            for i, header in enumerate(self.headers):
                if header.startswith("UID:"):
                    new_uid_formatted = ' '.join([new_uid_hex[j:j+2] for j in range(0, len(new_uid_hex), 2)])
                    self.headers[i] = f"UID: {new_uid_formatted}"
                    break

            # Save to history
            self.history.push(
                self.blocks,
                f"Changed UID to {new_uid_hex}",
                {"old_uid": current_uid, "new_uid": new_uid_hex}
            )

            # Refresh UI
            self.refresh_ui()

            dialog.destroy()
            messagebox.showinfo("Success",
                f"UID changed successfully!\n\n"
                f"Old UID: {current_uid_formatted}\n"
                f"New UID: {new_uid_formatted}\n"
                f"BCC: {bcc:02X}\n\n"
                f"Remember to save the file!")

        # Buttons
        btn_frame = tk.Frame(main_frame, bg=self.colors['frame_bg'])
        btn_frame.pack(fill="x")

        tk.Button(btn_frame, text="Apply", command=apply_uid,
                  bg="#4CAF50" if not self.dark_mode else "#388E3C", fg="white",
                  font=("Segoe UI", 10, "bold"), padx=20, pady=5, cursor="hand2").pack(side="left", padx=5)
        tk.Button(btn_frame, text="Cancel", command=dialog.destroy,
                  bg="#F44336" if not self.dark_mode else "#D32F2F", fg="white",
                  font=("Segoe UI", 10, "bold"), padx=20, pady=5, cursor="hand2").pack(side="left")

        # Bind Enter key to apply
        dialog.bind('<Return>', lambda e: apply_uid())

    def generate_random_card(self):
        """Generate a random laundry card with customizable UID and balance"""
        import random

        # Create dialog for user input
        dialog = tk.Toplevel(self.root)
        dialog.title("Generate Random Card")
        dialog.geometry("450x350")
        dialog.transient(self.root)
        dialog.grab_set()

        # Center the dialog
        dialog.update_idletasks()
        x = (dialog.winfo_screenwidth() // 2) - (450 // 2)
        y = (dialog.winfo_screenheight() // 2) - (350 // 2)
        dialog.geometry(f"450x350+{x}+{y}")

        # Configure dialog styling
        dialog.configure(bg=self.colors['frame_bg'])

        # Main frame
        main_frame = tk.Frame(dialog, bg=self.colors['frame_bg'], padx=20, pady=20)
        main_frame.pack(fill="both", expand=True)

        # UID Section
        uid_frame = tk.LabelFrame(main_frame, text="UID Configuration", font=("Segoe UI", 10, "bold"),
                                  bg=self.colors['label_bg'], fg=self.colors['label_fg'], padx=10, pady=10)
        uid_frame.pack(fill="x", pady=(0, 15))

        uid_mode_var = tk.StringVar(value="random")
        tk.Radiobutton(uid_frame, text="Random UID", variable=uid_mode_var, value="random",
                       bg=self.colors['label_bg'], fg=self.colors['label_fg'],
                       selectcolor=self.colors['entry_bg']).grid(row=0, column=0, sticky="w", pady=2)
        tk.Radiobutton(uid_frame, text="Custom UID (hex):", variable=uid_mode_var, value="custom",
                       bg=self.colors['label_bg'], fg=self.colors['label_fg'],
                       selectcolor=self.colors['entry_bg']).grid(row=1, column=0, sticky="w", pady=2)

        uid_entry = tk.Entry(uid_frame, width=20, bg=self.colors['entry_bg'], fg=self.colors['entry_fg'])
        uid_entry.grid(row=1, column=1, sticky="w", padx=(5, 0))
        uid_entry.insert(0, "01234567")

        # Balance Section
        balance_frame = tk.LabelFrame(main_frame, text="Balance Configuration", font=("Segoe UI", 10, "bold"),
                                       bg=self.colors['label_bg'], fg=self.colors['label_fg'], padx=10, pady=10)
        balance_frame.pack(fill="x", pady=(0, 15))

        balance_mode_var = tk.StringVar(value="random")
        tk.Radiobutton(balance_frame, text="Random Balance ($10-$100)", variable=balance_mode_var, value="random",
                       bg=self.colors['label_bg'], fg=self.colors['label_fg'],
                       selectcolor=self.colors['entry_bg']).grid(row=0, column=0, sticky="w", pady=2, columnspan=2)
        tk.Radiobutton(balance_frame, text="Custom Balance:", variable=balance_mode_var, value="custom",
                       bg=self.colors['label_bg'], fg=self.colors['label_fg'],
                       selectcolor=self.colors['entry_bg']).grid(row=1, column=0, sticky="w", pady=2)

        balance_entry = tk.Entry(balance_frame, width=15, bg=self.colors['entry_bg'], fg=self.colors['entry_fg'])
        balance_entry.grid(row=1, column=1, sticky="w", padx=(5, 0))
        balance_entry.insert(0, "25.00")

        # Provider Section
        provider_frame = tk.LabelFrame(main_frame, text="Provider/Operator", font=("Segoe UI", 10, "bold"),
                                        bg=self.colors['label_bg'], fg=self.colors['label_fg'], padx=10, pady=10)
        provider_frame.pack(fill="x", pady=(0, 15))

        tk.Label(provider_frame, text="Select provider:", bg=self.colors['label_bg'], fg=self.colors['label_fg']).grid(row=0, column=0, sticky="w")
        provider_var = tk.StringVar(value="CSC Service Works")
        provider_combo = ttk.Combobox(provider_frame, textvariable=provider_var, width=25,
                                      values=["CSC Service Works", "U-Best Wash", "Generic"])
        provider_combo.grid(row=0, column=1, sticky="w", padx=(5, 0))

        # Buttons
        btn_frame = tk.Frame(main_frame, bg=self.colors['frame_bg'])
        btn_frame.pack(fill="x", pady=(10, 0))

        def generate():
            try:
                # Generate or parse UID
                if uid_mode_var.get() == "random":
                    uid_bytes = bytes([random.randint(0, 255) for _ in range(4)])
                else:
                    uid_hex = uid_entry.get().replace(" ", "").replace(":", "")
                    if len(uid_hex) != 8:
                        messagebox.showerror("Error", "UID must be 8 hex characters (4 bytes)")
                        return
                    try:
                        uid_bytes = bytes.fromhex(uid_hex)
                    except ValueError:
                        messagebox.showerror("Error", "Invalid hex UID")
                        return

                # Generate or parse balance
                if balance_mode_var.get() == "random":
                    balance_dollars = random.uniform(10.0, 100.0)
                else:
                    try:
                        balance_dollars = float(balance_entry.get())
                        if balance_dollars < 0 or balance_dollars > 999.99:
                            messagebox.showerror("Error", "Balance must be between $0 and $999.99")
                            return
                    except ValueError:
                        messagebox.showerror("Error", "Invalid balance amount")
                        return

                balance_cents = int(balance_dollars * 100)

                # Generate transaction history that makes sense
                # Last transaction should be less than or equal to current balance
                max_transaction = min(500, balance_cents)  # Max $5 or current balance
                last_transaction_cents = random.randint(100, max_transaction)  # $1 to max

                # Counter (number of uses)
                counter = random.randint(1, 50)

                # Ask for save location
                path = filedialog.asksaveasfilename(
                    defaultextension=".nfc",
                    filetypes=[("NFC Files", "*.nfc")],
                    title="Save Generated Card As...",
                    initialfile=f"random_card_{uid_bytes.hex().upper()}.nfc"
                )

                if not path:
                    dialog.destroy()
                    return

                # Create card data
                provider = provider_var.get()

                # Generate headers
                uid_hex = uid_bytes.hex().upper()
                uid_formatted = ' '.join([uid_hex[i:i+2] for i in range(0, len(uid_hex), 2)])

                with open(path, 'w', newline='\n', encoding='utf-8') as f:
                    # Write headers
                    f.write("Filetype: Flipper NFC device\n")
                    f.write("Version: 3\n")
                    f.write("Device type: MIFARE Classic\n")
                    f.write(f"UID: {uid_formatted}\n")
                    f.write("ATQA: 00 04\n")
                    f.write("SAK: 08\n")
                    f.write("Mifare Classic type: 1K\n")
                    f.write("Data format version: 2\n")
                    f.write("# Nfc device type: Mifare Classic 1K\n")
                    f.write("# UID, ATQA, SAK are common for all formats\n")
                    f.write("# Block count: 64\n")

                    # Block 0: Manufacturer block (UID + BCC + SAK + ATQA + Manufacturer)
                    bcc = uid_bytes[0] ^ uid_bytes[1] ^ uid_bytes[2] ^ uid_bytes[3]
                    block0 = f"{uid_formatted} {bcc:02X} 08 04 00 00 00 00 00 00 00 00"
                    f.write(f"Block 0: {block0}\n")

                    # Block 1: Provider identifier
                    if "CSC" in provider:
                        block1 = "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"
                    elif "U-Best" in provider:
                        block1_data = b"UBESTWASHLA\x00\x00\x00\x00\x00"
                        block1 = ' '.join([f"{b:02X}" for b in block1_data])
                    else:
                        block1 = "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"
                    f.write(f"Block 1: {block1}\n")

                    # Block 2: Transaction history
                    block2_data = bytearray(16)
                    # Bytes 9-10: Last transaction amount (little-endian)
                    block2_data[9] = last_transaction_cents & 0xFF
                    block2_data[10] = (last_transaction_cents >> 8) & 0xFF
                    block2 = ' '.join([f"{b:02X}" for b in block2_data])
                    f.write(f"Block 2: {block2}\n")

                    # Block 3: Sector 0 trailer (default keys)
                    f.write("Block 3: FF FF FF FF FF FF FF 07 80 69 FF FF FF FF FF FF\n")

                    # Block 4: Balance block (CSC format: value(2) counter(2) ~value(2) ~counter(2) ...)
                    block4_data = bytearray(16)
                    # Value (little-endian)
                    block4_data[0] = balance_cents & 0xFF
                    block4_data[1] = (balance_cents >> 8) & 0xFF
                    # Counter
                    block4_data[2] = counter & 0xFF
                    block4_data[3] = (counter >> 8) & 0xFF
                    # Inverted value
                    block4_data[4] = (~balance_cents) & 0xFF
                    block4_data[5] = ((~balance_cents) >> 8) & 0xFF
                    # Inverted counter
                    block4_data[6] = (~counter) & 0xFF
                    block4_data[7] = ((~counter) >> 8) & 0xFF
                    # Address and inverted address
                    block4_data[8] = 0x04
                    block4_data[9] = 0x00
                    block4_data[10] = 0xFB
                    block4_data[11] = 0xFF
                    block4_data[12] = 0x04
                    block4_data[13] = 0x00
                    block4_data[14] = 0xFB
                    block4_data[15] = 0xFF

                    block4 = ' '.join([f"{b:02X}" for b in block4_data])
                    f.write(f"Block 4: {block4}\n")

                    # Blocks 5-6: Zero
                    f.write("Block 5: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00\n")
                    f.write("Block 6: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00\n")

                    # Block 7: Sector 1 trailer
                    f.write("Block 7: FF FF FF FF FF FF FF 07 80 69 FF FF FF FF FF FF\n")

                    # Block 8: Alternative balance block (zeros for CSC, but could have data)
                    f.write("Block 8: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00\n")

                    # Block 9: Usage counter (for some providers)
                    usages_left = random.randint(10, 100)
                    block9_data = bytearray(16)
                    block9_data[0] = usages_left & 0xFF
                    block9_data[1] = (usages_left >> 8) & 0xFF
                    block9 = ' '.join([f"{b:02X}" for b in block9_data])
                    f.write(f"Block 9: {block9}\n")

                    # Rest of the blocks
                    for i in range(10, 64):
                        if (i + 1) % 4 == 0:  # Sector trailer
                            f.write(f"Block {i}: FF FF FF FF FF FF FF 07 80 69 FF FF FF FF FF FF\n")
                        else:
                            f.write(f"Block {i}: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00\n")

                dialog.destroy()

                # Load the generated card
                self.load_file(path)

                messagebox.showinfo("Success",
                    f"Random card generated!\n\n"
                    f"UID: {uid_formatted}\n"
                    f"Balance: ${balance_dollars:.2f}\n"
                    f"Provider: {provider}\n"
                    f"Uses: {counter}\n"
                    f"Last Transaction: ${last_transaction_cents/100:.2f}")

            except Exception as e:
                messagebox.showerror("Error", f"Failed to generate card:\n{str(e)}")

        tk.Button(btn_frame, text="Generate & Load", command=generate,
                  bg="#4CAF50" if not self.dark_mode else "#388E3C", fg="white",
                  font=("Segoe UI", 10, "bold"), padx=20, pady=5, cursor="hand2").pack(side="left", padx=5)
        tk.Button(btn_frame, text="Cancel", command=dialog.destroy,
                  bg="#F44336" if not self.dark_mode else "#D32F2F", fg="white",
                  font=("Segoe UI", 10, "bold"), padx=20, pady=5, cursor="hand2").pack(side="left")

        # Center dialog and wait
        dialog.wait_window()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="LaundR Perfected - Forensic Analyzer for NFC Laundry Cards",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s                              # Launch GUI
  %(prog)s card.nfc                     # Launch GUI and load card.nfc
  %(prog)s -o report.txt card.nfc       # Generate report without GUI
        """
    )
    parser.add_argument('file', nargs='?', help='NFC file to open')
    parser.add_argument('-o', '--output', help='Generate report to file (no GUI)')
    parser.add_argument('--json', help='Export to JSON (no GUI)')
    parser.add_argument('--csv', help='Export to CSV (no GUI)')

    args = parser.parse_args()

    # CLI mode (no GUI)
    if args.output or args.json or args.csv:
        if not args.file:
            print("Error: Input file required for CLI mode")
            sys.exit(1)

        # Create a minimal app instance for processing
        root = tk.Tk()
        root.withdraw()  # Hide the window
        app = LaundRApp(root)
        app.load_file(args.file)

        # Generate outputs
        if args.output:
            with open(args.output, 'w') as f:
                f.write("=" * 70 + "\n")
                f.write("LaundR Forensic Analysis Report\n")
                f.write("=" * 70 + "\n\n")
                f.write(f"Analysis Date: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
                f.write(f"Source File: {args.file}\n")
                f.write(f"Card Type: Mifare Classic {app.card_type}\n\n")
                f.write(f"Detected Provider: {app.detected_provider}\n")
                f.write(f"Balance Block: {app.detected_balance_block}\n\n")

                for block_id in sorted(app.blocks.keys()):
                    f.write(f"Block {block_id:02d}: {app.blocks[block_id]}\n")

            print(f"Report generated: {args.output}")

        if args.json:
            export_data = {
                "metadata": {
                    "filename": args.file,
                    "card_type": app.card_type,
                    "provider": app.detected_provider,
                    "balance_block": app.detected_balance_block,
                    "export_date": datetime.now().isoformat()
                },
                "blocks": app.blocks
            }
            with open(args.json, 'w') as f:
                json.dump(export_data, f, indent=2)
            print(f"JSON exported: {args.json}")

        if args.csv:
            with open(args.csv, 'w', newline='') as f:
                writer = csv.writer(f)
                writer.writerow(["Block", "Hex Data"])
                for block_id in sorted(app.blocks.keys()):
                    writer.writerow([block_id, app.blocks[block_id]])
            print(f"CSV exported: {args.csv}")

        sys.exit(0)

    # GUI mode
    root = tk.Tk()
    app = LaundRApp(root)

    # Auto-load file if provided
    if args.file:
        root.after(100, lambda: app.load_file(args.file))

    root.mainloop()
