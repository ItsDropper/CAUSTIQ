package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NvidiumTerrainVaryingAdapterTest {
    @Test
    void addsUvTintLightmapRenderPositionQuadMidpointsTangentBasisAndMaterialFlags() {
        Map<String, String> adapted = NvidiumTerrainVaryingAdapter.adapt(sourceStages());
        String mesh = adapted.get("MESH");
        String fragment = adapted.get("FRAGMENT");

        assertTrue(mesh.contains("#define CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS 5"));
        assertTrue(fragment.contains("#define CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS 4"));
        assertTrue(mesh.indexOf("#extension GL_ARB_gpu_shader5 : require")
                < mesh.indexOf("uniform mat4 caustiqProjection;"),
                "Mesh uniform declarations must follow GLSL extension directives");
        assertTrue(fragment.indexOf("#extension GL_ARB_gpu_shader5 : require")
                < fragment.indexOf("uniform mat4 caustiqProjection;"),
                "Fragment uniform declarations must follow GLSL extension directives");
        for (String varying : new String[]{
                "vec2 uv;", "vec3 v_colour;", "vec3 v_vertexColour;",
                "vec2 v_lightmapCoord;", "vec3 v_renderPosition;", "vec2 v_quadMidUv;",
                "vec3 v_quadMidPosition;", "vec3 v_midBlock;", "vec3 v_blockEntity;",
                "vec3 v_normal;", "vec4 v_tangent;", "flat uint v_materialFlags;"
        }) {
            assertTrue(mesh.contains(varying), "Mesh output missing " + varying);
            assertTrue(fragment.contains(varying), "Fragment input missing " + varying);
        }
        assertTrue(mesh.contains("decodeVertexColour(V).rgb"));
        assertTrue(mesh.contains("color.a = float((vertex.nvidiumMaterial >> 8u) & 0xFFu) * COLOR_SCALE;"));
        assertTrue(mesh.contains("decodeLightUV(V)"));
        assertTrue(mesh.contains("OUT[id].v_materialFlags = V.nvidiumMaterial & 0xFFu;"));
        assertTrue(mesh.contains("uint nvidiumMaterial;"));
        assertTrue(mesh.contains("caustiqDecodeBlockEntity(V.blockId)"));
        assertTrue(mesh.contains("caustiqDecodeMidUv(V)"));
        assertTrue(mesh.contains("caustiqDecodeMidBlock(V)"));
        assertTrue(mesh.contains("caustiqDecodeTangent(V)"));
        assertTrue(fragment.contains("#define CAUSTIQ_IRIS_VIEW_WIDTH (screenSize.x * 2.0)"));
        assertTrue(fragment.contains("#define CAUSTIQ_IRIS_VIEW_HEIGHT (screenSize.y * 2.0)"));
        assertTrue(fragment.contains("#define CAUSTIQ_IRIS_GTEXTURE tex_diffuse"));
        assertTrue(fragment.contains("#define CAUSTIQ_IRIS_LIGHTMAP tex_light"));
        for (String output : new String[]{
                "layout(location = 1) out vec4 caustiqGBufferNormal;",
                "layout(location = 2) out vec4 caustiqGBufferPosition;",
                "layout(location = 3) out vec4 caustiqGBufferMaterial;",
                "layout(location = 4) out vec4 caustiqGBufferLightmap;",
                "caustiqGBufferNormal = vec4(normalize(v_normal) * 0.5 + 0.5, 1.0);",
                "caustiqGBufferPosition = vec4(v_renderPosition, 1.0);",
                "caustiqGBufferMaterial = vec4(v_vertexColour, float(v_materialFlags) / 255.0);",
                "caustiqGBufferLightmap = vec4(v_lightmapCoord, 0.0, 1.0);"}) {
            assertTrue(fragment.contains(output), "Fragment G-buffer ABI missing " + output);
        }
        assertTrue(fragment.contains("void main() {\n    caustiqGBufferNormal ="),
                "Injected G-buffer writes must start on a new GLSL source line");
        assertTrue(mesh.contains("transformMat * vec4(decodeVertexPosition(V) + origin, 1.0)"));
        assertTrue(mesh.contains("vec3 caustiqDecodeNormal(Vertex vertex)"));
        assertTrue(mesh.contains("vec4 caustiqDecodeTangent(Vertex vertex)"));
        assertTrue(mesh.contains("vec2 caustiqDecodeMidUv(Vertex vertex)"));
        assertTrue(mesh.contains("vec3 caustiqDecodeMidBlock(Vertex vertex)"));
        assertFalse(java.util.regex.Pattern.compile("\\bpacked\\b").matcher(mesh).find(),
                "Generated GLSL must not use the reserved layout qualifier token as an identifier");
        assertFalse(mesh.contains("flat mat3 v_tbn;"), "Unused TBN matrix must not consume three inter-stage locations");
        assertFalse(fragment.contains("flat mat3 v_tbn;"));
        assertFalse(mesh.contains("OUT[id].v_tbn ="));
        assertEquals("task source", adapted.get("TASK"));
    }

    @Test
    void disablesNvidiumBarycentricDefineWhenAdditionalInterpolantsAreRequired() {
        Map<String, String> base = sourceStages();
        Map<String, String> sources = Map.of(
                "TASK", base.get("TASK"),
                "MESH", base.get("MESH").replace("#version 460",
                        "#version 460\n#define USE_NV_FRAGMENT_SHADER_BARYCENTRIC"),
                "FRAGMENT", base.get("FRAGMENT").replace("#version 460",
                        "#version 460\n#define USE_NV_FRAGMENT_SHADER_BARYCENTRIC"));

        Map<String, String> adapted = NvidiumTerrainVaryingAdapter.adapt(sources);
        assertFalse(adapted.get("MESH").contains("#define USE_NV_FRAGMENT_SHADER_BARYCENTRIC"));
        assertFalse(adapted.get("FRAGMENT").contains("#define USE_NV_FRAGMENT_SHADER_BARYCENTRIC"));
        assertTrue(adapted.get("MESH").contains("layout(location=1) out Interpolants"));
        assertTrue(adapted.get("FRAGMENT").contains("layout(location = 1) in Interpolants"));
    }

    @Test
    void adaptsThePinnedNvidiumTerrainShaderSourcesWhenAvailable() throws IOException {
        String sourceDirectory = System.getenv("NVIDIUM_SOURCE_DIR");
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            assertNotNull(sourceDirectory, "CI must supply the pinned Nvidium source checkout");
            assertFalse(sourceDirectory.isBlank(), "CI supplied an empty Nvidium source path");
        }
        Assumptions.assumeTrue(sourceDirectory != null && !sourceDirectory.isBlank(),
                "Pinned Nvidium source checkout is supplied by CI only");
        Path shaderRoot = Path.of(sourceDirectory, "src/main/resources/assets/nvidium/shaders/terrain");
        String scene = Files.readString(shaderRoot.getParent().resolve("occlusion/scene.glsl"));
        String vertexFormat = Files.readString(shaderRoot.resolve("vertex_format/vertex_format.glsl"));
        String sodiumFormat = Files.readString(shaderRoot.resolve("vertex_format/sodium_vertex_format.glsl"));
        Map<String, String> sources = Map.of(
                "TASK", Files.readString(shaderRoot.resolve("task.glsl")),
                "MESH", "#define USE_SODIUM_VERTEX_FORMAT\n" + scene + "\n" + sodiumFormat + "\n" + vertexFormat + "\n" + Files.readString(shaderRoot.resolve("mesh.glsl")),
                "FRAGMENT", "#define USE_SODIUM_VERTEX_FORMAT\n" + scene + "\n" + sodiumFormat + "\n" + vertexFormat + "\n" + Files.readString(shaderRoot.resolve("frag.frag")));

        Map<String, String> adapted = NvidiumTerrainVaryingAdapter.adapt(sources);
        assertTrue(adapted.get("MESH").contains("caustiqDecodeTangent(V)"));
        assertTrue(adapted.get("MESH").contains("uint nvidiumMaterial;"));
        assertTrue(adapted.get("MESH").contains("OUT[id].v_midBlock = caustiqDecodeMidBlock(V);"));
        assertTrue(adapted.get("MESH").contains("OUT[id].v_lightmapCoord = decodeLightUV(V);"));
        assertTrue(adapted.get("MESH").contains("OUT[id].v_quadMidUv = caustiqDecodeMidUv(V);"));
        assertTrue(adapted.get("MESH").contains("OUT[id].v_tbn = caustiqDecodeTbn(V);"));
        assertTrue(adapted.get("FRAGMENT").contains("vec3 v_renderPosition;"));
        assertTrue(adapted.get("FRAGMENT").contains("flat mat3 v_tbn;"));
        assertTrue(adapted.get("FRAGMENT").contains("layout(location = 4) out vec4 caustiqGBufferLightmap;"));
        assertTrue(adapted.get("FRAGMENT").contains("flat uint v_materialFlags;"));
        assertEquals(sources.get("TASK"), adapted.get("TASK"));
    }

    @Test
    void adaptingAnAlreadyAdaptedProgramIsIdempotent() {
        Map<String, String> once = NvidiumTerrainVaryingAdapter.adapt(sourceStages());
        assertEquals(once, NvidiumTerrainVaryingAdapter.adapt(once));
    }

    @Test
    void rejectsNativeUvec4ShaderWhenExtendedEncoderWasRequested() {
        Map<String, String> base = sourceStages();
        Map<String, String> mismatched = Map.of(
                "TASK", base.get("TASK"),
                "MESH", base.get("MESH").replace("#define USE_SODIUM_VERTEX_FORMAT\n", ""),
                "FRAGMENT", base.get("FRAGMENT").replace("#define USE_SODIUM_VERTEX_FORMAT\n", ""));
        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> NvidiumTerrainVaryingAdapter.adapt(mismatched));
        assertTrue(exception.getMessage().contains("USE_SODIUM_VERTEX_FORMAT"));
        assertTrue(exception.getMessage().contains("uvec4"));
    }

    @Test
    void rejectsUnexpectedNvidiumShaderLayoutInsteadOfPartiallyPatchingIt() {
        Map<String, String> sources = sourceStages();
        sources = Map.of(
                "TASK", sources.get("TASK"),
                "MESH", sources.get("MESH").replace("vec3 v_colour;", "vec4 changedColour;"),
                "FRAGMENT", sources.get("FRAGMENT"));
        Map<String, String> unsupported = sources;
        assertThrows(IllegalStateException.class, () -> NvidiumTerrainVaryingAdapter.adapt(unsupported));
    }

    @Test
    void rejectsIncompleteStageSets() {
        assertThrows(IllegalArgumentException.class,
                () -> NvidiumTerrainVaryingAdapter.adapt(Map.of("MESH", "mesh")));
    }

    @Test
    void detectsPartiallyAppliedProfile() {
        Map<String, String> sources = sourceStages();
        Map<String, String> partiallyAdapted = Map.of(
                "TASK", sources.get("TASK"),
                "MESH", sources.get("MESH") + "\n#define CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS 4",
                "FRAGMENT", sources.get("FRAGMENT"));
        assertThrows(IllegalStateException.class,
                () -> NvidiumTerrainVaryingAdapter.adapt(partiallyAdapted));
    }

    private static Map<String, String> sourceStages() {
        String mesh = """
                #version 460
                #define USE_SODIUM_VERTEX_FORMAT
                #extension GL_ARB_gpu_shader5 : require
                layout(triangles, max_vertices=64, max_primitives=32) out;
                struct Vertex {
                    uint hi;
                    uint lo;
                    uint color;
                    uint16_t u;
                    uint16_t v;
                    uint8_t blockLight;
                    uint8_t skyLight;
                    uint8_t material;
                    uint8_t section;
                };
                vec4 decodeVertexColour(Vertex v) {
                    uvec3 packed_color = (uvec3(v.color) >> uvec3(0, 8, 16)) & uvec3(0xFFu);
                    return vec4(vec3(packed_color) * COLOR_SCALE, 1);
                }
                bool hasMipping(Vertex v) { return bool(int(v.material) & 1); }
                uint rawVertexAlphaCutoff(Vertex v) { return (int(v.material) >> 1) & 3; }
                layout(location=1) out Interpolants {
                    vec2 uv;
                    vec3 v_colour;
                } OUT[];
                Vertex Vc;
                Vertex V;
                vec4 transformVertex(Vertex V) {
                    return vec4(0.0);
                }
                void putVertex(uint id, Vertex V) {
                    OUT[id].uv = decodeVertexUV(V);
                    OUT[id].v_colour = computeMultiplier(V);
                }
                void main() {
                    uint quadId = 0u;
                    uint qId = 0u;
                    uint uId = 1u;
                    bool triangle1 = (gl_LocalInvocationIndex & uint(1)) == 1;
                    putVertex(qId+uint(triangle1), Vc);
                    putVertex(uId, V);
                }
                """;
        String fragment = """
                #version 460
                #define USE_SODIUM_VERTEX_FORMAT
                #extension GL_ARB_gpu_shader5 : require
                struct Vertex {
                    uint hi;
                    uint lo;
                    uint color;
                    uint16_t u;
                    uint16_t v;
                    uint8_t blockLight;
                    uint8_t skyLight;
                    uint8_t material;
                    uint8_t section;
                };
                bool hasMipping(Vertex v) { return bool(int(v.material) & 1); }
                uint rawVertexAlphaCutoff(Vertex v) { return (int(v.material) >> 1) & 3; }
                layout(location = 0) out vec4 colour;
                layout(location = 1) in Interpolants {
                    vec2 uv;
                    vec3 v_colour;
                };
                void main() {}
                """;
        return Map.of("TASK", "task source", "MESH", mesh, "FRAGMENT", fragment);
    }
    @Test
    void leavesNvidiumNonPrimaryTerrainProgramsUntouched() {
        Map<String, String> sources = Map.of(
                "TASK", "translucent task",
                "MESH", "#version 460\nlayout(triangles, max_vertices=128, max_primitives=64) out;\nvoid main() { gl_PrimitiveCountNV = 0; }",
                "FRAGMENT", "#version 460\nlayout(location = 0) out vec4 colour;\nlayout(location = 1) in Interpolants { vec2 uv; vec3 v_colour; };\nvoid main() {}");
        assertEquals(sources, NvidiumTerrainVaryingAdapter.adapt(sources));
    }

    @Test
    void rejectsPartiallyRecognizedPrimaryTerrainShaderLayouts() {
        Map<String, String> base = sourceStages();
        Map<String, String> sources = Map.of(
                "TASK", base.get("TASK"),
                "MESH", base.get("MESH"),
                "FRAGMENT", base.get("FRAGMENT").replace(
                        "layout(location = 1) in Interpolants {",
                        "layout(location = 2) in Interpolants {"));
        assertThrows(IllegalStateException.class, () -> NvidiumTerrainVaryingAdapter.adapt(sources));
    }

}
