package com.cokedoutsnail.realisticterrain.terrain.diffusion;

import com.google.gson.*;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SyntheticMapFactory {
    private static final int N = 5;
    private static final float[] FM = WorldPipelineModelConfig.frequencyMultipliers();
    private static final float BF = 0.05f;
    private static final int[] OCT = {4, 2, 4, 4, 4};
    private static final float LAC = 2.0f, GAIN = 0.5f;

    private final float[][] nq, dq;
    private final float aTS, bTS, tSp1, tSp99;
    private final FastNoiseLite[] noises = new FastNoiseLite[N];

    private static float[][] cdq;
    private static float caTS, cbTS, ctSp1, ctSp99;
    private static boolean loaded = false;

    public SyntheticMapFactory(long seed) {
        loadData();
        dq = cdq; aTS = caTS; bTS = cbTS; tSp1 = ctSp1; tSp99 = ctSp99;
        nq = new float[N][];
        for (int ch = 0; ch < N; ch++) {
            FastNoiseLite fnl = new FastNoiseLite((int)((seed + ch + 1) & 0x7FFFFFFFL));
            fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
            fnl.SetFrequency(BF * FM[ch]);
            fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
            fnl.SetFractalOctaves(OCT[ch]); fnl.SetFractalLacunarity(LAC); fnl.SetFractalGain(GAIN);
            noises[ch] = fnl;
            nq[ch] = buildQuantiles(fnl, 64);
        }
    }

    // Gson ships no null annotations, so fromJson's inferred @NonNull return is reported by Eclipse
    // null analysis as an unsafe interpretation. A parsed JsonObject is non-null by contract.
    @SuppressWarnings("null")
    private static synchronized void loadData() {
        if (loaded) return;
        try {
            ModelAssetManager.ensureAssetsReady();
            Path p = ModelAssetManager.resolveAssetPath("pipeline_data.json");
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                JsonObject data = new Gson().fromJson(r, JsonObject.class);
                JsonArray arr = data.getAsJsonArray("data_quantile_tables");
                int nQ = data.get("n_quantiles").getAsInt();
                cdq = new float[N][nQ];
                for (int ch = 0; ch < N; ch++) { JsonArray dq = arr.get(ch).getAsJsonArray(); for (int i = 0; i < nQ; i++) cdq[ch][i] = dq.get(i).getAsFloat(); }
                caTS = data.get("a_temp_std").getAsFloat(); cbTS = data.get("b_temp_std").getAsFloat();
                ctSp1 = data.get("temp_std_p1").getAsFloat(); ctSp99 = data.get("temp_std_p99").getAsFloat();
                loaded = true;
            }
        } catch (Exception e) { throw new IllegalStateException("Failed to load pipeline_data.json", e); }
    }

    static float[] buildQuantiles(FastNoiseLite fnl, int sz) {
        int n = sz * sz; float[] v = new float[n]; int i = 0;
        for (int r = 0; r < sz; r++) for (int c = 0; c < sz; c++) v[i++] = fnl.GetNoise(c, r);
        java.util.Arrays.sort(v); return v;
    }

    public float[][][] sample(int x1, int y1, int x2, int y2) {
        int H = y2 - y1, W = x2 - x1;
        float[][][] out = new float[N][H][W];
        for (int ch = 0; ch < N; ch++) {
            FastNoiseLite fnl = noises[ch]; float[] nx = nq[ch], dx = dq[ch];
            for (int r = 0; r < H; r++) for (int c = 0; c < W; c++)
                out[ch][r][c] = interp(fnl.GetNoise(x1 + c, y1 + r), nx, dx);
        }
        for (int r = 0; r < H; r++) for (int c = 0; c < W; c++) {
            float e = out[0][r][c], t = out[1][r][c], ts = out[2][r][c], p = out[3][r][c], ps = out[4][r][c];
            float lapse = Math.max(-9.8f, Math.min(-4.0f, -6.5f + 0.0015f * p)) / 1000.0f;
            t = Math.max(-10.0f, Math.min(40.0f, t + lapse * Math.max(0, e)));
            float bl = aTS * t + bTS, t01 = (ts - tSp1) / (tSp99 - tSp1);
            ts = t01 * (tSp99 - Math.max(tSp1, -bl)) + Math.max(tSp1, -bl) + bl;
            ts = Math.max(ts, 20.0f);
            ps *= Math.max(0, (185 - 0.04111f * p) / 185);
            out[0][r][c] = (float)(Math.signum(e) * Math.sqrt(Math.abs(e)));
            out[1][r][c] = t; out[2][r][c] = ts; out[3][r][c] = p; out[4][r][c] = ps;
        }
        return out;
    }

    static float interp(float x, float[] xp, float[] fp) {
        int n = xp.length;
        if (x <= xp[0]) return fp[0];
        if (x >= xp[n-1]) return fp[n-1];
        int lo = 0, hi = n-1;
        while (hi - lo > 1) { int m = (lo+hi)>>>1; if (xp[m] <= x) lo = m; else hi = m; }
        return fp[lo] + (x - xp[lo]) / (xp[hi] - xp[lo]) * (fp[hi] - fp[lo]);
    }
}