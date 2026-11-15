package dev.itsdropper.caustiq.shaderpack;

import dev.itsdropper.caustiq.integration.nvidium.IrisNvidiumTerrainTranslator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

class IrisTerrainCompatibilityAnalyzerTest {
    private final IrisTerrainCompatibilityAnalyzer analyzer = new IrisTerrainCompatibilityAnalyzer();

    @Test
    void rejectsPhotonStyleTerrainRequirementsMissingFromNvidiumVertexAndUniformAbi() {
        String vertex = """
                #version 400 compatibility
                attribute vec4 at_tangent;
                attribute vec3 mc_Entity;
                attribute vec2 mc_midTexCoord;
                uniform mat4 gbufferModelView;
                uniform mat4 gbufferModelViewInverse;
                uniform mat4 gbufferProjection;
                uniform vec3 cameraPosition;
                uniform float customPackUniform;
                out vec3 scene_pos;
                out vec4 tint;
                void main() {
                    tint = gl_Color;
                    scene_pos = (gbufferModelView * gl_Vertex).xyz + cameraPosition;
                    gl_Position = gbufferProjection * vec4(scene_pos, 1.0);
                }
                """;
        String fragment = """
                #version 400 compatibility
                in vec3 scene_pos;
                in vec4 tint;
                uniform sampler2D gtexture;
                layout(location = 0) out vec4 gbuffer0;
                layout(location = 1) out vec4 gbuffer1;
                void main() {
                    gbuffer0 = texture(gtexture, gl_TexCoord[0].xy) * tint;
                    gbuffer1 = vec4(normalize(scene_pos), 1.0);
                }
                """;

        var result = analyzer.analyze(vertex, fragment);
        assertFalse(result.compatible());
        assertFalse(result.issues().stream().anyMatch(i -> i.code().equals("UNAVAILABLE_VERTEX_INPUT")
                && i.symbol().equals("at_tangent")));
        assertFalse(result.issues().stream().anyMatch(i -> i.code().equals("UNAVAILABLE_VERTEX_INPUT")
                && i.symbol().equals("mc_Entity")));
        assertFalse(result.issues().stream().anyMatch(i -> i.code().equals("UNAVAILABLE_TRANSFORM")
                && i.symbol().equals("cameraPosition")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNBOUND_UNIFORM")
                && i.symbol().equals("customPackUniform")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("MULTIPLE_RENDER_TARGETS")));
    }

    @Test
    void acceptsOnlyVaryingsAndResourcesWithKnownNvidiumMappings() {
        String vertex = """
                #version 400
                out vec2 uv;
                out vec3 v_colour;
                void main() { uv = vec2(0.0); v_colour = vec3(1.0); }
                """;
        String fragment = """
                #version 400
                in vec2 uv;
                in vec3 v_colour;
                uniform sampler2D gtexture;
                layout(location = 0) out vec4 color;
                void main() { color = texture(gtexture, uv) * vec4(v_colour, 1.0); }
                """;

        var result = analyzer.analyze(vertex, fragment);
        assertTrue(result.compatible(), result.issues().toString());
    }

    @Test
    void ignoresCommentsWhenCheckingRequirements() {
        String vertex = """
                #version 400
                // at_tangent and cameraPosition are mentioned in a comment only
                void main() { gl_Position = vec4(0.0); }
                """;
        String fragment = """
                #version 400
                layout(location = 0) out vec4 color;
                void main() { color = vec4(1.0); }
                """;
        assertTrue(analyzer.analyze(vertex, fragment).compatible());
    }

    @Test
    void rejectsMissingShaderStages() {
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze("", "void main(){}"));
    }
    @Test
    void auditsThePinnedRealPhotonTerrainProgramWhenCiProvidesItsSource() throws Exception {
        String photonDirectory = System.getenv("PHOTON_SOURCE_DIR");
        Assumptions.assumeTrue(photonDirectory != null && !photonDirectory.isBlank(),
                "Photon source checkout is supplied by CI only");
        var descriptor = new ShaderPackDescriptor("photon", "Photon",
                Path.of(photonDirectory), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (var sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            var overworld = model.dimensionOverrides().get("world0");
            assertNotNull(overworld, "Photon's overworld program set must be parsed");
            assertTrue(overworld.programs().containsKey("deferred4_a"),
                    "Photon's numbered/lettered deferred compute entry point must be recognized");
            assertTrue(overworld.programs().containsKey("clrwl_gbuffers"),
                    "Photon's color-wheel gbuffers entry point must be recognized");
            assertTrue(overworld.programs().containsKey("shadow_block"),
                    "Photon's specialized shadow entry points must be recognized");
            IrisNvidiumTerrainTranslator translator = assertDoesNotThrow(
                    () -> IrisNvidiumTerrainTranslator.from(model, null, "gbuffers_terrain",
                            java.util.Map.of("TAA", "false", "NORMAL_MAPPING", "false", "SPECULAR_MAPPING", "false")),
                    "The pinned Photon gbuffers_terrain pair must translate into the Nvidium mesh/fragment ABI");
            assertNotNull(translator);
            assertEquals(java.util.List.of(1), translator.renderTargetIndices());
        }
    }

    @Test
    void acceptsOnlyTheLegacyVertexInputsRepresentedByNvidiumPackedVertices() {
        String vertex = """
                #version 400 compatibility
                out vec2 uv;
                out vec2 lightUv;
                out vec4 tint;
                void main() {
                    uv = gl_MultiTexCoord0.xy;
                    lightUv = gl_MultiTexCoord1.xy;
                    tint = gl_Color;
                    gl_Position = gl_Vertex;
                }
                """;
        String fragment = """
                #version 400 compatibility
                in vec2 uv;
                in vec2 lightUv;
                in vec4 tint;
                layout(location = 0) out vec4 color;
                void main() {
                    color = texture(gtexture, gl_TexCoord[0].xy) * tint
                            + texture(lightmap, gl_TexCoord[1].xy) * vec4(lightUv, 0.0, 0.0);
                }
                """;
        var result = analyzer.analyze(vertex, fragment);
        assertTrue(result.compatible(), result.issues().toString());
    }

    @Test
    void rejectsUnrepresentedTextureCoordinateSetsAndCustomVertexAttributes() {
        String vertex = """
                #version 400
                layout(location = 4) in vec4 customData;
                void main() { gl_Position = vec4(customData.xyz, 1.0); }
                """;
        String fragment = """
                #version 400
                layout(location = 0) out vec4 color;
                void main() { color = texture(gtexture, gl_TexCoord[2].xy); }
                """;
        var result = analyzer.analyze(vertex, fragment);
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNMAPPED_VERTEX_INPUT")
                && i.symbol().equals("customData")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNSUPPORTED_TEXTURE_COORDINATE_SET")
                && i.symbol().equals("gl_TexCoord[2]")));
    }

    @Test
    void comparesVaryingTypesAndParsesQualifiedFragmentOutputs() {
        String vertex = """
                #version 400
                out vec3 sharedValue;
                void main() { sharedValue = vec3(1.0); }
                """;
        String fragment = """
                #version 400
                in vec2 sharedValue;
                layout(location = 1) out highp vec4 normalTarget;
                void main() { normalTarget = vec4(sharedValue, 0.0, 1.0); }
                """;
        var result = analyzer.analyze(vertex, fragment);
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("VARYING_TYPE_MISMATCH")
                && i.symbol().equals("sharedValue")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("MULTIPLE_RENDER_TARGETS")
                && i.symbol().equals("normalTarget")));
    }

    @Test
    void acceptsQualifiedPrimaryFragmentOutputAndSamplerPrecision() {
        String vertex = """
                #version 400
                out vec2 uv;
                void main() { uv = vec2(0.0); }
                """;
        String fragment = """
                #version 400
                in vec2 uv;
                uniform highp sampler2D gtexture;
                layout(location = 0) out highp vec4 color;
                void main() { color = texture(gtexture, uv); }
                """;
        var result = analyzer.analyze(vertex, fragment);
        assertTrue(result.compatible(), result.issues().toString());
    }

    @Test
    void rejectsCustomUniformsAndUniformBlocksWithoutNvidiumBindings() {
        String vertex = """
                #version 400
                uniform float unsupportedFrameTime;
                uniform mat4 customTransform;
                void main() { gl_Position = customTransform * vec4(unsupportedFrameTime); }
                """;
        String fragment = """
                #version 400
                layout(std140) uniform PackLighting { vec4 customLight; };
                layout(location = 0) out vec4 color;
                void main() { color = customLight; }
                """;
        var result = analyzer.analyze(vertex, fragment);
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNBOUND_UNIFORM")
                && i.symbol().equals("unsupportedFrameTime")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNBOUND_UNIFORM")
                && i.symbol().equals("customTransform")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNSUPPORTED_UNIFORM_BLOCK")));
    }

    @Test
    void acceptsUniformsMappedByTheNvidiumTerrainScene() {
        String vertex = """
                #version 400
                void main() { gl_Position = gl_Vertex; }
                """;
        String fragment = """
                #version 400
                uniform float viewWidth;
                uniform float viewHeight;
                uniform vec4 fogColor;
                layout(location = 0) out vec4 color;
                void main() { color = fogColor + vec4(viewWidth + viewHeight); }
                """;
        var result = analyzer.analyze(vertex, fragment);
        assertTrue(result.compatible(), result.issues().toString());
    }

    @Test
    void doesNotTreatFragmentBindingsAsAvailableInTheVertexStage() {
        String vertex = """
                #version 400
                uniform float viewWidth;
                uniform sampler2D gtexture;
                void main() { gl_Position = vec4(texture(gtexture, vec2(viewWidth))); }
                """;
        String fragment = """
                #version 400
                layout(location = 0) out vec4 color;
                void main() { color = vec4(1.0); }
                """;
        var result = analyzer.analyze(vertex, fragment);
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNBOUND_UNIFORM")
                && i.symbol().equals("viewWidth")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNBOUND_SAMPLER")
                && i.symbol().equals("gtexture")));
    }

    @Test
    void rejectsLegacyFixedFunctionInputsOutsideThePackedVertexFormat() {
        String vertex = """
                #version 400 compatibility
                void main() {
                    gl_Position = gl_Vertex + gl_MultiTexCoord2 + vec4(gl_FogCoord);
                }
                """;
        String fragment = """
                #version 400
                layout(location = 0) out vec4 color;
                void main() { color = vec4(1.0); }
                """;
        var result = analyzer.analyze(vertex, fragment);
        assertTrue(result.issues().stream().anyMatch(i ->
                i.code().equals("UNAVAILABLE_VERTEX_INPUT") && i.symbol().equals("gl_MultiTexCoord2")));
        assertTrue(result.issues().stream().anyMatch(i ->
                i.code().equals("UNAVAILABLE_VERTEX_INPUT") && i.symbol().equals("gl_FogCoord")));
    }

    @Test
    void allowsPrimaryLegacyFragmentOutputButRejectsSecondaryAttachments() {
        String vertex = """
                #version 400
                void main() { gl_Position = gl_Vertex; }
                """;
        String primary = """
                #version 400
                void main() { gl_FragData[0] = vec4(1.0); }
                """;
        assertTrue(analyzer.analyze(vertex, primary).compatible());

        String multiple = """
                #version 400
                void main() {
                    gl_FragData[0] = vec4(1.0);
                    gl_FragData[1] = vec4(0.0);
                }
                """;
        var result = analyzer.analyze(vertex, multiple);
        assertTrue(result.issues().stream().anyMatch(i ->
                i.code().equals("MULTIPLE_RENDER_TARGETS") && i.symbol().equals("gl_FragData[1]")));
    }

    @Test
    void reportsConditionalCompilationThatTheAuditCannotEvaluate() {
        String vertex = """
                #version 400
                #ifdef USE_TANGENT
                attribute vec4 at_tangent;
                #endif
                void main() { gl_Position = gl_Vertex; }
                """;
        String fragment = """
                #version 400
                layout(location = 0) out vec4 color;
                void main() { color = vec4(1.0); }
                """;
        var result = analyzer.analyze(vertex, fragment);
        assertTrue(result.issues().stream().anyMatch(i ->
                i.code().equals("UNRESOLVED_PREPROCESSOR_CONDITION")));
    }

    @Test
    void rejectsFragmentOutputArraysEvenWhenOnlyOneDeclarationExists() {
        String vertex = """
                #version 400
                void main() { gl_Position = gl_Vertex; }
                """;
        String fragment = """
                #version 400
                layout(location = 0) out vec4 color[2];
                void main() { color[0] = vec4(1.0); color[1] = vec4(0.0); }
                """;
        var result = analyzer.analyze(vertex, fragment);
        assertTrue(result.issues().stream().anyMatch(i ->
                i.code().equals("UNSUPPORTED_FRAGMENT_OUTPUT_ARRAY") && i.symbol().equals("color")));
    }

}
