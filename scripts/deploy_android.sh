#!/bin/bash
#===============================================================================
# LaunDRoid Android Deployment Script
# For Linux/macOS - Deploys APK to connected Android device via ADB
#===============================================================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
APK_PATH="$PROJECT_DIR/releases/android/LaunDRoid-v1.7.0.apk"

echo -e "${CYAN}"
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║              LaunDRoid Android Deployment Tool                ║"
echo "║                    v1.7.0 - Crusty Agitate Goblin             ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

#-------------------------------------------------------------------------------
# Check for ADB
#-------------------------------------------------------------------------------
check_adb() {
    echo -e "${YELLOW}[1/5] Checking for ADB...${NC}"

    if command -v adb &> /dev/null; then
        ADB_VERSION=$(adb version | head -1)
        echo -e "${GREEN}  ✓ ADB found: $ADB_VERSION${NC}"
        return 0
    fi

    echo -e "${RED}  ✗ ADB not found!${NC}"
    echo ""
    echo -e "${YELLOW}Installing ADB...${NC}"

    # Detect package manager and install
    if command -v apt &> /dev/null; then
        echo "  Using apt (Debian/Ubuntu)..."
        sudo apt update && sudo apt install -y android-tools-adb
    elif command -v dnf &> /dev/null; then
        echo "  Using dnf (Fedora)..."
        sudo dnf install -y android-tools
    elif command -v pacman &> /dev/null; then
        echo "  Using pacman (Arch)..."
        sudo pacman -S --noconfirm android-tools
    elif command -v brew &> /dev/null; then
        echo "  Using Homebrew (macOS)..."
        brew install android-platform-tools
    else
        echo -e "${RED}  ✗ Could not detect package manager!${NC}"
        echo ""
        echo "  Please install ADB manually:"
        echo "    Ubuntu/Debian: sudo apt install android-tools-adb"
        echo "    Fedora:        sudo dnf install android-tools"
        echo "    Arch:          sudo pacman -S android-tools"
        echo "    macOS:         brew install android-platform-tools"
        echo ""
        echo "  Or download from: https://developer.android.com/studio/releases/platform-tools"
        exit 1
    fi

    # Verify installation
    if command -v adb &> /dev/null; then
        echo -e "${GREEN}  ✓ ADB installed successfully!${NC}"
    else
        echo -e "${RED}  ✗ ADB installation failed${NC}"
        exit 1
    fi
}

#-------------------------------------------------------------------------------
# Check APK exists
#-------------------------------------------------------------------------------
check_apk() {
    echo -e "${YELLOW}[2/5] Checking for APK...${NC}"

    if [ -f "$APK_PATH" ]; then
        APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
        echo -e "${GREEN}  ✓ APK found: $APK_PATH ($APK_SIZE)${NC}"
    else
        echo -e "${RED}  ✗ APK not found at: $APK_PATH${NC}"
        echo ""
        echo "  Looking for APK in releases folder..."

        # Try to find any APK
        FOUND_APK=$(find "$PROJECT_DIR/releases" -name "*.apk" 2>/dev/null | head -1)
        if [ -n "$FOUND_APK" ]; then
            APK_PATH="$FOUND_APK"
            echo -e "${GREEN}  ✓ Found: $APK_PATH${NC}"
        else
            echo -e "${RED}  ✗ No APK found in releases folder${NC}"
            echo "  Please build the Android app first or download the release."
            exit 1
        fi
    fi
}

#-------------------------------------------------------------------------------
# Enable USB Debugging prompt
#-------------------------------------------------------------------------------
show_usb_debug_help() {
    echo -e "${YELLOW}"
    echo "╔═══════════════════════════════════════════════════════════════╗"
    echo "║               Enable USB Debugging on Phone                   ║"
    echo "╠═══════════════════════════════════════════════════════════════╣"
    echo "║  1. Open Settings → About Phone                               ║"
    echo "║  2. Tap 'Build Number' 7 times (enables Developer Options)    ║"
    echo "║  3. Go back to Settings → Developer Options                   ║"
    echo "║  4. Enable 'USB Debugging'                                    ║"
    echo "║  5. Connect phone via USB cable                               ║"
    echo "║  6. When prompted, tap 'Allow USB debugging'                  ║"
    echo "╚═══════════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
}

#-------------------------------------------------------------------------------
# Check for connected devices
#-------------------------------------------------------------------------------
check_device() {
    echo -e "${YELLOW}[3/5] Checking for connected device...${NC}"

    # Start ADB server if not running
    adb start-server 2>/dev/null

    DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | grep "device$")

    if [ -z "$DEVICES" ]; then
        echo -e "${RED}  ✗ No device connected!${NC}"
        show_usb_debug_help

        echo -e "${CYAN}Waiting for device... (Press Ctrl+C to cancel)${NC}"
        adb wait-for-device

        DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | grep "device$")
    fi

    DEVICE_COUNT=$(echo "$DEVICES" | wc -l)

    if [ "$DEVICE_COUNT" -gt 1 ]; then
        echo -e "${YELLOW}  Multiple devices detected:${NC}"
        echo ""
        adb devices -l | grep -v "List"
        echo ""

        # Get list of device serials
        SERIALS=($(adb devices | grep "device$" | cut -f1))

        echo "  Select device (enter number):"
        for i in "${!SERIALS[@]}"; do
            DEVICE_INFO=$(adb -s "${SERIALS[$i]}" shell getprop ro.product.model 2>/dev/null || echo "Unknown")
            echo "    $((i+1)). ${SERIALS[$i]} - $DEVICE_INFO"
        done

        read -p "  Choice [1]: " CHOICE
        CHOICE=${CHOICE:-1}
        DEVICE_SERIAL="${SERIALS[$((CHOICE-1))]}"
    else
        DEVICE_SERIAL=$(echo "$DEVICES" | cut -f1)
    fi

    DEVICE_MODEL=$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.model 2>/dev/null || echo "Unknown")
    ANDROID_VERSION=$(adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.release 2>/dev/null || echo "Unknown")

    echo -e "${GREEN}  ✓ Device: $DEVICE_MODEL (Android $ANDROID_VERSION)${NC}"
    echo -e "${GREEN}  ✓ Serial: $DEVICE_SERIAL${NC}"
}

#-------------------------------------------------------------------------------
# Install APK
#-------------------------------------------------------------------------------
install_apk() {
    echo -e "${YELLOW}[4/5] Installing LaunDRoid...${NC}"

    # Check if already installed
    INSTALLED=$(adb -s "$DEVICE_SERIAL" shell pm list packages 2>/dev/null | grep "com.laundr.droid" || true)

    if [ -n "$INSTALLED" ]; then
        echo "  App already installed - replacing..."
        INSTALL_CMD="adb -s $DEVICE_SERIAL install -r"
    else
        echo "  Fresh install..."
        INSTALL_CMD="adb -s $DEVICE_SERIAL install"
    fi

    echo "  Installing APK (this may take a moment)..."

    if $INSTALL_CMD "$APK_PATH" 2>&1 | grep -q "Success"; then
        echo -e "${GREEN}  ✓ Installation successful!${NC}"
    else
        echo -e "${RED}  ✗ Installation failed!${NC}"
        echo ""
        echo "  Troubleshooting:"
        echo "    - Ensure USB debugging is enabled"
        echo "    - Accept any prompts on your phone"
        echo "    - Try: adb uninstall com.laundr.droid"
        exit 1
    fi
}

#-------------------------------------------------------------------------------
# Launch app
#-------------------------------------------------------------------------------
launch_app() {
    echo -e "${YELLOW}[5/5] Launching LaunDRoid...${NC}"

    adb -s "$DEVICE_SERIAL" shell am start -n com.laundr.droid/.MainActivity 2>/dev/null

    echo -e "${GREEN}  ✓ App launched!${NC}"
}

#-------------------------------------------------------------------------------
# Main
#-------------------------------------------------------------------------------
main() {
    check_adb
    check_apk
    check_device
    install_apk
    launch_app

    echo ""
    echo -e "${GREEN}╔═══════════════════════════════════════════════════════════════╗"
    echo -e "║              ${NC}LaunDRoid installed successfully!${GREEN}               ║"
    echo -e "╚═══════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo "  For authorized security research only."
    echo "  CVE-2025-46018 | CVE-2025-46019"
    echo ""
}

main "$@"
