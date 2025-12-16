"""
Mod JAR management utilities.

Handles finding, comparing, and syncing mod JARs between build output
and the test server.
"""

import hashlib
import logging
import shutil
from pathlib import Path
from typing import Optional, Tuple

from .config import PATHS


logger = logging.getLogger("harness.jar_utils")

# Pattern to identify the mod JAR (excludes sources/javadoc)
MOD_JAR_PATTERN = "microbiomereplacer*.jar"
EXCLUDED_SUFFIXES = ("-sources.jar", "-javadoc.jar")


def find_mod_jar(directory: Path) -> Optional[Path]:
    """
    Find the mod JAR in a directory.

    Excludes -sources.jar and -javadoc.jar files.
    If multiple JARs match, returns the most recently modified one.

    Args:
        directory: Directory to search.

    Returns:
        Path to the mod JAR, or None if not found.
    """
    if not directory.exists():
        return None

    candidates = []
    for jar_path in directory.glob(MOD_JAR_PATTERN):
        # Skip sources and javadoc JARs
        if any(jar_path.name.endswith(suffix) for suffix in EXCLUDED_SUFFIXES):
            continue
        candidates.append(jar_path)

    if not candidates:
        return None

    # Return the most recently modified JAR
    return max(candidates, key=lambda p: p.stat().st_mtime)


def compute_file_hash(path: Path) -> str:
    """
    Compute SHA-256 hash of a file.

    Args:
        path: Path to the file.

    Returns:
        Hex-encoded SHA-256 hash.
    """
    sha256 = hashlib.sha256()
    with open(path, "rb") as f:
        # Read in chunks for memory efficiency
        for chunk in iter(lambda: f.read(8192), b""):
            sha256.update(chunk)
    return sha256.hexdigest()


def sync_mod_jar() -> Tuple[bool, str]:
    """
    Sync mod JAR from build directory to test server if different.

    Compares the JAR in build/libs/ with the one in test_server/mods/
    using SHA-256 hashing. If they differ (or server JAR is missing),
    copies the build JAR to the server.

    Returns:
        Tuple of (was_copied, message) where:
        - was_copied: True if a JAR was copied, False otherwise
        - message: Description of what happened (for logging)
    """
    build_jar = find_mod_jar(PATHS.BUILD_LIBS_DIR)
    server_jar = find_mod_jar(PATHS.SERVER_MODS_DIR)

    # No build JAR available
    if build_jar is None:
        return False, "No mod JAR found in build/libs/. Run './gradlew build' first."

    # No server JAR - copy the build JAR
    if server_jar is None:
        dest = PATHS.SERVER_MODS_DIR / build_jar.name
        shutil.copy2(build_jar, dest)
        return True, f"Copied mod JAR to server: {build_jar.name}"

    # Both exist - compare hashes
    build_hash = compute_file_hash(build_jar)
    server_hash = compute_file_hash(server_jar)

    if build_hash == server_hash:
        logger.debug(f"Mod JAR up to date: {server_jar.name}")
        return False, ""

    # Hashes differ - replace server JAR
    # Remove old JAR if name differs
    if server_jar.name != build_jar.name:
        server_jar.unlink()
        logger.debug(f"Removed old JAR: {server_jar.name}")

    dest = PATHS.SERVER_MODS_DIR / build_jar.name
    shutil.copy2(build_jar, dest)

    # Remove old JAR if it still exists (same name case)
    if server_jar.exists() and server_jar != dest:
        server_jar.unlink()

    return True, f"Updated mod JAR: {build_jar.name}"
