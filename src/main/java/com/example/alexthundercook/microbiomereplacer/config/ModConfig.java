package com.example.alexthundercook.microbiomereplacer.config;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration for the Micro Biome Replacer mod.
 * Uses NeoForge's ModConfigSpec for type-safe configuration.
 */
public class ModConfig {

    public static final ModConfigSpec SPEC;

    // Config values
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue MINIMUM_SIZE_BLOCKS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NEVER_REPLACE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NEVER_USE_AS_REPLACEMENT;
    public static final ModConfigSpec.BooleanValue ACCURATE_SURFACE_SAMPLING;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;
    public static final ModConfigSpec.IntValue DEBUG_TARGET_X;
    public static final ModConfigSpec.IntValue DEBUG_TARGET_Z;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Micro Biome Replacer Configuration")
               .push("microbiome");

        ENABLED = builder
            .comment("Enable or disable micro biome replacement")
            .define("enabled", true);

        MINIMUM_SIZE_BLOCKS = builder
            .comment(
                "Minimum size in blocks for a biome to be preserved.",
                "Regions smaller than this are replaced with the dominant neighbor.",
                "Must be positive; internally converted to quart threshold (divide by 16).",
                "Default: 3000 blocks = 187 quarts (~11.7 chunks' worth of biome positions)"
            )
            .defineInRange("minimumSizeBlocks", 3000, 16, 4096);

        NEVER_REPLACE = builder
            .comment(
                "Biomes that should NEVER be replaced, even if small.",
                "Useful for preserving intentionally small biomes.",
                "Use full resource locations (e.g., 'minecraft:mushroom_fields')"
            )
            .defineListAllowEmpty(
                "neverReplace",
                List.of(
                    "minecraft:mushroom_fields",
                    "minecraft:ice_spikes",
                    "minecraft:deep_dark",
                    "minecraft:beach",
                    "minecraft:snowy_beach",
                    "minecraft:stony_shore",
                    "minecraft:river",
                    "minecraft:frozen_river"
                ),
                () -> "",
                obj -> obj instanceof String
            );

        NEVER_USE_AS_REPLACEMENT = builder
            .comment(
                "Biomes that should NEVER expand to replace micro biomes.",
                "Prevents oceans from 'eating' small islands, etc.",
                "Use full resource locations (e.g., 'minecraft:ocean')"
            )
            .defineListAllowEmpty(
                "neverUseAsReplacement",
                List.of(
                    "minecraft:ocean",
                    "minecraft:deep_ocean",
                    "minecraft:frozen_ocean",
                    "minecraft:deep_frozen_ocean",
                    "minecraft:warm_ocean",
                    "minecraft:lukewarm_ocean",
                    "minecraft:deep_lukewarm_ocean",
                    "minecraft:cold_ocean",
                    "minecraft:deep_cold_ocean",
                    "minecraft:river",
                    "minecraft:frozen_river"
                ),
                () -> "",
                obj -> obj instanceof String
            );

        builder.pop();

        builder.comment("Performance Settings")
               .push("performance");

        ACCURATE_SURFACE_SAMPLING = builder
            .comment(
                "Use accurate surface height sampling via noise queries.",
                "When true, uses ChunkGenerator.getBaseHeight() for precise surface detection.",
                "When false, uses a fixed Y=64 approximation (faster but less accurate)."
            )
            .define("accurateSurfaceSampling", true);

        DEBUG_LOGGING = builder
            .comment(
                "Enable verbose debug logging for troubleshooting.",
                "When true, logs detailed information about chunk processing,",
                "flood fill operations, and replacement decisions.",
                "WARNING: This generates a LOT of log output. Only enable for debugging.",
                "TIP: Set debugTargetX and debugTargetZ to limit logging to a specific area."
            )
            .define("debugLogging", false);

        DEBUG_TARGET_X = builder
            .comment(
                "Target block X coordinate for debug logging.",
                "When debugLogging is enabled, only log chunks containing this X coordinate.",
                "Set to Integer.MIN_VALUE (default) to log ALL chunks.",
                "Example: -1323 to debug the chunk containing block X=-1323"
            )
            .defineInRange("debugTargetX", Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);

        DEBUG_TARGET_Z = builder
            .comment(
                "Target block Z coordinate for debug logging.",
                "When debugLogging is enabled, only log chunks containing this Z coordinate.",
                "Set to Integer.MIN_VALUE (default) to log ALL chunks.",
                "Example: 19 to debug the chunk containing block Z=19"
            )
            .defineInRange("debugTargetZ", Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);

        builder.pop();

        SPEC = builder.build();
    }

    /**
     * Check if the mod is enabled.
     */
    public static boolean isEnabled() {
        return ENABLED.get();
    }

    /**
     * Get the minimum size threshold in quarts (biome coordinates).
     * Converts from block threshold to quart threshold.
     */
    public static int getThresholdInQuarts() {
        return MINIMUM_SIZE_BLOCKS.get() / 16;
    }

    /**
     * Check if a biome should never be replaced.
     */
    public static boolean shouldNeverReplace(String biomeId) {
        return NEVER_REPLACE.get().contains(biomeId);
    }

    /**
     * Check if a biome should never be used as a replacement.
     */
    public static boolean shouldNeverUseAsReplacement(String biomeId) {
        return NEVER_USE_AS_REPLACEMENT.get().contains(biomeId);
    }

    /**
     * Check if accurate surface sampling is enabled.
     */
    public static boolean useAccurateSurfaceSampling() {
        return ACCURATE_SURFACE_SAMPLING.get();
    }

    /**
     * Check if debug logging is enabled.
     */
    public static boolean isDebugLoggingEnabled() {
        return DEBUG_LOGGING.get();
    }

    /**
     * Get the target X block coordinate for debug logging.
     * Returns Integer.MIN_VALUE if no target is set (log all chunks).
     */
    public static int getDebugTargetX() {
        return DEBUG_TARGET_X.get();
    }

    /**
     * Get the target Z block coordinate for debug logging.
     * Returns Integer.MIN_VALUE if no target is set (log all chunks).
     */
    public static int getDebugTargetZ() {
        return DEBUG_TARGET_Z.get();
    }

    /**
     * Check if a chunk should be logged based on debug target coordinates.
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return true if this chunk should have debug logging
     */
    public static boolean shouldDebugLogChunk(int chunkX, int chunkZ) {
        if (!DEBUG_LOGGING.get()) {
            return false;
        }

        int targetX = DEBUG_TARGET_X.get();
        int targetZ = DEBUG_TARGET_Z.get();

        // If both targets are MIN_VALUE, log all chunks
        if (targetX == Integer.MIN_VALUE && targetZ == Integer.MIN_VALUE) {
            return true;
        }

        // Convert target block coordinates to chunk coordinates
        // Chunk contains blocks from (chunkX * 16) to (chunkX * 16 + 15)
        int targetChunkX = targetX >> 4;
        int targetChunkZ = targetZ >> 4;

        // Check if this chunk matches the target (or target is not set for that axis)
        boolean matchX = (targetX == Integer.MIN_VALUE) || (chunkX == targetChunkX);
        boolean matchZ = (targetZ == Integer.MIN_VALUE) || (chunkZ == targetChunkZ);

        return matchX && matchZ;
    }
}
