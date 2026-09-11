package com.cokedoutsnail.realisticterrain.client.gui;

import com.cokedoutsnail.realisticterrain.worldgen.RealisticChunkGenerator;
import com.cokedoutsnail.realisticterrain.worldgen.ScaledBiomeSource;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainBiomeSource;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.world.GeneratorOptionsHolder;
import net.minecraft.text.Text;
import net.minecraft.world.dimension.DimensionOptions;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The "Customize" screen of the Realistic world preset: world-type profile buttons, one slider per
 * geological setting, and a live heightmap preview that re-samples the terrain model as values move.
 *
 * <p>Slider movement only mutates the local {@link TerrainSettings} copy; nothing reaches the world
 * until <em>Apply</em> rebuilds the chunk generator - and the biome source, which carries its own copy
 * of the same settings - through {@code CreateWorldScreen}'s modifier. Because {@link TerrainSettings}
 * is serialized by {@code RealisticChunkGenerator.CODEC}, those customized values are exactly what the
 * created world stores and what a dedicated server reads back: there is no client-side side channel.
 *
 * <p>The preview's own view state (zoom, overlays, seed) deliberately lives on this screen rather than
 * in the settings: it changes how the map is drawn, not how the world generates, so it must never end
 * up in a world's generation data.
 */
@Environment(EnvType.CLIENT)
public final class RealisticTerrainScreen extends Screen {
    private final CreateWorldScreen parent;
    private final HeightmapPreview preview = new HeightmapPreview();
    private TerrainSettings s;
    private long previewSeed = 0x5245414c49535449L;
    private int spanIndex;
    private HeightmapPreview.Options options = HeightmapPreview.Options.DEFAULT;

    public RealisticTerrainScreen(CreateWorldScreen parent, GeneratorOptionsHolder holder) {
        super(Text.translatable("realisticterrain.customize.title"));
        this.parent = parent;
        // GeneratorOptionsHolder has no direct "selected dimensions" accessor - only the
        // registry every DimensionOptions (overworld/nether/end) lives in - so the overworld's
        // chunk generator has to be looked up by its well-known registry key.
        this.s = holder.dimensionOptionsRegistry().getOptionalValue(DimensionOptions.OVERWORLD)
                .map(DimensionOptions::chunkGenerator)
                .filter(generator -> generator instanceof RealisticChunkGenerator)
                .map(generator -> ((RealisticChunkGenerator) generator).settings())
                .orElse(TerrainSettings.DEFAULT);
    }

    /**
     * Every slider routes through here. The index to setting mapping lives in
     * {@link TerrainSettings#withValue(int, double)}, so adding a setting never means writing
     * another seventeen-argument constructor call in the GUI.
     */
    private TerrainSettings set(int idx, double v) { return s.withValue(idx, v); }
    @Override
    protected void init() {
        // World-type profile buttons: one click loads a full named style.
        int by = 24;
        int bw = Math.max(64, (width - 40 - (TerrainSettings.PROFILES.size() - 1) * 4) / TerrainSettings.PROFILES.size());
        int bx = 20;
        for (TerrainSettings.Profile p : TerrainSettings.PROFILES) {
            addDrawableChild(ButtonWidget.builder(Text.translatable(p.nameKey()), b -> {
                s = p.settings();
                clearAndInit();
            }).dimensions(bx, by, bw, 20).build());
            bx += bw + 4;
        }
        // Sixteen sliders do not fit in one column at a normal GUI scale, so they are laid out in two
        // columns of eight. Every row is data-driven: the index is the only thing tying a slider to a
        // setting. The row pitch shrinks on short windows so the last slider never collides with the
        // Apply button.
        int top = by + 28;
        int gap = Math.max(16, Math.min(24, (height - 56 - top) / 8));
        int colW = colWidth();
        slider(0, 20, top, colW, gap, "Mountain height", .25, 3);
        slider(1, 20, top, colW, gap, "Mountain frequency", .25, 3);
        slider(2, 20, top, colW, gap, "Ridge sharpness", .25, 3);
        slider(3, 20, top, colW, gap, "Erosion", 0, 2.5);
        slider(4, 20, top, colW, gap, "River width", .25, 4);
        slider(5, 20, top, colW, gap, "River frequency", .25, 3);
        slider(6, 20, top, colW, gap, "River depth", .25, 3);
        slider(7, 20, top, colW, gap, "Snow line", 96, 1800);
        slider(8, 20, top, colW, gap, "Biome scale", .25, 4);
        slider(9, 20, top, colW, gap, "Sea level", -32, 512);
        slider(10, 20, top, colW, gap, "Roughness", .2, 3);
        slider(11, 20, top, colW, gap, "Vegetation", 0, 3);
        slider(12, 20, top, colW, gap, "Continental scale", .5, 2.5);
        slider(13, 20, top, colW, gap, "Canyon depth", 0, 2.5);
        // The two ReTerraForged-style "control points" that let a player fix a world that came out as
        // endless ocean: Coast line moves the shoreline, Ocean depth deepens the abyssal plain.
        slider(14, 20, top, colW, gap, "Coast line", -.35, .15);
        slider(15, 20, top, colW, gap, "Ocean depth", 0, 300);

        initPreviewControls();
        initFooterButtons();
    }
    /** Panel geometry, shared by init() and render() so the buttons and the map always agree. */
    private int colWidth() { return Math.min(210, Math.max(140, (width - 48) / 2)); }

    private int previewX() { return 20 + 2 * (colWidth() + 8) + 16; }

    private int previewY() { return 62; }

    private int previewW() { return width - previewX() - 20; }

    private int previewH() { return Math.max(80, height - previewY() - 36); }

    /**
     * The preview's three view toggles and its zoom cycle. Each one rewrites only its own label
     * instead of rebuilding the screen, so toggling cannot lose a slider's focus or restart the other
     * widgets while one is being dragged.
     */
    private void initPreviewControls() {
        int pw = previewW();
        // Too narrow to draw the map at all, so its controls would be decoration with nothing to
        // decorate. The slider columns own the window in that case.
        if (pw < 140) return;
        int bw = (pw - 3 * 4) / 4;
        int y = 38;
        int x = previewX();
        addDrawableChild(ButtonWidget.builder(colourLabel(), b -> {
            options = new HeightmapPreview.Options(!options.grayscale(), options.rivers(), options.contours());
            b.setMessage(colourLabel());
        }).dimensions(x, y, bw, 20).build());
        x += bw + 4;
        addDrawableChild(ButtonWidget.builder(onOffLabel("rivers", options.rivers()), b -> {
            options = new HeightmapPreview.Options(options.grayscale(), !options.rivers(), options.contours());
            b.setMessage(onOffLabel("rivers", options.rivers()));
        }).dimensions(x, y, bw, 20).build());
        x += bw + 4;
        addDrawableChild(ButtonWidget.builder(onOffLabel("contours", options.contours()), b -> {
            options = new HeightmapPreview.Options(options.grayscale(), options.rivers(), !options.contours());
            b.setMessage(onOffLabel("contours", options.contours()));
        }).dimensions(x, y, bw, 20).build());
        x += bw + 4;
        addDrawableChild(ButtonWidget.builder(zoomLabel(), b -> {
            spanIndex = (spanIndex + 1) % HeightmapPreview.SPANS.length;
            b.setMessage(zoomLabel());
        }).dimensions(x, y, bw, 20).build());
    }

    /** Places slider {@code index} in a two-column grid: indices 0-7 in the left column, 8-15 right. */
    private void slider(int index, int left, int top, int colW, int gap, String label, double min, double max) {
        int row = index % 8, col = index / 8;
        addDrawableChild(new DoubleSlider(left + col * (colW + 8), top + row * gap, colW, label, min, max,
                s.getValue(index), v -> s = set(index, v)));
    }

    private Text colourLabel() {
        return Text.translatable(options.grayscale()
                ? "realisticterrain.customize.grayscale" : "realisticterrain.customize.colour");
    }

    private Text onOffLabel(String name, boolean on) {
        return Text.translatable("realisticterrain.customize." + name,
                Text.translatable(on ? "realisticterrain.customize.on" : "realisticterrain.customize.off"));
    }

    private Text zoomLabel() {
        return Text.translatable("realisticterrain.customize.zoom", HeightmapPreview.SPANS[spanIndex]);
    }
    /** Reset, new preview seed, Apply. Only the last of the three touches the pending world. */
    private void initFooterButtons() {
        int y = height - 28;
        addDrawableChild(ButtonWidget.builder(Text.translatable("realisticterrain.customize.reset"), b -> {
            s = TerrainSettings.DEFAULT;
            clearAndInit();
        }).dimensions(20, y, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("realisticterrain.customize.seed"), b -> {
            previewSeed = ThreadLocalRandom.current().nextLong();
        }).dimensions(126, y, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("realisticterrain.customize.done"), b -> apply())
                .dimensions(width - 120, y, 100, 20).build());
    }

    /** Writes the edited settings into the pending world, replacing the generator and its biome source. */
    private void apply() {
        TerrainSettings applied = s;
        parent.getWorldCreator().applyModifier((registries, dimensions) -> {
            var biomeSource = dimensions.getChunkGenerator().getBiomeSource();
            if (biomeSource instanceof TerrainBiomeSource tbs) {
                // The biome source carries its own copy of the settings (scale + climate), so it has
                // to be updated in lockstep with the generator or biomes drift away from the terrain.
                biomeSource = tbs.withSettings(applied);
            } else if (biomeSource instanceof ScaledBiomeSource scaled) {
                biomeSource = scaled.withScale(applied.biomeScale());
            }
            return dimensions.with(registries, new RealisticChunkGenerator(biomeSource, applied));
        });
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
        if (previewW() < 140) return;
        // Requesting on every frame is deliberate and cheap: HeightmapPreview ignores a request whose
        // stamp matches the one it is already serving, so this stays a no-op until a slider actually
        // moves. It is also what lets the map follow the settings without every widget having to
        // remember to poke it - profile buttons and Reset included.
        preview.request(previewSeed, s, HeightmapPreview.SPANS[spanIndex], options);
        preview.render(ctx, textRenderer, previewX(), previewY(), previewW(), previewH());
    }

    /** Stops the sampling thread. The shared GL texture stays registered for the next open. */
    @Override
    public void removed() {
        preview.close();
        super.removed();
    }

    @Override
    public void close() { client.setScreen(parent); }
}
