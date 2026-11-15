#version 330 compatibility

// Lumina - shadow (vertex)
//
// Renders terrain from the light's point of view into the shadow map that
// gbuffers_terrain samples. In this program the runtime maps the model-view and
// projection matrices to the light-space ones, so this reads like a normal
// terrain vertex stage.
//
// Foliage uses exactly the same sway as gbuffers_terrain. Keep the two in step:
// if they drift apart, leaf shadows slide off their leaves.

#define SWAY_STRENGTH 1.0 // [0.0 0.5 1.0 1.5 2.0]

in vec4 mc_Entity;
in vec2 mc_midTexCoord;

uniform mat4 shadowModelViewInverse;
uniform vec3 cameraPosition;
uniform float frameTimeCounter;
uniform float rainStrength;

out vec2 texcoord;
out vec4 glColor;

void main() {
    texcoord = gl_MultiTexCoord0.xy;
    glColor = gl_Color;

    vec4 viewPos = gl_ModelViewMatrix * gl_Vertex;

    float id = mc_Entity.x;
    float isLeaves = step(10000.5, id) * (1.0 - step(10001.5, id));
    float isPlant  = step(10001.5, id) * (1.0 - step(10002.5, id));
    float foliageClass = isLeaves + 2.0 * isPlant;

    if (foliageClass > 0.5 && SWAY_STRENGTH > 0.0) {
        vec3 p = (shadowModelViewInverse * viewPos).xyz + cameraPosition;
        float topVertex = step(gl_MultiTexCoord0.t, mc_midTexCoord.t);
        float weight = mix(0.30, topVertex, step(1.5, foliageClass));

        float t = frameTimeCounter;
        float gust = 0.5 + 0.5 * sin(dot(p.xz, vec2(0.071, 0.049)) + t * 0.55);
        vec2 sway = vec2(sin(t * 1.65 + p.x * 0.85 + p.z * 0.55),
                         sin(t * 1.25 + p.z * 0.75 - p.x * 0.45));
        sway += 0.55 * vec2(sin(t * 3.10 + p.x * 2.30 + p.y * 1.70),
                            cos(t * 2.70 + p.z * 2.10 + p.y * 1.90));
        float amplitude = weight * (0.035 + 0.055 * gust) * (1.0 + 1.6 * rainStrength) * SWAY_STRENGTH;

        vec3 offset = vec3(sway.x, -0.25 * abs(sway.x) * weight, sway.y) * amplitude;
        viewPos.xyz += mat3(gl_ModelViewMatrix) * offset;
    }

    gl_Position = gl_ProjectionMatrix * viewPos;
}
