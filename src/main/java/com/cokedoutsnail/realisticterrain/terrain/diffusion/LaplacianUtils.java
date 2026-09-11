package com.cokedoutsnail.realisticterrain.terrain.diffusion;

/**
 * Port of terrain_diffusion/data/laplacian_encoder.py.
 * Implements laplacian_decode and laplacian_denoise used by WorldPipeline._compute_elev.
 *
 * <p>Adapted from com.github.xandergos.terraindiffusionmc.pipeline.LaplacianUtils
 * Original copyright: MIT License, Copyright (c) 2024 xandergos
 */
public final class LaplacianUtils {

    public static float[][] laplacianDecode(float[][] residual, float[][] lowres) {
        int H = residual.length, W = residual[0].length;
        float[][] lowresUp = bilinearResize(lowres, H, W);
        float[][] result = new float[H][W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++)
                result[r][c] = residual[r][c] + lowresUp[r][c];
        return result;
    }

    public static float[][] laplacianDenoise(float[][] residual, float[][] lowres, float sigma) {
        int H = residual.length, W = residual[0].length;
        float[][] lowresUpEx = bilinearResizeExtrapolated(lowres, H, W);
        float[][] decoded = new float[H][W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++)
                decoded[r][c] = residual[r][c] + lowresUpEx[r][c];

        int lH = lowres.length, lW = lowres[0].length;
        float[][] downsampled = bilinearResize(decoded, lH, lW);
        float[][] kernelSigma = gaussianKernel1D(sigma);
        return separableGaussianBlur(downsampled, kernelSigma);
    }

    public static float[][] bilinearResize(float[][] src, int dstH, int dstW) {
        int srcH = src.length, srcW = src[0].length;
        float[][] dst = new float[dstH][dstW];
        for (int r = 0; r < dstH; r++) {
            float srcR = (r + 0.5f) * srcH / dstH - 0.5f;
            int r0 = (int) Math.floor(srcR), r1 = r0 + 1;
            float wr = srcR - r0;
            r0 = Math.max(0, Math.min(srcH - 1, r0));
            r1 = Math.max(0, Math.min(srcH - 1, r1));
            for (int c = 0; c < dstW; c++) {
                float srcC = (c + 0.5f) * srcW / dstW - 0.5f;
                int c0 = (int) Math.floor(srcC), c1 = c0 + 1;
                float wc = srcC - c0;
                c0 = Math.max(0, Math.min(srcW - 1, c0));
                c1 = Math.max(0, Math.min(srcW - 1, c1));
                dst[r][c] = (1-wr)*(1-wc)*src[r0][c0] + (1-wr)*wc*src[r0][c1]
                          + wr*(1-wc)*src[r1][c0] + wr*wc*src[r1][c1];
            }
        }
        return dst;
    }

    static float[][] bilinearResizeExtrapolated(float[][] src, int dstH, int dstW) {
        int sH = src.length, sW = src[0].length;
        float[][] padded = new float[sH + 2][sW + 2];
        for (int r = 0; r < sH; r++) System.arraycopy(src[r], 0, padded[r + 1], 1, sW);
        for (int r = 0; r < sH; r++) {
            padded[r + 1][0] = 2 * src[r][0] - src[r][1];
            padded[r + 1][sW + 1] = 2 * src[r][sW - 1] - src[r][sW - 2];
        }
        System.arraycopy(padded[1], 0, padded[0], 0, sW + 2);
        System.arraycopy(padded[sH], 0, padded[sH + 1], 0, sW + 2);
        return bilinearResize(padded, dstH, dstW);
    }

    public static float[][] gaussianKernel1D(float sigma) {
        int ks = ((int) (sigma * 2) / 2) * 2 + 1;
        float[] k = new float[ks];
        float sum = 0;
        int half = ks / 2;
        for (int i = 0; i < ks; i++) {
            float x = i - half;
            k[i] = (float) Math.exp(-0.5 * x * x / (sigma * sigma));
            sum += k[i];
        }
        for (int i = 0; i < ks; i++) k[i] /= sum;
        return new float[][]{k};
    }

    public static float[][] separableGaussianBlur(float[][] src, float[][] kernel1D) {
        float[] k = kernel1D[0];
        int ks = k.length, pad = ks / 2;
        int H = src.length, W = src[0].length;

        float[][] tmp = new float[H][W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                float sum = 0;
                for (int ki = 0; ki < ks; ki++)
                    sum += src[r][Math.max(0, Math.min(W - 1, c + ki - pad))] * k[ki];
                tmp[r][c] = sum;
            }

        float[][] result = new float[H][W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                float sum = 0;
                for (int ki = 0; ki < ks; ki++)
                    sum += tmp[Math.max(0, Math.min(H - 1, r + ki - pad))][c] * k[ki];
                result[r][c] = sum;
            }
        return result;
    }
}