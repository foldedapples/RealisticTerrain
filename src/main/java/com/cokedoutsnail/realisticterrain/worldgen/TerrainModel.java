package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.noise.Noise2D;

/** Shared mathematical terrain model used by both chunk generation and the live preview. */
public final class TerrainModel {
    private TerrainModel() {}
    public record Sample(double height, double waterLevel, double river, double lake, double ridge, double moisture, double temperature, double slopeHint) {}

    /**
     * Raw, uncarved field values shared by the terrain and the river/lake plumbing. Kept
     * separate from {@link Sample} so the downhill river probe can re-sample neighbours
     * without re-running the whole river logic (and without recursing into itself).
     */
    private record Base(double h0, double hSmooth, double riverCore, double riverField, double lakeNoise, double mountainMask, double ridge, double erosion, double moisture, double temperature) {}

    private static final double RIVER_PROBE = 28.0;

    private static Base base(double x, double z, long seed, TerrainSettings s) {
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
        double riverCore = Math.max(0.0, 1.0 - Math.abs(riverField) / (0.06 * s.riverWidth()));
        double lakeNoise = Noise2D.fbm(wx/920.0, wz/920.0, seed+167, 4, 2.07, .5);

        // Gentle rolling base terrain; big elevation swings are reserved for the mountain mask below
        // instead of being baked into every hill, so plains/foothills stay calm and readable.
        double lowland = 96 + continent*70 + macro*42;
        // Mountains ramp in only where macro/continent terrain is genuinely elevated, producing
        // distinct ranges separated by lowlands instead of ridged noise blanketing the whole map.
        double mountainRaw = macro*0.85 + continent*0.5;
        double mountainMask = clamp((mountainRaw - 0.08) / 0.55);
        // A soft apron of foothills around each range so the peaks rise naturally out of the plains.
        double foothills = clamp((mountainRaw - 0.02) / 0.60) * (1.0 - mountainMask);
        // Amplitude noise breaks long ridgelines up into individual summits instead of one lump.
        double ridgeDetail = clamp((Noise2D.fbm(x/210.0, z/210.0, seed+223, 3, 2.15, .5) + 1.0) * 0.5);
        double mountain = ridge * (0.62 + 0.38*ridgeDetail) * mountainMask * 340.0 * s.mountainHeight();

        // hSmooth is the terrain without the fine erosion texture - the "fall line" used by the
        // river probe - while h0 is the real eroded ground surface.
        double hSmooth = lowland + foothills*90.0*s.mountainHeight() + mountain;
        hSmooth = Math.max(-32, Math.min(1300, hSmooth));
        double h0 = hSmooth - Math.abs(erosion) * ridge * 45.0 * s.erosionIntensity();
        h0 = Math.max(-32, Math.min(1300, h0));

        double moisture = Noise2D.fbm(x/(1700.0*s.biomeScale()),z/(1700.0*s.biomeScale()),seed+191,4,2,.5);
        double temperature = Noise2D.fbm(x/(2200.0*s.biomeScale()),z/(2200.0*s.biomeScale()),seed+211,4,2,.5) - Math.max(0,h0-250)/1650.0;
        return new Base(h0, hSmooth, riverCore, riverField, lakeNoise, mountainMask, ridge, erosion, moisture, temperature);
    }

    public static Sample sample(long seed, double x, double z, TerrainSettings s) {
        seed ^= s.seedSalt();
        Base b = base(x, z, seed, s);
        double h0 = b.h0;

        // --- Rivers ---
        // The river field is warped signed noise: rivers live on its zero crossings, which
        // organically form a connected, winding drainage web. What makes it look real is that
        // a corridor may only carve when it actually runs downhill along the local fall line.
        double river = b.riverCore;
        river = river*river*(3-2*river); // smooth the corridor band
        double align = 1.0;
        if (river > 0.01) {
            // Probe the smoothed terrain and the river field a short distance ahead. Where the
            // corridor direction (perpendicular to the field gradient) lines up with the local
            // downhill direction the river carves; anywhere it crosses a hillside the carve dies,
            // so channels never climb ridges or slice sideways through slopes.
            Base px = base(x + RIVER_PROBE, z, seed, s);
            Base pz = base(x, z + RIVER_PROBE, seed, s);
            double dhx = (px.hSmooth - b.hSmooth) / RIVER_PROBE;
            double dhz = (pz.hSmooth - b.hSmooth) / RIVER_PROBE;
            double rfx = (px.riverField - b.riverField) / RIVER_PROBE;
            double rfz = (pz.riverField - b.riverField) / RIVER_PROBE;
            double slope = Math.sqrt(dhx*dhx + dhz*dhz);
            double rfMag = Math.sqrt(rfx*rfx + rfz*rfz) + 1e-9;
            // Corridor tangent, perpendicular to the river-field gradient.
            double tx =  rfz / rfMag, tz = -rfx / rfMag;
            double dot = (dhx*tx + dhz*tz) / (slope + 1e-9);
            // On flat floodplains there is no slope for the corridor to violate, so the river just
            // meanders along it; only real slopes demand downhill alignment.
            double slopeBlend = clamp(slope * 6.0);
            align = slopeBlend * Math.abs(dot) + (1.0 - slopeBlend);
            align *= align;
        }
        // Rivers fade out in the high ranges and strengthen as they approach the sea.
        double downstream = clamp(1.0 - (h0 - s.seaLevel()) / 190.0);
        double shoreFade = clamp((h0 - (s.seaLevel() - 30.0)) / 20.0); // keep the deep seafloor smooth
        double gate = clamp(1.0 - (h0 - (s.seaLevel() + 20.0)) / 260.0) * (1.0 - b.mountainMask*0.85) * shoreFade;
        river = clamp(river * align * gate * (0.30 + 0.70*downstream));
        double h = h0 - river * (12.0 + 38.0*s.riverDepth());

        // --- Lakes: only genuine basins low enough to hold water, clear of main channels ---
        double basin = clamp((s.seaLevel() + 6.0 - h0) / 34.0);
        double lake = clamp((b.lakeNoise - 0.40) / 0.18);
        lake = lake*lake * basin * (1.0 - b.mountainMask) * (1.0 - clamp(river*3.0));
        h -= lake * (10.0 + 20.0*s.riverDepth());
        h = Math.max(-32, Math.min(1300, h));

        // The water surface must be derived from the *carved* height, not an independent formula,
        // so it stays glued to the bed it was cut into and can never float above the ground.
        double inlandWater = s.seaLevel();
        if (river > .02) inlandWater = Math.max(inlandWater, h + river * (2.0 + 4.0*s.riverDepth()));
        if (lake > .05)  inlandWater = Math.max(inlandWater, h + lake * (2.0 + 4.0*s.riverDepth()));

        return new Sample(h, inlandWater, river, lake, b.ridge, b.moisture, b.temperature, Math.abs(b.erosion));
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
