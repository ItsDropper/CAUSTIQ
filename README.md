# Caustiq

Caustiq is an independent, client-side Minecraft shader engine. Its primary goal is to load existing OptiFine/Iris-compatible shader packs while preserving compatibility with accelerated terrain renderers such as Nvidium.

## Project status

**Early development — initialization only.** The current code establishes the Fabric client entrypoint and creates the standard `.minecraft/shaderpacks` directory. It does not load, compile, or render shader packs yet.

## Development target

- Minecraft 1.21.11
- Java 21 bytecode / runtime target
- JDK 25 for the current Fabric Loom build process
- Fabric Loader 0.20.1
- Fabric Loom 1.19-SNAPSHOT
- Official Mojang mappings
- Gradle Kotlin DSL
- No Fabric API dependency

The current Loom release requires JDK 25 to run Gradle. The mod itself is compiled with `--release 21` and targets Java 21, as required for Minecraft 1.21.11. Fabric Loader is the only required mod-platform dependency. Sodium and Mod Menu integrations are intended to remain optional.

## Build

Use JDK 25 to run Gradle.

On Windows:

```powershell
.\gradlew.bat build
```

On Linux/macOS:

```bash
./gradlew build
```

The distributable JAR is written to `build/libs/`.

## Initial engineering direction

1. Shader-pack discovery and safe ZIP inspection.
2. Shader-pack format identification and compatibility reporting.
3. GLSL preprocessing and compilation.
4. A renderer abstraction that does not require Sodium.
5. Optional Sodium integration.
6. Nvidium-compatible terrain rendering integration.
7. Optional Mod Menu integration.

Nvidium coexistence and broad shader-pack compatibility are design goals, not capabilities claimed by the current initialization commit.
