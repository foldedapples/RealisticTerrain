package com.cokedoutsnail.realisticterrain.worldgen.hydro;

import com.cokedoutsnail.realisticterrain.noise.Noise2D;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainModel;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;

/**
 * One immutable solved macro-hydrology tile.
 *
 * <p>A tile is a pure function of {@code (seed, settings, key)}: nothing here depends on which chunk
 * asked for it, in what order, or on any other tile. Once built it is never mutated, so it is safe to
 * publish to several chunk-worker threads at once and safe to evict and rebuild.
 *
 * <p>Geometry: the working grid is the published tile plus a {@link #HALO_CELLS}-cell halo on every
 * side. The halo is filled from real terrain, so flow inside the published area does not terminate at
 * its own edge - a river crossing the boundary has HALO blocks of genuine upstream valley to drain
 * from on the other side instead of a fabricated proxy line.
 */
public final class HydrologyTile {
    /** Blocks per hydrology cell. Matches the erosion grid so both agree on valley shapes. */
    public static final int CELL = 8;
    /** Cells published per tile side (256 x 8 = 2048 blocks). */
    public static final int TILE_CELLS = 256;
    /** Simulated halo around the published tile (32 x 8 = 256 blocks). */
    public static final int HALO_CELLS = 32;
    /** Working grid side, halo included. */
    public static final int GRID = TILE_CELLS + 2 * HALO_CELLS;
    /** Published tile side in blocks. */
    public static final int TILE_BLOCKS = TILE_CELLS * CELL;
    /** Halo width in blocks. */
    public static final int HALO_BLOCKS = HALO_CELLS * CELL;

    public final HydrologyTileKey key;
    public final long seed;
    public final float seaLevel;
    public final int originCellX;
    public final int originCellZ;

    public final float[] elevation;
    public final float[] filled;
    public final double[] accumulation;
    public final int[] strahler;
    public final int[] receiver;
    public final int[] rank;
    public final int[] popOrder;
    public final int popCount;

    public final float[] river;
    public final float[] lake;
    public final float[] wetland;
    public final float[] waterSurface;
    public final float[] bed;
    public final float[] width;
    public final float[] depth;
    public final float[] dischargeNorm;
    public final float[] orderNorm;
    public final float[] flowX;
    public final float[] flowZ;
    /** Distance, in blocks, to the nearest channel cell (0 on a channel). */
    public final float[] distance;

    public final int channelCells;
    public final int lakeCells;
    public final int outletCells;

    HydrologyTile(HydrologyTileKey key, long seed, float seaLevel, int originCellX, int originCellZ,
            float[] elevation, float[] filled, double[] accumulation, int[] strahler, int[] receiver,
            int[] rank, int[] popOrder, int popCount, RiverNetwork.Shaped shaped, float[] distance,
            int channelCells, int lakeCells, int outletCells) {
        this.key = key;
        this.seed = seed;
        this.seaLevel = seaLevel;
        this.originCellX = originCellX;
        this.originCellZ = originCellZ;
        this.elevation = elevation;
        this.filled = filled;
        this.accumulation = accumulation;
        this.strahler = strahler;
        this.receiver = receiver;
        this.rank = rank;
        this.popOrder = popOrder;
        this.popCount = popCount;
        this.river = shaped.river;
        this.lake = shaped.lake;
        this.wetland = shaped.wetland;
        this.waterSurface = shaped.waterSurface;
        this.bed = shaped.bed;
        this.width = shaped.width;
        this.depth = shaped.depth;
        this.dischargeNorm = shaped.dischargeNorm;
        this.orderNorm = shaped.orderNorm;
        this.flowX = shaped.flowX;
        this.flowZ = shaped.flowZ;
        this.distance = distance;
        this.channelCells = channelCells;
        this.lakeCells = lakeCells;
        this.outletCells = outletCells;
    }

    /** World block X of grid column {@code i} (cell centre). */
    public double blockX(int i) {
        return (originCellX + i) * (double) CELL + CELL * 0.5;
    }

    /** World block Z of grid row {@code j} (cell centre). */
    public double blockZ(int j) {
        return (originCellZ + j) * (double) CELL + CELL * 0.5;
    }

    /** Fractional grid column of a world block X. */
    public double gridX(double blockX) {
        return (blockX - CELL * 0.5) / CELL - originCellX;
    }

    /** Fractional grid row of a world block Z. */
    public double gridZ(double blockZ) {
        return (blockZ - CELL * 0.5) / CELL - originCellZ;
    }

    /** Approximate retained heap of one tile, in bytes; used by the cache's memory accounting. */
    public static long estimatedBytes() {
        int n = GRID * GRID;
        long floats = (long) n * 4 * 14;
        long doubles = (long) n * 8;
        long ints = (long) n * 4 * 4;
        return floats + doubles + ints;
    }

    /** Solve a tile from scratch against the real terrain model. Pure and side-effect free. */
    public static HydrologyTile solve(HydrologyTileKey key, long seed, TerrainSettings s,
            RiverNetwork.HydroShapeParams params) {
        return solve(key, seed, s, params,
                (bx, bz) -> TerrainModel.baseHeight(bx, bz, seed, s));
    }

    /**
     * Solve a tile against an arbitrary pre-carve heightfield. The synthetic-test entry point; the
     * production path is simply the {@link TerrainModel} lambda above.
     */
    public static HydrologyTile solve(HydrologyTileKey key, long seed, TerrainSettings s,
            RiverNetwork.HydroShapeParams params, ElevationGrid ground) {
        final int originCellX = key.tx() * TILE_CELLS - HALO_CELLS;
        final int originCellZ = key.tz() * TILE_CELLS - HALO_CELLS;
        final int n = GRID * GRID;

        float[] elevation = new float[n];
        for (int j = 0; j < GRID; j++) {
            double bz = (originCellZ + j) * (double) CELL + CELL * 0.5;
            for (int i = 0; i < GRID; i++) {
                double bx = (originCellX + i) * (double) CELL + CELL * 0.5;
                elevation[j * GRID + i] = (float) ground.height(bx, bz);
            }
        }

        float sea = (float) s.seaLevel();
        PriorityFlood.Result flood = PriorityFlood.solve(elevation, GRID, sea);

        int[] receiver = new int[n];
        FlowDirection.receivers(GRID, flood.rank, flood.popOrder, flood.popCount, flood.outlet, receiver);

        double[] runoff = cellRunoff(elevation, originCellX, originCellZ, seed);
        FlowAccumulation.Result flow =
                FlowAccumulation.solve(receiver, flood.popOrder, flood.popCount, runoff);

        RiverNetwork.Shaped shaped = RiverNetwork.shape(GRID, elevation, flood.filled,
                flow.accumulation, flow.strahler, receiver, flood.popOrder, flood.popCount, params);

        float[] distance = distanceToChannel(shaped.river);

        int channelCells = 0;
        int lakeCells = 0;
        int outletCells = 0;
        for (int c = 0; c < n; c++) {
            if (shaped.river[c] > 0.25f) channelCells++;
            if (shaped.lake[c] > 0.5f) lakeCells++;
            if (receiver[c] < 0) outletCells++;
        }

        return new HydrologyTile(key, seed, sea, originCellX, originCellZ, elevation, flood.filled,
                flow.accumulation, flow.strahler, receiver, flood.rank, flood.popOrder, flood.popCount,
                shaped, distance, channelCells, lakeCells, outletCells);
    }

    /**
     * Per-cell runoff, in cell areas: precipitation modulated by slope so steep ground sheds water
     * quickly and flat ground stores it. Deterministic, bounded and never negative.
     */
    private static double[] cellRunoff(float[] elevation, int originCellX, int originCellZ, long seed) {
        double[] out = new double[elevation.length];
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                int c = j * GRID + i;
                double bx = (originCellX + i) * (double) CELL + CELL * 0.5;
                double bz = (originCellZ + j) * (double) CELL + CELL * 0.5;
                double precip = 0.5 + 0.5 * Noise2D.fbm(bx / 2600.0, bz / 2600.0, seed + 421, 3, 2.0, .5);
                double slope = localSlope(elevation, i, j);
                out[c] = Math.max(0.05, precip * (0.7 + 0.6 * Math.min(1.0, slope / 0.06)));
            }
        }
        return out;
    }

    private static double localSlope(float[] elevation, int i, int j) {
        int im = Math.max(0, i - 1);
        int ip = Math.min(GRID - 1, i + 1);
        int jm = Math.max(0, j - 1);
        int jp = Math.min(GRID - 1, j + 1);
        double dx = (elevation[j * GRID + ip] - elevation[j * GRID + im]) / (2.0 * CELL);
        double dz = (elevation[jp * GRID + i] - elevation[jm * GRID + i]) / (2.0 * CELL);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Two-pass chamfer distance transform to the nearest channel cell, converted to blocks. */
    private static float[] distanceToChannel(float[] river) {
        final int n = river.length;
        final float INF = 1e9f;
        final float DIAG = 1.41421356f;
        float[] dist = new float[n];
        for (int c = 0; c < n; c++) dist[c] = river[c] > 0.25f ? 0f : INF;
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                int c = j * GRID + i;
                float d = dist[c];
                if (i > 0) d = Math.min(d, dist[c - 1] + 1f);
                if (j > 0) d = Math.min(d, dist[c - GRID] + 1f);
                if (i > 0 && j > 0) d = Math.min(d, dist[c - GRID - 1] + DIAG);
                if (i < GRID - 1 && j > 0) d = Math.min(d, dist[c - GRID + 1] + DIAG);
                dist[c] = d;
            }
        }
        for (int j = GRID - 1; j >= 0; j--) {
            for (int i = GRID - 1; i >= 0; i--) {
                int c = j * GRID + i;
                float d = dist[c];
                if (i < GRID - 1) d = Math.min(d, dist[c + 1] + 1f);
                if (j < GRID - 1) d = Math.min(d, dist[c + GRID] + 1f);
                if (i < GRID - 1 && j < GRID - 1) d = Math.min(d, dist[c + GRID + 1] + DIAG);
                if (i > 0 && j < GRID - 1) d = Math.min(d, dist[c + GRID - 1] + DIAG);
                dist[c] = d;
            }
        }
        for (int c = 0; c < n; c++) dist[c] *= CELL;
        return dist;
    }
}
