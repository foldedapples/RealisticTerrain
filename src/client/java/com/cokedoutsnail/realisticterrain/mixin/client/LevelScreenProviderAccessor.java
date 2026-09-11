package com.cokedoutsnail.realisticterrain.mixin.client;

import net.minecraft.client.gui.screen.world.LevelScreenProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Lets us overwrite the (otherwise immutable) static map of world-preset editor screens.
 *
 * This uses a plain Mixin accessor instead of redirecting/injecting into LevelScreenProvider's
 * static initializer: earlier attempts at that hit two dead ends - Mixin requires a mixin's
 * declared kind to match its target's kind, and LevelScreenProvider is actually an interface, but
 * an interface-declared @Redirect on its <clinit> then tripped a MixinExtras bug
 * (FactoryRedirectWrapperMixinTransformer threw a ClassCastException). A generated accessor is
 * a much simpler, far more common Mixin feature that sidesteps both problems entirely.
 */
@Mixin(LevelScreenProvider.class)
public interface LevelScreenProviderAccessor {
    @Accessor("WORLD_PRESET_TO_SCREEN_PROVIDER")
    @Mutable
    static void realisticterrain$setScreenProviders(Map<?, ?> value) {
        throw new AssertionError("Mixin was not applied");
    }
}
