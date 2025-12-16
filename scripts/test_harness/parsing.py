"""
Log parsing utilities for extracting test results from server logs.

Finds [TEST_RESULT] lines in server logs and parses the JSON payloads.
"""

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Dict, Any

from .exceptions import LogParsingError


logger = logging.getLogger("harness.parsing")

# Marker for test result lines
TEST_RESULT_MARKER = "[TEST_RESULT]"


@dataclass
class BiomeInspectResult:
    """Result from a single biome inspection."""
    x: int
    y: int
    z: int
    baseline_biome: str
    actual_biome: str
    replaced: bool  # True if actual differs from baseline


@dataclass
class ForceloadResult:
    """Result from forceload_chunks command."""
    chunks_loaded: int
    errors: int
    duration_ms: int


@dataclass
class BatchInspectSummary:
    """Summary from batch_inspect command."""
    total: int
    errors: int
    duration_ms: int


def extract_test_results(log_path: Path) -> List[Dict[str, Any]]:
    """
    Extract all [TEST_RESULT] JSON objects from a log file.

    Args:
        log_path: Path to the server log file.

    Returns:
        List of parsed JSON objects from test result lines.

    Raises:
        LogParsingError: If the log file cannot be read.
    """
    logger.debug(f"Extracting test results from: {log_path}")

    if not log_path.exists():
        raise LogParsingError(f"Log file not found: {log_path}")

    results: List[Dict[str, Any]] = []

    try:
        with open(log_path, "r", encoding="utf-8", errors="replace") as f:
            for line_num, line in enumerate(f, start=1):
                if TEST_RESULT_MARKER in line:
                    try:
                        result = parse_test_result_line(line)
                        if result:
                            results.append(result)
                    except Exception as e:
                        logger.warning(f"Failed to parse line {line_num}: {e}")

    except Exception as e:
        raise LogParsingError(f"Failed to read log file: {e}") from e

    logger.info(f"Extracted {len(results)} test results from log")
    return results


def parse_test_result_line(line: str) -> Optional[Dict[str, Any]]:
    """
    Parse a single [TEST_RESULT] line and extract the JSON payload.

    Args:
        line: A log line containing [TEST_RESULT].

    Returns:
        Parsed JSON object, or None if parsing fails.
    """
    marker_pos = line.find(TEST_RESULT_MARKER)
    if marker_pos == -1:
        return None

    # Find the JSON object after the marker
    after_marker = line[marker_pos + len(TEST_RESULT_MARKER):]

    # Find the opening brace
    json_start = after_marker.find("{")
    if json_start == -1:
        return None

    json_str = after_marker[json_start:]

    # Handle potential trailing content (find matching closing brace)
    brace_count = 0
    json_end = 0
    for i, char in enumerate(json_str):
        if char == "{":
            brace_count += 1
        elif char == "}":
            brace_count -= 1
            if brace_count == 0:
                json_end = i + 1
                break

    if json_end == 0:
        # No matching brace found, try parsing as-is
        pass
    else:
        json_str = json_str[:json_end]

    try:
        return json.loads(json_str)
    except json.JSONDecodeError as e:
        logger.debug(f"JSON parse error: {e} in: {json_str[:100]}...")
        return None


def parse_batch_inspect_results(
    results: List[Dict[str, Any]]
) -> tuple[List[BiomeInspectResult], Optional[BatchInspectSummary]]:
    """
    Parse batch_inspect results from extracted test results.

    Args:
        results: List of parsed JSON objects from extract_test_results.

    Returns:
        Tuple of (inspection results, summary or None).
    """
    inspections: List[BiomeInspectResult] = []
    summary: Optional[BatchInspectSummary] = None

    for result in results:
        command = result.get("command", "")

        if command == "batch_inspect":
            try:
                inspect_result = BiomeInspectResult(
                    x=result["x"],
                    y=result["y"],
                    z=result["z"],
                    baseline_biome=result["baseline_biome"],
                    actual_biome=result["actual_biome"],
                    replaced=result["baseline_biome"] != result["actual_biome"],
                )
                inspections.append(inspect_result)
            except KeyError as e:
                logger.warning(f"Missing key in batch_inspect result: {e}")

        elif command == "batch_inspect_complete":
            try:
                summary = BatchInspectSummary(
                    total=result["total"],
                    errors=result["errors"],
                    duration_ms=result["duration_ms"],
                )
            except KeyError as e:
                logger.warning(f"Missing key in batch_inspect_complete: {e}")

    return inspections, summary


def parse_forceload_result(results: List[Dict[str, Any]]) -> Optional[ForceloadResult]:
    """
    Parse forceload_chunks result from extracted test results.

    Args:
        results: List of parsed JSON objects.

    Returns:
        ForceloadResult or None if not found.
    """
    for result in results:
        if result.get("command") == "forceload_chunks" and "chunks_loaded" in result:
            try:
                return ForceloadResult(
                    chunks_loaded=result["chunks_loaded"],
                    errors=result.get("errors", 0),
                    duration_ms=result["duration_ms"],
                )
            except KeyError as e:
                logger.warning(f"Missing key in forceload_chunks result: {e}")

    return None


def calculate_accuracy_stats(
    inspections: List[BiomeInspectResult],
    threshold: float = 0.95,
) -> Dict[str, Any]:
    """
    Calculate accuracy statistics from inspection results.

    Args:
        inspections: List of biome inspection results.
        threshold: Pass threshold (proportion that must be replaced).

    Returns:
        Dictionary with accuracy statistics.
    """
    if not inspections:
        return {
            "total_coordinates": 0,
            "replaced": 0,
            "not_replaced": 0,
            "pass_rate": 0.0,
            "status": "FAIL",
            "threshold": threshold,
            "failures": [],
        }

    replaced = sum(1 for i in inspections if i.replaced)
    not_replaced = len(inspections) - replaced
    pass_rate = replaced / len(inspections)

    failures = [
        {
            "x": i.x,
            "y": i.y,
            "z": i.z,
            "expected_change": i.baseline_biome,
            "actual": i.actual_biome,
        }
        for i in inspections
        if not i.replaced
    ]

    return {
        "total_coordinates": len(inspections),
        "replaced": replaced,
        "not_replaced": not_replaced,
        "pass_rate": pass_rate,
        "status": "PASS" if pass_rate >= threshold else "FAIL",
        "threshold": threshold,
        "failures": failures[:20],  # Limit failure list size
    }
