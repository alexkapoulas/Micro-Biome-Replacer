"""
Output management for the test harness.

Separates console output (minimal, one line per test) from detailed
log file output. Console shows status updates; logs contain everything.
"""

import logging
import sys
from pathlib import Path
from typing import Optional

from .config import PATHS


# Module-level logger for the harness
logger = logging.getLogger("harness")

# Track if output has been set up
_output_initialized = False


def setup_output(log_dir: Optional[Path] = None) -> None:
    """
    Configure logging to separate console and file output.

    Console gets no logging output - we use console_status() for that.
    All logging goes to harness.log file only.

    Args:
        log_dir: Directory for log files. Defaults to test_results/.
    """
    global _output_initialized

    if _output_initialized:
        return

    log_dir = log_dir or PATHS.TEST_RESULTS_DIR
    log_dir.mkdir(parents=True, exist_ok=True)

    harness_log = log_dir / "harness.log"

    # Configure root logger
    root_logger = logging.getLogger()
    root_logger.setLevel(logging.DEBUG)

    # Remove any existing handlers
    for handler in root_logger.handlers[:]:
        root_logger.removeHandler(handler)

    # File handler - gets ALL log output with timestamps
    file_handler = logging.FileHandler(harness_log, mode="w", encoding="utf-8")
    file_handler.setLevel(logging.DEBUG)
    file_formatter = logging.Formatter(
        "%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"
    )
    file_handler.setFormatter(file_formatter)
    root_logger.addHandler(file_handler)

    # Configure harness logger
    logger.setLevel(logging.DEBUG)

    _output_initialized = True
    logger.info("Output system initialized")
    logger.info(f"Harness log: {harness_log}")


def console_status(message: str) -> None:
    """
    Print a status message to console (stdout).

    This is the ONLY way output should reach the console during test runs.
    Format: [PREFIX] message

    Args:
        message: The status message to display.
    """
    print(message, flush=True)


def console_harness(message: str) -> None:
    """Print a harness status message."""
    console_status(f"[HARNESS] {message}")


def console_pass(test_name: str, duration_secs: float) -> None:
    """Print a test pass message."""
    console_status(f"[PASS] {test_name} ({duration_secs:.1f}s)")


def console_fail(test_name: str, duration_secs: float, hint: str = "see harness.log") -> None:
    """Print a test fail message."""
    console_status(f"[FAIL] {test_name} ({duration_secs:.1f}s) - {hint}")


def console_skip(test_name: str, reason: str) -> None:
    """Print a test skip message."""
    console_status(f"[SKIP] {test_name} - {reason}")


def console_summary(passed: int, total: int, failed: int, duration_secs: float) -> None:
    """Print the final test summary."""
    console_status(
        f"[HARNESS] Complete: {passed}/{total} passed, {failed} failed ({duration_secs:.1f}s total)"
    )


def get_server_log_path(log_dir: Optional[Path] = None) -> Path:
    """Get the path for server stdout log file."""
    log_dir = log_dir or PATHS.TEST_RESULTS_DIR
    return log_dir / "server_stdout.log"


def ensure_log_dir(log_dir: Optional[Path] = None) -> Path:
    """Ensure the log directory exists and return its path."""
    log_dir = log_dir or PATHS.TEST_RESULTS_DIR
    log_dir.mkdir(parents=True, exist_ok=True)
    return log_dir
