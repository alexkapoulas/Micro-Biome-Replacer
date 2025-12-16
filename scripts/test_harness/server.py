"""
Server lifecycle management for the Minecraft test server.

Handles starting, stopping, and monitoring the Minecraft server process.
Includes zombie process cleanup and graceful shutdown procedures.
"""

import logging
import os
import re
import shutil
import signal
import subprocess
import time
from pathlib import Path
from typing import Optional, List, Tuple

from .config import TIMEOUTS, PATHS, RCON, SERVER
from .exceptions import (
    ServerStartupError,
    ServerCrashError,
    PortInUseError,
)
from .rcon import RCONClient
from .output import get_server_log_path, ensure_log_dir


logger = logging.getLogger("harness.server")

# Regex pattern to detect server ready
READY_PATTERN = re.compile(r"Done \([0-9.]+s\)! For help")


class ServerManager:
    """
    Manages the lifecycle of the Minecraft test server.

    Provides methods for starting, stopping, and monitoring the server.
    All operations have timeouts to prevent hanging.
    """

    def __init__(
        self,
        server_dir: Optional[Path] = None,
        log_dir: Optional[Path] = None,
    ):
        """
        Initialize the server manager.

        Args:
            server_dir: Path to the test server directory.
            log_dir: Path to store output logs.
        """
        self.server_dir = server_dir or PATHS.TEST_SERVER_DIR
        self.log_dir = log_dir or PATHS.TEST_RESULTS_DIR
        self.run_script = self.server_dir / "run.sh"
        self.world_dir = self.server_dir / "world"

        self.process: Optional[subprocess.Popen] = None
        self.server_log_file: Optional[Path] = None
        self.server_log_handle = None
        self.rcon_client: Optional[RCONClient] = None

        # Verify server installation
        if not self.run_script.exists():
            raise FileNotFoundError(f"Server run script not found: {self.run_script}")

        logger.debug(f"ServerManager initialized: {self.server_dir}")

    def cleanup_zombie_processes(self, timeout: int = TIMEOUTS.ZOMBIE_CLEANUP) -> None:
        """
        Find and kill any zombie processes holding the server ports.

        Must be called before every server start attempt.

        Args:
            timeout: Total timeout for cleanup operation.

        Raises:
            PortInUseError: If ports cannot be freed within timeout.
        """
        logger.info("Checking for zombie processes on server ports...")
        start_time = time.time()

        ports_to_check = [SERVER.PORT, RCON.PORT]
        pids_to_kill: List[int] = []

        # Find processes listening on our ports using ss
        for port in ports_to_check:
            try:
                result = subprocess.run(
                    ["ss", "-tlnp", f"sport = :{port}"],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )

                # Parse ss output for PIDs
                # Example output line: LISTEN 0 128 *:25565 *:* users:(("java",pid=12345,fd=10))
                for line in result.stdout.splitlines():
                    if f":{port}" in line:
                        # Extract PID from users:((..."pid=XXXXX"...))
                        pid_match = re.search(r'pid=(\d+)', line)
                        if pid_match:
                            pid = int(pid_match.group(1))
                            if pid not in pids_to_kill:
                                pids_to_kill.append(pid)
                                logger.warning(f"Found process {pid} on port {port}")

            except subprocess.TimeoutExpired:
                logger.warning(f"Timeout checking port {port}")
            except Exception as e:
                logger.warning(f"Error checking port {port}: {e}")

        if not pids_to_kill:
            logger.info("No zombie processes found")
            return

        # Kill found processes
        for pid in pids_to_kill:
            self._kill_process(pid, start_time, timeout)

        # Verify ports are free
        if not self._wait_for_ports_free(ports_to_check, start_time, timeout):
            raise PortInUseError(
                f"Could not free ports {ports_to_check} within {timeout}s"
            )

        logger.info("Zombie process cleanup complete")

    def _kill_process(self, pid: int, start_time: float, timeout: int) -> None:
        """Kill a process with escalating signals."""
        try:
            # Check if process exists
            os.kill(pid, 0)
        except ProcessLookupError:
            logger.debug(f"Process {pid} already gone")
            return
        except PermissionError:
            logger.warning(f"No permission to check process {pid}")
            return

        # Send SIGTERM first
        logger.info(f"Sending SIGTERM to process {pid}")
        try:
            os.kill(pid, signal.SIGTERM)
        except (ProcessLookupError, PermissionError):
            return

        # Wait for termination
        for _ in range(3):
            time.sleep(1)
            if time.time() - start_time > timeout:
                break
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                logger.info(f"Process {pid} terminated gracefully")
                return

        # Send SIGKILL if still running
        logger.warning(f"Process {pid} didn't terminate, sending SIGKILL")
        try:
            os.kill(pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass

    def _wait_for_ports_free(
        self,
        ports: List[int],
        start_time: float,
        timeout: int,
    ) -> bool:
        """Wait for ports to become free (TIME_WAIT to clear)."""
        while time.time() - start_time < timeout:
            all_free = True
            for port in ports:
                try:
                    result = subprocess.run(
                        ["ss", "-tlnp", f"sport = :{port}"],
                        capture_output=True,
                        text=True,
                        timeout=5,
                    )
                    if f":{port}" in result.stdout and "LISTEN" in result.stdout:
                        all_free = False
                        break
                except Exception:
                    pass

            if all_free:
                return True

            time.sleep(1)

        return False

    def delete_world(self, timeout: int = TIMEOUTS.WORLD_DELETION) -> None:
        """
        Delete the existing world directory for clean state.

        Args:
            timeout: Timeout for deletion operation.
        """
        if not self.world_dir.exists():
            logger.debug("No world directory to delete")
            return

        logger.info(f"Deleting world directory: {self.world_dir}")
        start_time = time.time()

        try:
            shutil.rmtree(self.world_dir, ignore_errors=False)
            logger.info("World directory deleted")
        except Exception as e:
            elapsed = time.time() - start_time
            if elapsed > timeout:
                logger.warning(f"World deletion timed out after {timeout}s")
            else:
                logger.warning(f"Error deleting world directory: {e}")
            # Continue anyway - files will be overwritten

    def start(self, timeout: int = TIMEOUTS.SERVER_STARTUP) -> None:
        """
        Start the Minecraft server.

        This method:
        1. Cleans up zombie processes
        2. Deletes existing world
        3. Starts the server process
        4. Waits for server to be ready (log + RCON)

        Args:
            timeout: Total timeout for server startup.

        Raises:
            ServerStartupError: If server fails to start within timeout.
        """
        logger.info("Starting Minecraft server...")
        start_time = time.time()

        # Pre-start cleanup
        self.cleanup_zombie_processes()
        self.delete_world()

        # Ensure log directory exists
        ensure_log_dir(self.log_dir)
        self.server_log_file = get_server_log_path(self.log_dir)

        # Open log file for server output
        self.server_log_handle = open(self.server_log_file, "w", encoding="utf-8")

        # Start server process
        logger.info(f"Launching server: {self.run_script} nogui")
        try:
            self.process = subprocess.Popen(
                [str(self.run_script), "nogui"],
                cwd=str(self.server_dir),
                stdout=self.server_log_handle,
                stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
            )
            logger.info(f"Server process started with PID {self.process.pid}")
        except Exception as e:
            self.server_log_handle.close()
            raise ServerStartupError(f"Failed to start server process: {e}") from e

        # Wait for ready
        remaining_timeout = timeout - (time.time() - start_time)
        self._wait_for_ready(int(remaining_timeout))

        # Initialize RCON client
        self.rcon_client = RCONClient()
        logger.info("Server startup complete")

    def _wait_for_ready(self, timeout: int) -> None:
        """
        Wait for server to become ready.

        Polls the server log for the ready message, then verifies RCON connectivity.

        Args:
            timeout: Timeout in seconds.

        Raises:
            ServerStartupError: If server doesn't become ready within timeout.
            ServerCrashError: If server process exits unexpectedly.
        """
        logger.info(f"Waiting for server ready (timeout: {timeout}s)...")
        start_time = time.time()
        log_ready = False

        # Phase 1: Wait for log ready message
        while time.time() - start_time < timeout:
            # Check if process died
            if self.process and self.process.poll() is not None:
                exit_code = self.process.returncode
                logger.error(f"Server process exited with code {exit_code}")
                raise ServerCrashError(f"Server exited during startup with code {exit_code}")

            # Check log file for ready message
            if self.server_log_file and self.server_log_file.exists():
                try:
                    with open(self.server_log_file, "r", encoding="utf-8") as f:
                        content = f.read()
                        if READY_PATTERN.search(content):
                            logger.info("Server ready message detected in log")
                            log_ready = True
                            break
                except Exception as e:
                    logger.debug(f"Error reading log file: {e}")

            time.sleep(2)

        if not log_ready:
            raise ServerStartupError(f"Server did not become ready within {timeout}s")

        # Phase 2: Verify RCON connectivity
        remaining = timeout - (time.time() - start_time)
        if remaining < 10:
            remaining = 10  # Give RCON at least 10 seconds

        rcon_client = RCONClient()
        for attempt in range(TIMEOUTS.RCON_READY_RETRIES):
            if rcon_client.test_connection(timeout=int(remaining / TIMEOUTS.RCON_READY_RETRIES)):
                logger.info("RCON connection verified")
                return

            if time.time() - start_time >= timeout:
                break

            logger.debug(f"RCON not ready, waiting {TIMEOUTS.RCON_READY_DELAY}s...")
            time.sleep(TIMEOUTS.RCON_READY_DELAY)

        raise ServerStartupError("Server started but RCON connection failed")

    def stop(self, timeout: int = TIMEOUTS.SERVER_SHUTDOWN) -> None:
        """
        Stop the server gracefully.

        Sequence:
        1. save-all via RCON
        2. stop via RCON
        3. Wait for process exit
        4. SIGTERM if still running
        5. SIGKILL as last resort

        Args:
            timeout: Timeout for graceful shutdown.
        """
        if self.process is None:
            logger.debug("No server process to stop")
            return

        logger.info("Stopping server...")
        start_time = time.time()

        # Try RCON commands
        if self.rcon_client:
            try:
                logger.debug("Sending save-all command")
                self.rcon_client.save_all()
                time.sleep(3)
            except Exception as e:
                logger.warning(f"save-all failed: {e}")

            try:
                logger.debug("Sending stop command")
                self.rcon_client.stop()
            except Exception as e:
                logger.warning(f"stop command failed: {e}")

        # Wait for graceful exit
        try:
            self.process.wait(timeout=timeout)
            logger.info(f"Server stopped gracefully (exit code: {self.process.returncode})")
        except subprocess.TimeoutExpired:
            logger.warning("Server didn't stop gracefully, sending SIGTERM")
            self._force_stop()

        self._cleanup_handles()

    def _force_stop(self) -> None:
        """Force stop the server with escalating signals."""
        if self.process is None:
            return

        # SIGTERM
        try:
            self.process.terminate()
            self.process.wait(timeout=TIMEOUTS.GRACEFUL_KILL)
            logger.info("Server terminated with SIGTERM")
            return
        except subprocess.TimeoutExpired:
            pass
        except Exception as e:
            logger.warning(f"SIGTERM failed: {e}")

        # SIGKILL
        logger.warning("Server didn't respond to SIGTERM, sending SIGKILL")
        try:
            self.process.kill()
            self.process.wait(timeout=5)
            logger.info("Server killed with SIGKILL")
        except Exception as e:
            logger.error(f"Failed to kill server: {e}")

    def _cleanup_handles(self) -> None:
        """Clean up file handles and references."""
        if self.server_log_handle:
            try:
                self.server_log_handle.close()
            except Exception:
                pass
            self.server_log_handle = None

        self.process = None
        self.rcon_client = None

    def cleanup(self) -> None:
        """
        Ensure server is stopped and resources are released.

        This method should never raise exceptions. Safe to call in finally blocks.
        """
        try:
            self.stop()
        except Exception as e:
            logger.warning(f"Error during cleanup stop: {e}")

        try:
            self._cleanup_handles()
        except Exception as e:
            logger.warning(f"Error during cleanup handles: {e}")

        # Force kill if somehow still running
        if self.process is not None:
            try:
                self.process.kill()
            except Exception:
                pass
            self.process = None

    def is_running(self) -> bool:
        """Check if the server process is running."""
        if self.process is None:
            return False
        return self.process.poll() is None

    def get_rcon(self) -> RCONClient:
        """Get the RCON client for the running server."""
        if self.rcon_client is None:
            raise RuntimeError("Server is not running or RCON not initialized")
        return self.rcon_client

    def copy_file_to_server(self, source: Path, dest_name: str) -> Path:
        """
        Copy a file to the server directory.

        Args:
            source: Source file path.
            dest_name: Destination filename (in server directory).

        Returns:
            Path to the copied file in the server directory.
        """
        dest = self.server_dir / dest_name
        shutil.copy2(source, dest)
        logger.debug(f"Copied {source} to {dest}")
        return dest
