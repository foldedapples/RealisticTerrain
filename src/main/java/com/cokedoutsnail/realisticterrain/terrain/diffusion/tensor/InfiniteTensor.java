package com.cokedoutsnail.realisticterrain.terrain.diffusion.tensor;

import java.util.ArrayList;
import java.util.List;

public class InfiniteTensor {
    public final String id; public final Integer[] shape;
    final TensorWindow outputWindow;
    final TensorFunction function;
    final BatchTensorFunction batchFunction;
    final int batchSize;
    final InfiniteTensor[] deps; final TensorWindow[] depWindows;
    final MemoryTileStore store; final long cacheLimitBytes;

    InfiniteTensor(String id, Integer[] shape, TensorWindow w, TensorFunction f, BatchTensorFunction bf, int bs,
                   InfiniteTensor[] deps, TensorWindow[] dw, MemoryTileStore s, long lim) {
        this.id = id; this.shape = shape; outputWindow = w; function = f; batchFunction = bf;
        batchSize = bs; this.deps = deps; depWindows = dw; store = s; cacheLimitBytes = lim;
    }

    public FloatTensor getSlice(int[] start, int[] end) {
        int n = shape.length; int[][] pr = buildRange(start, end);
        ensureComputed(pr);
        int[] os = new int[n]; for (int d = 0; d < n; d++) os[d] = end[d] - start[d];
        FloatTensor out = new FloatTensor(os);
        int[] lo = outputWindow.getLowestIntersection(pr), hi = outputWindow.getHighestIntersection(pr);
        iterateWindows(lo, hi, wi -> {
            FloatTensor c = store.getCachedWindow(id, wi); if (c == null) return;
            int[][] wb = outputWindow.getBounds(wi);
            for (int d = 0; d < n; d++) {
                int s0 = Math.max(pr[d][0], wb[d][0]), s1 = Math.min(pr[d][1], wb[d][1]);
                if (s0 >= s1) continue;
                int[][] dr = new int[n][2], sr = new int[n][2];
                dr[d][0] = s0 - start[d]; dr[d][1] = s1 - start[d];
                sr[d][0] = s0 - wb[d][0]; sr[d][1] = s1 - wb[d][0];
                out.addFrom(c, dr, sr);
            }
        });
        return out;
    }

    private void ensureComputed(int[][] pr) {
        int[] lo = outputWindow.getLowestIntersection(pr), hi = outputWindow.getHighestIntersection(pr);
        List<int[]> pending = new ArrayList<>();
        iterateWindows(lo, hi, wi -> { if (!store.isWindowCached(id, wi)) pending.add(wi); });
        if (pending.isEmpty()) return;
        if (batchFunction != null && batchSize > 0) computeBatched(pending);
        else for (int[] wi : pending) computeSingle(wi);
    }

    private void computeSingle(int[] wi) {
        List<FloatTensor> args = new ArrayList<>(deps.length);
        for (int i = 0; i < deps.length; i++) {
            int[][] b = depWindows[i].getBounds(wi);
            int[] s = new int[b.length], e = new int[b.length];
            for (int d = 0; d < b.length; d++) { s[d] = b[d][0]; e[d] = b[d][1]; }
            args.add(deps[i].getSlice(s, e));
        }
        FloatTensor r = function.apply(wi, args);
        store.cacheWindow(id, wi, r); store.evictIfNeeded(id, cacheLimitBytes);
    }

    private void computeBatched(List<int[]> wis) {
        int from = 0;
        while (from < wis.size()) {
            int to = Math.min(from + batchSize, wis.size());
            List<int[]> batch = wis.subList(from, to);
            List<List<FloatTensor>> args = new ArrayList<>(deps.length);
            for (int i = 0; i < deps.length; i++) {
                List<FloatTensor> da = new ArrayList<>(batch.size());
                for (int[] wi : batch) {
                    int[][] b = depWindows[i].getBounds(wi);
                    int[] s = new int[b.length], e = new int[b.length];
                    for (int d = 0; d < b.length; d++) { s[d] = b[d][0]; e[d] = b[d][1]; }
                    da.add(deps[i].getSlice(s, e));
                }
                args.add(da);
            }
            List<FloatTensor> outs = batchFunction.apply(batch, args);
            for (int k = 0; k < batch.size(); k++) { store.cacheWindow(id, batch.get(k), outs.get(k)); store.evictIfNeeded(id, cacheLimitBytes); }
            from = to;
        }
    }

    static int[][] buildRange(int[] s, int[] e) { int n = s.length; int[][] r = new int[n][2]; for (int d = 0; d < n; d++) { r[d][0] = s[d]; r[d][1] = e[d]; } return r; }

    static void iterateWindows(int[] lo, int[] hi, WinConsumer a) {
        int n = lo.length;
        for (int d = 0; d < n; d++) if (lo[d] > hi[d]) return;
        int[] cur = lo.clone();
        outer: while (true) { a.accept(cur.clone());
            for (int d = n-1; d >= 0; d--) { cur[d]++; if (cur[d] <= hi[d]) break; cur[d] = lo[d]; if (d == 0) break outer; } }
    }

    @FunctionalInterface interface WinConsumer { void accept(int[] wi); }
}