"""
Configuration constants for the test harness.

All timeout values, file paths, and constants are centralized here.
"""

from dataclasses import dataclass
from pathlib import Path
import os


@dataclass(frozen=True)
class TimeoutConfig:
    """Timeout values in seconds. All operations MUST have a timeout."""

    # Server lifecycle
    ZOMBIE_CLEANUP: int = 30
    SERVER_STARTUP: int = 180
    SERVER_SHUTDOWN: int = 30
    GRACEFUL_KILL: int = 10

    # RCON communication
    RCON_CONNECTION: int = 30
    RCON_COMMAND: int = 30
    RCON_RETRY_DELAY: int = 5
    RCON_MAX_RETRIES: int = 3
    RCON_READY_RETRIES: int = 10
    RCON_READY_DELAY: int = 2

    # Test execution
    TEST_EXECUTION: int = 600
    CHUNK_GENERATION: int = 400
    GENERATION_SETTLE: int = 3

    # File operations
    FILE_OPERATION: int = 30
    LOG_PARSING: int = 60
    WORLD_DELETION: int = 60


@dataclass(frozen=True)
class RCONConfig:
    """RCON connection settings."""

    HOST: str = "localhost"
    PORT: int = 25575
    PASSWORD: str = "test_password_12345"


@dataclass(frozen=True)
class ServerConfig:
    """Minecraft server settings."""

    PORT: int = 25565
    DEFAULT_SEED: int = -5704795430421488525


# Determine project root from this file's location
# scripts/test_harness/config.py -> project root is 3 levels up
_THIS_FILE = Path(__file__).resolve()
_SCRIPTS_DIR = _THIS_FILE.parent.parent
PROJECT_ROOT = _SCRIPTS_DIR.parent


@dataclass
class PathConfig:
    """File and directory paths. Resolved relative to project root."""

    def __init__(self, project_root: Path = PROJECT_ROOT):
        self.PROJECT_ROOT = project_root
        self.SCRIPTS_DIR = project_root / "scripts"
        self.TEST_SERVER_DIR = project_root / "test_server"
        self.TEST_TOOLS_DIR = project_root / "test_tools"
        self.TEST_RESULTS_DIR = project_root / "test_results"
        self.BASELINE_DIR = project_root / "baseline_data"
        self.BUILD_LIBS_DIR = project_root / "build" / "libs"

        # Executables
        self.MCRCON_PATH = self.TEST_TOOLS_DIR / "mcrcon"
        self.ASYNC_PROFILER_DIR = self.TEST_TOOLS_DIR / "async-profiler"
        self.SERVER_RUN_SCRIPT = self.TEST_SERVER_DIR / "run.sh"

        # Server directories
        self.SERVER_WORLD_DIR = self.TEST_SERVER_DIR / "world"
        self.SERVER_LOGS_DIR = self.TEST_SERVER_DIR / "logs"
        self.SERVER_MODS_DIR = self.TEST_SERVER_DIR / "mods"

        # Output files
        self.SERVER_STDOUT_LOG = self.TEST_RESULTS_DIR / "server_stdout.log"
        self.HARNESS_LOG = self.TEST_RESULTS_DIR / "harness.log"
        self.REPORT_JSON = self.TEST_RESULTS_DIR / "report.json"

        # Temp files (in server directory for command access)
        self.CHUNK_LIST_FILE = self.TEST_SERVER_DIR / "chunk_list.txt"
        self.BASELINE_COPY_FILE = self.TEST_SERVER_DIR / "baseline_coords.csv"


@dataclass(frozen=True)
class TestConfig:
    """Test execution settings."""

    DEFAULT_BUFFER_SIZE: int = 0
    PASS_THRESHOLD: float = 0.95

    # Tick sprint scaling based on chunk count
    TICK_SPRINT_BASE: int = 1000
    TICK_SPRINT_PER_100_CHUNKS: int = 500


# Global instances with default values
TIMEOUTS = TimeoutConfig()
RCON = RCONConfig()
SERVER = ServerConfig()
PATHS = PathConfig()
TEST = TestConfig()


def get_tick_sprint_count(chunk_count: int) -> int:
    """Calculate tick sprint count based on number of chunks to generate."""
    extra_ticks = (chunk_count // 100) * TEST.TICK_SPRINT_PER_100_CHUNKS
    return TEST.TICK_SPRINT_BASE + extra_ticks
