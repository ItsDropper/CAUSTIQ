#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float BLOOM_THRESHOLD = 0.78;
const float BLOOM_SOFT_KNEE = 0.20;

// A compact, low-radius highlight gather. The source is the completed scene,
// so this deliberately avoids pretending that depth, normals, or motion data
// are available to this final-only example.
vec3 extractHighlights(vec2 uv) {
    vec3 color = texture(DiffuseSampler, uv).rgb;
    float luminance = dot(color, LUMA);
    float contribution = smoothstep(
        BLOOM_THRESHOLD - BLOOM_SOFT_KNEE,
        BLOOM_THRESHOLD + BLOOM_SOFT_KNEE,
        luminance
    );
    return color * contribution / max(luminance, 0.001);
}

vec3 gatherBloom(vec2 uv, vec2 texel) {
    // A small 13-tap tent-like kernel keeps highlights soft without a
    // conspicuous blur halo. Weights sum to 1.0.
    vec3 bloom = extractHighlights(uv) * 0.20;

    bloom += extractHighlights(uv + texel * vec2( 2.0,  0.0)) * 0.10;
    bloom += extractHighlights(uv + texel * vec2(-2.0,  0.0)) * 0.10;
    bloom += extractHighlights(uv + texel * vec2( 0.0,  2.0)) * 0.10;
    bloom += extractHighlights(uv + texel * vec2( 0.0, -2.0)) * 0.10;

    bloom += extractHighlights(uv + texel * vec2( 3.5,  3.5)) * 0.05;
    bloom += extractHighlights(uv + texel * vec2(-3.5,  3.5)) * 0.05;
    bloom += extractHighlights(uv + texel * vec2( 3.5, -3.5)) * 0.05;
    bloom += extractHighlights(uv + texel * vec2(-3.5, -3.5)) * 0.05;

    bloom += extractHighlights(uv + texel * vec2( 5.0,  0.0)) * 0.05;
    bloom += extractHighlights(uv + texel * vec2(-5.0,  0.0)) * 0.05;
    bloom += extractHighlights(uv + texel * vec2( 0.0,  5.0)) * 0.05;
    bloom += extractHighlights(uv + texel * vec2( 0.0, -5.0)) * 0.05;

    return bloom;
}

// A restrained filmic shoulder. Unlike a hard clamp, it preserves highlight
// separation and keeps bright clouds, snow and lamps from flattening together.
vec3 filmicShoulder(vec3 color) {
    const float shoulder = 0.16;
    return color * (1.0 + color * shoulder) / (1.0 + color);
}

float interleavedNoise(vec2 pixel) {
    // Stable per-pixel dither; no time uniform is assumed by the host.
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec3 scene = texture(DiffuseSampler, texCoord).rgb;
    vec2 texel = 1.0 / vec2(textureSize(DiffuseSampler, 0));

    // Highlight bloom is intentionally subtle: it should soften emissive
    // points and sun glints, not wash the whole image in haze.
    vec3 bloom = gatherBloom(texCoord, texel);
    scene += bloom * 0.075;

    // Gentle contrast around a lifted photographic mid-grey.
    scene = max((scene - vec3(0.18)) * 1.045 + vec3(0.18), vec3(0.0));

    float luminance = dot(scene, LUMA);
    float shadowWeight = 1.0 - smoothstep(0.035, 0.62, luminance);
    float highlightWeight = smoothstep(0.38, 1.05, luminance);

    // Cool, clean shadows and a very slight warm bias in direct-looking
    // highlights. The small amplitudes preserve the source pack's palette.
    scene *= mix(vec3(1.0), vec3(0.965, 0.985, 1.015), shadowWeight * 0.16);
    scene *= mix(vec3(1.0), vec3(1.018, 1.006, 0.982), highlightWeight * 0.20);

    // Keep foliage and terrain colour believable rather than oversaturated.
    luminance = dot(scene, LUMA);
    scene = mix(vec3(luminance), scene, 1.035);

    scene = filmicShoulder(scene);

    // Subtle edge falloff, with the center left untouched. This is a lens
    // cue, not a dark cinematic border.
    vec2 centered = texCoord * 2.0 - 1.0;
    float radius = dot(centered, centered);
    float vignette = 1.0 - 0.075 * smoothstep(0.28, 1.65, radius);
    scene *= vignette;

    // Less than half an 8-bit step: enough to break up gradients without
    // looking like visible film grain.
    float dither = (interleavedNoise(gl_FragCoord.xy) - 0.5) / 255.0;
    scene += vec3(dither);

    fragColor = vec4(max(scene, vec3(0.0)), 1.0);
}
