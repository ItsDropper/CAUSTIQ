package dev.itsdropper.caustiq.shaderpack;

import java.util.List;
import java.util.Objects;

/** Maps pack-defined discrete option values to and from a normalized slider position. */
public final class ShaderPackOptionRange {
    private ShaderPackOptionRange() { }

    /** Resolves a saved value, falling back to the pack default and then its first allowed value. */
    public static int indexForValue(List<String> values, String configuredValue, String defaultValue) {
        requireValues(values);
        int index = values.indexOf(configuredValue);
        if (index >= 0) return index;
        index = values.indexOf(defaultValue);
        return index >= 0 ? index : 0;
    }

    /** Converts a discrete value index to the slider's normalized [0, 1] position. */
    public static double progressForIndex(int index, int valueCount) {
        requireValueCount(valueCount);
        if (valueCount == 1) return 0.0;
        return Math.clamp(index, 0, valueCount - 1) / (double) (valueCount - 1);
    }

    /** Converts a normalized slider position to the nearest discrete value index. */
    public static int indexForProgress(double progress, int valueCount) {
        requireValueCount(valueCount);
        if (valueCount == 1) return 0;
        double normalized = Double.isNaN(progress) ? 0.0 : Math.clamp(progress, 0.0, 1.0);
        return (int) Math.round(normalized * (valueCount - 1));
    }

    private static void requireValues(List<String> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("A shader option must have at least one allowed value");
        }
    }

    private static void requireValueCount(int valueCount) {
        if (valueCount < 1) {
            throw new IllegalArgumentException("A shader option must have at least one allowed value");
        }
    }
}
