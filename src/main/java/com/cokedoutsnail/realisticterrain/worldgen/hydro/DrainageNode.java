package com.cokedoutsnail.realisticterrain.worldgen.hydro;

/**
 * One node of the flow graph: a grid cell that carries a channel, plus the topology that makes it a
 * river rather than a raster mask.
 *
 * @param id            grid index within its tile (stable, unique per tile)
 * @param downstream    grid index of the downstream node, or {@code -1} when the channel ends here
 * @param discharge     accumulated upstream runoff in cell areas (never decreases downstream)
 * @param strahler      Strahler stream order (1 at every source)
 * @param waterSurface  the channel's water plane at this node
 * @param bedElevation  the channel bed at this node
 * @param outlet        true when this node's water leaves the solved domain or reaches the ocean
 */
public record DrainageNode(
        int id,
        int downstream,
        double discharge,
        int strahler,
        double waterSurface,
        double bedElevation,
        boolean outlet
) {
    /** True when the node's own geometry is self-consistent. */
    public boolean geometryIsConsistent() {
        return bedElevation < waterSurface;
    }
}
