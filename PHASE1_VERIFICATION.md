# RealisticTerrain — Phase 1 Verification (Baseline Build & Packaging)

- **Work branch**: `revamp/terrain-v2`
- **Scope of this phase**: make the four backend variants build and package correctly, and give each a
  self-describing artifact name. No terrain behaviour was changed; nothing in `src/` was touched.
- **Companion document**: `AUDIT_PHASE0.md` (the audit this phase answers).

Every row below was produced by running the command, not by reading the build script.

---

## 1. All four variants build and test green

| Command | Backend | Result |
| --- | --- | --- |
| `./gradlew clean test build` | Geological only | **BUILD SUCCESSFUL in 52s** |
| `./gradlew clean test build -PuseDml=true` | DirectML (Windows) | **BUILD SUCCESSFUL** |
| `./gradlew clean test build -PuseCuda=true` | CUDA | **BUILD SUCCESSFUL** |
| `./gradlew clean test build -PuseCpu=true` | CPU | **BUILD SUCCESSFUL in 1m 6s** |

Phase 0 recorded `./gradlew build -PuseDml=true` as **BUILD FAILED**. That is now fixed; see §3.

## 2. Artifact naming (the Phase 1 requirement)

Each variant writes a distinct artifact, and the value is repeated at runtime inside the jar:

| Artifact | Size | `build-variant.properties` | Nested ONNX Runtime |
| --- | --- | --- | --- |
| `realistic-terrain-0.3.0-geo.jar` | 425,666 B | `build_variant=geo` | none (correct) |
| `realistic-terrain-0.3.0-dml.jar` | 5,942,907 B | `build_variant=dml` | `onnxruntime-dml-1.20.0.jar` (5,535,401 B) |
| `realistic-terrain-0.3.0-cuda.jar` | 561,002,421 B | `build_variant=cuda` | `onnxruntime_gpu-1.20.0.jar` (566,137,763 B) |
| `realistic-terrain-0.3.0-cpu-ai.jar` | 94,790,818 B | `build_variant=cpu-ai` | `onnxruntime-1.20.0.jar` |

All four also nest `gson-2.11.0.jar` (298,672 B). The geo jar is byte-identical in size to the Phase 0
generic `realistic-terrain-0.3.0.jar`, which is expected: only the filename and the expanded
`build-variant.properties` changed.

**New finding (not in the Phase 0 audit):** the CUDA artifact is 561 MB, because
`onnxruntime_gpu-1.20.0.jar` from Maven Central is 566 MB — it carries CUDA native libraries for every
platform plus debug symbols. That is upstream packaging, not a mistake in this build, but it means the
CUDA flavour is not "just another 6 MB jar". The CPU-AI artifact is 94.8 MB for the same reason
(`onnxruntime-1.20.0.jar` ships every platform's binaries plus a 304 MB `.pdb`). Decide in a later phase
whether to prune both the way the DirectML jar is now pruned (see §4) or to document them as expected.

## 3. DirectML packaging fix

Phase 0: `com.microsoft.onnxruntime:onnxruntime-dml:1.0` does not exist on any repository.
Maven Central publishes only `onnxruntime` (CPU) and `onnxruntime_gpu` (CUDA); DirectML is a **NuGet
native** runtime. The reference integration ships a hand-built `libs/onnxruntime-dml.jar`.

`prepareDirectMlRuntime` now reproduces that jar automatically:

1. Downloads `Microsoft.ML.OnnxRuntime.DirectML` 1.20.0 from NuGet and verifies its SHA-256 against a
   pinned value (`e9e341daf6157f5e89413d9ad457e576b80832788916863c6427566c45f0dec9`); a mismatch fails
   the build. The verified package is cached in the Gradle user home, so `clean` does not refetch it.
2. Resolves the version-matched Maven Central Java bindings (Java classes + `onnxruntime4j_jni.dll`).
3. Emits `libs/onnxruntime-dml-1.20.0.jar` containing the Java classes, the backend-agnostic JNI
   bridge, and the DirectML `onnxruntime.dll` **substituted for** the CPU one.

No Visual Studio / Windows SDK toolchain is required, which was the reason a local build was previously
needed. The dependency is wired as a normal module coordinate resolved through the pre-existing
`flatDir { dirs 'libs' }` repository — Loom refuses to nest a raw file because a bare file exposes no
artifact capabilities. It is deliberately **not** on the compile classpath: `OnnxModel` reaches ONNX
entirely by reflection, and putting it there makes every compile task consume
`prepareDirectMlRuntime`'s output, which Gradle correctly rejects as an implicit dependency.

## 4. DirectML jar contents (verified)

`libs/onnxruntime-dml-1.20.0.jar` — 65 entries:

```
      87096 raw   36106 zipped   ai/onnxruntime/native/win-x64/onnxruntime4j_jni.dll
   14679608 raw 5351663 zipped   ai/onnxruntime/native/win-x64/onnxruntime.dll
class entries: 58
```

The `onnxruntime.dll` payload is 14,679,608 bytes, exactly the size of the DirectML DLL inside the
pinned NuGet package, so the substitution is confirmed rather than assumed.

The naive first attempt produced a 95,879,184-byte jar. Inspection showed why that was wrong: the
Maven Central jar carries a 304 MB `onnxruntime.pdb`, debug symbols for both Windows DLLs, and Linux and
macOS binaries. Keeping them would have:

- shipped a **CPU** `onnxruntime.dll` alongside the DirectML one in the same jar, leaving the loaded
  backend up to whichever native resolution won, and
- added ~90 MB of dead weight to every DirectML download.

The task now keeps only the Java classes, the Windows x64 JNI bridge, and the DirectML runtime; it also
fails the build if the JNI bridge is absent, rather than emitting a jar that cannot load.

## 5. Diagnostics opt-in (Phase 0 issue, fixed)

Phase 0: `TerrainDiagnostics` skipped itself even when invoked with exactly the documented command,
because `-Drealisticterrain.diagnostics=true` lands on the Gradle daemon, not on the forked test JVM.

`test { ... }` now forwards that property explicitly. The documented
`./gradlew test --tests '*TerrainDiagnostics*' -Drealisticterrain.diagnostics=true` therefore runs the
test it names instead of silently skipping it.

Phase 0 recorded `skip=1`. Now: `tests="1" skipped="0" failures="0" errors="0"`, in 29.7s, writing all
30 PNGs (3 maps × 3 seeds + 7 profiles) to `diagnostics/`. The test log shows real terrain, e.g.
`seed845fed: height -45..508`, `seed2a: height 16..573`. This is the first time the diagnostics
producer has actually been exercised on this branch.

## 6. Dedicated server / world validation (Phase 0 open item, now done)

Phase 0 listed this as unverified. The geological jar was loaded in a development server:

```
./gradlew runServer --args nogui          # run/server.properties: level-type=realisticterrain:realistic
```

Result: **the server started and generated a world with no errors.** Evidence from the log:

```
[Server thread/INFO] (Minecraft) Preparing level "phase1-smoke"
[Server thread/INFO] (Minecraft) Preparing spawn area: 100%
[Server thread/INFO] (Minecraft) Done (1.560s)! For help, type "help"
[main/INFO] (FabricLoader) Loading 43 mods:
        - realisticterrain 0.3.0-geo
```

- No `ERROR`, no exception, no crash report.
- The only warnings are the expected offline-mode notices and JDK native-access/`Unsafe` deprecations
  from Fabric/JOML, none of them from this mod.

The world's own `level.dat` confirms the Realistic Terrain generator was really used rather than a
silently-fallen-back vanilla one. Decompressing it yields:

```
realisticterrain:terrain      <- the chunk generator
realisticterrain:realistic    <- the world preset
realisticterrain:overworld    <- the dimension type
continental_scale             <- generator settings persisted into the world
```

So the geological backend generates chunks, persists its settings, and reloads them - this closes the
Phase 0 gap.

---

## 7. Variant helper tasks (`buildDml` / `buildCuda` / `buildCpu` / `buildAll`)

These pre-existed but were never exercised. `gradlew buildDml` was verified to **deadlock**: the outer
build holds the project lock, so the nested `gradlew build -PuseDml=true` reached `:test` and then sat
idle forever (the test JVM's CPU time froze) until killed. A nested build must also not fork its own
daemon while the outer one is alive.

Fixed by making the nested invocation explicit about its constraints:

```groovy
commandLine gradlewExecutable, 'build', '-x', 'test', '--no-daemon',
        "--console=plain", "-Puse${variant}=true"
```

Re-verified: `gradlew buildDml` now prints `BUILD SUCCESSFUL in 11s` (inner build `7s`), and because the
artifacts are now named per variant, it leaves `-cpu-ai.jar` and `-dml.jar` sitting side by side in
`build/libs/` instead of one overwriting the other.

Tests are deliberately excluded from this path. Use `gradlew test build -PuseDml=true` (the documented
command) when you want the suite to run.

Note that Gradle cannot switch backend within a single invocation, because the dependencies, the
artifact version and the `include` configuration are all decided at configuration time. That is why
these helpers delegate to a nested Gradle rather than being plain tasks.

---

## Open items handed to the next phase

1. **Mixin `refmap`** — the embedded `realisticterrain.client.mixins.json` still has no `refmap`; the
   production-client remap path is unverified. (Server-side is now proven clean, §6.)
2. **CUDA / CPU-AI artifact size** — 561 MB and 94.8 MB respectively, inherited from the published
   `onnxruntime_gpu` / `onnxruntime` jars. Decide whether to prune them the way the DirectML jar is.
3. **AI backends are not runtime-tested.** Only the geological variant has been loaded in a game; the
   DML/CUDA/CPU jars are verified to *build and package* correctly, not to *run* ONNX inference. That
   is expected while `WorldPipeline` is still a stub.
4. **`build-variant.properties`** is intended runtime metadata (it records the shipped backend) and is
   correctly expanded in every variant, so it is not development-only. No further action needed.
5. **`WorldPipeline` / `LocalTerrainProvider` remain stubs** — unchanged by this phase; that is Phase 2.
6. **CUDA was not built after the DirectML pruning change** — it passed before, and the change only
   affects the `useDml` branch, but a re-run is cheap if you want belt-and-braces confirmation.

## Files changed in this phase

| File | Change |
| --- | --- |
| `build.gradle` | `prepareDirectMlRuntime` task, backend-specific artifact naming, DML dependency wiring, resource expansion for every variant, diagnostics property forwarding |
| `.gitignore` | Ignore the generated `libs/onnxruntime-dml-*.jar` |
| `README.md` | New "Backends" section documenting the four flags, artifacts and the DirectML assembly |
| `PHASE1_VERIFICATION.md` | This document |
| `run-gradle.ps1` | Developer helper: runs Gradle and writes a readable UTF-8 log (Gradle's own output is otherwise mangled by Windows PowerShell 5.1's UTF-16 redirection) |

No file under `src/` was modified. `libs/onnxruntime-dml-1.20.0.jar` is generated, not committed.

