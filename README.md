# Caustiq

Caustiq is an independent, client-side Minecraft shader engine. Its primary goal is to load existing OptiFine/Iris-style shader packs while preserving compatibility with accelerated terrain renderers such as Nvidium.

## Project status

**Early development — discovery, source preprocessing, program discovery, OpenGL compilation, source-mapped diagnostics, uniform upload, and an initial final-pass executor are implemented.** Caustiq scans directory and ZIP packs, safely reads shader sources, expands includes, discovers conventional vertex/fragment pairs, compiles those pairs on the render thread, and can execute a conventional `final.vsh` / `final.fsh` program over Minecraft's completed world color target.

The final pass renders into a Caustiq-owned color target and then blits the result back to Minecraft's target. It restores framebuffer bindings, viewport, shader program, VAO/buffer bindings, texture bindings, color/depth write masks, and the blend/depth/cull/scissor enables it changes.

## Nvidium and renderer compatibility

Caustiq does not replace Minecraft's chunk renderer, alter terrain vertex formats, intercept chunk submissions, or depend on Sodium APIs. The post-process hook runs after `GameRenderer.renderLevel` and consumes Minecraft's main color target. This is intentionally renderer-agnostic: Nvidium remains responsible for producing the terrain image, and Caustiq only processes the completed color result.

This is an architectural compatibility strategy, **not a verified Nvidium compatibility claim**. In-game validation with Nvidium and without Sodium is still required, especially to confirm the main color target contains the completed accelerated frame at this injection point.

## Development target

- Minecraft 1.21.11
- Java 21 bytecode / runtime target
- JDK 25 for the current Fabric Loom build process
- Fabric Loader 0.20.1
- Fabric Loom 1.19-SNAPSHOT
- Official Mojang mappings
- Gradle Kotlin DSL
- No third-party runtime mod dependencies

The current Loom release requires JDK 25 to run Gradle. The mod itself is compiled with `--release 21`. Fabric Loader is the only required mod-platform dependency. Sodium and Mod Menu integrations are intended to remain optional. JUnit is used only by the test source set and is not included in the mod JAR. LWJGL is supplied by Minecraft and is not bundled separately.

## Shader-pack discovery and source access

- Discovers directory packs and `.zip` archives.
- Accepts `shaders/` at the pack root or one wrapper directory below it.
- Identifies common OptiFine-style pipeline entry points.
- Inspects ZIP central-directory metadata without extracting entries.
- Rejects traversal, absolute paths, duplicate/case-colliding entries, ambiguous roots, malformed archives, and suspicious archive metadata.
- Reads bounded UTF-8 shader sources from directories and ZIP archives.
- Lists shader-relative source paths with entry-count and path-safety limits.
- Expands nested quoted/angle-bracket includes and emits source IDs for diagnostics.

## GLSL program compilation and final pass

- Discovers matching `.vsh` and `.fsh` stages by their relative program name.
- Does not guess how to pair unmatched stages; they are reported as ignored.
- Preprocesses each stage independently before compilation.
- Compiles and links through Minecraft's existing LWJGL/OpenGL runtime.
- Binds conventional `Position` and `UV0` vertex attributes for full-screen geometry.
- Parses common NVIDIA and Mesa compiler log formats and maps source IDs/line numbers back to original include files when the driver reports them.
- Compiles at most one program per rendered world frame to avoid compiling an entire pack in one frame.
- Executes a discovered `final` program after world rendering using a full-screen triangle.
- Supplies common sampler and screen-size uniforms when those uniforms exist.
- Releases linked programs and Caustiq-owned GPU resources when the game renderer shuts down.

The final pass currently targets conventional GLSL vertex inputs named `Position` and `UV0`. It supplies identity `ProjMat` / `ModelViewMat` matrices and common color sampler aliases. Existing shader packs often need additional Iris/OptiFine uniforms, macros, texture layouts, and stage semantics, so this is an execution foundation rather than broad pack compatibility.

## Current limitations

- Shader-pack options, texture targets, shadow maps, deferred buffers, and full world render stages are not implemented.
- Many existing packs require Iris/OptiFine-specific uniforms, macros, and pipeline semantics and will not compile or render unchanged.
- A successful compile does not guarantee that a pack's final vertex stage is suitable for the provided full-screen geometry.
- The pass is injected at the end of world rendering; HUD composition is intentionally outside this pass.
- Nvidium coexistence has not yet been validated in-game.

## Build and test

Use JDK 25 to run Gradle.

On Windows:

```powershell
.\gradlew.bat build
```

On Linux/macOS:

```bash
./gradlew build
```

The build runs scanner, GLSL preprocessing, program-catalog, and compiler-diagnostic tests. The distributable JAR is written to `build/libs/`.

## Next engineering stages

1. Implement shader-pack options and a typed uniform/texture binding model.
2. Add OptiFine/Iris compatibility uniforms, macros, and stage semantics.
3. Add pack selection and configuration UI.
4. Add optional Sodium integration and validate Nvidium coexistence on real hardware.
