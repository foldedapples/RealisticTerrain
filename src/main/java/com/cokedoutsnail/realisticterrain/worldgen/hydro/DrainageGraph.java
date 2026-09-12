package com.cokedoutsnail.realisticterrain.worldgen.hydro;

import java.util.ArrayList;
import java.util.List;

/**
 * Topology view over one solved tile: the channels as a directed forest flowing to outlets.
 *
 * <p>This is what makes the hydrology testable as a <em>network</em> rather than as a raster. The
 * tests that matter - no cycles, every source reaches an outlet, discharge never falls downstream,
 * the water surface never rises downstream - are all questions about this graph, and the old proxy
 * implementation could not answer any of them because it had no graph at all.
 *
 * <p>Cycle-freeness is proved exactly rather than sampled: every receiver has a strictly smaller
 * flood rank than its cell (that is the invariant {@link FlowDirection} is built on), so a ranking
 * check over all edges is a complete proof, not a heuristic walk.
 */
public final class DrainageGraph {
    private final HydrologyTile tile;
    private final int[] nodeAtCell;
    private final List<DrainageNode> nodes;

    private DrainageGraph(HydrologyTile tile) {
        this.tile = tile;
        int n = HydrologyTile.GRID * HydrologyTile.GRID;
        nodeAtCell = new int[n];
        List<DrainageNode> list = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            nodeAtCell[c] = -1;
        }
        for (int c = 0; c < n; c++) {
            if (tile.river[c] <= 0.25f) continue;
            int r = tile.receiver[c];
            boolean outlet = r < 0 || tile.river[r] <= 0.25f;
            nodeAtCell[c] = list.size();
            list.add(new DrainageNode(c, outlet ? -1 : r, tile.accumulation[c], tile.strahler[c],
                    tile.waterSurface[c], tile.bed[c], outlet));
        }
        this.nodes = List.copyOf(list);
    }

    public static DrainageGraph of(HydrologyTile tile) {
        return new DrainageGraph(tile);
    }

    public HydrologyTile tile() {
        return tile;
    }

    public List<DrainageNode> nodes() {
        return nodes;
    }

    public int nodeCount() {
        return nodes.size();
    }

    /** Node position for a grid cell, or {@code -1} when the cell is not a channel. */
    public int nodeAtCell(int gridIndex) {
        return nodeAtCell[gridIndex];
    }

    /**
     * Exact cycle check: if every receiver has a strictly lower flood rank, no walk can revisit a
     * cell, so the graph is acyclic by construction.
     */
    public boolean hasCycle() {
        int[] receiver = tile.receiver;
        int[] rank = tile.rank;
        for (int c = 0; c < receiver.length; c++) {
            int r = receiver[c];
            if (r < 0) continue;
            if (rank[r] >= rank[c]) return true;
        }
        return false;
    }

    /** True when following the flow from this cell leaves the domain or reaches the ocean. */
    public boolean reachesOutlet(int gridIndex) {
        int c = gridIndex;
        int guard = 0;
        while (c >= 0 && guard++ <= tile.receiver.length) {
            if (tile.filled[c] <= tile.seaLevel) return true;   // reached the sea
            int r = tile.receiver[c];
            if (r < 0) return true;                              // spilled off the solved domain
            c = r;
        }
        return false;
    }

    /** True when discharge never decreases from a channel cell to its downstream channel cell. */
    public boolean dischargeMonotone() {
        for (DrainageNode node : nodes) {
            if (node.downstream() < 0) continue;
            if (tile.accumulation[node.downstream()] + 1e-9 < tile.accumulation[node.id()]) return false;
        }
        return true;
    }

    /** True when the water surface never rises from a channel cell to its downstream channel cell. */
    public boolean surfaceMonotone() {
        for (DrainageNode node : nodes) {
            if (node.downstream() < 0) continue;
            if (tile.waterSurface[node.downstream()] > node.waterSurface() + 1e-6) return false;
        }
        return true;
    }

    /** Grid cells from a channel cell downstream to its outlet, inclusive. */
    public List<Integer> traceToOutlet(int gridIndex) {
        List<Integer> path = new ArrayList<>();
        int c = gridIndex;
        int guard = 0;
        while (c >= 0 && guard++ <= tile.receiver.length) {
            path.add(c);
            if (tile.receiver[c] < 0) break;
            c = tile.receiver[c];
        }
        return path;
    }
}
