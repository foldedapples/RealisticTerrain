package com.cokedoutsnail.realisticterrain;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * The "Customize" screen for the Realistic preset is wired up entirely by
 * {@link com.cokedoutsnail.realisticterrain.mixin.client.WorldCreatorScreenMixin} - it injects into
 * {@code WorldCreator#getLevelScreenProvider} directly, so there is nothing left to register here.
 */
@Environment(EnvType.CLIENT)
public final class RealisticTerrainClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
    }
}
