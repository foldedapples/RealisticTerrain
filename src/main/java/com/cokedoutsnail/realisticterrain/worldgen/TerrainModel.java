package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.noise.Noise2D;

/** Shared mathematical terrain model used by both chunk generation and the live preview. */
public final class TerrainModel {
    private TerrainModel() {}
    public record Sample(double height, double waterLevel, double river, double lake, double ridge, double moisture, double temperature, double slopeHint) {}

    public static Sample sample(long seed, double x, double z, TerrainSettings s) {
        seed ^= s.seedSalt();
        double continent = Noise2D.fbm(x/4600.0, z/4600.0, seed+11, 5, 2.0, .5);
        double macro = Noise2D.fbm(x/(1350.0/s.mountainFrequency()), z/(1350.0/s.mountainFrequency()), seed+29, 5, 2.03, .48);
        // Lower exponents keep mountain silhouettes broad and rounded; higher ridgeSharpness sharpens them toward alpine peaks.
        double ridgeExponent = 0.6 + s.ridgeSharpness()*0.9;
        double ridge = Math.pow(Math.max(0, Noise2D.ridged(x/(710.0/s.mountainFrequency()), z/(710.0/s.mountainFrequency()), seed+47, 5)), ridgeExponent);
        double erosion = Noise2D.fbm(x/520.0,z/520.0,seed+83,4,2.11,.52);

        // A warped signed field produces long connected drainage corridors; low absolute values form rivers.
        double wx = x + Noise2D.fbm(x/1500.0,z/1500.0,seed+101,3,2,.5)*420;
        double wz = z + Noise2D.fbm(x/1500.0,z/1500.0,seed+131,3,2,.5)*420;
        double riverField = Noise2D.fbm(wx/(760.0/s.riverFrequency()), wz/(760.0/s.riverFrequency()), seed+151, 3, 2.0, .52);
        double river = Math.max(0.0, 1.0 - Math.abs(riverField) / (0.05 * s.riverWidth()));
        river = river*river*(3-2*river);
        double lakeNoise = Noise2D.fbm(wx/920.0, wz/920.0, seed+167, 4, 2.07, .5);

        // Gentle rolling base terrain; big elevation swings are reserved for the mountain mask below
        // instead of being baked into every hill, so plains/foothills stay calm and readable.
        double lowland = 96 + continent*70 + macro*42;
        // Mountains ramp in only where macro/continent terrain is genuinely elevated, producing
        // distinct ranges separated by lowlands instead of ridged noise blanketing the whole map.
        double mountainRaw = macro*0.85 + continent*0.5;
        double mountainMask = clamp((mountainRaw - 0.08) / 0.55);
        double lake = clamp((lakeNoise-.34)/.24) * clamp((.48-mountainMask)/.34);
        double mountain = ridge * mountainMask * 340.0 * s.mountainHeight();
        double h = lowland + mountain;
        // Erosion roughens slopes with continuous noise (real erosion texture) instead of the old
        // floor()-based terracing, which carved the terrain into blocky, hard-edged plateaus.
        h -= Math.abs(erosion) * ridge * 45.0 * s.erosionIntensity();

        // Carve valleys by subtracting a bounded, continuous depth from the *local* terrain rather
        // than clamping toward an absolute elevation - clamping toward a near-sea-level target
        // regardless of surrounding height tore ~350-block sheer canyons through tall mountains.
        // A river or lake basin only actually forms where that local carve brings the ground
        // down to the water table on its own, which happens naturally in lowlands.
        h -= river * (40.0 + 90.0*s.riverDepth());
        h -= lake * (28.0 + 40.0*s.riverDepth());
        h = Math.max(-32, Math.min(1300, h));

        // The water surface must be derived from the *carved* height, not an independent formula:
        // computing it separately (as this used to) let the water plane end up well above the
        // actual ground anywhere the two disagreed, flooding the terrain around every river/lake.
        // Filling only a few blocks above the freshly-carved bed keeps water glued to the ground
        // it was cut into, the same way real river/lake beds are always below their water line.
        double inlandWater = s.seaLevel();
        if (river > .02) inlandWater = Math.max(inlandWater, h + river * (2.0 + 4.0*s.riverDepth()));
        if (lake > .05) inlandWater = Math.max(inlandWater, h + lake * (3.0 + 7.0*s.riverDepth()));

        double moisture = Noise2D.fbm(x/(1700.0*s.biomeScale()),z/(1700.0*s.biomeScale()),seed+191,4,2,.5);
        double temperature = Noise2D.fbm(x/(2200.0*s.biomeScale()),z/(2200.0*s.biomeScale()),seed+211,4,2,.5) - Math.max(0,h-250)/1650.0;
        return new Sample(h, inlandWater, river, lake, ridge, moisture, temperature, Math.abs(erosion));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static boolean cave(long seed, int x, int y, int z, TerrainSettings s) {
        if (y > 1500 || y < -48) return false;
        double n1 = Noise2D.value((x + y*.42)/72.0, (z-y*.31)/72.0, seed+701);
        double n2 = Noise2D.value((x-y*.23)/38.0, (z+y*.37)/38.0, seed+709);
        double threshold = .78 - Math.min(.12, s.roughness()*.035);
        return Math.abs(n1*.68+n2*.32) > threshold;
    }
}
