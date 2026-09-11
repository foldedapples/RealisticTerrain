package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.noise.Noise2D;

/**
 * Shared mathematical terrain model used by both chunk generation and the live preview.
 *
 * Built in explicit geological layers rather than one flat noise expression:
 *   1. Continent-scale landmass shape (very low frequency, heavily domain-warped).
 *   2. Regional relief (rolling hills/plains baseline).
 *   3. Mountain ranges: an erosion-aware ridged multifractal, so ranges get sharp warped ridgelines
 *      with genuinely flatter flanks/valleys instead of uniform noisy roughness everywhere.
 *   4. A warped river network that carves valleys relative to the *local* terrain, never toward a
 *      fixed elevation.
 *   5. Lakes, in the same low-relief basins that would realistically collect water.
 * Every carve is bounded and continuous (no floor()/stair-stepping), and the water surface is
 * always derived from the already-carved ground height plus a small offset, never computed
 * independently - both of those were the source of real bugs (blocky terracing, water floating
 * above the ground) in earlier versions of this model.
 */
public final class TerrainModel {
    private TerrainModel() {}
    public record Sample(double height, double waterLevel, double river, double lake, double ridge, double moisture, double temperature, double slopeHint) {}

    public static Sample sample(long seed, double x, double z, TerrainSettings s) {
        seed ^= s.seedSalt();

        // --- Domain warp: distorts the coordinate space itself so features follow organic,
        // non-grid-aligned flow lines instead of looking like raw noise contours. Warp strength is
        // kept to a modest fraction of each layer's own wavelength - too strong (relative to the
        // wavelength being warped) locally compresses noise space and reads as extra high-frequency
        // roughness rather than an organic bend, which is the opposite of what we want here. ---
        double[] warpedContinent = Noise2D.warp(x, z, seed + 9001, 1.0 / 3200.0, 190.0);
        double cx = warpedContinent[0], cz = warpedContinent[1];
        double[] warpedMountain = Noise2D.warp(x, z, seed + 9002, 1.0 / 1100.0, 70.0);
        double mx = warpedMountain[0], mz = warpedMountain[1];

        // 1. Continent-scale landmass: very low frequency, warped, six octaves for coastline detail.
        double continent = Noise2D.fbm(cx / 5200.0, cz / 5200.0, seed + 11, 6, 2.0, .5);

        // 2. Regional relief: warped mid-frequency rolling terrain, independent of the mountain mask
        // below so plains/foothills stay calm even where the map isn't mountainous.
        double macro = Noise2D.fbm(mx / (1350.0 / s.mountainFrequency()), mz / (1350.0 / s.mountainFrequency()), seed + 29, 6, 2.03, .48);

        // 3. Mountain ranges: erosion-aware ridged multifractal. erosionIntensity raises the
        // feedback gain, which sharpens ridgelines and flattens the ground between them - a
        // genuine erosion-shaped silhouette instead of just subtracting extra noise afterward.
        double erosionGain = clamp(0.45 + s.erosionIntensity() * 0.18);
        double ridgeRaw = Noise2D.erodedRidge(mx / (760.0 / s.mountainFrequency()), mz / (760.0 / s.mountainFrequency()), seed + 47, 7, 2.05, erosionGain);
        double ridgeExponent = 0.55 + s.ridgeSharpness() * 0.85;
        double ridge = Math.pow(clamp(ridgeRaw), ridgeExponent);

        // Fine erosion texture: small, continuous roughening of slopes (never floor()-stepped).
        double erosionDetail = Noise2D.fbm(x / 480.0, z / 480.0, seed + 83, 5, 2.13, .5);

        // River network: a warped, multi-octave field converted to a channel via a narrow falloff
        // band. (An earlier version of this also blended in a second, independent tributary field,
        // but two uncorrelated fields combined with max() disagree near confluences - the ground
        // dips for one, rises, then dips again for the other, reading as a bumpy double valley
        // instead of one clean one. A single well-tuned field is more reliable than that.)
        double[] riverWarp = Noise2D.warp(x, z, seed + 101, 1.0 / 1500.0, 130.0);
        double riverField = Noise2D.fbm(riverWarp[0] / (820.0 / s.riverFrequency()), riverWarp[1] / (820.0 / s.riverFrequency()), seed + 151, 4, 2.0, .52);
        double river = riverFactor(riverField, 0.08 * s.riverWidth());

        double lakeNoise = Noise2D.fbm(riverWarp[0] / 900.0, riverWarp[1] / 900.0, seed + 167, 5, 2.07, .5);

        // Gentle rolling base terrain; big elevation swings are reserved for the mountain mask below
        // instead of being baked into every hill, so plains/foothills stay calm and readable.
        double lowland = 92 + continent*68 + macro*40;
        // Mountains ramp in only where continent/macro terrain is genuinely elevated, producing
        // distinct ranges separated by lowlands instead of ridged noise blanketing the whole map.
        double mountainRaw = macro*0.85 + continent*0.5;
        double mountainMask = clamp((mountainRaw + 0.16) / 0.20);
        double lake = clamp((lakeNoise-.34)/.24) * clamp((.48-mountainMask)/.34);
        double mountain = ridge * mountainMask * 430.0 * s.mountainHeight();
        double h = lowland + mountain;
        h -= Math.abs(erosionDetail) * ridge * 42.0 * s.erosionIntensity();

        // Carve valleys by subtracting a bounded, continuous depth from the *local* terrain rather
        // than clamping toward an absolute elevation - clamping toward a near-sea-level target
        // regardless of surrounding height tore sheer canyons through tall mountains.
        h -= river * (35.0 + 70.0*s.riverDepth());
        h -= lake * (26.0 + 36.0*s.riverDepth());
        h = Math.max(-32, Math.min(1300, h));

        // The water surface must be derived from the *carved* height, not an independent formula:
        // computing it separately let the water plane end up well above the actual ground anywhere
        // the two disagreed, flooding the terrain around every river/lake. Filling only a few
        // blocks above the freshly-carved bed keeps water glued to the ground it was cut into.
        double inlandWater = s.seaLevel();
        if (river > .02) inlandWater = Math.max(inlandWater, h + river * (2.0 + 4.0*s.riverDepth()));
        if (lake > .05) inlandWater = Math.max(inlandWater, h + lake * (3.0 + 7.0*s.riverDepth()));

        double moistureBase = Noise2D.fbm(cx/(1700.0*s.biomeScale()),cz/(1700.0*s.biomeScale()),seed+191,5,2,.5);
        double moisture = clampRange(moistureBase + river*0.25 + lake*0.3, -1.0, 1.0);
        double temperature = Noise2D.fbm(cx/(2200.0*s.biomeScale()),cz/(2200.0*s.biomeScale()),seed+211,5,2,.5) - Math.max(0,h-250)/1650.0;
        return new Sample(h, inlandWater, river, lake, ridge, moisture, temperature, Math.abs(erosionDetail));
    }

    private static double riverFactor(double field, double halfWidth) {
        double t = Math.max(0.0, 1.0 - Math.abs(field) / halfWidth);
        return t*t*(3-2*t);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double clampRange(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    public static boolean cave(long seed, int x, int y, int z, TerrainSettings s) {
        if (y > 1500 || y < -48) return false;
        double n1 = Noise2D.value((x + y*.42)/72.0, (z-y*.31)/72.0, seed+701);
        double n2 = Noise2D.value((x-y*.23)/38.0, (z+y*.37)/38.0, seed+709);
        double threshold = .5 - Math.min(.12, s.roughness()*.035);
        return Math.abs(n1*.68+n2*.32) > threshold;
    }
}
