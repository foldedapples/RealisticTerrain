package com.cokedoutsnail.realisticterrain.worldgen;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;

import java.util.List;
import java.util.Locale;

/**
 * Every tunable the terrain engine reads, stored as one dense array indexed by
 * {@link TerrainSetting#ordinal()} and serialized by a lenient codec.
 *
 * <p>Design notes, because this replaced a 16-component record:
 *
 * <ul>
 *   <li><b>One array, one descriptor table.</b> {@link TerrainSetting} owns the key, range, default
 *       and UI category of every value; this class owns only the numbers. Adding a setting means
 *       adding one enum constant - the codec, the sliders, the tooltips and the validation all pick
 *       it up automatically. It also sidesteps DataFixerUpper's sixteen-argument limit on
 *       {@code RecordCodecBuilder.group}, which is what previously forced unrelated settings into a
 *       nested {@code ControlPoints} record.</li>
 *   <li><b>Lenient, forward- and backward-compatible decoding.</b> The codec reads every key it
 *       recognizes, ignores keys it does not, and falls back to the descriptor default when a value
 *       is missing or is not a number. A world saved by an older build - which wrote the same flat
 *       keys plus a nested {@code control_points} object - still loads; a world saved by a newer
 *       build with settings this version has never heard of also loads; and a setting added later
 *       simply defaults instead of failing the whole registry load. Values outside their documented
 *       range are clamped, so a hand-edited {@code level.dat} cannot produce terrain the engine was
 *       never tested against.</li>
 *   <li><b>Value semantics.</b> {@link #equals}/{@link #hashCode} cover every setting and the seed
 *       salt, because both {@code TerrainCache} and the drainage region cache key entries on
 *       {@code settings.hashCode()} - two worlds (or two slider positions) must never share an
 *       entry.</li>
 * </ul>
 */
public final class TerrainSettings {
    /** Number of entries in the UI (one slider per descriptor). */
    public static final int UI_SETTING_COUNT = TerrainSetting.values().length;

    /** Depth of the abyssal plain below sea level, in blocks, for the default profile. */
    public static final float DEFAULT_OCEAN_DEPTH = 100f;

    private final double[] values;
    private final long seedSalt;
    private final TerrainEngine engine;

    private TerrainSettings(double[] values, long seedSalt, TerrainEngine engine) {
        this.values = values;
        this.seedSalt = seedSalt;
        this.engine = engine;
    }

    /** The descriptor defaults, clamped so the table itself can never declare an illegal default. */
    private static double[] defaults() {
        TerrainSetting[] keys = TerrainSetting.values();
        double[] v = new double[keys.length];
        for (TerrainSetting k : keys) v[k.ordinal()] = k.clamp(k.defaultValue());
        return v;
    }

    /** Builds settings from descriptor defaults with a single value replaced. */
    public static TerrainSettings of(TerrainSetting key, double value) {
        double[] v = defaults();
        v[key.ordinal()] = key.clamp(value);
        return new TerrainSettings(v, 0L, TerrainEngine.GEOLOGICAL);
    }

    /** Builds settings from a raw array already indexed by {@link TerrainSetting#ordinal()}. */
    private static TerrainSettings fromRaw(double[] values, long seedSalt) {
        double[] v = defaults();
        for (TerrainSetting k : TerrainSetting.values()) {
            v[k.ordinal()] = k.clamp(values[k.ordinal()]);
        }
        return new TerrainSettings(v, seedSalt, TerrainEngine.GEOLOGICAL);
    }

    public static final TerrainSettings DEFAULT = new TerrainSettings(defaults(), 0L, TerrainEngine.GEOLOGICAL);

    /** Raw (already clamped) value of one setting. */
    public double raw(TerrainSetting key) {
        return values[key.ordinal()];
    }

    public long seedSalt() {
        return seedSalt;
    }

    /**
     * Lenient settings codec (see the class javadoc). Writes the flat {@code key: number} object the
     * world save has always used, so existing worlds keep loading.
     */
    public static final Codec<TerrainSettings> CODEC = new Codec<>() {
        @Override
        public <T> DataResult<Pair<TerrainSettings, T>> decode(DynamicOps<T> ops, T input) {
            MapLike<T> map = ops.getMap(input).result().orElse(null);
            if (map == null) {
                // Not an object at all - keep the world loadable with defaults rather than failing
                // the whole datapack registry load.
                return DataResult.success(Pair.of(DEFAULT, input));
            }
            double[] v = defaults();
            for (TerrainSetting k : TerrainSetting.values()) {
                Double d = number(ops, map.get(k.jsonKey()));
                if (d != null) v[k.ordinal()] = k.clamp(d);
            }
            // Legacy nested control points, as written by builds before the descriptor table.
            MapLike<T> cp = ops.getMap(map.get("control_points")).result().orElse(null);
            if (cp != null) {
                Double coast = number(ops, cp.get("coast_line"));
                Double deep = number(ops, cp.get("ocean_depth"));
                if (coast != null) v[TerrainSetting.COAST_LINE.ordinal()] = TerrainSetting.COAST_LINE.clamp(coast);
                if (deep != null) v[TerrainSetting.OCEAN_DEPTH.ordinal()] = TerrainSetting.OCEAN_DEPTH.clamp(deep);
            }
            Double salt = number(ops, map.get("seed_salt"));
            // Engine selection (default GEOLOGICAL for old worlds missing the field)
            TerrainEngine eng = TerrainEngine.GEOLOGICAL;
            T engineValue = map.get("engine");
            if (engineValue != null) {
                String engStr = ops.getStringValue(engineValue).result().orElse(null);
                if (engStr != null) {
                    try { eng = TerrainEngine.valueOf(engStr.toUpperCase(java.util.Locale.ROOT)); } catch (Exception ignored) {}
                }
            }
            return DataResult.success(Pair.of(new TerrainSettings(v, salt == null ? 0L : salt.longValue(), eng), input));
        }

        @Override
        public <T> DataResult<T> encode(TerrainSettings s, DynamicOps<T> ops, T prefix) {
            RecordBuilder<T> builder = ops.mapBuilder();
            for (TerrainSetting k : TerrainSetting.values()) {
                builder.add(k.jsonKey(), ops.createDouble(s.values[k.ordinal()]));
            }
            builder.add("seed_salt", ops.createLong(s.seedSalt));
            if (s.engine != TerrainEngine.GEOLOGICAL) {
                builder.add("engine", ops.createString(s.engine.asString()));
            }
            return builder.build(prefix);
        }

        private <T> Double number(DynamicOps<T> ops, T value) {
            if (value == null) return null;
            Number n = ops.getNumberValue(value).result().orElse(null);
            if (n != null) return n.doubleValue();
            Boolean b = ops.getBooleanValue(value).result().orElse(null);
            return b == null ? null : (b ? 1.0 : 0.0);
        }
    };

    // ------------------------------------------------------------------
    // Typed accessors. These keep call sites terse and, more importantly,
    // mean the rest of the engine never indexes the array by hand - a
    // mistyped ordinal is a compile error here, not a silent terrain bug.
    // ------------------------------------------------------------------

    private float f(TerrainSetting key) { return (float) values[key.ordinal()]; }
    private int i(TerrainSetting key) { return (int) Math.rint(values[key.ordinal()]); }

    public float mountainHeight() { return f(TerrainSetting.MOUNTAIN_HEIGHT); }
    public float mountainFrequency() { return f(TerrainSetting.MOUNTAIN_FREQUENCY); }
    public float ridgeSharpness() { return f(TerrainSetting.RIDGE_SHARPNESS); }
    public float erosionIntensity() { return f(TerrainSetting.EROSION_INTENSITY); }
    public float riverWidth() { return f(TerrainSetting.RIVER_WIDTH); }
    public float riverFrequency() { return f(TerrainSetting.RIVER_FREQUENCY); }
    public float riverDepth() { return f(TerrainSetting.RIVER_DEPTH); }
    public int snowLine() { return i(TerrainSetting.SNOW_LINE); }
    public float biomeScale() { return f(TerrainSetting.BIOME_SCALE); }
    public int seaLevel() { return i(TerrainSetting.SEA_LEVEL); }
    public float roughness() { return f(TerrainSetting.ROUGHNESS); }
    public float vegetationDensity() { return f(TerrainSetting.VEGETATION_DENSITY); }
    public float continentalScale() { return f(TerrainSetting.CONTINENTAL_SCALE); }
    public float canyonDepth() { return f(TerrainSetting.CANYON_DEPTH); }
    public float coastLine() { return f(TerrainSetting.COAST_LINE); }
    public float oceanDepth() { return f(TerrainSetting.OCEAN_DEPTH); }
    public int maximumTerrainY() { return i(TerrainSetting.MAXIMUM_TERRAIN_Y); }
    public float plateScale() { return f(TerrainSetting.PLATE_SCALE); }
    public float tectonicActivity() { return f(TerrainSetting.TECTONIC_ACTIVITY); }
    public float mountainRangeWidth() { return f(TerrainSetting.MOUNTAIN_RANGE_WIDTH); }
    public float mountainUplift() { return f(TerrainSetting.MOUNTAIN_UPLIFT); }
    public float riverDensity() { return f(TerrainSetting.RIVER_DENSITY); }
    public float tributaryDensity() { return f(TerrainSetting.TRIBUTARY_DENSITY); }
    public float meanderStrength() { return f(TerrainSetting.MEANDER_STRENGTH); }
    public float lakeFrequency() { return f(TerrainSetting.LAKE_FREQUENCY); }
    public float wetlandFrequency() { return f(TerrainSetting.WETLAND_FREQUENCY); }
    public float drainageScale() { return f(TerrainSetting.DRAINAGE_SCALE); }
    public float caveGeneration() { return f(TerrainSetting.CAVE_GENERATION); }
    public boolean generateStructures() { return raw(TerrainSetting.GENERATE_STRUCTURES) >= 0.5; }

    /** The two named continental control points, derived from their descriptor entries. */
    public ControlPoints controlPoints() {
        return new ControlPoints(coastLine(), oceanDepth());
    }

    /** Reads one setting by its slider index (see {@link #withValue}). */
    public double getValue(int index) {
        TerrainSetting[] keys = TerrainSetting.values();
        return values[keys[Math.max(0, Math.min(keys.length - 1, index))].ordinal()];
    }

    /**
     * Returns a copy with exactly one setting replaced, addressed by its slider index. The index
     * &rarr; setting mapping lives in {@link TerrainSetting}'s declaration order, so the GUI never
     * spells out a constructor call and a new setting needs no wiring here.
     */
    public TerrainSettings withValue(int index, double value) {
        TerrainSetting[] keys = TerrainSetting.values();
        if (index < 0 || index >= keys.length) return this;
        return with(keys[index], value);
    }

    /** Returns a copy with one named setting replaced, clamped to its documented range. */
    public TerrainSettings with(TerrainSetting key, double value) {
        double[] v = values.clone();
        v[key.ordinal()] = key.clamp(value);
        return new TerrainSettings(v, seedSalt, engine);
    }

    /** Returns a copy with a different seed salt, which decorrelates otherwise identical worlds. */
    public TerrainSettings withSeedSalt(long salt) {
        return new TerrainSettings(values.clone(), salt, engine);
    }

    /** Returns a copy with a different terrain engine. */
    public TerrainSettings withEngine(TerrainEngine engine) {
        return new TerrainSettings(values.clone(), seedSalt, engine);
    }

    /** Convenience: the selected terrain engine. */
    public TerrainEngine engine() { return engine; }

    /**
     * The named continental thresholds, following ReTerraForged's
     * {@code WorldSettings.ControlPoints}. Retained as a distinct type only so older saves that
     * nested {@code control_points} still decode; the engine now reads {@link #coastLine()} and
     * {@link #oceanDepth()} directly.
     */
    public record ControlPoints(float coastLine, float deepOcean) {
        public static final ControlPoints DEFAULT = new ControlPoints(-0.12f, DEFAULT_OCEAN_DEPTH);
    }

    // ------------------------------------------------------------------
    // Profiles
    // ------------------------------------------------------------------

    /** A named world-type profile shown in the Customize screen; the first is the default style. */
    public record Profile(String nameKey, TerrainSettings settings) {}

    /** Builds a profile by applying {@code (TerrainSetting, Number)} override pairs to the defaults. */
    private static TerrainSettings profile(Object... keyValuePairs) {
        TerrainSettings s = DEFAULT;
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            s = s.with((TerrainSetting) keyValuePairs[i], ((Number) keyValuePairs[i + 1]).doubleValue());
        }
        return s;
    }

    // See TerrainSetting.CATEGORIES: List.of infers a @NonNull element type, which Eclipse null
    // analysis reports as an unsafe conversion into this unannotated declaration.
    @SuppressWarnings("null")
    public static final List<Profile> PROFILES = List.of(
            new Profile("realisticterrain.profile.continental",
                    profile(TerrainSetting.BIOME_SCALE, 1.1, TerrainSetting.RIVER_FREQUENCY, 0.8)),
            new Profile("realisticterrain.profile.alpine",
                    profile(TerrainSetting.MOUNTAIN_HEIGHT, 2.2, TerrainSetting.MOUNTAIN_FREQUENCY, 0.7,
                            TerrainSetting.RIDGE_SHARPNESS, 1.5, TerrainSetting.EROSION_INTENSITY, 1.3,
                            TerrainSetting.RIVER_WIDTH, 0.8, TerrainSetting.RIVER_DEPTH, 1.2,
                            TerrainSetting.SNOW_LINE, 360, TerrainSetting.VEGETATION_DENSITY, 0.7,
                            TerrainSetting.CANYON_DEPTH, 0.9, TerrainSetting.MOUNTAIN_RANGE_WIDTH, 1.2,
                            TerrainSetting.MOUNTAIN_UPLIFT, 1.4, TerrainSetting.OCEAN_DEPTH, 120)),
            new Profile("realisticterrain.profile.archipelago",
                    profile(TerrainSetting.MOUNTAIN_HEIGHT, 0.9, TerrainSetting.MOUNTAIN_FREQUENCY, 1.3,
                            TerrainSetting.RIDGE_SHARPNESS, 0.8, TerrainSetting.EROSION_INTENSITY, 1.1,
                            TerrainSetting.RIVER_WIDTH, 1.2, TerrainSetting.RIVER_FREQUENCY, 1.2,
                            TerrainSetting.BIOME_SCALE, 1.2, TerrainSetting.SEA_LEVEL, 76,
                            TerrainSetting.CONTINENTAL_SCALE, 0.8, TerrainSetting.COAST_LINE, -0.14,
                            TerrainSetting.OCEAN_DEPTH, 90, TerrainSetting.PLATE_SCALE, 0.7)),
            new Profile("realisticterrain.profile.rolling",
                    profile(TerrainSetting.MOUNTAIN_HEIGHT, 0.55, TerrainSetting.MOUNTAIN_FREQUENCY, 1.1,
                            TerrainSetting.RIDGE_SHARPNESS, 0.6, TerrainSetting.EROSION_INTENSITY, 0.8,
                            TerrainSetting.RIVER_WIDTH, 1.1, TerrainSetting.RIVER_DEPTH, 0.8,
                            TerrainSetting.SNOW_LINE, 500, TerrainSetting.ROUGHNESS, 0.8,
                            TerrainSetting.VEGETATION_DENSITY, 1.3, TerrainSetting.CONTINENTAL_SCALE, 1.1,
                            TerrainSetting.CANYON_DEPTH, 0.6, TerrainSetting.COAST_LINE, -0.02,
                            TerrainSetting.OCEAN_DEPTH, 80, TerrainSetting.TECTONIC_ACTIVITY, 0.4)),
            new Profile("realisticterrain.profile.canyons",
                    profile(TerrainSetting.MOUNTAIN_HEIGHT, 1.4, TerrainSetting.MOUNTAIN_FREQUENCY, 0.8,
                            TerrainSetting.RIDGE_SHARPNESS, 1.2, TerrainSetting.EROSION_INTENSITY, 2.2,
                            TerrainSetting.RIVER_WIDTH, 1.4, TerrainSetting.RIVER_DEPTH, 2.2,
                            TerrainSetting.SNOW_LINE, 480, TerrainSetting.BIOME_SCALE, 0.9,
                            TerrainSetting.VEGETATION_DENSITY, 0.6, TerrainSetting.CONTINENTAL_SCALE, 1.2,
                            TerrainSetting.CANYON_DEPTH, 2.2, TerrainSetting.OCEAN_DEPTH, 130)),
            new Profile("realisticterrain.profile.riverlands",
                    profile(TerrainSetting.MOUNTAIN_HEIGHT, 0.8, TerrainSetting.RIDGE_SHARPNESS, 0.8,
                            TerrainSetting.EROSION_INTENSITY, 1.2, TerrainSetting.RIVER_WIDTH, 1.3,
                            TerrainSetting.RIVER_FREQUENCY, 1.1, TerrainSetting.RIVER_DENSITY, 1.8,
                            TerrainSetting.TRIBUTARY_DENSITY, 1.8, TerrainSetting.MEANDER_STRENGTH, 1.6,
                            TerrainSetting.LAKE_FREQUENCY, 2.0, TerrainSetting.WETLAND_FREQUENCY, 2.0,
                            TerrainSetting.CONTINENTAL_SCALE, 1.3, TerrainSetting.COAST_LINE, -0.06,
                            TerrainSetting.VEGETATION_DENSITY, 1.4)),
            new Profile("realisticterrain.profile.earthlike",
                    profile(TerrainSetting.MOUNTAIN_HEIGHT, 1.15, TerrainSetting.MOUNTAIN_FREQUENCY, 0.85,
                            TerrainSetting.RIDGE_SHARPNESS, 1.1, TerrainSetting.EROSION_INTENSITY, 1.15,
                            TerrainSetting.RIVER_WIDTH, 1.1, TerrainSetting.RIVER_FREQUENCY, 0.9,
                            TerrainSetting.RIVER_DEPTH, 1.1, TerrainSetting.SNOW_LINE, 470,
                            TerrainSetting.BIOME_SCALE, 1.05, TerrainSetting.VEGETATION_DENSITY, 1.15,
                            TerrainSetting.CONTINENTAL_SCALE, 1.05, TerrainSetting.CANYON_DEPTH, 1.1,
                            TerrainSetting.COAST_LINE, -0.10, TerrainSetting.OCEAN_DEPTH, 115,
                            TerrainSetting.PLATE_SCALE, 1.15, TerrainSetting.TECTONIC_ACTIVITY, 1.15,
                            TerrainSetting.MOUNTAIN_RANGE_WIDTH, 1.15, TerrainSetting.MOUNTAIN_UPLIFT, 1.2,
                            TerrainSetting.RIVER_DENSITY, 1.2, TerrainSetting.TRIBUTARY_DENSITY, 1.2,
                            TerrainSetting.MEANDER_STRENGTH, 1.2, TerrainSetting.LAKE_FREQUENCY, 1.2,
                            TerrainSetting.WETLAND_FREQUENCY, 1.2, TerrainSetting.MAXIMUM_TERRAIN_Y, 1100))
    );

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TerrainSettings other)) return false;
        return seedSalt == other.seedSalt && engine == other.engine && java.util.Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * java.util.Arrays.hashCode(values) + Long.hashCode(seedSalt)) + engine.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TerrainSettings[");
        TerrainSetting[] keys = TerrainSetting.values();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(keys[i].jsonKey()).append('=').append(values[i]);
        }
        return sb.append(", seed_salt=").append(seedSalt).append(", engine=").append(engine).append(']').toString();
    }
}
