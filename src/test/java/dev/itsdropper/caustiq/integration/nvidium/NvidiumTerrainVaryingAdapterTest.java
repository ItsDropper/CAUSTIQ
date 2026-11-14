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

        assertTrue(mesh.contains("#define CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS 1"));
        assertTrue(fragment.contains("#define CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS 1"));
        for (String varying : new String[]{
                "vec2 uv;", "vec3 v_colour;", "vec3 v_vertexColour;",
                "vec2 v_lightmapCoord;", "vec3 v_renderPosition;", "vec2 v_quadMidUv;",
                "vec3 v_quadMidPosition;", "vec3 v_normal;", "vec4 v_tangent;",
                "flat mat3 v_tbn;", "flat uint v_materialFlags;"
        }) {
            assertTrue(mesh.contains(varying), "Mesh output missing " + varying);
            assertTrue(fragment.contains(varying), "Fragment input missing " + varying);
        }
        assertTrue(mesh.contains("decodeVertexColour(V).rgb"));
        assertTrue(mesh.contains("decodeLightUV(V)"));
        assertTrue(mesh.contains("rawVertexAlphaCutoff(V) << 1u"));
        assertTrue(mesh.contains("hasMipping(V) ? 1u : 0u"));
        assertTrue(fragment.contains("#define CAUSTIQ_IRIS_VIEW_WIDTH (screenSize.x * 2.0)"));
        assertTrue(fragment.contains("#define CAUSTIQ_IRIS_VIEW_HEIGHT (screenSize.y * 2.0)"));
        assertTrue(fragment.contains("#define CAUSTIQ_IRIS_GTEXTURE tex_diffuse"));
        assertTrue(fragment.contains("#define CAUSTIQ_IRIS_LIGHTMAP tex_light"));
        assertTrue(mesh.contains("transformMat * vec4(decodeVertexPosition(V) + origin, 1.0)"));
        assertTrue(mesh.contains("cross(edge1, edge2)"));
        assertTrue(mesh.contains("caustiqQuadMidUv(uint quadId)"));
        assertTrue(mesh.contains("caustiqQuadMidPosition(uint quadId)"));
        assertTrue(mesh.contains("caustiqComputeTriangleBasis(uint quadId, bool triangle1)"));
        assertTrue(mesh.contains("caustiqTriangleTbn = mat3(tangent, bitangent, caustiqTriangleNormal)"));
        assertTrue(mesh.contains("putVertex(uint id, Vertex V, uint quadId)"));
        assertTrue(mesh.contains("putVertex(qId+uint(triangle1), Vc, quadId)"));
        assertTrue(mesh.contains("putVertex(uId, V, quadId)"));
        assertTrue(mesh.contains("caustiqComputeTriangleNormal(quadId, triangle1)"));
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
        Map<String, String> sources = Map.of(
                "TASK", Files.readString(shaderRoot.resolve("task.glsl")),
                "MESH", Files.readString(shaderRoot.resolve("mesh.glsl")),
                "FRAGMENT", Files.readString(shaderRoot.resolve("frag.frag")));

        Map<String, String> adapted = NvidiumTerrainVaryingAdapter.adapt(sources);
        assertTrue(adapted.get("MESH").contains("caustiqComputeTriangleNormal(quadId, triangle1)"));
        assertTrue(adapted.get("MESH").contains("OUT[id].v_lightmapCoord = decodeLightUV(V);"));
        assertTrue(adapted.get("MESH").contains("OUT[id].v_quadMidUv = caustiqQuadMidUv(quadId);"));
        assertTrue(adapted.get("MESH").contains("OUT[id].v_tbn = caustiqTriangleTbn;"));
        assertTrue(adapted.get("FRAGMENT").contains("vec3 v_renderPosition;"));
        assertTrue(adapted.get("FRAGMENT").contains("flat mat3 v_tbn;"));
        assertTrue(adapted.get("FRAGMENT").contains("flat uint v_materialFlags;"));
        assertEquals(sources.get("TASK"), adapted.get("TASK"));
    }

    @Test
    void adaptingAnAlreadyAdaptedProgramIsIdempotent() {
        Map<String, String> once = NvidiumTerrainVaryingAdapter.adapt(sourceStages());
        assertEquals(once, NvidiumTerrainVaryingAdapter.adapt(once));
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
                "MESH", sources.get("MESH") + "\n#define CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS 1",
                "FRAGMENT", sources.get("FRAGMENT"));
        assertThrows(IllegalStateException.class,
                () -> NvidiumTerrainVaryingAdapter.adapt(partiallyAdapted));
    }

    private static Map<String, String> sourceStages() {
        String mesh = """
                #version 460
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
                    bool triangle1 = (gl_LocalInvocationIndex & uint(1)) == 1;
                    putVertex(0u, Vc);
                    putVertex(1u, V, quadId);
                }
                """;
        String fragment = """
                #version 460
                layout(location = 1) in Interpolants {
                    vec2 uv;
                    vec3 v_colour;
                };
                void main() {}
                """;
        return Map.of("TASK", "task source", "MESH", mesh, "FRAGMENT", fragment);
    }
}
