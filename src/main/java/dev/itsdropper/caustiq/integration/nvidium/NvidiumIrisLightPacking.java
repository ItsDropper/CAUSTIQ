package dev.itsdropper.caustiq.integration.nvidium;

/** Pure packing logic shared by the Sodium vertex encoder and unit tests. */
final class NvidiumIrisLightPacking {
    private NvidiumIrisLightPacking() { }

    static int compactLight(int light) {
        // Match Nvidium's native Sodium-format encoder without shifting UV light.
        int sky = Math.max(8, Math.min(248, (light >>> 16) & 0xFF));
        int block = Math.max(8, Math.min(248, light & 0xFF));
        return block | (sky << 8);
    }
}
