package com.cokedoutsnail.realisticterrain.worldgen.hydro;

import java.util.Arrays;

/**
 * Priority-flood depression filling (Barnes, Lehman &amp; Mulla) on a coarse elevation grid.
 *
 * <p>Two products come out of one pass, and both matter:
 *
 * <ul>
 *   <li>{@code filled}: every closed depression raised to its spill elevation, so there are no
 *       single-cell pits for flow to vanish into and no endorheic depression that is not either a
 *       real lake or properly drained.</li>
 *   <li>{@code popOrder} / {@code rank}: a valid <em>downstream topological order</em>. A cell is
 *       always popped before the neighbours it spills into, so "drain to the neighbour with the
 *       smallest rank" is acyclic for free, and both accumulation and the water-surface profile can
 *       be computed in a single linear pass instead of iterating to convergence.</li>
 * </ul>
 *
 * <p>Determinism: the queue is a binary heap keyed by {@code (filledHeight, cellIndex)}. Heights are
 * floats and indexes are unique, so the pop order is total and reproducible; two worker threads
 * solving the same tile produce bit-identical arrays.
 */
public final class PriorityFlood {
    private PriorityFlood() {
    }

    /** Filled surface, downstream pop order, per-cell rank, outlet flags and popped-cell count. */
    public static final class Result {
        public final float[] filled;
        public final int[] popOrder;
        public final int[] rank;
        public final boolean[] outlet;
        public final int popCount;

        Result(float[] filled, int[] popOrder, int[] rank, boolean[] outlet, int popCount) {
            this.filled = filled;
            this.popOrder = popOrder;
            this.rank = rank;
            this.outlet = outlet;
            this.popCount = popCount;
        }
    }

    /**
     * @param elevation flat row-major grid of pre-carve ground heights
     * @param grid      side length of the square grid
     * @param seaLevel  cells at or below this are ocean outlets and keep their own height
     */
    public static Result solve(float[] elevation, int grid, float seaLevel) {
        final int n = grid * grid;
        float[] filled = elevation.clone();
        int[] popOrder = new int[n];
        int[] rank = new int[n];
        Arrays.fill(rank, Integer.MAX_VALUE);

        boolean[] closed = new boolean[n];
        int[] heap = new int[n + 1];
        int heapSize = 0;

        // Seed every outlet: the grid rim (flow spills off-tile) and the ocean floor. Both are
        // given their own elevation so the flood can never raise the sea bed artificially.
        //
        // `outlet` must be recorded, not just applied here. An ocean cell in the *interior* of the
        // grid is popped first (it is the lowest ground), so its neighbours all have a HIGHER rank -
        // and a "drain to the lowest-rank neighbour" rule would then route it uphill, which is what
        // produced the flow cycles, the falling discharge and the rising water surfaces. Flow
        // routing therefore has to know which cells already drain nowhere.
        boolean[] outlet = new boolean[n];
        for (int j = 0; j < grid; j++) {
            for (int i = 0; i < grid; i++) {
                int c = j * grid + i;
                boolean rim = i == 0 || j == 0 || i == grid - 1 || j == grid - 1;
                if (rim || elevation[c] <= seaLevel) {
                    outlet[c] = true;
                    closed[c] = true;
                    heapSize = push(heap, heapSize, filled, c);
                }
            }
        }

        int popCount = 0;
        while (heapSize > 0) {
            int c = heap[1];
            heapSize = popRoot(heap, heapSize, filled);
            rank[c] = popCount;
            popOrder[popCount++] = c;

            int ci = c % grid;
            int cj = c / grid;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0) continue;
                    int ni = ci + di;
                    int nj = cj + dj;
                    if (ni < 0 || nj < 0 || ni >= grid || nj >= grid) continue;
                    int nb = nj * grid + ni;
                    if (closed[nb]) continue;
                    closed[nb] = true;
                    // A cell's water level is never below its own ground, and never below the level
                    // of the cell it was reached from.
                    filled[nb] = Math.max(elevation[nb], filled[c]);
                    heapSize = push(heap, heapSize, filled, nb);
                }
            }
        }
        return new Result(filled, popOrder, rank, outlet, popCount);
    }

    // 1-based binary min-heap on (key,index); forGetter-free and allocation-free.

    private static int push(int[] heap, int size, float[] key, int c) {
        int k = ++size;
        heap[k] = c;
        while (k > 1) {
            int parent = k >>> 1;
            if (less(key, heap[k], heap[parent])) {
                int t = heap[k];
                heap[k] = heap[parent];
                heap[parent] = t;
                k = parent;
            } else {
                break;
            }
        }
        return size;
    }

    private static int popRoot(int[] heap, int size, float[] key) {
        heap[1] = heap[size];
        size--;
        int k = 1;
        while (true) {
            int l = k << 1;
            int r = l + 1;
            int m = k;
            if (l <= size && less(key, heap[l], heap[m])) m = l;
            if (r <= size && less(key, heap[r], heap[m])) m = r;
            if (m == k) break;
            int t = heap[k];
            heap[k] = heap[m];
            heap[m] = t;
            k = m;
        }
        return size;
    }

    private static boolean less(float[] key, int a, int b) {
        return key[a] < key[b] || (key[a] == key[b] && a < b);
    }
}
