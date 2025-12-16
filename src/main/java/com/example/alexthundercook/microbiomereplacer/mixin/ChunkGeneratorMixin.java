package com.example.alexthundercook.microbiomereplacer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.alexthundercook.microbiomereplacer.MicroBiomeProcessor;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Mixin that injects into NoiseBasedChunkGenerator.doCreateBiomes() to process
 * micro biomes after vanilla biome generation completes but before the NOISE stage.
 *
 * NOTE: We target NoiseBasedChunkGenerator because it overrides createBiomes()
 * and the actual biome population happens inside doCreateBiomes() which runs
 * asynchronously inside the CompletableFuture.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    /**
     * Inject at the tail of doCreateBiomes() to process micro biomes after
     * vanilla biome population is complete.
     *
     * At this point:
     * - 'this' is the NoiseBasedChunkGenerator (extends ChunkGenerator)
     * - 'random' provides RandomState for height queries and sampler()
     * - 'chunk' is the ChunkAccess with fully populated biome data
     * - We're running inside the async task, biomes ARE populated
     */
    @Inject(method = "doCreateBiomes", at = @At("TAIL"))
    private void microbiomereplacer$onBiomesCreated(
            Blender blender,
            RandomState random,
            StructureManager structureManager,
            ChunkAccess chunk,
            CallbackInfo ci) {

        MicroBiomeProcessor.process(
            (ChunkGenerator) (Object) this,
            chunk,
            random
        );
    }
}
