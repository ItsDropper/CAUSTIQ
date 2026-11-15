#version 330 compatibility

/* RENDERTARGETS: 0,4 */

// Lumina - deferred
//
// Screen-space refinement of the lighting the terrain pass already computed.
//
// Passes run at the tail of the frame, after entities, clouds, water and particles
// have been drawn into colortex0. So every pixel is first checked against the copy
// the terrain pass stored in colortex3: if they disagree, something else was drawn
// there and the pixel is left exactly as it is. Mobs, water and sky are never touched.
//
// The sun's visibility itself is already settled by the shadow map in gbuffers_terrain;
// the passes here only add what a 10 cm-texel map cannot resolve. For untouched terrain:
//   * SSAO            - contact darkening in creases and under overhangs
//   * contact shadows - fine occlusion (grass blades, ledges, torches), ray-marched
//                       against view depth, applied only to light the map left lit
//   * a cool tint in shadow, since the remaining light is sky ambient
// and it emits a bloom source (bright pixels + emitters) into colortex4.

#ifndef SSAO_STRENGTH
#define SSAO_STRENGTH 1.0 // [0.0 0.5 1.0 1.5 2.0]
#endif
#ifndef CONTACT_SHADOWS
#define CONTACT_SHADOWS 1 // [0 1]
#endif
#ifndef BIOME_ATMOSPHERE
#define BIOME_ATMOSPHERE 1.0 // [0.0 0.5 1.0 1.5]
#endif
#ifndef BLOOM_THRESHOLD
#define BLOOM_THRESHOLD 0.78 // [0.6 0.7 0.78 0.85 0.92]
#endif

in vec2 texcoord;

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform sampler2D colortex5;

uniform mat4 gbufferProjection;
uniform mat4 gbufferModelView;
uniform vec2 viewSize;
uniform float lumBiomeTemp;   // camera biome (custom uniforms, see shaders.properties)
uniform float lumBiomeRain;

const float PI = 3.14159265;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// Interleaved gradient noise (Jimenez) - cheap, stable per pixel.
float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

float fetchDepth(vec2 uv) {
    ivec2 p = clamp(ivec2(uv * viewSize), ivec2(0), ivec2(viewSize) - 1);
    return texelFetch(colortex2, p, 0).r;
}

vec3 viewPosition(vec2 uv, float depth) {
    vec2 ndc = uv * 2.0 - 1.0;
    return vec3(ndc.x / gbufferProjection[0][0], ndc.y / gbufferProjection[1][1], -1.0) * depth;
}

vec2 projectToUv(vec3 viewPos) {
    vec4 clip = gbufferProjection * vec4(viewPos, 1.0);
    return clip.xy / clip.w * 0.5 + 0.5;
}

bool onScreen(vec2 uv) {
    return uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0;
}

// Normal-oriented hemisphere SSAO against the stored view depth.
float ambientOcclusion(vec3 P, vec3 N, float rot) {
    const int SAMPLES = 12;
    vec3 helper = abs(N.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(helper, N));
    vec3 bitangent = cross(N, tangent);

    float radius = 0.85;
    float occlusion = 0.0;
    for (int i = 0; i < SAMPLES; i++) {
        float f = (float(i) + 0.5) / float(SAMPLES);
        float angle = float(i) * 2.39996323 + rot;
        float r = sqrt(f);
        vec3 dir = tangent * (cos(angle) * r) + bitangent * (sin(angle) * r) + N * sqrt(max(1.0 - f, 0.0));
        float scale = mix(0.12, 1.0, f * f);
        vec3 S = P + N * 0.04 + dir * radius * scale;

        vec2 uv = projectToUv(S);
        if (!onScreen(uv)) continue;
        float surface = fetchDepth(uv);
        if (surface <= 0.0) continue;                 // sky: nothing there to occlude

        float sampleDepth = -S.z;
        float diff = sampleDepth - surface;           // > 0: the sample sits behind visible geometry
        float range = smoothstep(0.0, 1.0, radius / max(abs(-P.z - surface), 1e-3));
        occlusion += step(0.035 + 0.004 * sampleDepth, diff) * range;
    }
    return 1.0 - occlusion / float(SAMPLES);
}

// March toward the light through the depth buffer; 1 = fully lit, 0 = occluded.
float contactShadow(vec3 P, vec3 N, vec3 L, float jitter) {
    const int STEPS = 18;
    const float MAX_LEN = 2.8;
    vec3 origin = P + N * 0.035;
    float shadow = 0.0;
    for (int i = 0; i < STEPS; i++) {
        float t = (float(i) + jitter) / float(STEPS);
        float len = 0.05 + MAX_LEN * t * t;
        vec3 S = origin + L * len;
        vec2 uv = projectToUv(S);
        if (!onScreen(uv)) break;
        float surface = fetchDepth(uv);
        if (surface <= 0.0) continue;
        float diff = -S.z - surface;
        float thickness = 0.10 + len * 0.40;
        if (diff > 0.02 + len * 0.012 && diff < thickness) {
            shadow = max(shadow, 1.0 - smoothstep(0.55 * thickness, thickness, diff));
        }
    }
    return 1.0 - shadow;
}

void main() {
    vec4 base = texture(colortex0, texcoord);
    ivec2 pix = ivec2(texcoord * viewSize);
    vec4 g2 = texelFetch(colortex2, pix, 0);
    vec4 g3 = texelFetch(colortex3, pix, 0);

    float depth = g2.r;
    vec3 color = base.rgb;

    // Untouched terrain: the terrain pass wrote depth here and colortex0 still holds its colour.
    bool terrain = depth > 0.0 && max(abs(base.r - g3.r), max(abs(base.g - g3.g), abs(base.b - g3.b))) < 0.011;

    if (terrain) {
        vec3 Nw = texelFetch(colortex1, pix, 0).xyz;
        vec3 N = normalize(mat3(gbufferModelView) * Nw);
        vec3 P = viewPosition(texcoord, depth);
        float noise = ign(gl_FragCoord.xy);

        float sunShare = g3.a;
        float fog = g2.b;
        float aoFade = 1.0 - smoothstep(50.0, 130.0, depth);

        // Ambient occlusion, weighted toward the ambient part of the lighting.
        float ao = 1.0;
        if (SSAO_STRENGTH > 0.0) {
            ao = ambientOcclusion(P, N, noise * 2.0 * PI);
            ao = pow(clamp(ao, 0.0, 1.0), 1.5 * SSAO_STRENGTH);
            ao = mix(1.0, ao, aoFade);
        }
        float aoGain = mix(ao, 1.0, 0.40 * sunShare);

        // Contact shadows: strip the sun's share of the light where the ray is blocked.
        float shadow = 1.0;
        if (CONTACT_SHADOWS == 1 && sunShare > 0.02) {
            vec3 L = normalize(texelFetch(colortex5, pix, 0).xyz);   // view-space light, from the terrain pass
            shadow = contactShadow(P, N, L, noise);
            shadow = mix(1.0, shadow, aoFade);
        }
        float shadowed = sunShare * (1.0 - shadow);
        float sunGain = pow(max(1.0 - shadowed, 0.0), 0.55);

        float gain = sunGain * pow(aoGain, 0.75);
        gain = mix(gain, 1.0, fog);                       // distant, fogged terrain stays as it was
        color *= gain;
        // Biome atmosphere on the hazed part of the image: cold biomes cool, hot ones warm,
        // humid ones a little hazier. Camera-biome values, so the swing is deliberately small.
        float warm = smoothstep(0.15, 1.3, lumBiomeTemp);
        float humid = clamp(lumBiomeRain, 0.0, 1.0);
        vec3 biomeTint = mix(vec3(0.96, 0.99, 1.04), vec3(1.04, 1.00, 0.95), warm);
        color = mix(color, color * biomeTint, fog * 0.7 * BIOME_ATMOSPHERE);
        color = mix(color, vec3(luma(color)) * 0.92 + 0.02, fog * humid * 0.12 * BIOME_ATMOSPHERE);
        // Whatever light remains in shadow is sky light: nudge it cool.
        color *= mix(vec3(1.0), vec3(0.93, 0.975, 1.06), clamp(shadowed * 1.4 * (1.0 - fog), 0.0, 1.0));
        color = clamp(color, 0.0, 1.0);
    }

    // Bloom source: bright pixels plus block-light emitters. Works on anything in the
    // frame (the sun, lava, a glowing mob), so it uses the final colour, not the G-buffer.
    float emission = terrain ? g2.g * (1.0 - g2.b) : 0.0;
    float bright = smoothstep(BLOOM_THRESHOLD, 1.0, max(color.r, max(color.g, color.b)));
    vec3 bloomSource = color * (bright * 0.9 + emission * 1.7);

    gl_FragData[0] = vec4(color, 1.0);
    gl_FragData[1] = vec4(bloomSource, 1.0);
}
