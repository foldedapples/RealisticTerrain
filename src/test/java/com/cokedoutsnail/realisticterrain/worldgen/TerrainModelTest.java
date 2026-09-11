package com.cokedoutsnail.realisticterrain.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TerrainModelTest {
    @Test
    void samplingIsDeterministicAndFinite() {
        TerrainModel.Sample first = TerrainModel.sample(42L, 1234.5, -987.25, TerrainSettings.DEFAULT);
        TerrainModel.Sample second = TerrainModel.sample(42L, 1234.5, -987.25, TerrainSettings.DEFAULT);

        assertEquals(first, second);
        assertTrue(Double.isFinite(first.height()));
        assertTrue(Double.isFinite(first.waterLevel()));
        assertTrue(first.height() >= -120.0 && first.height() <= 1950.0);
    }

    @Test
    void drainageCreatesWaterFilledChannels() {
        boolean foundChannel = false;
        for (int z = -4096; z <= 4096 && !foundChannel; z += 64) {
            for (int x = -4096; x <= 4096; x += 64) {
                TerrainModel.Sample sample = TerrainModel.sample(8675309L, x, z, TerrainSettings.DEFAULT);
                if ((sample.river() > .32 || sample.lake() > .38) && sample.height() < sample.waterLevel()) {
                    foundChannel = true;
                    break;
                }
            }
        }
        assertTrue(foundChannel, "Expected at least one water-filled drainage channel in the sampled area");
    }

    @Test
    void riverWaterStaysAttached() {
        // The water surface is always just a few blocks above the freshly carved riverbed,
        // never an independently computed plain that could sit above the surrounding ground.
        for (int z = -4096; z <= 4096; z += 64) {
            for (int x = -4096; x <= 4096; x += 64) {
                TerrainModel.Sample sample = TerrainModel.sample(8675309L, x, z, TerrainSettings.DEFAULT);
                if (sample.river() > 0.1 && sample.height() >= 96) {
                    assertTrue(
                            sample.waterLevel() - sample.height() <= 14,
                            "Floating river water detected at " + x + "," + z
                                    + " waterLevel=" + sample.waterLevel() + " height=" + sample.height()
                    );
                }
            }
        }
    }

    @Test
    void riversStayOutOfTheHighRanges() {
        // Channels may only carve low and mid terrain; they fade out long before the
        // alpine zone, so rivers never cut gullies through mountain ridgelines.
        for (int z = -4096; z <= 4096; z += 64) {
            for (int x = -4096; x <= 4096; x += 64) {
                TerrainModel.Sample sample = TerrainModel.sample(8675309L, x, z, TerrainSettings.DEFAULT);
                if (sample.river() > 0.25) {
                    assertTrue(sample.height() < 450,
                            "River found cutting through high terrain at " + x + "," + z
                                    + " height=" + sample.height());
                }
            }
        }
    }

    @Test
    void riversPreferValleys() {
        // Drainage must dominate the lower, gentler terrain: over a large sample the average
        // river column should sit well below the average non-river column, and the river
        // coverage fraction should stay in a sane band (corridors exist, but never blanket the map).
        long riverCells = 0, total = 0;
        double riverSum = 0, nonRiverSum = 0, nonRiverCount = 0;
        for (int z = -2048; z <= 2048; z += 32) {
            for (int x = -2048; x <= 2048; x += 32) {
                TerrainModel.Sample sample = TerrainModel.sample(8675309L, x, z, TerrainSettings.DEFAULT);
                assertTrue(Double.isFinite(sample.height()));
                assertTrue(Double.isFinite(sample.waterLevel()));
                total++;
                if (sample.river() > 0.35) {
                    riverCells++;
                    riverSum += sample.height();
                } else if (sample.river() < 0.01) {
                    nonRiverCount++;
                    nonRiverSum += sample.height();
                }
            }
        }
        double fraction = (double) riverCells / total;
        assertTrue(fraction > 0.005, "Unexpectedly few rivers: " + fraction);
        assertTrue(fraction < 0.5, "Unexpectedly many rivers: " + fraction);
        double avgRiver = riverSum / Math.max(1, riverCells);
        double avgOther = nonRiverSum / Math.max(1, nonRiverCount);
        assertTrue(avgRiver < avgOther, "Rivers should sit in valleys: riverAvg=" + avgRiver + " otherAvg=" + avgOther);
    }

    @Test
    void profilesAndVegetationDefaultAreUsable() {
        assertEquals(1.0F, TerrainSettings.DEFAULT.vegetationDensity());
        assertEquals(5, TerrainSettings.PROFILES.size());
        for (TerrainSettings.Profile p : TerrainSettings.PROFILES) {
            assertTrue(p.settings().vegetationDensity() >= 0.0F);
            assertTrue(p.settings().vegetationDensity() <= 3.0F);
            TerrainModel.Sample s = TerrainModel.sample(42L, 0, 0, p.settings());
            assertTrue(Double.isFinite(s.height()));
        }
    }

    @Test
    void seedSaltChangesTerrain() {
        TerrainSettings salted = new TerrainSettings(1,1,1,1,1,1,1,430,1,96,1,1,1,1,99L);
        assertNotEquals(
                TerrainModel.sample(12L, 800, 1200, TerrainSettings.DEFAULT),
                TerrainModel.sample(12L, 800, 1200, salted)
        );
    }

    @Test
    void tectonicLayeringProducesMountainsAndOcean() {
        // Plate tectonics must yield both folded ranges and deep abyssal basins over a large area.
        double peak = -1e9, trough = 1e9;
        for (int z = -8192; z <= 8192; z += 128) {
            for (int x = -8192; x <= 8192; x += 128) {
                double h = TerrainModel.sample(8675309L, x, z, TerrainSettings.DEFAULT).height();
                if (h > peak) peak = h;
                if (h < trough) trough = h;
            }
        }
        assertTrue(peak > 650, "Expected folded mountain ranges, peak=" + peak);
        assertTrue(trough < -40, "Expected deep abyssal ocean, trough=" + trough);
    }

    @Test
    void cacheIsSmoothAndShared() {
        long seed = 424242L;
        // Determinism: repeated samples are identical (cache is a pure memoization).
        assertEquals(TerrainModel.sample(seed, 1234, -567, TerrainSettings.DEFAULT),
                TerrainModel.sample(seed, 1234, -567, TerrainSettings.DEFAULT));
        // Smoothness: adjacent 1-block columns differ by a bounded amount (no jagged lattice steps).
        double maxStep = 0;
        for (int x = 0; x < 64; x++) {
            double a = TerrainModel.sample(seed, x, 50, TerrainSettings.DEFAULT).height();
            double b = TerrainModel.sample(seed, x + 1, 50, TerrainSettings.DEFAULT).height();
            maxStep = Math.max(maxStep, Math.abs(a - b));
        }
        assertTrue(maxStep < 130, "Terrain jumped between adjacent columns: " + maxStep);
        // The coarse cache shares nodes across columns: sampling a fresh 64×64-block patch adds
        // only the handful of 16×16 cells it touches (plus the fall-line probe fringe), not one
        // node per column - so the cache growth must be far smaller than the columns sampled.
        long before = TerrainCache.size();
        for (int x = 2000; x < 2064; x++) {
            for (int z = 3000; z < 3064; z++) {
                TerrainModel.sample(seed, x, z, TerrainSettings.DEFAULT);
            }
        }
        long delta = TerrainCache.size() - before;
        assertTrue(delta < 400, "Cache should share coarse nodes across columns, delta=" + delta);
    }
}
