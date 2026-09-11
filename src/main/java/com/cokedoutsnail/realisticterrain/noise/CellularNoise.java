package com.cokedoutsnail.realisticterrain.noise;

/**
 * Deterministic integer-lattice cellular (Voronoi) noise, in the style of FastNoiseLite's
 * cellular mode and of ReTerraForged's {@code world.worldgen.noise.module.Worley}.
 *
 * <p>Each lattice cell gets a random "plate seed" and the field resolves to the nearest seed,
 * which yields flat polygonal regions. Three details are what keep that polygon lattice from
 * showing up as a repeating diamond/facet grid on the surface:
 *
 * <ol>
 *   <li><b>Full cell jitter.</b> The seed lives anywhere inside its own cell (ReTerraForged's
 *       {@code Worley} places it at {@code cx + vec.x() * distance} with {@code vec.x() ∈ [0,1)}),
 *       so no seed ever lines up with the integer lattice and no plate edge is axis-aligned.</li>
 *   <li><b>A pluggable distance metric.</b> {@link DistanceFunction} (ported from ReTerraForged)
 *       decides the outline of a plate - rounded polygon, diamond or a rounded diamond - without
 *       touching any noise frequency.</li>
 *   <li><b>Blended cell values.</b> A value that is constant per plate is a <em>step function</em>
 *       in space: it jumps at every margin. ReTerraForged never feeds the raw nearest-node value
 *       into anything that shapes height; it blends. Here the plate trait is an inverse-distance
 *       blend of the two nearest seeds, so it is continuous everywhere while still varying from
 *       plate to plate. That is what removes the hard amplitude jumps on plate boundaries.</li>
 * </ol>
 *
 * <p>The {@code boundary} value measures how close a point is to a cell edge (≈1 at a seed centre,
 * ≈0 exactly on the shared border). The metric below is deliberately unit-free: this class works in
 * <em>lattice</em> space and knows nothing about blocks, so anything that needs world space (domain
 * warping, the colliding/rifting decision for a margin) is done by the caller - see
 * {@code TerrainCache}, not here.
 *
 * <p>This is the tectonic backbone of the terrain: callers use the margin distance to raise orogeny
 * along collision margins and to carve rifts and ocean trenches along divergent ones.
 */
public final class CellularNoise {
    private CellularNoise() {}

    /**
     * Distance metric for the cellular lookup, ported from ReTerraForged's
     * {@code world.worldgen.noise.function.DistanceFunction}. The metric shapes a plate's outline:
     * {@link #EUCLIDEAN} gives rounded polygons, {@link #MANHATTAN} straight-edged diamonds and
     * {@link #NATURAL} a rounded diamond in between. Only the plate silhouette changes, never the
     * lattice frequency, so this can break up accidental grid alignment without retuning any noise
     * scale.
     */
    public enum DistanceFunction {
        /** {@code x² + z²} - the classic round-celled Voronoi. */
        EUCLIDEAN {
            @Override public double apply(double x, double z) { return x * x + z * z; }
        },
        /** {@code |x| + |z|} - straight-edged, diamond-shaped plates. */
        MANHATTAN {
            @Override public double apply(double x, double z) { return Math.abs(x) + Math.abs(z); }
        },
        /** {@code |x| + |z| + x² + z²} - a rounded diamond (ReTerraForged's "natural" shape). */
        NATURAL {
            @Override public double apply(double x, double z) {
                return Math.abs(x) + Math.abs(z) + x * x + z * z;
            }
        };

        public abstract double apply(double x, double z);
    }

    /** The plate silhouette used by the terrain engine. */
    public static final DistanceFunction DEFAULT_SHAPE = DistanceFunction.EUCLIDEAN;

    /**
     * Cell-centre jitter: 0 puts every seed on the lattice point (a visible square grid) and 1 puts
     * it anywhere inside its own cell (ReTerraForged's fully random Worley). Full jitter is what
     * removes the grid.
     */
    private static final double JITTER = 1.0;

    /**
     * How much of the F2-F1 separation counts as "plate interior". The edge measure is rescaled by
     * this before clamping so margins have a sensible width relative to the plate size.
     */
    private static final double EDGE_WIDTH = 1.7;

    /**
     * Sharpness of the plate-trait blend kernel (see {@link #sample}). Higher keeps each plate's
     * trait closer to its own value; lower spreads neighbouring plates further across a border.
     */
    private static final double TRAIT_SHARPNESS = 60.0;

    private static long mix(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        return x ^ (x >>> 33);
    }

    private static long hash01(int x, int z, long seed) {
        return mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL));
    }

    /** Uniform 0..1 drawn from the high 53 bits, so the result is exactly representable. */
    private static double unit(long h) {
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    /**
     * One sample of the cellular field.
     *
     * @param scalar   0..1 plate trait, blended across the 3x3 neighbourhood so it is continuous
     * @param boundary 0..1 distance from the nearest margin (1 = plate interior, 0 = on a border)
     */
    public record Cell(
            double scalar,
            double boundary
    ) {}

    public static Cell sample(double x, double z, long seed) {
        return sample(x, z, seed, DEFAULT_SHAPE);
    }

    public static Cell sample(double x, double z, long seed, DistanceFunction shape) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double fx = x - xi, fz = z - zi;
        double bestD = Double.POSITIVE_INFINITY, secondD = Double.POSITIVE_INFINITY;
        double weightSum = 0, traitSum = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = xi + dx, cz = zi + dz;
                long h = hash01(cx, cz, seed);
                long h2 = hash01(cx, cz, seed ^ 0xA5A5A5A5A5A5A5A5L);
                // ReTerraForged keeps a seed strictly inside its own cell: the offset is in
                // [0, JITTER) measured from the cell corner, not centred on it.
                double ox = unit(h) * JITTER;
                double oz = unit(h2) * JITTER;
                double ddx = (dx + ox) - fx, ddz = (dz + oz) - fz;
                double d = shape.apply(ddx, ddz);
                long id = mix(h ^ 0x123456789ABCDEFL);
                if (d < bestD) {
                    secondD = bestD;
                    bestD = d;
                } else if (d < secondD) {
                    secondD = d;
                }
                // Smooth radial blend over the whole 3x3 neighbourhood. Every term is a continuous
                // function of position, so the trait cannot jump anywhere - not on a plate border,
                // and not on the ray out of a triple junction where the second-nearest changes.
                double w = 1.0 / (1.0 + TRAIT_SHARPNESS * d);
                weightSum += w;
                traitSum += w * unit(id);
            }
        }

        // F2 - F1 separation, normalised: 0 exactly on a border, 1 at a seed.
        double boundary = (secondD - bestD) / (secondD + 1e-12);
        boundary = Math.min(1.0, boundary * EDGE_WIDTH);

        // Plate trait: a smooth radial average of every neighbouring plate's own value, weighted
        // 1/(1 + k·d). A raw nearest-node value is a step function across every margin - a few
        // hundred blocks of orogeny amplitude inside one cache cell - and merely blending the two
        // nearest still steps on the ray out of a triple junction, where the second-nearest plate
        // changes. Summing the whole neighbourhood is continuous in position by construction, so
        // neither cliff can return, while the weighting still keeps each plate's own character
        // across its interior.
        double trait = traitSum / weightSum;

        return new Cell(trait, boundary);
    }

    public static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
