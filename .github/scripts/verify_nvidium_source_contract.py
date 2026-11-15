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
mixins_json = json.loads((caustiq_root / "src/main/resources/caustiq.mixins.json").read_text(encoding="utf-8"))
assert "NvidiumShaderBuilderMixin" in mixins_json["client"]
assert "NvidiumTerrainRasterizerMixin" in mixins_json["client"]
rasterizer_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumTerrainRasterizerMixin.java").read_text(encoding="utf-8")
assert 'me.cortex.nvidium.renderers.PrimaryTerrainRasterizer' in rasterizer_mixin
assert 'method = "raster"' in rasterizer_mixin
gbuffer_manager = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumGBufferTargets.java").read_text(encoding="utf-8")
assert "GL_COLOR_ATTACHMENT1" in gbuffer_manager and "GL_COLOR_ATTACHMENT0 + i" in gbuffer_manager
assert "glCheckFramebufferStatus" in gbuffer_manager and "glDrawBuffers" in gbuffer_manager
assert "getColorTextureView()" in gbuffer_manager and "TextureTarget" in gbuffer_manager
postprocess = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/shaderpack/ShaderPostProcessPass.java").read_text(encoding="utf-8")
compiler = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/shaderpack/ShaderProgramCompiler.java").read_text(encoding="utf-8")
for index in range(1, 5):
    assert f'.withSampler("colortex{index}")' in compiler
    assert f'pass.bindTexture("colortex{index}"' in postprocess
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
assert "public static boolean hasTerrainAdapter()" in bridge
assert "primaryTerrainAdapter != null || translucentTerrainAdapter != null" in bridge

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
assert "caustiqDecodeNormal(Vertex vertex)" in adapter, "Packed Iris normal decoder is missing"
assert "caustiqDecodeTangent(Vertex vertex)" in adapter, "Packed Iris tangent decoder is missing"
assert "caustiqDecodeMidUv(Vertex vertex)" in adapter, "Packed quad midpoint UV decoder is missing"
assert "caustiqDecodeMidBlock(Vertex vertex)" in adapter, "Packed midpoint block decoder is missing"
assert "caustiqDecodeBlockEntity(uint packed)" in adapter, "Packed mc_Entity decoder is missing"
assert "flat mat3 v_tbn;" in adapter and "OUT[id].v_tbn = caustiqDecodeTbn(V);" in adapter
assert "uint nvidiumMaterial;" in adapter and "V.nvidiumMaterial" in adapter
assert "OUT[id].v_renderPosition = (transformMat * vec4(decodeVertexPosition(V) + origin, 1.0)).xyz;" in adapter
assert "OUT[id].v_quadMidPosition = (transformMat * vec4(decodeVertexPosition(V) + origin + OUT[id].v_midBlock, 1.0)).xyz;" in adapter
assert "flat uint v_materialFlags;" in adapter and "V.nvidiumMaterial" in adapter
assert "layout(location = 4) out vec4 caustiqGBufferLightmap;" in adapter
assert "caustiqGBufferNormal = vec4(normalize(v_normal)" in adapter
assert "caustiqGBufferPosition = vec4(v_renderPosition, 1.0)" in adapter
assert "caustiqGBufferMaterial = vec4(v_vertexColour" in adapter
assert "caustiqGBufferLightmap = vec4(v_lightmapCoord" in adapter
abi = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumIrisTerrainAbi.java").read_text(encoding="utf-8")
assert "CAUSTIQ_IRIS_VIEW_WIDTH (screenSize.x * 2.0)" in abi
assert "CAUSTIQ_IRIS_VIEW_HEIGHT (screenSize.y * 2.0)" in abi
assert "CAUSTIQ_IRIS_GTEXTURE tex_diffuse" in abi
assert "CAUSTIQ_IRIS_LIGHTMAP tex_light" in abi
assert 'NVIDIUM_VERSION = "0.4.3-beta13-1.21.11"' in abi
assert 'DIFFUSE_ATLAS_TEXTURE_UNIT = 0' in abi and 'LIGHTMAP_TEXTURE_UNIT = 1' in abi
assert 'Availability.UNAVAILABLE' in abi
# Verify the extended vertex encoder and Nvidium's CPU-side repackager use the same 40-byte stride.
nvidium_repackager = source("src/main/java/me/cortex/nvidium/sodiumCompat/SodiumResultCompatibility.java")
assert "int formatSize = Nvidium.config.use_sodium_vertex_format" in nvidium_repackager
assert "copyQuad" in nvidium_repackager
assert "CompactChunkVertex.STRIDE * 4" in nvidium_repackager
assert "NvidiumCompactChunkVertex.STRIDE * 4" in nvidium_repackager
translator = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/IrisNvidiumTerrainTranslator.java").read_text(encoding="utf-8")
uniform_bridge = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumIrisUniformBridge.java").read_text(encoding="utf-8")
texture_bridge = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumIrisTextureBridge.java").read_text(encoding="utf-8")
assert "photonUniformDeclarations()" in translator
assert 'Map.entry("noisetex", "caustiqNoiseTexture")' in translator
assert "layout(binding = 2) uniform sampler2D caustiqNoiseTexture;" in translator
assert "NvidiumIrisTextureBridge.bindForTerrain()" in uniform_bridge
assert "GL_TEXTURE2" in texture_bridge and "GL_TEXTURE3" in texture_bridge and "GL_TEXTURE4" in texture_bridge
vertex_encoder = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumIrisChunkVertex.java").read_text(encoding="utf-8")
assert "STRIDE = 40" in vertex_encoder
assert "ColorARGB.mulRGB(color, brightness)" in vertex_encoder
assert "MemoryUtil.memPutInt(ptr + 36L, nvidiumMaterial)" in vertex_encoder
assert "color.a = float((vertex.nvidiumMaterial >> 8u) & 0xFFu) * COLOR_SCALE;" in adapter
for field in ("blockId", "normalTangent", "midpointUv", "midBlock", "nvidiumMaterial"):
    assert field in vertex_encoder
format_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumExtendedVertexFormatMixin.java").read_text(encoding="utf-8")
stride_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumRepackagedStrideMixin.java").read_text(encoding="utf-8")
assert "NvidiumIrisChunkVertex.INSTANCE" in format_mixin
assert "NvidiumIrisChunkVertex.STRIDE" in stride_mixin
assert "@Redirect(" in stride_mixin and "CompactChunkVertex;STRIDE:I" in stride_mixin
assert "NvidiumIrisChunkVertex.STRIDE" in stride_mixin, "copyQuad must multiply the extended 40-byte vertex stride by four vertices"
world_renderer = source("src/main/java/me/cortex/nvidium/NvidiumWorldRenderer.java")
world_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumWorldRendererMixin.java").read_text(encoding="utf-8")
assert "new SectionManager(device, max_geometry_memory*1024*1024, uploadStream" in world_renderer
assert "SectionManager;<init>(Lme/cortex/nvidium/gl/RenderDevice;JLme/cortex/nvidium/util/UploadingBufferStream;ILme/cortex/nvidium/NvidiumWorldRenderer;)V" in world_mixin
assert "caustiq$allocateExtendedTerrainStride" in world_mixin and "NvidiumIrisChunkVertex.STRIDE" in world_mixin
for mixin in ("NvidiumExtendedVertexFormatMixin", "NvidiumRepackagedStrideMixin",
              "NvidiumBlockRendererVertexContextMixin", "NvidiumFluidRendererVertexContextMixin"):
    assert mixin in mixins_json["client"]
uniform_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumTerrainRasterizerMixin.java").read_text(encoding="utf-8")
assert "NvidiumIrisUniformBridge.upload(this)" in uniform_mixin
assert "caustiqDecodeTangent(V)" in adapter and "caustiqDecodeMidBlock(V)" in adapter

vertex_format = source("src/main/resources/assets/nvidium/shaders/terrain/vertex_format/vertex_format.glsl")
nvidium_vertex_format = source("src/main/resources/assets/nvidium/shaders/terrain/vertex_format/nvidium_vertex_format.glsl")
sodium_vertex_format = source("src/main/resources/assets/nvidium/shaders/terrain/vertex_format/sodium_vertex_format.glsl")
scene = source("src/main/resources/assets/nvidium/shaders/occlusion/scene.glsl")
assert "decodeVertexUV" in nvidium_vertex_format and "decodeLightUV" in nvidium_vertex_format and "decodeVertexColour" in nvidium_vertex_format
assert "decodeVertexUV" in sodium_vertex_format and "decodeLightUV" in sodium_vertex_format and "decodeVertexColour" in sodium_vertex_format
assert "mat4 MVP;" in scene and "vec4 fogColour;" in scene and "vec2 screenSize;" in scene
assert "NvidiumTerrainVaryingAdapter.class" in (caustiq_root / ".github/workflows/build.yml").read_text(encoding="utf-8")

print("Nvidium source contract verified: compile hook, Iris gate, packed vertex format, scene uniforms, sampler bindings, and varying ABI.")
