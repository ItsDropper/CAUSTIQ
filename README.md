# Caustiq — Nvidium branch

This branch is the dedicated CAUSTIQ distribution target for Minecraft 1.21.11 with Sodium + Nvidium installed. It keeps the same Fabric mod ID (caustiq) as main; install only one CAUSTIQ build in a game instance. Its JAR is named caustiq-nvidium-*.jar.

## Renderer integration status

Experimental — Nvidium terrain integration is not implemented or verified.

This branch pins Nvidium 0.4.3-beta13-1.21.11 and Sodium 0.8.12. The Nvidium-specific mixin targets that exact Nvidium renderer method surface; update the pin and verify mixin application before upgrading Nvidium. Update both dependency pins together when moving to another Nvidium release. It retains the same caustiq mod ID as main and produces a separately named caustiq-nvidium artifact. The branch starts with explicit renderer-stack validation and the existing final-colour post-process; it does not hook Nvidium's internal terrain renderer or adapt its mesh-shader output into Iris-style render targets. A successful build or detected Nvidium installation is not proof that Nvidium's accelerated terrain path remains active while shaders run.

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

The integration observes Nvidium's actual `NvidiumWorldRenderer.renderFrame` and `renderTranslucent` entrypoints and intercepts Nvidium's `Shader.Builder.compile()` boundary. When Nvidium is present, CAUSTIQ extends the native mesh/fragment interpolant interface with raw vertex tint, lightmap UV, world position, and a geometric face normal while preserving Nvidium's existing UV and lit-colour values. This is a native varying extension, not execution of Iris shader-pack code. Nvidium's `MixinRenderSectionManager` still owns terrain submission and binds each Sodium `TerrainRenderPass`; CAUSTIQ's final pass remains after `GameRenderer.renderLevel`. In-game validation is still required for mixin application, shader linking, visual correctness, and performance.

Nvidium's terrain backend is a GPU-driven mesh-shader renderer integrated into Sodium. The new varying profile exposes only data recoverable from Nvidium's packed terrain vertices; its normal is a per-triangle geometric normal, not an interpolated model-space vertex normal. Full Iris-level support still requires translating pack GLSL, binding Iris uniforms/textures, providing compatible colour/depth targets, and integrating gbuffers, shadows, and pass synchronization.

## GPU pipeline and final pass

- Inventories Iris/OptiFine-style program groups in pipeline order: setup, begin, shadow, shadowcomp, prepare, gbuffers, deferred, composite, and final.
- Preserves vertex, fragment, geometry, compute, and tessellation stage paths, including numbered composite/deferred programs.
- Reports invalid program shapes and unrecognized stages rather than silently treating them as fullscreen passes.
- Currently preprocesses and compiles only a standalone final fragment pass through Minecraft's GPU pipeline abstraction.
- Refuses to activate multi-pass packs until their required world-rendering pipeline is implemented, avoiding misleading partial rendering and unnecessary compilation of unused gbuffers/shadow programs.
- Uses a generated fullscreen triangle vertex stage and common UV varying aliases.
- Binds common color sampler aliases to the completed main color texture.
- Renders into a separate Minecraft TextureTarget, then copies the result back after the render pass ends.
- Keeps ownership of chunk submission and Nvidium rasterization in Nvidium; CAUSTIQ's compile-boundary mixin only adapts the native shader interface.
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
- Sodium 0.8.12 public config API at compile time only (Sodium itself is required at runtime)
- Nvidium 0.4.3-beta13-1.21.11 required at runtime
- Official Mojang mappings

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

## Nvidium shader compilation bridge

The Nvidium branch hooks `Shader.Builder.compile()` and transforms the TASK/MESH/FRAGMENT source set before OpenGL linking. The pinned Nvidium 0.4.3-beta13-1.21.11 adapter adds a matched mesh-output/fragment-input ABI for raw vertex tint, lightmap UV, world position, and a geometric face normal. The existing UV and pre-lit colour varyings are preserved. It disables Nvidium's optional fragment-barycentric define so the interpolant interface is actually emitted; this trades some of that optimization for the extra data. The adapter fails closed if its expected upstream shader anchors change, and is installed only when Nvidium is detected.

This native varying adapter is not an Iris pack translator. Nvidium's Iris opt-out remains active because CAUSTIQ has not yet translated any Iris `gbuffers_terrain` program into Nvidium's custom mesh-shader pipeline. The extra normal is reconstructed from the triangle's transformed packed positions, with degenerate triangles guarded; the format does not contain smooth vertex normals. Arbitrary pack uniforms, texture bindings, multiple render targets, depth, shadows, deferred passes, and pass synchronization remain unsupported. CI downloads the exact pinned Nvidium source revision and runs the adapter against its real task/mesh/fragment shaders, in addition to unit tests and JAR packaging checks.
