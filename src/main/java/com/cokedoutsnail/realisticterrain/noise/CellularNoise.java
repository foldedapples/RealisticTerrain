package com.cokedoutsnail.realisticterrain.noise;

/**
 * Deterministic integer-lattice cellular (Voronoi) noise in the style of FastNoiseLite's
 * cellular mode. Each lattice cell gets a random "plate seed" and the field resolves to the
 * nearest seed, which yields flat polygonal regions with crisp edges. The {@code boundary}
 * value measures how close a point is to a cell edge (≈1 at a seed centre, ≈0 exactly on the
 * shared border), and each border pair is deterministically classified as either convergent
 * (a colliding margin - fold belts) or divergent (a separating margin - rift valleys/trenches).
 *
 * This is the tectonic backbone of the terrain: collision margins raise orogeny, divergence
 * carves rifts and ocean trenches.
 */
public final class CellularNoise {
    private CellularNoise() {}

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

    /** One sample of the cellular field. */
    public record Cell(
            double scalar,    // 0..1 plate identity of the nearest seed
            double boundary,  // 0..1 distance from the nearest margin (1 = plate interior)
            boolean convergent // whether this margin converges (collision) or diverges (rift)
    ) {}

    public static Cell sample(double x, double z, long seed) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double fx = x - xi, fz = z - zi;
        long bestId = -1, secondId = -1;
        double bestD = Double.POSITIVE_INFINITY, secondD = Double.POSITIVE_INFINITY;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = xi + dx, cz = zi + dz;
                long h = hash01(cx, cz, seed);
                long h2 = hash01(cx, cz, seed ^ 0xA5A5A5A5A5A5A5A5L);
                double ox = (double) (h & 0xFFFFFFFFL) / 4294967296.0 - 0.5;
                double oz = (double) (h2 & 0xFFFFFFFFL) / 4294967296.0 - 0.5;
                double ddx = (dx + ox) - fx, ddz = (dz + oz) - fz;
                double d = ddx * ddx + ddz * ddz;
                if (d < bestD) {
                    secondD = bestD;
                    secondId = bestId;
                    bestD = d;
                    bestId = h ^ 0x123456789abcdefL;
                } else if (d < secondD) {
                    secondD = d;
                    secondId = h ^ 0x123456789abcdefL;
                }
            }
        }
        double boundary = (secondD - bestD) / (secondD + 1e-12);
        boundary = Math.min(1.0, boundary * 1.7);
        double scalar = (double) (bestId & 0xFFFFFFFFL) / 4294967296.0;
        // A margin's tectonic sense is a deterministic hash of the two plate ids on either side.
        boolean convergent = (((secondId ^ bestId) & 0x7FFFFFFFL) >>> 31) == 0;
        return new Cell(scalar, boundary, convergent);
    }

    public static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}