package com.cokedoutsnail.realisticterrain.worldgen;

/**
 * The authoritative, registry-free biome classification of a world column.
 *
 * <p>This is a <em>pure result type</em>: it contains no Minecraft registry objects, no
 * {@code RegistryKey} and no {@code BlockState}. Everything that needs to know "what kind of place
 * is this?" - the biome source (which converts a type to a vanilla biome key), the chunk generator
 * (which converts a type to surface materials) and the tree selector - reads the same value from
 * {@link TerrainBiomeClassifier}. There is deliberately no second, independent temperature/moisture
 * desert test anywhere else: a column has exactly one biome type and exactly one surface derived
 * from it.
 *
 * <p>Keeping the type registry-free is what makes the whole classification layer testable in a
 * plain JUnit JVM with no Minecraft bootstrap, which in turn is what lets the distribution,
 * determinism and surface tests run on every build.
 */
public enum TerrainBiomeType {
    /** Open ocean deeper than the shelf; abyssal, typically far from land. */
    DEEP_OCEAN(true),
    /** Open ocean over the continental shelf and shelf break. */
    OCEAN(true),
    /** A flowing channel - the column sits in the carved bed of a drainage line. */
    RIVER(true),
    /** A standing body of inland water - a closed basin filled above its own floor. */
    LAKE(true),
    /** The narrow sand/gravel band where genuine coastline meets sea level. */
    BEACH(false),
    /** Hot and genuinely dry; sand over sandstone. */
    DESERT(false),
    /** Warm and seasonally dry - sparse, but not a desert. */
    SAVANNA(false),
    /** The ordinary temperate fallback: grass over dirt. */
    PLAINS(false),
    /** Temperate with adequate moisture. */
    FOREST(false),
    /** Temperate, wet and dense; the rich end of the forest ecotone. */
    DARK_FOREST(false),
    /** Temperate, moderately moist birch woodland. */
    BIRCH_FOREST(false),
    /** Hot and very wet. */
    JUNGLE(false),
    /** Low, flat, poorly drained and wet. */
    SWAMP(false),
    /** Cold coniferous woodland. */
    TAIGA(false),
    /** Cold lowland. */
    SNOWY_PLAINS(false),
    /** Cold subalpine woodland just below the tree line. */
    GROVE(false),
    /** Temperate alpine grassland above the tree line. */
    MEADOW(false),
    /** High, cold and snow-covered mountain flank. */
    SNOWY_SLOPES(false),
    /** High, steep, rocky summits and scree. */
    STONY_PEAKS(false);

    private final boolean aquatic;

    TerrainBiomeType(boolean aquatic) {
        this.aquatic = aquatic;
    }

    /** True for the four water categories (deep ocean, ocean, river, lake). */
    public boolean isAquatic() {
        return aquatic;
    }

    /** True for dry-land categories; the complement of {@link #isAquatic()}. */
    public boolean isLand() {
        return !aquatic;
    }

    /** True when the biome's surface is expected to carry permanent snow/ice. */
    public boolean isSnowy() {
        return this == SNOWY_PLAINS || this == SNOWY_SLOPES;
    }

    /** True when the biome is expected to support a grass/soil surface. */
    public boolean isSoiled() {
        return this == PLAINS || this == FOREST || this == DARK_FOREST || this == BIRCH_FOREST
                || this == SAVANNA || this == MEADOW || this == TAIGA || this == GROVE
                || this == JUNGLE || this == SWAMP;
    }
}
