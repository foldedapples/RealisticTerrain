package com.cokedoutsnail.realisticterrain.client.gui;

import com.cokedoutsnail.realisticterrain.worldgen.TerrainModel;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Geological top-down preview: an elevation-tinted relief map with contour lines, rivers and
 * lakes overlaid in blue, plus a readout of the peak/average land height so the player can see
 * exactly how high the mountains will be for the current settings.
 */
final class TerrainPreview {
    private static final int CELLS = 64;
    private static final int[] COLORS = new int[CELLS * CELLS];
    private static TerrainSettings cachedSettings;
    private static long cachedSeed = Long.MIN_VALUE;
    private static double cachedPeak = 0, cachedAvgLand = 0, cachedMaxH = 1000;

    // Elevation ramp: deep sea -> shelf -> shallow -> beach -> plain -> forest -> foothill -> rock -> snow -> peak.
    private static final int[] LAND_RAMP = {
            0xFFE9D8A6, // coastal sand
            0xFF7F9A4A, // grass
            0xFF3E7044, // forest
            0xFF6B7A3F, // foothill
            0xFF8A8578, // rock
            0xFF9A9A92, // subalpine
            0xFFDDE6EC, // snow
            0xFFF4F7F9, // peak
    };
    private static final int[] WATER_RAMP = {
            0xFF49AFC0, // shallow
            0xFF1D4E89, // shelf
            0xFF0B1E4D, // deep
    };

    static void render(DrawContext ctx, TextRenderer textRenderer, int x, int y, int w, int h, long seed, TerrainSettings settings) {
        if (cachedSeed != seed || !settings.equals(cachedSettings)) {
            rebuild(seed, settings);
        }
        for (int py = 0; py < CELLS; py++) for (int px = 0; px < CELLS; px++) {
            int x0 = x + px * w / CELLS, y0 = y + py * h / CELLS, x1 = x + (px + 1) * w / CELLS + 1, y1 = y + (py + 1) * h / CELLS + 1;
            ctx.fill(x0, y0, x1, y1, COLORS[py * CELLS + px]);
        }
        ctx.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        ctx.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        ctx.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        ctx.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);
        // Geological readout: peak height, average land height, sea level.
        String readout = String.format("Peak %d | Avg land %d | Sea %d", (int) cachedPeak, (int) cachedAvgLand, settings.seaLevel());
        ctx.fill(x + 4, y + h - 18, x + 196, y + h - 2, 0xC0000000);
        ctx.drawTextWithShadow(textRenderer, readout, x + 8, y + h - 15, 0xFFFFFF);
    }

    private static void rebuild(long seed, TerrainSettings settings) {
        double sampleScale = 3600.0 / CELLS;
        TerrainModel.Sample[] samples = new TerrainModel.Sample[CELLS * CELLS];
        double peak = -1e18, avgLand = 0, maxH = -1e18;
        int landCount = 0;
        for (int py = 0; py < CELLS; py++) for (int px = 0; px < CELLS; px++) {
            double x = (px - CELLS / 2.0) * sampleScale, z = (py - CELLS / 2.0) * sampleScale;
            TerrainModel.Sample s = TerrainModel.sample(seed, x, z, settings);
            samples[py * CELLS + px] = s;
            double h = s.height();
            if (h >= settings.seaLevel()) {
                if (h > peak) peak = h;
                avgLand += h;
                landCount++;
            }
            if (h > maxH) maxH = h;
        }
        if (landCount > 0) avgLand /= landCount; else avgLand = settings.seaLevel();
        cachedPeak = peak < -1e17 ? settings.seaLevel() + 10 : peak;
        cachedAvgLand = avgLand;
        cachedMaxH = Math.max(1.0, maxH);
        for (int py = 0; py < CELLS; py++) for (int px = 0; px < CELLS; px++) {
            TerrainModel.Sample s = samples[py * CELLS + px];
            double h = s.height();
            int c;
            if (h < s.waterLevel()) {
                c = ramp(WATER_RAMP, clamp((s.waterLevel() - h) / 40.0));
            } else {
                c = ramp(LAND_RAMP, clamp((h - settings.seaLevel()) / (cachedMaxH - settings.seaLevel())));
                if (s.river() > 0.2 || s.lake() > 0.3) c = blend(c, 0xFF4A8FD0, 0.5f);
                int band = (int) (h / 100.0);
                boolean contour = false;
                if (px > 0) contour |= band != (int) (samples[py * CELLS + px - 1].height() / 100.0);
                if (py > 0) contour |= band != (int) (samples[(py - 1) * CELLS + px].height() / 100.0);
                if (contour) c = darken(c, 0.72f);
            }
            COLORS[py * CELLS + px] = c;
        }
        cachedSeed = seed;
        cachedSettings = settings;
    }

    private static int ramp(int[] stops, double t) {
        t = clamp(t);
        double pos = t * (stops.length - 1);
        int i = (int) pos;
        if (i >= stops.length - 1) return stops[stops.length - 1];
        double f = pos - i;
        int a = stops[i], b = stops[i + 1];
        int r = lerp((a >> 16) & 255, (b >> 16) & 255, f);
        int g = lerp((a >> 8) & 255, (b >> 8) & 255, f);
        int bl = lerp(a & 255, b & 255, f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int lerp(int a, int b, double f) {
        return (int) (a + (b - a) * f);
    }

    private static int blend(int base, int overlay, float amount) {
        int r = lerp((base >> 16) & 255, (overlay >> 16) & 255, amount);
        int g = lerp((base >> 8) & 255, (overlay >> 8) & 255, amount);
        int b = lerp(base & 255, overlay & 255, amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int darken(int c, float factor) {
        int r = (int) (((c >> 16) & 255) * factor);
        int g = (int) (((c >> 8) & 255) * factor);
        int b = (int) ((c & 255) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
