package com.cokedoutsnail.realisticterrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A biome source that picks vanilla biomes directly from the same {@link TerrainModel} that
 * builds the terrain, so deserts, forests, tundra and alpine meadows sit where the custom
 * mountains, rivers and climate actually are. The vanilla multi-noise sampler (which the old
 * scaled source used) reads vanilla height/erosion noise that has nothing to do with this
 * terrain, so biomes used to land on the wrong mountains and valleys.
 *
 * <p>The world seed used by {@link TerrainModel} is not exposed to biome sources, so the chunk
 * generator feeds it in through {@link #setTerrainSeed(long)} at populateNoise time (the first
 * thing that runs for every chunk). Before that the source falls back to a fixed salt, which
 * only matters for the brief window before worldgen starts.
 */
public final class TerrainBiomeSource extends BiomeSource {
    public static final MapCodec<TerrainBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("fallback").forGetter(TerrainBiomeSource::fallback),
            Codec.floatRange(0.25F, 4.0F).optionalFieldOf("scale", 1.0F).forGetter(TerrainBiomeSource::scale),
            TerrainSettings.CODEC.fieldOf("settings").forGetter(TerrainBiomeSource::settings)
    ).apply(instance, TerrainBiomeSource::new));

    private final BiomeSource fallback;
    private final float scale;
    private final TerrainSettings settings;
    private final Map<Identifier, RegistryEntry<Biome>> byId = new HashMap<>();
    private RegistryEntry<Biome> defaultBiome;
    private long terrainSeed = 0x5245414C49535449L; // transient; overwritten by the chunk generator

    public TerrainBiomeSource(BiomeSource fallback, float scale, TerrainSettings settings) {
        this.fallback = fallback;
        this.scale = scale;
        this.settings = settings;
        for (RegistryEntry<Biome> entry : fallback.getBiomes()) {
            entry.getKey().ifPresent(key -> byId.put(key.getValue(), entry));
            if (defaultBiome == null) defaultBiome = entry;
        }
        if (defaultBiome == null) throw new IllegalArgumentException("Fallback biome source produced no biomes");
    }

    public BiomeSource fallback() {
        return fallback;
    }

    public float scale() {
        return scale;
    }

    public TerrainSettings settings() {
        return settings;
    }

    public TerrainBiomeSource withScale(float newScale) {
        return new TerrainBiomeSource(fallback, newScale, settings);
    }

    /** New instance with updated terrain settings; biome scale follows the settings' biome_scale. */
    public TerrainBiomeSource withSettings(TerrainSettings newSettings) {
        return new TerrainBiomeSource(fallback, newSettings.biomeScale(), newSettings);
    }

    public void setTerrainSeed(long seed) {
        this.terrainSeed = seed;
    }

    @Override
    protected MapCodec<? extends BiomeSource> getCodec() {
        return CODEC;
    }

    @Override
    protected Stream<RegistryEntry<Biome>> biomeStream() {
        return fallback.getBiomes().stream();
    }

    @Override
    public RegistryEntry<Biome> getBiome(int biomeX, int biomeY, int biomeZ, MultiNoiseUtil.MultiNoiseSampler sampler) {
        double x = biomeX * 4.0 / scale;
        double z = biomeZ * 4.0 / scale;
        return pick(TerrainModel.sample(terrainSeed, x, z, settings));
    }

    private RegistryEntry<Biome> pick(TerrainModel.Sample s) {
        double h = s.height();
        double water = s.waterLevel();
        double moisture = s.moisture();
        double temperature = s.temperature();

        // Water, depth ordered: rivers/lakes first, then open water by depth.
        if (h < water) {
            if (s.river() > 0.25 || s.lake() > 0.3) return biome(BiomeKeys.RIVER);
            double depth = water - h;
            if (depth > 26) return biome(BiomeKeys.DEEP_OCEAN);
            if (depth > 4) return biome(BiomeKeys.OCEAN);
            return biome(BiomeKeys.BEACH);
        }
        // Coastal fringe.
        if (h < settings.seaLevel() + 3) return biome(BiomeKeys.BEACH);

        boolean cold = temperature < -0.2;
        boolean hot = temperature > 0.25;
        boolean wet = moisture > 0.15;
        boolean dry = moisture < -0.1;

        // Altitude bands: tundra, alpine scrub and tree line.
        if (h > settings.snowLine() + 130) return biome(BiomeKeys.SNOWY_SLOPES);
        if (h > settings.snowLine() + 30) return cold ? biome(BiomeKeys.SNOWY_SLOPES) : biome(BiomeKeys.MEADOW);
        if (h > settings.snowLine() - 60) return cold ? biome(BiomeKeys.GROVE) : biome(BiomeKeys.MEADOW);

        // Lowland climate zones.
        if (cold) return wet ? biome(BiomeKeys.TAIGA) : biome(BiomeKeys.SNOWY_PLAINS);
        if (hot) {
            if (wet) return biome(BiomeKeys.JUNGLE);
            if (dry) return biome(BiomeKeys.DESERT);
            return biome(BiomeKeys.SAVANNA);
        }
        if (wet) return moisture > 0.3 ? biome(BiomeKeys.DARK_FOREST) : biome(BiomeKeys.FOREST);
        if (dry) return biome(BiomeKeys.PLAINS);
        return biome(BiomeKeys.BIRCH_FOREST);
    }

    private RegistryEntry<Biome> biome(RegistryKey<Biome> key) {
        RegistryEntry<Biome> entry = byId.get(key.getValue());
        return entry != null ? entry : defaultBiome;
    }
}