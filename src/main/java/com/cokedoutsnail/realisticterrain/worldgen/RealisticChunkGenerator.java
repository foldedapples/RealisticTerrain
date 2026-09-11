package com.cokedoutsnail.realisticterrain.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.cokedoutsnail.realisticterrain.noise.Noise2D;
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
import java.util.concurrent.atomic.AtomicLong;

public final class RealisticChunkGenerator extends ChunkGenerator {
    public static final int MIN_Y=-64, MAX_Y=2031, WORLD_HEIGHT=2096;
    /**
     * Highest y that is <em>always</em> solid crust, whatever the modelled surface says. The
     * generator lays bedrock at {@link #MIN_Y} and deepslate directly above it, so every column
     * owns a floor from {@code y = -64} up to at least {@code y = -60}. Neither air nor water can
     * therefore open onto the void at the bottom of the world.
     */
    private static final int CRUST_TOP = MIN_Y + 4;
    /**
     * Deepest ground surface the generator will write. The terrain model already bottoms out at
     * {@link TerrainModel#MIN_SURFACE} (-52); this is a hard safety net that keeps the generated
     * surface inside the world even if the model is retuned later.
     */
    private static final int SURFACE_FLOOR = MIN_Y + 5;
    public static final MapCodec<RealisticChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(RealisticChunkGenerator::getBiomeSource),
            TerrainSettings.CODEC.fieldOf("settings").forGetter(RealisticChunkGenerator::settings)
    ).apply(i, RealisticChunkGenerator::new));

    private final TerrainSettings settings;
    // The terrain seed is derived once from NoiseConfig (deterministic per world) and reused for
    // both terrain sampling and biome sampling, so chunks and features stay perfectly aligned.
    // Atomic because populateNoise runs concurrently on chunk worker threads (benign, the derived
    // value is identical on every thread - the CAS just makes the write-once cache race-free).
    private final AtomicLong cachedTerrainSeed = new AtomicLong(Long.MIN_VALUE);

    public RealisticChunkGenerator(BiomeSource biomeSource, TerrainSettings settings){ super(biomeSource); this.settings=settings; }
    public TerrainSettings settings(){ return settings; }
    @Override protected MapCodec<? extends ChunkGenerator> getCodec(){ return CODEC; }
    @Override public int getSeaLevel(){ return settings.seaLevel(); }
    @Override public int getMinimumY(){ return MIN_Y; }
    @Override public int getWorldHeight(){ return WORLD_HEIGHT; }

    private long terrainSeed(NoiseConfig noiseConfig){
        long s = cachedTerrainSeed.get();
        if (s == Long.MIN_VALUE) {
            s = noiseConfig.getOrCreateRandomDeriver(com.cokedoutsnail.realisticterrain.RealisticTerrainMod.id("terrain"))
                    .split(0L).nextLong();
            cachedTerrainSeed.compareAndSet(Long.MIN_VALUE, s);
            s = cachedTerrainSeed.get();
        }
        return s;
    }

    /** Cached terrain seed for paths without a NoiseConfig (feature generation); set by populateNoise. */
    private long terrainSeedValue(){
        long s = cachedTerrainSeed.get();
        return s == Long.MIN_VALUE ? 0L : s;
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
                int surface=Math.max((int)Math.floor(sm.height()),SURFACE_FLOOR);
                int waterTop=(int)Math.floor(sm.waterLevel());
                RegistryEntry<Biome> biome = biomeSource.getBiome(x >> 2, surface >> 2, z >> 2, noiseConfig.getMultiNoiseSampler());
                boolean cold=biome.value().getTemperature() < 0.15F || sm.temperature() < -.2;
                boolean wet=biome.value().hasPrecipitation() || sm.moisture()>.15;
                // Sand belongs to the coast and to genuine desert, and to nowhere else. Both flags
                // mirror the exact branches TerrainBiomeSource picks DESERT and BEACH from, so the
                // surface block always agrees with the biome standing on it - instead of sand being a
                // global fallback (the all-sand world) or, as it was, absent from every dry surface
                // because land fell straight through to coarse dirt.
                boolean desert = sm.temperature() > 0.25 && sm.moisture() < -0.1;
                boolean beach = Math.abs(sm.continent() - settings.coastLine()) < 0.05
                        && sm.height() < settings.seaLevel() + 5;
                int top=Math.max(surface,waterTop);
                for(int y=MIN_Y;y<=top;y++){
                    BlockState state;
                    // Guaranteed crust first: bedrock at the very bottom, deepslate above it. This
                    // is branched before the water/air case so a submerged column can never have
                    // its floor overwritten by water and open a hole down into the void.
                    if(y<=CRUST_TOP) state = y==MIN_Y?Blocks.BEDROCK.getDefaultState():Blocks.DEEPSLATE.getDefaultState();
                    else if(y>surface) state = y<=waterTop?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState();
                    else if(TerrainModel.cave(seed,x,y,z,settings) && y<surface-7) state= y<settings.seaLevel()-18?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState();
                    else state=baseState(seed,x,z,surface,y,sm,cold,wet,desert,beach);
                    chunk.setBlockState(p.set(x,y,z),state,0);
                }
            }
            Heightmap.populateHeightmaps(chunk, EnumSet.of(Heightmap.Type.OCEAN_FLOOR_WG, Heightmap.Type.WORLD_SURFACE_WG));
            return chunk;
        }, Util.getMainWorkerExecutor());
    }

    private BlockState baseState(long seed,int x,int z,int surface,int y,TerrainModel.Sample sm,boolean cold,boolean wet,boolean desert,boolean beach){
        int depth=surface-y;
        // Beach and channel-bed test. This MUST be read off the continuous submersion depth, never
        // off `surface - waterTop`: both of those are floored to an integer y, so their difference is
        // quantised and the width of the beach band in blocks then depended on which way the water
        // surface had last been floored. That is what painted the concentric stair-step rings of sand
        // and gravel along every river and shoreline. `waterLevel` and `height` here are still
        // full-precision doubles straight out of the terrain model - the only floor in the entire
        // pipeline is the one that places a block - and the jitter keeps the shoreline off a ruler.
        boolean submerged = sm.waterLevel()-sm.height() > (pseudo(x,z)-0.5)*1.4 - 2.0;
        // Water-bearing beds: beaches, river/lake floors, sandbars.
        if(submerged){
            if(sm.river()>.25 && depth==0) return Blocks.GRAVEL.getDefaultState();
            if(depth==0) return Blocks.SAND.getDefaultState();
            if(depth==1) return Blocks.SANDSTONE.getDefaultState();
            return Blocks.STONE.getDefaultState();
        }
        // Snowline.
        double snowFade=(surface-settings.snowLine())/115.0 + (cold?.55:0);
        if(depth==0 && snowFade>0 && pseudo(x,z)<Math.min(1,snowFade)) return Blocks.SNOW_BLOCK.getDefaultState();
        // Exposed bedrock on steep, high, ridged slopes (scree, peaks).
        if(sm.ridge()>.72 && sm.slopeHint()>.42) return Blocks.STONE.getDefaultState();
        // Dry sand, restricted to the coast and to real desert. The coastal arm only needs a small
        // jittered band so the shore reads as a beach rather than a carpet: `(pseudo - 0.5)` is a
        // deterministic per-column value in [-0.5, 0.5], so it puts sand up to ~3 blocks inland of the
        // coastline contour and tapers it out. Desert is the genuine biome (hot AND dry, exactly the
        // branch TerrainBiomeSource uses), so the sand there is a desert floor with sandstone under
        // it - and because `desert`/`beach` are false everywhere else, no other biome can turn to sand.
        boolean coastal = beach && sm.continent() < settings.coastLine() + 0.02 + (pseudo(x,z)-0.5)*0.03;
        if(depth==0 && desert) return Blocks.SAND.getDefaultState();
        if(depth>0 && depth<=4 && desert) return Blocks.SANDSTONE.getDefaultState();
        if(depth==0 && coastal) return Blocks.SAND.getDefaultState();
        if(depth==1 && coastal) return Blocks.SANDSTONE.getDefaultState();

        // --- Geological rock strata ---
        // 1. Igneous intrusion near active plate margins (faults).
        if(sm.fault()>.45 && depth<40){
            if(y<settings.seaLevel()-40 && sm.fault()>.7) return Blocks.BASALT.getDefaultState();
            double mix=Noise2D.value((x + y*.30)/23.0,(z - y*.20)/23.0,seed+991);
            return mix>.5?Blocks.GRANITE.getDefaultState():Blocks.DIORITE.getDefaultState();
        }
        // 2. Deep basement rock.
        if(y < settings.seaLevel()-160) return Blocks.DEEPSLATE.getDefaultState();
        // 3. Canyon/exposed walls show sedimentary banding.
        if(depth>8 && sm.slopeHint()>.25){
            double band=Noise2D.value(x/17.0,(z + y*1.25)/17.0,seed+1009);
            return band>0.15?Blocks.SANDSTONE.getDefaultState():Blocks.STONE.getDefaultState();
        }
        // 4. Surface and subsoil (soil depth from hydraulic erosion).
        // Rich soil (high values) → deep dirt/grass (lush biomes, fertile valleys).
        // Stripped soil (low values) → exposed rock/gravel (scree, peaks, eroded canyon walls).
        double soil = sm.soil();
        int soilDepth = (int)(soil * 5.0); // 0-5 blocks of soil
        if(depth==0){
            if(soil < 0.15 && sm.slopeHint() > 0.3) return Blocks.GRAVEL.getDefaultState(); // scree
            if(soil < 0.3) return Blocks.STONE.getDefaultState(); // exposed bedrock
            return wet?Blocks.GRASS_BLOCK.getDefaultState():Blocks.COARSE_DIRT.getDefaultState();
        }
        if(depth <= soilDepth) return wet?Blocks.DIRT.getDefaultState():Blocks.COARSE_DIRT.getDefaultState();
        if(depth <= soilDepth + 1) return Blocks.DIRT.getDefaultState(); // transition layer
        return Blocks.STONE.getDefaultState();
    }
    private static double pseudo(int a,int b){ long h=(a*0x9E3779B97F4A7C15L)^(b*0xC2B2AE3D27D4EB4FL); h^=h>>>29; return (h&0xffff)/65535.0; }

    @Override public int getHeight(int x,int z,Heightmap.Type type,HeightLimitView world,NoiseConfig noiseConfig){
        TerrainModel.Sample sample=TerrainModel.sample(terrainSeed(noiseConfig),x,z,settings);
        double height=sample.height();
        if(type==Heightmap.Type.WORLD_SURFACE || type==Heightmap.Type.WORLD_SURFACE_WG) {
            height=Math.max(height,sample.waterLevel());
        }
        return Math.max((int)Math.floor(height),SURFACE_FLOOR)+1;
    }
    @Override public VerticalBlockSample getColumnSample(int x,int z,HeightLimitView world,NoiseConfig noiseConfig){
        long seed=terrainSeed(noiseConfig);
        TerrainModel.Sample sample=TerrainModel.sample(seed,x,z,settings);
        int terrainTop=Math.max((int)Math.floor(sample.height()),SURFACE_FLOOR)+1;
        int waterTop=(int)Math.floor(sample.waterLevel())+1;
        int top=Math.max(terrainTop,waterTop); BlockState[] states=new BlockState[top-MIN_Y];
        // Same continuous test as baseState: computing it from the floored terrainTop/waterTop pair
        // is what made the beach band step, and this column sample is what spawn placement and
        // feature generation read.
        boolean submerged=sample.waterLevel()-sample.height() > (pseudo(x,z)-0.5)*1.4 - 2.0;
        // Kept in lockstep with baseState: the column sample is what spawn placement and feature
        // generation read, so a desert or a dry beach has to report the same sand here too.
        boolean desert = sample.temperature() > 0.25 && sample.moisture() < -0.1;
        boolean coastal = Math.abs(sample.continent()-settings.coastLine()) < 0.05
                && sample.height() < settings.seaLevel()+5
                && sample.continent() < settings.coastLine() + 0.02 + (pseudo(x,z)-0.5)*0.03;
        for(int y=MIN_Y;y<top;y++){
            BlockState st;
            // Same guaranteed crust as populateNoise: this column sample is what spawn placement
            // and feature generation read, so it must never report a missing floor either.
            if(y<=CRUST_TOP) st=y==MIN_Y?Blocks.BEDROCK.getDefaultState():Blocks.DEEPSLATE.getDefaultState();
            else if(y>=terrainTop) st=y<waterTop?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState();
            else if(y<settings.seaLevel()-160) st=Blocks.DEEPSLATE.getDefaultState();
            else if(submerged && y==terrainTop-1) st=Blocks.SAND.getDefaultState();
            else if(submerged && y>=terrainTop-3) st=Blocks.SANDSTONE.getDefaultState();
            else if(!submerged && desert && y==terrainTop-1) st=Blocks.SAND.getDefaultState();
            else if(!submerged && desert && y>=terrainTop-5) st=Blocks.SANDSTONE.getDefaultState();
            else if(!submerged && coastal && y==terrainTop-1) st=Blocks.SAND.getDefaultState();
            else if(!submerged && coastal && y==terrainTop-2) st=Blocks.SANDSTONE.getDefaultState();
            else if(y>=terrainTop-1) st=Blocks.GRASS_BLOCK.getDefaultState();
            else if(y>=terrainTop-4) st=Blocks.DIRT.getDefaultState();
            else st=Blocks.STONE.getDefaultState();
            states[y-MIN_Y]=st;
        }
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
     * Density scales dynamically with real geomorph slope: dense, clustered forests in flat valley
     * floors and canyon bottoms, thinning to nothing on steep canyon walls, scree and peaks.
     */
    @Override public void generateFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor){
        super.generateFeatures(world, chunk, structureAccessor);
        stripIsolatedSprings(world, chunk);
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
            double slope=TerrainModel.geomorphSlope(seed,x,z,settings);
            Identifier species=treeSpecies(sm,settings,slope);
            if(species==null) continue;
            // Flat valley floors hold dense forest; steep walls and peaks hold none.
            double flatness=clamp01(1.0 - (slope-0.30)/1.2);
            // Forests come in patches (cluster noise), not uniform sprinkles.
            double cluster=0.42+0.35*Noise2D.value(x/300.0, z/300.0, seed+977);
            // Rich deposited soil supports denser stands; stripped erosion leaves bare ground.
            double soilFactor = 0.6 + 0.8 * sm.soil();
            double chance=settings.vegetationDensity()*0.24f*flatness*cluster*soilFactor;
            if(random.nextFloat() > chance) continue;
            int y=chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE_WG,lx,lz)+1;
            configured.getOptionalValue(RegistryKey.of(RegistryKeys.CONFIGURED_FEATURE, species)).ifPresent(f ->
                    f.generate(world, this, random, new BlockPos(x, y, z)));
        }
    }

    private static double clamp01(double v){ return v<0?0:(v>1?1:v); }

    /**
     * Vanilla's FLUID_SPRINGS decoration step litters underground open space with lone, non-flowing
     * water (and lava) source blocks - and in a world this tall, whose caves run far higher than
     * vanilla's ever do (see {@link TerrainModel#cave}), that step fires far more often than it does
     * in vanilla. The result reads as stray "water droplets" scattered through the caves with no
     * outlet, never flowing anywhere. Every body of water this generator itself carves - ocean,
     * river, lake, or a flooded cave pocket below {@code seaLevel - 18} - is always several blocks
     * across by construction, so a WATER block with no WATER neighbour at all can only be one of
     * these stray vanilla springs: safe to clear without ever touching a real body of water.
     */
    private static void stripIsolatedSprings(StructureWorldAccess world, Chunk chunk){
        ChunkPos cp = chunk.getPos();
        BlockPos.Mutable p = new BlockPos.Mutable();
        for(int lx=0; lx<16; lx++){
            for(int lz=0; lz<16; lz++){
                int x = cp.getStartX()+lx, z = cp.getStartZ()+lz;
                int surface = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE_WG, lx, lz);
                int bottom = Math.max(MIN_Y+5, surface-96);
                for(int y=bottom; y<surface-4; y++){
                    p.set(x,y,z);
                    if(world.getBlockState(p).isOf(Blocks.WATER) && isIsolatedWater(world,x,y,z)){
                        world.setBlockState(p, Blocks.AIR.getDefaultState(), 0);
                    }
                }
            }
        }
    }

    private static boolean isIsolatedWater(StructureWorldAccess world,int x,int y,int z){
        BlockPos.Mutable n = new BlockPos.Mutable();
        return !world.getBlockState(n.set(x+1,y,z)).isOf(Blocks.WATER)
                && !world.getBlockState(n.set(x-1,y,z)).isOf(Blocks.WATER)
                && !world.getBlockState(n.set(x,y+1,z)).isOf(Blocks.WATER)
                && !world.getBlockState(n.set(x,y-1,z)).isOf(Blocks.WATER)
                && !world.getBlockState(n.set(x,y,z+1)).isOf(Blocks.WATER)
                && !world.getBlockState(n.set(x,y,z-1)).isOf(Blocks.WATER);
    }

    /** Chooses a vanilla tree placed-feature id (or null for no tree) from terrain climate, slope and soil. */
    private static Identifier treeSpecies(TerrainModel.Sample sm,TerrainSettings s,double slope){
        double h=sm.height(), w=sm.waterLevel();
        if(h<w+2) return null;                            // below the waterline
        if(sm.river()>0.2 || sm.lake()>0.2) return null;  // in a channel or lake
        if(slope>0.62) return null;                       // steep canyon walls / scree / peaks
        if(h>s.snowLine()+40) return null;                // above the tree line
        // Stripped soil (eroded slopes, scree): no trees even where the climate would allow them.
        if(sm.soil() < 0.3) return null;
        double m=sm.moisture(), t=sm.temperature();
        // Rich deposited soil triggers lush forests where the climate is warm and wet enough.
        boolean fertile = sm.soil() > 0.65;
        if(t<-.2){
            if(m<-.1) return null;
            return Identifier.ofVanilla("trees_taiga");
        }
        if(t>.35 && m>.1) return fertile
                ? Identifier.ofVanilla("trees_jungle")
                : Identifier.ofVanilla("trees_sparse_jungle");
        if(m>.25) return t>.25
                ? Identifier.ofVanilla("trees_birch")
                : Identifier.ofVanilla("trees_birch_and_oak_leaf_litter");
        if(m>.12) return Identifier.ofVanilla("trees_birch_and_oak_leaf_litter");
        if(m<-.15) return null; // desert
        if(t>.3) return Identifier.ofVanilla("trees_savanna");
        return Identifier.ofVanilla("trees_plains");
    }
    @Override public void appendDebugHudText(List<String> text,NoiseConfig noiseConfig,BlockPos pos){ TerrainModel.Sample s=TerrainModel.sample(terrainSeed(noiseConfig),pos.getX(),pos.getZ(),settings); text.add(String.format("Realistic Terrain h=%.1f river=%.2f ridge=%.2f",s.height(),s.river(),s.ridge())); }
}
