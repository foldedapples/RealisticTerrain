package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.noise.Noise2D;

/**
 * The single authoritative surface-material resolver.
 *
 * <p>Both write paths in the chunk generator - the block-placing {@code populateNoise} pass and the
 * {@code getColumnSample} query used by spawn placement and feature code - call
 * {@link #resolve(SurfaceContext)}. They cannot disagree, because they no longer contain any surface
 * rules of their own; before Phase 1 the two paths had drifted into different, incompatible
 * implementations.
 *
 * <p>The resolver is pure: it reads the column's {@link TerrainBiomeType} and its
 * {@link TerrainModel.Sample} and returns a registry-free {@link SurfaceMaterial}. Sand is only ever
 * produced for {@link TerrainBiomeType#DESERT} and {@link TerrainBiomeType#BEACH} (and the bed of a
 * body of water), never as a fallback; coarse dirt never replaces grass across a merely dry
 * temperate plain.
 */
public final class TerrainSurfaceResolver {
    private TerrainSurfaceResolver() {
    }

    /**
     * Everything the surface rules may read for one block. {@code surfaceY} is the topmost ground
     * block of the column and {@code currentY} the block being resolved, so {@code surfaceY -
     * currentY} is the depth below the surface.
     */
    public record SurfaceContext(
            TerrainBiomeType biome,
            TerrainModel.Sample terrain,
            int surfaceY,
            int currentY,
            int x,
            int z,
            long seed,
            TerrainSettings settings
    ) {
        /** Depth, in blocks, of {@code currentY} below the column's surface. */
        public int depth() {
            return surfaceY - currentY;
        }
    }

    /** Resolve the material of one block of a column. */
    public static SurfaceMaterial resolve(SurfaceContext c) {
        TerrainBiomeType biome = c.biome();
        TerrainModel.Sample sm = c.terrain();
        TerrainSettings st = c.settings();
        int depth = c.depth();
        if (depth < 0) return SurfaceMaterial.STONE;

        // Deep basement is deepslate everywhere once the soil/biome layer has been passed.
        if (c.currentY() < st.seaLevel() - 160 && depth > 6) return SurfaceMaterial.DEEPSLATE;

        switch (biome) {
            case DEEP_OCEAN -> {
                return oceanFloor(depth, sm, true, c);
            }
            case OCEAN -> {
                return oceanFloor(depth, sm, false, c);
            }
            case RIVER -> {
                return riverbed(depth, c);
            }
            case LAKE -> {
                return lakebed(depth, sm, c);
            }
            case BEACH -> {
                return beach(depth, sm, c);
            }
            case DESERT -> {
                return desert(depth, sm, c);
            }
            default -> {
            }
        }

        // Snow cover: the rule itself lives in TerrainModel so it has one definition.
        boolean cold = biome.isSnowy() || sm.temperature() < -0.2;
        if (depth == 0 && TerrainModel.snowCover(c.x(), c.z(), c.surfaceY(), st, sm, cold) >= 0.5) {
            return SurfaceMaterial.SNOW_BLOCK;
        }
        // Steep, high, ridged ground shows rock/scree regardless of climate.
        if (sm.ridge() > 0.72 && sm.slopeHint() > 0.42) {
            return depth == 0 ? SurfaceMaterial.GRAVEL : SurfaceMaterial.STONE;
        }
        if (biome == TerrainBiomeType.STONY_PEAKS || biome == TerrainBiomeType.SNOWY_SLOPES) {
            return depth == 0 && sm.slopeHint() < 0.35 ? SurfaceMaterial.GRAVEL : SurfaceMaterial.STONE;
        }
        return soil(biome, depth, sm, c);
    }

    /** Desert: sand on top, sandstone beneath, then bare rock. */
    private static SurfaceMaterial desert(int depth, TerrainModel.Sample sm, SurfaceContext c) {
        if (depth == 0) return SurfaceMaterial.SAND;
        if (depth <= 4) return SurfaceMaterial.SANDSTONE;
        return stone(depth, sm, c);
    }

    /** Beach: a narrow sand/gravel band; cold coasts are gravel, not sand. */
    private static SurfaceMaterial beach(int depth, TerrainModel.Sample sm, SurfaceContext c) {
        boolean cold = sm.temperature() < -0.2;
        if (depth == 0) return cold ? SurfaceMaterial.GRAVEL : SurfaceMaterial.SAND;
        if (depth == 1) return cold ? SurfaceMaterial.GRAVEL : SurfaceMaterial.SANDSTONE;
        if (depth <= 3) return columnHash(c.x(), c.z()) < 0.5
                ? SurfaceMaterial.SAND : SurfaceMaterial.GRAVEL;
        return SurfaceMaterial.STONE;
    }

    /** Riverbed: mostly gravel and stone, some clay, sand only in limited bars. */
    private static SurfaceMaterial riverbed(int depth, SurfaceContext c) {
        double h = columnHash(c.x(), c.z());
        if (depth == 0) {
            if (h < 0.45) return SurfaceMaterial.GRAVEL;
            if (h < 0.70) return SurfaceMaterial.STONE;
            if (h < 0.88) return SurfaceMaterial.SAND;
            return SurfaceMaterial.CLAY;
        }
        if (depth <= 2) return h < 0.5 ? SurfaceMaterial.GRAVEL : SurfaceMaterial.STONE;
        return SurfaceMaterial.STONE;
    }

    /** Lakebed: mud/clay/gravel/sand, chosen by climate and depth. */
    private static SurfaceMaterial lakebed(int depth, TerrainModel.Sample sm, SurfaceContext c) {
        double h = columnHash(c.x(), c.z());
        if (depth == 0) {
            if (sm.moisture() > 0.20) return h < 0.5 ? SurfaceMaterial.MUD : SurfaceMaterial.CLAY;
            if (h < 0.40) return SurfaceMaterial.SAND;
            if (h < 0.75) return SurfaceMaterial.CLAY;
            return SurfaceMaterial.GRAVEL;
        }
        if (depth <= 2) return h < 0.5 ? SurfaceMaterial.CLAY : SurfaceMaterial.SAND;
        return SurfaceMaterial.STONE;
    }

    /** Ocean floor: sand/gravel/clay variation by depth and temperature. */
    private static SurfaceMaterial oceanFloor(int depth, TerrainModel.Sample sm, boolean deep,
            SurfaceContext c) {
        if (deep && depth > 8) return SurfaceMaterial.STONE;
        double h = columnHash(c.x(), c.z());
        if (depth == 0) {
            if (sm.temperature() < -0.3) return h < 0.5 ? SurfaceMaterial.GRAVEL : SurfaceMaterial.STONE;
            if (h < 0.50) return SurfaceMaterial.SAND;
            if (h < 0.80) return SurfaceMaterial.GRAVEL;
            return SurfaceMaterial.CLAY;
        }
        if (depth <= 3) return h < 0.6 ? SurfaceMaterial.SAND : SurfaceMaterial.GRAVEL;
        return SurfaceMaterial.STONE;
    }

    /** Ordinary land surface: grass top, dirt subsoil, with biome-controlled patches. */
    private static SurfaceMaterial soil(TerrainBiomeType biome, int depth, TerrainModel.Sample sm,
            SurfaceContext c) {
        double patch = columnHash(c.x(), c.z());
        switch (biome) {
            case SWAMP -> {
                if (depth == 0) return patch < 0.35 ? SurfaceMaterial.MUD : SurfaceMaterial.GRASS_BLOCK;
                if (depth <= 2) return SurfaceMaterial.MUD;
                return depth <= 4 ? SurfaceMaterial.DIRT : SurfaceMaterial.STONE;
            }
            case JUNGLE -> {
                if (depth == 0) return patch < 0.12 ? SurfaceMaterial.MUD : SurfaceMaterial.GRASS_BLOCK;
                return depth <= 3 ? SurfaceMaterial.DIRT : SurfaceMaterial.STONE;
            }
            case TAIGA, GROVE -> {
                if (depth == 0) {
                    if (patch < 0.22) return SurfaceMaterial.PODZOL;
                    if (patch < 0.34) return SurfaceMaterial.COARSE_DIRT;
                    return SurfaceMaterial.GRASS_BLOCK;
                }
                if (depth <= 2) return patch < 0.30 ? SurfaceMaterial.COARSE_DIRT : SurfaceMaterial.DIRT;
                return SurfaceMaterial.STONE;
            }
            default -> {
                if (depth == 0) {
                    // Stripped, steep soil shows gravel; otherwise grass is the ordinary surface.
                    if (sm.soil() < 0.15 && sm.slopeHint() > 0.30) return SurfaceMaterial.GRAVEL;
                    return SurfaceMaterial.GRASS_BLOCK;
                }
                int soilDepth = 3 + (int) (sm.soil() * 2.0);
                if (depth <= soilDepth) return SurfaceMaterial.DIRT;
                return stone(depth, sm, c);
            }
        }
    }

    /** Deep rock: igneous intrusions near active plate margins, otherwise plain stone. */
    private static SurfaceMaterial stone(int depth, TerrainModel.Sample sm, SurfaceContext c) {
        if (sm.fault() > 0.45 && depth < 40) {
            if (c.currentY() < c.settings().seaLevel() - 40 && sm.fault() > 0.7) {
                return SurfaceMaterial.BASALT;
            }
            double mix = Noise2D.value((c.x() + c.currentY() * 0.30) / 23.0,
                    (c.z() - c.currentY() * 0.20) / 23.0, c.seed() + 991);
            return mix > 0.5 ? SurfaceMaterial.GRANITE : SurfaceMaterial.DIORITE;
        }
        return SurfaceMaterial.STONE;
    }

    /** Deterministic per-column value in {@code [0, 1)} for surface patch variation. */
    private static double columnHash(int x, int z) {
        long h = (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 29;
        return (h & 0xFFFF) / 65535.0;
    }
}
