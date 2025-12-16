package com.example.alexthundercook.microbiomereplacer.profiling;

import com.example.alexthundercook.microbiomereplacer.MicroBiomeReplacer;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Performance profiling commands for automated testing via RCON.
 * All commands output JSON with [TEST_RESULT] prefix for parsing by test harness.
 *
 * Commands:
 * - /microbiome profile stats    - Output current performance statistics
 * - /microbiome profile reset    - Reset all statistics
 * - /microbiome profile export   - Export statistics to a JSON file
 */
@EventBusSubscriber(modid = MicroBiomeReplacer.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ProfileCommands {

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("microbiome")
            .then(Commands.literal("profile")
                .requires(source -> source.hasPermission(2)) // Require op level 2
                .then(Commands.literal("stats")
                    .executes(ProfileCommands::executeStats))
                .then(Commands.literal("reset")
                    .executes(ProfileCommands::executeReset))
                .then(Commands.literal("export")
                    .then(Commands.argument("file", StringArgumentType.string())
                        .executes(ProfileCommands::executeExport)))));

        MicroBiomeReplacer.LOGGER.info("Registered profile commands: /microbiome profile stats|reset|export");
    }

    /**
     * /microbiome profile stats
     * Output current performance statistics as JSON.
     */
    private static int executeStats(CommandContext<CommandSourceStack> context) {
        PerformanceStats.StatsSnapshot snapshot = PerformanceStats.getInstance().getSnapshot();

        // Build JSON output - merge command field with stats JSON
        String statsJson = snapshot.toJson();
        String json = String.format(
            "{\"command\":\"profile_stats\",%s",
            statsJson.substring(1)  // Remove leading { and merge
        );
        MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", json);

        // Send human-readable feedback to command sender
        context.getSource().sendSuccess(
            () -> Component.literal(String.format(
                "Chunks: %d | Mean: %.2f ms | P50: %.2f ms | P90: %.2f ms | P99: %.2f ms",
                snapshot.count(),
                snapshot.meanMicros() / 1000.0,
                snapshot.p50Nanos() / 1_000_000.0,
                snapshot.p90Nanos() / 1_000_000.0,
                snapshot.p99Nanos() / 1_000_000.0
            )),
            false
        );

        return Command.SINGLE_SUCCESS;
    }

    /**
     * /microbiome profile reset
     * Reset all performance statistics.
     */
    private static int executeReset(CommandContext<CommandSourceStack> context) {
        PerformanceStats.getInstance().reset();

        String json = "{\"command\":\"profile_reset\",\"status\":\"success\"}";
        MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", json);

        context.getSource().sendSuccess(
            () -> Component.literal("Performance statistics reset"),
            false
        );

        return Command.SINGLE_SUCCESS;
    }

    /**
     * /microbiome profile export <file>
     * Export performance statistics to a JSON file in the server directory.
     */
    private static int executeExport(CommandContext<CommandSourceStack> context) {
        String filename = StringArgumentType.getString(context, "file");
        ServerLevel level = context.getSource().getLevel();
        Path serverDir = level.getServer().getServerDirectory();
        Path filePath = serverDir.resolve(filename);

        try {
            PerformanceStats.StatsSnapshot snapshot = PerformanceStats.getInstance().getSnapshot();
            String content = snapshot.toJson();
            Files.writeString(filePath, content);

            String json = String.format(
                "{\"command\":\"profile_export\",\"status\":\"success\",\"file\":\"%s\"}",
                escapeJson(filename)
            );
            MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", json);

            context.getSource().sendSuccess(
                () -> Component.literal("Exported stats to " + filename),
                false
            );
            return Command.SINGLE_SUCCESS;

        } catch (IOException e) {
            String json = String.format(
                "{\"command\":\"profile_export\",\"status\":\"error\",\"message\":\"%s\"}",
                escapeJson(e.getMessage())
            );
            MicroBiomeReplacer.LOGGER.info("[TEST_RESULT] {}", json);

            context.getSource().sendFailure(
                Component.literal("Export failed: " + e.getMessage())
            );
            return 0;
        }
    }

    /**
     * Escape special characters for JSON string.
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
