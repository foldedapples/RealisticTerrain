package com.cokedoutsnail.realisticterrain.worldgen.hydro;

/**
 * What kind of water (if any) a column carries.
 *
 * <p>This exists so that "dry ground" is an explicit state rather than a sentinel height. The old
 * model represented a dry column by giving it a fake water surface equal to its own terrain height,
 * which is why dry land could end up receiving water whenever the carve maths drifted by a block.
 * With {@link #NONE} the generator has an unambiguous instruction: place no water here at all.
 */
public enum WaterBodyType {
    /** No water. The generator must not place water on this column. */
    NONE,
    /** Connected to the global ocean; surface is sea level. */
    OCEAN,
    /** A flowing channel; surface is the channel's water plane. */
    RIVER,
    /** A standing body in a closed basin; surface is the basin's spill elevation. */
    LAKE,
    /** Saturated, poorly drained ground; surface is a shallow film close to grade. */
    WETLAND
}
