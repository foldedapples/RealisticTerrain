package com.cokedoutsnail.realisticterrain.worldgen;

/**
 * Explicit physical water states shared by the biome classifier and the surface resolver.
 *
 * <p>Before Phase 1 the generator decided "is this column submerged?" with a signed comparison
 * whose right-hand side was almost always negative:
 *
 * <pre>sm.waterLevel() - sm.height() &gt; (pseudo(x, z) - 0.5) * 1.4 - 2.0</pre>
 *
 * <p>Because {@code pseudo} lies in {@code [0, 1)}, the threshold was close to {@code -2.0}, so any
 * dry column sitting a fraction of a block above the water surface was reported as submerged. That
 * single test is what painted the wide sand fringe along every shoreline and river in the "all-sand"
 * screenshots. It has been replaced by the explicit states below.
 *
 * <p>Every predicate here takes the <em>continuous, unfloored</em> {@link TerrainModel.Sample}; the
 * basin/coast masks are read off the model's own fields rather than reconstructed from floored,
 * integer heights, so a channel can never be mistaken for dry land by a rounding step.
 */
public final class TerrainHydrology {
    private TerrainHydrology() {
    }

    /**
     * Distance below the water surface that counts as open water. Columns within this band of the
     * surface are treated as neither dry land nor deep water, which is what makes a shoreline a
     * single contour instead of a one-block coin flip. The band is a physical tolerance, not a
     * classification threshold: it never moves a clearly dry column below the waterline.
     */
    public static final double WATER_EPSILON = 0.20;

    /** River-mask strength at or above which a submerged column is a flowing channel. */
    public static final double RIVER_THRESHOLD = 0.25;

    /** Lake-mask strength at or above which a submerged column is a lake basin. */
    public static final double LAKE_THRESHOLD = 0.30;

    /** Open-water depth, in blocks, beyond which ocean is deep ocean. */
    public static final double DEEP_OCEAN_DEPTH = 26.0;

    /** Depth, in blocks, beyond which a shallow submerged shelf reads as beach rather than ocean. */
    public static final double SHELF_DEPTH = 4.0;

    /** True when the column's ground actually lies below its own water surface. */
    public static boolean underwater(TerrainModel.Sample s) {
        return s.height() < s.waterLevel() - WATER_EPSILON;
    }

    /** True when the column carries a strong enough river mask to be a channel. */
    public static boolean riverbed(TerrainModel.Sample s) {
        return s.river() >= RIVER_THRESHOLD;
    }

    /** True when the column carries a strong enough lake mask to be a basin. */
    public static boolean lakebed(TerrainModel.Sample s) {
        return s.lake() >= LAKE_THRESHOLD;
    }

    /**
     * True when the column is open ocean: submerged AND on the ocean side of the continental
     * coastline. Continentalness, not height, is the field the shoreline is actually drawn from, so
     * this stays true out over the shelf and false in a below-sea-level inland basin.
     */
    public static boolean ocean(TerrainModel.Sample s, TerrainSettings settings) {
        return underwater(s) && s.continent() < settings.coastLine();
    }

    /** Water depth in blocks; negative on dry land. */
    public static double waterDepth(TerrainModel.Sample s) {
        return s.waterLevel() - s.height();
    }
}
