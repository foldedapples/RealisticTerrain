package com.cokedoutsnail.realisticterrain.client.gui;

import com.cokedoutsnail.realisticterrain.RealisticTerrainMod;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainModel;
import com.cokedoutsnail.realisticterrain.worldgen.TerrainSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live top-down heightmap preview, rendered through a real GPU texture.
 *
 * <p>The widget owns a {@link NativeImage} wrapped in a {@link NativeImageBackedTexture} - which is
 * the class that implements {@code DynamicTexture} in 1.21.11 - and samples {@link TerrainModel}
 * over a square block area: {@link #SPANS} blocks on a side into {@value #SIZE}&times;{@value #SIZE}
 * texels, so one texel is {@code span / SIZE} blocks. The image is drawn with a single
 * {@code DrawContext.drawTexture} call instead of thousands of filled quads, and the GPU scales it into
 * whatever square the panel happens to be.
 *
 * <p><b>Threading.</b> A frame costs 20-150 ms of noise evaluation (measured: 619 ns per warm
 * {@code TerrainModel.sample}, ~3.9 us when the coarse lattice cache is cold), far too long to run
 * inside {@code render} while a slider is being dragged. So sampling happens on a single daemon
 * worker thread and only the {@code NativeImage} write plus {@code upload()} run on the render
 * thread, as GL requires. That is safe because {@code TerrainModel.sample} is a pure static function
 * of (seed, x, z, settings) and {@code TerrainSettings} is an immutable record - exactly why the
 * chunk worker threads can call it concurrently too.
 *
 * <p>Requests are stamp-based rather than queued: dragging a slider overwrites the pending request
 * instead of enqueueing sixty frames nobody will look at, and a job that notices its stamp has been
 * superseded abandons the frame mid-row. The worker re-checks the stamp after publishing, which
 * closes the race where a request lands between that check and {@code inFlight} being cleared.
 *
 * <p>The GPU texture is a lazily created singleton: registering the same {@link Identifier} twice
 * would orphan the first texture, and 160 KB is not worth freeing and reallocating every time the
 * Customize screen opens. It goes with the texture manager at game shutdown. The worker thread
 * belongs to this instance and is stopped by {@link #close()}.
 */
@Environment(EnvType.CLIENT)
final class HeightmapPreview implements AutoCloseable {
    /** Texels per side of the preview image; also the panel's nominal size in GUI pixels. */
    static final int SIZE = 200;
    /**
     * Block spans the zoom button cycles through. The default is the 1024&times;1024 block area the
     * preview is specified over; the wider spans exist because continental features have a ~7000
     * block wavelength, so at 1024 blocks a change to Continental Scale reads as relief rather than
     * as coastline. The top span stops at 4096: the coarse lattice cache holds 262144 nodes and a
     * span of S blocks touches (S/16)^2 of them, so 8192 would evict the lot.
     */
    static final int[] SPANS = {1024, 2048, 4096};
    private static final Identifier TEXTURE_ID = RealisticTerrainMod.id("gui/heightmap_preview");

    /** Elevation ramp: coast -> grass -> forest -> foothill -> rock -> subalpine -> snow -> peak. */
    private static final int[] LAND_RAMP = {
            0xFFE9D8A6, 0xFF7F9A4A, 0xFF3E7044, 0xFF6B7A3F,
            0xFF8A8578, 0xFF9A9A92, 0xFFDDE6EC, 0xFFF4F7F9,
    };
    /** Water ramp: shallow -> shelf -> abyss. */
    private static final int[] WATER_RAMP = {0xFF49AFC0, 0xFF1D4E89, 0xFF0B1E4D};
    /** Height, in blocks, between contour lines. */
    private static final double CONTOUR_INTERVAL = 64.0;

    /** Toggle state of the overlays, driven by the buttons on the Customize screen. */
    record Options(boolean grayscale, boolean rivers, boolean contours) {
        static final Options DEFAULT = new Options(false, true, true);
    }

    /** A finished frame: the texels, plus the statistics for the readout strip. */
    record Frame(int[] argb, int peak, int avgLand, double landFraction, int seaLevel, int span) {}

    // --- request state: written by the render thread, read by the worker ---
    private volatile long reqSeed;
    private volatile TerrainSettings reqSettings = TerrainSettings.DEFAULT;
    private volatile int reqSpan = SPANS[0];
    private volatile Options reqOptions = Options.DEFAULT;
    private final AtomicLong reqStamp = new AtomicLong();
    private final AtomicLong doneStamp = new AtomicLong(Long.MIN_VALUE);
    private final AtomicBoolean inFlight = new AtomicBoolean();

    // --- published result ---
    private volatile Frame ready;
    private volatile boolean needsUpload = true;
    private volatile boolean closed;

    // --- worker-thread scratch; never published, so it is reused instead of reallocated ---
    private final float[] heights = new float[SIZE * SIZE];
    private final float[] waters = new float[SIZE * SIZE];
    private final float[] rivers = new float[SIZE * SIZE];
    private final float[] lakes = new float[SIZE * SIZE];

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RealisticTerrain-HeightmapPreview");
        t.setDaemon(true);
        return t;
    });

    /** Render-thread-only singleton texture; see the class comment. */
    private static NativeImageBackedTexture sharedTexture;
    private static NativeImage sharedImage;
    /** Asks for a frame. Cheap enough to call every tick; identical requests are ignored. */
    void request(long seed, TerrainSettings settings, int span, Options options) {
        if (closed) return;
        long stamp = stampOf(seed, settings, span, options);
        if (stamp == reqStamp.get()) return;
        reqSeed = seed;
        reqSettings = settings;
        reqSpan = span;
        reqOptions = options;
        reqStamp.set(stamp);
        wake();
    }

    private void wake() {
        if (closed || !inFlight.compareAndSet(false, true)) return;
        try {
            worker.submit(this::run);
        } catch (RuntimeException e) {
            inFlight.set(false); // the executor is already shut down
        }
    }

    private static long stampOf(long seed, TerrainSettings s, int span, Options o) {
        return seed * 0x9E3779B97F4A7C15L
                ^ ((long) s.hashCode() * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) span << 24)
                ^ (long) o.hashCode();
    }

    /** Worker loop: keeps producing frames until the newest request has been served. */
    private void run() {
        try {
            while (!closed) {
                long stamp = reqStamp.get();
                if (stamp == doneStamp.get()) return;
                Frame frame = compute(reqSeed, reqSettings, reqSpan, reqOptions, stamp);
                if (frame == null) continue; // superseded mid-flight; loop picks up the new stamp
                ready = frame;
                needsUpload = true;
                doneStamp.set(stamp);
            }
        } finally {
            inFlight.set(false);
            // A request can land between the stamp check above and clearing inFlight; re-arm here so
            // it is never left unserved.
            if (!closed && doneStamp.get() != reqStamp.get()) wake();
        }
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
    }
    /**
     * Samples the terrain model over the requested square and shades it. Runs on the worker thread
     * and returns null if the request was superseded while sampling, so a stale frame is never
     * published and a slider drag converges on its final value instead of on an old one.
     */
    private Frame compute(long seed, TerrainSettings s, int span, Options o, long stamp) {
        double step = span / (double) SIZE;
        double half = SIZE / 2.0;
        double sea = s.seaLevel();
        double peak = -Double.MAX_VALUE, maxH = -Double.MAX_VALUE, landSum = 0;
        int land = 0;
        for (int py = 0; py < SIZE; py++) {
            if (closed || stamp != reqStamp.get()) return null;
            double z = (py + 0.5 - half) * step;
            for (int px = 0; px < SIZE; px++) {
                TerrainModel.Sample sm = TerrainModel.sample(seed, (px + 0.5 - half) * step, z, s);
                int i = py * SIZE + px;
                float h = (float) sm.height();
                heights[i] = h;
                waters[i] = (float) sm.waterLevel();
                rivers[i] = (float) sm.river();
                lakes[i] = (float) sm.lake();
                if (h > maxH) maxH = h;
                if (h >= sea) {
                    land++;
                    landSum += h;
                    if (h > peak) peak = h;
                }
            }
        }
        // Second pass, because the land ramp is normalised against the highest column in the frame:
        // that keeps the tint stable while a slider moves instead of rescaling under the player.
        int[] argb = new int[SIZE * SIZE];
        double relief = Math.max(24.0, maxH - sea);
        for (int py = 0; py < SIZE; py++) {
            if (closed || stamp != reqStamp.get()) return null;
            for (int px = 0; px < SIZE; px++) {
                int i = py * SIZE + px;
                float h = heights[i], w = waters[i];
                int c;
                if (h < w) {
                    c = ramp(WATER_RAMP, clamp01((w - h) / 40.0));
                } else {
                    c = ramp(LAND_RAMP, clamp01((h - sea) / relief));
                    if (o.rivers() && (rivers[i] > 0.2f || lakes[i] > 0.3f)) c = blend(c, 0xFF4A8FD0, 0.5);
                    if (o.contours() && crossesContour(px, py)) c = darken(c, 0.72);
                }
                c = brighten(c, hillshade(px, py, step));
                argb[i] = o.grayscale() ? luminance(c) : c;
            }
        }
        return new Frame(argb, land == 0 ? (int) sea : (int) peak, land == 0 ? (int) sea : (int) (landSum / land),
                land / (double) (SIZE * SIZE), (int) sea, span);
    }

    /** True where a contour line falls between this texel and its left or upper neighbour. */
    private boolean crossesContour(int px, int py) {
        int i = py * SIZE + px;
        int band = (int) Math.floor(heights[i] / CONTOUR_INTERVAL);
        return (px > 0 && band != (int) Math.floor(heights[i - 1] / CONTOUR_INTERVAL))
                || (py > 0 && band != (int) Math.floor(heights[i - SIZE] / CONTOUR_INTERVAL));
    }

    /**
     * Relief shading from the local gradient, lit from the north-west. Reading the slope off the
     * already-sampled heights is what makes ridges and valley walls legible on a flat top-down map;
     * the result is clamped so a cliff can never blow a colour out to white or to black.
     */
    private double hillshade(int px, int py, double step) {
        int i = py * SIZE + px;
        double left = px > 0 ? heights[i - 1] : heights[i];
        double right = px < SIZE - 1 ? heights[i + 1] : heights[i];
        double up = py > 0 ? heights[i - SIZE] : heights[i];
        double down = py < SIZE - 1 ? heights[i + SIZE] : heights[i];
        double slopeX = (right - left) / (2 * step);
        double slopeZ = (down - up) / (2 * step);
        return clamp(-slopeX - slopeZ, -1.0, 1.0) * 0.32;
    }
    /**
     * Draws the preview into the given panel. Render thread only: it may allocate the GL texture, and
     * it uploads the newest finished frame.
     */
    void render(DrawContext ctx, TextRenderer textRenderer, int x, int y, int w, int h) {
        // Keep the map square whatever the panel's aspect ratio is, so a thousand blocks north-south
        // covers the same distance on screen as a thousand blocks east-west.
        int side = Math.min(w, h);
        int dx = x + (w - side) / 2, dy = y + (h - side) / 2;
        Frame frame = ready;
        boolean textured = ensureTexture();
        if (textured) {
            if (frame != null && needsUpload) {
                upload(frame);
                needsUpload = false;
            }
            if (frame != null) {
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, dx, dy, 0f, 0f, side, side, SIZE, SIZE);
            }
        }
        if (frame == null || !textured) {
            // Nothing finished yet (or no GL context): a dark panel with a hint beats a black hole.
            ctx.fill(dx, dy, dx + side, dy + side, 0xFF14161C);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.translatable("realisticterrain.customize.generating"),
                    dx + side / 2, dy + side / 2 - 4, 0xFFB0B8C4);
        }
        ctx.fill(dx - 1, dy - 1, dx + side + 1, dy, 0xFFFFFFFF);
        ctx.fill(dx - 1, dy + side, dx + side + 1, dy + side + 1, 0xFFFFFFFF);
        ctx.fill(dx - 1, dy, dx, dy + side, 0xFFFFFFFF);
        ctx.fill(dx + side, dy, dx + side + 1, dy + side, 0xFFFFFFFF);
        if (frame != null) {
            String readout = String.format("Peak %d  |  Avg land %d  |  Land %d%%  |  Sea %d",
                    frame.peak(), frame.avgLand(), Math.round(frame.landFraction() * 100.0), frame.seaLevel());
            ctx.fill(dx + 3, dy + side - 15, dx + side - 3, dy + side - 3, 0xC0000000);
            ctx.drawTextWithShadow(textRenderer, readout, dx + 7, dy + side - 13, 0xFFFFFFFF);
        }
    }

    /** Creates and registers the shared texture on first use; false when there is no client yet. */
    private static boolean ensureTexture() {
        if (sharedTexture != null) return true;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getTextureManager() == null) return false;
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false);
        NativeImageBackedTexture texture =
                new NativeImageBackedTexture(() -> "realisticterrain/heightmap_preview", image);
        client.getTextureManager().registerTexture(TEXTURE_ID, texture);
        sharedImage = image;
        sharedTexture = texture;
        return true;
    }

    /**
     * Copies a finished frame into the {@link NativeImage} and pushes it to the GPU. The frame's pixel
     * array is never reused by the worker once published, so reading it here cannot tear.
     */
    private static void upload(Frame frame) {
        int[] argb = frame.argb();
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                sharedImage.setColorArgb(px, py, argb[py * SIZE + px]);
            }
        }
        sharedTexture.upload();
    }
    /** Interpolates a position in [0,1] across a list of ARGB colour stops. */
    private static int ramp(int[] stops, double t) {
        double pos = clamp01(t) * (stops.length - 1);
        int i = (int) pos;
        if (i >= stops.length - 1) return stops[stops.length - 1];
        double f = pos - i;
        return pack(lerp(channel(stops[i], 16), channel(stops[i + 1], 16), f),
                lerp(channel(stops[i], 8), channel(stops[i + 1], 8), f),
                lerp(channel(stops[i], 0), channel(stops[i + 1], 0), f));
    }

    private static int blend(int base, int overlay, double amount) {
        return pack(lerp(channel(base, 16), channel(overlay, 16), amount),
                lerp(channel(base, 8), channel(overlay, 8), amount),
                lerp(channel(base, 0), channel(overlay, 0), amount));
    }

    private static int darken(int c, double factor) {
        return pack((int) (channel(c, 16) * factor), (int) (channel(c, 8) * factor), (int) (channel(c, 0) * factor));
    }

    /** Multiplies the colour by {@code 1 + amount}; amount is already clamped to a safe range. */
    private static int brighten(int c, double amount) {
        double f = 1.0 + amount;
        return pack(clampByte(channel(c, 16) * f), clampByte(channel(c, 8) * f), clampByte(channel(c, 0) * f));
    }

    /** Rec.709 luma, written back to all three channels for the grayscale preview mode. */
    private static int luminance(int c) {
        int y = clampByte(0.2126 * channel(c, 16) + 0.7152 * channel(c, 8) + 0.0722 * channel(c, 0));
        return pack(y, y, y);
    }

    private static int channel(int argb, int shift) {
        return (argb >> shift) & 0xFF;
    }

    private static int pack(int r, int g, int b) {
        return 0xFF000000 | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
    }

    private static int clampByte(double v) {
        return v <= 0 ? 0 : (v >= 255 ? 255 : (int) v);
    }

    private static int lerp(int a, int b, double f) {
        return (int) (a + (b - a) * f);
    }

    private static double clamp01(double v) {
        return v <= 0 ? 0 : (v >= 1 ? 1 : v);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
