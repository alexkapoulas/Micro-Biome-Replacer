package com.example.alexthundercook.microbiomereplacer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.alexthundercook.microbiomereplacer.BiomeReplacementRegistry;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Mixin that intercepts BiomeManager.getBiome() to apply micro biome replacements
 * at query time. This ensures correct biome results regardless of chunk generation order.
 *
 * BiomeManager.getBiome() applies a -2 block offset and samples 8 nearby positions,
 * which can cause it to return biomes from adjacent chunks. This Mixin intercepts
 * the final result and checks if it should be replaced based on a registry of
 * positions that were part of micro biome replacements.
 */
@Mixin(BiomeManager.class)
public abstract class BiomeQueryMixin {

    /**
     * Inject at the return of getBiome() to check if the result should be replaced.
     */
    @Inject(method = "getBiome", at = @At("RETURN"), cancellable = true)
    private void microbiomereplacer$onGetBiome(
            net.minecraft.core.BlockPos pos,
            CallbackInfoReturnable<Holder<Biome>> cir) {

        Holder<Biome> original = cir.getReturnValue();
        Holder<Biome> replacement = BiomeReplacementRegistry.getReplacement(pos, original);

        if (replacement != null && replacement != original) {
            cir.setReturnValue(replacement);
        }
    }
}
