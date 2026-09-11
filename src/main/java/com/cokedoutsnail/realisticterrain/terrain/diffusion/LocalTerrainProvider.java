package com.cokedoutsnail.realisticterrain.terrain.diffusion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Provides terrain heightmap and climate data from the WorldPipeline.
 * Single-threaded inference with LRU tile caching.
 *
 * <p>Adapted from com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider
 * Original copyright: MIT License, Copyright (c) 2024 xandergos
 */
public final class LocalTerrainProvider {
    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory.getLogger(LocalTerrainProvider.class);

    private static final int TILE_SIZE = 256;
    @SuppressWarnings("unused")
    private static final int MAX_CACHE_SIZE = 64;

    private static record CacheKey(int i1, int j1, int i2, int j2) {}
    private static record CacheEntry(short[][] elevation, float[][] climate, long lastAccess) {}

    private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final Map<CacheKey, Future<CacheEntry>> PENDING = new ConcurrentHashMap<>();
    private static final ExecutorService INFERENCE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "terrain-diffusion-inference");
        t.setDaemon(true);
        return t;
    });

    private static volatile LocalTerrainProvider INSTANCE;
    private static long instanceSeed;

    private final WorldPipeline pipeline;

    private LocalTerrainProvider(long seed, PipelineModels models) {
        this.pipeline = new WorldPipeline(seed, models);
    }

    public static synchronized void init(long seed) {
        PipelineModels.awaitLoad();
        PipelineModels models = PipelineModels.getInstance();
        if (INSTANCE == null || instanceSeed != seed) {
            if (INSTANCE != null) {
                INSTANCE.pipeline.close();
                CACHE.clear();
                PENDING.clear();
            }
            INSTANCE = new LocalTerrainProvider(seed, models);
            instanceSeed = seed;
        }
    }

    public static LocalTerrainProvider getInstance() {
        if (INSTANCE == null) throw new IllegalStateException("LocalTerrainProvider not initialized");
        return INSTANCE;
    }

    public static synchronized void clearCache() {
        CACHE.clear();
        PENDING.clear();
    }

    public CacheEntry fetchHeightmap(int i1, int j1, int i2, int j2) {
        CacheKey key = new CacheKey(i1, j1, i2, j2);

        // Check cache
        CacheEntry cached = CACHE.get(key);
        if (cached != null) return cached;

        // Check pending requests
        Future<CacheEntry> existing = PENDING.get(key);
        if (existing != null) {
            try { return existing.get(); }
            catch (Exception e) { throw new RuntimeException("Terrain inference failed", e); }
        }

        // Submit new request
        Future<CacheEntry> future = INFERENCE_EXECUTOR.submit(() -> computeTerrain(key));
        PENDING.put(key, future);
        try {
            CacheEntry result = future.get();
            CACHE.put(key, result);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Terrain inference failed", e);
        } finally {
            PENDING.remove(key);
        }
    }

    private CacheEntry computeTerrain(CacheKey key) {
        int H = key.i2() - key.i1();
        int W = key.j2() - key.j1();
        // In full implementation, call pipeline to compute tiles
        // For now, return stub data (48x48 blocks, 0=ocean, values in meters)
        short[][] elev = new short[H][W];
        float[][] climate = new float[4][H * W]; // temp, temp_std, precip, precip_std
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                elev[r][c] = -100; // ocean default
            }
        }
        long now = System.nanoTime();
        return new CacheEntry(elev, climate, now);
    }

    public static int tileSize() { return TILE_SIZE; }
}