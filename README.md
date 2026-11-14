# Caustiq — Nvidium branch

This branch is the dedicated CAUSTIQ distribution target for Minecraft 1.21.11 with Sodium + Nvidium installed. It keeps the same Fabric mod ID (caustiq) as main; install only one CAUSTIQ build in a game instance. Its JAR is named caustiq-nvidium-*.jar.

## Renderer integration status

Experimental — Nvidium terrain integration is not implemented or verified.

This branch requires Sodium (>=0.8.0) and Nvidium. It retains the same caustiq mod ID as main and produces a separately named caustiq-nvidium artifact. The branch starts with explicit renderer-stack validation and the existing final-colour post-process; it does not hook Nvidium's internal terrain renderer or adapt its mesh-shader output into Iris-style render targets. A successful build or detected Nvidium installation is not proof that Nvidium's accelerated terrain path remains active while shaders run.

Nvidium uses a separate mesh-shader terrain pipeline. Full Iris-level support requires a real bridge for terrain colour/depth, gbuffers, shadow rendering, and pipeline synchronization. Do not bypass Nvidium's own shader compatibility behavior or claim support until those paths are implemented and tested in-game.

## Project status

**Early development — pack discovery, source preprocessing, program discovery, Minecraft GPU pipeline compilation, an executable single-input final fragment pass, pack option metadata parsing, and video-settings integration are implemented.** This is not yet broad shader-pack compatibility.

## Bundled shader-pack example

The build produces an installable example shader ZIP: **CAUSTIQ Stillwater**.

- Gradle task: `packageStillwater` (also included in `build`).
- Local output: `build/distributions/caustiq-stillwater-<version>.zip`.
- GitHub Actions uploads the ZIP as an artifact named `caustiq-stillwater-<commit-sha>`.
- CI validates both GLSL stages with `glslangValidator` and checks the ZIP root layout.

**Important when downloading from GitHub Actions:** GitHub wraps uploaded artifacts in an outer download ZIP. Extract that outer archive first. It contains the actual `caustiq-stillwater-<version>.zip`; copy that inner ZIP into `.minecraft/shaderpacks/`. Do not put the GitHub artifact-download ZIP itself in `shaderpacks`.

Stillwater is a deliberately restrained cinematic grade: compact highlight bloom, a soft filmic shoulder, subtle cool shadows/warm highlights, mild contrast, restrained vignette and low-level dithering. It is authored for Caustiq's current final-colour pass, not presented as a full Iris/OptiFine shader pack.

To install, copy the inner generated shader ZIP directly into `.minecraft/shaderpacks/`, launch Minecraft with Caustiq, then select **caustiq-stillwater** in Options → Video Settings → Caustiq Shaders.

## First in-game pipeline smoke test

A deliberately obvious channel-rotation test pack remains included at `examples/caustiq-smoke-test`. It is useful for diagnosing whether the final pass executes, while Stillwater is the visual example. The smoke test validates only the current final color pass, not compatibility with arbitrary shader packs.

## Video settings integration

- Vanilla Minecraft exposes a conventional Caustiq Shaders button in Video Settings.
- Sodium exposes a separate Shader Packs category through its public Config API.
- Sodium's category uses a red accent (hex D9364F) and intentionally has no icon yet.
- Sodium's public Config API is a compile-only integration; Sodium is not a required runtime dependency.
- Sodium discovers the `sodium:config_api_user` entrypoint lazily. Without Sodium, CAUSTIQ's normal Minecraft Video Settings integration remains available and no Sodium classes are loaded by CAUSTIQ's common startup path.
- CAUSTIQ detects the active renderer stack (vanilla, Sodium, or Sodium + Nvidium) through Fabric Loader mod IDs without linking common code to renderer implementation classes.
- Both settings paths open the same renderer-independent Caustiq screen.
- The Video Settings integration uses a Screen invoker for widget registration because `addRenderableWidget` is declared on `Screen`, not on the `VideoSettingsScreen` subclass.

## Pack settings and metadata

- Persistent local settings are stored in config/caustiq.properties.
- The screen supports enabling/disabling Caustiq, selecting a pack, rescanning, and cycling declared pack options.
- Parses bounded shaders.properties metadata, including screen, nested screen.*, sliders, and option.* declarations.
- Selected option values are applied as GLSL preprocessor defines to fragment sources at compile time.
- Directory and ZIP pack access continues to use the existing bounded, traversal-safe source provider.

The option UI currently exposes discrete declared values as cycle buttons. Continuous slider rendering, profiles, option dependencies, localized pack-defined labels, and full Iris/OptiFine option semantics remain future work.

## Nvidium and renderer compatibility

Caustiq does not replace Minecraft's chunk renderer, alter terrain vertex formats, intercept chunk submissions, or depend on Sodium APIs for rendering. The final-pass hook consumes Minecraft's main color texture after GameRenderer.renderLevel has completed. Nvidium remains responsible for producing the terrain image; Caustiq only processes the completed color result.

This is an architectural compatibility strategy, **not a verified Nvidium compatibility claim**. In-game validation with Nvidium, with Sodium, and with neither renderer integration is still required to confirm that the main color texture contains the completed accelerated frame at this injection point.

## GPU pipeline and final pass

- Inventories Iris/OptiFine-style program groups in pipeline order: setup, begin, shadow, shadowcomp, prepare, gbuffers, deferred, composite, and final.
- Preserves vertex, fragment, geometry, compute, and tessellation stage paths, including numbered composite/deferred programs.
- Reports invalid program shapes and unrecognized stages rather than silently treating them as fullscreen passes.
- Currently preprocesses and compiles only a standalone final fragment pass through Minecraft's GPU pipeline abstraction.
- Refuses to activate multi-pass packs until their required world-rendering pipeline is implemented, avoiding misleading partial rendering and unnecessary compilation of unused gbuffers/shadow programs.
- Uses a generated fullscreen triangle vertex stage and common UV varying aliases.
- Binds common color sampler aliases to the completed main color texture.
- Renders into a separate Minecraft TextureTarget, then copies the result back after the render pass ends.
- Avoids raw framebuffer IDs, direct GL framebuffer manipulation, and terrain renderer hooks.
- Releases Caustiq-owned render targets when the game renderer shuts down.

The pack's own final vertex stage is catalogued but not yet executed. The first pass supports a single color input only; texture layouts, shadow maps, deferred buffers, geometry-specific gbuffers injection, and full Iris/OptiFine pipeline semantics remain unfinished. Multi-pass packs such as Complementary Reimagined are detected and deliberately left inactive instead of being incorrectly rendered through the final-pass path.

## Distribution target

- Branch: nvidium
- Artifact base name: caustiq-nvidium
- Fabric mod ID: caustiq (same as main; do not install both builds together)
- Required renderer mods: Sodium and Nvidium

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

Windows: `gradlew.bat build`

Linux/macOS: `./gradlew build`

The build runs scanner, preprocessing, metadata, program-catalog, diagnostic, and bundled smoke-pack discovery tests, and generates both the distributable mod JAR and the Stillwater shader ZIP.

## Next stages

1. Implement Iris-compatible render-target allocation, depth attachments, buffer flipping, pack-defined clear/format/scale directives, and sampler binding.
2. Implement the ordered fullscreen pass graph (setup, begin, shadowcomp, prepare, deferred, composite, final), including compute dispatch where supported.
3. Integrate geometry gbuffers and shadow rendering with Minecraft 1.21.11's render stages and Sodium's vertex conventions without taking ownership of chunk submission.
4. Replace regex-only GLSL rewriting with AST-based transformations and implement the pack uniform, macro, include, and compatibility surfaces used by real packs.
5. Validate Complementary Reimagined, BSL, and other representative packs against Iris output, and benchmark vanilla, Sodium, and Nvidium separately.
