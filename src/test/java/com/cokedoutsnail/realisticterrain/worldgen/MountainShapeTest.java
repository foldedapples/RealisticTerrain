package com.cokedoutsnail.realisticterrain.worldgen;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terrain-quality invariants for mountain ranges: they must be broad and connected - real orogeny is a
 * belt, not a field of isolated spikes - and no single-column needle peak may poke through the world.
 * These are the properties that separate the tectonic model from "ordinary fBm noise multiplied
 * vertically", exactly the things a PNG diagnostic shows but a numeric test usually misses.
 */
final class MountainShapeTest {
    private static final long SEED = 8675309L;
    private static final int MOUNTAIN_STEP = 64;
    /**
     * Contour at which a column counts as part of a mountain range. Measured at the mountain BODY: the
     * summit contour (300+ above sea) is naturally sparse for real peaks that only reach 500-600
     * locally, and connectivity and breadth are properties of the whole range, foothills included.
     */
    private static final double MOUNTAIN_CUTOFF = 120.0; // blocks above sea level

    /** The mountain mask over a large area must form one dominant connected belt, not many flecks. */
    @Test
    void rangesAreBroadAndConnected() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        int cells = (8192 / MOUNTAIN_STEP) + 1; // ~129 x 129
        boolean[] mountain = new boolean[cells * cells];
        int count = 0;
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                double x = (i - cells / 2) * MOUNTAIN_STEP;
                double z = (j - cells / 2) * MOUNTAIN_STEP;
                double height = TerrainModel.sample(SEED, x, z, s).height();
                if (height > s.seaLevel() + MOUNTAIN_CUTOFF) {
                    mountain[j * cells + i] = true;
                    count++;
                }
            }
        }
        assertTrue(count > cells, "mountain mask is empty: only " + count + " of " + cells * cells + " cells");

        // Flood-fill connected components (8-neighbourhood) and rank them by size.
        int largest = 0;
        int tiny = 0; // components of a handful of cells
        boolean[] visited = new boolean[mountain.length];
        for (int start = 0; start < mountain.length; start++) {
            if (!mountain[start] || visited[start]) continue;
            int size = fill(mountain, visited, start, cells);
            largest = Math.max(largest, size);
            if (size <= 3) tiny++;
        }
        // A single range must dominate: the largest belt is at least a quarter of all mountain cells.
        assertTrue(largest >= count / 4,
                "largest mountain component (" + largest + ") is not a dominant belt of " + count);
        // And the map must not be a mosaic of tiny flecks.
        assertTrue(tiny <= count / 50,
                "too many isolated two- and three-cell mountain flecks: " + tiny + " in " + count);
    }

    /** No column may be a spike: every local maximum must have real support a few blocks away. */
    @Test
    void noIsolatedNeedlePeaks() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        // Find where the mountains actually are, then examine one principled region at block
        // resolution so a single-cell needle cannot hide between lattice samples.
        boolean any = false;
        for (int j = -8; j <= 8; j++) {
            for (int i = -8; i <= 8; i++) {
                double x = i * 1024.0, z = j * 1024.0;
                double height = TerrainModel.sample(SEED, x, z, s).height();
                if (height - s.seaLevel() < 300.0) continue;
                any = true;
                scanFineRegion(SEED, s, x, z);
            }
        }
        assertTrue(any, "no mountain region found to scan");
    }
private static void scanFineRegion(long seed, TerrainSettings s, double cx, double cz) {
        int radius = 24;                    // 48 x 48 blocks at 1-block resolution
        int span = 2 * radius + 1;
        double[][] h = new double[span][span];
        for (int j = 0; j < span; j++) {
            for (int i = 0; i < span; i++) {
                h[j][i] = TerrainModel.sample(seed, cx - radius + i, cz - radius + j, s).height();
            }
        }
        for (int j = 1; j < span - 1; j++) {
            for (int i = 1; i < span - 1; i++) {
                double here = h[j][i];
                if (here < s.seaLevel() + 320.0) continue; // not a summit
                // Support: the average of the four axis neighbours and of the diagonal ring. A true
                // range crest declines over tens of blocks; a needle has nothing close at all.
                double axis = (h[j - 1][i] + h[j + 1][i] + h[j][i - 1] + h[j][i + 1]) / 4.0;
                double ring = (h[j - 1][i - 1] + h[j - 1][i + 1] + h[j + 1][i - 1] + h[j + 1][i + 1]) / 4.0;
                assertTrue(here - axis < 90.0,
                        "needle peak at " + (cx - radius + i) + "," + (cz - radius + j)
                                + " h=" + here + " axis-neighbours=" + axis);
                assertTrue(here - ring < 150.0,
                        "unsupported peak at " + (cx - radius + i) + "," + (cz - radius + j)
                                + " ring=" + ring);
            }
        }
    }

    /** Flood-fills the 8-connected mountain mask from {@code start}; returns the component size. */
    private static int fill(boolean[] mountain, boolean[] visited, int start, int cells) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.push(start);
        visited[start] = true;
        int size = 0;
        while (!queue.isEmpty()) {
            int cell = queue.pop();
            size++;
            int x = cell % cells, z = cell / cells;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = x + dx, nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= cells || nz >= cells) continue;
                    int next = nz * cells + nx;
                    if (mountain[next] && !visited[next]) {
                        visited[next] = true;
                        queue.push(next);
                    }
                }
            }
        }
        return size;
    }
}