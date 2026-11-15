package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NvidiumIrisUniformBridgeTest {
    @Test
    void providesEveryDocumentedIrisUniformAndComponentToExpressionEvaluation() {
        Map<String, Double> values = new HashMap<>();
        NvidiumIrisUniformBridge.putIrisBuiltInDefaults(values);
        String[] required = {
                "cameraPosition.x", "cameraPosition.y", "cameraPosition.z",
                "previousCameraPosition.x", "eyeAltitude", "cameraPositionFract.x",
                "cameraPositionInt.x", "eyePosition.x", "relativeEyePosition.x",
                "playerBodyVector.x", "playerLookVector.x", "vehicleLookVector.x",
                "relativeVehiclePosition.x", "upPosition.x", "eyeBrightness.x",
                "eyeBrightnessSmooth.y", "centerDepthSmooth", "firstPersonCamera",
                "isEyeInWater", "isSpectator", "isRightHanded", "blindness",
                "darknessFactor", "darknessLightFactor", "nightVision", "playerMood",
                "constantMood", "currentPlayerAir", "maxPlayerAir", "currentPlayerArmor",
                "maxPlayerArmor", "currentPlayerHealth", "maxPlayerHealth", "currentPlayerHunger",
                "maxPlayerHunger", "vehicleInWater", "isRiding", "inSwimmingAnimation",
                "feetInWater", "isElytraFlying", "is_burning", "is_hurt", "is_invisible",
                "is_on_ground", "is_sneaking", "is_sprinting", "hideGUI", "viewHeight",
                "viewWidth", "aspectRatio", "screenBrightness", "frameCounter", "frameTime",
                "frameTimeCounter", "currentColorSpace", "entityId", "blockEntityId",
                "vehicleId", "currentRenderedItemId", "currentSelectedBlockId", "heldItemId",
                "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2", "sunPosition.x",
                "moonPosition.y", "shadowLightPosition.z", "sunAngle", "shadowAngle", "moonPhase",
                "rainStrength", "wetness", "thunderStrength", "lightningBoltPosition.w",
                "worldTime", "worldDay", "endFlashPosition.x", "endFlashIntensity",
                "previousEndFlashIntensity", "biome", "biome_category", "biome_precipitation",
                "rainfall", "temperature", "ambientLight", "bedrockLevel", "cloudHeight",
                "hasCeiling", "hasSkylight", "heightLimit", "logicalHeightLimit", "near", "far",
                "alphaTestRef", "chunkOffset.x", "entityColor.a", "blendFunc.x", "atlasSize.x",
                "renderStage", "skyColor.r", "fogColor.b", "fogDensity", "fogStart", "fogEnd",
                "fogMode", "fogShape", "textureFilteringMode", "gbufferModelView.0.0",
                "gbufferModelViewInverse.0.0", "gbufferProjection.0.0",
                "gbufferProjectionInverse.0.0", "shadowModelView.0.0",
                "shadowModelViewInverse.0.0", "shadowProjection.0.0",
                "shadowProjectionInverse.0.0", "gbufferPreviousModelView.0.0",
                "gbufferPreviousProjection.0.0", "gl_ModelViewMatrix.0.0",
                "gl_ModelViewMatrixInverse.0.0", "gl_ProjectionMatrix.0.0",
                "gl_ProjectionMatrixInverse.0.0", "gl_ModelViewProjectionMatrix.0.0",
                "gl_NormalMatrix.0.0", "CAT_DESERT", "CAT_MESA", "CAT_SAVANNA",
                "CAT_ICY", "CAT_TAIGA", "CAT_JUNGLE", "CAT_SWAMP", "PPT_NONE",
                "PPT_RAIN", "PPT_SNOW", "BIOME_PALE_GARDEN"
        };
        for (String name : required) {
            assertTrue(values.containsKey(name), "Missing Iris expression symbol: " + name);
            assertTrue(Double.isFinite(values.get(name)), "Non-finite Iris symbol: " + name);
        }
    }

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
