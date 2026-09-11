package com.cokedoutsnail.realisticterrain.terrain.diffusion;

import com.cokedoutsnail.realisticterrain.terrain.diffusion.tensor.InfiniteTensor;
import com.cokedoutsnail.realisticterrain.terrain.diffusion.tensor.MemoryTileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java port of terrain-diffusion-mc WorldPipeline (MIT license).
 *
 * <p>Three stages: coarse (20-step DPM-Solver++), latent (2 flow-matching steps),
 * decoder (1 flow-matching step). All pixel coordinates are native-resolution space.
 *
 * <p>Adapted from com.github.xandergos.terraindiffusionmc.pipeline.WorldPipeline
 * Original copyright: MIT License, Copyright (c) 2024 xandergos
 */
public final class WorldPipeline implements AutoCloseable {

    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory.getLogger(WorldPipeline.class);

    static final int LATENT_COMPRESSION = WorldPipelineModelConfig.latentCompression();
    static final float SIGMA_DATA = EDMScheduler.SIGMA_DATA;

    static final int COARSE_TILE_SIZE   = 64;
    static final int COARSE_TILE_STRIDE = 48;
    static final int LATENT_TILE_SIZE   = 64;
    static final int LATENT_TILE_STRIDE = 32;
    static final int DECODER_TILE_SIZE  = 256;
    static final int DECODER_TILE_STRIDE = 192;

    static final float[] MODEL_MEANS = WorldPipelineModelConfig.coarseMeans();
    static final float[] MODEL_STDS = WorldPipelineModelConfig.coarseStds();

    static final float[] COND_MEANS = {14.99f, 11.65f, 15.87f, 619.26f, 833.12f, 69.40f, 0.66f};
    static final float[] COND_STDS  = {21.72f, 21.78f, 10.40f, 452.29f, 738.09f, 34.59f, 0.47f};

    static final float LOWFREQ_MEAN  = -31.4f;
    static final float LOWFREQ_STD   = 38.6f;
    static final float RESIDUAL_MEAN = WorldPipelineModelConfig.residualMean();
    static final float RESIDUAL_STD  = WorldPipelineModelConfig.residualStd();

    static final float[] COND_SNR  = WorldPipelineModelConfig.conditioningSnr();
    static final int COARSE_POOLING = WorldPipelineModelConfig.coarsePooling();
    static final float[] COND_VALS;
    static {
        if (COARSE_POOLING != 1) {
            throw new IllegalStateException("coarse_pooling=" + COARSE_POOLING + " is not supported in the Java pipeline yet");
        }
        COND_VALS = new float[COND_SNR.length];
        for (int i = 0; i < COND_SNR.length; i++) COND_VALS[i] = (float) Math.log(COND_SNR[i] / 8.0);
    }

    static final int[] COND_DIMS = {16, 16, 4, 16, 5, 1};
    static final float[] MP_CONCAT_SCALES;
    static final float[] HISTOGRAM_RAW;
    static {
        int sumN = 0; for (int n : COND_DIMS) sumN += n;
        int k = COND_DIMS.length;
        float C = (float) Math.sqrt((double) sumN * k);
        MP_CONCAT_SCALES = new float[k];
        for (int i = 0; i < k; i++) MP_CONCAT_SCALES[i] = C / (float) Math.sqrt(COND_DIMS[i]) / k;
        float[] configuredHistogramRaw = WorldPipelineModelConfig.histogramRaw();
        HISTOGRAM_RAW = configuredHistogramRaw != null ? configuredHistogramRaw : new float[]{0f, 0f, 0f, 0f, 0f};
    }

    private final OnnxModel coarseModel;
    private final OnnxModel baseModel;
    private final OnnxModel decoderModel;
    private final boolean ownModels;
    @SuppressWarnings("unused")
    private volatile SyntheticMapFactory syntheticMapFactory;
    @SuppressWarnings("unused")
    private volatile long seed;

    @SuppressWarnings("unused")
    private final MemoryTileStore tileStore;
    @SuppressWarnings("unused")
    private final long cacheLimitBytes = 100L * 1024 * 1024;

    @SuppressWarnings("unused")
    private final InfiniteTensor coarse;
    @SuppressWarnings("unused")
    private final InfiniteTensor latents;
    @SuppressWarnings("unused")
    private final InfiniteTensor residual;

    public WorldPipeline(long seed, PipelineModels models) {
        this.seed = seed & 0xFFFFFFFFFFFFFFFFL;
        this.coarseModel = null; // models will set these
        this.baseModel = null;
        this.decoderModel = null;
        this.ownModels = false;
        this.tileStore = new MemoryTileStore();
        // Stub: In full implementation, create the InfiniteTensor instances
        // and set up the pipeline graph
        this.coarse = null;
        this.latents = null;
        this.residual = null;
    }

    @Override
    public void close() {
        if (ownModels) {
            if (coarseModel != null) coarseModel.close();
            if (baseModel != null) baseModel.close();
            if (decoderModel != null) decoderModel.close();
        }
    }
}