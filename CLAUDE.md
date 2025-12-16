# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build              # Build the mod JAR
./gradlew runClient          # Launch Minecraft client with mod loaded
./gradlew runServer          # Launch dedicated server with mod
./gradlew runGameTestServer  # Run registered game tests
./gradlew runData            # Run data generators
./gradlew --refresh-dependencies  # Refresh dependency cache
```

## Testing Commands

```bash
./scripts/test.sh all           # Run all tests (accuracy + determinism)
./scripts/test.sh accuracy      # Run accuracy test only
./scripts/test.sh determinism   # Run determinism test only
./scripts/test.sh performance   # Run performance test only
./scripts/test.sh --threshold 0.80  # Lower pass threshold (default: 0.95)
./scripts/test.sh --buffer 1    # Add 1-chunk buffer around coordinates
./scripts/test.sh --debug       # Enable debug logging to console
```

### Performance Testing Commands

```bash
./scripts/test.sh performance                    # Run performance test (default: 500 chunks)
./scripts/test.sh performance --chunk-count 1000 # Generate more chunks
./scripts/test.sh performance --p99-threshold 30  # Stricter threshold (30ms)
./scripts/test.sh performance --warmup-chunks 200    # More warmup
```

### Test Harness Overview

The test harness (`scripts/test_harness/`) automates validation of micro biome replacement:

- **Accuracy Test**: Verifies biomes at known micro biome coordinates are replaced after world generation
- **Determinism Test**: Runs accuracy test twice, comparing SHA-256 hashes to ensure identical results
- **Performance Test**: Measures chunk processing latencies and verifies p99 < threshold

### Key Files

| Path | Purpose |
|------|---------|
| `scripts/test_harness/` | Python test harness package |
| `scripts/test.sh` | Bash wrapper for running tests |
| `baseline_data/` | CSV files with known micro biome coordinates |
| `test_server/` | NeoForge server for testing |
| `test_results/` | Output: `report.json`, `harness.log`, `server_stdout.log` |

### Test Mod Commands (RCON)

The mod registers commands for automated testing via RCON:

```
/microbiome inspect <x> <y> <z>       # Query biome at coordinates
/microbiome batch_inspect <file>      # Batch query from CSV file
/microbiome forceload_chunks <file>   # Force-load chunks from file
```

All commands output JSON with `[TEST_RESULT]` prefix for parsing.

### Profiling Commands (RCON)

Commands for performance profiling:

```
/microbiome profile stats             # Output p50/p90/p99 latencies as JSON
/microbiome profile reset             # Reset all statistics
/microbiome profile export <file>     # Export stats to JSON file
```

Example output from `/microbiome profile stats`:
```json
{"command":"profile_stats","count":500,"mean_ms":1.23,"p50_ms":0.80,"p90_ms":2.00,"p99_ms":5.00,...}
```

### JFR Recording

The mod emits JFR (Java Flight Recorder) events for detailed per-chunk analysis:

```bash
# Start server with JFR recording
java -XX:StartFlightRecording=filename=mbr.jfr,settings=profile -jar server.jar

# View with JDK Mission Control
jmc mbr.jfr
```

Event name: `microbiomereplacer.ChunkProcessing`
Fields: `chunkX`, `chunkZ`, `durationNanos`, `positionsProcessed`, `replacementsMade`, `skippedHomogeneous`

### Flamegraph Generation

If async-profiler is installed at `test_tools/async-profiler/`, the performance test will automatically generate a flamegraph to `test_results/flamegraphs/chunk_processing.html`.

The flamegraph is filtered with `-I com/example/alexthundercook/microbiomereplacer/*` to show only the mod's methods.

## Project Overview

This is a NeoForge 1.21.1 mod that removes "micro biomes" (isolated biome patches below a configurable size threshold) during world generation. The mod uses Mixin to inject into the biome generation pipeline.

**Target**: NeoForge 1.21.1, Java 21, Overworld only

## Architecture

### Critical Timing Constraint

Biome modifications **must** occur during the BIOMES chunk generation stage, **before** the NOISE stage begins. The NOISE stage uses biome data to shape terrain—modifying biomes after NOISE creates terrain/biome mismatches.

### Injection Point

Mixin target: `ChunkGenerator.createBiomes()` at `@At("TAIL")`

This provides access to:
- `ChunkGenerator` (for `getBaseHeight()` and `getBiomeSource()`)
- `RandomState` (for `sampler()` and height queries)
- `ChunkAccess` (the chunk with populated biome data)

### Key Technical Insights

1. **BiomeSource pre-query**: `BiomeSource.getNoiseBiome()` computes biomes for any coordinate from world seed alone—no neighbor chunks required. This enables cross-chunk region detection without synchronization.

2. **Deterministic height sampling**: `ChunkGenerator.getBaseHeight()` returns terrain height from noise calculations without chunk generation, enabling accurate surface-level biome sampling.

3. **Biome coordinates**: Biomes use 4×4×4 block resolution ("quarts"). Block coordinate `(x, y, z)` → biome coordinate `(x >> 2, y >> 2, z >> 2)`.

### Algorithm Summary

1. For each biome position in chunk's 4×4 surface grid
2. Flood fill to find contiguous region (early-exit when threshold exceeded)
3. Cross-chunk queries via `biomeSource.getNoiseBiome()`
4. If region < threshold: replace with dominant neighbor biome
5. Deterministic tie-breaking ensures consistent results across chunks

### File Structure

- `src/main/java/.../MicroBiomeReplacer.java` - Main mod entry point
- `src/main/java/.../MicroBiomeProcessor.java` - Core biome replacement algorithm
- `src/main/java/.../mixin/` - Mixin classes for injection
- `src/main/java/.../profiling/` - Performance profiling infrastructure
  - `ChunkProcessingEvent.java` - JFR custom event
  - `PerformanceStats.java` - Thread-safe statistics collector
  - `ProfileCommands.java` - Server commands for profiling
- `src/main/resources/microbiomereplacer.mixins.json` - Mixin config
- `scripts/test_harness/profiling.py` - Python profiling integration

### Performance Targets

- ThreadLocal object pools to eliminate GC pressure
- BitSet for visited tracking instead of HashSet
- Cache 16 height queries per chunk
- Early-exit flood fill bounds worst-case to O(threshold)
- Quick characterization pass skips homogeneous chunks
