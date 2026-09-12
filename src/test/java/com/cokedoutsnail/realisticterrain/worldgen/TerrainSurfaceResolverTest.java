package com.cokedoutsnail.realisticterrain.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Surface-material invariants for the Phase-1 fix.
 *
 * <p>These test the one shared resolver that both {@code populateNoise} and {@code getColumnSample}
 * call, so they lock down what the world is actually built from rather than what a screenshot looked
 * like. The material enum is registry-free, so no Minecraft bootstrap is needed.
 */
final class TerrainSurfaceResolverTest {
    private static final TerrainSettings S = TerrainSettings.DEFAULT;

    /** A synthetic column sample: height, water level, temperature, moisture; calm everything else. */
    private static TerrainModel.Sample sample(double h, double w, double t, double m) {
        return new TerrainModel.Sample(h, w, 0, 0, 0.2, m, t, 0.1, 0.5, 0, 0, 0, 0.6, 0.3);
    }

    private static SurfaceMaterial resolve(TerrainBiomeType biome, TerrainModel.Sample sm,
            int surfaceY, int y) {
        return TerrainSurfaceResolver.resolve(new TerrainSurfaceResolver.SurfaceContext(
                biome, sm, surfaceY, y, 0, 0, 8675309L, S));
    }

    private static SurfaceMaterial resolveAt(TerrainBiomeType biome, TerrainModel.Sample sm,
            int surfaceY, int y, int x, int z) {
        return TerrainSurfaceResolver.resolve(new TerrainSurfaceResolver.SurfaceContext(
                biome, sm, surfaceY, y, x, z, 8675309L, S));
    }

    @Test
    void desertSurfaceIsSandOverSandstone() {
        TerrainModel.Sample sm = sample(110, 96, 0.5, -0.5);
        assertEquals(SurfaceMaterial.SAND, resolve(TerrainBiomeType.DESERT, sm, 110, 110));
        assertEquals(SurfaceMaterial.SANDSTONE, resolve(TerrainBiomeType.DESERT, sm, 110, 109));
        assertEquals(SurfaceMaterial.SANDSTONE, resolve(TerrainBiomeType.DESERT, sm, 110, 106));
        assertEquals(SurfaceMaterial.STONE, resolve(TerrainBiomeType.DESERT, sm, 110, 90));
    }

    @Test
    void plainsAndForestsUseGrassAndDirt() {
        TerrainModel.Sample sm = sample(110, 96, 0.1, 0.1);
        TerrainBiomeType[] grassy = {TerrainBiomeType.PLAINS, TerrainBiomeType.FOREST,
                TerrainBiomeType.BIRCH_FOREST, TerrainBiomeType.SAVANNA, TerrainBiomeType.MEADOW,
                TerrainBiomeType.DARK_FOREST};
        for (TerrainBiomeType biome : grassy) {
            assertEquals(SurfaceMaterial.GRASS_BLOCK, resolve(biome, sm, 110, 110), biome.toString());
            assertEquals(SurfaceMaterial.DIRT, resolve(biome, sm, 110, 109), biome.toString());
        }
    }

    @Test
    void mountainsAreNeverSand() {
        // High, steep and cold, but NOT classified as desert.
        TerrainModel.Sample mountain = new TerrainModel.Sample(
                650, 96, 0, 0, 0.8, -0.4, -0.3, 0.6, 0.5, 0, 0, 0, 0.2, 0.4);
        TerrainBiomeType[] high = {TerrainBiomeType.STONY_PEAKS, TerrainBiomeType.SNOWY_SLOPES,
                TerrainBiomeType.MEADOW, TerrainBiomeType.GROVE, TerrainBiomeType.TAIGA};
        for (TerrainBiomeType biome : high) {
            for (int y = 650; y > 640; y--) {
                SurfaceMaterial material = resolve(biome, mountain, 650, y);
                assertFalse(material.isSand(), biome + " produced sand at y=" + y);
            }
        }
    }

    @Test
    void dryPlainsNeverBecomeSand() {
        // Dry enough to look desert-like, but the classification says ordinary plains.
        TerrainModel.Sample dry = sample(110, 96, 0.05, -0.35);
        assertEquals(SurfaceMaterial.GRASS_BLOCK, resolve(TerrainBiomeType.PLAINS, dry, 110, 110));
    }

    @Test
    void dryLandIsNeverSubmerged() {
        // A column five centimetres above the water surface is dry. The removed threshold formula
        // (whose right-hand side was about -2.0) declared columns like this submerged.
        assertFalse(TerrainHydrology.underwater(sample(96.05, 96.0, 0.0, 0.0)));
        assertFalse(TerrainHydrology.underwater(sample(200.0, 96.0, 0.0, 0.0)));
        assertTrue(TerrainHydrology.underwater(sample(40.0, 96.0, 0.0, 0.0)));
    }

    @Test
    void beachesAreSandWarmAndGravelCold() {
        assertEquals(SurfaceMaterial.SAND,
                resolve(TerrainBiomeType.BEACH, sample(98, 96, 0.2, 0.0), 98, 98));
        assertEquals(SurfaceMaterial.GRAVEL,
                resolve(TerrainBiomeType.BEACH, sample(98, 96, -0.4, 0.0), 98, 98));
    }

    @Test
    void riverbedsAreMostlyGravelAndStoneWithLimitedSand() {
        TerrainModel.Sample sm = sample(80, 82, 0.0, 0.0);
        int sand = 0;
        for (int x = 0; x < 200; x++) {
            if (resolveAt(TerrainBiomeType.RIVER, sm, 80, 80, x, 0) == SurfaceMaterial.SAND) sand++;
        }
        assertTrue(sand < 200 * 0.35, "riverbed was mostly sand: " + sand + "/200");
    }

    @Test
    void resolverIsDeterministic() {
        TerrainModel.Sample sm = sample(120, 96, 0.2, 0.1);
        TerrainBiomeType biome = TerrainBiomeClassifier.classify(8675309L, 123, -456, S, sm);
        for (int y = 120; y > 60; y--) {
            assertEquals(resolveAt(biome, sm, 120, y, 123, -456),
                    resolveAt(biome, sm, 120, y, 123, -456));
        }
    }

    /** Sand must stay a place, not the surface of the world. */
    @Test
    void sandIsLessThanAQuarterOfLand() {
        long[] seeds = {8675309L, 42L, 0L, 0x5245414C49535449L, -123456789L};
        long land = 0, sand = 0;
        for (long seed : seeds) {
            for (double z = -6144; z <= 6144; z += 192) {
                for (double x = -6144; x <= 6144; x += 192) {
                    TerrainModel.Sample sm = TerrainModel.sample(seed, x, z, S);
                    if (sm.height() < sm.waterLevel()) continue;
                    land++;
                    TerrainBiomeType biome = TerrainBiomeClassifier.classify(seed, x, z, S, sm);
                    int surfaceY = (int) Math.floor(sm.height());
                    SurfaceMaterial top = resolveAt(biome, sm, surfaceY, surfaceY, (int) x, (int) z);
                    if (top.isSand()) sand++;
                }
            }
        }
        assertTrue(land > 0, "no land sampled");
        assertTrue(sand / (double) land < 0.25, "sand covered " + (sand / (double) land) + " of land");
    }
}
