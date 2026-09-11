package com.cokedoutsnail.realisticterrain.terrain.diffusion.tensor;

import java.util.List;

/**
 * Batched compute function for InfiniteTensor windows.
 *
 * <p>Adapted from com.github.xandergos.terraindiffusionmc.infinitetensor.BatchTensorFunction
 * Original copyright: MIT License, Copyright (c) 2024 xandergos
 */
@FunctionalInterface
public interface BatchTensorFunction {
    List<FloatTensor> apply(List<int[]> windowIndices, List<List<FloatTensor>> depLists);
}