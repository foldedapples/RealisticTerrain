package com.cokedoutsnail.realisticterrain.worldgen.hydro;

import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;

/**
 * The public face of the hydrology system.
 *
 * <p>This is the only class the rest of the engine talks to. It turns a world column into a
 * {@link RiverSample}: no masks to re-interpret, no fake water heights, and an explicit
 * {@link WaterBodyType#NONE} when there is no water.
 *
 * <p>Sampling never moves the query position. The previous design warped {@code x} and {@code z}
 * with two independent noise fields before asking the drainage solver, which slid correctly computed
 * channels off their own watershed, broke confluences and let rivers cut sideways across slopes.
 * Meandering is now a property of the channel cross-section (applied laterally to the distance
 * field), not of the sampling coordinates.
 *
 * <p>Grid continuity: a column is owned by the tile that contains it, and that tile is solved with a
 * {@link HydrologyTile#HALO_CELLS}-cell halo of real terrain, so flow inside the published area does
 * not terminate at its own edge.
 */
public final class HydrologyManager {
    private HydrologyManager() {
    }

    /** Cross-section strength above which a column is published as a river. */
    public static final double RIVER_PRESENCE = 0.5;
    /** Basin strength above which a column is published as a lake. */
    public static final double LAKE_PRESENCE = 0.5;
    /** Wetland strength above which a column is published as a wetland. */
    public static final double WETLAND_PRESENCE = 0.5;

    /** Maps the settings sliders onto the shaper's parameters. */
    public static RiverNetwork.HydroShapeParams paramsFrom(TerrainSettings s) {
        // river_frequency, tributary_density, river_density and drainage_scale all describe the same
        // physical trade-off: how finely the runoff is split up. A denser network therefore carries
        // the same water in more, smaller channels, which is why they widen the network AND shrink
        // each individual channel. Wiring them here is what stops them being decorative sliders.
        double network = Math.max(0.25, s.riverFrequency() * s.tributaryDensity());
        double density = s.riverDensity() * s.drainageScale() * network;
        double channelScale = 1.0 / Math.sqrt(network);
        return new RiverNetwork.HydroShapeParams(
                density > 0.001,
                s.lakeFrequency() > 0.001,
                s.wetlandFrequency() > 0.001,
                HydrologyTile.CELL,
                density,
                s.riverWidth() * channelScale,
                s.riverDepth() * channelScale,
                120.0,
                16.0,
                s.wetlandFrequency(),
                s.tributaryDensity());
    }

    /** Hydrology at a world column. Deterministic, pure, and safe from any thread. */
    public static RiverSample sample(double x, double z, long seed, TerrainSettings s) {
        return sample(tileAt(x, z, seed, s), x, z);
    }

    /** The solved tile that owns a world column. */
    public static HydrologyTile tileAt(double x, double z, long seed, TerrainSettings s) {
        HydrologyTileKey key = HydrologyTileKey.of(x, z, HydrologyTile.TILE_BLOCKS);
        return HydrologyCache.get(key, seed, s, paramsFrom(s));
    }

    /** Drops every cached tile. */
    public static void clear() {
        HydrologyCache.clear();
    }

    /** Number of cached tiles. */
    public static long size() {
        return HydrologyCache.size();
    }

    /** Approximate retained heap for cached tiles, in bytes. */
    public static long estimatedCacheBytes() {
        return HydrologyCache.estimatedBytes();
    }

    /** Approximate retained bytes for one cached tile. */
    public static long estimatedTileBytes() {
        return HydrologyTile.estimatedBytes();
    }

    /** Number of tiles actually solved (cache misses). */
    public static long solveCount() {
        return HydrologyCache.solveCount();
    }

    /** Mean wall-clock cost of solving one hydrology tile, in milliseconds. */
    public static double meanSolveMillis() {
        return HydrologyCache.meanSolveMillis();
    }

    /** Wall-clock cost of the most recent tile solve, in milliseconds. */
    public static double lastSolveMillis() {
        return HydrologyCache.lastSolveMillis();
    }

    /** Interpolate an already-solved tile at a world column. */
    public static RiverSample sample(HydrologyTile tile, double x, double z) {
        final int grid = HydrologyTile.GRID;
        double gx = tile.gridX(x);
        double gz = tile.gridZ(z);
        int i = Math.max(0, Math.min(grid - 2, (int) Math.floor(gx)));
        int j = Math.max(0, Math.min(grid - 2, (int) Math.floor(gz)));
        double sx = gx - i;
        double sz = gz - j;
        int c00 = j * grid + i;
        int c10 = c00 + 1;
        int c01 = c00 + grid;
        int c11 = c01 + 1;

        double river = bilinear(tile.river, c00, c10, c01, c11, sx, sz);
        double lake = bilinear(tile.lake, c00, c10, c01, c11, sx, sz);
        double wetland = bilinear(tile.wetland, c00, c10, c01, c11, sx, sz);
        double order = bilinear(tile.orderNorm, c00, c10, c01, c11, sx, sz);
        double width = bilinear(tile.width, c00, c10, c01, c11, sx, sz);
        double depth = bilinear(tile.depth, c00, c10, c01, c11, sx, sz);
        double distance = bilinear(tile.distance, c00, c10, c01, c11, sx, sz);
        double flowX = bilinear(tile.flowX, c00, c10, c01, c11, sx, sz);
        double flowZ = bilinear(tile.flowZ, c00, c10, c01, c11, sx, sz);
        double filled = bilinear(tile.filled, c00, c10, c01, c11, sx, sz);
        double elevation = bilinear(tile.elevation, c00, c10, c01, c11, sx, sz);
        double accumulation = bilinear(tile.accumulation, c00, c10, c01, c11, sx, sz);
        double waterSurface = interpolateSurface(tile.waterSurface, c00, c10, c01, c11, sx, sz);
        double sea = tile.seaLevel;

        WaterBodyType type;
        double bed;
        double bank = Math.max(filled, elevation);
        if (elevation <= sea && filled <= sea) {
            type = WaterBodyType.OCEAN;
            waterSurface = sea;
            bed = elevation;
        } else if (river >= RIVER_PRESENCE) {
            type = WaterBodyType.RIVER;
            bed = bilinear(tile.bed, c00, c10, c01, c11, sx, sz);
        } else if (lake >= LAKE_PRESENCE) {
            type = WaterBodyType.LAKE;
            bed = bilinear(tile.bed, c00, c10, c01, c11, sx, sz);
        } else if (wetland >= WETLAND_PRESENCE) {
            type = WaterBodyType.WETLAND;
            bed = elevation - 0.30;
            waterSurface = elevation - 0.15;
        } else {
            type = WaterBodyType.NONE;
            bed = elevation;
            waterSurface = Double.NEGATIVE_INFINITY;
        }

        if (type != WaterBodyType.NONE && type != WaterBodyType.OCEAN) {
            // Enforce bed < surface < bank by moving the bed, never by lifting water over a bank.
            if (!(waterSurface > bed)) {
                waterSurface = bed + 0.25;
            }
            if (waterSurface >= bank) {
                waterSurface = bank - 0.05;
            }
            if (!(waterSurface > bed)) {
                bed = waterSurface - 0.25;
            }
        } else if (type == WaterBodyType.OCEAN) {
            // The sea floor of a deep ocean column can sit well below sea level and far inland from
            // any shore, so it has no "bank" to be below. The shoreline is where the ground rises out
            // of the water, so that is what the invariant is anchored to here.
            if (!(waterSurface > bed)) {
                bed = waterSurface - 0.25;
            }
            if (bank <= waterSurface) {
                bank = waterSurface + 0.25;
            }
        }

        return new RiverSample(type, bed, waterSurface, bank, distance, width, depth, accumulation,
                order, river, lake, wetland, flowX, flowZ);
    }

    private static double bilinear(float[] f, int c00, int c10, int c01, int c11, double sx, double sz) {
        double top = f[c00] + (f[c10] - f[c00]) * sx;
        double bottom = f[c01] + (f[c11] - f[c01]) * sx;
        return top + (bottom - top) * sz;
    }

    private static double bilinear(double[] f, int c00, int c10, int c01, int c11, double sx, double sz) {
        double top = f[c00] + (f[c10] - f[c00]) * sx;
        double bottom = f[c01] + (f[c11] - f[c01]) * sx;
        return top + (bottom - top) * sz;
    }

    /**
     * Water-surface interpolation that ignores {@code -inf} cells instead of poisoning the result: a
     * column is only as wet as the wet cells around it, never wetter.
     */
    private static double interpolateSurface(float[] surface, int c00, int c10, int c01, int c11,
            double sx, double sz) {
        int[] cells = {c00, c10, c01, c11};
        double[] weights = {(1 - sx) * (1 - sz), sx * (1 - sz), (1 - sx) * sz, sx * sz};
        double acc = 0;
        double wsum = 0;
        for (int k = 0; k < 4; k++) {
            float v = surface[cells[k]];
            if (!(v > Float.NEGATIVE_INFINITY)) continue;
            acc += v * weights[k];
            wsum += weights[k];
        }
        return wsum > 0 ? acc / wsum : Double.NEGATIVE_INFINITY;
    }
}
