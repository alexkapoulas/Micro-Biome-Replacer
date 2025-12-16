package com.example.alexthundercook.microbiomereplacer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

/**
 * Registry for tracking micro biome replacements at query time.
 *
 * This registry is used by the BiomeQueryMixin to intercept BiomeManager.getBiome()
 * calls and return the correct replacement biome. This handles BiomeManager's
 * -2 block offset and fuzzy sampling which can query positions in adjacent chunks.
 *
 * The registry stores:
 * - Which biome positions have been replaced
 * - What original biome they had (for verification)
 * - What replacement biome they should return
 *
 * Thread-safe: Uses ConcurrentHashMap for parallel chunk generation.
 */
public final class BiomeReplacementRegistry {

    /**
     * Entry storing replacement information for a biome position.
     */
    public record ReplacementEntry(
        ResourceKey<Biome> originalBiome,
        Holder<Biome> replacementHolder
    ) {}

    /**
     * Registry of replaced positions.
     * Key: Encoded position (chunkPosLong combined with local biome index)
     * Value: Replacement entry
     */
    private static final ConcurrentHashMap<Long, ReplacementEntry> REPLACEMENTS = new ConcurrentHashMap<>();

    /**
     * Biome holder cache for looking up biomes by ResourceKey.
     * Populated during chunk processing.
     */
    private static final ConcurrentHashMap<ResourceKey<Biome>, Holder<Biome>> BIOME_HOLDERS = new ConcurrentHashMap<>();

    // Prevent instantiation
    private BiomeReplacementRegistry() {}

    /**
     * Register a biome replacement for a specific position.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param localX Local biome X within chunk (0-3)
     * @param localZ Local biome Z within chunk (0-3)
     * @param originalBiome The original biome's ResourceKey
     * @param replacementHolder The replacement biome Holder
     */
    public static void registerReplacement(int chunkX, int chunkZ, int localX, int localZ,
            ResourceKey<Biome> originalBiome, Holder<Biome> replacementHolder) {

        long key = encodePosition(chunkX, chunkZ, localX, localZ);
        REPLACEMENTS.put(key, new ReplacementEntry(originalBiome, replacementHolder));

        // Cache the replacement holder for later lookup
        replacementHolder.unwrapKey().ifPresent(rk -> BIOME_HOLDERS.putIfAbsent(rk, replacementHolder));
    }

    /**
     * Check if a biome query should return a replacement.
     * Called from BiomeQueryMixin for every BiomeManager.getBiome() call.
     *
     * @param pos The block position being queried
     * @param queriedBiome The biome that BiomeManager would return
     * @return The replacement biome if applicable, or null if no replacement needed
     */
    public static Holder<Biome> getReplacement(BlockPos pos, Holder<Biome> queriedBiome) {
        // Convert block position to biome position
        int biomeX = pos.getX() >> 2;
        int biomeY = pos.getY() >> 2;
        int biomeZ = pos.getZ() >> 2;

        // Get chunk coordinates from biome coordinates
        int chunkX = biomeX >> 2;
        int chunkZ = biomeZ >> 2;

        // Get local biome coordinates within chunk
        int localX = biomeX & 3;
        int localZ = biomeZ & 3;

        // Look up replacement
        long key = encodePosition(chunkX, chunkZ, localX, localZ);
        ReplacementEntry entry = REPLACEMENTS.get(key);

        if (entry == null) {
            return null; // No replacement registered for this position
        }

        // Verify the queried biome matches what we expect to replace
        // This handles the case where the biome was already replaced in chunk data
        ResourceKey<Biome> queriedKey = queriedBiome.unwrapKey().orElse(null);
        if (queriedKey != null && queriedKey.equals(entry.originalBiome)) {
            return entry.replacementHolder;
        }

        // Also return replacement if queried biome is already the replacement
        // (BiomeManager might sample from our replaced position)
        ResourceKey<Biome> replacementKey = entry.replacementHolder.unwrapKey().orElse(null);
        if (queriedKey != null && queriedKey.equals(replacementKey)) {
            return entry.replacementHolder; // Already correct, return as-is
        }

        return null;
    }

    /**
     * Register all 8 neighbors of a position for buffer zone coverage.
     * This ensures BiomeManager's fuzzy sampling returns correct results.
     *
     * @param chunkX Chunk X coordinate of the center position
     * @param chunkZ Chunk Z coordinate of the center position
     * @param localX Local biome X of the center position (0-3)
     * @param localZ Local biome Z of the center position (0-3)
     * @param originalBiome The original biome's ResourceKey
     * @param replacementHolder The replacement biome Holder
     */
    public static void registerWithNeighbors(int chunkX, int chunkZ, int localX, int localZ,
            ResourceKey<Biome> originalBiome, Holder<Biome> replacementHolder) {

        // Register the center position
        registerReplacement(chunkX, chunkZ, localX, localZ, originalBiome, replacementHolder);

        // Register all 8 neighbors
        int[][] neighbors = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1},   // Cardinal
            {-1, -1}, {1, -1}, {-1, 1}, {1, 1}  // Diagonal
        };

        for (int[] offset : neighbors) {
            int nx = localX + offset[0];
            int nz = localZ + offset[1];

            int targetChunkX = chunkX;
            int targetChunkZ = chunkZ;
            int targetLocalX = nx;
            int targetLocalZ = nz;

            // Handle cross-chunk positions
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

            registerReplacement(targetChunkX, targetChunkZ, targetLocalX, targetLocalZ,
                originalBiome, replacementHolder);
        }
    }

    /**
     * Clear all registered replacements.
     * Call when world unloads to prevent memory leaks.
     */
    public static void clear() {
        REPLACEMENTS.clear();
        BIOME_HOLDERS.clear();
    }

    /**
     * Get the number of registered replacements (for debugging).
     */
    public static int size() {
        return REPLACEMENTS.size();
    }

    /**
     * Encode chunk and local position into a single long key.
     */
    private static long encodePosition(int chunkX, int chunkZ, int localX, int localZ) {
        // Pack: chunkX (20 bits) | chunkZ (20 bits) | localX (2 bits) | localZ (2 bits)
        // This gives us range of about ±500k chunks which is more than enough
        long cx = chunkX & 0xFFFFF;
        long cz = chunkZ & 0xFFFFF;
        long lx = localX & 0x3;
        long lz = localZ & 0x3;
        return (cx << 24) | (cz << 4) | (lx << 2) | lz;
    }
}
