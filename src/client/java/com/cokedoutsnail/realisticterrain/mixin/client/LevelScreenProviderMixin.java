package com.cokedoutsnail.realisticterrain.mixin.client;

import com.cokedoutsnail.realisticterrain.RealisticTerrainMod;
import com.cokedoutsnail.realisticterrain.client.gui.RealisticTerrainScreen;
import net.minecraft.client.gui.screen.world.LevelScreenProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Mixin(LevelScreenProvider.class)
public interface LevelScreenProviderMixin {
    @Redirect(
        method = "<clinit>",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;of(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"
        ),
        require = 0
    )
    private static Map<Object, Object> realisticterrain$addPresetEditor(
        Object firstKey, Object firstValue, Object secondKey, Object secondValue
    ) {
        Map<Object, Object> providers = new HashMap<>();
        providers.put(firstKey, firstValue);
        providers.put(secondKey, secondValue);
        LevelScreenProvider editor = RealisticTerrainScreen::new;
        providers.put(Optional.of(RealisticTerrainMod.PRESET_KEY), editor);
        return Map.copyOf(providers);
    }
}
