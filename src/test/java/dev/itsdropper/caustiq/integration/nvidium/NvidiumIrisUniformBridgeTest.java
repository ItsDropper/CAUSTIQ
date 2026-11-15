package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NvidiumIrisUniformBridgeTest {
    @Test
    void exposesSunPositionInViewSpaceUsingMinecraftCelestialTransform() {
        Map<String, Double> values = new HashMap<>();
        float[] identity = new org.joml.Matrix4f().get(new float[16]);
        NvidiumIrisUniformBridge.putSunPositionComponents(values, identity, 0.0f);
        assertEquals(0.0, values.get("sunPosition.x"), 1.0e-5);
        assertEquals(100.0, values.get("sunPosition.y"), 1.0e-5);
        assertEquals(0.0, values.get("sunPosition.z"), 1.0e-5);

        values.clear();
        NvidiumIrisUniformBridge.putSunPositionComponents(values, identity, (float) (Math.PI / 2.0));
        assertEquals(-100.0, values.get("sunPosition.x"), 1.0e-4);
        assertEquals(0.0, values.get("sunPosition.y"), 1.0e-4);
        assertEquals(0.0, values.get("sunPosition.z"), 1.0e-4);
    }

    @Test
    void exposesIrisBiomeTemperatureAndRainfallUniforms() {
        Map<String, Double> values = new HashMap<>();
        NvidiumIrisUniformBridge.putBiomeUniforms(values, 0.8f, 0.4f);
        assertEquals(0.8, values.get("temperature"), 1.0e-6);
        assertEquals(0.4, values.get("rainfall"), 1.0e-6);

        values.clear();
        NvidiumIrisUniformBridge.putBiomeUniforms(values, Float.NaN, Float.NaN);
        assertEquals(0.0, values.get("temperature"));
        assertEquals(0.0, values.get("rainfall"));
    }

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
