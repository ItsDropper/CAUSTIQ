#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 scene = texture(DiffuseSampler, texCoord);
    // Deliberately obvious channel rotation to verify fragment execution.
    fragColor = vec4(scene.b, scene.r, scene.g, scene.a);
}
