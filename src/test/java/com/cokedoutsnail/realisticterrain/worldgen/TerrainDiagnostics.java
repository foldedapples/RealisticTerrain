package com.cokedoutsnail.realisticterrain.worldgen;

import com.cokedoutsnail.realisticterrain.worldgen.hydro.Drainage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Developer utility: renders deterministic PNG maps of the terrain engine so a change can be compared
 * against the previous one by eye. Grid patterns, ring artefacts, disconnected rivers, needle peaks and
 * region seams are all obvious in a map and invisible in a numeric assertion.
 *
 * <p>It is skipped unless explicitly asked for, because it writes files and is slow:
 *
 * <pre>./gradlew test --tests '*TerrainDiagnostics*' -Drealisticterrain.diagnostics=true</pre>
 *
 * <p>Output goes to {@code diagnostics/}, which is git-ignored: generated images are development
 * artefacts, not sources.
 */
final class TerrainDiagnostics {
    private static final int SIZE = 512;          // pixels per side
    private static final int SPAN = 8192;         // blocks covered by the map
    private static final long[] SEEDS = {8675309L, 42L, 0x5245414C49535449L};
    private static final String[] PROFILE_NAMES = {
            "continental", "alpine", "archipelago", "rolling", "canyons", "riverlands", "earthlike"};

    @Test
    void exportMaps() throws IOException {
        assumeTrue(Boolean.getBoolean("realisticterrain.diagnostics"),
                "diagnostics are opt-in: pass -Drealisticterrain.diagnostics=true");

        Path dir = Path.of("diagnostics");
        Files.createDirectories(dir);
        TerrainSettings s = TerrainSettings.DEFAULT;
        for (long seed : SEEDS) {
            render(seed, s, "seed" + Long.toHexString(seed));
        }
        for (int i = 0; i < TerrainSettings.PROFILES.size(); i++) {
            render(SEEDS[0], TerrainSettings.PROFILES.get(i).settings(), PROFILE_NAMES[i]);
        }
        System.out.println("[diagnostics] wrote maps to " + dir.toAbsolutePath());
    }

    private static void render(long seed, TerrainSettings s, String name) throws IOException {
        BufferedImage elevation = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        BufferedImage rivers = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        BufferedImage slope = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        double step = SPAN / (double) SIZE;
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
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                double x = (px - SIZE / 2.0) * step;
                double z = (py - SIZE / 2.0) * step;
                TerrainModel.Sample sm = TerrainModel.sample(seed, x, z, s);
                double t = (sm.height() - minH) / Math.max(1.0, maxH - minH);
                elevation.setRGB(px, py, ramp(t));
                rivers.setRGB(px, py, channel(sm));
                slope.setRGB(px, py, grey(sm.slopeHint()));
            }
        }
        write(elevation, dir(name + "_elevation.png"));
        write(rivers, dir(name + "_rivers.png"));
        write(slope, dir(name + "_slope.png"));
        System.out.printf("[diagnostics] %s: height %.0f..%.0f%n", name, minH, maxH);
    }

    private static Path dir(String file) {
        return Path.of("diagnostics", file);
    }

    private static void write(BufferedImage image, Path path) throws IOException {
        ImageIO.write(image, "PNG", path.toFile());
    }

    /** Blue ocean -> sand -> green -> brown -> white, the same story the in-game preview tells. */
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

    /** Rivers and lakes in blue on a pale background, so a break in the network is unmistakable. */
    private static int channel(TerrainModel.Sample sm) {
        if (sm.lake() > 0.1) return rgb(new int[]{40, 90, 220});
        if (sm.river() > 0.5) return rgb(new int[]{60, 140, 255});
        if (sm.river() > 0.2) return rgb(new int[]{140, 190, 255});
        if (sm.height() < sm.waterLevel()) return rgb(new int[]{20, 40, 90});
        return rgb(new int[]{235, 235, 225});
    }

    private static int grey(double v) {
        int g = (int) (Math.max(0, Math.min(1, v)) * 255);
        return rgb(new int[]{g, g, g});
    }

    private static int rgb(int[] c) {
        return (c[0] << 16) | (c[1] << 8) | c[2];
    }
}
