package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.noise.CellularNoise;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariants of the plate-physics model.
 *
 * <p>The point of this layer is that a boundary's landform follows from the plates' own properties and
 * their relative motion, rather than from a single smooth "is this margin colliding" field. That gives
 * three properties worth guarding: plates really do differ from each other, the boundary classification
 * follows the physics, and the plate-pair lookup does not introduce a discontinuity where the
 * nearest/second pair swaps (a triple junction).
 */
final class PlateTectonicsTest {
    private static final long SEED = 8675309L;

    /** Plates must not all be the same, and both crust types must actually occur. */
    @Test
    void platesHaveDistinctProperties() {
        Set<Long> ids = new HashSet<>();
        int continental = 0, oceanic = 0;
        Set<Long> velocityBuckets = new HashSet<>();
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                ids.add(CellularNoise.sample(x * 0.37, z * 0.41, SEED).nearestId());
            }
        }
        for (long id : ids) {
            PlateTectonics.Plate plate = PlateTectonics.plate(id);
            if (plate.continental()) continental++; else oceanic++;
            velocityBuckets.add(Math.round(plate.velocityX() * 8) * 100L + Math.round(plate.velocityZ() * 8));
            assertTrue(plate.resistance() >= 0.25 && plate.resistance() <= 1.0, "resistance out of range");
            assertTrue(Math.hypot(plate.velocityX(), plate.velocityZ()) <= 1.0 + 1e-9, "velocity above unit band");
        }
        assertTrue(ids.size() >= 20, "too few distinct plates sampled: " + ids.size());
        assertTrue(continental > 0, "no continental plates");
        assertTrue(oceanic > 0, "no oceanic plates");
        assertTrue(velocityBuckets.size() >= 10, "plates do not have distinct velocities");
    }

    /** Convergence must follow the closing rate, and the three weights must always sum to one. */
    @Test
    void boundaryClassificationFollowsThePhysics() {
        PlateTectonics.Plate a = new PlateTectonics.Plate(1L, true, 0, 0.0, 0.0, 0.5);

        // `b` heading straight back towards `a` along the normal: the plates close.
        PlateTectonics.Plate closing = new PlateTectonics.Plate(2L, true, 0, -0.8, 0.0, 0.5);
        PlateTectonics.Boundary convergent = PlateTectonics.classify(a, closing, 1.0, 0.0);
        assertTrue(convergent.convergent() > 0.7, "closing plates were not convergent: " + convergent);
        assertTrue(convergent.divergent() < 0.3, "closing plates were also divergent: " + convergent);

        // `b` heading away: the plates separate.
        PlateTectonics.Plate opening = new PlateTectonics.Plate(3L, true, 0, 0.8, 0.0, 0.5);
        PlateTectonics.Boundary divergent = PlateTectonics.classify(a, opening, 1.0, 0.0);
        assertTrue(divergent.divergent() > 0.7, "separating plates were not divergent: " + divergent);
        assertTrue(divergent.convergent() < 0.3, "separating plates were also convergent: " + divergent);

        // `b` sliding along the boundary: a strike-slip margin, which the old model could not express.
        PlateTectonics.Plate shearing = new PlateTectonics.Plate(4L, true, 0, 0.0, 0.9, 0.5);
        PlateTectonics.Boundary transform = PlateTectonics.classify(a, shearing, 1.0, 0.0);
        assertTrue(transform.transform() > 0.7, "sliding plates were not a transform margin: " + transform);

        // Whatever the pair, the three weights are a partition of unity.
        for (double nx = -1; nx <= 1; nx += 0.5) {
            for (double nz = -1; nz <= 1; nz += 0.5) {
                for (long id = 1; id <= 12; id++) {
                    PlateTectonics.Boundary boundary = PlateTectonics.classify(a, PlateTectonics.plate(id * 7919L), nx, nz);
                    double total = boundary.convergent() + boundary.divergent() + boundary.transform();
                    if (total == 0) continue; // the degenerate normal
                    assertEquals(1.0, total, 1e-9, "boundary weights do not sum to one: " + boundary);
                    assertTrue(boundary.convergent() >= 0 && boundary.divergent() >= 0
                            && boundary.transform() >= 0, "negative boundary weight: " + boundary);
                }
            }
        }
    }

    /** A degenerate normal must be reported rather than dividing by zero. */
    @Test
    void degenerateNormalYieldsNoBoundary() {
        PlateTectonics.Plate a = PlateTectonics.plate(11L);
        PlateTectonics.Plate b = PlateTectonics.plate(22L);
        assertEquals(PlateTectonics.Boundary.NONE, PlateTectonics.classify(a, b, 0.0, 0.0));
        assertEquals(0.0, PlateTectonics.classify(a, b, 0.0, 0.0).total());
    }

    /** Crust type must distinguish a subduction zone from a purely continental or oceanic margin. */
    @Test
    void oceanicFractionPeaksAtAMixedMargin() {
        PlateTectonics.Plate ocean = new PlateTectonics.Plate(5L, false, 0, 0, 0, 0.5);
        PlateTectonics.Plate land = new PlateTectonics.Plate(6L, true, 0, 0, 0, 0.5);
        assertEquals(0.0, PlateTectonics.oceanicFraction(land, land), 1e-9);
        assertEquals(0.5, PlateTectonics.oceanicFraction(land, ocean), 1e-9);
        assertEquals(1.0, PlateTectonics.oceanicFraction(ocean, ocean), 1e-9);
    }

    /** The reported normal must be a unit vector, or the classification would be scale-dependent. */
    @Test
    void boundaryNormalIsUnitLength() {
        int checked = 0;
        for (int i = 0; i < 200; i++) {
            double x = (i % 20 - 10) * 260.0 + 3.5;
            double z = (i / 20 - 5) * 410.0 - 7.5;
            CellularNoise.Cell cell = CellularNoise.sample(x / 700.0, z / 700.0, SEED);
            double length = Math.hypot(cell.normalX(), cell.normalZ());
            if (length < 1e-9) continue; // degenerate, already covered above
            checked++;
            assertEquals(1.0, length, 1e-9, "boundary normal is not unit length");
        }
        assertTrue(checked > 100, "too few non-degenerate normals sampled: " + checked);
    }

    /**
     * Where the nearest/second plate pair swaps - a triple junction - {@code clarity} must fall, because
     * that is the guard the terrain relies on to fade the velocity-derived physics out instead of
     * stepping a 560-block orogeny amplitude across it.
     */
    @Test
    void pairChangesAreAccompaniedByLowClarity() {
        List<Probe> samples = new ArrayList<>();
        // Lattice units, not blocks: a plate spans about one lattice unit, so a step of 0.002 walks the
        // line finely enough to resolve a triple junction. (Passing raw block coordinates here would
        // step across whole plates at a time and make the comparison meaningless.)
        for (double lattice = -4.0; lattice <= 4.0; lattice += 0.002) {
            CellularNoise.Cell cell = CellularNoise.sample(lattice, 1.731, SEED);
            samples.add(new Probe(cell.scalar(), cell.boundary(), cell.clarity(),
                    cell.nearestId(), cell.secondId()));
        }
        int pairChanges = 0;
        for (int i = 1; i < samples.size(); i++) {
            Probe previous = samples.get(i - 1);
            Probe current = samples.get(i);
            // The trait must never tear, whatever happens to the pair.
            assertTrue(Math.abs(current.scalar() - previous.scalar()) < 0.2,
                    "plate trait tore along the test line: " + previous.scalar() + " -> " + current.scalar());
            // Compare the UNORDERED pair: when the nearest and second plates simply swap places the
            // boundary is the same physical margin, and classify() is symmetric under swapping the pair
            // and flipping the normal, so nothing is discontinuous there. What matters is a change of
            // pair SET - a genuine triple junction.
            if (pairKey(previous.nearestId(), previous.secondId())
                    == pairKey(current.nearestId(), current.secondId())) {
                continue;
            }
            pairChanges++;
            assertTrue(current.clarity() < 0.5 || previous.clarity() < 0.5,
                    "plate pair changed while clarity stayed high: "
                            + previous.clarity() + " -> " + current.clarity());
        }
        assertTrue(pairChanges > 0, "the test line never crossed a plate pair change");
    }

    /** One probe along the test line. Ids are kept as longs - a 64-bit plate hash does not fit a double. */
    private record Probe(double scalar, double boundary, double clarity, long nearestId, long secondId) {}

    /** Order-independent key for the nearest/second plate pair. */
    private static long pairKey(long a, long b) {
        return a < b ? (a * 31L + b) : (b * 31L + a);
    }
}
