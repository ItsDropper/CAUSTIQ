package dev.itsdropper.caustiq.integration.nvidium;

/**
 * Eight-sample Halton jitter for the projection used by Nvidium's terrain pass.
 * Offsets are expressed in clip-space units so the same values can be exposed
 * through Iris' taa_offset uniform.
 */
public final class NvidiumTaaJitter {
    private static final int SAMPLE_COUNT = 8;

    private NvidiumTaaJitter() { }

    public static Offset forFrame(int frame, int width, int height) {
        if (width <= 0 || height <= 0) return new Offset(0.0f, 0.0f);
        int sample = Math.floorMod(frame - 1, SAMPLE_COUNT) + 1;
        float x = (halton(sample, 2) - 0.5f) * 2.0f / width;
        float y = (halton(sample, 3) - 0.5f) * 2.0f / height;
        return new Offset(x, y);
    }

    private static float halton(int index, int base) {
        float result = 0.0f;
        float fraction = 1.0f / base;
        int value = index;
        while (value > 0) {
            result += fraction * (value % base);
            value /= base;
            fraction /= base;
        }
        return result;
    }

    public record Offset(float x, float y) { }
}
