package com.cokedoutsnail.realisticterrain;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.world.LevelScreenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public final class RealisticTerrainClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealisticTerrainMod.MOD_ID);

    @Override public void onInitializeClient() {
        // The customize-screen editor is registered by LevelScreenProviderMixin, which patches
        // an immutable vanilla map at world-preset-screen class init. That patch is best-effort
        // (it can stop matching if Mojang changes the target method), so a missing entry here must
        // never crash the game - it just means the "Customize" button won't appear for our preset.
        if (!LevelScreenProvider.WORLD_PRESET_TO_SCREEN_PROVIDER.containsKey(Optional.of(RealisticTerrainMod.PRESET_KEY))) {
            LOGGER.warn("Realistic Terrain world-preset editor screen was not registered; the Customize button will not appear for the Realistic preset.");
        }
    }
}
