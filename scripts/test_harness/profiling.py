"""
Performance profiling integration for the test harness.

Provides:
- ProfileConfig: Configuration for performance tests
- AsyncProfiler: Wrapper for async-profiler CLI (optional)
- PerformanceTestResult: Result dataclass with timing metrics
- Parsing functions for profile command output
"""

import logging
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

from .config import PATHS, TIMEOUTS
from .exceptions import HarnessError


logger = logging.getLogger("harness.profiling")


@dataclass
class ProfileConfig:
    """Configuration for performance profiling tests."""

    # Performance thresholds (milliseconds)
    p99_threshold_ms: float = 50.0  # 50ms default
    p90_threshold_ms: float = 20.0  # 20ms
    mean_threshold_ms: float = 5.0  # 5ms

    # Chunk generation settings
    chunk_count: int = 500  # Number of chunks to generate for profiling
    warmup_chunks: int = 100  # Discard first N chunks from stats

    # async-profiler settings (if available)
    profiler_event: str = "cpu"  # cpu, wall, alloc, lock
    profiler_interval: str = "1ms"
    # Package filter for focused flamegraphs (JVM format with / separators)
    include_pattern: str = "com/example/alexthundercook/microbiomereplacer/*"


@dataclass
class PerformanceTestResult:
    """Result of a performance test run."""

    status: str  # PASS, FAIL
    chunk_count: int
    mean_ms: float
    p50_ms: float
    p90_ms: float
    p99_ms: float
    min_ms: float
    max_ms: float
    total_ms: float
    replacements: int
    homogeneous_skips: int
    positions_processed: int
    threshold_p99_ms: float
    flamegraph_path: Optional[str] = None
    duration_ms: int = 0

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return {
            "status": self.status,
            "chunk_count": self.chunk_count,
            "timing": {
                "mean_ms": self.mean_ms,
                "p50_ms": self.p50_ms,
                "p90_ms": self.p90_ms,
                "p99_ms": self.p99_ms,
                "min_ms": self.min_ms,
                "max_ms": self.max_ms,
                "total_ms": self.total_ms,
            },
            "work": {
                "replacements": self.replacements,
                "homogeneous_skips": self.homogeneous_skips,
                "positions_processed": self.positions_processed,
            },
            "threshold_p99_ms": self.threshold_p99_ms,
            "flamegraph_path": self.flamegraph_path,
            "duration_ms": self.duration_ms,
        }


class AsyncProfiler:
    """
    Wrapper for async-profiler attachment and control.

    Uses the `asprof` CLI tool to attach to the running JVM.
    This is optional - if async-profiler is not installed, profiling
    will still work but without flamegraph generation.
    """

    def __init__(self, profiler_dir: Optional[Path] = None):
        self.profiler_dir = profiler_dir or PATHS.ASYNC_PROFILER_DIR
        self.asprof = self.profiler_dir / "bin" / "asprof"
        self.output_dir = PATHS.TEST_RESULTS_DIR / "flamegraphs"

        self._pid: Optional[int] = None
        self._recording = False
        self._output_name: Optional[str] = None
        self._include_pattern: Optional[str] = None

    def is_available(self) -> bool:
        """Check if async-profiler is installed and accessible."""
        return self.asprof.exists() and self.asprof.is_file()

    def attach(self, pid: int) -> None:
        """
        Attach profiler to a running JVM process.

        If the given PID is a shell script, this will find the actual
        Java child process PID.
        """
        if not self.is_available():
            logger.warning(f"async-profiler not found at {self.asprof}")
            return

        # The server starts via run.sh, so the PID might be the shell.
        # Find the actual Java process (child of the shell).
        java_pid = self._find_java_pid(pid)
        if java_pid:
            self._pid = java_pid
            logger.info(f"Attached async-profiler to Java PID {java_pid} (parent shell: {pid})")
        else:
            # Fall back to the given PID
            self._pid = pid
            logger.info(f"Attached async-profiler to PID {pid}")

    def _find_java_pid(self, parent_pid: int) -> Optional[int]:
        """
        Find the Java child process of a parent shell.

        Args:
            parent_pid: PID of the parent process (shell script).

        Returns:
            Java process PID if found, None otherwise.
        """
        import time as time_module

        # Give the Java process time to start
        for attempt in range(5):
            # Method 1: Use pgrep to find java processes with the given parent
            try:
                result = subprocess.run(
                    ["pgrep", "-P", str(parent_pid)],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                if result.returncode == 0 and result.stdout.strip():
                    pids = result.stdout.strip().split('\n')
                    for pid_str in pids:
                        child_pid = int(pid_str.strip())
                        # Check if this child is a Java process
                        try:
                            cmd_result = subprocess.run(
                                ["ps", "-p", str(child_pid), "-o", "comm="],
                                capture_output=True,
                                text=True,
                                timeout=5,
                            )
                            if 'java' in cmd_result.stdout.lower():
                                logger.debug(f"Found Java child PID: {child_pid}")
                                return child_pid
                        except Exception:
                            pass
            except Exception as e:
                logger.debug(f"pgrep attempt {attempt + 1} failed: {e}")

            # Method 2: Use ps to find children
            try:
                result = subprocess.run(
                    ["ps", "--ppid", str(parent_pid), "-o", "pid,comm", "--no-headers"],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                for line in result.stdout.strip().split('\n'):
                    if line.strip():
                        parts = line.split()
                        if len(parts) >= 2 and 'java' in parts[1].lower():
                            java_pid = int(parts[0])
                            logger.debug(f"Found Java child PID via ps: {java_pid}")
                            return java_pid
            except Exception as e:
                logger.debug(f"ps attempt {attempt + 1} failed: {e}")

            # Wait a bit before retrying
            if attempt < 4:
                time_module.sleep(1)

        logger.warning(f"Could not find Java child process of PID {parent_pid}")
        return None

    def start_recording(
        self,
        output_name: str,
        event: str = "cpu",
        interval: str = "1ms",
        include_pattern: Optional[str] = None,
    ) -> bool:
        """
        Start CPU profiling.

        Args:
            output_name: Base name for output files.
            event: Profiling event type (cpu, wall, alloc, lock).
            interval: Sampling interval.
            include_pattern: Optional package filter (JVM format, e.g., "com/example/*").

        Returns True if profiling started successfully, False otherwise.
        """
        if not self.is_available() or not self._pid:
            return False

        self.output_dir.mkdir(parents=True, exist_ok=True)
        self._output_name = output_name
        self._include_pattern = include_pattern

        # Start profiling - output file is specified when stopping
        cmd = [
            str(self.asprof),
            "start",
            "-e", event,
            "-i", interval,
            str(self._pid),
        ]

        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=30,
            )
            if result.returncode != 0:
                logger.warning(f"Profiler start warning: {result.stderr}")
                # Check if it's a real error or just a warning
                if "Error" in result.stderr or "error" in result.stderr.lower():
                    logger.error(f"Profiler failed to start: {result.stderr}")
                    return False

            self._recording = True
            logger.info(f"Started {event} profiling with interval {interval}")
            return True

        except subprocess.TimeoutExpired:
            logger.error("Profiler start timed out")
            return False
        except Exception as e:
            logger.error(f"Failed to start profiler: {e}")
            return False

    def stop_recording(self) -> Optional[Path]:
        """
        Stop profiling and generate flamegraph.

        Returns the path to the generated flamegraph, or None if profiling
        was not active or failed. If an include pattern is set, the flamegraph
        will be filtered to show only matching frames.
        """
        if not self.is_available() or not self._pid or not self._recording:
            return None

        output_path = self.output_dir / f"{self._output_name}.html"

        # Build stop command with optional include filter
        cmd = [
            str(self.asprof),
            "stop",
            "-f", str(output_path),
            "-o", "flamegraph",
        ]

        # Add include filter if specified
        if self._include_pattern:
            cmd.extend(["-I", self._include_pattern])

        cmd.append(str(self._pid))

        try:
            if self._include_pattern:
                logger.info(f"Stopping profiler with filter: {self._include_pattern}")
            else:
                logger.info("Stopping profiler and generating flamegraph")

            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=60,
            )

            self._recording = False

            if result.returncode != 0:
                logger.warning(f"Profiler stop stderr: {result.stderr}")
                logger.warning(f"Profiler stop stdout: {result.stdout}")

            if output_path.exists():
                logger.info(f"Flamegraph generated: {output_path}")
                return output_path
            else:
                logger.warning(f"Flamegraph not created at {output_path}")
                return None

        except subprocess.TimeoutExpired:
            logger.error("Profiler stop timed out")
            return None
        except Exception as e:
            logger.error(f"Failed to stop profiler: {e}")
            return None

    def cleanup(self) -> None:
        """Clean up profiler state."""
        if self._recording and self._pid:
            try:
                self.stop_recording()
            except Exception:
                pass
        self._pid = None
        self._recording = False


def parse_profile_stats(results: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """
    Extract profile stats from test results.

    Args:
        results: List of parsed JSON results from server log.

    Returns:
        The profile_stats command result, or None if not found.
    """
    for result in results:
        if result.get("command") == "profile_stats":
            return result
    return None


def create_performance_result(
    stats: Dict[str, Any],
    config: ProfileConfig,
    duration_ms: int,
    flamegraph_path: Optional[Path] = None,
) -> PerformanceTestResult:
    """
    Create a PerformanceTestResult from parsed stats.

    Args:
        stats: Parsed profile_stats JSON from server.
        config: Profile configuration with thresholds.
        duration_ms: Total test duration in milliseconds.
        flamegraph_path: Path to generated flamegraph (if any).

    Returns:
        PerformanceTestResult with pass/fail status.
    """
    p99_ms = stats.get("p99_ms", 0)
    passed = p99_ms < config.p99_threshold_ms

    return PerformanceTestResult(
        status="PASS" if passed else "FAIL",
        chunk_count=stats.get("count", 0),
        mean_ms=stats.get("mean_ms", 0),
        p50_ms=stats.get("p50_ms", 0),
        p90_ms=stats.get("p90_ms", 0),
        p99_ms=p99_ms,
        min_ms=stats.get("min_ms", 0),
        max_ms=stats.get("max_ms", 0),
        total_ms=stats.get("total_ms", 0),
        replacements=stats.get("replacements", 0),
        homogeneous_skips=stats.get("homogeneous_skips", 0),
        positions_processed=stats.get("positions_processed", 0),
        threshold_p99_ms=config.p99_threshold_ms,
        flamegraph_path=str(flamegraph_path) if flamegraph_path else None,
        duration_ms=duration_ms,
    )
