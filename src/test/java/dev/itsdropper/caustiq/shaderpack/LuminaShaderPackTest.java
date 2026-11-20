package dev.itsdropper.caustiq.shaderpack;

import dev.itsdropper.caustiq.integration.nvidium.IrisNvidiumTerrainTranslator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lumina is written against Nvidium's real terrain ABI, so these tests run the pack
 * through the same parser, audit and translator the game uses. They cannot prove the
 * image looks right, but they catch the failures that silently disable a pack.
 */
class LuminaShaderPackTest {
    private static final Path PACK = Path.of("examples", "caustiq-lumina");

    private static ShaderPackSources open() throws IOException {
        ShaderPackScanResult scan = new ShaderPackScanner().scan(PACK.getParent());
        ShaderPackDescriptor pack = scan.packs().stream()
                .filter(candidate -> candidate.source().equals(PACK.toAbsolutePath().normalize()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Lumina was not discovered: " + scan.issues()));
        return ShaderPackSources.open(pack);
    }

    private static Map<String, String> defaultOptions(IrisShaderPackModel model) {
        Map<String, String> options = new LinkedHashMap<>();
        model.options().options().forEach(option -> options.put(option.name(), option.defaultValue()));
        return options;
    }

    @Test
    void passesTheNvidiumTerrainAuditWithoutDiagnostics() throws IOException {
        try (ShaderPackSources sources = open()) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            assertEquals(List.of(), model.diagnostics());
        }
    }

    @Test
    void terrainAndShadowProgramsTranslateForNvidium() throws IOException {
        try (ShaderPackSources sources = open()) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            Map<String, String> options = defaultOptions(model);

            IrisNvidiumTerrainTranslator terrain =
                    IrisNvidiumTerrainTranslator.from(model, null, "gbuffers_terrain", options);
            assertEquals(List.of(1, 2, 3, 5), terrain.renderTargetIndices());

            IrisNvidiumTerrainTranslator shadow =
                    IrisNvidiumTerrainTranslator.from(model, null, "shadow", options);
            assertEquals(List.of(0), shadow.renderTargetIndices());
        }
    }

    @Test
    void exposesNumericOptionsAsSlidersAndLeavesBinaryTogglesAsCycleControls() throws IOException {
        try (ShaderPackSources sources = open()) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            Map<String, ShaderPackMetadata.Option> options = new LinkedHashMap<>();
            model.options().options().forEach(option -> options.put(option.name(), option));

            assertTrue(options.get("EXPOSURE").slider());
            assertTrue(options.get("SHADOW_SAMPLES").slider());
            assertEquals(List.of("8", "12", "16", "24"), options.get("SHADOW_SAMPLES").values());
            assertTrue(options.get("SATURATION").slider());
            assertFalse(options.get("CONTACT_SHADOWS").slider());
            assertFalse(options.get("FXAA").slider());
        }
    }

    @Test
    void declaresTheShadowMapAndEveryGBufferFormat() throws IOException {
        try (ShaderPackSources sources = open()) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            assertEquals("2048", model.properties().get("shadowMapResolution"));
            assertEquals("96", model.properties().get("shadowDistance"));
            for (int target = 1; target <= 5; target++) {
                assertEquals("RGBA16F", model.properties().get("colortex" + target + "Format"),
                        "colortex" + target);
            }
        }
    }

    @Test
    void terrainStagesAvoidPreprocessorConditionals() throws IOException {
        try (ShaderPackSources sources = open()) {
            for (String path : List.of("gbuffers_terrain.vsh", "gbuffers_terrain.fsh",
                    "shadow.vsh", "shadow.fsh")) {
                String text = sources.read(path);
                assertTrue(text.lines().noneMatch(line -> line.matches("\\s*#\\s*(if|ifdef|ifndef)\\b.*")),
                        path + " must stay free of conditionals so the terrain audit can prove its interface");
            }
        }
    }

    @Test
    void terrainSamplesTheShadowMapAndReadsItsLightDirection() throws IOException {
        try (ShaderPackSources sources = open()) {
            String fragment = sources.read("gbuffers_terrain.fsh");
            assertTrue(fragment.contains("uniform sampler2D shadowtex0"));
            assertTrue(fragment.contains("shadowModelViewInverse"));
            assertTrue(fragment.contains("uniform int worldTime"),
                    "worldTime is an int tick count in Nvidium's terrain ABI");
            assertFalse(fragment.toLowerCase().contains("todo"));
        }
    }
}
