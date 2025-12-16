package com.example.alexthundercook.microbiomereplacer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.alexthundercook.microbiomereplacer.BiomeReplacementRegistry;
import com.example.alexthundercook.microbiomereplacer.config.ModConfig;
import com.example.alexthundercook.microbiomereplacer.profiling.ChunkProcessingEvent;
import com.example.alexthundercook.microbiomereplacer.profiling.PerformanceStats;
import com.example.alexthundercook.microbiomereplacer.util.ObjectPools;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Main processor for micro biome detection and replacement.
 *
 * This class is called from the ChunkGeneratorMixin after vanilla biome
 * generation completes. It processes the chunk to detect and replace
 * micro biomes (isolated biome patches below the configured threshold).
 *
 * Algorithm overview:
 * 1. Cache surface heights for 16 biome positions in chunk
 * 2. Quick characterization pass to skip homogeneous chunks
 * 3. For each position in 4x4 surface grid:
 *    a. Flood fill with early exit to detect region size
 *    b. If region < threshold, find dominant neighbor biome
 *    c. Apply replacement to all Y levels in this chunk
 */
public final class MicroBiomeProcessor {

    // Prevent instantiation
    private MicroBiomeProcessor() {}

    /**
     * Result of a flood fill operation.
     */
    private static class FloodFillResult {
        int size;                    // Number of positions in region (for threshold)
        int inChunkCount;            // Number of positions within current chunk (for buffer iteration)
        boolean exceededThreshold;   // True if region >= threshold (not a micro biome)
        int minX, minZ;              // Canonical position (lexicographically smallest)
        int maxX, maxZ;              // Bounding box max
        boolean hitQueueLimit;       // True if stopped due to queue overflow
    }

    /**
     * Metrics collected during chunk processing for performance profiling.
     */
    private record ProcessingMetrics(
        int positionsProcessed,
        int replacementsMade,
        boolean skippedHomogeneous
    ) {
        static final ProcessingMetrics DISABLED = new ProcessingMetrics(0, 0, false);
        static final ProcessingMetrics HOMOGENEOUS = new ProcessingMetrics(16, 0, true);
    }

    /**
     * Information about a candidate replacement biome, including where it was sampled.
     * The sample position is needed for validation flood fill - we start the fill
     * from where the candidate was found as a neighbor.
     */
    private record CandidateInfo(Holder<Biome> holder, int sampleX, int sampleZ) {}

    // ThreadLocal to track whether debug logging is enabled for the current chunk
    private static final ThreadLocal<Boolean> DEBUG_ENABLED_FOR_CHUNK = ThreadLocal.withInitial(() -> false);

    /**
     * Helper method for debug logging - only logs when debug is enabled for the current chunk.
     */
    private static void debugLog(String message, Object... args) {
        if (DEBUG_ENABLED_FOR_CHUNK.get()) {
            MicroBiomeReplacer.LOGGER.info("[MBR-DEBUG] " + message, args);
        }
    }

    /**
     * Process a chunk for micro biome replacement.
     *
     * Called from ChunkGeneratorMixin at the tail of createBiomes().
     * At this point, vanilla biome generation is complete but the NOISE
     * stage hasn't started yet, so modifications are safe.
     *
     * @param generator The ChunkGenerator (for getBaseHeight() and getBiomeSource())
     * @param chunk The ChunkAccess with populated biome data
     * @param randomState The RandomState for height queries and climate sampling
     */
    public static void process(ChunkGenerator generator, ChunkAccess chunk, RandomState randomState) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Start timing for profiling
        long startNanos = System.nanoTime();

        // Begin JFR event (only records if JFR is active)
        ChunkProcessingEvent jfrEvent = new ChunkProcessingEvent();
        jfrEvent.chunkX = chunkX;
        jfrEvent.chunkZ = chunkZ;
        jfrEvent.begin();

        // Set up debug logging for this chunk (gated by config)
        boolean shouldDebug = ModConfig.shouldDebugLogChunk(chunkX, chunkZ);
        DEBUG_ENABLED_FOR_CHUNK.set(shouldDebug);

        ProcessingMetrics metrics;
        try {
            metrics = processInternal(generator, chunk, randomState);
        } finally {
            DEBUG_ENABLED_FOR_CHUNK.set(false);
        }

        // Record timing and metrics
        long durationNanos = System.nanoTime() - startNanos;

        // Record to in-memory stats collector
        PerformanceStats.getInstance().record(
            durationNanos,
            metrics.positionsProcessed(),
            metrics.replacementsMade(),
            metrics.skippedHomogeneous()
        );

        // Complete JFR event
        jfrEvent.durationNanos = durationNanos;
        jfrEvent.positionsProcessed = metrics.positionsProcessed();
        jfrEvent.replacementsMade = metrics.replacementsMade();
        jfrEvent.skippedHomogeneous = metrics.skippedHomogeneous();
        jfrEvent.end();
        jfrEvent.commit();
    }

    private static ProcessingMetrics processInternal(ChunkGenerator generator, ChunkAccess chunk, RandomState randomState) {
        // Check if processing is enabled
        if (!ModConfig.isEnabled()) {
            debugLog("Skipping chunk {} - mod is disabled", chunk.getPos());
            return ProcessingMetrics.DISABLED;
        }

        // Reset object pools for this chunk
        ObjectPools.resetAll();
        ObjectPools.resetChunkProcessed();
        ObjectPools.resetValidatedCandidatesCache();

        // Apply any pending buffer zone replacements from adjacent chunks
        // This handles BiomeManager's fuzzy sampling at chunk boundaries
        applyPendingBufferZones(chunk, generator);

        int thresholdBlocks = ModConfig.MINIMUM_SIZE_BLOCKS.get();
        int threshold = ModConfig.getThresholdInQuarts();

        int chunkMinX = chunk.getPos().x << 2;  // Chunk's min biome X coordinate
        int chunkMinZ = chunk.getPos().z << 2;  // Chunk's min biome Z coordinate

        debugLog("Processing chunk {} (biome coords X:{}-{}, Z:{}-{}), threshold={} quarts ({} blocks)",
            chunk.getPos(), chunkMinX, chunkMinX + 3, chunkMinZ, chunkMinZ + 3, threshold, thresholdBlocks);

        // 1. Cache single corner height for homogeneous check (lazy initialization phase 1)
        cacheHeightsSingleCorner(generator, chunk, randomState);

        // 2. Quick characterization - check if chunk is homogeneous
        // A chunk has 16 surface biome positions (4x4). If threshold <= 16 quarts (256 blocks),
        // a homogeneous chunk is guaranteed to meet/exceed threshold, so skip is safe.
        // If threshold > 16 quarts, chunk could be part of a larger cross-chunk micro biome,
        // but we can still avoid the 3 extra height samples if chunk is homogeneous.
        boolean isHomogeneous = isHomogeneousChunk(chunk);

        if (isHomogeneous && threshold <= 16) {
            debugLog("Skipping chunk {} - homogeneous and threshold {} <= 16 quarts", chunk.getPos(), threshold);
            return ProcessingMetrics.HOMOGENEOUS;  // Only 1 getBaseHeight() call for homogeneous chunks!
        }

        // 3. Complete height cache with bilinear interpolation (lazy initialization phase 2)
        // Only needed for non-homogeneous chunks - homogeneous chunks can use uniform heights
        if (!isHomogeneous) {
            completeHeightCache(generator, chunk, randomState);
        }
        // For homogeneous chunks with threshold > 16: single-corner heights are accurate enough
        // since the terrain is uniform where biomes are uniform

        boolean[] processed = ObjectPools.getChunkProcessed();
        int[] heights = ObjectPools.getHeightCache();

        // Track replacements for profiling
        int replacementsMade = 0;

        // 4. Process each position in 4x4 surface grid
        for (int localIdx = 0; localIdx < 16; localIdx++) {
            if (processed[localIdx]) {
                debugLog("  Position localIdx={} (local {},{}) - already processed, skipping",
                    localIdx, localIdx & 3, localIdx >> 2);
                continue;
            }

            int localX = localIdx & 3;
            int localZ = localIdx >> 2;
            int biomeY = heights[localIdx] >> 2;  // Convert block Y to biome Y

            Holder<Biome> biome = getBiomeFromChunk(chunk, localX, biomeY, localZ);

            // Check neverReplace list
            String biomeId = getBiomeId(biome);

            int globalX = chunkMinX + localX;
            int globalZ = chunkMinZ + localZ;
            int blockX = globalX << 2;
            int blockZ = globalZ << 2;

            debugLog("  Position localIdx={} (local {},{}) global biome ({},{},{}), block ~({},{},{}), biome={}",
                localIdx, localX, localZ, globalX, biomeY, globalZ,
                blockX, heights[localIdx], blockZ, biomeId);

            if (ModConfig.shouldNeverReplace(biomeId)) {
                debugLog("    -> Skipped: biome {} is in neverReplace list", biomeId);
                processed[localIdx] = true;
                continue;
            }

            // Flood fill to detect region
            FloodFillResult result = floodFill(
                globalX, globalZ, biome, biomeY,
                generator, chunk, randomState, threshold
            );

            debugLog("    -> Flood fill result: size={} quarts ({} blocks), exceededThreshold={}, hitQueueLimit={}, bbox blocks ({},{}) to ({},{})",
                result.size, result.size * 16, result.exceededThreshold, result.hitQueueLimit,
                result.minX << 2, result.minZ << 2, (result.maxX << 2) + 3, (result.maxZ << 2) + 3);

            // Mark positions in this chunk as processed
            markProcessed(result.inChunkCount, chunkMinX, chunkMinZ, processed);

            // If region exceeds threshold, not a micro biome - skip replacement
            if (result.exceededThreshold) {
                debugLog("    -> Skipped: region size {} >= threshold {}, not a micro biome",
                    result.size, threshold);
                continue;
            }

            // Select replacement biome from dominant neighbor (must be non-micro)
            Holder<Biome> replacement = selectReplacementBiome(
                result.inChunkCount, generator, chunk, randomState,
                result.minX, result.minZ, biome, threshold
            );

            if (replacement == null) {
                debugLog("    -> Skipped: no valid replacement biome found (all neighbors same biome or blacklisted)");
                continue;  // No valid replacement available
            }

            debugLog("    -> REPLACING: {} (size {}) -> {}", biomeId, result.size, getBiomeId(replacement));

            // Apply replacement to all Y levels in this chunk
            applyReplacement(chunk, result.inChunkCount, chunkMinX, chunkMinZ, biome, replacement);
            replacementsMade++;

            if (MicroBiomeReplacer.LOGGER.isDebugEnabled()) {
                MicroBiomeReplacer.LOGGER.debug(
                    "Replaced micro biome {} (size {}) with {} at chunk {}",
                    biomeId, result.size, getBiomeId(replacement), chunk.getPos()
                );
            }
        }

        return new ProcessingMetrics(16, replacementsMade, false);
    }

    /**
     * Cache a single corner height for the initial homogeneous check.
     * This is the first phase of lazy height initialization - we only sample one corner
     * and use that value for all 16 positions during the homogeneous check.
     *
     * For homogeneous chunks (60-80% of chunks), this saves 3 getBaseHeight() calls.
     */
    private static void cacheHeightsSingleCorner(ChunkGenerator generator, ChunkAccess chunk, RandomState randomState) {
        int[] cache = ObjectPools.getHeightCache();

        if (!ModConfig.useAccurateSurfaceSampling()) {
            // Fast path: fixed Y=64 for all positions
            for (int i = 0; i < 16; i++) {
                cache[i] = 64;
            }
            ObjectPools.setHeightCacheFullyInitialized(true);
            return;
        }

        // Sample only corner (0,0) - quart center at block (2,2)
        int baseX = chunk.getPos().x << 4;
        int baseZ = chunk.getPos().z << 4;
        int h00 = generator.getBaseHeight(baseX + 2, baseZ + 2, Heightmap.Types.WORLD_SURFACE, chunk, randomState);

        // Use this single height for all 16 positions (sufficient for homogeneous check)
        for (int i = 0; i < 16; i++) {
            cache[i] = h00;
        }
        ObjectPools.setHeightCacheFullyInitialized(false);
    }

    /**
     * Complete the height cache with full bilinear interpolation.
     * This is the second phase of lazy height initialization - only called for
     * non-homogeneous chunks that need accurate height sampling for flood fill.
     *
     * Samples the remaining 3 corners and interpolates all 16 positions.
     */
    private static void completeHeightCache(ChunkGenerator generator, ChunkAccess chunk, RandomState randomState) {
        if (ObjectPools.isHeightCacheFullyInitialized()) {
            return;  // Already fully initialized (e.g., fast path with fixed Y=64)
        }

        int[] cache = ObjectPools.getHeightCache();
        int baseX = chunk.getPos().x << 4;
        int baseZ = chunk.getPos().z << 4;

        // Corner (0,0) was already sampled in phase 1
        int h00 = cache[0];
        // Sample remaining 3 corners
        int h30 = generator.getBaseHeight(baseX + 14, baseZ + 2, Heightmap.Types.WORLD_SURFACE, chunk, randomState);
        int h03 = generator.getBaseHeight(baseX + 2, baseZ + 14, Heightmap.Types.WORLD_SURFACE, chunk, randomState);
        int h33 = generator.getBaseHeight(baseX + 14, baseZ + 14, Heightmap.Types.WORLD_SURFACE, chunk, randomState);

        // Interpolate all 16 positions using bilinear interpolation
        for (int i = 0; i < 16; i++) {
            int qx = i & 3;
            int qz = i >> 2;

            // Normalized coordinates (0.0 to 1.0)
            float u = qx / 3.0f;
            float v = qz / 3.0f;

            // Bilinear interpolation: h = (1-u)(1-v)h00 + u(1-v)h30 + (1-u)v*h03 + uv*h33
            float height = (1 - u) * (1 - v) * h00
                         +      u  * (1 - v) * h30
                         + (1 - u) *      v  * h03
                         +      u  *      v  * h33;

            cache[i] = Math.round(height);
        }

        ObjectPools.setHeightCacheFullyInitialized(true);
    }

    /**
     * Quick check if chunk contains only one biome (optimization).
     * Checks all 16 positions - if all same, skip detailed flood fill analysis.
     * This skips 60-80% of chunks in typical worlds (large ocean/plains areas).
     */
    private static boolean isHomogeneousChunk(ChunkAccess chunk) {
        int[] heights = ObjectPools.getHeightCache();

        // Check all 16 positions to avoid missing small micro biomes
        // (Previous 5-sample approach could miss micro biomes at unsampled positions)
        Holder<Biome> firstBiome = null;

        for (int idx = 0; idx < 16; idx++) {
            int qx = idx & 3;
            int qz = idx >> 2;
            int biomeY = heights[idx] >> 2;

            Holder<Biome> biome = getBiomeFromChunk(chunk, qx, biomeY, qz);

            if (firstBiome == null) {
                firstBiome = biome;
                debugLog("  Homogeneous check: first sample at ({},{}) = {}", qx, qz, getBiomeId(biome));
            } else if (!sameBiomeKey(biome, firstBiome)) {
                debugLog("  Homogeneous check: sample at ({},{}) = {} differs from {}, NOT homogeneous",
                    qx, qz, getBiomeId(biome), getBiomeId(firstBiome));
                return false;  // Not homogeneous
            }
        }
        debugLog("  Homogeneous check: all 16 positions = {}", getBiomeId(firstBiome));
        return true;  // All positions same biome
    }

    /**
     * Flood fill to detect a contiguous biome region.
     * Uses early exit when region exceeds threshold (not a micro biome).
     *
     * @return FloodFillResult containing region size and canonical position
     */
    private static FloodFillResult floodFill(
            int startX, int startZ,
            Holder<Biome> targetBiome,
            int surfaceBiomeY,
            ChunkGenerator generator,
            ChunkAccess chunk,
            RandomState randomState,
            int threshold) {

        BiomeSource biomeSource = generator.getBiomeSource();
        Climate.Sampler sampler = randomState.sampler();

        // Use ResourceKey comparison for consistency across different Holder sources
        // (chunk data vs BiomeSource may return different Holder instances for same biome,
        // but ResourceKeys are canonical and can be compared efficiently)

        // Use HashSet for visited tracking (simpler and correct for global coords)
        Set<Long> visited = new HashSet<>();
        long[] queue = ObjectPools.getFloodQueue();
        int[] regionBuffer = ObjectPools.getRegionBuffer();

        int queueHead = 0;
        int queueTail = 0;
        int regionSize = 0;
        int bufferCount = 0;  // Count of in-chunk positions stored in regionBuffer
        int minX = startX;
        int minZ = startZ;
        int maxX = startX;
        int maxZ = startZ;

        int chunkMinX = chunk.getPos().x << 2;
        int chunkMinZ = chunk.getPos().z << 2;

        // Add starting position to queue
        long startEncoded = ObjectPools.encodePos(startX, startZ);
        queue[queueTail++] = startEncoded;
        visited.add(startEncoded);

        // Track if we're processing the starting position (already verified to have targetBiome by caller)
        // This avoids re-checking the biome which can fail at chunk corners due to Y-level interpolation differences
        boolean isStartingPosition = true;

        while (queueHead < queueTail && regionSize < threshold) {
            long encoded = queue[queueHead++];
            int x = ObjectPools.decodeX(encoded);
            int z = ObjectPools.decodeZ(encoded);

            // Get surface height for this position
            int biomeY = getSurfaceHeight(x, z, generator, chunk, randomState, chunkMinX, chunkMinZ,
                                          biomeSource, sampler);

            // Get biome at this position
            Holder<Biome> biome = getBiomeAt(x, biomeY, z, chunk, biomeSource, sampler, chunkMinX, chunkMinZ);

            // Debug: log starting position processing
            if (isStartingPosition) {
                debugLog("      Flood fill: processing starting position ({},{}), biomeY={}, biome={}, targetBiome={}",
                    x, z, biomeY, getBiomeId(biome), getBiomeId(targetBiome));
            }

            // Skip biome check for starting position - it was already verified by caller before floodFill() was called.
            // For all other positions, verify they have the target biome before including in region.
            if (!isStartingPosition && !sameBiomeKey(biome, targetBiome)) {
                continue;  // Different biome, not part of region
            }
            isStartingPosition = false;

            // Add to region buffer (only for positions within current chunk)
            // Cross-chunk positions are counted for threshold but don't need buffering
            // since markProcessed() and applyReplacement() only operate on in-chunk positions.
            // NOTE: encodeLocal() only handles values 0-3; out-of-range values corrupt the encoding.
            int localX = x - chunkMinX;
            int localZ = z - chunkMinZ;
            if (localX >= 0 && localX < 4 && localZ >= 0 && localZ < 4) {
                if (bufferCount < regionBuffer.length) {
                    regionBuffer[bufferCount] = ObjectPools.encodeLocal(localX, localZ);
                    bufferCount++;
                }
            }
            regionSize++;

            // Track canonical position (lexicographically smallest for determinism)
            if (x < minX || (x == minX && z < minZ)) {
                minX = x;
                minZ = z;
            }
            // Track bounding box
            if (x > maxX) maxX = x;
            if (z > maxZ) maxZ = z;

            // Add cardinal neighbors to queue (only increment tail when actually added)
            if (addNeighborToQueue(x - 1, z, queue, queueTail, visited)) queueTail++;
            if (addNeighborToQueue(x + 1, z, queue, queueTail, visited)) queueTail++;
            if (addNeighborToQueue(x, z - 1, queue, queueTail, visited)) queueTail++;
            if (addNeighborToQueue(x, z + 1, queue, queueTail, visited)) queueTail++;

            // Ensure we don't overflow the queue
            if (queueTail >= queue.length - 4) {
                FloodFillResult limitResult = new FloodFillResult();
                limitResult.size = regionSize;
                limitResult.inChunkCount = bufferCount;
                limitResult.exceededThreshold = regionSize >= threshold;
                limitResult.minX = minX;
                limitResult.minZ = minZ;
                limitResult.maxX = maxX;
                limitResult.maxZ = maxZ;
                limitResult.hitQueueLimit = true;
                return limitResult;
            }
        }

        FloodFillResult result = new FloodFillResult();
        result.size = regionSize;
        result.inChunkCount = bufferCount;
        result.exceededThreshold = regionSize >= threshold;
        result.minX = minX;
        result.minZ = minZ;
        result.maxX = maxX;
        result.maxZ = maxZ;
        result.hitQueueLimit = false;
        return result;
    }

    /**
     * Add a neighbor position to the flood fill queue if not visited.
     * @return true if the position was added, false if already visited
     */
    private static boolean addNeighborToQueue(int x, int z, long[] queue, int index, Set<Long> visited) {
        long encoded = ObjectPools.encodePos(x, z);
        if (!visited.contains(encoded)) {
            visited.add(encoded);
            if (index < queue.length) {
                queue[index] = encoded;
                return true;
            }
        }
        return false;
    }

    /**
     * Flood fill specifically for validation - only determines if region >= threshold.
     * Uses a separate visited set to avoid interfering with the main flood fill state.
     * Does NOT populate the region buffer (not needed for validation).
     *
     * @param startX Starting biome X coordinate
     * @param startZ Starting biome Z coordinate
     * @param targetBiome The biome to flood fill
     * @param generator ChunkGenerator for BiomeSource access
     * @param chunk Current chunk being processed
     * @param randomState For Climate.Sampler
     * @param threshold Minimum region size to be non-micro
     * @return FloodFillResult with size and exceededThreshold
     */
    private static FloodFillResult floodFillForValidation(
            int startX, int startZ,
            Holder<Biome> targetBiome,
            ChunkGenerator generator,
            ChunkAccess chunk,
            RandomState randomState,
            int threshold) {

        BiomeSource biomeSource = generator.getBiomeSource();
        Climate.Sampler sampler = randomState.sampler();

        // Use a fresh HashSet for validation (don't pollute the main VISITED_SET)
        Set<Long> visited = new HashSet<>();

        // Reuse flood queue from pool (safe since we complete before returning to caller)
        long[] queue = ObjectPools.getFloodQueue();

        int queueHead = 0;
        int queueTail = 0;
        int regionSize = 0;

        int chunkMinX = chunk.getPos().x << 2;
        int chunkMinZ = chunk.getPos().z << 2;

        long startEncoded = ObjectPools.encodePos(startX, startZ);
        queue[queueTail++] = startEncoded;
        visited.add(startEncoded);

        while (queueHead < queueTail && regionSize < threshold) {
            long encoded = queue[queueHead++];
            int x = ObjectPools.decodeX(encoded);
            int z = ObjectPools.decodeZ(encoded);

            int biomeY = getSurfaceHeight(x, z, generator, chunk, randomState, chunkMinX, chunkMinZ,
                                          biomeSource, sampler);
            Holder<Biome> biome = getBiomeAt(x, biomeY, z, chunk, biomeSource, sampler, chunkMinX, chunkMinZ);

            if (!sameBiomeKey(biome, targetBiome)) {
                continue;  // Different biome, not part of region
            }

            regionSize++;

            // Add cardinal neighbors
            if (addNeighborToQueue(x - 1, z, queue, queueTail, visited)) queueTail++;
            if (addNeighborToQueue(x + 1, z, queue, queueTail, visited)) queueTail++;
            if (addNeighborToQueue(x, z - 1, queue, queueTail, visited)) queueTail++;
            if (addNeighborToQueue(x, z + 1, queue, queueTail, visited)) queueTail++;

            // Check queue overflow - treat as exceeded threshold (conservative)
            if (queueTail >= queue.length - 4) {
                FloodFillResult result = new FloodFillResult();
                result.size = regionSize;
                result.exceededThreshold = true;  // Assume large region if we overflow
                result.inChunkCount = 0;
                result.hitQueueLimit = true;
                return result;
            }
        }

        FloodFillResult result = new FloodFillResult();
        result.size = regionSize;
        result.exceededThreshold = regionSize >= threshold;
        result.inChunkCount = 0;  // Not tracking for validation
        result.hitQueueLimit = false;
        return result;
    }

    /**
     * Check if a candidate biome is non-micro (region size >= threshold).
     * Uses the validation cache to avoid redundant flood fills.
     *
     * @param candidateKey The ResourceKey of the candidate biome
     * @param info CandidateInfo containing the Holder and sample position
     * @param generator ChunkGenerator for BiomeSource access
     * @param chunk Current chunk being processed
     * @param randomState For Climate.Sampler
     * @param threshold Minimum region size to be non-micro
     * @return true if region >= threshold (non-micro), false otherwise
     */
    private static boolean isNonMicroBiome(
            ResourceKey<Biome> candidateKey,
            CandidateInfo info,
            ChunkGenerator generator,
            ChunkAccess chunk,
            RandomState randomState,
            int threshold) {

        // Check cache first
        Map<Long, Boolean> cache = ObjectPools.getValidatedCandidatesCache();
        long cacheKey = encodeCandidateCacheKey(info.sampleX(), info.sampleZ(), candidateKey);
        Boolean cached = cache.get(cacheKey);
        if (cached != null) {
            debugLog("      isNonMicroBiome: cache hit for {} at ({},{}): {}",
                candidateKey.location(), info.sampleX(), info.sampleZ(), cached);
            return cached;
        }

        // Perform flood fill starting from the neighbor position
        FloodFillResult result = floodFillForValidation(
            info.sampleX(), info.sampleZ(), info.holder(),
            generator, chunk, randomState, threshold
        );

        boolean isNonMicro = result.exceededThreshold;
        cache.put(cacheKey, isNonMicro);

        debugLog("      isNonMicroBiome: {} at ({},{}) -> size={}, exceededThreshold={}",
            candidateKey.location(), info.sampleX(), info.sampleZ(), result.size, isNonMicro);

        return isNonMicro;
    }

    /**
     * Encode a cache key for validated candidates.
     * Combines position and biome key hash into a single long.
     */
    private static long encodeCandidateCacheKey(int biomeX, int biomeZ, ResourceKey<Biome> key) {
        // Position in upper 48 bits, key hash in lower 16 bits
        long posEncoded = ((long)(biomeX & 0xFFFFFF) << 24) | (biomeZ & 0xFFFFFF);
        int keyHash = key.location().hashCode() & 0xFFFF;
        return (posEncoded << 16) | keyHash;
    }

    /**
     * Get surface height for a biome position.
     * Uses cache for positions in current chunk, 3x3 grid cache for neighboring chunks.
     * Applies homogeneous detection and bilinear interpolation for cross-chunk queries.
     */
    private static int getSurfaceHeight(int biomeX, int biomeZ,
            ChunkGenerator generator, ChunkAccess chunk, RandomState randomState,
            int chunkMinX, int chunkMinZ,
            BiomeSource biomeSource, Climate.Sampler sampler) {

        int localX = biomeX - chunkMinX;
        int localZ = biomeZ - chunkMinZ;

        // In-chunk: use existing cache
        if (localX >= 0 && localX < 4 && localZ >= 0 && localZ < 4) {
            int idx = ObjectPools.encodeLocal(localX, localZ);
            return ObjectPools.getHeightCache()[idx] >> 2;
        }

        // Cross-chunk: use 3x3 grid cache with lazy chunk-level initialization
        int crossIdx = ObjectPools.getCrossChunkHeightIndex(biomeX, biomeZ, chunkMinX, chunkMinZ);
        if (crossIdx >= 0) {
            int[] cache = ObjectPools.getCrossChunkHeightCache();

            // Ensure this neighboring chunk has been initialized (once per chunk, not per position)
            int chunkGridIdx = ObjectPools.getChunkGridIndex(biomeX, biomeZ, chunkMinX, chunkMinZ);
            if (chunkGridIdx >= 0) {
                BitSet initialized = ObjectPools.getCrossChunkInitialized();

                if (!initialized.get(chunkGridIdx)) {
                    // Two-tier cache: Check L2 shared cache before computing
                    long neighborChunkPos = getNeighborChunkPosLong(biomeX, biomeZ, chunkMinX, chunkMinZ);
                    int[] sharedHeights = ObjectPools.getSharedHeights(neighborChunkPos);

                    if (sharedHeights != null) {
                        // L2 hit: Copy to L1 ThreadLocal cache
                        copyToL1Cache(sharedHeights, chunkGridIdx);
                    } else {
                        // L2 miss: Compute heights and store in both caches
                        int[] computedHeights = computeChunkHeights(
                            biomeX, biomeZ, chunkMinX, chunkMinZ,
                            generator, chunk, randomState, biomeSource, sampler);

                        // Store in L2 shared cache (thread-safe via putIfAbsent)
                        ObjectPools.putSharedHeights(neighborChunkPos, computedHeights);

                        // Copy to L1 ThreadLocal cache
                        copyToL1Cache(computedHeights, chunkGridIdx);
                    }

                    initialized.set(chunkGridIdx);
                }
            }
            // Cache is now populated for all 16 positions in this chunk
            return cache[crossIdx] >> 2;
        }

        // Beyond 3x3 grid: use L2 shared cache with thorough homogeneity check
        long chunkPosLong = getNeighborChunkPosLong(biomeX, biomeZ, chunkMinX, chunkMinZ);
        int[] sharedHeights = ObjectPools.getSharedHeights(chunkPosLong);

        if (sharedHeights == null) {
            // Cache miss: compute with thorough homogeneity check (1-4 getBaseHeight calls)
            sharedHeights = computeChunkHeightsWithFullHomogeneityCheck(
                biomeX, biomeZ,
                generator, chunk, randomState, biomeSource, sampler);
            ObjectPools.putSharedHeights(chunkPosLong, sharedHeights);
        }

        int localIdx = ObjectPools.encodeLocal(biomeX & 3, biomeZ & 3);
        return sharedHeights[localIdx] >> 2;
    }

    // ========== Cross-Thread Cache (L2) Helper Methods ==========

    /**
     * Get the ChunkPos.asLong() for the chunk containing a biome position.
     *
     * @param biomeX Global biome X coordinate
     * @param biomeZ Global biome Z coordinate
     * @param chunkMinX Current chunk's minimum biome X (chunk.getPos().x << 2)
     * @param chunkMinZ Current chunk's minimum biome Z (chunk.getPos().z << 2)
     * @return ChunkPos.asLong() for the neighbor chunk
     */
    private static long getNeighborChunkPosLong(int biomeX, int biomeZ,
            int chunkMinX, int chunkMinZ) {
        // Calculate which chunk contains this biome position
        // chunkMinX/chunkMinZ are in biome coordinates (chunk.getPos().x << 2)
        // so we need to convert back to chunk coordinates
        int currentChunkX = chunkMinX >> 2;
        int currentChunkZ = chunkMinZ >> 2;
        int chunkOffsetX = (biomeX - chunkMinX) >> 2;
        int chunkOffsetZ = (biomeZ - chunkMinZ) >> 2;
        return ChunkPos.asLong(currentChunkX + chunkOffsetX, currentChunkZ + chunkOffsetZ);
    }

    /**
     * Compute heights for all 16 positions in a neighboring chunk.
     * Uses actual surface height for biome Y coordinate (not fixed Y=64).
     * Pattern: 1 height query → 16 biome queries → if all same, done (1 getBaseHeight total)
     * Otherwise: 3 more corner heights + bilinear interpolation (4 getBaseHeight total)
     *
     * @return int[16] containing heights for all biome positions in the chunk
     */
    private static int[] computeChunkHeights(int biomeX, int biomeZ,
            int chunkMinX, int chunkMinZ,
            ChunkGenerator generator, ChunkAccess chunk, RandomState randomState,
            BiomeSource biomeSource, Climate.Sampler sampler) {

        int[] heights = new int[16];

        // Calculate the neighbor chunk's corner biome coordinates
        int neighborChunkBiomeX = (biomeX - chunkMinX) >> 2;
        neighborChunkBiomeX = chunkMinX + (neighborChunkBiomeX << 2);
        int neighborChunkBiomeZ = (biomeZ - chunkMinZ) >> 2;
        neighborChunkBiomeZ = chunkMinZ + (neighborChunkBiomeZ << 2);

        if (!ModConfig.useAccurateSurfaceSampling()) {
            // Fast path: fixed Y=64 for all positions
            for (int i = 0; i < 16; i++) {
                heights[i] = 64;
            }
            return heights;
        }

        int baseBlockX = neighborChunkBiomeX << 2;
        int baseBlockZ = neighborChunkBiomeZ << 2;

        // Step 1: Query single corner height to get actual surface Y
        int h00 = generator.getBaseHeight(baseBlockX + 2, baseBlockZ + 2,
            Heightmap.Types.WORLD_SURFACE, chunk, randomState);

        // Step 2a: Fast path - check 4 corners first using actual surface Y (not fixed Y=64)
        int biomeY = h00 >> 2;
        Holder<Biome> c00 = biomeSource.getNoiseBiome(neighborChunkBiomeX, biomeY, neighborChunkBiomeZ, sampler);
        Holder<Biome> c30 = biomeSource.getNoiseBiome(neighborChunkBiomeX + 3, biomeY, neighborChunkBiomeZ, sampler);
        Holder<Biome> c03 = biomeSource.getNoiseBiome(neighborChunkBiomeX, biomeY, neighborChunkBiomeZ + 3, sampler);
        Holder<Biome> c33 = biomeSource.getNoiseBiome(neighborChunkBiomeX + 3, biomeY, neighborChunkBiomeZ + 3, sampler);

        // Quick rejection: if corners differ, not homogeneous → full interpolation
        if (!sameBiomeKey(c00, c30) || !sameBiomeKey(c00, c03) || !sameBiomeKey(c00, c33)) {
            return computeInterpolatedHeights(heights, h00, baseBlockX, baseBlockZ, generator, chunk, randomState);
        }

        // Step 2b: Corners match, scan interior 12 positions to confirm homogeneity
        boolean isHomogeneous = true;
        for (int i = 0; i < 16 && isHomogeneous; i++) {
            // Skip corners (already checked): indices 0, 3, 12, 15
            if (i == 0 || i == 3 || i == 12 || i == 15) continue;

            int lx = i & 3;
            int lz = i >> 2;
            Holder<Biome> biome = biomeSource.getNoiseBiome(
                neighborChunkBiomeX + lx, biomeY, neighborChunkBiomeZ + lz, sampler);

            if (!sameBiomeKey(biome, c00)) {
                isHomogeneous = false;
            }
        }

        if (isHomogeneous) {
            // All 16 biomes same → use single height for all positions (1 getBaseHeight total!)
            Arrays.fill(heights, h00);
        } else {
            // Interior differs → need full interpolation
            return computeInterpolatedHeights(heights, h00, baseBlockX, baseBlockZ, generator, chunk, randomState);
        }

        return heights;
    }

    /**
     * Compute heights for a distant chunk (beyond 3x3 grid) with thorough homogeneity check.
     * Pattern: 1 height query → 16 biome queries → if all same, done (1 getBaseHeight total)
     * Otherwise: 3 more corner heights + bilinear interpolation (4 getBaseHeight total)
     */
    private static int[] computeChunkHeightsWithFullHomogeneityCheck(
            int biomeX, int biomeZ,
            ChunkGenerator generator, ChunkAccess chunk, RandomState randomState,
            BiomeSource biomeSource, Climate.Sampler sampler) {

        int[] heights = new int[16];

        // Calculate the chunk's corner biome coordinates (align to chunk boundary)
        int targetChunkBiomeX = (biomeX >> 2) << 2;
        int targetChunkBiomeZ = (biomeZ >> 2) << 2;

        if (!ModConfig.useAccurateSurfaceSampling()) {
            Arrays.fill(heights, 64);
            return heights;
        }

        // Step 1: Query single corner height
        int baseBlockX = targetChunkBiomeX << 2;
        int baseBlockZ = targetChunkBiomeZ << 2;
        int h00 = generator.getBaseHeight(baseBlockX + 2, baseBlockZ + 2,
            Heightmap.Types.WORLD_SURFACE, chunk, randomState);

        // Step 2a: Fast path - check 4 corners first (4 biome queries vs 16)
        int biomeY = h00 >> 2;
        Holder<Biome> c00 = biomeSource.getNoiseBiome(targetChunkBiomeX, biomeY, targetChunkBiomeZ, sampler);
        Holder<Biome> c30 = biomeSource.getNoiseBiome(targetChunkBiomeX + 3, biomeY, targetChunkBiomeZ, sampler);
        Holder<Biome> c03 = biomeSource.getNoiseBiome(targetChunkBiomeX, biomeY, targetChunkBiomeZ + 3, sampler);
        Holder<Biome> c33 = biomeSource.getNoiseBiome(targetChunkBiomeX + 3, biomeY, targetChunkBiomeZ + 3, sampler);

        // Quick rejection: if corners differ, not homogeneous → full interpolation
        if (!sameBiomeKey(c00, c30) || !sameBiomeKey(c00, c03) || !sameBiomeKey(c00, c33)) {
            return computeInterpolatedHeights(heights, h00, baseBlockX, baseBlockZ, generator, chunk, randomState);
        }

        // Step 2b: Corners match, scan interior 12 positions to confirm homogeneity
        boolean isHomogeneous = true;
        for (int i = 0; i < 16 && isHomogeneous; i++) {
            // Skip corners (already checked): indices 0, 3, 12, 15
            if (i == 0 || i == 3 || i == 12 || i == 15) continue;

            int lx = i & 3;
            int lz = i >> 2;
            Holder<Biome> biome = biomeSource.getNoiseBiome(
                targetChunkBiomeX + lx, biomeY, targetChunkBiomeZ + lz, sampler);

            if (!sameBiomeKey(biome, c00)) {
                isHomogeneous = false;
            }
        }

        if (isHomogeneous) {
            // All 16 biomes same → use single height for all positions (1 getBaseHeight total!)
            Arrays.fill(heights, h00);
        } else {
            // Interior differs → need full interpolation
            return computeInterpolatedHeights(heights, h00, baseBlockX, baseBlockZ, generator, chunk, randomState);
        }

        return heights;
    }

    /**
     * Compute bilinear-interpolated heights using 4 corner samples.
     * Helper for computeChunkHeightsWithFullHomogeneityCheck().
     */
    private static int[] computeInterpolatedHeights(int[] heights, int h00,
            int baseBlockX, int baseBlockZ,
            ChunkGenerator generator, ChunkAccess chunk, RandomState randomState) {

        int h30 = generator.getBaseHeight(baseBlockX + 14, baseBlockZ + 2,
            Heightmap.Types.WORLD_SURFACE, chunk, randomState);
        int h03 = generator.getBaseHeight(baseBlockX + 2, baseBlockZ + 14,
            Heightmap.Types.WORLD_SURFACE, chunk, randomState);
        int h33 = generator.getBaseHeight(baseBlockX + 14, baseBlockZ + 14,
            Heightmap.Types.WORLD_SURFACE, chunk, randomState);

        for (int i = 0; i < 16; i++) {
            int lx = i & 3;
            int lz = i >> 2;
            float u = lx / 3.0f;
            float v = lz / 3.0f;
            float height = (1 - u) * (1 - v) * h00
                         +      u  * (1 - v) * h30
                         + (1 - u) *      v  * h03
                         +      u  *      v  * h33;
            heights[i] = Math.round(height);
        }

        return heights;
    }

    /**
     * Copy heights from shared L2 cache to ThreadLocal L1 cache.
     *
     * @param sharedHeights int[16] from the shared cache
     * @param chunkGridIdx Index (0-8) identifying which chunk in the 3x3 grid
     */
    private static void copyToL1Cache(int[] sharedHeights, int chunkGridIdx) {
        int[] l1Cache = ObjectPools.getCrossChunkHeightCache();

        // Calculate base offset in the 12x12 grid for this chunk
        int chunkOffsetX = (chunkGridIdx % 3) - 1;  // -1, 0, or +1
        int chunkOffsetZ = (chunkGridIdx / 3) - 1;
        int baseGridX = (chunkOffsetX * 4) + 4;     // 0, 4, or 8
        int baseGridZ = (chunkOffsetZ * 4) + 4;

        // Copy all 16 positions from shared cache to L1 cache
        for (int lz = 0; lz < 4; lz++) {
            for (int lx = 0; lx < 4; lx++) {
                int l1Idx = (baseGridZ + lz) * 12 + (baseGridX + lx);
                int sharedIdx = (lz << 2) | lx;
                l1Cache[l1Idx] = sharedHeights[sharedIdx];
            }
        }
    }

    /**
     * Get biome at a position.
     * Uses chunk data for positions in current chunk, BiomeSource for cross-chunk.
     */
    private static Holder<Biome> getBiomeAt(int biomeX, int biomeY, int biomeZ,
            ChunkAccess chunk, BiomeSource biomeSource, Climate.Sampler sampler,
            int chunkMinX, int chunkMinZ) {

        int localX = biomeX - chunkMinX;
        int localZ = biomeZ - chunkMinZ;

        if (localX >= 0 && localX < 4 && localZ >= 0 && localZ < 4) {
            // Position is in current chunk - read from chunk data
            return getBiomeFromChunk(chunk, localX, biomeY, localZ);
        }

        // Cross-chunk query via BiomeSource (deterministic from seed)
        return biomeSource.getNoiseBiome(biomeX, biomeY, biomeZ, sampler);
    }

    /**
     * Mark positions in this chunk as processed to avoid re-detection.
     */
    private static void markProcessed(int regionSize, int chunkMinX, int chunkMinZ, boolean[] processed) {
        int[] regionBuffer = ObjectPools.getRegionBuffer();

        for (int i = 0; i < regionSize && i < regionBuffer.length; i++) {
            int localEncoded = regionBuffer[i];
            int localX = ObjectPools.decodeLocalX(localEncoded);
            int localZ = ObjectPools.decodeLocalZ(localEncoded);

            // Only mark positions within this chunk's 4x4 grid
            if (localX >= 0 && localX < 4 && localZ >= 0 && localZ < 4) {
                int idx = ObjectPools.encodeLocal(localX, localZ);
                processed[idx] = true;
            }
        }
    }

    /**
     * Select the replacement biome based on neighbor frequency.
     * Returns the most common adjacent biome that isn't blacklisted and is non-micro.
     * If the most frequent neighbor is itself a micro biome, falls back to next best.
     */
    private static Holder<Biome> selectReplacementBiome(
            int regionSize,
            ChunkGenerator generator,
            ChunkAccess chunk,
            RandomState randomState,
            int canonicalX, int canonicalZ,
            Holder<Biome> originalBiome,
            int threshold) {

        BiomeSource biomeSource = generator.getBiomeSource();
        Climate.Sampler sampler = randomState.sampler();
        int[] regionBuffer = ObjectPools.getRegionBuffer();

        int chunkMinX = chunk.getPos().x << 2;
        int chunkMinZ = chunk.getPos().z << 2;

        Map<ResourceKey<Biome>, Integer> neighborCounts = new HashMap<>();
        Map<ResourceKey<Biome>, CandidateInfo> candidateInfo = new HashMap<>();
        // Track blacklisted candidates separately for fallback when all neighbors are blacklisted
        Map<ResourceKey<Biome>, Integer> blacklistedCounts = new HashMap<>();
        Map<ResourceKey<Biome>, CandidateInfo> blacklistedCandidateInfo = new HashMap<>();

        // Scan boundary neighbors for each position in the region
        for (int i = 0; i < regionSize && i < regionBuffer.length; i++) {
            int localEncoded = regionBuffer[i];
            int localX = ObjectPools.decodeLocalX(localEncoded);
            int localZ = ObjectPools.decodeLocalZ(localEncoded);
            int x = localX + chunkMinX;
            int z = localZ + chunkMinZ;

            // Check all 4 cardinal neighbors
            checkNeighbor(x - 1, z, originalBiome, neighborCounts, candidateInfo, blacklistedCounts, blacklistedCandidateInfo, generator, chunk, randomState, biomeSource, sampler, chunkMinX, chunkMinZ);
            checkNeighbor(x + 1, z, originalBiome, neighborCounts, candidateInfo, blacklistedCounts, blacklistedCandidateInfo, generator, chunk, randomState, biomeSource, sampler, chunkMinX, chunkMinZ);
            checkNeighbor(x, z - 1, originalBiome, neighborCounts, candidateInfo, blacklistedCounts, blacklistedCandidateInfo, generator, chunk, randomState, biomeSource, sampler, chunkMinX, chunkMinZ);
            checkNeighbor(x, z + 1, originalBiome, neighborCounts, candidateInfo, blacklistedCounts, blacklistedCandidateInfo, generator, chunk, randomState, biomeSource, sampler, chunkMinX, chunkMinZ);
        }

        // Fallback: if no cardinal neighbors found, try diagonal neighbors
        // This handles the case where a small diagonally-connected patch at a chunk corner
        // is processed before the main region, so all cardinal neighbors are still the original biome
        if (neighborCounts.isEmpty()) {
            debugLog("      selectReplacement: no cardinal neighbors, trying diagonal fallback");
            for (int i = 0; i < regionSize && i < regionBuffer.length; i++) {
                int localEncoded = regionBuffer[i];
                int localX = ObjectPools.decodeLocalX(localEncoded);
                int localZ = ObjectPools.decodeLocalZ(localEncoded);
                int x = localX + chunkMinX;
                int z = localZ + chunkMinZ;

                // Check all 4 diagonal neighbors
                checkNeighbor(x - 1, z - 1, originalBiome, neighborCounts, candidateInfo, blacklistedCounts, blacklistedCandidateInfo, generator, chunk, randomState, biomeSource, sampler, chunkMinX, chunkMinZ);
                checkNeighbor(x + 1, z - 1, originalBiome, neighborCounts, candidateInfo, blacklistedCounts, blacklistedCandidateInfo, generator, chunk, randomState, biomeSource, sampler, chunkMinX, chunkMinZ);
                checkNeighbor(x - 1, z + 1, originalBiome, neighborCounts, candidateInfo, blacklistedCounts, blacklistedCandidateInfo, generator, chunk, randomState, biomeSource, sampler, chunkMinX, chunkMinZ);
                checkNeighbor(x + 1, z + 1, originalBiome, neighborCounts, candidateInfo, blacklistedCounts, blacklistedCandidateInfo, generator, chunk, randomState, biomeSource, sampler, chunkMinX, chunkMinZ);
            }
        }

        // If no non-blacklisted neighbors found, fall back to blacklisted ones
        if (neighborCounts.isEmpty() && !blacklistedCounts.isEmpty()) {
            debugLog("      selectReplacement: no non-blacklisted neighbors, falling back to blacklisted");
            neighborCounts = blacklistedCounts;
            candidateInfo = blacklistedCandidateInfo;
        }

        if (neighborCounts.isEmpty()) {
            debugLog("      selectReplacement: no valid neighbors found (cardinal + diagonal)");
            return null;  // No valid replacement available
        }

        // Log neighbor counts
        if (ModConfig.isDebugLoggingEnabled()) {
            StringBuilder sb = new StringBuilder("      selectReplacement: neighbor counts: ");
            for (Map.Entry<ResourceKey<Biome>, Integer> entry : neighborCounts.entrySet()) {
                sb.append(entry.getKey().location()).append("=").append(entry.getValue()).append(", ");
            }
            debugLog(sb.toString());
        }

        // Sort candidates by frequency (descending), then by name (for determinism)
        List<Map.Entry<ResourceKey<Biome>, Integer>> sortedCandidates = new ArrayList<>(neighborCounts.entrySet());
        sortedCandidates.sort((a, b) -> {
            int freqCompare = Integer.compare(b.getValue(), a.getValue());  // Descending frequency
            if (freqCompare != 0) return freqCompare;
            return a.getKey().location().compareTo(b.getKey().location());  // Ascending by name
        });

        // Process candidates by frequency level, validating each as non-micro
        int currentFrequency = -1;
        List<ResourceKey<Biome>> nonMicroCandidatesAtLevel = new ArrayList<>();

        for (Map.Entry<ResourceKey<Biome>, Integer> entry : sortedCandidates) {
            ResourceKey<Biome> candidateKey = entry.getKey();
            int frequency = entry.getValue();

            // When we move to a lower frequency level, check if we found any valid candidates at previous level
            if (frequency != currentFrequency) {
                if (!nonMicroCandidatesAtLevel.isEmpty()) {
                    // Select from validated non-micro candidates at this frequency level
                    ResourceKey<Biome> selectedKey;
                    if (nonMicroCandidatesAtLevel.size() == 1) {
                        selectedKey = nonMicroCandidatesAtLevel.get(0);
                        debugLog("      selectReplacement: single non-micro winner {} with count {}",
                            selectedKey.location(), currentFrequency);
                    } else {
                        selectedKey = deterministicSelect(nonMicroCandidatesAtLevel, canonicalX, canonicalZ);
                        debugLog("      selectReplacement: tie-break between {} non-micro candidates, selected {}",
                            nonMicroCandidatesAtLevel.size(), selectedKey.location());
                    }
                    return candidateInfo.get(selectedKey).holder();
                }
                // Start new frequency level
                currentFrequency = frequency;
                nonMicroCandidatesAtLevel.clear();
            }

            CandidateInfo info = candidateInfo.get(candidateKey);

            // Skip if same as original (shouldn't happen due to checkNeighbor, but safety check)
            if (sameBiomeKey(info.holder(), originalBiome)) {
                debugLog("      selectReplacement: skipping {} - same as original biome", candidateKey.location());
                continue;
            }

            // Validate that this candidate is non-micro
            if (isNonMicroBiome(candidateKey, info, generator, chunk, randomState, threshold)) {
                nonMicroCandidatesAtLevel.add(candidateKey);
                debugLog("      selectReplacement: {} is non-micro, adding to candidates", candidateKey.location());
            } else {
                debugLog("      selectReplacement: {} is micro, skipping", candidateKey.location());
            }
        }

        // Check last frequency level
        if (!nonMicroCandidatesAtLevel.isEmpty()) {
            ResourceKey<Biome> selectedKey;
            if (nonMicroCandidatesAtLevel.size() == 1) {
                selectedKey = nonMicroCandidatesAtLevel.get(0);
                debugLog("      selectReplacement: single non-micro winner {} with count {}",
                    selectedKey.location(), currentFrequency);
            } else {
                selectedKey = deterministicSelect(nonMicroCandidatesAtLevel, canonicalX, canonicalZ);
                debugLog("      selectReplacement: tie-break between {} non-micro candidates, selected {}",
                    nonMicroCandidatesAtLevel.size(), selectedKey.location());
            }
            return candidateInfo.get(selectedKey).holder();
        }

        // No valid non-micro replacement found
        debugLog("      selectReplacement: no non-micro candidates found");
        return null;
    }

    /**
     * Check a neighbor position and add to frequency counts if valid.
     * Also tracks the sample position for each candidate (needed for validation flood fill).
     * Blacklisted biomes are tracked separately for fallback when all neighbors are blacklisted.
     */
    private static void checkNeighbor(int x, int z, Holder<Biome> originalBiome,
            Map<ResourceKey<Biome>, Integer> counts,
            Map<ResourceKey<Biome>, CandidateInfo> candidateInfo,
            Map<ResourceKey<Biome>, Integer> blacklistedCounts,
            Map<ResourceKey<Biome>, CandidateInfo> blacklistedCandidateInfo,
            ChunkGenerator generator, ChunkAccess chunk, RandomState randomState,
            BiomeSource biomeSource, Climate.Sampler sampler,
            int chunkMinX, int chunkMinZ) {

        int biomeY = getSurfaceHeight(x, z, generator, chunk, randomState, chunkMinX, chunkMinZ,
                                      biomeSource, sampler);
        Holder<Biome> biome = getBiomeAt(x, biomeY, z, chunk, biomeSource, sampler, chunkMinX, chunkMinZ);

        // Skip if same as original biome (part of the region) - use ResourceKey comparison
        // for consistency across different Holder sources (chunk data vs BiomeSource)
        if (sameBiomeKey(biome, originalBiome)) {
            return;
        }

        // Get ResourceKey for map keying (ensures same biome from different sources counts together)
        ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
        if (key == null) {
            return;
        }

        // Check if biome is blacklisted from being used as replacement
        String biomeId = getBiomeId(biome);
        if (ModConfig.shouldNeverUseAsReplacement(biomeId)) {
            // Track blacklisted biome separately for fallback when all neighbors are blacklisted
            blacklistedCounts.merge(key, 1, Integer::sum);
            blacklistedCandidateInfo.putIfAbsent(key, new CandidateInfo(biome, x, z));
            return;
        }

        // Increment count for this neighbor biome and store info including sample position
        // (only store first occurrence - that position will be used for validation flood fill)
        counts.merge(key, 1, Integer::sum);
        candidateInfo.putIfAbsent(key, new CandidateInfo(biome, x, z));
    }

    /**
     * Deterministic selection when multiple biomes have same frequency.
     * Uses canonical region position to ensure consistent selection across chunks.
     */
    private static ResourceKey<Biome> deterministicSelect(List<ResourceKey<Biome>> candidates, int regionX, int regionZ) {
        // Sort by ResourceLocation for consistent ordering (more efficient than String comparison)
        candidates.sort((a, b) -> a.location().compareTo(b.location()));

        // Generate deterministic hash from region position
        // Constants from research document for good distribution
        long hash = (regionX * 341873128712L) ^ (regionZ * 132897987541L);
        int index = (int) (Math.abs(hash) % candidates.size());

        return candidates.get(index);
    }

    /**
     * Apply pending buffer zone replacements registered by adjacent chunks.
     * This handles BiomeManager's fuzzy sampling which can query positions
     * up to 1 biome away from the requested block coordinate.
     *
     * IMPORTANT: When a buffer is applied, we must also register buffers for
     * the position's neighbors. This is because BiomeManager might sample
     * positions diagonally adjacent to the original micro biome, and those
     * positions need buffer zones too.
     */
    private static void applyPendingBufferZones(ChunkAccess chunk, ChunkGenerator generator) {
        long chunkPosLong = chunk.getPos().toLong();
        java.util.List<ObjectPools.BufferZoneEntry> entries = ObjectPools.consumeBufferZone(chunkPosLong);

        if (entries == null || entries.isEmpty()) {
            return;
        }

        debugLog("  Applying {} pending buffer zone entries from adjacent chunks", entries.size());

        LevelChunkSection[] sections = chunk.getSections();
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Get biome registry for looking up biomes by ID
        var biomeRegistry = generator.getBiomeSource().possibleBiomes().stream()
            .filter(h -> h.unwrapKey().isPresent())
            .collect(java.util.stream.Collectors.toMap(
                h -> h.unwrapKey().get().location().toString(),
                h -> h,
                (a, b) -> a  // In case of duplicates, keep first
            ));

        for (ObjectPools.BufferZoneEntry entry : entries) {
            int localX = entry.localX();
            int localZ = entry.localZ();

            if (localX < 0 || localX >= 4 || localZ < 0 || localZ >= 4) {
                continue;
            }

            Holder<Biome> replacementHolder = biomeRegistry.get(entry.replacementBiome());
            if (replacementHolder == null) {
                debugLog("    Buffer zone: replacement biome {} not found in registry", entry.replacementBiome());
                continue;
            }

            int replacedCount = 0;
            // Apply replacement to all sections (vertical propagation)
            for (LevelChunkSection section : sections) {
                if (section == null) {
                    continue;
                }

                PalettedContainerRO<Holder<Biome>> biomesRO = section.getBiomes();
                if (!(biomesRO instanceof PalettedContainer)) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                PalettedContainer<Holder<Biome>> biomes = (PalettedContainer<Holder<Biome>>) biomesRO;

                for (int y = 0; y < 4; y++) {
                    Holder<Biome> current = biomes.get(localX, y, localZ);
                    String currentId = getBiomeId(current);
                    if (currentId.equals(entry.originalBiome())) {
                        biomes.set(localX, y, localZ, replacementHolder);
                        replacedCount++;
                    }
                }
            }

            debugLog("    Buffer zone: local ({},{}) {} -> {} ({} entries replaced)",
                localX, localZ, entry.originalBiome(), entry.replacementBiome(), replacedCount);

            // If we applied a replacement, register in BiomeReplacementRegistry for query-time interception
            // and also register buffers for this position's neighbors to handle BiomeManager's diagonal sampling
            if (replacedCount > 0) {
                // Get ResourceKey for original biome to register in BiomeReplacementRegistry
                ResourceKey<Biome> originalKey = null;
                for (var holder : generator.getBiomeSource().possibleBiomes()) {
                    if (holder.unwrapKey().isPresent() &&
                        holder.unwrapKey().get().location().toString().equals(entry.originalBiome())) {
                        originalKey = holder.unwrapKey().get();
                        break;
                    }
                }

                if (originalKey != null) {
                    BiomeReplacementRegistry.registerWithNeighbors(
                        chunkX, chunkZ, localX, localZ, originalKey, replacementHolder);
                    debugLog("    Buffer zone: registered in BiomeReplacementRegistry");
                }

                registerNeighborBuffers(chunkX, chunkZ, localX, localZ,
                    entry.originalBiome(), entry.replacementBiome());
            }
        }
    }

    /**
     * Register buffer zones for all 8 neighbors of a position.
     * This propagates buffer zone coverage to handle BiomeManager's diagonal sampling.
     */
    private static void registerNeighborBuffers(int chunkX, int chunkZ, int localX, int localZ,
            String originalBiome, String replacementBiome) {
        // Check all 8 neighbors (cardinal + diagonal)
        int[][] neighbors = {
            {localX - 1, localZ},     // West
            {localX + 1, localZ},     // East
            {localX, localZ - 1},     // North
            {localX, localZ + 1},     // South
            {localX - 1, localZ - 1}, // Northwest
            {localX + 1, localZ - 1}, // Northeast
            {localX - 1, localZ + 1}, // Southwest
            {localX + 1, localZ + 1}  // Southeast
        };

        for (int[] neighbor : neighbors) {
            int nx = neighbor[0];
            int nz = neighbor[1];

            // Skip in-chunk neighbors (they'll be handled by normal processing)
            if (nx >= 0 && nx < 4 && nz >= 0 && nz < 4) {
                continue;
            }

            // Cross-chunk neighbor - register buffer
            int targetChunkX = chunkX;
            int targetChunkZ = chunkZ;
            int targetLocalX = nx;
            int targetLocalZ = nz;

            if (nx < 0) {
                targetChunkX = chunkX - 1;
                targetLocalX = 3;
            } else if (nx >= 4) {
                targetChunkX = chunkX + 1;
                targetLocalX = 0;
            }

            if (nz < 0) {
                targetChunkZ = chunkZ - 1;
                targetLocalZ = 3;
            } else if (nz >= 4) {
                targetChunkZ = chunkZ + 1;
                targetLocalZ = 0;
            }

            long targetChunkPosLong = ChunkPos.asLong(targetChunkX, targetChunkZ);
            ObjectPools.registerBufferZone(targetChunkPosLong, targetLocalX, targetLocalZ,
                originalBiome, replacementBiome);
            debugLog("    Buffer zone: propagated buffer to chunk [{},{}] local ({},{})",
                targetChunkX, targetChunkZ, targetLocalX, targetLocalZ);
        }
    }

    /**
     * Apply the biome replacement to all Y levels in the chunk.
     * Only modifies positions within the current chunk that match the original biome.
     *
     * IMPORTANT: Also replaces a 1-biome buffer around the detected region to account
     * for BiomeManager's fuzzy sampling. BiomeManager applies a -2 block offset before
     * converting to biome coordinates, which means level.getBiome() can query positions
     * up to 1 biome away from the requested block coordinate. Without this buffer,
     * micro biomes at chunk corners may appear unreplaced when queried via level.getBiome().
     *
     * For positions at chunk edges, registers buffer zone entries for adjacent chunks
     * so they can apply the replacement when they are generated.
     */
    private static void applyReplacement(ChunkAccess chunk, int regionSize,
            int chunkMinX, int chunkMinZ,
            Holder<Biome> originalBiome, Holder<Biome> replacementBiome) {

        int[] regionBuffer = ObjectPools.getRegionBuffer();
        LevelChunkSection[] sections = chunk.getSections();
        int[] heights = ObjectPools.getHeightCache();

        String originalBiomeId = getBiomeId(originalBiome);
        String replacementBiomeId = getBiomeId(replacementBiome);

        debugLog("      applyReplacement: regionSize={}, original={}, replacement={}",
            regionSize, originalBiomeId, replacementBiomeId);

        // Collect all positions to replace: detected region + 1-biome buffer
        // Use a 16-element boolean array for efficient tracking (4x4 grid)
        boolean[] positionsToReplace = new boolean[16];
        int positionCount = 0;

        // Step 1: Add all positions from the detected region
        for (int i = 0; i < regionSize && i < regionBuffer.length; i++) {
            int localEncoded = regionBuffer[i];
            int localX = ObjectPools.decodeLocalX(localEncoded);
            int localZ = ObjectPools.decodeLocalZ(localEncoded);

            if (localX >= 0 && localX < 4 && localZ >= 0 && localZ < 4) {
                int idx = localZ * 4 + localX;
                if (!positionsToReplace[idx]) {
                    positionsToReplace[idx] = true;
                    positionCount++;
                }
            }
        }

        debugLog("      applyReplacement: {} positions from detected region", positionCount);

        // Step 2: Add 1-biome buffer around the region (BiomeManager fuzzy sampling compensation)
        // For in-chunk positions: add directly if they have the original biome
        // For cross-chunk positions: register in buffer zone registry for later processing
        //
        // IMPORTANT: BiomeManager applies a -2 BLOCK offset before converting to biome coords.
        // This can shift BOTH X and Z by 1 biome position (since 2 blocks < 4 blocks per biome).
        // Therefore, we must check ALL 8 neighbors (cardinal + diagonal), not just cardinal.
        int bufferAdded = 0;
        int crossChunkRegistered = 0;
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        for (int i = 0; i < regionSize && i < regionBuffer.length; i++) {
            int localEncoded = regionBuffer[i];
            int localX = ObjectPools.decodeLocalX(localEncoded);
            int localZ = ObjectPools.decodeLocalZ(localEncoded);

            if (localX < 0 || localX >= 4 || localZ < 0 || localZ >= 4) {
                continue;
            }

            // Check all 8 neighbors (cardinal + diagonal) for BiomeManager's -2 block offset
            int[][] neighbors = {
                {localX - 1, localZ},     // West
                {localX + 1, localZ},     // East
                {localX, localZ - 1},     // North
                {localX, localZ + 1},     // South
                {localX - 1, localZ - 1}, // Northwest
                {localX + 1, localZ - 1}, // Northeast
                {localX - 1, localZ + 1}, // Southwest
                {localX + 1, localZ + 1}  // Southeast
            };
            for (int[] neighbor : neighbors) {
                int nx = neighbor[0];
                int nz = neighbor[1];

                // In-chunk neighbor
                if (nx >= 0 && nx < 4 && nz >= 0 && nz < 4) {
                    int idx = nz * 4 + nx;
                    // Skip if already marked for replacement
                    if (!positionsToReplace[idx]) {
                        // Check if this position has the original biome at surface level
                        int biomeY = heights[idx] >> 2;
                        Holder<Biome> current = getBiomeFromChunk(chunk, nx, biomeY, nz);
                        if (sameBiomeKey(current, originalBiome)) {
                            positionsToReplace[idx] = true;
                            positionCount++;
                            bufferAdded++;
                            debugLog("      applyReplacement: added buffer position ({},{}) with original biome",
                                nx, nz);
                        }
                    }
                } else {
                    // Cross-chunk neighbor - register for the adjacent chunk
                    int targetChunkX = chunkX;
                    int targetChunkZ = chunkZ;
                    int targetLocalX = nx;
                    int targetLocalZ = nz;

                    if (nx < 0) {
                        targetChunkX = chunkX - 1;
                        targetLocalX = 3;  // Rightmost column of left neighbor
                    } else if (nx >= 4) {
                        targetChunkX = chunkX + 1;
                        targetLocalX = 0;  // Leftmost column of right neighbor
                    }

                    if (nz < 0) {
                        targetChunkZ = chunkZ - 1;
                        targetLocalZ = 3;  // Bottom row of top neighbor
                    } else if (nz >= 4) {
                        targetChunkZ = chunkZ + 1;
                        targetLocalZ = 0;  // Top row of bottom neighbor
                    }

                    long targetChunkPosLong = ChunkPos.asLong(targetChunkX, targetChunkZ);
                    ObjectPools.registerBufferZone(targetChunkPosLong, targetLocalX, targetLocalZ,
                        originalBiomeId, replacementBiomeId);
                    crossChunkRegistered++;
                    debugLog("      applyReplacement: registered buffer for chunk [{},{}] local ({},{})",
                        targetChunkX, targetChunkZ, targetLocalX, targetLocalZ);
                }
            }
        }

        debugLog("      applyReplacement: added {} in-chunk buffer, registered {} cross-chunk buffer, total {} in-chunk positions",
            bufferAdded, crossChunkRegistered, positionCount);

        // Get ResourceKey for original biome (needed for registry)
        ResourceKey<Biome> originalKey = originalBiome.unwrapKey().orElse(null);

        // Step 3: Replace all marked positions
        for (int idx = 0; idx < 16; idx++) {
            if (!positionsToReplace[idx]) {
                continue;
            }

            int localX = idx & 3;
            int localZ = idx >> 2;

            debugLog("      applyReplacement: processing position local ({},{})",
                localX, localZ);

            int replacedCount = 0;
            int sectionIdx = 0;
            // Apply replacement to all sections (vertical propagation)
            for (LevelChunkSection section : sections) {
                if (section == null) {
                    sectionIdx++;
                    continue;
                }

                // During chunk generation, the biome container is mutable
                // getBiomes() returns PalettedContainerRO but actual type is PalettedContainer
                PalettedContainerRO<Holder<Biome>> biomesRO = section.getBiomes();
                if (!(biomesRO instanceof PalettedContainer)) {
                    continue;  // Safety check - shouldn't happen during generation
                }

                @SuppressWarnings("unchecked")
                PalettedContainer<Holder<Biome>> biomes = (PalettedContainer<Holder<Biome>>) biomesRO;

                // Each section has 4 biome Y positions
                for (int y = 0; y < 4; y++) {
                    Holder<Biome> current = biomes.get(localX, y, localZ);
                    // Use ResourceKey comparison for consistency across different Holder sources
                    if (sameBiomeKey(current, originalBiome)) {
                        biomes.set(localX, y, localZ, replacementBiome);
                        // Verify the replacement actually took effect
                        Holder<Biome> afterSet = biomes.get(localX, y, localZ);
                        if (!sameBiomeKey(afterSet, replacementBiome)) {
                            debugLog("      WARNING: biomes.set() did not update at section {} y {}! Expected {}, got {}",
                                sectionIdx, y, getBiomeId(replacementBiome), getBiomeId(afterSet));
                        }
                        // Log surface level replacement (section 7, y=3 for biomeY=15)
                        if (sectionIdx == 7 && y == 3) {
                            debugLog("      SURFACE: section={} y={} (biomeY=15): {} -> {}",
                                sectionIdx, y, getBiomeId(current), getBiomeId(replacementBiome));
                        }
                        replacedCount++;
                    }
                }
                sectionIdx++;
            }
            debugLog("      applyReplacement: local ({},{}) replaced {} biome entries",
                localX, localZ, replacedCount);

            // Step 4: Register this position AND its neighbors in BiomeReplacementRegistry
            // This ensures BiomeManager.getBiome() returns correct results even when
            // its fuzzy sampling queries adjacent positions in already-generated chunks
            if (replacedCount > 0 && originalKey != null) {
                BiomeReplacementRegistry.registerWithNeighbors(
                    chunkX, chunkZ, localX, localZ, originalKey, replacementBiome);
                debugLog("      applyReplacement: registered position ({},{}) and neighbors in BiomeReplacementRegistry",
                    localX, localZ);
            }
        }
    }

    /**
     * Get the string ID of a biome holder.
     * Used only for config lookups (neverReplace, neverUseAsReplacement lists).
     */
    private static String getBiomeId(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> key.location().toString())
            .orElse("");
    }

    /**
     * Compare biomes by ResourceKey (safe across different Holder sources).
     * Faster than String comparison - no allocation, uses reference equality on interned keys.
     *
     * This is preferred over Holder.equals() because chunk data and BiomeSource may return
     * different Holder instances for the same biome, but ResourceKeys are canonical.
     */
    private static boolean sameBiomeKey(Holder<Biome> a, Holder<Biome> b) {
        return a.unwrapKey().equals(b.unwrapKey());
    }

    /**
     * Get biome at position, bypassing ProtoChunk's status check.
     *
     * ProtoChunk.getNoiseBiome() throws "Asking for biomes before we have biomes"
     * if chunk status isn't BIOMES yet. But when we inject at TAIL of doCreateBiomes,
     * the biomes ARE populated via fillBiomesFromNoise - just the status isn't updated yet.
     *
     * This method accesses the biome data directly from sections.
     */
    private static Holder<Biome> getBiomeFromChunk(ChunkAccess chunk, int x, int y, int z) {
        int minY = chunk.getMinBuildHeight() >> 2;  // Convert to biome coords
        int maxY = minY + (chunk.getHeight() >> 2) - 1;
        int clampedY = Math.max(minY, Math.min(y, maxY));
        int sectionIndex = chunk.getSectionIndex(clampedY << 2);  // Convert back to block for section index
        LevelChunkSection section = chunk.getSections()[sectionIndex];
        return section.getNoiseBiome(x & 3, clampedY & 3, z & 3);
    }
}
