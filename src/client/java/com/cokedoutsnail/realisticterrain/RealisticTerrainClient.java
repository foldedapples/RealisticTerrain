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
        // so any failure here must never take the whole game down with it - but it logs loudly
        // either way, because "silently didn't work" is exactly what happened the first time
        // this was tried and there was no way to tell from the outside.
        try {
            Object key = Optional.of(RealisticTerrainMod.PRESET_KEY);
            Map<Object, Object> providers = new HashMap<>(LevelScreenProvider.WORLD_PRESET_TO_SCREEN_PROVIDER);
            providers.put(key, (LevelScreenProvider) RealisticTerrainScreen::new);
            LevelScreenProviderAccessor.realisticterrain$setScreenProviders(Map.copyOf(providers));

            boolean confirmed = LevelScreenProviderAccessor.realisticterrain$getScreenProviders().containsKey(key);
            if (confirmed) {
                LOGGER.info("Realistic Terrain: registered the world-preset Customize screen ({} entries in the provider map).", providers.size());
            } else {
                LOGGER.warn("Realistic Terrain: the Customize screen registration ran without error, but the entry isn't present afterward - the Customize button will stay disabled for the Realistic preset. This means LevelScreenProvider.WORLD_PRESET_TO_SCREEN_PROVIDER isn't the field CreateWorldScreen actually reads, or something else resets it after client init.");
            }
        } catch (Throwable t) {
            LOGGER.warn("Realistic Terrain: could not register the world-preset Customize screen; the Customize button will not appear for the Realistic preset.", t);
        }
    }
}
