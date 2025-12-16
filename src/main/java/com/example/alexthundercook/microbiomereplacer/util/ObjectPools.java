package com.example.alexthundercook.microbiomereplacer.util;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ThreadLocal object pools to eliminate GC pressure during chunk processing.
 *
 * Each thread gets its own set of reusable data structures, avoiding:
 * - Object allocation during flood fill operations
 * - Cross-thread synchronization
 * - Garbage collection pauses
 *
 * Memory comparison (from research doc):
 * - BitSet vs HashSet<BlockPos>: 14x smaller
 * - long[] vs ArrayDeque<Long>: 1.5x smaller
 * - int[] vs ArrayList<Position>: 4.7x smaller
 */
public final class ObjectPools {

    /**
     * Maximum capacity for pools based on threshold.
     * Default threshold is 256 blocks = 16 quarts, but we allocate
     * extra space for cross-chunk exploration and boundary positions.
     * Increased to handle larger thresholds (up to 4096 blocks = 256 quarts).
     */
    private static final int POOL_CAPACITY = 1024;

    /**
     * Visited set for flood fill tracking.
     * Uses BitSet for compact storage - positions are encoded as indices.
     */
    private static final ThreadLocal<BitSet> VISITED_SET =
        ThreadLocal.withInitial(() -> new BitSet(POOL_CAPACITY));

    /**
     * Queue for BFS flood fill.
     * Stores encoded (x, z) coordinates as longs.
     */
    private static final ThreadLocal<long[]> FLOOD_QUEUE =
        ThreadLocal.withInitial(() -> new long[POOL_CAPACITY]);

    /**
     * Buffer for storing region positions during detection.
     * Each int encodes a (x, z) position.
     */
    private static final ThreadLocal<int[]> REGION_BUFFER =
        ThreadLocal.withInitial(() -> new int[POOL_CAPACITY]);

    /**
     * Cache for surface heights.
     * 16 positions per chunk (4x4 biome grid).
     */
    private static final ThreadLocal<int[]> HEIGHT_CACHE =
        ThreadLocal.withInitial(() -> new int[16]);

    /**
     * Tracks whether the height cache has been fully initialized with bilinear interpolation.
     * When false, only corner (0,0) has been sampled and all positions contain that value.
     * This enables lazy initialization - we only do full interpolation for non-homogeneous chunks.
     */
    private static final ThreadLocal<Boolean> HEIGHT_CACHE_FULLY_INITIALIZED =
        ThreadLocal.withInitial(() -> false);

    /**
     * Flag array for quick biome presence checking within a chunk.
     * Tracks which positions in the 4x4 grid have been processed.
     */
    private static final ThreadLocal<boolean[]> CHUNK_PROCESSED =
        ThreadLocal.withInitial(() -> new boolean[16]);

    /**
     * Cross-chunk height cache: 3x3 chunks = 12x12 biome positions = 144 entries.
     * All 16 positions for a chunk are populated together (no per-position tracking needed).
     */
    private static final ThreadLocal<int[]> CROSS_CHUNK_HEIGHT_CACHE =
        ThreadLocal.withInitial(() -> new int[144]);

    /**
     * Track which of the 9 chunks (3x3 grid) have been initialized.
     * Index: chunkOffsetZ * 3 + chunkOffsetX, where offsets are 0,1,2 (representing -1,0,+1).
     */
    private static final ThreadLocal<BitSet> CROSS_CHUNK_INITIALIZED =
        ThreadLocal.withInitial(() -> new BitSet(9));

    /**
     * Cache for validated replacement candidates during micro biome processing.
     * When selecting a replacement biome, we need to verify it's not itself a micro biome.
     * This cache stores validation results to avoid redundant flood fills.
     *
     * Key: Encoded (biomeX, biomeZ, biomeKeyHashCode) - position where candidate was sampled
     * Value: true if region >= threshold (non-micro, valid replacement), false if micro
     */
    private static final ThreadLocal<Map<Long, Boolean>> VALIDATED_CANDIDATES_CACHE =
        ThreadLocal.withInitial(() -> new HashMap<>(32));

    // ========== Cross-Thread Shared Height Cache (L2) ==========
    // Allows multiple chunk generation threads to share computed height values,
    // reducing redundant getBaseHeight() calls when processing adjacent chunks.

    /**
     * Maximum entries in the shared cache before eviction triggers.
     * 4096 entries * ~144 bytes/entry = ~590KB memory footprint.
     */
    private static final int SHARED_CACHE_MAX_ENTRIES = 4096;

    /**
     * Number of entries to remove during each eviction pass.
     * Batch eviction amortizes the cost over multiple insertions.
     */
    private static final int SHARED_CACHE_EVICTION_BATCH = 512;

    /**
     * Cross-thread shared height cache.
     * Key: ChunkPos.asLong() for the chunk
     * Value: int[16] containing heights for all 16 biome positions in the chunk
     */
    private static final ConcurrentHashMap<Long, int[]> SHARED_HEIGHT_CACHE =
        new ConcurrentHashMap<>(SHARED_CACHE_MAX_ENTRIES);

    /**
     * Tracks last access time for each cached chunk (for LRU eviction).
     * Key: ChunkPos.asLong()
     * Value: System.nanoTime() of last access
     */
    private static final ConcurrentHashMap<Long, Long> SHARED_CACHE_ACCESS_TIMES =
        new ConcurrentHashMap<>(SHARED_CACHE_MAX_ENTRIES);

    /**
     * Approximate size counter to avoid expensive ConcurrentHashMap.size() calls.
     */
    private static final AtomicInteger SHARED_CACHE_SIZE = new AtomicInteger(0);

    /**
     * Lock for eviction - only one thread performs eviction at a time.
     */
    private static final Object EVICTION_LOCK = new Object();

    // ========== Cross-Chunk Buffer Zone Registry ==========
    // Tracks positions in adjacent chunks that need buffer replacement due to
    // BiomeManager's fuzzy sampling. When a micro biome at a chunk edge is replaced,
    // adjacent positions (up to 1 biome away) may be queried by BiomeManager.
    // These positions are registered here so adjacent chunks can replace them.

    /**
     * Buffer zone entry containing position and replacement info.
     */
    public record BufferZoneEntry(
        int localX,           // Local biome X within chunk (0-3)
        int localZ,           // Local biome Z within chunk (0-3)
        String originalBiome, // Only replace if current biome matches this
        String replacementBiome // Replace with this biome
    ) {}

    /**
     * Buffer zone registry.
     * Key: ChunkPos.asLong() for the chunk that should apply replacements
     * Value: List of buffer zone entries to apply
     */
    private static final ConcurrentHashMap<Long, java.util.concurrent.CopyOnWriteArrayList<BufferZoneEntry>>
        BUFFER_ZONE_REGISTRY = new ConcurrentHashMap<>();

    /**
     * Maximum age in milliseconds for buffer zone entries before they expire.
     * Entries older than this are considered stale (chunk was never generated).
     */
    private static final long BUFFER_ZONE_MAX_AGE_MS = 60_000; // 1 minute

    /**
     * Timestamp tracking for buffer zone entries.
     * Key: ChunkPos.asLong()
     * Value: Creation timestamp (System.currentTimeMillis())
     */
    private static final ConcurrentHashMap<Long, Long> BUFFER_ZONE_TIMESTAMPS = new ConcurrentHashMap<>();

    // Prevent instantiation
    private ObjectPools() {}

    // ========== Pool Accessors ==========

    public static BitSet getVisitedSet() {
        return VISITED_SET.get();
    }

    public static long[] getFloodQueue() {
        return FLOOD_QUEUE.get();
    }

    public static int[] getRegionBuffer() {
        return REGION_BUFFER.get();
    }

    public static int[] getHeightCache() {
        return HEIGHT_CACHE.get();
    }

    public static boolean[] getChunkProcessed() {
        return CHUNK_PROCESSED.get();
    }

    public static int[] getCrossChunkHeightCache() {
        return CROSS_CHUNK_HEIGHT_CACHE.get();
    }

    public static BitSet getCrossChunkInitialized() {
        return CROSS_CHUNK_INITIALIZED.get();
    }

    public static Map<Long, Boolean> getValidatedCandidatesCache() {
        return VALIDATED_CANDIDATES_CACHE.get();
    }

    // ========== Shared Height Cache (L2) Accessors ==========

    /**
     * Get cached heights for a chunk from the shared cross-thread cache.
     * Updates access time for LRU tracking.
     *
     * @param chunkPosLong ChunkPos.asLong() for the target chunk
     * @return int[16] of cached heights, or null if not cached
     */
    public static int[] getSharedHeights(long chunkPosLong) {
        int[] heights = SHARED_HEIGHT_CACHE.get(chunkPosLong);
        if (heights != null) {
            // Update access time for LRU tracking
            SHARED_CACHE_ACCESS_TIMES.put(chunkPosLong, System.nanoTime());
        }
        return heights;
    }

    /**
     * Store heights for a chunk in the shared cross-thread cache.
     * Uses putIfAbsent for thread-safe insertion without duplicating computation.
     * Triggers eviction if cache exceeds size limit.
     *
     * @param chunkPosLong ChunkPos.asLong() for the target chunk
     * @param heights int[16] containing heights for all 16 biome positions
     */
    public static void putSharedHeights(long chunkPosLong, int[] heights) {
        if (SHARED_HEIGHT_CACHE.putIfAbsent(chunkPosLong, heights) == null) {
            // Successfully inserted (wasn't already present)
            SHARED_CACHE_ACCESS_TIMES.put(chunkPosLong, System.nanoTime());
            if (SHARED_CACHE_SIZE.incrementAndGet() > SHARED_CACHE_MAX_ENTRIES) {
                maybeEvictShared();
            }
        }
    }

    /**
     * Perform LRU eviction if cache exceeds size limit.
     * Only one thread performs eviction at a time.
     */
    private static void maybeEvictShared() {
        synchronized (EVICTION_LOCK) {
            // Double-check after acquiring lock
            if (SHARED_CACHE_SIZE.get() <= SHARED_CACHE_MAX_ENTRIES) {
                return;
            }

            // Collect entries and sort by access time (oldest first)
            List<Map.Entry<Long, Long>> entries =
                new ArrayList<>(SHARED_CACHE_ACCESS_TIMES.entrySet());
            entries.sort(Map.Entry.comparingByValue());

            // Remove oldest entries
            int removed = 0;
            for (Map.Entry<Long, Long> entry : entries) {
                if (removed >= SHARED_CACHE_EVICTION_BATCH) break;
                Long key = entry.getKey();
                SHARED_HEIGHT_CACHE.remove(key);
                SHARED_CACHE_ACCESS_TIMES.remove(key);
                removed++;
            }
            SHARED_CACHE_SIZE.addAndGet(-removed);
        }
    }

    /**
     * Clear the shared height cache.
     * Call when world unloads to prevent stale data and memory leaks.
     */
    public static void clearSharedCache() {
        SHARED_HEIGHT_CACHE.clear();
        SHARED_CACHE_ACCESS_TIMES.clear();
        SHARED_CACHE_SIZE.set(0);
    }

    /**
     * Get approximate size of shared cache for monitoring/debugging.
     */
    public static int getSharedCacheSize() {
        return SHARED_CACHE_SIZE.get();
    }

    // ========== Buffer Zone Registry Accessors ==========

    /**
     * Register a buffer zone position for an adjacent chunk.
     * This position will be replaced when the target chunk is processed.
     *
     * @param targetChunkPosLong ChunkPos.asLong() for the chunk that should apply replacement
     * @param localX Local biome X within target chunk (0-3)
     * @param localZ Local biome Z within target chunk (0-3)
     * @param originalBiome Only replace if current biome matches this ID
     * @param replacementBiome Replace with this biome ID
     */
    public static void registerBufferZone(long targetChunkPosLong, int localX, int localZ,
            String originalBiome, String replacementBiome) {
        BufferZoneEntry entry = new BufferZoneEntry(localX, localZ, originalBiome, replacementBiome);

        BUFFER_ZONE_REGISTRY.computeIfAbsent(targetChunkPosLong,
            k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(entry);

        BUFFER_ZONE_TIMESTAMPS.putIfAbsent(targetChunkPosLong, System.currentTimeMillis());
    }

    /**
     * Get and remove buffer zone entries for a chunk.
     * Returns null if no entries registered, and cleans up stale entries.
     *
     * @param chunkPosLong ChunkPos.asLong() for the chunk being processed
     * @return List of buffer zone entries to apply, or null if none
     */
    public static java.util.List<BufferZoneEntry> consumeBufferZone(long chunkPosLong) {
        // Remove and return entries for this chunk
        java.util.concurrent.CopyOnWriteArrayList<BufferZoneEntry> entries =
            BUFFER_ZONE_REGISTRY.remove(chunkPosLong);
        BUFFER_ZONE_TIMESTAMPS.remove(chunkPosLong);

        // Periodically clean up stale entries (simple probabilistic cleanup)
        if (Math.random() < 0.01) { // 1% chance on each call
            cleanupStaleBufferZones();
        }

        return entries;
    }

    /**
     * Remove buffer zone entries older than MAX_AGE_MS.
     * Called periodically to prevent memory leaks from never-generated chunks.
     */
    private static void cleanupStaleBufferZones() {
        long now = System.currentTimeMillis();
        BUFFER_ZONE_TIMESTAMPS.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > BUFFER_ZONE_MAX_AGE_MS) {
                BUFFER_ZONE_REGISTRY.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * Clear all buffer zone entries.
     * Call when world unloads to prevent memory leaks.
     */
    public static void clearBufferZones() {
        BUFFER_ZONE_REGISTRY.clear();
        BUFFER_ZONE_TIMESTAMPS.clear();
    }

    /**
     * Get count of chunks with pending buffer zones (for debugging).
     */
    public static int getBufferZoneChunkCount() {
        return BUFFER_ZONE_REGISTRY.size();
    }

    // ========== Cross-Chunk Coordinate Helpers ==========

    /**
     * Get chunk grid index (0-8) from biome coordinates.
     * The 3x3 grid is indexed as: chunkOffsetZ * 3 + chunkOffsetX
     * where offsets are 0,1,2 representing chunk positions -1,0,+1 relative to current.
     *
     * @param biomeX Global biome X coordinate
     * @param biomeZ Global biome Z coordinate
     * @param chunkMinBiomeX Current chunk's minimum biome X (chunk.getPos().x << 2)
     * @param chunkMinBiomeZ Current chunk's minimum biome Z (chunk.getPos().z << 2)
     * @return Chunk grid index (0-8), or -1 if outside 3x3 grid
     */
    public static int getChunkGridIndex(int biomeX, int biomeZ, int chunkMinBiomeX, int chunkMinBiomeZ) {
        int chunkOffsetX = ((biomeX - chunkMinBiomeX) >> 2) + 1;  // -1,0,+1 → 0,1,2
        int chunkOffsetZ = ((biomeZ - chunkMinBiomeZ) >> 2) + 1;
        if (chunkOffsetX < 0 || chunkOffsetX > 2 || chunkOffsetZ < 0 || chunkOffsetZ > 2) {
            return -1;  // Outside 3x3 grid
        }
        return chunkOffsetZ * 3 + chunkOffsetX;
    }

    /**
     * Get height cache index (0-143) from biome coordinates.
     * The 12x12 grid covers 3x3 chunks, with the current chunk at center (indices 4-7 on each axis).
     *
     * @param biomeX Global biome X coordinate
     * @param biomeZ Global biome Z coordinate
     * @param chunkMinBiomeX Current chunk's minimum biome X
     * @param chunkMinBiomeZ Current chunk's minimum biome Z
     * @return Cache index (0-143), or -1 if outside 3x3 grid
     */
    public static int getCrossChunkHeightIndex(int biomeX, int biomeZ,
            int chunkMinBiomeX, int chunkMinBiomeZ) {
        int gridX = (biomeX - chunkMinBiomeX) + 4;
        int gridZ = (biomeZ - chunkMinBiomeZ) + 4;
        if (gridX < 0 || gridX >= 12 || gridZ < 0 || gridZ >= 12) {
            return -1;
        }
        return gridZ * 12 + gridX;
    }

    public static boolean isHeightCacheFullyInitialized() {
        return HEIGHT_CACHE_FULLY_INITIALIZED.get();
    }

    public static void setHeightCacheFullyInitialized(boolean value) {
        HEIGHT_CACHE_FULLY_INITIALIZED.set(value);
    }

    // ========== Reset Methods ==========

    /**
     * Reset all pools for a new chunk processing operation.
     * Call this at the start of processing each chunk.
     */
    public static void resetAll() {
        VISITED_SET.get().clear();
        HEIGHT_CACHE_FULLY_INITIALIZED.set(false);
        CROSS_CHUNK_INITIALIZED.get().clear();
        // Arrays don't need explicit clearing if we track usage indices
    }

    /**
     * Reset the chunk-local processed flags.
     */
    public static void resetChunkProcessed() {
        boolean[] processed = CHUNK_PROCESSED.get();
        for (int i = 0; i < processed.length; i++) {
            processed[i] = false;
        }
    }

    /**
     * Reset the validated candidates cache.
     * Call at start of each chunk processing to clear stale validation results.
     */
    public static void resetValidatedCandidatesCache() {
        VALIDATED_CANDIDATES_CACHE.get().clear();
    }

    // ========== Coordinate Encoding ==========

    /**
     * Encode (x, z) biome coordinates into a single long.
     * This avoids creating BlockPos objects during flood fill.
     *
     * @param x Biome X coordinate (can be negative)
     * @param z Biome Z coordinate (can be negative)
     * @return Encoded long value
     */
    public static long encodePos(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Decode X coordinate from encoded position.
     */
    public static int decodeX(long encoded) {
        return (int) (encoded >> 32);
    }

    /**
     * Decode Z coordinate from encoded position.
     */
    public static int decodeZ(long encoded) {
        return (int) encoded;
    }

    /**
     * Encode section-relative (x, z) into a single int for chunk-local tracking.
     * x and z must be in range [0, 3] (4x4 biome grid per chunk).
     *
     * @param x Local biome X (0-3)
     * @param z Local biome Z (0-3)
     * @return Index in range [0, 15]
     */
    public static int encodeLocal(int x, int z) {
        return (z << 2) | x;
    }

    /**
     * Decode local X from encoded local position.
     */
    public static int decodeLocalX(int encoded) {
        return encoded & 3;
    }

    /**
     * Decode local Z from encoded local position.
     */
    public static int decodeLocalZ(int encoded) {
        return encoded >> 2;
    }

    // ========== Coordinate Conversion ==========

    /**
     * Convert block coordinate to biome coordinate (quart).
     * Biomes use 4x4x4 block resolution.
     */
    public static int blockToBiome(int blockCoord) {
        return blockCoord >> 2;
    }

    /**
     * Convert biome coordinate to block coordinate (center of quart).
     */
    public static int biomeToBlock(int biomeCoord) {
        return (biomeCoord << 2) + 2;
    }
}
