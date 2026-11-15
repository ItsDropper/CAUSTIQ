package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NvidiumGBufferTargetsTest {
    @Test
    void preservesNativeColorAndRoutesIrisOutputsToRequestedColortexAttachments() {
        assertArrayEquals(new int[]{0, 1, 3, 4, -1, -1, -1, -1},
                NvidiumGBufferTargets.createOutputRouting(List.of(1, 3, 4)));
    }

    @Test
    void preservesNvidiumsIdentityRoutingWhenNoIrisAdapterIsInstalled() {
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7},
                NvidiumGBufferTargets.createOutputRouting(List.of(0, 1, 2, 3, 4, 5, 6, 7)));
    }

    @Test
    void reservesColortexZeroForNativeNvidiumColor() {
        assertThrows(IllegalArgumentException.class,
                () -> NvidiumGBufferTargets.createOutputRouting(List.of(0, 1)));
    }

    @Test
    void rejectsMoreIrisOutputsThanAvailableDrawBufferSlots() {
        assertThrows(IllegalArgumentException.class,
                () -> NvidiumGBufferTargets.createOutputRouting(List.of(1, 2, 3, 4, 5, 6, 7, 1)));
    }
}
