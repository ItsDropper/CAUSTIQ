#version 330 compatibility

/* RENDERTARGETS: 0 */

// Lumina - composite
//
//   * bloom      - a wide, soft glow gathered from colortex4's mip chain
//   * sun glow   - forward-scattering halo around the sun, drawn on the sky
//   * light rays - a short radial march through the sky mask toward the sun
//
// Sky pixels are the ones with no terrain depth in colortex2. The vanilla sky, sun,
// moon, stars and clouds are kept; this only adds light on top of them.

#ifndef BLOOM_STRENGTH
#define BLOOM_STRENGTH 1.0 // [0.0 0.5 1.0 1.5 2.0]
#endif
#ifndef SUN_GLOW
#define SUN_GLOW 1.0 // [0.0 0.5 1.0 1.5]
#endif
#ifndef LIGHT_RAYS
#define LIGHT_RAYS 1 // [0 1]
#endif

in vec2 texcoord;

uniform sampler2D colortex0;
uniform sampler2D colortex2;
uniform sampler2D colortex4;

uniform mat4 gbufferProjection;
uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform vec2 viewSize;
uniform float lumClock;
uniform float lumRain;

const float PI = 3.14159265;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

vec3 sunDirectionFromClock(float t) {
    float d = fract(t - 0.25);
    float e = 0.5 - cos(d * PI) * 0.5;
    float a = d + (e - d) / 3.0;
    float phi = a * 2.0 * PI;
    return vec3(-sin(phi), cos(phi), 0.0);
}

bool isSky(vec2 uv) {
    ivec2 p = clamp(ivec2(uv * viewSize), ivec2(0), ivec2(viewSize) - 1);
    return texelFetch(colortex2, p, 0).r <= 0.0;
}

// Four bilinear taps per mip level, offset to even out the box-filter blockiness.
vec3 bloomLevel(vec2 uv, float lod) {
    vec2 texel = 1.0 / viewSize * exp2(lod);
    vec3 sum = textureLod(colortex4, uv + texel * vec2( 0.5,  0.5), lod).rgb;
    sum += textureLod(colortex4, uv + texel * vec2(-0.5,  0.5), lod).rgb;
    sum += textureLod(colortex4, uv + texel * vec2( 0.5, -0.5), lod).rgb;
    sum += textureLod(colortex4, uv + texel * vec2(-0.5, -0.5), lod).rgb;
    return sum * 0.25;
}

vec3 gatherBloom(vec2 uv) {
    vec3 bloom = bloomLevel(uv, 2.0) * 0.30;
    bloom += bloomLevel(uv, 3.0) * 0.27;
    bloom += bloomLevel(uv, 4.0) * 0.22;
    bloom += bloomLevel(uv, 5.0) * 0.14;
    bloom += bloomLevel(uv, 6.0) * 0.07;
    return bloom;
}

void main() {
    vec3 color = texture(colortex0, texcoord).rgb;

    vec3 sunDir = sunDirectionFromClock(lumClock);
    float h = sunDir.y;
    float rain = clamp(lumRain, 0.0, 1.0);
    float dayLight = smoothstep(-0.10, 0.12, h) * (1.0 - 0.8 * rain);

    // View ray of this pixel in world space.
    vec2 ndc = texcoord * 2.0 - 1.0;
    vec3 viewRay = normalize(vec3(ndc.x / gbufferProjection[0][0], ndc.y / gbufferProjection[1][1], -1.0));
    vec3 worldRay = normalize(mat3(gbufferModelViewInverse) * viewRay);
    float cosSun = dot(worldRay, sunDir);

    vec3 glowTint = mix(vec3(1.00, 0.42, 0.16), vec3(1.00, 0.90, 0.70), smoothstep(0.0, 0.40, h));

    // ---- sun glow ----------------------------------------------------------
    if (SUN_GLOW > 0.0 && isSky(texcoord)) {
        float c = max(cosSun, 0.0);
        float halo = 0.10 * pow(c, 8.0) + 0.24 * pow(c, 48.0) + 0.60 * pow(c, 600.0);
        // Warm band hugging the horizon at sunrise/sunset, strongest toward the sun.
        float twilight = 1.0 - smoothstep(0.0, 0.30, abs(h));
        float band = twilight * exp(-abs(worldRay.y) * 7.0) * (0.12 + 0.55 * pow(c, 3.0));
        color += glowTint * (halo + band) * dayLight * SUN_GLOW * 0.55;
    }

    // ---- light rays --------------------------------------------------------
    if (LIGHT_RAYS == 1 && dayLight > 0.01) {
        vec3 sunView = mat3(gbufferModelView) * sunDir;
        if (sunView.z < -0.05) {
            vec4 clip = gbufferProjection * vec4(sunView, 0.0);
            vec2 sunUv = clip.xy / max(-sunView.z, 1e-3) * 0.5 + 0.5;
            vec2 toSun = sunUv - texcoord;
            float reach = clamp(length(toSun), 0.0, 1.2);
            float facing = pow(max(cosSun, 0.0), 2.0);
            if (facing > 0.01) {
                const int STEPS = 14;
                float jitter = ign(gl_FragCoord.xy);
                float rays = 0.0;
                for (int i = 0; i < STEPS; i++) {
                    float t = (float(i) + jitter) / float(STEPS);
                    vec2 uv = texcoord + toSun * t * 0.85;
                    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) continue;
                    rays += isSky(uv) ? 1.0 : 0.0;
                }
                rays /= float(STEPS);
                // Only shafts cast by terrain: pixels in the sky itself are already lit.
                float shaft = rays * facing * (1.0 - smoothstep(0.0, 1.1, reach));
                color += glowTint * shaft * dayLight * 0.085;
            }
        }
    }

    // ---- bloom -------------------------------------------------------------
    color += gatherBloom(texcoord) * BLOOM_STRENGTH * 0.55;

    gl_FragData[0] = vec4(clamp(color, 0.0, 1.0), 1.0);
}
