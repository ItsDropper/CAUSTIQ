# Caustiq smoke-test shader pack

This simple final-pass shader verifies that Caustiq executes GLSL against Minecraft's completed scene color.

## Install

1. Build Caustiq and install its development JAR in a Minecraft 1.21.11 Fabric instance.
2. Copy the caustiq-smoke-test folder into the instance's shaderpacks directory.
3. Launch the game and open Options → Video Settings → Caustiq Shaders.
4. Select caustiq-smoke-test and ensure Caustiq is enabled.
5. Return to a world. The scene should show a clearly visible red/green/blue channel rotation.

The pack contains a conventional final.vsh / final.fsh pair so it is discoverable by the current program catalog. The current final-pass implementation supplies its own fullscreen-triangle vertex stage; the pack vertex file is presently required for pairing but is not executed.

## Scope

This tests only the current single-input final color pass. It does not validate gbuffers, shadow maps, deferred stages, depth textures, pack-specific uniforms, or full Iris/OptiFine compatibility.
