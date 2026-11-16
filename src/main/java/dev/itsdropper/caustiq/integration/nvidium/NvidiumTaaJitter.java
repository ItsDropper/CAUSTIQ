package dev.itsdropper.caustiq.integration.nvidium;

import org.joml.Matrix4f;

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

    public static Offset apply(Matrix4f projection, int frame, int width, int height) {
        return apply(projection, frame, width, height, 1.0f);
    }

    public static Offset apply(Matrix4f projection, int frame, int width, int height, float projectionScale) {
        Offset offset = forFrame(frame, width, height);
        projection.m20(projection.m20() - offset.x() * projectionScale);
        projection.m21(projection.m21() - offset.y() * projectionScale);
        return offset;
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
