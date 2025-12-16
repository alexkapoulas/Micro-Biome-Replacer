#!/bin/bash
#
# uninstall_test_server.sh
# Clean removal of NeoForge test server infrastructure
#
# Usage:
#   ./uninstall_test_server.sh [OPTIONS]
#
# Options:
#   --force, -f           Skip all confirmation prompts
#   --keep-tools          Keep test_tools directory (mcrcon, async-profiler)
#   --keep-results        Keep test_results directory
#   --help, -h            Show this help message
#

set -e  # Exit on error

# ============================================================================
# Configuration
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TEST_SERVER_DIR="${PROJECT_ROOT}/test_server"
TEST_TOOLS_DIR="${PROJECT_ROOT}/test_tools"
TEST_RESULTS_DIR="${PROJECT_ROOT}/test_results"

# Server ports to check for running processes
SERVER_PORT="25565"
RCON_PORT="25575"
RCON_PASSWORD="test_password_12345"

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
    sed -n '3,15p' "$0" | sed 's/^# \?//'
    exit 0
}

confirm() {
    local prompt="$1"
    if [[ "$FORCE" == "true" ]]; then
        return 0
    fi
    read -p "$prompt [y/N] " -n 1 -r
    echo
    [[ $REPLY =~ ^[Yy]$ ]]
}

# ============================================================================
# Cleanup Functions
# ============================================================================

find_server_processes() {
    # Find processes listening on server ports
    local pids=""

    # Try ss first (modern), fall back to netstat
    if command -v ss &> /dev/null; then
        # Extract PIDs from ss output
        local ss_output
        ss_output=$(ss -tlnp "sport = :${SERVER_PORT} or sport = :${RCON_PORT}" 2>/dev/null || true)

        # Parse PIDs from output like "pid=12345"
        while read -r pid; do
            if [[ -n "$pid" ]] && [[ "$pid" =~ ^[0-9]+$ ]]; then
                pids="$pids $pid"
            fi
        done < <(echo "$ss_output" | grep -oP 'pid=\K[0-9]+' 2>/dev/null || true)
    elif command -v netstat &> /dev/null; then
        # Parse PIDs from netstat output
        local netstat_output
        netstat_output=$(netstat -tlnp 2>/dev/null | grep -E ":${SERVER_PORT}|:${RCON_PORT}" || true)

        while read -r pid; do
            if [[ -n "$pid" ]] && [[ "$pid" =~ ^[0-9]+$ ]]; then
                pids="$pids $pid"
            fi
        done < <(echo "$netstat_output" | grep -oP '[0-9]+(?=/)' 2>/dev/null || true)
    fi

    # Remove duplicates and return
    echo "$pids" | tr ' ' '\n' | sort -u | grep -v '^$' | tr '\n' ' '
}

stop_running_server() {
    log_info "Checking for running server processes..."

    local pids
    pids=$(find_server_processes)

    if [[ -z "$pids" ]]; then
        log_success "No server processes found on ports ${SERVER_PORT}/${RCON_PORT}"
        return 0
    fi

    log_warn "Found server processes: $pids"

    # Try graceful shutdown via RCON first
    local mcrcon_cmd=""
    if [[ -x "${TEST_TOOLS_DIR}/mcrcon" ]]; then
        mcrcon_cmd="${TEST_TOOLS_DIR}/mcrcon"
    elif command -v mcrcon &> /dev/null; then
        mcrcon_cmd="mcrcon"
    fi

    if [[ -n "$mcrcon_cmd" ]]; then
        log_info "Attempting graceful shutdown via RCON..."

        # Try to send save-all and stop commands
        timeout 10 $mcrcon_cmd -H localhost -P "$RCON_PORT" -p "$RCON_PASSWORD" "save-all" 2>/dev/null || true
        sleep 2
        timeout 10 $mcrcon_cmd -H localhost -P "$RCON_PORT" -p "$RCON_PASSWORD" "stop" 2>/dev/null || true

        # Wait for graceful shutdown
        log_info "Waiting for graceful shutdown..."
        local wait_count=0
        while [[ $wait_count -lt 30 ]]; do
            sleep 1
            wait_count=$((wait_count + 1))

            pids=$(find_server_processes)
            if [[ -z "$pids" ]]; then
                log_success "Server stopped gracefully"
                return 0
            fi
        done
    fi

    # Force kill remaining processes
    pids=$(find_server_processes)
    if [[ -n "$pids" ]]; then
        log_warn "Graceful shutdown incomplete, sending SIGTERM..."

        for pid in $pids; do
            if kill -0 "$pid" 2>/dev/null; then
                log_info "Sending SIGTERM to PID $pid"
                kill -15 "$pid" 2>/dev/null || true
            fi
        done

        sleep 3

        # Check if still running, use SIGKILL
        pids=$(find_server_processes)
        if [[ -n "$pids" ]]; then
            log_warn "Processes still running, sending SIGKILL..."

            for pid in $pids; do
                if kill -0 "$pid" 2>/dev/null; then
                    log_info "Sending SIGKILL to PID $pid"
                    kill -9 "$pid" 2>/dev/null || true
                fi
            done

            sleep 2
        fi
    fi

    # Final check
    pids=$(find_server_processes)
    if [[ -z "$pids" ]]; then
        log_success "All server processes stopped"
    else
        log_error "Some processes could not be stopped: $pids"
        log_error "You may need to stop them manually"
    fi
}

remove_test_server() {
    if [[ ! -d "$TEST_SERVER_DIR" ]]; then
        log_info "Test server directory does not exist: $TEST_SERVER_DIR"
        return 0
    fi

    # Calculate size
    local size
    size=$(du -sh "$TEST_SERVER_DIR" 2>/dev/null | cut -f1)

    log_info "Test server directory: $TEST_SERVER_DIR ($size)"

    if confirm "Remove test server directory?"; then
        log_info "Removing test server directory..."

        # Use timeout in case of slow filesystem
        if timeout 60 rm -rf "$TEST_SERVER_DIR"; then
            log_success "Test server directory removed"
        else
            log_error "Failed to remove test server directory (timeout or error)"
            return 1
        fi
    else
        log_info "Keeping test server directory"
    fi
}

remove_test_tools() {
    if [[ "$KEEP_TOOLS" == "true" ]]; then
        log_info "Keeping test_tools (--keep-tools)"
        return
    fi

    if [[ ! -d "$TEST_TOOLS_DIR" ]]; then
        log_info "Test tools directory does not exist: $TEST_TOOLS_DIR"
        return 0
    fi

    # Check what was installed by our script
    local has_mcrcon=false
    local has_async_profiler=false

    [[ -f "${TEST_TOOLS_DIR}/.mcrcon_installed_by_script" ]] && has_mcrcon=true
    [[ -f "${TEST_TOOLS_DIR}/.async_profiler_installed_by_script" ]] && has_async_profiler=true

    if [[ "$has_mcrcon" == "false" ]] && [[ "$has_async_profiler" == "false" ]]; then
        log_info "No tools installed by our script found in test_tools/"
        return
    fi

    local size
    size=$(du -sh "$TEST_TOOLS_DIR" 2>/dev/null | cut -f1)

    log_info "Test tools directory: $TEST_TOOLS_DIR ($size)"
    echo "  Contains:"
    [[ "$has_mcrcon" == "true" ]] && echo "    - mcrcon"
    [[ "$has_async_profiler" == "true" ]] && echo "    - async-profiler"

    if confirm "Remove test_tools directory?"; then
        rm -rf "$TEST_TOOLS_DIR"
        log_success "Test tools directory removed"
    else
        log_info "Keeping test_tools directory"
    fi
}

remove_test_results() {
    if [[ "$KEEP_RESULTS" == "true" ]]; then
        log_info "Keeping test_results (--keep-results)"
        return
    fi

    if [[ ! -d "$TEST_RESULTS_DIR" ]]; then
        return 0
    fi

    local size
    size=$(du -sh "$TEST_RESULTS_DIR" 2>/dev/null | cut -f1)

    log_info "Test results directory: $TEST_RESULTS_DIR ($size)"

    if confirm "Remove test results directory?"; then
        rm -rf "$TEST_RESULTS_DIR"
        log_success "Test results directory removed"
    else
        log_info "Keeping test results directory"
    fi
}

# ============================================================================
# Main
# ============================================================================

main() {
    # Parse arguments
    FORCE=false
    KEEP_TOOLS=false
    KEEP_RESULTS=false

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --force|-f)
                FORCE=true
                shift
                ;;
            --keep-tools)
                KEEP_TOOLS=true
                shift
                ;;
            --keep-results)
                KEEP_RESULTS=true
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
    echo " NeoForge Test Server Uninstallation"
    echo "=============================================="
    echo

    # Check if there's anything to uninstall
    local has_server=false
    local has_results=false
    local has_tools=false

    [[ -d "$TEST_SERVER_DIR" ]] && has_server=true
    [[ -d "$TEST_RESULTS_DIR" ]] && has_results=true
    [[ -d "$TEST_TOOLS_DIR" ]] && has_tools=true

    if [[ "$has_server" == "false" ]] && [[ "$has_results" == "false" ]] && [[ "$has_tools" == "false" ]]; then
        log_info "Nothing to uninstall"
        exit 0
    fi

    # Show what will be checked for removal
    echo "The following will be checked for removal:"
    [[ "$has_server" == "true" ]] && echo "  - Test server: $TEST_SERVER_DIR"
    [[ "$has_tools" == "true" ]] && echo "  - Test tools: $TEST_TOOLS_DIR"
    [[ "$has_results" == "true" ]] && echo "  - Test results: $TEST_RESULTS_DIR"
    echo

    if [[ "$FORCE" != "true" ]]; then
        if ! confirm "Proceed with uninstallation?"; then
            log_info "Uninstallation cancelled"
            exit 0
        fi
    fi

    echo

    # Run uninstallation steps
    stop_running_server
    remove_test_server
    remove_test_tools
    remove_test_results

    echo
    echo "=============================================="
    echo -e " ${GREEN}Uninstallation Complete!${NC}"
    echo "=============================================="
    echo
    echo "To reinstall: ./scripts/install_test_server.sh"
    echo
}

main "$@"
