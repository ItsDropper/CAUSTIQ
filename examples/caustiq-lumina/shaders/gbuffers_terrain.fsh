#version 330 compatibility

/* RENDERTARGETS: 1,2,3,5 */

// Lumina - gbuffers_terrain (fragment)
//
// Nvidium rasterises the terrain; this stage lights it. The finished, tone-mapped
// colour goes to colortex0 (gl_FragColor), so the frame is already a complete image
// even if no later pass runs. The screen-space passes then refine it using:
//
//   colortex1  world normal (xyz), foliage class (w)
//   colortex2  view depth (x), emission (y), fog factor (z), vanilla AO (w)
//   colortex3  copy of the colour written to colortex0 (rgb), sun share of the
//              lighting (w) - used to prove a pixel is still untouched terrain
//   colortex5  view-space direction to the light (xyz), shadow-map visibility (w)
//
// Lighting model: hemispheric sky ambient + directional sun/moon + warm block light,
// all in linear HDR, then an ACES fit.
//
// Shadows come from a real shadow map (see shadow.vsh): a normal-offset, slope-scaled
// depth test, a blocker search to widen the penumbra with occluder distance, and a
// rotated Vogel-disk PCF. The light direction is read back out of the shadow matrices,
// so shading and shadow always agree about where the light is. Beyond the map the
// term fades to the old sky-light gate. Pixels the map says are fully lit skip the
// filter entirely.

#define EXPOSURE 1.0 // [0.6 0.8 1.0 1.2 1.5]
#define FOG_DENSITY 1.0 // [0.5 0.75 1.0 1.5 2.0]
#define TORCH_STRENGTH 1.0 // [0.5 0.75 1.0 1.5 2.0]
#define SHADOW_SAMPLES 16 // [8 12 16 24]
#define SHADOW_SOFTNESS 1.0 // [0.5 1.0 1.5 2.0]

in vec2 texcoord;
in vec4 glColor;
in vec2 lmcoord;
in vec3 worldNormal;
in vec3 worldPos;
in vec3 viewPosition;
in float foliageClass;

uniform sampler2D gtexture;
uniform sampler2D shadowtex0;

uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform mat4 shadowModelView;
uniform mat4 shadowModelViewInverse;
uniform mat4 shadowProjection;
uniform vec3 cameraPosition;
uniform vec3 fogColor;
uniform int worldTime;            // ticks, 0..23999
uniform float rainStrength;
uniform float far;
uniform float alphaTestRef;

const float PI = 3.14159265;

// ---------------------------------------------------------------- helpers ---

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), f.x),
               mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), f.x), f.y);
}

// Vanilla's celestial angle for a day fraction (0 = sunrise, 0.25 = noon).
vec3 sunDirectionFromClock(float clock) {
    float d = fract(clock - 0.25);
    float e = 0.5 - cos(d * PI) * 0.5;
    float a = d + (e - d) / 3.0;
    float phi = a * 2.0 * PI;
    return vec3(-sin(phi), cos(phi), 0.0);   // rises in +X, sets in -X
}

// ACES (Stephen Hill's fit). Input linear scene-referred, output linear display.
vec3 acesFit(vec3 c) {
    const mat3 inM = mat3(0.59719, 0.07600, 0.02840,
                          0.35458, 0.90834, 0.13383,
                          0.04823, 0.01566, 0.83777);
    const mat3 outM = mat3(1.60475, -0.10208, -0.00327,
                          -0.53108,  1.10813, -0.07276,
                          -0.07367, -0.00605,  1.07602);
    c = inM * c;
    vec3 a = c * (c + 0.0245786) - 0.000090537;
    vec3 b = c * (0.983729 * c + 0.4329510) + 0.238081;
    return clamp(outM * (a / b), 0.0, 1.0);
}

vec3 toDisplay(vec3 hdr) {
    return pow(acesFit(hdr * EXPOSURE * 0.62), vec3(1.0 / 2.2));
}

// -------------------------------------------------------------- shadows ---

vec2 vogelDisk(int i, int n, float phi) {
    float r = sqrt((float(i) + 0.5) / float(n));
    float a = float(i) * 2.39996323 + phi;
    return r * vec2(cos(a), sin(a));
}

// Visibility of the light at a surface point, 1 = lit. `coverage` reports how much
// the shadow map actually covers the point (it fades to 0 near the map's edge).
//   feet     camera-relative position (the shadow map is centred on the camera)
//   N, L     world-space normal and direction to the light
float shadowVisibility(vec3 feet, vec3 N, vec3 L, float NdotL, float phi, out float coverage) {
    coverage = 0.0;
    float res = float(textureSize(shadowtex0, 0).x);
    if (res < 64.0) return 1.0;                                  // no shadow map this frame

    // Orthographic light-space geometry, read straight from the projection:
    //   [0][0] = 1/halfExtent    [2][2] = -2/(far-near)
    float halfExtent = 1.0 / shadowProjection[0][0];
    float texel = 2.0 * halfExtent / res;                        // blocks per shadow texel
    float zPerBlock = -0.5 * shadowProjection[2][2];             // 0..1 depth per block

    float sinT = sqrt(max(1.0 - NdotL * NdotL, 0.0));
    float slope = min(sinT / max(NdotL, 0.15), 4.0);

    // Normal-offset the lookup (kills acne without peter-panning), then add a small depth bias.
    vec3 P = feet + N * (texel * (0.9 + 0.5 * slope) + 0.02);
    vec3 sc = (shadowProjection * (shadowModelView * vec4(P, 1.0))).xyz;
    vec3 uvz = sc * 0.5 + 0.5;

    float edge = max(abs(sc.x), abs(sc.y));
    coverage = 1.0 - smoothstep(0.82, 0.98, edge);
    if (coverage <= 0.0 || uvz.z >= 1.0) return 1.0;

    float receiver = uvz.z - (0.012 + texel * (0.5 + 0.5 * slope)) * zPerBlock;
    vec2 texelUv = vec2(1.0 / res);

    // 1. Blocker search over a few texels.
    float blockerSum = 0.0;
    float blockers = 0.0;
    float searchRadius = 3.5 * SHADOW_SOFTNESS;
    for (int i = 0; i < 8; i++) {
        vec2 offs = vogelDisk(i, 8, phi) * searchRadius * texelUv;
        float d = texture(shadowtex0, uvz.xy + offs).r;
        if (d < receiver) { blockerSum += d; blockers += 1.0; }
    }
    if (blockers < 0.5) return 1.0;                              // nothing between us and the light

    // 2. Penumbra grows with the receiver-to-occluder distance, in blocks.
    float gap = max(receiver - blockerSum / blockers, 0.0) / max(zPerBlock, 1e-6);
    float radius = clamp(0.8 + gap * 0.55, 0.8, 5.5) * SHADOW_SOFTNESS;

    // 3. Rotated Vogel-disk PCF.
    float lit = 0.0;
    for (int i = 0; i < SHADOW_SAMPLES; i++) {
        vec2 offs = vogelDisk(i, SHADOW_SAMPLES, phi) * radius * texelUv;
        float d = texture(shadowtex0, uvz.xy + offs).r;
        lit += step(receiver, d);
    }
    return lit / float(SHADOW_SAMPLES);
}

// ------------------------------------------------------------------- main ---

void main() {
    vec4 tex = texture(gtexture, texcoord);
    if (tex.a < alphaTestRef) discard;

    // ---- geometry ----------------------------------------------------------
    float viewDepth = -viewPosition.z;
    float dist = length(viewPosition);
    vec3 viewDir = normalize(mat3(gbufferModelViewInverse) * viewPosition);   // camera -> surface, world space

    vec3 N = normalize(worldNormal);
    float foliage = step(0.5, foliageClass);

    // ---- albedo ------------------------------------------------------------
    vec3 tint = glColor.rgb;
    float vanillaAO = clamp(glColor.a, 0.0, 1.0);
    vec3 albedo = pow(tex.rgb * tint, vec3(2.2));

    // Break up tiling on grass and leaves with a slow, low-frequency hue/value drift.
    if (foliage > 0.5) {
        float n = valueNoise(worldPos.xz * 0.11);
        albedo *= mix(vec3(0.90, 0.94, 0.86), vec3(1.10, 1.06, 1.02), n);
    }

    // ---- light inputs ------------------------------------------------------
    vec2 lm = clamp((lmcoord - 0.03125) * 1.06667, 0.0, 1.0);
    float blockL = lm.x;
    float skyL = lm.y;

    vec3 sunDir = sunDirectionFromClock(float(worldTime) / 24000.0);
    float h = sunDir.y;                                           // sun elevation, sin(angle)
    float tDay = smoothstep(0.03, 0.30, h);
    float tNight = 1.0 - smoothstep(-0.20, 0.03, h);
    float tDusk = max(1.0 - tDay - tNight, 0.0);
    float rain = clamp(rainStrength, 0.0, 1.0);

    // Direct light. The sun reddens and fades near the horizon; the moon is a cool
    // trickle opposite it.
    vec3 sunCol = mix(vec3(1.00, 0.40, 0.12), vec3(1.00, 0.95, 0.84), smoothstep(0.02, 0.45, h))
                * (3.3 * smoothstep(-0.04, 0.32, h));
    vec3 moonCol = vec3(0.30, 0.42, 0.75) * 0.26 * smoothstep(-0.05, 0.18, -h);
    // The direction the shadow map was rendered from, recovered from its view matrix
    // (view-space +z points at the light). Shading follows the map, not the clock.
    vec3 lightDir = normalize(mat3(shadowModelViewInverse) * vec3(0.0, 0.0, 1.0));
    vec3 lightCol = (sunCol + moonCol) * (1.0 - 0.90 * rain);
    float sunWeight = luma(sunCol) / max(luma(sunCol) + luma(moonCol), 1e-4);

    // Sky ambient: hemisphere between a horizon and a zenith colour that follow the clock.
    vec3 zenith = tDay * vec3(0.17, 0.33, 0.72)
                + tDusk * vec3(0.20, 0.22, 0.46)
                + tNight * vec3(0.016, 0.028, 0.075);
    vec3 horizon = tDay * vec3(0.46, 0.58, 0.76)
                 + tDusk * vec3(0.95, 0.50, 0.30) * 0.62
                 + tNight * vec3(0.035, 0.045, 0.095);
    vec3 skyAmbient = mix(horizon, zenith, 0.55);
    float hemi = N.y * 0.5 + 0.5;
    vec3 ambient = mix(horizon * 0.34, skyAmbient, hemi);
    ambient = mix(ambient, vec3(luma(ambient)) * 0.9, rain * 0.6) * (1.0 - 0.30 * rain);

    // Vanilla sky light doubles as a coarse occlusion term: caves, overhangs and
    // canopies drop it long before they go fully dark.
    float skyOcc = skyL * skyL;
    float skyGate = smoothstep(0.62, 0.96, skyL);          // fallback beyond the shadow map

    // ---- direct term -------------------------------------------------------
    float NdotL = dot(N, lightDir);

    vec3 feet = worldPos - cameraPosition;
    float phi = hash12(gl_FragCoord.xy) * 6.2831853;
    float coverage;
    float mapVis = 1.0;
    // Foliage is lit from either side, so its bias uses the light-facing normal.
    vec3 Nbias = (foliage > 0.5 && NdotL < 0.0) ? -N : N;
    float NdotLbias = max(dot(Nbias, lightDir), 0.0);
    if (NdotLbias > 0.0 || foliage > 0.5) {
        mapVis = shadowVisibility(feet, Nbias, lightDir, max(NdotLbias, 0.2), phi, coverage);
    } else {
        coverage = 1.0;
        mapVis = 0.0;
    }
    // Inside the map: the map decides, with only a deep-cave floor on sky light.
    // Outside: fall back to the coarse sky-light gate.
    float sunGate = mix(skyGate, mapVis * smoothstep(0.12, 0.45, skyL), coverage);
    float direct = max(NdotL, 0.0);
    vec3 translucency = vec3(0.0);
    if (foliage > 0.5) {
        // Wrap lighting plus a warm glow when the light is behind the leaf.
        direct = clamp((NdotL + 0.45) / 1.45, 0.0, 1.0);
        float back = pow(clamp(dot(viewDir, lightDir), 0.0, 1.0), 3.0);
        translucency = albedo * vec3(1.0, 0.95, 0.35) * back * 0.55;
    }
    vec3 sunLight = lightCol * direct * sunGate;
    vec3 sunBounce = translucency * lightCol * sunGate;

    // ---- block light -------------------------------------------------------
    // Warm, with a steep falloff. Cooler/whiter right at the source.
    float torchFall = pow(blockL, 3.6);
    vec3 torchCol = mix(vec3(1.00, 0.46, 0.16), vec3(1.00, 0.72, 0.42), pow(blockL, 10.0));
    vec3 torchLight = torchCol * torchFall * 2.9 * TORCH_STRENGTH * mix(1.0, 0.55, tDay * skyL);

    // Block-light level 14/15 only occurs on emitters themselves, so bright pixels
    // there glow (torch flames, glowstone, lava, lanterns, sea lanterns...).
    float emitter = smoothstep(0.90, 0.97, blockL);
    float brightMask = smoothstep(0.32, 0.78, max(albedo.r, max(albedo.g, albedo.b)) + 0.5 * luma(albedo));
    float emission = emitter * brightMask;

    // ---- wet sheen ---------------------------------------------------------
    vec3 halfDir = normalize(lightDir - viewDir);
    float spec = pow(max(dot(N, halfDir), 0.0), 90.0) * rain * step(0.5, N.y) * sunGate * 1.4;

    // ---- combine -----------------------------------------------------------
    float ao = mix(1.0, vanillaAO, 0.85);
    vec3 minAmbient = vec3(0.0035, 0.0042, 0.0070);

    vec3 lit = albedo * (ambient * skyOcc * ao + torchLight * mix(1.0, ao, 0.6) + minAmbient)
             + albedo * (sunLight + sunBounce)
             + albedo * emission * 2.6
             + lightCol * spec;

    float sunShare = clamp(luma(albedo * sunLight) / max(luma(lit), 1e-4), 0.0, 1.0) * sunWeight;

    vec3 display = toDisplay(lit);

    // ---- fog ---------------------------------------------------------------
    // Blend to vanilla's own fog colour so terrain meets the untouched sky exactly.
    float fogEnd = max(far * 0.25 * 0.96, 24.0);
    float x = dist / fogEnd * 2.3 * FOG_DENSITY;
    float fog = 1.0 - exp(-x * x * 0.9);
    fog = max(fog, smoothstep(0.88, 1.0, dist / fogEnd));
    fog *= 1.0 - 0.55 * emission;
    float heightHaze = exp(-max(worldPos.y - 62.0, 0.0) / 38.0);
    fog = clamp(fog * mix(0.72, 1.0, heightHaze) + (1.0 - exp(-dist * 0.0018 * FOG_DENSITY)) * 0.12 * heightHaze, 0.0, 1.0);

    // Forward-scatter: haze toward the sun picks up its colour.
    float sunDot = max(dot(viewDir, sunDir), 0.0);
    vec3 hazeTint = sunCol * pow(sunDot, 6.0) * 0.045 * (1.0 - rain);
    vec3 fogDisplay = clamp(fogColor + pow(hazeTint, vec3(1.0 / 2.2)) * 0.8, 0.0, 1.0);

    display = mix(display, fogDisplay, fog);

    // ---- outputs -----------------------------------------------------------
    gl_FragColor = vec4(display, 1.0);
    gl_FragData[0] = vec4(N, foliageClass / 4.0);
    gl_FragData[1] = vec4(viewDepth, emission, fog, vanillaAO);
    gl_FragData[2] = vec4(display, sunShare * (1.0 - fog));
    gl_FragData[3] = vec4(normalize(mat3(gbufferModelView) * lightDir), sunGate);
}
