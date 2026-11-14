package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StillwaterShaderPackTest {
    @Test
    void bundledStillwaterPackIsDiscoverableAndHasARealFinalProgram() throws IOException {
        Path packPath = Path.of("examples", "caustiq-stillwater");
        ShaderPackScanResult scan = new ShaderPackScanner().scan(packPath.getParent());

        ShaderPackDescriptor pack = scan.packs().stream()
                .filter(candidate -> candidate.source().equals(packPath.toAbsolutePath().normalize()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Stillwater pack was not discovered: " + scan.issues()));

        try (ShaderPackSources sources = ShaderPackSources.open(pack)) {
            ShaderPackProgramCatalog.Result catalog = new ShaderPackProgramCatalog().discover(sources);
            assertEquals(1, catalog.programs().size());
            assertEquals("final", catalog.programs().getFirst().name());

            String fragment = sources.read("final.fsh");
            assertTrue(fragment.contains("uniform sampler2D DiffuseSampler"));
            assertTrue(fragment.contains("textureSize(DiffuseSampler, 0)"));
            assertTrue(fragment.contains("gatherBloom"));
            assertTrue(fragment.contains("filmicShoulder"));
            assertTrue(fragment.contains("interleavedNoise"));
            assertFalse(fragment.toLowerCase().contains("todo"));
            assertFalse(fragment.toLowerCase().contains("placeholder"));
        }

        assertTrue(Files.size(packPath.resolve("shaders/final.fsh")) > 2_000,
                "Stillwater must remain a substantive shader implementation, not a stub");
    }
}
