package com.cokedoutsnail.realisticterrain.worldgen;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against inert sliders: every setting the customize screen offers must change something the
 * generator actually produces.
 *
 * <p>This is the counter-test to the descriptor table. The table makes it easy to add a setting and get
 * a slider for free; this makes sure that slider is not decoration.
 */
final class SettingsAffectGenerationTest {
    private static final long SEED = 8675309L;

    /**
     * Settings whose effect is deliberately NOT in the terrain model, with the reason. Anything added
     * here must be covered by {@link #settingsOutsideTheTerrainModelAreStillWired} instead.
     */
    private static final Set<TerrainSetting> NOT_TERRAIN_MODEL = EnumSet.of(
            TerrainSetting.SNOW_LINE,           // surface block choice, exercised separately below
            TerrainSetting.CAVE_GENERATION,     // cave carving, exercised separately below
            TerrainSetting.VEGETATION_DENSITY,  // tree placement, exercised separately below
            TerrainSetting.LAKE_FREQUENCY,      // basins, exercised separately below
            TerrainSetting.WETLAND_FREQUENCY,   // basins, exercised separately below
            TerrainSetting.GENERATE_STRUCTURES  // structure placement, at the chunk generator's hook
    );

    /**
     * A fingerprint of everything the terrain model publishes, over two sweeps: a fine one that
     * resolves ridgelines and channels, and a wide one that is guaranteed to cross ocean, coast and
     * plate margins - an ocean-only knob such as ocean_depth would be invisible in a small probe area
     * that happened to land entirely on land.
     */
    private static long fingerprint(TerrainSettings s) {
        long h = 0xcbf29ce484222325L;
        // Fine sweep: the full model, drainage network included.
        h = sampleInto(h, s, 600, 150, true);
        // Wide sweep: the tectonic fall line only. It can therefore afford to cover ocean, coast and
        // plate margins - which an ocean-only knob such as ocean_depth needs - without solving hundreds
        // of drainage regions for every candidate value.
        h = sampleInto(h, s, 6000, 1000, false);
        return h;
    }

    private static long sampleInto(long h, TerrainSettings s, int span, int step, boolean full) {
        for (int z = -span; z <= span; z += step) {
            for (int x = -span; x <= span; x += step) {
                if (!full) {
                    h = mix(h, Double.doubleToLongBits(TerrainModel.baseHeight(x, z, SEED, s)));
                    continue;
                }
                TerrainModel.Sample sm = TerrainModel.sample(SEED, x, z, s);
                h = mix(h, Double.doubleToLongBits(sm.height()));
                h = mix(h, Double.doubleToLongBits(sm.waterLevel()));
                h = mix(h, Double.doubleToLongBits(sm.river()));
                h = mix(h, Double.doubleToLongBits(sm.lake()));
                h = mix(h, Double.doubleToLongBits(sm.ridge()));
                h = mix(h, Double.doubleToLongBits(sm.moisture()));
                h = mix(h, Double.doubleToLongBits(sm.temperature()));
                h = mix(h, Double.doubleToLongBits(sm.soil()));
                h = mix(h, Double.doubleToLongBits(sm.convergent()));
                h = mix(h, Double.doubleToLongBits(sm.divergent()));
                h = mix(h, Double.doubleToLongBits(sm.fault()));
                h = mix(h, Double.doubleToLongBits(sm.continent()));
            }
        }
        return h;
    }

    private static long mix(long h, long bits) {
        return (h ^ bits) * 0x100000001B3L;
    }

    /** Picks a legal value that differs from the current one, for any setting. */
    private static double otherValue(TerrainSetting key, TerrainSettings base) {
        double current = base.raw(key);
        double candidate = key.clamp(current == key.max() ? key.min() : current + 1.0);
        if (candidate == current) candidate = key.clamp(key.min() == current ? key.max() : key.min());
        return candidate;
    }

    @Test
    void everySettingChangesTheTerrainModel() {
        TerrainSettings base = TerrainSettings.DEFAULT;
        long basePrint = fingerprint(base);
        StringBuilder inert = new StringBuilder();
        int checked = 0;
        for (TerrainSetting key : TerrainSetting.values()) {
            if (NOT_TERRAIN_MODEL.contains(key)) continue;
            checked++;
            // A couple of candidate values, because a few settings only bite at one end of their range.
            double[] candidates = {otherValue(key, base), key.clamp(key.min()), key.clamp(key.max())};
            boolean changedAnything = false;
            for (double candidate : candidates) {
                if (candidate == base.raw(key)) continue;
                if (fingerprint(base.with(key, candidate)) != basePrint) {
                    changedAnything = true;
                    break;
                }
            }
            if (!changedAnything) inert.append('\n').append(key.jsonKey());
        }
        assertTrue(inert.length() == 0,
                "these settings do not affect the generated terrain - they are inert sliders:" + inert);
        assertTrue(checked > 20, "expected the terrain settings to be covered, got " + checked);
    }

    /**
     * The three settings that act outside the terrain model still have to do something real.
     */
    @Test
    void settingsOutsideTheTerrainModelAreStillWired() {
        TerrainSettings base = TerrainSettings.DEFAULT;

        // Cave carving: zero must switch it off completely, and a higher density must carve more.
        TerrainSettings noCaves = base.with(TerrainSetting.CAVE_GENERATION, 0.0);
        TerrainSettings manyCaves = base.with(TerrainSetting.CAVE_GENERATION, 2.0);
        long off = 0, more = 0, on = 0;
        for (int y = -40; y < 400; y += 3) {
            for (int x = -400; x < 400; x += 41) {
                if (TerrainModel.cave(SEED, x, y, x / 3, noCaves)) off++;
                if (TerrainModel.cave(SEED, x, y, x / 3, manyCaves)) more++;
                if (TerrainModel.cave(SEED, x, y, x / 3, base)) on++;
            }
        }
        assertEquals625(0, off, "cave_generation = 0 still carved caves");
        assertTrue(more > on, "cave_generation did not increase carving: " + more + " vs " + on);

        // Snow: the same column must snow more readily with a lower snow line.
        TerrainSettings lowSnow = base.with(TerrainSetting.SNOW_LINE, base.snowLine() * 0.5);
        long lowCount = 0, baseCount = 0;
        for (int z = -800; z < 800; z += 37) {
            for (int x = -800; x < 800; x += 41) {
                TerrainModel.Sample sm = TerrainModel.sample(SEED, x, z, base);
                double altitude = base.snowLine() + 40;
                lowCount += TerrainModel.snowCover(x, z, altitude, lowSnow, sm, false) >= 0.5 ? 1 : 0;
                baseCount += TerrainModel.snowCover(x, z, altitude, base, sm, false) >= 0.5 ? 1 : 0;
            }
        }
        assertTrue(lowCount > baseCount,
                "snow_line does not affect the snow decision: " + lowCount + " vs " + baseCount);
        assertTrue(lowCount > 0, "no snow at all was placed even below the snow line");

        // Structures: the chunk generator reads this accessor in its setStructureStarts override, so it
        // has to answer correctly. (Its effect on placement needs a live chunk.)
        assertTrue(base.generateStructures(), "structures are on by default");
        assertTrue(!base.with(TerrainSetting.GENERATE_STRUCTURES, 0.0).generateStructures(),
                "the structures accessor ignores its own setting");

        // Vegetation: density must scale the tree chance, and zero must plant nothing.
        TerrainSettings noTrees = base.with(TerrainSetting.VEGETATION_DENSITY, 0.0);
        TerrainSettings lotsOfTrees = base.with(TerrainSetting.VEGETATION_DENSITY, 3.0);
        double dense = 0, normal = 0, none = 0;
        for (int z = -600; z < 600; z += 53) {
            for (int x = -600; x < 600; x += 61) {
                TerrainModel.Sample sm = TerrainModel.sample(SEED, x, z, base);
                double slope = TerrainModel.geomorphSlope(SEED, x, z, base);
                dense += TerrainModel.treeChance(SEED, x, z, sm, slope, lotsOfTrees);
                normal += TerrainModel.treeChance(SEED, x, z, sm, slope, base);
                none += TerrainModel.treeChance(SEED, x, z, sm, slope, noTrees);
            }
        }
        assertTrue(dense > normal && normal > none,
                "vegetation_density does not scale tree placement: " + dense + " / " + normal + " / " + none);
        assertEquals625(0, Double.compare(none, 0.0) == 0 ? 0 : 1, "vegetation_density = 0 still planted trees");
    }

    /** Convenience: assert a value is what was expected. */
    private static void assertEquals625(long expected, long actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }

    /**
     * Lakes and wetlands need closed basins, which are rare and not guaranteed to occur inside a small
     * probe area, so they get their own wide sweep rather than a slot in the fingerprint.
     */
    @Test
    void basinSlidersChangeTheHydrology() {
        TerrainSettings dry = TerrainSettings.DEFAULT
                .with(TerrainSetting.LAKE_FREQUENCY, 0.0)
                .with(TerrainSetting.WETLAND_FREQUENCY, 0.0);
        double lakesNow = basinTotals(TerrainSettings.DEFAULT)[0];
        double lakesNone = basinTotals(dry)[0];
        assertTrue(lakesNone == 0.0, "lake_frequency = 0 still produced lakes: " + lakesNone);
        assertTrue(lakesNow > 0.0, "the default world has no lakes at all");

        TerrainSettings wet = TerrainSettings.DEFAULT.with(TerrainSetting.WETLAND_FREQUENCY, 2.0);
        double wetHigh = basinTotals(wet)[1];
        double wetNone = basinTotals(dry)[1];
        assertTrue(wetHigh > wetNone, "wetland_frequency did not add wetland: " + wetHigh + " vs " + wetNone);
        assertTrue(wetHigh > 0.0, "wetland_frequency = 2 produced no wetland at all");

        TerrainSettings manyLakes = TerrainSettings.DEFAULT.with(TerrainSetting.LAKE_FREQUENCY, 2.0);
        assertTrue(basinTotals(manyLakes)[0] >= lakesNow,
                "raising lake_frequency removed lakes");
    }

    /** Summed lake and wetland fields over a wide sweep, which is where basins actually live. */
    private static double[] basinTotals(TerrainSettings s) {
        double lake = 0, wetland = 0;
        for (int z = -4000; z <= 4000; z += 250) {
            for (int x = -4000; x <= 4000; x += 250) {
                com.cokedoutsnail.realisticterrain.worldgen.hydro.Drainage.Cell c =
                        com.cokedoutsnail.realisticterrain.worldgen.hydro.Drainage.sample(x + 0.5, z - 0.5, SEED, s);
                lake += c.lake();
                wetland += c.wetland();
            }
        }
        return new double[]{lake, wetland};
    }
}
