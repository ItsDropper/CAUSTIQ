package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ShaderPackProgramCatalogTest {
    @TempDir Path temporaryDirectory;

    @Test
    void discoversProgramsInIrisPipelineOrderAndKeepsAllStages() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("shaders"));
        for (String name : new String[]{"final", "composite2", "composite", "deferred", "gbuffers_terrain", "shadow", "begin"}) {
            Files.writeString(shaders.resolve(name + ".vsh"), "#version 150\nvoid main() {}");
            Files.writeString(shaders.resolve(name + ".fsh"), "#version 150\nout vec4 color; void main() { color = vec4(1); }");
        }
        Files.writeString(shaders.resolve("composite.csh"), "#version 430\nlayout(local_size_x=1) in; void main() {}");
        Files.writeString(shaders.resolve("gbuffers_terrain.gsh"), "#version 150\nvoid main() {}");
        Files.writeString(shaders.resolve("gbuffers_terrain.tcs"), "#version 400\nvoid main() {}");
        Files.writeString(shaders.resolve("gbuffers_terrain.tes"), "#version 400\nvoid main() {}");

        try (ShaderPackSources sources = ShaderPackSources.open(descriptor(temporaryDirectory))) {
            var result = new ShaderPackProgramCatalog().discover(sources);
            assertEquals(List.of("begin", "shadow", "gbuffers_terrain", "deferred", "composite", "composite2", "final"),
                    result.programs().stream().map(ShaderPackProgramCatalog.Program::name).toList());
            var terrain = result.programs().stream().filter(program -> program.name().equals("gbuffers_terrain")).findFirst().orElseThrow();
            assertTrue(terrain.stages().containsKey(ShaderPackProgramCatalog.Stage.GEOMETRY));
            assertTrue(terrain.stages().containsKey(ShaderPackProgramCatalog.Stage.TESSELLATION_CONTROL));
            assertTrue(terrain.stages().containsKey(ShaderPackProgramCatalog.Stage.TESSELLATION_EVALUATION));
            assertTrue(result.programs().stream().filter(program -> program.name().equals("composite")).findFirst().orElseThrow().hasCompute());
            assertTrue(result.requiresWorldPipeline());
            assertFalse(result.isFinalOnly());
            assertTrue(result.ignoredStages().isEmpty());
            assertTrue(result.invalidPrograms().isEmpty());
        }
    }

    @Test
    void keepsColorwheelProgramsOutsideTheIrisProgramGroups() {
        assertEquals(ShaderPackProgramCatalog.Group.UNKNOWN,
                ShaderPackProgramCatalog.Group.classify("clrwl_gbuffers"));
        assertEquals(ShaderPackProgramCatalog.Group.UNKNOWN,
                ShaderPackProgramCatalog.Group.classify("clrwl_shadow"));
    }

    @Test
    void recognizesStandaloneFinalPassAsCurrentSupportedShape() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("shaders"));
        Files.writeString(shaders.resolve("final.vsh"), "#version 150\nvoid main() {}");
        Files.writeString(shaders.resolve("final.fsh"), "#version 150\nout vec4 color; void main() { color = vec4(1); }");
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor(temporaryDirectory))) {
            var result = new ShaderPackProgramCatalog().discover(sources);
            assertTrue(result.isFinalOnly());
            assertFalse(result.requiresWorldPipeline());
        }
    }

    @Test
    void reportsUnmatchedAndInvalidProgramStagesInsteadOfGuessing() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("shaders"));
        Files.writeString(shaders.resolve("final.fsh"), "#version 150\nvoid main() {}");
        Files.writeString(shaders.resolve("orphan.vsh"), "#version 150\nvoid main() {}");
        Files.writeString(shaders.resolve("composite.csh"), "#version 430\nvoid main() {}");
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor(temporaryDirectory))) {
            var result = new ShaderPackProgramCatalog().discover(sources);
            assertFalse(result.isFinalOnly());
            assertEquals(List.of("final"), result.invalidPrograms());
            assertEquals(List.of("orphan.vsh"), result.ignoredStages());
            assertTrue(result.programs().stream().anyMatch(ShaderPackProgramCatalog.Program::hasCompute));
        }
    }

    private static ShaderPackDescriptor descriptor(Path root) {
        return new ShaderPackDescriptor("test", "Test", root, "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 2, 1, 0);
    }
}
