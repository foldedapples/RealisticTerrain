package com.cokedoutsnail.realisticterrain.worldgen;

import java.util.List;

/**
 * Every tunable the terrain engine reads, described once and used everywhere.
 *
 * <p>This is the single source of truth for a setting: its JSON key, the UI category it belongs to,
 * the safe range it must stay inside, its default and whether it is an integer. The customize screen
 * builds its sliders and tooltips from this table, the codec reads and writes the JSON keys from it,
 * and the tests iterate it - so it is impossible to add a setting here and forget to wire it up
 * somewhere, and impossible to ship a slider that does not affect generation (the "fake slider"
 * bug the UI used to risk).
 *
 * <p>The enum's declaration order is also the slider index order used by
 * {@link TerrainSettings#withValue(int, double)}. The first sixteen entries keep the order the
 * customize screen shipped with, so the existing tests and any stored slider positions keep their
 * meaning; new settings are appended.
 */
public enum TerrainSetting {
    // --- index 0..13: the original slider order, unchanged ---
    MOUNTAIN_HEIGHT("mountain_height", Category.MOUNTAINS, 0.10, 3.00, 1.00,
            "Overall height of folded mountain ranges."),
    MOUNTAIN_FREQUENCY("mountain_frequency", Category.MOUNTAINS, 0.25, 2.50, 1.00,
            "How often mountain ranges appear across the world."),
    RIDGE_SHARPNESS("ridge_sharpness", Category.MOUNTAINS, 0.25, 3.00, 1.00,
            "How sharp and narrow the ridgelines are."),
    EROSION_INTENSITY("erosion_intensity", Category.EROSION, 0.00, 3.00, 1.00,
            "Strength of hydraulic erosion and gully carving. Zero disables the pass entirely."),
    RIVER_WIDTH("river_width", Category.RIVERS, 0.25, 4.00, 1.00,
            "Physical width of river channels, in blocks."),
    RIVER_FREQUENCY("river_frequency", Category.RIVERS, 0.25, 3.00, 1.00,
            "Spacing of the drainage network before discharge is applied."),
    RIVER_DEPTH("river_depth", Category.RIVERS, 0.25, 2.50, 1.00,
            "Incision depth of river channels and ocean trenches."),
    SNOW_LINE("snow_line", Category.CLIMATE, 120.0, 900.0, 430.0, true,
            "Base altitude of permanent snow, before climate and slope bias it."),
    BIOME_SCALE("biome_scale", Category.CLIMATE, 0.50, 2.50, 1.00,
            "Size of temperature and humidity zones."),
    SEA_LEVEL("sea_level", Category.WORLD, 32.0, 320.0, 96.0, true,
            "Water level of the world's oceans."),
    ROUGHNESS("roughness", Category.MOUNTAINS, 0.00, 3.00, 1.00,
            "Fine terrain roughness and cave threshold variation."),
    VEGETATION_DENSITY("vegetation_density", Category.SURFACE, 0.00, 3.00, 1.00,
            "Density of slope- and soil-aware forests. Zero plants no trees."),
    CONTINENTAL_SCALE("continental_scale", Category.CONTINENTS, 0.50, 2.50, 1.00,
            "Size of continents and ocean basins."),
    CANYON_DEPTH("canyon_depth", Category.EROSION, 0.00, 2.50, 1.00,
            "Incision of fluvial canyons into plateaus."),
    // --- index 14..15: the original control points, kept last in the old order ---
    COAST_LINE("coast_line", Category.CONTINENTS, -0.35, 0.15, -0.12,
            "Continentalness at the shoreline: lower means more land, higher means more ocean."),
    OCEAN_DEPTH("ocean_depth", Category.CONTINENTS, 0.00, 300.00, 100.00,
            "How far the abyssal plain sits below sea level."),
    // --- appended settings (index 16+) ---
    MAXIMUM_TERRAIN_Y("maximum_terrain_y", Category.WORLD, 256.0, 1900.0, 1024.0, true,
            "Highest ground the generator may write, kept below the build ceiling so snow, trees and structures still fit."),
    PLATE_SCALE("plate_scale", Category.CONTINENTS, 0.50, 3.00, 1.00,
            "Size of tectonic plates. Larger plates mean longer, straighter ranges."),
    TECTONIC_ACTIVITY("tectonic_activity", Category.CONTINENTS, 0.00, 2.00, 1.00,
            "How much uplift, rifting and faulting the plate margins produce."),
    MOUNTAIN_RANGE_WIDTH("mountain_range_width", Category.MOUNTAINS, 0.30, 3.00, 1.00,
            "Width of the folded belt either side of a convergent margin."),
    MOUNTAIN_UPLIFT("mountain_uplift", Category.MOUNTAINS, 0.00, 2.00, 1.00,
            "Broad regional uplift applied under a mountain range."),
    RIVER_DENSITY("river_density", Category.RIVERS, 0.00, 2.50, 1.00,
            "Number of drainage networks. Zero generates no rivers at all."),
    TRIBUTARY_DENSITY("tributary_density", Category.RIVERS, 0.00, 2.50, 1.00,
            "How readily small tributaries are cut into the main channels."),
    MEANDER_STRENGTH("meander_strength", Category.RIVERS, 0.00, 2.00, 1.00,
            "Lateral wandering of a river's course. Never lets the water flow uphill."),
    LAKE_FREQUENCY("lake_frequency", Category.RIVERS, 0.00, 2.00, 1.00,
            "How many closed drainage basins fill with lakes."),
    WETLAND_FREQUENCY("wetland_frequency", Category.RIVERS, 0.00, 2.00, 0.50,
            "How often flat, poorly drained ground becomes wetland."),
    DRAINAGE_SCALE("drainage_scale", Category.ADVANCED, 0.50, 4.00, 1.00,
            "How much water the drainage network carries, by scaling the channel threshold."),
    CAVE_GENERATION("cave_generation", Category.SURFACE, 0.00, 2.00, 1.00,
            "Density of mountain caves. Zero disables cave carving and its water pockets."),
    GENERATE_STRUCTURES("generate_structures", Category.WORLD, 0.00, 1.00, 1.00, true,
            "Whether structures such as villages and temples may generate in this world.");

    /** UI grouping; the customize screen shows one tab per category. */
    public enum Category {
        WORLD("world"), CONTINENTS("continents"), MOUNTAINS("mountains"), RIVERS("rivers"),
        EROSION("erosion"), CLIMATE("climate"), SURFACE("surface"), ADVANCED("advanced");

        private final String id;
        Category(String id) { this.id = id; }
        public String id() { return id; }
    }

    // List.of infers a @NonNull element type, which Eclipse null analysis then reports as an unsafe
    // conversion into this unannotated declaration. The list is immutable and never null-element, so
    // the diagnostic is suppressed rather than worked around.
    @SuppressWarnings("null")
    public static final List<Category> CATEGORIES = List.of(Category.values());

    private final String jsonKey;
    private final Category category;
    private final double min;
    private final double max;
    private final double defaultValue;
    private final boolean integral;
    private final String tooltip;

    TerrainSetting(String jsonKey, Category category, double min, double max, double defaultValue, String tooltip) {
        this(jsonKey, category, min, max, defaultValue, false, tooltip);
    }

    TerrainSetting(String jsonKey, Category category, double min, double max, double defaultValue, boolean integral, String tooltip) {
        this.jsonKey = jsonKey;
        this.category = category;
        this.min = min;
        this.max = max;
        this.defaultValue = defaultValue;
        this.integral = integral;
        this.tooltip = tooltip;
    }

    public String jsonKey() { return jsonKey; }
    public Category category() { return category; }
    public double min() { return min; }
    public double max() { return max; }
    public double defaultValue() { return defaultValue; }
    public boolean integral() { return integral; }
    public String tooltip() { return tooltip; }

    /** Translation key for the setting's display name. */
    public String nameKey() { return "realisticterrain.setting." + jsonKey; }
    /** Translation key for the setting's tooltip. */
    public String tooltipKey() { return "realisticterrain.setting." + jsonKey + ".tooltip"; }
    /** Translation key for the category tab. */
    public String categoryKey() { return "realisticterrain.category." + category.id(); }

    /** Clamps a value into this setting's safe range, rounding it when it is an integer setting. */
    public double clamp(double value) {
        double v = Double.isFinite(value) ? value : defaultValue;
        if (v < min) v = min;
        if (v > max) v = max;
        return integral ? Math.rint(v) : v;
    }
}
