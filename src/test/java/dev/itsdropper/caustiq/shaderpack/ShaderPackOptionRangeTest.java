package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShaderPackOptionRangeTest {
    @Test
    void resolvesConfiguredValuesBeforeDefaults() {
        assertEquals(1, ShaderPackOptionRange.indexForValue(
                List.of("LOW", "MEDIUM", "HIGH"), "MEDIUM", "HIGH"));
    }

    @Test
    void fallsBackToTheDeclaredDefaultWhenASavedValueDisappears() {
        assertEquals(1, ShaderPackOptionRange.indexForValue(
                List.of("LOW", "MEDIUM", "HIGH"), "ULTRA", "MEDIUM"));
    }

    @Test
    void fallsBackToTheFirstAllowedValueWhenTheDefaultIsAlsoUnavailable() {
        assertEquals(0, ShaderPackOptionRange.indexForValue(
                List.of("LOW", "MEDIUM", "HIGH"), "ULTRA", "AUTO"));
    }

    @Test
    void roundTripsEveryDiscreteIndexAcrossDifferentRangeSizes() {
        for (int count = 2; count <= 64; count++) {
            for (int index = 0; index < count; index++) {
                double progress = ShaderPackOptionRange.progressForIndex(index, count);
                assertEquals(index, ShaderPackOptionRange.indexForProgress(progress, count),
                        "range size " + count + ", index " + index);
            }
        }
    }

    @Test
    void mapsIntermediatePositionsToTheNearestDeclaredValue() {
        assertEquals(0, ShaderPackOptionRange.indexForProgress(0.16, 4));
        assertEquals(1, ShaderPackOptionRange.indexForProgress(0.17, 4));
        assertEquals(2, ShaderPackOptionRange.indexForProgress(0.50, 4));
        assertEquals(3, ShaderPackOptionRange.indexForProgress(0.84, 4));
    }

    @Test
    void clampsPositionsAndIndexesAndTreatsNanAsTheFirstValue() {
        assertEquals(0.0, ShaderPackOptionRange.progressForIndex(-5, 4));
        assertEquals(1.0, ShaderPackOptionRange.progressForIndex(50, 4));
        assertEquals(0, ShaderPackOptionRange.indexForProgress(-2.0, 4));
        assertEquals(3, ShaderPackOptionRange.indexForProgress(2.0, 4));
        assertEquals(0, ShaderPackOptionRange.indexForProgress(Double.NaN, 4));
        assertEquals(3, ShaderPackOptionRange.indexForProgress(Double.POSITIVE_INFINITY, 4));
    }

    @Test
    void handlesSingleValueOptionsWithoutDivisionByZero() {
        assertEquals(0.0, ShaderPackOptionRange.progressForIndex(7, 1));
        assertEquals(0, ShaderPackOptionRange.indexForProgress(0.75, 1));
    }

    @Test
    void rejectsEmptyValueSetsAndInvalidRangeSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> ShaderPackOptionRange.indexForValue(List.of(), "A", "A"));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderPackOptionRange.progressForIndex(0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderPackOptionRange.indexForProgress(0.5, 0));
    }
}
