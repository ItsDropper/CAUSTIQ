package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NvidiumIrisChunkVertexTest {
    @Test
    void preservesNvidiumLightCoordinatesWithoutAnExtraOffset() {
        assertEquals((128 << 8) | 64, NvidiumIrisChunkVertex.compactLight((128 << 16) | 64));
    }

    @Test
    void clampsLightCoordinatesToNvidiumsNativeSamplingRange() {
        assertEquals((8 << 8) | 8, NvidiumIrisChunkVertex.compactLight(0));
        assertEquals((248 << 8) | 248,
                NvidiumIrisChunkVertex.compactLight((255 << 16) | 255));
    }
}
