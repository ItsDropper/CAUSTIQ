# Caustiq

Caustiq is an independent, client-side Minecraft shader engine. Its primary goal is to load existing OptiFine/Iris-style shader packs while preserving compatibility with accelerated terrain renderers such as Nvidium.

## Project status

**Early development — discovery, source preprocessing, program discovery, OpenGL compilation, source-mapped diagnostics, and a uniform upload API are implemented.** Caustiq scans directory and ZIP packs, safely reads shader sources, expands includes, discovers conventional vertex/fragment pairs, compiles/links those pairs on the Minecraft render thread, and exposes typed uniform setters on linked programs.

This milestone establishes real GPU program objects and the first part of the shader-to-renderer interface. It does **not** yet draw the linked programs, replace Minecraft's world-render pipeline, or claim general Iris/OptiFine pack compatibility.

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
- Parses common NVIDIA and Mesa compiler log formats and maps source IDs/line numbers back to original include files when the driver reports them.
- Retains the original raw driver log if a vendor-specific format cannot be parsed.
- Compiles at most one program per rendered world frame to avoid compiling an entire pack in one frame.
- Releases linked GPU programs when the game renderer shuts down.

## Program uniform interface

A linked program can be bound and supplied with integer/sampler, float, vec2, vec3, vec4, and column-major mat4 uniforms. Uniform locations are cached per program. Missing or optimized-out uniforms are treated as optional and return `false`; callers must bind the program before uploading values. GPU operations and program destruction must run with Minecraft's render context current.

Automatic compilation is enabled only when exactly one structurally valid shader pack is present. With multiple packs, Caustiq reports that selection is required and does not silently choose one. Pack-selection UI/configuration is a later milestone.

## Current limitations

- Linked programs are not yet executed or connected to a post-processing/frame-graph pass.
- This is not a complete GLSL C preprocessor; macro expansion and extension-specific transformations remain compiler/compatibility-layer work.
- Shader-pack options, texture targets, shadow maps, deferred buffers, and world render stages are not implemented.
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

The build runs scanner, GLSL preprocessing, program-catalog, and compiler-diagnostic tests. The distributable JAR is written to `build/libs/`.

## Next engineering stages

1. Implement a safe render-target/frame-graph backend and execute a real post-processing pass.
2. Add shader-pack uniforms, texture targets, options, and common OptiFine/Iris compatibility semantics.
3. Add pack selection and configuration UI.
4. Add optional Sodium integration and validate Nvidium coexistence.
