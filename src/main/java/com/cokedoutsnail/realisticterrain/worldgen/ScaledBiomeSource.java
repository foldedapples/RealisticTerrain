package com.cokedoutsnail.realisticterrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.util.stream.Stream;

/** A serializable coordinate-scaling wrapper around any vanilla biome source. */
public final class ScaledBiomeSource extends BiomeSource {
    public static final MapCodec<ScaledBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("source").forGetter(ScaledBiomeSource::source),
            Codec.floatRange(0.25F, 4.0F).optionalFieldOf("scale", 1.0F).forGetter(ScaledBiomeSource::scale)
    ).apply(instance, ScaledBiomeSource::new));

    private final BiomeSource source;
    private final float scale;

    public ScaledBiomeSource(BiomeSource source, float scale) {
        this.source = source;
        this.scale = scale;
    }

    public BiomeSource source() {
        return source;
    }

    public float scale() {
        return scale;
    }

    public ScaledBiomeSource withScale(float newScale) {
        return new ScaledBiomeSource(source, newScale);
    }

    @Override
    protected MapCodec<? extends BiomeSource> getCodec() {
        return CODEC;
    }

    @Override
    protected Stream<RegistryEntry<Biome>> biomeStream() {
        return source.getBiomes().stream();
    }

    @Override
    public RegistryEntry<Biome> getBiome(int biomeX, int biomeY, int biomeZ, MultiNoiseUtil.MultiNoiseSampler sampler) {
        return source.getBiome(scaleCoordinate(biomeX), biomeY, scaleCoordinate(biomeZ), sampler);
    }

    private int scaleCoordinate(int value) {
        return (int) Math.floor(value / (double) scale);
    }
}
