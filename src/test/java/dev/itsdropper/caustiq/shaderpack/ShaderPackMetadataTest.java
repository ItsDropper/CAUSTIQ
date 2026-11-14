package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShaderPackMetadataTest {
    @Test
    void parsesOptionsAndSliderDeclarationsFromNestedScreens() {
        ShaderPackMetadata metadata = ShaderPackMetadata.parse("""
                screen=SHADOWS QUALITY
                screen.SHADOWS=SHADOW_MODE
                sliders=QUALITY
                option.SHADOW_MODE=OFF LOW HIGH
                option.QUALITY=0.0 0.5 1.0
                """);

        assertEquals(2, metadata.options().size());
        ShaderPackMetadata.Option mode = metadata.options().getFirst();
        assertEquals("SHADOW_MODE", mode.name());
        assertEquals("OFF", mode.defaultValue());
        assertEquals(3, mode.values().size());

        ShaderPackMetadata.Option quality = metadata.options().get(1);
        assertEquals("QUALITY", quality.name());
        assertTrue(quality.slider());
        assertEquals("0.0", quality.defaultValue());
    }

    @Test
    void parsesInlineGlslDefineOptionsAndUsesTheirDeclaredDefaults() {
        ShaderPackMetadata metadata = ShaderPackMetadata.parse("""
                screen=SHADOW_QUALITY
                sliders=SHADOW_QUALITY
                """, Map.of("final.fsh", """
                #define SHADOW_QUALITY 2 // [0 1 2 3]
                #define UNUSED true // [true false]
                """));

        assertEquals(1, metadata.options().size());
        ShaderPackMetadata.Option option = metadata.options().getFirst();
        assertEquals("SHADOW_QUALITY", option.name());
        assertEquals("2", option.defaultValue());
        assertEquals(java.util.List.of("2", "0", "1", "3"), option.values());
        assertTrue(option.slider());
    }

    @Test
    void ignoresUnsafeOptionNamesAndValues() {
        ShaderPackMetadata metadata = ShaderPackMetadata.parse("""
                option.GOOD=0 1
                option.BAD-NAME=0 1
                option.INJECTION=1;touch
                """);

        assertEquals(1, metadata.options().size());
        assertEquals("GOOD", metadata.options().getFirst().name());
    }

    @Test
    void rejectsOversizedMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> ShaderPackMetadata.parse("x".repeat(256 * 1024 + 1)));
    }
}
