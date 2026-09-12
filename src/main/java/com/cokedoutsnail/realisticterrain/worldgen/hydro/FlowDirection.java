package com.cokedoutsnail.realisticterrain.worldgen.hydro;

import java.util.Arrays;

/**
 * D8 flow routing that cannot produce a cycle.
 *
 * <p>The receiver of a cell is the neighbour with the <em>smallest flood rank</em>, not the smallest
 * elevation. That single choice is what makes the flow graph acyclic without any cycle-breaking
 * pass: the flood pops a downstream cell strictly before an upstream one, so a rank-minimal
 * neighbour can never point back upstream, and even a perfectly flat filled basin drains out through
 * its spill point instead of looping.
 *
 * <p>Ties are broken by the fixed neighbour iteration order, which is deterministic. Cells on the
 * grid rim are outlets ({@code -1} receiver): their water leaves the solved domain, which is why the
 * rim must be far enough from anything that is published.
 */
public final class FlowDirection {
    private FlowDirection() {
    }

    /**
     * @param outlet   cells that already drain nowhere (grid rim and ocean floor)
     * @param receiver output array, filled with the neighbour index or {@code -1} for an outlet
     */
    public static void receivers(int grid, int[] rank, int[] popOrder, int popCount, boolean[] outlet,
            int[] receiver) {
        Arrays.fill(receiver, -1);
        for (int k = 0; k < popCount; k++) {
            int c = popOrder[k];
            // Rim and ocean cells are outlets. Without this check an interior ocean cell - which is
            // popped first because it is the lowest ground - would be routed towards a neighbour with
            // a HIGHER rank, i.e. uphill, creating a flow cycle and a rising water surface.
            if (outlet[c]) {
                continue;
            }
            int ci = c % grid;
            int cj = c / grid;
            int best = -1;
            int bestRank = Integer.MAX_VALUE;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0) continue;
                    int nb = (cj + dj) * grid + (ci + di);
                    int r = rank[nb];
                    if (r < bestRank) {
                        bestRank = r;
                        best = nb;
                    }
                }
            }
            receiver[c] = best;
        }
    }
}
