package com.cokedoutsnail.realisticterrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

public record TerrainSettings(
        float mountainHeight,
        float mountainFrequency,
        float ridgeSharpness,
        float erosionIntensity,
        float riverWidth,
        float riverFrequency,
        float riverDepth,
        int snowLine,
        float biomeScale,
        int seaLevel,
        float roughness,
        float vegetationDensity,
        float continentalScale,
        float canyonDepth,
        long seedSalt
) {
    public static final TerrainSettings DEFAULT = new TerrainSettings(1f, 1f, 1f, 1f, 1f, 1f, 1f, 430, 1f, 96, 1f, 1f, 1f, 1f, 0L);

    public static final Codec<TerrainSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.floatRange(0.25f, 3.0f).fieldOf("mountain_height").forGetter(TerrainSettings::mountainHeight),
            Codec.floatRange(0.25f, 3.0f).fieldOf("mountain_frequency").forGetter(TerrainSettings::mountainFrequency),
            Codec.floatRange(0.25f, 3.0f).fieldOf("ridge_sharpness").forGetter(TerrainSettings::ridgeSharpness),
            Codec.floatRange(0.0f, 2.5f).fieldOf("erosion_intensity").forGetter(TerrainSettings::erosionIntensity),
            Codec.floatRange(0.25f, 4.0f).fieldOf("river_width").forGetter(TerrainSettings::riverWidth),
            Codec.floatRange(0.25f, 3.0f).fieldOf("river_frequency").forGetter(TerrainSettings::riverFrequency),
            Codec.floatRange(0.25f, 3.0f).fieldOf("river_depth").forGetter(TerrainSettings::riverDepth),
            Codec.intRange(96, 1800).fieldOf("snow_line").forGetter(TerrainSettings::snowLine),
            Codec.floatRange(0.25f, 4.0f).fieldOf("biome_scale").forGetter(TerrainSettings::biomeScale),
            Codec.intRange(-32, 512).fieldOf("sea_level").forGetter(TerrainSettings::seaLevel),
            Codec.floatRange(0.2f, 3.0f).fieldOf("roughness").forGetter(TerrainSettings::roughness),
            Codec.floatRange(0.0f, 3.0f).optionalFieldOf("vegetation_density", 1.0f).forGetter(TerrainSettings::vegetationDensity),
            Codec.floatRange(0.5f, 2.5f).optionalFieldOf("continental_scale", 1.0f).forGetter(TerrainSettings::continentalScale),
            Codec.floatRange(0.0f, 2.5f).optionalFieldOf("canyon_depth", 1.0f).forGetter(TerrainSettings::canyonDepth),
            Codec.LONG.optionalFieldOf("seed_salt", 0L).forGetter(TerrainSettings::seedSalt)
    ).apply(i, TerrainSettings::new));

    /** A named world-type profile shown in the Customize screen; the first is the default style. */
    public record Profile(String nameKey, TerrainSettings settings) {}

    public static final List<Profile> PROFILES = List.of(
            new Profile("realisticterrain.profile.continental", new TerrainSettings(1.0f, 0.9f, 0.9f, 1.0f, 1.0f, 0.8f, 1.0f, 430, 1.1f, 96, 1.0f, 1.0f, 1.0f, 1.0f, 0L)),
            new Profile("realisticterrain.profile.alpine",      new TerrainSettings(2.2f, 0.7f, 1.5f, 1.3f, 0.8f, 0.9f, 1.2f, 360, 1.0f, 96, 1.0f, 0.7f, 1.0f, 0.9f, 0L)),
            new Profile("realisticterrain.profile.archipelago", new TerrainSettings(0.9f, 1.3f, 0.8f, 1.1f, 1.2f, 1.2f, 1.0f, 430, 1.2f, 76, 1.0f, 1.2f, 0.8f, 1.0f, 0L)),
            new Profile("realisticterrain.profile.rolling",     new TerrainSettings(0.55f, 1.1f, 0.6f, 0.8f, 1.1f, 1.0f, 0.8f, 500, 1.0f, 96, 0.8f, 1.3f, 1.1f, 0.6f, 0L)),
            new Profile("realisticterrain.profile.canyons",     new TerrainSettings(1.4f, 0.8f, 1.2f, 2.2f, 1.4f, 0.9f, 2.2f, 480, 0.9f, 96, 1.1f, 0.6f, 1.2f, 2.2f, 0L))
    );
}
