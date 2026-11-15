package dev.itsdropper.caustiq.integration.nvidium;

import dev.itsdropper.caustiq.shaderpack.IrisShaderResources;
import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackLayout;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSourceType;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSources;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audits the exact public Complementary Reimagined r5.9.3 archive used by CI.
 * This intentionally tests the complete custom uniform/variable expression
 * table, not a hand-picked subset copied into a synthetic test pack.
 */
class ComplementaryReimaginedCompatibilityTest {
    @Test
    void evaluatesEveryCustomUniformAndVariableFromPinnedR593Archive() throws IOException {
        String archiveValue = System.getenv("COMPLEMENTARY_SOURCE_ARCHIVE");
        Assumptions.assumeTrue(archiveValue != null && !archiveValue.isBlank(),
                "CI supplies the pinned Complementary Reimagined r5.9.3 archive.");

        Path archive = Path.of(archiveValue);
        assertTrue(Files.isRegularFile(archive) && Files.size(archive) > 0L,
                "Pinned Complementary Reimagined archive is missing or empty.");

        ShaderPackDescriptor descriptor = new ShaderPackDescriptor(
                "complementary-reimagined-r593",
                "Complementary Reimagined r5.9.3",
                archive,
                "",
                ShaderPackSourceType.ZIP_ARCHIVE,
                ShaderPackLayout.OPTIFINE_STYLE,
                1,
                1,
                Files.size(archive));

        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            Properties loaded = new Properties();
            loaded.load(new StringReader(sources.read("shaders.properties")));
            Map<String, String> properties = new HashMap<>();
            for (String name : loaded.stringPropertyNames()) {
                properties.put(name, loaded.getProperty(name));
            }

            try (IrisShaderResources resources = IrisShaderResources.loadExpressions(
                    properties, Map.of("MC_VERSION", "12111", "IS_IRIS", "1"))) {
                assertFalse(resources.uniformNames().isEmpty(),
                        "Complementary r5.9.3 must expose custom uniforms.");
                assertTrue(resources.variableNames().containsAll(
                                java.util.Set.of("difX", "difY", "difZ", "difSum", "moving", "moved",
                                        "endFlashFactor0", "endFlashFactor1")),
                        "Complementary's declared variable dependency chain was not loaded.");

                Map<String, Double> context = new HashMap<>();
                NvidiumIrisUniformBridge.putIrisBuiltInDefaults(context);
                context.put("frameCounter", 123.0);
                context.put("frameTime", 1.0 / 60.0);
                context.put("isEyeInWater", 0.0);
                context.put("eyeAltitude", 64.0);
                context.put("eyeBrightness.y", 180.0);
                context.put("cameraPosition.x", 100.0);
                context.put("cameraPosition.y", 64.0);
                context.put("cameraPosition.z", -40.0);
                context.put("previousCameraPosition.x", 99.5);
                context.put("previousCameraPosition.y", 64.0);
                context.put("previousCameraPosition.z", -40.0);
                context.put("rainStrength", 0.35);
                context.put("biome", 0.0);
                context.put("biome_precipitation", 1.0);
                context.put("blindness", 0.0);
                context.put("darknessFactor", 0.0);
                context.put("endFlashIntensity", 0.0);

                Map<String, double[]> evaluated = resources.evaluateUniformValues(context);
                assertEquals(resources.uniformNames(), evaluated.keySet(),
                        "Every declared Complementary custom uniform must evaluate.");
                for (Map.Entry<String, double[]> uniform : evaluated.entrySet()) {
                    for (double component : uniform.getValue()) {
                        assertTrue(Double.isFinite(component),
                                "Complementary uniform '" + uniform.getKey()
                                        + "' evaluated to a non-finite component.");
                    }
                }
            }
        }
    }
}
