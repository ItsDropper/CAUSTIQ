package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NvidiumIrisUniformBridgeTest {
    @Test
    void exposesColumnMajorMatrixComponentsForIrisCustomExpressions() {
        Map<String, Double> values = new HashMap<>();
        float[] matrix = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        NvidiumIrisUniformBridge.putMatrixComponents(values, "gbufferModelViewInverse", matrix, 4);
        assertEquals(1.0, values.get("gbufferModelViewInverse.0.0"));
        assertEquals(2.0, values.get("gbufferModelViewInverse.0.1"));
        assertEquals(5.0, values.get("gbufferModelViewInverse.1.0"));
        assertEquals(16.0, values.get("gbufferModelViewInverse.3.3"));
    }
}
