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

// Transaction log entry
typedef struct {
    uint8_t block_num;
    uint8_t operation; // 0=read, 1=write, 2=auth
    uint8_t data[16];
    uint32_t timestamp;
} TransactionLogEntry;

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

    // Original balance (for comparison)
    uint16_t original_balance;
    uint16_t current_balance;

    // Statistics
    uint32_t read_count;
    uint32_t write_count;
    uint32_t auth_count;
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

    FURI_LOG_I(
        TAG,
        "Reader AUTH Block %d (Key %s)",
        block_num,
        key_type == MfClassicKeyTypeA ? "A" : "B");

    app->auth_count++;

    // Always allow authentication with known keys
    return true;
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

    case LaundRSubmenuViewLog:
        // TODO: Show transaction log
        FURI_LOG_I(TAG, "View log selected");
        break;

    case LaundRSubmenuExit:
        view_dispatcher_stop(app->view_dispatcher);
        break;
    }
}

static void laundr_draw_callback(Canvas* canvas, void* context) {
    LaundRApp* app = (LaundRApp*)context;

    canvas_clear(canvas);
    canvas_set_font(canvas, FontPrimary);

    canvas_draw_str(canvas, 2, 10, "Laundry Tester v1.0");

    canvas_set_font(canvas, FontSecondary);

    char buffer[64];

    // Show mode
    snprintf(
        buffer,
        sizeof(buffer),
        "Mode: %s",
        app->apply_changes ? "NORMAL" : "TESTING");
    canvas_draw_str(canvas, 2, 25, buffer);

    // Show balance
    snprintf(
        buffer,
        sizeof(buffer),
        "Balance: $%.2f",
        app->current_balance / 100.0);
    canvas_draw_str(canvas, 2, 35, buffer);

    // Show statistics
    snprintf(buffer, sizeof(buffer), "Reads: %lu", app->read_count);
    canvas_draw_str(canvas, 2, 45, buffer);

    snprintf(buffer, sizeof(buffer), "Writes: %lu", app->write_count);
    canvas_draw_str(canvas, 2, 55, buffer);

    // Status
    if(app->emulating) {
        canvas_draw_str(canvas, 2, 64, "Status: EMULATING");
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
    app->read_count = 0;
    app->write_count = 0;
    app->auth_count = 0;
    app->original_balance = 0;
    app->current_balance = 0;

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
