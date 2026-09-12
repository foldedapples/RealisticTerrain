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
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.StructureSet;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.Util;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.feature.ConfiguredFeature;
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
    // Eclipse null analysis (enabled by this project's .vscode settings) flags the generic signatures
    // of RecordCodecBuilder and BiomeSource.CODEC because Mojang and DataFixerUpper ship no null
    // annotations at all, so every value they hand back counts as "unannotated" and the method
    // references below are reported as unchecked conversions. There is nothing to fix in this code -
    // a codec's group() never passes null to forGetter - so the diagnostic is suppressed at its source
    // rather than left to drown out real warnings.
    @SuppressWarnings("null")
    public static final MapCodec<RealisticChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(RealisticChunkGenerator::getBiomeSource),
            TerrainSettings.CODEC.fieldOf("settings").forGetter(RealisticChunkGenerator::settings)
    ).apply(i, RealisticChunkGenerator::new));

    private final TerrainSettings settings;
    // The one terrain seed context, owned by this generator and shared with its biome source. It is
    // initialized from NoiseConfig once (see createStructurePlacementCalculator) - before any biome
    // or terrain sampling - so no stage can ever observe an unseeded fallback. See TerrainContext.
    private final TerrainContext terrainContext = new TerrainContext();

    public RealisticChunkGenerator(BiomeSource biomeSource, TerrainSettings settings){
        super(biomeSource);
        this.settings=settings;
        // Hand the biome source the very same context, so biome classification and terrain sampling
        // read one seed rather than two independent guesses.
        if(biomeSource instanceof TerrainBiomeSource tbs) tbs.attachContext(terrainContext);
    }
    public TerrainSettings settings(){ return settings; }
    @Override protected MapCodec<? extends ChunkGenerator> getCodec(){ return CODEC; }
    @Override public int getSeaLevel(){ return settings.seaLevel(); }
    @Override public int getMinimumY(){ return MIN_Y; }
    @Override public int getWorldHeight(){ return WORLD_HEIGHT; }

    /**
     * Derives and caches the world's terrain seed from its noise configuration. Minecraft builds the
     * world's NoiseConfig once per world, and every stage that receives it derives the identical
     * value, so this is safe to call from any stage, on any thread, in any order.
     */
    private long terrainSeed(NoiseConfig noiseConfig){
        return terrainContext.initializeFrom(noiseConfig);
    }

    /**
     * World-load hook: Minecraft calls this once, from {@code ServerChunkLoadingManager}'s
     * constructor, with the world seed and the world's NoiseConfig, before any chunk is generated.
     * Establishing the terrain seed here is what guarantees it exists before both terrain and biome
     * work - the old design only seeded inside {@code populateNoise}, which runs after biomes.
     */
    @Override public StructurePlacementCalculator createStructurePlacementCalculator(
            RegistryWrapper<StructureSet> structureSetRegistry, NoiseConfig noiseConfig, long seed){
        terrainSeed(noiseConfig);
        return super.createStructurePlacementCalculator(structureSetRegistry, noiseConfig, seed);
    }

    /**
     * Biome population is its own chunk stage, earlier than noise, so the seed is established here
     * too - independently of {@code populateNoise}. The registered biome source then classifies each
     * column with the same world-derived seed the terrain uses.
     */
    @Override public CompletableFuture<Chunk> populateBiomes(NoiseConfig noiseConfig, Blender blender,
            StructureAccessor structures, Chunk chunk){
        terrainSeed(noiseConfig);
        return super.populateBiomes(noiseConfig, blender, structures, chunk);
    }

    @Override public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig, StructureAccessor structures, Chunk chunk){
        return CompletableFuture.supplyAsync(() -> {
            long seed=terrainSeed(noiseConfig);
            ChunkPos cp=chunk.getPos(); BlockPos.Mutable p=new BlockPos.Mutable();
            for(int lx=0;lx<16;lx++) for(int lz=0;lz<16;lz++){
                int x=cp.getStartX()+lx,z=cp.getStartZ()+lz;
                TerrainModel.Sample sm=TerrainModel.sample(seed,x,z,settings);
                int surface=Math.max((int)Math.floor(sm.height()),SURFACE_FLOOR);
                int waterTop=(int)Math.floor(sm.waterLevel());
                // One authoritative classification - the very same value the biome source stores in
                // the chunk. There is no second desert/beach test here any more.
                TerrainBiomeType biome=TerrainBiomeClassifier.classify(seed,x,z,settings,sm);
                int top=Math.max(surface,waterTop);
                for(int y=MIN_Y;y<=top;y++){
                    BlockState state;
                    // Guaranteed crust first: bedrock at the very bottom, deepslate above it. This
                    // is branched before the water/air case so a submerged column can never have
                    // its floor overwritten by water and open a hole down into the void.
                    if(y<=CRUST_TOP) state = y==MIN_Y?Blocks.BEDROCK.getDefaultState():Blocks.DEEPSLATE.getDefaultState();
                    else if(y>surface) state = y<=waterTop?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState();
                    else if(TerrainModel.cave(seed,x,y,z,settings) && y<surface-7) state= y<settings.seaLevel()-18?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState();
                    else state=blockFor(seed,x,z,surface,y,sm,biome);
                    chunk.setBlockState(p.set(x,y,z),state,0);
                }
            }
            Heightmap.populateHeightmaps(chunk, EnumSet.of(Heightmap.Type.OCEAN_FLOOR_WG, Heightmap.Type.WORLD_SURFACE_WG));
            return chunk;
        }, Util.getMainWorkerExecutor());
    }

    /**
     * The block at one column position, resolved by the single authoritative surface resolver. Both
     * this pass and {@link #getColumnSample} call it, so the blocks a chunk is built from and the
     * blocks a query reports can never disagree.
     */
    private BlockState blockFor(long seed,int x,int z,int surface,int y,TerrainModel.Sample sm,TerrainBiomeType biome){
        SurfaceMaterial material=TerrainSurfaceResolver.resolve(
                new TerrainSurfaceResolver.SurfaceContext(biome,sm,surface,y,x,z,seed,settings));
        return stateFor(material);
    }

    /** The single mapping from a pure {@link SurfaceMaterial} to a vanilla block state. */
    private static BlockState stateFor(SurfaceMaterial material){
        return switch(material){
            case BEDROCK -> Blocks.BEDROCK.getDefaultState();
            case DEEPSLATE -> Blocks.DEEPSLATE.getDefaultState();
            case STONE -> Blocks.STONE.getDefaultState();
            case GRAVEL -> Blocks.GRAVEL.getDefaultState();
            case SAND -> Blocks.SAND.getDefaultState();
            case SANDSTONE -> Blocks.SANDSTONE.getDefaultState();
            case RED_SAND -> Blocks.RED_SAND.getDefaultState();
            case RED_SANDSTONE -> Blocks.RED_SANDSTONE.getDefaultState();
            case CLAY -> Blocks.CLAY.getDefaultState();
            case MUD -> Blocks.MUD.getDefaultState();
            case GRASS_BLOCK -> Blocks.GRASS_BLOCK.getDefaultState();
            case DIRT -> Blocks.DIRT.getDefaultState();
            case COARSE_DIRT -> Blocks.COARSE_DIRT.getDefaultState();
            case PODZOL -> Blocks.PODZOL.getDefaultState();
            case MOSS_BLOCK -> Blocks.MOSS_BLOCK.getDefaultState();
            case SNOW_BLOCK -> Blocks.SNOW_BLOCK.getDefaultState();
            case ICE -> Blocks.ICE.getDefaultState();
            case WATER -> Blocks.WATER.getDefaultState();
            case AIR -> Blocks.AIR.getDefaultState();
            case BASALT -> Blocks.BASALT.getDefaultState();
            case GRANITE -> Blocks.GRANITE.getDefaultState();
            case DIORITE -> Blocks.DIORITE.getDefaultState();
            case TERRACOTTA -> Blocks.TERRACOTTA.getDefaultState();
        };
    }

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
        // One classification, then the one shared resolver - this column sample is what spawn
        // placement and feature generation read, so it reports exactly the blocks populateNoise wrote.
        TerrainBiomeType biome=TerrainBiomeClassifier.classify(seed,x,z,settings,sample);
        int surface=Math.max((int)Math.floor(sample.height()),SURFACE_FLOOR);
        for(int y=MIN_Y;y<top;y++){
            BlockState st;
            // Same guaranteed crust as populateNoise: this column sample is what spawn placement
            // and feature generation read, so it must never report a missing floor either.
            if(y<=CRUST_TOP) st=y==MIN_Y?Blocks.BEDROCK.getDefaultState():Blocks.DEEPSLATE.getDefaultState();
            else if(y>=terrainTop) st=y<waterTop?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState();
            else st=blockFor(seed,x,z,surface,y,sample,biome);
            states[y-MIN_Y]=st;
        }
        return new VerticalBlockSample(MIN_Y,states);
    }
    @Override public void buildSurface(ChunkRegion region,StructureAccessor structures,NoiseConfig noiseConfig,Chunk chunk) { }

    /**
     * Structure placement gate.
     *
     * <p>Structures are not placed by {@code generateFeatures} - that only runs decoration features -
     * they are placed here, when the chunk's structure starts are created. Overriding this is therefore
     * a genuine suppression: with the setting off, no start is ever created, so no structure, no
     * structure piece and no structure-specific mob spawn can appear, while terrain, ores, trees and
     * every other decoration feature are untouched. Vanilla's own global "Generate Structures" option
     * is a world-creation toggle outside the generator, so it cannot be read from here; this is the
     * generator-side gate the setting actually drives.
     */
    @Override public void setStructureStarts(DynamicRegistryManager registryManager,
            StructurePlacementCalculator placementCalculator, StructureAccessor structureAccessor,
            Chunk chunk, StructureTemplateManager templateManager, RegistryKey<World> dimension){
        if(!settings.generateStructures()) return;
        super.setStructureStarts(registryManager, placementCalculator, structureAccessor, chunk, templateManager, dimension);
    }
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
        // The context is established from NoiseConfig before any chunk stage runs, so this never
        // guesses: generation order cannot change which seed the forest is planted with.
        long seed=terrainContext.seed();
        ChunkPos cp=chunk.getPos();
        Random random=Random.create(world.getSeed() ^ (cp.x*0x9E3779B97F4A7C15L) ^ (cp.z*0xC2B2AE3D27D4EB4FL) ^ 0x5245414CL);
        DynamicRegistryManager registries=world.getRegistryManager();
        Registry<ConfiguredFeature<?, ?>> configured=registries.getOrThrow(RegistryKeys.CONFIGURED_FEATURE);
        for(int lx=0;lx<16;lx+=4) for(int lz=0;lz<16;lz+=4){
            int x=cp.getStartX()+lx, z=cp.getStartZ()+lz;
            TerrainModel.Sample sm=TerrainModel.sample(seed,x,z,settings);
            double slope=TerrainModel.geomorphSlope(seed,x,z,settings);
            TerrainBiomeType biome=TerrainBiomeClassifier.classify(seed,x,z,settings,sm);
            Identifier species=treeSpecies(biome,sm,settings,slope);
            if(species==null) continue;
            // Flat valley floors hold dense forest; steep walls and peaks hold none. The rule itself
            // lives in TerrainModel so the density slider has one definition and can be tested.
            double chance = TerrainModel.treeChance(seed, x, z, sm, slope, settings);
            if(random.nextFloat() > chance) continue;
            int y=chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE_WG,lx,lz)+1;
            configured.getOptionalValue(RegistryKey.of(RegistryKeys.CONFIGURED_FEATURE, species)).ifPresent(f ->
                    f.generate(world, this, random, new BlockPos(x, y, z)));
        }
    }

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

    /** Chooses a vanilla tree placed-feature id (or null for no tree) from the column's biome and soil. */
    private static Identifier treeSpecies(TerrainBiomeType biome,TerrainModel.Sample sm,TerrainSettings s,double slope){
        double h=sm.height(), w=sm.waterLevel();
        if(h<w+2) return null;                            // below the waterline
        if(slope>0.62) return null;                       // steep canyon walls / scree / peaks
        if(h>s.snowLine()+40) return null;                // above the tree line
        // Stripped soil (eroded slopes, scree): no trees even where the climate would allow them.
        if(sm.soil() < 0.3) return null;
        // Rich deposited soil triggers lush forests where the climate is warm and wet enough.
        boolean fertile = sm.soil() > 0.65;
        return switch(biome){
            case DESERT, BEACH, SNOWY_PLAINS, SNOWY_SLOPES, STONY_PEAKS, RIVER, LAKE,
                 OCEAN, DEEP_OCEAN -> null;
            case JUNGLE -> fertile
                    ? Identifier.ofVanilla("trees_jungle")
                    : Identifier.ofVanilla("trees_sparse_jungle");
            case SWAMP -> Identifier.ofVanilla("swamp_oak");
            case TAIGA, GROVE -> Identifier.ofVanilla("trees_taiga");
            case SAVANNA -> Identifier.ofVanilla("trees_savanna");
            case PLAINS, MEADOW -> Identifier.ofVanilla("trees_plains");
            case FOREST, DARK_FOREST -> Identifier.ofVanilla("trees_birch_and_oak_leaf_litter");
            case BIRCH_FOREST -> Identifier.ofVanilla("trees_birch");
        };
    }
    @Override public void appendDebugHudText(List<String> text,NoiseConfig noiseConfig,BlockPos pos){ TerrainModel.Sample s=TerrainModel.sample(terrainSeed(noiseConfig),pos.getX(),pos.getZ(),settings); text.add(String.format("Realistic Terrain h=%.1f river=%.2f ridge=%.2f",s.height(),s.river(),s.ridge())); }
}
