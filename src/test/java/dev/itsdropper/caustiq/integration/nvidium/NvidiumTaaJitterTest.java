package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NvidiumTaaJitterTest {
    @Test
    void generatesBoundedNonRepeatingHaltonOffsetsForTheEightFrameSequence() {
        var first = NvidiumTaaJitter.forFrame(1, 1920, 1080);
        var second = NvidiumTaaJitter.forFrame(2, 1920, 1080);
        var third = NvidiumTaaJitter.forFrame(3, 1920, 1080);

        assertEquals(0.0f, first.x(), 1.0e-7f);
        assertTrue(Math.abs(first.y()) <= 1.0f / 1080.0f);
        assertNotEquals(first, second);
        assertNotEquals(second, third);
        for (int frame = 1; frame <= 8; frame++) {
            var offset = NvidiumTaaJitter.forFrame(frame, 1920, 1080);
            assertTrue(Math.abs(offset.x()) <= 1.0f / 1920.0f);
            assertTrue(Math.abs(offset.y()) <= 1.0f / 1080.0f);
        }
        assertEquals(first, NvidiumTaaJitter.forFrame(9, 1920, 1080));
    }

    @Test
    void returnsZeroOffsetForInvalidViewportDimensions() {
        assertEquals(new NvidiumTaaJitter.Offset(0.0f, 0.0f),
                NvidiumTaaJitter.forFrame(1, 0, 1080));
    }
}
