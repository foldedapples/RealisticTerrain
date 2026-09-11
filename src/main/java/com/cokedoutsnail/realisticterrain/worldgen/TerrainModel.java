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
            double fault        // 0..1 proximity to an active plate margin
    ) {}

    private static final double RIVER_PROBE = 28.0;

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
    private static final double SHELF_SPAN = 0.55;

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
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
        // Orogeny: folded chains only where plates collide (convergent margins).
        double collision = c.convergent();
        double orogeny = Math.pow(c.belt(), 0.5 + 0.85 * s.ridgeSharpness());
        double collisionMask = collision * clamp(0.35 + 0.65 * c.plates());
        double range = orogeny * collisionMask * 620.0 * s.mountainHeight();
        // Divergent margins: ocean trenches below sea level, continental rifts carved into valleys.
        double trench = clamp(-continent) * c.divergent() * 95.0 * s.riverDepth();
        double continentalRift = clamp(continent) * c.divergent() * 60.0 * Math.max(1.0, s.canyonDepth()) * (0.6 + 0.4 * c.fault());
        // Broad macro highs and soft foothill aprons around the ranges.
        double foothills = clamp((c.macro() - 0.02) / 0.60) * (1.0 - collisionMask);
        double h = craton + oceanBasin + c.macro() * 40.0 + foothills * 95.0 * s.mountainHeight()
                + range - trench - continentalRift;
        return clamp(h, MIN_SURFACE, MAX_SURFACE);
    }

    private static double baseHeight(double x, double z, long seed, TerrainSettings s) {
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
        seed ^= s.seedSalt();
        TerrainCache.Node c = TerrainCache.sample(x, z, seed, s);
        double hSmooth = tectonicBase(x, z, s, c);

        // --- fine texture at full resolution (not cached, cheap) ---
        double erosion = Noise2D.fbm(x / 430.0, z / 430.0, seed + 83, 4, 2.11, .52);
        double ridgeDetail = clamp((Noise2D.fbm(x / 200.0, z / 200.0, seed + 223, 3, 2.15, .5) + 1.0) * 0.5);
        double ridge = Math.min(1.0, Math.pow(c.belt(), 0.5 + 0.6 * s.ridgeSharpness()) * (0.6 + 0.4 * ridgeDetail));

        // --- drainage: twice-domain-warped signed field yields dendritic river basins ---
        double wx = x + c.warpX() * 420.0;
        double wz = z + c.warpZ() * 420.0;
        double wx2 = wx + Noise2D.fbm(wx / 170.0, wz / 170.0, seed + 193, 2, 2.3, .5) * 150.0;
        double wz2 = wz + Noise2D.fbm(wx / 170.0, wz / 170.0, seed + 197, 2, 2.3, .5) * 150.0;
        double riverField = Noise2D.fbm(wx2 / (760.0 / s.riverFrequency()), wz2 / (760.0 / s.riverFrequency()), seed + 151, 3, 2.0, .52);
        double riverCore = Math.max(0.0, 1.0 - Math.abs(riverField) / (0.06 * s.riverWidth()));
        double lakeNoise = Noise2D.fbm(wx2 / 920.0, wz2 / 920.0, seed + 167, 4, 2.07, .5);

        // A corridor may only carve where it runs downhill along the local fall line.
        double river = riverCore;
        river = river * river * (3 - 2 * river); // smooth the corridor band
        double align = 1.0;
        if (river > 0.01) {
            double px = baseHeight(x + RIVER_PROBE, z, seed, s);
            double pz = baseHeight(x, z + RIVER_PROBE, seed, s);
            double dhx = (px - hSmooth) / RIVER_PROBE;
            double dhz = (pz - hSmooth) / RIVER_PROBE;
            double fx = riverField(x + RIVER_PROBE, z, seed, s) - riverField;
            double fz = riverField(x, z + RIVER_PROBE, seed, s) - riverField;
            double slope = Math.sqrt(dhx * dhx + dhz * dhz);
            double rfMag = Math.sqrt(fx * fx + fz * fz) + 1e-9;
            double tx = fz / rfMag, tz = -fx / rfMag; // corridor tangent
            double dot = (dhx * tx + dhz * tz) / (slope + 1e-9);
            double slopeBlend = clamp(slope * 6.0);
            align = slopeBlend * Math.abs(dot) + (1.0 - slopeBlend);
            align *= align;
        }
        // Rivers taper out of the folded ranges and strengthen downstream toward the sea.
        double downstream = clamp(1.0 - (hSmooth - s.seaLevel()) / 190.0);
        double shoreFade = clamp((hSmooth - (s.seaLevel() - 30.0)) / 20.0); // keep the deep seafloor smooth
        double gate = clamp(1.0 - (hSmooth - (s.seaLevel() + 20.0)) / 260.0) * (1.0 - c.convergent() * 0.9) * shoreFade;
        river = clamp(river * align * gate * (0.30 + 0.70 * downstream));

        // --- fluvial canyon dissection of high plateaus ---
        double dissection = Math.max(0.0, Noise2D.ridged(wx2 / 260.0, wz2 / 260.0, seed + 87, 3));
        double canyonHost = clamp((hSmooth - (s.seaLevel() + 20.0)) / 230.0) * (1.0 - c.convergent() * 0.6);
        double canyon = Math.pow(dissection, 1.8) * canyonHost * (8.0 + 42.0 * s.canyonDepth());

        double h0 = hSmooth - Math.abs(erosion) * ridge * 42.0 * s.erosionIntensity() - canyon;
        double h = h0 - river * (12.0 + 38.0 * s.riverDepth());

        // --- lakes: only genuine basins low enough to hold water, clear of main channels ---
        double basin = clamp((s.seaLevel() + 6.0 - h0) / 34.0);
        double lake = clamp((lakeNoise - 0.40) / 0.18);
        lake = lake * lake * basin * (1.0 - c.convergent()) * (1.0 - clamp(river * 3.0));
        h -= lake * (10.0 + 20.0 * s.riverDepth());
        h = clamp(h, MIN_SURFACE, MAX_SURFACE);

        // The water surface is derived from the carved bed so it can never float above the ground.
        double inlandWater = s.seaLevel();
        if (river > .02) inlandWater = Math.max(inlandWater, h + river * (2.0 + 4.0 * s.riverDepth()));
        if (lake > .05) inlandWater = Math.max(inlandWater, h + lake * (2.0 + 4.0 * s.riverDepth()));

        double moisture = Noise2D.fbm(x / (1700.0 * s.biomeScale()), z / (1700.0 * s.biomeScale()), seed + 191, 4, 2, .5);
        double temperature = Noise2D.fbm(x / (2200.0 * s.biomeScale()), z / (2200.0 * s.biomeScale()), seed + 211, 4, 2, .5)
                - Math.max(0, h0 - 250) / 1650.0;
        double slopeHint = clamp(Math.abs(erosion) * (0.4 + 0.6 * ridge));

        return new Sample(h, inlandWater, river, lake, ridge, moisture, temperature, slopeHint,
                c.plates(), c.convergent(), c.divergent(), c.fault());
    }

    public static boolean cave(long seed, int x, int y, int z, TerrainSettings s) {
        if (y > 1500 || y < -48) return false;
        double n1 = Noise2D.value((x + y * .42) / 72.0, (z - y * .31) / 72.0, seed + 701);
        double n2 = Noise2D.value((x - y * .23) / 38.0, (z + y * .37) / 38.0, seed + 709);
        double threshold = .78 - Math.min(.12, s.roughness() * .035);
        return Math.abs(n1 * .68 + n2 * .32) > threshold;
    }

    private static double riverField(double x, double z, long seed, TerrainSettings s) {
        TerrainCache.Node c = TerrainCache.sample(x, z, seed, s);
        double wx = x + c.warpX() * 420.0;
        double wz = z + c.warpZ() * 420.0;
        double wx2 = wx + Noise2D.fbm(wx / 170.0, wz / 170.0, seed + 193, 2, 2.3, .5) * 150.0;
        double wz2 = wz + Noise2D.fbm(wx / 170.0, wz / 170.0, seed + 197, 2, 2.3, .5) * 150.0;
        return Noise2D.fbm(wx2 / (760.0 / s.riverFrequency()), wz2 / (760.0 / s.riverFrequency()), seed + 151, 3, 2.0, .52);
    }
}