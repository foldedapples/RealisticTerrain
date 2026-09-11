package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.noise.Noise2D;
import com.cokedoutsnail.realisticterrain.worldgen.hydro.Drainage;

/**
 * Shared mathematical terrain model used by both chunk generation and the live preview.
 *
 * <p>Phase 2 rewrite: terrain shape now comes from a plate-tectonic heightmap instead of raw
 * 3D Perlin noise. Continents are 2D cellular plates (see {@link CellularNoise}: collision margins
 * raise folded orogeny belts, divergent margins carve rift valleys and ocean trenches), the slow
 * fields are cached on a coarse lattice ({@link TerrainCache}) and smoothly interpolated, drainage
 * uses twice-domain-warped noise for dendritic river basins, and plateaus are dissected by gully
 * noise into steep canyons. Block-level strata are decided in {@link RealisticChunkGenerator}.
 */
public final class TerrainModel {
    private TerrainModel() {}

    /**
     * One column of generated surface data. All scalar fields are continuous over X/Z so
     * neighbouring columns never snap; blocky steps only appear at the final floor() in
     * {@link RealisticChunkGenerator}.
     */
    public record Sample(
            double height,      // final ground elevation (or seafloor) after carving
            double waterLevel,  // local water surface (sea, lake or river)
            double river,       // 0..1 river/water-corridor strength
            double lake,        // 0..1 lake-basin strength
            double ridge,       // 0..1 local ridgedness (fold fabric)
            double moisture,    // -1..1 climate humidity
            double temperature, // -1..1 climate warmth (altitude-corrected)
            double slopeHint,   // 0..1 cheap relative steepness proxy (erosion * ridge)
            double plates,      // 0..1 tectonic plate identity at this column
            double convergent,  // 0..1 collision-margin strength (orogeny host)
            double divergent,   // 0..1 rift-margin strength (valley/trench host)
            double fault,       // 0..1 proximity to an active plate margin
            double soil,        // 0..1 soil depth from hydraulic erosion (0=bedrock, 1=deep soil)
            double continent    // -1..1 raw continentalness, for genuine coastal proximity (see TerrainBiomeSource)
    ) {}

    /** Probe distance, in blocks, for the drainage field's gradient and the fall-line slope. */
    private static final double RIVER_PROBE = 28.0;

    /**
     * Mapping from the droplet pass's water traffic onto the climate moisture field. {@code FLOOR} is
     * the traffic the median column sees (measured at 0.028), so anything at or below it contributes
     * nothing and the biome thresholds keep their existing meaning; {@code BOOST} then scales the
     * excess so a main drainage line (measured p99 traffic 0.26) adds roughly +0.35 of moisture -
     * enough to turn an arid plain into a gallery forest without drowning the climate that put it
     * there. It is deliberately one-sided: water running across ground can only ever make it wetter.
     */
    private static final double MOISTURE_FLOOR = 0.028;
    private static final double MOISTURE_BOOST = 1.5;

    /**
     * Normalisation for the climate noise fields, the value the {@code fbm(x, z, seed, 4, 2, .5)}
     * sampler configuration actually reaches near its extremes (measured p-max over a 12k&times;12k
     * block sweep: {@literal ~0.62}).
     *
     * <p>It is here to fix a real biome-distribution bug. A four-octave fractal whose octaves keep
     * halving only reaches about 60% of its nominal {@code [-1, 1]} range before the weights pull the
     * sum back towards the middle, so the raw field lived in roughly {@code [-0.6, 0.6]}. Every biome
     * threshold in {@link TerrainBiomeSource} is stated on the nominal {@code [-1, 1]} scale - desert
     * at {@code moisture < -0.1}, jungle at {@code temperature > 0.35} - so a compressed field pushes
     * the whole world towards the middle of the table: the hot/cold and wet/dry extremes that pick
     * desert, savanna, jungle and taiga are barely reachable, and the map collapses onto the few
     * temperate entries in the middle (forest, plains, birch forest). Dividing by this constant
     * stretches the field so the thresholds land where their names say they do, and the
     * {@link #clamp} below is what keeps the stretch from ever leaving {@code [-1, 1]}. It multiplies
     * the noise only, never the altitude correction, so a tall range still reads colder than the
     * valley beside it at the same latitude.
     */
    private static final double CLIMATE_NORM = 0.62;

    /**
     * Half-width of a river's <em>flat</em> bed, in blocks, at {@code riverWidth = 1}. It is scaled
     * by the river-width slider and then clamped: those clamps are the guarantee that a wide-river
     * setting produces a big river rather than an inland fjord, because the corridor is now measured
     * in blocks instead of in units of the drainage field.
     */
    private static final double RIVER_BED_WIDTH = 3.0;
    private static final double RIVER_BED_WIDTH_MIN = 1.5;
    private static final double RIVER_BED_WIDTH_MAX = 9.0;
    /** Width, in blocks, of the sloped bank that carries the flat bed back up to the hillside. */
    private static final double RIVER_BANK_WIDTH = 15.0;
    private static final double RIVER_BANK_WIDTH_MIN = 6.0;
    private static final double RIVER_BANK_WIDTH_MAX = 30.0;
    /**
     * Incision of a channel's centre line into the fall line, in blocks, at {@code riverDepth = 1}.
     * The water column is derived from this through {@link #RIVER_WATER_FILL}, never tuned separately.
     */
    private static final double RIVER_BED_DEPTH = 11.0;
    /**
     * Fraction of a channel's centre-line incision that stands as water. The incision is derived at
     * {@link #RIVER_BED_DEPTH} and the water level from the incision, so this fraction being below 1
     * is what structurally guarantees the water always sits below the ground beside the channel - for
     * every combination of sliders - and it is also what sets how far up the bank the shoreline
     * reaches. 0.55 gives a proper river rather than a trickle: about 6 blocks of water in an
     * 11-block channel at {@code riverDepth = 1}, with the shoreline a little over halfway out
     * along the bank.
     */
    private static final double RIVER_WATER_FILL = 0.55;
    /** Centre-line incision of a lake basin, in blocks, and the fraction of it that stands as water. */
    private static final double LAKE_BED_DEPTH = 10.0;
    private static final double LAKE_WATER_FILL = 0.40;
    /**
     * Nominal drainage-field gradient, in field units per block, at {@code riverFrequency = 1}. The
     * pre-filter radius is {@code (bedWidth + bankWidth) * this * riverFrequency}, so the cheap reject
     * skips the gradient probes for the vast majority of columns while still covering the whole
     * corridor. Measured on the default field: the per-block gradient runs p50 = 0.0014, p90 = 0.0038,
     * p99 = 0.0068, so this (just over p90) covers the full corridor almost everywhere. It scales with
     * riverFrequency because a higher drainage frequency stretches the field's gradient linearly, and
     * the radius that has to be covered is fixed in blocks. Exactness is not required anyway: the
     * corridor is clamped to a fixed reach in blocks and the carve is additionally weighted by a window
     * that reaches exactly zero at the filter's edge, so an under-estimate tapers the outer bank
     * gracefully rather than leaving a cliff.
     */
    private static final double RIVER_GATE_GRADIENT = 0.0038;
    /** Width, in field units, of the fade that takes the carve to zero at the pre-filter edge. */
    private static final double RIVER_GATE_FADE = 0.03;

    /** Upper bound on the modelled surface height. */
    public static final double MAX_SURFACE = 1900.0;
    /**
     * Lower bound on the modelled surface height. The world floor is {@code y = -64}
     * (see {@link RealisticChunkGenerator#MIN_Y}), so the abyssal plain deliberately bottoms
     * out well above it. Previously the model was allowed down to -120, which is 56 blocks
     * <em>below</em> the world: those columns had no ground at all and their ocean sat open on
     * the void (the "holes dropping into the void" bug). Keeping the floor in-world guarantees
     * a solid crust beneath every column.
     */
    public static final double MIN_SURFACE = -52.0;

    /** Craton relief per unit of continentalness, in blocks. */
    private static final double CRATON_RELIEF = 115.0;
    /**
     * Continentalness span over which the abyssal plain reaches its full depth. Together with
     * {@link TerrainSettings#coastLine()} this sets how quickly the shelf falls away into the deep
     * ocean; it is kept fixed so the depth slider changes how deep the sea is, not how abrupt the
     * continental margin looks.
     */
    private static final double SHELF_SPAN = 0.85;

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Hermite/S-curve, i.e. smoothstep: 0 below 0, 1 above 1 and - the property that matters here -
     * flat-sloped at both ends, so a bank built from it has no crease at the channel's centre line
     * and no ridge where it rejoins the hillside.
     */
    private static double sstep(double v) {
        double t = clamp(v);
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * Smooth tectonic surface (before erosion, canyons, rivers and lakes): the "fall line" that
     * drainage and slope follow. Pure function of the cached coarse fields, so the river probe
     * and geomorphSlope re-run it cheaply.
     */
    private static double tectonicBase(double x, double z, TerrainSettings s, TerrainCache.Node c) {
        double continent = c.continent();
        // Craton base. The shoreline is placed at the configured coast line: land is
        // continent > coastLine, so this single number is the switch a player uses to trade ocean
        // for land. Lowering it pushes the shoreline seaward (more land); raising it moves the
        // shoreline inland and drowns the coasts. This is ReTerraForged's "coast" control point.
        double coast = s.coastLine();
        double craton = s.seaLevel() + (continent - coast) * CRATON_RELIEF * s.continentalScale();
        // Ocean basins deepen away from the shelf and bottom out on the abyssal plain rather than
        // punching through the world floor (MIN_SURFACE keeps them in-world). ReTerraForged's
        // "deepOcean" control point.
        double oceanBasin = -clamp((coast - continent) / SHELF_SPAN) * s.oceanDepth();
        // Orogeny: folded chains only where plates collide (convergent margins). The exponent used
        // to run 0.5-1.35 (0.5 + 0.85 * ridgeSharpness at ridgeSharpness in [0.25, 3]): anything
        // above 1 compresses the belt's ridged fabric toward its own extremes, so instead of a range
        // with foothills, shoulders and a crest it produced a nearly flat apron with a few needle-thin
        // spikes at full amplitude - "too tiny and crazy high" for exactly the columns whose belt
        // value happened to be near 1. Keeping the exponent at or below 1 spreads that same 0..1
        // range out over real shoulder terrain instead of concentrating it at the peak.
        double collision = c.convergent();
        double orogeny = Math.pow(c.belt(), 0.30 + 0.45 * s.ridgeSharpness());
        double collisionMask = collision * clamp(0.35 + 0.65 * c.plates());
        double range = orogeny * collisionMask * 560.0 * s.mountainHeight();
        // Divergent margins: ocean trenches below sea level, continental rifts carved into valleys.
        double trench = clamp(-continent) * c.divergent() * 95.0 * s.riverDepth();
        double continentalRift = clamp(continent) * c.divergent() * 60.0 * Math.max(1.0, s.canyonDepth()) * (0.6 + 0.4 * c.fault());
        // Broad macro highs and soft foothill aprons around the ranges.
        double foothills = clamp((c.macro() - 0.02) / 0.60) * (1.0 - collisionMask);
        double h = craton + oceanBasin + c.macro() * 40.0 + foothills * 95.0 * s.mountainHeight()
                + range - trench - continentalRift;
        return clamp(h, MIN_SURFACE, maxSurface(s));
    }

    /**
     * Highest surface the model may produce: the {@code maximum_terrain_y} setting, kept inside the
     * hard {@link #MAX_SURFACE} cap. Terrain is clamped here rather than at the chunk writer so every
     * consumer of {@link #sample} - carving, water, heightmaps, biomes - sees the same ceiling, and
     * because the cap is well below the dimension's build height there is always room left for snow,
     * trees and structures on top of the tallest peak.
     */
    public static double maxSurface(TerrainSettings s) {
        return clamp(s.maximumTerrainY(), 64.0, MAX_SURFACE);
    }

    /**
     * The smooth tectonic fall line at a column - {@code tectonicBase} with the cached coarse fields
     * resolved. Public because the drainage solver fills its regional grid from it: the flow network
     * must be computed on the same surface the channels are later cut into, and that surface must
     * exclude the channels themselves or the solver would chase its own incisions.
     */
    public static double baseHeight(double x, double z, long seed, TerrainSettings s) {
        return tectonicBase(x, z, s, TerrainCache.sample(x, z, seed, s));
    }

    /** True local slope (blocks per block) of the smooth tectonic fall line. */
    public static double geomorphSlope(long seed, double x, double z, TerrainSettings s) {
        seed ^= s.seedSalt();
        double d = 12.0;
        double sx = (baseHeight(x + d, z, seed, s) - baseHeight(x - d, z, seed, s)) / (2 * d);
        double sz = (baseHeight(x, z + d, seed, s) - baseHeight(x, z - d, seed, s)) / (2 * d);
        return Math.sqrt(sx * sx + sz * sz);
    }

    public static Sample sample(long seed, double x, double z, TerrainSettings s) {
        long worldSeed = seed;
        seed ^= s.seedSalt();
        TerrainCache.Node c = TerrainCache.sample(x, z, seed, s);
        double hSmooth = tectonicBase(x, z, s, c);

        // --- hydraulic erosion: droplet-based gully carving and valley filling ---
        // The pass skips itself at zero erosion intensity (see HydraulicErosion.sample), so that
        // slider position costs nothing and leaves the tectonic fall line exactly as it was.
        HydraulicErosion.Field hydro = HydraulicErosion.sample(x, z, worldSeed, s);

        // --- fine texture at full resolution (not cached, cheap) ---
        double erosion = Noise2D.fbm(x / 430.0, z / 430.0, seed + 83, 4, 2.11, .52);
        double ridgeDetail = clamp((Noise2D.fbm(x / 200.0, z / 200.0, seed + 223, 3, 2.15, .5) + 1.0) * 0.5);
        double ridge = Math.min(1.0, Math.pow(c.belt(), 0.5 + 0.6 * s.ridgeSharpness()) * (0.6 + 0.4 * ridgeDetail));

        // --- drainage network: real flow accumulation, order, basins and wetlands ---
        // The channel mask is no longer noise. Drainage solves a priority-flood + D8 flow network per
        // region and hands back discharge, Strahler order and closed-basin depth here. Meander warps
        // WHERE the network is sampled, never the height the water sits at, so a meander can move a
        // channel sideways but can never make it run uphill.
        double meander = s.meanderStrength();
        double mx = x, mz = z;
        if (meander > 0.01) {
            double amp = 55.0 * meander * s.riverWidth();
            mx = x + Noise2D.fbm(x / 640.0, z / 640.0, seed + 733, 2, 2.3, .5) * amp;
            mz = z + Noise2D.fbm(x / 640.0, z / 640.0, seed + 739, 2, 2.3, .5) * amp;
        }
        Drainage.Cell d = Drainage.sample(mx, mz, seed, s);
        double discharge = d.discharge();
        double order = d.order();

        // --- fluvial canyon dissection of high plateaus ---
        double dissection = Math.max(0.0, Noise2D.ridged(x / 260.0, z / 260.0, seed + 87, 3));
        double canyonHost = clamp((hSmooth - (s.seaLevel() + 20.0)) / 230.0) * (1.0 - c.convergent() * 0.6);
        double canyon = Math.pow(dissection, 1.8) * canyonHost * (8.0 + 42.0 * s.canyonDepth());

        // Eroded fall line. Every channel below is cut into this surface, and it is the only height
        // the channel model is allowed to read. It stays in double precision all the way down to the
        // single floor() in RealisticChunkGenerator#populateNoise, which is where a block is placed.
        // The droplet pass contributes a delta that is already in blocks and already clamped at its
        // source, so the Erosion slider is the only thing scaling it here - and at zero the pass
        // contributes nothing at all.
        double h0 = hSmooth - Math.abs(erosion) * ridge * 42.0 * s.erosionIntensity() - canyon
                  + hydro.delta() * s.erosionIntensity();
        // Wetland is soft, saturated ground: it settles very slightly and holds moisture, but it can
        // never LIFT the surface, so it cannot produce water above its own banks.
        h0 -= d.wetland() * 1.4 * s.wetlandFrequency();

        // Width, depth and water level all come from the drainage network instead of from a noise
        // field. Two different quantities are needed, and keeping them apart is what makes the
        // channel behave:
        //
        //   * channelProfile is the cross-section shape - 0 at the rim, 1 on the centre line - and it
        //     grades the INCISION.
        //   * channelPresence saturates to 1 across the whole water-bearing part of the section, and
        //     it anchors the WATER LEVEL, which is therefore a flat plane rather than a copy of the
        //     bed. Deriving the surface from the carved ground instead would make every column with
        //     any incision at all "wet", so the river would spread across its own banks.
        //
        // `size` grows with discharge and Strahler order - the stream-power relationship in its
        // simplest game-ready form - so a trunk river is genuinely wider and deeper than the
        // tributary feeding it, while the sliders set the overall scale rather than the shape.
        double depthScale = 0.5 + 0.5 * s.riverDepth();
        double size = 0.45 + 0.85 * discharge + 0.30 * order;
        double channelProfile = clamp((d.river() - 0.16) / 0.74);
        double channelPresence = sstep(clamp((channelProfile - 0.30) / 0.20));
        double riverBed = RIVER_BED_DEPTH * depthScale * size * channelProfile;
        double riverPlane = RIVER_BED_DEPTH * depthScale * size * channelPresence;
        double lakeProfile = sstep(clamp((d.lake() - 0.15) / 0.85));
        double lakeBed = LAKE_BED_DEPTH * depthScale * lakeProfile;
        double river = channelProfile;
        double lake = lakeProfile;
        // (The derived gradient/slope gate that used to live here is gone. It existed to stop the old
        // noise field from carving where it was not running downhill; the drainage network already
        // flows downhill by construction, so the gate has nothing left to do. The old noise-based
        // lake mask is gone for the same reason - basins now come from the solver's depression fill.)

        // Incision follows the cross-section profile; the water plane follows the corridor presence,
        // so it is flat across the channel and falls away to the local fall line outside it. Water
        // therefore appears exactly where the bed has been cut deeper than the plane - a band around
        // the centre line - and never on the shoulders, which is what keeps the shoreline a single
        // contour instead of a ring.
        double incision = riverBed + lakeBed;
        double h = clamp(h0 - incision, MIN_SURFACE, maxSurface(s));

        // Both fills are strictly below 1, so the water level always sits below the surrounding fall
        // line: a channel can never lift water above its own banks, for any slider combination, and it
        // equals h0 exactly where there is no channel or basin at all - so no film of water is ever
        // left floating on dry ground.
        double waterLevel = h0
                - riverPlane * (1.0 - RIVER_WATER_FILL)
                - lakeBed * (1.0 - LAKE_WATER_FILL);
        double inlandWater = Math.max(s.seaLevel(), waterLevel);
        // A closed basin's real water level is its spill elevation, which the regional solver knows
        // and this height model does not. Using it is what makes an endorheic lake fill to its rim
        // instead of leaking away or overflowing it. The guard keeps the extra water out of shallow
        // dips that only the erosion delta lowered, which would otherwise leave a film of water
        // lying over dry land.
        if (lakeProfile > 0.5 && d.waterSurface() > h + 2.0) {
            inlandWater = Math.max(inlandWater, d.waterSurface());
        }

        // Low-frequency value-noise fbm, stretched by CLIMATE_NORM so its warm/cold and wet/dry
        // extremes actually reach the biome table's thresholds, then clamped to the nominal range.
        double climateMoisture = clamp(Noise2D.fbm(x / (1700.0 * s.biomeScale()), z / (1700.0 * s.biomeScale()), seed + 191, 4, 2, .5) / CLIMATE_NORM, -1.0, 1.0);
        // Drainage traffic can only ADD moisture. A stream through an arid plain is an oasis; it never
        // dries anything out, and it must not silently rewrite the climate either, because the biome
        // thresholds downstream are tuned to the climate field's own scale. So the bonus only starts at
        // the measured median traffic: ordinary ground that merely drains towards somewhere else is
        // left exactly as the climate set it, and only the channels and the slopes feeding them get
        // wetter. That is the Phase-5 rule - lush where the water ran, sparse where it did not.
        double moisture = clamp(climateMoisture
                + Math.max(0.0, hydro.moisture() - MOISTURE_FLOOR) * MOISTURE_BOOST, -1.0, 1.0);
        // Same normalisation for the temperature field, then the altitude lapse: high ground inside a
        // zone is colder than the low ground beside it, but the noise stretch is applied first so the
        // climate zones themselves still span the full range.
        double temperature = clamp(Noise2D.fbm(x / (2200.0 * s.biomeScale()), z / (2200.0 * s.biomeScale()), seed + 211, 4, 2, .5) / CLIMATE_NORM
                - Math.max(0, h0 - 250) / 1650.0, -1.0, 1.0);
        double slopeHint = clamp(Math.abs(erosion) * (0.4 + 0.6 * ridge));

        return new Sample(h, inlandWater, river, lake, ridge, moisture, temperature, slopeHint,
                c.plates(), c.convergent(), c.divergent(), c.fault(), hydro.soil(), c.continent());
    }

    public static boolean cave(long seed, int x, int y, int z, TerrainSettings s) {
        if (y > 1500 || y < -48) return false;
        double n1 = Noise2D.value((x + y * .42) / 72.0, (z - y * .31) / 72.0, seed + 701);
        double n2 = Noise2D.value((x - y * .23) / 38.0, (z + y * .37) / 38.0, seed + 709);
        double threshold = .78 - Math.min(.12, s.roughness() * .035);
        return Math.abs(n1 * .68 + n2 * .32) > threshold;
    }

    /**
     * The raw drainage field: zero along a channel's centre line, positive on one side of it and
     * negative on the other. Package-private rather than private so the terrain tests can measure the
     * channel geometry directly instead of re-deriving the field and drifting out of sync with it.
     */
    static double riverField(double x, double z, long seed, TerrainSettings s) {
        TerrainCache.Node c = TerrainCache.sample(x, z, seed, s);
        double wx = x + c.warpX() * 420.0;
        double wz = z + c.warpZ() * 420.0;
        double wx2 = wx + Noise2D.fbm(wx / 170.0, wz / 170.0, seed + 193, 2, 2.3, .5) * 150.0;
        double wz2 = wz + Noise2D.fbm(wx / 170.0, wz / 170.0, seed + 197, 2, 2.3, .5) * 150.0;
        return Noise2D.fbm(wx2 / (760.0 / s.riverFrequency()), wz2 / (760.0 / s.riverFrequency()), seed + 151, 3, 2.0, .52);
    }
}