#!/usr/bin/env python3
"""Verify CAUSTIQ's mixin assumptions against the pinned Nvidium source tree."""
from pathlib import Path
import json
import sys

root = Path(sys.argv[1])
caustiq_root = Path(sys.argv[2])
sodium_root = Path(sys.argv[3])

def source(relative: str) -> str:
    path = root / relative
    if not path.is_file():
        raise SystemExit(f"Missing pinned Nvidium source: {relative}")
    return path.read_text(encoding="utf-8")

# Sodium's base FluidRenderer is abstract: it has no executable method body
# for HEAD/RETURN injection points. The Fabric implementation is the concrete
# class used by the installed Fabric distribution and owns the render body.
sodium_base = (sodium_root / "common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/FluidRenderer.java").read_text(encoding="utf-8")
sodium_fabric = (sodium_root / "fabric/src/main/java/net/caffeinemc/mods/sodium/fabric/render/FluidRendererImpl.java").read_text(encoding="utf-8")
fluid_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumFluidRendererVertexContextMixin.java").read_text(encoding="utf-8")
assert "public abstract class FluidRenderer" in sodium_base
assert "public abstract void render(" in sodium_base
assert "public class FluidRendererImpl extends FluidRenderer" in sodium_fabric
assert "public void render(" in sodium_fabric
assert 'net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl' in fluid_mixin
assert 'net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer"' not in fluid_mixin
assert 'method = "render", at = @At("HEAD")' in fluid_mixin
assert 'method = "render", at = @At("RETURN")' in fluid_mixin

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
assert "NvidiumTranslucentTerrainRasterizerMixin" in mixins_json["client"]
translucent_rasterizer_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumTranslucentTerrainRasterizerMixin.java").read_text(encoding="utf-8")
assert 'method = "<init>", at = @At("HEAD")' in translucent_rasterizer_mixin
assert "private static void caustiq$beginTranslucentShaderCompilation(CallbackInfo callbackInfo)" in translucent_rasterizer_mixin, "Translucent constructor HEAD injection must be static because it runs before super()"
assert 'method = "<init>", at = @At("TAIL")' in translucent_rasterizer_mixin
rasterizer_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumTerrainRasterizerMixin.java").read_text(encoding="utf-8")
assert 'me.cortex.nvidium.renderers.PrimaryTerrainRasterizer' in rasterizer_mixin
assert 'method = "raster"' in rasterizer_mixin
primary_rasterizer_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumPrimaryTerrainRasterizerMixin.java").read_text(encoding="utf-8")
assert 'method = "<init>", at = @At("HEAD")' in primary_rasterizer_mixin
assert "private static void caustiq$beginPrimaryShaderCompilation(CallbackInfo callbackInfo)" in primary_rasterizer_mixin, "Constructor HEAD injection must be static because it runs before super()"
assert 'method = "<init>", at = @At("TAIL")' in primary_rasterizer_mixin
gbuffer_manager = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumGBufferTargets.java").read_text(encoding="utf-8")
assert "GL_COLOR_ATTACHMENT1" in gbuffer_manager and "GL_COLOR_ATTACHMENT0 + i" in gbuffer_manager
assert "glCheckFramebufferStatus" in gbuffer_manager and "glDrawBuffers" in gbuffer_manager
assert "targetIndices.equals(java.util.List.of(0, 1, 2, 3, 4, 5, 6, 7))" in gbuffer_manager
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
assert "if (ShaderRuntime.activateTerrainAdapterForCurrentWorld())" in iris_mixin
assert "private static volatile UnaryOperator<Map<String, String>> nativeSourceAdapter;" in bridge
client = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/CaustiqClient.java").read_text(encoding="utf-8")
assert "requestExtendedVertexFormat()" in client
assert "registerNativeSourceAdapter(NvidiumTerrainVaryingAdapter::adapt)" in client
assert "public static boolean hasTerrainAdapter()" in bridge
assert "primaryTerrainAdapter != null || translucentTerrainAdapter != null" in bridge
runtime = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/shaderpack/ShaderRuntime.java").read_text(encoding="utf-8")
assert "prepareSelectedNvidiumTerrainAdapter();" in runtime
assert "preparedTerrainTranslators" in runtime
assert "preparedUnsupportedTerrainDimensions" in runtime
assert "activateTerrainAdapterForCurrentWorld()" in runtime
assert "activateTerrainAdapterForCurrentWorld()" in runtime
assert "IrisNvidiumTerrainTranslator.from(" in runtime
assert "NvidiumGBufferTargets.configureTargets(" in runtime
translator = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/IrisNvidiumTerrainTranslator.java").read_text(encoding="utf-8")
assert "OUT[id].v_materialFlags = V.nvidiumMaterial & 0xFFu;" in translator
assert "caustiqIrisClipPosition = gl_MeshVerticesNV[id].gl_Position;" in translator

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
assert "caustiqDecodeBlockEntity(uint packedBlockEntityId)" in adapter, "Packed mc_Entity decoder is missing"
assert "packedNormal" in adapter and "packedTangentAngle" in adapter and "diamondCoordinate" in adapter
assert "flat mat3 v_tbn;" not in adapter and "OUT[id].v_tbn =" not in adapter, "Unused TBN varying must stay out of the constrained mesh/fragment interface"
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
assert "MC_HAND_DEPTH" in translator
assert "append(System.lineSeparator())" in translator
assert "injectedDefines.append(\"#define \" )" not in translator
assert "CAUSTIQ_NVIDIUM_UNIFORM_ABI" in translator
assert 'Map.entry("noisetex", "caustiqNoiseTexture")' in translator
assert "layout(binding = 2) uniform sampler2D caustiqNoiseTexture;" in translator
assert "NvidiumIrisTextureBridge.bindForTerrain(program)" in uniform_bridge
assert "NvidiumIrisTextureBridge.bindForShadow(program)" in uniform_bridge
assert "bindTerrainCustomTextures(program, firstCustomTextureUnit)" in uniform_bridge
assert "resources.bindTerrainTextures(" in texture_bridge
assert "firstTextureUnit, GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS)" in texture_bridge
assert "MAX_COLORTEX_SAMPLER = 15" in texture_bridge
assert "NvidiumPbrTextureAtlases.normalsTexture()" in texture_bridge
assert "NvidiumPbrTextureAtlases.specularTexture()" in texture_bridge
assert 'Map.entry("isElytraFlying", "caustiqIsElytraFlying")' in translator
assert 'Map.entry("isEyeInWater", "caustiqIsEyeInWater")' in translator
assert "uniform int caustiqIsEyeInWater;" in translator
assert 'integer(program, "caustiqIsEyeInWater", isEyeInWater())' in uniform_bridge
assert 'Map.entry("moonPhase", "caustiqMoonPhase")' in translator
assert "uniform int caustiqMoonPhase;" in translator
assert 'case "isEyeInWater", "moonPhase", "heldBlockLightValue"' in translator
assert 'case "worldTime" -> "int";' in translator
assert 'case "worldDay" -> "int";' in translator
assert 'Map.entry("worldDay", "caustiqWorldDay")' in translator
assert 'uniform int caustiqWorldDay;' in translator
assert 'integer(program, "caustiqWorldDay"' in uniform_bridge
assert 'uniform int caustiqWorldTime;' in translator
assert 'uniform vec2 caustiqTaaOffset;' in translator
assert '"taa_offset"' not in translator.split('UNSUPPORTED_RUNTIME_UNIFORMS', 1)[1].split(');', 1)[0]
assert 'integer(program, "caustiqWorldTime", worldTime)' in uniform_bridge
assert 'integer(program, "caustiqMoonPhase", moonPhase(level))' in uniform_bridge
assert "Math.floorDiv(dayTime, 24_000L), 8L" in uniform_bridge
assert 'case "WATER" -> 1;' in uniform_bridge and 'case "LAVA" -> 2;' in uniform_bridge and 'case "POWDER_SNOW" -> 3;' in uniform_bridge
assert 'uniform bool caustiqIsElytraFlying;' in translator
assert 'integer(program, "caustiqIsElytraFlying", isElytraFlying() ? 1 : 0)' in uniform_bridge
assert 'invokeBoolean(Minecraft.getInstance().player, "isFallFlying", false)' in uniform_bridge
assert "GL_TEXTURE2" in texture_bridge and "GL_TEXTURE0 + unit" in texture_bridge
assert "ShaderRuntime.irisColorTexture(attachment)" in texture_bridge
assert "MAX_COLORTEX_SAMPLER = 15" in texture_bridge
assert "terrainSamplerTexture(int index)" in (caustiq_root / "src/main/java/dev/itsdropper/caustiq/shaderpack/IrisGlPassExecutor.java").read_text(encoding="utf-8")
assert "terrainSamplerTexture(int index)" in (caustiq_root / "src/main/java/dev/itsdropper/caustiq/shaderpack/IrisGlPassExecutor.java").read_text(encoding="utf-8")
assert 'Map.entry("colortex8", "caustiqColortex8")' in translator
assert "sampler2DShadow caustiqShadowtex1" in translator
assert "GL33C.glBindSampler" in texture_bridge
assert "0x884C, 0x884E" in texture_bridge
assert "if (program == 0) return 6;" in texture_bridge
assert "return unit;" in texture_bridge
assert "noiseTexture, noiseTexture" not in texture_bridge, "Unavailable Iris samplers must not receive dummy noise textures"
vertex_encoder = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumIrisChunkVertex.java").read_text(encoding="utf-8")
assert "STRIDE = 40" in vertex_encoder
assert "ColorARGB.mulRGB(color, brightness)" in vertex_encoder
assert "MemoryUtil.memPutInt(ptr + 36L, nvidiumMaterial)" in vertex_encoder
assert "color.a = float((vertex.nvidiumMaterial >> 8u) & 0xFFu) * COLOR_SCALE;" in adapter
assert "insertAfterExtensions(mesh, MESH_UNIFORM_ALIASES" in adapter
assert "insertAfterExtensions(fragment, FRAGMENT_UNIFORM_ALIASES" in adapter
assert "uniform int caustiqHeldBlockLightValue;" in translator
assert 'Map.entry("heldBlockLightValue", "caustiqHeldBlockLightValue")' in translator
assert 'integer(program, "caustiqHeldBlockLightValue", heldBlockLightValue(InteractionHand.MAIN_HAND))' in uniform_bridge
assert '"getLightEmission"' in uniform_bridge and "getItemInHand" in uniform_bridge and "InteractionHand.class" in uniform_bridge
assert "heldBlockLightValue(InteractionHand.MAIN_HAND)" in uniform_bridge
assert "heldBlockLightValue(InteractionHand.OFF_HAND)" in uniform_bridge
assert "BlockItemStateProperties properties" in uniform_bridge
assert "properties.apply(state)" in uniform_bridge and "state.getLightEmission()" in uniform_bridge
assert 'Map.entry("heldBlockLightValue2", "caustiqHeldBlockLightValue2")' in translator
assert "uniform int caustiqHeldBlockLightValue2;" in translator
assert 'integer(program, "caustiqHeldBlockLightValue2", heldBlockLightValue(InteractionHand.OFF_HAND))' in uniform_bridge
assert "private static int heldBlockLightValue(InteractionHand hand)" in uniform_bridge
assert "packedBlockEntityId" in adapter and "packedTangentAngle" in adapter
for field in ("blockId", "normalTangent", "midpointUv", "midBlock", "nvidiumMaterial"):
    assert field in vertex_encoder
format_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumExtendedVertexFormatMixin.java").read_text(encoding="utf-8")
compatibility = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumCompatibility.java").read_text(encoding="utf-8")
stride_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumRepackagedStrideMixin.java").read_text(encoding="utf-8")
assert "NvidiumIrisChunkVertex.INSTANCE" in format_mixin
assert "NvidiumIrisChunkVertex.isCommitted()" in format_mixin
assert 'method = "<init>", at = @At("HEAD")' in format_mixin
assert "enableExtendedVertexFormat()" in format_mixin
assert "requestExtendedVertexFormat()" in compatibility
assert "NvidiumIrisChunkVertex.disable()" in compatibility
assert "CaustiqConfig.get().enabled()" in compatibility
assert "IS_COMPATIBLE" in compatibility and "FORCE_DISABLE" in compatibility
assert "setSodiumVertexFormat(nvidium)" in compatibility
assert "formatField.setBoolean(config, true)" in compatibility
assert "NvidiumIrisChunkVertex.commit()" in compatibility
assert "configuration will be committed after Nvidium's capability check" in compatibility
assert "isCommitted()" in (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumIrisChunkVertex.java").read_text(encoding="utf-8")
bridge = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumShaderProgramBridge.java").read_text(encoding="utf-8")
abi_state = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumVertexAbiState.java").read_text(encoding="utf-8")
assert "static boolean isCommitted()" in abi_state
assert "NvidiumVertexAbiState.commit()" in vertex_encoder
assert "NvidiumVertexAbiState.isCommitted()" in bridge
assert "SODIUM_VERTEX_FORMAT_DEFINE" in adapter
preprocessor = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/shaderpack/IrisGlslConditionalPreprocessor.java").read_text(encoding="utf-8")
assert "maskComments(normalized)" in preprocessor
assert "private static String maskComments(String source)" in preprocessor
assert "public static String stripComments(String source)" in preprocessor
assert "IrisGlslConditionalPreprocessor.stripComments(source)" in translator
assert "preservesCodeInsideIrisSlashStarToggleComments" in (caustiq_root / "src/test/java/dev/itsdropper/caustiq/shaderpack/IrisGlslConditionalPreprocessorTest.java").read_text(encoding="utf-8")
translator = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/integration/nvidium/IrisNvidiumTerrainTranslator.java").read_text(encoding="utf-8")
assert "softwareRenderHarnessSources()" in translator
render_smoke = (caustiq_root / "src/test/java/dev/itsdropper/caustiq/integration/nvidium/NvidiumIrisTerrainGlRenderSmokeTest.java").read_text(encoding="utf-8")
assert "rendersTheTranslatedPhotonTerrainVertexAndFragmentProgramsOnMesa" in render_smoke
assert 'dumpShaderSources("before-adaptation", original)' in bridge
assert 'dumpShaderSources("compiled", transformed)' in bridge
assert "caustiq.dumpNvidiumShaders" in bridge
assert "NvidiumIrisChunkVertex.STRIDE" in stride_mixin
assert "NvidiumIrisChunkVertex.isCommitted()" in stride_mixin
assert "@ModifyConstant(" in stride_mixin, "copyQuad stride constants must be modified after javac inlines the static final fields"
assert "@Constant(longValue = 80L)" in stride_mixin and "@Constant(longValue = 64L)" in stride_mixin
assert "NvidiumIrisChunkVertex.STRIDE * 4L" in stride_mixin, "copyQuad must copy four extended 40-byte vertices"
assert "@Redirect(" not in stride_mixin, "Compile-time STRIDE constants cannot be redirected as field reads"
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
assert "#define Vertex uvec4" in scene, "Pinned Nvidium fallback Vertex ABI changed"
assert "struct Vertex {" in scene, "Pinned Nvidium Sodium Vertex ABI changed"
assert "NvidiumTerrainVaryingAdapter.class" in (caustiq_root / ".github/workflows/build.yml").read_text(encoding="utf-8")

block_parser = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/shaderpack/IrisBlockPropertiesParser.java").read_text(encoding="utf-8")
assert 'if (!value.contains(":")) value = "minecraft:" + value;' in block_parser
print("Nvidium source contract verified: compile hook, Iris gate, packed vertex format, scene uniforms, sampler bindings, and varying ABI.")

# block.properties uses MC_VERSION conditionals to separate legacy and modern block IDs.
block_parser = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/shaderpack/IrisBlockPropertiesParser.java").read_text(encoding="utf-8")
shader_runtime = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/shaderpack/ShaderRuntime.java").read_text(encoding="utf-8")
assert "VERSION_CONDITION" in block_parser and "activeLines(source, mcVersion)" in block_parser
assert '.parse(sources.read("block.properties"), minecraftVersionCode())' in shader_runtime
