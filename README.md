# Caustiq

Caustiq is an independent, client-side Minecraft shader engine. Its primary goal is to load existing OptiFine/Iris-style shader packs while preserving compatibility with accelerated terrain renderers such as Nvidium.

## Project status

**Early development — pack discovery, source preprocessing, program discovery, Minecraft GPU pipeline compilation, an executable single-input final fragment pass, pack option metadata parsing, and video-settings integration are implemented.** This is not yet broad shader-pack compatibility.

## First in-game shader smoke test

A known-purpose test pack is included at examples/caustiq-smoke-test.

1. Build Caustiq and install its development JAR in a Minecraft 1.21.11 Fabric instance.
2. Copy the caustiq-smoke-test folder into that instance's shaderpacks directory.
3. Open Options → Video Settings → Caustiq Shaders.
4. Select caustiq-smoke-test and ensure Caustiq is enabled.
5. Enter a world. The final pass should visibly rotate the scene's color channels.

The effect is intentionally unmistakable. If the world colors do not change, collect the Caustiq log lines around shader discovery, compilation, and final-pass execution. This smoke test validates only the current final color pass, not compatibility with arbitrary shader packs.

## Video settings integration

- Vanilla Minecraft exposes a conventional Caustiq Shaders button in Video Settings.
- Sodium exposes a separate Shader Packs category through its public Config API.
- Sodium's category uses a red accent (hex D9364F) and intentionally has no icon yet.
- Sodium is compile-only/optional; Caustiq does not require Sodium to launch.
- Both settings paths open the same renderer-independent Caustiq screen.

## Pack settings and metadata

- Persistent local settings are stored in config/caustiq.properties.
- The screen supports enabling/disabling Caustiq, selecting a discovered pack, rescanning, and cycling declared pack options.
- Parses bounded shaders.properties metadata, including screen, nested screen.*, sliders, and option.* declarations.
- Selected option values are applied as GLSL preprocessor defines to fragment sources at compile time.
- Directory and ZIP pack access continues to use the existing bounded, traversal-safe source provider.

The option UI currently exposes discrete declared values as cycle buttons. Continuous slider rendering, profiles, option dependencies, localized pack-defined labels, and full Iris/OptiFine option semantics remain future work.

## Nvidium and renderer compatibility

Caustiq does not replace Minecraft's chunk renderer, alter terrain vertex formats, intercept chunk submissions, or depend on Sodium APIs for rendering. The final-pass hook consumes Minecraft's main color texture after GameRenderer.renderLevel has completed. Nvidium remains responsible for producing the terrain image; Caustiq only processes the completed color result.

This is an architectural compatibility strategy, **not a verified Nvidium compatibility claim**. In-game validation with Nvidium, with Sodium, and with neither renderer integration is still required to confirm that the main color texture contains the completed accelerated frame at this injection point.

## GPU pipeline and final pass

- Discovers matching .vsh and .fsh stages by relative program name.
- Preprocesses the final fragment source and compiles it through Minecraft's GPU pipeline abstraction.
- Uses a generated fullscreen triangle vertex stage and common UV varying aliases.
- Binds common color sampler aliases to the completed main color texture.
- Renders into a separate Minecraft TextureTarget, then copies the result back after the render pass ends.
- Avoids raw framebuffer IDs, direct GL framebuffer manipulation, and terrain renderer hooks.
- Releases Caustiq-owned render targets when the game renderer shuts down.

The pack's own final vertex stage is catalogued but not yet executed. The first pass supports a single color input only; texture layouts, shadow maps, deferred buffers, and full Iris/OptiFine pipeline semantics remain unfinished. Many existing packs will not render unchanged yet.

## Development target

- Minecraft 1.21.11
- Java 21 bytecode target
- JDK 25 for the current Loom build process
- Fabric Loader 0.20.1
- Sodium 0.8.0 public config API at compile time only
- Official Mojang mappings
- No required third-party runtime mod dependencies

## Build and test

Use JDK 25 to run Gradle.

Windows: gradlew.bat build

Linux/macOS: ./gradlew build

The build runs scanner, preprocessing, metadata, program-catalog, diagnostic, and bundled smoke-pack discovery tests. The distributable JAR is written to build/libs/.

## Next stages

1. Implement profiles, true continuous sliders, option dependencies, and richer metadata semantics.
2. Execute pack-specific vertex stages and implement Iris/OptiFine compatibility uniforms/macros.
3. Add the remaining texture targets and world render stages.
4. Validate vanilla, Sodium, and Nvidium separately on Minecraft 1.21.11.
