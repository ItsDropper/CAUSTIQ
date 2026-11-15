package dev.itsdropper.caustiq.integration.nvidium;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extends Nvidium's Sodium-compatible terrain vertex ABI with Iris-style
 * block IDs, packed normal/tangent data, midpoint UV/block offsets, and the
 * Nvidium material flags kept separate from tangent handedness.
 */
public final class NvidiumTerrainVaryingAdapter {
    public static final String PROFILE_ID = "caustiq-iris-vertex-abi-v5";
    private static final String MARKER = "#define CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS 5";
    private static final String TRANSLUCENT_MARKER = "#define CAUSTIQ_NVIDIUM_TRANSLUCENT_VERTEX_ABI 1";
    private static final Pattern SODIUM_VERTEX_FORMAT_DEFINE = Pattern.compile(
            "(?m)^[\\t ]*#[\\t ]*define[\\t ]+USE_SODIUM_VERTEX_FORMAT\\b");
    private static final Pattern BARYCENTRIC_DEFINE = Pattern.compile(
            "(?m)^\\s*#\\s*define\\s+USE_NV_FRAGMENT_SHADER_BARYCENTRIC\\b[^\\r\n]*");
    private static final Pattern PRIMARY_FRAGMENT_INTERFACE = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*1\\s*\\)\\s*in\\s+Interpolants\\s*\\{");
    private static final String MESH_VARYINGS = String.join("\n",
            "    vec2 uv;",
            "    vec3 v_colour;",
            "    vec3 v_vertexColour;",
            "    vec2 v_lightmapCoord;",
            "    vec3 v_renderPosition;",
            "    vec2 v_quadMidUv;",
            "    vec3 v_quadMidPosition;",
            "    vec3 v_midBlock;",
            "    vec3 v_blockEntity;",
            "    vec3 v_normal;",
            "    vec4 v_tangent;",
             "    flat uint v_materialFlags;");
    private static final String FRAGMENT_VARYINGS = MESH_VARYINGS;
    private static final String MESH_UNIFORM_ALIASES =
            NvidiumIrisTerrainAbi.vertexAliasDeclarations();
    private static final String FRAGMENT_UNIFORM_ALIASES =
            NvidiumIrisTerrainAbi.fragmentAliasDeclarations();
    private static final String FRAGMENT_GBUFFER_OUTPUTS = String.join("\n",
            "layout(location = 1) out vec4 caustiqGBufferNormal;",
            "layout(location = 2) out vec4 caustiqGBufferPosition;",
            "layout(location = 3) out vec4 caustiqGBufferMaterial;",
            "layout(location = 4) out vec4 caustiqGBufferLightmap;");
    private static final String FRAGMENT_GBUFFER_WRITES = String.join("\n",
            "    caustiqGBufferNormal = vec4(normalize(v_normal) * 0.5 + 0.5, 1.0);",
            "    caustiqGBufferPosition = vec4(v_renderPosition, 1.0);",
            "    caustiqGBufferMaterial = vec4(v_vertexColour, float(v_materialFlags) / 255.0);",
            "    caustiqGBufferLightmap = vec4(v_lightmapCoord, 0.0, 1.0);");
    private static final String MESH_ASSIGNMENTS = String.join("\n",
            "    OUT[id].uv = decodeVertexUV(V);",
            "    OUT[id].v_colour = computeMultiplier(V);",
            "    OUT[id].v_vertexColour = decodeVertexColour(V).rgb;",
            "    OUT[id].v_lightmapCoord = decodeLightUV(V);",
            "    OUT[id].v_renderPosition = (transformMat * vec4(decodeVertexPosition(V) + origin, 1.0)).xyz;",
            "    OUT[id].v_quadMidUv = caustiqDecodeMidUv(V);",
            "    OUT[id].v_midBlock = caustiqDecodeMidBlock(V);",
            "    OUT[id].v_quadMidPosition = (transformMat * vec4(decodeVertexPosition(V) + origin + OUT[id].v_midBlock, 1.0)).xyz;",
            "    OUT[id].v_blockEntity = caustiqDecodeBlockEntity(V.blockId);",
            "    OUT[id].v_normal = caustiqDecodeNormal(V);",
            "    OUT[id].v_tangent = caustiqDecodeTangent(V);",
             "    OUT[id].v_materialFlags = V.nvidiumMaterial & 0xFFu;");
    private static final String VERTEX_FORMAT_EXTENSION = String.join("\n",
            "    uint blockId;",
            "    uint normal;",
            "    uint midUV;",
            "    uint midBlock;",
            "    uint nvidiumMaterial;");
    private static final String NORMAL_HELPER = String.join("\n",
            "// CAUSTIQ_NVIDIUM_ADAPTER_HELPERS_BEGIN",
            "vec3 caustiqDecodeNormal(Vertex vertex) {",
            "    int packedNormal = int(vertex.normal);",
            "    vec2 e = vec2((packedNormal << 8) >> 20, (packedNormal << 20) >> 20) * (1.0 / 2047.0);",
            "    vec3 normal = vec3(e, 1.0 - abs(e.x) - abs(e.y));",
            "    normal.xy = normal.z >= 0.0 ? normal.xy : (1.0 - abs(normal.yx)) * sign(normal.xy + vec2(1e-20));",
            "    return normalize(normal);",
            "}",
            "",
            "void caustiqOnbFromNormal(vec3 normal, out vec3 t1, out vec3 t2) {",
            "    float s = normal.z >= 0.0 ? 1.0 : -1.0;",
            "    float a = -1.0 / (s + normal.z);",
            "    float b = normal.x * normal.y * a;",
            "    t1 = normalize(vec3(1.0 + s * normal.x * normal.x * a, s * b, -s * normal.x));",
            "    t2 = cross(normal, t1);",
            "}",
            "",
            "vec2 caustiqDecodeDiamond(float diamondCoordinate) {",
            "    float x = 1.0 - 4.0 * abs(diamondCoordinate - 0.5);",
            "    float y = (diamondCoordinate >= 0.5 ? 1.0 : -1.0) * (1.0 - abs(x));",
            "    return vec2(x, y);",
            "}",
            "",
            "vec4 caustiqDecodeTangent(Vertex vertex) {",
            "    vec3 normal = caustiqDecodeNormal(vertex);",
            "    vec3 t1, t2;",
            "    caustiqOnbFromNormal(normal, t1, t2);",
            "    float packedTangentAngle = float(vertex.normal >> 24u) / 256.0;",
            "    vec2 diamond = caustiqDecodeDiamond(packedTangentAngle);",
            "    vec3 tangent = normalize(diamond.x * t1 + diamond.y * t2);",
            "    return vec4(tangent, (uint(vertex.material) & 1u) != 0u ? 1.0 : -1.0);",
            "}",
            "",
            "mat3 caustiqDecodeTbn(Vertex vertex) {",
            "    vec3 normal = caustiqDecodeNormal(vertex);",
            "    vec4 tangent = caustiqDecodeTangent(vertex);",
            "    vec3 bitangent = normalize(cross(normal, tangent.xyz)) * tangent.w;",
            "    return mat3(tangent.xyz, bitangent, normal);",
            "}",
            "",
            "vec2 caustiqDecodeMidUv(Vertex vertex) {",
            "    return vec2(vertex.midUV & 0xFFFFu, vertex.midUV >> 16u) * (1.0 / 32768.0);",
            "}",
            "",
            "vec3 caustiqDecodeMidBlock(Vertex vertex) {",
            "    int x = int(vertex.midBlock << 24u) >> 24;",
            "    int y = int(vertex.midBlock << 16u) >> 24;",
            "    int z = int(vertex.midBlock << 8u) >> 24;",
            "    return vec3(x, y, z) * (1.0 / 64.0);",
            "}",
            "",
            "vec3 caustiqDecodeBlockEntity(uint packedBlockEntityId) {",
            "    return vec3(float(int(packedBlockEntityId >> 1u) - 1), float(packedBlockEntityId & 1u), 0.0);",
            "}",
            "",
            "vec4 caustiqDecodeIrisColor(Vertex vertex) {",
            "    vec4 color = decodeVertexColour(vertex);",
            "    color.a = float((vertex.nvidiumMaterial >> 8u) & 0xFFu) * COLOR_SCALE;",
            "    return color;",
            "}",
            "// CAUSTIQ_NVIDIUM_ADAPTER_HELPERS_END");

    static String softwareRenderVertexHelpers() {
        return NORMAL_HELPER;
    }

    private NvidiumTerrainVaryingAdapter() { }

    /** Adapts the pinned Nvidium terrain shader ABI; unknown layouts fail closed. */
    public static boolean isPrimary(Map<String, String> sourceStages) {
        return isPrimaryMesh(sourceStages) || isTranslucentMesh(sourceStages);
    }

    public static boolean isPrimaryMesh(Map<String, String> sourceStages) {
        String mesh = sourceStages.get("MESH");
        return mesh != null
                && mesh.contains("layout(triangles, max_vertices=64, max_primitives=32) out;");
    }

    public static boolean isTranslucentMesh(Map<String, String> sourceStages) {
        String mesh = sourceStages.get("MESH");
        return mesh != null
                && mesh.contains("layout(triangles, max_vertices=128, max_primitives=64) out;")
                && mesh.contains("void emitVertex(uint vertexBaseId, uint innerId)");
    }

    public static Map<String, String> adapt(Map<String, String> sourceStages) {
        Objects.requireNonNull(sourceStages, "sourceStages");
        if (!sourceStages.keySet().equals(Set.of("TASK", "MESH", "FRAGMENT"))) {
            throw new IllegalArgumentException("Expected Nvidium TASK, MESH and FRAGMENT stages.");
        }

        String mesh = disableBarycentricPath(Objects.requireNonNull(sourceStages.get("MESH"), "MESH source"));
        String fragment = disableBarycentricPath(Objects.requireNonNull(sourceStages.get("FRAGMENT"), "FRAGMENT source"));
        if ((isPrimaryMesh(sourceStages) || isTranslucentMesh(sourceStages))
                && (!SODIUM_VERTEX_FORMAT_DEFINE.matcher(mesh).find()
                || !SODIUM_VERTEX_FORMAT_DEFINE.matcher(fragment).find())) {
            throw new IllegalStateException("Nvidium did not preprocess USE_SODIUM_VERTEX_FORMAT into both terrain stages; refusing to pair CAUSTIQ's 40-byte encoder with Nvidium's native uvec4 vertex ABI.");
        }
        boolean meshMarked = mesh.contains(MARKER);
        boolean fragmentMarked = fragment.contains(MARKER);
        boolean translucentMeshMarked = mesh.contains(TRANSLUCENT_MARKER);
        boolean translucentFragmentMarked = fragment.contains(TRANSLUCENT_MARKER);
        if (meshMarked || fragmentMarked) {
            boolean complete = meshMarked && fragmentMarked
                    && mesh.contains(MESH_VARYINGS) && fragment.contains(FRAGMENT_VARYINGS)
                    && mesh.contains(MESH_ASSIGNMENTS) && mesh.contains(NORMAL_HELPER)
                    && mesh.contains("caustiqDecodeIrisColor(Vertex vertex)")
                    && mesh.contains(MESH_UNIFORM_ALIASES)
                    && mesh.contains(VERTEX_FORMAT_EXTENSION)
                    && fragment.contains(VERTEX_FORMAT_EXTENSION)
                    && fragment.contains(FRAGMENT_UNIFORM_ALIASES)
                    && fragment.contains(FRAGMENT_GBUFFER_OUTPUTS)
                    && fragment.contains(FRAGMENT_GBUFFER_WRITES);
            if (complete) return Map.copyOf(sourceStages);
            throw new IllegalStateException("Nvidium terrain varying profile is only partially applied or has an incompatible ABI.");
        }
        if (translucentMeshMarked || translucentFragmentMarked) {
            boolean complete = translucentMeshMarked && translucentFragmentMarked
                    && mesh.contains(VERTEX_FORMAT_EXTENSION) && fragment.contains(VERTEX_FORMAT_EXTENSION)
                    && mesh.contains("v.nvidiumMaterial") && fragment.contains("v.nvidiumMaterial");
            if (complete) return Map.copyOf(sourceStages);
            throw new IllegalStateException("Nvidium translucent vertex ABI is only partially applied or incompatible.");
        }
        if (isTranslucentMesh(sourceStages)) return adaptTranslucent(sourceStages);
        boolean primaryMeshSignature = mesh.contains("layout(triangles, max_vertices=64, max_primitives=32) out;");
        if (!primaryMeshSignature) return Map.copyOf(sourceStages);
        boolean hasPrimaryMeshAnchors = mesh.contains("void putVertex(uint id, Vertex V) {")
                && mesh.contains("    OUT[id].uv = decodeVertexUV(V);");
        boolean hasPrimaryFragmentAnchor = fragment.contains("layout(location = 0) out vec4 colour;")
                && PRIMARY_FRAGMENT_INTERFACE.matcher(fragment).find(); 
        if (!hasPrimaryMeshAnchors || !hasPrimaryFragmentAnchor) {
            throw new IllegalStateException("Nvidium primary terrain shader has a partial or incompatible source layout.");
        }
        mesh = replaceOnce(mesh, "    uint8_t section;", "    uint8_t section;\n" + VERTEX_FORMAT_EXTENSION,
                "extended mesh vertex storage layout");
        fragment = replaceOnce(fragment, "    uint8_t section;", "    uint8_t section;\n" + VERTEX_FORMAT_EXTENSION,
                "extended fragment vertex storage layout");
        mesh = replaceOnce(mesh, "return bool(int(v.material) & 1);",
                "return bool(int(v.nvidiumMaterial) & 1);", "Nvidium mip flag source");
        mesh = replaceOnce(mesh, "return (int(v.material) >> 1) & 3;",
                "return (int(v.nvidiumMaterial) >> 1) & 3;", "Nvidium alpha cutoff source");
        fragment = replaceOnce(fragment, "return bool(int(v.material) & 1);",
                "return bool(int(v.nvidiumMaterial) & 1);", "fragment Nvidium mip flag source");
        fragment = replaceOnce(fragment, "return (int(v.material) >> 1) & 3;",
                "return (int(v.nvidiumMaterial) >> 1) & 3;", "fragment Nvidium alpha cutoff source");
        mesh = replaceOnce(mesh, "#version 460", "#version 460\n" + MARKER, "mesh version");
        fragment = replaceOnce(fragment, "#version 460", "#version 460\n" + MARKER, "fragment version");
        mesh = insertAfterExtensions(mesh, MESH_UNIFORM_ALIASES, "mesh Iris uniform aliases");
        mesh = replaceOnce(mesh, "    vec2 uv;\n    vec3 v_colour;", MESH_VARYINGS, "mesh interpolant declaration");
        fragment = replaceOnce(fragment, "    vec2 uv;\n    vec3 v_colour;", FRAGMENT_VARYINGS, "fragment interpolant declaration");
        fragment = insertAfterExtensions(fragment, FRAGMENT_UNIFORM_ALIASES, "fragment uniform aliases");
        fragment = replaceOnce(fragment, "layout(location = 0) out vec4 colour;",
                "layout(location = 0) out vec4 colour;\n" + FRAGMENT_GBUFFER_OUTPUTS,
                "G-buffer fragment outputs");
        mesh = replaceOnce(mesh, "vec4 transformVertex(Vertex V) {",
                NORMAL_HELPER + "\n\nvec4 transformVertex(Vertex V) {", "terrain basis helper insertion point");
        mesh = replaceOnce(mesh, "    OUT[id].uv = decodeVertexUV(V);\n    OUT[id].v_colour = computeMultiplier(V);",
                MESH_ASSIGNMENTS, "mesh varying assignments");
        fragment = appendBeforeFinalBrace(fragment, FRAGMENT_GBUFFER_WRITES);

        Map<String, String> adapted = new LinkedHashMap<>(sourceStages);
        adapted.put("MESH", mesh);
        adapted.put("FRAGMENT", fragment);
        return Map.copyOf(adapted);
    }

    private static Map<String, String> adaptTranslucent(Map<String, String> sourceStages) {
        String mesh = disableBarycentricPath(Objects.requireNonNull(sourceStages.get("MESH"), "MESH source"));
        String fragment = disableBarycentricPath(Objects.requireNonNull(sourceStages.get("FRAGMENT"), "FRAGMENT source"));
        if (!mesh.contains("    uint8_t section;") || !fragment.contains("    uint8_t section;")
                || !mesh.contains("OUT[outId].uv = decodeVertexUV(V);")) {
            throw new IllegalStateException("Nvidium translucent shader no longer matches the pinned Sodium vertex ABI.");
        }
        mesh = replaceOnce(mesh, "    uint8_t section;",
                "    uint8_t section;\n" + VERTEX_FORMAT_EXTENSION, "translucent mesh vertex storage");
        fragment = replaceOnce(fragment, "    uint8_t section;",
                "    uint8_t section;\n" + VERTEX_FORMAT_EXTENSION, "translucent fragment vertex storage");
        mesh = replaceOnce(mesh, "return bool(int(v.material) & 1);",
                "return bool(int(v.nvidiumMaterial) & 1);", "translucent Nvidium mip flag");
        mesh = replaceOnce(mesh, "return (int(v.material) >> 1) & 3;",
                "return (int(v.nvidiumMaterial) >> 1) & 3;", "translucent Nvidium alpha cutoff");
        fragment = replaceOnce(fragment, "return bool(int(v.material) & 1);",
                "return bool(int(v.nvidiumMaterial) & 1);", "translucent fragment mip flag");
        fragment = replaceOnce(fragment, "return (int(v.material) >> 1) & 3;",
                "return (int(v.nvidiumMaterial) >> 1) & 3;", "translucent fragment alpha cutoff");
        mesh = replaceOnce(mesh, "#version 460", "#version 460\n" + TRANSLUCENT_MARKER,
                "translucent mesh version");
        fragment = replaceOnce(fragment, "#version 460", "#version 460\n" + TRANSLUCENT_MARKER,
                "translucent fragment version");
        Map<String, String> adapted = new LinkedHashMap<>(sourceStages);
        adapted.put("MESH", mesh);
        adapted.put("FRAGMENT", fragment);
        return Map.copyOf(adapted);
    }

    private static String insertAfterExtensions(String source, String declarations, String label) {
        java.util.regex.Matcher extensions = Pattern.compile(
                "(?m)^[\\t ]*#[\\t ]*extension\\b[^\\r\\n]*(?:\\r?\\n|$)")
                .matcher(source);
        int insertion = -1;
        while (extensions.find()) insertion = extensions.end();
        if (insertion < 0) {
            java.util.regex.Matcher marker = Pattern.compile(
                    "(?m)^[\\t ]*#\\s*define\\s+CAUSTIQ_NVIDIUM_(?:TERRAIN_VARYINGS|TRANSLUCENT_VERTEX_ABI)\\b[^\\r\\n]*(?:\\r?\\n|$)")
                    .matcher(source);
            if (marker.find()) insertion = marker.end();
        }
        if (insertion < 0) {
            throw new IllegalStateException("Could not locate a legal GLSL declaration point for " + label + ".");
        }
        return source.substring(0, insertion) + declarations + "\n" + source.substring(insertion);
    }

    private static String appendBeforeFinalBrace(String source, String statements) {
        int closingBrace = source.lastIndexOf('}');
        if (closingBrace < 0 || !source.substring(closingBrace + 1).isBlank()) {
            throw new IllegalStateException("Unsupported Nvidium fragment shader layout at main() terminator.");
        }
        return source.substring(0, closingBrace) + "\n" + statements + "\n" + source.substring(closingBrace);
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
