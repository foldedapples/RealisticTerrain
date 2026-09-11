package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.worldgen.hydro.Drainage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariants of the regional drainage network. These are the properties the rest of the engine
 * (channels, water, lakes, biomes) is allowed to assume, and the ones a wrong solver would break
 * silently: nondeterminism, order dependence, units leaking into the output, or water in places water
 * cannot be.
 */
final class DrainageTest {
    private static final long SEED = 0x5245414C49535449L;

    /** A spread of positions crossing several regions and the origin, including negative coordinates. */
    private static List<double[]> probePositions() {
        List<double[]> probes = new ArrayList<>();
        for (int z = -1200; z <= 1200; z += 173) {
            for (int x = -1200; x <= 1200; x += 191) {
                probes.add(new double[]{x + 0.37, z - 0.62});
            }
        }
        return probes;
    }

    private static List<Drainage.Cell> sampleAll(List<double[]> probes, TerrainSettings s) {
        List<Drainage.Cell> out = new ArrayList<>(probes.size());
        for (double[] p : probes) out.add(Drainage.sample(p[0], p[1], SEED, s));
        return out;
    }

    /**
     * The same position must give the same answer whatever else has been sampled first. A regional
     * solver that leaked state between regions - or that depended on which region was asked for first -
     * would show up here, and nowhere else.
     */
    @Test
    void samplingIsDeterministicAndOrderIndependent() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        List<double[]> probes = probePositions();

        Drainage.clear();
        List<Drainage.Cell> cold = sampleAll(probes, s);

        Drainage.clear();
        List<double[]> reversed = new ArrayList<>(probes);
        java.util.Collections.reverse(reversed);
        List<Drainage.Cell> warmReversed = sampleAll(reversed, s);
        java.util.Collections.reverse(warmReversed);

        List<Drainage.Cell> warm = sampleAll(probes, s);

        for (int i = 0; i < probes.size(); i++) {
            double[] p = probes.get(i);
            assertEquals(cold.get(i), warmReversed.get(i),
                    "reversed order disagreed at " + p[0] + "," + p[1]);
            assertEquals(cold.get(i), warm.get(i),
                    "warm cache disagreed at " + p[0] + "," + p[1]);
        }
    }

    /** Chunk generation is asynchronous, so a region is solved from several threads at once. */
    @Test
    void concurrentSamplingMatchesSingleThreaded() throws Exception {
        TerrainSettings s = TerrainSettings.DEFAULT;
        List<double[]> probes = probePositions();
        Drainage.clear();
        List<Drainage.Cell> expected = sampleAll(probes, s);

        Drainage.clear();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<List<Drainage.Cell>>> jobs = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                jobs.add(() -> sampleAll(probes, s));
            }
            for (Future<List<Drainage.Cell>> f : pool.invokeAll(jobs)) {
                assertEquals(expected, f.get(), "a worker thread produced different drainage");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Zero density on all three water sliders must produce no drainage work and no water at all. */
    @Test
    void disabledHydrologyProducesNoChannels() {
        TerrainSettings off = TerrainSettings.DEFAULT
                .with(TerrainSetting.RIVER_DENSITY, 0.0)
                .with(TerrainSetting.LAKE_FREQUENCY, 0.0)
                .with(TerrainSetting.WETLAND_FREQUENCY, 0.0);
        Drainage.clear();
        long before = Drainage.size();
        for (double[] p : probePositions()) {
            assertEquals(Drainage.Cell.DRY, Drainage.sample(p[0], p[1], SEED, off),
                    "drainage sample with hydrology disabled");
        }
        assertEquals(before, Drainage.size(), "disabled hydrology still solved regions");
    }

    /** Every published field stays in its documented range, everywhere, for every profile. */
    @Test
    void fieldsStayInBoundsAndFinite() {
        for (TerrainSettings.Profile profile : TerrainSettings.PROFILES) {
            TerrainSettings s = profile.settings();
            for (double[] p : probePositions()) {
                Drainage.Cell c = Drainage.sample(p[0], p[1], SEED, s);
                for (double v : new double[]{c.discharge(), c.order(), c.river(), c.lake(), c.wetland()}) {
                    assertTrue(Double.isFinite(v), profile.nameKey() + ": non-finite field at " + p[0] + "," + p[1]);
                    assertTrue(v >= 0.0 && v <= 1.0, profile.nameKey() + ": field out of range: " + v);
                }
                assertTrue(Double.isFinite(c.waterSurface()), "non-finite water surface");
            }
        }
    }

    /** Channels must not climb into the alpine zone - a river cannot flow over a ridge. */
    @Test
    void channelsStayOutOfTheHighRanges() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        int checked = 0;
        for (double[] p : probePositions()) {
            Drainage.Cell c = Drainage.sample(p[0], p[1], SEED, s);
            if (c.river() < 0.4) continue;
            checked++;
            double height = TerrainModel.sample(SEED, p[0], p[1], s).height();
            assertTrue(height < s.snowLine() + 200.0,
                    "channel at " + p[0] + "," + p[1] + " sits at y=" + height);
        }
        assertTrue(checked > 0, "no channels found to test");
    }

    /** A lake's spill surface can never sit below its own bed. */
    @Test
    void lakesAreConsistentWithTheirBasins() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        int lakes = 0;
        // A deliberately wide sweep: lakes need a deep closed basin with a real catchment, so they are
        // rare and a small probe area would find none.
        for (int z = -4000; z <= 4000; z += 137) {
            for (int x = -4000; x <= 4000; x += 149) {
                Drainage.Cell c = Drainage.sample(x + 0.5, z - 0.5, SEED, s);
                if (c.lake() <= 0.0) continue;
                lakes++;
                double ground = TerrainModel.baseHeight(x + 0.5, z - 0.5, SEED, s);
                // The surface comes from the coarse lattice (8-block cells, then interpolated) while
                // `ground` is the exact terrain at this position, so the two only have to agree to
                // within a couple of cells. The bound still catches a surface that has drifted
                // hundreds of blocks below its own bed, which is the failure worth guarding against.
                assertTrue(c.waterSurface() >= ground - 2.0 * Drainage.CELL,
                        "lake surface far below its own ground at " + x + "," + z
                                + " surface=" + c.waterSurface() + " ground=" + ground);
            }
        }
        assertTrue(lakes > 0, "the default world has no lakes at all");
    }

    /**
     * A river's water surface must never rise as it goes upstream. Sampling along the flow is awkward
     * from outside the solver, so the equivalent local property is used: inside a channel the surface
     * must never exceed the surrounding fall line by more than the carve, which is what a rising
     * profile would require.
     */
    @Test
    void channelSurfaceNeverExceedsTheFallLine() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        for (double[] p : probePositions()) {
            TerrainModel.Sample sm = TerrainModel.sample(SEED, p[0], p[1], s);
            if (sm.river() <= 0.1) continue;
            double fallLine = TerrainModel.baseHeight(p[0], p[1], SEED, s);
            assertTrue(sm.waterLevel() <= fallLine + 1e-6,
                    "water above the fall line at " + p[0] + "," + p[1]
                            + " water=" + sm.waterLevel() + " fallLine=" + fallLine);
        }
    }
}
