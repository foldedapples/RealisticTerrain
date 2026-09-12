package com.cokedoutsnail.realisticterrain.worldgen.hydro;

/**
 * The pre-carve ground height a hydrology tile is solved against.
 *
 * <p>This is an interface rather than a direct call so the solver can be driven from a synthetic
 * heightfield in tests. Algorithm failures (pits, cycles, uphill flow, basin leaks) are far easier
 * to see on a single downhill plane or a V-shaped valley than in random terrain, and the production
 * implementation is just one lambda over {@code TerrainModel.baseHeight}.
 *
 * <p>Implementations must be pure: the same {@code (blockX, blockZ)} always returns the same height,
 * because the solver's determinism depends on it.
 */
@FunctionalInterface
public interface ElevationGrid {
    /** Pre-carve ground height in blocks at a world column. */
    double height(double blockX, double blockZ);
}
