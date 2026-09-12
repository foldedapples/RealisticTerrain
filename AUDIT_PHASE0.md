# RealisticTerrain — Phase 0 Audit (Truth Before Features)

- **Repository**: https://github.com/foldedapples/RealisticTerrain
- **Branch audited**: `main` @ `8f6ef14c9e3a1d0fdc2cba0a1d3b6956ded31a32` ("fixed bugs revamped code")
- **Work branch**: `revamp/terrain-v2`
- **Audit date**: 2026-09-11
- **Toolchain observed**: Minecraft 1.21.11, Fabric Loader 0.19.5, Fabric API 0.141.6+1.21.11,
  Yarn 1.21.11+build.6, Loom 1.15.5, Gradle 9.2.0, JVM Temurin 25.0.4.1 (project compiles with
  `options.release = 21`).

This document records what is **verified by execution** versus what is claimed. Nothing here is
copied from a README; every line is backed by a command that was run or a source file that was read.

---

## 0. Baseline commands and results (executed)

| Command | Result |
| --- | --- |
| `git status` | Clean, on `main` before branch creation |
| `git log --oneline -25` | Latest = `8f6ef14 fixed bugs revamped code` |
| `./gradlew clean test build` | **BUILD SUCCESSFUL in 45s** |
| Test totals (from `build/test-results/test/*.xml`) | **61 tests, 0 failures, 0 errors, 1 skipped** |
| `jar tf build/libs/realistic-terrain-0.3.0.jar` | Valid Fabric mod layout (see §5) |
| `./gradlew build -PuseDml=true` | **BUILD FAILED** — see §4 |

Per-suite baseline (all green):

```
BiomeDistributionTest          tests=3  fail=0
DrainageTest                   tests=7  fail=0
HydraulicErosionTest           tests=4  fail=0
MountainShapeTest              tests=2  fail=0
PlateTectonicsTest             tests=6  fail=0
SettingsAffectGenerationTest   tests=3  fail=0
TerrainDiagnostics             tests=1  fail=0  skip=1   (opt-in, needs -Drealisticterrain.diagnostics=true)
TerrainModelTest               tests=23 fail=0
TerrainPerformanceDiagnostic   tests=2  fail=0
TerrainSettingsCodecTest       tests=10 fail=0
TOTAL                          tests=61 fail=0 err=0 skip=1
```

The geological (no-AI) build and its tests are genuinely healthy. The problems are all in the AI
path, the engine plumbing, the UI surface, and packaging.

---

## 1. Fully working (verified)

- **Geological terrain engine** — `TerrainModel` + `TerrainCache` + `PlateTectonics` +
  `HydraulicErosion` + `hydro/Drainage`. Deterministic, tested, and it is what actually generates
  chunks today.
- **Regional drainage solver** (`worldgen/hydro/Drainage`) — priority-flood + D8 + flow
  accumulation + Strahler order, solved per region and cached. `DrainageTest` verifies determinism,
  order-independence, **and** concurrency equality.
- **Chunk generator for the geological engine** — `RealisticChunkGenerator.populateNoise` writes
  real terrain, water, caves, bedrock crust, surfaces.
- **Biome source** — `TerrainBiomeSource` reads the same model as the generator.
- **World preset + registration** — `data/realisticterrain/worldgen/world_preset/realistic.json`,
  `data/realisticterrain/dimension_type/overworld.json`, `minecraft:tags/worldgen/world_preset/normal`
  tag, `RealisticTerrainMod` registry hooks.
- **Customize screen** — `RealisticTerrainScreen` (profiles, 8 category tabs, descriptor-driven
  sliders, tooltips, live heightmap preview).
- **Settings codec** — `TerrainSettings.CODEC`, lenient, clamps, legacy `control_points` migration.
  `TerrainSettingsCodecTest` covers it.
- **Metadata validation task** — `validateModMetadata` runs on `check` and validates resource JSON.
- **Model asset manifest plumbing** — `build.gradle:generateModelAssetManifest` pins a Hugging Face
  revision and records SHA-256/size/URL; `ModelAssetManager` downloads, verifies SHA-256, and
  atomically replaces. (Runtime download path is unverified — see §6.)

## 2. Partially implemented

- **Terrain engine selection** — `TerrainEngine` enum exists (`GEOLOGICAL`/`DIFFUSION`/`HYBRID`),
  is stored in `TerrainSettings`, is encoded/decoded by the codec, and defaults to `GEOLOGICAL`.
  **But the value is never read during generation** (§3). The enum is a label with no consumer.
- **Diffusion scaffolding** — ~20 classes ported (`WorldPipeline`, `LocalTerrainProvider`,
  `OnnxModel`, `InfiniteTensor`, `MemoryTileStore`, `FloatTensor`, `TensorWindow`,
  `EDMScheduler`, `GaussianNoisePatch`, `PortableRng`, `LaplacianUtils`, `SyntheticMapFactory`,
  `WorldPipelineModelConfig`, …). They compile, but they do not form a working pipeline (§4).
- **Structures** — `setStructureStarts` override genuinely suppresses starts when
  `generate_structures = 0`. Interaction with the vanilla global "Generate Structures" option is
  **unverified in a live world** (§6).
- **AI build variants** — flags exist in `build.gradle`, but the DirectML variant cannot resolve its
  dependency (§4); CPU/CUDA are unverified here.

## 3. Stubbed / not connected

- **`settings.engine()` is never called.** Exhaustive search: the only match for `engine()` is its
  own declaration in `TerrainSettings.java:233`. `RealisticChunkGenerator` always calls
  `TerrainModel.sample(...)` regardless of the engine. ⇒ Selecting DIFFUSION or HYBRID today would
  silently produce **geological** terrain. This is a false feature claim and must be fixed (Phase 3).
- **`LocalTerrainProvider` is never instantiated.** No call to `LocalTerrainProvider.init(...)`
  exists anywhere. The whole diffusion package is dead code on the generation path.
- **The engine selector is absent from the Customize screen.** `RealisticTerrainScreen` builds only
  profile buttons, category tabs and sliders. There is no widget that sets `engine`, and the preset
  JSON contains no `engine` field. (Phase 7)
- **`TerrainSample`** — a shared record exists but **no backend produces it**; the generator still
  consumes `TerrainModel.Sample`. It is an unused contract. (Phase 3)
- **`ScaledBiomeSource`** — registered and referenced by the customize screen, but the shipped preset
  uses `realisticterrain:terrain`; the scaled source is effectively legacy.

## 4. Broken (verified by execution)

- **DirectML build variant is broken.** `build.gradle` declares
  `com.microsoft.onnxruntime:onnxruntime-dml:1.0`, which does not exist on Maven Central, FabricMC
  maven, or `libs/`. `./gradlew build -PuseDml=true` fails:

  ```
  > Could not resolve all files for configuration ':compileClasspath'.
     > Could not find com.microsoft.onnxruntime:onnxruntime-dml:1.0.
  BUILD FAILED in 2s
  ```

  The correct artifact family is `com.microsoft.onnxruntime:onnxruntime-directml:<version>`
  (and `onnxruntime_gpu` / `onnxruntime` for CUDA / CPU). (Phase 1)
- **`WorldPipeline` is a hollow shell.** The constructor hard-codes every model and tensor field to
  `null` and never builds the `InfiniteTensor` graph:

  ```java
  this.coarseModel = null; // models will set these
  this.baseModel    = null;
  this.decoderModel = null;
  this.coarse  = null;
  this.latents = null;
  this.residual= null;
  ```

  So even if the diffusion path were reachable, it would NPE immediately. (Phase 2)
- **`LocalTerrainProvider.computeTerrain()` returns placeholder terrain.** Every cell is set to
  `elev[r][c] = -100` ("ocean default"); the climate tensor is a zero-filled `float[4][H*W]`. It never
  calls `WorldPipeline`. This is a constant-height stub. (Phase 2)
- **`OnnxModel` uses a reflection facade.** It loads ONNX Runtime reflectively and *can* run a single
  output, but nothing constructs it from the generation path, and `WorldPipeline` never feeds it.
  It "compiles but silently skips inference". (Phase 2)

## 5. Packaging status (verified)

`build/libs/realistic-terrain-0.3.0.jar` (425,666 bytes) contains:

- `fabric.mod.json`, `realisticterrain.client.mixins.json`
- `assets/realisticterrain/lang/en_us.json`, `build-variant.properties`
- All `com/cokedoutsnail/realisticterrain/**` classes
- `data/minecraft/tags/worldgen/world_preset/normal.json`
- `data/realisticterrain/dimension_type/overworld.json`
- `data/realisticterrain/worldgen/world_preset/realistic.json`
- `META-INF/jars/gson-2.11.0.jar` (nested Fabric jar)

Verdict: it is a **valid, self-contained Fabric mod JAR** for the geological build. No ONNX native
libraries are bundled in the geo variant (correct). Model weights (≈2.5 GB) are **not** bundled —
they are downloaded to `<gameDir>/realisticterrain-models` from the pinned revision (correct).

Notes to verify further in Phase 1:
- The embedded mixin config has **no `refmap`**; production-client remap behaviour must be confirmed.
- `build-variant.properties` is written into the JAR; confirm it is intended runtime metadata and not
  a development-only file.
- Artifact naming is generic (`realistic-terrain-0.3.0.jar`); Phase 1 requires explicit
  `-geo` / `-dml` / `-cuda` / `-cpu-ai` names.

## 6. Unverified (needs a live run)

- Dedicated-server launch and world creation with the custom preset.
- Reopening a saved world without codec/registry failure.
- Cross-region river continuity in an actual generated world (unit-tested, not world-tested).
- Whether the vanilla global "Generate Structures" option fully disables structures in combination
  with the generator-side `generate_structures` toggle.
- The diffusion model download/verify path end-to-end (manifest fetch, SHA-256, corrupt rejection).
- CUDA build (large native dependency) on this machine.
- Client customize screen actually opening from the preset.

## 7. README / claim accuracy

The README describes only the geological stack and does not (currently) advertise working AI. That is
honest. However:

- `TERRAIN_ENGINE_AUDIT.md` (dated 2026-09-11, base `8219fa7`) still claims "**No AI/Diffusion
  engine**" — stale, because commit `8f6ef14` added the (non-functional) diffusion scaffolding.
- No user-facing documentation currently warns that `DIFFUSION`/`HYBRID` selections are inert.
  Until Phase 2–3 land, those options must be labelled experimental/unavailable.

---

## Verdict

| Area | State |
| --- | --- |
| Geological terrain | **Working** |
| Drainage/rivers (geological) | **Working** |
| Biomes (geological) | **Working** |
| Preset + registration + codec | **Working** |
| Geo build + JAR | **Working** |
| Engine selection plumbing | **Not connected** (dead value) |
| Engine selector UI | **Missing** |
| Diffusion pipeline | **Broken/Stubbed** (all-null, constant `-100`) |
| `TerrainSample` backend contract | **Unused** |
| DirectML build | **Broken** (unresolvable dependency) |
| CPU/CUDA builds | **Unverified** |
| Structures gating | **Partially verified** (unit-level only) |

**No false feature will be preserved.** Each item above is either implemented or explicitly marked
experimental/unavailable in later phases.

