package com.cokedoutsnail.realisticterrain.noise;

/**
 * Deterministic, allocation-free noise primitives for worldgen and the live preview.
 *
 * Uses gradient (Perlin-style) noise rather than value noise: dot products of pseudo-random
 * gradient vectors with the sample offset produce visibly less "blobby"/grid-aligned detail than
 * interpolating random lattice values directly, at the same cost per lattice corner. Gradients are
 * derived purely from integer hashing (no permutation table) so every call stays allocation-free -
 * this runs per-block, many times per column, so it has to stay cheap even though the terrain model
 * built on top of it is intentionally layered and heavy.
 */
public final class Noise2D {
    private Noise2D() {}

    private static long mix(long x) { x ^= x >>> 33; x *= 0xff51afd7ed558ccdl; x ^= x >>> 33; x *= 0xc4ceb9fe1a85ec53l; return x ^ (x >>> 33); }
    private static double fade(double t){ return t*t*t*(t*(t*6-15)+10); }
    private static double lerp(double a,double b,double t){ return a+(b-a)*t; }

    // Eight unit-ish gradient directions selected by hash, matching the classic "improved noise"
    // reduced gradient set - avoids a permutation table while still giving every lattice corner an
    // independent pseudo-random direction.
    private static double grad(int ix, int iz, long seed, double dx, double dz) {
        long h = mix(seed ^ (ix * 0x9E3779B97F4A7C15L) ^ (iz * 0xC2B2AE3D27D4EB4FL));
        switch ((int) (h & 7)) {
            case 0: return dx + dz;
            case 1: return -dx + dz;
            case 2: return dx - dz;
            case 3: return -dx - dz;
            case 4: return dx * 2;
            case 5: return -dx * 2;
            case 6: return dz * 2;
            default: return -dz * 2;
        }
    }

    /** Gradient noise in roughly [-1, 1]. */
    public static double value(double x, double z, long seed) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double dx = x - x0, dz = z - z0;
        double u = fade(dx), v = fade(dz);
        double n00 = grad(x0, z0, seed, dx, dz);
        double n10 = grad(x0 + 1, z0, seed, dx - 1, dz);
        double n01 = grad(x0, z0 + 1, seed, dx, dz - 1);
        double n11 = grad(x0 + 1, z0 + 1, seed, dx - 1, dz - 1);
        double nx0 = lerp(n00, n10, u);
        double nx1 = lerp(n01, n11, u);
        // Empirically-fit normalizer (measured raw peak ~1.07) keeps output within [-1, 1].
        return lerp(nx0, nx1, v) * 0.88;
    }

    public static double fbm(double x, double z, long seed, int oct, double lac, double gain) {
        double a=.5,f=1,s=0,n=0;
        for(int i=0;i<oct;i++){ s+=value(x*f,z*f,seed+i*1013L)*a; n+=a; f*=lac; a*=gain; }
        return s/n;
    }

    /**
     * A single ridge layer: sharp peaks, rounded valleys, in [0, 1]. The underlying noise's typical
     * magnitude is well under its rare peak (a normal property of coherent noise), so a flat
     * `1 - abs(value)` would read as "high" almost everywhere and give mountains no real contrast
     * between ridge and valley. Stretching abs(value) before subtracting fixes that: valleys reach
     * genuinely near 0 and only true peaks approach 1.
     */
    public static double ridge(double x, double z, long seed) {
        return clamp01(1.0 - Math.abs(value(x, z, seed)) * 1.9);
    }

    /**
     * Erosion-aware ridged multifractal: each octave's contribution is weighted by how strong the
     * previous octave already was, so sharp ridges keep accumulating fine detail while the noise
     * between them (valleys) is damped rather than getting the same busy detail as the peaks. This
     * is what actually gives mountains a "sharp ridge, smooth flank" silhouette instead of uniform
     * noisy roughness everywhere - a cheap stand-in for real hydraulic erosion.
     */
    public static double erodedRidge(double x, double z, long seed, int oct, double lac, double gain) {
        double freqX = x, freqZ = z;
        double sum = 0, amplitude = 1, range = 0, weight = 1;
        for (int i = 0; i < oct; i++) {
            double signal = ridge(freqX, freqZ, seed + i * 1013L);
            signal = signal * signal;
            signal *= weight;
            weight = clamp01(signal * 2.1);
            sum += signal * amplitude;
            range += amplitude;
            freqX *= lac; freqZ *= lac;
            amplitude *= gain;
        }
        return range > 0 ? sum / range : 0;
    }

    /** Warps (x, z) by its own noise field, producing organic, non-grid-aligned large-scale flow. */
    public static double[] warp(double x, double z, long seed, double frequency, double strength) {
        double wx = value(x * frequency, z * frequency, seed) * strength;
        double wz = value(x * frequency + 91.7, z * frequency - 41.3, seed + 977) * strength;
        return new double[] { x + wx, z + wz };
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
