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
    void addsUvTintLightmapWorldPositionAndFaceNormalToMeshFragmentAbi() {
        Map<String, String> adapted = NvidiumTerrainVaryingAdapter.adapt(sourceStages());
        String mesh = adapted.get("MESH");
        String fragment = adapted.get("FRAGMENT");

        assertTrue(mesh.contains("#define CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS 1"));
        assertTrue(fragment.contains("#define CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS 1"));
        for (String varying : new String[]{
                "vec2 uv;", "vec3 v_colour;", "vec3 v_vertexColour;",
                "vec2 v_lightmapCoord;", "vec3 v_worldPosition;", "vec3 v_normal;"
        }) {
            assertTrue(mesh.contains(varying), "Mesh output missing " + varying);
            assertTrue(fragment.contains(varying), "Fragment input missing " + varying);
        }
        assertTrue(mesh.contains("decodeVertexColour(V).rgb"));
        assertTrue(mesh.contains("decodeLightUV(V)"));
        assertTrue(mesh.contains("transformMat * vec4(decodeVertexPosition(V) + origin, 1.0)"));
        assertTrue(mesh.contains("cross(pb - pa, pc - pa)"));
        assertTrue(mesh.contains("caustiqComputeTriangleNormal(quadId, triangle1)"));
        assertEquals("task source", adapted.get("TASK"));
    }

    @Test
    void disablesNvidiumBarycentricDefineWhenAdditionalInterpolantsAreRequired() {
        Map<String, String> base = sourceStages();
        Map<String, String> sources = Map.of(
                "TASK", base.get("TASK"),
                "MESH", base.get("MESH").replace("#version 460",
                        "#version 460\\n#define USE_NV_FRAGMENT_SHADER_BARYCENTRIC"),
                "FRAGMENT", base.get("FRAGMENT").replace("#version 460",
                        "#version 460\\n#define USE_NV_FRAGMENT_SHADER_BARYCENTRIC"));

        Map<String, String> adapted = NvidiumTerrainVaryingAdapter.adapt(sources);
        assertFalse(adapted.get("MESH").contains("#define USE_NV_FRAGMENT_SHADER_BARYCENTRIC"));
        assertFalse(adapted.get("FRAGMENT").contains("#define USE_NV_FRAGMENT_SHADER_BARYCENTRIC"));
        assertTrue(adapted.get("MESH").contains("layout(location=1) out Interpolants"));
        assertTrue(adapted.get("FRAGMENT").contains("layout(location = 1) in Interpolants"));
    }

    @Test
    void adaptsThePinnedNvidiumTerrainShaderSourcesWhenAvailable() throws IOException {
        String sourceDirectory = System.getenv("NVIDIUM_SOURCE_DIR");
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
        assertTrue(adapted.get("FRAGMENT").contains("vec3 v_worldPosition;"));
        assertEquals(sources.get("TASK"), adapted.get("TASK"));
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
                    bool triangle1 = (gl_LocalInvocationIndex & uint(1)) == 1;
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
