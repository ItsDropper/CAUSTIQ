package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ShaderPackSmokeTest {
    @Test
    void bundledSmokePackIsDiscoverableAndContainsFinalProgram() throws IOException {
        Path packPath = Path.of("examples", "caustiq-smoke-test");
        ShaderPackScanResult scan = new ShaderPackScanner().scan(packPath.getParent());

        ShaderPackDescriptor pack = scan.packs().stream()
                .filter(candidate -> candidate.source().equals(packPath.toAbsolutePath().normalize()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Bundled smoke-test pack was not discovered: " + scan.issues()));

        try (ShaderPackSources sources = ShaderPackSources.open(pack)) {
            ShaderPackProgramCatalog.Result catalog = new ShaderPackProgramCatalog().discover(sources);
            assertTrue(catalog.programs().stream().anyMatch(program -> program.name().equals("final")),
                    "Smoke-test pack must expose a paired final program");
            assertEquals(1, catalog.programs().size());
            assertTrue(catalog.ignoredStages().isEmpty());
        }
    }
}
