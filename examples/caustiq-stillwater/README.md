# CAUSTIQ — STILLWATER

**Stillwater** is a restrained cinematic finishing profile bundled as a real, installable Caustiq shader-pack ZIP. It is designed to make the familiar Minecraft palette feel more photographic without turning every scene orange, crushing night-time blacks, or covering the world in bloom.

## Look

- Soft highlight bloom around bright pixels, with a compact 13-tap gather.
- A gentle filmic highlight shoulder to retain separation in bright clouds, snow and emissive blocks.
- Mild midtone contrast and near-neutral colour grading.
- Cool shadow bias and a restrained warm highlight bias.
- Very subtle vignette and sub-half-step ordered dithering to reduce visible banding.

The grade is intentionally understated. The objective is coherent light and material colour, not an aggressive teal-and-orange preset.

## Install

1. Build Caustiq for Minecraft 1.21.11.
2. Copy `caustiq-stillwater-*.zip` from `build/distributions/` into `.minecraft/shaderpacks/`.
3. Launch Minecraft with Caustiq installed.
4. Open **Options → Video Settings → Caustiq Shaders**, select **caustiq-stillwater**, and enable Caustiq.

Do not extract the ZIP. Its root contains the `shaders/` directory, as expected by the shader-pack scanner.

## Compatibility and honest scope

This pack targets Caustiq's current **single-input final colour pass**. It uses the scene colour sampler and fragment UVs only. It does not pretend to implement depth-aware ambient occlusion, shadow maps, volumetric lighting, physically based materials, water refraction, or screen-space reflections; those require render targets and pipeline stages that the current Caustiq runtime does not expose yet.

The final vertex file is present for conventional program pairing. Caustiq currently generates its own fullscreen-triangle vertex stage, so the pack's vertex source is not executed.

The pack is not advertised as an Iris or OptiFine-compatible visual pack. It is an example authored for the current Caustiq runtime and is intended to exercise its real GPU pass, not just demonstrate pack discovery.

## Tuning

The constants near the top of `shaders/final.fsh` control the bloom threshold and soft knee. The bloom contribution, contrast, colour bias, vignette and dither are deliberately conservative; change them in small increments and compare bright daylight, foliage, snow, caves and night scenes.

## Visual validation

GLSL syntax is validated in CI with `glslangValidator`, and the generated ZIP is checked for the expected root layout. This validates packaging and shader compilation, not the final artistic result on every GPU. Capture in-game comparisons at the same location, time and weather before treating any visual tuning as verified.
