package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.noise.Noise2D;

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
        return clamp(h, MIN_SURFACE, MAX_SURFACE);
    }

    /**
     * The smooth tectonic fall line at a column - {@code tectonicBase} with the cached coarse fields
     * resolved. Package-private rather than private so the terrain tests can compare the surface the
     * channels are cut into against the surface that comes out of them.
     */
    static double baseHeight(double x, double z, long seed, TerrainSettings s) {
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

        // --- drainage: twice-domain-warped signed field yields dendritic river basins ---
        double wx = x + c.warpX() * 420.0;
        double wz = z + c.warpZ() * 420.0;
        double wx2 = wx + Noise2D.fbm(wx / 170.0, wz / 170.0, seed + 193, 2, 2.3, .5) * 150.0;
        double wz2 = wz + Noise2D.fbm(wx / 170.0, wz / 170.0, seed + 197, 2, 2.3, .5) * 150.0;
        double drainage = Noise2D.fbm(wx2 / (760.0 / s.riverFrequency()), wz2 / (760.0 / s.riverFrequency()), seed + 151, 3, 2.0, .52);
        double lakeNoise = Noise2D.fbm(wx2 / 920.0, wz2 / 920.0, seed + 167, 4, 2.07, .5);

        // --- fluvial canyon dissection of high plateaus ---
        double dissection = Math.max(0.0, Noise2D.ridged(wx2 / 260.0, wz2 / 260.0, seed + 87, 3));
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

        // --- river corridor: a graded (Hermite) channel, ReTerraForged style ---
        // The previous model subtracted depth along a LINEAR tent, max(0, 1 - |field| / (0.06 * w)),
        // and two things were wrong with it.
        //
        //   1. A tent is a straight-sided V with a hard crease along its centre line, and its width
        //      in *blocks* was whatever the drainage field's gradient happened to imply: tens of
        //      blocks in one place, a couple of hundred wherever the field flattened out. That is
        //      the "massive, harsh trench", and the occasional inland fjord.
        //   2. The water surface was derived from the locally CARVED height, h + river * 6. Such a
        //      surface is not monotone across the channel, so once it had been floored to an integer
        //      y it stepped up and down several times on the way out to the bank. Those steps are
        //      the concentric stair-step rings of water, sand and gravel.
        //
        // So: the corridor is measured in BLOCKS - |field| / |grad field| is the standard first-order
        // estimate of the distance to the field's zero set - and clamped to a configured reach; the
        // cross-section is a smoothstep S-curve that is flat-sloped at the bed AND at the rim; and
        // the water surface is taken from the BED rather than from the carved height.
        double bedWidth = clamp(RIVER_BED_WIDTH * s.riverWidth(), RIVER_BED_WIDTH_MIN, RIVER_BED_WIDTH_MAX);
        double bankWidth = clamp(RIVER_BANK_WIDTH * s.riverWidth(), RIVER_BANK_WIDTH_MIN, RIVER_BANK_WIDTH_MAX);
        double channelReach = bedWidth + bankWidth;
        // The drainage field's gradient grows linearly with its frequency, so the radius that has to be
        // covered - which is fixed in blocks - has to be converted with the same factor.
        double gateField = channelReach * RIVER_GATE_GRADIENT * s.riverFrequency();
        double river = 0.0;      // set below once the cross-section profile is known
        double bankFactor = 1.0; // 0 across the flat bed, 1 at the rim / where there is no channel
        double channel = 0.0;    // 1 where a real channel runs, 0 outside; CONSTANT across the section
        if (Math.abs(drainage) < gateField) {
            double gx = (riverField(x + RIVER_PROBE, z, seed, s) - drainage) / RIVER_PROBE;
            double gz = (riverField(x, z + RIVER_PROBE, seed, s) - drainage) / RIVER_PROBE;
            double gMag = Math.sqrt(gx * gx + gz * gz) + 1e-9;
            // Distance to the channel's centre line, in blocks. Capped at the configured reach, so
            // a flat patch of the drainage field can no longer widen a river past its clamped width;
            // the cap is continuous and the bank profile has already reached the hillside by then,
            // so nothing is clipped by it.
            double distance = Math.min(Math.abs(drainage) / gMag, channelReach);

            // Hermite/S-curve bank: flat bed, organic bank, and zero slope at BOTH ends, so there is
            // no crease along the centre line and no ridge where the bank rejoins the terrain.
            double t = clamp((distance - bedWidth) / bankWidth);
            bankFactor = t * t * (3.0 - 2.0 * t);

            // A corridor may only carve where it runs downhill along the local fall line.
            double px = baseHeight(x + RIVER_PROBE, z, seed, s);
            double pz = baseHeight(x, z + RIVER_PROBE, seed, s);
            double dhx = (px - hSmooth) / RIVER_PROBE;
            double dhz = (pz - hSmooth) / RIVER_PROBE;
            double slope = Math.sqrt(dhx * dhx + dhz * dhz);
            double tx = gz / gMag, tz = -gx / gMag; // unit tangent of the channel
            double dot = (dhx * tx + dhz * tz) / (slope + 1e-9);
            // Squaring this (as an earlier version did) pushes every partially-misaligned stretch
            // toward zero, chopping an otherwise continuous corridor into disconnected fragments
            // wherever the tangent and the downhill direction merely disagree a little rather than
            // a lot - measured as "1408 components, biggest 0.8% of river cells" against a
            // connectivity-preserving version of the same field. Left linear, a channel stays above
            // the RIVER/LAKE biome threshold along its whole run instead of just its best-aligned
            // stretches.
            double slopeBlend = clamp(slope * 6.0);
            double align = slopeBlend * Math.abs(dot) + (1.0 - slopeBlend);
            // Rivers taper out of the folded ranges and strengthen downstream toward the sea.
            double downstream = clamp(1.0 - (hSmooth - s.seaLevel()) / 190.0);
            double shoreFade = clamp((hSmooth - (s.seaLevel() - 30.0)) / 20.0); // keep the deep seafloor smooth
            double gate = clamp(1.0 - (hSmooth - (s.seaLevel() + 20.0)) / 260.0) * (1.0 - c.convergent() * 0.9) * shoreFade;
            // Window that reaches exactly zero at the pre-filter edge, so the cheap reject can never
            // leave a seam behind however steep the drainage field happens to be at that point.
            double reachWindow = sstep((gateField - Math.abs(drainage)) / RIVER_GATE_FADE);
            // Deliberately independent of bankFactor: this says HOW MUCH river runs here, not where
            // across the cross-section the column sits, so it is the same on the bed and on both
            // banks. The flat water surface below depends on exactly that.
            channel = clamp(align * gate * reachWindow * (0.30 + 0.70 * downstream));
        }
        // Channel strength at this column (unchanged meaning for biomes and materials): full across
        // the flat bed, tapering to nothing at the rim.
        river = clamp((1.0 - bankFactor) * channel);

        // --- lakes: only genuine basins low enough to hold water, clear of main channels ---
        double basin = clamp((s.seaLevel() + 6.0 - h0) / 34.0);
        double lake = clamp((lakeNoise - 0.40) / 0.18);
        lake = lake * lake * basin * (1.0 - c.convergent()) * (1.0 - clamp(river * 3.0));

        // Incision and water column, both anchored to the channel's CENTRE-LINE depth. Only the
        // incision is graded across the bank, by the Hermite profile above; the anchor that sets the
        // water surface is the same on the bed and on both banks. Two consequences, both deliberate:
        //
        //   * the water surface is a flat plane across the cross-section (the anchor does not vary
        //     across it), so it is monotone by construction and cannot terrace into concentric rings;
        //   * the water reaches the ground only where the bed has been cut deeper than the surface,
        //     i.e. over a band of the bank, so the shoreline lands on real bank and never floods the
        //     whole valley.
        //
        // Both anchors taper to zero with the channel and the basin mask, so nothing has to be switched
        // off with a hard threshold. (The old `if (river > .02)` / `if (lake > .05)` pair put a step
        // discontinuity straight into the water surface, which the block floor() then rendered as a
        // stepped ring.)
        double depthScale = 0.5 + 0.5 * s.riverDepth();
        double riverAnchor = RIVER_BED_DEPTH * depthScale * channel;
        double lakeAnchor = LAKE_BED_DEPTH * depthScale * lake;
        double riverProfile = 1.0 - bankFactor; // 1 across the flat bed, 0 where the bank meets the hills
        double lakeProfile = sstep(lake);       // 1 in the middle of a basin, 0 at its shore
        // The two bodies are mutually exclusive (the lake mask was multiplied by 1 - 3 * river just
        // above), so the sums below are selects rather than mixtures, and every term is continuous.
        double incision = riverAnchor * riverProfile + lakeAnchor * lakeProfile;
        double h = clamp(h0 - incision, MIN_SURFACE, MAX_SURFACE);

        // Both fills are strictly below 1, so the water level always sits below the surrounding fall
        // line: a channel can never lift water above its own banks, for any slider combination, and it
        // equals h0 exactly where there is no channel or basin at all - so no film of water is ever
        // left floating on dry ground. Water still fills to the configured sea level wherever the bed
        // lies below it, so river mouths and ocean shelves stay flooded exactly as before.
        double waterColumn = riverAnchor * RIVER_WATER_FILL + lakeAnchor * LAKE_WATER_FILL;
        double inlandWater = Math.max(s.seaLevel(), h0 - riverAnchor - lakeAnchor + waterColumn);

        double climateMoisture = Noise2D.fbm(x / (1700.0 * s.biomeScale()), z / (1700.0 * s.biomeScale()), seed + 191, 4, 2, .5);
        // Drainage traffic can only ADD moisture. A stream through an arid plain is an oasis; it never
        // dries anything out, and it must not silently rewrite the climate either, because the biome
        // thresholds downstream are tuned to the climate field's own scale. So the bonus only starts at
        // the measured median traffic: ordinary ground that merely drains towards somewhere else is
        // left exactly as the climate set it, and only the channels and the slopes feeding them get
        // wetter. That is the Phase-5 rule - lush where the water ran, sparse where it did not.
        double moisture = clamp(climateMoisture
                + Math.max(0.0, hydro.moisture() - MOISTURE_FLOOR) * MOISTURE_BOOST, -1.0, 1.0);
        double temperature = Noise2D.fbm(x / (2200.0 * s.biomeScale()), z / (2200.0 * s.biomeScale()), seed + 211, 4, 2, .5)
                - Math.max(0, h0 - 250) / 1650.0;
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