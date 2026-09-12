package com.cokedoutsnail.realisticterrain.worldgen.hydro;

/**
 * Turns accumulated discharge into the geometry the carve needs: a channel cross-section, a
 * monotone water-surface profile, closed-basin lakes and floodplain wetlands.
 *
 * <p>Everything here is a bounded function of the accumulated runoff and the local slope, which is
 * what stops every headwater becoming a fifty-block river: width grows with {@code sqrt(discharge)}
 * and depth with {@code log1p(discharge)}, and both are clamped.
 *
 * <p>The water-surface pass is the load-bearing part. It walks the pop order (outlet first) and
 * clamps each channel cell's surface down to its downstream neighbour's, so the published profile is
 * guaranteed non-increasing downstream by construction rather than by luck. The bed is then placed
 * strictly below it, and the bank strictly above it.
 */
public final class RiverNetwork {
    private RiverNetwork() {
    }

    /** Accumulated runoff (cells) needed for the smallest channel at {@code riverDensity = 1}. */
    public static final double CHANNEL_AREA = 26.0;
    /** Accumulated runoff that counts as a full-size trunk river. */
    public static final double TRUNK_AREA = 900.0;
    /** Fraction of the channel depth filled with water (the rest is freeboard to the bank). */
    public static final double WATER_FILL = 0.70;
    /** Depth, in blocks, at or above which a filled depression is treated as a lake. */
    public static final double MIN_LAKE_DEPTH = 3.0;
    /** Hard cap on a lake's water column, in blocks. */
    public static final double LAKE_CAP = 24.0;

    /** Result of the shaping pass, indexed by grid cell. */
    public static final class Shaped {
        public final float[] river;
        public final float[] lake;
        public final float[] wetland;
        public final float[] waterSurface;
        public final float[] bed;
        public final float[] width;
        public final float[] depth;
        public final float[] dischargeNorm;
        public final float[] orderNorm;
        public final float[] flowX;
        public final float[] flowZ;

        public Shaped(int n) {
            river = new float[n];
            lake = new float[n];
            wetland = new float[n];
            waterSurface = new float[n];
            bed = new float[n];
            width = new float[n];
            depth = new float[n];
            dischargeNorm = new float[n];
            orderNorm = new float[n];
            flowX = new float[n];
            flowZ = new float[n];
        }
    }

    /** Everything the shaper needs that comes from the settings sliders. */
    public record HydroShapeParams(
            boolean riversEnabled,
            boolean lakesEnabled,
            boolean wetlandsEnabled,
            double cellSize,
            double riverDensity,
            double widthScale,
            double depthScale,
            double maxWidth,
            double maxDepth,
            double wetlandFrequency,
            double tributaryDensity
    ) {
    }

    /**
     * @param grid         side length
     * @param elevation    true (pre-carve) ground
     * @param filled       depression-filled surface
     * @param accumulation runoff-weighted catchment
     * @param strahler     Strahler order
     * @param receiver     D8 receiver, {@code -1} for outlets
     * @param popOrder     downstream-first pop order
     * @param popCount     number of valid entries in {@code popOrder}
     */
    public static Shaped shape(int grid, float[] elevation, float[] filled, double[] accumulation,
            int[] strahler, int[] receiver, int[] popOrder, int popCount, HydroShapeParams p) {
        final int n = grid * grid;
        Shaped out = new Shaped(n);
        final double channelArea = CHANNEL_AREA
                / Math.max(0.05, p.riverDensity * p.tributaryDensity);
        final double logSpan = Math.log1p(TRUNK_AREA / CHANNEL_AREA);

        for (int c = 0; c < n; c++) {
            double q = accumulation[c];
            out.dischargeNorm[c] = (float) clamp(Math.log1p(q / CHANNEL_AREA) / logSpan);
            out.orderNorm[c] = (float) clamp((strahler[c] - 1) / 6.0);
            float[] dir = unitDirection(grid, receiver, c);
            out.flowX[c] = dir[0];
            out.flowZ[c] = dir[1];

            if (p.riversEnabled && q >= channelArea) {
                double widthBlocks = (0.35 + 0.55 * Math.sqrt(q / CHANNEL_AREA)) * p.cellSize * p.widthScale;
                double depthBlocks = (0.35 + 0.55 * Math.log1p(q / CHANNEL_AREA)) * p.depthScale;
                out.width[c] = (float) clamp(widthBlocks, 1.0, p.maxWidth);
                out.depth[c] = (float) clamp(depthBlocks, 0.5, p.maxDepth);
                double strength = sstep(clamp((out.dischargeNorm[c] - 0.05) / 0.45));
                out.river[c] = (float) clamp(strength);
                out.bed[c] = (float) (filled[c] - out.depth[c]);
                out.waterSurface[c] = (float) (filled[c] - out.depth[c] * (1.0 - WATER_FILL));
            } else {
                out.river[c] = 0f;
                out.bed[c] = elevation[c];
                out.waterSurface[c] = Float.NEGATIVE_INFINITY;
            }
        }

        // Monotone water profile: outlet-first, so the downstream neighbour is already final. The
        // surface may only fall downstream; if the geometry wants to raise it, the bed is deepened
        // instead so the column still has water in it.
        for (int k = 0; k < popCount; k++) {
            int c = popOrder[k];
            if (out.river[c] <= 0f) continue;
            int r = receiver[c];
            if (r < 0 || out.river[r] <= 0f) continue;
            if (out.waterSurface[c] > out.waterSurface[r]) {
                out.waterSurface[c] = out.waterSurface[r];
            }
            double floor = out.bed[c] + 0.2 * out.depth[c];
            if (out.waterSurface[c] < floor) {
                out.waterSurface[c] = (float) floor;
                out.bed[c] = (float) (floor - 0.2 * out.depth[c]);
            }
        }

        // Lakes: a genuine closed basin is where the filled surface stands above the true ground.
        for (int c = 0; c < n; c++) {
            double basinDepth = filled[c] - elevation[c];
            if (!p.lakesEnabled || basinDepth < MIN_LAKE_DEPTH || accumulation[c] < 12.0) {
                continue;
            }
            double basin = sstep(clamp((basinDepth - MIN_LAKE_DEPTH) / Math.max(1.0, MIN_LAKE_DEPTH * 0.6)));
            double fed = sstep(clamp((accumulation[c] - 12.0) / 40.0));
            // Rivers and lakes are mutually exclusive: a channel through a basin drains it, which
            // also bounds the water column so a river can never be lifted by a lake beneath it.
            out.lake[c] = (float) clamp(basin * fed * (1.0 - out.river[c]));
            if (out.lake[c] > 0f && out.waterSurface[c] == Float.NEGATIVE_INFINITY) {
                out.waterSurface[c] = (float) Math.min(filled[c], elevation[c] + LAKE_CAP);
                out.bed[c] = elevation[c];
            }
        }

        // Wetlands: flat, fed, and not already water.
        for (int j = 0; j < grid; j++) {
            for (int i = 0; i < grid; i++) {
                int c = j * grid + i;
                if (out.lake[c] > 0f || out.river[c] > 0f) continue;
                double slope = localSlope(elevation, grid, i, j, p.cellSize);
                double flat = sstep(clamp(1.0 - slope / 0.02));
                double fed = sstep(clamp((out.dischargeNorm[c] - 0.02) / 0.25));
                double wet = p.wetlandsEnabled ? flat * fed * p.wetlandFrequency : 0.0;
                out.wetland[c] = (float) clamp(wet);
                if (out.wetland[c] > 0.5) {
                    // A wetland film sits essentially at grade, just below the bank, so the
                    // generator can never lift it above its own shore.
                    out.waterSurface[c] = elevation[c] - 0.15f;
                }
            }
        }
        return out;
    }

    /** Unit vector pointing from a cell towards its receiver (zero at an outlet). */
    private static float[] unitDirection(int grid, int[] receiver, int c) {
        int r = receiver[c];
        if (r < 0) return new float[]{0f, 0f};
        int ci = c % grid;
        int cj = c / grid;
        double dx = (r % grid) - ci;
        double dz = (r / grid) - cj;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len <= 0) return new float[]{0f, 0f};
        return new float[]{(float) (dx / len), (float) (dz / len)};
    }

    private static double localSlope(float[] elevation, int grid, int i, int j, double cellSize) {
        int im = Math.max(0, i - 1);
        int ip = Math.min(grid - 1, i + 1);
        int jm = Math.max(0, j - 1);
        int jp = Math.min(grid - 1, j + 1);
        double dx = (elevation[j * grid + ip] - elevation[j * grid + im]) / (2.0 * cellSize);
        double dz = (elevation[jp * grid + i] - elevation[jm * grid + i]) / (2.0 * cellSize);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double sstep(double v) {
        double t = clamp(v);
        return t * t * (3 - 2 * t);
    }
}
