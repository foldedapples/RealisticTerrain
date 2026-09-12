package com.cokedoutsnail.realisticterrain.worldgen;

/**
 * A registry-free surface material. The resolver produces these; the chunk generator maps each one
 * to a vanilla {@code BlockState} at the single boundary between the pure terrain logic and
 * Minecraft.
 *
 * <p>Keeping the resolver on this enum (rather than on {@code BlockState}) is what makes the surface
 * rules testable in a plain JUnit JVM: "desert surface is sand, sandstone beneath" is a statement
 * about {@link #SAND} and {@link #SANDSTONE}, not about registry objects.
 */
public enum SurfaceMaterial {
    BEDROCK,
    DEEPSLATE,
    STONE,
    GRAVEL,
    SAND,
    SANDSTONE,
    RED_SAND,
    RED_SANDSTONE,
    CLAY,
    MUD,
    GRASS_BLOCK,
    DIRT,
    COARSE_DIRT,
    PODZOL,
    MOSS_BLOCK,
    SNOW_BLOCK,
    ICE,
    WATER,
    AIR,
    BASALT,
    GRANITE,
    DIORITE,
    TERRACOTTA;

    /** True for the loose / soft top-soil materials that mark a habitable land surface. */
    public boolean isSoil() {
        return this == GRASS_BLOCK || this == DIRT || this == COARSE_DIRT || this == PODZOL
                || this == MUD || this == MOSS_BLOCK;
    }

    /** True for the sandy materials a desert or a beach is built from. */
    public boolean isSand() {
        return this == SAND || this == SANDSTONE || this == RED_SAND || this == RED_SANDSTONE;
    }
}
