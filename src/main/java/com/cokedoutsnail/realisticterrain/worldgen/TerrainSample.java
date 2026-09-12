package com.cokedoutsnail.realisticterrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * An immutable terrain sample that any backend engine can produce.
 *
 * <p>This is the contract between the terrain engine backend and the chunk
 * generator, biome source, preview, and hydrology systems. All backends
 * ({@link TerrainEngine#GEOLOGICAL GEOLOGICAL}, {@link TerrainEngine#DIFFUSION DIFFUSION},
 * {@link TerrainEngine#HYBRID HYBRID}) return the same record type.
 *
 * <p>Scalar fields are continuous over X/Z so neighbouring columns never snap;
 * blocky steps only appear at the final floor() in RealisticChunkGenerator.
 *
 * @param elevation       Final ground elevation (or seafloor) in blocks.
 * @param waterLevel      Local water surface (sea, lake or river) in blocks.
 * @param riverStrength   0..1 river/water-corridor strength.
 * @param lakeStrength    0..1 lake-basin strength.
 * @param temperature     -1..1 climate warmth (may be altitude-corrected).
 * @param moisture        -1..1 climate humidity.
 * @param continentalness -1..1 land probability (abyssal plain → craton).
 * @param precipitation   0..1 optional precipitation intensity (0 if unavailable).
 * @param backendStatus   Bitmask: bit0=backend computed, bit1=diffusion used, bit2=hybrid used.
 * @param confidence      0..1 confidence estimate from the backend (1 = deterministic geological).
 */
public record TerrainSample(
        double elevation,
        double waterLevel,
        double riverStrength,
        double lakeStrength,
        double temperature,
        double moisture,
        double continentalness,
        double precipitation,
        int backendStatus,
        double confidence
) {
    public static final int STATUS_COMPUTED = 1;
    public static final int STATUS_DIFFUSION = 2;
    public static final int STATUS_HYBRID = 4;

    /** Default ocean-level water surface. */
    public static final TerrainSample OCEAN = new TerrainSample(
            -52.0, 64.0, 0.0, 0.0, 0.0, 0.0, -0.5, 0.0, STATUS_COMPUTED, 1.0
    );

    public boolean isWater() {
        return elevation < waterLevel;
    }

    public boolean isRiver() {
        return riverStrength > 0.25;
    }

    public boolean isLake() {
        return lakeStrength > 0.3;
    }

    // The "unchecked" component formerly here was redundant (Eclipse flags it as unnecessary); the
    // record builder's unannotated generics only raise a null-type-safety problem.
    @SuppressWarnings("null")
    public static final Codec<TerrainSample> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("elevation").forGetter(s -> s.elevation),
            Codec.DOUBLE.fieldOf("water_level").forGetter(s -> s.waterLevel),
            Codec.DOUBLE.fieldOf("river_strength").forGetter(s -> s.riverStrength),
            Codec.DOUBLE.fieldOf("lake_strength").forGetter(s -> s.lakeStrength),
            Codec.DOUBLE.fieldOf("temperature").forGetter(s -> s.temperature),
            Codec.DOUBLE.fieldOf("moisture").forGetter(s -> s.moisture),
            Codec.DOUBLE.fieldOf("continentalness").forGetter(s -> s.continentalness),
            Codec.DOUBLE.fieldOf("precipitation").forGetter(s -> s.precipitation),
            Codec.INT.fieldOf("backend_status").forGetter(s -> s.backendStatus),
            Codec.DOUBLE.fieldOf("confidence").forGetter(s -> s.confidence)
    ).apply(i, TerrainSample::new));
}