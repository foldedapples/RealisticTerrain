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
 * the identical continental data instead of recalculating it, and interpolation with correct
 * bilinear weights (quintically S-curved in-cell coordinates) turns the lattice into smooth,
 * continuous fields: no cell-border creases, no diamond/facet grid on slopes.
 *
 * <p>Shared by every chunk worker thread through a {@link ConcurrentHashMap}; it is keyed by
 * world seed + terrain settings so different worlds or slider changes never cross-contaminate.
 * All node computations are pure functions of (seed, settings, cell) so the cache never alters
 * the generated terrain - it only removes redundant work.
 */
public final class TerrainCache {
    public static final int CELL = 16;
    /** Plate-lookup frequency: one tectonic plate spans roughly this many blocks. */
    private static final double PLATE_SCALE = 2600.0;
    /**
     * Domain-warp amplitude applied to the plate lookup, in blocks. ReTerraForged warps every
     * continental field so its Voronoi edges meander instead of tracing the cell lattice.
     */
    private static final double PLATE_WARP = 260.0;
    /** Wavelength, in blocks, of the field that decides whether a plate margin collides or rifts. */
    private static final double MARGIN_SENSE_SCALE = 4200.0;
    /** Transition width of that field, in field units (it spans roughly -0.5..0.7). */
    private static final double MARGIN_SENSE_BAND = 0.12;
    private static final int MAX_NODES = 262_144; // ~512×512 coarse tiles
    private static final ConcurrentHashMap<Long, Node> NODES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> ACCESS = new ConcurrentHashMap<>();
    private static final AtomicLong CLOCK = new AtomicLong();

    /** All slow-moving fields evaluated once per coarse node. */
    public record Node(
            double continent,  // -1..1  ocean basins -> cratonic shields
            double plates,     //  0..1  tectonic plate trait (distance-blended, continuous)
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
        // Coordinates must be avalanche-mixed before being combined. XOR-ing scaled coordinates
        // directly made ~20% of neighbouring cells land on the same key, so a chunk could be
        // served an unrelated cell's continental/tectonic data - non-deterministic terrain with
        // hard jumps along the lattice. Mixing after each step keeps collisions birthday-bounded.
        long h = seed ^ ((long) s.hashCode() * 0x9E3779B97F4A7C15L);
        h = mix(h ^ ((long) cx * 0xC2B2AE3D27D4EB4FL));
        h = mix(h ^ ((long) cz * 0x51F4A7C15D3A9E37L));
        return h;
    }

    /** 64-bit avalanche finalizer (murmur/splitmix style). */
    private static long mix(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
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
     * Interpolated view of the coarse lattice at an arbitrary (x, z): the slow fields become
     * smooth and continuous. Every field is blended with the same weights, including the plate
     * identity, which is what keeps the orogeny amplitude (620 blocks at full strength) free of
     * the stepped quadrant cliffs a nearest-node lookup used to create.
     */
    public static Node sample(double x, double z, long seed, TerrainSettings s) {
        double gx = x / CELL, gz = z / CELL;
        int ax = (int) Math.floor(gx), az = (int) Math.floor(gz);
        // Local coordinates inside the current cell, normalized to 0..1.
        double fx = gx - ax, fz = gz - az;
        // Quintic S-curve (6t^5 - 15t^4 + 10t^3) over those normalized coordinates.
        // Linear bilinear weights are only C0 at the 16-block cell borders, so every border
        // showed up as a slope crease / sawtooth terrace, and the fx*fz saddle term read as a
        // repeating diamond-facet grid across hills and mountains. The faded weights are C2
        // (value, slope and curvature are continuous across borders) which removes the faceting
        // while still passing exactly through the cached node values at the corners.
        double sx = fade(fx), sz = fade(fz);
        Node n00 = node(ax, az, seed, s);
        Node n10 = node(ax + 1, az, seed, s);
        Node n01 = node(ax, az + 1, seed, s);
        Node n11 = node(ax + 1, az + 1, seed, s);
        return new Node(
                bl(n00.continent(), n10.continent(), n01.continent(), n11.continent(), sx, sz),
                bl(n00.plates(), n10.plates(), n01.plates(), n11.plates(), sx, sz),
                bl(n00.convergent(), n10.convergent(), n01.convergent(), n11.convergent(), sx, sz),
                bl(n00.divergent(), n10.divergent(), n01.divergent(), n11.divergent(), sx, sz),
                bl(n00.macro(), n10.macro(), n01.macro(), n11.macro(), sx, sz),
                bl(n00.belt(), n10.belt(), n01.belt(), n11.belt(), sx, sz),
                bl(n00.warpX(), n10.warpX(), n01.warpX(), n11.warpX(), sx, sz),
                bl(n00.warpZ(), n10.warpZ(), n01.warpZ(), n11.warpZ(), sx, sz),
                bl(n00.fault(), n10.fault(), n01.fault(), n11.fault(), sx, sz));
    }

    /** Quintic S-curve: 0 at t=0, 1 at t=1, with zero first and second derivatives at both ends. */
    private static double fade(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /**
     * Bilinear interpolation written in ReTerraForged's nested-lerp form, i.e. exactly
     * {@code Perlin.sample}'s {@code lerp(lerp(a00, a10, fx), lerp(a01, a11, fx), fz)}.
     *
     * <p>Expanding the nested lerps gives
     * {@code a00 + (a10-a00)fx + (a01-a00)fz + (a00-a10-a01+a11)fx.fz}. The weights of a nested
     * lerp always sum to one, so the result is a convex combination of the four corners: it can
     * never overshoot them and it passes exactly through each of them. The previous code had the
     * cross term as {@code (a00 - a11 - a10 + a01)}, which does not sum to one and does not pass
     * through the corners, so every 16-block cell was twisted by an extra
     * {@code 2.(a01 - a11).fx.fz}. That twist is what produced the repeating diamond/facet grid
     * and the sawtooth terrace steps on slopes.
     */
    private static double bl(double a00, double a10, double a01, double a11, double fx, double fz) {
        return lerp(lerp(a00, a10, fx), lerp(a01, a11, fx), fz);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
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
        double warpX = Noise2D.fbm(x / 1500.0, z / 1500.0, seed + 101, 3, 2.0, .5);
        double warpZ = Noise2D.fbm(x / 1500.0, z / 1500.0, seed + 131, 3, 2.0, .5);
        // Domain-warp the plate lookup before resolving it. ReTerraForged always warps its
        // continental/cellular fields (AddWarp / DirectionWarp / CompoundWarp) for exactly this
        // reason: an un-warped Voronoi has dead-straight edges along its own lattice, and straight
        // edges are what read as a man-made grid on the surface. The warp bends them into
        // meandering, natural-looking margins without changing the plate statistics at all.
        double px = x + warpX * PLATE_WARP;
        double pz = z + warpZ * PLATE_WARP;
        CellularNoise.Cell plate = CellularNoise.sample(
                px / PLATE_SCALE, pz / PLATE_SCALE, seed + 31, CellularNoise.DEFAULT_SHAPE);
        // Orogeny/rift strength: proximity to the nearest margin, 0 deep inside a plate and 1 on the
        // margin itself. The 0.30 divisor used to confine this to a thin sliver right on the
        // boundary, so orogeny (see TerrainModel#tectonicBase) had nowhere to put a foothill or
        // shoulder - only a narrow band that could reach full amplitude. Widening it to 0.55 spreads
        // the same margin over a proportionally bigger swath of each plate, which is what turns a
        // "too tiny" spike into an actual range with real extent.
        double margin = sstep(clamp((0.42 - plate.boundary()) / 0.55));
        // Whether a margin collides (fold belt) or separates (rift, ocean trench) is read from a
        // smooth *world-space* field, so a boundary keeps one tectonic style over long stretches
        // while the switch between styles is a gradual change, never a cliff. A hash of the plate
        // pair would be crisp along a margin but would flip discontinuously wherever the
        // second-nearest plate changes; averaging the smooth field instead would halve every
        // margin's strength, because orogeny is linear in this. The narrow threshold band below
        // keeps the decision essentially binary - full amplitude one way or the other - while the
        // field it reads is smooth.
        double sense = Noise2D.fbm(x / MARGIN_SENSE_SCALE, z / MARGIN_SENSE_SCALE, seed + 53, 3, 2.0, .5);
        double collisionAffinity = sstep(clamp(sense / MARGIN_SENSE_BAND + 0.5));
        double convergent = collisionAffinity * margin;
        double divergent = (1.0 - collisionAffinity) * margin;
        double macro = Noise2D.fbm(x / (1350.0 / s.mountainFrequency()), z / (1350.0 / s.mountainFrequency()), seed + 29, 4, 2.03, .48);
        double belt = Math.max(0.0, Noise2D.ridged(x / (940.0 / s.mountainFrequency()), z / (940.0 / s.mountainFrequency()), seed + 41, 5));
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