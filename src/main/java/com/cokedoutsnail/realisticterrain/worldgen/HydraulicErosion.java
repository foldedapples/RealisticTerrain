package com.cokedoutsnail.realisticterrain.worldgen;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

/**
 * Particle-based hydraulic erosion, run once per region over the cached 2D heightmap and read back by
 * {@link TerrainModel} for every column it samples.
 *
 * <p>This is the "drop thousands of virtual raindrops on the terrain" pass. Each droplet spawns at a
 * random point, follows the local height gradient downhill with a little inertia so its path meanders
 * instead of tracing the steepest-descent line, and carries a sediment load whose capacity grows with
 * its speed, its remaining water and - crucially - with how steeply it is descending. When the load
 * exceeds capacity it deposits, filling the base of slopes and building flat valley floors and
 * alluvial fans; when it has spare capacity it erodes, deepening gullies and steepening canyon walls.
 * Speed is gained from the drop in height and lost climbing, and water evaporates as it travels, so
 * long channels cut harder near their mouths and die out on the flats.
 *
 * <p>Three fields come out of it: {@code delta}, the signed height change in blocks, added to the
 * tectonic fall line before rivers are cut into it; {@code soil}, how much sediment the simulation
 * left behind, which drives soil depth, scree and tree density; and {@code moisture}, how much water
 * passed over the column, which feeds the biome choice.
 *
 * <p><b>Thread safety and determinism.</b> A region is a pure function of (seed, settings, regionX,
 * regionZ): droplets come from an xorshift stream seeded by that key and are processed in a fixed
 * order, so two chunk worker threads computing the same region produce bit-identical arrays and
 * {@code putIfAbsent} may keep either one. The only shared mutable state is the bounded cache, which
 * mirrors {@link TerrainCache}. That keeps the pass compatible with 1.21.11's asynchronous chunk
 * generation, the same requirement the rest of the engine is built around.
 *
 * <p><b>No seams.</b> Each region is simulated on a grid extending {@value #HALO} cells past its own
 * borders, filled from {@link TerrainModel#baseHeight} rather than from a neighbour's output.
 * Droplets that cross a border keep eroding against the true terrain and then run off the edge of the
 * working grid, so erosion never has to agree with itself across a region boundary and no 512-block
 * grid can show up on the surface. Only the interior is published.
 */
public final class HydraulicErosion {
    /**
     * Blocks per grid cell. Coarse on purpose: droplets need a slope to follow, not block detail.
     * Cell count is the dominant cost of the whole pass - filling the working grid from
     * {@link TerrainModel#baseHeight} measured several times more expensive than running the droplets -
     * and cost per unit area scales as 1/CELL^2, so this one number is the lever. At 16 blocks a cell
     * still resolves gullies well inside a river channel (~36 blocks across) once the field is
     * interpolated, while keeping a region cheap enough to simulate on a chunk worker thread.
     */
    public static final int CELL = 8;
    /** Published cells per region side, so a region covers {@link #SPAN} blocks. */
    public static final int SIZE = 64;
    /**
     * Extra cells simulated around each region so border droplets see real neighbouring terrain. The
     * halo is filled from the true terrain rather than from a neighbour's output, so a droplet leaving
     * the region is still flowing down the real slope; those that travel further run off the working
     * grid and terminate, and their lost load is what {@link #publish} centres out.
     */
    public static final int HALO = 8;
    /** Region size in blocks: 512, i.e. 32&times;32 chunks per simulation. */
    public static final int SPAN = SIZE * CELL;

    /** Internal grid side, including the halo and the far node row. */
    private static final int GRID = SIZE + 1 + 2 * HALO;
    /** Droplets per region: one per published cell, i.e. a fixed rainfall density per unit area. */
    private static final int DROPLETS = SIZE * SIZE;
    /** Longest droplet path, in cells. */
    private static final int MAX_STEPS = 24;

    /**
     * Per-thread working grids for {@link #compute}: the height field, its pre-erosion copy and the
     * water ledger. Chunk worker threads each get their own set, which keeps the simulation
     * allocation-free in steady state. Never shared between threads, so no locking is needed.
     */
    private static final ThreadLocal<float[][]> SCRATCH =
            ThreadLocal.withInitial(() -> new float[3][GRID * GRID]);
    // --- simulation constants: the droplet rule-set ---
    /** How much of the previous direction is kept; the rest comes from the height gradient. */
    private static final float INERTIA = 0.06f;
    /** Sediment capacity multiplier: {@code capacity = max(-drop, MIN_SLOPE) * speed * water * this}. */
    private static final float CAPACITY_FACTOR = 6.0f;
    /** Capacity floor in blocks of relief, so a droplet on dead-flat ground still carries and deposits a little. */
    private static final float MIN_SLOPE = 0.012f;
    /** Fraction of the capacity surplus picked up per step. */
    private static final float EROSION_RATE = 0.28f;
    /** Fraction of the capacity excess dropped per step. */
    private static final float DEPOSITION_RATE = 0.30f;
    /** Never cut more than this fraction of a step's height drop: stops single-cell pits forming. */
    private static final float MAX_EROSION_FRACTION = 0.45f;
    /** Speed gained per unit of height lost. */
    private static final float GRAVITY = 4.0f;
    /** Fraction of water lost per step; this is what limits how far a channel runs. */
    private static final float EVAPORATION = 0.022f;
    /** Droplets stop doing useful work below this water volume. */
    private static final float MIN_WATER = 0.02f;
    /** Weight of one step's water in the moisture field. */
    private static final float MOISTURE_GAIN = 0.02f;

    /**
     * Gain from the raw sediment ledger (blocks moved per cell by one generation of droplets) to the
     * published height change. Set from measurement, not guesswork: over 65k sampled columns the raw
     * ledger's 1st/99th percentiles are about -2.2/+1.3 blocks, so this gain puts the published tails
     * near -5.5/+3.3 - well inside the clamp below, which is what a clamp is for. Pushing the gain
     * higher was measurably worse, not better: the tails saturated against the clamp and the gullies
     * and fans flattened into plateaus at exactly -13 and +9.
     */
    private static final float AMPLITUDE = 2.0f;
    /**
     * Hard clamp on the published height delta, in blocks, applied <em>after</em> the gain. The
     * simulation models the same process the drainage field already carves, so it is deliberately not
     * allowed to move the surface further than a gully would. Clamping here rather than at the call
     * site also bounds its influence on the shoreline and on sea level whatever the droplet statistics
     * do, and it means {@link Field#delta()} can be added to a height directly.
     */
    private static final float MAX_CUT = 13.0f;
    private static final float MAX_FILL = 9.0f;
    /**
     * Published height change, in blocks, that maps to soil 0 and soil 1. Soil thickness is derived
     * from the same net-accumulation ledger as the height delta, which is the geomorphological point
     * rather than a shortcut: convex slopes that are losing material carry thin regolith, scree and
     * bare rock, while concave footslopes and valley floors that are gaining it carry deep, fertile
     * soil. Storing a second ledger for it would be redundant state - the two are the same quantity.
     */
    private static final float SOIL_SPAN = 5.0f;
    /**
     * Raw water traffic that maps to moisture 1.0. Chosen so the measured distribution is legible
     * rather than compressed: at 3.5 the 99th percentile was already pinned at 1.0, which throws away
     * exactly the distinction between a small rill and a main channel that the biome pass needs.
     */
    private static final float MOISTURE_SCALE = 8.0f;

    /**
     * Region cache bound. A region is only ~13 KB of published lattices, so this costs a few MB at
     * most. The bound has to sit well ABOVE the number of regions a single generation pass touches, or
     * the cache thrashes: a 8192&times;8192 block area is exactly 256 regions, and bounding the cache
     * at 256 measured 7.5x the baseline sampling cost because every sweep evicted what the next one
     * needed. At 1024 the same sweep runs warm.
     */
    private static final int MAX_REGIONS = 1024;
    private static final ConcurrentHashMap<Long, Region> REGIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> ACCESS = new ConcurrentHashMap<>();
    private static final AtomicLong CLOCK = new AtomicLong();

    /**
     * The three published fields at one column. {@code delta} is signed and in blocks, negative where
     * the droplets cut; {@code soil} and {@code moisture} run 0..1.
     */
    public record Field(double delta, double soil, double moisture) {
        /**
         * The value used where the pass does not run at all (Erosion at zero): no height change,
         * undisturbed soil and no water traffic. Moisture is deliberately 0 rather than mid-scale so
         * that skipping the pass leaves the climate moisture exactly as it was.
         */
        public static final Field NEUTRAL = new Field(0.0, 0.5, 0.0);
    }

    /** One simulated region: three node lattices of (SIZE+1)^2 values, published interior only. */
    private static final class Region {
        final float[] delta = new float[(SIZE + 1) * (SIZE + 1)];
        final float[] soil = new float[(SIZE + 1) * (SIZE + 1)];
        final float[] moisture = new float[(SIZE + 1) * (SIZE + 1)];
    }

    private HydraulicErosion() {}
    /**
     * The eroded fields at a world column. Bilinearly interpolated over the region's node lattice with
     * smoothstep weights, for the same reason {@link TerrainCache} does it: a plain lerp leaves a
     * crease on every cell boundary, and a repeating 8-block crease across a valley floor is exactly
     * the kind of grid artefact this pass exists to remove.
     *
     * <p>Skipped at zero erosion intensity (see the identical guard in {@link TerrainModel#sample}):
     * the region cache must not be populated when erosion is off, both for performance and so that
     * the {@link #NEUTRAL} field is returned from every column without ever writing to shared state.
     */
    public static Field sample(double x, double z, long seed, TerrainSettings s) {
        if (s.erosionIntensity() <= 0f) return Field.NEUTRAL;
        int rx = (int) Math.floor(x / SPAN);
        int rz = (int) Math.floor(z / SPAN);
        Region r = region(rx, rz, seed, s);
        double u = (x - rx * (double) SPAN) / CELL; // 0..SIZE, floor() keeps this true for negative x
        double v = (z - rz * (double) SPAN) / CELL;
        int i = clampIndex((int) Math.floor(u));
        int j = clampIndex((int) Math.floor(v));
        double fx = smooth(u - i), fy = smooth(v - j);
        int stride = SIZE + 1;
        int a = j * stride + i, b = a + 1, c = a + stride, d = c + 1;
        return new Field(
                bilerp(r.delta[a], r.delta[b], r.delta[c], r.delta[d], fx, fy),
                bilerp(r.soil[a], r.soil[b], r.soil[c], r.soil[d], fx, fy),
                bilerp(r.moisture[a], r.moisture[b], r.moisture[c], r.moisture[d], fx, fy));
    }

    private static int clampIndex(int i) {
        return i < 0 ? 0 : Math.min(i, SIZE - 1);
    }

    private static Region region(int rx, int rz, long seed, TerrainSettings s) {
        long key = key(seed, s, rx, rz);
        long now = CLOCK.incrementAndGet();
        Region cached = REGIONS.get(key);
        if (cached != null) {
            ACCESS.put(key, now);
            return cached;
        }
        // Computed outside any map lock and then raced in: two worker threads may both simulate the
        // region, but they produce bit-identical arrays, so whichever wins is correct. That is cheaper
        // than holding a bin lock for the tens of milliseconds a simulation takes.
        Region built = compute(rx, rz, seed, s);
        Region existing = REGIONS.putIfAbsent(key, built);
        ACCESS.put(key, now);
        if (REGIONS.size() > MAX_REGIONS) evict(now);
        return existing != null ? existing : built;
    }

    private static long key(long seed, TerrainSettings s, int rx, int rz) {
        // Coordinates are avalanche-mixed before being combined. XOR-ing scaled coordinates directly
        // lets neighbouring regions collide, which would serve one region's erosion to another.
        return mix(seed ^ (s.hashCode() * 0x9E3779B97F4A7C15L) ^ mix(((long) rx << 32) ^ (rz & 0xFFFFFFFFL)));
    }

    /** splitmix64 finalizer: cheap, and every bit of the input reaches every bit of the output. */
    private static long mix(long x) {
        x ^= x >>> 30;
        x *= 0xbf58476d1ce4e5b9L;
        x ^= x >>> 27;
        x *= 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    private static void evict(long now) {
        long floor = now - (long) (MAX_REGIONS * 0.75);
        int removed = 0;
        for (Map.Entry<Long, Long> e : ACCESS.entrySet()) {
            if (e.getValue() < floor) {
                REGIONS.remove(e.getKey());
                ACCESS.remove(e.getKey());
                if (++removed > MAX_REGIONS / 4) break;
            }
        }
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double bilerp(float a, float b, float c, float d, double fx, double fy) {
        double top = a + (b - a) * fx;
        double bottom = c + (d - c) * fx;
        return top + (bottom - top) * fy;
    }

    /** Cached regions; test hook. */
    public static int size() {
        return REGIONS.size();
    }

    /** Drops every cached region; test hook, so a case can measure a cold simulation. */
    public static void clear() {
        REGIONS.clear();
        ACCESS.clear();
    }
    /**
     * Simulates one region. A pure function of its arguments - no shared state is touched - so two
     * chunk worker threads may run it concurrently for the same region and get identical arrays.
     */
    private static Region compute(int rx, int rz, long seed, TerrainSettings s) {
        long salted = seed ^ s.seedSalt();
        int cells = GRID * GRID;
        // Reuse the four working grids per thread. A region needs ~336 KB of transient arrays and a
        // world (or a wide test sweep) touches thousands of regions, so allocating fresh arrays here
        // produced gigabytes of short-lived garbage - enough to exhaust the test JVM's heap outright.
        // Every array is fully overwritten before it is read, so nothing leaks between regions.
        float[][] scratch = SCRATCH.get();
        float[] base = scratch[0], h = scratch[1], wet = scratch[2];
        double originX = rx * (double) SPAN, originZ = rz * (double) SPAN;
        // The halo is filled from the real terrain, not from a neighbour's published output, so a
        // droplet leaving the region is still flowing down the true slope (see the class comment).
        for (int j = 0; j < GRID; j++) {
            double z = originZ + (j - HALO) * CELL;
            for (int i = 0; i < GRID; i++) {
                base[j * GRID + i] = (float) TerrainModel.baseHeight(originX + (i - HALO) * CELL, z, salted, s);
            }
        }
        System.arraycopy(base, 0, h, 0, cells);
        java.util.Arrays.fill(wet, 0f);
        long[] rnd = {mix(salted ^ (((long) rx << 32) ^ (rz & 0xFFFFFFFFL)) ^ 0x5E4D520F1E2D3C4BL)};

        for (int d = 0; d < DROPLETS; d++) {
            float px = HALO + nextFloat(rnd) * SIZE;
            float py = HALO + nextFloat(rnd) * SIZE;
            float dirX = nextFloat(rnd) * 2 - 1, dirY = nextFloat(rnd) * 2 - 1;
            float n = norm(dirX, dirY);
            if (n < 1e-6f) {
                dirX = 1;
                dirY = 0;
            } else {
                dirX /= n;
                dirY /= n;
            }
            float speed = 1f, water = 1f, sediment = 0f;

            for (int step = 0; step < MAX_STEPS; step++) {
                int ci = (int) px, cj = (int) py;
                if (ci < 0 || cj < 0 || ci >= GRID - 1 || cj >= GRID - 1) break;
                float ux = px - ci, uy = py - cj;
                int o = cj * GRID + ci;
                float h00 = h[o], h10 = h[o + 1], h01 = h[o + GRID], h11 = h[o + GRID + 1];
                float startH = h00 * (1 - ux) * (1 - uy) + h10 * ux * (1 - uy)
                        + h01 * (1 - ux) * uy + h11 * ux * uy;
                // Height gradient, interpolated so the heading turns smoothly inside a cell instead of
                // snapping at every cell boundary.
                float gradX = (h10 - h00) * (1 - uy) + (h11 - h01) * uy;
                float gradY = (h01 - h00) * (1 - ux) + (h11 - h10) * ux;
                dirX = dirX * INERTIA - gradX * (1 - INERTIA);
                dirY = dirY * INERTIA - gradY * (1 - INERTIA);
                n = norm(dirX, dirY);
                if (n < 1e-6f) {
                    // Perfectly flat and perfectly still: take a random heading or the droplet stalls.
                    dirX = nextFloat(rnd) * 2 - 1;
                    dirY = nextFloat(rnd) * 2 - 1;
                    n = norm(dirX, dirY);
                    if (n < 1e-6f) break;
                }
                dirX /= n;
                dirY /= n;

                float nx = px + dirX, ny = py + dirY;
                int ni = (int) nx, nj = (int) ny;
                if (ni < 0 || nj < 0 || ni >= GRID - 1 || nj >= GRID - 1) break;
                float vx = nx - ni, vy = ny - nj;
                int p = nj * GRID + ni;
                float newH = h[p] * (1 - vx) * (1 - vy) + h[p + 1] * vx * (1 - vy)
                        + h[p + GRID] * (1 - vx) * vy + h[p + GRID + 1] * vx * vy;
                float drop = newH - startH; // negative when downhill

                // Sediment capacity: steeper, faster and wetter water carries more.
                //
                // This reads the height lost over one step, which is slope * CELL - so CELL is a PHYSICS
                // parameter here, not a free cost knob. Normalising it to slope (dividing by CELL and
                // scaling CAPACITY_FACTOR to match) was tried and measured worse, not better: the median
                // column still came out at +6 blocks with both output clamps pinned. Two further terms
                // scale with CELL and were the reason - a droplet's path is MAX_STEPS * CELL blocks
                // long, and the erosion cap below is a fraction of per-step relief. Changing the
                // resolution therefore means retuning the rates with it.
                float capacity = Math.max(-drop, MIN_SLOPE) * speed * water * CAPACITY_FACTOR;
                if (sediment > capacity || drop > 0) {
                    // Overloaded, or climbing into a hollow. Depositing into the hollow is what builds
                    // flat valley floors, alluvial fans and the gentle aprons at the base of slopes.
                    float amount = drop > 0
                            ? Math.min(drop, sediment) * DEPOSITION_RATE
                            : (sediment - capacity) * DEPOSITION_RATE;
                    if (amount > 0) {
                        sediment -= amount;
                        addWeighted(h, o, ux, uy, amount);
                    }
                } else {
                    // Spare capacity: cut. This is what deepens gullies and steepens canyon walls.
                    float amount = Math.min((capacity - sediment) * EROSION_RATE, -drop * MAX_EROSION_FRACTION);
                    if (amount > 0) {
                        sediment += amount;
                        addWeighted(h, o, ux, uy, -amount);
                    }
                }
                speed = (float) Math.sqrt(Math.max(0.0, speed * speed + drop * GRAVITY));
                water = Math.max(MIN_WATER, water * (1 - EVAPORATION));
                // Moisture is how much water actually travelled over this column, which is a better
                // proxy for plant-available water than the climate noise alone: it is high in the
                // drainage network and on the slopes that feed it, low on rain-shadowed flats.
                addWeighted(wet, o, ux, uy, water * MOISTURE_GAIN);
                px = nx;
                py = ny;
            }
        }
        return publish(base, h, wet);
    }
    /**
     * Copies the interior of the working grids into the published region fields: the height change
     * with its gain and clamp applied, the soil thickness derived from that change, and the water
     * traffic that produced it.
     */
    private static Region publish(float[] base, float[] h, float[] wet) {
        Region region = new Region();
        int stride = SIZE + 1;
        int nodes = stride * stride;
        // First pass: the mean raw ledger over the published interior. Droplets that run off the edge
        // of the working grid carry their load with them, so the raw field has a systematic deficit -
        // measured at 2.3 blocks per cell, which would have sunk the entire world by that much and
        // dragged the shoreline with it. Centring makes the pass mass-neutral: it redistributes
        // material, which is what erosion actually does, and leaves AMPLITUDE in charge of contrast
        // alone. It is a per-region constant, so the only artefact it could introduce is a step where
        // two regions meet, and two neighbouring means differ by a fraction of a block.
        double sum = 0;
        for (int j = 0; j <= SIZE; j++) {
            for (int i = 0; i <= SIZE; i++) {
                int g = (j + HALO) * GRID + (i + HALO);
                sum += h[g] - base[g];
            }
        }
        float mean = (float) (sum / nodes);
        for (int j = 0; j <= SIZE; j++) {
            for (int i = 0; i <= SIZE; i++) {
                int g = (j + HALO) * GRID + (i + HALO);
                int out = j * stride + i;
                // Centre, gain, then clamp - in that order. The clamp is a guarantee about the world
                // (no column may move further than a gully would move it), so it has to be the last
                // thing that touches the number, and applying it per node means interpolation between
                // nodes cannot overshoot it either.
                float delta = clamp((h[g] - base[g] - mean) * AMPLITUDE, -MAX_CUT, MAX_FILL);
                region.delta[out] = delta;
                region.soil[out] = clamp01(0.5f + delta / SOIL_SPAN);
                region.moisture[out] = clamp01(wet[g] / MOISTURE_SCALE);
            }
        }
        return region;
    }

    /** Adds {@code amount} to the four cells around a node, by bilinear weight; negative cuts. */
    private static void addWeighted(float[] a, int o, float ux, float uy, float amount) {
        a[o] += amount * (1 - ux) * (1 - uy);
        a[o + 1] += amount * ux * (1 - uy);
        a[o + GRID] += amount * (1 - ux) * uy;
        a[o + GRID + 1] += amount * ux * uy;
    }

    /** xorshift64*: allocation-free and, unlike anything identity-based, deterministic on every JVM. */
    private static float nextFloat(long[] r) {
        long x = r[0];
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        r[0] = x;
        return (float) ((x >>> 40) * (1.0 / 16777216.0));
    }

    private static float norm(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
