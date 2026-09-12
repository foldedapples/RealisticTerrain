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
 * Turns the authoritative {@link TerrainBiomeClassifier} result into a vanilla biome.
 *
 * <p>The source classifies nothing itself any more. It samples the same {@link TerrainModel} at the
 * same real world coordinates the chunk generator uses - biome scale is applied inside the model's
 * climate fields, never by dividing the sample coordinates - and hands the column to the classifier.
 * Because the generator's surface resolver reads the same classifier, the biome on a column and the
 * blocks in it cannot disagree.
 *
 * <p>The world seed comes from the generator-owned {@link TerrainContext}, which
 * {@code RealisticChunkGenerator} attaches in its constructor and initializes from {@code NoiseConfig}
 * before any biome sampling (see {@code createStructurePlacementCalculator} and
 * {@code populateBiomes}). There is no fallback seed and no mutable per-chunk setter: reading a biome
 * from an unattached source is a programming error, not a silently wrong world.
 */
public final class TerrainBiomeSource extends BiomeSource {
    // See RealisticChunkGenerator.CODEC: Mojang and DataFixerUpper ship no null annotations, so the
    // record builder's generics are reported as unchecked conversions by Eclipse null analysis.
    @SuppressWarnings("null")
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
    // Owned by the chunk generator; supplies the same world-derived seed the terrain uses.
    private volatile TerrainContext terrainContext;

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

    /**
     * Attaches the generator-owned terrain seed context. Called once by the chunk generator's
     * constructor; the seed itself has no public setter, because that mutable per-chunk design is
     * exactly what Phase 1 removed.
     */
    public void attachContext(TerrainContext context) {
        this.terrainContext = context;
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
    public RegistryEntry<Biome> getBiome(int biomeX, int biomeY, int biomeZ,
            MultiNoiseUtil.MultiNoiseSampler sampler) {
        ensureBiomeLookup();
        TerrainContext ctx = terrainContext;
        if (ctx == null) {
            throw new IllegalStateException("TerrainBiomeSource is not attached to a terrain context; "
                    + "it must be driven by RealisticChunkGenerator");
        }
        long seed = ctx.seed();
        // Real world coordinates: never divide by the biome scale here. The scale is a property of
        // the climate fields inside TerrainModel, so moving the sample would classify a different
        // physical column than the one the chunk generator actually builds.
        double x = biomeX * 4.0;
        double z = biomeZ * 4.0;
        TerrainModel.Sample sample = TerrainModel.sample(seed, x, z, settings);
        TerrainBiomeType type = TerrainBiomeClassifier.classify(seed, x, z, settings, sample, scale);
        return biome(keyFor(type));
    }

    /** The single, explicit mapping from a pure classification to a vanilla biome key. */
    private static RegistryKey<Biome> keyFor(TerrainBiomeType type) {
        return switch (type) {
            case DEEP_OCEAN -> BiomeKeys.DEEP_OCEAN;
            case OCEAN -> BiomeKeys.OCEAN;
            // Vanilla has no lake biome; an inland lake reads as river water when standing in it.
            case RIVER, LAKE -> BiomeKeys.RIVER;
            case BEACH -> BiomeKeys.BEACH;
            case DESERT -> BiomeKeys.DESERT;
            case SAVANNA -> BiomeKeys.SAVANNA;
            case PLAINS -> BiomeKeys.PLAINS;
            case FOREST -> BiomeKeys.FOREST;
            case DARK_FOREST -> BiomeKeys.DARK_FOREST;
            case BIRCH_FOREST -> BiomeKeys.BIRCH_FOREST;
            case JUNGLE -> BiomeKeys.JUNGLE;
            case SWAMP -> BiomeKeys.SWAMP;
            case TAIGA -> BiomeKeys.TAIGA;
            case SNOWY_PLAINS -> BiomeKeys.SNOWY_PLAINS;
            case GROVE -> BiomeKeys.GROVE;
            case MEADOW -> BiomeKeys.MEADOW;
            case SNOWY_SLOPES -> BiomeKeys.SNOWY_SLOPES;
            case STONY_PEAKS -> BiomeKeys.STONY_PEAKS;
        };
    }

    private RegistryEntry<Biome> biome(RegistryKey<Biome> key) {
        ensureBiomeLookup();
        RegistryEntry<Biome> entry = byId.get(key.getValue());
        return entry != null ? entry : defaultBiome;
    }
}
