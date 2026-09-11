# Terrain Engine Audit – RealisticTerrain

Date: 2026-09-11
Base commit: 8219fa7

## What Currently Works

- **Geological engine** (the only engine): fully functional plate-tectonic terrain generation.
- **Cellular plate tectonics**: collision margins (fold orogeny), divergent margins (rift valleys/trenches), fault proximity.
- **Hydraulic erosion**: CPU-based particle simulation with haloed regions for seamless borders.
- **River carving**: twice-domain-warped drainage field → continuous river/slope profiles → graded valley cross-sections.
- **Lake basins**: depression detection from the drainage field.
- **Climate fields**: humidity and temperature from FBM noise, altitude-corrected.
- **Biome selection**: `TerrainBiomeSource` reads the same model output as the chunk generator.
- **Tree placement**: slope, soil, and climate-aware species selection.
- **Customize screen**: profile buttons, 16 sliders, live heightmap preview.
- **Hydraulic erosion tests**: 5 unit tests verifying determinism, bounds, erosion physics, zero-intensity skip.
- **Terrain model tests**: 10 unit tests verifying sampling determinism, channel formation, water attachment, river/valley preference, cross-section geometry, slider bounds.

## What Is Incomplete

- **No AI/Diffusion engine**: The mod has no ONNX Runtime dependency, no model loading, no tensor infrastructure, and no neural inference.
- **No engine selection**: The chunk generator always uses the geological engine.
- **No engine selector in the GUI**: The customize screen only has geological settings.
- **No model asset download infrastructure**: No Hugging Face manifest, no SHA-256 validation, no model directory.
- **No hybrid terrain**: No integration of AI output with deterministic hydrology.

## What Is Fake or Incorrectly Labeled

- **Nothing**. There is no code labeled "AI," "diffusion," or "neural" anywhere in this codebase. Everything is honest procedural noise with plate-tectonic modeling.

## What Is Directly Adapted

- **ReTerraForged (MIT)**: `CellularNoise` (Voronoi plate tectonics with jittered seeds, pluggable distance metric, blended cell traits), `TerrainSettings.ControlPoints` (named continental thresholds), interpolation helpers, cache architecture concepts.
- Won-G is credited in `fabric.mod.json` under `contributors`.

## What Must Be Replaced or Added

1. **Build configuration**: Add ONNX Runtime dependency (DirectML default, CPU/CUDA optional).
2. **TerrainBackend enum**: `GEOLOGICAL`, `DIFFUSION`, `HYBRID` with Mojang codec (default to `GEOLOGICAL` for old worlds).
3. **TerrainSample record**: Shared immutable sample from any backend (elevation, temperature, moisture, continentalness, precipitation, confidence).
4. **TerrainSettings extension**: Add backend field to `TerrainSettings` with codec.
5. **ONNX Pipeline port**: Port the following from `terrain-diffusion-mc` (MIT licensed, preserve copyright):
   - `InfiniteTensor`, `MemoryTileStore`, `FloatTensor`, `TensorWindow`, `TensorFunction`, `BatchTensorFunction`
   - `WorldPipeline`, `LocalTerrainProvider`
   - `OnnxModel`, `EDMScheduler`, `GaussianNoisePatch`, `PortableRng`
   - `LaplacianUtils`, `SyntheticMapFactory`
   - `ModelAssetManager`, `PipelineModels`, `WorldPipelineModelConfig`
6. **Model download**: Pinned Hugging Face revision, download once, SHA-256 verify, atomic replace, offline error.
7. **Inference executor**: Single-threaded executor, deduplicated tile requests, LRU tile cache.
8. **Diffusion chunk generator integration**: Backend switch in `RealisticChunkGenerator`.
9. **Hybrid engine**: AI macro terrain + deterministic hydrology.
10. **Customize screen update**: Engine selector, AI-specific controls, greyed-out irrelevant settings.
11. **Tests**: Backend codec round-trip, old-world default, tile mapping, negative coordinates, cache keys, deduplication, LRU bounds, deterministic Gaussian tiles, overlap blending, chunk-border continuity, river-region continuity.
12. **Attribution**: Add `THIRD_PARTY_NOTICES.md` for MIT-adapted code.

## Next Implementation Phase

**Phase 1 ✅ (Audit complete)** – This document.

**Phase 2** – Terrain Backend Interface (enum, codec, sample record, settings update).

**Phase 3** – Port ONNX pipeline classes from terrain-diffusion-mc + build configuration.

**Phase 4** – Hybrid terrain engine.

**Phase 5** – Configuration screen update.

**Phase 6** – Tests and diagnostics.

**Phase 7** – Final verification and attribution.