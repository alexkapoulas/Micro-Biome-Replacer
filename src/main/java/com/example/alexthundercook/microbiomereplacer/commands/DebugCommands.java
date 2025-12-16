package com.example.alexthundercook.microbiomereplacer.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Debug commands for investigating biome calculations.
 * These commands are independent from the main mod logic and use
 * straightforward implementations for accuracy verification.
 */
public final class DebugCommands {

    private DebugCommands() {}

    /**
     * Execute /microbiome surface_biome_size [x] [z]
     *
     * Calculates the 2D surface biome region size in quarts by flood filling
     * from the given position. Each quart position samples its own surface
     * height from noise (no interpolation).
     *
     * If no coordinates provided, uses the player's current position.
     */
    public static int executeSurfaceBiomeSize(CommandContext<CommandSourceStack> context) {
        // Determine coordinates - use arguments if provided, otherwise player position
        int x, z;
        try {
            x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "x");
            z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "z");
        } catch (IllegalArgumentException ignored) {
            // Arguments not provided, use player position
            x = (int) context.getSource().getPosition().x;
            z = (int) context.getSource().getPosition().z;
        }
        final int blockX = x;
        final int blockZ = z;

        ServerLevel level = context.getSource().getLevel();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        BiomeSource biomeSource = generator.getBiomeSource();
        Climate.Sampler sampler = randomState.sampler();

        // Convert block coords to biome coords (quarts)
        int startBiomeX = blockX >> 2;
        int startBiomeZ = blockZ >> 2;

        // Get surface height at starting position
        // We need a ChunkAccess for getBaseHeight - use any loaded chunk or null
        ChunkAccess chunk = level.getChunk(blockX >> 4, blockZ >> 4);
        int startSurfaceY = generator.getBaseHeight(blockX, blockZ, Heightmap.Types.WORLD_SURFACE, chunk, randomState);
        int startBiomeY = startSurfaceY >> 2;

        // Get biome at starting position
        Holder<Biome> targetBiome = biomeSource.getNoiseBiome(startBiomeX, startBiomeY, startBiomeZ, sampler);
        Optional<ResourceKey<Biome>> targetKey = targetBiome.unwrapKey();

        if (targetKey.isEmpty()) {
            context.getSource().sendFailure(Component.literal("Could not determine biome at position"));
            return 0;
        }

        // Flood fill to find region size
        int regionSize = floodFillRegionSize(
            startBiomeX, startBiomeZ, targetKey.get(),
            generator, chunk, randomState, biomeSource, sampler
        );

        String biomeName = targetKey.get().location().toString();
        int sizeInBlocks = regionSize * 16; // Each quart is 4x4 blocks
        context.getSource().sendSuccess(
            () -> Component.literal(String.format(
                "Surface biome size at (%d, %d): %s - %d quarts (%d blocks)",
                blockX, blockZ, biomeName, regionSize, sizeInBlocks
            )),
            false
        );

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Flood fill to count contiguous biome region size.
     * Each position samples its own surface height from noise (no interpolation).
     */
    private static int floodFillRegionSize(
            int startX, int startZ,
            ResourceKey<Biome> targetBiomeKey,
            ChunkGenerator generator,
            ChunkAccess chunk,
            RandomState randomState,
            BiomeSource biomeSource,
            Climate.Sampler sampler) {

        Set<Long> visited = new HashSet<>();
        Queue<long[]> queue = new ArrayDeque<>();

        queue.add(new long[]{startX, startZ});
        visited.add(encodePos(startX, startZ));

        int regionSize = 0;

        while (!queue.isEmpty()) {
            long[] pos = queue.poll();
            int biomeX = (int) pos[0];
            int biomeZ = (int) pos[1];

            // Get surface height for this specific position (no interpolation)
            int blockX = (biomeX << 2) + 2; // Center of quart
            int blockZ = (biomeZ << 2) + 2;
            int surfaceY = generator.getBaseHeight(blockX, blockZ, Heightmap.Types.WORLD_SURFACE, chunk, randomState);
            int biomeY = surfaceY >> 2;

            // Get biome at this position's surface
            Holder<Biome> biome = biomeSource.getNoiseBiome(biomeX, biomeY, biomeZ, sampler);
            Optional<ResourceKey<Biome>> biomeKey = biome.unwrapKey();

            // Check if same biome (compare by ResourceKey)
            if (biomeKey.isEmpty() || !biomeKey.get().equals(targetBiomeKey)) {
                continue; // Different biome, not part of region
            }

            regionSize++;

            // Add cardinal neighbors to queue
            addNeighbor(biomeX - 1, biomeZ, visited, queue);
            addNeighbor(biomeX + 1, biomeZ, visited, queue);
            addNeighbor(biomeX, biomeZ - 1, visited, queue);
            addNeighbor(biomeX, biomeZ + 1, visited, queue);
        }

        return regionSize;
    }

    private static void addNeighbor(int x, int z, Set<Long> visited, Queue<long[]> queue) {
        long encoded = encodePos(x, z);
        if (!visited.contains(encoded)) {
            visited.add(encoded);
            queue.add(new long[]{x, z});
        }
    }

    private static long encodePos(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
