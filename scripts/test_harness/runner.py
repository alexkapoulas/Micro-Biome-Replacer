"""
Test execution orchestration.

Handles test discovery, execution, and result collection.
Ensures one test failure doesn't prevent other tests from running.
"""

import hashlib
import json
import logging
import shutil
import time
from pathlib import Path
from typing import List, Optional, Callable, Tuple

from .config import TIMEOUTS, PATHS, TEST, get_tick_sprint_count
from .exceptions import HarnessError
from .server import ServerManager
from .chunks import ChunkCalculator
from .parsing import (
    extract_test_results,
    parse_batch_inspect_results,
    parse_forceload_result,
    calculate_accuracy_stats,
    BiomeInspectResult,
)
from .reporting import (
    ReportGenerator,
    TestResult,
    create_accuracy_result,
    create_error_result,
    create_determinism_result,
)
from .output import (
    console_harness,
    console_pass,
    console_fail,
    console_summary,
    get_server_log_path,
    logger as output_logger,
)
from .jar_utils import sync_mod_jar
from .profiling import (
    ProfileConfig,
    AsyncProfiler,
    parse_profile_stats,
    create_performance_result,
)


logger = logging.getLogger("harness.runner")


class TestRunner:
    """
    Orchestrates test execution.

    Manages server lifecycle, executes tests, and collects results.
    """

    def __init__(
        self,
        seed: int = 12345,
        buffer_size: int = TEST.DEFAULT_BUFFER_SIZE,
        threshold: float = TEST.PASS_THRESHOLD,
        profile_config: Optional[ProfileConfig] = None,
    ):
        """
        Initialize the test runner.

        Args:
            seed: World seed for testing.
            buffer_size: Chunk buffer size for loading.
            threshold: Pass threshold for accuracy tests.
            profile_config: Configuration for performance tests.
        """
        self.seed = seed
        self.buffer_size = buffer_size
        self.threshold = threshold
        self.profile_config = profile_config or ProfileConfig()

        self.server: Optional[ServerManager] = None
        self.report = ReportGenerator()

    def run_all(self) -> bool:
        """
        Run all available tests.

        Returns:
            True if all tests passed, False otherwise.
        """
        return self.run_tests(["accuracy", "determinism"])

    def run_accuracy(self) -> bool:
        """
        Run accuracy tests only.

        Returns:
            True if all tests passed, False otherwise.
        """
        return self.run_tests(["accuracy"])

    def run_determinism(self) -> bool:
        """
        Run determinism tests only.

        Returns:
            True if all tests passed, False otherwise.
        """
        return self.run_tests(["determinism"])

    def run_tests(self, test_names: List[str]) -> bool:
        """
        Run specified tests.

        Args:
            test_names: List of test names to run.

        Returns:
            True if all tests passed, False otherwise.
        """
        # Sync mod JAR from build to test server if needed
        jar_copied, jar_message = sync_mod_jar()
        if jar_copied:
            console_harness(jar_message)
            logger.info(jar_message)
        elif jar_message:
            logger.warning(jar_message)

        console_harness(f"Starting test run: {len(test_names)} tests queued")
        logger.info(f"Starting test run with seed={self.seed}, buffer={self.buffer_size}")

        self.report.start_run()
        start_time = time.time()

        test_map = {
            "accuracy": self._run_accuracy_test,
            "determinism": self._run_determinism_test,
            "performance": self._run_performance_test,
        }

        all_passed = True

        for test_name in test_names:
            test_func = test_map.get(test_name)
            if test_func is None:
                logger.warning(f"Unknown test: {test_name}")
                continue

            try:
                result = test_func()
                self.report.add_result(result)

                if result.status == "PASS":
                    console_pass(test_name, result.duration_ms / 1000)
                else:
                    console_fail(test_name, result.duration_ms / 1000)
                    all_passed = False

            except Exception as e:
                logger.exception(f"Test {test_name} crashed: {e}")
                error_result = create_error_result(test_name, str(e), 0)
                self.report.add_result(error_result)
                console_fail(test_name, 0, f"crashed: {e}")
                all_passed = False

        self.report.end_run()
        total_duration = time.time() - start_time

        summary = self.report.get_summary()
        console_summary(
            summary["passed"],
            summary["total"],
            summary["failed"],
            total_duration,
        )

        # Write report
        self.report.write()
        logger.info(f"Report written to {self.report.output_path}")

        return all_passed

    def _run_accuracy_test(self) -> TestResult:
        """
        Run the accuracy test.

        Tests that micro biomes are replaced during world generation.

        Returns:
            TestResult with accuracy statistics.
        """
        test_name = "accuracy"
        start_time = time.time()

        try:
            # Find baseline file
            baseline_file = self._find_baseline_file()
            if baseline_file is None:
                raise HarnessError("No baseline file found")

            logger.info(f"Using baseline: {baseline_file}")

            # Calculate chunks
            calculator = ChunkCalculator()
            calculator.load_baseline(baseline_file)
            calculator.calculate_chunks(self.buffer_size)
            chunk_stats = calculator.get_stats()

            # Start server
            self.server = ServerManager()
            try:
                self.server.start()

                # Copy files to server
                chunk_list_path = self.server.copy_file_to_server(
                    self._write_chunk_file(calculator),
                    "chunk_list.txt"
                )
                baseline_copy_path = self.server.copy_file_to_server(
                    baseline_file,
                    "baseline_coords.csv"
                )

                # Force load chunks
                rcon = self.server.get_rcon()
                logger.info(f"Force-loading {calculator.get_chunk_count()} chunks...")
                rcon.forceload_chunks("chunk_list.txt")

                # Tick sprint for generation
                tick_count = get_tick_sprint_count(calculator.get_chunk_count())
                logger.info(f"Running tick sprint: {tick_count} ticks")
                rcon.tick_sprint(tick_count)

                # Wait for generation to settle
                time.sleep(TIMEOUTS.GENERATION_SETTLE)

                # Query biomes
                logger.info("Querying biomes via batch_inspect...")
                rcon.batch_inspect("baseline_coords.csv")

                # Stop server to flush logs
                self.server.stop()

            finally:
                self.server.cleanup()

            # Parse results from log
            server_log = get_server_log_path()
            results = extract_test_results(server_log)
            inspections, summary = parse_batch_inspect_results(results)
            forceload_result = parse_forceload_result(results)

            # Calculate accuracy
            accuracy_stats = calculate_accuracy_stats(inspections, self.threshold)

            # Update chunk stats with actual load info
            if forceload_result:
                chunk_stats["loaded"] = forceload_result.chunks_loaded
                chunk_stats["load_duration_ms"] = forceload_result.duration_ms

            duration_ms = int((time.time() - start_time) * 1000)

            return create_accuracy_result(
                name=test_name,
                seed=self.seed,
                baseline_file=str(baseline_file),
                chunk_stats=chunk_stats,
                accuracy_stats=accuracy_stats,
                duration_ms=duration_ms,
            )

        except Exception as e:
            logger.exception(f"Accuracy test failed: {e}")
            duration_ms = int((time.time() - start_time) * 1000)
            return create_error_result(test_name, str(e), duration_ms)

        finally:
            if self.server:
                self.server.cleanup()
                self.server = None

    def _run_determinism_test(self) -> TestResult:
        """
        Run the determinism test.

        Verifies that the same seed produces identical results across runs.
        Runs the biome inspection twice with the same seed and compares
        the results using SHA-256 hashing.

        Returns:
            TestResult with determinism verification.
        """
        test_name = "determinism"
        start_time = time.time()

        try:
            # Find baseline file
            baseline_file = self._find_baseline_file()
            if baseline_file is None:
                raise HarnessError("No baseline file found")

            logger.info(f"Determinism test using baseline: {baseline_file}")

            # Calculate chunks (same for both runs)
            calculator = ChunkCalculator()
            calculator.load_baseline(baseline_file)
            calculator.calculate_chunks(self.buffer_size)

            # Write chunk file once (reused for both runs)
            chunk_file = self._write_chunk_file(calculator)

            # Run two separate world generations and collect results
            logger.info("Starting determinism run 1...")
            run1_results = self._run_single_generation(
                calculator, baseline_file, chunk_file, "run1"
            )
            run1_hash = self._hash_inspection_results(run1_results)
            logger.info(f"Run 1 complete: {len(run1_results)} results, hash={run1_hash[:16]}...")

            logger.info("Starting determinism run 2...")
            run2_results = self._run_single_generation(
                calculator, baseline_file, chunk_file, "run2"
            )
            run2_hash = self._hash_inspection_results(run2_results)
            logger.info(f"Run 2 complete: {len(run2_results)} results, hash={run2_hash[:16]}...")

            # Compare hashes
            match = run1_hash == run2_hash
            if match:
                logger.info("Determinism test PASSED: results are identical")
            else:
                logger.warning("Determinism test FAILED: results differ")
                # Log some differences for debugging
                self._log_result_differences(run1_results, run2_results)

            duration_ms = int((time.time() - start_time) * 1000)

            return create_determinism_result(
                name=test_name,
                seed=self.seed,
                run1_hash=f"sha256:{run1_hash}",
                run2_hash=f"sha256:{run2_hash}",
                match=match,
                duration_ms=duration_ms,
            )

        except Exception as e:
            logger.exception(f"Determinism test failed: {e}")
            duration_ms = int((time.time() - start_time) * 1000)
            return create_error_result(test_name, str(e), duration_ms)

    def _run_performance_test(self) -> TestResult:
        """
        Run the performance test.

        Measures chunk processing latencies and verifies p99 is below threshold.
        Optionally generates flamegraphs if async-profiler is available.

        Returns:
            TestResult with performance statistics.
        """
        from .reporting import create_performance_result as create_perf_result

        test_name = "performance"
        start_time = time.time()
        config = self.profile_config
        profiler = AsyncProfiler()
        flamegraph_path = None

        try:
            # Start server
            self.server = ServerManager()
            self.server.start()

            # Attach async-profiler if available
            if profiler.is_available():
                profiler.attach(self.server.process.pid)
                profiler.start_recording(
                    "chunk_processing",
                    event=config.profiler_event,
                    interval=config.profiler_interval,
                    include_pattern=config.include_pattern,
                )

            rcon = self.server.get_rcon()

            # Reset stats before warmup
            logger.info("Resetting performance stats...")
            rcon.profile_reset()

            # Generate warmup chunks
            if config.warmup_chunks > 0:
                logger.info(f"Generating {config.warmup_chunks} warmup chunks...")
                self._generate_chunks_for_profiling(rcon, config.warmup_chunks)

                # Reset stats after warmup
                rcon.profile_reset()

            # Generate test chunks
            logger.info(f"Generating {config.chunk_count} test chunks...")
            self._generate_chunks_for_profiling(rcon, config.chunk_count)

            # Collect stats
            logger.info("Collecting performance stats...")
            rcon.profile_stats()

            # Stop profiler and get flamegraph
            if profiler.is_available():
                flamegraph_path = profiler.stop_recording()

            # Stop server to flush logs
            self.server.stop()

        except Exception as e:
            logger.exception(f"Performance test failed: {e}")
            if profiler.is_available():
                profiler.cleanup()
            if self.server:
                self.server.cleanup()
                self.server = None
            duration_ms = int((time.time() - start_time) * 1000)
            return create_error_result(test_name, str(e), duration_ms)

        finally:
            if self.server:
                self.server.cleanup()
                self.server = None

        # Parse results from log
        server_log = get_server_log_path()
        results = extract_test_results(server_log)
        stats = parse_profile_stats(results)

        if not stats:
            duration_ms = int((time.time() - start_time) * 1000)
            return create_error_result(
                test_name,
                "No profile stats found in server output",
                duration_ms,
            )

        # Create result
        duration_ms = int((time.time() - start_time) * 1000)
        perf_result = create_performance_result(
            stats, config, duration_ms,
            flamegraph_path=flamegraph_path,
        )

        return create_perf_result(
            name=test_name,
            perf_result=perf_result,
            duration_ms=duration_ms,
        )

    def _generate_chunks_for_profiling(self, rcon, chunk_count: int) -> None:
        """
        Generate chunks for performance profiling.

        Creates a grid of chunks around spawn by force-loading them.

        Args:
            rcon: RCONClient for server communication.
            chunk_count: Number of chunks to generate.
        """
        # Calculate grid size (roughly square)
        grid_size = int(chunk_count ** 0.5) + 1

        # Create chunk list file
        chunk_file = PATHS.TEST_RESULTS_DIR / "perf_chunks.txt"
        PATHS.TEST_RESULTS_DIR.mkdir(parents=True, exist_ok=True)

        with open(chunk_file, "w") as f:
            count = 0
            half = grid_size // 2
            for x in range(-half, half + 1):
                for z in range(-half, half + 1):
                    if count >= chunk_count:
                        break
                    f.write(f"{x},{z}\n")
                    count += 1
                if count >= chunk_count:
                    break

        # Copy to server and force-load
        self.server.copy_file_to_server(chunk_file, "perf_chunks.txt")
        rcon.forceload_chunks("perf_chunks.txt")

        # Tick sprint for generation
        tick_count = get_tick_sprint_count(chunk_count)
        logger.info(f"Running tick sprint: {tick_count} ticks for {chunk_count} chunks")
        rcon.tick_sprint(tick_count)

        # Wait for generation to settle
        time.sleep(TIMEOUTS.GENERATION_SETTLE)

    def _run_single_generation(
        self,
        calculator: ChunkCalculator,
        baseline_file: Path,
        chunk_file: Path,
        run_name: str,
    ) -> List[BiomeInspectResult]:
        """
        Run a single world generation cycle and collect biome inspection results.

        Args:
            calculator: ChunkCalculator with loaded baseline data.
            baseline_file: Path to the baseline CSV file.
            chunk_file: Path to the chunk list file.
            run_name: Name for this run (for logging).

        Returns:
            List of BiomeInspectResult from this run.
        """
        self.server = ServerManager()
        try:
            self.server.start()

            # Copy files to server
            self.server.copy_file_to_server(chunk_file, "chunk_list.txt")
            self.server.copy_file_to_server(baseline_file, "baseline_coords.csv")

            # Force load chunks
            rcon = self.server.get_rcon()
            logger.info(f"[{run_name}] Force-loading {calculator.get_chunk_count()} chunks...")
            rcon.forceload_chunks("chunk_list.txt")

            # Tick sprint for generation
            tick_count = get_tick_sprint_count(calculator.get_chunk_count())
            logger.info(f"[{run_name}] Running tick sprint: {tick_count} ticks")
            rcon.tick_sprint(tick_count)

            # Wait for generation to settle
            time.sleep(TIMEOUTS.GENERATION_SETTLE)

            # Query biomes
            logger.info(f"[{run_name}] Querying biomes via batch_inspect...")
            rcon.batch_inspect("baseline_coords.csv")

            # Stop server to flush logs
            self.server.stop()

        finally:
            self.server.cleanup()
            self.server = None

        # Parse results from log
        server_log = get_server_log_path()
        results = extract_test_results(server_log)
        inspections, _ = parse_batch_inspect_results(results)

        return inspections

    def _hash_inspection_results(self, results: List[BiomeInspectResult]) -> str:
        """
        Create a deterministic hash of inspection results.

        Results are sorted by coordinates to ensure consistent ordering
        regardless of the order they were processed.

        Args:
            results: List of BiomeInspectResult to hash.

        Returns:
            SHA-256 hash as hex string.
        """
        # Sort by coordinates for deterministic ordering
        sorted_results = sorted(results, key=lambda r: (r.x, r.y, r.z))

        # Create a stable string representation
        data = []
        for r in sorted_results:
            data.append({
                "x": r.x,
                "y": r.y,
                "z": r.z,
                "actual_biome": r.actual_biome,
            })

        # Hash the JSON representation
        json_str = json.dumps(data, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(json_str.encode("utf-8")).hexdigest()

    def _log_result_differences(
        self,
        run1: List[BiomeInspectResult],
        run2: List[BiomeInspectResult],
    ) -> None:
        """Log differences between two result sets for debugging."""
        # Create lookup by coordinates
        run1_map = {(r.x, r.y, r.z): r for r in run1}
        run2_map = {(r.x, r.y, r.z): r for r in run2}

        differences = []
        all_coords = set(run1_map.keys()) | set(run2_map.keys())

        for coord in sorted(all_coords):
            r1 = run1_map.get(coord)
            r2 = run2_map.get(coord)

            if r1 is None and r2 is not None:
                differences.append(f"  {coord}: missing in run1, run2={r2.actual_biome}")
            elif r2 is None and r1 is not None:
                differences.append(f"  {coord}: run1={r1.actual_biome}, missing in run2")
            elif r1 is not None and r2 is not None and r1.actual_biome != r2.actual_biome:
                differences.append(f"  {coord}: run1={r1.actual_biome}, run2={r2.actual_biome}")

        if differences:
            logger.warning(f"Found {len(differences)} differences (showing first 10):")
            for diff in differences[:10]:
                logger.warning(diff)

    def _find_baseline_file(self) -> Optional[Path]:
        """Find a baseline CSV file to use for testing."""
        baseline_dir = PATHS.BASELINE_DIR

        if not baseline_dir.exists():
            logger.warning(f"Baseline directory not found: {baseline_dir}")
            return None

        # Look for CSV files
        csv_files = list(baseline_dir.glob("*.csv"))
        if not csv_files:
            logger.warning(f"No CSV files found in {baseline_dir}")
            return None

        # Prefer files matching our seed
        seed_str = str(self.seed)
        for csv_file in csv_files:
            if seed_str in csv_file.name:
                return csv_file

        # Fall back to first file
        logger.info(f"No baseline for seed {self.seed}, using {csv_files[0].name}")
        return csv_files[0]

    def _write_chunk_file(self, calculator: ChunkCalculator) -> Path:
        """Write chunk coordinates to a temporary file."""
        chunk_file = PATHS.TEST_RESULTS_DIR / "chunk_list.txt"
        PATHS.TEST_RESULTS_DIR.mkdir(parents=True, exist_ok=True)
        calculator.write_chunk_file(chunk_file)
        return chunk_file
