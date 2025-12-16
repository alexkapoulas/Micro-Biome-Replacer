"""
Custom exception types for the test harness.

Each exception represents a distinct failure mode, enabling precise
error handling and clear error messages.
"""


class HarnessError(Exception):
    """Base exception for all harness errors."""
    pass


class ServerStartupError(HarnessError):
    """Server failed to start within the timeout period."""
    pass


class ServerCrashError(HarnessError):
    """Server process exited unexpectedly during operation."""
    pass


class RCONConnectionError(HarnessError):
    """Failed to establish RCON connection to the server."""
    pass


class RCONTimeoutError(HarnessError):
    """RCON command timed out waiting for response."""
    pass


class RCONCommandError(HarnessError):
    """RCON command failed with an error response."""
    pass


class PortInUseError(HarnessError):
    """Required port is already in use and could not be freed."""
    pass


class TestTimeoutError(HarnessError):
    """Test execution exceeded the allowed time limit."""
    pass


class BaselineError(HarnessError):
    """Error loading or parsing baseline CSV data."""
    pass


class ChunkCalculationError(HarnessError):
    """Error calculating chunks from baseline coordinates."""
    pass


class LogParsingError(HarnessError):
    """Error parsing test results from server log."""
    pass
