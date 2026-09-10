package com.cokedoutsnail.realisticterrain.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TerrainModelTest {
    @Test
    void samplingIsDeterministicAndFinite() {
        TerrainModel.Sample first = TerrainModel.sample(42L, 1234.5, -987.25, TerrainSettings.DEFAULT);
        TerrainModel.Sample second = TerrainModel.sample(42L, 1234.5, -987.25, TerrainSettings.DEFAULT);

        assertEquals(first, second);
        assertTrue(Double.isFinite(first.height()));
        assertTrue(Double.isFinite(first.waterLevel()));
        assertTrue(first.height() >= -32.0 && first.height() <= 1950.0);
    }

    @Test
    void drainageCreatesWaterFilledChannels() {
        boolean foundChannel = false;
        for (int z = -4096; z <= 4096 && !foundChannel; z += 64) {
            for (int x = -4096; x <= 4096; x += 64) {
                TerrainModel.Sample sample = TerrainModel.sample(8675309L, x, z, TerrainSettings.DEFAULT);
                if ((sample.river() > .32 || sample.lake() > .38) && sample.height() < sample.waterLevel()) {
                    foundChannel = true;
                    break;
                }
            }
        }
        assertTrue(foundChannel, "Expected at least one water-filled drainage channel in the sampled area");
    }

    @Test
    void seedSaltChangesTerrain() {
        TerrainSettings salted = new TerrainSettings(1,1,1,1,1,1,1,430,1,96,1,99L);
        assertNotEquals(
                TerrainModel.sample(12L, 800, 1200, TerrainSettings.DEFAULT),
                TerrainModel.sample(12L, 800, 1200, salted)
        );
    }
}
