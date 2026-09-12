package com.cokedoutsnail.realisticterrain.worldgen.hydro;

/**
 * Runoff-weighted flow accumulation and Strahler stream order in a single reverse pass.
 *
 * <p>Every contributor to a cell is popped by the priority flood <em>after</em> the cell itself, so
 * walking the pop order from the headwaters down means a cell's catchment is already complete by the
 * time it is folded into its receiver. No iteration to convergence, and no dependence on which chunk
 * asked for the tile first.
 *
 * <p>Accumulation is seeded with each cell's own runoff rather than with a constant, so the network
 * responds to climate: a wet basin carries more discharge than a dry one of the same size. Because
 * every contribution is non-negative, discharge can only ever grow downstream - which is exactly the
 * "discharge never decreases after a confluence" invariant the tests assert.
 */
public final class FlowAccumulation {
    private FlowAccumulation() {
    }

    public static final class Result {
        public final double[] accumulation;
        public final int[] strahler;

        Result(double[] accumulation, int[] strahler) {
            this.accumulation = accumulation;
            this.strahler = strahler;
        }
    }

    /**
     * @param receiver   D8 receiver index per cell, {@code -1} for outlets
     * @param popOrder   downstream-first pop order from {@link PriorityFlood}
     * @param cellRunoff per-cell runoff contribution (own area, precipitation, runoff coefficient)
     */
    public static Result solve(int[] receiver, int[] popOrder, int popCount, double[] cellRunoff) {
        final int n = receiver.length;
        double[] accumulation = cellRunoff.clone();
        int[] strahler = new int[n];
        int[] maxChild = new int[n];
        int[] maxCount = new int[n];

        for (int k = popCount - 1; k >= 0; k--) {
            int c = popOrder[k];
            // Strahler: the highest child order, plus one where that highest order arrives twice.
            int order = Math.max(1, maxChild[c] + (maxCount[c] >= 2 ? 1 : 0));
            strahler[c] = order;

            int r = receiver[c];
            if (r < 0) continue;
            accumulation[r] += accumulation[c];
            if (order > maxChild[r]) {
                maxChild[r] = order;
                maxCount[r] = 1;
            } else if (order == maxChild[r]) {
                maxCount[r] += 1;
            }
        }
        return new Result(accumulation, strahler);
    }
}
