package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NvidiumIrisTextureBridgeTest {
    @Test
    void rejectsOpaqueDepthSnapshotsFromPreviousFrames() {
        assertEquals(true, NvidiumIrisTextureBridge.isDepthSnapshotCurrent(12, 12));
        assertEquals(false, NvidiumIrisTextureBridge.isDepthSnapshotCurrent(11, 12));
        assertEquals(false, NvidiumIrisTextureBridge.isDepthSnapshotCurrent(0, 0));
    }

    @Test
    void skipsReservedShadowTextureUnitsWhenAllocatingCustomSamplers() {
        assertEquals(6, NvidiumIrisTextureBridge.firstCustomTextureUnit(6));
        assertEquals(11, NvidiumIrisTextureBridge.firstCustomTextureUnit(11));
        assertEquals(16, NvidiumIrisTextureBridge.firstCustomTextureUnit(12));
        assertEquals(16, NvidiumIrisTextureBridge.firstCustomTextureUnit(15));
        assertEquals(16, NvidiumIrisTextureBridge.firstCustomTextureUnit(16));
        assertEquals(21, NvidiumIrisTextureBridge.firstCustomTextureUnit(21));
    }
}
