package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NvidiumGBufferTargetsTest {
    @Test
    void preservesNativeColorAndRoutesIrisOutputsToRequestedColortexAttachments() {
        assertArrayEquals(new int[]{0, 1, 2, 3, -1, -1, -1, -1},
                NvidiumGBufferTargets.createOutputRouting(List.of(1, 3, 4)));
    }

    @Test
    void mapsHighLogicalColortexIndicesIntoAvailablePhysicalAttachments() {
        assertArrayEquals(new int[]{0, 1, 2, -1, -1, -1, -1, -1},
                NvidiumGBufferTargets.createOutputRouting(List.of(3, 13)));
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

    @Test
    void generatesMipmapsOnlyForTerrainOutputsDeclaredAsMipmapped() {
        var specs = java.util.Map.of(
                3, new dev.itsdropper.caustiq.shaderpack.IrisTerrainPassScheduler.RenderTarget(
                        3, "RGBA16F", true, "0 0 0 0", true, false, 1, 1, false),
                5, new dev.itsdropper.caustiq.shaderpack.IrisTerrainPassScheduler.RenderTarget(
                        5, "RGBA16F", true, "0 0 0 0", false, false, 1, 1, false),
                7, new dev.itsdropper.caustiq.shaderpack.IrisTerrainPassScheduler.RenderTarget(
                        7, "RGBA16F", true, "0 0 0 0", true, false, 1, 1, false));
        assertEquals(List.of(3, 7), NvidiumGBufferTargets.mipmapTargets(List.of(3, 5, 7), specs));
        assertEquals(List.of(), NvidiumGBufferTargets.mipmapTargets(List.of(1, 2), specs));
    }
}
