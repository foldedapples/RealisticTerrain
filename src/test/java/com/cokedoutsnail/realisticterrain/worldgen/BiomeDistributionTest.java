package com.cokedoutsnail.realisticterrain.worldgen;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Permanent invariants of the biome-distribution fix (the "all-sand world" bug).
 *
 * <p>The bug had two halves. First, the climate noise was compressed: a four-octave fbm only reaches
 * about 60% of its nominal {@code [-1, 1]} range, so the warm/cold and wet/dry extremes that select
 * desert, savanna, jungle, taiga and snowy plains were barely reachable and the map collapsed onto the
 * few temperate entries in the middle of the biome table. Second, sand was not gated at all in the
 * surface rules - every dry land surface fell through to coarse dirt, while the only sand in the world
 * was the submerged fringe. These tests lock both halves down: the climate must fill its range, real
 * deserts must exist, sand must not dominate the land, and river channels must stay filled.
 *
 * <p>They read only {@link TerrainModel}, so they run in the normal unit-test JVM with no bootstrap.
 */
final class BiomeDistributionTest {
    private static final long SEED = 8675309L;
    private static final double STEP = 96.0;
    private static final double SPAN = 6144.0;

    /** Every column of the audit grid, once. */
    private static TerrainModel.Sample[] grid(TerrainSettings s) {
        int side = (int) (2 * SPAN / STEP) + 1;
        TerrainModel.Sample[] all = new TerrainModel.Sample[side * side];
        int n = 0;
        for (double z = -SPAN; z <= SPAN; z += STEP) {
            for (double x = -SPAN; x <= SPAN; x += STEP) {
                all[n++] = TerrainModel.sample(SEED, x, z, s);
            }
        }
        assertTrue(n == all.length, "grid size mismatch");
        return all;
    }

    /** One classified audit column: the real coordinates and the sample, so the classifier is called
     *  exactly where the terrain was sampled. */
    private record Col(double x, double z, TerrainModel.Sample sm) {}

    private static java.util.List<Col> columns(TerrainSettings s) {
        int side = (int) (2 * SPAN / STEP) + 1;
        java.util.List<Col> all = new java.util.ArrayList<>(side * side);
        for (double z = -SPAN; z <= SPAN; z += STEP) {
            for (double x = -SPAN; x <= SPAN; x += STEP) {
                all.add(new Col(x, z, TerrainModel.sample(SEED, x, z, s)));
            }
        }
        return all;
    }

    private static TerrainBiomeType classify(TerrainSettings s, Col c) {
        return TerrainBiomeClassifier.classify(SEED, c.x(), c.z(), s, c.sm());
    }

    private static double percentile(double[] values, double q) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * q))];
    }

    /**
     * The climate fields must actually use the range their biome thresholds are stated on. Before the
     * {@code CLIMATE_NORM} stretch, temperature sat in roughly {@code [-0.6, 0.6]} and its fifth and
     * ninety-fifth percentiles were -0.46 and +0.39, so "hot enough for jungle" and "cold enough for
     * taiga" almost never happened.
     */
    @Test
    void climateFieldsSpanTheFullRange() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        TerrainModel.Sample[] all = grid(s);
        double[] temp = new double[all.length];
        double[] moist = new double[all.length];
        for (int i = 0; i < all.length; i++) {
            temp[i] = all[i].temperature();
            moist[i] = all[i].moisture();
            assertTrue(temp[i] >= -1.0 && temp[i] <= 1.0, "temperature left [-1,1]: " + temp[i]);
            assertTrue(moist[i] >= -1.0 && moist[i] <= 1.0, "moisture left [-1,1]: " + moist[i]);
        }
        assertTrue(percentile(temp, 0.05) <= -0.55, "temperature has no cold tail: p05=" + percentile(temp, 0.05));
        assertTrue(percentile(temp, 0.95) >= 0.45, "temperature has no warm tail: p95=" + percentile(temp, 0.95));
        assertTrue(percentile(moist, 0.05) <= -0.45, "moisture has no dry tail: p05=" + percentile(moist, 0.05));
        assertTrue(percentile(moist, 0.95) >= 0.50, "moisture has no wet tail: p95=" + percentile(moist, 0.95));
    }

    /**
     * Deserts must be a real, findable part of the world - but sand must not be the world. This now
     * counts the authoritative {@link TerrainBiomeClassifier} result rather than re-deriving a
     * temperature/moisture rectangle, so it measures the biomes the world actually gets.
     */
    @Test
    void sandIsAPlaceNotTheDefault() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        java.util.List<Col> all = columns(s);
        int land = 0, beach = 0, desert = 0;
        for (Col c : all) {
            if (c.sm().height() < c.sm().waterLevel()) continue; // water covers it, not a surface
            land++;
            TerrainBiomeType type = classify(s, c);
            if (type == TerrainBiomeType.BEACH) beach++;
            if (type == TerrainBiomeType.DESERT) desert++;
        }
        assertTrue(land > all.size() / 3, "not enough land sampled: " + land + "/" + all.size());
        double desertFrac = desert / (double) land;
        assertTrue(desertFrac > 0.005, "no deserts reachable, climate is still collapsed: " + desertFrac);
        assertTrue(desertFrac < 0.20, "deserts swallowed the land: " + desertFrac);
        double beachOfLand = beach / (double) land;
        assertTrue(beachOfLand < 0.12, "beach band is not a coast any more: " + beachOfLand + " of land");
    }

    /**
     * Water in a channel whose bed lies below sea level must be filled up to sea level, so a frozen or
     * half-filled river cannot happen. Above sea level the channel keeps a local surface that sits just
     * above its own bed (never a floating sheet).
     */
    @Test
    void riverChannelsAreFilledToSeaLevel() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        int checked = 0;
        for (TerrainModel.Sample sm : grid(s)) {
            // A bed below sea level is always flooded to sea level: the model takes
            // max(seaLevel, localSurface), so no channel can be left dry or half filled.
            if (sm.height() < s.seaLevel()) {
                assertTrue(sm.waterLevel() >= s.seaLevel(),
                        "bed below sea level left dry: h=" + sm.height() + " water=" + sm.waterLevel());
            }
            if (sm.river() > 0.4 && sm.height() >= s.seaLevel()) {
                checked++;
                assertTrue(sm.waterLevel() - sm.height() <= 14,
                        "floating river water: h=" + sm.height() + " water=" + sm.waterLevel());
            }
        }
        assertTrue(checked > 5, "too few river columns sampled: " + checked);
    }

    /**
     * The classification must be a pure function: identical on repeat, identical when the traversal
     * order is reversed, and identical when the work is spread across threads. Parallel generation
     * must not be able to change a biome.
     */
    @Test
    void classificationIsDeterministicAndOrderIndependent() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        long seed = 8675309L;
        java.util.List<double[]> pts = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            pts.add(new double[]{(i * 137) % 4096 - 2048, (i * 941) % 4096 - 2048});
        }
        TerrainBiomeType[] first = new TerrainBiomeType[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            first[i] = TerrainBiomeClassifier.classify(seed, p[0], p[1], s,
                    TerrainModel.sample(seed, p[0], p[1], s));
        }
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            assertEquals(first[i], TerrainBiomeClassifier.classify(seed, p[0], p[1], s,
                    TerrainModel.sample(seed, p[0], p[1], s)), "repeat classification differed");
        }
        for (int i = pts.size() - 1; i >= 0; i--) {
            double[] p = pts.get(i);
            assertEquals(first[i], TerrainBiomeClassifier.classify(seed, p[0], p[1], s,
                    TerrainModel.sample(seed, p[0], p[1], s)), "reversed order changed the biome");
        }
        TerrainBiomeType[] parallel = new TerrainBiomeType[pts.size()];
        java.util.stream.IntStream.range(0, pts.size()).parallel().forEach(i -> {
            double[] p = pts.get(i);
            parallel[i] = TerrainBiomeClassifier.classify(seed, p[0], p[1], s,
                    TerrainModel.sample(seed, p[0], p[1], s));
        });
        assertArrayEquals(first, parallel, "parallel classification differed from sequential");
    }

    /**
     * The core Phase-1 distribution contract, measured over five fixed seeds and a 12k12k block
     * area: deserts exist but stay local, no single land biome dominates, several land biomes occur,
     * beaches are a coastline rather than a band, and sand is a place rather than the default.
     */
    @Test
    void fiveSeedDistributionIsVariedAndBounded() {
        long[] seeds = {8675309L, 42L, 0L, 0x5245414C49535449L, -123456789L};
        TerrainSettings s = TerrainSettings.DEFAULT;
        double step = 192.0, span = 6144.0;
        java.util.EnumMap<TerrainBiomeType, Long> catLand = new java.util.EnumMap<>(TerrainBiomeType.class);
        long land = 0, water = 0, sandLand = 0;
        for (long seed : seeds) {
            long seedLand = 0, seedWater = 0, seedDesert = 0, seedBeach = 0;
            for (double z = -span; z <= span; z += step) {
                for (double x = -span; x <= span; x += step) {
                    TerrainModel.Sample sm = TerrainModel.sample(seed, x, z, s);
                    if (sm.height() < sm.waterLevel()) {
                        seedWater++;
                        continue;
                    }
                    seedLand++;
                    TerrainBiomeType type = TerrainBiomeClassifier.classify(seed, x, z, s, sm);
                    catLand.merge(type, 1L, Long::sum);
                    if (type == TerrainBiomeType.DESERT) seedDesert++;
                    if (type == TerrainBiomeType.BEACH) seedBeach++;
                    if (type == TerrainBiomeType.DESERT || type == TerrainBiomeType.BEACH) sandLand++;
                }
            }
            land += seedLand;
            water += seedWater;
            assertTrue(seedLand > 0, "seed " + seed + " produced no land");
            assertTrue(seedDesert / (double) seedLand < 0.20,
                    "seed " + seed + " deserts covered " + (seedDesert / (double) seedLand) + " of land");
            assertTrue(seedBeach / (double) seedLand < 0.12,
                    "seed " + seed + " beaches covered " + (seedBeach / (double) seedLand) + " of land");
        }
        assertTrue(water > 0, "no water at all across the five seeds");
        assertTrue(land > 0, "no land at all across the five seeds");

        int distinctLand = 0;
        for (java.util.Map.Entry<TerrainBiomeType, Long> e : catLand.entrySet()) {
            if (!e.getKey().isLand()) continue;
            distinctLand++;
            double frac = e.getValue() / (double) land;
            assertTrue(frac <= 0.65,
                    "land biome " + e.getKey() + " exceeded 65% of land: " + frac);
        }
        assertTrue(distinctLand >= 6, "only " + distinctLand + " land biome categories occurred");
        assertTrue(sandLand / (double) land < 0.25,
                "sand covered " + (sandLand / (double) land) + " of land");
    }

    /** River and lake categories may appear only where the hydrology masks actually pass. */
    @Test
    void riverAndLakeOnlyWhereHydrologySaysSo() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        int rivers = 0, lakes = 0;
        for (Col c : columns(s)) {
            TerrainBiomeType type = classify(s, c);
            if (type == TerrainBiomeType.RIVER) {
                rivers++;
                assertTrue(c.sm().river() >= TerrainHydrology.RIVER_THRESHOLD,
                        "river biome without a river mask: " + c.sm().river());
                assertTrue(c.sm().height() < c.sm().waterLevel(), "river biome on dry land");
            }
            if (type == TerrainBiomeType.LAKE) {
                lakes++;
                assertTrue(c.sm().lake() >= TerrainHydrology.LAKE_THRESHOLD,
                        "lake biome without a lake mask: " + c.sm().lake());
                assertTrue(c.sm().height() < c.sm().waterLevel(), "lake biome on dry land");
            }
        }
        assertTrue(rivers > 0, "no river biomes found anywhere");
    }

    /** Hot and wet is jungle, never desert: the desert gate must require genuine dryness. */
    @Test
    void hotWetClimateIsNeverDesert() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        for (Col c : columns(s)) {
            if (c.sm().temperature() > 0.25 && c.sm().moisture() > 0.15) {
                assertNotEquals(TerrainBiomeType.DESERT, classify(s, c),
                        "hot wet column became a desert at " + c.x() + "," + c.z());
            }
        }
    }

    /** High, cold, dry land must land in an alpine or snow category. */
    @Test
    void highColdTerrainIsAlpine() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        long[] seeds = {8675309L, 42L};
        int found = 0;
        for (long seed : seeds) {
            for (double z = -6144; z <= 6144; z += 96) {
                for (double x = -6144; x <= 6144; x += 96) {
                    TerrainModel.Sample sm = TerrainModel.sample(seed, x, z, s);
                    if (TerrainHydrology.underwater(sm)) continue;
                    // Strictly above the subalpine band the classifier must choose a snow/rock category.
                    if (sm.height() > s.snowLine() + 60) {
                        found++;
                        TerrainBiomeType t = TerrainBiomeClassifier.classify(seed, x, z, s, sm);
                        assertTrue(t == TerrainBiomeType.SNOWY_SLOPES || t == TerrainBiomeType.STONY_PEAKS,
                                "high terrain was not alpine: " + t + " at h=" + sm.height());
                    }
                }
            }
        }
        assertTrue(found > 0, "no high terrain was sampled");
    }

    /** Ordinary temperate land at low altitude must be capable of carrying grass. */
    @Test
    void temperateOrdinaryLandIsGrassCapable() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        for (Col c : columns(s)) {
            TerrainModel.Sample sm = c.sm();
            if (TerrainHydrology.underwater(sm)) continue;
            if (sm.temperature() > -0.2 && sm.temperature() < 0.25
                    && sm.moisture() > -0.1 && sm.moisture() < 0.5
                    && sm.height() > s.seaLevel() && sm.height() < s.seaLevel() + 40) {
                TerrainBiomeType t = classify(s, c);
                assertTrue(t.isSoiled() || t == TerrainBiomeType.BEACH,
                        "ordinary temperate land had no grass-capable biome: " + t);
            }
        }
    }
}
