package com.example.alexthundercook.microbiomereplacer.commands;

import com.example.alexthundercook.microbiomereplacer.MicroBiomeReplacer;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Test commands for automated testing via RCON.
 * All commands output JSON with [TEST_RESULT] prefix for parsing by test harness.
 */
@EventBusSubscriber(modid = MicroBiomeReplacer.MODID, bus = EventBusSubscriber.Bus.GAME)
public class TestCommands {

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("microbiome")
            .requires(source -> source.hasPermission(2)) // Require op level 2
            .then(Commands.literal("inspect")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(TestCommands::executeInspect)))))
            .then(Commands.literal("batch_inspect")
                .then(Commands.argument("file", StringArgumentType.string())
                    .executes(TestCommands::executeBatchInspect)))
            .then(Commands.literal("forceload_chunks")
                .then(Commands.argument("file", StringArgumentType.string())
                    .executes(TestCommands::executeForceloadChunks)))
            .then(Commands.literal("surface_biome_size")
                .executes(DebugCommands::executeSurfaceBiomeSize)
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .executes(DebugCommands::executeSurfaceBiomeSize)))));

        MicroBiomeReplacer.LOGGER.info("Registered test commands: /microbiome inspect, batch_inspect, forceload_chunks, surface_biome_size");
    }

    /**
     * /microbiome inspect <x> <y> <z>
     * Query the biome at specific block coordinates.
     */
    private static int executeInspect(CommandContext<CommandSourceStack> context) {
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");

        ServerLevel level = context.getSource().getLevel();

        // Debug: Calculate chunk and biome coordinates
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int biomeX = x >> 2;
        int biomeY = y >> 2;
        int biomeZ = z >> 2;
        int localBiomeX = biomeX & 3;
        int localBiomeZ = biomeZ & 3;

        MicroBiomeReplacer.LOGGER.info("[INSPECT-DEBUG] Block ({},{},{}) -> chunk [{},{}], biome ({},{},{}), local biome ({},{})",
            x, y, z, chunkX, chunkZ, biomeX, biomeY, biomeZ, localBiomeX, localBiomeZ);

        // Query biome stored in chunk (after mod processing), not from noise
        Holder<Biome> biomeHolder = level.getBiome(new BlockPos(x, y, z));
        String biomeId = getBiomeId(biomeHolder);

        MicroBiomeReplacer.LOGGER.info("[INSPECT-DEBUG] level.getBiome() returned: {}", biomeId);

        // Also query directly from chunk section to compare
        try {
            var chunk = level.getChunk(chunkX, chunkZ);
            int sectionIndex = chunk.getSectionIndex(y);
            var section = chunk.getSections()[sectionIndex];
            if (section != null) {
                int sectionLocalY = (biomeY) & 3;  // Y within section's 4 biome levels
                var sectionBiome = section.getNoiseBiome(localBiomeX, sectionLocalY, localBiomeZ);
                String sectionBiomeId = getBiomeId(sectionBiome);
                MicroBiomeReplacer.LOGGER.info("[INSPECT-DEBUG] Direct section read: section={}, localY={}, biome={}",
                    sectionIndex, sectionLocalY, sectionBiomeId);
                if (!biomeId.equals(sectionBiomeId)) {
                    MicroBiomeReplacer.LOGGER.warn("[INSPECT-DEBUG] MISMATCH! level.getBiome()={} but section has {}",
                        biomeId, sectionBiomeId);
                }
            }
        } catch (Exception e) {
            MicroBiomeReplacer.LOGGER.warn("[INSPECT-DEBUG] Error reading section: {}", e.getMessage());
        }

        long timestamp = System.currentTimeMillis() / 1000;

        // Output JSON result
        String json = String.format(
            "{\"command\":\"inspect\",\"x\":%d,\"y\":%d,\"z\":%d,\"biome\":\"%s\",\"timestamp\":%d}",
            x, y, z, biomeId, timestamp
        );
        MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", json);

        // Send feedback to command sender
        context.getSource().sendSuccess(
            () -> Component.literal("Biome at " + x + "," + y + "," + z + ": " + biomeId),
            false
        );

        return Command.SINGLE_SUCCESS;
    }

    /**
     * /microbiome batch_inspect <file>
     * Query biomes at multiple coordinates from a CSV file.
     * CSV format: biome_id,x,z,surface_y,is_block_coord
     */
    private static int executeBatchInspect(CommandContext<CommandSourceStack> context) {
        String filename = StringArgumentType.getString(context, "file");
        ServerLevel level = context.getSource().getLevel();
        Path serverDir = level.getServer().getServerDirectory();
        Path filePath = serverDir.resolve(filename);

        if (!Files.exists(filePath)) {
            String errorJson = String.format(
                "{\"command\":\"batch_inspect\",\"error\":\"file_not_found\",\"file\":\"%s\"}",
                escapeJson(filename)
            );
            MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", errorJson);
            context.getSource().sendFailure(Component.literal("File not found: " + filename));
            return 0;
        }

        long startTime = System.currentTimeMillis();
        int count = 0;
        int errors = 0;

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            boolean isHeader = true;

            while ((line = reader.readLine()) != null) {
                // Skip header line
                if (isHeader) {
                    isHeader = false;
                    if (line.startsWith("biome_id,") || line.contains("is_block_coord")) {
                        continue;
                    }
                }

                // Skip empty lines
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                try {
                    // Parse CSV: biome_id,x,z,surface_y,is_block_coord
                    String[] parts = line.split(",");
                    if (parts.length < 4) {
                        errors++;
                        continue;
                    }

                    String baselineBiome = parts[0].trim();
                    int x = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    int y = Integer.parseInt(parts[3].trim());

                    // Query biome stored in chunk (after mod processing), not from noise
                    Holder<Biome> biomeHolder = level.getBiome(new BlockPos(x, y, z));
                    String actualBiome = getBiomeId(biomeHolder);

                    // Output JSON result for this coordinate
                    String json = String.format(
                        "{\"command\":\"batch_inspect\",\"x\":%d,\"y\":%d,\"z\":%d,\"baseline_biome\":\"%s\",\"actual_biome\":\"%s\"}",
                        x, y, z, escapeJson(baselineBiome), escapeJson(actualBiome)
                    );
                    MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", json);
                    count++;

                } catch (NumberFormatException e) {
                    errors++;
                    MicroBiomeReplacer.LOGGER.warn("Failed to parse line: {}", line);
                }
            }

        } catch (IOException e) {
            String errorJson = String.format(
                "{\"command\":\"batch_inspect\",\"error\":\"io_error\",\"message\":\"%s\"}",
                escapeJson(e.getMessage())
            );
            MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", errorJson);
            context.getSource().sendFailure(Component.literal("Error reading file: " + e.getMessage()));
            return 0;
        }

        long duration = System.currentTimeMillis() - startTime;

        // Output summary
        String summaryJson = String.format(
            "{\"command\":\"batch_inspect_complete\",\"total\":%d,\"errors\":%d,\"duration_ms\":%d}",
            count, errors, duration
        );
        MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", summaryJson);

        // Capture for lambda
        final int finalCount = count;
        final long finalDuration = duration;
        context.getSource().sendSuccess(
            () -> Component.literal("Batch inspect complete: " + finalCount + " coordinates processed in " + finalDuration + "ms"),
            false
        );

        return Command.SINGLE_SUCCESS;
    }

    /**
     * /microbiome forceload_chunks <file>
     * Force-load chunks using vanilla forceload command batched by region.
     * File format: chunkX,chunkZ (one per line)
     *
     * Batches chunks into rectangular regions and uses vanilla /forceload add <from> <to>
     * for better integration with C2ME's parallel chunk loading.
     */
    private static int executeForceloadChunks(CommandContext<CommandSourceStack> context) {
        String filename = StringArgumentType.getString(context, "file");
        ServerLevel level = context.getSource().getLevel();
        Path serverDir = level.getServer().getServerDirectory();
        Path filePath = serverDir.resolve(filename);

        if (!Files.exists(filePath)) {
            String errorJson = String.format(
                "{\"command\":\"forceload_chunks\",\"error\":\"file_not_found\",\"file\":\"%s\"}",
                escapeJson(filename)
            );
            MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", errorJson);
            context.getSource().sendFailure(Component.literal("File not found: " + filename));
            return 0;
        }

        long startTime = System.currentTimeMillis();
        int loaded = 0;
        int errors = 0;

        // Parse all chunks first
        java.util.List<int[]> chunks = new java.util.ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    String[] parts = line.split(",");
                    if (parts.length < 2) {
                        errors++;
                        continue;
                    }
                    int chunkX = Integer.parseInt(parts[0].trim());
                    int chunkZ = Integer.parseInt(parts[1].trim());
                    chunks.add(new int[]{chunkX, chunkZ});
                } catch (NumberFormatException e) {
                    errors++;
                }
            }
        } catch (IOException e) {
            String errorJson = String.format(
                "{\"command\":\"forceload_chunks\",\"error\":\"io_error\",\"message\":\"%s\"}",
                escapeJson(e.getMessage())
            );
            MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", errorJson);
            context.getSource().sendFailure(Component.literal("Error reading file: " + e.getMessage()));
            return 0;
        }

        if (chunks.isEmpty()) {
            String resultJson = "{\"command\":\"forceload_chunks\",\"chunks_loaded\":0,\"errors\":0,\"duration_ms\":0}";
            MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", resultJson);
            return Command.SINGLE_SUCCESS;
        }

        // Sort chunks by coordinate to group nearby chunks together for better C2ME batching
        // Uses Morton/Z-order curve approximation: sort by (x + z), then by x
        chunks.sort(Comparator.<int[]>comparingInt(c -> c[0] + c[1]).thenComparingInt(c -> c[0]));

        // Force load each chunk using setChunkForced (triggers full world gen pipeline)
        for (int[] chunk : chunks) {
            try {
                level.setChunkForced(chunk[0], chunk[1], true);
                loaded++;
            } catch (Exception e) {
                errors++;
                MicroBiomeReplacer.LOGGER.warn("Failed to force load chunk ({}, {}): {}",
                    chunk[0], chunk[1], e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        String resultJson = String.format(
            "{\"command\":\"forceload_chunks\",\"chunks_loaded\":%d,\"errors\":%d,\"duration_ms\":%d}",
            loaded, errors, duration
        );
        MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", resultJson);

        final int finalLoaded = loaded;
        final long finalDuration = duration;
        context.getSource().sendSuccess(
            () -> Component.literal("Force-loaded " + finalLoaded + " chunks in " + finalDuration + "ms"),
            false
        );

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Extract biome ID string from Holder<Biome>
     */
    private static String getBiomeId(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> key.location().toString())
            .orElse("unknown");
    }

    /**
     * Escape special characters for JSON string
     */
    private static String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
