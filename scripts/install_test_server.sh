#!/bin/bash
#
# install_test_server.sh
# Automated installation and setup of NeoForge test server for Micro-Biome-Replacer mod
#
# Usage:
#   ./install_test_server.sh [OPTIONS]
#
# Options:
#   --yes, -y              Skip all confirmation prompts
#   --skip-mcrcon          Skip mcrcon installation
#   --skip-async-profiler  Skip async-profiler installation
#   --skip-verify          Skip server startup verification
#   --help, -h             Show this help message
#

set -e  # Exit on error

# ============================================================================
# Configuration
# ============================================================================

NEOFORGE_VERSION="21.1.216"
MINECRAFT_VERSION="1.21.1"
NEOFORGE_INSTALLER_URL="https://maven.neoforged.net/releases/net/neoforged/neoforge/${NEOFORGE_VERSION}/neoforge-${NEOFORGE_VERSION}-installer.jar"

# Paths (relative to project root)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TEST_SERVER_DIR="${PROJECT_ROOT}/test_server"
TEST_TOOLS_DIR="${PROJECT_ROOT}/test_tools"
MOD_BUILD_DIR="${PROJECT_ROOT}/build/libs"

# Server configuration
RCON_PORT="25575"
RCON_PASSWORD="test_password_12345"
SERVER_PORT="25565"
LEVEL_SEED="-5704795430421488525"

# Tool versions and URLs
MCRCON_REPO="https://github.com/Tiiffi/mcrcon.git"
ASYNC_PROFILER_VERSION="3.0"
ASYNC_PROFILER_URL="https://github.com/async-profiler/async-profiler/releases/download/v${ASYNC_PROFILER_VERSION}/async-profiler-${ASYNC_PROFILER_VERSION}-linux-x64.tar.gz"

# Timeouts (seconds)
DOWNLOAD_TIMEOUT=300
INSTALL_TIMEOUT=600
SERVER_START_TIMEOUT=180

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ============================================================================
# Helper Functions
# ============================================================================

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[OK]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

show_help() {
    sed -n '3,14p' "$0" | sed 's/^# \?//'
    exit 0
}

confirm() {
    local prompt="$1"
    if [[ "$AUTO_YES" == "true" ]]; then
        return 0
    fi
    read -p "$prompt [y/N] " -n 1 -r
    echo
    [[ $REPLY =~ ^[Yy]$ ]]
}

cleanup_on_error() {
    log_error "Installation failed. Cleaning up..."
    if [[ -d "$TEST_SERVER_DIR" ]] && [[ "$CLEANUP_ON_FAIL" == "true" ]]; then
        rm -rf "$TEST_SERVER_DIR"
        log_info "Removed incomplete test_server directory"
    fi
    exit 1
}

# ============================================================================
# Prerequisite Checks
# ============================================================================

check_java() {
    log_info "Checking Java installation..."

    if ! command -v java &> /dev/null; then
        log_error "Java is not installed or not in PATH"
        log_error "Please install Java 21 or later"
        exit 1
    fi

    # Get Java version
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)

    if [[ "$JAVA_VERSION" -lt 21 ]]; then
        log_error "Java 21 or later is required (found: Java $JAVA_VERSION)"
        exit 1
    fi

    log_success "Java $JAVA_VERSION found"
}

check_internet() {
    log_info "Checking internet connectivity..."

    if ! curl -s --connect-timeout 10 "https://maven.neoforged.net" > /dev/null 2>&1; then
        log_error "Cannot reach maven.neoforged.net"
        log_error "Please check your internet connection"
        exit 1
    fi

    log_success "Internet connection available"
}

check_existing_installation() {
    if [[ -d "$TEST_SERVER_DIR" ]]; then
        log_warn "Test server directory already exists at: $TEST_SERVER_DIR"
        if confirm "Remove existing installation and start fresh?"; then
            log_info "Removing existing installation..."
            rm -rf "$TEST_SERVER_DIR"
        else
            log_error "Installation cancelled"
            exit 1
        fi
    fi
}

check_ports() {
    log_info "Checking if required ports are available..."

    local ports_in_use=false

    if ss -tlnp 2>/dev/null | grep -q ":${SERVER_PORT} " || \
       netstat -tlnp 2>/dev/null | grep -q ":${SERVER_PORT} "; then
        log_warn "Port $SERVER_PORT (Minecraft) is already in use"
        ports_in_use=true
    fi

    if ss -tlnp 2>/dev/null | grep -q ":${RCON_PORT} " || \
       netstat -tlnp 2>/dev/null | grep -q ":${RCON_PORT} "; then
        log_warn "Port $RCON_PORT (RCON) is already in use"
        ports_in_use=true
    fi

    if [[ "$ports_in_use" == "true" ]]; then
        log_warn "Some ports are in use. Server may fail to start."
        log_warn "Run 'scripts/uninstall_test_server.sh' to clean up zombie processes"
    else
        log_success "Required ports are available"
    fi
}

# ============================================================================
# Installation Steps
# ============================================================================

create_directory_structure() {
    log_info "Creating directory structure..."

    mkdir -p "$TEST_SERVER_DIR"/{mods,config,logs}

    log_success "Created test_server directory structure"
}

download_neoforge_installer() {
    log_info "Downloading NeoForge ${NEOFORGE_VERSION} installer..."

    local installer_jar="${TEST_SERVER_DIR}/neoforge-installer.jar"

    if ! curl -L --connect-timeout 30 --max-time "$DOWNLOAD_TIMEOUT" \
         -o "$installer_jar" \
         "$NEOFORGE_INSTALLER_URL" 2>/dev/null; then
        log_error "Failed to download NeoForge installer"
        cleanup_on_error
    fi

    # Verify the file was downloaded and is a valid JAR
    if [[ ! -f "$installer_jar" ]] || [[ ! -s "$installer_jar" ]]; then
        log_error "Downloaded installer is empty or missing"
        cleanup_on_error
    fi

    # Basic JAR validation (check for PK header - ZIP format)
    if ! head -c 2 "$installer_jar" | grep -q "PK"; then
        log_error "Downloaded file is not a valid JAR/ZIP file"
        cleanup_on_error
    fi

    log_success "Downloaded NeoForge installer"
}

run_neoforge_installer() {
    log_info "Running NeoForge server installer (this may take a few minutes)..."

    local installer_jar="${TEST_SERVER_DIR}/neoforge-installer.jar"

    cd "$TEST_SERVER_DIR"

    # Run installer with timeout
    if ! timeout "$INSTALL_TIMEOUT" java -jar "$installer_jar" --installServer 2>&1 | \
         while IFS= read -r line; do
             # Show progress dots for long operations
             if [[ "$line" == *"Downloading"* ]] || [[ "$line" == *"Installing"* ]]; then
                 echo -n "."
             fi
         done; then
        echo  # newline after dots
        log_error "NeoForge installer failed or timed out"
        cleanup_on_error
    fi
    echo  # newline after dots

    # Verify installation
    if [[ ! -f "${TEST_SERVER_DIR}/run.sh" ]]; then
        log_error "NeoForge installation incomplete - run.sh not found"
        cleanup_on_error
    fi

    # Make run.sh executable
    chmod +x "${TEST_SERVER_DIR}/run.sh"

    # Clean up installer
    rm -f "$installer_jar"
    rm -f "${TEST_SERVER_DIR}/installer.log"

    log_success "NeoForge server installed"
}

create_server_properties() {
    log_info "Creating server.properties..."

    cat > "${TEST_SERVER_DIR}/server.properties" << EOF
# Minecraft Server Properties - Test Configuration
# Generated by install_test_server.sh

# Network
server-port=${SERVER_PORT}
server-ip=
query.port=${SERVER_PORT}

# RCON - Required for test harness
enable-rcon=true
rcon.port=${RCON_PORT}
rcon.password=${RCON_PASSWORD}

# World Generation
level-seed=${LEVEL_SEED}
level-type=minecraft\:normal
level-name=world
generate-structures=true

# Performance - Optimized for testing
view-distance=5
simulation-distance=5
sync-chunk-writes=false
max-tick-time=-1

# Security - Relaxed for testing
online-mode=false
spawn-protection=0
white-list=false
enforce-whitelist=false

# Players
max-players=1
gamemode=creative
difficulty=peaceful
pvp=false

# Misc
motd=MicroBiome Test Server
enable-status=true
enable-query=false
enable-jmx-monitoring=false
allow-flight=true
broadcast-console-to-ops=true
broadcast-rcon-to-ops=false
EOF

    log_success "Created server.properties"
}

create_jvm_args() {
    log_info "Creating user_jvm_args.txt..."

    cat > "${TEST_SERVER_DIR}/user_jvm_args.txt" << 'EOF'
# JVM Arguments for Test Server
# Optimized for chunk generation testing

# Memory allocation - fixed heap prevents resize pauses
-Xms8G
-Xmx8G

# Garbage Collection - G1GC with predictable pauses
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:+UnlockExperimentalVMOptions
-XX:+DisableExplicitGC
-XX:+AlwaysPreTouch

# Headless mode - critical for CI environments
-Djava.awt.headless=true

# Performance tuning
-XX:+ParallelRefProcEnabled
-XX:G1HeapRegionSize=8M
-XX:G1NewSizePercent=40
-XX:G1MaxNewSizePercent=50
-XX:G1ReservePercent=15
EOF

    log_success "Created user_jvm_args.txt"
}

create_eula() {
    log_info "Creating eula.txt..."

    cat > "${TEST_SERVER_DIR}/eula.txt" << EOF
# By setting eula=true you agree to the Minecraft EULA
# https://www.minecraft.net/eula
# Generated by install_test_server.sh
eula=true
EOF

    log_success "Created eula.txt (EULA accepted)"
}

install_mcrcon() {
    if [[ "$SKIP_MCRCON" == "true" ]]; then
        log_info "Skipping mcrcon installation (--skip-mcrcon)"
        return
    fi

    log_info "Checking for mcrcon..."

    # Check if already installed in test_tools
    if [[ -x "${TEST_TOOLS_DIR}/mcrcon" ]]; then
        log_success "mcrcon already installed in test_tools/"
        return
    fi

    # Check if available system-wide
    if command -v mcrcon &> /dev/null; then
        log_success "mcrcon already installed system-wide"
        return
    fi

    # Compile from source
    log_info "Compiling mcrcon from source..."

    # Ensure test_tools directory exists
    mkdir -p "$TEST_TOOLS_DIR"

    local mcrcon_build_dir="${TEST_TOOLS_DIR}/.mcrcon_build"
    mkdir -p "$mcrcon_build_dir"

    # Check for git and gcc
    if ! command -v git &> /dev/null; then
        log_error "git is required to compile mcrcon"
        log_error "Install with: sudo apt-get install git"
        return 1
    fi

    if ! command -v gcc &> /dev/null; then
        log_error "gcc is required to compile mcrcon"
        log_error "Install with: sudo apt-get install build-essential"
        return 1
    fi

    cd "$mcrcon_build_dir"

    # Clone repository
    if ! git clone --depth 1 "$MCRCON_REPO" . 2>/dev/null; then
        log_error "Failed to clone mcrcon repository"
        rm -rf "$mcrcon_build_dir"
        return 1
    fi

    # Compile
    if ! gcc -std=gnu11 -pedantic -Wall -Wextra -O2 -s -o mcrcon mcrcon.c 2>/dev/null; then
        log_error "Failed to compile mcrcon"
        rm -rf "$mcrcon_build_dir"
        return 1
    fi

    # Install to test_tools directory
    cp mcrcon "${TEST_TOOLS_DIR}/mcrcon"
    chmod +x "${TEST_TOOLS_DIR}/mcrcon"

    # Create marker file to indicate we installed it
    touch "${TEST_TOOLS_DIR}/.mcrcon_installed_by_script"

    # Clean up build directory
    cd "$PROJECT_ROOT"
    rm -rf "$mcrcon_build_dir"

    log_success "mcrcon compiled and installed to test_tools/mcrcon"
    log_info "Use: ./test_tools/mcrcon -H localhost -P ${RCON_PORT} -p ${RCON_PASSWORD} 'command'"
}

install_async_profiler() {
    if [[ "$SKIP_ASYNC_PROFILER" == "true" ]]; then
        log_info "Skipping async-profiler installation (--skip-async-profiler)"
        return
    fi

    log_info "Checking for async-profiler..."

    # Check if already installed
    if [[ -x "${TEST_TOOLS_DIR}/async-profiler/bin/asprof" ]]; then
        log_success "async-profiler already installed in test_tools/"
        return
    fi

    log_info "Downloading async-profiler ${ASYNC_PROFILER_VERSION}..."

    # Ensure test_tools directory exists
    mkdir -p "$TEST_TOOLS_DIR"

    local tarball="${TEST_TOOLS_DIR}/async-profiler.tar.gz"

    # Download the tarball
    if ! curl -L --connect-timeout 30 --max-time "$DOWNLOAD_TIMEOUT" \
         -o "$tarball" \
         "$ASYNC_PROFILER_URL" 2>/dev/null; then
        log_error "Failed to download async-profiler"
        return 1
    fi

    # Verify download
    if [[ ! -f "$tarball" ]] || [[ ! -s "$tarball" ]]; then
        log_error "Downloaded async-profiler archive is empty or missing"
        return 1
    fi

    # Extract to test_tools/async-profiler
    log_info "Extracting async-profiler..."
    mkdir -p "${TEST_TOOLS_DIR}/async-profiler"
    if ! tar -xzf "$tarball" -C "${TEST_TOOLS_DIR}/async-profiler" --strip-components=1 2>/dev/null; then
        log_error "Failed to extract async-profiler"
        rm -f "$tarball"
        rm -rf "${TEST_TOOLS_DIR}/async-profiler"
        return 1
    fi

    # Clean up tarball
    rm -f "$tarball"

    # Make binaries executable
    chmod +x "${TEST_TOOLS_DIR}/async-profiler/bin/asprof" 2>/dev/null || true

    # Create marker file
    touch "${TEST_TOOLS_DIR}/.async_profiler_installed_by_script"

    log_success "async-profiler ${ASYNC_PROFILER_VERSION} installed to test_tools/async-profiler/"
    log_info "Use: ./test_tools/async-profiler/bin/asprof <pid> [options]"
}

copy_mod_jar() {
    log_info "Looking for mod JAR..."

    if [[ ! -d "$MOD_BUILD_DIR" ]]; then
        log_warn "Build directory not found. Run './gradlew build' first to build the mod."
        return
    fi

    # Find the latest mod JAR (exclude sources and javadoc)
    local mod_jar
    mod_jar=$(find "$MOD_BUILD_DIR" -name "*.jar" \
              ! -name "*-sources.jar" \
              ! -name "*-javadoc.jar" \
              -type f -printf '%T@ %p\n' 2>/dev/null | \
              sort -n | tail -1 | cut -d' ' -f2-)

    if [[ -z "$mod_jar" ]] || [[ ! -f "$mod_jar" ]]; then
        log_warn "No mod JAR found. Run './gradlew build' to build the mod."
        return
    fi

    cp "$mod_jar" "${TEST_SERVER_DIR}/mods/"
    log_success "Copied mod JAR: $(basename "$mod_jar")"
}

verify_installation() {
    if [[ "$SKIP_VERIFY" == "true" ]]; then
        log_info "Skipping server verification (--skip-verify)"
        return
    fi

    log_info "Verifying installation (brief server startup test)..."

    cd "$TEST_SERVER_DIR"

    # Start server in background
    ./run.sh nogui > "${TEST_SERVER_DIR}/logs/startup_test.log" 2>&1 &
    local server_pid=$!

    # Wait for server to start (or timeout)
    local elapsed=0
    local ready=false

    while [[ $elapsed -lt 120 ]]; do
        sleep 5
        elapsed=$((elapsed + 5))

        # Check if server crashed
        if ! kill -0 "$server_pid" 2>/dev/null; then
            log_error "Server process exited unexpectedly"
            log_error "Check ${TEST_SERVER_DIR}/logs/startup_test.log for details"
            return 1
        fi

        # Check for ready message
        if grep -q "Done ([0-9.]*s)! For help" "${TEST_SERVER_DIR}/logs/startup_test.log" 2>/dev/null; then
            ready=true
            break
        fi

        echo -n "."
    done
    echo

    if [[ "$ready" == "true" ]]; then
        log_success "Server started successfully"

        # Test RCON if mcrcon is available
        local mcrcon_cmd=""
        if [[ -x "${TEST_TOOLS_DIR}/mcrcon" ]]; then
            mcrcon_cmd="${TEST_TOOLS_DIR}/mcrcon"
        elif command -v mcrcon &> /dev/null; then
            mcrcon_cmd="mcrcon"
        fi

        if [[ -n "$mcrcon_cmd" ]]; then
            log_info "Testing RCON connection..."
            sleep 2  # Give RCON a moment to initialize

            if $mcrcon_cmd -H localhost -P "$RCON_PORT" -p "$RCON_PASSWORD" "say Test" 2>/dev/null; then
                log_success "RCON connection successful"
            else
                log_warn "RCON connection failed (server may still be initializing)"
            fi
        fi

        # Stop server gracefully
        log_info "Stopping test server..."
        if [[ -n "$mcrcon_cmd" ]]; then
            $mcrcon_cmd -H localhost -P "$RCON_PORT" -p "$RCON_PASSWORD" "stop" 2>/dev/null || true
        fi

        # Wait for graceful shutdown
        local shutdown_wait=0
        while kill -0 "$server_pid" 2>/dev/null && [[ $shutdown_wait -lt 30 ]]; do
            sleep 1
            shutdown_wait=$((shutdown_wait + 1))
        done

        # Force kill if still running
        if kill -0 "$server_pid" 2>/dev/null; then
            kill -9 "$server_pid" 2>/dev/null || true
        fi

        # Clean up test world
        rm -rf "${TEST_SERVER_DIR}/world"

        log_success "Server verification complete"
    else
        log_error "Server failed to start within 120 seconds"
        log_error "Check ${TEST_SERVER_DIR}/logs/startup_test.log for details"

        # Kill the server
        kill -9 "$server_pid" 2>/dev/null || true
        return 1
    fi
}

# ============================================================================
# Main
# ============================================================================

main() {
    # Parse arguments
    AUTO_YES=false
    SKIP_MCRCON=false
    SKIP_ASYNC_PROFILER=false
    SKIP_VERIFY=false
    CLEANUP_ON_FAIL=true

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --yes|-y)
                AUTO_YES=true
                shift
                ;;
            --skip-mcrcon)
                SKIP_MCRCON=true
                shift
                ;;
            --skip-async-profiler)
                SKIP_ASYNC_PROFILER=true
                shift
                ;;
            --skip-verify)
                SKIP_VERIFY=true
                shift
                ;;
            --help|-h)
                show_help
                ;;
            *)
                log_error "Unknown option: $1"
                show_help
                ;;
        esac
    done

    echo "=============================================="
    echo " NeoForge Test Server Installation"
    echo " Minecraft ${MINECRAFT_VERSION} / NeoForge ${NEOFORGE_VERSION}"
    echo "=============================================="
    echo

    # Run installation steps
    check_java
    check_internet
    check_existing_installation
    check_ports

    echo
    log_info "Starting installation..."
    echo

    create_directory_structure
    download_neoforge_installer
    run_neoforge_installer
    create_server_properties
    create_jvm_args
    create_eula
    install_mcrcon
    install_async_profiler
    copy_mod_jar

    echo
    verify_installation

    echo
    echo "=============================================="
    echo -e " ${GREEN}Installation Complete!${NC}"
    echo "=============================================="
    echo
    echo "Test server location: ${TEST_SERVER_DIR}"
    echo "Test tools location:  ${TEST_TOOLS_DIR}"
    echo
    echo "Usage:"
    echo "  Start server:   cd test_server && ./run.sh nogui"
    echo "  Stop server:    ./test_tools/mcrcon -H localhost -P ${RCON_PORT} -p ${RCON_PASSWORD} stop"
    echo "  Profile server: ./test_tools/async-profiler/bin/asprof <pid>"
    echo
    echo "RCON Configuration:"
    echo "  Host:     localhost"
    echo "  Port:     ${RCON_PORT}"
    echo "  Password: ${RCON_PASSWORD}"
    echo
    echo "To uninstall: ./scripts/uninstall_test_server.sh"
    echo
}

# Trap errors for cleanup
trap cleanup_on_error ERR

main "$@"
