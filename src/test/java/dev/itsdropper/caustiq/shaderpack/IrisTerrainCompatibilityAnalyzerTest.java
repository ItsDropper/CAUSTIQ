package dev.itsdropper.caustiq.shaderpack;

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
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNAVAILABLE_VERTEX_INPUT")
                && i.symbol().equals("at_tangent")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNAVAILABLE_VERTEX_INPUT")
                && i.symbol().equals("mc_Entity")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNAVAILABLE_TRANSFORM")
                && i.symbol().equals("cameraPosition")));
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
            var preprocessor = new GlslPreprocessor();
            String vertex = preprocessor.preprocess("world0/gbuffers_terrain.vsh", sources).source();
            String fragment = preprocessor.preprocess("world0/gbuffers_terrain.fsh", sources).source();
            var result = analyzer.analyze(vertex, fragment);
            assertFalse(result.compatible(), "Photon must not be reported compatible with the incomplete Nvidium ABI");
            assertTrue(result.issues().stream().anyMatch(i -> i.symbol().equals("at_tangent")));
            assertTrue(result.issues().stream().anyMatch(i -> i.symbol().equals("mc_Entity")));
            assertTrue(result.issues().stream().anyMatch(i -> i.symbol().equals("mc_midTexCoord")));
            assertTrue(result.issues().stream().anyMatch(i -> i.symbol().equals("cameraPosition")));
            assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("MULTIPLE_RENDER_TARGETS")));
        }
    }

}
