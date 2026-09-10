package com.cokedoutsnail.realisticterrain.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class RealisticChunkGenerator extends ChunkGenerator {
    public static final int MIN_Y=-64, MAX_Y=2031, WORLD_HEIGHT=2096;
    public static final MapCodec<RealisticChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            TerrainSettings.CODEC.forGetter(g -> g.settings)
    ).apply(i, RealisticChunkGenerator::new));

    private final TerrainSettings settings;
    public RealisticChunkGenerator(BiomeSource biomeSource, TerrainSettings settings){ super(biomeSource); this.settings=settings; }
    public TerrainSettings settings(){ return settings; }
    @Override protected MapCodec<? extends ChunkGenerator> getCodec(){ return CODEC; }
    @Override public int getSeaLevel(){ return settings.seaLevel(); }
    @Override public int getMinimumY(){ return MIN_Y; }
    @Override public int getWorldHeight(){ return WORLD_HEIGHT; }

    private long terrainSeed(NoiseConfig noiseConfig){ return noiseConfig.getMultiNoiseSampler().hashCode() * 341873128712L + settings.seedSalt(); }

    @Override public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig, StructureAccessor structures, Chunk chunk){
        return CompletableFuture.supplyAsync(() -> {
            ChunkPos cp=chunk.getPos(); BlockPos.Mutable p=new BlockPos.Mutable(); long seed=terrainSeed(noiseConfig);
            for(int lx=0;lx<16;lx++) for(int lz=0;lz<16;lz++){
                int x=cp.getStartX()+lx,z=cp.getStartZ()+lz;
                TerrainModel.Sample sm=TerrainModel.sample(seed,x,z,settings);
                int surface=(int)Math.floor(sm.height());
                boolean cold=sm.temperature() < -.2; boolean wet=sm.moisture()>.15;
                for(int y=MIN_Y;y<=Math.max(surface,settings.seaLevel());y++){
                    BlockState state;
                    if(y>surface) state = y<=settings.seaLevel()?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState();
                    else if(y==MIN_Y) state=Blocks.BEDROCK.getDefaultState();
                    else if(TerrainModel.cave(seed,x,y,z,settings) && y<surface-7) state= y<settings.seaLevel()-18?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState();
                    else state=baseState(surface,y,sm,cold,wet);
                    chunk.setBlockState(p.set(x,y,z),state,0);
                }
            }
            return chunk;
        });
    }

    private BlockState baseState(int surface,int y,TerrainModel.Sample sm,boolean cold,boolean wet){
        int depth=surface-y;
        if(depth>5) return Blocks.STONE.getDefaultState();
        if(surface<=settings.seaLevel()+4) return depth==0?Blocks.SAND.getDefaultState():Blocks.SANDSTONE.getDefaultState();
        double snowFade=(surface-settings.snowLine())/115.0 + (cold?.55:0);
        if(depth==0 && snowFade>0 && pseudo(surface,(int)(sm.ridge()*10000))<Math.min(1,snowFade)) return Blocks.SNOW_BLOCK.getDefaultState();
        if(sm.ridge()>.72 && sm.slopeHint()>.42) return Blocks.STONE.getDefaultState();
        if(depth==0) return wet?Blocks.GRASS_BLOCK.getDefaultState():Blocks.COARSE_DIRT.getDefaultState();
        return Blocks.DIRT.getDefaultState();
    }
    private static double pseudo(int a,int b){ long h=(a*0x9E3779B97F4A7C15L)^(b*0xC2B2AE3D27D4EB4FL); h^=h>>>29; return (h&0xffff)/65535.0; }

    @Override public int getHeight(int x,int z,Heightmap.Type type,HeightLimitView world,NoiseConfig noiseConfig){ return (int)Math.floor(TerrainModel.sample(terrainSeed(noiseConfig),x,z,settings).height())+1; }
    @Override public VerticalBlockSample getColumnSample(int x,int z,HeightLimitView world,NoiseConfig noiseConfig){
        int top=Math.max(getHeight(x,z,Heightmap.Type.WORLD_SURFACE_WG,world,noiseConfig),settings.seaLevel()+1); BlockState[] states=new BlockState[top-MIN_Y];
        for(int y=MIN_Y;y<top;y++) states[y-MIN_Y]=y<getHeight(x,z,Heightmap.Type.WORLD_SURFACE_WG,world,noiseConfig)?Blocks.STONE.getDefaultState():(y<=settings.seaLevel()?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState());
        return new VerticalBlockSample(MIN_Y,states);
    }
    @Override public void buildSurface(ChunkRegion region,StructureAccessor structures,NoiseConfig noiseConfig,Chunk chunk) { }
    @Override public void carve(ChunkRegion region,long seed,NoiseConfig noiseConfig,BiomeAccess biomeAccess,StructureAccessor structures,Chunk chunk) { }
    @Override public void populateEntities(ChunkRegion region) { }
    @Override public void appendDebugHudText(List<String> text,NoiseConfig noiseConfig,BlockPos pos){ TerrainModel.Sample s=TerrainModel.sample(terrainSeed(noiseConfig),pos.getX(),pos.getZ(),settings); text.add(String.format("Realistic Terrain h=%.1f river=%.2f ridge=%.2f",s.height(),s.river(),s.ridge())); }
}
