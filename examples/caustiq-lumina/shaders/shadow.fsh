#version 330 compatibility

/* RENDERTARGETS: 0 */

// Lumina - shadow (fragment)
//
// Depth is written implicitly. All this stage does is punch holes: cutout
// geometry (leaves, grass, fences' gaps) must not cast a solid shadow. The colour
// output is unused by gbuffers_terrain but keeps the shadow attachment defined.

in vec2 texcoord;
in vec4 glColor;

uniform sampler2D gtexture;

void main() {
    vec4 tex = texture(gtexture, texcoord);
    if (tex.a < 0.2) discard;
    gl_FragData[0] = vec4(tex.rgb * glColor.rgb, 1.0);
}
