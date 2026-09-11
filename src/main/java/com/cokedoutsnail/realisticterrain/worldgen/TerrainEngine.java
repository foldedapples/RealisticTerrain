package com.cokedoutsnail.realisticterrain.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringIdentifiable;

import java.util.Locale;

/**
 * Selectable terrain generation engines for RealisticTerrain.
 *
 * <p>Each world persists its engine choice through this enum's codec.
 * Old worlds missing the field default to {@link #GEOLOGICAL}.
 *
 * <p>Never allow a world to silently switch from DIFFUSION/HYBRID to GEOLOGICAL
 * after some chunks have generated. The codec throws on unrecognized values.
 */
public enum TerrainEngine implements StringIdentifiable {
    /**
     * Existing CPU-friendly plate tectonics, mountains, erosion, hydrology.
     * No AI model required.
     */
    GEOLOGICAL,
    /**
     * Real ONNX Terrain Diffusion elevation and climate generation.
     * Requires downloaded model files + ONNX Runtime.
     */
    DIFFUSION,
    /**
     * Terrain Diffusion supplies coherent macro elevation and climate.
     * RealisticTerrain adds deterministic drainage, rivers, lakes,
     * erosion-aware surfaces, snow, and biome placement.
     */
    HYBRID;

    public static final Codec<TerrainEngine> CODEC = StringIdentifiable.createCodec(TerrainEngine::values);

    @Override
    public String asString() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean requiresAiModels() {
        return this == DIFFUSION || this == HYBRID;
    }
}