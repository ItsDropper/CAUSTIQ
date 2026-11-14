#!/usr/bin/env python3
"""Verify CAUSTIQ's mixin assumptions against the pinned Nvidium source tree."""
from pathlib import Path
import json
import sys

root = Path(sys.argv[1])
caustiq_root = Path(sys.argv[2])

def source(relative: str) -> str:
    path = root / relative
    if not path.is_file():
        raise SystemExit(f"Missing pinned Nvidium source: {relative}")
    return path.read_text(encoding="utf-8")

shader = source("src/main/java/me/cortex/nvidium/gl/shader/Shader.java")
iris_check = source("src/main/java/me/cortex/nvidium/sodiumCompat/IrisCheck.java")
render_section = source("src/main/java/me/cortex/nvidium/mixin/sodium/MixinRenderSectionManager.java")
mesh = source("src/main/resources/assets/nvidium/shaders/terrain/mesh.glsl")
fragment = source("src/main/resources/assets/nvidium/shaders/terrain/frag.frag")
primary = source("src/main/java/me/cortex/nvidium/renderers/PrimaryTerrainRasterizer.java")
adapter = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumTerrainVaryingAdapter.java").read_text(encoding="utf-8")
bridge = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumShaderProgramBridge.java").read_text(encoding="utf-8")

# The mixin shadows this exact private field and injects at this exact method.
assert "private final Map<ShaderType, String> sources" in shader, "Shader.Builder.sources signature changed"
assert "public Shader compile()" in shader, "Shader.Builder.compile() signature changed"
assert "class Builder" in shader, "Shader.Builder nested class disappeared"
assert "NvidiumShaderBuilderMixin" in (caustiq_root / "src/main/resources/caustiq.mixins.json").read_text()
builder_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumShaderBuilderMixin.java").read_text()
assert 'me.cortex.nvidium.gl.shader.Shader$Builder' in builder_mixin
assert '@Shadow @Final private Map<?, String> sources' in builder_mixin
assert 'method = "compile"' in builder_mixin

# checkIrisShouldDisable() returns true when the Nvidium backend is allowed.
assert "public static boolean checkIrisShouldDisable()" in iris_check
assert "return !(IRIS_LOADED && checkIrisShaders());" in iris_check
assert "IrisCheck.checkIrisShouldDisable()" in render_section
assert "Nvidium.IS_ENABLED = (!Nvidium.FORCE_DISABLE) && Nvidium.IS_COMPATIBLE" in render_section
iris_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumIrisCheckMixin.java").read_text()
assert 'me.cortex.nvidium.sodiumCompat.IrisCheck' in iris_mixin
assert 'method = "checkIrisShouldDisable"' in iris_mixin
assert "callbackInfo.setReturnValue(true)" in iris_mixin
assert "if (NvidiumShaderProgramBridge.hasTerrainAdapter())" in iris_mixin
assert "nativeSourceAdapter = NvidiumTerrainVaryingAdapter::adapt" in bridge
assert "hasTerrainAdapter() { return irisTerrainAdapter != null; }" in bridge

# The active Nvidium terrain pipeline is TASK + MESH + FRAGMENT, with mesh
# outputs consumed by the fragment shader. Keep this check tied to its source.
assert ".addSource(TASK" in primary and ".addSource(MESH" in primary and ".addSource(FRAGMENT" in primary
assert "layout(triangles, max_vertices=64, max_primitives=32) out;" in mesh
assert "layout(location=1) out Interpolants" in mesh
assert "layout(location = 1) in Interpolants" in fragment
assert "gl_PrimitiveID" in fragment

# The native varying adapter must target real, unique anchors in the pinned
# upstream sources. The Java integration test runs the transformation itself.
for anchor, label in [
    ("#version 460", "GLSL version"),
    ("    vec2 uv;\n    vec3 v_colour;", "interpolant declaration"),
    ("vec4 transformVertex(Vertex V) {", "mesh helper insertion"),
    ("    bool triangle1 = (gl_LocalInvocationIndex & uint(1)) == 1;", "triangle selection"),
    ("    OUT[id].uv = decodeVertexUV(V);\n    OUT[id].v_colour = computeMultiplier(V);", "mesh output assignments"),
]:
    assert mesh.count(anchor) == 1, f"Pinned Nvidium mesh shader adapter anchor is missing or ambiguous: {label}"
assert fragment.count("    vec2 uv;\n    vec3 v_colour;") == 1, "Pinned Nvidium fragment varying declaration changed or is ambiguous"
assert "CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS" in adapter
assert "USE_NV_FRAGMENT_SHADER_BARYCENTRIC" in adapter, "Extended varying ABI must disable Nvidium barycentric-only path"
assert "decodeLightUV(V)" in adapter and "decodeVertexColour(V).rgb" in adapter
assert "cross(edge1, edge2)" in adapter, "Geometric face-normal reconstruction is missing"
assert "caustiqQuadMidUv(uint quadId)" in adapter, "Quad midpoint UV reconstruction is missing"
assert "caustiqQuadMidPosition(uint quadId)" in adapter, "Quad midpoint position reconstruction is missing"
assert "caustiqComputeTriangleBasis(uint quadId, bool triangle1)" in adapter, "Per-triangle tangent basis reconstruction is missing"
assert "flat mat3 v_tbn;" in adapter and "OUT[id].v_tbn = caustiqTriangleTbn;" in adapter
assert "putVertex(uint id, Vertex V, uint quadId)" in adapter, "Mesh varying writer does not receive quad identity"
assert "transformMat * vec4(decodeVertexPosition(vertex) + origin, 1.0)" in adapter, "Terrain render position is not transformed"
assert "flat uint v_materialFlags;" in adapter and "rawVertexAlphaCutoff(V)" in adapter
abi = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumIrisTerrainAbi.java").read_text(encoding="utf-8")
assert "CAUSTIQ_IRIS_VIEW_WIDTH (screenSize.x * 2.0)" in abi
assert "CAUSTIQ_IRIS_VIEW_HEIGHT (screenSize.y * 2.0)" in abi
assert "CAUSTIQ_IRIS_GTEXTURE tex_diffuse" in abi
assert "CAUSTIQ_IRIS_LIGHTMAP tex_light" in abi
assert 'NVIDIUM_VERSION = "0.4.3-beta13-1.21.11"' in abi
assert 'DIFFUSE_ATLAS_TEXTURE_UNIT = 0' in abi and 'LIGHTMAP_TEXTURE_UNIT = 1' in abi
assert 'Availability.UNAVAILABLE' in abi
vertex_format = source("src/main/resources/assets/nvidium/shaders/terrain/vertex_format/vertex_format.glsl")
nvidium_vertex_format = source("src/main/resources/assets/nvidium/shaders/terrain/vertex_format/nvidium_vertex_format.glsl")
sodium_vertex_format = source("src/main/resources/assets/nvidium/shaders/terrain/vertex_format/sodium_vertex_format.glsl")
scene = source("src/main/resources/assets/nvidium/shaders/occlusion/scene.glsl")
assert "decodeVertexUV" in nvidium_vertex_format and "decodeLightUV" in nvidium_vertex_format and "decodeVertexColour" in nvidium_vertex_format
assert "decodeVertexUV" in sodium_vertex_format and "decodeLightUV" in sodium_vertex_format and "decodeVertexColour" in sodium_vertex_format
assert "mat4 MVP;" in scene and "vec4 fogColour;" in scene and "vec2 screenSize;" in scene
assert "NvidiumTerrainVaryingAdapter.class" in (caustiq_root / ".github/workflows/build.yml").read_text(encoding="utf-8")

print("Nvidium source contract verified: compile hook, Iris gate, packed vertex format, scene uniforms, sampler bindings, and varying ABI.")
