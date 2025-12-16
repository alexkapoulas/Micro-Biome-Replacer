#!/bin/bash
#
# test.sh - Wrapper script for the test harness
#
# Usage:
#   ./scripts/test.sh all              # Run all tests
#   ./scripts/test.sh accuracy         # Run accuracy tests
#   ./scripts/test.sh --seed 12345     # Override world seed
#   ./scripts/test.sh --buffer 2       # Use larger chunk buffer
#   ./scripts/test.sh --debug          # Enable debug output
#

set -e

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Change to project root so relative paths work
cd "$PROJECT_ROOT"

# Run the test harness as a Python module
exec python3 -m scripts.test_harness "$@"
