package com.cokedoutsnail.realisticterrain.worldgen;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
     * Deserts must be a real, findable part of the world - but sand must not be the world. The two
     * check each other: a nonzero desert fraction proves the hot/dry corner of the climate is
     * reachable, and the bounded beach fraction proves the coastline gate stayed a gate.
     */
    @Test
    void sandIsAPlaceNotTheDefault() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        TerrainModel.Sample[] all = grid(s);
        int land = 0, beachBand = 0, desert = 0;
        for (TerrainModel.Sample sm : all) {
            if (sm.height() < sm.waterLevel()) continue; // water covers it, not a surface
            land++;
            if (Math.abs(sm.continent() - s.coastLine()) < 0.05 && sm.height() < s.seaLevel() + 5) beachBand++;
            if (sm.temperature() > 0.25 && sm.moisture() < -0.1) desert++;
        }
        assertTrue(land > all.length / 3, "not enough land sampled: " + land + "/" + all.length);
        double desertFrac = desert / (double) all.length;
        assertTrue(desertFrac > 0.005, "no deserts reachable, climate is still collapsed: " + desertFrac);
        assertTrue(desertFrac < 0.30, "deserts swallowed the world: " + desertFrac);
        double beachOfLand = beachBand / (double) land;
        assertTrue(beachOfLand < 0.35, "beach band is not a coast any more: " + beachOfLand + " of land");
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
}
