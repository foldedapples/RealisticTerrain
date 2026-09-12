package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.RealisticTerrainMod;
import net.minecraft.world.gen.noise.NoiseConfig;

/**
 * The generator-owned terrain seed, established once per world.
 *
 * <p>This replaces the old design where the chunk generator pushed a raw {@code long} into a mutable
 * field on the biome source from inside {@code populateNoise}. That was wrong twice over: Minecraft
 * populates a chunk's biomes in the separate, earlier {@code BIOMES} stage (not from
 * {@code populateNoise}), so biomes were frequently sampled before the seed arrived and fell back to
 * a hard-coded salt; and the mutation was unsynchronised, so parallel generation could observe the
 * fallback.
 *
 * <p>The context is write-once and derived purely from the world's {@link NoiseConfig}
 * ({@link #derive(NoiseConfig)}), so every thread and every generation order computes the identical
 * value. It is initialized from the world-seed hook
 * {@code RealisticChunkGenerator#createStructurePlacementCalculator}, which Minecraft calls once
 * when the world's chunk manager is built - before any terrain or biome work - and again defensively
 * from {@code populateBiomes}/{@code populateNoise}. It is never a global, and it never falls back
 * to a constant seed for chunk generation: reading an uninitialized context is an error.
 */
public final class TerrainContext {
    private static final long UNSET = Long.MIN_VALUE;
    private volatile long seed = UNSET;

    /** True once a world seed has been derived and stored. */
    public boolean isInitialized() {
        return seed != UNSET;
    }

    /**
     * Derives the terrain seed from a world's noise configuration. Deterministic per world seed, and
     * identical no matter which stage calls it, which is what keeps terrain and biomes aligned.
     */
    public static long derive(NoiseConfig noiseConfig) {
        return noiseConfig.getOrCreateRandomDeriver(RealisticTerrainMod.id("terrain"))
                .split(0L).nextLong();
    }

    /** Derives and stores the seed from the given noise configuration; returns it. */
    public long initializeFrom(NoiseConfig noiseConfig) {
        initialize(derive(noiseConfig));
        return seed;
    }

    /**
     * Write-once initialization. Concurrent callers race harmlessly because every caller passes the
     * same derived value; the first write wins and later writes are ignored.
     */
    public void initialize(long value) {
        if (seed == UNSET) {
            seed = value;
        }
    }

    /** The terrain seed. Throws if this context was never initialized, rather than guessing. */
    public long seed() {
        long s = seed;
        if (s == UNSET) {
            throw new IllegalStateException(
                    "TerrainContext is uninitialized: biomes and terrain must be seeded from the "
                            + "world's NoiseConfig before either is sampled");
        }
        return s;
    }
}
