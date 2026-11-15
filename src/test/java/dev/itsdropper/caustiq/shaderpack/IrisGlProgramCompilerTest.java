package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisGlProgramCompilerTest {
    @Test
    void suppliesIrisHandDepthDefineToFullscreenPasses() {
        Set<String> defines = IrisGlProgramCompiler.runtimeDefines(Set.of("SHADOWS=1"));

        assertTrue(defines.contains("MC_HAND_DEPTH=1.0"));
        assertTrue(defines.contains("SHADOWS=1"));
    }

    @Test
    void suppliesIrisStandardQualityDefinesToFullscreenPasses() {
        Set<String> defines = IrisGlProgramCompiler.runtimeDefines(Set.of());

        assertTrue(defines.contains("MC_RENDER_QUALITY=1.0"));
        assertTrue(defines.contains("MC_SHADOW_QUALITY=1.0"));
    }

    @Test
    void preservesPackHandDepthDefineWithoutAddingAConflictingRuntimeDefault() {
        Set<String> defines = IrisGlProgramCompiler.runtimeDefines(Set.of("MC_HAND_DEPTH=0.125"));

        assertEquals(1, defines.stream().filter(value -> value.startsWith("MC_HAND_DEPTH=")).count());
        assertTrue(defines.contains("MC_HAND_DEPTH=0.125"));
        assertTrue(!defines.contains("MC_HAND_DEPTH=1.0"));
    }
}
