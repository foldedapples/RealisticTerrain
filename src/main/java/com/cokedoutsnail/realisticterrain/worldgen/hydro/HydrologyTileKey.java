package com.cokedoutsnail.realisticterrain.worldgen.hydro;

/**
 * Identity of one macro-hydrology tile.
 *
 * <p>Negative coordinates are handled with {@link Math#floorDiv} so that the tile lattice is
 * symmetric about the origin and tile {@code 0} contains blocks {@code 0..tileBlocks-1}. Using
 * integer division instead would make the tiles either side of the origin twice as wide, which
 * shifts the halo overlap and produces a seam that only appears on one side of the world.
 */
public record HydrologyTileKey(int tx, int tz) {
    /** The tile containing a block column. */
    public static HydrologyTileKey of(double blockX, double blockZ, int tileBlocks) {
        int bx = (int) Math.floor(blockX);
        int bz = (int) Math.floor(blockZ);
        return new HydrologyTileKey(Math.floorDiv(bx, tileBlocks), Math.floorDiv(bz, tileBlocks));
    }

    /** Packed identity for use as a cache key. */
    public long key() {
        return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
    }

    @Override
    public String toString() {
        return "tile(" + tx + "," + tz + ")";
    }
}
