package com.cokedoutsnail.realisticterrain.terrain.diffusion.tensor;

/**
 * Defines the sliding window layout for an InfiniteTensor.
 *
 * <p>Adapted from com.github.xandergos.terraindiffusionmc.infinitetensor.TensorWindow
 * Original copyright: MIT License, Copyright (c) 2024 xandergos
 */
public class TensorWindow {
    public final int[] size;
    public final int[] stride;
    public final int[] offset;

    public TensorWindow(int[] size, int[] stride, int[] offset) {
        this.size = size.clone();
        this.stride = stride.clone();
        this.offset = offset.clone();
    }

    public TensorWindow(int[] size) {
        this.size = size.clone();
        this.stride = size.clone();
        this.offset = new int[size.length];
    }

    public TensorWindow(int[] size, int[] stride) {
        this.size = size.clone();
        this.stride = stride.clone();
        this.offset = new int[size.length];
    }

    public int ndim() { return size.length; }

    public int[][] getBounds(int[] windowIndex) {
        int n = size.length;
        int[][] bounds = new int[n][2];
        for (int i = 0; i < n; i++) {
            bounds[i][0] = windowIndex[i] * stride[i] + offset[i];
            bounds[i][1] = windowIndex[i] * stride[i] + offset[i] + size[i];
        }
        return bounds;
    }

    public int[] getLowestIntersection(int[][] pixelRange) {
        int n = size.length;
        int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            int p = pixelRange[i][0];
            int numerator = p - offset[i] - size[i] + 1;
            if (numerator >= 0) {
                result[i] = (numerator + stride[i] - 1) / stride[i];
            } else {
                result[i] = -((-numerator) / stride[i]);
            }
        }
        return result;
    }

    public int[] getHighestIntersection(int[][] pixelRange) {
        int n = size.length;
        int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            int p = pixelRange[i][1] - 1;
            result[i] = Math.floorDiv(p - offset[i], stride[i]);
        }
        return result;
    }
}