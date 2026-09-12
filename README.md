# Realistic Terrain

A Fabric 1.21.11 world-generation mod for continent-scale mountain ranges, broad valleys, terraced foothills, winding river corridors, natural coasts, altitude-driven snow, and mountain caves.

## Status

This repository contains a working **0.3.0 alpha**. It compiles against Minecraft 1.21.11, launches on Fabric Loader 0.19.5, loads its registries, creates a `realisticterrain:realistic` world, and serializes the custom generator into `level.dat`.

The generator is intentionally source-first and experimental. Back up worlds before updating the mod; changing terrain settings after chunks exist will produce borders between old and new terrain.

## Features

- Selectable **Realistic Terrain** preset in Create World.
- Dedicated customization screen with seven world-type profiles (**Continental, Alpine, Archipelago, Rolling Hills, Canyons, Riverlands, Realistic Earthlike**), twenty-nine settings organised into eight category tabs (**World, Continents, Mountains, Rivers, Erosion, Climate, Surface, Advanced**), a tooltip on every setting, and a live geological elevation preview with contour lines and a peak/average-height readout.
- **Plate tectonics**: continents are 2D cellular tectonic plates. Collision margins fold into long mountain belts (orogeny) while divergent margins carve rift valleys and deep ocean trenches. The plate field is domain-warped and each plate's trait is blended smoothly across the margin, so plate boundaries meander instead of tracing the cell lattice and no cliff appears where two plates meet.
- **A real drainage network, not a river-shaped noise field**: `worldgen.hydro.Drainage` solves a priority-flood + D8 + flow-accumulation + Strahler-order network once per 512-block region and caches it. Channels are selected by accumulated catchment, so rivers are continuous, flow downhill by construction, merge where tributaries join instead of crossing, and are wider and deeper downstream because width and depth follow discharge and order rather than a slider. Lakes appear only in genuine closed basins (filled to their capped spill elevation), and wetlands only on flat, poorly drained ground. `river_density` changes how many networks exist; `river_density = 0` skips the solver entirely.
- **Hydraulic erosion**: a deterministic droplet pass deposits soil on valley floors and strips it from canyon walls, and high plateaus are dissected by gully noise into steep, dramatic canyons.
- **Geological rock strata**: canyon walls expose sedimentary banding, active fault zones get granite/diorite/basalt intrusions, and deep basement rock is deepslate.
- **Tile caching & smooth interpolation**: slow continental fields are computed once per 16×16 tile, shared by all neighbour chunks, and interpolated in ReTerraForged's nested-lerp form (`lerp(lerp(a00,a10,fx), lerp(a01,a11,fx), fz)`) with a quintic S-curve (`interpQuintic`) applied to the in-cell coordinates. A nested lerp is a convex combination by construction, so the surface passes exactly through its corner values and can never overshoot them - which is what removes the repeating diamond/facet grid and the sawtooth terraces on slopes.
- **Named continental control points**: the shoreline and abyssal depth are exposed as `coast_line` and `ocean_depth` (modelled on ReTerraForged's `WorldSettings.ControlPoints`), both serialized into the world's generation data and both adjustable from the Customize screen. `coast_line` is the continentalness at which the craton baseline meets sea level, so it is the knob that trades ocean for land; the default leaves land covering roughly 59% of the surface.
- **Guaranteed world floor**: the model bottoms out on the abyssal plain well above `y = -64`, and generation always lays bedrock at `-64` with deepslate above it (solid to at least `y = -60`), so no column can open a hole into the void.
- Terrain-aware biomes pick vanilla biomes from the same tectonic height/moisture/temperature model (rift valleys get lush corridors, folded ranges get alpine meadows, rift oceans stay abyssal), with ecotone jitter that softens biome borders.
- **Slope-aware vegetation**: forests cluster in flat valley floors and canyon bottoms and vanish on steep walls, scree and peaks, scaled by the Vegetation density slider.
- **Rivers flow downhill by construction**: the flow graph is built from the flood order, so a channel can never climb a ridge. Water is a flat plane across each channel - never a stepped ring of sand and gravel - it never rises above the surrounding fall line, and a bed that dips below sea level is always flooded to sea level.
- **Structures can genuinely be switched off**: `generate_structures` is enforced where structure starts are created, so a world with structures disabled still has terrain, ores, trees and every other decoration feature.
- **Soft climatic snow line**: altitude, climate and how exposed the slope is all bias it, with a ragged edge instead of a horizontal cutoff.
- Biome-coordinate scaling and biome-aware surface temperature/precipitation.
- **Full-range climate fields**: the temperature and humidity fbm are stretched by `CLIMATE_NORM` so they actually reach the `[-1, 1]` range the biome thresholds are stated on. Before that, a four-octave fbm only reached ~±0.6, which pushed the world towards the temperate middle of the biome table and left desert, jungle, taiga and snowy plains nearly unreachable.
- **Sand is a place, not a default**: sand/sandstone is applied only to submerged beds, to a jittered coastal band around the `coast_line` contour, and to genuine desert (hot and dry) columns. Every other land surface is grass/dirt/coarse dirt/stone/gravel as before.
- **Sealed river hydrology**: a river bed that dips below sea level is flooded up to sea level, and the "no floating water" bound holds for every channel above it.
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

The distributable jar is created in `build/libs/`. On Windows, `.\run-gradle.ps1 <tasks...>` wraps
`gradlew` and writes a readable UTF-8 log to `gradle-run.log`; Gradle's own output is otherwise mangled
by Windows PowerShell 5.1's UTF-16 redirection.

### Backends

The terrain engine ships in four flavours. Exactly one flag is used per build, and each flavour
writes a distinctly named artifact so two builds can never be confused for one another:

| Command | Backend | Artifact |
| --- | --- | --- |
| `./gradlew build` | Geological only — no ONNX Runtime is bundled at all | `realistic-terrain-<version>-geo.jar` |
| `./gradlew build -PuseDml=true` | DirectML (Windows) | `realistic-terrain-<version>-dml.jar` |
| `./gradlew build -PuseCuda=true` | CUDA | `realistic-terrain-<version>-cuda.jar` |
| `./gradlew build -PuseCpu=true` | CPU | `realistic-terrain-<version>-cpu-ai.jar` |

The AI flavours nest ONNX Runtime inside the mod jar under `META-INF/jars/` through Loom's `include`,
so there is nothing to install alongside the mod. The Java source never references ONNX types
directly - `OnnxModel` reaches them by reflection and degrades to "Runtime not available" - which is
why the geological build can carry no runtime at all.

Maven Central publishes only `onnxruntime` (CPU) and `onnxruntime_gpu` (CUDA). DirectML is distributed
as a NuGet *native* runtime, so `prepareDirectMlRuntime` assembles the missing Java artifact itself:
it takes the version-matched Maven Central Java bindings and swaps in the pinned, SHA-256-verified
DirectML `onnxruntime.dll`, keeping the backend-agnostic JNI bridge and dropping the other platforms'
binaries and debug symbols. That means `-PuseDml=true` needs **no** Visual Studio / Windows SDK
toolchain, and the result is a ~5 MB nested jar rather than ~96 MB. The package is downloaded once into
the Gradle user home and reused; `build-variant.properties` inside the jar records which backend was
shipped.

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

## Settings

Every generator option is described exactly once, in `TerrainSetting`: its JSON key, UI category, safe
range, default, whether it is an integer, and its tooltip. The customize screen's sliders and
tooltips, the codec's validation and the tests all read that one table, so a setting cannot be added
without being wired up end to end - and no slider can exist that does not affect generation.

The codec is deliberately lenient: it reads the keys it knows and ignores the rest, defaults anything
missing, still decodes the pre-0.4 nested `control_points` object, and clamps out-of-range values. A
world saved by an older build therefore loads, and a setting added by a newer build simply defaults
instead of failing the registry load.

| Category | Settings |
| --- | --- |
| World | Maximum terrain height, Sea level, Generate structures |
| Continents | Continental scale, Coast line, Ocean depth, Plate scale, Tectonic activity |
| Mountains | Mountain height, Mountain frequency, Ridge sharpness, Roughness, Mountain range width, Mountain uplift |
| Rivers | River width, River spacing, River depth, River density, Tributary density, Meander strength, Lake frequency, Wetland frequency |
| Erosion | Erosion intensity, Canyon depth |
| Climate | Snow line, Biome scale |
| Surface | Vegetation density, Cave generation |
| Advanced | Drainage region scale |

## Developer diagnostics

The terrain engine can export deterministic PNG maps so a change can be compared against the previous
one by eye - grid patterns, ring artefacts, disconnected rivers, needle peaks and region seams are all
obvious in a map and invisible in an assertion:

```bash
./gradlew test --tests '*TerrainDiagnostics*' -Drealisticterrain.diagnostics=true
```

Maps are written to `diagnostics/`, which is git-ignored. `TerrainPerformanceDiagnostic` prints
cold/warm sweep timings, per-column cost and cache sizes, and enforces no timing threshold - a
hardware-specific bound would fail the build on a slow machine for no good reason.

## Limitations

- Changing generator settings after chunks already exist produces a border between old and new terrain.
  Back up worlds before updating.
- The drainage network is solved per 512-block region. A region cannot know the upstream area that
  crosses its own border, so its rim is blended into a smooth local channel estimate over three cells.
  The field is continuous there by construction and the interior uses the real network, but a river
  crossing a region border can shift sideways slightly where the two representations meet.
- Lakes are capped at 18 blocks above their own floor, so a very large closed basin ends up as a lake
  with dry land around it rather than a lake filled to its rim.
- `drainage_scale` currently scales the channel threshold rather than the region side, so it changes
  how much water the network carries, not the cache footprint.

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
