package com.cokedoutsnail.realisticterrain.worldgen.hydro;

import com.cokedoutsnail.realisticterrain.worldgen.TerrainModel;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Hydrology diagnostic maps: one PNG per hydrology field, for each of the five fixed seeds, plus the
 * tile-boundary overlay that makes a cross-region seam obvious if one exists.
 *
 * <p>These are the maps a human has to look at. Every failure mode the task lists - square tile
 * patterns, straight boundary cuts, rivers ending suddenly, parallel duplicate rivers, rivers
 * crossing ridges, rivers widening upstream, broken tributary connections, random blue puddles,
 * giant water sheets, floating water, empty carved trenches - is invisible in a numeric assertion and
 * obvious in a picture.
 *
 * <p>It is skipped unless explicitly asked for, because it writes files and is slow:
 *
 * <pre>./gradlew test --tests '*HydrologyDiagnostics*' -Drealisticterrain.diagnostics=true</pre>
 *
 * <p>Output goes to {@code diagnostics/}, which is git-ignored.
 */
final class HydrologyDiagnostics {
    private static final int SIZE = 512;                  // pixels per side
    private static final int SPAN = 8192;                 // blocks covered (>= 8192, as required)
    private static final long[] SEEDS = {8675309L, 42L, 0L, 0x5245414C49535449L, 123456789L};

    @Test
    void exportHydrologyMaps() throws IOException {
        assumeTrue(Boolean.getBoolean("realisticterrain.diagnostics"),
                "diagnostics are opt-in: pass -Drealisticterrain.diagnostics=true");

        Path dir = Path.of("diagnostics");
        Files.createDirectories(dir);
        TerrainSettings s = TerrainSettings.DEFAULT;
        System.out.println("[hydro-diag] tile=" + HydrologyTile.TILE_BLOCKS + " blocks, halo="
                + HydrologyTile.HALO_BLOCKS + " blocks, cell=" + HydrologyTile.CELL
                + " blocks, seaLevel=" + s.seaLevel());
        for (long seed : SEEDS) {
            render(seed, s, dir);
        }
        System.out.println("[hydro-diag] water-body invariants: " + waterBodyInvariants(SEEDS[0], s)
                + " | graphAcyclic=" + graphAcyclic(SEEDS[0]));
        System.out.println("[hydro-diag] wrote maps to " + dir.toAbsolutePath());
    }

    /** A column the classifier calls river-free must not report any water at all. */
    private static String waterBodyInvariants(long seed, TerrainSettings s) {
        long bad = 0;
        long ocean = 0;
        long river = 0;
        long lake = 0;
        long wetland = 0;
        long dry = 0;
        for (int z = -2000; z <= 2000; z += 61) {
            for (int x = -2000; x <= 2000; x += 67) {
                RiverSample rs = HydrologyManager.sample(x, z, seed, s);
                switch (rs.waterBody()) {
                    case OCEAN -> ocean++;
                    case RIVER -> {
                        river++;
                        if (rs.river() < HydrologyManager.RIVER_PRESENCE) bad++;
                    }
                    case LAKE -> {
                        lake++;
                        if (rs.lake() < HydrologyManager.LAKE_PRESENCE) bad++;
                    }
                    case WETLAND -> {
                        wetland++;
                        if (rs.wetland() < HydrologyManager.WETLAND_PRESENCE) bad++;
                    }
                    case NONE -> {
                        dry++;
                        if (rs.waterSurface() != Double.NEGATIVE_INFINITY) bad++;
                    }
                }
                if (rs.isWater()) {
                    if (!rs.geometryIsConsistent()) bad++;
                    if (rs.waterBody() != WaterBodyType.OCEAN && !(rs.bedElevation() < rs.waterSurface())) bad++;
                    if (rs.waterSurface() >= rs.bankElevation() && rs.waterBody() != WaterBodyType.OCEAN) bad++;
                }
            }
        }
        return "bad=" + bad + " dry=" + dry + " river=" + river + " lake=" + lake
                + " wetland=" + wetland + " ocean=" + ocean;
    }

    /** The published graph must be acyclic across several tiles. */
    private static boolean graphAcyclic(long seed) {
        TerrainSettings s = TerrainSettings.DEFAULT;
        for (int tx = -1; tx <= 1; tx++) {
            for (int tz = -1; tz <= 1; tz++) {
                HydrologyTile tile = HydrologyTile.solve(new HydrologyTileKey(tx, tz), seed, s,
                        HydrologyManager.paramsFrom(s));
                if (DrainageGraph.of(tile).hasCycle()) return false;
            }
        }
        return true;
    }

    private static void render(long seed, TerrainSettings s, Path dir) throws IOException {
        String tag = "hydro_seed" + Long.toHexString(seed);
        BufferedImage elevation = img();
        BufferedImage filledImg = img();
        BufferedImage hillshade = img();
        BufferedImage flowDir = img();
        BufferedImage accumulation = img();
        BufferedImage riverGraph = img();
        BufferedImage graphWithTiles = img();
        BufferedImage strahler = img();
        BufferedImage widthImg = img();
        BufferedImage depthImg = img();
        BufferedImage surfaceImg = img();
        BufferedImage waterImg = img();
        BufferedImage carvedImg = img();

        double step = SPAN / (double) SIZE;
        double[] elev = new double[SIZE * SIZE];
        double minH = Double.MAX_VALUE;
        double maxH = -Double.MAX_VALUE;
        double maxAcc = 1;
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                double x = (px - SIZE / 2.0) * step;
                double z = (py - SIZE / 2.0) * step;
                double h = TerrainModel.baseHeight(x, z, seed, s);
                elev[py * SIZE + px] = h;
                minH = Math.min(minH, h);
                maxH = Math.max(maxH, h);
                maxAcc = Math.max(maxAcc, HydrologyManager.sample(x, z, seed, s).discharge());
            }
        }
        double range = Math.max(1.0, maxH - minH);

        int[][] basins = new int[SIZE][SIZE];
        long channels = 0;
        long waterCells = 0;
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                double x = (px - SIZE / 2.0) * step;
                double z = (py - SIZE / 2.0) * step;
                RiverSample rs = HydrologyManager.sample(x, z, seed, s);
                HydrologyTile tile = HydrologyManager.tileAt(x, z, seed, s);
                int cell = clampIndex(tile.gridZ(z)) * HydrologyTile.GRID + clampIndex(tile.gridX(x));
                basins[py][px] = tile.basinId[cell];
                if (rs.river() > 0.5) channels++;
                if (rs.isWater()) waterCells++;

                double t = (elev[py * SIZE + px] - minH) / range;
                elevation.setRGB(px, py, ramp(t));
                filledImg.setRGB(px, py, ramp((tile.filled[cell] - minH) / range));
                hillshade.setRGB(px, py, hillshade(elev, px, py, step));
                flowDir.setRGB(px, py, flowColor(tile.flowX[cell], tile.flowZ[cell]));
                accumulation.setRGB(px, py, logColor(rs.discharge() / maxAcc));
                int graph = graphColor(rs);
                riverGraph.setRGB(px, py, graph);
                graphWithTiles.setRGB(px, py, onTileEdge(x, z) ? rgb(255, 0, 255) : graph);
                strahler.setRGB(px, py, grey(rs.order()));
                widthImg.setRGB(px, py, grey(clamp01(rs.width() / 60.0)));
                depthImg.setRGB(px, py, grey(clamp01(rs.depth() / 16.0)));
                surfaceImg.setRGB(px, py, surfaceColor(rs, s));
                waterImg.setRGB(px, py, waterColor(rs));
                carvedImg.setRGB(px, py, ramp((rs.bedElevation() - minH) / range));
            }
        }

        write(elevation, dir.resolve(tag + "_elevation.png"));
        write(filledImg, dir.resolve(tag + "_filled.png"));
        write(hillshade, dir.resolve(tag + "_hillshade.png"));
        write(flowDir, dir.resolve(tag + "_flow_direction.png"));
        write(accumulation, dir.resolve(tag + "_flow_accumulation.png"));
        write(colourBasins(basins), dir.resolve(tag + "_basins.png"));
        write(riverGraph, dir.resolve(tag + "_river_graph.png"));
        write(graphWithTiles, dir.resolve(tag + "_river_graph_tile_boundaries.png"));
        write(strahler, dir.resolve(tag + "_strahler.png"));
        write(widthImg, dir.resolve(tag + "_width.png"));
        write(depthImg, dir.resolve(tag + "_depth.png"));
        write(surfaceImg, dir.resolve(tag + "_water_surface.png"));
        write(waterImg, dir.resolve(tag + "_water_bodies.png"));
        write(carvedImg, dir.resolve(tag + "_carved.png"));
        System.out.printf("[hydro-diag] %-18s elevation %.0f..%.0f  maxDischarge %.0f  channelPx=%d  waterPx=%d%n",
                Long.toHexString(seed), minH, maxH, maxAcc, channels, waterCells);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static int clampIndex(double v) {
        int i = (int) Math.floor(v);
        return Math.max(0, Math.min(HydrologyTile.GRID - 2, i));
    }

    /** True within 8 blocks of a published tile edge, so the overlay marks the real seams. */
    private static boolean onTileEdge(double x, double z) {
        long edge = HydrologyTile.TILE_BLOCKS;
        return Math.floorMod((long) Math.floor(x), edge) < 8L
                || Math.floorMod((long) Math.floor(z), edge) < 8L;
    }

    private static BufferedImage img() {
        return new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
    }

    private static void write(BufferedImage image, Path path) throws IOException {
        ImageIO.write(image, "PNG", path.toFile());
    }

    /** River topology: order-shaded channels, lakes, wetlands and ocean, on black land. */
    private static int graphColor(RiverSample rs) {
        if (!rs.isWater()) return rgb(18, 18, 22);
        return switch (rs.waterBody()) {
            case RIVER -> ramp(clamp01(rs.order()));
            case LAKE -> rgb(40, 90, 220);
            case WETLAND -> rgb(70, 130, 90);
            case OCEAN -> rgb(20, 45, 110);
            case NONE -> rgb(18, 18, 22);
        };
    }

    private static int waterColor(RiverSample rs) {
        return switch (rs.waterBody()) {
            case NONE -> rgb(24, 26, 30);
            case OCEAN -> rgb(20, 45, 110);
            case RIVER -> rgb(60, 140, 255);
            case LAKE -> rgb(40, 90, 220);
            case WETLAND -> rgb(80, 150, 110);
        };
    }

    /** Water-surface elevation coloured on the shared ramp, so steps at seams show as bands. */
    private static int surfaceColor(RiverSample rs, TerrainSettings s) {
        double v = rs.isWater() ? rs.waterSurface() : rs.bankElevation();
        return ramp(clamp01((v - s.seaLevel()) / 600.0));
    }

    /** Flow direction as a hue wheel, so two tiles disagreeing shows as a colour break. */
    private static int flowColor(double fx, double fz) {
        double ang = Math.atan2(fz, fx);
        double r = 0.5 + 0.5 * Math.cos(ang);
        double g = 0.5 + 0.5 * Math.cos(ang - 2.094);
        double b = 0.5 + 0.5 * Math.cos(ang + 2.094);
        return rgb((int) (r * 255), (int) (g * 255), (int) (b * 255));
    }

    private static int logColor(double v) {
        return ramp(clamp01(Math.log1p(v * 100.0) / Math.log1p(100.0)));
    }

    private static int hillshade(double[] elev, int px, int py, double step) {
        int xm = Math.max(0, px - 1);
        int xp = Math.min(SIZE - 1, px + 1);
        int ym = Math.max(0, py - 1);
        int yp = Math.min(SIZE - 1, py + 1);
        double dzdx = (elev[py * SIZE + xp] - elev[py * SIZE + xm]) / (2 * step);
        double dzdy = (elev[yp * SIZE + px] - elev[ym * SIZE + px]) / (2 * step);
        double slope = Math.atan(Math.hypot(dzdx, dzdy));
        double aspect = Math.atan2(dzdy, -dzdx);
        double shade = Math.cos(slope) * Math.cos(0.6)
                + Math.sin(slope) * Math.sin(0.6) * Math.cos(aspect - 2.4);
        return grey(0.35 + 0.65 * shade);
    }

    /** Distinct deterministic colour per catchment, so a basin boundary is a colour boundary. */
    private static BufferedImage colourBasins(int[][] basins) {
        BufferedImage out = img();
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                long h = basins[py][px] * 0x9E3779B97F4A7C15L;
                h ^= h >>> 31;
                out.setRGB(px, py, rgb((int) (h & 0xFF) / 2 + 64,
                        (int) ((h >>> 8) & 0xFF) / 2 + 64, (int) ((h >>> 16) & 0xFF) / 2 + 64));
            }
        }
        return out;
    }

    private static int grey(double v) {
        int g = (int) (clamp01(v) * 255);
        return rgb(g, g, g);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static int ramp(double t) {
        int[][] stops = {{20, 40, 120}, {40, 90, 190}, {230, 220, 150}, {70, 140, 60},
                {120, 100, 70}, {150, 150, 150}, {255, 255, 255}};
        double pos = clamp01(t) * (stops.length - 1);
        int i = (int) pos;
        if (i >= stops.length - 1) return rgb(stops[stops.length - 1]);
        double f = pos - i;
        return rgb(new int[]{
                (int) (stops[i][0] + (stops[i + 1][0] - stops[i][0]) * f),
                (int) (stops[i][1] + (stops[i + 1][1] - stops[i][1]) * f),
                (int) (stops[i][2] + (stops[i + 1][2] - stops[i][2]) * f)});
    }

    private static int rgb(int[] c) {
        return (c[0] << 16) | (c[1] << 8) | c[2];
    }

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }
}
