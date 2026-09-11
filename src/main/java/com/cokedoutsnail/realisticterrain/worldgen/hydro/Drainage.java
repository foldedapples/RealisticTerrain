package com.cokedoutsnail.realisticterrain.worldgen.hydro;

import com.cokedoutsnail.realisticterrain.noise.Noise2D;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainCache;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainModel;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic regional drainage network: the hydrology behind every river, lake and wetland.
 *
 * <p>Replaces the previous approach, which was a domain-warped fBm field thresholded into a
 * "channel": that produced river-<em>shaped</em> noise, not a drainage network. It had no flow
 * direction, so channels could run along a contour or uphill; no accumulation, so a headwater
 * trickle and the trunk of the basin were drawn with the same width; no ordering, so tributaries
 * crossed instead of merging; and no basins, so lakes were wherever the noise happened to be high.
 *
 * <p>The pipeline is the standard terrain-analysis one, solved once per region on a coarse lattice
 * and cached (the same shape as {@code HydraulicErosion}, and the same idea as ReTerraForged's
 * regional heightmap/rivermap tiles):
 *
 * <ol>
 *   <li><b>Fill</b> a regional elevation grid from {@link TerrainModel#baseHeight} - the tectonic
 *       fall line plus hydraulic erosion, explicitly <em>without</em> any already-carved channel, or
 *       the solver would chase its own incisions and the network would depend on evaluation order.</li>
 *   <li><b>Priority-flood</b> the grid (with a halo) to resolve closed depressions. This both raises
 *       pits to their spill elevation - the classic "no single-cell pits" guarantee - and yields a
 *       pop order that is a valid downstream topological order on the filled surface.</li>
 *   <li><b>D8 flow direction</b> to the lowest filled neighbour, with a deterministic tie-break.</li>
 *   <li><b>Flow accumulation</b> and <b>Strahler order</b> by walking the cells in reverse pop order
 *       (a cell's contributors are always popped before it, so one pass is exact).</li>
 *   <li><b>Channels</b> where accumulation exceeds a threshold set by the river-density slider;
 *       width and depth come from discharge and order, not from the slider directly, so a trunk
 *       river is genuinely wider than the tributary that feeds it.</li>
 *   <li><b>Lakes</b> where the filled surface stands above the true surface - i.e. genuine closed
 *       basins - with the spill elevation as the water level, so an endorheic basin fills instead of
 *       leaking. <b>Wetlands</b> where the ground is nearly flat and moderately well drained.</li>
 * </ol>
 *
 * <p><b>Continuity across regions.</b> A regional solve cannot know the true upstream area that
 * crosses its own border, so unlike erosion it cannot simply publish an interior and ignore the rim:
 * a river crossing the border would stop at it. Instead the region's fields are blended towards a
 * <em>border proxy</em> - a smooth, purely local estimate of discharge built from the cached
 * tectonic fields - over the last few cells before the border. Both neighbouring regions evaluate
 * the identical proxy at the shared line, so the field is continuous there by construction, while
 * the region interior (the overwhelming majority of the area) uses the real accumulation, order and
 * basin data.
 *
 * <p><b>Determinism and thread safety.</b> A region is a pure function of
 * {@code (seed, settings, rx, rz)}; the flood queue and every tie-break are index-ordered, so two
 * chunk-worker threads solving the same region produce bit-identical arrays and {@code putIfAbsent}
 * may keep either. The only shared mutable state is the bounded cache mirroring {@link TerrainCache}.
 */
public final class Drainage {
    /** Blocks per coarse drainage cell. Matches the erosion grid, so the two agree on gullies. */
    public static final int CELL = 8;
    /** Coarse cells published per region side (512 blocks). */
    public static final int REGION = 64;
    /** Extra cells simulated around a region so border flow sees real neighbouring terrain. */
    public static final int HALO = 12;
    /** Cells per working-grid side. */
    private static final int GRID = REGION + 2 * HALO;
    /** Region side in blocks. */
    public static final int REGION_BLOCKS = REGION * CELL;
    /**
     * Cells, from the region border inwards, over which the region's own fields are blended into the
     * border proxy. Deliberately tiny: the blend zone is a fixed width, so a wide taper would cover a
     * large fraction of every region (a 10-cell taper already reaches 53% of a 512-block region) and
     * the proxy - which is only a smooth estimate - would then be deciding the terrain over half the
     * map instead of merely stitching the rim. At three cells it is ~9%, and it is zero exactly on the
     * shared line where the two regions must agree.
     */
    private static final double BORDER_TAPER = 3.0;
    /** Accumulated cells needed for the smallest channel at {@code riverDensity = 1}. */
    private static final double CHANNEL_AREA = 26.0;
    /** Discharge (accumulated cells) that counts as a "full-size" river for width and depth. */
    private static final double TRUNK_AREA = 900.0;
    /** Bounded cache. A region is {@value #GRID}^2 cells x 8 float arrays (~250 KB), so this caps the
     * cache at roughly 125 MB - the same order as {@link TerrainCache}, and small enough that the unit
     * tests can sweep several thousand blocks without exhausting a 1 GB heap. */
    private static final int MAX_REGIONS = 512;
    private static final ConcurrentHashMap<Long, Region> REGIONS = new ConcurrentHashMap<>();

    private Drainage() {}

    /** One column of drainage data, interpolated to a world position. */
    public record Cell(
            double discharge,    // 0..1 relative discharge (log-scaled accumulation)
            double order,        // 0..1 relative Strahler order
            double river,        // 0..1 channel strength at this column
            double lake,         // 0..1 closed-basin strength
            double waterSurface, // lake/sea surface where the column is a basin, else sea level
            double wetland       // 0..1 poorly drained flat ground
    ) {
        public static final Cell DRY = new Cell(0, 0, 0, 0, 0, 0);
    }

    /** Per-region solved fields on the working grid (halo included). */
    private static final class Region {
        final float[] filled;     // depression-filled surface (blocks)
        final float[] elevation;  // true surface (blocks)
        final float[] discharge;  // log-scaled accumulation, 0..1
        final float[] order;      // normalised Strahler order, 0..1
        final float[] river;      // 0..1 channel strength
        final float[] lake;       // 0..1 basin strength
        final float[] surface;    // water surface for basins (blocks)
        final float[] wetland;    // 0..1

        Region(int n) {
            filled = new float[n];
            elevation = new float[n];
            discharge = new float[n];
            order = new float[n];
            river = new float[n];
            lake = new float[n];
            surface = new float[n];
            wetland = new float[n];
        }
    }

    /**
     * Drainage at a world column. Short-circuits to {@link Cell#DRY} when the sliders disable rivers,
     * lakes and wetlands together, so a river-free world never pays for a region solve.
     */
    public static Cell sample(double x, double z, long seed, TerrainSettings s) {
        if (s.riverDensity() <= 0.001f && s.lakeFrequency() <= 0.001f && s.wetlandFrequency() <= 0.001f) {
            return Cell.DRY;
        }
        int rx = (int) Math.floor(x / REGION_BLOCKS);
        int rz = (int) Math.floor(z / REGION_BLOCKS);
        Region region = region(rx, rz, seed, s);
        return sampleRegion(region, x, z, seed, s, rx, rz);
    }

    /** Test/diagnostic only: number of cached regions. */
    public static long size() {
        return REGIONS.size();
    }

    /** Test only: drops the cache so a cold-start path can be measured. */
    public static void clear() {
        REGIONS.clear();
    }

    private static Region region(int rx, int rz, long seed, TerrainSettings s) {
        // Each coordinate is avalanche-mixed on its own BEFORE being combined, exactly as
        // TerrainCache#key does. Multiplication by an odd constant is GF(2)-linear and XOR is
        // addition in that space, so a plain `rx*C1 ^ rz*C2` combination has a non-trivial kernel:
        // two different regions can share a key, and then whichever was solved first silently serves
        // the other its terrain. Mixing each component first breaks the linearity.
        long k = mix(seed ^ ((long) s.hashCode() * 0x9E3779B97F4A7C15L));
        k = mix(k ^ ((long) rx * 0xC2B2AE3D27D4EB4FL));
        k = mix(k ^ ((long) rz * 0x51F4A7C15D3A9E37L));
        Region r = REGIONS.get(k);
        if (r == null) {
            r = compute(rx, rz, seed, s);
            Region race = REGIONS.putIfAbsent(k, r);
            if (race != null) r = race;
            if (REGIONS.size() > MAX_REGIONS) trim();
        }
        return r;
    }

    /**
     * Bounded eviction. Unlike {@link TerrainCache} this keeps no access clock - a drainage region is
     * far more expensive to recompute than a tectonic node, but the cache is also far smaller, so the
     * cheaper policy (drop an arbitrary quarter once the cap is passed) is preferred to paying for a
     * second map on every lookup. It cannot change terrain: a dropped region is recomputed
     * bit-identically on demand.
     */
    private static void trim() {
        int budget = MAX_REGIONS / 4;
        int removed = 0;
        for (Long k : REGIONS.keySet()) {
            REGIONS.remove(k);
            if (++removed >= budget) break;
        }
    }

    /** 64-bit avalanche finalizer, shared with the other caches. */
    private static long mix(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
    }

    // ------------------------------------------------------------------
    // Solver
    // ------------------------------------------------------------------

    private static Region compute(int rx, int rz, long seed, TerrainSettings s) {
        Region region = new Region(GRID * GRID);
        int originX = rx * REGION - HALO; // in cells, relative to the world lattice
        int originZ = rz * REGION - HALO;
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                double bx = (originX + i) * (double) CELL + CELL * 0.5;
                double bz = (originZ + j) * (double) CELL + CELL * 0.5;
                region.elevation[j * GRID + i] = (float) TerrainModel.baseHeight(bx, bz, seed, s);
            }
        }
        System.arraycopy(region.elevation, 0, region.filled, 0, region.elevation.length);
        solveFlow(region, s);
        return region;
    }

    /**
     * Priority-flood, flow direction, accumulation and Strahler order, in two passes over one array.
     *
     * <p>The flood is what makes the result trustworthy: every closed depression is raised to its
     * spill elevation (so there are no single-cell pits for flow to vanish into), and the order in
     * which cells are popped is a valid downstream topological order on the filled surface. Both of
     * the later passes rely on that order, which is why this is one algorithm rather than three.
     */
    private static void solveFlow(Region region, TerrainSettings s) {
        final int n = GRID * GRID;
        final float[] filled = region.filled;
        final float[] elevation = region.elevation;

        // --- 1. Priority-flood from the ocean and from the halo rim. ---
        boolean[] closed = new boolean[n];
        int[] popOrder = new int[n];
        int[] rank = new int[n];
        int popCount = 0;
        int[] heap = new int[n + 1];
        int heapSize = 0;

        double sea = s.seaLevel();
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                int c = j * GRID + i;
                boolean rim = i == 0 || j == 0 || i == GRID - 1 || j == GRID - 1;
                if (rim || elevation[c] <= sea) {
                    // Outlets: the ocean drains to itself and the halo rim spills off-region. Both
                    // keep their own elevation so the flood never raises the sea floor artificially.
                    closed[c] = true;
                    heapSize = heapPush(heap, heapSize, filled, c);
                }
            }
        }
        while (heapSize > 0) {
            int c = heap[0];
            heapSize = heapPop(heap, heapSize, filled);
            rank[c] = popCount;
            popOrder[popCount++] = c;
            int ci = c % GRID, cj = c / GRID;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0) continue;
                    int ni = ci + di, nj = cj + dj;
                    if (ni < 0 || nj < 0 || ni >= GRID || nj >= GRID) continue;
                    int nb = nj * GRID + ni;
                    if (closed[nb]) continue;
                    closed[nb] = true;
                    // Barnes priority-flood: a cell's water level is never below its own ground, and
                    // never below the level of the cell it was reached from.
                    filled[nb] = (float) Math.max(elevation[nb], filled[c]);
                    heapSize = heapPush(heap, heapSize, filled, nb);
                }
            }
        }

        // --- 2. D8 receiver, chosen by flood rank. ---
        // Picking the neighbour with the smallest rank rather than the smallest elevation is what
        // makes the graph acyclic for free: the flood pops a downstream cell strictly before an
        // upstream one, so "smallest rank" can never point back upstream, and even a perfectly flat
        // filled basin drains out through its spill point instead of looping.
        int[] receiver = new int[n];
        Arrays.fill(receiver, -1);
        for (int k = 0; k < popCount; k++) {
            int c = popOrder[k];
            int ci = c % GRID, cj = c / GRID;
            boolean onRim = ci == 0 || cj == 0 || ci == GRID - 1 || cj == GRID - 1;
            if (onRim) continue; // already an outlet
            int best = -1, bestRank = Integer.MAX_VALUE;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0) continue;
                    int nb = (cj + dj) * GRID + (ci + di);
                    int r = rank[nb];
                    if (r < bestRank || (r == bestRank && best >= 0 && filled[nb] < filled[best])) {
                        bestRank = r;
                        best = nb;
                    }
                }
            }
            receiver[c] = best;
        }
        accumulate(region, popOrder, popCount, receiver, s);
    }
    /**
     * Flow accumulation and Strahler order in a single reverse pass over the flood order.
     *
     * <p>Every contributor to a cell has a strictly higher rank than the cell itself (that is the
     * invariant the flood order provides), so walking ranks from the headwaters down means a cell's
     * catchment is already complete by the time it is folded into its receiver. No iteration to
     * convergence, and no dependence on which chunk asked for the region first.
     */
    private static void accumulate(Region region, int[] popOrder, int popCount, int[] receiver, TerrainSettings s) {
        final int n = GRID * GRID;
        double[] acc = new double[n];
        double[] orderMax = new double[n];
        double[] orderCount = new double[n];
        Arrays.fill(acc, 1.0); // a cell always drains at least its own area
        for (int k = popCount - 1; k >= 0; k--) {
            int c = popOrder[k];
            // Strahler: the highest-order child, or one more where the highest order arrives twice.
            region.order[c] = (float) Math.max(1.0, orderMax[c] + (orderCount[c] >= 2.0 ? 1.0 : 0.0));
            int r = receiver[c];
            if (r < 0) continue;
            acc[r] += acc[c];
            double o = region.order[c];
            if (o > orderMax[r]) {
                orderMax[r] = o;
                orderCount[r] = 1.0;
            } else if (o == orderMax[r]) {
                orderCount[r] += 1.0;
            }
        }
        shape(region, acc, s);
    }

    // ------------------------------------------------------------------
    // Deterministic index heap (keyed by filled height, tie-broken by cell index)
    // ------------------------------------------------------------------

    private static boolean less(int a, int b, float[] key) {
        if (key[a] < key[b]) return true;
        if (key[a] > key[b]) return false;
        return a < b;
    }

    private static int heapPush(int[] heap, int size, float[] key, int cell) {
        heap[size] = cell;
        int i = size++;
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (less(heap[p], heap[i], key)) break;
            int t = heap[p];
            heap[p] = heap[i];
            heap[i] = t;
            i = p;
        }
        return size;
    }

    private static int heapPop(int[] heap, int size, float[] key) {
        heap[0] = heap[--size];
        int i = 0;
        while (true) {
            int l = 2 * i + 1, r = l + 1, m = i;
            if (l < size && less(heap[l], heap[m], key)) m = l;
            if (r < size && less(heap[r], heap[m], key)) m = r;
            if (m == i) break;
            int t = heap[i];
            heap[i] = heap[m];
            heap[m] = t;
            i = m;
        }
        return size;
    }
    /**
     * Turns accumulation and basin depth into the published fields. This is where the sliders act,
     * and they act on the hydrology rather than on a noise texture:
     *
     * <ul>
     *   <li>{@code river_density} sets the minimum catchment a channel needs, so it changes how many
     *       networks exist, not how noisy a field looks.</li>
     *   <li>{@code tributary_density} lowers that bar for the small orders only, so it genuinely adds
     *       first- and second-order streams without fattening the trunks.</li>
     *   <li>{@code lake_frequency} sets how shallow a closed basin may be and still hold water.</li>
     *   <li>{@code wetland_frequency} scales the flat, poorly drained ground that becomes marsh.</li>
     * </ul>
     *
     * <p>Channel <em>width</em> is deliberately not decided here: it follows discharge downstream in
     * {@link TerrainModel}, which is the geomorphologically correct place for it, because stream
     * power scales with discharge and discharge is accumulated area - not a slider.
     */
    private static void shape(Region region, double[] acc, TerrainSettings s) {
        final double sea = s.seaLevel();
        final double density = Math.max(0.05, s.riverDensity());
        final double trib = Math.log1p(Math.max(0.0, s.tributaryDensity()));
        final double minArea = CHANNEL_AREA / density;
        final double logMin = Math.log1p(minArea);
        // A closed basin is only a lake if it is genuinely deep AND has a catchment feeding it. The
        // priority-flood raises EVERY depression, including the countless one- and two-block hollows
        // rough terrain is full of, and flooding all of those to their spill point would drown the
        // map. lake_frequency sets the minimum depth a basin must reach, and the accumulated-area term
        // keeps isolated pits (which have no catchment at all) out.
        final double minLakeDepth = 10.0 / Math.max(0.05, s.lakeFrequency());
        // Hard cap on how far above its own floor a basin may be filled. A region-wide bowl whose only
        // spill point is hundreds of blocks up the surrounding range is a valley, not a lake; without
        // this the flood would fill it to the rim and drown the map. With it, a lake is at most this
        // deep and the rest of the bowl stays dry land.
        final double lakeCap = 18.0;
        final double wetFactor = Math.max(0.0, s.wetlandFrequency());
        final double trunkLog = Math.log1p(TRUNK_AREA * density);
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                int c = j * GRID + i;
                double lr = Math.log1p(acc[c]) - logMin;
                region.discharge[c] = (float) clamp(Math.log1p(acc[c]) / trunkLog);
                region.order[c] = (float) clamp(region.order[c] / 7.0);
                if (region.elevation[c] <= sea) {
                    region.river[c] = 0;
                    region.lake[c] = 0;
                    region.surface[c] = (float) sea;
                    region.wetland[c] = 0;
                    continue;
                }
                double channel = sstep(clamp(lr / 0.45));
                // Tributaries: the same signal, taken earlier, and deliberately capped below a trunk
                // so the network keeps its hierarchy instead of becoming a uniform sheet of water.
                double tributary = sstep(clamp((lr + trib) / 0.55)) * 0.8;
                region.river[c] = (float) clamp(Math.max(channel, tributary));
                double depth = region.filled[c] - region.elevation[c];
                double basin = sstep(clamp((depth - minLakeDepth) / Math.max(1.0, minLakeDepth * 0.6)));
                // Rivers and lakes are mutually exclusive: a channel running through a basin drains
                // it. That also bounds the water column, so a channel can never be lifted above its
                // own banks by a lake underneath it.
                region.lake[c] = (float) clamp(basin * sstep(clamp((acc[c] - 12.0) / 40.0)) * (1.0 - region.river[c]));
                // Water surface: the spill elevation, capped. For a column that is not a basin the
                // filled surface IS the ground, so this falls out as "no water here" without a branch.
                region.surface[c] = (float) Math.min(region.filled[c], region.elevation[c] + lakeCap);
                double slope = localSlope(region.elevation, i, j);
                double flat = sstep(clamp(1.0 - slope / 0.02));
                double fed = sstep(clamp(lr / 1.2));
                region.wetland[c] = (float) clamp(flat * fed * wetFactor * (1.0 - region.lake[c]));
            }
        }
    }

    /** Central-difference slope of the elevation grid at a cell, in blocks per block. */
    private static double localSlope(float[] elevation, int i, int j) {
        int im = Math.max(0, i - 1), ip = Math.min(GRID - 1, i + 1);
        int jm = Math.max(0, j - 1), jp = Math.min(GRID - 1, j + 1);
        double dx = (elevation[j * GRID + ip] - elevation[j * GRID + im]) / (double) (2 * CELL);
        double dz = (elevation[jp * GRID + i] - elevation[jm * GRID + i]) / (double) (2 * CELL);
        return Math.sqrt(dx * dx + dz * dz);
    }




    /**
     * Interpolates the region's fields to a world position.
     *
     * <p>Three things are combined here:
     *
     * <ol>
     *   <li><b>Local discharge</b> from the region's own flow accumulation, box-interpolated over the
     *       four surrounding cells so it is continuous across cell borders.</li>
     *   <li><b>A continental border proxy</b> used only near the region rim. See
     *       {@link #borderProxy}. Without it a river would stop dead on a region boundary, because a
     *       region's accumulation counts only the area the region itself contains.</li>
     *   <li><b>Basin and order fields</b>, faded rather than cut at the rim for the same reason: a
     *       hard switch there would put a step in the water surface on every region border.</li>
     * </ol>
     */
    private static Cell sampleRegion(Region r, double x, double z, long seed, TerrainSettings s, int rx, int rz) {
        int originX = rx * REGION - HALO;
        int originZ = rz * REGION - HALO;
        double gx = x / CELL - originX;
        double gz = z / CELL - originZ;
        int i = Math.max(0, Math.min(GRID - 2, (int) Math.floor(gx)));
        int j = Math.max(0, Math.min(GRID - 2, (int) Math.floor(gz)));
        double sx = sp(gx - i), sz = sp(gz - j);

        double localQ = box(r.discharge, i, j, sx, sz);
        double localRiver = box(r.river, i, j, sx, sz);
        double localWet = box(r.wetland, i, j, sx, sz);
        double order = box(r.order, i, j, sx, sz);
        double lake = box(r.lake, i, j, sx, sz);
        double surface = box(r.surface, i, j, sx, sz);

        // Distance to the region rim, in cells, measured from the continuous in-cell position so the
        // blend weight itself cannot step.
        double rimDistance = Math.min(Math.min(gx, REGION - gx), Math.min(gz, REGION - gz));
        if (rimDistance < BORDER_TAPER) {
            double proxy = borderProxy(x, z, seed, s);
            double w = sstep(clamp(rimDistance / BORDER_TAPER)); // 0 exactly on the border, 1 inside
            localQ = proxy * (1.0 - w) + localQ * w;
            localRiver = proxy * (1.0 - w) + localRiver * w;
        }
        double rim = 0.35 + 0.65 * sstep(clamp(Math.min(1.0, rimDistance / BORDER_TAPER)));
        return new Cell(clamp(localQ), clamp(order), clamp(localRiver),
                clamp(lake * rim), surface, clamp(localWet * rim));
    }

    /**
     * A smooth, purely local estimate of river discharge from the cached tectonic fields: high on low
     * ground away from a collision margin, low in the folded ranges.
     *
     * <p>It exists solely so two neighbouring regions agree on the line they share. It is a function
     * of {@code (x, z, seed, settings)} only - never of a region - so both sides evaluate exactly the
     * same number there and the blended field is continuous by construction, even though the interior
     * uses real accumulation, order and basin data.
     */
    private static double borderProxy(double x, double z, long seed, TerrainSettings s) {
        TerrainCache.Node c = TerrainCache.sample(x, z, seed, s);
        double lowland = clamp(1.0 - (TerrainModel.baseHeight(x, z, seed, s) - s.seaLevel()) / 300.0);
        double calm = 1.0 - clamp(c.convergent());
        // A NARROW channel line rather than a broad sheet. The proxy only has to agree with the
        // neighbouring region on the shared line, but it is blended into the terrain over a few cells
        // either side of it, so whatever shape it has is visible there. A smooth sheet reads as a wide
        // shallow lake around every region border; a narrow line reads as a channel, which is what the
        // real network it is standing in for looks like.
        double line = Math.abs(Noise2D.fbm(x / (900.0 / s.riverFrequency()), z / (900.0 / s.riverFrequency()),
                seed + 151, 3, 2.0, .52));
        double channel = 1.0 - sstep(clamp(line / 0.05));
        return channel * lowland * calm * 0.85;
    }

    private static double box(float[] field, int i, int j, double sx, double sz) {
        double a = field[j * GRID + i], b = field[j * GRID + i + 1];
        double c = field[(j + 1) * GRID + i], d = field[(j + 1) * GRID + i + 1];
        double top = a + (b - a) * sx;
        double bot = c + (d - c) * sx;
        return top + (bot - top) * sz;
    }

    /** Quintic S-curve, matching TerrainCache's fade so both lattices share the same smoothness. */
    private static double sp(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static double sstep(double v) {
        double t = clamp(v);
        return t * t * (3 - 2 * t);
    }
}
