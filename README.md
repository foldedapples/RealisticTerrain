# Realistic Terrain — Fabric 1.21.11

A source-first Fabric worldgen mod prototype for large, erosion-inspired terrain.

## Implemented
- Custom selectable world preset: **Realistic Terrain**.
- Maximum legal upper terrain coordinate for vanilla 1.21.11: Y=2031 (`min_y=-64`, total span 2096).
- Large macro continents, mountain masks, ridged peaks, erosion channels and terraced foothills.
- Wide warped river corridors that cut terrain below local height.
- Snow-line gradient and temperature-assisted snow.
- Beaches/coastal shelves, exposed stone on sharp ridges, caves integrated into the base fill.
- Serialized generator parameters via codec.
- Dedicated customization GUI and live top-down preview.
- Ordinary vanilla chunk/section storage; no Voxy-specific storage hooks.

## Important implementation status
This archive is an **alpha source implementation**, not a claimed production-tested release. The execution environment used to generate it has no outbound Maven/GitHub access, so Gradle dependencies could not be downloaded and the project could not be compiled against the live 1.21.11 mappings here.

The remaining integration item to validate during the first local compile is applying the GUI's `PENDING` `TerrainSettings` back into `WorldCreator`'s selected `DimensionOptions`. The preset and generator default values work data-driven; the screen and preview are present, but the Apply button currently stores the selected values client-side until that adapter is wired against the exact 1.21.11 `DimensionOptionsRegistryHolder` mutation API.

## Build
Requirements: JDK 21 and internet access for Gradle dependencies.

```bash
./gradlew build
```

Output: `build/libs/realistic-terrain-0.1.0.jar`

## Voxy
The generator writes normal `Chunk` sections and does not replace chunk storage/rendering. This is deliberately safer for Voxy. Extended vertical span must still be tested with the exact Voxy build you intend to ship because Voxy itself can impose implementation-specific limits independent of vanilla's world format.

## Water color
Depth-based turquoise-to-blue water cannot be represented faithfully by server worldgen alone because vanilla water tint is biome-based, not per-block depth based. This project shapes shallow/deep channels correctly. Exact depth-graded coloration should be supplied by a companion client renderer/resource-pack/shader module rather than baking non-vanilla water blocks into world data.
