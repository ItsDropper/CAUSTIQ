package dev.itsdropper.caustiq.integration.nvidium;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extends Nvidium's native terrain interpolant ABI with values reconstructible
 * from its packed quad data. All generated values are explicitly geometric;
 * this adapter does not invent block IDs, model normals, or world transforms.
 */
public final class NvidiumTerrainVaryingAdapter {
    public static final String PROFILE_ID = "caustiq-terrain-varyings-v2";
    private static final String MARKER = "#define CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS 2";
    private static final Pattern BARYCENTRIC_DEFINE = Pattern.compile(
            "(?m)^\\s*#\\s*define\\s+USE_NV_FRAGMENT_SHADER_BARYCENTRIC\\b[^\\r\\n]*");
    private static final String MESH_VARYINGS = String.join("\n",
            "    vec2 uv;",
            "    vec3 v_colour;",
            "    vec3 v_vertexColour;",
            "    vec2 v_lightmapCoord;",
            "    vec3 v_renderPosition;",
            "    vec2 v_quadMidUv;",
            "    vec3 v_quadMidPosition;",
            "    vec3 v_normal;",
            "    vec4 v_tangent;",
            "    flat mat3 v_tbn;",
            "    flat uint v_materialFlags;");
    private static final String FRAGMENT_VARYINGS = MESH_VARYINGS;
    private static final String FRAGMENT_UNIFORM_ALIASES =
            NvidiumIrisTerrainAbi.fragmentAliasDeclarations();
    private static final String MESH_ASSIGNMENTS = String.join("\n",
            "    OUT[id].uv = decodeVertexUV(V);",
            "    OUT[id].v_colour = computeMultiplier(V);",
            "    OUT[id].v_vertexColour = decodeVertexColour(V).rgb;",
            "    OUT[id].v_lightmapCoord = decodeLightUV(V);",
            "    OUT[id].v_renderPosition = (transformMat * vec4(decodeVertexPosition(V) + origin, 1.0)).xyz;",
            "    OUT[id].v_quadMidUv = caustiqQuadMidUv(quadId);",
            "    OUT[id].v_quadMidPosition = caustiqQuadMidPosition(quadId);",
            "    OUT[id].v_normal = caustiqTriangleNormal;",
            "    OUT[id].v_tangent = caustiqTriangleTangent;",
            "    OUT[id].v_tbn = caustiqTriangleTbn;",
            "    OUT[id].v_materialFlags = (hasMipping(V) ? 1u : 0u) | (rawVertexAlphaCutoff(V) << 1u);");
    private static final String NORMAL_HELPER = String.join("\n",
            "vec3 caustiqTriangleNormal;",
            "vec4 caustiqTriangleTangent;",
            "mat3 caustiqTriangleTbn;",
            "",
            "vec3 caustiqRenderPosition(Vertex vertex) {",
            "    return (transformMat * vec4(decodeVertexPosition(vertex) + origin, 1.0)).xyz;",
            "}",
            "",
            "vec2 caustiqQuadMidUv(uint quadId) {",
            "    uint base = quadId << 2;",
            "    return (decodeVertexUV(terrainData[base]) + decodeVertexUV(terrainData[base + 1u])",
            "          + decodeVertexUV(terrainData[base + 2u]) + decodeVertexUV(terrainData[base + 3u])) * 0.25;",
            "}",
            "",
            "vec3 caustiqQuadMidPosition(uint quadId) {",
            "    uint base = quadId << 2;",
            "    return (caustiqRenderPosition(terrainData[base]) + caustiqRenderPosition(terrainData[base + 1u])",
            "          + caustiqRenderPosition(terrainData[base + 2u]) + caustiqRenderPosition(terrainData[base + 3u])) * 0.25;",
            "}",
            "",
            "void caustiqComputeTriangleBasis(uint quadId, bool triangle1) {",
            "    uint base = quadId << 2;",
            "    Vertex a = terrainData[base + (triangle1 ? 2u : 0u)];",
            "    Vertex b = terrainData[base + (triangle1 ? 3u : 1u)];",
            "    Vertex c = terrainData[base + (triangle1 ? 0u : 2u)];",
            "    vec3 pa = caustiqRenderPosition(a);",
            "    vec3 pb = caustiqRenderPosition(b);",
            "    vec3 pc = caustiqRenderPosition(c);",
            "    vec2 ta = decodeVertexUV(a);",
            "    vec2 tb = decodeVertexUV(b);",
            "    vec2 tc = decodeVertexUV(c);",
            "    vec3 edge1 = pb - pa;",
            "    vec3 edge2 = pc - pa;",
            "    vec2 delta1 = tb - ta;",
            "    vec2 delta2 = tc - ta;",
            "    vec3 face = cross(edge1, edge2);",
            "    float faceLength2 = dot(face, face);",
            "    caustiqTriangleNormal = faceLength2 > 1e-12 ? face * inversesqrt(faceLength2) : vec3(0.0, 1.0, 0.0);",
            "    float determinant = delta1.x * delta2.y - delta1.y * delta2.x;",
            "    vec3 tangent;",
            "    vec3 bitangent;",
            "    if (abs(determinant) > 1e-10 && faceLength2 > 1e-12) {",
            "        float inverseDeterminant = 1.0 / determinant;",
            "        tangent = (edge1 * delta2.y - edge2 * delta1.y) * inverseDeterminant;",
            "        bitangent = (edge2 * delta1.x - edge1 * delta2.x) * inverseDeterminant;",
            "        tangent = normalize(tangent - caustiqTriangleNormal * dot(caustiqTriangleNormal, tangent));",
            "        bitangent = normalize(bitangent - caustiqTriangleNormal * dot(caustiqTriangleNormal, bitangent));",
            "    } else {",
            "        vec3 axis = abs(caustiqTriangleNormal.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);",
            "        tangent = normalize(cross(axis, caustiqTriangleNormal));",
            "        bitangent = normalize(cross(caustiqTriangleNormal, tangent));",
            "    }",
            "    float handedness = dot(cross(caustiqTriangleNormal, tangent), bitangent) < 0.0 ? -1.0 : 1.0;",
            "    caustiqTriangleTangent = vec4(tangent, handedness);",
            "    caustiqTriangleTbn = mat3(tangent, bitangent, caustiqTriangleNormal);",
            "}";

    private NvidiumTerrainVaryingAdapter() { }

    /** Adapts the pinned Nvidium terrain shader ABI; unknown layouts fail closed. */
    public static Map<String, String> adapt(Map<String, String> sourceStages) {
        Objects.requireNonNull(sourceStages, "sourceStages");
        if (!sourceStages.keySet().equals(Set.of("TASK", "MESH", "FRAGMENT"))) {
            throw new IllegalArgumentException("Expected Nvidium TASK, MESH and FRAGMENT stages.");
        }

        String mesh = disableBarycentricPath(Objects.requireNonNull(sourceStages.get("MESH"), "MESH source"));
        String fragment = disableBarycentricPath(Objects.requireNonNull(sourceStages.get("FRAGMENT"), "FRAGMENT source"));
        if (mesh.contains(MARKER) || fragment.contains(MARKER)) {
            boolean complete = mesh.contains(MARKER) && fragment.contains(MARKER)
                    && mesh.contains(MESH_VARYINGS) && fragment.contains(FRAGMENT_VARYINGS)
                    && mesh.contains(MESH_ASSIGNMENTS) && mesh.contains(NORMAL_HELPER)
                    && fragment.contains(FRAGMENT_UNIFORM_ALIASES);
            if (complete) return Map.copyOf(sourceStages);
            throw new IllegalStateException("Nvidium terrain varying profile is only partially applied or has an incompatible ABI.");
        }

        mesh = replaceOnce(mesh, "#version 460", "#version 460\n" + MARKER, "mesh version");
        fragment = replaceOnce(fragment, "#version 460", "#version 460\n" + MARKER, "fragment version");
        mesh = replaceOnce(mesh, "    vec2 uv;\n    vec3 v_colour;", MESH_VARYINGS, "mesh interpolant declaration");
        fragment = replaceOnce(fragment, "    vec2 uv;\n    vec3 v_colour;", FRAGMENT_VARYINGS, "fragment interpolant declaration");
        fragment = replaceOnce(fragment, MARKER + "\n",
                MARKER + "\n" + FRAGMENT_UNIFORM_ALIASES, "fragment uniform aliases");
        mesh = replaceOnce(mesh, "void putVertex(uint id, Vertex V) {",
                "void putVertex(uint id, Vertex V, uint quadId) {", "mesh varying writer signature");
        mesh = replaceOnce(mesh, "putVertex(qId+uint(triangle1), Vc);",
                "putVertex(qId+uint(triangle1), Vc, quadId);", "common vertex varying call");
        mesh = replaceOnce(mesh, "putVertex(uId, V);",
                "putVertex(uId, V, quadId);", "unique vertex varying call");
        mesh = replaceOnce(mesh, "vec4 transformVertex(Vertex V) {",
                NORMAL_HELPER + "\n\nvec4 transformVertex(Vertex V) {", "terrain basis helper insertion point");
        mesh = replaceOnce(mesh, "    bool triangle1 = (gl_LocalInvocationIndex & uint(1)) == 1;",
                "    bool triangle1 = (gl_LocalInvocationIndex & uint(1)) == 1;\n"
                        + "    caustiqComputeTriangleBasis(quadId, triangle1);", "triangle selection");
        mesh = replaceOnce(mesh, "    OUT[id].uv = decodeVertexUV(V);\n    OUT[id].v_colour = computeMultiplier(V);",
                MESH_ASSIGNMENTS, "mesh varying assignments");

        Map<String, String> adapted = new LinkedHashMap<>(sourceStages);
        adapted.put("MESH", mesh);
        adapted.put("FRAGMENT", fragment);
        return Map.copyOf(adapted);
    }

    private static String disableBarycentricPath(String source) {
        return BARYCENTRIC_DEFINE.matcher(source)
                .replaceAll("// CAUSTIQ terrain varyings require the interpolant path");
    }

    private static String replaceOnce(String source, String expected, String replacement, String label) {
        int first = source.indexOf(expected);
        if (first < 0 || source.indexOf(expected, first + expected.length()) >= 0) {
            throw new IllegalStateException("Unsupported Nvidium terrain shader layout at " + label + ".");
        }
        return source.substring(0, first) + replacement + source.substring(first + expected.length());
    }
}
