package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisGlslConditionalPreprocessorTest {
    @Test
    void selectsTerrainVariantAndResolvesNestedOptionMacros() {
        String source = """
                #define AO_NONE 0
                #define AO_GTAO 2
                #define AO AO_GTAO
                #if defined(PROGRAM_GBUFFERS_TERRAIN) && !defined(POM) && (MC_VERSION >= 12100)
                #define TERRAIN_VARIANT 1
                #if AO != AO_NONE
                vec3 terrainAo();
                #elif defined(POM)
                vec3 parallax();
                #else
                vec3 flatTerrain();
                #endif
                #else
                vec3 entityVariant();
                #endif
                #if defined(VOXY) || defined(DISTANT_HORIZONS)
                uniform sampler2D lodDepth;
                #else
                vec3 vanillaDepthPath();
                #endif
                """;

        String result = new IrisGlslConditionalPreprocessor().preprocess(source, Map.of(
                "PROGRAM_GBUFFERS_TERRAIN", "1",
                "MC_VERSION", "12111"));

        assertTrue(result.contains("vec3 terrainAo();"));
        assertTrue(result.contains("vec3 vanillaDepthPath();"));
        assertFalse(result.contains("vec3 parallax();"));
        assertFalse(result.contains("vec3 entityVariant();"));
        assertFalse(result.contains("uniform sampler2D lodDepth;"));
    }

    @Test
    void inactiveBranchesDoNotLeakMacroDefinitions() {
        String source = """
                #if defined(OPTION_DISABLED)
                #define LEAKED 1
                #else
                #define ACTIVE 1
                #endif
                #ifdef LEAKED
                vec3 wrong();
                #elif defined(ACTIVE)
                vec3 correct();
                #endif
                """;

        String result = new IrisGlslConditionalPreprocessor().preprocess(source, Map.of());

        assertTrue(result.contains("vec3 correct();"));
        assertFalse(result.contains("vec3 wrong();"));
    }
}
