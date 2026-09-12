package com.cokedoutsnail.realisticterrain.worldgen;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Developer utility: renders deterministic PNG maps and prints a per-seed table for the Phase-1
 * biome/surface work, so a change can be compared against the previous one by eye and by number.
 *
 * <p>It is skipped unless explicitly asked for, because it writes files and is slow:
 *
 * <pre>./gradlew test --tests '*TerrainDiagnostics*' -Drealisticterrain.diagnostics=true</pre>
 *
 * <p>Output goes to {@code diagnostics/}, which is git-ignored: generated images are development
 * artefacts, not sources. Because the classification and surface rules are pure, this runs in a
 * plain unit-test JVM with no Minecraft bootstrap.
 */
final class TerrainDiagnostics {
    private static final int SIZE = 512;          // pixels per side
    private static final int SPAN = 8192;         // blocks covered by the map
    private static final long[] SEEDS = {8675309L, 42L, 0L, 0x5245414C49535449L, 123456789L};

    @Test
    void exportMaps() throws IOException {
        assumeTrue(Boolean.getBoolean("realisticterrain.diagnostics"),
                "diagnostics are opt-in: pass -Drealisticterrain.diagnostics=true");

        Path dir = Path.of("diagnostics");
        Files.createDirectories(dir);
        TerrainSettings settings = TerrainSettings.DEFAULT;
        System.out.println("[diagnostics] profile=DEFAULT seaLevel=" + settings.seaLevel()
                + " snowLine=" + settings.snowLine() + " coastLine=" + settings.coastLine());
        System.out.printf("%-14s %8s %8s | %s%n", "seed", "land", "water", "biome percentages of land");
        for (long seed : SEEDS) {
            report(seed, settings, dir);
        }
        System.out.println("[diagnostics] wrote maps to " + dir.toAbsolutePath());
    }

    private static void report(long seed, TerrainSettings s, Path dir) throws IOException {
        double step = SPAN / (double) SIZE;
        BufferedImage biomeImg = img();
        BufferedImage tempImg = img();
        BufferedImage moistImg = img();
        BufferedImage heightImg = img();
        BufferedImage coastImg = img();
        BufferedImage surfaceImg = img();
        BufferedImage hydroImg = img();

        double minH = Double.MAX_VALUE, maxH = -Double.MAX_VALUE;
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                double x = (px - SIZE / 2.0) * step;
                double z = (py - SIZE / 2.0) * step;
                TerrainModel.Sample sm = TerrainModel.sample(seed, x, z, s);
                minH = Math.min(minH, sm.height());
                maxH = Math.max(maxH, sm.height());
            }
        }
        double range = Math.max(1.0, maxH - minH);

        EnumMap<TerrainBiomeType, Long> landBiomes = new EnumMap<>(TerrainBiomeType.class);
        long land = 0, water = 0, grass = 0, dirt = 0, sand = 0, stone = 0, snow = 0;
        long river = 0, lake = 0, ocean = 0;
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                double x = (px - SIZE / 2.0) * step;
                double z = (py - SIZE / 2.0) * step;
                TerrainModel.Sample sm = TerrainModel.sample(seed, x, z, s);
                TerrainBiomeType type = TerrainBiomeClassifier.classify(seed, x, z, s, sm);
                biomeImg.setRGB(px, py, biomeColor(type));
                tempImg.setRGB(px, py, divColor(sm.temperature()));
                moistImg.setRGB(px, py, divColor(sm.moisture()));
                heightImg.setRGB(px, py, ramp((sm.height() - minH) / range));
                coastImg.setRGB(px, py, coastColor(sm, s));
                hydroImg.setRGB(px, py, hydroColor(sm));

                if (TerrainHydrology.underwater(sm)) {
                    water++;
                    if (type == TerrainBiomeType.RIVER) river++;
                    if (type == TerrainBiomeType.LAKE) lake++;
                    if (type == TerrainBiomeType.OCEAN || type == TerrainBiomeType.DEEP_OCEAN) ocean++;
                } else {
                    land++;
                    landBiomes.merge(type, 1L, Long::sum);
                }
                int surfaceY = (int) Math.floor(sm.height());
                SurfaceMaterial top = TerrainSurfaceResolver.resolve(
                        new TerrainSurfaceResolver.SurfaceContext(type, sm, surfaceY, surfaceY, (int) x, (int) z, seed, s));
                surfaceImg.setRGB(px, py, materialColor(top));
                if (!TerrainHydrology.underwater(sm)) {
                    if (top == SurfaceMaterial.GRASS_BLOCK) grass++;
                    else if (top == SurfaceMaterial.DIRT || top == SurfaceMaterial.COARSE_DIRT
                            || top == SurfaceMaterial.PODZOL) dirt++;
                    else if (top.isSand()) sand++;
                    else if (top == SurfaceMaterial.STONE || top == SurfaceMaterial.GRAVEL) stone++;
                    else if (top == SurfaceMaterial.SNOW_BLOCK) snow++;
                }
            }
        }

        String hex = Long.toHexString(seed);
        write(biomeImg, dir.resolve("seed" + hex + "_biome.png"));
        write(tempImg, dir.resolve("seed" + hex + "_temperature.png"));
        write(moistImg, dir.resolve("seed" + hex + "_moisture.png"));
        write(heightImg, dir.resolve("seed" + hex + "_height.png"));
        write(coastImg, dir.resolve("seed" + hex + "_coastline.png"));
        write(surfaceImg, dir.resolve("seed" + hex + "_surface.png"));
        write(hydroImg, dir.resolve("seed" + hex + "_hydrology.png"));

        long total = (long) SIZE * SIZE;
        StringBuilder biomes = new StringBuilder();
        for (Map.Entry<TerrainBiomeType, Long> e : landBiomes.entrySet()) {
            if (!e.getKey().isLand()) continue;
            biomes.append(String.format("%s=%.1f%% ", e.getKey(), 100.0 * e.getValue() / Math.max(1, land)));
        }
        System.out.printf("%-14s %8d %8d | %s%n", Long.toHexString(seed), land, water, biomes.toString().trim());
        System.out.printf("%-14s land=%.1f%% water=%.1f%% grass=%.1f%% dirt=%.1f%% sand=%.1f%% stone=%.1f%% snow=%.1f%% river=%.1f%% lake=%.1f%% ocean=%.1f%%%n",
                "  surfaces", 100.0 * land / total, 100.0 * water / total,
                100.0 * grass / Math.max(1, land), 100.0 * dirt / Math.max(1, land),
                100.0 * sand / Math.max(1, land), 100.0 * stone / Math.max(1, land),
                100.0 * snow / Math.max(1, land),
                100.0 * river / Math.max(1, water), 100.0 * lake / Math.max(1, water),
                100.0 * ocean / Math.max(1, water));
    }

    private static BufferedImage img() {
        return new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
    }

    private static void write(BufferedImage image, Path path) throws IOException {
        ImageIO.write(image, "PNG", path.toFile());
    }

    /** Distinct colours per classification, so a biome map reads at a glance. */
    private static int biomeColor(TerrainBiomeType type) {
        return switch (type) {
            case DEEP_OCEAN -> rgb(10, 25, 90);
            case OCEAN -> rgb(30, 80, 180);
            case RIVER -> rgb(70, 150, 255);
            case LAKE -> rgb(110, 190, 235);
            case BEACH -> rgb(235, 220, 150);
            case DESERT -> rgb(235, 210, 110);
            case SAVANNA -> rgb(190, 190, 100);
            case PLAINS -> rgb(130, 190, 90);
            case FOREST -> rgb(60, 140, 60);
            case DARK_FOREST -> rgb(30, 100, 45);
            case BIRCH_FOREST -> rgb(120, 180, 110);
            case JUNGLE -> rgb(20, 120, 40);
            case SWAMP -> rgb(70, 110, 70);
            case TAIGA -> rgb(50, 110, 90);
            case SNOWY_PLAINS -> rgb(235, 240, 245);
            case GROVE -> rgb(170, 200, 190);
            case MEADOW -> rgb(160, 210, 130);
            case SNOWY_SLOPES -> rgb(250, 250, 255);
            case STONY_PEAKS -> rgb(140, 140, 150);
        };
    }

    private static int materialColor(SurfaceMaterial material) {
        return switch (material) {
            case GRASS_BLOCK, MOSS_BLOCK -> rgb(90, 170, 70);
            case DIRT -> rgb(120, 90, 60);
            case COARSE_DIRT -> rgb(105, 78, 50);
            case PODZOL -> rgb(95, 70, 40);
            case MUD -> rgb(75, 60, 55);
            case CLAY -> rgb(160, 165, 175);
            case SAND, SANDSTONE, RED_SAND, RED_SANDSTONE -> rgb(230, 215, 140);
            case GRAVEL -> rgb(140, 135, 130);
            case STONE -> rgb(120, 120, 120);
            case DEEPSLATE -> rgb(75, 75, 80);
            case BEDROCK -> rgb(40, 40, 40);
            case SNOW_BLOCK -> rgb(250, 250, 255);
            case ICE -> rgb(180, 220, 255);
            case WATER -> rgb(40, 90, 200);
            case AIR -> rgb(200, 230, 255);
            case BASALT -> rgb(60, 60, 70);
            case GRANITE -> rgb(160, 120, 110);
            case DIORITE -> rgb(200, 200, 200);
            case TERRACOTTA -> rgb(180, 110, 80);
        };
    }

    private static int coastColor(TerrainModel.Sample sm, TerrainSettings s) {
        double d = sm.continent() - s.coastLine();
        if (Math.abs(d) < 0.045) return rgb(255, 240, 120);   // coastline band
        if (d < 0) return rgb(30, 60, 140);                    // ocean side
        if (sm.height() < s.seaLevel() + 5) return rgb(120, 180, 120);
        return rgb(90, 90, 90);
    }

    /** Hydrology mask, blue-scale: lakes, rivers, then ordinary open water. */
    private static int hydroColor(TerrainModel.Sample sm) {
        if (sm.lake() > 0.1) return rgb(40, 90, 220);
        if (sm.river() > 0.5) return rgb(60, 140, 255);
        if (sm.river() > 0.2) return rgb(140, 190, 255);
        if (sm.height() < sm.waterLevel()) return rgb(20, 40, 90);
        return rgb(235, 235, 225);
    }

    private static int divColor(double v) {
        double t = Math.max(0, Math.min(1, v * 0.5 + 0.5));
        return ramp(t);
    }

    /** Blue -> sand -> green -> brown -> grey -> white. */
    private static int ramp(double t) {
        int[][] stops = {{20, 40, 120}, {40, 90, 190}, {230, 220, 150}, {70, 140, 60},
                {120, 100, 70}, {150, 150, 150}, {255, 255, 255}};
        double pos = Math.max(0, Math.min(1, t)) * (stops.length - 1);
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
