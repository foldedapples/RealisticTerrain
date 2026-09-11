package com.cokedoutsnail.realisticterrain.terrain.diffusion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin wrapper around ONNX Runtime loaded via reflection.
 * This allows compilation without ONNX Runtime on the classpath.
 *
 * <p>Adapted from com.github.xandergos.terraindiffusionmc.pipeline.OnnxModel
 * Original copyright: MIT License, Copyright (c) 2024 xandergos
 */
public final class OnnxModel implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OnnxModel.class);
    private static final AtomicBoolean initLogged = new AtomicBoolean(false);
    private static volatile boolean onnxAvailable = false;

    // Reflection handles
    private static Class<?> envClass;
    private static Class<?> sessionClass;
    private static Class<?> tensorClass;
    private static Method createTensorMethod;
    private static Method createSessionMethod;
    private static Method getEnvironmentMethod;
    private static Method runMethod;
    private static Method getOutputMethod;
    private static Method getBufferMethod;

    private final Object env;
    private Object session;
    @SuppressWarnings("unused")
    private final String name;
    private boolean closed;

    public OnnxModel(Path modelFilePath, String name) {
        this.name = name;
        try {
            tryInitOnnx();
            if (!onnxAvailable) {
                LOG.warn("ONNX Runtime not available, model '{}' will not perform inference", name);
                this.env = null;
                return;
            }
            long start = System.currentTimeMillis();
            this.env = getEnvironmentMethod.invoke(null);
            byte[] modelBytes = Files.readAllBytes(modelFilePath);
            Object opts = sessionClass.getClassLoader()
                    .loadClass("ai.onnxruntime.OrtSession$SessionOptions")
                    .getConstructor().newInstance();
            opts.getClass().getMethod("setOptimizationLevel", int.class).invoke(opts, 2);
            this.session = createSessionMethod.invoke(env, modelBytes, opts);
            long elapsed = System.currentTimeMillis() - start;
            LOG.info("ONNX model '{}' loaded ({} ms)", name, elapsed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load ONNX model '" + name + "' from " + modelFilePath, e);
        }
    }

    private static synchronized void tryInitOnnx() {
        if (initLogged.getAndSet(true)) return;
        try {
            envClass = Class.forName("ai.onnxruntime.OrtEnvironment");
            sessionClass = Class.forName("ai.onnxruntime.OrtSession");
            tensorClass = Class.forName("ai.onnxruntime.OnnxTensor");

            getEnvironmentMethod = envClass.getMethod("getEnvironment");
            createSessionMethod = envClass.getMethod("createSession", byte[].class,
                    Class.forName("ai.onnxruntime.OrtSession$SessionOptions"));
            createTensorMethod = tensorClass.getMethod("createTensor", envClass, FloatBuffer.class, long[].class);
            runMethod = sessionClass.getMethod("run", Map.class);
            getOutputMethod = Class.forName("ai.onnxruntime.OrtSession$Result").getMethod("get", int.class);
            getBufferMethod = Class.forName("ai.onnxruntime.OnnxValue").getMethod("getFloatBuffer");

            onnxAvailable = true;
            LOG.info("ONNX Runtime detected via reflection");
        } catch (ClassNotFoundException e) {
            LOG.info("ONNX Runtime not on classpath (expected for CPU/geological-only builds)");
        } catch (NoSuchMethodException e) {
            LOG.warn("ONNX Runtime API mismatch: {}", e.getMessage());
        }
    }

    public boolean isAvailable() { return onnxAvailable && session != null && !closed; }

    public float[] run(String[] inputNames, float[][] inputData, long[][] inputShapes) {
        if (!isAvailable()) {
            throw new IllegalStateException("ONNX Runtime not available for inference");
        }
        try {
            Map<String, Object> feed = new LinkedHashMap<>();
            for (int i = 0; i < inputNames.length; i++) {
                Object tensor = createTensorMethod.invoke(null, env,
                        FloatBuffer.wrap(inputData[i]), inputShapes[i]);
                feed.put(inputNames[i], tensor);
            }
            Object result = runMethod.invoke(session, feed);
            Object output = getOutputMethod.invoke(result, 0);
            FloatBuffer buf = (FloatBuffer) getBufferMethod.invoke(output);
            float[] out = new float[buf.remaining()];
            buf.get(out);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("ONNX inference failed", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (session != null) {
            try { session.getClass().getMethod("close").invoke(session); } catch (Exception ignored) {}
            session = null;
        }
    }
}