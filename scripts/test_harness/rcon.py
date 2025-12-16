"""
RCON client wrapper for communicating with the Minecraft server.

Wraps the mcrcon CLI tool with timeout handling and retry logic.
All commands and responses are logged to the harness log file.
"""

import logging
import subprocess
import time
from pathlib import Path
from typing import Optional

from .config import RCON, TIMEOUTS, PATHS
from .exceptions import RCONConnectionError, RCONTimeoutError, RCONCommandError


logger = logging.getLogger("harness.rcon")


class RCONClient:
    """
    RCON client that wraps the mcrcon CLI tool.

    All commands are executed with timeouts to prevent hanging.
    Connection failures trigger automatic retries.
    """

    def __init__(
        self,
        host: str = RCON.HOST,
        port: int = RCON.PORT,
        password: str = RCON.PASSWORD,
        mcrcon_path: Optional[Path] = None,
    ):
        """
        Initialize the RCON client.

        Args:
            host: RCON server host.
            port: RCON server port.
            password: RCON password.
            mcrcon_path: Path to mcrcon executable. Defaults to scripts/mcrcon.
        """
        self.host = host
        self.port = port
        self.password = password
        self.mcrcon_path = mcrcon_path or PATHS.MCRCON_PATH

        if not self.mcrcon_path.exists():
            raise FileNotFoundError(f"mcrcon not found at {self.mcrcon_path}")

        logger.debug(f"RCON client initialized: {host}:{port}")

    def send_command(
        self,
        command: str,
        timeout: int = TIMEOUTS.RCON_COMMAND,
        retries: int = TIMEOUTS.RCON_MAX_RETRIES,
        retry_delay: int = TIMEOUTS.RCON_RETRY_DELAY,
    ) -> str:
        """
        Send a command to the server via RCON.

        Args:
            command: The Minecraft command to execute (without leading /).
            timeout: Command timeout in seconds.
            retries: Number of retry attempts on connection failure.
            retry_delay: Delay between retries in seconds.

        Returns:
            The command output from the server.

        Raises:
            RCONConnectionError: Failed to connect after all retries.
            RCONTimeoutError: Command timed out.
            RCONCommandError: Command execution failed.
        """
        logger.debug(f"RCON command: {command}")

        last_error: Optional[Exception] = None

        for attempt in range(retries + 1):
            try:
                result = self._execute_mcrcon(command, timeout)
                logger.debug(f"RCON response: {result[:200]}..." if len(result) > 200 else f"RCON response: {result}")
                return result

            except subprocess.TimeoutExpired as e:
                logger.warning(f"RCON command timed out after {timeout}s: {command}")
                raise RCONTimeoutError(f"Command timed out after {timeout}s: {command}") from e

            except subprocess.CalledProcessError as e:
                last_error = e
                if attempt < retries:
                    logger.warning(
                        f"RCON connection failed (attempt {attempt + 1}/{retries + 1}), "
                        f"retrying in {retry_delay}s..."
                    )
                    time.sleep(retry_delay)
                else:
                    logger.error(f"RCON connection failed after {retries + 1} attempts")

            except Exception as e:
                last_error = e
                if attempt < retries:
                    logger.warning(
                        f"RCON error: {e} (attempt {attempt + 1}/{retries + 1}), "
                        f"retrying in {retry_delay}s..."
                    )
                    time.sleep(retry_delay)
                else:
                    logger.error(f"RCON failed after {retries + 1} attempts: {e}")

        raise RCONConnectionError(
            f"Failed to execute RCON command after {retries + 1} attempts: {command}"
        ) from last_error

    def _execute_mcrcon(self, command: str, timeout: int) -> str:
        """
        Execute mcrcon subprocess with the given command.

        Args:
            command: The command to execute.
            timeout: Timeout in seconds.

        Returns:
            The command output.

        Raises:
            subprocess.TimeoutExpired: If the command times out.
            subprocess.CalledProcessError: If mcrcon returns non-zero exit code.
        """
        cmd = [
            str(self.mcrcon_path),
            "-H", self.host,
            "-P", str(self.port),
            "-p", self.password,
            command,
        ]

        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=True,
        )

        return result.stdout.strip()

    def test_connection(self, timeout: int = TIMEOUTS.RCON_CONNECTION) -> bool:
        """
        Test if the RCON connection is working.

        Args:
            timeout: Connection timeout in seconds.

        Returns:
            True if connection succeeded, False otherwise.
        """
        try:
            # Use 'list' command as a simple connectivity test
            self.send_command("list", timeout=timeout, retries=0)
            logger.info("RCON connection test successful")
            return True
        except Exception as e:
            logger.warning(f"RCON connection test failed: {e}")
            return False

    def say(self, message: str) -> str:
        """Send a chat message to the server."""
        return self.send_command(f"say {message}")

    def save_all(self) -> str:
        """Save all world data."""
        return self.send_command("save-all")

    def stop(self) -> str:
        """Stop the server gracefully."""
        return self.send_command("stop")

    def forceload_chunks(self, chunk_file: str) -> str:
        """
        Force-load chunks from a file.

        Args:
            chunk_file: Filename in server directory containing chunk coordinates.

        Returns:
            Command response.
        """
        # Use longer timeout for chunk loading operations
        return self.send_command(
            f"microbiome forceload_chunks {chunk_file}",
            timeout=TIMEOUTS.CHUNK_GENERATION,
        )

    def batch_inspect(self, baseline_file: str) -> str:
        """
        Batch inspect biomes from a baseline file.

        Args:
            baseline_file: Filename in server directory containing coordinates.

        Returns:
            Command response.
        """
        # Use longer timeout for batch operations
        return self.send_command(
            f"microbiome batch_inspect {baseline_file}",
            timeout=TIMEOUTS.CHUNK_GENERATION,
        )

    def tick_sprint(self, ticks: int) -> str:
        """
        Run tick sprint for accelerated chunk generation.

        Args:
            ticks: Number of ticks to sprint.

        Returns:
            Command response.
        """
        return self.send_command(f"tick sprint {ticks}")

    def profile_stats(self) -> str:
        """
        Get performance profiling statistics.

        Returns:
            Command response with JSON stats.
        """
        return self.send_command("microbiome profile stats")

    def profile_reset(self) -> str:
        """
        Reset performance profiling statistics.

        Returns:
            Command response.
        """
        return self.send_command("microbiome profile reset")

    def profile_export(self, filename: str) -> str:
        """
        Export performance statistics to a file.

        Args:
            filename: Filename to write in server directory.

        Returns:
            Command response.
        """
        return self.send_command(f"microbiome profile export {filename}")
