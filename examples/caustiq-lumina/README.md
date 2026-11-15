# CAUSTIQ — LUMINA

**Lumina** is a physically-motivated lighting pack written for the CAUSTIQ `nvidium` branch. Unlike [Stillwater](../caustiq-stillwater/README.md), which grades a finished frame, Lumina lights the terrain itself: it runs inside Nvidium's mesh-shader terrain pass and uses the shadow map, G-buffers and uniforms that branch exposes.

> **Status: written against the runtime's real ABI, not yet seen on screen.** All seven stages compile with `glslangValidator`, and the pack passes the repo's own parser, terrain audit and Nvidium translator with zero diagnostics (`LuminaShaderPackTest`). Nobody has launched it in-game yet, so treat every number below as a starting point to tune against screenshots.

## Look

- **Real sun and moon shadows** from a 2048 px shadow map: normal-offset and slope-scaled biasing, a blocker search so penumbrae widen with occluder distance, and a rotated Vogel-disk PCF. Fully lit pixels skip the filter.
- **Dappled canopy light.** Leaves and grass are cutouts in the shadow map, and foliage gets wrap lighting plus a warm back-scatter when the light is behind it.
- **Sky-aware ambient.** A hemisphere between horizon and zenith colours that follow the world clock, with a cool shadow tint.
- **Warm block light** with a steep falloff; emitters (torches, lava, glowstone, lanterns) are detected from the light level and glow.
- **Screen-space refinement:** 12-sample SSAO and 18-step contact shadows against a linear depth buffer, then a mip-chain bloom, sun glow and light shafts in the sky only.
- **ACES tone mapping** (Hill fit), unsharp mask, FXAA, gentle grade, vignette and a temporally varying dither.
- **Foliage sway** that shares one function between the terrain and shadow stages, so shadows move with their leaves.
- **Biome atmosphere:** cold biomes cool the haze, hot ones warm it, humid ones soften it a little.

## Install

1. Build the `nvidium` branch of CAUSTIQ and install Sodium, Nvidium and the `caustiq-nvidium` JAR.
2. Copy `caustiq-lumina-*.zip` from `build/distributions/` into `.minecraft/shaderpacks/`. Do not extract it.
3. **Options → Video Settings → Caustiq Shaders**, pick **caustiq-lumina**, enable Caustiq.

## Options

| Option | Default | What it does |
|---|:-:|---|
| `EXPOSURE` | 1.0 | Overall brightness before tone mapping |
| `FOG_DENSITY` | 1.0 | Distance haze |
| `TORCH_STRENGTH` | 1.0 | Block-light intensity |
| `BIOME_ATMOSPHERE` | 1.0 | Biome-driven haze tint (0 disables) |
| `SHADOW_SAMPLES` | 16 | PCF taps: 8 / 12 / 16 / 24 |
| `SHADOW_SOFTNESS` | 1.0 | Scales the blocker search and penumbra |
| `SWAY_STRENGTH` | 1.0 | Foliage wind (0 disables) |
| `SSAO_STRENGTH`, `CONTACT_SHADOWS` | 1.0 / 1 | Screen-space occlusion and fine shadows |
| `BLOOM_STRENGTH`, `BLOOM_THRESHOLD`, `SUN_GLOW`, `LIGHT_RAYS` | | Bloom and sky effects |
| `FXAA`, `SATURATION`, `CONTRAST`, `VIGNETTE`, `GRAIN` | | Final grade |

Shadow resolution and range are set in `shaders.properties` (`shadowMapResolution=2048`, `shadowDistance=96`, about 10 texels per block). They are not in-game options because the runtime reads them when the pack loads.

## How it works

```
shadow            light-space terrain  ──►  shadow map (depth)
gbuffers_terrain  forward-lit terrain  ──►  colortex0  lit colour
                                            colortex1  world normal, foliage class
                                            colortex2  view depth, emission, fog, vanilla AO
                                            colortex3  copy of colortex0, sun share
                                            colortex5  view-space light direction, sun gate
deferred          SSAO + contact shadows, bloom source ──► colortex0, colortex4
composite         bloom, sun glow, light shafts
final             FXAA, grade, dither
```

Fullscreen passes run at the end of the frame, after entities, water, clouds and particles are already in `colortex0`. A naive deferred relight would therefore corrupt them, so the terrain pass lights its own pixels and stores a copy in `colortex3`. `deferred` only touches a pixel when `colortex0` still matches that copy; mobs, water and sky pass through untouched.

The light direction is read back from the shadow view matrix rather than recomputed from the clock, so shading and shadows can never disagree about where the light is.

## Limits

- **Overworld only.** Nether and End are not tuned.
- **Entities, water and particles are not shadowed or relit** by the pack (see above); they keep vanilla lighting.
- **No shadow-map texel snapping.** The map is centred on the camera in the runtime, so very fine shadow edges can shimmer slightly as you move. The penumbra filtering hides most of it; raising `SHADOW_SOFTNESS` hides more.
- **Shadows end at 96 blocks** and fade into a sky-light gate before that.
- **No previous-frame history yet.** The runtime exposes previous-frame matrices, but Lumina does not use them, so there is no TAA; FXAA does the anti-aliasing.
- **Biome uniforms follow the camera's biome**, so the atmosphere shifts when you cross a biome border.
- **Terrain stages avoid `#if` blocks on purpose**, so the terrain audit can prove the shader interface. Options are plain `#define`s.

## Tuning

Change one thing at a time and compare the same place, time and weather: a forest at noon, a cave mouth, a snowy plain at dusk, a torch-lit room at night. Start with `EXPOSURE`, `SHADOW_SOFTNESS` and `TORCH_STRENGTH`.
