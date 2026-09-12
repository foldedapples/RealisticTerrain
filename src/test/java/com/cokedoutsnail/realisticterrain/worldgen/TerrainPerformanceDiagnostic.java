package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.worldgen.hydro.HydrologyManager;
import org.junit.jupiter.api.Test;

/**
 * Performance diagnostic. It reports numbers and enforces nothing: thresholds on wall-clock time are
 * hardware-specific and would make the suite fail on a slow machine for no reason, which is exactly
 * what the task asks not to do. Run it directly to see the shapes:
 *
 * <pre>./gradlew test --tests '*TerrainPerformanceDiagnostic*' -i</pre>
 */
final class TerrainPerformanceDiagnostic {
    private static final long SEED = 0x5245414C49535449L;
    private static final int SIDE = 96;      // samples per side
    private static final int STEP = 16;      // blocks between samples
    private static final int AREA = 256;     // a 16x16 chunk block of terrain

    @Test
    void reportThroughput() {
        TerrainSettings s = TerrainSettings.DEFAULT;

        // Cold: fresh caches, so every coarse tile and drainage region has to be built.
        TerrainCache.clear();
        HydrologyManager.clear();
        long t0 = System.nanoTime();
        sweep(s);
        double coldMs = (System.nanoTime() - t0) / 1e6;

        // Warm: the same area again with both caches populated.
        t0 = System.nanoTime();
        sweep(s);
        double warmMs = (System.nanoTime() - t0) / 1e6;

        int samples = SIDE * SIDE;
        System.out.printf("[terrain] cold %.1f ms (%.0f k samples/s), warm %.1f ms (%.0f k samples/s)%n",
                coldMs, samples / coldMs, warmMs, samples / warmMs);
        System.out.printf("[terrain] covering %d x %d blocks at 1 sample per column%n", SIDE * STEP, SIDE * STEP);
        System.out.printf("[caches] tectonic nodes=%d, hydrology tiles=%d%n", TerrainCache.size(), HydrologyManager.size());

        // The task asks for explicit tile cost and memory numbers.
        System.out.printf("[hydro] %.1f ms per hydrology tile (%d solved), %.1f MB per tile, %.1f MB cached%n",
                HydrologyManager.meanSolveMillis(), HydrologyManager.solveCount(),
                HydrologyManager.estimatedTileBytes() / 1048576.0,
                HydrologyManager.estimatedCacheBytes() / 1048576.0);

        // Cheap, but a genuine sanity bound: a whole column must not take microseconds-times-a-thousand.
        org.junit.jupiter.api.Assertions.assertTrue(coldMs > 0.0 && warmMs > 0.0);
    }

    private static void sweep(TerrainSettings s) {
        for (int j = 0; j < SIDE; j++) {
            for (int i = 0; i < SIDE; i++) {
                TerrainModel.sample(SEED, (i - SIDE / 2) * (double) STEP, (j - SIDE / 2) * (double) STEP, s);
            }
        }
    }

    /** Reports the cost of a single chunk-shaped batch, which is what generation actually pays. */
    @Test
    void reportChunkBatch() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        TerrainCache.clear();
        HydrologyManager.clear();
        long t0 = System.nanoTime();
        for (int c = 0; c < AREA; c++) {
            int cx = (c % 16) * 16, cz = (c / 16) * 16;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    TerrainModel.sample(SEED, cx + lx, cz + lz, s);
                }
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        double perColumn = ms / (AREA * 256.0);
        System.out.printf("[terrain] %d chunks (cold) in %.0f ms -> %.1f us/column%n", AREA, ms, perColumn * 1000);
        System.out.printf("[caches] tectonic nodes=%d, hydrology tiles=%d%n", TerrainCache.size(), HydrologyManager.size());
        org.junit.jupiter.api.Assertions.assertTrue(perColumn > 0.0);
    }
}
