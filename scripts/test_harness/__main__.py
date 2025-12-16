"""
CLI entry point for the test harness.

Usage:
    python -m test_harness all           # Run all tests
    python -m test_harness accuracy      # Run accuracy tests only
    python -m test_harness performance   # Run performance tests only
    python -m test_harness --seed 12345  # Override world seed
    python -m test_harness --buffer 2    # Use larger chunk buffer
"""

import argparse
import sys
import logging

from .config import TEST, SERVER
from .output import setup_output, logger
from .profiling import ProfileConfig
from .runner import TestRunner


def parse_args() -> argparse.Namespace:
    """Parse command-line arguments."""
    parser = argparse.ArgumentParser(
        prog="test_harness",
        description="Automated test harness for Micro Biome Replacer mod",
    )

    parser.add_argument(
        "command",
        nargs="?",
        default="all",
        choices=["all", "accuracy", "determinism", "performance"],
        help="Test suite to run (default: all)",
    )

    parser.add_argument(
        "--seed",
        type=int,
        default=SERVER.DEFAULT_SEED,
        help=f"World seed for testing (default: {SERVER.DEFAULT_SEED})",
    )

    parser.add_argument(
        "--buffer",
        type=int,
        default=TEST.DEFAULT_BUFFER_SIZE,
        choices=[0, 1, 2],
        help=f"Chunk buffer size around coordinates (default: {TEST.DEFAULT_BUFFER_SIZE})",
    )

    parser.add_argument(
        "--threshold",
        type=float,
        default=TEST.PASS_THRESHOLD,
        help=f"Pass threshold for accuracy tests (default: {TEST.PASS_THRESHOLD})",
    )

    parser.add_argument(
        "--p99-threshold",
        type=float,
        default=50.0,
        help="P99 latency threshold in milliseconds for performance tests (default: 50)",
    )

    parser.add_argument(
        "--chunk-count",
        type=int,
        default=500,
        help="Number of chunks to generate for performance tests (default: 500)",
    )

    parser.add_argument(
        "--warmup-chunks",
        type=int,
        default=100,
        help="Number of warmup chunks before performance measurement (default: 100)",
    )

    parser.add_argument(
        "--debug",
        action="store_true",
        help="Enable debug logging to console",
    )

    return parser.parse_args()


def main() -> int:
    """
    Main entry point.

    Returns:
        Exit code: 0 for success, 1 for test failures, 2 for harness errors.
    """
    args = parse_args()

    # Initialize output system
    setup_output()

    # Add console handler in debug mode
    if args.debug:
        console_handler = logging.StreamHandler(sys.stderr)
        console_handler.setLevel(logging.DEBUG)
        console_handler.setFormatter(
            logging.Formatter("[%(levelname)s] %(name)s: %(message)s")
        )
        logging.getLogger().addHandler(console_handler)

    logger.info(f"Test harness starting: command={args.command}")
    logger.info(f"Settings: seed={args.seed}, buffer={args.buffer}, threshold={args.threshold}")

    # Create profile config from CLI args
    profile_config = ProfileConfig(
        p99_threshold_ms=args.p99_threshold,
        chunk_count=args.chunk_count,
        warmup_chunks=args.warmup_chunks,
    )

    try:
        runner = TestRunner(
            seed=args.seed,
            buffer_size=args.buffer,
            threshold=args.threshold,
            profile_config=profile_config,
        )

        if args.command == "all":
            success = runner.run_all()
        elif args.command == "accuracy":
            success = runner.run_accuracy()
        elif args.command == "determinism":
            success = runner.run_determinism()
        elif args.command == "performance":
            success = runner.run_tests(["performance"])
        else:
            logger.error(f"Unknown command: {args.command}")
            return 2

        return 0 if success else 1

    except KeyboardInterrupt:
        logger.warning("Interrupted by user")
        return 130

    except Exception as e:
        logger.exception(f"Harness error: {e}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
