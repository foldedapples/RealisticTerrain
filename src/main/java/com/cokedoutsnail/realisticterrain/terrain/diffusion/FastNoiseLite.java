package com.cokedoutsnail.realisticterrain.terrain.diffusion;

/**
 * Minimal FastNoiseLite implementation for synthetic map generation.
 * Uses the same interface as terrain-diffusion-mc's FastNoiseLite.
 *
 * <p>Adapted from terrain-diffusion-mc FastNoiseLite (MIT license)
 * Original copyright: Copyright (c) 2024 xandergos
 */
public class FastNoiseLite {
    public enum NoiseType { Perlin }
    public enum FractalType { FBm }

    @SuppressWarnings("unused")
    private NoiseType noiseType = NoiseType.Perlin;
    private FractalType fractalType = FractalType.FBm;
    private float frequency = 0.01f;
    private int octaves = 3;
    private float lacunarity = 2.0f;
    private float gain = 0.5f;
    @SuppressWarnings("unused")
    private final int seed;
    private final long seed64;

    public FastNoiseLite(int seed) {
        this.seed = seed;
        this.seed64 = seed & 0xFFFFFFFFL;
    }

    public void SetNoiseType(NoiseType type) { this.noiseType = type; }
    public void SetFrequency(float freq) { this.frequency = freq; }
    public void SetFractalType(FractalType type) { this.fractalType = type; }
    public void SetFractalOctaves(int oct) { this.octaves = oct; }
    public void SetFractalLacunarity(float lac) { this.lacunarity = lac; }
    public void SetFractalGain(float g) { this.gain = g; }

    private static double grad(int hash, double x, double z) {
        int h = hash & 3;
        double u = (h < 2) ? x : z;
        double v = (h < 2) ? z : x;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    private double perlin(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double tx = x - x0, tz = z - z0;
        double sx = tx * tx * tx * (tx * (tx * 6 - 15) + 10);
        double sz = tz * tz * tz * (tz * (tz * 6 - 15) + 10);
        long seedHash = seed64;
        long h0 = hash(x0, z0, seedHash);
        long h1 = hash(x0 + 1, z0, seedHash);
        long h2 = hash(x0, z0 + 1, seedHash);
        long h3 = hash(x0 + 1, z0 + 1, seedHash);
        double n0 = grad((int)h0, tx, tz);
        double n1 = grad((int)h1, tx - 1, tz);
        double n2 = grad((int)h2, tx, tz - 1);
        double n3 = grad((int)h3, tx - 1, tz - 1);
        double nx0 = n0 + sx * (n1 - n0);
        double nx1 = n2 + sx * (n3 - n2);
        return nx0 + sz * (nx1 - nx0);
    }

    private static long hash(int x, int z, long seed) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        return h ^ (h >>> 33);
    }

    private double fBm(double x, double z) {
        double value = 0, amplitude = 1, maxVal = 0, freq = frequency;
        for (int i = 0; i < octaves; i++) {
            value += perlin(x * freq, z * freq) * amplitude;
            maxVal += amplitude;
            amplitude *= gain;
            freq *= lacunarity;
        }
        return value / maxVal;
    }

    public float GetNoise(double x, double z) {
        if (fractalType == FractalType.FBm) {
            return (float) fBm(x, z);
        }
        return (float) perlin(x * frequency, z * frequency);
    }
}