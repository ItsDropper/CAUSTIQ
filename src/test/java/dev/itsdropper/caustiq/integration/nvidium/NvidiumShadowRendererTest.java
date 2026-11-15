package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NvidiumShadowRendererTest {
    @AfterEach
    void reset() {
        NvidiumShadowRenderer.configure(false, 2048, 128.0f);
    }

    @Test
    void clampsShadowResolutionToSupportedPowerOfTwoRange() {
        NvidiumShadowRenderer.configure(true, 5000, 128.0f);

        assertTrue(NvidiumShadowRenderer.enabledForTests());
        assertEquals(4096, NvidiumShadowRenderer.requestedResolutionForTests());

        NvidiumShadowRenderer.configure(true, 16384, 128.0f);
        assertEquals(8192, NvidiumShadowRenderer.requestedResolutionForTests());

        NvidiumShadowRenderer.configure(true, 128, 128.0f);
        assertEquals(256, NvidiumShadowRenderer.requestedResolutionForTests());
    }

    @Test
    void clampsInvalidAndExcessiveShadowDistance() {
        NvidiumShadowRenderer.configure(true, 2048, Float.NaN);
        assertEquals(128.0f, NvidiumShadowRenderer.requestedDistanceForTests(), 0.0001f);

        NvidiumShadowRenderer.configure(true, 2048, 900.0f);
        assertEquals(512.0f, NvidiumShadowRenderer.requestedDistanceForTests(), 0.0001f);

        NvidiumShadowRenderer.configure(true, 2048, 2.0f);
        assertEquals(16.0f, NvidiumShadowRenderer.requestedDistanceForTests(), 0.0001f);
    }

    @Test
    void disablingShadowRenderingDoesNotLeaveItEnabled() {
        NvidiumShadowRenderer.configure(true, 2048, 128.0f);
        NvidiumShadowRenderer.configure(false, 2048, 128.0f);

        assertFalse(NvidiumShadowRenderer.enabledForTests());
    }
}
