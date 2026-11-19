package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShaderRuntimeDimensionTest {
    @Test
    void defaultsRendererConstructionWithoutAClientLevelToOverworld() {
        assertEquals("minecraft:overworld", ShaderRuntime.resolveDimensionId(null));
        assertEquals("minecraft:overworld", ShaderRuntime.resolveDimensionId(""));
        assertEquals("minecraft:overworld", ShaderRuntime.resolveDimensionId("   "));
    }

    @Test
    void preservesAnAttachedWorldDimension() {
        assertEquals("minecraft:the_nether", ShaderRuntime.resolveDimensionId("minecraft:the_nether"));
        assertEquals("minecraft:the_end", ShaderRuntime.resolveDimensionId("minecraft:the_end"));
    }
}
