package com.cokedoutsnail.realisticterrain.worldgen.hydro;

import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Algorithm tests on synthetic heightfields.
 *
 * <p>These exist because random terrain is a poor oracle: if the solver has a pit, a cycle or a
 * basin leak, random terrain just produces a slightly wrong river somewhere and nobody notices. A
 * single downhill plane, a V-shaped valley, two merging tributaries or a closed bowl make the same
 * failure obvious and reproducible.
 */
final class HydrologySyntheticTest {
    private static final long SEED = 8675309L;

    private static RiverNetwork.HydroShapeParams params() {
        return HydrologyManager.paramsFrom(TerrainSettings.DEFAULT);
    }

    private static HydrologyTile solve(int tx, int tz, ElevationGrid grid) {
        return HydrologyTile.solve(new HydrologyTileKey(tx, tz), SEED, TerrainSettings.DEFAULT,
                params(), grid);
    }

    /** 7. On a uniform downhill plane flow must be monotone and never uphill. */
    @Test
    void singleDownhillPlaneDrainsCleanly() {
        ElevationGrid plane = (x, z) -> 400.0 - x * 0.05 - z * 0.03;
        HydrologyTile tile = solve(0, 0, plane);
        DrainageGraph graph = DrainageGraph.of(tile);
        assertFalse(graph.hasCycle(), "a plane produced a flow cycle");
        assertTrue(graph.dischargeMonotone(), "a plane produced non-monotone discharge");
        assertTrue(graph.surfaceMonotone(), "a plane produced a rising water surface");
        for (int c = 0; c < tile.receiver.length; c++) {
            int r = tile.receiver[c];
            if (r < 0) continue;
            assertTrue(tile.filled[r] <= tile.filled[c] + 1e-3,
                    "flow ran uphill on a plane at cell " + c);
        }
    }

    /** A V-shaped valley must collect its water into a channel that stays on the valley axis. */
    @Test
    void vShapedValleyCollectsIntoOneChannel() {
        ElevationGrid valley = (x, z) -> 300.0 + Math.abs(z) * 0.35 - x * 0.02;
        HydrologyTile tile = solve(0, 0, valley);
        int bestCell = -1;
        double best = -1;
        for (int j = 40; j < HydrologyTile.GRID - 40; j++) {
            for (int i = 40; i < HydrologyTile.GRID - 40; i++) {
                int c = j * HydrologyTile.GRID + i;
                if (tile.accumulation[c] > best) {
                    best = tile.accumulation[c];
                    bestCell = c;
                }
            }
        }
        assertTrue(bestCell >= 0, "no accumulation found in the valley");
        double axisDistance = Math.abs(tile.blockZ(bestCell / HydrologyTile.GRID));
        assertTrue(axisDistance < HydrologyTile.TILE_BLOCKS * 0.25,
                "the trunk channel wandered off the valley axis by " + axisDistance + " blocks");
    }

    /** 2 and 11-12. Two tributaries merging must raise discharge and Strahler order. */
    @Test
    void twoTributariesMergeWithCorrectTopology() {
        ElevationGrid twoValleys = (x, z) -> Math.min(
                320.0 + Math.abs(z + 700.0) * 0.40 - x * 0.015,
                320.0 + Math.abs(z - 700.0) * 0.40 - x * 0.015);
        HydrologyTile tile = solve(0, 0, twoValleys);
        DrainageGraph graph = DrainageGraph.of(tile);
        assertFalse(graph.hasCycle(), "the confluence case produced a cycle");
        assertTrue(graph.dischargeMonotone(), "discharge fell through the confluence");
        assertTrue(graph.surfaceMonotone(), "the water surface rose through the confluence");

        int[] upstream = new int[tile.receiver.length];
        for (int c = 0; c < tile.receiver.length; c++) {
            int r = tile.receiver[c];
            if (r >= 0 && tile.river[c] > 0.25f && tile.river[r] > 0.25f) upstream[r]++;
        }
        int confluences = 0;
        for (int c = 0; c < tile.receiver.length; c++) {
            if (upstream[c] >= 2) {
                confluences++;
                assertTrue(tile.strahler[c] >= 2, "a confluence did not raise Strahler order");
            }
        }
        assertTrue(confluences > 0, "two merging valleys produced no confluence at all");
    }

    /** 4. A closed bowl must fill to its own spill level, not leak through the rim. */
    @Test
    void closedBasinFillsInsteadOfLeaking() {
        ElevationGrid bowl = (x, z) -> 250.0 + Math.hypot(x, z) * 0.30;
        HydrologyTile tile = solve(0, 0, bowl);
        int centre = (HydrologyTile.GRID / 2) * HydrologyTile.GRID + HydrologyTile.GRID / 2;
        assertEquals(tile.elevation[centre], tile.filled[centre], 1e-3,
                "the deepest point of a closed bowl should be its own spill level");
        for (int c = 0; c < tile.receiver.length; c++) {
            int r = tile.receiver[c];
            if (r < 0) continue;
            assertTrue(tile.filled[r] <= tile.filled[c] + 1e-3,
                    "flow ran uphill out of a closed bowl at cell " + c);
        }
    }

    /** 6. A ridge must separate two catchments rather than letting a channel cross it. */
    @Test
    void ridgeSeparatesTwoBasins() {
        ElevationGrid ridge = (x, z) ->
                280.0 + Math.max(0.0, 60.0 - Math.abs(x) * 0.6) + Math.abs(z) * 0.05;
        HydrologyTile tile = solve(0, 0, ridge);
        for (int j = 40; j < HydrologyTile.GRID - 40; j += 3) {
            for (int i = 40; i < HydrologyTile.GRID - 40; i += 3) {
                int c = j * HydrologyTile.GRID + i;
                if (tile.river[c] <= 0.25f) continue;
                int r = tile.receiver[c];
                if (r < 0) continue;
                assertTrue(tile.filled[r] <= tile.filled[c] + 1e-3,
                        "a channel climbed the ridge at cell " + c);
            }
        }
    }

    /** 13-15. A river crossing a tile boundary must not be moved or stepped by the boundary. */
    @Test
    void riverCrossesTileBoundaryWithoutMoving() {
        ElevationGrid plane = (x, z) -> 400.0 - x * 0.04 - z * 0.01;
        HydrologyTile left = solve(-1, 0, plane);
        HydrologyTile right = solve(0, 0, plane);
        // Sample one block either side of the shared boundary at x = 0, well inside the published
        // z range of both tiles, so the halo - not a clamp - is what carries the flow across.
        double before = HydrologyManager.sample(left, -1.0, 1000.0).bankElevation();
        double after = HydrologyManager.sample(right, 1.0, 1000.0).bankElevation();
        assertTrue(Math.abs(before - after) < 35.0,
                "the boundary introduced a step in the shaping fields: " + before + " vs " + after);
        RiverSample a = HydrologyManager.sample(left, -100.0, 1000.0);
        RiverSample b = HydrologyManager.sample(right, 100.0, 1000.0);
        assertEquals(a.flowX(), b.flowX(), 0.35, "flow direction disagreed across a tile boundary");
    }

    /** 21. Tiles either side of the origin must tile without overlapping or leaving a gap. */
    @Test
    void originStraddlingTilesAreConsistent() {
        ElevationGrid plane = (x, z) -> 400.0 - x * 0.04;
        HydrologyTile neg = solve(-1, -1, plane);
        HydrologyTile pos = solve(0, 0, plane);
        assertEquals(neg.seed, pos.seed);
        assertEquals(neg.channelCells, solve(-1, -1, plane).channelCells, "negative tile not repeatable");
        assertNotEquals(neg.originCellX, pos.originCellX, "origin tiles overlapped exactly");
        assertEquals(0, pos.originCellX + HydrologyTile.HALO_CELLS, "tile 0 must start at block 0");
        assertEquals(-HydrologyTile.TILE_BLOCKS, neg.originCellX * HydrologyTile.CELL
                + HydrologyTile.HALO_BLOCKS, "tile -1 must end at block 0");
    }
}
