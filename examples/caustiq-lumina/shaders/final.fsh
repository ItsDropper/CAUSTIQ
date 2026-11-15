#version 330 compatibility

// Lumina - final
//
// FXAA (single pass, edge-directed), a touch of sharpening to win back what FXAA
// softens, then grading: gentle S-curve, saturation, vignette, and dither to kill
// banding in the dark gradients that the tone curve stretches.

#ifndef FXAA
#define FXAA 1 // [0 1]
#endif
#ifndef SATURATION
#define SATURATION 1.06 // [0.85 0.95 1.0 1.06 1.15 1.25]
#endif
#ifndef CONTRAST
#define CONTRAST 0.14 // [0.0 0.07 0.14 0.21 0.28]
#endif
#ifndef VIGNETTE
#define VIGNETTE 0.22 // [0.0 0.11 0.22 0.33 0.44]
#endif
#ifndef GRAIN
#define GRAIN 1.0 // [0.0 0.5 1.0 2.0]
#endif

in vec2 texcoord;

uniform sampler2D colortex0;
uniform vec2 viewSize;
uniform int frameCounter;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

vec3 fetch(vec2 uv) {
    return texture(colortex0, uv).rgb;
}

// Compact FXAA 3.11-style: find the edge direction from a 3x3 luma neighbourhood,
// then blend along it with a two-tap and four-tap span, picking whichever stays
// inside the local luma range.
vec3 fxaa(vec2 uv, vec2 texel) {
    vec3 cM = fetch(uv);
    vec3 cN = fetch(uv + vec2( 0.0,  texel.y));
    vec3 cS = fetch(uv + vec2( 0.0, -texel.y));
    vec3 cE = fetch(uv + vec2( texel.x, 0.0));
    vec3 cW = fetch(uv + vec2(-texel.x, 0.0));
    float lM = luma(cM), lN = luma(cN), lS = luma(cS), lE = luma(cE), lW = luma(cW);

    float lMin = min(lM, min(min(lN, lS), min(lE, lW)));
    float lMax = max(lM, max(max(lN, lS), max(lE, lW)));
    float range = lMax - lMin;
    if (range < max(0.0312, lMax * 0.125)) return cM;          // not an edge

    float lNW = luma(fetch(uv + vec2(-texel.x,  texel.y)));
    float lNE = luma(fetch(uv + vec2( texel.x,  texel.y)));
    float lSW = luma(fetch(uv + vec2(-texel.x, -texel.y)));
    float lSE = luma(fetch(uv + vec2( texel.x, -texel.y)));

    // Sub-pixel aliasing amount.
    float lAvg = (lN + lS + lE + lW) * 0.25;
    float subpix = clamp(abs(lAvg - lM) / range, 0.0, 1.0);
    subpix = smoothstep(0.0, 1.0, subpix);
    subpix = subpix * subpix * 0.75;

    float edgeH = abs(-2.0 * lW + lNW + lSW) + abs(-2.0 * lM + lN + lS) * 2.0 + abs(-2.0 * lE + lNE + lSE);
    float edgeV = abs(-2.0 * lN + lNW + lNE) + abs(-2.0 * lM + lW + lE) * 2.0 + abs(-2.0 * lS + lSW + lSE);
    bool horizontal = edgeH >= edgeV;

    float lA = horizontal ? lN : lE;
    float lB = horizontal ? lS : lW;
    float gA = abs(lA - lM);
    float gB = abs(lB - lM);
    bool steeperA = gA >= gB;
    float gradient = max(gA, gB);
    vec2 stepDir = horizontal ? vec2(0.0, texel.y) : vec2(texel.x, 0.0);
    if (!steeperA) stepDir = -stepDir;
    vec2 along = horizontal ? vec2(texel.x, 0.0) : vec2(0.0, texel.y);

    // Walk both ways along the edge to find where it ends.
    vec2 edgeUv = uv + stepDir * 0.5;
    float lEdge = 0.5 * (lM + (steeperA ? lA : lB));
    vec2 uvP = edgeUv + along;
    vec2 uvN = edgeUv - along;
    float dP = luma(fetch(uvP)) - lEdge;
    float dN = luma(fetch(uvN)) - lEdge;
    bool doneP = abs(dP) >= gradient * 0.25;
    bool doneN = abs(dN) >= gradient * 0.25;
    for (int i = 0; i < 8; i++) {
        if (!doneP) { uvP += along; dP = luma(fetch(uvP)) - lEdge; doneP = abs(dP) >= gradient * 0.25; }
        if (!doneN) { uvN -= along; dN = luma(fetch(uvN)) - lEdge; doneN = abs(dN) >= gradient * 0.25; }
        if (doneP && doneN) break;
    }
    float distP = horizontal ? (uvP.x - uv.x) : (uvP.y - uv.y);
    float distN = horizontal ? (uv.x - uvN.x) : (uv.y - uvN.y);
    bool closerP = distP < distN;
    float span = max(distP + distN, 1e-5);
    float distMin = closerP ? distP : distN;
    float pixelOffset = -distMin / span + 0.5;

    bool lumaSmaller = lM < lEdge;
    bool correct = ((closerP ? dP : dN) < 0.0) != lumaSmaller;
    float edgeOffset = correct ? pixelOffset : 0.0;
    float offset = max(edgeOffset, subpix);
    return fetch(uv + stepDir * offset);
}

void main() {
    vec2 texel = 1.0 / viewSize;
    vec3 color = (FXAA == 1) ? fxaa(texcoord, texel) : fetch(texcoord);

    // Mild unsharp mask from the immediate neighbours.
    vec3 blur = (fetch(texcoord + vec2(texel.x, 0.0)) + fetch(texcoord - vec2(texel.x, 0.0))
               + fetch(texcoord + vec2(0.0, texel.y)) + fetch(texcoord - vec2(0.0, texel.y))) * 0.25;
    color += (color - blur) * 0.22;

    // Grade.
    color = clamp(color, 0.0, 1.0);
    vec3 curved = color * color * (3.0 - 2.0 * color);          // smoothstep S-curve
    color = mix(color, curved, CONTRAST);
    color = mix(vec3(luma(color)), color, SATURATION);

    vec2 centered = texcoord - 0.5;
    centered.x *= viewSize.x / viewSize.y;
    float vig = smoothstep(0.35, 1.05, length(centered));
    color *= 1.0 - VIGNETTE * vig * vig;

    // Triangular-PDF dither, about +/- 1 LSB.
    vec2 jit = vec2(float(frameCounter % 64) * 17.13, float(frameCounter % 64) * 9.71);
    float n = ign(gl_FragCoord.xy + jit) + ign(gl_FragCoord.xy + 91.7 - jit) - 1.0;
    color += n * (GRAIN / 255.0);

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
