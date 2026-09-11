package com.cokedoutsnail.realisticterrain.mixin.client;

import com.cokedoutsnail.realisticterrain.client.gui.RealisticTerrainScreen;
import com.cokedoutsnail.realisticterrain.worldgen.RealisticChunkGenerator;
import net.minecraft.client.gui.screen.world.LevelScreenProvider;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.world.GeneratorOptionsHolder;
import net.minecraft.world.dimension.DimensionOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the "Customize" button appear for the Realistic preset in the create-world UI.
 *
 * <p>Earlier attempts patched {@code LevelScreenProvider.WORLD_PRESET_TO_SCREEN_PROVIDER} - an
 * otherwise-immutable static field declared on an interface - via a Mixin accessor. That field is
 * read in exactly one place, {@code WorldCreator#getLevelScreenProvider}, so injecting straight
 * into that method sidesteps the interface-static-field question (and its history of silently
 * failing to apply) entirely: whenever the pending world is currently configured to use our chunk
 * generator, this simply hands back our own screen supplier before the vanilla map lookup runs.
 */
@Environment(EnvType.CLIENT)
@Mixin(WorldCreator.class)
public abstract class WorldCreatorScreenMixin {
    @Shadow
    private GeneratorOptionsHolder generatorOptionsHolder;

    @Inject(method = "getLevelScreenProvider", at = @At("HEAD"), cancellable = true)
    private void realisticterrain$provideCustomizeScreen(CallbackInfoReturnable<LevelScreenProvider> cir) {
        if (realisticterrain$isOurWorld()) {
            cir.setReturnValue((LevelScreenProvider) RealisticTerrainScreen::new);
        }
    }

    /**
     * Whether the pending world is currently configured to use our chunk generator.
     *
     * <p>Eclipse null analysis reports the registry lookup's generics as an unchecked conversion
     * because the registry API carries no null annotations; the lookup itself is null-safe, so the
     * diagnostic is suppressed rather than left in the build output.
     */
    @SuppressWarnings("null")
    private boolean realisticterrain$isOurWorld() {
        return this.generatorOptionsHolder.dimensionOptionsRegistry()
                .getOptionalValue(DimensionOptions.OVERWORLD)
                .map(DimensionOptions::chunkGenerator)
                .filter(generator -> generator instanceof RealisticChunkGenerator)
                .isPresent();
    }
}
