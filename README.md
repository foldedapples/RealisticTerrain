# Realistic Terrain

A Fabric 1.21.11 world-generation mod for continent-scale mountain ranges, broad valleys, terraced foothills, winding river corridors, natural coasts, altitude-driven snow, and mountain caves.

## Status

This repository contains a working **0.1.0 alpha**. It compiles against Minecraft 1.21.11, launches on Fabric Loader 0.19.5, loads its registries, creates a `realisticterrain:realistic` world, and serializes the custom generator into `level.dat`.

The generator is intentionally source-first and experimental. Back up worlds before updating the mod; changing terrain settings after chunks exist will produce borders between old and new terrain.

## Features

- Selectable **Realistic Terrain** preset in Create World.
- Dedicated customization screen with five world-type profiles (**Continental, Alpine, Archipelago, Rolling Hills, Canyons**), sixteen sliders in two columns, and a live geological elevation preview with contour lines and a peak/average-height readout.
- **Plate tectonics**: continents are 2D cellular tectonic plates. Collision margins fold into long mountain belts (orogeny) while divergent margins carve rift valleys and deep ocean trenches. The plate field is domain-warped and each plate's trait is blended smoothly across the margin, so plate boundaries meander instead of tracing the cell lattice and no cliff appears where two plates meet.
- **Hydrological erosion**: twice-domain-warped drainage carves dendritic river basins, and high plateaus are dissected by gully noise into steep, dramatic canyons.
- **Geological rock strata**: canyon walls expose sedimentary banding, active fault zones get granite/diorite/basalt intrusions, and deep basement rock is deepslate.
- **Tile caching & smooth interpolation**: slow continental fields are computed once per 16×16 tile, shared by all neighbour chunks, and interpolated in ReTerraForged's nested-lerp form (`lerp(lerp(a00,a10,fx), lerp(a01,a11,fx), fz)`) with a quintic S-curve (`interpQuintic`) applied to the in-cell coordinates. A nested lerp is a convex combination by construction, so the surface passes exactly through its corner values and can never overshoot them - which is what removes the repeating diamond/facet grid and the sawtooth terraces on slopes.
- **Named continental control points**: the shoreline and abyssal depth are exposed as `coast_line` and `ocean_depth` (modelled on ReTerraForged's `WorldSettings.ControlPoints`), both serialized into the world's generation data and both adjustable from the Customize screen. `coast_line` is the continentalness at which the craton baseline meets sea level, so it is the knob that trades ocean for land; the default leaves land covering roughly 59% of the surface.
- **Guaranteed world floor**: the model bottoms out on the abyssal plain well above `y = -64`, and generation always lays bedrock at `-64` with deepslate above it (solid to at least `y = -60`), so no column can open a hole into the void.
- Terrain-aware biomes pick vanilla biomes from the same tectonic height/moisture/temperature model (rift valleys get lush corridors, folded ranges get alpine meadows, rift oceans stay abyssal), with ecotone jitter that softens biome borders.
- **Slope-aware vegetation**: forests cluster in flat valley floors and canyon bottoms and vanish on steep walls, scree and peaks, scaled by the Vegetation density slider.
- Rivers enforce a fall line: corridors only carve where they run downhill inside valleys, so channels meander from headwater to coast, taper out of the folded ranges, and never slice through ridgelines - plus basin-only lakes.
- Biome-coordinate scaling and biome-aware surface temperature/precipitation.
- Soft snow-line probability instead of a hard horizontal cutoff, and mountain caves generated without a custom chunk format.
- Vanilla Nether and End dimensions, and normal vanilla chunk sections for the safest practical Voxy interoperability.

## Minecraft's height limit

Minecraft 1.21.11 rejects any dimension where `min_y + height` exceeds **2032**. With `min_y = -64`, the largest valid aligned dimension is therefore:

- Minimum build Y: `-64`
- Maximum build Y: `2031`
- Total vertical span: `2096` blocks

That is the closest legal implementation to the requested Y=2040 target. Y=2040 itself cannot be made buildable by a normal Fabric world preset without replacing core chunk/coordinate assumptions, which would also be hostile to Voxy compatibility.

## Customization

Choose **Realistic Terrain** in the World Type selector, then open **Customize**.

| Setting | Effect |
| --- | --- |
| Mountain height | Peak relief and maximum range height |
| Mountain frequency | Spacing and density of ranges |
| Ridge sharpness | Narrowness of ridgelines |
| Erosion | Strength of erosion cuts and terraces |
| River width | Width of connected river corridors |
| River frequency | Drainage-network spacing |
| River depth | Valley/canyon incision |
| Snow line | Base permanent-snow altitude |
| Biome scale | Horizontal size of vanilla biome regions |
| Sea level | Ocean and river waterline |
| Roughness | Fine terrain variation and cave threshold |
| Continental scale | Size of continents and ocean basins |
| Canyon depth | Incision of fluvial canyons into plateaus |
| Coast line | Continentalness at the shoreline: lower = more land, higher = more ocean |
| Ocean depth | How far the abyssal plain sits below sea level |

Clicking **Apply** replaces the selected preset's Overworld generator with one containing the chosen settings. The generator codec writes those values into the world's generation data, so the same world reproduces them after rejoining.

## Build

Requirements: JDK 21 and an internet connection for the first dependency download.

```bash
./gradlew build
```

The distributable jar is created in `build/libs/`.

`build` also runs `validateModMetadata`, which parses every resource JSON file and
enforces the parts of the Fabric metadata spec that make the loader reject a mod
(for example, `authors` / `contributors` must be arrays, and dependency blocks must
be objects). A malformed `fabric.mod.json` therefore fails the build with a readable
message instead of failing at game launch.

## Development test

```bash
./gradlew runServer --args nogui
```

For an automated local smoke test, set `level-type=realisticterrain:realistic` in `run/server.properties`, accept the Minecraft EULA in `run/eula.txt`, and start the development server.

## Voxy compatibility

Realistic Terrain does not replace chunk storage, sections, palettes, heightmaps, or the client renderer. Voxy can consume the resulting ordinary chunks. The unusually tall dimension increases LoD memory and generation work, so use a current Voxy build that explicitly supports Minecraft 1.21.11 and test the chosen LoD distance before distributing a pack.

## Water color note

The terrain generator creates shallow shelves and deep channels. Vanilla water tint is biome-based rather than depth-based, so exact turquoise-to-deep-blue grading belongs in a client shader or rendering module. The generator does not substitute fake water blocks that would break survival behavior or LoD renderers.

## Credits

The terrain mathematics and overall architecture are inspired by, and pay homage to, **TerraForged** and its successor **ReTerraForged** by **Won-G** (https://github.com/won-g, https://github.com/KVarens/ReTerraForged). Plate tectonics, folded orogeny belts, drainage-based erosion and terrain-aware biome placement all follow the spirit of that pioneering work.

Four specific mechanisms were ported from ReTerraForged's engine (`world.worldgen`), adapted to this mod's own 1.21.11 Fabric architecture:

| Concept | ReTerraForged source | Where it lives here |
| --- | --- | --- |
| Nested-lerp interpolation + quintic fade | `noise/module/Perlin.sample`, `noise/function/Interpolation.CURVE4`, `noise/NoiseUtil.interpQuintic` | `TerrainCache.bl` / `TerrainCache.fade` |
| Jittered-cell Worley with a pluggable distance metric | `noise/module/Worley.sample`, `noise/function/DistanceFunction` | `CellularNoise` (`DistanceFunction`, full cell jitter) |
| Domain-warped continental fields | `noise/domain/AddWarp`, `DirectionWarp`, `CompoundWarp` | `TerrainCache.PLATE_WARP`, `TerrainModel` drainage warp |
| Named continental control points | `data/preset/settings/WorldSettings.ControlPoints` | `TerrainSettings.ControlPoints` |

No ReTerraForged code was copied: that project targets the older Forge 1.18+ API and builds terrain by intercepting vanilla's `NoiseBasedChunkGenerator`, whereas this mod writes its own chunk sections from a shared 2D heightmap model. Only the mathematics and the configuration shape were carried across.

## License

MIT
