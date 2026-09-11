package com.cokedoutsnail.realisticterrain;

import com.cokedoutsnail.realisticterrain.client.gui.RealisticTerrainScreen;
import com.cokedoutsnail.realisticterrain.mixin.client.LevelScreenProviderAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.world.LevelScreenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class RealisticTerrainClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealisticTerrainMod.MOD_ID);

    @Override public void onInitializeClient() {
        // Register the "Customize" screen for the Realistic preset in the create-world UI.
        // LevelScreenProviderAccessor is a generated Mixin accessor that can overwrite the
        // (otherwise immutable) WORLD_PRESET_TO_SCREEN_PROVIDER map. This is purely cosmetic,
        // so any failure here must never take the whole game down with it.
        try {
            Map<Object, Object> providers = new HashMap<>(LevelScreenProvider.WORLD_PRESET_TO_SCREEN_PROVIDER);
            providers.put(Optional.of(RealisticTerrainMod.PRESET_KEY), (LevelScreenProvider) RealisticTerrainScreen::new);
            LevelScreenProviderAccessor.realisticterrain$setScreenProviders(Map.copyOf(providers));
        } catch (Throwable t) {
            LOGGER.warn("Could not register the Realistic Terrain customize screen; the Customize button will not appear for the Realistic preset.", t);
        }
    }
}
