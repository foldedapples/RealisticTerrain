# Realistic Terrain

A Fabric 1.21.11 world-generation mod for continent-scale mountain ranges, broad valleys, warped river networks, natural coasts, altitude-driven snow, and mountain caves.

## Status

This repository contains a working **0.2.0 alpha**. It compiles against Minecraft 1.21.11, launches on Fabric Loader 0.19.5, loads its registries, creates a `realisticterrain:realistic` world, and serializes the custom generator into `level.dat`.

The generator is intentionally source-first and experimental. Back up worlds before updating the mod; changing terrain settings after chunks exist will produce borders between old and new terrain.

## Features

- Selectable **Realistic Terrain** preset in Create World, with a **Customize** screen (eleven sliders and a live 64×64 top-down preview).
- A multi-layer terrain model, not a single flat noise expression: continent-scale landmass, regional relief, and mountain ranges are separate, independently tunable layers.
- Real gradient (Perlin-style) noise throughout, with domain warping so ranges, coastlines, and rivers follow organic, non-grid-aligned lines.
- An erosion-aware ridged multifractal for mountains - sharp, warped ridgelines with genuinely flatter valleys and flanks, instead of uniform noisy roughness.
- Warped river network that carves valleys relative to the *local* terrain (never toward a fixed elevation), narrower and shallower through steep mountainside than through open lowland.
- A smoothing pass over the finished heightmap that caps how steep any single wall can get, regardless of which layers combined to produce it.
- Biome-coordinate scaling and biome-aware surface temperature/precipitation.
- Soft snow-line probability instead of a hard horizontal cutoff.
- Mountain caves generated without a custom chunk format.
- Vanilla Nether and End dimensions.
- Normal vanilla chunk sections and serialization for the safest practical Voxy interoperability.

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

Clicking **Apply** replaces the selected preset's Overworld generator with one containing the chosen settings. The generator codec writes those values into the world's generation data, so the same world reproduces them after rejoining.

## Build

Requirements: JDK 21 and an internet connection for the first dependency download.

```bash
./gradlew build
```

The distributable jar is created in `build/libs/`.

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

- cokedoutsnail - original author
- FoldedApples - maintainer

## License

MIT
