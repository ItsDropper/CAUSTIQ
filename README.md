# Caustiq

Caustiq is an independent, client-side Minecraft shader engine. Its primary goal is to load existing OptiFine/Iris-style shader packs while preserving compatibility with accelerated terrain renderers such as Nvidium.

## Project status

**Early development — discovery, source preprocessing, program discovery, and OpenGL compilation are implemented.** Caustiq scans directory and ZIP packs, safely reads shader sources, expands includes, discovers conventional vertex/fragment pairs, and compiles/links those pairs on the Minecraft render thread.

This milestone establishes the first real GPU-facing shader stage. It does **not** yet draw the linked programs, replace Minecraft's world-render pipeline, or claim general Iris/OptiFine pack compatibility.

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

## GLSL program compilation

- Discovers matching `.vsh` and `.fsh` stages by their relative program name.
- Does not guess how to pair unmatched stages; they are reported as ignored.
- Preprocesses each stage independently before compilation.
- Compiles and links through Minecraft's existing LWJGL/OpenGL runtime.
- Includes the original source path and driver compiler/linker log in failures.
- Compiles at most one program per rendered world frame to avoid compiling an entire pack in one frame.
- Releases linked GPU programs when the game renderer shuts down.

Automatic compilation is enabled only when exactly one structurally valid shader pack is present. With multiple packs, Caustiq reports that selection is required and does not silently choose one. Pack-selection UI/configuration is a later milestone.

## Current limitations

- Linked programs are not yet executed or connected to a post-processing/frame-graph pass.
- This is not a complete GLSL C preprocessor; macro expansion and extension-specific transformations remain compiler/compatibility-layer work.
- Shader-pack options, uniforms, texture targets, shadow maps, deferred buffers, and world render stages are not implemented.
- Many existing packs require Iris/OptiFine-specific uniforms, macros, and pipeline semantics and will not compile unchanged.
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

The build runs the shader-pack scanner, GLSL preprocessor, and program-catalog tests. The distributable JAR is written to `build/libs/`.

## Next engineering stages

1. Add pack selection and configuration UI.
2. Implement a safe render-target/frame-graph backend and execute a real post-processing pass.
3. Add shader-pack uniforms, texture targets, options, and common OptiFine/Iris compatibility semantics.
4. Add optional Sodium integration and validate Nvidium coexistence.
