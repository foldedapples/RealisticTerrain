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
        double ridge = Math.pow(Math.max(0, Noise2D.ridged(x/(710.0/s.mountainFrequency()), z/(710.0/s.mountainFrequency()), seed+47, 5)), 1.25 + s.ridgeSharpness()*1.8);
        double erosion = Noise2D.fbm(x/520.0,z/520.0,seed+83,4,2.11,.52);

        // A warped signed field produces long connected drainage corridors; low absolute values form rivers.
        double wx = x + Noise2D.fbm(x/1500.0,z/1500.0,seed+101,3,2,.5)*420;
        double wz = z + Noise2D.fbm(x/1500.0,z/1500.0,seed+131,3,2,.5)*420;
        double riverField = Noise2D.fbm(wx/(760.0/s.riverFrequency()), wz/(760.0/s.riverFrequency()), seed+151, 3, 2.0, .52);
        double river = Math.max(0.0, 1.0 - Math.abs(riverField) / (0.035 * s.riverWidth()));
        river = river*river*(3-2*river);
        double lakeNoise = Noise2D.fbm(wx/920.0, wz/920.0, seed+167, 4, 2.07, .5);

        double lowland = 118 + continent*105 + macro*64;
        double mountainMask = Math.max(0, (macro + continent*.55) * .78 + .15);
        double lake = clamp((lakeNoise-.34)/.24) * clamp((.48-mountainMask)/.34);
        double mountain = ridge * mountainMask * 900.0 * s.mountainHeight();
        double terraceSource = lowland + mountain;
        double step = 22.0 + 18.0 / Math.max(.25, s.roughness());
        double terraced = Math.floor(terraceSource/step)*step;
        double terraceBlend = Math.max(0, Math.min(1, .28*s.erosionIntensity()));
        double h = terraceSource*(1-terraceBlend) + terraced*terraceBlend;
        h -= Math.abs(erosion) * ridge * 95.0 * s.erosionIntensity();
        double riverWater = s.seaLevel() + 8.0 + Math.max(0, continent+.18)*52.0;
        double lakeWater = s.seaLevel() + 12.0 + Math.max(0, continent+.12)*34.0;
        h -= river * (38.0 + 88.0*s.riverDepth());
        h -= lake * (26.0 + 34.0*s.riverDepth());
        if (river > .32) h = Math.min(h, riverWater - 3.0 - river*8.0*s.riverDepth());
        if (lake > .38) h = Math.min(h, lakeWater - 4.0 - lake*5.0*s.riverDepth());
        h = Math.max(-32, Math.min(1950, h));

        double inlandWater = s.seaLevel();
        if (river > .12) inlandWater = Math.max(inlandWater, riverWater);
        if (lake > .18) inlandWater = Math.max(inlandWater, lakeWater);

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
