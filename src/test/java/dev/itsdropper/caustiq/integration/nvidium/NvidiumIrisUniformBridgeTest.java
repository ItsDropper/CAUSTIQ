package dev.itsdropper.caustiq.integration.nvidium;

import dev.itsdropper.caustiq.shaderpack.IrisShaderPackModel;
import dev.itsdropper.caustiq.shaderpack.IrisShaderPackParser;
import dev.itsdropper.caustiq.shaderpack.IrisShaderResources;
import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackLayout;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSourceType;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSources;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NvidiumIrisUniformBridgeTest {
    @TempDir Path temporaryDirectory;

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
                "CAT_ICY", "CAT_TAIGA", "CAT_JUNGLE", "CAT_SWAMP", "CAT_EXTREME_HILLS",
                "CAT_PLAINS", "CAT_THE_END", "CAT_BEACH", "CAT_FOREST", "CAT_OCEAN",
                "CAT_RIVER", "CAT_MUSHROOM", "CAT_NETHER", "CAT_MOUNTAIN", "CAT_UNDERGROUND",
                "PPT_NONE", "PPT_RAIN", "PPT_SNOW", "BIOME_PALE_GARDEN",
                "BIOME_NETHER_WASTES", "BIOME_CRIMSON_FOREST", "BIOME_WARPED_FOREST",
                "BIOME_BASALT_DELTAS", "BIOME_SOUL_SAND_VALLEY", "BIOME_SULFUR_CAVES",
                "chunkFadeTimeInv",
                "seaLevel", "cloudTime", "heavyFog", "inSwimmingAnimation",
                "heldBlockLightColor.x", "heldBlockLightColor2.z", "dhProjection.0.0",
                "dhProjectionInverse.0.0", "dhPreviousProjection.0.0",
                "dhPreviousProjectionInverse.0.0", "iris_ModelViewMatrix.0.0",
                "iris_ProjectionMatrix.0.0", "iris_NormalMatrix.0.0",
                "u_ModelViewProjectionMatrix.0.0", "iris_TextureMat.0.0",
                "iris_ModelViewMat.0.0", "iris_ProjMat.0.0", "iris_NormalMat.0.0",
                "iris_FogStart", "iris_FogEnd", "iris_FogDensity", "iris_FogColor.r",
                "iris_ColorModulator.a", "iris_TextureScale", "iris_GlintAlpha",
                "iris_ModelScale.x", "iris_ModelOffset.x", "iris_CameraTranslation.x",
                "u_RegionOffset.z", "iris_ScreenSize.x", "u_TextureScale.y",
                "u_ModelScale.z", "dhNearPlane", "dhFarPlane", "dhRenderDistance",
                "CAT_NONE", "aspectRatio", "viewWidth", "viewHeight", "frameTime"
        };
        for (String name : required) {
            assertTrue(values.containsKey(name), "Missing Iris expression symbol: " + name);
            assertTrue(Double.isFinite(values.get(name)), "Non-finite Iris symbol: " + name);
        }
    }

    @Test
    void evaluatesPhotonStyleUniformExpressionsWithTheIrisBuiltInContext() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("Photon/shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                uniform.vec2.view_res = vec2(viewWidth, viewHeight)
                uniform.float.eye_blocklight = smooth(eyeBrightness.x / 240.0, 1.0, 1.0)
                variable.float.view_up_dir_norm = 1.0 / sqrt(upPosition.x * upPosition.x + upPosition.y * upPosition.y + upPosition.z * upPosition.z)
                uniform.vec3.view_up_dir = vec3(upPosition.x * view_up_dir_norm, upPosition.y * view_up_dir_norm, upPosition.z * view_up_dir_norm)
                uniform.float.lightning_flash_base = if(equals(skyColor.r, skyColor.g, 0.01) && skyColor.r > 0.3, 1.0, 0.0)
                uniform.float.moon_phase_brightness = if(moonPhase == 0, 1.0, moonPhase == 1, 0.875, moonPhase == 2, 0.75, moonPhase == 3, 0.625, moonPhase == 4, 0.5, moonPhase == 5, 0.75, moonPhase == 6, 0.875, 1.0)
                uniform.float.biome_pale_garden = if(in(biome, BIOME_PALE_GARDEN), 1.0, 0.0)
                uniform.float.biome_temperature = smooth(temperature * 2.0 - 1.0, 1000.0, 1000.0)
                """);
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("photon-expressions", "Photon Expressions",
                temporaryDirectory.resolve("Photon"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            try (IrisShaderResources resources = IrisShaderResources.load(model.properties(), sources)) {
                Map<String, Double> base = new HashMap<>();
                NvidiumIrisUniformBridge.putIrisBuiltInDefaults(base);
                base.put("viewWidth", 1920.0);
                base.put("viewHeight", 1080.0);
                base.put("eyeBrightness.x", 120.0);
                base.put("upPosition.x", 0.0);
                base.put("upPosition.y", 100.0);
                base.put("upPosition.z", 0.0);
                base.put("skyColor.r", 0.5);
                base.put("skyColor.g", 0.5);
                base.put("moonPhase", 1.0);
                base.put("biome", 1.0);
                base.put("BIOME_PALE_GARDEN", 1.0);
                base.put("temperature", 0.8);
                Map<String, double[]> evaluated = resources.evaluateUniformValues(base);
                assertArrayEquals(new double[]{1920.0, 1080.0}, evaluated.get("view_res"), 1.0e-9);
                assertEquals(0.5, evaluated.get("eye_blocklight")[0], 1.0e-9);
                assertArrayEquals(new double[]{0.0, 1.0, 0.0}, evaluated.get("view_up_dir"), 1.0e-9);
                assertEquals(1.0, evaluated.get("lightning_flash_base")[0], 1.0e-9);
                assertEquals(0.875, evaluated.get("moon_phase_brightness")[0], 1.0e-9);
                assertEquals(1.0, evaluated.get("biome_pale_garden")[0], 1.0e-9);
                assertEquals(0.6, evaluated.get("biome_temperature")[0], 1.0e-9);
            }
        }
    }

    @Test
    void evaluatesComplementaryCustomUniformExpressionsWithFourArgumentSmooth() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("Complementary/shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                uniform.float.isEyeInCave = if(isEyeInWater == 0, 1.0 - smooth(202, if(eyeAltitude < 5.0, eyeBrightness.y / 240.0, 1.0), 6, 12), 0.0)
                uniform.float.inDry = smooth(101, if(in(biome_precipitation, 0), 1, 0), 20, 10)
                variable.float.difX = cameraPosition.x - previousCameraPosition.x
                variable.float.difY = cameraPosition.y - previousCameraPosition.y
                variable.float.difZ = cameraPosition.z - previousCameraPosition.z
                variable.float.difSum = abs(difX) + abs(difY) + abs(difZ)
                variable.float.moving = if(difSum > 0.0 && difSum < 1.0, 1, 0)
                variable.float.moved = smooth(2, moving, 0, 31536000)
                uniform.float.starter = smooth(3, moved, 20, 20)
                uniform.float.inNetherWastes = smooth(50, if(in(biome, BIOME_NETHER_WASTES), 1, 0), 15, 15)
                uniform.float.maxBlindnessDarkness = max(blindness, darknessFactor)
                """);
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("complementary-expressions", "Complementary Expressions",
                temporaryDirectory.resolve("Complementary"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            try (IrisShaderResources resources = IrisShaderResources.load(model.properties(), sources)) {
                Map<String, Double> base = new HashMap<>();
                NvidiumIrisUniformBridge.putIrisBuiltInDefaults(base);
                base.put("eyeAltitude", 10.0);
                base.put("eyeBrightness.y", 120.0);
                base.put("cameraPosition.x", 100.0);
                base.put("previousCameraPosition.x", 99.5);
                base.put("biome", -1.0);
                Map<String, double[]> evaluated = resources.evaluateUniformValues(base);
                assertEquals(0.0, evaluated.get("isEyeInCave")[0], 1.0e-9);
                assertEquals(1.0, evaluated.get("inDry")[0], 1.0e-4);
                assertEquals(1.0, evaluated.get("starter")[0], 1.0e-8);
                assertEquals(1.0, evaluated.get("inNetherWastes")[0], 1.0e-9);
                assertEquals(0.0, evaluated.get("maxBlindnessDarkness")[0], 1.0e-9);
            }
        }
    }

    @Test
    void evaluatesEveryPinnedPhotonCustomUniformExpressionWithTheIrisSymbolTable() throws IOException {
        String photonDirectory = System.getenv("PHOTON_SOURCE_DIR");
        Assumptions.assumeTrue(photonDirectory != null && !photonDirectory.isBlank(),
                "CI supplies the pinned Photon shader-pack source tree.");
        java.util.Properties loaded = new java.util.Properties();
        try (java.io.InputStream stream = Files.newInputStream(
                Path.of(photonDirectory, "shaders", "shaders.properties"))) {
            loaded.load(stream);
        }
        Map<String, String> properties = new HashMap<>();
        for (String name : loaded.stringPropertyNames()) properties.put(name, loaded.getProperty(name));
        Map<String, String> defines = Map.of(
                "MC_VERSION", "12111",
                "MC_HAND_DEPTH", "0.125",
                "COLORED_LIGHTS", "1",
                "VOXEL_VOLUME_SIZE", "64");
        try (IrisShaderResources resources = IrisShaderResources.loadExpressions(properties, defines)) {
                Map<String, Double> base = new HashMap<>();
                NvidiumIrisUniformBridge.putIrisBuiltInDefaults(base);
                base.put("viewWidth", 1920.0);
                base.put("viewHeight", 1080.0);
                base.put("temperature", 0.8);
                base.put("rainfall", 0.4);
                base.put("eyeBrightness.x", 120.0);
                base.put("eyeBrightness.y", 160.0);
                base.put("sunPosition.x", 0.0);
                base.put("sunPosition.y", 100.0);
                base.put("sunPosition.z", 0.0);
                base.put("moonPosition.x", 0.0);
                base.put("moonPosition.y", 100.0);
                base.put("moonPosition.z", 0.0);
                base.put("skyColor.r", 0.5);
                base.put("skyColor.g", 0.5);
                base.put("skyColor.b", 0.7);
                Map<String, double[]> evaluated = resources.evaluateUniformValues(base);
                assertFalse(evaluated.isEmpty(), "Pinned Photon must expose custom uniforms for expression evaluation.");
        }
    }

    @Test
    void reportsAllMissingExpressionSymbolsTogether() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("Unknowns/shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                uniform.float.first = temperature + missing_temperature_alias
                uniform.float.second = rainfall + missing_weather_alias
                uniform.float.third = eyeBrightness.x + missing_light_alias
                """);
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("unknown-symbols", "Unknown Symbols",
                temporaryDirectory.resolve("Unknowns"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            try (IrisShaderResources resources = IrisShaderResources.load(model.properties(), sources)) {
                Map<String, Double> base = new HashMap<>();
                NvidiumIrisUniformBridge.putIrisBuiltInDefaults(base);
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> resources.evaluateUniformValues(base));
                assertTrue(error.getMessage().contains("missing_temperature_alias"));
                assertTrue(error.getMessage().contains("missing_weather_alias"));
                assertTrue(error.getMessage().contains("missing_light_alias"));
            }
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
