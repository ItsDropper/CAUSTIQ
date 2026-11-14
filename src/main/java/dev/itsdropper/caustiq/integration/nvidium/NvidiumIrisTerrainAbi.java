package dev.itsdropper.caustiq.integration.nvidium;

import java.util.Map;
import java.util.Optional;

/**
 * Audited data contract exposed by Nvidium 0.4.3-beta13-1.21.11 to a CAUSTIQ
 * terrain shader. This is deliberately a capability description, not a claim
 * that arbitrary Iris uniforms or vertex attributes exist in Nvidium.
 */
public final class NvidiumIrisTerrainAbi {
    public static final String NVIDIUM_VERSION = "0.4.3-beta13-1.21.11";
    public static final int DIFFUSE_ATLAS_TEXTURE_UNIT = 0;
    public static final int LIGHTMAP_TEXTURE_UNIT = 1;

    public enum Availability {
        EXACT,
        DERIVED,
        UNAVAILABLE
    }

    public record Semantic(String irisSemantic, Availability availability,
                           String meshExpression, String explanation) { }

    private static final Map<String, Semantic> VERTEX_SEMANTICS = Map.ofEntries(
            Map.entry("position", new Semantic("gl_Vertex", Availability.DERIVED,
                    "decodeVertexPosition(V) + origin",
                    "Packed terrain position, relative to the camera chunk before the region transform.")),
            Map.entry("modernPosition", new Semantic("vaPosition", Availability.DERIVED,
                    "decodeVertexPosition(V) + origin", "Modern Iris alias for the packed terrain position.")),
            Map.entry("textureCoordinate", new Semantic("gl_MultiTexCoord0", Availability.DERIVED,
                    "decodeVertexUV(V)",
                    "Nvidium's atlas UV including its texture-coordinate shrink correction.")),
            Map.entry("modernTextureCoordinate", new Semantic("vaUV0", Availability.DERIVED,
                    "decodeVertexUV(V)", "Modern Iris alias for the packed terrain UV.")),
            Map.entry("lightmapCoordinateLegacy", new Semantic("gl_MultiTexCoord1", Availability.EXACT,
                    "decodeLightUV(V)", "Legacy Iris lightmap UV attribute.")),
            Map.entry("lightmapCoordinateModern", new Semantic("vaUV2", Availability.EXACT,
                    "decodeLightUV(V)", "Modern Iris lightmap UV attribute.")),
            Map.entry("vertexColor", new Semantic("gl_Color", Availability.DERIVED,
                    "decodeVertexColour(V).rgb",
                    "Packed vertex tint; Nvidium's pre-lit multiplier is separately available.")),
            Map.entry("modernVertexColor", new Semantic("vaColor", Availability.DERIVED,
                    "decodeVertexColour(V).rgb", "Modern Iris alias for packed vertex tint.")),
            Map.entry("lightmapCoordinate", new Semantic("lmcoord", Availability.EXACT,
                    "decodeLightUV(V)",
                    "Packed block/sky light coordinates in Nvidium's normalized lightmap convention.")),
            Map.entry("normal", new Semantic("gl_Normal", Availability.UNAVAILABLE,
                    "caustiqTriangleNormal",
                    "Nvidium can reconstruct a geometric face normal, but this is not equivalent to Iris per-vertex/model normals.")),
            Map.entry("renderPosition", new Semantic("viewSpacePosition", Availability.UNAVAILABLE,
                    "(transformMat * vec4(decodeVertexPosition(V) + origin, 1.0)).xyz",
                    "The available Nvidium position is region-transformed camera-relative data, not Iris view-space position.")),
            Map.entry("materialFlags", new Semantic("mc_Entity", Availability.UNAVAILABLE,
                    "",
                    "Nvidium stores mip and alpha-cutoff flags, but does not preserve Minecraft block/entity IDs.")),
            Map.entry("midTextureCoordinate", new Semantic("at_midTexCoord", Availability.UNAVAILABLE,
                    "",
                    "The compact Nvidium vertex formats do not retain the quad midpoint UV.")),
            Map.entry("midBlockPosition", new Semantic("at_midBlock", Availability.UNAVAILABLE,
                    "",
                    "The compact Nvidium vertex formats do not retain the midpoint block position.")),
            Map.entry("tangent", new Semantic("at_tangent", Availability.UNAVAILABLE,
                    "",
                    "Neither supported packed vertex format contains a tangent basis."))
    );

    private static final Map<String, Semantic> FRAGMENT_UNIFORMS = Map.ofEntries(
            Map.entry("viewWidth", new Semantic("viewWidth", Availability.DERIVED,
                    "screenSize.x * 2.0",
                    "Nvidium stores half the framebuffer width in SceneData.screenSize.x.")),
            Map.entry("viewHeight", new Semantic("viewHeight", Availability.DERIVED,
                    "screenSize.y * 2.0",
                    "Nvidium stores half the framebuffer height in SceneData.screenSize.y.")),
            Map.entry("fogColor", new Semantic("fogColor", Availability.EXACT,
                    "fogColour",
                    "Copied from Sodium FogParameters for this Nvidium terrain pass.")),
            Map.entry("gtexture", new Semantic("gtexture", Availability.EXACT,
                    "tex_diffuse (texture unit 0)",
                    "The active Sodium TerrainRenderPass block atlas.")),
            Map.entry("lightmap", new Semantic("lightmap", Availability.EXACT,
                    "tex_light (texture unit 1)",
                    "Minecraft's active light texture.")),
            Map.entry("gbufferModelView", new Semantic("gbufferModelView", Availability.EXACT,
                    "caustiqModelView", "Sodium's current camera view matrix captured at Nvidium renderFrame entry.")),
            Map.entry("gbufferModelViewInverse", new Semantic("gbufferModelViewInverse", Availability.DERIVED,
                    "inverse(caustiqModelView)", "Inverse of the captured camera view matrix.")),
            Map.entry("gbufferProjection", new Semantic("gbufferProjection", Availability.EXACT,
                    "caustiqProjection", "Sodium's current projection matrix captured at Nvidium renderFrame entry.")),
            Map.entry("gbufferProjectionInverse", new Semantic("gbufferProjectionInverse", Availability.DERIVED,
                    "inverse(caustiqProjection)", "Inverse of the captured projection matrix.")),
            Map.entry("cameraPosition", new Semantic("cameraPosition", Availability.EXACT,
                    "caustiqCameraPosition", "World-space camera coordinates passed to Nvidium renderFrame.")),
            Map.entry("frameTimeCounter", new Semantic("frameTimeCounter", Availability.DERIVED,
                    "caustiqFrameTimeCounter", "Monotonic elapsed seconds since CAUSTIQ first observed the Nvidium world renderer.")),
            Map.entry("frameCounter", new Semantic("frameCounter", Availability.EXACT,
                    "caustiqFrameCounter", "Monotonic count of Nvidium world renderFrame captures."))
    );

    private NvidiumIrisTerrainAbi() { }

    public static Optional<Semantic> vertexSemantic(String name) {
        return VERTEX_SEMANTICS.values().stream()
                .filter(value -> value.irisSemantic().equals(name))
                .findFirst();
    }

    public static Optional<Semantic> fragmentSemantic(String name) {
        return FRAGMENT_UNIFORMS.values().stream()
                .filter(value -> value.irisSemantic().equals(name))
                .findFirst();
    }

    /**
     * GLSL aliases for the subset of Iris terrain fragment uniforms/samplers
     * that Nvidium actually binds. Pack-specific uniforms are intentionally
     * absent and must be rejected by the translator.
     */
    public static String fragmentAliasDeclarations() {
        return String.join("\n",
                "// CAUSTIQ mappings to values Nvidium actually binds for this terrain pass.",
                "#define CAUSTIQ_IRIS_VIEW_WIDTH (screenSize.x * 2.0)",
                "#define CAUSTIQ_IRIS_VIEW_HEIGHT (screenSize.y * 2.0)",
                "#define CAUSTIQ_IRIS_FOG_COLOR fogColour",
                "#define CAUSTIQ_IRIS_GTEXTURE tex_diffuse",
                "#define CAUSTIQ_IRIS_LIGHTMAP tex_light",
                "#define gbufferModelView caustiqModelView",
                "#define gbufferModelViewInverse caustiqModelViewInverse",
                "#define gbufferProjection caustiqProjection",
                "#define gbufferProjectionInverse caustiqProjectionInverse",
                "#define cameraPosition caustiqCameraPosition",
                "#define frameTimeCounter caustiqFrameTimeCounter",
                "#define frameCounter caustiqFrameCounter") + "\n";
    }

    public static Map<String, Semantic> vertexSemantics() { return VERTEX_SEMANTICS; }
    public static Map<String, Semantic> fragmentSemantics() { return FRAGMENT_UNIFORMS; }

    public static boolean canRepresentRequiredVertexInputs(Iterable<String> requiredInputs) {
        for (String input : requiredInputs) {
            Semantic semantic = vertexSemantic(input).orElse(null);
            if (semantic == null || semantic.availability() == Availability.UNAVAILABLE) return false;
        }
        return true;
    }
}
