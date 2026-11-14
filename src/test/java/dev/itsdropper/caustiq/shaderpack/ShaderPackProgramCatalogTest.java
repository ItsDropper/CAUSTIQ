package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ShaderPackProgramCatalogTest {
    @TempDir Path temporaryDirectory;

    @Test
    void discoversMatchingVertexAndFragmentStages() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.vsh"), "#version 150\nvoid main() {}");
        Files.writeString(shaders.resolve("composite.fsh"), "#version 150\nout vec4 color; void main() { color = vec4(1); }");
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), "#version 150\nvoid main() {}");
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), "#version 150\nout vec4 color; void main() { color = vec4(1); }");
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor(temporaryDirectory))) {
            var result = new ShaderPackProgramCatalog().discover(sources);
            assertEquals(2, result.programs().size());
            assertEquals("composite", result.programs().getFirst().name());
            assertEquals("gbuffers_terrain", result.programs().get(1).name());
            assertTrue(result.ignoredStages().isEmpty());
        }
    }

    @Test
    void reportsUnmatchedStagesInsteadOfGuessing() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("shaders"));
        Files.writeString(shaders.resolve("final.fsh"), "#version 150\nvoid main() {}");
        Files.writeString(shaders.resolve("orphan.vsh"), "#version 150\nvoid main() {}");
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor(temporaryDirectory))) {
            var result = new ShaderPackProgramCatalog().discover(sources);
            assertTrue(result.programs().isEmpty());
            assertEquals(2, result.ignoredStages().size());
        }
    }

    private static ShaderPackDescriptor descriptor(Path root) {
        return new ShaderPackDescriptor("test", "Test", root, "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 2, 1, 0);
    }
}
