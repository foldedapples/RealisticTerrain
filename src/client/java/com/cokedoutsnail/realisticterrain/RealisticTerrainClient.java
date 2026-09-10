package com.cokedoutsnail.realisticterrain;

import net.fabricmc.api.ClientModInitializer;

public final class RealisticTerrainClient implements ClientModInitializer {
    // Intentionally does not register a "Customize" screen for the Realistic preset in the
    // create-world UI: that requires patching an immutable vanilla map (LevelScreenProvider,
    // an interface) at class-init time, which repeatedly proved too fragile across Mixin/
    // MixinExtras versions to do safely. The preset still works fully without it - it just
    // won't have a Customize button, the same as several vanilla presets (e.g. Large Biomes).
    @Override public void onInitializeClient() { }
}
