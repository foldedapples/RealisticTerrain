package com.cokedoutsnail.realisticterrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * Every tunable the terrain engine reads, serialized as a MapCodec so each world stores its own
 * configuration (see {@link #CODEC}). The chunk generator and the terrain-aware biome source each
 * carry a copy, which is why the customize screen updates both together.
 *
 * <p>{@link ControlPoints} is this mod's take on ReTerraForged's {@code WorldSettings.ControlPoints}:
 * named continental thresholds that decide where the shoreline sits ({@code coast}) and how deep the
 * abyssal plain goes ({@code deepOcean}). Surfacing them is what lets a player turn a drowned world
 * back into a continent without recompiling the mod, and they are the knobs the "Customize" screen
 * exposes for exactly that. They live in a nested record rather than as flat components because
 * DataFixerUpper's {@code RecordCodecBuilder.group} only has overloads up to sixteen arguments.
 */
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
        long seedSalt,
        ControlPoints controlPoints
) {
    /**
     * Continentalness at which the craton baseline sits exactly on sea level - the threshold above
     * which a column is land ({@code continent > coastLine}). Raising it moves the shoreline inland
     * and drowns more of the world; lowering it pushes the shoreline seaward and leaves more land.
     * Tuned so continents hold a clear majority of the surface rather than being drowned by ocean.
     */
    public static final float DEFAULT_COAST_LINE = -0.12f;
    /** Depth of the abyssal plain below sea level, in blocks. */
    public static final float DEFAULT_OCEAN_DEPTH = 100f;
    /** Number of settings the customize screen exposes, i.e. the valid indices for {@link #withValue}. */
    public static final int UI_SETTING_COUNT = 16;

    /**
     * The named continental thresholds, ported from ReTerraForged's
     * {@code WorldSettings.ControlPoints}. {@code coastLine} is the continentalness at which the
     * craton baseline sits exactly on sea level, so raising it moves the shoreline inland (less
     * land) and lowering it pushes the shoreline seaward (more land); {@code deepOcean} is how far
     * the abyssal plain sits below sea level.
     */
    public record ControlPoints(float coastLine, float deepOcean) {
        public static final ControlPoints DEFAULT = new ControlPoints(DEFAULT_COAST_LINE, DEFAULT_OCEAN_DEPTH);

        public static final Codec<ControlPoints> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.floatRange(-0.35f, 0.15f).optionalFieldOf("coast_line", DEFAULT_COAST_LINE).forGetter(ControlPoints::coastLine),
                Codec.floatRange(0.0f, 300.0f).optionalFieldOf("ocean_depth", DEFAULT_OCEAN_DEPTH).forGetter(ControlPoints::deepOcean)
        ).apply(i, ControlPoints::new));
    }

    public static final TerrainSettings DEFAULT = new TerrainSettings(
            1f, 1f, 1f, 1f, 1f, 1f, 1f, 430, 1f, 96, 1f, 1f, 1f, 1f, 0L,
            ControlPoints.DEFAULT);

    /** Convenience: continentalness of the shoreline (see {@link ControlPoints}). */
    public float coastLine() { return controlPoints.coastLine(); }

    /** Convenience: abyssal depth below sea level (see {@link ControlPoints}). */
    public float oceanDepth() { return controlPoints.deepOcean(); }

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
            Codec.LONG.optionalFieldOf("seed_salt", 0L).forGetter(TerrainSettings::seedSalt),
            ControlPoints.CODEC.optionalFieldOf("control_points", ControlPoints.DEFAULT).forGetter(TerrainSettings::controlPoints)
    ).apply(i, TerrainSettings::new));

    /**
     * The settings in the order the customize screen addresses them ({@code 0} = mountain height,
     * ... {@code 15} = ocean depth). The integers are widened to double only for this array; every
     * value is far inside double's exact-integer range.
     */
    private double[] uiValues() {
        return new double[]{mountainHeight, mountainFrequency, ridgeSharpness, erosionIntensity,
                riverWidth, riverFrequency, riverDepth, snowLine, biomeScale, seaLevel, roughness,
                vegetationDensity, continentalScale, canyonDepth,
                controlPoints.coastLine(), controlPoints.deepOcean()};
    }

    /** Reads one setting by its customize-screen index (see {@link #withValue}). */
    public double getValue(int index) {
        return uiValues()[index];
    }

    /**
     * Returns a copy with exactly one setting replaced, addressed by the index the customize screen
     * uses ({@code 0} = mountain height, ... {@code 15} = ocean depth).
     *
     * <p>Keeping the index &rarr; component mapping here rather than in the GUI means the screen
     * never has to spell out a seventeen-argument constructor call, and a newly added setting only
     * has to be wired up in one place.
     */
    public TerrainSettings withValue(int index, double value) {
        double[] v = uiValues();
        v[index] = value;
        return new TerrainSettings(
                (float) v[0], (float) v[1], (float) v[2], (float) v[3], (float) v[4], (float) v[5],
                (float) v[6], (int) v[7], (float) v[8], (int) v[9], (float) v[10], (float) v[11],
                (float) v[12], (float) v[13], seedSalt,
                new ControlPoints((float) v[14], (float) v[15]));
    }

    /** A named world-type profile shown in the Customize screen; the first is the default style. */
    public record Profile(String nameKey, TerrainSettings settings) {}

    public static final List<Profile> PROFILES = List.of(
            new Profile("realisticterrain.profile.continental", new TerrainSettings(1.0f, 0.9f, 0.9f, 1.0f, 1.0f, 0.8f, 1.0f, 430, 1.1f, 96, 1.0f, 1.0f, 1.0f, 1.0f, 0L, ControlPoints.DEFAULT)),
            new Profile("realisticterrain.profile.alpine",      new TerrainSettings(2.2f, 0.7f, 1.5f, 1.3f, 0.8f, 0.9f, 1.2f, 360, 1.0f, 96, 1.0f, 0.7f, 1.0f, 0.9f, 0L, new ControlPoints(DEFAULT_COAST_LINE, 120f))),
            new Profile("realisticterrain.profile.archipelago", new TerrainSettings(0.9f, 1.3f, 0.8f, 1.1f, 1.2f, 1.2f, 1.0f, 430, 1.2f, 76, 1.0f, 1.2f, 0.8f, 1.0f, 0L, new ControlPoints(-0.14f, 90f))),
            new Profile("realisticterrain.profile.rolling",     new TerrainSettings(0.55f, 1.1f, 0.6f, 0.8f, 1.1f, 1.0f, 0.8f, 500, 1.0f, 96, 0.8f, 1.3f, 1.1f, 0.6f, 0L, new ControlPoints(-0.02f, 80f))),
            new Profile("realisticterrain.profile.canyons",     new TerrainSettings(1.4f, 0.8f, 1.2f, 2.2f, 1.4f, 0.9f, 2.2f, 480, 0.9f, 96, 1.1f, 0.6f, 1.2f, 2.2f, 0L, new ControlPoints(DEFAULT_COAST_LINE, 130f)))
    );
}
