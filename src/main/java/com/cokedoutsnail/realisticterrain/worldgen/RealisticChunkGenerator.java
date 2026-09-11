package com.cokedoutsnail.realisticterrain.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.Util;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.SpawnHelper;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.Random;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class RealisticChunkGenerator extends ChunkGenerator {
    public static final int MIN_Y=-64, MAX_Y=2031, WORLD_HEIGHT=2096;
    public static final MapCodec<RealisticChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(RealisticChunkGenerator::getBiomeSource),
            TerrainSettings.CODEC.fieldOf("settings").forGetter(RealisticChunkGenerator::settings)
    ).apply(i, RealisticChunkGenerator::new));

    private final TerrainSettings settings;
    // The terrain seed is derived once from NoiseConfig (deterministic per world) and reused for
    // both terrain sampling and biome sampling, so chunks and features stay perfectly aligned.
    private volatile long cachedTerrainSeed = Long.MIN_VALUE;

    public RealisticChunkGenerator(BiomeSource biomeSource, TerrainSettings settings){ super(biomeSource); this.settings=settings; }
    public TerrainSettings settings(){ return settings; }
    @Override protected MapCodec<? extends ChunkGenerator> getCodec(){ return CODEC; }
    @Override public int getSeaLevel(){ return settings.seaLevel(); }
    @Override public int getMinimumY(){ return MIN_Y; }
    @Override public int getWorldHeight(){ return WORLD_HEIGHT; }

    private long terrainSeed(NoiseConfig noiseConfig){
        long s=cachedTerrainSeed;
        if(s==Long.MIN_VALUE){
            s=noiseConfig.getOrCreateRandomDeriver(com.cokedoutsnail.realisticterrain.RealisticTerrainMod.id("terrain"))
                    .split(0L).nextLong();
            cachedTerrainSeed=s;
        }
        return s;
    }

    /** Cached terrain seed for paths without a NoiseConfig (feature generation); set by populateNoise. */
    private long terrainSeedValue(){
        long s=cachedTerrainSeed;
        return s==Long.MIN_VALUE ? 0L : s;
    }

    @Override public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig, StructureAccessor structures, Chunk chunk){
        return CompletableFuture.supplyAsync(() -> {
            long seed=terrainSeed(noiseConfig);
            // The terrain-aware biome source must sample the terrain with the same seed the
            // terrain itself uses, otherwise biomes would drift away from the real landscape.
            if(getBiomeSource() instanceof TerrainBiomeSource tbs) tbs.setTerrainSeed(seed);
            ChunkPos cp=chunk.getPos(); BlockPos.Mutable p=new BlockPos.Mutable();
            for(int lx=0;lx<16;lx++) for(int lz=0;lz<16;lz++){
                int x=cp.getStartX()+lx,z=cp.getStartZ()+lz;
                TerrainModel.Sample sm=TerrainModel.sample(seed,x,z,settings);
                int surface=(int)Math.floor(sm.height());
                int waterTop=(int)Math.floor(sm.waterLevel());
                RegistryEntry<Biome> biome = biomeSource.getBiome(x >> 2, surface >> 2, z >> 2, noiseConfig.getMultiNoiseSampler());
                boolean cold=biome.value().getTemperature() < 0.15F || sm.temperature() < -.2;
                boolean wet=biome.value().hasPrecipitation() || sm.moisture()>.15;
                for(int y=MIN_Y;y<=Math.max(surface,waterTop);y++){
                    BlockState state;
                    if(y>surface) state = y<=waterTop?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState();
                    else if(y==MIN_Y) state=Blocks.BEDROCK.getDefaultState();
                    else if(TerrainModel.cave(seed,x,y,z,settings) && y<surface-7) state= y<settings.seaLevel()-18?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState();
                    else state=baseState(x,z,surface,y,waterTop,sm,cold,wet);
                    chunk.setBlockState(p.set(x,y,z),state,0);
                }
            }
            Heightmap.populateHeightmaps(chunk, EnumSet.of(Heightmap.Type.OCEAN_FLOOR_WG, Heightmap.Type.WORLD_SURFACE_WG));
            return chunk;
        }, Util.getMainWorkerExecutor());
    }

    private BlockState baseState(int x,int z,int surface,int y,int waterTop,TerrainModel.Sample sm,boolean cold,boolean wet){
        int depth=surface-y;
        if(depth>5) return Blocks.STONE.getDefaultState();
        if(surface<=waterTop+2) {
            if(sm.river()>.25 && depth==0) return Blocks.GRAVEL.getDefaultState();
            return depth==0?Blocks.SAND.getDefaultState():Blocks.SANDSTONE.getDefaultState();
        }
        double snowFade=(surface-settings.snowLine())/115.0 + (cold?.55:0);
        if(depth==0 && snowFade>0 && pseudo(x,z)<Math.min(1,snowFade)) return Blocks.SNOW_BLOCK.getDefaultState();
        if(sm.ridge()>.72 && sm.slopeHint()>.42) return Blocks.STONE.getDefaultState();
        if(depth==0) return wet?Blocks.GRASS_BLOCK.getDefaultState():Blocks.COARSE_DIRT.getDefaultState();
        return Blocks.DIRT.getDefaultState();
    }
    private static double pseudo(int a,int b){ long h=(a*0x9E3779B97F4A7C15L)^(b*0xC2B2AE3D27D4EB4FL); h^=h>>>29; return (h&0xffff)/65535.0; }

    @Override public int getHeight(int x,int z,Heightmap.Type type,HeightLimitView world,NoiseConfig noiseConfig){
        TerrainModel.Sample sample=TerrainModel.sample(terrainSeed(noiseConfig),x,z,settings);
        double height=sample.height();
        if(type==Heightmap.Type.WORLD_SURFACE || type==Heightmap.Type.WORLD_SURFACE_WG) {
            height=Math.max(height,sample.waterLevel());
        }
        return (int)Math.floor(height)+1;
    }
    @Override public VerticalBlockSample getColumnSample(int x,int z,HeightLimitView world,NoiseConfig noiseConfig){
        TerrainModel.Sample sample=TerrainModel.sample(terrainSeed(noiseConfig),x,z,settings);
        int terrainTop=(int)Math.floor(sample.height())+1;
        int waterTop=(int)Math.floor(sample.waterLevel())+1;
        int top=Math.max(terrainTop,waterTop); BlockState[] states=new BlockState[top-MIN_Y];
        for(int y=MIN_Y;y<top;y++) states[y-MIN_Y]=y<terrainTop?Blocks.STONE.getDefaultState():(y<waterTop?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState());
        return new VerticalBlockSample(MIN_Y,states);
    }
    @Override public void buildSurface(ChunkRegion region,StructureAccessor structures,NoiseConfig noiseConfig,Chunk chunk) { }
    @Override public void carve(ChunkRegion region,long seed,NoiseConfig noiseConfig,BiomeAccess biomeAccess,StructureAccessor structures,Chunk chunk) { }
    @Override public void populateEntities(ChunkRegion region) {
        ChunkPos chunkPos = region.getCenterPos();
        RegistryEntry<Biome> biome = region.getBiome(chunkPos.getStartPos().withY(region.getTopYInclusive()));
        ChunkRandom random = new ChunkRandom(new CheckedRandom(region.getSeed()));
        random.setPopulationSeed(region.getSeed(), chunkPos.getStartX(), chunkPos.getStartZ());
        SpawnHelper.populateEntities(region, biome, chunkPos, random);
    }

    /**
     * Vanilla biome decorations first, then extra terrain-aware trees so the vegetation density
     * slider actually does something. Trees are placed straight from configured features (bypassing
     * placement modifiers, which are biome-gated and would block extras outside their biome list).
     */
    @Override public void generateFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor){
        super.generateFeatures(world, chunk, structureAccessor);
        if(settings.vegetationDensity() <= 0.02f) return;
        long seed=terrainSeedValue();
        // Fallback before any noise chunk ran; still deterministic per world.
        if(seed==0L) seed=world.getSeed() ^ 0x5245414C49535449L;
        ChunkPos cp=chunk.getPos();
        Random random=Random.create(world.getSeed() ^ (cp.x*0x9E3779B97F4A7C15L) ^ (cp.z*0xC2B2AE3D27D4EB4FL) ^ 0x5245414CL);
        DynamicRegistryManager registries=world.getRegistryManager();
        Registry<ConfiguredFeature<?, ?>> configured=registries.getOrThrow(RegistryKeys.CONFIGURED_FEATURE);
        for(int lx=0;lx<16;lx+=4) for(int lz=0;lz<16;lz+=4){
            int x=cp.getStartX()+lx, z=cp.getStartZ()+lz;
            TerrainModel.Sample sm=TerrainModel.sample(seed,x,z,settings);
            Identifier species=treeSpecies(sm,settings);
            if(species==null) continue;
            if(random.nextFloat() > settings.vegetationDensity()*0.16f) continue;
            int y=chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE_WG,lx,lz)+1;
            configured.getOptionalValue(RegistryKey.of(RegistryKeys.CONFIGURED_FEATURE, species)).ifPresent(f ->
                    f.generate(world, this, random, new BlockPos(x, y, z)));
        }
    }

    /** Chooses a vanilla tree placed-feature id (or null for no tree) from the terrain model climate. */
    private static Identifier treeSpecies(TerrainModel.Sample sm,TerrainSettings s){
        double h=sm.height(), w=sm.waterLevel();
        if(h<w+2) return null;                            // below the waterline
        if(sm.river()>0.2 || sm.lake()>0.2) return null;  // in a channel or lake
        if(sm.slopeHint()>0.5) return null;               // too steep to hold a tree
        if(h>s.snowLine()+40) return null;                // above the tree line
        double m=sm.moisture(), t=sm.temperature();
        if(t<-.2){
            if(m<-.1) return null;
            return Identifier.ofVanilla("trees_taiga");
        }
        if(t>.35 && m>.1) return Identifier.ofVanilla("trees_sparse_jungle");
        if(m>.25) return t>.25?Identifier.ofVanilla("trees_birch"):Identifier.ofVanilla("trees_birch_and_oak_leaf_litter");
        if(m>.12) return Identifier.ofVanilla("trees_birch_and_oak_leaf_litter");
        if(m<-.15) return null; // desert
        if(t>.3) return Identifier.ofVanilla("trees_savanna");
        return Identifier.ofVanilla("trees_plains");
    }
    @Override public void appendDebugHudText(List<String> text,NoiseConfig noiseConfig,BlockPos pos){ TerrainModel.Sample s=TerrainModel.sample(terrainSeed(noiseConfig),pos.getX(),pos.getZ(),settings); text.add(String.format("Realistic Terrain h=%.1f river=%.2f ridge=%.2f",s.height(),s.river(),s.ridge())); }
}
