package com.cokedoutsnail.realisticterrain.worldgen.hydro;

/**
 * Everything the terrain engine is allowed to know about one water column.
 *
 * <p>The important change from the old drainage contract is {@link #waterBody}. Previously a dry
 * column was published with a "water surface" equal to its own ground height, so every consumer had
 * to re-derive whether water actually existed; a small carve or interpolation error was enough to
 * turn dry land into a river. Here {@link WaterBodyType#NONE} is explicit and the generator is
 * obliged to place no water.
 *
 * <p>The geometric fields satisfy three invariants that the tests assert directly:
 *
 * <pre>
 *   bedElevation &lt; waterSurface &lt; bankElevation          (whenever waterBody != NONE)
 *   waterSurface is non-increasing along the flow path   (per connected channel)
 *   distanceToCenter &gt;= halfWidth                        (whenever waterBody == NONE)
 * </pre>
 *
 * @param discharge absolutely accumulated upstream runoff in coarse cells (not normalised); it is
 *                  monotonically non-decreasing downstream by construction.
 * @param order     Strahler stream order normalised to {@code 0..1}.
 * @param river     {@code 0..1} cross-section strength; 1 on the channel centre line.
 * @param lake      {@code 0..1} closed-basin strength.
 * @param wetland   {@code 0..1} poorly drained ground strength.
 */
public record RiverSample(
        WaterBodyType waterBody,
        double bedElevation,
        double waterSurface,
        double bankElevation,
        double distanceToCenter,
        double width,
        double depth,
        double discharge,
        double order,
        double river,
        double lake,
        double wetland,
        double flowX,
        double flowZ
) {
    /** No water: infinite distance to any channel, no bed, no surface. */
    public static final RiverSample DRY = new RiverSample(
            WaterBodyType.NONE,
            Double.NaN, Double.NEGATIVE_INFINITY, Double.NaN,
            Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

    /** True when the generator should place water on this column. */
    public boolean isWater() {
        return waterBody != WaterBodyType.NONE;
    }

    /** True when the geometry is self-consistent (bed below surface below bank). */
    public boolean geometryIsConsistent() {
        if (!isWater()) return true;
        return bedElevation < waterSurface && waterSurface < bankElevation;
    }
}
