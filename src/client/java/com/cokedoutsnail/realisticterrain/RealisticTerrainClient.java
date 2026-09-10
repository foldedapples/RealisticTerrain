package com.cokedoutsnail.realisticterrain;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.world.LevelScreenProvider;

import java.util.Optional;

public final class RealisticTerrainClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        if (!LevelScreenProvider.WORLD_PRESET_TO_SCREEN_PROVIDER.containsKey(Optional.of(RealisticTerrainMod.PRESET_KEY))) {
            throw new IllegalStateException("Realistic Terrain world-preset editor was not registered");
        }
    }
}
