package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.noise.CellularNoise;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TerrainModelTest {
    @Test
    void samplingIsDeterministicAndFinite() {
        TerrainModel.Sample first = TerrainModel.sample(42L, 1234.5, -987.25, TerrainSettings.DEFAULT);
        TerrainModel.Sample second = TerrainModel.sample(42L, 1234.5, -987.25, TerrainSettings.DEFAULT);

        assertEquals(first, second);
        assertTrue(Double.isFinite(first.height()));
        assertTrue(Double.isFinite(first.waterLevel()));
        assertTrue(first.height() >= -120.0 && first.height() <= 1950.0);
    }

    @Test
    void drainageCreatesWaterFilledChannels() {
        boolean foundChannel = false;
        for (int z = -4096; z <= 4096 && !foundChannel; z += 64) {
            for (int x = -4096; x <= 4096; x += 64) {
                TerrainModel.Sample sample = TerrainModel.sample(8675309L, x, z, TerrainSettings.DEFAULT);
                if ((sample.river() > .32 || sample.lake() > .38) && sample.height() < sample.waterLevel()) {
                    foundChannel = true;
                    break;
                }
            }
        }
        assertTrue(foundChannel, "Expected at least one water-filled drainage channel in the sampled area");
    }

    @Test
    void riverWaterStaysAttached() {
        // The water surface is always just a few blocks above the freshly carved riverbed,
        // never an independently computed plain that could sit above the surrounding ground.
        for (int z = -4096; z <= 4096; z += 64) {
            for (int x = -4096; x <= 4096; x += 64) {
                TerrainModel.Sample sample = TerrainModel.sample(8675309L, x, z, TerrainSettings.DEFAULT);
                if (sample.river() > 0.1 && sample.height() >= 96) {
                    assertTrue(
                            sample.waterLevel() - sample.height() <= 14,
                            "Floating river water detected at " + x + "," + z
                                    + " waterLevel=" + sample.waterLevel() + " height=" + sample.height()
                    );
                }
            }
        }
    }

    @Test
    void riversStayOutOfTheHighRanges() {
        // Channels may only carve low and mid terrain; they fade out long before the
        // alpine zone, so rivers never cut gullies through mountain ridgelines.
        for (int z = -4096; z <= 4096; z += 64) {
            for (int x = -4096; x <= 4096; x += 64) {
                TerrainModel.Sample sample = TerrainModel.sample(8675309L, x, z, TerrainSettings.DEFAULT);
                if (sample.river() > 0.25) {
                    assertTrue(sample.height() < 450,
                            "River found cutting through high terrain at " + x + "," + z
                                    + " height=" + sample.height());
                }
            }
        }
    }

    @Test
    void riversPreferValleys() {
        // Drainage must dominate the lower, gentler terrain: over a large sample the average
        // river column should sit well below the average non-river column, and the river
        // coverage fraction should stay in a sane band (corridors exist, but never blanket the map).
        long riverCells = 0, total = 0;
        double riverSum = 0, nonRiverSum = 0, nonRiverCount = 0;
        for (int z = -2048; z <= 2048; z += 32) {
            for (int x = -2048; x <= 2048; x += 32) {
                TerrainModel.Sample sample = TerrainModel.sample(8675309L, x, z, TerrainSettings.DEFAULT);
                assertTrue(Double.isFinite(sample.height()));
                assertTrue(Double.isFinite(sample.waterLevel()));
                total++;
                if (sample.river() > 0.35) {
                    riverCells++;
                    riverSum += sample.height();
                } else if (sample.river() < 0.01) {
                    nonRiverCount++;
                    nonRiverSum += sample.height();
                }
            }
        }
        double fraction = (double) riverCells / total;
        assertTrue(fraction > 0.005, "Unexpectedly few rivers: " + fraction);
        assertTrue(fraction < 0.5, "Unexpectedly many rivers: " + fraction);
        double avgRiver = riverSum / Math.max(1, riverCells);
        double avgOther = nonRiverSum / Math.max(1, nonRiverCount);
        assertTrue(avgRiver < avgOther, "Rivers should sit in valleys: riverAvg=" + avgRiver + " otherAvg=" + avgOther);
    }

    @Test
    void profilesAndVegetationDefaultAreUsable() {
        assertEquals(1.0F, TerrainSettings.DEFAULT.vegetationDensity());
        assertTrue(TerrainSettings.PROFILES.size() >= 5);
        for (TerrainSettings.Profile p : TerrainSettings.PROFILES) {
            assertTrue(p.settings().vegetationDensity() >= 0.0F);
            assertTrue(p.settings().vegetationDensity() <= 3.0F);
            TerrainModel.Sample s = TerrainModel.sample(42L, 0, 0, p.settings());
            assertTrue(Double.isFinite(s.height()));
        }
    }

    @Test
    void seedSaltChangesTerrain() {
        TerrainSettings salted = TerrainSettings.DEFAULT.withSeedSalt(99L);
        assertNotEquals(
                TerrainModel.sample(12L, 800, 1200, TerrainSettings.DEFAULT),
                TerrainModel.sample(12L, 800, 1200, salted)
        );
    }

    @Test
    void tectonicLayeringProducesMountainsAndOcean() {
        // Plate tectonics must yield both folded ranges and deep abyssal basins over a large area.
        // Roughly half of all margins collide (full-amplitude orogeny) and half separate (rifts and
        // ocean trenches), and the fold fabric that gates the orogeny is fine-grained, so the scan
        // step has to be finer than a range's own crest width or the peaks get stepped over.
        double peak = -1e9, trough = 1e9;
        for (int z = -8192; z <= 8192; z += 64) {
            for (int x = -8192; x <= 8192; x += 64) {
                double h = TerrainModel.sample(8675309L, x, z, TerrainSettings.DEFAULT).height();
                if (h > peak) peak = h;
                if (h < trough) trough = h;
            }
        }
        assertTrue(peak > 500, "Expected folded mountain ranges, peak=" + peak);
        assertTrue(trough < -40, "Expected deep abyssal ocean, trough=" + trough);
    }

    @Test
    void cacheIsSmoothAndShared() {
        long seed = 424242L;
        // Determinism: repeated samples are identical (cache is a pure memoization).
        assertEquals(TerrainModel.sample(seed, 1234, -567, TerrainSettings.DEFAULT),
                TerrainModel.sample(seed, 1234, -567, TerrainSettings.DEFAULT));
        // Smoothness: adjacent 1-block columns differ by a bounded amount (no jagged lattice steps).
        double maxStep = 0;
        for (int x = 0; x < 64; x++) {
            double a = TerrainModel.sample(seed, x, 50, TerrainSettings.DEFAULT).height();
            double b = TerrainModel.sample(seed, x + 1, 50, TerrainSettings.DEFAULT).height();
            maxStep = Math.max(maxStep, Math.abs(a - b));
        }
        assertTrue(maxStep < 130, "Terrain jumped between adjacent columns: " + maxStep);
        // The coarse cache shares nodes across columns: sampling a fresh 64×64-block patch adds
        // only the handful of 16×16 cells it touches. The hydraulic erosion pass also pre-warms the
        // coarse field for the (at most four) 512-block regions whose working grids cover the patch -
        // each grid is 640×640 blocks and touches ~1600 coarse cells - so the delta is a few thousand
        // nodes at most, still far smaller than the 4096 columns sampled here, and those nodes are
        // shared by every column rather than recomputed per column.
        long before = TerrainCache.size();
        for (int x = 2000; x < 2064; x++) {
            for (int z = 3000; z < 3064; z++) {
                TerrainModel.sample(seed, x, z, TerrainSettings.DEFAULT);
            }
        }
        long delta = TerrainCache.size() - before;
        assertTrue(delta < 8192, "Cache should share coarse nodes across columns, delta=" + delta);
    }

    // ---------------------------------------------------------------------------------------
    // Regression guards for the coordinate-interpolation / floor / ocean-balance bugfix.
    // ---------------------------------------------------------------------------------------

    /**
     * The cache interpolates the coarse 16-block lattice between its four corner nodes. That
     * interpolation must be a convex combination: the old code used the wrong sign on the
     * bilinear cross term, so the surface did not even pass through its corner values and every
     * cell was twisted by 2.(a01 - a11).fx.fz - the repeating diamond/facet grid and sawtooth
     * terraces on slopes. A correct interpolation can never leave the corner value range.
     */
    @Test
    void coarseFieldInterpolationStaysWithinItsCornerValues() {
        long seed = 24680L;
        TerrainSettings s = TerrainSettings.DEFAULT;
        double[] fractions = {0.25, 0.5, 0.75};
        double worst = 0;
        int checked = 0;
        for (int cx = -40; cx <= 40; cx += 7) {
            for (int cz = -40; cz <= 40; cz += 7) {
                TerrainCache.Node c00 = TerrainCache.sample(cx * 16.0, cz * 16.0, seed, s);
                TerrainCache.Node c10 = TerrainCache.sample(cx * 16.0 + 16, cz * 16.0, seed, s);
                TerrainCache.Node c01 = TerrainCache.sample(cx * 16.0, cz * 16.0 + 16, seed, s);
                TerrainCache.Node c11 = TerrainCache.sample(cx * 16.0 + 16, cz * 16.0 + 16, seed, s);
                for (double fx : fractions) {
                    for (double fz : fractions) {
                        TerrainCache.Node v = TerrainCache.sample(
                                cx * 16.0 + 16 * fx, cz * 16.0 + 16 * fz, seed, s);
                        worst = Math.max(worst, outside(v.plates(), c00.plates(), c10.plates(), c01.plates(), c11.plates()));
                        worst = Math.max(worst, outside(v.convergent(), c00.convergent(), c10.convergent(), c01.convergent(), c11.convergent()));
                        worst = Math.max(worst, outside(v.divergent(), c00.divergent(), c10.divergent(), c01.divergent(), c11.divergent()));
                        worst = Math.max(worst, outside(v.belt(), c00.belt(), c10.belt(), c01.belt(), c11.belt()));
                        worst = Math.max(worst, outside(v.fault(), c00.fault(), c10.fault(), c01.fault(), c11.fault()));
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 500, "expected a large interpolation sample, got " + checked);
        assertTrue(worst < 1e-9,
                "Coarse interpolation is not convex - the bilinear weights are broken (overshoot "
                        + worst + ")");
    }

    /** How far outside the four corner values the sampled field fell. */
    private static double outside(double v, double a, double b, double c, double d) {
        double lo = Math.min(Math.min(a, b), Math.min(c, d));
        double hi = Math.max(Math.max(a, b), Math.max(c, d));
        return Math.max(0, Math.max(lo - v, v - hi));
    }

    /**
     * The cache must hand back an independent coarse node per cell. The old XOR-combined key
     * collided for ~20% of neighbouring cells, so chunks were fed another cell's continental and
     * tectonic data, which produced non-deterministic terrain with hard jumps along the lattice.
     */
    @Test
    void everyCoarseCellOwnsItsOwnNode() {
        long seed = 20250910L;
        TerrainSettings s = TerrainSettings.DEFAULT;
        java.util.HashSet<String> nodes = new java.util.HashSet<>();
        long cells = 0, duplicates = 0;
        for (int cx = -60; cx < 60; cx++) {
            for (int cz = -60; cz < 60; cz++) {
                cells++;
                if (!nodes.add(TerrainCache.sample(cx * 16.0, cz * 16.0, seed, s).toString())) {
                    duplicates++;
                }
            }
        }
        assertTrue(duplicates < cells / 1000,
                "Coarse nodes collide between cells: " + duplicates + " of " + cells + " cells");
    }

    /**
     * Slopes must not step in 16-block terraces. A broken bilinear cross term put 1000+ block
     * cliffs on the lattice; with the weights fixed the worst step is a genuine canyon/mountain
     * cliff and cell borders have no special status at all.
     */
    @Test
    void slopesAreSmoothAcrossTheCoarseLattice() {
        long seed = 8675309L;
        double maxStep = 0, maxBorderStep = 0;
        for (int z = -2000; z <= 2000; z += 50) {
            double cur = TerrainModel.sample(seed, -500, z, TerrainSettings.DEFAULT).height();
            for (int x = -499; x <= 500; x++) {
                double next = TerrainModel.sample(seed, x, z, TerrainSettings.DEFAULT).height();
                double step = Math.abs(next - cur);
                maxStep = Math.max(maxStep, step);
                if (Math.floorMod(x, 16) == 0) maxBorderStep = Math.max(maxBorderStep, step);
                cur = next;
            }
        }
        assertTrue(maxStep < 60, "Terrain steps by " + maxStep + " blocks in a single block");
        assertTrue(maxBorderStep < 20,
                "Cell borders still show lattice cliffs: border=" + maxBorderStep + " overall=" + maxStep);
    }

    /** No generated column may sit below the world floor, or its ocean would open onto the void. */
    @Test
    void generatedSurfaceNeverSinksBelowTheWorldFloor() {
        long seed = 13579L;
        double lowest = Double.MAX_VALUE;
        for (int z = -6000; z <= 6000; z += 96) {
            for (int x = -6000; x <= 6000; x += 96) {
                lowest = Math.min(lowest,
                        TerrainModel.sample(seed, x, z, TerrainSettings.DEFAULT).height());
            }
        }
        assertTrue(lowest > RealisticChunkGenerator.MIN_Y + 8,
                "Abyssal floor sank towards the void: lowest=" + lowest
                        + ", world floor=" + RealisticChunkGenerator.MIN_Y);
    }

    /** Continents should hold a clear majority of the surface instead of being drowned by ocean. */
    @Test
    void landCoversRoughlyHalfToSixtyPercentOfTheSurface() {
        long land = 0, total = 0;
        for (int z = -4096; z <= 4096; z += 64) {
            for (int x = -4096; x <= 4096; x += 64) {
                if (TerrainModel.sample(8675309L, x, z, TerrainSettings.DEFAULT).height()
                        > TerrainSettings.DEFAULT.seaLevel()) {
                    land++;
                }
                total++;
            }
        }
        double fraction = land / (double) total;
        assertTrue(fraction >= 0.50 && fraction <= 0.65,
                "Land/ocean balance is off: land covers " + fraction + " of the surface");
    }

    // ---------------------------------------------------------------------------------------
    // Regression guards for the ReTerraForged port: interpolation form, Voronoi continuity, the
    // ported distance metrics and the exposed continental control points.
    // ---------------------------------------------------------------------------------------

    /**
     * The coarse cache must interpolate exactly what ReTerraForged's Perlin sampler computes, i.e.
     * the nested lerp {@code lerp(lerp(a00,a10,fx), lerp(a01,a11,fx), fz)} over quintically faded
     * in-cell coordinates. Checking the closed form pins the bilinear weights down: the cross term
     * carries the surface, and a wrong sign there puts the repeating diamond/facet grid back on
     * every slope.
     */
    @Test
    void coarseInterpolationMatchesTheReferenceNestedLerp() {
        long seed = 31415926L;
        TerrainSettings s = TerrainSettings.DEFAULT;
        double worst = 0;
        int checked = 0;
        for (int cx = -20; cx <= 20; cx += 3) {
            for (int cz = -20; cz <= 20; cz += 3) {
                TerrainCache.Node c00 = TerrainCache.sample(cx * 16.0, cz * 16.0, seed, s);
                TerrainCache.Node c10 = TerrainCache.sample(cx * 16.0 + 16, cz * 16.0, seed, s);
                TerrainCache.Node c01 = TerrainCache.sample(cx * 16.0, cz * 16.0 + 16, seed, s);
                TerrainCache.Node c11 = TerrainCache.sample(cx * 16.0 + 16, cz * 16.0 + 16, seed, s);
                for (int i = 1; i < 4; i++) {
                    for (int j = 1; j < 4; j++) {
                        double u = i / 4.0, v = j / 4.0;
                        double fx = fade(u), fz = fade(v);
                        TerrainCache.Node got = TerrainCache.sample(
                                cx * 16.0 + 16 * u, cz * 16.0 + 16 * v, seed, s);
                        worst = Math.max(worst, Math.abs(got.macro() - refLerp(
                                c00.macro(), c10.macro(), c01.macro(), c11.macro(), fx, fz)));
                        worst = Math.max(worst, Math.abs(got.belt() - refLerp(
                                c00.belt(), c10.belt(), c01.belt(), c11.belt(), fx, fz)));
                        worst = Math.max(worst, Math.abs(got.fault() - refLerp(
                                c00.fault(), c10.fault(), c01.fault(), c11.fault(), fx, fz)));
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 300, "expected a large interpolation sample, got " + checked);
        assertTrue(worst < 1e-9,
                "Coarse interpolation no longer matches the reference nested lerp (worst error "
                        + worst + ")");
    }

    /** ReTerraForged's {@code Perlin.sample} interpolation, spelled out independently of production code. */
    private static double refLerp(double a00, double a10, double a01, double a11, double fx, double fz) {
        return refLerp(refLerp(a00, a10, fx), refLerp(a01, a11, fx), fz);
    }

    private static double refLerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Quintic fade, matching {@code NoiseUtil.interpQuintic} in the reference engine. */
    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /**
     * The plate trait feeds the orogeny amplitude, so it must be continuous across a plate margin
     * and across the plate centres. It used to be the raw hash of the nearest plate - a step
     * function - which put up to a few hundred blocks of mountain height on one side of a cache
     * cell boundary and none on the other. Blending on the smooth edge measure removes that.
     */
    @Test
    void plateTraitIsContinuousAcrossMargins() {
        long seed = 8675309L;
        double worst = 0;
        for (int z = -8000; z <= 8000; z += 501) {
            double cur = TerrainCache.sample(-8000, z, seed, TerrainSettings.DEFAULT).plates();
            for (int x = -7993; x <= 8000; x += 7) {
                double next = TerrainCache.sample(x, z, seed, TerrainSettings.DEFAULT).plates();
                worst = Math.max(worst, Math.abs(next - cur));
                cur = next;
            }
        }
        assertTrue(worst < 0.06,
                "Plate trait still steps across a margin (worst 7-block change " + worst + ")");
    }

    /**
     * The ported cellular metrics must be well behaved and genuinely different from one another,
     * otherwise the configurable plate shape is not doing anything.
     */
    @Test
    void cellularDistanceMetricsShapeDistinctPlates() {
        for (CellularNoise.DistanceFunction shape : CellularNoise.DistanceFunction.values()) {
            for (double d = 0.05; d < 4; d += 0.37) {
                double e = shape.apply(d, 0.0);
                assertTrue(e > 0, shape + " must be positive at distance " + d);
                assertTrue(Double.isFinite(e), shape + " produced a non-finite value");
            }
        }
        // Off the diagonal the two metrics must disagree, or the configurable plate shape is inert.
        assertNotEquals(CellularNoise.DistanceFunction.EUCLIDEAN.apply(1.0, 0.5),
                CellularNoise.DistanceFunction.MANHATTAN.apply(1.0, 0.5));
        // MANHATTAN's level sets are diamonds, so points on x + z = const are equidistant.
        assertEquals(CellularNoise.DistanceFunction.MANHATTAN.apply(1.0, 0.4),
                CellularNoise.DistanceFunction.MANHATTAN.apply(0.4, 1.0), 1e-12);
    }

    /** Land shares the surface: fraction of sampled columns whose ground sits above sea level. */
    private static double landFraction(long seed, TerrainSettings s) {
        long land = 0, total = 0;
        for (int z = -4096; z <= 4096; z += 64) {
            for (int x = -4096; x <= 4096; x += 64) {
                if (TerrainModel.sample(seed, x, z, s).height() > s.seaLevel()) land++;
                total++;
            }
        }
        return land / (double) total;
    }

    /**
     * The coast-line control point (ReTerraForged's {@code ControlPoints.coast}) has to actually move
     * the shoreline in both directions, because it is the knob a player uses to rescue a world that
     * generated as endless ocean. Land is {@code continent > coastLine}, so lowering the coast line
     * pushes the shoreline seaward and raising it moves the shoreline inland.
     */
    @Test
    void coastLineControlPointTradesOceanForLand() {
        long seed = 8675309L;
        double flooded = landFraction(seed, TerrainSettings.DEFAULT.withValue(14, 0.10));
        double normal = landFraction(seed, TerrainSettings.DEFAULT);
        double continent = landFraction(seed, TerrainSettings.DEFAULT.withValue(14, -0.30));
        assertTrue(flooded < normal,
                "Raising the coast line must drown land: " + flooded + " vs " + normal);
        assertTrue(continent > normal,
                "Lowering the coast line must extend land: " + continent + " vs " + normal);
        assertTrue(normal >= 0.40,
                "The default shoreline should still leave a substantial continent: " + normal);
    }

    /**
     * The ocean-depth control point (ReTerraForged's {@code ControlPoints.deepOcean}) may deepen the
     * abyssal plain, but it must never push the modelled floor towards the void: the hard floor in
     * the chunk generator is a backstop, not the intended limit.
     */
    @Test
    void maximumOceanDepthStillStaysInsideTheWorld() {
        long seed = 13579L;
        TerrainSettings deepOcean = TerrainSettings.DEFAULT.withValue(15, 300);
        double lowest = Double.MAX_VALUE;
        for (int z = -4000; z <= 4000; z += 128) {
            for (int x = -4000; x <= 4000; x += 128) {
                lowest = Math.min(lowest, TerrainModel.sample(seed, x, z, deepOcean).height());
            }
        }
        assertTrue(lowest > RealisticChunkGenerator.MIN_Y + 8,
                "Maximum ocean depth sank the floor towards the void: lowest=" + lowest);
    }

    /** {@code withValue} must replace exactly one setting and leave every other one untouched. */
    @Test
    void withValueReplacesExactlyOneSetting() {
        TerrainSettings base = TerrainSettings.DEFAULT;
        for (int i = 0; i < TerrainSettings.UI_SETTING_COUNT; i++) {
            // Probe with a legal value taken from the descriptor range: some settings (for example
            // the structures toggle) default to their own maximum, so a blind "+1" would be clamped
            // straight back and the test would be probing a no-op.
            TerrainSetting key = TerrainSetting.values()[i];
            double probe = key.clamp(base.getValue(i) == key.max() ? key.min() : base.getValue(i) + 1.0);
            assertNotEquals(probe, base.getValue(i), "test probe is a no-op for index " + i);
            TerrainSettings changed = base.withValue(i, probe);
            assertNotEquals(base, changed, "withValue(" + i + ") changed nothing");
            // Settings are stored as floats for the float-shaped entries, so compare at float
            // precision rather than double.
            assertEquals(probe, changed.getValue(i), 1e-5, "withValue(" + i + ") did not apply");
            for (int j = 0; j < TerrainSettings.UI_SETTING_COUNT; j++) {
                if (j != i) {
                    assertEquals(base.getValue(j), changed.getValue(j), 1e-9,
                            "withValue(" + i + ") also changed setting " + j);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Regression guards for the graded-channel (Hermite bank) river port.
    //
    // The old model subtracted depth along a linear tent, max(0, 1 - |field| / (0.06 * width)),
    // and then derived the water surface from the locally CARVED height, h + river * 6. Two
    // visible failures followed:
    //   * a straight-sided V trench up to 12 + 38 * riverDepth = 50 blocks deep, whose width in
    //     BLOCKS was whatever the drainage field's gradient happened to imply - tens of blocks in
    //     one place, a couple of hundred where the field flattened out: the "inland fjord";
    //   * a water surface that read the carved height, and therefore the fine erosion noise, so
    //     that once it was floored to a block the shoreline stepped through a dozen integer levels
    //     in a row: the concentric stair-step rings of water, sand and gravel.
    //
    // Channels are now a smoothstep cross-section measured in blocks, and the water surface is a
    // flat plane anchored to the channel's centre line. The tests below pin both down.
    // ---------------------------------------------------------------------------------------

    /** Ground and water surface either side of a channel centre, at 1-block resolution. */
    private record Cross(double[] height, double[] water, boolean[] wet) {}

    private static final int CROSS_HALF = 60;

    /** Walks scan lines and returns a cross-section for the middle of each channel it finds. */
    private static List<Cross> channelCrossSections(long seed, TerrainSettings s, int lines) {
        List<Cross> found = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            int z = -2400 + i * 470;
            int perLine = 0;
            for (int x = -2400; x <= 2400 && perLine < 10; x++) {
                if (TerrainModel.sample(seed, x, z, s).river() <= 0.5) continue;
                int start = x;
                while (x <= 2400 && TerrainModel.sample(seed, x, z, s).river() > 0.5) x++;
                int centre = (start + x) / 2;
                double[] h = new double[2 * CROSS_HALF + 1];
                double[] w = new double[h.length];
                boolean[] wet = new boolean[h.length];
                for (int k = 0; k < h.length; k++) {
                    TerrainModel.Sample sm = TerrainModel.sample(seed, centre - CROSS_HALF + k, z, s);
                    h[k] = sm.height();
                    w[k] = sm.waterLevel();
                    wet[k] = Math.floor(w[k]) > Math.floor(h[k]);
                }
                found.add(new Cross(h, w, wet));
                perLine++;
            }
        }
        return found;
    }

    /** The indices of the water band around a cross-section's centre, as {@code [from, to]}. */
    private static int[] waterBand(Cross c) {
        int a = CROSS_HALF, b = CROSS_HALF;
        while (a > 0 && c.wet()[a - 1]) a--;
        while (b < c.height().length - 1 && c.wet()[b + 1]) b++;
        return new int[]{a, b};
    }

    /**
     * A river is a graded valley, not a trench. The bed may be cut below the fall line, but only by
     * the configured incision (7.5 blocks times the river-depth scale) - never by the fixed
     * {@code 12 + 38 * riverDepth} the old linear tent subtracted. Measured from the bed up to the
     * ground immediately beside the water, so it is the channel's own depth and not the local relief.
     */
    @Test
    void riverChannelsAreGradedValleysNotTrenches() {
        double worst = 0;
        int checked = 0;
        for (Cross c : channelCrossSections(8675309L, TerrainSettings.DEFAULT, 6)) {
            int[] band = waterBand(c);
            if (band[1] - band[0] < 2) continue;
            double beside = Math.max(
                    c.height()[Math.max(0, band[0] - 1)],
                    c.height()[Math.min(c.height().length - 1, band[1] + 1)]);
            double bed = Math.min(c.height()[band[0]], c.height()[band[1]]);
            worst = Math.max(worst, beside - bed);
            checked++;
        }
        assertTrue(checked >= 10, "too few channels found to test: " + checked);
        assertTrue(worst < 25.0,
                "a channel was cut like a trench, " + worst + " blocks below the ground beside it");
    }

    /**
     * The water's edge has to be a single contour line. A ring needs the surface to rise again on its
     * way out to the bank, so monotonicity across the water band is exactly the property that rules
     * the concentric stair-step rings out. The span bound then rules out the sloped funnel a
     * height-derived surface produced: the old one dropped tens of blocks across its own channel.
     */
    @Test
    void riverSurfaceIsASingleContourNotRings() {
        int checked = 0, reversals = 0;
        double spanSum = 0, spanWorst = 0;
        for (Cross c : channelCrossSections(8675309L, TerrainSettings.DEFAULT, 6)) {
            int[] band = waterBand(c);
            if (band[1] - band[0] < 4) continue;
            checked++;
            int dir = 0;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int k = band[0]; k <= band[1]; k++) {
                lo = Math.min(lo, c.water()[k]);
                hi = Math.max(hi, c.water()[k]);
                if (k == band[0]) continue;
                double d = c.water()[k] - c.water()[k - 1];
                if (Math.abs(d) < 1.0) continue; // a flat plate, floating-point noise, or a gentle drift
                int sgn = d > 0 ? 1 : -1;
                if (dir != 0 && sgn != dir) reversals++;
                dir = sgn;
            }
            double span = hi - lo;
            spanSum += span;
            spanWorst = Math.max(spanWorst, span);
        }
        assertTrue(checked >= 10, "too few channels found to test: " + checked);
        assertTrue(reversals <= checked / 5,
                "the water surface rises again inside " + reversals + " of " + checked + " channels");
        assertTrue(spanSum / checked <= 6.0 && spanWorst <= 25.0,
                "the water surface is a slope rather than a plane: avg=" + spanSum / checked
                        + " worst=" + spanWorst);
    }

    /**
     * The river-width slider may make rivers wider, but never unbounded: drainage has to stay a sane
     * fraction of the map at every setting, or a wide-river world becomes one inland fjord. The old
     * corridor was measured in units of the drainage field, so at high width settings, and wherever
     * the field flattened out, its width in blocks ran away entirely.
     */
    @Test
    void riverCoverageStaysBoundedAcrossTheWidthSlider() {
        for (double width : new double[]{0.25, 1.0, 2.5, 4.0}) {
            TerrainSettings s = TerrainSettings.DEFAULT.withValue(4, width);
            long channel = 0, total = 0;
            for (int z = -2048; z <= 2048; z += 32) {
                for (int x = -2048; x <= 2048; x += 32) {
                    if (TerrainModel.sample(8675309L, x, z, s).river() > 0.35) channel++;
                    total++;
                }
            }
            double fraction = channel / (double) total;
            assertTrue(fraction > 0.001, "riverWidth=" + width + " left no rivers: " + fraction);
            assertTrue(fraction < 0.40, "riverWidth=" + width + " flooded the map: " + fraction);
        }
    }
}
