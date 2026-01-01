#!/bin/bash
#
# LaundR Flipper App - Automated Build Script
# This script does EVERYTHING needed to build the Flipper app
#
# Usage:
#   ./build_flipper_app.sh                    # Interactive mode
#   ./build_flipper_app.sh -b                 # Build only (use cached firmware)
#   ./build_flipper_app.sh -d                 # Build and deploy to Flipper
#   ./build_flipper_app.sh -f momentum -v dev # Specify firmware type and version
#   ./build_flipper_app.sh -f momentum -v dev -d  # Full non-interactive build + deploy
#   ./build_flipper_app.sh -c                 # Clean build
#   ./build_flipper_app.sh -h                 # Show help
#
# Flags:
#   -b, --build      Build only using cached firmware (non-interactive)
#   -d, --deploy     Auto-deploy to Flipper if mounted (no prompt)
#   -f, --firmware   Firmware type: official, momentum, unleashed, roguemaster
#   -v, --version    Firmware version (e.g., dev, mntm-005, 1.0.0)
#   -c, --clean      Clean build (remove old artifacts first)
#   -q, --quiet      Quiet mode (less output)
#   -h, --help       Show this help message
#

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Default values
BUILD_ONLY=false
AUTO_DEPLOY=false
FIRMWARE_TYPE=""
SELECTED_VERSION=""
CLEAN_BUILD=false
QUIET_MODE=false
INTERACTIVE=true

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -b|--build)
            BUILD_ONLY=true
            INTERACTIVE=false
            shift
            ;;
        -d|--deploy)
            AUTO_DEPLOY=true
            INTERACTIVE=false
            shift
            ;;
        -f|--firmware)
            FIRMWARE_TYPE="$2"
            INTERACTIVE=false
            shift 2
            ;;
        -v|--version)
            SELECTED_VERSION="$2"
            INTERACTIVE=false
            shift 2
            ;;
        -c|--clean)
            CLEAN_BUILD=true
            shift
            ;;
        -q|--quiet)
            QUIET_MODE=true
            shift
            ;;
        -h|--help)
            echo "LaundR Flipper App - Build Script"
            echo ""
            echo "Usage:"
            echo "  ./build_flipper_app.sh                        # Interactive mode"
            echo "  ./build_flipper_app.sh -b                     # Build only (use cached firmware)"
            echo "  ./build_flipper_app.sh -d                     # Build and deploy to Flipper"
            echo "  ./build_flipper_app.sh -f momentum -v dev     # Specify firmware"
            echo "  ./build_flipper_app.sh -f momentum -v dev -d  # Full non-interactive + deploy"
            echo "  ./build_flipper_app.sh -c                     # Clean build"
            echo ""
            echo "Flags:"
            echo "  -b, --build      Build using cached firmware (non-interactive)"
            echo "  -d, --deploy     Auto-deploy to Flipper if mounted (no prompt)"
            echo "  -f, --firmware   Firmware type: official, momentum, unleashed, roguemaster"
            echo "  -v, --version    Firmware version (e.g., dev, mntm-005, 1.0.0)"
            echo "  -c, --clean      Clean build (remove old artifacts first)"
            echo "  -q, --quiet      Quiet mode (less output)"
            echo "  -h, --help       Show this help message"
            echo ""
            echo "Examples:"
            echo "  # Quick rebuild with current cached firmware:"
            echo "  ./build_flipper_app.sh -b"
            echo ""
            echo "  # Build with Momentum dev and deploy:"
            echo "  ./build_flipper_app.sh -f momentum -v dev -d"
            echo ""
            echo "  # Clean build with deployment:"
            echo "  ./build_flipper_app.sh -b -c -d"
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            echo "Use -h or --help for usage information"
            exit 1
            ;;
    esac
done

# Function for conditional output
log() {
    if [ "$QUIET_MODE" = false ]; then
        echo -e "$@"
    fi
}

log "${BLUE}╔════════════════════════════════════════════════╗${NC}"
log "${BLUE}║  LaundR Flipper App - Automated Builder       ║${NC}"
log "${BLUE}╚════════════════════════════════════════════════╝${NC}"
log ""

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
LAUNDR_ROOT="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$LAUNDR_ROOT/flipper_build"
OUTPUT_DIR="$LAUNDR_ROOT/flipper_app/output"
VERSION_FILE="$BUILD_DIR/.current_fw_version"
TYPE_FILE="$BUILD_DIR/.current_fw_type"

# Firmware types and their repos
declare -A FIRMWARE_REPOS=(
    ["official"]="https://github.com/flipperdevices/flipperzero-firmware.git"
    ["momentum"]="https://github.com/Next-Flip/Momentum-Firmware.git"
    ["unleashed"]="https://github.com/DarkFlippers/unleashed-firmware.git"
    ["roguemaster"]="https://github.com/RogueMaster/flipperzero-firmware-wPlugins.git"
)

declare -A FIRMWARE_API=(
    ["official"]="https://api.github.com/repos/flipperdevices/flipperzero-firmware"
    ["momentum"]="https://api.github.com/repos/Next-Flip/Momentum-Firmware"
    ["unleashed"]="https://api.github.com/repos/DarkFlippers/unleashed-firmware"
    ["roguemaster"]="https://api.github.com/repos/RogueMaster/flipperzero-firmware-wPlugins"
)

log "${YELLOW}Configuration:${NC}"
log "  LaundR root: $LAUNDR_ROOT"
log "  Build directory: $BUILD_DIR"
log "  Output directory: $OUTPUT_DIR"
log ""

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to fetch versions for a specific firmware type
fetch_versions_for_type() {
    local fw_type=$1
    local api_url="${FIRMWARE_API[$fw_type]}"

    # Official uses tags, custom firmwares use releases
    if [ "$fw_type" == "official" ]; then
        curl -s --max-time 10 "$api_url/tags" 2>/dev/null | \
            grep '"name":' | \
            head -n 3 | \
            sed 's/.*"name": "\(.*\)".*/\1/'
    else
        curl -s --max-time 10 "$api_url/releases" 2>/dev/null | \
            grep '"tag_name":' | \
            head -n 3 | \
            sed 's/.*"tag_name": "\(.*\)".*/\1/'
    fi
}

# Function to select firmware type and version in one menu
select_firmware() {
    log "${CYAN}Fetching available firmware versions...${NC}"
    log ""

    # Fetch versions for all firmware types
    OFFICIAL_VERSIONS=$(fetch_versions_for_type "official")
    MOMENTUM_VERSIONS=$(fetch_versions_for_type "momentum")
    UNLEASHED_VERSIONS=$(fetch_versions_for_type "unleashed")
    ROGUEMASTER_VERSIONS=$(fetch_versions_for_type "roguemaster")

    # Get current configuration if exists
    CURRENT_TYPE=""
    CURRENT_VERSION=""
    if [ -f "$TYPE_FILE" ] && [ -f "$VERSION_FILE" ]; then
        CURRENT_TYPE=$(cat "$TYPE_FILE")
        CURRENT_VERSION=$(cat "$VERSION_FILE")
    fi

    # Display unified menu
    log "${BLUE}╔════════════════════════════════════════════════╗${NC}"
    log "${BLUE}║        Select Firmware & Version              ║${NC}"
    log "${BLUE}╚════════════════════════════════════════════════╝${NC}"
    log ""

    if [ -n "$CURRENT_TYPE" ] && [ -n "$CURRENT_VERSION" ]; then
        log "${YELLOW}Currently: $CURRENT_TYPE @ $CURRENT_VERSION${NC}"
        log ""
    fi

    # Build menu arrays (using associative arrays for dynamic indexing)
    declare -A MENU_TYPES
    declare -A MENU_VERSIONS
    declare -A MENU_LABELS
    idx=1

    # Official Flipper
    log "${GREEN}Official Flipper Zero:${NC}"
    if [ -n "$OFFICIAL_VERSIONS" ]; then
        mapfile -t OFF_ARRAY <<< "$OFFICIAL_VERSIONS"
        for ver in "${OFF_ARRAY[@]}"; do
            if [ -n "$ver" ]; then
                MENU_TYPES[$idx]="official"
                MENU_VERSIONS[$idx]="$ver"
                MENU_LABELS[$idx]="Official $ver"
                log "  ${CYAN}[$idx]${NC} $ver"
                ((idx++))
            fi
        done
    else
        log "  ${RED}(Could not fetch)${NC}"
    fi
    log ""

    # Momentum
    log "${GREEN}Momentum Firmware:${NC}"
    # Add dev branch option first
    MENU_TYPES[$idx]="momentum"
    MENU_VERSIONS[$idx]="dev"
    MENU_LABELS[$idx]="Momentum Latest Dev (dev branch)"
    log "  ${CYAN}[$idx]${NC} Latest Dev (dev branch) ${YELLOW}[API 87+]${NC}"
    ((idx++))

    if [ -n "$MOMENTUM_VERSIONS" ]; then
        mapfile -t MTM_ARRAY <<< "$MOMENTUM_VERSIONS"
        for ver in "${MTM_ARRAY[@]}"; do
            if [ -n "$ver" ]; then
                MENU_TYPES[$idx]="momentum"
                MENU_VERSIONS[$idx]="$ver"
                MENU_LABELS[$idx]="Momentum $ver"
                log "  ${CYAN}[$idx]${NC} $ver"
                ((idx++))
            fi
        done
    else
        log "  ${RED}(Could not fetch)${NC}"
    fi
    log ""

    # Unleashed
    log "${GREEN}Unleashed Firmware:${NC}"
    if [ -n "$UNLEASHED_VERSIONS" ]; then
        mapfile -t UNL_ARRAY <<< "$UNLEASHED_VERSIONS"
        for ver in "${UNL_ARRAY[@]}"; do
            if [ -n "$ver" ]; then
                MENU_TYPES[$idx]="unleashed"
                MENU_VERSIONS[$idx]="$ver"
                MENU_LABELS[$idx]="Unleashed $ver"
                log "  ${CYAN}[$idx]${NC} $ver"
                ((idx++))
            fi
        done
    else
        log "  ${RED}(Could not fetch)${NC}"
    fi
    log ""

    # RogueMaster
    log "${GREEN}RogueMaster Firmware:${NC}"
    if [ -n "$ROGUEMASTER_VERSIONS" ]; then
        mapfile -t RM_ARRAY <<< "$ROGUEMASTER_VERSIONS"
        for ver in "${RM_ARRAY[@]}"; do
            if [ -n "$ver" ]; then
                MENU_TYPES[$idx]="roguemaster"
                MENU_VERSIONS[$idx]="$ver"
                MENU_LABELS[$idx]="RogueMaster $ver"
                # Truncate long RogueMaster version names
                if [ ${#ver} -gt 30 ]; then
                    log "  ${CYAN}[$idx]${NC} ${ver:0:30}..."
                else
                    log "  ${CYAN}[$idx]${NC} $ver"
                fi
                ((idx++))
            fi
        done
    else
        log "  ${RED}(Could not fetch)${NC}"
    fi
    log ""

    max_choice=$((idx-1))

    # Ask user to select
    while true; do
        read -p "Select firmware (1-$max_choice): " choice

        if [[ "$choice" =~ ^[0-9]+$ ]] && [ "$choice" -ge 1 ] && [ "$choice" -le "$max_choice" ]; then
            FIRMWARE_TYPE="${MENU_TYPES[$choice]}"
            SELECTED_VERSION="${MENU_VERSIONS[$choice]}"

            log ""
            log "${GREEN}✓ Selected: ${MENU_LABELS[$choice]}${NC}"
            log ""
            break
        else
            log "${RED}Invalid choice. Please enter a number between 1 and $max_choice${NC}"
        fi
    done
}

# Step 1: Check prerequisites
log "${BLUE}[1/6] Checking prerequisites...${NC}"

if ! command_exists git; then
    echo -e "${RED}ERROR: git is not installed${NC}"
    echo "Install with: sudo apt install git"
    exit 1
fi

if ! command_exists python3; then
    echo -e "${RED}ERROR: python3 is not installed${NC}"
    echo "Install with: sudo apt install python3"
    exit 1
fi

if ! command_exists curl; then
    echo -e "${RED}ERROR: curl is not installed${NC}"
    echo "Install with: sudo apt install curl"
    exit 1
fi

log "${GREEN}✓ Prerequisites OK${NC}"
log ""

# Step 2: Select firmware and version (or use provided/cached)
log "${BLUE}[2/6] Select firmware and version...${NC}"
log ""

# Non-interactive mode: use flags or cached values
if [ "$INTERACTIVE" = false ]; then
    # If build-only mode or firmware not specified, use cached
    if [ -z "$FIRMWARE_TYPE" ] || [ -z "$SELECTED_VERSION" ]; then
        if [ -f "$TYPE_FILE" ] && [ -f "$VERSION_FILE" ]; then
            if [ -z "$FIRMWARE_TYPE" ]; then
                FIRMWARE_TYPE=$(cat "$TYPE_FILE")
            fi
            if [ -z "$SELECTED_VERSION" ]; then
                SELECTED_VERSION=$(cat "$VERSION_FILE")
            fi
            log "${GREEN}Using cached firmware: $FIRMWARE_TYPE @ $SELECTED_VERSION${NC}"
        else
            echo -e "${RED}ERROR: No cached firmware and no -f/-v specified${NC}"
            echo "Run interactively first or specify -f and -v flags"
            exit 1
        fi
    else
        log "${GREEN}Using specified firmware: $FIRMWARE_TYPE @ $SELECTED_VERSION${NC}"
    fi
    log ""
else
    # Interactive mode
    select_firmware
fi

# Validate firmware type
if [ -z "${FIRMWARE_REPOS[$FIRMWARE_TYPE]}" ]; then
    echo -e "${RED}ERROR: Invalid firmware type: $FIRMWARE_TYPE${NC}"
    echo "Valid types: official, momentum, unleashed, roguemaster"
    exit 1
fi

# Step 3: Create build directory
log "${BLUE}[3/6] Creating build directory...${NC}"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"
log "${GREEN}✓ Build directory ready${NC}"
log ""

# Step 4: Clone or update firmware (smart caching)
log "${BLUE}[4/6] Getting $FIRMWARE_TYPE firmware...${NC}"

# Set firmware directory based on type
FIRMWARE_DIR="$BUILD_DIR/$FIRMWARE_TYPE-firmware"
FIRMWARE_REPO="${FIRMWARE_REPOS[$FIRMWARE_TYPE]}"

NEEDS_DOWNLOAD=false
CURRENT_VERSION=""
CURRENT_TYPE=""

# Check if firmware directory exists
if [ -d "$FIRMWARE_DIR" ]; then
    # Check current type and version
    if [ -f "$TYPE_FILE" ] && [ -f "$VERSION_FILE" ]; then
        CURRENT_TYPE=$(cat "$TYPE_FILE")
        CURRENT_VERSION=$(cat "$VERSION_FILE")
        log "Current firmware: $CURRENT_TYPE @ $CURRENT_VERSION"

        if [ "$CURRENT_TYPE" == "$FIRMWARE_TYPE" ] && [ "$CURRENT_VERSION" == "$SELECTED_VERSION" ]; then
            log "${GREEN}✓ Firmware already at desired version!${NC}"
            log "Skipping download (saving time and bandwidth)"
            NEEDS_DOWNLOAD=false
        else
            if [ "$CURRENT_TYPE" != "$FIRMWARE_TYPE" ]; then
                log "${YELLOW}Firmware type changed${NC}"
                log "  Current: $CURRENT_TYPE"
                log "  Desired: $FIRMWARE_TYPE"
            else
                log "${YELLOW}Version mismatch detected${NC}"
                log "  Current: $CURRENT_VERSION"
                log "  Desired: $SELECTED_VERSION"
            fi
            NEEDS_DOWNLOAD=true
        fi
    else
        log "${YELLOW}No version/type file found${NC}"
        NEEDS_DOWNLOAD=true
    fi
else
    log "No firmware found"
    NEEDS_DOWNLOAD=true
fi

# Download/update firmware if needed
if [ "$NEEDS_DOWNLOAD" = true ]; then
    log ""
    log "${YELLOW}Downloading $FIRMWARE_TYPE firmware version: $SELECTED_VERSION${NC}"
    log "${YELLOW}Note: This downloads ~1-2 GB (one-time per version)${NC}"
    log ""

    # Remove old firmware if exists
    if [ -d "$FIRMWARE_DIR" ]; then
        log "Removing old firmware..."
        rm -rf "$FIRMWARE_DIR"
    fi

    # Clone firmware
    log "Cloning $FIRMWARE_TYPE firmware..."
    git clone "$FIRMWARE_REPO" "$FIRMWARE_DIR"

    cd "$FIRMWARE_DIR"

    # Checkout specific version if selected
    if [ -n "$SELECTED_VERSION" ]; then
        if [ "$SELECTED_VERSION" == "dev" ]; then
            log "Checking out latest dev branch (dev)..."
            git checkout dev
            git pull origin dev
        else
            log "Checking out version: $SELECTED_VERSION"
            git checkout "$SELECTED_VERSION"
        fi
    fi

    # Update submodules
    log "Updating submodules..."
    git submodule update --init --recursive

    # Save current version and type
    echo "$SELECTED_VERSION" > "$VERSION_FILE"
    echo "$FIRMWARE_TYPE" > "$TYPE_FILE"
    log "${GREEN}✓ Firmware downloaded and cached${NC}"
else
    cd "$FIRMWARE_DIR"
fi

log ""
log "${GREEN}✓ Firmware ready${NC}"
log ""

# Step 5: Copy LaundR app to firmware
log "${BLUE}[5/6] Installing LaundR app into firmware...${NC}"

# Create applications_user directory if it doesn't exist
mkdir -p "$FIRMWARE_DIR/applications_user"

# Copy LaundR app - ONLY essential files
if [ -d "$FIRMWARE_DIR/applications_user/laundr" ]; then
    log "Removing old LaundR app..."
    if ! rm -rf "$FIRMWARE_DIR/applications_user/laundr" 2>/dev/null; then
        echo -e "${RED}ERROR: Cannot remove old LaundR directory (permission denied)${NC}"
        echo -e "${YELLOW}This usually happens if you previously ran the script as root/sudo.${NC}"
        echo ""
        echo "Please fix permissions manually with one of these commands:"
        echo -e "${CYAN}  sudo rm -rf \"$FIRMWARE_DIR/applications_user/laundr\"${NC}"
        echo -e "${CYAN}  sudo chown -R \$USER:\$USER \"$FIRMWARE_DIR\"${NC}"
        echo ""
        echo "Then run this script again (without sudo)."
        exit 1
    fi
fi

log "Copying LaundR app files (source, manifest, icon only)..."
mkdir -p "$FIRMWARE_DIR/applications_user/laundr"

# Copy ONLY the essential files needed for building
cp "$SCRIPT_DIR/laundr_simple.c" "$FIRMWARE_DIR/applications_user/laundr/"
cp "$SCRIPT_DIR/application.fam" "$FIRMWARE_DIR/applications_user/laundr/"
cp "$SCRIPT_DIR/laundr.png" "$FIRMWARE_DIR/applications_user/laundr/"

log "Files copied:"
if [ "$QUIET_MODE" = false ]; then
    ls -lh "$FIRMWARE_DIR/applications_user/laundr/"
fi

log "${GREEN}✓ LaundR app installed${NC}"
log ""

# Step 6: Build the app and copy output
log "${BLUE}[6/6] Building LaundR app...${NC}"
log "${YELLOW}This may take a few minutes on first build...${NC}"
log ""

cd "$FIRMWARE_DIR"

# Clean old build artifacts if requested
if [ "$CLEAN_BUILD" = true ]; then
    log "Cleaning old build artifacts..."
    ./fbt -c fap_laundr 2>&1 | grep -v "warning:" || true
fi

# Run the build
log "Building LaundR FAP..."
if [ "$QUIET_MODE" = true ]; then
    ./fbt fap_laundr 2>&1 | tail -10
else
    ./fbt fap_laundr 2>&1
fi

BUILD_EXIT_CODE=${PIPESTATUS[0]}

if [ $BUILD_EXIT_CODE -eq 0 ]; then
    log ""
    log "${GREEN}✓ Build successful!${NC}"
else
    echo ""
    echo -e "${RED}✗ Build failed${NC}"
    echo "Check the output above for errors"
    exit 1
fi

log ""
log "${BLUE}Copying output files...${NC}"

mkdir -p "$OUTPUT_DIR"

# Find the built .fap file
FAP_FILE=$(find "$FIRMWARE_DIR/build" -name "laundr.fap" -type f | head -n 1)

if [ -z "$FAP_FILE" ]; then
    echo -e "${RED}ERROR: Could not find laundr.fap${NC}"
    echo "Build completed but output file not found"
    exit 1
fi

# Copy the .fap file
cp "$FAP_FILE" "$OUTPUT_DIR/laundr.fap"

# Get file size
FAP_SIZE=$(du -h "$OUTPUT_DIR/laundr.fap" | cut -f1)

log "${GREEN}✓ Output copied${NC}"
log ""

# Try to auto-deploy to Flipper if mounted
FLIPPER_MOUNTED=false
FLIPPER_PATH=""

# Check common mount points
for path in /run/media/$USER/Flipper*/apps/NFC /media/$USER/Flipper*/apps/NFC /mnt/Flipper*/apps/NFC; do
    if [ -d "$path" ]; then
        FLIPPER_MOUNTED=true
        FLIPPER_PATH="$path"
        break
    fi
done

if [ "$FLIPPER_MOUNTED" = true ]; then
    log "${YELLOW}Flipper Zero detected at: $FLIPPER_PATH${NC}"

    if [ "$AUTO_DEPLOY" = true ]; then
        # Non-interactive deploy
        log "Auto-deploying to Flipper..."
        cp "$OUTPUT_DIR/laundr.fap" "$FLIPPER_PATH/laundr.fap"
        sync
        log "${GREEN}✓ Deployed to Flipper!${NC}"
        log "${YELLOW}You can now run: Apps → NFC → LaundR${NC}"
        log ""
    elif [ "$INTERACTIVE" = true ]; then
        # Interactive deploy prompt
        read -p "Auto-deploy to Flipper? [Y/n]: " deploy_choice
        deploy_choice=${deploy_choice:-Y}

        if [[ "$deploy_choice" =~ ^[Yy]$ ]]; then
            log "Copying to Flipper..."
            cp "$OUTPUT_DIR/laundr.fap" "$FLIPPER_PATH/laundr.fap"
            sync
            log "${GREEN}✓ Deployed to Flipper!${NC}"
            log "${YELLOW}You can now run: Apps → NFC → LaundR${NC}"
            log ""
        fi
    else
        log "${CYAN}Flipper detected but -d not specified. Skipping deploy.${NC}"
    fi
elif [ "$AUTO_DEPLOY" = true ]; then
    log "${YELLOW}Warning: -d specified but Flipper not mounted${NC}"
fi

# Success summary
log "${GREEN}╔════════════════════════════════════════════════╗${NC}"
log "${GREEN}║           BUILD SUCCESSFUL!                    ║${NC}"
log "${GREEN}╚════════════════════════════════════════════════╝${NC}"
log ""
log "${BLUE}Firmware Type:${NC} $FIRMWARE_TYPE"
log "${BLUE}Firmware Version:${NC} $SELECTED_VERSION"
log "${BLUE}Output file:${NC}"
log "  $OUTPUT_DIR/laundr.fap"
log "  Size: $FAP_SIZE"
log ""

if [ "$QUIET_MODE" = false ]; then
    log "${BLUE}Next steps:${NC}"
    log "  1. Copy laundr.fap to your Flipper Zero SD card"
    log "  2. Location: SD:/apps/NFC/laundr.fap"
    log "  3. Restart Flipper (optional)"
    log "  4. Run: Apps → NFC → LaundR"
    log ""
    log "${YELLOW}Quick rebuild:${NC}"
    log "  ./build_flipper_app.sh -b        # Rebuild with cached firmware"
    log "  ./build_flipper_app.sh -b -d     # Rebuild and deploy"
    log ""
fi

log "${GREEN}Done!${NC}"
