package com.cokedoutsnail.realisticterrain.terrain.diffusion.tensor;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory factory and LRU cache for InfiniteTensor window outputs.
 *
 * <p>Adapted from com.github.xandergos.terraindiffusionmc.infinitetensor.MemoryTileStore
 * Original copyright: MIT License, Copyright (c) 2024 xandergos
 */
public class MemoryTileStore {

    private final Map<String, LinkedHashMap<List<Integer>, FloatTensor>> windowCaches = new HashMap<>();
    private final Map<String, long[]> cacheSizes = new HashMap<>();
    private final Map<String, InfiniteTensor> tensors = new HashMap<>();
    private final AtomicLong totalComputedWindowCount = new AtomicLong(0L);

    public InfiniteTensor getOrCreate(
            String id,
            Integer[] shape,
            TensorFunction function,
            TensorWindow outputWindow,
            InfiniteTensor[] deps,
            TensorWindow[] depWindows,
            long cacheLimitBytes) {

        if (tensors.containsKey(id)) return tensors.get(id);

        InfiniteTensor tensor = new InfiniteTensor(
                id, shape, outputWindow, function, null, 0,
                deps, depWindows, this, cacheLimitBytes);
        register(id, tensor);
        return tensor;
    }

    public InfiniteTensor getOrCreateBatched(
            String id,
            Integer[] shape,
            BatchTensorFunction batchFunction,
            TensorWindow outputWindow,
            InfiniteTensor[] deps,
            TensorWindow[] depWindows,
            long cacheLimitBytes,
            int batchSize) {

        if (tensors.containsKey(id)) return tensors.get(id);

        InfiniteTensor tensor = new InfiniteTensor(
                id, shape, outputWindow, null, batchFunction, batchSize,
                deps, depWindows, this, cacheLimitBytes);
        register(id, tensor);
        return tensor;
    }

    private void register(String id, InfiniteTensor tensor) {
        tensors.put(id, tensor);
        windowCaches.put(id, new LinkedHashMap<>(16, 0.75f, true));
        cacheSizes.put(id, new long[]{0L});
    }

    void cacheWindow(String id, int[] windowIndex, FloatTensor output) {
        List<Integer> key = toKey(windowIndex);
        LinkedHashMap<List<Integer>, FloatTensor> cache = windowCaches.get(id);
        long[] size = cacheSizes.get(id);

        if (cache.containsKey(key)) {
            cache.get(key);
            return;
        }

        cache.put(key, output);
        size[0] += output.byteSize();
        totalComputedWindowCount.incrementAndGet();
    }

    public long getTotalComputedWindowCount() {
        return totalComputedWindowCount.get();
    }

    void evictIfNeeded(String id, long limitBytes) {
        if (limitBytes == Long.MAX_VALUE) return;
        LinkedHashMap<List<Integer>, FloatTensor> cache = windowCaches.get(id);
        long[] size = cacheSizes.get(id);
        if (cache == null) return;

        Iterator<Map.Entry<List<Integer>, FloatTensor>> it = cache.entrySet().iterator();
        while (size[0] > limitBytes && cache.size() > 1 && it.hasNext()) {
            Map.Entry<List<Integer>, FloatTensor> entry = it.next();
            size[0] -= entry.getValue().byteSize();
            it.remove();
        }
    }

    FloatTensor getCachedWindow(String id, int[] windowIndex) {
        LinkedHashMap<List<Integer>, FloatTensor> cache = windowCaches.get(id);
        if (cache == null) return null;
        return cache.get(toKey(windowIndex));
    }

    boolean isWindowCached(String id, int[] windowIndex) {
        LinkedHashMap<List<Integer>, FloatTensor> cache = windowCaches.get(id);
        return cache != null && cache.containsKey(toKey(windowIndex));
    }

    public void clearAllCaches() {
        for (Map.Entry<String, LinkedHashMap<List<Integer>, FloatTensor>> e : windowCaches.entrySet()) {
            e.getValue().clear();
            cacheSizes.get(e.getKey())[0] = 0L;
        }
    }

    public void removeTensor(String id) {
        tensors.remove(id);
        windowCaches.remove(id);
        cacheSizes.remove(id);
    }

    private static List<Integer> toKey(int[] windowIndex) {
        List<Integer> key = new ArrayList<>(windowIndex.length);
        for (int v : windowIndex) key.add(v);
        return key;
    }
}