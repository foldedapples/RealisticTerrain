package com.cokedoutsnail.realisticterrain.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permanent invariants of the droplet hydraulic erosion pass. These are intentionally not tuned to
 * exact output values (the amplitude and clamps are engineering choices, not laws): they lock down
 * the physics and determinism that the terrain and biomes depend on.
 */
final class HydraulicErosionTest {

    private static final long SEED = 0x5245414c49535449L;

    /** The same region sampled twice must be bit-identical, even across a cold cache. */
    @Test
    void sameRegionIsDeterministic() {
        HydraulicErosion.clear();
        double a = HydraulicErosion.sample(123, 456, SEED, TerrainSettings.DEFAULT).delta();
        HydraulicErosion.clear();
        double b = HydraulicErosion.sample(123, 456, SEED, TerrainSettings.DEFAULT).delta();
        assertEquals(a, b, "hydraulic erosion must be a pure function of (seed, x, z, settings)");

        HydraulicErosion.Field sa = HydraulicErosion.sample(123, 456, SEED, TerrainSettings.DEFAULT);
        HydraulicErosion.Field sb = HydraulicErosion.sample(123, 456, SEED, TerrainSettings.DEFAULT);
        assertEquals(sa.delta(), sb.delta());
        assertEquals(sa.soil(), sb.soil());
        assertEquals(sa.moisture(), sb.moisture());
    }

    /** Every published field stays inside its documented bounds. */
    @Test
    void fieldsStayInBounds() {
        for (int j = 0; j < 16; j++) {
            for (int i = 0; i < 16; i++) {
                HydraulicErosion.Field f = HydraulicErosion.sample((i - 8) * 32.0, (j - 8) * 32.0, SEED, TerrainSettings.DEFAULT);
                assertTrue(f.delta() >= -14.0f && f.delta() <= 10.0f, "delta out of clamp: " + f.delta());
                assertTrue(f.soil() >= 0.0 && f.soil() <= 1.0, "soil out of range: " + f.soil());
                assertTrue(f.moisture() >= 0.0 && f.moisture() <= 1.0, "moisture out of range: " + f.moisture());
            }
        }
    }

    /**
     * Erosion runs downhill: steep slopes must on average lose material (negative delta, thin soil)
     * while flats gain it. The direction is what drives scree on canyon walls and fertile valleys.
     */
    @Test
    void steepSlopesErodeMoreThanFlats() {
        // Samples a broad area (50×50 grid over 2400×2400 blocks) to capture both canyon walls
        // and valley floors from the same seed and settings.
        int n = 2500;
        double step = 24.0;
        int cols = (int) Math.sqrt(n);
        double flatDelta = 0, flatSoil = 0, steepDelta = 0, steepSoil = 0;
        int flatN = 0, steepN = 0;
        for (int i = 0; i < n; i++) {
            double x = (i % cols - cols / 2) * step, z = (i / cols - cols / 2) * step;
            double slope = TerrainModel.geomorphSlope(SEED, x, z, TerrainSettings.DEFAULT);
            if (slope > 0.4) {
                HydraulicErosion.Field f = HydraulicErosion.sample(x, z, SEED, TerrainSettings.DEFAULT);
                steepDelta += f.delta();
                steepSoil += f.soil();
                steepN++;
            } else if (slope < 0.06) {
                HydraulicErosion.Field f = HydraulicErosion.sample(x, z, SEED, TerrainSettings.DEFAULT);
                flatDelta += f.delta();
                flatSoil += f.soil();
                flatN++;
            }
        }
        // The landscape must actually contain both extremes, or this test is sampling nothing.
        assertTrue(flatN > 50 && steepN > 50, "not enough flat/steep samples: flat=" + flatN + " steep=" + steepN);
        assertTrue(steepDelta / steepN < flatDelta / flatN,
                "steep slopes should erode (or deposit less) than flats: "
                        + steepDelta / steepN + " vs " + flatDelta / flatN);
        assertTrue(steepSoil / steepN < flatSoil / flatN,
                "steep slopes should carry thinner soil than flats: "
                        + steepSoil / steepN + " vs " + flatSoil / flatN);
    }

    /** At zero erosion intensity the pass is skipped entirely and must not even allocate regions. */
    @Test
    void zeroErosionIsNeutral() {
        TerrainSettings off = TerrainSettings.DEFAULT.withValue(3, 0.0);
        HydraulicErosion.clear();
        long regions = HydraulicErosion.size();
        HydraulicErosion.Field f = HydraulicErosion.sample(123, 456, SEED, off);
        assertEquals(0.0, f.delta(), "no height change at zero erosion");
        assertEquals(0.5, f.soil(), "undisturbed soil at zero erosion");
        assertEquals(0.0, f.moisture(), "no water traffic at zero erosion");
        assertEquals(regions, HydraulicErosion.size(), "zero erosion must not touch the region cache");
    }
}