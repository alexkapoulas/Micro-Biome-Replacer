"""
JSON report generation for test results.

Generates structured reports with test summaries, environment info,
and per-test details.
"""

import json
import logging
import platform
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Any, List, Optional

from .config import PATHS


logger = logging.getLogger("harness.reporting")


def get_environment_info() -> Dict[str, Any]:
    """
    Collect environment information for the report.

    Returns:
        Dictionary with platform, Java, and mod information.
    """
    env = {
        "platform": f"{platform.system()} {platform.release()}",
        "python": platform.python_version(),
        "java": get_java_version(),
        "neoforge": "1.21.1-21.1.x",  # Could be parsed from server jar
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }

    # Try to get mod version
    mod_version = get_mod_version()
    if mod_version:
        env["mod_version"] = mod_version

    return env


def get_java_version() -> str:
    """Get the installed Java version."""
    try:
        result = subprocess.run(
            ["java", "-version"],
            capture_output=True,
            text=True,
            timeout=10,
        )
        # Java version is typically on stderr
        output = result.stderr or result.stdout
        for line in output.splitlines():
            if "version" in line.lower():
                # Extract version string
                parts = line.split('"')
                if len(parts) >= 2:
                    return parts[1]
                return line.strip()
        return "unknown"
    except Exception as e:
        logger.debug(f"Failed to get Java version: {e}")
        return "unknown"


def get_mod_version() -> Optional[str]:
    """Try to determine the mod version from the JAR file."""
    mods_dir = PATHS.SERVER_MODS_DIR
    if not mods_dir.exists():
        return None

    # Look for our mod JAR
    for jar in mods_dir.glob("*.jar"):
        if "microbiome" in jar.name.lower():
            # Could parse mods.toml from JAR, but for now just return filename
            return jar.stem
    return None


class TestResult:
    """Represents the result of a single test."""

    def __init__(
        self,
        name: str,
        status: str,
        duration_ms: int,
        details: Optional[Dict[str, Any]] = None,
        error: Optional[str] = None,
    ):
        self.name = name
        self.status = status
        self.duration_ms = duration_ms
        self.details = details or {}
        self.error = error

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        result = {
            "name": self.name,
            "status": self.status,
            "duration_ms": self.duration_ms,
        }

        if self.details:
            result["details"] = self.details

        if self.error:
            result["error"] = self.error

        return result


class ReportGenerator:
    """
    Generates JSON reports from test results.

    Collects results during test run and produces a final report.
    """

    def __init__(self, output_path: Optional[Path] = None):
        """
        Initialize the report generator.

        Args:
            output_path: Path to write the report. Defaults to test_results/report.json.
        """
        self.output_path = output_path or PATHS.REPORT_JSON
        self.results: List[TestResult] = []
        self.start_time: Optional[datetime] = None
        self.end_time: Optional[datetime] = None

    def start_run(self) -> None:
        """Mark the start of a test run."""
        self.start_time = datetime.now(timezone.utc)
        self.results = []

    def add_result(self, result: TestResult) -> None:
        """Add a test result to the report."""
        self.results.append(result)

    def end_run(self) -> None:
        """Mark the end of a test run."""
        self.end_time = datetime.now(timezone.utc)

    def get_summary(self) -> Dict[str, Any]:
        """Calculate summary statistics."""
        total = len(self.results)
        passed = sum(1 for r in self.results if r.status == "PASS")
        failed = total - passed

        duration_ms = 0
        if self.start_time and self.end_time:
            duration_ms = int((self.end_time - self.start_time).total_seconds() * 1000)

        return {
            "total": total,
            "passed": passed,
            "failed": failed,
            "duration_ms": duration_ms,
        }

    def generate(self) -> Dict[str, Any]:
        """
        Generate the complete report.

        Returns:
            Complete report as a dictionary.
        """
        report = {
            "summary": self.get_summary(),
            "environment": get_environment_info(),
            "tests": [r.to_dict() for r in self.results],
        }

        return report

    def write(self) -> Path:
        """
        Write the report to the output file.

        Returns:
            Path to the written report.
        """
        # Ensure directory exists
        self.output_path.parent.mkdir(parents=True, exist_ok=True)

        report = self.generate()

        logger.info(f"Writing report to {self.output_path}")

        with open(self.output_path, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)

        return self.output_path


def create_accuracy_result(
    name: str,
    seed: int,
    baseline_file: str,
    chunk_stats: Dict[str, Any],
    accuracy_stats: Dict[str, Any],
    duration_ms: int,
) -> TestResult:
    """
    Create a TestResult for an accuracy test.

    Args:
        name: Test name.
        seed: World seed used.
        baseline_file: Path to baseline file.
        chunk_stats: Chunk calculation statistics.
        accuracy_stats: Accuracy calculation statistics.
        duration_ms: Test duration in milliseconds.

    Returns:
        TestResult instance.
    """
    details = {
        "seed": seed,
        "baseline_file": baseline_file,
        "chunks": chunk_stats,
        **accuracy_stats,
    }

    return TestResult(
        name=name,
        status=accuracy_stats.get("status", "FAIL"),
        duration_ms=duration_ms,
        details=details,
    )


def create_error_result(
    name: str,
    error: str,
    duration_ms: int,
) -> TestResult:
    """
    Create a TestResult for a failed test.

    Args:
        name: Test name.
        error: Error message.
        duration_ms: Test duration before failure.

    Returns:
        TestResult instance.
    """
    return TestResult(
        name=name,
        status="FAIL",
        duration_ms=duration_ms,
        error=error,
    )


def create_determinism_result(
    name: str,
    seed: int,
    run1_hash: str,
    run2_hash: str,
    match: bool,
    duration_ms: int,
) -> TestResult:
    """
    Create a TestResult for a determinism test.

    Args:
        name: Test name.
        seed: World seed used for both runs.
        run1_hash: SHA-256 hash of first run results.
        run2_hash: SHA-256 hash of second run results.
        match: Whether the hashes match.
        duration_ms: Total test duration in milliseconds.

    Returns:
        TestResult instance.
    """
    details = {
        "seed": seed,
        "run1_hash": run1_hash,
        "run2_hash": run2_hash,
        "match": match,
    }

    return TestResult(
        name=name,
        status="PASS" if match else "FAIL",
        duration_ms=duration_ms,
        details=details,
    )


def create_performance_result(
    name: str,
    perf_result: Any,  # PerformanceTestResult from profiling module
    duration_ms: int,
) -> TestResult:
    """
    Create a TestResult for a performance test.

    Args:
        name: Test name.
        perf_result: PerformanceTestResult with timing data.
        duration_ms: Total test duration in milliseconds.

    Returns:
        TestResult instance.
    """
    return TestResult(
        name=name,
        status=perf_result.status,
        duration_ms=duration_ms,
        details=perf_result.to_dict(),
    )
