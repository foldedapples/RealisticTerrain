package com.cokedoutsnail.realisticterrain.terrain.diffusion.tensor;

import java.util.List;

/**
 * Non-batched compute function for InfiniteTensor windows.
 *
 * <p>Adapted from com.github.xandergos.terraindiffusionmc.infinitetensor.TensorFunction
 * Original copyright: MIT License, Copyright (c) 2024 xandergos
 */
@FunctionalInterface
public interface TensorFunction {
    FloatTensor apply(int[] windowIndex, List<FloatTensor> deps);
}