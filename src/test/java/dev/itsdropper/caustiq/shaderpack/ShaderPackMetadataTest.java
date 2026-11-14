package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;
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
