package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.noise.CellularNoise;
import com.cokedoutsnail.realisticterrain.noise.Noise2D;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, bounded cache of the slow continental/tectonic field values evaluated on a coarse
 * 16-block lattice. Every chunk samples the same coarse node as its neighbours, so this cache
 * means the expensive low-frequency noise (continents, tectonic plates, fold belts, domain warps)
 * is evaluated once per 16×16 tile instead of once per column. Neighbour chunks therefore reuse
 * the identical continental data instead of recalculating it, and bilinear interpolation turns
 * the lattice into smooth, continuous fields (no jagged cell boundaries in the terrain).
 *
 * <p>Shared by every chunk worker thread through a {@link ConcurrentHashMap}; it is keyed by
 * world seed + terrain settings so different worlds or slider changes never cross-contaminate.
 * All node computations are pure functions of (seed, settings, cell) so the cache never alters
 * the generated terrain - it only removes redundant work.
 */
public final class TerrainCache {
    public static final int CELL = 16;
    private static final int MAX_NODES = 262_144; // ~512×512 coarse tiles
    private static final ConcurrentHashMap<Long, Node> NODES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> ACCESS = new ConcurrentHashMap<>();
    private static final AtomicLong CLOCK = new AtomicLong();

    /** All slow-moving fields evaluated once per coarse node. */
    public record Node(
            double continent,  // -1..1  ocean basins -> cratonic shields
            double plates,     //  0..1  tectonic plate identity (nearest-node)
            double convergent, //  0..1  colliding margins (fold-belt mask)
            double divergent,  //  0..1  separating margins (rift/trench mask)
            double macro,      // -1..1  broad regional crustal highs
            double belt,       //  0..1  deformed fold fabric (coarse ridged field)
            double warpX,      // domain-warp field for drainage
            double warpZ,      // domain-warp field for drainage
            double fault       //  0..1  proximity to any active margin
    ) {}

    private TerrainCache() {}
private static long key(long seed, TerrainSettings s, int cx, int cz) {
        long settings = s.hashCode();
        return seed ^ (settings * 0x9E3779B97F4A7C15L) ^ (cx * 0xC2B2AE3D27D4EB4FL) ^ (cz * 0x51F4A7C15D3A9E37L);
    }

    /** Fetches or computes the coarse node whose cell contains (worldX, worldZ). */
    public static Node node(double worldX, double worldZ, long seed, TerrainSettings s) {
        int cx = (int) Math.floor(worldX / CELL), cz = (int) Math.floor(worldZ / CELL);
        return node(cx, cz, seed, s);
    }

    private static Node node(int cx, int cz, long seed, TerrainSettings s) {
        long k = key(seed, s, cx, cz);
        Node n = NODES.get(k);
        if (n == null) {
            n = compute(seed, s, cx, cz);
            Node race = NODES.putIfAbsent(k, n);
            if (race != null) n = race;
            touch(k);
        }
        return n;
    }

    /**
     * Bilinearly interpolated view at an arbitrary (x, z): slow fields become smooth and
     * continuous. The plate identity uses the nearest node so plate boundaries stay crisp.
     */
    public static Node sample(double x, double z, long seed, TerrainSettings s) {
        double gx = x / CELL, gz = z / CELL;
        int ax = (int) Math.floor(gx), az = (int) Math.floor(gz);
        double fx = gx - ax, fz = gz - az;
        Node n00 = node(ax, az, seed, s);
        Node n10 = node(ax + 1, az, seed, s);
        Node n01 = node(ax, az + 1, seed, s);
        Node n11 = node(ax + 1, az + 1, seed, s);
        return new Node(
                bl(n00.continent(), n10.continent(), n01.continent(), n11.continent(), fx, fz),
                nearest(n00.plates(), n10.plates(), n01.plates(), n11.plates(), fx, fz),
                bl(n00.convergent(), n10.convergent(), n01.convergent(), n11.convergent(), fx, fz),
                bl(n00.divergent(), n10.divergent(), n01.divergent(), n11.divergent(), fx, fz),
                bl(n00.macro(), n10.macro(), n01.macro(), n11.macro(), fx, fz),
                bl(n00.belt(), n10.belt(), n01.belt(), n11.belt(), fx, fz),
                bl(n00.warpX(), n10.warpX(), n01.warpX(), n11.warpX(), fx, fz),
                bl(n00.warpZ(), n10.warpZ(), n01.warpZ(), n11.warpZ(), fx, fz),
                bl(n00.fault(), n10.fault(), n01.fault(), n11.fault(), fx, fz));
    }

    private static double bl(double a00, double a10, double a01, double a11, double fx, double fz) {
        return a00 + (a10 - a00) * fx + (a01 - a00) * fz + (a00 - a11 - a10 + a01) * fx * fz;
    }

    private static double nearest(double a00, double a10, double a01, double a11, double fx, double fz) {
        double xx = fx < 0.5 ? 0 : 1, zz = fz < 0.5 ? 0 : 1;
        return xx == 0 ? (zz == 0 ? a00 : a01) : (zz == 0 ? a10 : a11);
    }

    private static void touch(long k) {
        ACCESS.put(k, CLOCK.incrementAndGet());
        if (NODES.size() > MAX_NODES) evict();
    }

    private static void evict() {
        long now = CLOCK.get();
        long floor = now - (long) (MAX_NODES * 0.75);
        int removed = 0;
        for (Map.Entry<Long, Long> e : ACCESS.entrySet()) {
            if (e.getValue() < floor) {
                NODES.remove(e.getKey());
                ACCESS.remove(e.getKey());
                if (++removed > MAX_NODES / 4) break;
            }
        }
    }

    private static Node compute(long seed, TerrainSettings s, int cx, int cz) {
        double x = cx * CELL, z = cz * CELL;
        double continent = Noise2D.fbm(x / 7000.0, z / 7000.0, seed + 11, 5, 2.0, .5);
        CellularNoise.Cell plate = CellularNoise.sample(x / 2600.0, z / 2600.0, seed + 31);
        double margin = sstep(clamp((0.42 - plate.boundary()) / 0.30));
        double convergent = plate.convergent() ? margin : 0.0;
        double divergent = plate.convergent() ? 0.0 : margin;
        double macro = Noise2D.fbm(x / (1350.0 / s.mountainFrequency()), z / (1350.0 / s.mountainFrequency()), seed + 29, 4, 2.03, .48);
        double belt = Math.max(0.0, Noise2D.ridged(x / (940.0 / s.mountainFrequency()), z / (940.0 / s.mountainFrequency()), seed + 41, 5));
        double warpX = Noise2D.fbm(x / 1500.0, z / 1500.0, seed + 101, 3, 2.0, .5);
        double warpZ = Noise2D.fbm(x / 1500.0, z / 1500.0, seed + 131, 3, 2.0, .5);
        double fault = clamp(convergent + divergent);
        return new Node(continent, plate.scalar(), clamp(convergent), clamp(divergent), macro, belt, warpX, warpZ, clamp(fault));
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Smoothstep: 0 at x<=0, 1 at x>=1. */
    private static double sstep(double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return x * x * (3 - 2 * x);
    }

    /** Test/assert only: number of cached nodes. */
    public static long size() {
        return NODES.size();
    }
}