package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IrisRenderTargetSizeTest {
    @Test
    void appliesIndependentAxisScalesWithRoundedPixelDimensions() {
        assertEquals(new IrisRenderTargetSize(960, 540),
                IrisRenderTargetSize.of(1920, 1080, 0.5f, 0.5f));
        assertEquals(new IrisRenderTargetSize(1920, 540),
                IrisRenderTargetSize.of(1920, 1080, 1.0f, 0.5f));
        assertEquals(new IrisRenderTargetSize(2880, 1620),
                IrisRenderTargetSize.of(1920, 1080, 1.5f, 1.5f));
    }

    @Test
    void neverProducesZeroSizedAttachments() {
        assertEquals(new IrisRenderTargetSize(1, 1),
                IrisRenderTargetSize.of(1, 1, 0.01f, 0.01f));
    }

    @Test
    void rejectsInvalidScalesAndBaseDimensions() {
        assertThrows(IllegalArgumentException.class, () -> IrisRenderTargetSize.of(0, 1080, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> IrisRenderTargetSize.of(1920, 1080, Float.NaN, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> IrisRenderTargetSize.of(1920, 1080, -0.5f, 1.0f));
    }
}
