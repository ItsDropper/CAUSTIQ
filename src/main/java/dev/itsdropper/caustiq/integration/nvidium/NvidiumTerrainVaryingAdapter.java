package dev.itsdropper.caustiq.integration.nvidium;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Extends Nvidium's native terrain varying ABI with the data needed by a
 * constrained Iris-style terrain fragment profile.
 *
 * <p>This does not execute arbitrary Iris GLSL. It exposes only values that
 * can be reconstructed from Nvidium's packed terrain vertices: UV, raw vertex
 * tint, lightmap UV, world position, and a geometric face normal.</p>
 */
public final class NvidiumTerrainVaryingAdapter {
    public static final String PROFILE_ID = "caustiq-terrain-varyings-v1";
    private static final String MARKER = "#define CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS 1";
    private static final String MESH_VARYINGS = String.join("\n",
            "    vec2 uv;",
            "    vec3 v_colour;",
            "    vec3 v_vertexColour;",
            "    vec2 v_lightmapCoord;",
            "    vec3 v_worldPosition;",
            "    vec3 v_normal;");
    private static final String FRAGMENT_VARYINGS = MESH_VARYINGS;
    private static final String MESH_ASSIGNMENTS = String.join("\n",
            "    OUT[id].uv = decodeVertexUV(V);",
            "    OUT[id].v_colour = computeMultiplier(V);",
            "    OUT[id].v_vertexColour = decodeVertexColour(V).rgb;",
            "    OUT[id].v_lightmapCoord = decodeLightUV(V);",
            "    OUT[id].v_worldPosition = decodeVertexPosition(V) + origin;",
            "    OUT[id].v_normal = caustiqTriangleNormal;");
    private static final String NORMAL_HELPER = String.join("\n",
            "vec3 caustiqTriangleNormal;",
            "",
            "vec3 caustiqComputeTriangleNormal(uint quadId, bool triangle1) {",
            "    Vertex a = terrainData[(quadId << 2) + (triangle1 ? 2u : 0u)];",
            "    Vertex b = terrainData[(quadId << 2) + (triangle1 ? 3u : 1u)];",
            "    Vertex c = terrainData[(quadId << 2) + (triangle1 ? 0u : 2u)];",
            "    vec3 pa = decodeVertexPosition(a);",
            "    vec3 pb = decodeVertexPosition(b);",
            "    vec3 pc = decodeVertexPosition(c);",
            "    return normalize(cross(pb - pa, pc - pa));",
            "}") + "\n";

    private NvidiumTerrainVaryingAdapter() { }

    /**
     * Adapts the exact Nvidium 0.4.3 terrain shader interface. Unknown source
     * layouts fail closed; silently patching a different Nvidium shader is unsafe.
     */
    public static Map<String, String> adapt(Map<String, String> sourceStages) {
        Objects.requireNonNull(sourceStages, "sourceStages");
        if (!sourceStages.keySet().equals(java.util.Set.of("TASK", "MESH", "FRAGMENT"))) {
            throw new IllegalArgumentException("Expected Nvidium TASK, MESH and FRAGMENT stages.");
        }

        String mesh = Objects.requireNonNull(sourceStages.get("MESH"), "MESH source");
        String fragment = Objects.requireNonNull(sourceStages.get("FRAGMENT"), "FRAGMENT source");
        if (mesh.contains(MARKER) || fragment.contains(MARKER)) {
            if (mesh.contains(MARKER) && fragment.contains(MARKER)) return Map.copyOf(sourceStages);
            throw new IllegalStateException("Nvidium terrain varying profile is only partially applied.");
        }

        mesh = replaceOnce(mesh,
                "#version 460",
                "#version 460\n" + MARKER,
                "mesh version");
        fragment = replaceOnce(fragment,
                "#version 460",
                "#version 460\n" + MARKER,
                "fragment version");
        mesh = replaceOnce(mesh,
                "    vec2 uv;\n    vec3 v_colour;",
                MESH_VARYINGS,
                "mesh interpolant declaration");
        fragment = replaceOnce(fragment,
                "    vec2 uv;\n    vec3 v_colour;",
                FRAGMENT_VARYINGS,
                "fragment interpolant declaration");
        mesh = replaceOnce(mesh,
                "vec4 transformVertex(Vertex V) {",
                NORMAL_HELPER + "\nvec4 transformVertex(Vertex V) {",
                "normal helper insertion point");
        mesh = replaceOnce(mesh,
                "    bool triangle1 = (gl_LocalInvocationIndex & uint(1)) == 1;",
                "    bool triangle1 = (gl_LocalInvocationIndex & uint(1)) == 1;\n"
                        + "    caustiqTriangleNormal = caustiqComputeTriangleNormal(quadId, triangle1);",
                "triangle selection");
        mesh = replaceOnce(mesh,
                "    OUT[id].uv = decodeVertexUV(V);\n    OUT[id].v_colour = computeMultiplier(V);",
                MESH_ASSIGNMENTS,
                "mesh varying assignments");

        Map<String, String> adapted = new LinkedHashMap<>(sourceStages);
        adapted.put("MESH", mesh);
        adapted.put("FRAGMENT", fragment);
        return Map.copyOf(adapted);
    }

    private static String replaceOnce(String source, String expected, String replacement, String label) {
        int first = source.indexOf(expected);
        if (first < 0 || source.indexOf(expected, first + expected.length()) >= 0) {
            throw new IllegalStateException("Unsupported Nvidium terrain shader layout at " + label + ".");
        }
        return source.substring(0, first) + replacement + source.substring(first + expected.length());
    }
}
