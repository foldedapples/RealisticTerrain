package com.cokedoutsnail.realisticterrain.worldgen.hydro;

import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded cache of solved hydrology tiles.
 *
 * <p>Eviction can never change a result, and that is a property of the design rather than a promise:
 * a tile is a pure function of {@code (seed, settings, key)} and is immutable once built, so dropping
 * it and recomputing it later reproduces exactly the same arrays. The access stamps below therefore
 * only affect <em>performance</em>, never output.
 *
 * <p>The cache is keyed by a mix of the terrain seed, the settings identity and the tile key, so two
 * worlds - or two slider positions - can never share an entry, and negative tile coordinates cannot
 * collide with positive ones (see {@link HydrologyTileKey#key()}).
 */
public final class HydrologyCache {
    /**
     * Tile cap. A tile is roughly {@link HydrologyTile#estimatedBytes()} bytes (about 7 MB at the
     * current grid size), so this bounds the cache at a little over 100 MB - the same order as
     * {@link com.cokedoutsnail.realisticterrain.worldgen.TerrainCache}.
     */
    public static final int MAX_TILES = 16;

    private static final ConcurrentHashMap<Long, HydrologyTile> TILES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> STAMPS = new ConcurrentHashMap<>();
    private static final AtomicLong CLOCK = new AtomicLong();
    // Instrumentation the task asks for explicitly: approximate milliseconds per solved tile and
    // approximate retained bytes per cached tile. Monitoring only - nothing in the output depends
    // on these counters, so they cannot affect determinism.
    private static final AtomicLong SOLVES = new AtomicLong();
    private static final AtomicLong SOLVE_NANOS = new AtomicLong();
    private static final AtomicLong LAST_SOLVE_NANOS = new AtomicLong();

    private HydrologyCache() {
    }

    /** Tile lookup, solving on miss. */
    public static HydrologyTile get(HydrologyTileKey key, long seed, TerrainSettings s,
            RiverNetwork.HydroShapeParams params) {
        long ck = cacheKey(key, seed, s);
        HydrologyTile cached = TILES.get(ck);
        if (cached != null) {
            STAMPS.put(ck, CLOCK.incrementAndGet());
            return cached;
        }
        // Two threads can race here and both solve; that is deliberate. The solve is pure, so both
        // produce identical arrays and the map simply keeps one of them - far cheaper than holding a
        // lock across a multi-millisecond solve and blocking every other worker.
        long start = System.nanoTime();
        HydrologyTile tile = HydrologyTile.solve(key, seed, s, params);
        long elapsed = System.nanoTime() - start;
        SOLVES.incrementAndGet();
        SOLVE_NANOS.addAndGet(elapsed);
        LAST_SOLVE_NANOS.set(elapsed);
        TILES.put(ck, tile);
        STAMPS.put(ck, CLOCK.incrementAndGet());
        trim();
        return tile;
    }

    /** Deterministic eviction of the least-recently-used entries once the cap is exceeded. */
    private static void trim() {
        if (TILES.size() <= MAX_TILES) return;
        List<Map.Entry<Long, Long>> entries = new ArrayList<>(STAMPS.entrySet());
        entries.sort(Map.Entry.comparingByValue());
        int toRemove = TILES.size() - MAX_TILES;
        for (int i = 0; i < entries.size() && toRemove > 0; i++) {
            long k = entries.get(i).getKey();
            if (TILES.remove(k) != null) {
                toRemove--;
            }
            STAMPS.remove(k);
        }
    }

    private static long cacheKey(HydrologyTileKey key, long seed, TerrainSettings s) {
        long world = seed * 0x9E3779B97F4A7C15L ^ (s.hashCode() & 0xFFFFFFFFL);
        world ^= world >>> 29;
        return world * 31L + key.key();
    }

    /** Drops every cached tile. Used by tests to prove cold/warm equivalence. */
    public static void clear() {
        TILES.clear();
        STAMPS.clear();
        SOLVES.set(0);
        SOLVE_NANOS.set(0);
        LAST_SOLVE_NANOS.set(0);
    }

    /** Number of tiles actually solved (cache misses). */
    public static long solveCount() {
        return SOLVES.get();
    }

    /** Mean wall-clock cost of solving one hydrology tile, in milliseconds. */
    public static double meanSolveMillis() {
        long n = SOLVES.get();
        return n == 0 ? 0.0 : SOLVE_NANOS.get() / 1e6 / n;
    }

    /** Wall-clock cost of the most recent tile solve, in milliseconds. */
    public static double lastSolveMillis() {
        return LAST_SOLVE_NANOS.get() / 1e6;
    }

    /** Number of cached tiles. */
    public static long size() {
        return TILES.size();
    }

    /** Approximate retained heap for the cached tiles, in bytes. */
    public static long estimatedBytes() {
        return TILES.size() * HydrologyTile.estimatedBytes();
    }

    /** Number of entry timestamps; used by tests to check the cache does not leak stamps. */
    public static long stampCount() {
        return STAMPS.size();
    }
}
