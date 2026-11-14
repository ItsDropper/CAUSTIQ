# Caustiq

Caustiq is an independent, client-side Minecraft shader engine. Its primary goal is to load existing OptiFine/Iris-style shader packs while preserving compatibility with accelerated terrain renderers such as Nvidium.

## Project status

**Early development — discovery, source preprocessing, program discovery, Minecraft GPU pipeline compilation, and a first executable final fragment pass are implemented.** Caustiq scans directory and ZIP packs, safely reads shader sources, expands includes, discovers conventional vertex/fragment pairs, and compiles a final fragment stage through Minecraft's GPU device.

The first executable pass draws a full-screen triangle into a Caustiq-owned texture target and copies the result back to Minecraft's main color texture. It uses Minecraft's GPU device, command encoder, render pass, texture views, and texture target APIs; it does not assume OpenGL framebuffer IDs or manually mutate global GL state.

## Nvidium and renderer compatibility

Caustiq does not replace Minecraft's chunk renderer, alter terrain vertex formats, intercept chunk submissions, or depend on Sodium APIs. The post-process hook runs after GameRenderer.renderLevel and consumes Minecraft's main color texture. Nvidium remains responsible for producing the terrain image; Caustiq only processes the completed color result.

This is an architectural compatibility strategy, **not a verified Nvidium compatibility claim**. In-game validation with Nvidium and without Sodium is still required to confirm that the main color texture contains the completed accelerated frame at this injection point.

## Development target

- Minecraft 1.21.11
- Java 21 bytecode / runtime target
- JDK 25 for the current Fabric Loom build process
- Fabric Loader 0.20.1
- Fabric Loom 1.19-SNAPSHOT
- Official Mojang mappings
- Gradle Kotlin DSL
- No third-party runtime mod dependencies

The current Loom release requires JDK 25 to run Gradle. The mod itself is compiled with --release 21. Fabric Loader is the only required mod-platform dependency. Sodium and Mod Menu integrations are intended to remain optional. JUnit is used only by the test source set and is not included in the mod JAR. Minecraft supplies the GPU backend and its rendering dependencies.

## Shader-pack discovery and source access

- Discovers directory packs and .zip archives.
- Accepts shaders/ at the pack root or one wrapper directory below it.
- Identifies common OptiFine-style pipeline entry points.
- Inspects ZIP central-directory metadata without extracting entries.
- Rejects traversal, absolute paths, duplicate/case-colliding entries, ambiguous roots, malformed archives, and suspicious archive metadata.
- Reads bounded UTF-8 shader sources from directories and ZIP archives.
- Lists shader-relative source paths with entry-count and path-safety limits.
- Expands nested quoted/angle-bracket includes and emits source IDs/source maps for diagnostics.

## GPU pipeline and final pass

- Discovers matching .vsh and .fsh stages by their relative program name.
- Preprocesses the final fragment source and compiles it through Minecraft's GPU pipeline abstraction.
- Uses a generated full-screen triangle vertex stage with common UV varying aliases (texCoord, texcoord, vTexCoord, and uv).
- Binds common color sampler aliases (DiffuseSampler, colortex0, gcolor, texture, and tex) to the completed main color texture.
- Renders into a separate Minecraft TextureTarget, then copies the result back after the render pass ends.
- Avoids raw framebuffer IDs, direct GL framebuffer manipulation, and terrain renderer hooks.
- Releases Caustiq-owned render targets when the game renderer shuts down.

This is an executable post-processing foundation, not broad shader-pack compatibility. The pack's own final vertex stage is currently catalogued but not executed; the generated vertex stage is used to provide a stable fullscreen contract. Packs requiring custom vertex varyings, non-color samplers, options, texture layouts, macros, or OptiFine/Iris-specific uniforms need the compatibility layer before they can render correctly.

GPU pipeline compilation failures currently include the program name, but the driver's diagnostic log is not yet mapped back through the preprocessor source map. The diagnostic parser and source-map tests remain available for the upcoming integration.

## Current limitations

- Shader-pack options, texture targets, shadow maps, deferred buffers, and full world render stages are not implemented.
- The first pass supports a single color input and common UV/sampler naming only.
- Many existing packs require Iris/OptiFine-specific uniforms, macros, and pipeline semantics and will not render unchanged.
- The pass is injected at the end of world rendering; HUD composition is intentionally outside this pass.
- Nvidium coexistence has not yet been validated in-game.

## Build and test

Use JDK 25 to run Gradle.

On Windows:

    .\gradlew.bat build

On Linux/macOS:

    ./gradlew build

The build runs scanner, GLSL preprocessing, program-catalog, and compiler-diagnostic tests. The distributable JAR is written to build/libs/.

## Next engineering stages

1. Add real pack uniform blocks, option parsing, and texture input mapping.
2. Execute pack-specific vertex stages and add OptiFine/Iris compatibility uniforms and macros.
3. Integrate GPU pipeline diagnostics with the preprocessor source map.
4. Add pack selection and configuration UI, then validate final-target timing with Nvidium and without Sodium on real hardware.
