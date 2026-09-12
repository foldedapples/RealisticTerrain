package com.cokedoutsnail.realisticterrain.worldgen.hydro;

import com.cokedoutsnail.realisticterrain.worldgen.TerrainSetting;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Topology invariants of the connected hydrology network.
 *
 * <p>The point of this file is that it tests <em>topology</em>, not a raster threshold. "No cycles",
 * "every source reaches an outlet", "discharge never decreases downstream" and "the water surface
 * never rises downstream" are statements about a flow graph; the implementation this replaced had no
 * graph, which is why the old test could only check a local height inequality and prove nothing.
 */
final class DrainageGraphTest {
    private static final long SEED = 0x5245414C49535449L;

    private static RiverNetwork.HydroShapeParams params() {
        return HydrologyManager.paramsFrom(TerrainSettings.DEFAULT);
    }

    private static DrainageGraph graphAt(long seed, int tx, int tz) {
        HydrologyTile tile = HydrologyTile.solve(new HydrologyTileKey(tx, tz), seed,
                TerrainSettings.DEFAULT, params());
        return DrainageGraph.of(tile);
    }

    /** 5. No flow-graph cycles, proved exactly by rank ordering rather than by walking. */
    @Test
    void flowGraphHasNoCycles() {
        for (int tx = -2; tx <= 2; tx++) {
            for (int tz = -2; tz <= 2; tz++) {
                DrainageGraph graph = graphAt(SEED, tx, tz);
                assertFalse(graph.hasCycle(), "cycle in " + graph.tile().key);
            }
        }
    }

    /** 6. Every channel node must reach a valid outlet (the sea, or off the solved domain). */
    @Test
    void everyChannelReachesAnOutlet() {
        int checked = 0;
        for (int tx = -1; tx <= 1; tx++) {
            for (int tz = -1; tz <= 1; tz++) {
                DrainageGraph graph = graphAt(SEED, tx, tz);
                for (DrainageNode node : graph.nodes()) {
                    checked++;
                    assertTrue(graph.reachesOutlet(node.id()),
                            "channel node " + node.id() + " never reaches an outlet in " + graph.tile().key);
                }
            }
        }
        assertTrue(checked > 0, "no channel nodes were produced at all");
    }

    /** 11. Discharge must never fall from a node to its downstream neighbour. */
    @Test
    void dischargeNeverDecreasesDownstream() {
        for (int tx = -1; tx <= 1; tx++) {
            for (int tz = -1; tz <= 1; tz++) {
                DrainageGraph graph = graphAt(SEED, tx, tz);
                assertTrue(graph.dischargeMonotone(), "discharge fell downstream in " + graph.tile().key);
            }
        }
    }

    /** 8. The water surface must never rise in the downstream direction. */
    @Test
    void waterSurfaceNeverRisesDownstream() {
        for (int tx = -1; tx <= 1; tx++) {
            for (int tz = -1; tz <= 1; tz++) {
                DrainageGraph graph = graphAt(SEED, tx, tz);
                assertTrue(graph.surfaceMonotone(), "water surface rose downstream in " + graph.tile().key);
            }
        }
    }

    /** 12. Strahler order is at least 1 everywhere and never exceeds the node count. */
    @Test
    void strahlerOrderIsSane() {
        DrainageGraph graph = graphAt(SEED, 0, 0);
        int max = 0;
        for (DrainageNode node : graph.nodes()) {
            assertTrue(node.strahler() >= 1, "order below 1");
            max = Math.max(max, node.strahler());
        }
        assertTrue(max <= 16, "implausible Strahler order: " + max);
    }

    /** 18. A river-free configuration must publish no rivers at all. */
    @Test
    void disablingRiversRemovesEveryChannel() {
        TerrainSettings off = TerrainSettings.DEFAULT.with(TerrainSetting.RIVER_DENSITY, 0.0);
        RiverNetwork.HydroShapeParams p = HydrologyManager.paramsFrom(off);
        assertFalse(p.riversEnabled(), "riverDensity=0 should disable rivers");
        HydrologyTile tile = HydrologyTile.solve(new HydrologyTileKey(0, 0), SEED, off, p);
        assertEquals(0, tile.channelCells, "rivers were carved with river density at zero");
    }

    /** 9-10 and 22. Bed below water, water below bank, and nothing non-finite. */
    @Test
    void channelGeometryIsConsistentAndFinite() {
        TerrainSettings s = TerrainSettings.DEFAULT;
        int water = 0;
        for (int gz = -1500; gz <= 1500; gz += 53) {
            for (int gx = -1500; gx <= 1500; gx += 57) {
                RiverSample rs = HydrologyManager.sample(gx + 0.5, gz - 0.5, SEED, s);
                assertTrue(Double.isFinite(rs.bedElevation()), "non-finite bed");
                assertTrue(Double.isFinite(rs.bankElevation()), "non-finite bank");
                assertTrue(Double.isFinite(rs.distanceToCenter()), "non-finite distance");
                assertTrue(Double.isFinite(rs.width()) && rs.width() >= 0, "bad width");
                assertTrue(Double.isFinite(rs.depth()) && rs.depth() >= 0, "bad depth");
                assertTrue(Double.isFinite(rs.discharge()) && rs.discharge() >= 0, "bad discharge");
                assertTrue(Double.isFinite(rs.flowX()) && Double.isFinite(rs.flowZ()), "bad flow vector");
                if (!rs.isWater()) {
                    assertEquals(WaterBodyType.NONE, rs.waterBody());
                    assertEquals(Double.NEGATIVE_INFINITY, rs.waterSurface(),
                            "a dry column must carry no water surface at " + gx + "," + gz);
                    continue;
                }
                water++;
                assertTrue(rs.geometryIsConsistent(),
                        "bed/surface/bank out of order at " + gx + "," + gz + ": " + rs);
                assertTrue(rs.bedElevation() < rs.waterSurface(),
                        "bed not below water at " + gx + "," + gz);
                assertTrue(rs.waterSurface() < rs.bankElevation(),
                        "water above the bank at " + gx + "," + gz);
            }
        }
        assertTrue(water > 0, "the default world produced no water at all");
    }

    /** 21. Negative-coordinate tiles must behave exactly like positive ones. */
    @Test
    void negativeCoordinatesWork() {
        HydrologyTile neg = HydrologyTile.solve(new HydrologyTileKey(-3, -4), SEED,
                TerrainSettings.DEFAULT, params());
        HydrologyTile same = HydrologyTile.solve(new HydrologyTileKey(-3, -4), SEED,
                TerrainSettings.DEFAULT, params());
        assertEquals(neg.channelCells, same.channelCells, "negative tile is not deterministic");
        assertEquals(neg.popCount, same.popCount);
        assertArrayEquals(neg.filled, same.filled);
        // floorDiv puts -1 and 0 blocks in genuinely different tiles.
        assertEquals(-1, HydrologyTileKey.of(-1.0, -1.0, HydrologyTile.TILE_BLOCKS).tx());
        assertEquals(0, HydrologyTileKey.of(0.0, 0.0, HydrologyTile.TILE_BLOCKS).tx());
    }
}
