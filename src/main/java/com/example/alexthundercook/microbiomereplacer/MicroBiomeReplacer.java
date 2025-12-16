package com.example.alexthundercook.microbiomereplacer;

import org.slf4j.Logger;

import com.example.alexthundercook.microbiomereplacer.config.ModConfig;
import com.example.alexthundercook.microbiomereplacer.util.ObjectPools;
import com.mojang.logging.LogUtils;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

@Mod(MicroBiomeReplacer.MODID)
public class MicroBiomeReplacer {
    public static final String MODID = "microbiomereplacer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MicroBiomeReplacer(IEventBus modEventBus, ModContainer modContainer) {
        // Register configuration
        modContainer.registerConfig(Type.COMMON, ModConfig.SPEC);

        // Register event handler for world unload to clear shared height cache
        NeoForge.EVENT_BUS.addListener(this::onWorldUnload);

        LOGGER.info("MicroBiomeReplacer initialized");
    }

    /**
     * Clear the shared height cache when a world unloads.
     * Prevents stale data and memory leaks between world loads.
     */
    private void onWorldUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel) {
            ObjectPools.clearSharedCache();
            LOGGER.debug("Cleared shared height cache on world unload");
        }
    }
}
