package com.cokedoutsnail.realisticterrain;

import com.cokedoutsnail.realisticterrain.worldgen.RealisticChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;

public final class RealisticTerrainMod implements ModInitializer {
    public static final String MOD_ID = "realisticterrain";
    public static final RegistryKey<WorldPreset> PRESET_KEY = RegistryKey.of(RegistryKeys.WORLD_PRESET, id("realistic"));
    public static Identifier id(String path){ return Identifier.of(MOD_ID, path); }
    @Override public void onInitialize() {
        Registry.register(Registries.CHUNK_GENERATOR, id("realistic"), RealisticChunkGenerator.CODEC);
    }
}
