package com.cokedoutsnail.realisticterrain.terrain.diffusion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Holds the three ONNX models used by WorldPipeline.
 * Loaded once and shared across pipeline instances.
 *
 * <p>Adapted from com.github.xandergos.terraindiffusionmc.pipeline.PipelineModels
 * Original copyright: MIT License, Copyright (c) 2024 xandergos
 */
public final class PipelineModels implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineModels.class);

    private static volatile PipelineModels INSTANCE;
    private static volatile CountDownLatch loadDone;
    private static volatile boolean loadStarted;
    private static volatile Throwable loadFailure;

    private final OnnxModel coarseModel;
    private final OnnxModel baseModel;
    private final OnnxModel decoderModel;

    public static synchronized void load() {
        if (INSTANCE != null) return;
        if (loadStarted) return;
        loadStarted = true;
        loadFailure = null;
        loadDone = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                LOG.info("Loading terrain-diffusion ML models (background)...");
                long start = System.currentTimeMillis();
                INSTANCE = new PipelineModels();
                long elapsed = System.currentTimeMillis() - start;
                LOG.info("Terrain-diffusion ML models loaded in {} ms", elapsed);
            } catch (Throwable e) {
                loadFailure = e;
                loadStarted = false;
                LOG.error("Failed to load terrain-diffusion models", e);
            } finally {
                loadDone.countDown();
            }
        }, "terrain-diffusion-models");
        t.setDaemon(true);
        t.start();
    }

    public static void awaitLoad() {
        synchronized (PipelineModels.class) {
            if (INSTANCE != null) return;
            if (!loadStarted) load();
        }
        CountDownLatch latch = loadDone;
        if (latch != null) {
            try {
                if (!latch.await(10, TimeUnit.MINUTES))
                    throw new RuntimeException("Timed out waiting for models");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for models", e);
            }
        }
        if (INSTANCE == null) {
            Throwable cause = loadFailure;
            if (cause != null) throw new IllegalStateException("Failed to load models", cause);
            throw new IllegalStateException("Models did not load");
        }
    }

    public static PipelineModels getInstance() { return INSTANCE; }

    private PipelineModels() {
        ModelAssetManager.ensureAssetsReady();
        this.coarseModel = new OnnxModel(ModelAssetManager.resolveAssetPath("coarse_model.onnx"), "coarse");
        this.baseModel = new OnnxModel(ModelAssetManager.resolveAssetPath("base_model.onnx"), "base");
        this.decoderModel = new OnnxModel(ModelAssetManager.resolveAssetPath("decoder_model.onnx"), "decoder");
    }

    public OnnxModel getCoarseModel() { return coarseModel; }
    public OnnxModel getBaseModel() { return baseModel; }
    public OnnxModel getDecoderModel() { return decoderModel; }

    @Override
    public synchronized void close() {
        if (INSTANCE != this) return;
        LOG.info("Closing terrain-diffusion ML models");
        try {
            coarseModel.close();
            baseModel.close();
            decoderModel.close();
        } finally {
            INSTANCE = null;
            loadStarted = false;
            loadFailure = null;
        }
    }
}