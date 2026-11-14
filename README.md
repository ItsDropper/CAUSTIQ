# Caustiq

Caustiq is an independent, client-side Minecraft shader engine. Its primary goal is to load existing OptiFine/Iris-style shader packs while preserving compatibility with accelerated terrain renderers such as Nvidium.

## Project status

**Early development — shader-pack discovery is implemented.** Caustiq now scans the standard `.minecraft/shaderpacks` directory for directory packs and ZIP archives, validates their layout, and reports recognized pipeline entry points. It does not compile or render shaders yet.

## Development target

- Minecraft 1.21.11
- Java 21 bytecode / runtime target
- JDK 25 for the current Fabric Loom build process
- Fabric Loader 0.20.1
- Fabric Loom 1.19-SNAPSHOT
- Official Mojang mappings
- Gradle Kotlin DSL
- No third-party runtime mod dependencies

The current Loom release requires JDK 25 to run Gradle. The mod itself is compiled with `--release 21`. Fabric Loader is the only required mod-platform dependency. Sodium and Mod Menu integrations are intended to remain optional. JUnit is used only by the test source set and is not included in the mod JAR.

## Shader-pack discovery

The scanner:

- Discovers directory packs and `.zip` archives.
- Accepts a `shaders/` directory at the pack root or one wrapper directory below it, and preserves that internal root in each descriptor.
- Identifies common OptiFine-style pipeline entry points such as `gbuffers_*`, `composite*`, `deferred*`, `shadow*`, and `final`.
- Inspects ZIP central-directory metadata without extracting or decompressing entries.
- Rejects absolute paths, traversal segments, backslash paths, duplicate/case-colliding entries, ambiguous shader roots, and malformed archives.
- Applies candidate, entry-count, nesting-depth, compressed-size, per-entry-size, total-size, and compression-ratio limits.
- Skips symbolic links and hidden top-level entries.
- Produces immutable descriptors and per-candidate diagnostics.

This is structural recognition, not a claim that all Iris extensions or shader-pack features are supported. Actual compatibility requires later preprocessing, compilation, and rendering stages.

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

The build runs the shader-pack scanner and GLSL preprocessing tests. The distributable JAR is written to `build/libs/`.

## Next engineering stages

1. Parse shader-pack metadata and user options.
2. Implement GLSL parsing/validation and compiler diagnostic mapping.
3. Establish a renderer abstraction independent of Sodium.
4. Add optional Sodium and Mod Menu integrations.
5. Develop and test Nvidium coexistence.
