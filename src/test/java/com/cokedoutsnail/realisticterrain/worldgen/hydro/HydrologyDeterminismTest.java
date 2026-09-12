package com.cokedoutsnail.realisticterrain.worldgen.hydro;

import com.cokedoutsnail.realisticterrain.worldgen.TerrainSetting;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Determinism, thread-safety and cache behaviour of the hydrology network.
 *
 * <p>Chunk generation is asynchronous and caches are bounded, so these four properties - repeat,
 * reverse order, parallel, and after eviction - all have to hold. Each of them broke the old design
 * in a different way, which is why they are asserted separately rather than rolled into one.
 */
final class HydrologyDeterminismTest {
    private static final long SEED = 8675309L;

    private static List<double[]> probes() {
        List<double[]> probes = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            probes.add(new double[]{(i * 331) % 6000 - 3000, (i * 977) % 6000 - 3000});
        }
        return probes;
    }

    private static List<RiverSample> sampleAll(List<double[]> probes, TerrainSettings s) {
        List<RiverSample> out = new ArrayList<>(probes.size());
        for (double[] p : probes) {
            out.add(HydrologyManager.sample(p[0], p[1], SEED, s));
        }
        return out;
    }

    @Test
    void repeatOrderParallelAndEvictionAllAgree() throws Exception {
        TerrainSettings s = TerrainSettings.DEFAULT;
        List<double[]> probes = probes();

        HydrologyManager.clear();
        List<RiverSample> cold = sampleAll(probes, s);

        List<double[]> reversed = new ArrayList<>(probes);
        Collections.reverse(reversed);
        List<RiverSample> reversedOut = sampleAll(reversed, s);
        Collections.reverse(reversedOut);
        assertEquals(cold, reversedOut, "reversed sampling order changed the hydrology");

        assertEquals(cold, sampleAll(probes, s), "warm cache changed the hydrology");

        // Push far more tiles through the cache than it can hold, then re-ask the original probes.
        HydrologyManager.clear();
        for (int i = 0; i < 40000; i += 41) {
            HydrologyManager.sample(i % 40000 - 20000, (i * 3) % 40000 - 20000, SEED, s);
        }
        assertEquals(cold, sampleAll(probes, s), "cache eviction changed the hydrology");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<List<RiverSample>>> jobs = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                jobs.add(() -> sampleAll(probes, s));
            }
            for (Future<List<RiverSample>> f : pool.invokeAll(jobs)) {
                assertEquals(cold, f.get(), "parallel sampling disagreed with sequential");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 23. The cache must stay bounded even after sweeping a large area. */
    @Test
    void cacheStaysBounded() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        HydrologyManager.clear();
        for (int z = -20000; z <= 20000; z += 900) {
            for (int x = -20000; x <= 20000; x += 900) {
                HydrologyManager.sample(x, z, SEED, s);
            }
        }
        assertTrue(HydrologyManager.size() <= HydrologyCache.MAX_TILES,
                "cache exceeded its cap: " + HydrologyManager.size());
        assertTrue(HydrologyManager.estimatedCacheBytes() < 256L * 1024 * 1024,
                "cache retained too much: " + HydrologyManager.estimatedCacheBytes() + " bytes");
        // Timestamps must not leak when tiles are evicted.
        assertTrue(HydrologyCache.stampCount() <= HydrologyCache.MAX_TILES + 4,
                "eviction leaked access stamps: " + HydrologyCache.stampCount());
    }

    /** 20. The lake and wetland sliders must visibly change their own output. */
    @Test
    void lakeAndWetlandSlidersChangeTheirOutput() {
        long seed = 0x5245414C49535449L;
        double lakeOn = totalLake(seed, TerrainSettings.DEFAULT);
        double lakeOff = totalLake(seed, TerrainSettings.DEFAULT.with(TerrainSetting.LAKE_FREQUENCY, 0.0));
        assertTrue(lakeOff == 0.0, "lakeFrequency = 0 still produced lakes: " + lakeOff);
        assertTrue(lakeOn >= lakeOff, "raising lake frequency removed lakes");

        double wetOn = totalWetland(seed, TerrainSettings.DEFAULT);
        double wetOff = totalWetland(seed, TerrainSettings.DEFAULT.with(TerrainSetting.WETLAND_FREQUENCY, 0.0));
        assertTrue(wetOff == 0.0, "wetlandFrequency = 0 still produced wetlands: " + wetOff);
        assertTrue(wetOn > wetOff, "wetland frequency did not add wetland: " + wetOn + " vs " + wetOff);
    }

    private static double totalLake(long seed, TerrainSettings s) {
        double sum = 0;
        for (int z = -3000; z <= 3000; z += 233) {
            for (int x = -3000; x <= 3000; x += 241) {
                sum += HydrologyManager.sample(x + 0.5, z - 0.5, seed, s).lake();
            }
        }
        return sum;
    }

    private static double totalWetland(long seed, TerrainSettings s) {
        double sum = 0;
        for (int z = -3000; z <= 3000; z += 233) {
            for (int x = -3000; x <= 3000; x += 241) {
                sum += HydrologyManager.sample(x + 0.5, z - 0.5, seed, s).wetland();
            }
        }
        return sum;
    }
}
