package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NvidiumTerrainPassBridgeTest {
    @Test
    void recognizesCutoutAndCutoutMippedLayers() {
        assertTrue(NvidiumTerrainPassBridge.isCutoutPass(new TestPass(RenderType.CUTOUT)));
        assertTrue(NvidiumTerrainPassBridge.isCutoutPass(new TestPass(RenderType.CUTOUT_MIPPED)));
    }

    @Test
    void doesNotClassifySolidOrTranslucentLayersAsCutout() {
        assertFalse(NvidiumTerrainPassBridge.isCutoutPass(new TestPass(RenderType.SOLID)));
        assertFalse(NvidiumTerrainPassBridge.isCutoutPass(new TestPass(RenderType.TRANSLUCENT)));
        assertFalse(NvidiumTerrainPassBridge.isCutoutPass(null));
    }

    public static final class TestPass {
        private final RenderType renderType;
        TestPass(RenderType renderType) { this.renderType = renderType; }
        public RenderType renderType() { return renderType; }
    }

    private enum RenderType { SOLID, CUTOUT, CUTOUT_MIPPED, TRANSLUCENT }
}