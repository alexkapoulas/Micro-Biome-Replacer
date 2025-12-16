"""
Chunk calculation utilities for selective chunk loading.

Handles reading baseline CSV files, calculating which chunks need to be
loaded, and writing chunk list files for the batch forceload command.
"""

import csv
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import List, Set, Tuple, Optional

from .config import TEST
from .exceptions import BaselineError, ChunkCalculationError


logger = logging.getLogger("harness.chunks")


@dataclass
class BaselineCoordinate:
    """A single coordinate from the baseline CSV."""
    biome_id: str
    x: int
    z: int
    surface_y: int
    is_block_coord: bool = True


class ChunkCalculator:
    """
    Calculates which chunks need to be loaded for testing.

    Reads baseline coordinates and determines the minimal set of chunks
    required, optionally with a buffer around each coordinate.
    """

    def __init__(self):
        """Initialize the chunk calculator."""
        self.coordinates: List[BaselineCoordinate] = []
        self.chunks: Set[Tuple[int, int]] = set()
        self.buffer_size: int = TEST.DEFAULT_BUFFER_SIZE

    def load_baseline(self, csv_path: Path) -> int:
        """
        Load coordinates from a baseline CSV file.

        CSV format: biome_id,x,z,surface_y,is_block_coord

        Args:
            csv_path: Path to the baseline CSV file.

        Returns:
            Number of coordinates loaded.

        Raises:
            BaselineError: If the file cannot be read or parsed.
        """
        logger.info(f"Loading baseline: {csv_path}")

        if not csv_path.exists():
            raise BaselineError(f"Baseline file not found: {csv_path}")

        self.coordinates = []

        try:
            with open(csv_path, "r", encoding="utf-8") as f:
                reader = csv.DictReader(f)

                for row_num, row in enumerate(reader, start=2):
                    try:
                        coord = BaselineCoordinate(
                            biome_id=row["biome_id"].strip(),
                            x=int(row["x"]),
                            z=int(row["z"]),
                            surface_y=int(row["surface_y"]),
                            is_block_coord=row.get("is_block_coord", "true").lower() == "true",
                        )
                        self.coordinates.append(coord)
                    except (KeyError, ValueError) as e:
                        logger.warning(f"Skipping invalid row {row_num}: {e}")

        except Exception as e:
            raise BaselineError(f"Failed to read baseline file: {e}") from e

        logger.info(f"Loaded {len(self.coordinates)} coordinates")
        return len(self.coordinates)

    def calculate_chunks(self, buffer_size: int = TEST.DEFAULT_BUFFER_SIZE) -> int:
        """
        Calculate unique chunks needed for loaded coordinates.

        For each coordinate, calculates the chunk it belongs to and optionally
        adds surrounding buffer chunks. Results are deduplicated.

        Args:
            buffer_size: Number of chunks to buffer around each coordinate.
                         0 = no buffer, 1 = 8 neighbors, 2 = 24 neighbors.

        Returns:
            Number of unique chunks calculated.

        Raises:
            ChunkCalculationError: If no coordinates have been loaded.
        """
        if not self.coordinates:
            raise ChunkCalculationError("No coordinates loaded. Call load_baseline first.")

        self.buffer_size = buffer_size
        self.chunks = set()

        for coord in self.coordinates:
            # Convert block coordinates to chunk coordinates
            # Block -> Chunk: chunkX = blockX >> 4 (divide by 16)
            chunk_x = coord.x >> 4
            chunk_z = coord.z >> 4

            # Add base chunk
            self.chunks.add((chunk_x, chunk_z))

            # Add buffer chunks if requested
            if buffer_size > 0:
                for dx in range(-buffer_size, buffer_size + 1):
                    for dz in range(-buffer_size, buffer_size + 1):
                        self.chunks.add((chunk_x + dx, chunk_z + dz))

        logger.info(
            f"Calculated {len(self.chunks)} unique chunks "
            f"(buffer={buffer_size}, coords={len(self.coordinates)})"
        )
        return len(self.chunks)

    def write_chunk_file(self, output_path: Path) -> Path:
        """
        Write chunk coordinates to a file for the forceload command.

        Format: chunkX,chunkZ (one per line)

        Args:
            output_path: Path to write the chunk file.

        Returns:
            Path to the written file.

        Raises:
            ChunkCalculationError: If no chunks have been calculated.
        """
        if not self.chunks:
            raise ChunkCalculationError("No chunks calculated. Call calculate_chunks first.")

        logger.info(f"Writing {len(self.chunks)} chunks to {output_path}")

        try:
            with open(output_path, "w", encoding="utf-8") as f:
                for chunk_x, chunk_z in sorted(self.chunks):
                    f.write(f"{chunk_x},{chunk_z}\n")
        except Exception as e:
            raise ChunkCalculationError(f"Failed to write chunk file: {e}") from e

        return output_path

    def get_chunk_count(self) -> int:
        """Get the number of calculated chunks."""
        return len(self.chunks)

    def get_coordinate_count(self) -> int:
        """Get the number of loaded coordinates."""
        return len(self.coordinates)

    def get_coordinates(self) -> List[BaselineCoordinate]:
        """Get the loaded coordinates."""
        return self.coordinates.copy()

    def get_bounding_box(self) -> Optional[Tuple[int, int, int, int]]:
        """
        Get the bounding box of loaded coordinates.

        Returns:
            Tuple of (min_x, min_z, max_x, max_z) or None if no coordinates.
        """
        if not self.coordinates:
            return None

        min_x = min(c.x for c in self.coordinates)
        max_x = max(c.x for c in self.coordinates)
        min_z = min(c.z for c in self.coordinates)
        max_z = max(c.z for c in self.coordinates)

        return (min_x, min_z, max_x, max_z)

    def get_stats(self) -> dict:
        """Get statistics about the loaded data."""
        bbox = self.get_bounding_box()
        return {
            "coordinates": len(self.coordinates),
            "chunks": len(self.chunks),
            "buffer_size": self.buffer_size,
            "bounding_box": {
                "min_x": bbox[0] if bbox else None,
                "min_z": bbox[1] if bbox else None,
                "max_x": bbox[2] if bbox else None,
                "max_z": bbox[3] if bbox else None,
            } if bbox else None,
            "unique_biomes": len(set(c.biome_id for c in self.coordinates)),
        }
