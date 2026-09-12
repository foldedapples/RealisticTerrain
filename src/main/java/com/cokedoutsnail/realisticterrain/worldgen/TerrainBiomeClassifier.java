package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.noise.Noise2D;

/**
 * The single authoritative biome classifier.
 *
 * <p>Given the terrain seed, the real world column and the column's {@link TerrainModel.Sample},
 * this returns exactly one {@link TerrainBiomeType}. It is a pure function: no registry lookups, no
 * mutable state, no hidden ordering. Both the biome source and the chunk generator call it, which is
 * what guarantees that the biome standing on a column and the blocks under it can never disagree -
 * the old code re-derived "desert" from raw {@code temperature > 0.25 && moisture < -0.1} in a
 * second place, and the two definitions drifted.
 *
 * <p>Classification is physical-state first, climate second:
 *
 * <ol>
 *   <li><b>Water</b> is decided by the drainage masks and the continental coastline, not by height
 *       alone (see {@link TerrainHydrology}).</li>
 *   <li><b>Beach</b> requires genuine proximity to the coastline contour <em>and</em> elevation near
 *       sea level, so a dry mountain flank or an inland plateau can never become a beach.</li>
 *   <li><b>Altitude bands</b> (snow line, tree line) then place the alpine categories.</li>
 *   <li><b>Climate</b> places the lowland categories, with jittered ecotones so biome borders are
 *       broad and organic rather than ruler-straight confetti.</li>
 * </ol>
 *
 * <p>Biome scale is <em>not</em> applied here by moving the sample: {@link TerrainModel} already
 * stretches only its low-frequency climate fields by {@code biomeScale}, so terrain and biomes are
 * always sampled at the same real {@code (x, z)}. The optional {@code transitionScale} only widens
 * the ecotone bands.
 */
public final class TerrainBiomeClassifier {
    private TerrainBiomeClassifier() {
    }

    // --- climate thresholds, stated on TerrainModel's normalised [-1, 1] fields ---
    private static final double COLD_T = -0.20;
    private static final double HOT_T = 0.25;
    private static final double WARM_T = 0.05;
    private static final double VERY_WET_M = 0.35;
    private static final double WET_M = 0.15;
    private static final double DRY_M = -0.10;
    private static final double DENSE_M = 0.28;
    // Desert is deliberately stricter than "hot and dry" so it stays a localized climate region
    // rather than swallowing the warm-seasonal-dry savanna belt: genuinely hot AND genuinely arid.
    private static final double DESERT_HOT_T = 0.30;
    private static final double DESERT_DRY_M = -0.24;

    // --- beach gate: coastline proximity AND elevation, both required ---
    private static final double BEACH_COAST_BAND = 0.045;
    private static final double BEACH_RISE_BLOCKS = 3.0;
    private static final double BEACH_MAX_SLOPE = 0.35;

    /** Classify with the default transition width (the biome source's own scale). */
    public static TerrainBiomeType classify(long seed, double x, double z, TerrainSettings settings,
            TerrainModel.Sample sample) {
        return classify(seed, x, z, settings, sample, 1.0f);
    }


    /**
     * Classify a column. {@code transitionScale} is the biome source's configured scale and only
     * stretches the ecotone bands; it never moves the sample.
     */
    public static TerrainBiomeType classify(long seed, double x, double z, TerrainSettings settings,
            TerrainModel.Sample sample, float transitionScale) {
        double h = sample.height();
        double t = sample.temperature();
        double m = sample.moisture();
        double slope = sample.slopeHint();
        double sea = settings.seaLevel();

        // ---- 1. Water: physical state decides before climate gets a say ----
        if (TerrainHydrology.underwater(sample)) {
            if (TerrainHydrology.riverbed(sample)) return TerrainBiomeType.RIVER;
            if (TerrainHydrology.lakebed(sample)) return TerrainBiomeType.LAKE;
            double depth = TerrainHydrology.waterDepth(sample);
            if (TerrainHydrology.ocean(sample, settings)) {
                if (depth > TerrainHydrology.DEEP_OCEAN_DEPTH
                        || (sample.divergent() > 0.60 && depth > 10.0)) {
                    return TerrainBiomeType.DEEP_OCEAN;
                }
                return TerrainBiomeType.OCEAN;
            }
            // Submerged, but neither a channel nor on the ocean side of the coastline. Without a
            // lake mask this is just coastal/inland open water: deep reads as open water, shallow
            // as the water's edge. (A genuine lake passed the LAKE_THRESHOLD branch above.)
            return depth > TerrainHydrology.SHELF_DEPTH ? TerrainBiomeType.OCEAN : TerrainBiomeType.BEACH;
        }

        // ---- 2. Coastline: narrow, and anchored to BOTH continentalness and elevation ----
        double coastDistance = sample.continent() - settings.coastLine();
        double jitter = hash01(seed, x, z);
        double band = BEACH_COAST_BAND * (0.7 + 0.6 * (transitionScale - 1.0f) + 0.6 * jitter);
        boolean nearCoast = Math.abs(coastDistance) < band;
        boolean nearSeaLevel = h <= sea + BEACH_RISE_BLOCKS + 4.0 * jitter;
        if (nearCoast && nearSeaLevel && slope < BEACH_MAX_SLOPE
                && !TerrainHydrology.riverbed(sample) && !TerrainHydrology.lakebed(sample)) {
            return TerrainBiomeType.BEACH;
        }

        // ---- 3. Altitude bands: snow, alpine scrub and tree line ----
        double aboveSnowLine = h - settings.snowLine();
        if (aboveSnowLine > 60.0) {
            if (sample.ridge() > 0.55 && slope > 0.45) return TerrainBiomeType.STONY_PEAKS;
            return TerrainBiomeType.SNOWY_SLOPES;
        }
        if (aboveSnowLine > -40.0) {
            return t < -0.05 ? TerrainBiomeType.GROVE : TerrainBiomeType.MEADOW;
        }

        // ---- 4. Low, flat, poorly drained ground is wetland at any latitude ----
        if (m > 0.30 && h < sea + 6.0 && slope < 0.20 && t > -0.20) {
            return TerrainBiomeType.SWAMP;
        }

        // ---- 5. Rift corridors hold moisture and lush vegetation despite the altitude ----
        if (sample.divergent() > 0.55 && h < sea + 70.0) {
            if (t < COLD_T) return TerrainBiomeType.TAIGA;
            return m > WET_M ? TerrainBiomeType.FOREST : TerrainBiomeType.PLAINS;
        }

        // ---- 6. Lowland climate, with smooth ecotones ----
        boolean cold = t < COLD_T;
        boolean hot = t > HOT_T;
        boolean veryWet = m > VERY_WET_M;
        boolean wet = m > WET_M;
        boolean dry = m < DRY_M;

        if (cold) {
            return wet ? TerrainBiomeType.TAIGA : TerrainBiomeType.SNOWY_PLAINS;
        }
        if (hot) {
            if (veryWet) return TerrainBiomeType.JUNGLE;
            if (t > DESERT_HOT_T && m < DESERT_DRY_M) return TerrainBiomeType.DESERT;
            return TerrainBiomeType.SAVANNA;
        }
        if (veryWet) {
            return ecotone(seed, x, z, DENSE_M, DENSE_M + 0.08, m, transitionScale)
                    ? TerrainBiomeType.DARK_FOREST : TerrainBiomeType.FOREST;
        }
        if (wet) {
            return ecotone(seed, x, z, WET_M, WET_M + 0.07, m, transitionScale)
                    ? TerrainBiomeType.FOREST : TerrainBiomeType.BIRCH_FOREST;
        }
        if (dry) {
            return t > WARM_T ? TerrainBiomeType.SAVANNA : TerrainBiomeType.PLAINS;
        }
        return t > WARM_T ? TerrainBiomeType.SAVANNA : TerrainBiomeType.PLAINS;
    }

    /**
     * Picks the "richer" side of a climate threshold across a jittered transition band so the 4-block
     * biome lattice blends instead of lining up into a hard, stepped border.
     */
    private static boolean ecotone(long seed, double x, double z, double lo, double hi, double v,
            float transitionScale) {
        if (v < lo) return false;
        if (v > hi) return true;
        double band = (hi - lo) * (0.6 + 0.8 * transitionScale);
        double t = (v - lo) / band;
        double jitter = 0.5 + 0.5 * Noise2D.value(x / 6.0, z / 6.0, seed + 907);
        return jitter < t;
    }

    /** Deterministic per-column value in {@code [0, 1)} used for beach width and ecotone jitter. */
    private static double hash01(long seed, double x, double z) {
        long h = ((long) Math.floor(x)) * 0x9E3779B97F4A7C15L
                ^ ((long) Math.floor(z)) * 0xC2B2AE3D27D4EB4FL
                ^ seed * 0x2545F4914F6CDD1DL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }
}
