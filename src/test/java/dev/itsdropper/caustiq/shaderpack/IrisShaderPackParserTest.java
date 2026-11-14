package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrisShaderPackParserTest {
    @TempDir Path temporaryDirectory;

    @Test
    void parsesBaseAndDimensionProgramsWithoutMergingTheirStages() throws IOException {
        Path pack = Files.createDirectories(temporaryDirectory.resolve("Photon/shaders"));
        Files.writeString(pack.resolve("shaders.properties"), """
                iris.features.required=SSBO
                iris.features.optional=COMPUTE_SHADERS
                program.gbuffers_terrain.enabled=true
                colortex0Format=RGBA16F
                """);
        Files.writeString(pack.resolve("dimension.properties"), "dimension.world0=minecraft:overworld\\n");
        Files.writeString(pack.resolve("gbuffers_terrain.vsh"), "#version 150\nvoid main(){ gl_Position=vec4(0.0); }");
        Files.writeString(pack.resolve("gbuffers_terrain.fsh"), "#version 150\nout vec4 color; void main(){ color=vec4(1.0); }");
        Path world = Files.createDirectories(pack.resolve("world0"));
        Files.writeString(world.resolve("gbuffers_terrain.vsh"), "#version 150\nvoid main(){ gl_Position=vec4(1.0); }");
        Files.writeString(world.resolve("gbuffers_terrain.fsh"), "#version 150\nout vec4 color; void main(){ color=vec4(0.5); }");

        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("photon", "Photon",
                temporaryDirectory.resolve("Photon"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 4, 4, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            assertEquals(1, model.base().programs().size());
            assertTrue(model.base().programs().get("gbuffers_terrain").hasVertexAndFragment());
            assertEquals(1, model.dimensionOverrides().get("world0").programs().size());
            assertEquals("world0", model.dimensionMappings().get("minecraft:overworld"));
            assertTrue(model.requiredFeatures().contains("ssbo"));
            assertTrue(model.optionalFeatures().contains("compute_shaders"));
            assertEquals("true", model.conditionalPrograms().get("gbuffers_terrain"));
            assertEquals("RGBA16F", model.renderTargetDirectives().get("0Format"));
            assertTrue(model.preprocessedSources().containsKey("world0/gbuffers_terrain.vsh"));
        }
    }

    @Test
    void expandsRelativeAndRootedIrisIncludesForProgramStages() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("Pack/shaders/lib"));
        Files.writeString(shaders.resolve("common.glsl"), "vec4 caustiqIncluded(){ return vec4(1.0); }");
        Files.writeString(shaders.getParent().resolve("final.vsh"), """
                #version 150
                #include "/lib/common.glsl"
                void main(){ gl_Position=caustiqIncluded(); }
                """);
        Files.writeString(shaders.getParent().resolve("final.fsh"), """
                #version 150
                out vec4 color;
                void main(){ color=vec4(1.0); }
                """);

        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("pack", "Pack",
                temporaryDirectory.resolve("Pack"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 2, 2, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            assertTrue(model.preprocessedSources().get("final.vsh").contains("caustiqIncluded"));
            assertFalse(model.preprocessedSources().get("final.vsh").contains("#include"));
        }
    }

    @Test
    void rejectsDuplicateStagesWithinTheSameDimensionProgramSet() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("Pack/shaders"));
        Files.writeString(shaders.resolve("final.vsh"), "#version 150\nvoid main(){}");
        Files.writeString(shaders.resolve("final.VSH"), "#version 150\nvoid main(){}");
        Files.writeString(shaders.resolve("final.fsh"), "#version 150\nvoid main(){}");

        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("pack", "Pack",
                temporaryDirectory.resolve("Pack"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 3, 3, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            assertThrows(IOException.class, () -> new IrisShaderPackParser().parse(sources));
        }
    }
    @Test
    void reportsExactNvidiumTerrainAbiGapsForIrisPrograms() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("TerrainPack/shaders"));
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), """
                #version 400 compatibility
                attribute vec4 at_tangent;
                attribute vec3 mc_Entity;
                uniform mat4 gbufferModelView;
                uniform vec3 cameraPosition;
                out vec3 scene_pos;
                void main() {
                    scene_pos = (gbufferModelView * gl_Vertex).xyz + cameraPosition;
                    gl_Position = gl_ProjectionMatrix * vec4(scene_pos, 1.0);
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), """
                #version 400 compatibility
                in vec3 scene_pos;
                uniform sampler2D noisetex;
                layout(location = 0) out vec4 gbuffer0;
                layout(location = 1) out vec4 gbuffer1;
                void main() {
                    gbuffer0 = texture(noisetex, scene_pos.xy);
                    gbuffer1 = vec4(scene_pos, 1.0);
                }
                """);

        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("terrain", "TerrainPack",
                temporaryDirectory.resolve("TerrainPack"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 2, 2, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            assertTrue(model.diagnostics().stream().anyMatch(issue -> issue.contains("at_tangent")));
            assertTrue(model.diagnostics().stream().anyMatch(issue -> issue.contains("mc_Entity")));
            assertTrue(model.diagnostics().stream().anyMatch(issue -> issue.contains("cameraPosition")));
            assertTrue(model.diagnostics().stream().anyMatch(issue -> issue.contains("MULTIPLE_RENDER_TARGETS")));
            assertTrue(model.diagnostics().stream().anyMatch(issue -> issue.contains("noisetex")));
        }
    }

}
