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
    // Built lazily by ensureBiomeLookup() - NOT in the constructor. See that method for why.
    private Map<Identifier, RegistryEntry<Biome>> byId;
    private RegistryEntry<Biome> defaultBiome;
    private volatile boolean biomeLookupReady;
    private long terrainSeed = 0x5245414C49535449L; // transient; overwritten by the chunk generator

    public TerrainBiomeSource(BiomeSource fallback, float scale, TerrainSettings settings) {
        this.fallback = fallback;
        this.scale = scale;
        this.settings = settings;
    }

    /**
     * Resolves the identifier -> biome lookup from the fallback source, once, on first use.
     *
     * <p>This must not run from the constructor. The datapack registry loader decodes the world
     * preset while the {@code multi_noise_biome_source_parameter_list} registry is still unbound,
     * and {@link BiomeSource#getBiomes()} on a preset-backed fallback resolves
     * {@code minecraft:overworld} immediately - throwing
     * {@code IllegalStateException: Trying to access unbound value ...} and aborting the entire
     * registry load (which is what froze the client on "Preparing for world creation..."). By
     * world-generation time the registries are bound, so the first real {@link #getBiome} call
     * builds the lookup safely instead.
     */
    private synchronized void ensureBiomeLookup() {
        if (biomeLookupReady) return;
        Map<Identifier, RegistryEntry<Biome>> map = new HashMap<>();
        RegistryEntry<Biome> fallbackDefault = null;
        for (RegistryEntry<Biome> entry : fallback.getBiomes()) {
            entry.getKey().ifPresent(key -> map.put(key.getValue(), entry));
            if (fallbackDefault == null) fallbackDefault = entry;
        }
        if (fallbackDefault == null) throw new IllegalStateException("Fallback biome source produced no biomes");
        this.byId = map;
        this.defaultBiome = fallbackDefault;
        this.biomeLookupReady = true;
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
        ensureBiomeLookup();
        double x = biomeX * 4.0 / scale;
        double z = biomeZ * 4.0 / scale;
        return pick(x, z, TerrainModel.sample(terrainSeed, x, z, settings));
    }

    private RegistryEntry<Biome> pick(double x, double z, TerrainModel.Sample s) {
        double h = s.height();
        double water = s.waterLevel();
        double m = s.moisture();
        double t = s.temperature();

        // Water, depth ordered: rivers/lakes first, then open water by depth.
        if (h < water) {
            if (s.river() > 0.25 || s.lake() > 0.3) return biome(BiomeKeys.RIVER);
            double depth = water - h;
            // Mid-ocean ridges and rift trenches stay abyssal even when shallow.
            if (depth > 26 || (s.divergent() > 0.6 && depth > 10)) return biome(BiomeKeys.DEEP_OCEAN);
            if (depth > 4) return biome(BiomeKeys.OCEAN);
            return biome(BiomeKeys.BEACH);
        }
        // Coastal fringe. Gating this on raw height alone - "within N blocks of sea level" - turned
        // every inland flat that happened to sit near sea level (a wide plain, the floor of a rift
        // valley, the apron below a range) into beach as well, because at these terrain scales a
        // great deal of ordinary low ground sits within a few blocks of sea level without being
        // anywhere near the coast. Continentalness is the field the coastline is actually drawn
        // from (see TerrainModel#tectonicBase), so gating on genuine proximity to the coast line in
        // THAT field - not the derived height - is what confines beach to the coast itself, the way
        // ReTerraForged's COAST control point does.
        boolean nearCoastline = s.continent() - settings.coastLine() < 0.05;
        if (nearCoastline && h < settings.seaLevel() + 5) return biome(BiomeKeys.BEACH);

        // Tectonic refinements.
        boolean riftValley = s.divergent() > 0.55 && h < settings.seaLevel() + 70; // damp rift corridor
        boolean faulted = s.fault() > 0.55; // rocky, harsh active zone

        boolean cold = t < -0.2;
        boolean hot = t > 0.25;
        boolean wet = m > 0.15;
        boolean dry = m < -0.1;

        // Altitude bands: tundra, alpine scrub and tree line.
        if (h > settings.snowLine() + 130) return biome(BiomeKeys.SNOWY_SLOPES);
        if (h > settings.snowLine() + 30) return cold ? biome(BiomeKeys.SNOWY_SLOPES) : biome(BiomeKeys.MEADOW);
        if (h > settings.snowLine() - 60) return cold ? biome(BiomeKeys.GROVE) : biome(BiomeKeys.MEADOW);

        // Rift valleys hold moisture and lush vegetation despite the altitude.
        if (riftValley) {
            if (cold) return biome(BiomeKeys.TAIGA);
            return wet ? biome(BiomeKeys.FOREST) : biome(BiomeKeys.PLAINS);
        }

        // Lowland climate zones, with smooth ecotone blending at boundaries so the 4-block
        // biome lattice never produces a hard, stepped chunk border.
        if (cold) return wet ? biome(BiomeKeys.TAIGA) : biome(BiomeKeys.SNOWY_PLAINS);
        if (hot) {
            if (wet) return biome(BiomeKeys.JUNGLE);
            if (dry) return biome(BiomeKeys.DESERT);
            return biome(BiomeKeys.SAVANNA);
        }
        if (wet) return ecotone(0.28, 0.34, m, x, z) ? biome(BiomeKeys.DARK_FOREST) : biome(BiomeKeys.FOREST);
        if (dry) return biome(BiomeKeys.PLAINS);
        if (faulted) return biome(BiomeKeys.WINDSWEPT_SAVANNA); // sparse, rocky
        return biome(BiomeKeys.BIRCH_FOREST);
    }

    /**
     * Smoothly picks the "rich" side of a climate threshold over a transition band. Within the
     * band a micro-jitter field decides, so neighbouring 4-block cells blend instead of lining up
     * into a hard border.
     */
    private boolean ecotone(double lo, double hi, double v, double x, double z) {
        if (v < lo) return false;
        if (v > hi) return true;
        double t = (v - lo) / (hi - lo);
        double jitter = 0.5 + 0.5 * com.cokedoutsnail.realisticterrain.noise.Noise2D.value(x / 6.0, z / 6.0, terrainSeed + 907);
        return jitter < t;
    }

    private RegistryEntry<Biome> biome(RegistryKey<Biome> key) {
        ensureBiomeLookup();
        RegistryEntry<Biome> entry = byId.get(key.getValue());
        return entry != null ? entry : defaultBiome;
    }
}