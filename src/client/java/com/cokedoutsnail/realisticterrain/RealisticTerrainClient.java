package com.cokedoutsnail.realisticterrain;

import com.cokedoutsnail.realisticterrain.client.gui.RealisticTerrainScreen;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.world.LevelScreenProvider;
import java.util.Optional;

public final class RealisticTerrainClient implements ClientModInitializer {
    @Override public void onInitializeClient(){
        LevelScreenProvider.WORLD_PRESET_TO_SCREEN_PROVIDER.put(Optional.of(RealisticTerrainMod.PRESET_KEY), RealisticTerrainScreen::new);
    }
}
