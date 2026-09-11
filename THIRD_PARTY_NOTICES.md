# Third-party notices

Realistic Terrain is MIT-licensed (see `LICENSE`). It adapts mathematics, algorithms and
configuration shapes from the projects below. No third-party project is a runtime dependency of
this mod; the adapted code has been re-implemented against the Minecraft 1.21.11 / Fabric Yarn API.

## ReTerraForged / TerraForged

- Upstream: https://github.com/racoonman2/ReTerraForged (1.20.2 branch used as the reference)
- Original: https://github.com/won-g (TerraForged)
- License: MIT
- Copyright (c) Won-G and the ReTerraForged contributors

Adapted concepts and algorithms (re-implemented, not copied verbatim, unless noted):

| Concept | ReTerraForged source | Where it lives here |
| --- | --- | --- |
| Jittered-cell Worley with a pluggable distance metric | `world/worldgen/noise/module/Worley`, `world/worldgen/noise/function/DistanceFunction` | `noise/CellularNoise` (`DistanceFunction`, full cell jitter, blended trait) |
| Nested-lerp interpolation + quintic fade | `world/worldgen/noise/module/Perlin`, `world/worldgen/noise/function/Interpolation`, `noise/NoiseUtil.interpQuintic` | `worldgen/TerrainCache.bl` / `TerrainCache.fade` |
| Domain-warped continental/cellular fields | `world/worldgen/noise/domain/AddWarp`, `DirectionWarp`, `CompoundWarp` | `worldgen/TerrainCache` (`PLATE_WARP`), `worldgen/TerrainModel` drainage warp |
| Regional heightmap with a halo, river map and terrain tile filters | `world/worldgen/heightmap`, `world/worldgen/rivermap`, `world/worldgen/terrain`, `world/worldgen/tile/filter` | `worldgen/hydro/*` (`DrainageRegion`, `PriorityFlood`, `FlowDirections`, `FlowAccumulation`, `RiverGraph`) |
| Priority-flood depression resolution, D8/D-infinity flow routing, flow accumulation, Strahler order, discharge-based channel width/depth, stream-power incision `E = K·A^m·S^n` | `world/worldgen/rivermap/river`, `world/worldgen/rivermap/RiverPopulator`, `world/worldgen/rivermap/Rivermap` | `worldgen/hydro/*` |
| Named continental control points | `data/preset/settings/WorldSettings.ControlPoints` | `worldgen/TerrainSettings` (`coast_line`, `ocean_depth`), `worldgen/TerrainSetting` |
| Tile/region caches with bounded eviction and haloed simulation | `world/worldgen/tile`, `world/worldgen/tile/filter/Erosion` | `worldgen/TerrainCache`, `worldgen/HydraulicErosion`, `worldgen/hydro/DrainageRegion` |

No ReTerraForged source file was copied wholesale. The drainage network is an independent
implementation of the published algorithms listed above, written for this mod's 2D-heightmap
architecture rather than ReTerraForged's `NoiseBasedChunkGenerator` interception.

## Minecraft / Fabric

Minecraft is © Mojang Studios. Fabric Loader / Fabric API are © their respective contributors.
Both are used under their own licenses and are not redistributed here.
